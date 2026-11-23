package com.linkedin.openhouse.spark.sql.catalyst.plans.logical

import com.linkedin.openhouse.capabilities.{UnlockReason, UnlockTableTarget}
import org.apache.spark.sql.catalyst.plans.logical.LeafCommand
import org.apache.spark.sql.connector.catalog.TableCatalog

case class UnlockTable(tableName: Seq[String], reason: UnlockReason) extends LeafCommand {
  override lazy val resolved: Boolean = false

  override def simpleString(maxFields: Int): String = {
    s"UnlockTable: $tableName $reason"
  }
}

case class ResolvedUnlockTable(
  catalog: TableCatalog,
  target: UnlockTableTarget,
  reason: UnlockReason) extends LeafCommand
