package com.linkedin.openhouse.spark.sql.execution.datasources.v2

import com.linkedin.openhouse.capabilities.OpenHouseTableProperties
import com.linkedin.openhouse.spark.capabilities.OpenHouseCapabilities
import org.apache.iceberg.spark.source.SparkTable
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.connector.catalog.{Identifier, TableCatalog}
import org.apache.spark.sql.execution.datasources.v2.LeafV2CommandExec

case class SetReplicationPolicyExec(catalog: TableCatalog, ident: Identifier, replicationPolicies: Seq[(String, Option[String])]) extends LeafV2CommandExec{
  override protected def run(): Seq[InternalRow] = {
    catalog.loadTable(ident) match {
      case iceberg: SparkTable if iceberg.table().properties().containsKey(OpenHouseTableProperties.TABLE_ID) =>
        val key = OpenHouseTableProperties.POLICY_PATCH
        val value = s"""{"replication":{"config":[${replicationPolicies.map(replication => s"""{"destination":"${replication._1}","interval":"${replication._2.getOrElse("")}"}""").mkString(",")}]}}"""
        // On an Iceberg REST server that does not advertise policies, fail instead of storing the patch.
        OpenHouseCapabilities.requirePolicies(catalog)
        iceberg.table().updateProperties()
          .set(key, value)
          .commit()

      case table =>
        throw new UnsupportedOperationException(s"Cannot set replication policy for non-Openhouse table: $table")
    }
    Nil
  }

  override def output: Seq[Attribute] = Nil
}
