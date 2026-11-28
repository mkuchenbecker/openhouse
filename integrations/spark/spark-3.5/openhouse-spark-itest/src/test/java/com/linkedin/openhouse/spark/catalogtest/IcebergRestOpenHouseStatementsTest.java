package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.common.security.DummyTokenInterceptor;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Every OpenHouse SQL statement through Iceberg's own {@code SparkCatalog} over an unmodified
 * {@code RESTCatalog} ({@code type=rest}, no OpenHouse catalog class), against the embedded tables
 * service, with the OpenHouse SQL extensions loaded. Each statement is pinned with the outcome it
 * gets, and the table's stored state is checked through the OpenHouse catalog afterwards.
 *
 * <p>Two catalogs point at the same server: {@code irc}, configured as a user would, and {@code
 * irc_adv}, which also sets {@code openhouse.capabilities=policies,grants,unlock} on the client
 * side. The server's own config override ({@code policies,grants,unlock}) wins over that client
 * value, so the two behave the same. Grants and unlock reach the server through the REST capability
 * client; this fixture's authorization handler stores no grants, so SHOW GRANTS is empty here as on
 * the OpenHouse catalog (smoke test s17). {@code IcebergRestGrantsUnlockSqlTest} compares every
 * outcome and the stored grants and locks with the OpenHouse catalog's.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class IcebergRestOpenHouseStatementsTest extends OpenHouseSparkITest {
  private static final String DB = "irc_oh_sql";
  private static final String TABLE = "t";
  private static final String PLAIN = "irc";
  private static final String ADVERTISED = "irc_adv";
  private static final String UNLOCK_ENABLED = "spark.sql.openhouse.unlock.enabled";
  private static final String POLICY_KEY = "updated.openhouse.policy";

  private SparkSession spark;
  private Catalog openhouse;

  @BeforeAll
  void start() throws Exception {
    spark = getSparkSession();
    openhouse = getOpenHouseCatalog(spark);
    for (String catalog : Arrays.asList(PLAIN, ADVERTISED)) {
      String prefix = "spark.sql.catalog." + catalog;
      spark.conf().set(prefix, "org.apache.iceberg.spark.SparkCatalog");
      spark.conf().set(prefix + ".type", "rest");
      spark.conf().set(prefix + ".uri", getOpenHouseLocalServerURI() + "/iceberg");
      spark
          .conf()
          .set(
              prefix + ".token",
              new DummyTokenInterceptor.DummySecurityJWT("irc_oh_sql").buildNoopJWT());
    }
    spark
        .conf()
        .set(
            "spark.sql.catalog." + ADVERTISED + ".openhouse.capabilities",
            "policies,grants,unlock");
    try {
      openhouse.dropTable(TableIdentifier.of(DB, TABLE));
    } catch (RuntimeException ignored) {
      // not there
    }
    spark.sql(
        "CREATE TABLE openhouse."
            + DB
            + "."
            + TABLE
            + " (id bigint, name string, ts timestamp) PARTITIONED BY (days(ts))");
  }

  private static String t(String catalog) {
    return catalog + "." + DB + "." + TABLE;
  }

  private Table viaOpenHouse() {
    return openhouse.loadTable(TableIdentifier.of(DB, TABLE));
  }

  /** "ok", or the failure's class and message, as the statement's outcome. */
  private String outcome(String sql) {
    try {
      spark.sql(sql).collectAsList();
      return "ok";
    } catch (Throwable failure) {
      return failure.getClass().getName() + ": " + failure.getMessage();
    }
  }

  /** The policy statements, each against catalog {@code %s}. */
  private static Map<String, String> policyStatements(String catalog) {
    Map<String, String> statements = new LinkedHashMap<>();
    statements.put("retention", "ALTER TABLE " + t(catalog) + " SET POLICY (RETENTION=30d)");
    statements.put("sharing", "ALTER TABLE " + t(catalog) + " SET POLICY (SHARING=TRUE)");
    statements.put(
        "history", "ALTER TABLE " + t(catalog) + " SET POLICY (HISTORY MAX_AGE=3D VERSIONS=5)");
    statements.put(
        "replication",
        "ALTER TABLE "
            + t(catalog)
            + " SET POLICY (REPLICATION=({destination:'a', interval:12H}))");
    statements.put(
        "unset replication", "ALTER TABLE " + t(catalog) + " UNSET POLICY (REPLICATION)");
    statements.put(
        "column tag", "ALTER TABLE " + t(catalog) + " MODIFY COLUMN name SET TAG = (PII)");
    return statements;
  }

  /** The grant statements, each against catalog {@code %s}. */
  private static Map<String, String> grantStatements(String catalog) {
    Map<String, String> statements = new LinkedHashMap<>();
    statements.put("grant table", "GRANT SELECT ON TABLE " + t(catalog) + " TO smoke_user");
    statements.put("revoke table", "REVOKE SELECT ON TABLE " + t(catalog) + " FROM smoke_user");
    statements.put(
        "grant database",
        "GRANT CREATE TABLE ON DATABASE " + catalog + "." + DB + " TO smoke_user");
    statements.put(
        "revoke database",
        "REVOKE CREATE TABLE ON DATABASE " + catalog + "." + DB + " FROM smoke_user");
    statements.put("show grants table", "SHOW GRANTS ON TABLE " + t(catalog));
    statements.put("show grants database", "SHOW GRANTS ON DATABASE " + catalog + "." + DB);
    return statements;
  }

  private void assertGrants(String catalog) {
    for (Map.Entry<String, String> statement : grantStatements(catalog).entrySet()) {
      Assertions.assertEquals(
          "ok", outcome(statement.getValue()), statement.getKey() + " on " + catalog);
    }
  }

  private void assertUnlock(String catalog) {
    spark.conf().unset(UNLOCK_ENABLED);
    Assertions.assertEquals(
        "java.lang.UnsupportedOperationException: ALTER TABLE UNLOCK is disabled. Set "
            + UNLOCK_ENABLED
            + "=true to enable it.",
        outcome("ALTER TABLE " + t(catalog) + " UNLOCK"));
    spark.conf().set(UNLOCK_ENABLED, "true");
    try {
      // The table holds no lock: the server removes nothing, with either reason.
      for (String suffix : Arrays.asList("", " REASON SYSTEM_ONLY")) {
        Assertions.assertEquals(
            "ok", outcome("ALTER TABLE " + t(catalog) + " UNLOCK" + suffix), catalog + suffix);
      }
    } finally {
      spark.conf().unset(UNLOCK_ENABLED);
    }
  }

  @Test
  void policyStatementsApplyWhereTheServerAdvertisesPolicies() {
    // This server advertises openhouse.capabilities=policies,grants,unlock in its config, which
    // overrides the client-side value of irc_adv: on both catalogs the resolver offers policies,
    // the statement sends its patch, and the server applies it as a policy change. A server that
    // does not advertise policies is refused in the client before anything is sent
    // (OpenHouseCapabilitiesTest, which controls the config response).
    for (String catalog : Arrays.asList(PLAIN, ADVERTISED)) {
      for (Map.Entry<String, String> statement : policyStatements(catalog).entrySet()) {
        Assertions.assertEquals(
            "ok", outcome(statement.getValue()), statement.getKey() + " on " + catalog);
        Assertions.assertFalse(
            viaOpenHouse().properties().containsKey(POLICY_KEY), statement.getKey());
      }
    }
    String policies = viaOpenHouse().properties().get("policies");
    for (String applied :
        Arrays.asList(
            "\"retention\"", "\"history\"", "\"columnTags\"", "\"sharingEnabled\": true")) {
      Assertions.assertTrue(policies.contains(applied), applied + " in " + policies);
    }
  }

  @Test
  void grantStatementsReachTheServerThroughTheCapabilityClient() {
    // A grant needs a shared table; sharing is set through the OpenHouse catalog so this case does
    // not depend on the policy case's order.
    Assertions.assertEquals(
        "ok", outcome("ALTER TABLE openhouse." + DB + "." + TABLE + " SET POLICY (SHARING=TRUE)"));
    assertGrants(PLAIN);
    assertGrants(ADVERTISED);
  }

  @Test
  void unlockReachesTheServerThroughTheCapabilityClient() {
    assertUnlock(PLAIN);
    assertUnlock(ADVERTISED);
  }

  @Test
  void theSameTableThroughTheOpenHouseCatalogStillTakesEveryStatement() {
    // Control: the statements themselves are valid; only the catalog path differs.
    Assertions.assertEquals(
        "ok", outcome("ALTER TABLE openhouse." + DB + "." + TABLE + " SET POLICY (SHARING=TRUE)"));
    Assertions.assertEquals(
        "ok", outcome("GRANT SELECT ON TABLE openhouse." + DB + "." + TABLE + " TO smoke_user"));
    Assertions.assertTrue(
        viaOpenHouse().properties().get("policies").contains("\"sharingEnabled\": true"),
        viaOpenHouse().properties().toString());
  }
}
