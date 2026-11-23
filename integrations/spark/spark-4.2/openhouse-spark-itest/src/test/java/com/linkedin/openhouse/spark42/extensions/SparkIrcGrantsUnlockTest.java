package com.linkedin.openhouse.spark42.extensions;

import static com.linkedin.openhouse.spark42.extensions.ExtensionsSpark.IRC;
import static com.linkedin.openhouse.spark42.extensions.ExtensionsSpark.IRC_STRANGER;
import static com.linkedin.openhouse.spark42.extensions.ExtensionsTestService.DB;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.spark.sql.Row;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * GRANT, REVOKE, SHOW GRANTS and UNLOCK from Spark 4.2 with the OpenHouse SQL extensions over
 * Iceberg's own {@code RESTCatalog}, through the REST capability client: the same statements, on
 * tables of the same names and set-up, as the Spark 3.5 itest's {@code
 * IcebergRestGrantsUnlockSqlTest}, which compares each one with the OpenHouse catalog. Every
 * outcome pinned here is the exact string that test pins for Spark 3.5 over {@code RESTCatalog}
 * (the database written {@code {db}}), and after each statement the stored grants and locks, read
 * back through the service's control API, are the ones the 3.5 test reads.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SparkIrcGrantsUnlockTest {
  private static final String UNLOCK_ENABLED = "spark.sql.openhouse.unlock.enabled";
  private static final String SHARED = "{\"sharingEnabled\": true}";

  private ExtensionsTestService service;

  @BeforeAll
  void createTables() throws Exception {
    service = ExtensionsTestService.get();
    service.create("shared", false, SHARED);
    service.create("unshared", false);
    for (String table :
        Arrays.asList(
            "grant_locked",
            "legacy_lock",
            "legacy_lock_by_reason",
            "legacy_lock_wrong_reason",
            "denied_lock")) {
      service.create(table, false, SHARED);
      service.lock(table, null);
    }
    for (String table : Arrays.asList("system_lock", "system_lock_wrong_reason")) {
      service.create(table, false, SHARED);
      service.lock(table, "SYSTEM_ONLY");
    }
    service.create("unlocked", false, SHARED);
  }

  /** "ok" plus the rows, or the failure's class and message, with the database written {db}. */
  private static String outcome(String template, boolean stranger) throws Exception {
    String sql = template.replace("{cat}", stranger ? IRC_STRANGER : IRC).replace("{db}", DB);
    String outcome;
    try {
      List<String> rows =
          ExtensionsSpark.session().sql(sql).collectAsList().stream()
              .map(Row::toString)
              .collect(Collectors.toList());
      outcome = rows.isEmpty() ? "ok" : "ok " + rows;
    } catch (Throwable failure) {
      outcome = failure.getClass().getName() + ": " + failure.getMessage();
    }
    return outcome.replace(DB, "{db}");
  }

  private static String unlock(String template, boolean stranger) throws Exception {
    ExtensionsSpark.session().conf().set(UNLOCK_ENABLED, "true");
    try {
      return outcome(template, stranger);
    } finally {
      ExtensionsSpark.session().conf().unset(UNLOCK_ENABLED);
    }
  }

  // ------------------------------------------------------------------ grants

  @Test
  void grantShowAndRevokeOnATable() throws Exception {
    Assertions.assertEquals(
        "ok", outcome("GRANT SELECT ON TABLE {cat}.{db}.shared TO gu_reader", false));
    Assertions.assertEquals(
        "ok", outcome("GRANT ALTER ON TABLE {cat}.{db}.shared TO PUBLIC", false));
    Assertions.assertEquals(
        "ok", outcome("GRANT MANAGE GRANTS ON TABLE {cat}.{db}.shared TO gu_admin", false));
    Assertions.assertEquals(
        Arrays.asList("gu_reader=TABLE_VIEWER", "*=TABLE_ADMIN", "gu_admin=ACL_EDITOR"),
        service.acls(DB + ".shared"));
    Assertions.assertEquals(
        "ok [[SELECT,gu_reader], [ALTER,PUBLIC], [MANAGE GRANTS,gu_admin]]",
        outcome("SHOW GRANTS ON TABLE {cat}.{db}.shared", false));
    Assertions.assertEquals(
        "ok [[SELECT,gu_reader], [ALTER,PUBLIC], [MANAGE GRANTS,gu_admin]]",
        outcome("SHOW GRANTS ON TABLE {cat}.{db}.shared", true));
    Assertions.assertEquals(
        "ok", outcome("REVOKE SELECT ON TABLE {cat}.{db}.shared FROM gu_reader", false));
    Assertions.assertEquals(
        "ok", outcome("REVOKE ALTER ON TABLE {cat}.{db}.shared FROM public", false));
    Assertions.assertEquals(
        Collections.singletonList("gu_admin=ACL_EDITOR"), service.acls(DB + ".shared"));
    Assertions.assertEquals(
        "ok [[MANAGE GRANTS,gu_admin]]", outcome("SHOW GRANTS ON TABLE {cat}.{db}.shared", false));
  }

  @Test
  void grantShowAndRevokeOnADatabaseWithTablesAndOnOneWithNone() throws Exception {
    Assertions.assertEquals(
        "ok", outcome("GRANT CREATE TABLE ON DATABASE {cat}.{db} TO gu_creator", false));
    Assertions.assertEquals(
        "ok [[CREATE TABLE,gu_creator]]", outcome("SHOW GRANTS ON DATABASE {cat}.{db}", false));
    Assertions.assertEquals(
        "ok", outcome("REVOKE CREATE TABLE ON DATABASE {cat}.{db} FROM gu_creator", false));
    Assertions.assertEquals("ok", outcome("SHOW GRANTS ON DATABASE {cat}.{db}", false));
    Assertions.assertEquals(Collections.emptyList(), service.acls(DB));

    Assertions.assertEquals(
        "ok", outcome("GRANT CREATE TABLE ON DATABASE {cat}.{db}_new TO gu_creator", false));
    Assertions.assertEquals(
        Collections.singletonList("gu_creator=TABLE_CREATOR"), service.acls(DB + "_new"));
    Assertions.assertEquals(
        "ok [[CREATE TABLE,gu_creator]]", outcome("SHOW GRANTS ON DATABASE {cat}.{db}_new", false));
    Assertions.assertEquals(
        "ok", outcome("REVOKE CREATE TABLE ON DATABASE {cat}.{db}_new FROM gu_creator", false));
    Assertions.assertEquals(Collections.emptyList(), service.acls(DB + "_new"));
  }

  @Test
  void aTableThatIsNotSharedRefusesAGrant() throws Exception {
    Assertions.assertEquals(
        "java.lang.IllegalArgumentException: {db}.unshared is not a shared table",
        outcome("GRANT SELECT ON TABLE {cat}.{db}.unshared TO gu_reader", false));
    Assertions.assertEquals(Collections.emptyList(), service.acls(DB + ".unshared"));
  }

  @Test
  void aLockedTableRefusesAGrantButNotARevoke() throws Exception {
    Assertions.assertEquals(
        "java.lang.IllegalArgumentException: {db}.grant_locked is in locked state and grants are"
            + " not allowed for sharing",
        outcome("GRANT SELECT ON TABLE {cat}.{db}.grant_locked TO gu_reader", false));
    Assertions.assertEquals(
        "ok", outcome("REVOKE SELECT ON TABLE {cat}.{db}.grant_locked FROM gu_reader", false));
    Assertions.assertEquals(Collections.emptyList(), service.acls(DB + ".grant_locked"));
  }

  @Test
  void aDeniedPrincipalIsForbidden() throws Exception {
    String table = "Operation on table {db}.shared failed as user irc_stranger is unauthorized";
    List<String> before = service.acls(DB + ".shared");
    Assertions.assertEquals(
        "org.apache.iceberg.exceptions.ForbiddenException: Forbidden: " + table,
        outcome("GRANT SELECT ON TABLE {cat}.{db}.shared TO gu_reader", true));
    Assertions.assertEquals(
        "org.apache.iceberg.exceptions.ForbiddenException: Forbidden: " + table,
        outcome("REVOKE SELECT ON TABLE {cat}.{db}.shared FROM gu_reader", true));
    Assertions.assertEquals(before, service.acls(DB + ".shared"));
    Assertions.assertEquals(
        "org.apache.iceberg.exceptions.ForbiddenException: Forbidden: Operation on database [{db}]"
            + " failed as user [irc_stranger] is unauthorized",
        outcome("GRANT CREATE TABLE ON DATABASE {cat}.{db} TO gu_reader", true));
  }

  @Test
  void aMissingTableIsNoSuchTable() throws Exception {
    String message =
        "org.apache.iceberg.exceptions.NoSuchTableException: User table {db}.missing cannot be"
            + " found";
    Assertions.assertEquals(
        message, outcome("GRANT SELECT ON TABLE {cat}.{db}.missing TO gu_reader", false));
    Assertions.assertEquals(message, outcome("SHOW GRANTS ON TABLE {cat}.{db}.missing", false));
  }

  @Test
  void aMultiLevelNamespaceIsRefusedBeforeAnyRequest() throws Exception {
    String message =
        "org.apache.iceberg.exceptions.ValidationException: Input namespace has more than one"
            + " levels {db}.x";
    Assertions.assertEquals(
        message, outcome("GRANT SELECT ON TABLE {cat}.{db}.x.t TO gu_reader", false));
    Assertions.assertEquals(message, outcome("SHOW GRANTS ON TABLE {cat}.{db}.x.t", false));
  }

  // ------------------------------------------------------------------ unlock

  @Test
  void unlockIsDisabledUnlessSwitchedOn() throws Exception {
    ExtensionsSpark.session().conf().unset(UNLOCK_ENABLED);
    Assertions.assertEquals(
        "java.lang.UnsupportedOperationException: ALTER TABLE UNLOCK is disabled. Set "
            + UNLOCK_ENABLED
            + "=true to enable it.",
        outcome("ALTER TABLE {cat}.{db}.grant_locked UNLOCK", false));
    Assertions.assertEquals("locked LEGACY", service.lockState("grant_locked"));
  }

  @Test
  void unlockRemovesTheLockItNames() throws Exception {
    Assertions.assertEquals("ok", unlock("ALTER TABLE {cat}.{db}.legacy_lock UNLOCK", false));
    Assertions.assertEquals("unlocked", service.lockState("legacy_lock"));
    Assertions.assertEquals(
        "ok", unlock("ALTER TABLE {cat}.{db}.legacy_lock_by_reason UNLOCK REASON Legacy", false));
    Assertions.assertEquals("unlocked", service.lockState("legacy_lock_by_reason"));
    Assertions.assertEquals(
        "ok", unlock("ALTER TABLE {cat}.{db}.system_lock UNLOCK REASON system_only", false));
    Assertions.assertEquals("unlocked", service.lockState("system_lock"));
    Assertions.assertEquals("ok", unlock("ALTER TABLE {cat}.{db}.unlocked UNLOCK", false));
    Assertions.assertEquals(
        "ok", unlock("ALTER TABLE {cat}.{db}.unlocked UNLOCK REASON SYSTEM_ONLY", false));
    Assertions.assertEquals("unlocked", service.lockState("unlocked"));
  }

  @Test
  void unlockWithTheWrongReasonIsALockConflictAndKeepsTheLock() throws Exception {
    Assertions.assertEquals(
        "java.lang.IllegalStateException: Table {db}.system_lock_wrong_reason has a SYSTEM_ONLY"
            + " lock. Remove it with reason SYSTEM_ONLY.",
        unlock("ALTER TABLE {cat}.{db}.system_lock_wrong_reason UNLOCK", false));
    Assertions.assertEquals("locked SYSTEM_ONLY", service.lockState("system_lock_wrong_reason"));
    Assertions.assertEquals(
        "java.lang.IllegalStateException: Table {db}.legacy_lock_wrong_reason has a LEGACY lock,"
            + " not SYSTEM_ONLY.",
        unlock("ALTER TABLE {cat}.{db}.legacy_lock_wrong_reason UNLOCK REASON SYSTEM_ONLY", false));
    Assertions.assertEquals("locked LEGACY", service.lockState("legacy_lock_wrong_reason"));
  }

  @Test
  void unlockByADeniedPrincipalIsForbiddenAndKeepsTheLock() throws Exception {
    Assertions.assertEquals(
        "org.apache.iceberg.exceptions.ForbiddenException: Forbidden: Operation on table"
            + " {db}.denied_lock failed as user irc_stranger is unauthorized to act on Locked"
            + " table",
        unlock("ALTER TABLE {cat}.{db}.denied_lock UNLOCK", true));
    Assertions.assertEquals("locked LEGACY", service.lockState("denied_lock"));
  }

  @Test
  void unlockOfAMissingTableIsNoSuchTable() throws Exception {
    Assertions.assertEquals(
        "org.apache.iceberg.exceptions.NoSuchTableException: User table {db}.missing cannot be"
            + " found",
        unlock("ALTER TABLE {cat}.{db}.missing UNLOCK", false));
  }

  @Test
  void anUnknownReasonIsAParseError() throws Exception {
    String outcome = unlock("ALTER TABLE {cat}.{db}.unlocked UNLOCK REASON BOGUS", false);
    Assertions.assertTrue(
        outcome.startsWith(
                "com.linkedin.openhouse.spark.sql.catalyst.parser.extensions.OpenhouseParseException")
            && outcome.contains("BOGUS"),
        outcome);
  }
}
