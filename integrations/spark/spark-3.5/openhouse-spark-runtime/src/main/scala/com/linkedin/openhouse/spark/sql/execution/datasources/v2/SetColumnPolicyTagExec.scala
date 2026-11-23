package com.linkedin.openhouse.spark.sql.execution.datasources.v2

import com.linkedin.openhouse.capabilities.OpenHouseTableProperties
import com.linkedin.openhouse.spark.capabilities.OpenHouseCapabilities
import org.apache.iceberg.spark.source.SparkTable
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.connector.catalog.{Identifier, TableCatalog}
import org.apache.spark.sql.execution.datasources.v2.LeafV2CommandExec

case class SetColumnPolicyTagExec(
  catalog: TableCatalog,
  ident: Identifier,
  colName: String,
  policyTags: Seq[String]) extends LeafV2CommandExec {

  override lazy val output: Seq[Attribute] = Nil

  override protected def run(): Seq[InternalRow] = {
    catalog.loadTable(ident) match {
      case iceberg: SparkTable if iceberg.table().properties().containsKey(OpenHouseTableProperties.TABLE_ID) =>
        val key = OpenHouseTableProperties.POLICY_PATCH
        val value = s"""{"columnTags":{"${colName}": {"tags": [${policyTags.mkString(", ")}]}}}"""

        // On an Iceberg REST server that does not advertise policies, fail instead of storing the patch.
        OpenHouseCapabilities.requirePolicies(catalog)
        iceberg.table().updateProperties()
          .set(key, value)
          .commit()

      case table =>
        throw new UnsupportedOperationException(s"Cannot set column policy tags for non-Openhouse table: $table")
    }

    Nil
  }

  override def simpleString(maxFields: Int): String = {
    s"SetColumnPolicyTagExec: ${catalog} ${ident} ${colName} ${policyTags}"
  }
}
