package com.linkedin.openhouse.spark.sql.catalyst.analysis

import com.linkedin.openhouse.capabilities.{UnlockTableTarget, UnlockValidationException}
import com.linkedin.openhouse.spark.capabilities.OpenHouseCapabilities
import com.linkedin.openhouse.spark.sql.catalyst.plans.logical.{ResolvedUnlockTable, UnlockTable}
import org.apache.iceberg.spark.Spark3Util
import org.apache.spark.sql.{AnalysisException, SparkSession}
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.connector.catalog.TableCatalog
import org.apache.spark.sql.internal.SQLConf

import scala.jdk.CollectionConverters._

case class ResolveUnlockTable(spark: SparkSession) extends Rule[LogicalPlan] {
  override def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperators {
    case UnlockTable(tableName, reason) =>
      // The session's conf as the analyzer sees it (Spark 3.5 reads spark.sessionState.conf).
      if (!SQLConf.get.getConf(ResolveUnlockTable.UNLOCK_ENABLED)) {
        throw new UnsupportedOperationException(
          s"ALTER TABLE UNLOCK is disabled. Set ${ResolveUnlockTable.UNLOCK_ENABLED.key}=true to enable it.")
      }
      val resolved = Spark3Util.catalogAndIdentifier(spark, tableName.asJava)
      resolved.catalog match {
        case catalog: TableCatalog if OpenHouseCapabilities.resolve(catalog).unlock().isPresent =>
          val target = try {
            UnlockTableTarget.of(resolved.identifier.namespace(), resolved.identifier.name())
          } catch {
            case e: UnlockValidationException => throw new UnlockAnalysisException(e)
          }
          ResolvedUnlockTable(catalog, target, reason)
        case _ =>
          throw new UnsupportedOperationException(s"Catalog '${resolved.catalog.name()}' does not support UNLOCK")
      }
  }
}

object ResolveUnlockTable {
  val UNLOCK_ENABLED = SQLConf.buildConf("spark.sql.openhouse.unlock.enabled")
    .doc("Enable ALTER TABLE UNLOCK. The catalog's authenticated caller must also have lock-administration permission.")
    .booleanConf
    .createWithDefault(false)
}

// Extends AnalysisException to access its protected constructor.
private[analysis] class UnlockAnalysisException(cause: UnlockValidationException)
  extends AnalysisException(cause.getMessage, None, None, cause = Some(cause))
