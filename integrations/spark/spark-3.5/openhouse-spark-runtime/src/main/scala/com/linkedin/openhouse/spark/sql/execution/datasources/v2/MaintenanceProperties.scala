package com.linkedin.openhouse.spark.sql.execution.datasources.v2

import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.Locale

import scala.util.control.NonFatal

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * The table-property contract shared by the interactive maintenance DDL and the scheduled
 * OpenHouse maintenance jobs.
 *
 * The jobs and the Spark SQL extensions ship as separate artifacts and cannot share code, so the
 * keys and defaults below are mirrored from the job sources and pinned by
 * `MaintenancePropertiesTest`:
 *
 *  - snapshot expiration (SE) is policy-driven -- `apps/spark/.../jobs/scheduler/tasks/
 *    TableSnapshotsExpirationTask.java` reads `policies.history` and passes it to
 *    `SnapshotsExpirationSparkApp` / `Operations.expireSnapshots`;
 *  - orphan-file deletion (OFD) is not policy-driven -- `apps/spark/.../jobs/spark/
 *    OrphanFilesDeletionSparkApp.java` reads raw table properties (see `AppConstants.java`);
 *  - `maintenance.*` is the user-settable channel the scheduler reads back as
 *    `jobExecutionProperties` (`apps/spark/.../jobs/client/TablesClient.java`), including the
 *    per-table and per-job-type disable switches (`TableMetadata.isMaintenanceJobDisabled`).
 *
 * Note that `openhouse.*` and `policies` are '''preserved''' keys: the /tables service rejects any
 * attempt to set them with `ALTER TABLE ... SET TBLPROPERTIES`. Anything a user is expected to set
 * therefore has to live outside those namespaces -- hence `maintenance.vacuum.enabled` rather than
 * `openhouse.vacuum.enabled`, and `ALTER TABLE ... SET POLICY (HISTORY ...)` rather than a direct
 * write to `policies`.
 */
object MaintenanceProperties {

  /** Present on every OpenHouse table; its absence means the table is not an OpenHouse table. */
  val TABLE_ID_PROP = "openhouse.tableId"

  /** Server-written JSON holding the table's OpenHouse policies, including `history`. */
  val POLICIES_PROP = "policies"

  /** Server-written table type. The scheduled SE job runs on primary tables only. */
  val TABLE_TYPE_PROP = "openhouse.tableType"
  val REPLICA_TABLE_TYPE = "REPLICA_TABLE"

  /** Opts a table into the Alpha VACUUM command. Must not be `openhouse.`-prefixed (preserved). */
  val VACUUM_ENABLED_PROP = "maintenance.vacuum.enabled"

  /** OFD: forces a one-day orphan window when set to `true`. */
  val OFD_ONE_DAY_TTL_PROP = "ofd.one_day_ttl.enabled"

  /** OFD: when backups are on, the job moves orphans aside instead of deleting them. */
  val BACKUP_ENABLED_PROP = "retention.backup.enabled"
  val BACKUP_DIR_PROP = "retention.backup.dir"

  /** Job types, as named by `JobConf.JobTypeEnum` in the disable switches. */
  val SNAPSHOTS_EXPIRATION_JOB = "SNAPSHOTS_EXPIRATION"
  val ORPHAN_FILES_DELETION_JOB = "ORPHAN_FILES_DELETION"
  val DATA_COMPACTION_JOB = "DATA_COMPACTION"

  /** `SnapshotsExpirationSparkApp.DEFAULT_CONFIGURATION`: a 3-day TTL is enforced even unset. */
  val DEFAULT_HISTORY_MAX_AGE = 3
  val DEFAULT_HISTORY_GRANULARITY = "DAY"

  /**
   * `OrphanFilesDeletionSparkApp.createApp`: a 7-day default window, which that job additionally
   * floors at one day because its window arrives as a CLI argument. Here the default is fixed, so
   * the floor is only reachable through an explicit `RETAIN`, which is the operator's own call.
   */
  val DEFAULT_ORPHAN_TTL: Duration = Duration.ofDays(7)
  val ONE_DAY_ORPHAN_TTL: Duration = Duration.ofDays(1)

  private val policiesMapper = new ObjectMapper()

  /**
   * The snapshot-expiration window a table is configured for.
   *
   * @param age      snapshots older than this are expired
   * @param versions retain at most this many snapshots, when the policy sets it (`versions > 0`)
   * @param source   where the window came from, for the command's output row
   */
  case class SnapshotRetention(age: Duration, versions: Option[Int], source: String)

