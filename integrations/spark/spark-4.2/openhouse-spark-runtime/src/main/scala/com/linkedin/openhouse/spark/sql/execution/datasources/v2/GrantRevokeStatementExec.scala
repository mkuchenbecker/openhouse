package com.linkedin.openhouse.spark.sql.execution.datasources.v2


import com.linkedin.openhouse.capabilities.Principal
import com.linkedin.openhouse.spark.capabilities.OpenHouseCapabilities
import com.linkedin.openhouse.spark.sql.catalyst.enums.GrantableResourceTypes
import com.linkedin.openhouse.spark.sql.catalyst.enums.GrantableResourceTypes.GrantableResourceType
import org.apache.iceberg.catalog.Namespace
import org.apache.iceberg.spark.Spark3Util
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.connector.catalog.{Identifier, TableCatalog}
import org.apache.spark.sql.execution.datasources.v2.LeafV2CommandExec

case class GrantRevokeStatementExec(
  isGrant: Boolean,
  resourceType: GrantableResourceType,
  catalog: TableCatalog,
  ident: Identifier,
  privilege: String,
  principal: String) extends LeafV2CommandExec {

  override lazy val output: Seq[Attribute] = Nil

  override protected def run(): Seq[InternalRow] = {
    /** Find the catalog's grants capability; fails with "does not support Grant Revoke Statements" */
    val grants = OpenHouseCapabilities.requireGrants(catalog)
    resourceType match {
      case GrantableResourceTypes.TABLE =>
        grants.updateTableAclPolicies(Spark3Util.identifierToTableIdentifier(ident), isGrant, privilege, Principal.toAcl(principal))
      case GrantableResourceTypes.DATABASE =>
        grants.updateDatabaseAclPolicies(toNamespace(ident), isGrant, privilege, Principal.toAcl(principal))
    }
    Nil
  }

  override def simpleString(maxFields: Int): String = {
    s"GrantRevokeStatementExec: ${catalog.name()} $isGrant $ident $privilege $principal"
  }

  private def toNamespace(ident: Identifier): Namespace = {
    val dbArray: Array[String] = ident.namespace() :+ ident.name()
    Namespace.of(dbArray:_*)
  }
}
