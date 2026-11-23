package com.linkedin.openhouse.spark.sql.execution.datasources.v2

import com.linkedin.openhouse.capabilities.{UnlockReason, UnlockTableTarget}
import com.linkedin.openhouse.spark.capabilities.OpenHouseCapabilities
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.connector.catalog.{Identifier, TableCatalog}
import org.apache.spark.sql.execution.datasources.v2.LeafV2CommandExec

case class UnlockTableExec(
  catalog: TableCatalog,
  target: UnlockTableTarget,
  reason: UnlockReason) extends LeafV2CommandExec {

  override lazy val output: Seq[Attribute] = Nil

  override protected def run(): Seq[InternalRow] = {
    // Skips loading, which fails on a SYSTEM_ONLY lock. Fails with "does not support UNLOCK".
    OpenHouseCapabilities.requireUnlock(catalog).unlockTable(target, reason)
    // Clear the session's cached lock.
    catalog.invalidateTable(Identifier.of(Array(target.getDatabaseId), target.getTableId))
    Nil
  }

  override def simpleString(maxFields: Int): String = {
    s"UnlockTableExec: ${catalog.name()} $target $reason"
  }
}
