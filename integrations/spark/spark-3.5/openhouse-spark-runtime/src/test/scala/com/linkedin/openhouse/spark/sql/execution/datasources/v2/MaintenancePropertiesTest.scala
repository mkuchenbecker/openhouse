package com.linkedin.openhouse.spark.sql.execution.datasources.v2

import java.time.Duration

import org.junit.jupiter.api.Assertions.{assertEquals, assertFalse, assertThrows, assertTrue}
import org.junit.jupiter.api.Test

import com.linkedin.openhouse.spark.sql.execution.datasources.v2.MaintenanceProperties._

/**
 * Pins the property contract the interactive maintenance DDL shares with the scheduled jobs. The
 * expected values here are the job sources' own: a change that breaks one of these tests means the
 * DDL and the job would disagree about the same table.
 */
class MaintenancePropertiesTest {

  private def policies(history: String): Map[String, String] =
    Map(POLICIES_PROP -> s"""{"retention":{"count":1,"granularity":"DAY"},"history":$history}""")

  @Test
  def snapshotRetentionFallsBackToTheSnapshotExpirationJobDefault(): Unit = {
    // SnapshotsExpirationSparkApp enforces a 3-day TTL even when the table has no history policy.
    Seq(Map.empty[String, String], Map(POLICIES_PROP -> ""), policies("""{"versions":0}"""))
      .foreach { props =>
        val retention = snapshotRetention(props)
        assertEquals(Duration.ofDays(3), retention.age)
        assertTrue(retention.versions.isEmpty)
        assertEquals("default (3 DAY)", retention.source)
      }
  }

  @Test
  def snapshotRetentionUsesTheHistoryPolicyMaxAgeAndGranularity(): Unit = {
    assertEquals(Duration.ofDays(5),
      snapshotRetention(policies("""{"maxAge":5,"granularity":"DAY"}""")).age)
    assertEquals(Duration.ofHours(12),
      snapshotRetention(policies("""{"maxAge":12,"granularity":"HOUR"}""")).age)
    // SparkJobUtil.convertGranularityToChrono also accepts an already-ChronoUnit granularity, as
    // the expiration job's own default produces.
    assertEquals(Duration.ofDays(2),
      snapshotRetention(policies("""{"maxAge":2,"granularity":"DAYS"}""")).age)
  }

  @Test
  def snapshotRetentionGranularityIsCaseInsensitiveAndDefaultsToDays(): Unit = {
    assertEquals(Duration.ofDays(4),
      snapshotRetention(policies("""{"maxAge":4,"granularity":"day"}""")).age)
    assertEquals(Duration.ofDays(4), snapshotRetention(policies("""{"maxAge":4}""")).age)
  }

  @Test
  def snapshotRetentionCarriesTheVersionsCapOnlyWhenSet(): Unit = {
    val capped = snapshotRetention(policies("""{"maxAge":1,"granularity":"DAY","versions":10}"""))
    assertEquals(Some(10), capped.versions)
    assertEquals("policies.history (1 DAY)", capped.source)
    assertTrue(
      snapshotRetention(policies("""{"maxAge":1,"granularity":"DAY","versions":0}""")).versions
        .isEmpty)
  }

  @Test
  def malformedPoliciesFailsRatherThanSilentlyReadingAsNoPolicy(): Unit = {
    val e = assertThrows(classOf[IllegalStateException],
      () => snapshotRetention(Map(POLICIES_PROP -> "not json")))
    assertTrue(e.getMessage.contains(POLICIES_PROP))
  }

  @Test
  def unknownGranularityFailsLoudly(): Unit = {
    assertThrows(classOf[IllegalStateException],
      () => snapshotRetention(policies("""{"maxAge":1,"granularity":"FORTNIGHT"}""")))
  }

  @Test
  def orphanRetentionMirrorsTheOrphanFilesDeletionJobDefaults(): Unit = {
    assertEquals((Duration.ofDays(7), "default"), orphanRetention(Map.empty, None))
    assertEquals((Duration.ofDays(1), OFD_ONE_DAY_TTL_PROP),
      orphanRetention(Map(OFD_ONE_DAY_TTL_PROP -> "true"), None))
    assertEquals((Duration.ofDays(7), "default"),
      orphanRetention(Map(OFD_ONE_DAY_TTL_PROP -> "false"), None))
  }

  @Test
  def orphanRetentionHonorsAnExplicitRetainEvenBelowTheDefaults(): Unit = {
    assertEquals((Duration.ofHours(1), "RETAIN"),
      orphanRetention(Map(OFD_ONE_DAY_TTL_PROP -> "true"), Some(1)))
  }

  @Test
  def maintenanceIsDisabledWholesaleOrPerJobType(): Unit = {
    assertFalse(isMaintenanceDisabled(Map.empty, SNAPSHOTS_EXPIRATION_JOB))
    assertTrue(isMaintenanceDisabled(Map("maintenance.disabled" -> "true"),
      SNAPSHOTS_EXPIRATION_JOB))
    val perJob = Map(s"maintenance.$ORPHAN_FILES_DELETION_JOB.disabled" -> "true")
    assertTrue(isMaintenanceDisabled(perJob, ORPHAN_FILES_DELETION_JOB))
    assertFalse(isMaintenanceDisabled(perJob, SNAPSHOTS_EXPIRATION_JOB))
  }

  @Test
  def backupIsConfiguredByEitherTheFlagOrTheDirectory(): Unit = {
    assertFalse(isBackupConfigured(Map.empty))
    assertFalse(isBackupConfigured(Map(BACKUP_ENABLED_PROP -> "false", BACKUP_DIR_PROP -> " ")))
    assertTrue(isBackupConfigured(Map(BACKUP_ENABLED_PROP -> "true")))
    assertTrue(isBackupConfigured(Map(BACKUP_DIR_PROP -> ".backup")))
  }

  @Test
  def replicaIsRecognizedFromTheServerWrittenTableType(): Unit = {
    assertFalse(isReplica(Map.empty))
    assertFalse(isReplica(Map(TABLE_TYPE_PROP -> "PRIMARY_TABLE")))
    assertTrue(isReplica(Map(TABLE_TYPE_PROP -> REPLICA_TABLE_TYPE)))
  }
}
