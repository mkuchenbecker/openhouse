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
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;

/**
 * The Spark SQL smoke suite (work item T1) as a list of statements with their baseline outcomes,
 * free of any engine version, catalog class or test fixture: it needs only {@code spark.sql} and
 * JUnit. The baseline is what Spark 3.5 does with the OpenHouse catalog on Iceberg 1.5.2, as {@code
 * SparkSqlSmokeTest} of the Spark 3.5 itest records it. Every action and every assertion goes
 * through {@code spark.sql}.
 *
 * <p>A platform runs the suite by extending this class: it supplies the sessions ({@link
 * #sparkSession()}, {@link #viewsSparkSession()}), the directory its service keeps tables in
 * ({@link #storageRoot()}) and the statements on which it differs from the baseline ({@link
 * #differences()}). A statement without a declared difference must give the baseline outcome. A
 * statement with one must give the outcome the difference states, the difference must quote the
 * baseline it departs from exactly as the scenario states it, and it must really differ. So a
 * platform's list of differences is exact: it cannot hide a difference, keep one that is gone, or
 * survive a change of the baseline unnoticed.
 *
 * <p>Every statement of a scenario is run even after one of them gave an unexpected outcome, and
 * the scenario then fails with all of them. With the system property {@code smoke.outcomes.file}
 * set, or another file named by {@link #outcomesFile()}, the run writes one line per statement to
 * that file: the statement, the baseline, this platform's outcome and whether they are the same.
 *
 * <p>This is the one list of scenarios (work item N7). It lives in the Spark 3.5 itest ({@code
 * src/shared/smoke/java}), whose {@code SparkSqlSmokeTest} runs it on each Spark 3.5 catalog path;
 * the Spark 4.2 itest compiles the same file into its smoke source set and runs it over IRC.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class SparkSqlSmokeScenarios {

  protected static final String DB = "smoke_t1";
  protected static final String CAT_DB = "openhouse." + DB;
  protected static final String VIEWS_DB = "smoke_t1_views";
  protected static final String VIEWS_CATALOG = "openhouse_views";

  // ---------- what a platform supplies ----------

  /** A session whose catalog {@code openhouse} is the platform's catalog path. */
  protected abstract SparkSession sparkSession() throws Exception;

  /**
   * A session whose catalog {@value #VIEWS_CATALOG} is the same catalog path against a service
   * where views are enabled for {@value #VIEWS_DB}. Views are off by default.
   */
  protected abstract SparkSession viewsSparkSession() throws Exception;

  /** The directory the service keeps tables in. The baseline fixture uses {@code /tmp}. */
  protected String storageRoot() {
    return "/tmp";
  }

  /**
   * The file the run writes its outcomes to, or null for none. By default the file the system
   * property {@code smoke.outcomes.file} names; a platform that runs the suite more than once in a
   * JVM names one file per run.
   */
  protected String outcomesFile() {
    return System.getProperty("smoke.outcomes.file");
  }

  /** The statements on which this platform differs from the baseline. None by default. */
  protected List<Difference> differences() {
    return Collections.emptyList();
  }

  // ---------- outcomes and expectations ----------

  /** What a statement did: the rows it returned, or the failure it raised. */
  public static final class Outcome {
    private final List<Row> raw;
    private final Throwable failure;

    private Outcome(List<Row> raw, Throwable failure) {
      this.raw = raw;
      this.failure = failure;
    }

    public boolean failed() {
      return failure != null;
    }

    /** Each row as comma-joined values (null renders as "null"); fails if the statement failed. */
    public List<String> rows() {
      if (failed()) {
        throw new AssertionError("the statement failed: " + this);
      }
      return raw.stream().map(r -> r.mkString(",")).collect(Collectors.toList());
    }

    /** The first value of every row, sorted. */
    public List<String> firstColumn() {
      if (failed()) {
        throw new AssertionError("the statement failed: " + this);
      }
      return raw.stream().map(r -> String.valueOf(r.get(0))).sorted().collect(Collectors.toList());
    }

    public Throwable failure() {
      return failure;
    }

    @Override
    public String toString() {
      return failed()
          ? "fails with " + failure.getClass().getName() + ": " + failure.getMessage()
          : "rows " + rows();
    }
  }

  /** What a statement is expected to do. */
  public abstract static class Expectation {
    private final String description;

    Expectation(String description) {
      this.description = description;
    }

    abstract boolean holds(Outcome outcome);

    @Override
    public String toString() {
      return description;
    }
  }

  /** The statement succeeds; what it returns is not looked at. */
  protected static Expectation succeeds() {
    return new Expectation("succeeds") {
      @Override
      boolean holds(Outcome outcome) {
        return !outcome.failed();
      }
    };
  }

  /** The statement returns exactly these rows, in this order. */
  protected static Expectation rows(String... expected) {
    List<String> rows = Arrays.asList(expected);
    return new Expectation("rows " + rows) {
      @Override
      boolean holds(Outcome outcome) {
        return !outcome.failed() && rows.equals(outcome.rows());
      }
    };
  }

  /**
   * The statement returns exactly as many rows as {@code regexes}, each matching its regular
   * expression in full, in this order. For a value the service draws at random.
   */
  protected static Expectation rowsMatching(String... regexes) {
    List<String> patterns = Arrays.asList(regexes);
    return new Expectation("rows matching " + patterns) {
      @Override
      boolean holds(Outcome outcome) {
        if (outcome.failed() || outcome.rows().size() != patterns.size()) {
          return false;
        }
        for (int i = 0; i < patterns.size(); i++) {
          if (!outcome.rows().get(i).matches(patterns.get(i))) {
            return false;
          }
        }
        return true;
      }
    };
  }

  /** The statement returns this row, among others or alone. */
  protected static Expectation hasRow(String row) {
    return new Expectation("has the row [" + row + "]") {
      @Override
      boolean holds(Outcome outcome) {
        return !outcome.failed() && outcome.rows().contains(row);
      }
    };
  }

  /** The statement returns a row that starts with {@code prefix}. */
  protected static Expectation hasRowStartingWith(String prefix) {
    return new Expectation("has a row starting with [" + prefix + "]") {
      @Override
      boolean holds(Outcome outcome) {
        return !outcome.failed() && outcome.rows().stream().anyMatch(r -> r.startsWith(prefix));
      }
    };
  }

  /** The statement succeeds and returns no row that starts with {@code prefix}. */
  protected static Expectation noRowStartingWith(String prefix) {
    return new Expectation("has no row starting with [" + prefix + "]") {
      @Override
      boolean holds(Outcome outcome) {
        return !outcome.failed() && outcome.rows().stream().noneMatch(r -> r.startsWith(prefix));
      }
    };
  }

  /** The first values of the statement's rows, sorted, are exactly these. */
  protected static Expectation firstColumn(List<String> sorted) {
    return new Expectation("first column, sorted, is " + sorted) {
      @Override
      boolean holds(Outcome outcome) {
        return !outcome.failed() && sorted.equals(outcome.firstColumn());
      }
    };
  }

  /**
   * The statement fails with an exception of exactly this class whose message contains the
   * fragment.
   */
  protected static Expectation fails(String exceptionClass, String messageFragment) {
    return new Expectation("fails with " + exceptionClass + ": ..." + messageFragment + "...") {
      @Override
      boolean holds(Outcome outcome) {
        return outcome.failed()
            && exceptionClass.equals(outcome.failure().getClass().getName())
            && String.valueOf(outcome.failure().getMessage()).contains(messageFragment);
      }
    };
  }

  /**
   * One statement on which a platform differs from the baseline: where, the baseline it departs
   * from, what the platform does instead, and why.
   */
  public static final class Difference {
    private final String scenario;
    private final String statement;
    private int occurrence = 1;
    private List<Expectation> baseline = Collections.emptyList();
    private List<Expectation> here = Collections.emptyList();
    private String kind = "";
    private String why = "";

    private Difference(String scenario, String statement) {
      this.scenario = scenario;
      this.statement = statement;
    }

    /**
     * A difference on {@code statement} of {@code scenario} (the scenario method's name up to its
     * first underscore, for example {@code s26}).
     */
    public static Difference on(String scenario, String statement) {
      return new Difference(scenario, statement);
    }

    /** Which run of the statement in the scenario, from 1, when it runs more than once. */
    public Difference occurrence(int number) {
      this.occurrence = number;
      return this;
    }

    /** The baseline outcome, exactly as the scenario states it. */
    public Difference baseline(Expectation... expectations) {
      this.baseline = Arrays.asList(expectations);
      return this;
    }

    /** What this platform does instead. */
    public Difference here(Expectation... expectations) {
      this.here = Arrays.asList(expectations);
      return this;
    }

    /** The kind of difference and the reason or evidence for it. */
    public Difference because(String differenceKind, String reason) {
      this.kind = differenceKind;
      this.why = reason;
      return this;
    }

    private String key() {
      return key(scenario, statement, occurrence);
    }

    private static String key(String scenario, String statement, int occurrence) {
      return scenario + " #" + occurrence + " " + statement;
    }
  }

  // ---------- running and recording ----------

  private Map<String, Difference> declared;
  private final Set<String> consulted = new LinkedHashSet<>();
  private final Set<String> scenariosRun = new LinkedHashSet<>();
  private final List<String> outcomeLines = new ArrayList<>();

  private String scenario;
  private Map<String, Integer> occurrences;
  private List<String> unexpected;
  private int statementNumber;

  @BeforeEach
  public void beginScenario(TestInfo info) {
    if (declared == null) {
      declared = new HashMap<>();
      for (Difference difference : differences()) {
        Assertions.assertNull(
            declared.put(difference.key(), difference),
            "Two differences declared for " + difference.key());
      }
    }
    String method = info.getTestMethod().map(m -> m.getName()).orElse("");
    scenario = method.contains("_") ? method.substring(0, method.indexOf('_')) : method;
    scenariosRun.add(scenario);
    occurrences = new HashMap<>();
    unexpected = new ArrayList<>();
    statementNumber = 0;
  }

  @AfterEach
  public void endScenario() {
    if (!unexpected.isEmpty()) {
      Assertions.fail(
          unexpected.size()
              + " unexpected outcome(s) in "
              + scenario
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
      Files.write(path, outcomeLines, StandardCharsets.UTF_8);
    }
    List<String> unused =
        (declared == null ? Collections.<String, Difference>emptyMap() : declared)
            .values().stream()
                .filter(d -> scenariosRun.contains(d.scenario))
                .map(Difference::key)
                .filter(key -> !consulted.contains(key))
                .sorted()
                .collect(Collectors.toList());
    Assertions.assertEquals(
        Collections.emptyList(), unused, "Differences declared for statements that were not run");
  }

  /** Runs a statement that the baseline expects to succeed. */
  protected final Outcome sql(SparkSession spark, String statement) {
    return sql(spark, statement, succeeds());
  }

  /** Runs a statement and checks its outcome against the baseline or the declared difference. */
  protected final Outcome sql(SparkSession spark, String statement, Expectation... baseline) {
    return sqlShownAs(statement, spark, statement, baseline);
  }

  /**
   * As {@link #sql(SparkSession, String, Expectation...)}, for a statement that holds a value of
   * this run (a snapshot id): {@code shown} is the statement with that value named, and it is what
   * a difference and the outcomes file quote.
   */
  protected final Outcome sqlShownAs(
      String shown, SparkSession spark, String statement, Expectation... baseline) {
    Outcome actual = run(spark, statement);
    statementNumber++;
    int occurrence = occurrences.merge(shown, 1, Integer::sum);
    String key = Difference.key(scenario, shown, occurrence);
    Difference difference = declared.get(key);
    List<Expectation> baselineList = Arrays.asList(baseline);
    boolean baselineHolds = baselineList.stream().allMatch(e -> e.holds(actual));
    String verdict;
    if (difference == null) {
      verdict = baselineHolds ? "same" : "UNEXPECTED";
      if (!baselineHolds) {
        unexpected.add(
            "  [" + key + "]\n    baseline: " + baselineList + "\n    here:     " + actual);
      }
    } else {
      consulted.add(key);
      verdict = "different: " + difference.kind;
      if (!baselineList.toString().equals(difference.baseline.toString())) {
        verdict = "UNEXPECTED";
        unexpected.add(
            "  ["
                + key
                + "]\n    the difference quotes a baseline the scenario does not state\n"
                + "    scenario:   "
                + baselineList
                + "\n    difference: "
                + difference.baseline);
      } else if (baselineHolds) {
        verdict = "UNEXPECTED";
        unexpected.add(
            "  ["
                + key
                + "]\n    a difference is declared but the outcome is the baseline's: "
                + actual);
      } else if (!difference.here.stream().allMatch(e -> e.holds(actual))) {
        verdict = "UNEXPECTED";
        unexpected.add(
            "  [" + key + "]\n    declared: " + difference.here + "\n    here:     " + actual);
      }
    }
    outcomeLines.add(
        String.join(
            "\t",
            scenario,
            String.valueOf(statementNumber),
            oneLine(shown),
            oneLine(baselineList.toString()),
            oneLine(actual.toString()),
            verdict,
            difference == null ? "" : oneLine(difference.why)));
    return actual;
  }

  private static Outcome run(SparkSession spark, String statement) {
    try {
      return new Outcome(spark.sql(statement).collectAsList(), null);
    } catch (Throwable failure) {
      return new Outcome(null, failure);
    }
  }

  private static String oneLine(String text) {
    return text.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
  }

  // ---------- helpers ----------

  private static String table(String name) {
    return CAT_DB + "." + name;
  }

  /** Setup, not part of a scenario: the tables a scenario creates do not exist yet. */
  private static void fresh(SparkSession spark, String... tables) {
    for (String t : tables) {
      spark.sql("DROP TABLE IF EXISTS " + t);
    }
  }

  private static final String TABLE_NOT_FOUND_CLASS =
      "org.apache.spark.sql.catalyst.ExtendedAnalysisException";
  private static final String TABLE_NOT_FOUND = "[TABLE_OR_VIEW_NOT_FOUND] The table or view";

  /** The baseline outcome of reading a table or view that does not exist. */
  protected static Expectation tableNotFound() {
    return fails(TABLE_NOT_FOUND_CLASS, TABLE_NOT_FOUND);
  }

  // ---------- scenarios ----------

  @Test
  public void s01_create_partitionedByDayAndString() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s01_create");
    fresh(spark, t);
    sql(
        spark,
        "CREATE TABLE "
            + t
            + " (id bigint, name string, ts timestamp) PARTITIONED BY (days(ts), name)");

    sql(
        spark,
        "DESCRIBE " + t,
        rows(
            "id,bigint,null",
            "name,string,null",
            "ts,timestamp,null",
            ",,",
            "# Partitioning,,",
            "Part 0,days(ts),",
            "Part 1,name,"));
    sql(spark, "SHOW TABLES IN " + CAT_DB + " LIKE 's01_create'", rows(DB + ",s01_create,false"));
    sql(
        spark,
        "SHOW TBLPROPERTIES " + t + " ('openhouse.tableId')",
        rows("openhouse.tableId,s01_create"));
    sql(
        spark,
        "SHOW TBLPROPERTIES " + t + " ('openhouse.databaseId')",
        rows("openhouse.databaseId," + DB));
    sql(
        spark,
        "SHOW TBLPROPERTIES " + t + " ('openhouse.tableType')",
        rows("openhouse.tableType,PRIMARY_TABLE"));
    sql(spark, "SHOW TBLPROPERTIES " + t + " ('format-version')", rows("format-version,2"));
    sql(
        spark,
        "SHOW TBLPROPERTIES " + t + " ('write.format.default')",
        rows("write.format.default,orc"));
    sql(spark, "SHOW TBLPROPERTIES " + t + " ('policies')", rows("policies,"));
  }

  @Test
  public void s02_insert_twice() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s02_insert");
    fresh(spark, t);
    sql(spark, "CREATE TABLE " + t + " (id bigint, name string)");
    sql(spark, "INSERT INTO " + t + " VALUES (1,'a'),(2,'b')");
    sql(spark, "INSERT INTO " + t + " VALUES (3,'c')");

    sql(spark, "SELECT * FROM " + t + " ORDER BY id", rows("1,a", "2,b", "3,c"));
    sql(spark, "SELECT count(*) FROM " + t, rows("3"));
    sql(spark, "SELECT count(*) FROM " + t + ".snapshots", rows("2"));
    sql(
        spark,
        "SELECT operation FROM " + t + ".snapshots ORDER BY operation",
        rows("append", "append"));
  }

  @Test
  public void s03_overwrite_onePartition() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s03_overwrite");
    fresh(spark, t);
    sql(spark, "CREATE TABLE " + t + " (id bigint, p string) PARTITIONED BY (p)");
    sql(spark, "INSERT INTO " + t + " VALUES (1,'x'),(2,'y')");
    sql(spark, "INSERT OVERWRITE " + t + " PARTITION (p='x') SELECT 10");

    sql(spark, "SELECT * FROM " + t + " ORDER BY id", rows("2,y", "10,x"));
    sql(
        spark,
        "SELECT operation FROM " + t + ".snapshots ORDER BY operation",
        rows("append", "overwrite"));
  }

  @Test
  public void s04_ctas_schemaAndRows() throws Exception {
    SparkSession spark = sparkSession();
    String src = table("s04_ctas_src");
    String t = table("s04_ctas");
    fresh(spark, t, src);
    sql(spark, "CREATE TABLE " + src + " (id bigint, name string)");
    sql(spark, "INSERT INTO " + src + " VALUES (1,'a'),(2,'b')");
    sql(spark, "CREATE TABLE " + t + " AS SELECT * FROM " + src);

    sql(spark, "DESCRIBE " + t, rows("id,bigint,null", "name,string,null"));
    sql(spark, "SELECT * FROM " + t + " ORDER BY id", rows("1,a", "2,b"));
  }

  @Test
  public void s05_replace_gatedByReplaceEnabled() throws Exception {
    SparkSession spark = sparkSession();
    String allowed = table("s05_replace_on");
    String gated = table("s05_replace_off");
    fresh(spark, allowed, gated);

    sql(spark, "CREATE TABLE " + allowed + " (id bigint, name string)");
    sql(spark, "INSERT INTO " + allowed + " VALUES (1,'a')");
    sql(spark, "ALTER TABLE " + allowed + " SET TBLPROPERTIES ('replace.enabled'='true')");
    sql(spark, "CREATE OR REPLACE TABLE " + allowed + " AS SELECT 7 AS id, 'z' AS name");
    sql(spark, "SELECT * FROM " + allowed, rows("7,z"));

    sql(spark, "CREATE TABLE " + gated + " (id bigint, name string)");
    sql(spark, "INSERT INTO " + gated + " VALUES (1,'a')");
    sql(
        spark,
        "CREATE OR REPLACE TABLE " + gated + " AS SELECT 7 AS id, 'z' AS name",
        fails(
            "org.apache.iceberg.exceptions.BadRequestException",
            "REPLACE TABLE AS SELECT is not enabled for table " + gated));
    sql(spark, "SELECT * FROM " + gated, rows("1,a"));
  }

  @Test
  public void s06_schema_addColumn() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s06_schema");
    fresh(spark, t);
    sql(spark, "CREATE TABLE " + t + " (id bigint)");
    sql(spark, "INSERT INTO " + t + " VALUES (1)");
    sql(spark, "ALTER TABLE " + t + " ADD COLUMN extra string");
    sql(spark, "INSERT INTO " + t + " VALUES (2,'v')");

    sql(spark, "SELECT * FROM " + t + " ORDER BY id", rows("1,null", "2,v"));
    sql(spark, "DESCRIBE " + t, rows("id,bigint,null", "extra,string,null"));
  }

  @Test
  public void s07_properties_setUnsetAndReserved() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s07_props");
    fresh(spark, t);
    sql(spark, "CREATE TABLE " + t + " (id bigint)");

    sql(spark, "ALTER TABLE " + t + " SET TBLPROPERTIES ('team'='x')");
    sql(spark, "SHOW TBLPROPERTIES " + t + " ('team')", rows("team,x"));

    sql(spark, "ALTER TABLE " + t + " UNSET TBLPROPERTIES ('team')");
    sql(spark, "SHOW TBLPROPERTIES " + t, noRowStartingWith("team,"));

    String restriction =
        "Bad tblproperties provided: Can't add, alter or drop table properties due to the"
            + " restriction: [table properties starting with `openhouse.` and the `policies` key"
            + " cannot be modified]";
    sql(
        spark,
        "ALTER TABLE " + t + " SET TBLPROPERTIES ('openhouse.foo'='hack')",
        fails("org.apache.iceberg.exceptions.BadRequestException", restriction));
    sql(
        spark,
        "ALTER TABLE " + t + " SET TBLPROPERTIES ('openhouse.tableId'='hack')",
        fails("org.apache.iceberg.exceptions.BadRequestException", restriction));
    sql(
        spark,
        "SHOW TBLPROPERTIES " + t + " ('openhouse.tableId')",
        rows("openhouse.tableId,s07_props"));
  }

  @Test
  public void s08_delete_withPredicate() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s08_delete");
    fresh(spark, t);
    sql(spark, "CREATE TABLE " + t + " (id bigint, name string)");
    sql(spark, "INSERT INTO " + t + " VALUES (1,'a'),(2,'b'),(3,'c')");
    sql(spark, "DELETE FROM " + t + " WHERE id = 2");

    sql(spark, "SELECT * FROM " + t + " ORDER BY id", rows("1,a", "3,c"));
    sql(
        spark,
        "SELECT operation FROM " + t + ".snapshots ORDER BY operation",
        rows("append", "overwrite"));
  }

  @Test
  public void s09_update_withPredicate() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s09_update");
    fresh(spark, t);
    sql(spark, "CREATE TABLE " + t + " (id bigint, name string)");
    sql(spark, "INSERT INTO " + t + " VALUES (1,'a'),(2,'b')");
    sql(spark, "UPDATE " + t + " SET name='B' WHERE id = 2");

    sql(spark, "SELECT * FROM " + t + " ORDER BY id", rows("1,a", "2,B"));
  }

  @Test
  public void s10_merge_mergeOnRead() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s10_merge");
    String src = table("s10_merge_src");
    fresh(spark, t, src);
    sql(
        spark,
        "CREATE TABLE "
            + t
            + " (id bigint, name string) TBLPROPERTIES ("
            + "'write.merge.mode'='merge-on-read','write.delete.mode'='merge-on-read',"
            + "'write.update.mode'='merge-on-read')");
    sql(spark, "CREATE TABLE " + src + " (id bigint, name string)");
    sql(spark, "INSERT INTO " + t + " VALUES (1,'a'),(2,'b')");
    sql(spark, "INSERT INTO " + src + " VALUES (2,'B'),(3,'c')");
    sql(
        spark,
        "MERGE INTO "
            + t
            + " t USING "
            + src
            + " s ON t.id = s.id"
            + " WHEN MATCHED THEN UPDATE SET t.name = s.name"
            + " WHEN NOT MATCHED THEN INSERT *");

    // Delete files are present (content 1 = position deletes).
    sql(spark, "SELECT content, record_count FROM " + t + ".delete_files", rows("1,1"));
    sql(
        spark,
        "SELECT operation FROM " + t + ".snapshots ORDER BY operation",
        rows("append", "overwrite"));
    sql(spark, "SELECT * FROM " + t + " ORDER BY id", rows("1,a", "2,B", "3,c"));
  }

  @Test
  public void s11_timeTravel_versionAsOf() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s11_timetravel");
    fresh(spark, t);
    sql(spark, "CREATE TABLE " + t + " (id bigint)");
    sql(spark, "INSERT INTO " + t + " VALUES (1)");
    sql(spark, "INSERT INTO " + t + " VALUES (2)");

    List<String> first =
        sql(spark, "SELECT snapshot_id FROM " + t + ".snapshots WHERE parent_id IS NULL").rows();
    Assertions.assertEquals(1, first.size());
    long firstSnapshot = Long.parseLong(first.get(0));

    sqlShownAs(
        "SELECT * FROM " + t + " VERSION AS OF <the first snapshot's id>",
        spark,
        "SELECT * FROM " + t + " VERSION AS OF " + firstSnapshot,
        rows("1"));
    sql(spark, "SELECT * FROM " + t + " ORDER BY id", rows("1", "2"));
  }

  @Test
  public void s12_branch_createAndInsert() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s12_branch");
    fresh(spark, t);
    sql(spark, "CREATE TABLE " + t + " (id bigint)");
    sql(spark, "INSERT INTO " + t + " VALUES (1)");
    sql(spark, "ALTER TABLE " + t + " CREATE BRANCH b1");
    sql(spark, "INSERT INTO " + t + ".branch_b1 VALUES (2)");

    sql(spark, "SELECT * FROM " + t + " ORDER BY id", rows("1"));
    sql(spark, "SELECT * FROM " + t + ".branch_b1 ORDER BY id", rows("1", "2"));
    sql(
        spark,
        "SELECT name, type FROM " + t + ".refs ORDER BY name",
        rows("b1,BRANCH", "main,BRANCH"));
  }

  @Test
  public void s13_rename_table() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s13_rename");
    String renamed = table("s13_renamed");
    fresh(spark, t, renamed);
    sql(spark, "CREATE TABLE " + t + " (id bigint)");
    sql(spark, "INSERT INTO " + t + " VALUES (1)");
    sql(spark, "ALTER TABLE " + t + " RENAME TO " + DB + ".s13_renamed");

    sql(spark, "SELECT * FROM " + t, tableNotFound());
    sql(spark, "SELECT * FROM " + renamed, rows("1"));
    sql(spark, "SHOW TABLES IN " + CAT_DB + " LIKE 's13_*'", rows(DB + ",s13_renamed,false"));
  }

  @Test
  public void s13b_rename_catalogQualifiedTarget() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s13b_rename");
    String renamed = table("s13b_renamed");
    fresh(spark, t, renamed);
    sql(spark, "CREATE TABLE " + t + " (id bigint)");
    sql(spark, "INSERT INTO " + t + " VALUES (1)");

    // Observed on the baseline: a target qualified with the catalog name is accepted (it does not
    // fail).
    sql(spark, "ALTER TABLE " + t + " RENAME TO " + renamed);

    sql(spark, "SELECT * FROM " + t, tableNotFound());
    sql(spark, "SELECT * FROM " + renamed, rows("1"));
    sql(spark, "SHOW TABLES IN " + CAT_DB + " LIKE 's13b_*'", rows(DB + ",s13b_renamed,false"));
  }

  @Test
  public void s14_drop_table() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s14_drop");
    fresh(spark, t);
    sql(spark, "CREATE TABLE " + t + " (id bigint)");
    sql(spark, "DROP TABLE " + t);

    sql(spark, "SELECT * FROM " + t, tableNotFound());
    sql(spark, "SHOW TABLES IN " + CAT_DB + " LIKE 's14_drop'", rows());
  }

  @Test
  public void s15_partitionEvolution_rejected() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s15_partevo");
    fresh(spark, t);
    sql(spark, "CREATE TABLE " + t + " (id bigint, ts timestamp) PARTITIONED BY (days(ts))");

    sql(
        spark,
        "ALTER TABLE " + t + " ADD PARTITION FIELD id",
        fails(
            "org.apache.iceberg.exceptions.BadRequestException",
            "Evolution of table partitioning and clustering columns are not supported"));
    sql(
        spark,
        "DESCRIBE " + t,
        rows("id,bigint,null", "ts,timestamp,null", ",,", "# Partitioning,,", "Part 0,days(ts),"));
  }

  @Test
  public void s16_policy_retention() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s16_policy");
    fresh(spark, t);
    sql(spark, "CREATE TABLE " + t + " (id bigint, ts timestamp) PARTITIONED BY (days(ts))");
    sql(spark, "ALTER TABLE " + t + " SET POLICY (RETENTION=30d)");

    sql(
        spark,
        "SHOW TBLPROPERTIES " + t + " ('policies')",
        rows(
            "policies,{\n  \"retention\": {\n    \"count\": 30,\n    \"granularity\": \"DAY\"\n  },\n"
                + "  \"sharingEnabled\": false\n}"));
  }

  @Test
  public void s17_grants_grantShowRevoke() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s17_grants");
    fresh(spark, t);
    sql(spark, "CREATE TABLE " + t + " (id bigint)");

    // Without the sharing policy a grant is rejected.
    sql(
        spark,
        "GRANT SELECT ON TABLE " + t + " TO smoke_user",
        fails("java.lang.IllegalArgumentException", DB + ".s17_grants is not a shared table"));

    sql(spark, "ALTER TABLE " + t + " SET POLICY (SHARING=TRUE)");
    sql(spark, "GRANT SELECT ON TABLE " + t + " TO smoke_user");
    // The baseline fixture cannot observe grants: with no OPA base URI configured,
    // OpaAuthorizationHandler (the AuthorizationHandler the embedded server uses) logs "Skipping
    // grant role" and its list methods return nothing, so SHOW GRANTS is empty even right after a
    // successful GRANT.
    sql(spark, "SHOW GRANTS ON TABLE " + t, rows());
    sql(spark, "REVOKE SELECT ON TABLE " + t + " FROM smoke_user");
    sql(spark, "SHOW GRANTS ON TABLE " + t, rows());
  }

  @Test
  public void s18_views_createSelectDrop() throws Exception {
    SparkSession spark = viewsSparkSession();
    String t = VIEWS_CATALOG + "." + VIEWS_DB + ".s18_views";
    String v = VIEWS_CATALOG + "." + VIEWS_DB + ".s18_views_v";
    fresh(spark, t);
    sql(spark, "CREATE TABLE " + t + " (id bigint)");
    sql(spark, "INSERT INTO " + t + " VALUES (1),(2)");

    // Views work where the views feature gate is open; s28 covers the default.
    sql(spark, "CREATE VIEW " + v + " AS SELECT id FROM " + t);
    sql(spark, "SELECT * FROM " + v + " ORDER BY id", rows("1", "2"));
    sql(
        spark,
        "SHOW VIEWS IN " + VIEWS_CATALOG + "." + VIEWS_DB + " LIKE 's18_views_v'",
        rows(VIEWS_DB + ",s18_views_v,false"));

    sql(spark, "DROP VIEW " + v);
    sql(spark, "SHOW VIEWS IN " + VIEWS_CATALOG + "." + VIEWS_DB + " LIKE 's18_views_v'", rows());
    sql(spark, "SELECT * FROM " + v, tableNotFound());
  }

  @Test
  public void s19_namespaces_showAndCreate() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s19_namespaces");
    fresh(spark, t);
    sql(spark, "CREATE TABLE " + t + " (id bigint)");

    sql(spark, "SHOW DATABASES IN openhouse", hasRow(DB));
    // Unsupported. The baseline message is Iceberg 1.5.2's; with the OpenHouse catalog on Iceberg
    // 1.12 it is "Checking if database exists is not supported" (decision D14b): Iceberg 1.12's
    // SparkCatalog checks existence with catalog.namespaceExists (apache/iceberg#14507), where
    // 1.5.2 left Spark's default, which describes the namespace.
    sql(
        spark,
        "CREATE NAMESPACE openhouse.smoke_t1_new_ns",
        fails("java.lang.UnsupportedOperationException", "Describing database is not supported"));
    sql(spark, "SHOW DATABASES IN openhouse LIKE 'smoke_t1_new_ns'", rows());
  }

  // ---------- scenarios added with the REST-shaped path (work item C1) ----------
  // Each records the baseline behavior for something the scenarios above do not pin and that a
  // REST client handles differently from the OpenHouse catalog unless the path keeps the baseline
  // behavior.

  /**
   * Property keys every new table gets on the baseline; values that vary (ids, times, paths) are
   * not pinned.
   */
  protected static final List<String> NEW_TABLE_PROPERTY_KEYS =
      Collections.unmodifiableList(
          Arrays.asList(
              "current-snapshot-id",
              "format",
              "format-version",
              "history.expire.max-ref-age-ms",
              "openhouse.clusterId",
              "openhouse.creationTime",
              "openhouse.databaseId",
              "openhouse.isTableReplicated",
              "openhouse.lastModifiedTime",
              "openhouse.tableCreator",
              "openhouse.tableId",
              "openhouse.tableLocation",
              "openhouse.tableType",
              "openhouse.tableUUID",
              "openhouse.tableUri",
              "openhouse.tableVersion",
              "policies",
              "write.format.default",
              "write.metadata.delete-after-commit.enabled",
              "write.metadata.previous-versions-max",
              "write.parquet.compression-codec"));

  @Test
  public void s21_ctas_tablePropertiesMatchCreate() throws Exception {
    SparkSession spark = sparkSession();
    String src = table("s21_ctas_src");
    String t = table("s21_ctas");
    fresh(spark, t, src);
    sql(spark, "CREATE TABLE " + src + " (id bigint, name string)");
    sql(spark, "INSERT INTO " + src + " VALUES (1,'a')");
    sql(spark, "CREATE TABLE " + t + " AS SELECT * FROM " + src);

    // CTAS gets exactly the properties a plain CREATE TABLE gets (no client-added keys such as
    // created-at), from a single create: the table version is still the initial one.
    sql(spark, "SHOW TBLPROPERTIES " + t, firstColumn(NEW_TABLE_PROPERTY_KEYS));
    sql(spark, "SHOW TBLPROPERTIES " + src, firstColumn(NEW_TABLE_PROPERTY_KEYS));
    sql(
        spark,
        "SHOW TBLPROPERTIES " + t + " ('openhouse.tableVersion')",
        rows("openhouse.tableVersion,INITIAL_VERSION"));
    sql(
        spark,
        "SHOW TBLPROPERTIES " + t + " ('openhouse.tableId')",
        rows("openhouse.tableId,s21_ctas"));
    sql(spark, "SELECT count(*) FROM " + t + ".snapshots", rows("1"));
  }

  @Test
  public void s22_rtas_keepsPropertiesAndHistory() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s22_rtas");
    String created = table("s22_rtas_new");
    fresh(spark, t, created);
    sql(spark, "CREATE TABLE " + t + " (id bigint, name string) TBLPROPERTIES ('team'='x')");
    sql(spark, "INSERT INTO " + t + " VALUES (1,'a')");
    sql(spark, "ALTER TABLE " + t + " SET TBLPROPERTIES ('replace.enabled'='true')");
    sql(
        spark,
        "CREATE OR REPLACE TABLE "
            + t
            + " TBLPROPERTIES ('extra'='y') AS SELECT 7L AS id, 'z' AS name, 'q' AS more");

    sql(spark, "SELECT * FROM " + t, rows("7,z,q"));
    sql(spark, "DESCRIBE " + t, rows("id,bigint,null", "name,string,null", "more,string,null"));
    // Existing properties survive a replace; the replace adds its own.
    sql(spark, "SHOW TBLPROPERTIES " + t + " ('team')", rows("team,x"));
    sql(spark, "SHOW TBLPROPERTIES " + t + " ('extra')", rows("extra,y"));
    sql(spark, "SHOW TBLPROPERTIES " + t + " ('replace.enabled')", rows("replace.enabled,true"));
    sql(
        spark,
        "SHOW TBLPROPERTIES " + t + " ('openhouse.creationTime')",
        rows("openhouse.creationTime,0"));
    // The snapshot history is kept.
    sql(
        spark,
        "SELECT operation FROM " + t + ".snapshots ORDER BY committed_at",
        rows("append", "append"));

    // CREATE OR REPLACE of a missing table creates it.
    sql(spark, "CREATE OR REPLACE TABLE " + created + " AS SELECT 1L AS id");
    sql(spark, "SELECT * FROM " + created, rows("1"));
  }

  @Test
  public void s23_replaceTable_gatedByReplaceEnabled() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s23_replace");
    fresh(spark, t);
    sql(spark, "CREATE TABLE " + t + " (id bigint)");

    // REPLACE TABLE without AS SELECT goes through the same gate.
    sql(
        spark,
        "REPLACE TABLE " + t + " (id bigint, x string)",
        fails(
            "org.apache.iceberg.exceptions.BadRequestException",
            "REPLACE TABLE AS SELECT is not enabled for table " + t));
    sql(spark, "DESCRIBE " + t, rows("id,bigint,null"));

    sql(spark, "ALTER TABLE " + t + " SET TBLPROPERTIES ('replace.enabled'='true')");
    sql(spark, "REPLACE TABLE " + t + " (id bigint, x string)");
    sql(spark, "DESCRIBE " + t, rows("id,bigint,null", "x,string,null"));
  }

  @Test
  public void s24_namespaces_otherStatementsUnsupported() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s24_namespaces");
    fresh(spark, t);
    sql(spark, "CREATE TABLE " + t + " (id bigint)");

    sql(
        spark,
        "DESCRIBE NAMESPACE " + CAT_DB,
        fails("java.lang.UnsupportedOperationException", "Describing database is not supported"));
    sql(
        spark,
        "ALTER NAMESPACE " + CAT_DB + " SET DBPROPERTIES ('k'='v')",
        fails(
            "java.lang.UnsupportedOperationException",
            "Set properties on a database is not supported"));
    // As CREATE NAMESPACE in s19: the baseline message is Iceberg 1.5.2's, and with the OpenHouse
    // catalog on Iceberg 1.12 it is "Checking if database exists is not supported" (decision D14b,
    // apache/iceberg#14507).
    sql(
        spark,
        "DROP NAMESPACE " + CAT_DB,
        fails("java.lang.UnsupportedOperationException", "Describing database is not supported"));
    sql(
        spark,
        "SHOW NAMESPACES IN " + CAT_DB,
        fails(
            "java.lang.UnsupportedOperationException",
            "Openhouse supports 2-lvl namespace <schema>.<table>"));
    sql(
        spark,
        "SHOW TABLES IN " + CAT_DB + " LIKE 's24_namespaces'",
        rows(DB + ",s24_namespaces,false"));
  }

  @Test
  public void s25_showTables_withoutDatabase() throws Exception {
    SparkSession spark = sparkSession();
    String noDatabase =
        "DatabaseId was not provided, for SQL please run \"SHOW TABLES IN <databaseId>\" instead";
    sql(
        spark,
        "SHOW TABLES IN openhouse",
        fails("org.apache.iceberg.exceptions.ValidationException", noDatabase));
    try {
      sql(spark, "USE openhouse");
      sql(
          spark,
          "SHOW TABLES",
          fails("org.apache.iceberg.exceptions.ValidationException", noDatabase));
    } finally {
      sql(spark, "USE spark_catalog");
    }
  }

  @Test
  public void s26_describeExtended_nameTypeProvider() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s26_describe");
    fresh(spark, t);
    sql(spark, "CREATE TABLE " + t + " (id bigint)");

    sql(
        spark,
        "DESCRIBE TABLE EXTENDED " + t,
        hasRow("Name," + t + ","),
        hasRow("Type,MANAGED,"),
        hasRow("Provider,iceberg,"),
        hasRowStartingWith("Location," + storageRoot() + "/" + DB + "/s26_describe-"));
  }

  @Test
  public void s27_unlock_unlockedAndMissingTable() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s27_unlock");
    fresh(spark, t);
    sql(spark, "CREATE TABLE " + t + " (id bigint)");
    spark.conf().set("spark.sql.openhouse.unlock.enabled", "true");
    try {
      // Unlocking a table that holds no lock is a no-op.
      sql(spark, "ALTER TABLE " + t + " UNLOCK");
      sql(
          spark,
          "ALTER TABLE " + table("s27_missing") + " UNLOCK",
          fails(
              "com.linkedin.openhouse.javaclient.exception.WebClientResponseWithMessageException",
              "User table " + DB + ".s27_missing cannot be found"));
    } finally {
      spark.conf().unset("spark.sql.openhouse.unlock.enabled");
    }
  }

  @Test
  public void s28_views_disabledByDefault() throws Exception {
    SparkSession spark = sparkSession();
    sql(
        spark,
        "CREATE VIEW " + table("s28_view") + " AS SELECT 1",
        fails(
            "org.apache.spark.sql.catalyst.analysis.NoSuchNamespaceException",
            "[SCHEMA_NOT_FOUND]"));
    sql(spark, "SHOW VIEWS IN " + CAT_DB, rows());
    sql(spark, "DROP VIEW IF EXISTS " + table("s28_view"));
  }

  @Test
  public void s29_rename_rejected() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s29_rename");
    fresh(spark, t);
    sql(spark, "CREATE TABLE " + t + " (id bigint)");

    sql(
        spark,
        "ALTER TABLE " + t + " RENAME TO " + DB + ".s29_rename",
        fails(
            "com.linkedin.openhouse.javaclient.exception.WebClientResponseWithMessageException",
            "Cannot rename a table to the same current db name and table name: s29_rename"));
    sql(
        spark,
        "ALTER TABLE " + t + " RENAME TO other_catalog." + DB + ".s29_renamed",
        fails(
            "java.lang.UnsupportedOperationException",
            "Cannot rename tables across catalogs: from="
                + t
                + ", to=other_catalog."
                + DB
                + ".s29_renamed"));
    sql(
        spark,
        "ALTER TABLE " + table("s29_missing") + " RENAME TO " + DB + ".s29_missing2",
        tableNotFound());
  }

  @Test
  public void s30_metadataTablesAndProcedure() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s30_meta");
    fresh(spark, t);
    sql(spark, "CREATE TABLE " + t + " (id bigint, name string)");
    sql(spark, "INSERT INTO " + t + " VALUES (1,'a')");
    sql(spark, "ALTER TABLE " + t + " ALTER COLUMN name COMMENT 'hello'");

    sql(spark, "SELECT count(*) FROM " + t + ".history", rows("1"));
    sql(spark, "SELECT count(*) FROM " + t + ".files", rows("1"));
    sql(spark, "DESCRIBE " + t, rows("id,bigint,null", "name,string,hello"));
    sql(
        spark,
        "CALL openhouse.system.expire_snapshots(table => '" + DB + ".s30_meta', retain_last => 1)",
        rows("0,0,0,0,0,0"));
  }

  // ---------- scenarios added with the plain REST path (work item N1-c) ----------

  /**
   * Every form of the OpenHouse policy statements that s16 (retention) and s17 (sharing) do not
   * run, each followed by the stored {@code policies}: the form reaches the service and stores what
   * it stores on the baseline.
   */
  @Test
  public void s31_policy_everyForm() throws Exception {
    SparkSession spark = sparkSession();
    String t = table("s31_policy");
    String c = table("s31_policy_col");
    fresh(spark, t, c);
    sql(
        spark,
        "CREATE TABLE " + t + " (id bigint, name string, ts timestamp) PARTITIONED BY (days(ts))");
    sql(spark, "CREATE TABLE " + c + " (id bigint, name string)");
    String policies = " ('policies')";

    sql(spark, "ALTER TABLE " + t + " SET POLICY (HISTORY MAX_AGE=3d VERSIONS=5)");
    String history =
        "  \"history\": {\n    \"maxAge\": 3,\n    \"granularity\": \"DAY\",\n"
            + "    \"versions\": 5\n  }\n}";
    String noReplication = "  \"replication\": {\n    \"config\": []\n  },\n";
    sql(
        spark,
        "SHOW TBLPROPERTIES " + t + policies,
        rows("policies,{\n  \"sharingEnabled\": false,\n" + history));
    sql(spark, "ALTER TABLE " + t + " SET POLICY (REPLICATION=({destination:'a', interval:12h}))");
    // The service draws the replication schedule's minute at random; the rest is pinned. The
    // destination is stored as the statement's literal, quotes kept and upper-cased.
    sql(
        spark,
        "SHOW TBLPROPERTIES " + t + policies,
        rowsMatching(
            Pattern.quote(
                    "policies,{\n  \"sharingEnabled\": false,\n  \"replication\": {\n"
                        + "    \"config\": [\n      {\n        \"destination\": \"'A'\",\n"
                        + "        \"interval\": \"12H\",\n        \"cronSchedule\": \"")
                + "0 [0-9]+ [0-9]+/12 \\? \\* \\* \\*"
                + Pattern.quote("\"\n      }\n    ]\n  },\n" + history)));
    sql(spark, "ALTER TABLE " + t + " UNSET POLICY (REPLICATION)");
    sql(
        spark,
        "SHOW TBLPROPERTIES " + t + policies,
        rows("policies,{\n  \"sharingEnabled\": false,\n" + noReplication + history));
    // The service writes a column's tags in the declaration order of its tag enum, PII then HC,
    // whatever order the statement lists them in (work item N8; before, the order changed from one
    // service JVM to the next).
    String bothTags =
        "policies,{\n  \"sharingEnabled\": false,\n  \"columnTags\": {\n    \"name\": {\n"
            + "      \"tags\": [\n        \"PII\",\n        \"HC\"\n      ]\n    }\n  },\n"
            + noReplication
            + history;
    sql(spark, "ALTER TABLE " + t + " MODIFY COLUMN name SET TAG = (HC, PII)");
    sql(spark, "SHOW TBLPROPERTIES " + t + policies, rows(bothTags));
    sql(spark, "ALTER TABLE " + t + " MODIFY COLUMN name SET TAG = (PII, HC)");
    sql(spark, "SHOW TBLPROPERTIES " + t + policies, rows(bothTags));
    sql(spark, "ALTER TABLE " + t + " MODIFY COLUMN name SET TAG = (NONE)");
    sql(
        spark,
        "SHOW TBLPROPERTIES " + t + policies,
        rows(
            "policies,{\n  \"sharingEnabled\": false,\n  \"columnTags\": {\n    \"name\": {\n"
                + "      \"tags\": []\n    }\n  },\n"
                + noReplication
                + history));

    // The pattern is stored with its quotes without WITH TIMEZONE, and without them with it.
    sql(
        spark,
        "ALTER TABLE "
            + c
            + " SET POLICY (RETENTION=30d ON COLUMN name WHERE PATTERN='yyyy-MM-dd')");
    sql(
        spark,
        "SHOW TBLPROPERTIES " + c + policies,
        rows(
            "policies,{\n  \"retention\": {\n    \"count\": 30,\n    \"granularity\": \"DAY\",\n"
                + "    \"columnPattern\": {\n      \"columnName\": \"name\",\n"
                + "      \"pattern\": \"'yyyy-MM-dd'\"\n    }\n  },\n  \"sharingEnabled\": false\n}"));
    sql(
        spark,
        "ALTER TABLE "
            + c
            + " SET POLICY (RETENTION=7d WITH TIMEZONE 'America/Los_Angeles' ON COLUMN name"
            + " WHERE PATTERN='yyyy-MM-dd')");
    sql(
        spark,
        "SHOW TBLPROPERTIES " + c + policies,
        rows(
            "policies,{\n  \"retention\": {\n    \"count\": 7,\n    \"granularity\": \"DAY\",\n"
                + "    \"columnPattern\": {\n      \"columnName\": \"name\",\n"
                + "      \"pattern\": \"yyyy-MM-dd\"\n    },\n"
                + "    \"timeZone\": \"America/Los_Angeles\"\n  },\n  \"sharingEnabled\": false\n}"));
  }

  @Test
  public void s20_missingTable_select() throws Exception {
    SparkSession spark = sparkSession();
    sql(spark, "SELECT * FROM " + table("s20_does_not_exist"), tableNotFound());
  }
}
