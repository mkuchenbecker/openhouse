package com.linkedin.openhouse.spark.sql.execution.datasources.v2

import com.linkedin.openhouse.spark.sql.catalyst.plans.logical.{SetColumnPolicyTag, SetHistoryPolicy, SetReplicationPolicy, SetRetentionPolicy, SetSharingPolicy, UnSetReplicationPolicy}
import org.apache.iceberg.spark.{Spark3Util, SparkCatalog, SparkSessionCatalog}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.PredicateHelper
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.connector.catalog.{Identifier, TableCatalog}
import org.apache.spark.sql.execution.{SparkPlan, SparkStrategy}

import scala.collection.JavaConverters._

/* Strategy to convert a logical plan to physical plans.
 * Spark 4.0 removed the `org.apache.spark.sql.Strategy` type alias, so extend
 * `org.apache.spark.sql.execution.SparkStrategy` (the type `injectPlannerStrategy` expects) directly. */
case class OpenhouseDataSourceV2Strategy(spark: SparkSession) extends SparkStrategy with PredicateHelper {
  override def apply(plan: LogicalPlan): Seq[SparkPlan] = plan match {
    case SetRetentionPolicy(CatalogAndIdentifierExtractor(catalog, ident), granularity, count, colName, colPattern) =>
      SetRetentionPolicyExec(catalog, ident, granularity, count, colName, colPattern) :: Nil
    case SetReplicationPolicy(CatalogAndIdentifierExtractor(catalog, ident), replicationPolicies) =>
      SetReplicationPolicyExec(catalog, ident, replicationPolicies) :: Nil
    case UnSetReplicationPolicy(CatalogAndIdentifierExtractor(catalog, ident), replicationPolicies) =>
      UnSetReplicationPolicyExec(catalog, ident, replicationPolicies) :: Nil
    case SetHistoryPolicy(CatalogAndIdentifierExtractor(catalog, ident), granularity, maxAge, versions) =>
      SetHistoryPolicyExec(catalog, ident, granularity, maxAge, versions) :: Nil
    case SetSharingPolicy(CatalogAndIdentifierExtractor(catalog, ident), sharing) =>
      SetSharingPolicyExec(catalog, ident, sharing) :: Nil
    case SetColumnPolicyTag(CatalogAndIdentifierExtractor(catalog, ident), policyTag, cols) =>
      SetColumnPolicyTagExec(catalog, ident, policyTag, cols) :: Nil

    // NOTE: GRANT / REVOKE / SHOW GRANTS still parse (grammar + logical plans retained), but the
    // REST lane has no server ACL endpoint (SupportsGrantRevoke), so no physical execution is wired
    // here. They fall through to the empty match and surface as an unsupported-plan error until a
    // /iceberg grant endpoint exists. See policy-sql-extension-spark4.md.
    case _ => Nil
  }

  private object CatalogAndIdentifierExtractor {
    def unapply(identifier: Seq[String]): Option[(TableCatalog, Identifier)] = {
      val catalogAndIdentifier = Spark3Util.catalogAndIdentifier(spark, identifier.asJava)
      catalogAndIdentifier.catalog match {
        case icebergCatalog: SparkCatalog =>
          Some((icebergCatalog, catalogAndIdentifier.identifier))
        case icebergCatalog: SparkSessionCatalog[_] =>
          Some((icebergCatalog, catalogAndIdentifier.identifier))
        case _ =>
          None
      }
    }
  }
}