  /**
   * Resolve the snapshot-expiration window exactly as the scheduled SE job does: from the table's
   * `policies.history` (`maxAge` x `granularity`, plus `versions`), falling back to the job's own
   * 3-day default when the table has no history policy.
   */
  def snapshotRetention(props: Map[String, String]): SnapshotRetention = {
    val history = policyNode(props, "history")
    val maxAge = history.map(_.path("maxAge").asInt(0)).getOrElse(0)
    val versions = history.map(_.path("versions").asInt(0)).getOrElse(0)
    if (maxAge > 0) {
      val granularity = history.map(_.path("granularity").asText(DEFAULT_HISTORY_GRANULARITY))
        .filter(_.nonEmpty).getOrElse(DEFAULT_HISTORY_GRANULARITY)
      SnapshotRetention(
        granularityUnit(granularity).getDuration.multipliedBy(maxAge.toLong),
        Some(versions).filter(_ > 0),
        s"$POLICIES_PROP.history ($maxAge ${granularity.toUpperCase(Locale.ROOT)})")
    } else {
      SnapshotRetention(
        granularityUnit(DEFAULT_HISTORY_GRANULARITY).getDuration
          .multipliedBy(DEFAULT_HISTORY_MAX_AGE.toLong),
        Some(versions).filter(_ > 0),
        s"default ($DEFAULT_HISTORY_MAX_AGE $DEFAULT_HISTORY_GRANULARITY)")
    }
  }

  /**
   * Resolve the orphan-file window the way `OrphanFilesDeletionSparkApp` does: a 7-day default,
   * dropped to one day by `ofd.one_day_ttl.enabled`. An explicit `RETAIN` is the operator's own
   * call and is used as given, so the documented "lower it to handle an emergency" lever keeps
   * working.
   */
  def orphanRetention(props: Map[String, String], requested: Option[Int]): (Duration, String) =
    requested match {
      case Some(hours) => (Duration.ofHours(hours.toLong), "RETAIN")
      case None if isEnabled(props, OFD_ONE_DAY_TTL_PROP) =>
        (ONE_DAY_ORPHAN_TTL, OFD_ONE_DAY_TTL_PROP)
      case None => (DEFAULT_ORPHAN_TTL, "default")
    }

  /**
   * True when the platform has been told not to run maintenance on this table -- either wholesale
   * (`maintenance.disabled`) or for one job type (`maintenance.<JOB_TYPE>.disabled`). Mirrors
   * `TableMetadata.isMaintenanceJobDisabled`, which the scheduler consults before dispatching.
   */
  def isMaintenanceDisabled(props: Map[String, String], jobType: String): Boolean =
    isEnabled(props, "maintenance.disabled") || isEnabled(props, s"maintenance.$jobType.disabled")

  /**
   * True when the platform is configured to preserve orphans by moving them to a backup directory
   * rather than deleting them (`Operations.deleteOrphanFiles`'s `deleteWith` hook).
   */
  def isBackupConfigured(props: Map[String, String]): Boolean =
    isEnabled(props, BACKUP_ENABLED_PROP) || props.get(BACKUP_DIR_PROP).exists(_.trim.nonEmpty)

  /** True when the table is a replica; the scheduled SE job skips non-primary tables. */
  def isReplica(props: Map[String, String]): Boolean =
    props.get(TABLE_TYPE_PROP).exists(REPLICA_TABLE_TYPE.equalsIgnoreCase)

  private def isEnabled(props: Map[String, String], key: String): Boolean =
    props.get(key).exists("true".equalsIgnoreCase)

  /**
   * One sub-object of the server-written `policies` JSON. An absent or empty property is a table
   * with no policies; a non-empty but unparseable one is corruption that must not be silently read
   * as "no policy", since that would quietly expire snapshots on a different schedule than the one
   * the table is configured for.
   */
  private def policyNode(props: Map[String, String], name: String) = {
    props.get(POLICIES_PROP).map(_.trim).filter(_.nonEmpty).flatMap { json =>
      val root =
        try policiesMapper.readTree(json)
        catch {
          case NonFatal(e) =>
            throw new IllegalStateException(
              s"Malformed '$POLICIES_PROP' table property; cannot resolve the maintenance " +
                s"window the table is configured for. Value was: $json", e)
        }
      Option(root.get(name))
    }
  }

  /**
   * Map an OpenHouse policy granularity to its time unit, mirroring
   * `SparkJobUtil.convertGranularityToChrono`: the `TimePartitionSpec.Granularity` names, with a
   * fallback to the [[ChronoUnit]] name so a granularity already stored as e.g. `DAYS` resolves.
   */
  private def granularityUnit(granularity: String): ChronoUnit =
    granularity.toUpperCase(Locale.ROOT) match {
      case "HOUR" => ChronoUnit.HOURS
      case "DAY" => ChronoUnit.DAYS
      case "MONTH" => ChronoUnit.MONTHS
      case "YEAR" => ChronoUnit.YEARS
      case other =>
        try ChronoUnit.valueOf(other)
        catch {
          case _: IllegalArgumentException =>
            throw new IllegalStateException(
              s"Unrecognized granularity '$granularity' in the '$POLICIES_PROP' history policy.")
        }
    }
}
