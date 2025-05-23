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

package org.apache.comet

import java.nio.ByteOrder

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer

import org.apache.spark.SparkConf
import org.apache.spark.internal.Logging
import org.apache.spark.network.util.ByteUnit
import org.apache.spark.sql.{SparkSession, SparkSessionExtensions}
import org.apache.spark.sql.catalyst.expressions.{Attribute, Divide, DoubleLiteral, EqualNullSafe, EqualTo, Expression, FloatLiteral, GreaterThan, GreaterThanOrEqual, KnownFloatingPointNormalized, LessThan, LessThanOrEqual, NamedExpression, PlanExpression, Remainder}
import org.apache.spark.sql.catalyst.expressions.aggregate.{Final, Partial}
import org.apache.spark.sql.catalyst.optimizer.NormalizeNaNAndZero
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.catalyst.trees.TreeNode
import org.apache.spark.sql.catalyst.util.MetadataColumnHelper
import org.apache.spark.sql.comet._
import org.apache.spark.sql.comet.execution.shuffle.{CometColumnarShuffle, CometNativeShuffle, CometShuffleExchangeExec, CometShuffleManager}
import org.apache.spark.sql.comet.util.Utils
import org.apache.spark.sql.execution._
import org.apache.spark.sql.execution.adaptive.{AQEShuffleReadExec, BroadcastQueryStageExec, QueryStageExec, ShuffleQueryStageExec}
import org.apache.spark.sql.execution.aggregate.{BaseAggregateExec, HashAggregateExec, ObjectHashAggregateExec}
import org.apache.spark.sql.execution.datasources._
import org.apache.spark.sql.execution.datasources.csv.CSVFileFormat
import org.apache.spark.sql.execution.datasources.json.JsonFileFormat
import org.apache.spark.sql.execution.datasources.parquet.ParquetFileFormat
import org.apache.spark.sql.execution.datasources.v2.BatchScanExec
import org.apache.spark.sql.execution.datasources.v2.csv.CSVScan
import org.apache.spark.sql.execution.datasources.v2.json.JsonScan
import org.apache.spark.sql.execution.datasources.v2.parquet.ParquetScan
import org.apache.spark.sql.execution.exchange.{BroadcastExchangeExec, ReusedExchangeExec, ShuffleExchangeExec}
import org.apache.spark.sql.execution.joins.{BroadcastHashJoinExec, ShuffledHashJoinExec, SortMergeJoinExec}
import org.apache.spark.sql.execution.window.WindowExec
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{DoubleType, FloatType}

import org.apache.comet.CometConf._
import org.apache.comet.CometExplainInfo.getActualPlan
import org.apache.comet.CometSparkSessionExtensions.{createMessage, getCometBroadcastNotEnabledReason, getCometShuffleNotEnabledReason, isANSIEnabled, isCometBroadCastForceEnabled, isCometExecEnabled, isCometJVMShuffleMode, isCometLoaded, isCometNativeShuffleMode, isCometScan, isCometScanEnabled, isCometShuffleEnabled, isSpark40Plus, shouldApplySparkToColumnar, withInfo}
import org.apache.comet.parquet.{CometParquetScan, SupportsComet}
import org.apache.comet.rules.RewriteJoin
import org.apache.comet.serde.OperatorOuterClass.Operator
import org.apache.comet.serde.QueryPlanSerde
import org.apache.comet.shims.ShimCometSparkSessionExtensions

/**
 * The entry point of Comet extension to Spark. This class is responsible for injecting Comet
 * rules and extensions into Spark.
 *
 * CometScanRule: A rule to transform a Spark scan plan into a Comet scan plan. CometExecRule: A
 * rule to transform a Spark execution plan into a Comet execution plan.
 */
