package com.linkedin.openhouse.spark.sql.catalyst.constants

/**
 * This object is used to represent keyword global user group "PUBLIC" which maps to the acl policy representation "*".
 *
 * Ported verbatim from the spark-3.x runtime (`spark-3.1 .../catalyst/constants/Principal.scala`) so
 * the Spark-4.0 GRANT / REVOKE / SHOW GRANTS execs share the identical PUBLIC <-> "*" convention.
 */
object Principal {
  private val GLOBAL_USER_GROUP = "PUBLIC"
  private val GLOBAL_USER_GROUP_ACL = "*"
  def apply(principal: String): String = principal.toUpperCase match {
    case GLOBAL_USER_GROUP => GLOBAL_USER_GROUP_ACL
    case _ => principal
  }

  def unapply(principalAcl: String): Option[String] = principalAcl match {
    case GLOBAL_USER_GROUP_ACL => Some(GLOBAL_USER_GROUP)
    case _ => Some(principalAcl)
  }
}
