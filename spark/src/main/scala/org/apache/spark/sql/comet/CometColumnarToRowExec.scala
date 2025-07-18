/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.spark.sql.comet

import java.util.UUID
import java.util.concurrent.{Future, TimeoutException, TimeUnit}

import scala.collection.JavaConverters._
import scala.concurrent.Promise
import scala.util.control.NonFatal

import org.apache.spark.{broadcast, SparkException}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Attribute, SortOrder, UnsafeProjection}
import org.apache.spark.sql.catalyst.expressions.codegen._
import org.apache.spark.sql.catalyst.expressions.codegen.Block._
import org.apache.spark.sql.catalyst.plans.physical.Partitioning
import org.apache.spark.sql.comet.util.{Utils => CometUtils}
import org.apache.spark.sql.errors.QueryExecutionErrors
import org.apache.spark.sql.execution.{CodegenSupport, ColumnarToRowTransition, SparkPlan, SQLExecution}
import org.apache.spark.sql.execution.adaptive.BroadcastQueryStageExec
import org.apache.spark.sql.execution.exchange.ReusedExchangeExec
import org.apache.spark.sql.execution.metric.{SQLMetric, SQLMetrics}
import org.apache.spark.sql.execution.vectorized.{ConstantColumnVector, WritableColumnVector}
import org.apache.spark.sql.types._
import org.apache.spark.sql.vectorized.{ColumnarBatch, ColumnVector}
import org.apache.spark.util.{SparkFatalException, Utils}
import org.apache.spark.util.io.ChunkedByteBuffer

import org.apache.comet.vector.CometPlainVector

/**
 * Copied from Spark `ColumnarToRowExec`. Comet needs the fix for SPARK-50235 but cannot wait for
 * the fix to be released in Spark versions. We copy the implementation here to apply the fix.
 */