class CometSparkSessionExtensions
    extends (SparkSessionExtensions => Unit)
    with Logging
    with ShimCometSparkSessionExtensions {
  override def apply(extensions: SparkSessionExtensions): Unit = {
    extensions.injectColumnar { session => CometScanColumnar(session) }
    extensions.injectColumnar { session => CometExecColumnar(session) }
    extensions.injectQueryStagePrepRule { session => CometScanRule(session) }
    extensions.injectQueryStagePrepRule { session => CometExecRule(session) }
  }

  case class CometScanColumnar(session: SparkSession) extends ColumnarRule {
    override def preColumnarTransitions: Rule[SparkPlan] = CometScanRule(session)
  }

  case class CometExecColumnar(session: SparkSession) extends ColumnarRule {
    override def preColumnarTransitions: Rule[SparkPlan] = CometExecRule(session)

    override def postColumnarTransitions: Rule[SparkPlan] =
      EliminateRedundantTransitions(session)
  }

  case class CometScanRule(session: SparkSession) extends Rule[SparkPlan] {
    override def apply(plan: SparkPlan): SparkPlan = {
      if (!isCometLoaded(conf) || !isCometScanEnabled(conf)) {
        if (!isCometLoaded(conf)) {
          withInfo(plan, "Comet is not enabled")
        } else if (!isCometScanEnabled(conf)) {
          withInfo(plan, "Comet Scan is not enabled")
        }
        plan
      } else {

        def hasMetadataCol(plan: SparkPlan): Boolean = {
          plan.expressions.exists(_.exists {
            case a: Attribute =>
              a.isMetadataCol
            case _ => false
          })
        }

        plan.transform {
          case scan if hasMetadataCol(scan) =>
            withInfo(scan, "Metadata column is not supported")

          // data source V1
          case scanExec: FileSourceScanExec =>
            transformV1Scan(scanExec)

          // data source V2
          case scanExec: BatchScanExec =>
            transformV2Scan(scanExec)
        }
      }
    }

    private def isDynamicPruningFilter(e: Expression): Boolean =
      e.exists(_.isInstanceOf[PlanExpression[_]])

    private def transformV1Scan(scanExec: FileSourceScanExec): SparkPlan = {

      if (COMET_DPP_FALLBACK_ENABLED.get() &&
        scanExec.partitionFilters.exists(isDynamicPruningFilter)) {
        return withInfo(scanExec, "Dynamic Partition Pruning is not supported")
      }

      scanExec.relation match {
        case r: HadoopFsRelation =>
          val fallbackReasons = new ListBuffer[String]()
          if (!CometScanExec.isFileFormatSupported(r.fileFormat)) {
            fallbackReasons += s"Unsupported file format ${r.fileFormat}"
          }

          val scanImpl = COMET_NATIVE_SCAN_IMPL.get()
          if (scanImpl == CometConf.SCAN_NATIVE_DATAFUSION && !COMET_EXEC_ENABLED.get()) {
            fallbackReasons +=
              s"Full native scan disabled because ${COMET_EXEC_ENABLED.key} disabled"
          }

          val (schemaSupported, partitionSchemaSupported) = scanImpl match {
            case CometConf.SCAN_NATIVE_DATAFUSION =>
              (
                CometNativeScanExec.isSchemaSupported(scanExec.requiredSchema),
                CometNativeScanExec.isSchemaSupported(r.partitionSchema))
            case CometConf.SCAN_NATIVE_COMET | SCAN_NATIVE_ICEBERG_COMPAT =>
              (
                CometScanExec.isSchemaSupported(scanExec.requiredSchema),
                CometScanExec.isSchemaSupported(r.partitionSchema))
          }

          if (!schemaSupported) {
            fallbackReasons += s"Unsupported schema ${scanExec.requiredSchema} for $scanImpl"
          }
          if (!partitionSchemaSupported) {
            fallbackReasons += s"Unsupported partitioning schema ${r.partitionSchema} for $scanImpl"
          }

          if (fallbackReasons.isEmpty) {
            CometScanExec(scanExec, session)
          } else {
            withInfo(scanExec, fallbackReasons.mkString(", "))
          }

        case _ =>
          withInfo(scanExec, s"Unsupported relation ${scanExec.relation}")
      }
    }
  }

  private def transformV2Scan(scanExec: BatchScanExec): SparkPlan = {

    scanExec.scan match {
      case scan: ParquetScan =>
        val fallbackReasons = new ListBuffer[String]()
        if (!CometBatchScanExec.isSchemaSupported(scan.readDataSchema)) {
          fallbackReasons += s"Schema ${scan.readDataSchema} is not supported"
        }

        if (!CometBatchScanExec.isSchemaSupported(scan.readPartitionSchema)) {
          fallbackReasons += s"Partition schema ${scan.readPartitionSchema} is not supported"
        }

        if (scan.pushedAggregate.nonEmpty) {
          fallbackReasons += "Comet does not support pushed aggregate"
        }

        if (fallbackReasons.isEmpty) {
          val cometScan = CometParquetScan(scanExec.scan.asInstanceOf[ParquetScan])
          logInfo("Comet extension enabled for Scan")
          CometBatchScanExec(
            scanExec.copy(scan = cometScan),
            runtimeFilters = scanExec.runtimeFilters)
        } else {
          withInfo(scanExec, fallbackReasons.mkString(", "))
        }

      // Iceberg scan
      case s: SupportsComet =>
        val fallbackReasons = new ListBuffer[String]()

        if (!s.isCometEnabled) {
          fallbackReasons += "Comet extension is not enabled for " +
            s"${scanExec.scan.getClass.getSimpleName}: not enabled on data source side"
        }

        if (!CometBatchScanExec.isSchemaSupported(scanExec.scan.readSchema())) {
          fallbackReasons += "Comet extension is not enabled for " +
            s"${scanExec.scan.getClass.getSimpleName}: Schema not supported"
        }

        if (fallbackReasons.isEmpty) {
          // When reading from Iceberg, we automatically enable type promotion
          SQLConf.get.setConfString(COMET_SCHEMA_EVOLUTION_ENABLED.key, "true")
          CometBatchScanExec(
            scanExec.clone().asInstanceOf[BatchScanExec],
            runtimeFilters = scanExec.runtimeFilters)
        } else {
          withInfo(scanExec, fallbackReasons.mkString(", "))
        }

      case _ =>
        withInfo(scanExec, "Comet Scan only supports Parquet and Iceberg Parquet file formats")
    }
  }

  case class CometExecRule(session: SparkSession) extends Rule[SparkPlan] {
    private def applyCometShuffle(plan: SparkPlan): SparkPlan = {
      plan.transformUp {
        case s: ShuffleExchangeExec
            if isCometPlan(s.child) && isCometNativeShuffleMode(conf) &&
              QueryPlanSerde.nativeShuffleSupported(s)._1 =>
          logInfo("Comet extension enabled for Native Shuffle")

          // Switch to use Decimal128 regardless of precision, since Arrow native execution
          // doesn't support Decimal32 and Decimal64 yet.
          conf.setConfString(CometConf.COMET_USE_DECIMAL_128.key, "true")
          CometShuffleExchangeExec(s, shuffleType = CometNativeShuffle)

        // Columnar shuffle for regular Spark operators (not Comet) and Comet operators
        // (if configured)
        case s: ShuffleExchangeExec
            if (!s.child.supportsColumnar || isCometPlan(s.child)) && isCometJVMShuffleMode(
              conf) &&
              QueryPlanSerde.columnarShuffleSupported(s)._1 &&
              !isShuffleOperator(s.child) =>
          logInfo("Comet extension enabled for JVM Columnar Shuffle")
          CometShuffleExchangeExec(s, shuffleType = CometColumnarShuffle)
      }
    }

    private def isCometPlan(op: SparkPlan): Boolean = op.isInstanceOf[CometPlan]

    private def isCometNative(op: SparkPlan): Boolean = op.isInstanceOf[CometNativeExec]

    private def explainChildNotNative(op: SparkPlan): String = {
      var nonNatives: Seq[String] = Seq()
      val actualOp = getActualPlan(op)
      actualOp.children.foreach {
        case p: SparkPlan =>
          if (!isCometNative(p)) {
            nonNatives = nonNatives :+ getActualPlan(p).nodeName
          }
        case _ =>
      }
      nonNatives.mkString("(", ", ", ")")
    }

    // spotless:off
    /**
     * Tries to transform a Spark physical plan into a Comet plan.
     *
     * This rule traverses bottom-up from the original Spark plan and for each plan node, there
     * are a few cases to consider:
     *
     * 1. The child(ren) of the current node `p` cannot be converted to native
     *   In this case, we'll simply return the original Spark plan, since Comet native
     *   execution cannot start from an arbitrary Spark operator (unless it is special node
     *   such as scan or sink such as shuffle exchange, union etc., which are wrapped by
     *   `CometScanWrapper` and `CometSinkPlaceHolder` respectively).
     *
     * 2. The child(ren) of the current node `p` can be converted to native
     *   There are two sub-cases for this scenario: 1) This node `p` can also be converted to
     *   native. In this case, we'll create a new native Comet operator for `p` and connect it with
     *   its previously converted child(ren); 2) This node `p` cannot be converted to native. In
     *   this case, similar to 1) above, we simply return `p` as it is. Its child(ren) would still
     *   be native Comet operators.
     *
     * After this rule finishes, we'll do another pass on the final plan to convert all adjacent
     * Comet native operators into a single native execution block. Please see where
     * `convertBlock` is called below.
     *
     * Here are a few examples:
     *
     *     Scan                       ======>             CometScan
     *      |                                                |
     *     Filter                                         CometFilter
     *      |                                                |
     *     HashAggregate                                  CometHashAggregate
     *      |                                                |
     *     Exchange                                       CometExchange
     *      |                                                |
     *     HashAggregate                                  CometHashAggregate
     *      |                                                |
     *     UnsupportedOperator                            UnsupportedOperator
     *
     * Native execution doesn't necessarily have to start from `CometScan`:
     *
     *     Scan                       =======>            CometScan
     *      |                                                |
     *     UnsupportedOperator                            UnsupportedOperator
     *      |                                                |
     *     HashAggregate                                  HashAggregate
     *      |                                                |
     *     Exchange                                       CometExchange
     *      |                                                |
     *     HashAggregate                                  CometHashAggregate
     *      |                                                |
     *     UnsupportedOperator                            UnsupportedOperator
     *
     * A sink can also be Comet operators other than `CometExchange`, for instance `CometUnion`:
     *
     *     Scan   Scan                =======>          CometScan CometScan
     *      |      |                                       |         |
     *     Filter Filter                                CometFilter CometFilter
     *      |      |                                       |         |
     *        Union                                         CometUnion
     *          |                                               |
     *        Project                                       CometProject
     */
    // spotless:on
    private def transform(plan: SparkPlan): SparkPlan = {
      def operator2Proto(op: SparkPlan): Option[Operator] = {
        if (op.children.forall(_.isInstanceOf[CometNativeExec])) {
          QueryPlanSerde.operator2Proto(
            op,
            op.children.map(_.asInstanceOf[CometNativeExec].nativeOp): _*)
        } else {
          withInfo(
            op,
            s"${op.nodeName} is not native because the following children are not native " +
              s"${explainChildNotNative(op)}")
          None
        }
      }

      /**
       * Convert operator to proto and then apply a transformation to wrap the proto in a new
       * plan.
       */
      def newPlanWithProto(op: SparkPlan, fun: Operator => SparkPlan): SparkPlan = {
        operator2Proto(op).map(fun).getOrElse(op)
      }

      plan.transformUp {
        // Fully native scan for V1
        case scan: CometScanExec
            if COMET_NATIVE_SCAN_IMPL.get() == CometConf.SCAN_NATIVE_DATAFUSION =>
          val nativeOp = QueryPlanSerde.operator2Proto(scan).get
          CometNativeScanExec(nativeOp, scan.wrapped, scan.session)

        // Comet JVM + native scan for V1 and V2
        case op if isCometScan(op) =>
          val nativeOp = QueryPlanSerde.operator2Proto(op)
          CometScanWrapper(nativeOp.get, op)

        case op if shouldApplySparkToColumnar(conf, op) =>
          val cometOp = CometSparkToColumnarExec(op)
          val nativeOp = QueryPlanSerde.operator2Proto(cometOp)
          CometScanWrapper(nativeOp.get, cometOp)

        case op: ProjectExec =>
          newPlanWithProto(
            op,
            CometProjectExec(_, op, op.output, op.projectList, op.child, SerializedPlan(None)))

        case op: FilterExec =>
          newPlanWithProto(
            op,
            CometFilterExec(_, op, op.output, op.condition, op.child, SerializedPlan(None)))

        case op: SortExec =>
          newPlanWithProto(
            op,
            CometSortExec(
              _,
              op,
              op.output,
              op.outputOrdering,
              op.sortOrder,
              op.child,
              SerializedPlan(None)))

        case op: LocalLimitExec =>
          newPlanWithProto(
            op,
            CometLocalLimitExec(_, op, op.limit, op.child, SerializedPlan(None)))

        case op: GlobalLimitExec if op.offset == 0 =>
          newPlanWithProto(
            op,
            CometGlobalLimitExec(_, op, op.limit, op.child, SerializedPlan(None)))

        case op: CollectLimitExec
            if isCometNative(op.child) && CometConf.COMET_EXEC_COLLECT_LIMIT_ENABLED.get(conf)
              && isCometShuffleEnabled(conf)
              && op.offset == 0 =>
          QueryPlanSerde
            .operator2Proto(op)
            .map { nativeOp =>
              val cometOp =
                CometCollectLimitExec(op, op.limit, op.offset, op.child)
              CometSinkPlaceHolder(nativeOp, op, cometOp)
            }
            .getOrElse(op)

        case op: ExpandExec =>
          newPlanWithProto(
            op,
            CometExpandExec(_, op, op.output, op.projections, op.child, SerializedPlan(None)))

        case op: BaseAggregateExec
            if op.isInstanceOf[HashAggregateExec] ||
              op.isInstanceOf[ObjectHashAggregateExec] &&
              // When Comet shuffle is disabled, we don't want to transform the HashAggregate
              // to CometHashAggregate. Otherwise, we probably get partial Comet aggregation
              // and final Spark aggregation.
              isCometShuffleEnabled(conf) =>
          val groupingExprs = op.groupingExpressions
          val aggExprs = op.aggregateExpressions
          val resultExpressions = op.resultExpressions
          val child = op.child
          val modes = aggExprs.map(_.mode).distinct

          if (modes.nonEmpty && modes.size != 1) {
            // This shouldn't happen as all aggregation expressions should share the same mode.
            // Fallback to Spark nevertheless here.
            op
          } else {
            // For a final mode HashAggregate, we only need to transform the HashAggregate
            // if there is Comet partial aggregation.
            val sparkFinalMode = {
              modes.nonEmpty && modes.head == Final && findCometPartialAgg(child).isEmpty
            }

            if (sparkFinalMode) {
              op
            } else {
              newPlanWithProto(
                op,
                nativeOp => {
                  val modes = aggExprs.map(_.mode).distinct
                  // The aggExprs could be empty. For example, if the aggregate functions only have
                  // distinct aggregate functions or only have group by, the aggExprs is empty and
                  // modes is empty too. If aggExprs is not empty, we need to verify all the
                  // aggregates have the same mode.
                  assert(modes.length == 1 || modes.isEmpty)
                  CometHashAggregateExec(
                    nativeOp,
                    op,
                    op.output,
                    groupingExprs,
                    aggExprs,
                    resultExpressions,
                    child.output,
                    modes.headOption,
                    child,
                    SerializedPlan(None))
                })
            }
          }

        case op: ShuffledHashJoinExec
            if CometConf.COMET_EXEC_HASH_JOIN_ENABLED.get(conf) &&
              op.children.forall(isCometNative) =>
          newPlanWithProto(
            op,
            CometHashJoinExec(
              _,
              op,
              op.output,
              op.outputOrdering,
              op.leftKeys,
              op.rightKeys,
              op.joinType,
              op.condition,
              op.buildSide,
              op.left,
              op.right,
              SerializedPlan(None)))

        case op: ShuffledHashJoinExec if !CometConf.COMET_EXEC_HASH_JOIN_ENABLED.get(conf) =>
          withInfo(op, "ShuffleHashJoin is not enabled")

        case op: ShuffledHashJoinExec if !op.children.forall(isCometNative) =>
          withInfo(
            op,
            "ShuffleHashJoin disabled because the following children are not native " +
              s"${explainChildNotNative(op)}")

        case op: BroadcastHashJoinExec
            if CometConf.COMET_EXEC_BROADCAST_HASH_JOIN_ENABLED.get(conf) &&
              op.children.forall(isCometNative) =>
          newPlanWithProto(
            op,
            CometBroadcastHashJoinExec(
              _,
              op,
              op.output,
              op.outputOrdering,
              op.leftKeys,
              op.rightKeys,
              op.joinType,
              op.condition,
              op.buildSide,
              op.left,
              op.right,
              SerializedPlan(None)))

        case op: SortMergeJoinExec
            if CometConf.COMET_EXEC_SORT_MERGE_JOIN_ENABLED.get(conf) &&
              op.children.forall(isCometNative) =>
          newPlanWithProto(
            op,
            CometSortMergeJoinExec(
              _,
              op,
              op.output,
              op.outputOrdering,
              op.leftKeys,
              op.rightKeys,
              op.joinType,
              op.condition,
              op.left,
              op.right,
              SerializedPlan(None)))

        case op: SortMergeJoinExec
            if CometConf.COMET_EXEC_SORT_MERGE_JOIN_ENABLED.get(conf) &&
              !op.children.forall(isCometNative(_)) =>
          withInfo(
            op,
            "SortMergeJoin is not enabled because the following children are not native " +
              s"${explainChildNotNative(op)}")

        case op: SortMergeJoinExec if !CometConf.COMET_EXEC_SORT_MERGE_JOIN_ENABLED.get(conf) =>
          withInfo(op, "SortMergeJoin is not enabled")

        case op: SortMergeJoinExec if !op.children.forall(isCometNative(_)) =>
          withInfo(
            op,
            "SortMergeJoin is not enabled because the following children are not native " +
              s"${explainChildNotNative(op)}")

        case c @ CoalesceExec(numPartitions, child)
            if CometConf.COMET_EXEC_COALESCE_ENABLED.get(conf)
              && isCometNative(child) =>
          QueryPlanSerde
            .operator2Proto(c)
            .map { nativeOp =>
              val cometOp = CometCoalesceExec(c, c.output, numPartitions, child)
              CometSinkPlaceHolder(nativeOp, c, cometOp)
            }
            .getOrElse(c)

        case c @ CoalesceExec(_, _) if !CometConf.COMET_EXEC_COALESCE_ENABLED.get(conf) =>
          withInfo(c, "Coalesce is not enabled")

        case op: CoalesceExec if !op.children.forall(isCometNative(_)) =>
          withInfo(
            op,
            "Coalesce is not enabled because the following children are not native " +
              s"${explainChildNotNative(op)}")

        case s: TakeOrderedAndProjectExec
            if isCometNative(s.child) && CometConf.COMET_EXEC_TAKE_ORDERED_AND_PROJECT_ENABLED
              .get(conf)
              && isCometShuffleEnabled(conf) &&
              CometTakeOrderedAndProjectExec.isSupported(s) =>
          QueryPlanSerde
            .operator2Proto(s)
            .map { nativeOp =>
              val cometOp =
                CometTakeOrderedAndProjectExec(
                  s,
                  s.output,
                  s.limit,
                  s.sortOrder,
                  s.projectList,
                  s.child)
              CometSinkPlaceHolder(nativeOp, s, cometOp)
            }
            .getOrElse(s)

        case s: TakeOrderedAndProjectExec =>
          val info1 = createMessage(
            !CometConf.COMET_EXEC_TAKE_ORDERED_AND_PROJECT_ENABLED.get(conf),
            "TakeOrderedAndProject is not enabled")
          val info2 = createMessage(
            !isCometShuffleEnabled(conf),
            "TakeOrderedAndProject requires shuffle to be enabled")
          withInfo(s, Seq(info1, info2).flatten.mkString(","))

        case w: WindowExec =>
          newPlanWithProto(
            w,
            CometWindowExec(
              _,
              w,
              w.output,
              w.windowExpression,
              w.partitionSpec,
              w.orderSpec,
              w.child,
              SerializedPlan(None)))

        case u: UnionExec
            if CometConf.COMET_EXEC_UNION_ENABLED.get(conf) &&
              u.children.forall(isCometNative) =>
          newPlanWithProto(
            u, {
              val cometOp = CometUnionExec(u, u.output, u.children)
              CometSinkPlaceHolder(_, u, cometOp)
            })

        case u: UnionExec if !CometConf.COMET_EXEC_UNION_ENABLED.get(conf) =>
          withInfo(u, "Union is not enabled")

        case op: UnionExec if !op.children.forall(isCometNative(_)) =>
          withInfo(
            op,
            "Union is not enabled because the following children are not native " +
              s"${explainChildNotNative(op)}")

        // For AQE broadcast stage on a Comet broadcast exchange
        case s @ BroadcastQueryStageExec(_, _: CometBroadcastExchangeExec, _) =>
          newPlanWithProto(s, CometSinkPlaceHolder(_, s, s))

        case s @ BroadcastQueryStageExec(
              _,
              ReusedExchangeExec(_, _: CometBroadcastExchangeExec),
              _) =>
          newPlanWithProto(s, CometSinkPlaceHolder(_, s, s))

        // `CometBroadcastExchangeExec`'s broadcast output is not compatible with Spark's broadcast
        // exchange. It is only used for Comet native execution. We only transform Spark broadcast
        // exchange to Comet broadcast exchange if its downstream is a Comet native plan or if the
        // broadcast exchange is forced to be enabled by Comet config.
        case plan if plan.children.exists(_.isInstanceOf[BroadcastExchangeExec]) =>
          val newChildren = plan.children.map {
            case b: BroadcastExchangeExec
                if isCometNative(b.child) &&
                  CometConf.COMET_EXEC_BROADCAST_EXCHANGE_ENABLED.get(conf) =>
              QueryPlanSerde.operator2Proto(b) match {
                case Some(nativeOp) =>
                  val cometOp = CometBroadcastExchangeExec(b, b.output, b.mode, b.child)
                  CometSinkPlaceHolder(nativeOp, b, cometOp)
                case None => b
              }
            case other => other
          }
          if (!newChildren.exists(_.isInstanceOf[BroadcastExchangeExec])) {
            val newPlan = apply(plan.withNewChildren(newChildren))
            if (isCometNative(newPlan) || isCometBroadCastForceEnabled(conf)) {
              newPlan
            } else {
              if (isCometNative(newPlan)) {
                val reason =
                  getCometBroadcastNotEnabledReason(conf).getOrElse("no reason available")
                withInfo(plan, s"Broadcast is not enabled: $reason")
              }
              plan
            }
          } else {
            withInfo(
              plan,
              s"${plan.nodeName} is not native because the following children are not native " +
                s"${explainChildNotNative(plan)}")
          }

        // this case should be checked only after the previous case checking for a
        // child BroadcastExchange has been applied, otherwise that transform
        // never gets applied
        case op: BroadcastHashJoinExec if !op.children.forall(isCometNative(_)) =>
          withInfo(
            op,
            "BroadcastHashJoin is not enabled because the following children are not native " +
              s"${explainChildNotNative(op)}")

        case op: BroadcastHashJoinExec
            if !CometConf.COMET_EXEC_BROADCAST_HASH_JOIN_ENABLED.get(conf) =>
          withInfo(op, "BroadcastHashJoin is not enabled")

        // For AQE shuffle stage on a Comet shuffle exchange
        case s @ ShuffleQueryStageExec(_, _: CometShuffleExchangeExec, _) =>
          newPlanWithProto(s, CometSinkPlaceHolder(_, s, s))

        // For AQE shuffle stage on a reused Comet shuffle exchange
        // Note that we don't need to handle `ReusedExchangeExec` for non-AQE case, because
        // the query plan won't be re-optimized/planned in non-AQE mode.
        case s @ ShuffleQueryStageExec(
              _,
              ReusedExchangeExec(_, _: CometShuffleExchangeExec),
              _) =>
          newPlanWithProto(s, CometSinkPlaceHolder(_, s, s))

        // Native shuffle for Comet operators
        case s: ShuffleExchangeExec =>
          val nativePrecondition = isCometShuffleEnabled(conf) &&
            isCometNativeShuffleMode(conf) &&
            QueryPlanSerde.nativeShuffleSupported(s)._1

          val nativeShuffle: Option[SparkPlan] =
            if (nativePrecondition) {
              val newOp = operator2Proto(s)
              newOp match {
                case Some(nativeOp) =>
                  // Switch to use Decimal128 regardless of precision, since Arrow native execution
                  // doesn't support Decimal32 and Decimal64 yet.
                  conf.setConfString(CometConf.COMET_USE_DECIMAL_128.key, "true")
                  val cometOp = CometShuffleExchangeExec(s, shuffleType = CometNativeShuffle)
                  Some(CometSinkPlaceHolder(nativeOp, s, cometOp))
                case None =>
                  None
              }
            } else {
              None
            }

          // this is a temporary workaround because some Spark SQL tests fail
          // when we enable COMET_SHUFFLE_FALLBACK_TO_COLUMNAR due to valid bugs
          // that we had not previously seen
          val tryColumnarNext =
            !nativePrecondition || (nativePrecondition && nativeShuffle.isEmpty &&
              COMET_SHUFFLE_FALLBACK_TO_COLUMNAR.get(conf))

          val nativeOrColumnarShuffle = if (nativeShuffle.isDefined) {
            nativeShuffle
          } else if (tryColumnarNext) {
            // Columnar shuffle for regular Spark operators (not Comet) and Comet operators
            // (if configured).
            // If the child of ShuffleExchangeExec is also a ShuffleExchangeExec, we should not
            // convert it to CometColumnarShuffle,
            if (isCometShuffleEnabled(conf) && isCometJVMShuffleMode(conf) &&
              QueryPlanSerde.columnarShuffleSupported(s)._1 &&
              !isShuffleOperator(s.child)) {

              val newOp = QueryPlanSerde.operator2Proto(s)
              newOp match {
                case Some(nativeOp) =>
                  s.child match {
                    case n if n.isInstanceOf[CometNativeExec] || !n.supportsColumnar =>
                      val cometOp =
                        CometShuffleExchangeExec(s, shuffleType = CometColumnarShuffle)
                      Some(CometSinkPlaceHolder(nativeOp, s, cometOp))
                    case _ =>
                      None
                  }
                case None =>
                  None
              }
            } else {
              None
            }
          } else {
            None
          }

          if (nativeOrColumnarShuffle.isDefined) {
            nativeOrColumnarShuffle.get
          } else {
            val isShuffleEnabled = isCometShuffleEnabled(conf)
            s.outputPartitioning
            val reason = getCometShuffleNotEnabledReason(conf).getOrElse("no reason available")
            val msg1 = createMessage(!isShuffleEnabled, s"Comet shuffle is not enabled: $reason")
            val columnarShuffleEnabled = isCometJVMShuffleMode(conf)
            val msg2 = createMessage(
              isShuffleEnabled && !columnarShuffleEnabled && !QueryPlanSerde
                .nativeShuffleSupported(s)
                ._1,
              "Native shuffle: " +
                s"${QueryPlanSerde.nativeShuffleSupported(s)._2}")
            val typeInfo = QueryPlanSerde
              .columnarShuffleSupported(s)
              ._2
            val msg3 = createMessage(
              isShuffleEnabled && columnarShuffleEnabled && !QueryPlanSerde
                .columnarShuffleSupported(s)
                ._1,
              "JVM shuffle: " +
                s"$typeInfo")
            withInfo(s, Seq(msg1, msg2, msg3).flatten.mkString(","))
          }

        case op =>
          op match {
            case _: CometExec | _: AQEShuffleReadExec | _: BroadcastExchangeExec |
                _: CometBroadcastExchangeExec | _: CometShuffleExchangeExec =>
              // Some execs should never be replaced. We include
              // these cases specially here so we do not add a misleading 'info' message
              op
            case _ =>
              // An operator that is not supported by Comet
              withInfo(op, s"${op.nodeName} is not supported")
          }
      }
    }

    def normalizePlan(plan: SparkPlan): SparkPlan = {
      plan.transformUp {
        case p: ProjectExec =>
          val newProjectList = p.projectList.map(normalize(_).asInstanceOf[NamedExpression])
          ProjectExec(newProjectList, p.child)
        case f: FilterExec =>
          val newCondition = normalize(f.condition)
          FilterExec(newCondition, f.child)
      }
    }

    // Spark will normalize NaN and zero for floating point numbers for several cases.
    // See `NormalizeFloatingNumbers` optimization rule in Spark.
    // However, one exception is for comparison operators. Spark does not normalize NaN and zero
    // because they are handled well in Spark (e.g., `SQLOrderingUtil.compareFloats`). But the
    // comparison functions in arrow-rs do not normalize NaN and zero. So we need to normalize NaN
    // and zero for comparison operators in Comet.
    def normalize(expr: Expression): Expression = {
      expr.transformUp {
        case EqualTo(left, right) =>
          EqualTo(normalizeNaNAndZero(left), normalizeNaNAndZero(right))
        case EqualNullSafe(left, right) =>
          EqualNullSafe(normalizeNaNAndZero(left), normalizeNaNAndZero(right))
        case GreaterThan(left, right) =>
          GreaterThan(normalizeNaNAndZero(left), normalizeNaNAndZero(right))
        case GreaterThanOrEqual(left, right) =>
          GreaterThanOrEqual(normalizeNaNAndZero(left), normalizeNaNAndZero(right))
        case LessThan(left, right) =>
          LessThan(normalizeNaNAndZero(left), normalizeNaNAndZero(right))
        case LessThanOrEqual(left, right) =>
          LessThanOrEqual(normalizeNaNAndZero(left), normalizeNaNAndZero(right))
        case Divide(left, right, evalMode) =>
          Divide(left, normalizeNaNAndZero(right), evalMode)
        case Remainder(left, right, evalMode) =>
          Remainder(left, normalizeNaNAndZero(right), evalMode)
      }
    }

    def normalizeNaNAndZero(expr: Expression): Expression = {
      expr match {
        case _: KnownFloatingPointNormalized => expr
        case FloatLiteral(f) if !f.equals(-0.0f) => expr
        case DoubleLiteral(d) if !d.equals(-0.0d) => expr
        case _ =>
          expr.dataType match {
            case _: FloatType | _: DoubleType =>
              KnownFloatingPointNormalized(NormalizeNaNAndZero(expr))
            case _ => expr
          }
      }
    }

    override def apply(plan: SparkPlan): SparkPlan = {
      // DataFusion doesn't have ANSI mode. For now we just disable CometExec if ANSI mode is
      // enabled.
      if (isANSIEnabled(conf)) {
        if (COMET_ANSI_MODE_ENABLED.get()) {
          if (!isSpark40Plus) {
            logWarning("Using Comet's experimental support for ANSI mode.")
          }
        } else {
          logInfo("Comet extension disabled for ANSI mode")
          return plan
        }
      }

      // We shouldn't transform Spark query plan if Comet is not loaded.
      if (!isCometLoaded(conf)) return plan

      if (!isCometExecEnabled(conf)) {
        // Comet exec is disabled, but for Spark shuffle, we still can use Comet columnar shuffle
        if (isCometShuffleEnabled(conf)) {
          applyCometShuffle(plan)
        } else {
          plan
        }
      } else {
        val normalizedPlan = if (CometConf.COMET_REPLACE_SMJ.get()) {
          normalizePlan(plan).transformUp { case p =>
            RewriteJoin.rewrite(p)
          }
        } else {
          normalizePlan(plan)
        }

        var newPlan = transform(normalizedPlan)

        // if the plan cannot be run fully natively then explain why (when appropriate
        // config is enabled)
        if (CometConf.COMET_EXPLAIN_FALLBACK_ENABLED.get()) {
          val info = new ExtendedExplainInfo()
          if (info.extensionInfo(newPlan).nonEmpty) {
            logWarning(
              "Comet cannot execute some parts of this plan natively " +
                s"(set ${CometConf.COMET_EXPLAIN_FALLBACK_ENABLED.key}=false " +
                "to disable this logging):\n" +
                s"${info.generateVerboseExtendedInfo(newPlan)}")
          }
        }

        // Remove placeholders
        newPlan = newPlan.transform {
          case CometSinkPlaceHolder(_, _, s) => s
          case CometScanWrapper(_, s) => s
        }

        // Set up logical links
        newPlan = newPlan.transform {
          case op: CometExec =>
            if (op.originalPlan.logicalLink.isEmpty) {
              op.unsetTagValue(SparkPlan.LOGICAL_PLAN_TAG)
              op.unsetTagValue(SparkPlan.LOGICAL_PLAN_INHERITED_TAG)
            } else {
              op.originalPlan.logicalLink.foreach(op.setLogicalLink)
            }
            op
          case op: CometShuffleExchangeExec =>
            // Original Spark shuffle exchange operator might have empty logical link.
            // But the `setLogicalLink` call above on downstream operator of
            // `CometShuffleExchangeExec` will set its logical link to the downstream
            // operators which cause AQE behavior to be incorrect. So we need to unset
            // the logical link here.
            if (op.originalPlan.logicalLink.isEmpty) {
              op.unsetTagValue(SparkPlan.LOGICAL_PLAN_TAG)
              op.unsetTagValue(SparkPlan.LOGICAL_PLAN_INHERITED_TAG)
            } else {
              op.originalPlan.logicalLink.foreach(op.setLogicalLink)
            }
            op

          case op: CometBroadcastExchangeExec =>
            if (op.originalPlan.logicalLink.isEmpty) {
              op.unsetTagValue(SparkPlan.LOGICAL_PLAN_TAG)
              op.unsetTagValue(SparkPlan.LOGICAL_PLAN_INHERITED_TAG)
            } else {
              op.originalPlan.logicalLink.foreach(op.setLogicalLink)
            }
            op
        }

        // Convert native execution block by linking consecutive native operators.
        var firstNativeOp = true
        newPlan.transformDown {
          case op: CometNativeExec =>
            val newPlan = if (firstNativeOp) {
              firstNativeOp = false
              op.convertBlock()
            } else {
              op
            }

            // If reaching leaf node, reset `firstNativeOp` to true
            // because it will start a new block in next iteration.
            if (op.children.isEmpty) {
              firstNativeOp = true
            }

            newPlan
          case op =>
            firstNativeOp = true
            op
        }
      }
    }

    /**
     * Find the first Comet partial aggregate in the plan. If it reaches a Spark HashAggregate
     * with partial mode, it will return None.
     */
    def findCometPartialAgg(plan: SparkPlan): Option[CometHashAggregateExec] = {
      plan.collectFirst {
        case agg: CometHashAggregateExec if agg.aggregateExpressions.forall(_.mode == Partial) =>
          Some(agg)
        case agg: HashAggregateExec if agg.aggregateExpressions.forall(_.mode == Partial) => None
        case agg: ObjectHashAggregateExec if agg.aggregateExpressions.forall(_.mode == Partial) =>
          None
        case a: AQEShuffleReadExec => findCometPartialAgg(a.child)
        case s: ShuffleQueryStageExec => findCometPartialAgg(s.plan)
      }.flatten
    }

    /**
     * Returns true if a given spark plan is Comet shuffle operator.
     */
    def isShuffleOperator(op: SparkPlan): Boolean = {
      op match {
        case op: ShuffleQueryStageExec if op.plan.isInstanceOf[CometShuffleExchangeExec] => true
        case _: CometShuffleExchangeExec => true
        case op: CometSinkPlaceHolder => isShuffleOperator(op.child)
        case _ => false
      }
    }
  }

  // This rule is responsible for eliminating redundant transitions between row-based and
  // columnar-based operators for Comet. Currently, three potential redundant transitions are:
  // 1. `ColumnarToRowExec` on top of an ending `CometCollectLimitExec` operator, which is
  //    redundant as `CometCollectLimitExec` already wraps a `ColumnarToRowExec` for row-based
  //    output.
  // 2. Consecutive operators of `CometSparkToColumnarExec` and `ColumnarToRowExec`.
  // 3. AQE inserts an additional `CometSparkToColumnarExec` in addition to the one inserted in the
  //    original plan.
  //
  // Note about the first case: The `ColumnarToRowExec` was added during
  // ApplyColumnarRulesAndInsertTransitions' insertTransitions phase when Spark requests row-based
  // output such as a `collect` call. It's correct to add a redundant `ColumnarToRowExec` for
  // `CometExec`. However, for certain operators such as `CometCollectLimitExec` which overrides
  // `executeCollect`, the redundant `ColumnarToRowExec` makes the override ineffective.
  //
  // Note about the second case: When `spark.comet.sparkToColumnar.enabled` is set, Comet will add
  // `CometSparkToColumnarExec` on top of row-based operators first, but the downstream operator
  // only takes row-based input as it's a vanilla Spark operator(as Comet cannot convert it for
  // various reasons) or Spark requests row-based output such as a `collect` call. Spark will adds
  // another `ColumnarToRowExec` on top of `CometSparkToColumnarExec`. In this case, the pair could
  // be removed.
  case class EliminateRedundantTransitions(session: SparkSession) extends Rule[SparkPlan] {
    override def apply(plan: SparkPlan): SparkPlan = {
      val eliminatedPlan = plan transformUp {
        case ColumnarToRowExec(sparkToColumnar: CometSparkToColumnarExec) =>
          if (sparkToColumnar.child.supportsColumnar) {
            // For Spark Columnar to Comet Columnar, we should keep the ColumnarToRowExec
            ColumnarToRowExec(sparkToColumnar.child)
          } else {
            // For Spark Row to Comet Columnar, we should remove ColumnarToRowExec
            // and CometSparkToColumnarExec
            sparkToColumnar.child
          }
        case c @ ColumnarToRowExec(child) if hasCometNativeChild(child) =>
          val op = CometColumnarToRowExec(child)
          if (c.logicalLink.isEmpty) {
            op.unsetTagValue(SparkPlan.LOGICAL_PLAN_TAG)
            op.unsetTagValue(SparkPlan.LOGICAL_PLAN_INHERITED_TAG)
          } else {
            c.logicalLink.foreach(op.setLogicalLink)
          }
          op
        case CometColumnarToRowExec(sparkToColumnar: CometSparkToColumnarExec) =>
          sparkToColumnar.child
        case CometSparkToColumnarExec(child: CometSparkToColumnarExec) => child
        // Spark adds `RowToColumnar` under Comet columnar shuffle. But it's redundant as the
        // shuffle takes row-based input.
        case s @ CometShuffleExchangeExec(
              _,
              RowToColumnarExec(child),
              _,
              _,
              CometColumnarShuffle,
              _) =>
          s.withNewChildren(Seq(child))
      }

      eliminatedPlan match {
        case ColumnarToRowExec(child: CometCollectLimitExec) =>
          child
        case CometColumnarToRowExec(child: CometCollectLimitExec) =>
          child
        case other =>
          other
      }
    }
  }

  @tailrec
  private def hasCometNativeChild(op: SparkPlan): Boolean = {
    op match {
      case c: QueryStageExec => hasCometNativeChild(c.plan)
      case _ => op.exists(_.isInstanceOf[CometPlan])
    }
  }
}

