package com.linkedin.openhouse.spark.sql.execution.datasources.v2

import scala.collection.JavaConverters._

import com.linkedin.openhouse.spark.sql.catalyst.plans.logical.OptimizeTable
import com.linkedin.openhouse.spark.sql.catalyst.plans.logical.OptimizeTable._
import org.apache.iceberg.{HasTableOperations, SortOrder, Table}
import org.apache.iceberg.spark.actions.SparkActions
import org.apache.iceberg.spark.source.SparkTable
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Attribute, GenericInternalRow}
import org.apache.spark.sql.catalyst.util.quoteIfNeeded
import org.apache.spark.sql.connector.catalog.{Identifier, TableCatalog, TableChange}
import org.apache.spark.sql.execution.datasources.v2.LeafV2CommandExec
import org.apache.spark.unsafe.types.UTF8String

/**
 * Runs Iceberg data-layout maintenance for the OPTIMIZE command through the Iceberg action API.
 *
 * With no `optimize.cluster.keys` configured this is a plain bin-pack compaction. With clustering
 * configured it is a sort / z-order rewrite whose progress is tracked two ways at once, both in
 * table metadata that survives snapshot expiration:
 *
 *  - '''Per-file layout stamp.''' Every file the rewrite writes records the layout id as its
 *    Iceberg `sort_order_id`, and carries the id and the layout JSON in its footer. A file is
 *    clustered iff its stamp is the current layout; a file rewritten by anything else (a scheduled
 *    bin-pack, another engine) loses the stamp and is eligible again.
 *  - '''Sequence watermark.''' `optimize.cluster.hwm-seq` is the highest data sequence number a
 *    run has consumed. Output files are committed with the data sequence number of the newest
 *    input they replaced, so a run's own output never reads as new data.
 *
 * Incremental (the default) rewrites files above the watermark that do not carry the current
 * layout; `FULL` rewrites every file that does not carry the current layout, whatever its
 * sequence. Files stamped with an older layout are left to `FULL`.
 *
 * After the data rewrite, position delete files are compacted and dangling deletes dropped (a
 * no-op on copy-on-write or delete-free tables), then `REWRITE MANIFESTS` runs if requested.
 */
