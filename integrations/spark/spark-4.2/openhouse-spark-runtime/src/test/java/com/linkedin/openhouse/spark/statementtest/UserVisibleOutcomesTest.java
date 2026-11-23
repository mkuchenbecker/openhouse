package com.linkedin.openhouse.spark.statementtest;

import com.linkedin.openhouse.capabilities.AclPolicy;
import com.linkedin.openhouse.capabilities.GrantsCapability;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.hadoop.HadoopCatalog;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.StructField;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * What a user sees on Spark 4.2 for each kind of OpenHouse statement outcome: the exception class
 * and the whole message. The expected values are the ones the same statements give on Spark 3.5
 * with the 3.5 extensions (recorded by running this list there), except where a comment says
 * otherwise: those differences come from Spark's own messages, never from the extensions.
 *
 * <p>The catalog {@code oh} is Iceberg's Hadoop catalog: {@code oh.db.t} carries {@code
 * openhouse.tableId}, {@code oh.db.plain} does not, and the catalog offers no grants or unlock. The
 * catalog {@code grants} implements {@link GrantsCapability}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class UserVisibleOutcomesTest {
  private static final String UNLOCK_ENABLED = "spark.sql.openhouse.unlock.enabled";
  private static final String KEYWORDS =
      "{'ALTER', 'TABLE', 'SET', 'POLICY', 'RETENTION', 'REPLICATION', 'HISTORY', 'SHARING',"
          + " 'GRANT', 'REVOKE', 'ON', 'TO', 'SHOW', 'GRANTS', 'PATTERN', 'WHERE', 'COLUMN', 'WITH',"
          + " 'TIMEZONE', 'UNLOCK', 'REASON', IDENTIFIER, BACKQUOTED_IDENTIFIER}";
  private static final String PARSE =
      "com.linkedin.openhouse.spark.sql.catalyst.parser.extensions.OpenhouseParseException: ";
  private static final String UNSUPPORTED = "java.lang.UnsupportedOperationException: ";

  private SparkSession spark;

  @BeforeAll
  void start() throws Exception {
    String warehouse = Files.createTempDirectory("outcomes").toString();
    spark =
        SparkSession.builder()
            .master("local[1]")
            .config(
                "spark.sql.extensions",
                "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions,"
                    + "com.linkedin.openhouse.spark.extensions.OpenhouseSparkSessionExtensions")
            .config("spark.sql.catalog.oh", "org.apache.iceberg.spark.SparkCatalog")
            .config("spark.sql.catalog.oh.type", "hadoop")
            .config("spark.sql.catalog.oh.warehouse", warehouse + "/oh")
            .config("spark.sql.catalog.grants", "org.apache.iceberg.spark.SparkCatalog")
            .config("spark.sql.catalog.grants.catalog-impl", GrantsHadoopCatalog.class.getName())
            .config("spark.sql.catalog.grants.warehouse", warehouse + "/grants")
            .config("spark.sql.warehouse.dir", warehouse + "/spark")
            .getOrCreate();
    spark.sql(
        "CREATE TABLE oh.db.t (id bigint, name string, ts timestamp) USING iceberg"
            + " TBLPROPERTIES ('openhouse.tableId'='t')");
    spark.sql("CREATE TABLE oh.db.plain (id bigint, name string) USING iceberg");
    spark.sql("CREATE TABLE spark_catalog.default.p (id bigint) USING parquet");
  }

  @AfterAll
  void stop() throws Exception {
    spark.close();
  }

  private String outcome(String sql) {
    try {
      spark.sql(sql).collectAsList();
      return "ok";
    } catch (Throwable failure) {
      return failure.getClass().getName() + ": " + failure.getMessage();
    }
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '~',
      value = {
        // Parse errors from the OpenHouse grammar: ANTLR 4.13.1 words them as 4.7.1 does.
        "ALTER TABLE oh.db.t SET POLICY (RETENTION = 30d ON COLUMN)"
            + "|mismatched input ')' expecting KEYWORDS; line 1 pos 57",
        "ALTER TABLE oh.db.t SET POLICY (RETENTION = 30d WHERE pattern = 'yyyy')"
            + "|mismatched input 'WHERE' expecting {')', 'ON'}; line 1 pos 48",
        "ALTER TABLE oh.db.t SET POLICY (HISTORY)"
            + "|At least one of MAX_AGE or VERSIONS must be specified in HISTORY policy, e.g."
            + " ALTER TABLE openhouse.db.table SET POLICY (HISTORY MAX_AGE=2D) or ALTER TABLE"
            + " openhouse.db.table SET POLICY (HISTORY VERSIONS=3); line 1 pos 32",
        "ALTER TABLE oh.db.t UNLOCK REASON NOPE"
            + "|Invalid lock reason 'NOPE'. Expected one of: LEGACY, SYSTEM_ONLY.; line 1 pos 34",
        "ALTER TABLE oh.db.t UNLOCK REASON|mismatched input '<EOF>' expecting KEYWORDS; line 1 pos 33",
        "GRANT SELECT oh.db.t sraikar|mismatched input 'oh' expecting 'ON'; line 1 pos 13",
        "SHOW GRANTS TABLE oh.db.t|missing 'ON' at 'TABLE'; line 1 pos 12",
        "ALTER TABLE oh.db.t MODIFY COLUMN name SET TAG = (FOO)"
            + "|no viable alternative at input 'TAG = (FOO'; line 1 pos 50",
        // The retention exec's own checks, before and after loading the table.
        "ALTER TABLE oh.db.t SET POLICY (RETENTION=30d WITH TIMEZONE 'Mars/Olympus' ON COLUMN name)"
            + "|Invalid retention time zone 'Mars/Olympus': Unknown time-zone ID: Mars/Olympus;"
            + " line 1 pos 0",
        "ALTER TABLE oh.db.t SET POLICY (RETENTION=30d WITH TIMEZONE 'UTC')"
            + "|WITH TIMEZONE requires ON COLUMN for a string retention column; line 1 pos 0",
        "ALTER TABLE oh.db.t SET POLICY (RETENTION=30d WITH TIMEZONE 'UTC' ON COLUMN name"
            + " WHERE PATTERN='yyyy-MM-dd Z')"
            + "|The retention column pattern already encodes a time zone; line 1 pos 0",
        "ALTER TABLE oh.db.t SET POLICY (RETENTION=30d WITH TIMEZONE 'UTC' ON COLUMN id)"
            + "|WITH TIMEZONE requires a string retention column; line 1 pos 0",
      })
  void extensionParseErrors(String sql, String message) {
    Assertions.assertEquals(PARSE + message.replace("KEYWORDS", KEYWORDS), outcome(sql));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '~',
      value = {
        "SET POLICY (RETENTION=30d)|Cannot set retention policy for non-Openhouse table: oh.db.plain",
        "SET POLICY (SHARING=TRUE)|Cannot set sharing policy for non-Openhouse table: oh.db.plain",
        "SET POLICY (HISTORY VERSIONS=3)"
            + "|Cannot set history policy for non-Openhouse table: oh.db.plain",
        "SET POLICY (REPLICATION=({destination:'a'}))"
            + "|Cannot set replication policy for non-Openhouse table: oh.db.plain",
        "UNSET POLICY (REPLICATION)"
            + "|Cannot unset replication policy for non-Openhouse table: oh.db.plain",
        "MODIFY COLUMN name SET TAG = (PII)"
            + "|Cannot set column policy tags for non-Openhouse table: oh.db.plain",
      })
  void policyStatementsRefuseATableThatIsNotAnOpenHouseTable(String statement, String message) {
    Assertions.assertEquals(UNSUPPORTED + message, outcome("ALTER TABLE oh.db.plain " + statement));
  }

  @Test
  void grantsAndUnlockOnACatalogWithoutThem() {
    for (String sql :
        Arrays.asList(
            "GRANT SELECT ON TABLE oh.db.t TO sraikar",
            "REVOKE SELECT ON TABLE oh.db.t FROM sraikar",
            "GRANT CREATE TABLE ON DATABASE oh.db TO sraikar",
            "SHOW GRANTS ON TABLE oh.db.t",
            "SHOW GRANTS ON DATABASE oh.db")) {
      Assertions.assertEquals(
          UNSUPPORTED + "Catalog 'oh' does not support Grant Revoke Statements", outcome(sql), sql);
    }
    spark.conf().unset(UNLOCK_ENABLED);
    Assertions.assertEquals(
        UNSUPPORTED
            + "ALTER TABLE UNLOCK is disabled. Set spark.sql.openhouse.unlock.enabled=true to"
            + " enable it.",
        outcome("ALTER TABLE oh.db.t UNLOCK"));
    spark.conf().set(UNLOCK_ENABLED, "true");
    try {
      for (String sql :
          Arrays.asList(
              "ALTER TABLE oh.db.t UNLOCK",
              "ALTER TABLE oh.db.t UNLOCK REASON SYSTEM_ONLY",
              "ALTER TABLE spark_catalog.default.p UNLOCK")) {
        String catalog = sql.contains("spark_catalog") ? "spark_catalog" : "oh";
        Assertions.assertEquals(
            UNSUPPORTED + "Catalog '" + catalog + "' does not support UNLOCK", outcome(sql), sql);
      }
    } finally {
      spark.conf().unset(UNLOCK_ENABLED);
    }
  }

  /**
   * Statements Spark itself rejects. Same exception classes and texts as on Spark 3.5, but Spark 4
   * appends {@code SQLSTATE: <state>} to its own messages, and its table-not-found message gains a
   * {@code Search path} line. On 3.5 a message ends right before the {@code SQLSTATE} part.
   */
  @Test
  void sparkOwnErrorsCarryTheSqlState() {
    Assertions.assertEquals(
        "org.apache.spark.sql.catalyst.parser.ParseException: \n"
            + "[PARSE_SYNTAX_ERROR] Syntax error at or near 'GRAN'. SQLSTATE: 42601 (line 1, pos 0)\n"
            + "\n"
            + "== SQL ==\n"
            + "GRAN SELECT ON TABLE oh.db.t TO sraikar\n"
            + "^^^\n",
        outcome("GRAN SELECT ON TABLE oh.db.t TO sraikar"));
    Assertions.assertEquals(
        "org.apache.spark.sql.catalyst.analysis.NoSuchTableException: [TABLE_OR_VIEW_NOT_FOUND]"
            + " The table or view db.missing cannot be found. Verify the spelling and correctness"
            + " of the schema and catalog.\n"
            + "Search path: [`db`].\n"
            + "If you did not qualify the name with a schema, verify the current_schema() output,"
            + " or qualify the name with the correct schema and catalog.\n"
            + "To tolerate the error on drop use DROP VIEW IF EXISTS or DROP TABLE IF EXISTS."
            + " SQLSTATE: 42P01",
        outcome("ALTER TABLE oh.db.missing SET POLICY (SHARING=TRUE)"));
    // A catalog that is not Iceberg's: the planner finds no exec for the statement (an assertion
    // inside Spark), as on 3.5. UNLOCK fails earlier, in analysis (above).
    for (String sql :
        Arrays.asList(
            "GRANT SELECT ON TABLE spark_catalog.default.p TO sraikar",
            "SHOW GRANTS ON TABLE spark_catalog.default.p",
            "ALTER TABLE spark_catalog.default.p SET POLICY (SHARING=TRUE)")) {
      Assertions.assertEquals(
          "org.apache.spark.SparkException: [INTERNAL_ERROR] The Spark SQL phase planning failed"
              + " with an internal error. You hit a bug in Spark or the Spark plugins you use."
              + " Please, report this bug to the corresponding communities or vendors, and provide"
              + " the full stack trace. SQLSTATE: XX000",
          outcome(sql),
          sql);
    }
  }

  @Test
  void showGrantsColumnsAndRows() {
    GrantsHadoopCatalog.policies =
        Arrays.asList(new AclPolicy("SELECT", "*"), new AclPolicy("ALTER", "sraikar"));
    org.apache.spark.sql.Dataset<org.apache.spark.sql.Row> grants =
        spark.sql("SHOW GRANTS ON TABLE grants.db.t");
    Assertions.assertEquals(
        Arrays.asList("privilege", "principal"),
        Arrays.stream(grants.schema().fields())
            .map(StructField::name)
            .collect(Collectors.toList()));
    Assertions.assertEquals(
        Arrays.asList("[SELECT,PUBLIC]", "[ALTER,sraikar]"),
        grants.collectAsList().stream().map(Object::toString).collect(Collectors.toList()));
    Assertions.assertEquals("ok", outcome("SHOW GRANTS ON DATABASE grants.db"));
  }

  /** An Iceberg catalog that offers grants through the capability interface. */
  public static class GrantsHadoopCatalog extends HadoopCatalog implements GrantsCapability {
    static List<AclPolicy> policies = Collections.emptyList();

    @Override
    public void updateTableAclPolicies(
        TableIdentifier table, boolean isGrant, String privilege, String principal) {}

    @Override
    public List<AclPolicy> getTableAclPolicies(TableIdentifier table) {
      return policies;
    }

    @Override
    public void updateDatabaseAclPolicies(
        Namespace database, boolean isGrant, String privilege, String principal) {}

    @Override
    public List<AclPolicy> getDatabaseAclPolicies(Namespace database) {
      return policies;
    }
  }
}