object CometSparkSessionExtensions extends Logging {
  lazy val isBigEndian: Boolean = ByteOrder.nativeOrder().equals(ByteOrder.BIG_ENDIAN)

  private[comet] def isANSIEnabled(conf: SQLConf): Boolean = {
    conf.getConf(SQLConf.ANSI_ENABLED)
  }

  /**
   * Checks whether Comet extension should be loaded for Spark.
   */
  private[comet] def isCometLoaded(conf: SQLConf): Boolean = {
    if (isBigEndian) {
      logInfo("Comet extension is disabled because platform is big-endian")
      return false
    }
    if (!COMET_ENABLED.get(conf)) {
      logInfo(s"Comet extension is disabled, please turn on ${COMET_ENABLED.key} to enable it")
      return false
    }

    // We don't support INT96 timestamps written by Apache Impala in a different timezone yet
    if (conf.getConf(SQLConf.PARQUET_INT96_TIMESTAMP_CONVERSION)) {
      logWarning(
        "Comet extension is disabled, because it currently doesn't support" +
          s" ${SQLConf.PARQUET_INT96_TIMESTAMP_CONVERSION} setting to true.")
      return false
    }

    try {
      // This will load the Comet native lib on demand, and if success, should set
      // `NativeBase.loaded` to true
      NativeBase.isLoaded
    } catch {
      case e: Throwable =>
        if (COMET_NATIVE_LOAD_REQUIRED.get(conf)) {
          throw new CometRuntimeException(
            "Error when loading native library. Please fix the error and try again, or fallback " +
              s"to Spark by setting ${COMET_ENABLED.key} to false",
            e)
        } else {
          logWarning(
            "Comet extension is disabled because of error when loading native lib. " +
              "Falling back to Spark",
            e)
        }
        false
    }
  }

