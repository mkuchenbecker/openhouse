package com.linkedin.openhouse.spark.smoke;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;

/**
 * The write statements Spark SQL users rely on, one small test each, as one list that more than one
 * platform runs (work item N10; the checks were Spark 4.2's {@code SparkIrcWriteStatementsTest},
 * work item N3-b). Where the smoke suite ({@link SparkSqlSmokeScenarios}) reads a statement's
 * effect back through the path under test, these read what the service stored, through something
 * other than the path under test ({@link #stored(String)}): the schema, the properties, the
 * snapshots and refs, and the rows a reader of each ref gets from the data and delete files.
 *
 * <p>The baseline is what Spark 4.2 over the service's Iceberg REST routes stores, the platform the
 * checks were written for. A platform states, in {@link #differences()}, every check on which it
 * differs, with the baseline it departs from, what it does instead and why. A check without a
 * declared difference must give the baseline; one with a declaration must give exactly what it
 * declares, must really differ, and must quote the baseline as the test states it; a declaration
 * that no check consulted fails the run. Every check of a test runs even after one gave an
 * unexpected value, and the test then fails with all of them. With {@link #outcomesFile()} set, the
 * run writes one line per check: test, check, baseline, this platform's value and the verdict.
 *
 * <p>This file lives in the Spark 3.5 itest's {@code src/shared/smoke/java}, next to the smoke
 * scenarios, and both itests compile it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class SparkSqlWriteStatements {

  /** The catalog the statements name. */
  protected static final String CATALOG = "openhouse";

  /** The database the statements write to. */
  protected static final String DB = "smoke_writes";

  private static final String BAD_REQUEST = "org.apache.iceberg.exceptions.BadRequestException";

  // ---------- what a platform supplies ----------

  /** A session whose catalog {@value #CATALOG} is the platform's catalog path. */
  protected abstract SparkSession sparkSession() throws Exception;

  /**
   * What the service stored for {@code <db>.<table>}, read without the catalog path under test;
   * fails when the table cannot be loaded.
   */
  protected abstract StoredTable stored(String name) throws Exception;

  /** The checks on which this platform differs from the baseline. None by default. */
  protected List<Difference> differences() {
    return Collections.emptyList();
  }

  /** The file the run writes its outcomes to, or null for none. */
  protected String outcomesFile() {
    return null;
  }

  // ---------- declared differences ----------

  /** One check on which a platform differs from the baseline. */
  public static final class Difference {
    private final String test;
    private final String check;
    private String baseline = "";
    private String here = "";
    private String kind = "";
    private String why = "";

    private Difference(String test, String check) {
      this.test = test;
      this.check = check;
    }

    /** A difference on {@code check} of the test method named {@code test}. */
    public static Difference on(String test, String check) {
      return new Difference(test, check);
    }

    /** The baseline value, exactly as the test states it. */
    public Difference baseline(Object value) {
      this.baseline = String.valueOf(value);
      return this;
    }

    /** What this platform stores instead. */
    public Difference here(Object value) {
      this.here = String.valueOf(value);
      return this;
    }

    /** The kind of difference and the reason or evidence for it. */
    public Difference because(String differenceKind, String reason) {
      this.kind = differenceKind;
      this.why = reason;
      return this;
    }

    private String key() {
      return test + " / " + check;
    }
  }

  // ---------- running and recording ----------

  private Map<String, Difference> declared;
  private final Set<String> consulted = new LinkedHashSet<>();
  private final Set<String> testsRun = new LinkedHashSet<>();
  private final Set<String> testsDiffering = new LinkedHashSet<>();
  private final List<String> outcomeLines = new ArrayList<>();
  private String test;
  private List<String> unexpected;

  @BeforeEach
  public void beginTest(TestInfo info) {
    if (declared == null) {
      declared = new HashMap<>();
      for (Difference difference : differences()) {
        Assertions.assertNull(
            declared.put(difference.key(), difference),
            "Two differences declared for " + difference.key());
      }
    }
    test = info.getTestMethod().map(m -> m.getName()).orElse("");
    testsRun.add(test);
    unexpected = new ArrayList<>();
  }

  @AfterEach
  public void endTest() {
    if (!unexpected.isEmpty()) {
      Assertions.fail(
          unexpected.size()
              + " unexpected value(s) in "
              + test
              + ":\n"
              + String.join("\n", unexpected));
    }
  }

  @AfterAll
  public void endSuite() throws IOException {
    String file = outcomesFile();
    if (file != null) {
      Path path = Paths.get(file);
      Files.createDirectories(path.getParent());
      List<String> lines = new ArrayList<>(outcomeLines);
      lines.add(
          "# "
              + (testsRun.size() - testsDiffering.size())
              + " of "
              + testsRun.size()
              + " tests identical to the baseline");
      Files.write(path, lines, StandardCharsets.UTF_8);
    }
    List<String> unused =
        (declared == null ? Collections.<String, Difference>emptyMap() : declared)
            .values().stream()
                .filter(d -> testsRun.contains(d.test))
                .map(Difference::key)
                .filter(key -> !consulted.contains(key))
                .sorted()
                .collect(Collectors.toList());
    Assertions.assertEquals(
        Collections.emptyList(), unused, "Differences declared for checks that were not made");
  }

  /**
   * One check: {@code actual} must be {@code baseline}, or what this platform declares for the
   * check instead.
   */
  protected final void check(String check, Object baseline, Object actual) {
    String key = test + " / " + check;
    Difference difference = declared.get(key);
    String expected = String.valueOf(baseline);
    String value = String.valueOf(actual);
    boolean same = Objects.equals(expected, value);
    String verdict;
    if (difference == null) {
      verdict = same ? "same" : "UNEXPECTED";
      if (!same) {
        testsDiffering.add(test);
        unexpected.add("  [" + key + "]\n    baseline: " + expected + "\n    here:     " + value);
      }
    } else {
      consulted.add(key);
      testsDiffering.add(test);
      verdict = "different: " + difference.kind;
      if (!expected.equals(difference.baseline)) {
        verdict = "UNEXPECTED";
        unexpected.add(
            "  ["
                + key
                + "]\n    the difference quotes a baseline the test does not state\n"
                + "    test:       "
                + expected
                + "\n    difference: "
                + difference.baseline);
      } else if (same) {
        verdict = "UNEXPECTED";
        unexpected.add(
            "  [" + key + "]\n    a difference is declared but the value is the baseline's");
      } else if (!value.equals(difference.here)) {
        verdict = "UNEXPECTED";
        unexpected.add(
            "  [" + key + "]\n    declared: " + difference.here + "\n    here:     " + value);
      }
    }
    outcomeLines.add(
        String.join(
            "\t",
            test,
            oneLine(check),
            oneLine(expected),
            oneLine(value),
            verdict,
            difference == null ? "" : oneLine(difference.why)));
  }

  private static String oneLine(String text) {
    return text.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
  }

  // ---------- helpers ----------

  private void sql(String statement) throws Exception {
    sparkSession().sql(statement).collectAsList();
  }

  /** The Spark name of a table of this suite that does not exist yet. */
  private String fresh(String table) throws Exception {
    String name = CATALOG + "." + DB + "." + table;
    sql("DROP TABLE IF EXISTS " + name);
    return name;
  }

  private StoredTable storedTable(String table) throws Exception {
    return stored(DB + "." + table);
  }

  private Throwable failure(String statement) throws Exception {
    SparkSession spark = sparkSession();
    return Assertions.assertThrows(Throwable.class, () -> spark.sql(statement).collectAsList());
  }

  /** The statement is refused by the service: a bad request that carries {@code fragment}. */
  private void checkRefused(Throwable failure, String fragment) {
    check("refused with", BAD_REQUEST, failure.getClass().getName());
    check(
        "refusal carries \"" + fragment + "\"",
        true,
        String.valueOf(failure.getMessage()).contains(fragment));
  }

  // ---------------------------------------------------------------- inserts

  @Test
  public void insertIntoAppendsASnapshotPerStatement() throws Exception {
    String t = fresh("insert_into");
    sql("CREATE TABLE " + t + " (id bigint, name string)");
    sql("INSERT INTO " + t + " VALUES (1,'a'),(2,'b')");
    sql("INSERT INTO " + t + " SELECT 3, 'c'");

    StoredTable stored = storedTable("insert_into");
    check("columns", Arrays.asList("id long", "name string"), stored.columns());
    check("rows", Arrays.asList("1,a", "2,b", "3,c"), stored.rows());
    check("operations", Arrays.asList("append", "append"), stored.operations());
    check("added-records of snapshot 1", "2", stored.summary(1).get("added-records"));
    check("total-records of snapshot 2", "3", stored.summary(2).get("total-records"));
  }

  @Test
  public void insertOverwriteOfAStaticPartitionLeavesTheOtherPartitions() throws Exception {
    String t = fresh("overwrite_static");
    sql("CREATE TABLE " + t + " (id bigint, p string) PARTITIONED BY (p)");
    sql("INSERT INTO " + t + " VALUES (1,'x'),(2,'y')");
    sql("INSERT OVERWRITE " + t + " PARTITION (p='x') SELECT 10");

    StoredTable stored = storedTable("overwrite_static");
    check("partitioning", "[1000: p: identity(2)]", stored.partitioning());
    check("rows", Arrays.asList("10,x", "2,y"), stored.rows());
    check("operations", Arrays.asList("append", "overwrite"), stored.operations());
  }

  @Test
  public void insertOverwriteWithoutAPartitionReplacesEveryRowInStaticMode() throws Exception {
    String t = fresh("overwrite_all");
    sql("CREATE TABLE " + t + " (id bigint, p string) PARTITIONED BY (p)");
    sql("INSERT INTO " + t + " VALUES (1,'x'),(2,'y'),(3,'z')");
    sql("INSERT OVERWRITE " + t + " VALUES (10,'x')");

    StoredTable stored = storedTable("overwrite_all");
    check("rows", Collections.singletonList("10,x"), stored.rows());
    check("operations", Arrays.asList("append", "overwrite"), stored.operations());
  }

  @Test
  public void insertOverwriteWithDynamicPartitionsReplacesOnlyThePartitionsItWrites()
      throws Exception {
    String t = fresh("overwrite_dynamic");
    sql("CREATE TABLE " + t + " (id bigint, p string) PARTITIONED BY (p)");
    sql("INSERT INTO " + t + " VALUES (1,'x'),(2,'y'),(3,'z')");
    SparkSession spark = sparkSession();
    spark.conf().set("spark.sql.sources.partitionOverwriteMode", "dynamic");
    try {
      sql("INSERT OVERWRITE " + t + " VALUES (10,'x'),(20,'y')");
    } finally {
      spark.conf().unset("spark.sql.sources.partitionOverwriteMode");
    }

    StoredTable stored = storedTable("overwrite_dynamic");
    check("rows", Arrays.asList("10,x", "20,y", "3,z"), stored.rows());
    check("operations", Arrays.asList("append", "overwrite"), stored.operations());
    check("replace-partitions of snapshot 2", "true", stored.summary(2).get("replace-partitions"));
  }

  // ---------------------------------------------------------------- row-level changes

  @Test
  public void mergeIntoRewritesTheDataFilesByDefault() throws Exception {
    String t = fresh("merge_cow");
    String source = fresh("merge_cow_source");
    sql("CREATE TABLE " + t + " (id bigint, name string)");
    sql("CREATE TABLE " + source + " (id bigint, name string)");
    sql("INSERT INTO " + t + " VALUES (1,'a'),(2,'b')");
    sql("INSERT INTO " + source + " VALUES (2,'B'),(3,'c')");
    sql(
        "MERGE INTO "
            + t
            + " t USING "
            + source
            + " s ON t.id = s.id"
            + " WHEN MATCHED THEN UPDATE SET t.name = s.name"
            + " WHEN NOT MATCHED THEN INSERT *");

    StoredTable stored = storedTable("merge_cow");
    check("rows", Arrays.asList("1,a", "2,B", "3,c"), stored.rows());
    check("operations", Arrays.asList("append", "overwrite"), stored.operations());
    check("total-delete-files of snapshot 2", "0", stored.summary(2).get("total-delete-files"));
  }

  @Test
  public void mergeIntoWritesAPositionDeleteFileWhenTheTableAsksForMergeOnRead() throws Exception {
    String t = fresh("merge_mor");
    String source = fresh("merge_mor_source");
    sql(
        "CREATE TABLE "
            + t
            + " (id bigint, name string) TBLPROPERTIES ("
            + "'write.merge.mode'='merge-on-read','write.delete.mode'='merge-on-read',"
            + "'write.update.mode'='merge-on-read')");
    sql("CREATE TABLE " + source + " (id bigint, name string)");
    sql("INSERT INTO " + t + " VALUES (1,'a'),(2,'b')");
    sql("INSERT INTO " + source + " VALUES (2,'B'),(3,'c')");
    sql(
        "MERGE INTO "
            + t
            + " t USING "
            + source
            + " s ON t.id = s.id"
            + " WHEN MATCHED THEN UPDATE SET t.name = s.name"
            + " WHEN NOT MATCHED THEN INSERT *");

    StoredTable stored = storedTable("merge_mor");
    // The reader applies the delete file: it does not return the row (2,b).
    check("rows", Arrays.asList("1,a", "2,B", "3,c"), stored.rows());
    check("operations", Arrays.asList("append", "overwrite"), stored.operations());
    check("total-delete-files of snapshot 2", "1", stored.summary(2).get("total-delete-files"));
    check(
        "total-position-deletes of snapshot 2",
        "1",
        stored.summary(2).get("total-position-deletes"));
  }

  @Test
  public void updateChangesTheMatchingRows() throws Exception {
    String t = fresh("update_rows");
    sql("CREATE TABLE " + t + " (id bigint, name string)");
    sql("INSERT INTO " + t + " VALUES (1,'a'),(2,'b')");
    sql("UPDATE " + t + " SET name = 'B' WHERE id = 2");

    StoredTable stored = storedTable("update_rows");
    check("rows", Arrays.asList("1,a", "2,B"), stored.rows());
    check("operations", Arrays.asList("append", "overwrite"), stored.operations());
  }

  @Test
  public void deleteRemovesTheMatchingRows() throws Exception {
    String t = fresh("delete_rows");
    sql("CREATE TABLE " + t + " (id bigint, name string)");
    sql("INSERT INTO " + t + " VALUES (1,'a'),(2,'b'),(3,'c')");
    sql("DELETE FROM " + t + " WHERE id = 2");

    StoredTable stored = storedTable("delete_rows");
    check("rows", Arrays.asList("1,a", "3,c"), stored.rows());
    check("operations", Arrays.asList("append", "overwrite"), stored.operations());
  }

  // ---------------------------------------------------------------- create and replace as select

  @Test
  public void createTableAsSelectIsOneCreateWithItsRows() throws Exception {
    String source = fresh("ctas_source");
    String t = fresh("ctas");
    sql("CREATE TABLE " + source + " (id bigint, name string)");
    sql("INSERT INTO " + source + " VALUES (1,'a'),(2,'b')");
    sql("CREATE TABLE " + t + " AS SELECT * FROM " + source);

    StoredTable stored = storedTable("ctas");
    check("columns", Arrays.asList("id long", "name string"), stored.columns());
    check("rows", Arrays.asList("1,a", "2,b"), stored.rows());
    check("operations", Collections.singletonList("append"), stored.operations());
    // One native create carried the snapshot: no second version was written.
    check(
        "openhouse.tableVersion",
        "INITIAL_VERSION",
        stored.properties().get("openhouse.tableVersion"));
    check("openhouse.tableId", "ctas", stored.properties().get("openhouse.tableId"));
  }

  @Test
  public void replaceTableAsSelectReplacesSchemaAndRowsAndKeepsPropertiesWhereReplaceIsEnabled()
      throws Exception {
    String t = fresh("rtas_enabled");
    sql("CREATE TABLE " + t + " (id bigint, name string) TBLPROPERTIES ('team'='x')");
    sql("INSERT INTO " + t + " VALUES (1,'a')");
    sql("ALTER TABLE " + t + " SET TBLPROPERTIES ('replace.enabled'='true')");
    sql(
        "CREATE OR REPLACE TABLE "
            + t
            + " TBLPROPERTIES ('extra'='y') AS SELECT 7L AS id, 'z' AS name, 'q' AS more");

    StoredTable stored = storedTable("rtas_enabled");
    check("columns", Arrays.asList("id long", "name string", "more string"), stored.columns());
    check("rows", Collections.singletonList("7,z,q"), stored.rows());
    check("team", "x", stored.properties().get("team"));
    check("extra", "y", stored.properties().get("extra"));
    check("replace.enabled", "true", stored.properties().get("replace.enabled"));
    // The replaced table's snapshot is kept, outside the history of main. On Spark 4 the replace
    // adds an overwrite snapshot.
    check("operations", Arrays.asList("append", "overwrite"), stored.operations());
    check("snapshot 1 reachable", false, stored.reachable(1));
    check("rows of snapshot 1", Collections.singletonList("1,a"), stored.rows("snapshot 1"));
  }

  @Test
  public void replaceTableAsSelectIsRefusedAndChangesNothingWhereReplaceIsNotEnabled()
      throws Exception {
    String t = fresh("rtas_gated");
    sql("CREATE TABLE " + t + " (id bigint, name string)");
    sql("INSERT INTO " + t + " VALUES (1,'a')");

    checkRefused(
        failure("CREATE OR REPLACE TABLE " + t + " AS SELECT 7L AS id, 'z' AS name, 'q' AS more"),
        "REPLACE TABLE AS SELECT is not enabled for table");

    StoredTable stored = storedTable("rtas_gated");
    check("columns", Arrays.asList("id long", "name string"), stored.columns());
    check("rows", Collections.singletonList("1,a"), stored.rows());
    check("operations", Collections.singletonList("append"), stored.operations());
  }

  // ---------------------------------------------------------------- columns and properties

  @Test
  public void addColumnIsStoredAndOldRowsReadNull() throws Exception {
    String t = fresh("add_column");
    sql("CREATE TABLE " + t + " (id bigint)");
    sql("INSERT INTO " + t + " VALUES (1)");
    sql("ALTER TABLE " + t + " ADD COLUMN extra string");
    sql("INSERT INTO " + t + " VALUES (2,'v')");

    StoredTable stored = storedTable("add_column");
    check("columns", Arrays.asList("id long", "extra string"), stored.columns());
    check("rows", Arrays.asList("1,null", "2,v"), stored.rows());
  }

  /** The native schema rule matches columns by name, so a rename is refused, as natively. */
  @Test
  public void renameColumnIsRefusedByTheNativeSchemaRuleAndChangesNothing() throws Exception {
    String t = fresh("rename_column");
    sql("CREATE TABLE " + t + " (id bigint, name string)");
    sql("INSERT INTO " + t + " VALUES (1,'a')");

    checkRefused(
        failure("ALTER TABLE " + t + " RENAME COLUMN name TO label"), "invalid schema evolution");

    StoredTable stored = storedTable("rename_column");
    check("columns", Arrays.asList("id long", "name string"), stored.columns());
    check("rows", Collections.singletonList("1,a"), stored.rows());
  }

  /** The native schema rule forbids dropping a column, so a drop is refused, as natively. */
  @Test
  public void dropColumnIsRefusedByTheNativeSchemaRuleAndChangesNothing() throws Exception {
    String t = fresh("drop_column");
    sql("CREATE TABLE " + t + " (id bigint, name string)");
    sql("INSERT INTO " + t + " VALUES (1,'a')");

    checkRefused(failure("ALTER TABLE " + t + " DROP COLUMN name"), "invalid schema evolution");

    StoredTable stored = storedTable("drop_column");
    check("columns", Arrays.asList("id long", "name string"), stored.columns());
    check("rows", Collections.singletonList("1,a"), stored.rows());
  }

  @Test
  public void tablePropertiesAreSetAndUnsetAndAReservedKeyIsRefused() throws Exception {
    String t = fresh("properties");
    sql("CREATE TABLE " + t + " (id bigint)");

    sql("ALTER TABLE " + t + " SET TBLPROPERTIES ('team'='x', 'tier'='gold')");
    check("team after set", "x", storedTable("properties").properties().get("team"));
    check("tier after set", "gold", storedTable("properties").properties().get("tier"));

    sql("ALTER TABLE " + t + " UNSET TBLPROPERTIES ('team')");
    check("team after unset", false, storedTable("properties").properties().containsKey("team"));
    check("tier after unset", "gold", storedTable("properties").properties().get("tier"));

    checkRefused(
        failure("ALTER TABLE " + t + " SET TBLPROPERTIES ('openhouse.foo'='hack')"),
        "table properties starting with `openhouse.` and the `policies` key cannot be modified");
    check(
        "openhouse.foo stored",
        false,
        storedTable("properties").properties().containsKey("openhouse.foo"));
  }

  /**
   * Spark adds the property {@code owner}, the user Spark runs as, to every table it creates
   * ({@code CatalogV2Util.withDefaultOwnership}, on Spark 3.5 as on 4.2), and Iceberg's catalog
   * classes pass it on as a table property. The service stores it. Spark hides it from SHOW
   * TBLPROPERTIES and keeps it from being set, so only the stored table shows it.
   */
  @Test
  public void aTableSparkCreatesStoresTheUserSparkRunsAsAsItsOwner() throws Exception {
    String t = fresh("owner");
    String ctas = fresh("owner_ctas");
    SparkSession spark = sparkSession();
    String sparkUser = spark.sparkContext().sparkUser();

    sql("CREATE TABLE " + t + " (id bigint)");
    sql("CREATE TABLE " + ctas + " AS SELECT 1L AS id");
    check("owner is the Spark user", true, sparkUser.equals(owner("owner")));
    check("owner of the CTAS table is the Spark user", true, sparkUser.equals(owner("owner_ctas")));

    // Not shown, and not a property a statement can name.
    check(
        "owner rows of SHOW TBLPROPERTIES",
        Collections.emptyList(),
        spark.sql("SHOW TBLPROPERTIES " + t).collectAsList().stream()
            .map(row -> row.getString(0))
            .filter("owner"::equals)
            .collect(Collectors.toList()));
    check(
        "SHOW TBLPROPERTIES ('owner')",
        "[owner,Table " + t + " does not have property: owner]",
        spark.sql("SHOW TBLPROPERTIES " + t + " ('owner')").collectAsList().get(0).toString());
    Throwable reserved = failure("ALTER TABLE " + t + " SET TBLPROPERTIES ('owner'='someone')");
    check(
        "setting owner fails with",
        "org.apache.spark.sql.catalyst.parser.ParseException",
        reserved.getClass().getName());
    check("owner after the refused set", true, sparkUser.equals(owner("owner")));

    // A later write keeps it.
    sql("INSERT INTO " + t + " VALUES (1)");
    sql("ALTER TABLE " + t + " SET TBLPROPERTIES ('team'='x')");
    check("owner after later writes", true, sparkUser.equals(owner("owner")));
  }

  private String owner(String table) throws Exception {
    return storedTable(table).properties().get("owner");
  }

  // ---------------------------------------------------------------- branches and staged writes

  @Test
  public void anInsertIntoABranchLeavesMainAsItWas() throws Exception {
    String t = fresh("branch_write");
    sql("CREATE TABLE " + t + " (id bigint)");
    sql("INSERT INTO " + t + " VALUES (1)");
    sql("ALTER TABLE " + t + " CREATE BRANCH b1");
    sql("INSERT INTO " + t + ".branch_b1 VALUES (2)");

    StoredTable stored = storedTable("branch_write");
    check("refs", Arrays.asList("b1=branch", "main=branch"), stored.refs());
    check("rows of main", Collections.singletonList("1"), stored.rows());
    check("rows of b1", Arrays.asList("1", "2"), stored.rows("b1"));
  }

  /** Write-audit-publish by id, as the Spark 3.5 itest's WapIdTest: staged, then published. */
  @Test
  public void aWriteWithAWapIdIsStagedAndCherryPickingPublishesIt() throws Exception {
    String t = fresh("wap_id");
    sql("CREATE TABLE " + t + " (name string)");
    sql("ALTER TABLE " + t + " SET TBLPROPERTIES ('write.wap.enabled'='true')");
    sql("INSERT INTO " + t + " VALUES ('main.a')");
    SparkSession spark = sparkSession();
    spark.conf().set("spark.wap.id", "wap1");
    try {
      sql("INSERT INTO " + t + " VALUES ('wap1.a')");
    } finally {
      spark.conf().unset("spark.wap.id");
    }

    StoredTable staged = storedTable("wap_id");
    check("rows of main, staged", Collections.singletonList("main.a"), staged.rows());
    check("operations, staged", Arrays.asList("append", "append"), staged.operations());
    check("wap.id of snapshot 2", "wap1", staged.summary(2).get("wap.id"));
    check("snapshot 2 reachable, staged", false, staged.reachable(2));
    check("rows of snapshot 2", Arrays.asList("main.a", "wap1.a"), staged.rows("snapshot 2"));

    sql(
        "CALL "
            + CATALOG
            + ".system.cherrypick_snapshot('"
            + DB
            + ".wap_id', "
            + staged.snapshotId(2)
            + ")");

    StoredTable published = storedTable("wap_id");
    check("rows of main, published", Arrays.asList("main.a", "wap1.a"), published.rows());
    check("snapshot 2 reachable, published", true, published.reachable(2));
    check("operations, published", Arrays.asList("append", "append"), published.operations());
  }

  /** Write-audit-publish by branch: the session's writes go to the branch it names. */
  @Test
  public void aWriteWithAWapBranchGoesToThatBranch() throws Exception {
    String t = fresh("wap_branch");
    sql("CREATE TABLE " + t + " (name string)");
    sql("ALTER TABLE " + t + " SET TBLPROPERTIES ('write.wap.enabled'='true')");
    sql("INSERT INTO " + t + " VALUES ('main.a')");
    SparkSession spark = sparkSession();
    spark.conf().set("spark.wap.branch", "staging");
    try {
      sql("INSERT INTO " + t + " VALUES ('staging.a')");
    } finally {
      spark.conf().unset("spark.wap.branch");
    }

    StoredTable stored = storedTable("wap_branch");
    check("refs", Arrays.asList("main=branch", "staging=branch"), stored.refs());
    check("rows of main", Collections.singletonList("main.a"), stored.rows());
    check("rows of staging", Arrays.asList("main.a", "staging.a"), stored.rows("staging"));
  }
}
