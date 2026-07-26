package com.linkedin.openhouse.spark.sql.execution.datasources.v2

import java.time.{Duration, Instant, ZoneId}
import java.time.format.DateTimeFormatter

import scala.collection.JavaConverters._

import org.apache.iceberg.spark.source.SparkTable
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Attribute, GenericInternalRow}
import org.apache.spark.sql.catalyst.util.quoteIfNeeded
import org.apache.spark.sql.connector.catalog.{Identifier, TableCatalog}
import org.apache.spark.sql.execution.datasources.v2.LeafV2CommandExec
import org.apache.spark.unsafe.types.UTF8String

/**
 * Runs Iceberg table maintenance for the VACUUM command as thin sugar over the catalog's stored
 * procedures, using the same table-property contract as the scheduled maintenance jobs (see
 * [[MaintenanceProperties]]). VACUUM is an '''Alpha''' feature and is opt-in per table via the
 * `maintenance.vacuum.enabled` property.
 *
 * When `REMOVE ORPHAN FILES` is given, orphan-file deletion runs first (it only removes
 * unreferenced files from storage, so it works even when the table is out of quota, unlike snapshot
 * expiration which commits metadata); snapshot expiration always runs afterwards.
 *
 * `RETAIN n HOURS` bounds both operations. When it is omitted, each falls back to what the
 * corresponding job would have used for this table: the `policies.history` window for expiration,
 * and the orphan-file job's own default (or one day, under `ofd.one_day_ttl.enabled`) for orphan
 * removal. The one deliberate divergence from the jobs is that expiration here also deletes the
 * files the expired snapshots exclusively referenced -- reclaiming that storage is the point of
 * running VACUUM by hand, whereas the scheduled job leaves it to orphan-file deletion.
 */