  private[comet] def isCometBroadCastForceEnabled(conf: SQLConf): Boolean = {
    COMET_EXEC_BROADCAST_FORCE_ENABLED.get(conf)
  }

  private[comet] def getCometBroadcastNotEnabledReason(conf: SQLConf): Option[String] = {
    if (!CometConf.COMET_EXEC_BROADCAST_EXCHANGE_ENABLED.get(conf) &&
      !isCometBroadCastForceEnabled(conf)) {
      Some(
        s"${COMET_EXEC_BROADCAST_EXCHANGE_ENABLED.key}.enabled is not specified and " +
          s"${COMET_EXEC_BROADCAST_FORCE_ENABLED.key} is not specified")
    } else {
      None
    }
  }

  // Check whether Comet shuffle is enabled:
  // 1. `COMET_EXEC_SHUFFLE_ENABLED` is true
  // 2. `spark.shuffle.manager` is set to `CometShuffleManager`
  // 3. Off-heap memory is enabled || Spark/Comet unit testing
  private[comet] def isCometShuffleEnabled(conf: SQLConf): Boolean =
    COMET_EXEC_SHUFFLE_ENABLED.get(conf) && isCometShuffleManagerEnabled(conf)

  private[comet] def getCometShuffleNotEnabledReason(conf: SQLConf): Option[String] = {
    if (!COMET_EXEC_SHUFFLE_ENABLED.get(conf)) {
      Some(s"${COMET_EXEC_SHUFFLE_ENABLED.key} is not enabled")
    } else if (!isCometShuffleManagerEnabled(conf)) {
      Some(s"spark.shuffle.manager is not set to ${CometShuffleManager.getClass.getName}")
    } else {
      None
    }
  }

