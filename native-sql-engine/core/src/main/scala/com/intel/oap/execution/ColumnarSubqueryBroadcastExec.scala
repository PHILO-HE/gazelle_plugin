/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution

import com.intel.oap.execution.BroadcastColumnarRDD
import org.apache.spark.sql.execution.ColumnarHashedRelation

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.QueryPlan
import org.apache.spark.sql.execution.joins.{HashJoin, HashedRelation, LongHashedRelation}
import org.apache.spark.sql.execution.metric.SQLMetrics
import org.apache.spark.util.ThreadUtils

case class ColumnarSubqueryBroadcastExec(
                                  name: String,
                                  index: Int,
                                  buildKeys: Seq[Expression],
                                  child: SparkPlan) extends BaseSubqueryExec with UnaryExecNode {

  // `SubqueryBroadcastExec` is only used with `InSubqueryExec`. No one would reference this output,
  // so the exprId doesn't matter here. But it's important to correctly report the output length, so
  // that `InSubqueryExec` can know it's the single-column execution mode, not multi-column.
  override def output: Seq[Attribute] = {
    val key = buildKeys(index)
    val name = key match {
      case n: NamedExpression => n.name
      case Cast(n: NamedExpression, _, _, _) => n.name
      case _ => "key"
    }
    Seq(AttributeReference(name, key.dataType, key.nullable)())
  }

  override lazy val metrics = Map(
    "dataSize" -> SQLMetrics.createMetric(sparkContext, "data size (bytes)"),
    "collectTime" -> SQLMetrics.createMetric(sparkContext, "time to collect (ms)"))

  override def doCanonicalize(): SparkPlan = {
    val keys = buildKeys.map(k => QueryPlan.normalizeExpressions(k, child.output))
    ColumnarSubqueryBroadcastExec("dpp", index, keys, child.canonicalized)
  }

  @transient
  private lazy val relationFuture: Future[Array[InternalRow]] = {
    // relationFuture is used in "doExecute". Therefore we can get the execution id correctly here.
    val executionId = sparkContext.getLocalProperty(SQLExecution.EXECUTION_ID_KEY)
    Future {
      // This will run in another thread. Set the execution id so that we can connect these jobs
      // with the correct execution.
      SQLExecution.withExecutionId(session, executionId) {
        val beforeCollect = System.nanoTime()

//        val broadcastRelation = child.executeBroadcast[HashedRelation]().value
//        val (iter, expr) = if (broadcastRelation.isInstanceOf[LongHashedRelation]) {
//          (broadcastRelation.keys(), HashJoin.extractKeyExprAt(buildKeys, index))
//        } else {
//          (broadcastRelation.keys(),
//            BoundReference(index, buildKeys(index).dataType, buildKeys(index).nullable))
//        }
//
//        val proj = UnsafeProjection.create(expr)
//        val keyIter = iter.map(proj).map(_.copy())

        val broadcastRelation = child.executeBroadcast[ColumnarHashedRelation]()
        val inputRdd = BroadcastColumnarRDD(sparkContext, metrics, 1, broadcastRelation)

        val localOutput = this.output
        val internalRowRDD = inputRdd.mapPartitionsInternal { batches =>
          val toUnsafe = UnsafeProjection.create(localOutput, localOutput)
          batches.flatMap { batch =>
            batch.rowIterator().asScala.map(toUnsafe)
          }
        }

        val iter = internalRowRDD.toLocalIterator[InternalRow]

        val proj = UnsafeProjection.create(output, output)
        val keyIter = iter.map(proj).map(_.copy())

        val rows = keyIter.toArray[InternalRow].distinct
        val beforeBuild = System.nanoTime()
        longMetric("collectTime") += (beforeBuild - beforeCollect) / 1000000
        val dataSize = rows.map(_.asInstanceOf[UnsafeRow].getSizeInBytes).sum
        longMetric("dataSize") += dataSize
        SQLMetrics.postDriverMetricUpdates(sparkContext, executionId, metrics.values.toSeq)

        rows
      }
    }(ColumnarSubqueryBroadcastExec.executionContext)
  }

  protected override def doPrepare(): Unit = {
    relationFuture
  }

  protected override def doExecute(): RDD[InternalRow] = {
    throw new RuntimeException("Not supported by ColumnarSubqueryBroadcastExec")
  }

  override def executeCollect(): Array[InternalRow] = {
    ThreadUtils.awaitResult(relationFuture, Duration.Inf)
  }

  override def stringArgs: Iterator[Any] = super.stringArgs ++ Iterator(s"[id=#$id]")

  protected def withNewChildInternal(newChild: SparkPlan): ColumnarSubqueryBroadcastExec =
    copy(child = newChild)
}

object ColumnarSubqueryBroadcastExec {
  private[execution] val executionContext = ExecutionContext.fromExecutorService(
    ThreadUtils.newDaemonCachedThreadPool("dynamicpruning", 16))
}
