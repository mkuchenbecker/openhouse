package com.linkedin.openhouse.spark.sql.catalyst.plans.logical

import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeReference}
import org.apache.spark.sql.catalyst.plans.logical.LeafCommand
import org.apache.spark.sql.types.StringType

/**
 * The logical plan of the VACUUM command:
 * {{{
 *   VACUUM multi_part_name [REMOVE ORPHAN FILES] [RETAIN n HOURS]
 * }}}
 *
 * Reports the retention windows it resolved, so an operator can see which one each step actually
 * used -- an explicit `RETAIN`, the table's `policies.history`, or the maintenance job's default.
 */
case class VacuumTable(tableName: Seq[String], removeOrphanFiles: Boolean, retainHours: Option[Int])
  extends LeafCommand {

  override lazy val output: Seq[Attribute] = Seq(
    AttributeReference("metric", StringType, nullable = false)(),
    AttributeReference("value", StringType, nullable = false)())

  override def simpleString(maxFields: Int): String = {
    s"VacuumTable: ${tableName} removeOrphanFiles=${removeOrphanFiles} retainHours=${retainHours.getOrElse("default")}"
  }
}