  private def isCometShuffleManagerEnabled(conf: SQLConf) = {
    conf.contains("spark.shuffle.manager") && conf.getConfString("spark.shuffle.manager") ==
      "org.apache.spark.sql.comet.execution.shuffle.CometShuffleManager"
  }

  private[comet] def isCometScanEnabled(conf: SQLConf): Boolean = {
    COMET_NATIVE_SCAN_ENABLED.get(conf)
  }

  private[comet] def isCometExecEnabled(conf: SQLConf): Boolean = {
    COMET_EXEC_ENABLED.get(conf)
  }

  private[comet] def isCometNativeShuffleMode(conf: SQLConf): Boolean = {
    COMET_SHUFFLE_MODE.get(conf) match {
      case "native" => true
      case "auto" => true
      case _ => false
    }
  }

  private[comet] def isCometJVMShuffleMode(conf: SQLConf): Boolean = {
    COMET_SHUFFLE_MODE.get(conf) match {
      case "jvm" => true
      case "auto" => true
      case _ => false
    }
  }

  def isCometScan(op: SparkPlan): Boolean = {
    op.isInstanceOf[CometBatchScanExec] || op.isInstanceOf[CometScanExec]
  }

  def shouldApplySparkToColumnar(conf: SQLConf, op: SparkPlan): Boolean = {
    // Only consider converting leaf nodes to columnar currently, so that all the following
    // operators can have a chance to be converted to columnar. Leaf operators that output
    // columnar batches, such as Spark's vectorized readers, will also be converted to native
    // comet batches.
    if (CometSparkToColumnarExec.isSchemaSupported(op.schema)) {
      op match {
        // Convert Spark DS v1 scan to Arrow format
        case scan: FileSourceScanExec =>
          scan.relation.fileFormat match {
            case _: CSVFileFormat => CometConf.COMET_CONVERT_FROM_CSV_ENABLED.get(conf)
            case _: JsonFileFormat => CometConf.COMET_CONVERT_FROM_JSON_ENABLED.get(conf)
            case _: ParquetFileFormat => CometConf.COMET_CONVERT_FROM_PARQUET_ENABLED.get(conf)
            case _ => isSparkToArrowEnabled(conf, op)
          }
        // Convert Spark DS v2 scan to Arrow format
        case scan: BatchScanExec =>
          scan.scan match {
            case _: CSVScan => CometConf.COMET_CONVERT_FROM_CSV_ENABLED.get(conf)
            case _: JsonScan => CometConf.COMET_CONVERT_FROM_JSON_ENABLED.get(conf)
            case _: ParquetScan => CometConf.COMET_CONVERT_FROM_PARQUET_ENABLED.get(conf)
            case _ => isSparkToArrowEnabled(conf, op)
          }
        // other leaf nodes
        case _: LeafExecNode =>
          isSparkToArrowEnabled(conf, op)
        case _ =>
          // TODO: consider converting other intermediate operators to columnar.
          false
      }
    } else {
      false
    }
  }