case class CometColumnarToRowExec(child: SparkPlan)
    extends ColumnarToRowTransition
    with CodegenSupport {
  // supportsColumnar requires to be only called on driver side, see also SPARK-37779.
  assert(Utils.isInRunningSparkTask || child.supportsColumnar)

  override def output: Seq[Attribute] = child.output

  override def outputPartitioning: Partitioning = child.outputPartitioning

  override def outputOrdering: Seq[SortOrder] = child.outputOrdering

  // `ColumnarToRowExec` processes the input RDD directly, which is kind of a leaf node in the
  // codegen stage and needs to do the limit check.
  protected override def canCheckLimitNotReached: Boolean = true

  override lazy val metrics: Map[String, SQLMetric] = Map(
    "numOutputRows" -> SQLMetrics.createMetric(sparkContext, "number of output rows"),
    "numInputBatches" -> SQLMetrics.createMetric(sparkContext, "number of input batches"))

  override def doExecute(): RDD[InternalRow] = {
    val numOutputRows = longMetric("numOutputRows")
    val numInputBatches = longMetric("numInputBatches")
    // This avoids calling `output` in the RDD closure, so that we don't need to include the entire
    // plan (this) in the closure.
    val localOutput = this.output
    child.executeColumnar().mapPartitionsInternal { batches =>
      val toUnsafe = UnsafeProjection.create(localOutput, localOutput)
      batches.flatMap { batch =>
        numInputBatches += 1
        numOutputRows += batch.numRows()
        batch.rowIterator().asScala.map(toUnsafe)
      }
    }
  }

  @transient
  private lazy val promise = Promise[broadcast.Broadcast[Any]]()

  @transient
  private val timeout: Long = conf.broadcastTimeout

  private val runId: UUID = UUID.randomUUID

  private lazy val cometBroadcastExchange = findCometBroadcastExchange(child)

  @transient
  lazy val relationFuture: Future[broadcast.Broadcast[Any]] = {
    SQLExecution.withThreadLocalCaptured[broadcast.Broadcast[Any]](
      session,
      CometBroadcastExchangeExec.executionContext) {
      try {
        // Setup a job group here so later it may get cancelled by groupId if necessary.
        sparkContext.setJobGroup(
          runId.toString,
          s"CometColumnarToRow broadcast exchange (runId $runId)",
          interruptOnCancel = true)

        val numOutputRows = longMetric("numOutputRows")
        val numInputBatches = longMetric("numInputBatches")
        val localOutput = this.output
        val broadcastColumnar = child.executeBroadcast()
        val serializedBatches = broadcastColumnar.value.asInstanceOf[Array[ChunkedByteBuffer]]
        val toUnsafe = UnsafeProjection.create(localOutput, localOutput)
        val rows = serializedBatches.iterator
          .flatMap(CometUtils.decodeBatches(_, this.getClass.getSimpleName))
          .flatMap { batch =>
            numInputBatches += 1
            numOutputRows += batch.numRows()
            batch.rowIterator().asScala.map(toUnsafe)
          }

        val mode = cometBroadcastExchange.get.mode
        val relation = mode.transform(rows, Some(numOutputRows.value))
        val broadcasted = sparkContext.broadcastInternal(relation, serializedOnly = true)
        val executionId = sparkContext.getLocalProperty(SQLExecution.EXECUTION_ID_KEY)
        SQLMetrics.postDriverMetricUpdates(sparkContext, executionId, metrics.values.toSeq)
        promise.trySuccess(broadcasted)
        broadcasted
      } catch {
        // SPARK-24294: To bypass scala bug: https://github.com/scala/bug/issues/9554, we throw
        // SparkFatalException, which is a subclass of Exception. ThreadUtils.awaitResult
        // will catch this exception and re-throw the wrapped fatal throwable.
        case oe: OutOfMemoryError =>
          val ex = new SparkFatalException(oe)
          promise.tryFailure(ex)
          throw ex
        case e if !NonFatal(e) =>
          val ex = new SparkFatalException(e)
          promise.tryFailure(ex)
          throw ex
        case e: Throwable =>
          promise.tryFailure(e)
          throw e
      }
    }
  }

  override def doExecuteBroadcast[T](): broadcast.Broadcast[T] = {
    if (cometBroadcastExchange.isEmpty) {
      throw new SparkException(
        "ColumnarToRowExec only supports doExecuteBroadcast when child contains a " +
          "CometBroadcastExchange, but got " + child)
    }

    try {
      relationFuture.get(timeout, TimeUnit.SECONDS).asInstanceOf[broadcast.Broadcast[T]]
    } catch {
      case ex: TimeoutException =>
        logError(s"Could not execute broadcast in $timeout secs.", ex)
        if (!relationFuture.isDone) {
          sparkContext.cancelJobGroup(runId.toString)
          relationFuture.cancel(true)
        }
        throw QueryExecutionErrors.executeBroadcastTimeoutError(timeout, Some(ex))
    }
  }

  private def findCometBroadcastExchange(op: SparkPlan): Option[CometBroadcastExchangeExec] = {
    op match {
      case b: CometBroadcastExchangeExec => Some(b)
      case b: BroadcastQueryStageExec => findCometBroadcastExchange(b.plan)
      case b: ReusedExchangeExec => findCometBroadcastExchange(b.child)
      case _ => op.children.collectFirst(Function.unlift(findCometBroadcastExchange))
    }
  }

  /**
   * Generate [[ColumnVector]] expressions for our parent to consume as rows. This is called once
   * per [[ColumnVector]] in the batch.
   */
  private def genCodeColumnVector(
      ctx: CodegenContext,
      columnVar: String,
      ordinal: String,
      dataType: DataType,
      nullable: Boolean): ExprCode = {
    val javaType = CodeGenerator.javaType(dataType)
    val value = CodeGenerator.getValueFromVector(columnVar, dataType, ordinal)
    val isNullVar = if (nullable) {
      JavaCode.isNullVariable(ctx.freshName("isNull"))
    } else {
      FalseLiteral
    }
    val valueVar = ctx.freshName("value")
    val str = s"columnVector[$columnVar, $ordinal, ${dataType.simpleString}]"
    val code = code"${ctx.registerComment(str)}" + (if (nullable) {
                                                      code"""
        boolean $isNullVar = $columnVar.isNullAt($ordinal);
        $javaType $valueVar = $isNullVar ? ${CodeGenerator.defaultValue(dataType)} : ($value);
      """
                                                    } else {
                                                      code"$javaType $valueVar = $value;"
                                                    })
    ExprCode(code, isNullVar, JavaCode.variable(valueVar, dataType))
  }

  /**
   * Produce code to process the input iterator as [[ColumnarBatch]]es. This produces an
   * [[org.apache.spark.sql.catalyst.expressions.UnsafeRow]] for each row in each batch.
   */
  override protected def doProduce(ctx: CodegenContext): String = {
    // PhysicalRDD always just has one input
    val input = ctx.addMutableState("scala.collection.Iterator", "input", v => s"$v = inputs[0];")

    // metrics
    val numOutputRows = metricTerm(ctx, "numOutputRows")
    val numInputBatches = metricTerm(ctx, "numInputBatches")

    val columnarBatchClz = classOf[ColumnarBatch].getName
    val batch = ctx.addMutableState(columnarBatchClz, "batch")

    val idx = ctx.addMutableState(CodeGenerator.JAVA_INT, "batchIdx") // init as batchIdx = 0
    val columnVectorClzs =
      child.vectorTypes.getOrElse(Seq.fill(output.indices.size)(classOf[ColumnVector].getName))
    val (colVars, columnAssigns) = columnVectorClzs.zipWithIndex.map {
      case (columnVectorClz, i) =>
        val name = ctx.addMutableState(columnVectorClz, s"colInstance$i")
        (name, s"$name = ($columnVectorClz) $batch.column($i);")
    }.unzip

    val nextBatch = ctx.freshName("nextBatch")
    val nextBatchFuncName = ctx.addNewFunction(
      nextBatch,
      s"""
         |private void $nextBatch() throws java.io.IOException {
         |  if ($input.hasNext()) {
         |    $batch = ($columnarBatchClz)$input.next();
         |    $numInputBatches.add(1);
         |    $numOutputRows.add($batch.numRows());
         |    $idx = 0;
         |    ${columnAssigns.mkString("", "\n", "\n")}
         |  }
         |}""".stripMargin)

    ctx.currentVars = null
    val rowidx = ctx.freshName("rowIdx")
    val columnsBatchInput = (output zip colVars).map { case (attr, colVar) =>
      genCodeColumnVector(ctx, colVar, rowidx, attr.dataType, attr.nullable)
    }
    val localIdx = ctx.freshName("localIdx")
    val localEnd = ctx.freshName("localEnd")
    val numRows = ctx.freshName("numRows")
    val shouldStop = if (parent.needStopCheck) {
      s"if (shouldStop()) { $idx = $rowidx + 1; return; }"
    } else {
      "// shouldStop check is eliminated"
    }

    val writableColumnVectorClz = classOf[WritableColumnVector].getName
    val constantColumnVectorClz = classOf[ConstantColumnVector].getName
    val cometPlainColumnVectorClz = classOf[CometPlainVector].getName

    // scalastyle:off line.size.limit
    s"""
       |if ($batch == null) {
       |  $nextBatchFuncName();
       |}
       |while ($limitNotReachedCond $batch != null) {
       |  int $numRows = $batch.numRows();
       |  int $localEnd = $numRows - $idx;
       |  for (int $localIdx = 0; $localIdx < $localEnd; $localIdx++) {
       |    int $rowidx = $idx + $localIdx;
       |    ${consume(ctx, columnsBatchInput).trim}
       |    $shouldStop
       |  }
       |  $idx = $numRows;
       |
       |  // Comet fix for SPARK-50235
       |  for (int i = 0; i < ${colVars.length}; i++) {
       |    if (!($batch.column(i) instanceof $writableColumnVectorClz || $batch.column(i) instanceof $constantColumnVectorClz || $batch.column(i) instanceof $cometPlainColumnVectorClz)) {
       |      $batch.column(i).close();
       |    } else if ($batch.column(i) instanceof $cometPlainColumnVectorClz) {
       |      $cometPlainColumnVectorClz cometPlainColumnVector = ($cometPlainColumnVectorClz) $batch.column(i);
       |      if (!cometPlainColumnVector.isReused()) {
       |        cometPlainColumnVector.close();
       |      }
       |    }
       |  }
       |
       |  $batch = null;
       |  $nextBatchFuncName();
       |}
       |// Comet fix for SPARK-50235: clean up resources
       |if ($batch != null) {
       |  $batch.close();
       |}
     """.stripMargin
    // scalastyle:on line.size.limit
  }

  override def inputRDDs(): Seq[RDD[InternalRow]] = {
    Seq(child.executeColumnar().asInstanceOf[RDD[InternalRow]]) // Hack because of type erasure
  }

  override protected def withNewChildInternal(newChild: SparkPlan): CometColumnarToRowExec =
    copy(child = newChild)
}
