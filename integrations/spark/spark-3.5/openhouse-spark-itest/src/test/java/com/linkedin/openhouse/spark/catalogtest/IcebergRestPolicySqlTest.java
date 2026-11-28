package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.common.security.DummyTokenInterceptor;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.util.HashMap;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * OpenHouse's policy statements through Iceberg's own {@code SparkCatalog} over an unmodified
 * {@code RESTCatalog} ({@code type=rest}), with the OpenHouse SQL extensions in the session and no
 * OpenHouse catalog class on that catalog, against the embedded tables service.
 *
 * <p>Each statement runs on two tables that start identical: through the OpenHouse catalog (the
 * OpenHouse Java client, which merges the statement's patch client-side) on one, and through the
 * IRC catalog (the server merges it) on the other. The stored {@code policies}, read through the
 * OpenHouse client, must be the same, the replication schedule aside, which each write draws at
 * random. The real client's merge is the reference here, where the service's MockMvc test uses a
 * copy of it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class IcebergRestPolicySqlTest extends OpenHouseSparkITest {
  private static final String DB = "irc_policy_sql";
  private static final String CATALOG = "irc";

  private SparkSession spark;
  private Catalog openhouse;
  private int next = 0;

  @BeforeAll
  void start() throws Exception {
    spark = getSparkSession();
    openhouse = getOpenHouseCatalog(spark);
    String prefix = "spark.sql.catalog." + CATALOG;
    spark.conf().set(prefix, "org.apache.iceberg.spark.SparkCatalog");
    spark.conf().set(prefix + ".type", "rest");
    spark.conf().set(prefix + ".uri", getOpenHouseLocalServerURI() + "/iceberg");
    spark.conf().set(prefix + ".token", token());
  }

  private static String token() throws Exception {
    return new DummyTokenInterceptor.DummySecurityJWT("irc_policy_sql").buildNoopJWT();
  }

  private String policies(String table) {
    return openhouse
        .loadTable(TableIdentifier.of(DB, table))
        .properties()
        .get("policies")
        .replaceAll("\"cronSchedule\": \"[^\"]*\"", "\"cronSchedule\": \"cron\"");
  }

  /** Two identical tables, made through the OpenHouse catalog; returns their names. */
  private String[] pair(boolean partitioned, String... setUp) {
    String name = "t" + next++;
    String[] tables = {name + "_oh", name + "_irc"};
    for (String table : tables) {
      try {
        openhouse.dropTable(TableIdentifier.of(DB, table));
      } catch (RuntimeException ignored) {
        // not there
      }
      spark.sql(
          "CREATE TABLE openhouse."
              + DB
              + "."
              + table
              + " (id bigint, name string, ts timestamp)"
              + (partitioned ? " PARTITIONED BY (hours(ts))" : ""));
      for (String statement : setUp) {
        spark.sql("ALTER TABLE openhouse." + DB + "." + table + " " + statement);
      }
    }
    return tables;
  }

  /** {@code statement} through both catalogs: the same stored policies. */
  private void assertSameThroughBoth(boolean partitioned, String statement, String... setUp) {
    String[] tables = pair(partitioned, setUp);
    spark.sql("ALTER TABLE openhouse." + DB + "." + tables[0] + " " + statement);
    spark.sql("ALTER TABLE " + CATALOG + "." + DB + "." + tables[1] + " " + statement);
    Assertions.assertEquals(policies(tables[0]), policies(tables[1]), statement);
    Assertions.assertFalse(
        openhouse
            .loadTable(TableIdentifier.of(DB, tables[1]))
            .properties()
            .containsKey("updated.openhouse.policy"),
        statement);
  }

  private static final String[] WITH_POLICIES = {
    "SET POLICY (HISTORY VERSIONS=7)",
    "MODIFY COLUMN name SET TAG = (PII)",
    "SET POLICY (RETENTION=48h)"
  };

  @Test
  void everyPolicyStatementStoresWhatTheOpenHouseCatalogStores() {
    String[] onPartitioned = {
      "SET POLICY (RETENTION=24h)",
      "SET POLICY (SHARING=TRUE)",
      "SET POLICY (SHARING=false)",
      "SET POLICY (HISTORY MAX_AGE=3d)",
      "SET POLICY (HISTORY VERSIONS=5)",
      "SET POLICY (HISTORY MAX_AGE=24h VERSIONS=5)",
      "SET POLICY (REPLICATION=({destination:'a', interval:12h}))",
      "SET POLICY (REPLICATION=({destination:'a', interval:12h}, {destination:'b'}))",
      "UNSET POLICY (REPLICATION)",
      "MODIFY COLUMN name SET TAG = (PII, HC)",
      "MODIFY COLUMN id SET TAG = (HC)",
      "MODIFY COLUMN name SET TAG = (NONE)",
    };
    for (String statement : onPartitioned) {
      assertSameThroughBoth(true, statement);
      assertSameThroughBoth(true, statement, WITH_POLICIES);
    }
    String[] onUnpartitioned = {
      "SET POLICY (RETENTION=30d ON COLUMN name)",
      "SET POLICY (RETENTION=30d ON COLUMN name WHERE PATTERN='yyyy-MM-dd')",
      "SET POLICY (RETENTION=30d WITH TIMEZONE 'America/Los_Angeles' ON COLUMN name"
          + " WHERE PATTERN='yyyy-MM-dd')",
      "SET POLICY (SHARING=TRUE)",
      "MODIFY COLUMN name SET TAG = (PII)",
    };
    for (String statement : onUnpartitioned) {
      assertSameThroughBoth(false, statement);
    }
  }

  /**
   * A statement a native policy rule refuses: refused through both catalogs with the native message
   * (the OpenHouse client's exception wraps the response body; the IRC catalog's is Iceberg's
   * BadRequestException), and nothing stored on either.
   */
  @Test
  void aRefusedStatementIsRefusedThroughBothWithTheNativeMessage() {
    String[] tables = pair(true);
    String before = policies(tables[1]);
    String rule = "Policies granularity must be equal to or lesser than time partition spec";
    Throwable legacy =
        Assertions.assertThrows(
            Exception.class,
            () ->
                spark.sql(
                    "ALTER TABLE openhouse."
                        + DB
                        + "."
                        + tables[0]
                        + " SET POLICY (RETENTION=30d)"));
    Assertions.assertTrue(String.valueOf(legacy.getMessage()).contains(rule), legacy.toString());
    Throwable irc =
        Assertions.assertThrows(
            Exception.class,
            () ->
                spark.sql(
                    "ALTER TABLE "
                        + CATALOG
                        + "."
                        + DB
                        + "."
                        + tables[1]
                        + " SET POLICY (RETENTION=30d)"));
    Assertions.assertEquals(
        "org.apache.iceberg.exceptions.BadRequestException", irc.getClass().getName());
    Assertions.assertTrue(String.valueOf(irc.getMessage()).contains(rule), irc.toString());
    Assertions.assertEquals(before, policies(tables[0]));
    Assertions.assertEquals(before, policies(tables[1]));
  }

  /**
   * The capability a client reads: an unmodified RESTCatalog configured as the Spark catalog is has
   * {@code openhouse.capabilities=policies,grants,unlock} in its properties.
   */
  @Test
  void aRestCatalogReadsThePolicyCapability() throws Exception {
    Map<String, String> properties = new HashMap<>();
    properties.put("uri", getOpenHouseLocalServerURI() + "/iceberg");
    properties.put("token", token());
    RESTCatalog rest =
        (RESTCatalog)
            CatalogUtil.loadCatalog(
                RESTCatalog.class.getName(), CATALOG, properties, new Configuration());
    try {
      Assertions.assertEquals(
          "policies,grants,unlock", rest.properties().get("openhouse.capabilities"));
    } finally {
      rest.close();
    }
  }
}