  private def isSparkToArrowEnabled(conf: SQLConf, op: SparkPlan) = {
    COMET_SPARK_TO_ARROW_ENABLED.get(conf) && {
      val simpleClassName = Utils.getSimpleName(op.getClass)
      val nodeName = simpleClassName.replaceAll("Exec$", "")
      COMET_SPARK_TO_ARROW_SUPPORTED_OPERATOR_LIST.get(conf).contains(nodeName)
    }
  }

  def isSpark35Plus: Boolean = {
    org.apache.spark.SPARK_VERSION >= "3.5"
  }

  def isSpark40Plus: Boolean = {
    org.apache.spark.SPARK_VERSION >= "4.0"
  }

  def usingDataFusionParquetExec(conf: SQLConf): Boolean =
    Seq(CometConf.SCAN_NATIVE_ICEBERG_COMPAT, CometConf.SCAN_NATIVE_DATAFUSION).contains(
      CometConf.COMET_NATIVE_SCAN_IMPL.get(conf))

  /**
   * Whether we should override Spark memory configuration for Comet. This only returns true when
   * Comet native execution is enabled and/or Comet shuffle is enabled and Comet doesn't use
   * off-heap mode (unified memory manager).
   */
  def shouldOverrideMemoryConf(conf: SparkConf): Boolean = {
    val cometEnabled = getBooleanConf(conf, CometConf.COMET_ENABLED)
    val cometShuffleEnabled = getBooleanConf(conf, CometConf.COMET_EXEC_SHUFFLE_ENABLED)
    val cometExecEnabled = getBooleanConf(conf, CometConf.COMET_EXEC_ENABLED)
    val offHeapMode = CometSparkSessionExtensions.isOffHeapEnabled(conf)
    cometEnabled && (cometShuffleEnabled || cometExecEnabled) && !offHeapMode
  }