case class VacuumTableExec(
  output: Seq[Attribute],
  spark: SparkSession,
  catalog: TableCatalog,
  ident: Identifier,
  removeOrphanFiles: Boolean,
  retainHours: Option[Int]) extends LeafV2CommandExec {

  import MaintenanceProperties._

  private def row(metric: String, value: String): InternalRow =
    new GenericInternalRow(
      Array[Any](UTF8String.fromString(metric), UTF8String.fromString(value)))

  override protected def run(): Seq[InternalRow] = {
    val props = catalog.loadTable(ident) match {
      case iceberg: SparkTable if iceberg.table().properties().containsKey(TABLE_ID_PROP) =>
        iceberg.table().properties().asScala.toMap
      case table =>
        throw new UnsupportedOperationException(s"Cannot vacuum non-Openhouse table: $table")
    }

    // VACUUM is an Alpha feature and is opt-in per table. The gate lives in the `maintenance.*`
    // namespace because `openhouse.*` keys are preserved -- the /tables service rejects any attempt
    // to set them -- so an `openhouse.`-prefixed gate could never be turned on.
    if (!"true".equalsIgnoreCase(props.getOrElse(VACUUM_ENABLED_PROP, ""))) {
      throw new UnsupportedOperationException(
        s"VACUUM is an Alpha feature and must be enabled on the table before use. Enable it " +
          s"with: ALTER TABLE <table> SET TBLPROPERTIES ('$VACUUM_ENABLED_PROP' = 'true').")
    }

    // The scheduled snapshot-expiration job runs on primary tables only, so neither does VACUUM.
    // A replica's snapshots are the replication protocol's state; expiring them by hand can strand
    // an incremental replication mid-stream. Orphan cleanup for replicas stays with the scheduled
    // orphan-file job, which applies its own replica-specific floor.
    if (isReplica(props)) {
      throw new UnsupportedOperationException(
        s"Cannot vacuum replica table '$ident': snapshot expiration is not run on replica " +
          s"tables. Maintenance for replicas is handled by the scheduled jobs.")
    }

    requireMaintenanceEnabled(props, SNAPSHOTS_EXPIRATION_JOB)
    if (removeOrphanFiles) {
      requireMaintenanceEnabled(props, ORPHAN_FILES_DELETION_JOB)
    }

    val quotedCatalog = quoteIfNeeded(catalog.name())
    val tableArg = (ident.namespace() :+ ident.name()).map(quoteIfNeeded).mkString(".")
    val metrics = Seq.newBuilder[InternalRow]

    if (removeOrphanFiles) {
      // Orphan-file deletion runs BEFORE expiration. Snapshot expiration commits table metadata, so
      // it cannot run on a table that is out of quota; orphan-file deletion only removes
      // unreferenced files from storage and always can, so doing it first ensures it still runs in
      // that case. Running first also means it scans against the pre-expiration referenced-file
      // set, so it can never delete a file that a still-live snapshot references.
      //
      // On a table configured for orphan backups the scheduled job moves orphans into the backup
      // directory instead of deleting them, via a delete hook the stored procedure has no
      // equivalent of. Running the procedure would both destroy files the platform expects to
      // remain recoverable and treat the backup directory's own contents as orphans, so refuse.
      if (isBackupConfigured(props)) {
        throw new UnsupportedOperationException(
          s"Cannot remove orphan files on table '$ident': it is configured for orphan backups " +
            s"('$BACKUP_ENABLED_PROP'/'$BACKUP_DIR_PROP'), which preserve orphans instead of " +
            s"deleting them. Leave orphan-file cleanup to the scheduled job, or run VACUUM " +
            s"without REMOVE ORPHAN FILES.")
      }
      val (age, source) = orphanRetention(props, retainHours)
      metrics += row("orphan_files_retain_hours", age.toHours.toString)
      metrics += row("orphan_files_retain_source", source)
      spark.sql(
        s"CALL $quotedCatalog.system.remove_orphan_files(" +
          s"table => '$tableArg'${olderThanArg(age)})").collect()
    }

    // Snapshot expiration always runs. An explicit RETAIN overrides the age the history policy
    // configures, but not its `versions` cap: that is a separate policy dimension, and the
    // scheduled job applies it independently of the age.
    val configured = snapshotRetention(props)
    val retention = retainHours
      .map(h => configured.copy(age = Duration.ofHours(h.toLong), source = "RETAIN"))
      .getOrElse(configured)
    metrics += row("snapshots_retain_hours", retention.age.toHours.toString)
    metrics += row("snapshots_retain_source", retention.source)
    spark.sql(
      s"CALL $quotedCatalog.system.expire_snapshots(" +
        s"table => '$tableArg'${olderThanArg(retention.age)})").collect()

    // A `versions` history policy caps how many snapshots survive regardless of age. The job
    // applies it as a second, separate expiration; mirror that rather than folding it into the
    // call above, where `retain_last` would instead act as a floor on the age-based expiry.
    retention.versions.foreach { versions =>
      metrics += row("snapshots_retain_last", versions.toString)
      spark.sql(
        s"CALL $quotedCatalog.system.expire_snapshots(" +
          s"table => '$tableArg'${olderThanArg(Duration.ZERO)}, retain_last => $versions)").collect()
    }

    metrics.result()
  }

  /**
   * Render an `older_than` argument for a retention window. Procedure arguments must be foldable,
   * so the window is resolved here to a literal timestamp rather than an expression over
   * `current_timestamp()`. The literal is rendered in the session time zone because the CALL's
   * `TIMESTAMP '...'` literal is parsed back in that same zone, so the round-trip preserves the
   * intended instant.
   */
  private def olderThanArg(age: Duration): String = {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
      .withZone(ZoneId.of(spark.sessionState.conf.sessionLocalTimeZone))
    s", older_than => TIMESTAMP '${formatter.format(Instant.now().minus(age))}'"
  }

  private def requireMaintenanceEnabled(props: Map[String, String], jobType: String): Unit = {
    if (isMaintenanceDisabled(props, jobType)) {
      throw new UnsupportedOperationException(
        s"Maintenance is disabled for table '$ident' ('maintenance.disabled' or " +
          s"'maintenance.$jobType.disabled'), so VACUUM will not run $jobType on it.")
    }
  }

  override def simpleString(maxFields: Int): String = {
    s"VacuumTableExec: ${catalog} ${ident} removeOrphanFiles=${removeOrphanFiles} " +
      s"retainHours=${retainHours.getOrElse("default")}"
  }
}
