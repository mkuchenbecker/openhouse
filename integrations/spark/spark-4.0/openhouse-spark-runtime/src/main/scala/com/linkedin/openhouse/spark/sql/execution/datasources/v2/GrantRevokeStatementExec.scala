package com.linkedin.openhouse.spark.sql.execution.datasources.v2

import com.linkedin.openhouse.spark.sql.catalyst.constants.Principal
import com.linkedin.openhouse.spark.sql.catalyst.enums.GrantableResourceTypes
import com.linkedin.openhouse.spark.sql.catalyst.enums.GrantableResourceTypes.GrantableResourceType
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.connector.catalog.{Identifier, TableCatalog}
import org.apache.spark.sql.execution.datasources.v2.LeafV2CommandExec

/**
 * Physical exec for `GRANT <priv> ON <resource> TO <principal>` / `REVOKE <priv> ... FROM ...` on
 * the Spark-4.0 REST-first lane.
 *
 * <p>Unlike the legacy spark-3.x exec (which downcast the catalog to a custom `SupportsGrantRevoke`
 * `OpenHouseCatalog`), the REST lane's catalog is the stock `RESTCatalog`, so this exec calls the
 * OpenHouse server ACL endpoint directly over HTTP via {@link OpenHouseAclClient}. The privilege is
 * mapped to its role using the authoritative mapping (mirrors `javaclient/mapper/Privileges.java`).
 */
case class GrantRevokeStatementExec(
  isGrant: Boolean,
  resourceType: GrantableResourceType,
  catalog: TableCatalog,
  ident: Identifier,
  privilege: String,
  principal: String) extends LeafV2CommandExec {

  override lazy val output: Seq[Attribute] = Nil

  override protected def run(): Seq[InternalRow] = {
    val spark = SparkSession.active
    val (baseUri, token) = OpenHouseAclClient.serverBaseAndToken(spark, catalog.name())
    val role = OpenHouseAclClient.privilegeToRole(privilege)
    val aclPath = resourceType match {
      case GrantableResourceTypes.TABLE =>
        s"/v1/databases/${ident.namespace().mkString(".")}/tables/${ident.name()}/aclPolicies"
      case GrantableResourceTypes.DATABASE =>
        s"/v1/databases/${(ident.namespace() :+ ident.name()).mkString(".")}/aclPolicies"
    }
    OpenHouseAclClient.updateAclPolicies(
      baseUri, token, aclPath, isGrant, role, Principal(principal))
    Nil
  }

  override def simpleString(maxFields: Int): String = {
    s"GrantRevokeStatementExec: ${catalog.name()} $isGrant $ident $privilege $principal"
  }
}
