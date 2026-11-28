package com.linkedin.openhouse.spark.statementtest;

import com.linkedin.openhouse.capabilities.CapabilityNames;
import com.linkedin.openhouse.spark.capabilities.CatalogCapabilities;
import com.linkedin.openhouse.spark.capabilities.OpenHouseCapabilities;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import org.apache.iceberg.spark.Spark3Util;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.connector.catalog.CatalogPlugin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The OpenHouse SQL statements on a Spark catalog that is not Iceberg's {@code SparkCatalog} or
 * {@code SparkSessionCatalog} but delegates to one ({@link DelegatingTableCatalog}), next to the
 * same tables through Iceberg's {@code SparkCatalog}.
 *
 * <p>The extensions' planning strategy turns a statement into its exec only when the statement
 * names an Iceberg {@code SparkCatalog} or {@code SparkSessionCatalog}; for any other catalog it
 * plans nothing and Spark's planner fails. So on such a catalog every statement fails at planning,
 * before the capability resolver is asked, and the resolver must not refuse policies there with a
 * message about a server it does not have.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatementsOnAWrapperCatalogTest {
  private SparkSession spark;

  @BeforeAll
  void startSpark() throws Exception {
    String warehouse = Files.createTempDirectory("wrapper-catalog").toString();
    SparkSession.clearActiveSession();
    SparkSession.clearDefaultSession();
    spark =
        SparkSession.builder()
            .master("local[1]")
            .config(
                "spark.sql.extensions",
                "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions,"
                    + "com.linkedin.openhouse.spark.extensions.OpenhouseSparkSessionExtensions")
            .config("spark.sql.catalog.iceberg", "org.apache.iceberg.spark.SparkCatalog")
            .config("spark.sql.catalog.iceberg.type", "hadoop")
            .config("spark.sql.catalog.iceberg.warehouse", warehouse)
            .config("spark.sql.catalog.wrapper", DelegatingTableCatalog.class.getName())
            .config("spark.sql.catalog.wrapper.type", "hadoop")
            .config("spark.sql.catalog.wrapper.warehouse", warehouse)
            .getOrCreate();
    spark.sql(
        "CREATE TABLE iceberg.db.t (id bigint, ts timestamp) USING iceberg"
            + " TBLPROPERTIES ('openhouse.tableId'='t')");
  }

  @AfterAll
  void stopSpark() throws Exception {
    spark.close();
    SparkSession.clearActiveSession();
    SparkSession.clearDefaultSession();
  }

  private String policyPatch() {
    return spark
        .sql("SHOW TBLPROPERTIES iceberg.db.t ('updated.openhouse.policy')")
        .collectAsList()
        .get(0)
        .getString(1);
  }

  private CatalogPlugin catalog(String name) {
    return Spark3Util.catalogAndIdentifier(spark, Arrays.asList(name, "db", "t")).catalog();
  }

  /** The control: through Iceberg's SparkCatalog the policy statement commits its patch. */
  @Test
  void throughIcebergsSparkCatalogThePolicyStatementCommits() {
    spark.sql("ALTER TABLE iceberg.db.t SET POLICY (RETENTION=3d)");
    Assertions.assertEquals("{\"retention\":{\"count\":3,\"granularity\":\"DAY\"}}", policyPatch());
  }

  /**
   * Through the wrapper the same table is an OpenHouse {@code SparkTable}, yet each statement fails
   * at planning, as it did before the capability resolver existed, and commits nothing.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "ALTER TABLE wrapper.db.t SET POLICY (RETENTION=5d)",
        "ALTER TABLE wrapper.db.t SET POLICY (SHARING=true)",
        "ALTER TABLE wrapper.db.t SET POLICY (HISTORY MAX_AGE=2d)",
        "GRANT SELECT ON TABLE wrapper.db.t TO someone",
        "SHOW GRANTS ON TABLE wrapper.db.t"
      })
  void throughAWrapperCatalogEveryStatementFailsAtPlanning(String statement) {
    String before = spark.sql("SHOW TBLPROPERTIES iceberg.db.t").collectAsList().toString();
    Throwable failure = Assertions.assertThrows(Throwable.class, () -> spark.sql(statement));
    StringBuilder chain = new StringBuilder();
    for (Throwable t = failure; t != null; t = t.getCause()) {
      chain.append(t).append('\n');
    }
    Assertions.assertTrue(chain.toString().contains("No plan for"), chain.toString());
    Assertions.assertEquals(
        before, spark.sql("SHOW TBLPROPERTIES iceberg.db.t").collectAsList().toString());
  }

  /** UNLOCK is resolved at analysis and refused there, with the message it has always given. */
  @Test
  void throughAWrapperCatalogUnlockIsRefusedAtAnalysis() {
    spark.conf().set("spark.sql.openhouse.unlock.enabled", "true");
    try {
      UnsupportedOperationException failure =
          Assertions.assertThrows(
              UnsupportedOperationException.class,
              () -> spark.sql("ALTER TABLE wrapper.db.t UNLOCK"));
      Assertions.assertEquals("Catalog 'wrapper' does not support UNLOCK", failure.getMessage());
    } finally {
      spark.conf().unset("spark.sql.openhouse.unlock.enabled");
    }
  }

  /**
   * The resolver offers policies on the wrapper, as on any catalog that is not Iceberg's REST
   * catalog: only a REST server that does not advertise them is a reason to refuse. Grants and
   * unlock are refused with the messages those statements have always given.
   */
  @Test
  void theResolverDoesNotRefusePoliciesOnTheWrapper() {
    CatalogPlugin wrapper = catalog("wrapper");
    Assertions.assertTrue(wrapper instanceof DelegatingTableCatalog);
    CatalogCapabilities capabilities = OpenHouseCapabilities.resolve(wrapper);
    Assertions.assertEquals(CatalogCapabilities.Path.NOT_ICEBERG, capabilities.path());
    Assertions.assertEquals(
        Collections.singleton(CapabilityNames.POLICIES), capabilities.offered());
    OpenHouseCapabilities.requirePolicies(wrapper);
    Assertions.assertEquals(
        "Catalog 'wrapper' does not support Grant Revoke Statements",
        Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> OpenHouseCapabilities.requireGrants(wrapper))
            .getMessage());
    Assertions.assertEquals(
        "Catalog 'wrapper' does not support UNLOCK",
        Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> OpenHouseCapabilities.requireUnlock(wrapper))
            .getMessage());
  }
}