  /**
   * Calculates required memory overhead in MB per executor process for Comet when running in
   * on-heap mode.
   *
   * If `COMET_MEMORY_OVERHEAD` is defined then that value will be used, otherwise the overhead
   * will be calculated by multiplying executor memory (`spark.executor.memory`) by
   * `COMET_MEMORY_OVERHEAD_FACTOR`.
   *
   * In either case, a minimum value of `COMET_MEMORY_OVERHEAD_MIN_MIB` will be returned.
   */
  def getCometMemoryOverheadInMiB(sparkConf: SparkConf): Long = {
    if (isOffHeapEnabled(sparkConf)) {
      // when running in off-heap mode we use unified memory management to share
      // off-heap memory with Spark so do not add overhead
      return 0
    }

    // `spark.executor.memory` default value is 1g
    val baseMemoryMiB = ConfigHelpers
      .byteFromString(sparkConf.get("spark.executor.memory", "1024MB"), ByteUnit.MiB)

    val cometMemoryOverheadMinAsString = sparkConf.get(
      COMET_MEMORY_OVERHEAD_MIN_MIB.key,
      COMET_MEMORY_OVERHEAD_MIN_MIB.defaultValueString)

    val minimum = ConfigHelpers.byteFromString(cometMemoryOverheadMinAsString, ByteUnit.MiB)
    val overheadFactor = getDoubleConf(sparkConf, COMET_MEMORY_OVERHEAD_FACTOR)

    val overHeadMemFromConf = sparkConf
      .getOption(COMET_MEMORY_OVERHEAD.key)
      .map(ConfigHelpers.byteFromString(_, ByteUnit.MiB))

    overHeadMemFromConf.getOrElse(math.max((overheadFactor * baseMemoryMiB).toLong, minimum))
  }

