package com.linkedin.openhouse.spark42.extensions;

import static com.linkedin.openhouse.spark42.extensions.ExtensionsSpark.IRC;
import static com.linkedin.openhouse.spark42.extensions.ExtensionsSpark.IRC_ADVERTISED;
import static com.linkedin.openhouse.spark42.extensions.ExtensionsSpark.LOCAL;
import static com.linkedin.openhouse.spark42.extensions.ExtensionsSpark.outcome;
import static com.linkedin.openhouse.spark42.extensions.ExtensionsTestService.DB;

import com.linkedin.openhouse.spark.capabilities.CatalogCapabilities;
import com.linkedin.openhouse.spark.capabilities.OpenHouseCapabilities;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The OpenHouse statements other than the policy ones, and the policy statements on a table that is
 * not an OpenHouse table, from Spark 4.2 with the OpenHouse SQL extensions over Iceberg's own
 * {@code RESTCatalog}. Each statement is pinned with the outcome it gets, which is the one the
 * Spark 3.5 itest's {@code IcebergRestOpenHouseStatementsTest} pins from Spark 3.5 over the same
 * kind of catalog.
 *
 * <p>GRANT, REVOKE, SHOW GRANTS and UNLOCK parse and analyze, resolve the catalog's capabilities,
 * and fail with "does not support": a plain {@code RESTCatalog} has no client for the OpenHouse
 * grant and lock routes yet (work item C2-3), and the server does not serve them yet (V5-3). These
 * pins are meant to fail when the capability client lands; they then become the cases that show the
 * statements working.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SparkIrcOpenHouseStatementsTest {
  private static final String TABLE = "statements";
  private static final String UNLOCK_ENABLED = "spark.sql.openhouse.unlock.enabled";

  @BeforeAll
  void createTable() throws Exception {
    ExtensionsTestService.get().create(TABLE, true);
  }

  private static String t(String catalog) {
    return catalog + "." + DB + "." + TABLE;
  }

  /** The grant statements, each against {@code catalog}. */
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

  @Test
  void grantStatementsAreUnsupportedUntilTheCapabilityClientLands() throws Exception {
    for (String catalog : Arrays.asList(IRC, IRC_ADVERTISED)) {
      for (Map.Entry<String, String> statement : grantStatements(catalog).entrySet()) {
        Assertions.assertEquals(
            "java.lang.UnsupportedOperationException: Catalog '"
                + catalog
                + "' does not support Grant Revoke Statements",
            outcome(statement.getValue()),
            statement.getKey() + " on " + catalog);
      }
    }
  }

  @Test
  void unlockIsUnsupportedUntilTheCapabilityClientLands() throws Exception {
    for (String catalog : Arrays.asList(IRC, IRC_ADVERTISED)) {
      ExtensionsSpark.session().conf().unset(UNLOCK_ENABLED);
      Assertions.assertEquals(
          "java.lang.UnsupportedOperationException: ALTER TABLE UNLOCK is disabled. Set "
              + UNLOCK_ENABLED
              + "=true to enable it.",
          outcome("ALTER TABLE " + t(catalog) + " UNLOCK"));
      ExtensionsSpark.session().conf().set(UNLOCK_ENABLED, "true");
      try {
        for (String suffix : Arrays.asList("", " REASON SYSTEM_ONLY")) {
          Assertions.assertEquals(
              "java.lang.UnsupportedOperationException: Catalog '"
                  + catalog
                  + "' does not support UNLOCK",
              outcome("ALTER TABLE " + t(catalog) + " UNLOCK" + suffix),
              catalog + suffix);
        }
      } finally {
        ExtensionsSpark.session().conf().unset(UNLOCK_ENABLED);
      }
    }
  }

  /**
   * What the resolver finds on the session's IRC catalogs: Iceberg's REST path, the server's
   * advertised {@code policies} (its config override wins over {@code irc_adv}'s client-side
   * claim), and policies the only usable capability.
   */
  @Test
  void theResolverReadsTheServersAdvertisedCapabilities() throws Exception {
    for (String catalog : Arrays.asList(IRC, IRC_ADVERTISED)) {
      CatalogCapabilities capabilities =
          OpenHouseCapabilities.resolve(
              ExtensionsSpark.session().sessionState().catalogManager().catalog(catalog));
      Assertions.assertEquals(CatalogCapabilities.Path.ICEBERG_REST, capabilities.path(), catalog);
      Assertions.assertEquals(
          new LinkedHashSet<>(Collections.singletonList("policies")),
          capabilities.advertised(),
          catalog);
      Assertions.assertEquals(
          new LinkedHashSet<>(Collections.singletonList("policies")),
          capabilities.offered(),
          catalog);
    }
  }

  /**
   * A policy statement on an Iceberg table that is not an OpenHouse table (no {@code
   * openhouse.tableId}): the exec node's own refusal, the message of the Spark 3.5 exec node.
   */
  @Test
  void policyStatementsRefuseATableThatIsNotAnOpenHouseTable() throws Exception {
    String plain = LOCAL + ".db.plain";
    Assertions.assertEquals(
        "ok",
        outcome("CREATE TABLE IF NOT EXISTS " + plain + " (id bigint, name string) USING iceberg"));
    Map<String, String> statements = new LinkedHashMap<>();
    statements.put("SET POLICY (RETENTION=30d)", "retention policy");
    statements.put("SET POLICY (SHARING=TRUE)", "sharing policy");
    statements.put("SET POLICY (HISTORY VERSIONS=3)", "history policy");
    statements.put("SET POLICY (REPLICATION=({destination:'a'}))", "replication policy");
    statements.put("UNSET POLICY (REPLICATION)", "unset replication policy");
    statements.put("MODIFY COLUMN name SET TAG = (PII)", "column policy tags");
    for (Map.Entry<String, String> statement : statements.entrySet()) {
      String verb = statement.getValue().startsWith("unset") ? "unset " : "set ";
      String what = statement.getValue().replaceFirst("^unset ", "");
      Assertions.assertEquals(
          "java.lang.UnsupportedOperationException: Cannot "
              + verb
              + what
              + " for non-Openhouse table: "
              + plain,
          outcome("ALTER TABLE " + plain + " " + statement.getKey()),
          statement.getKey());
    }
  }

  /**
   * The same statements on the IRC table still work through policies: the control that only the
   * capability differs, not the statement or the table.
   */
  @Test
  void theSameTableTakesAPolicyStatement() throws Exception {
    Assertions.assertEquals("ok", outcome("ALTER TABLE " + t(IRC) + " SET POLICY (SHARING=TRUE)"));
    Assertions.assertTrue(
        ExtensionsTestService.get()
            .properties(TABLE)
            .get("policies")
            .contains("\"sharingEnabled\": true"));
  }
}
