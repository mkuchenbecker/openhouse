package com.linkedin.openhouse.spark.sql.execution.datasources.v2

import com.linkedin.openhouse.spark.sql.catalyst.constants.Principal
import com.linkedin.openhouse.spark.sql.catalyst.enums.GrantableResourceTypes
import com.linkedin.openhouse.spark.sql.catalyst.enums.GrantableResourceTypes.GrantableResourceType
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Attribute, GenericInternalRow}
import org.apache.spark.sql.connector.catalog.{Identifier, TableCatalog}
import org.apache.spark.sql.execution.LeafExecNode
import org.apache.spark.sql.execution.datasources.v2.LeafV2CommandExec
import org.apache.spark.unsafe.types.UTF8String

/**
 * Physical exec for `SHOW GRANTS ON <resource>` on the Spark-4.0 REST-first lane. Reads the ACL
 * policies from the OpenHouse server ACL endpoint directly over HTTP via {@link OpenHouseAclClient}
 * and emits one (privilege, principal) row per policy. The server-side `role` is reverse-mapped to
 * its privilege, mirroring the java-client `SparkMapper.toAclPolicyDto`.
 */
case class ShowGrantsStatementExec(
  output: Seq[Attribute],
  resourceType: GrantableResourceType,
  catalog: TableCatalog,
  ident: Identifier) extends LeafV2CommandExec with LeafExecNode {

  override protected def run(): Seq[InternalRow] = {
    val spark = SparkSession.active
    val (baseUri, token) = OpenHouseAclClient.serverBaseAndToken(spark, catalog.name())
    val aclPath = resourceType match {
      case GrantableResourceTypes.TABLE =>
        s"/v1/databases/${ident.namespace().mkString(".")}/tables/${ident.name()}/aclPolicies"
      case GrantableResourceTypes.DATABASE =>
        s"/v1/databases/${(ident.namespace() :+ ident.name()).mkString(".")}/aclPolicies"
    }
    OpenHouseAclClient.getAclPolicies(baseUri, token, aclPath).map { case (privilege, principal) =>
      val displayPrincipal = Principal.unapply(principal).get
      val row: Array[Any] =
        Array(UTF8String.fromString(privilege), UTF8String.fromString(displayPrincipal))
      new GenericInternalRow(row)
    }
  }

  override def simpleString(maxFields: Int): String = {
    s"ShowGrantsStatementExec: ${catalog.name()} $ident"
  }
}
