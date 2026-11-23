package com.linkedin.openhouse.spark.sql.catalyst.constants

/**
 * This object is used to represent keyword global user group "PUBLIC" which maps to the acl policy representation "*".
 * The mapping lives in the capabilities module ({@link com.linkedin.openhouse.capabilities.Principal}).
 */
object Principal {
  def apply(principal: String): String = com.linkedin.openhouse.capabilities.Principal.toAcl(principal)

  def unapply(principalAcl: String): Option[String] =
    Some(com.linkedin.openhouse.capabilities.Principal.fromAcl(principalAcl))
}