  private def getBooleanConf(conf: SparkConf, entry: ConfigEntry[Boolean]) =
    conf.getBoolean(entry.key, entry.defaultValue.get)

  private def getDoubleConf(conf: SparkConf, entry: ConfigEntry[Double]) =
    conf.getDouble(entry.key, entry.defaultValue.get)

  /**
   * Calculates required memory overhead in bytes per executor process for Comet when running in
   * on-heap mode.
   */
  def getCometMemoryOverhead(sparkConf: SparkConf): Long = {
    ByteUnit.MiB.toBytes(getCometMemoryOverheadInMiB(sparkConf))
  }

  /**
   * Calculates required shuffle memory size in bytes per executor process for Comet when running
   * in on-heap mode.
   */
  def getCometShuffleMemorySize(sparkConf: SparkConf, conf: SQLConf = SQLConf.get): Long = {
    assert(!isOffHeapEnabled(sparkConf))

    val cometMemoryOverhead = getCometMemoryOverheadInMiB(sparkConf)

    val overheadFactor = COMET_COLUMNAR_SHUFFLE_MEMORY_FACTOR.get(conf)
    val cometShuffleMemoryFromConf = COMET_COLUMNAR_SHUFFLE_MEMORY_SIZE.get(conf)

    val shuffleMemorySize =
      cometShuffleMemoryFromConf.getOrElse((overheadFactor * cometMemoryOverhead).toLong)
    if (shuffleMemorySize > cometMemoryOverhead) {
      logWarning(
        s"Configured shuffle memory size $shuffleMemorySize is larger than Comet memory overhead " +
          s"$cometMemoryOverhead, using Comet memory overhead instead.")
      ByteUnit.MiB.toBytes(cometMemoryOverhead)
    } else {
      ByteUnit.MiB.toBytes(shuffleMemorySize)
    }
  }

  def isOffHeapEnabled(sparkConf: SparkConf): Boolean = {
    sparkConf.getBoolean("spark.memory.offHeap.enabled", false)
  }

  /**
   * Attaches explain information to a TreeNode, rolling up the corresponding information tags
   * from any child nodes. For now, we are using this to attach the reasons why certain Spark
   * operators or expressions are disabled.
   *
   * @param node
   *   The node to attach the explain information to. Typically a SparkPlan
   * @param info
   *   Information text. Optional, may be null or empty. If not provided, then only information
   *   from child nodes will be included.
   * @param exprs
   *   Child nodes. Information attached in these nodes will be be included in the information
   *   attached to @node
   * @tparam T
   *   The type of the TreeNode. Typically SparkPlan, AggregateExpression, or Expression
   * @return
   *   The node with information (if any) attached
   */
  def withInfo[T <: TreeNode[_]](node: T, info: String, exprs: T*): T = {
    // support existing approach of passing in multiple infos in a newline-delimited string
    val infoSet = if (info == null || info.isEmpty) {
      Set.empty[String]
    } else {
      info.split("\n").toSet
    }
    withInfos(node, infoSet, exprs: _*)
  }

  /**
   * Attaches explain information to a TreeNode, rolling up the corresponding information tags
   * from any child nodes. For now, we are using this to attach the reasons why certain Spark
   * operators or expressions are disabled.
   *
   * @param node
   *   The node to attach the explain information to. Typically a SparkPlan
   * @param info
   *   Information text. May contain zero or more strings. If not provided, then only information
   *   from child nodes will be included.
   * @param exprs
   *   Child nodes. Information attached in these nodes will be be included in the information
   *   attached to @node
   * @tparam T
   *   The type of the TreeNode. Typically SparkPlan, AggregateExpression, or Expression
   * @return
   *   The node with information (if any) attached
   */
  def withInfos[T <: TreeNode[_]](node: T, info: Set[String], exprs: T*): T = {
    val existingNodeInfos = node.getTagValue(CometExplainInfo.EXTENSION_INFO)
    val newNodeInfo = (existingNodeInfos ++ exprs
      .flatMap(_.getTagValue(CometExplainInfo.EXTENSION_INFO))).flatten.toSet
    node.setTagValue(CometExplainInfo.EXTENSION_INFO, newNodeInfo ++ info)
    node
  }

  /**
   * Attaches explain information to a TreeNode, rolling up the corresponding information tags
   * from any child nodes
   *
   * @param node
   *   The node to attach the explain information to. Typically a SparkPlan
   * @param exprs
   *   Child nodes. Information attached in these nodes will be be included in the information
   *   attached to @node
   * @tparam T
   *   The type of the TreeNode. Typically SparkPlan, AggregateExpression, or Expression
   * @return
   *   The node with information (if any) attached
   */
  def withInfo[T <: TreeNode[_]](node: T, exprs: T*): T = {
    withInfos(node, Set.empty, exprs: _*)
  }

  // Helper to reduce boilerplate
  def createMessage(condition: Boolean, message: => String): Option[String] = {
    if (condition) {
      Some(message)
    } else {
      None
    }
  }
}