case class OptimizeTableExec(
  output: Seq[Attribute],
  spark: SparkSession,
  catalog: TableCatalog,
  ident: Identifier,
  full: Boolean,
  rewriteManifests: Boolean) extends LeafV2CommandExec {

  import OptimizeTableExec._

  private def row(metric: String, value: String): InternalRow =
    new GenericInternalRow(
      Array[Any](UTF8String.fromString(metric), UTF8String.fromString(value)))

  override protected def run(): Seq[InternalRow] = {
    val table = catalog.loadTable(ident) match {
      case iceberg: SparkTable
        if iceberg.table().properties().containsKey(MaintenanceProperties.TABLE_ID_PROP) =>
        iceberg.table()
      case other =>
        throw new UnsupportedOperationException(s"Cannot optimize non-Openhouse table: $other")
    }
    val props = table.properties().asScala.toMap

    // A table opted out of platform maintenance should not be compacted by hand either.
    val compactionJob = MaintenanceProperties.DATA_COMPACTION_JOB
    if (MaintenanceProperties.isMaintenanceDisabled(props, compactionJob)) {
      throw new UnsupportedOperationException(
        s"Maintenance is disabled for table '$ident' ('maintenance.disabled' or " +
          s"'maintenance.$compactionJob.disabled'), so OPTIMIZE will not run on it.")
    }

    val qualifiedTableName =
      (Seq(catalog.name()) ++ ident.namespace() :+ ident.name()).map(quoteIfNeeded).mkString(".")

    // Snapshot the physical layout before doing any work so we can report the reduction.
    val filesBefore = spark.table(s"$qualifiedTableName.files").count()
    val snapshotsBefore = spark.table(s"$qualifiedTableName.snapshots").count()
    val metrics = Seq.newBuilder[InternalRow]

    val config = OptimizeTable.parseClusterConfig(props)
    if (config.keys.isEmpty) {
      // No clustering configured: plain bin-pack compaction.
      val result = SparkActions.get(spark).rewriteDataFiles(table).binPack().execute()
      metrics += row("files_rewritten", result.rewrittenDataFilesCount().toString)
      metrics += row("bytes_rewritten", result.rewrittenBytesCount().toString)
    } else {
      cluster(table, qualifiedTableName, props, config, metrics)
    }

    // Compact merge-on-read position delete files and drop dangling deletes (deletes that no longer
    // apply to any live data, e.g. because the data files they targeted were just rewritten).
    // Runs after the data rewrite so it also cleans up deletes that rewrite made dangling, and
    // before REWRITE MANIFESTS so manifest compaction sees the reduced delete-file set.
    SparkActions.get(spark).rewritePositionDeletes(table).execute()

    if (rewriteManifests) {
      // Independent manifest compaction; runs after the data rewrite so it sees the new layout.
      SparkActions.get(spark).rewriteManifests(table).execute()
    }

    val filesAfter = spark.table(s"$qualifiedTableName.files").count()
    val snapshotsAfter = spark.table(s"$qualifiedTableName.snapshots").count()
    metrics += row("files_before", filesBefore.toString)
    metrics += row("files_after", filesAfter.toString)
    metrics += row("files_removed", (filesBefore - filesAfter).toString)
    metrics += row("snapshots_committed", (snapshotsAfter - snapshotsBefore).toString)
    metrics.result()
  }

  private def cluster(
      table: Table,
      qualifiedTableName: String,
      props: Map[String, String],
      config: ClusterConfig,
      metrics: scala.collection.mutable.Builder[InternalRow, Seq[InternalRow]]): Unit = {
    requireSequenceNumbers(table)

    val layout = config.layout
    // A watermark left by the snapshot-id design is carried over once: a live snapshot maps to its
    // sequence number, an expired one to 0 (everything is re-examined, and the stamps decide).
    val hwmBefore = props.get(LEGACY_HWM_SNAPSHOT_PROP).map(_.trim.toLong) match {
      case Some(snapshotId) if !props.contains(HWM_SEQ_PROP) =>
        Option(table.snapshot(snapshotId)).map(_.sequenceNumber()).getOrElse(0L)
      case _ => config.hwmSeq
    }

    metrics += row("layout_id", layout.id.toString)
    metrics += row("hwm_seq_before", hwmBefore.toString)

    val action = SparkActions.get(spark).rewriteDataFiles(table)
    val rewrite = config.sortMode match {
      case "zorder" => action.zOrder(config.keys: _*)
      case _ =>
        val order = SortOrder.builderFor(table.schema())
        config.keys.foreach(key => order.asc(key))
        action.sort(order.build())
    }

    // Selection: never a file already carrying the current layout; incremental additionally skips
    // everything at or below the watermark, which leaves files stamped with an older layout (they
    // are all below it) to FULL.
    rewrite
      .option("min-input-files", "1")
      .option("rewrite-all", "true")
      .option("partial-progress.enabled", "true")
      .option("partial-progress.max-commits", config.maxCommits.toString)
      .option(EXCLUDE_SORT_ORDER_IDS, layout.id.toString)
      .option(USE_MAX_INPUT_SEQUENCE_NUMBER, "true")
      .option(OUTPUT_SORT_ORDER_ID, layout.id.toString)
      .option(OUTPUT_FILE_METADATA_PREFIX + FILE_LAYOUT_ID_KEY, layout.id.toString)
      .option(OUTPUT_FILE_METADATA_PREFIX + FILE_LAYOUT_KEY, layoutToJson(layout))
    if (!full && hwmBefore > 0) {
      rewrite.option(MIN_DATA_SEQUENCE_NUMBER, hwmBefore.toString)
    }

    val result = rewrite.execute()
    metrics += row("files_rewritten", result.rewrittenDataFilesCount().toString)
    metrics += row("bytes_rewritten", result.rewrittenBytesCount().toString)
    metrics += row("files_failed", result.failedDataFilesCount().toString)

    // The watermark is what the stamps say was consumed: every stamped file was committed with the
    // data sequence number of the newest input it replaced, so the newest stamped file marks the
    // highest sequence any run has clustered.
    table.refresh()
    val hwmAfter = math.max(hwmBefore, maxStampedSequence(qualifiedTableName, layout.id))
    metrics += row("hwm_seq_after", hwmAfter.toString)

    val epochs = if (result.rewrittenDataFilesCount() > 0) {
      OptimizeTable.advanceEpochs(config.epochs, layout.id, hwmBefore, hwmAfter, full)
    } else {
      config.epochs
    }

    // Persist every piece of clustering state in one atomic alterTable so they never disagree,
    // and drop the properties of the design this replaces.
    val changes = Seq(
      TableChange.setProperty(LAYOUT_ID_PROP, layout.id.toString),
      TableChange.setProperty(LAYOUT_PROP_PREFIX + layout.id, layoutToJson(layout)),
      TableChange.setProperty(HWM_SEQ_PROP, hwmAfter.toString),
      TableChange.setProperty(EPOCHS_PROP, epochsToJson(epochs))) ++
      LEGACY_PROPS.filter(props.contains).map(TableChange.removeProperty)
    catalog.alterTable(ident, changes: _*)
  }

  /** The highest data sequence number among live data files stamped with the layout, or 0. */
  private def maxStampedSequence(qualifiedTableName: String, layoutId: Int): Long = {
    val rows = spark.sql(
      s"""SELECT max(sequence_number) FROM $qualifiedTableName.entries
         |WHERE status < 2 AND data_file.content = 0 AND data_file.sort_order_id = $layoutId
         |""".stripMargin).collect()
    if (rows.isEmpty || rows.head.isNullAt(0)) 0L else rows.head.getLong(0)
  }

  private def requireSequenceNumbers(table: Table): Unit = {
    val formatVersion = table match {
      case ops: HasTableOperations => ops.operations().current().formatVersion()
      case _ => 2
    }
    if (formatVersion < 2) {
      throw new UnsupportedOperationException(
        s"Cannot cluster table '$ident': clustering tracks progress by data sequence number, " +
          s"which format-version 1 tables do not have. Upgrade the table to format-version 2.")
    }
  }

  override def simpleString(maxFields: Int): String = {
    s"OptimizeTableExec: ${catalog} ${ident} full=${full} rewriteManifests=${rewriteManifests}"
  }
}

object OptimizeTableExec {
  /**
   * Rewrite options of the OpenHouse Iceberg fork (openhouse-1.5.2, 1.5.2.22 and later). They are
   * spelled out here so the extension compiles against any 1.5.2 runtime; an older runtime rejects
   * them at execution time with "Cannot use options".
   */
  val MIN_DATA_SEQUENCE_NUMBER = "min-data-sequence-number"
  val EXCLUDE_SORT_ORDER_IDS = "exclude-sort-order-ids"
  val USE_MAX_INPUT_SEQUENCE_NUMBER = "use-max-input-sequence-number"
  val OUTPUT_SORT_ORDER_ID = "output-sort-order-id"
  val OUTPUT_FILE_METADATA_PREFIX = "output-file-metadata."
}
