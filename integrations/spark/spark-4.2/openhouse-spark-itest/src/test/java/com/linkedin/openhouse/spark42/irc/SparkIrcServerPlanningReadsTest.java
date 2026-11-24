package com.linkedin.openhouse.spark42.irc;

import static com.linkedin.openhouse.spark42.irc.IrcSpark.rows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.iceberg.Table;
import org.apache.iceberg.spark.Spark3Util;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Spark 4.2 reads the same rows with server-side planning as with client-side planning, for the
 * reads {@link SparkIrcScanPlanningTest} does not cover (work item V11b): a join of two planned
 * tables, aggregates with pushed filters, time travel by snapshot id and by timestamp, branch and
 * tag reads (with a column added after them), the {@code files}, {@code snapshots}, {@code entries}
 * and {@code partitions} metadata tables, an incremental read, a table of a few thousand files, and
 * a LIMIT over files whose rows are partly deleted. The same tables are read through two catalogs
 * of one service: {@code pr_client} ({@code scan-planning-mode=client}) and {@code pr_server}
 * ({@code scan-planning-mode=server}).
 *
 * <p>Some of these reads never plan through the route, whichever catalog: Iceberg 1.12's {@code
 * RESTTable} plans only its own table scans on the server, so metadata tables and incremental reads
 * plan on the client. The tests pin which reads sent a plan request.
 */
public class SparkIrcServerPlanningReadsTest {

  private static final String DB = SparkIrcScanPlanningTest.DB;
  private static final String CLIENT = "pr_client";
  private static final String SERVER = "pr_server";
  private static final String ORDERS = DB + ".r_orders";
  private static final String CUSTS = DB + ".r_custs";
  private static final String MANY = DB + ".r_many";
  private static final int MANY_FILES = 2500;

  private static long[] snapshots;

  @BeforeAll
  static void createTheTables() throws Exception {
    SparkSession spark = IrcSpark.session();
    SparkIrcServerPlanningWritesTest.catalog(spark, CLIENT, IrcSpark.service(), "client");
    SparkIrcServerPlanningWritesTest.catalog(spark, SERVER, IrcSpark.service(), "server");
    String orders = CLIENT + "." + ORDERS;
    String custs = CLIENT + "." + CUSTS;
    rows("DROP TABLE IF EXISTS " + orders);
    rows("DROP TABLE IF EXISTS " + custs);
    rows("DROP TABLE IF EXISTS " + CLIENT + "." + MANY);
    rows(
        "CREATE TABLE "
            + orders
            + " (id bigint, cust bigint, amount int, p string) USING iceberg PARTITIONED BY (p)"
            + " TBLPROPERTIES ('write.delete.mode'='merge-on-read')");
    rows("CREATE TABLE " + custs + " (cust bigint, name string) USING iceberg");
    rows("INSERT INTO " + custs + " VALUES (1,'c1'),(2,'c2'),(3,'c3'),(4,'c4')");
    // Four appends of eight rows, two partitions each: ids 1-32, amount = id * 3.
    for (int append = 0; append < 4; append++) {
      StringBuilder values = new StringBuilder();
      for (int i = 1; i <= 8; i++) {
        long id = append * 8L + i;
        values.append(values.length() == 0 ? "" : ",");
        values.append(
            "("
                + id
                + ","
                + (id % 4 + 1)
                + ","
                + (id * 3)
                + ",'"
                + (id % 2 == 0 ? "a" : "b")
                + "')");
      }
      rows("INSERT INTO " + orders + " VALUES " + values);
    }
    // Part of the rows of most files: merge-on-read position deletes.
    rows("DELETE FROM " + orders + " WHERE id IN (2, 3, 4, 11, 12, 20, 27, 28, 29)");

    List<Long> ids = new ArrayList<>();
    for (List<Object> row :
        rows("SELECT snapshot_id FROM " + orders + ".snapshots ORDER BY committed_at")) {
      ids.add((Long) row.get(0));
    }
    snapshots = ids.stream().mapToLong(Long::longValue).toArray();

    // A branch and a tag at the third append, then a column added on main, then one more append:
    // the branch reads with the table's schema, the tag with its snapshot's.
    Table table = Spark3Util.loadIcebergTable(spark, orders);
    table.manageSnapshots().createBranch("b1", snapshots[2]).createTag("t1", snapshots[2]).commit();
    rows("ALTER TABLE " + orders + " ADD COLUMN note string");
    rows("INSERT INTO " + orders + " VALUES (33, 1, 99, 'a', 'late')");

    // A few thousand files: one file per value of k.
    rows(
        "CREATE TABLE "
            + CLIENT
            + "."
            + MANY
            + " (id bigint, k int) USING iceberg PARTITIONED BY (k)");
    rows(
        "INSERT INTO "
            + CLIENT
            + "."
            + MANY
            + " SELECT id, CAST(id % "
            + MANY_FILES
            + " AS int) FROM range(0, "
            + (MANY_FILES * 2)
            + ")");
  }

  @AfterAll
  static void dropTheTables() throws Exception {
    for (String table : new String[] {ORDERS, CUSTS, MANY}) {
      rows("DROP TABLE IF EXISTS " + CLIENT + "." + table);
    }
  }

  /** The plan requests the shared service logged so far. */
  private static List<String> planRequests() throws Exception {
    Path access = IrcSpark.service().work().resolve("access");
    List<String> lines = new ArrayList<>();
    try (Stream<Path> files = Files.list(access)) {
      for (Path file : files.collect(Collectors.toList())) {
        lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return lines.stream()
        .filter(l -> l.matches(".*/tables/[^/ ]+/(plan|tasks)[/ ].*"))
        .collect(Collectors.toList());
  }

  /**
   * The number of plan requests logged, once it has stopped changing for half a second: the access
   * log is written after the response, so the last request's line can come late.
   */
  private static int settledPlanRequests() throws Exception {
    int seen = planRequests().size();
    for (int stable = 0; stable < 10; stable++) {
      Thread.sleep(50);
      int now = planRequests().size();
      if (now != seen) {
        seen = now;
        stable = -1;
      }
    }
    return seen;
  }

  /** The access log is written after the response: waits until it has at least {@code n}. */
  private static int awaitPlanRequests(int n) throws Exception {
    long deadline = System.nanoTime() + 10_000_000_000L;
    int seen = planRequests().size();
    while (seen < n && System.nanoTime() < deadline) {
      Thread.sleep(50);
      seen = planRequests().size();
    }
    return seen;
  }

  /**
   * Runs {@code query} ({@code %s} for each table, in order) through both catalogs, asserts the
   * same rows, and returns them with the number of plan requests the server-planned run sent.
   */
  private static List<List<Object>> assertSame(String query, String... tables) throws Exception {
    List<List<Object>> onClient = rows(String.format(query, qualified(CLIENT, tables)));
    List<List<Object>> onServer = rows(String.format(query, qualified(SERVER, tables)));
    Assertions.assertEquals(onClient, onServer, query);
    Assertions.assertFalse(onClient.isEmpty(), "no rows: " + query);
    return onClient;
  }

  private static Object[] qualified(String catalog, String... tables) {
    return Arrays.stream(tables).map(t -> catalog + "." + t).toArray();
  }

  /** Asserts that {@code query} sent plan requests through the server catalog, or none. */
  private static void assertPlannedOnServer(boolean expected, String query, String... tables)
      throws Exception {
    int before = settledPlanRequests();
    assertSame(query, tables);
    int after = expected ? awaitPlanRequests(before + 1) : settledPlanRequests();
    Assertions.assertEquals(expected, after > before, query + ": " + before + " -> " + after);
  }

  @Test
  void aJoinOfTwoPlannedTables() throws Exception {
    assertPlannedOnServer(
        true,
        "SELECT o.id, c.name, o.amount FROM %s o JOIN %s c ON o.cust = c.cust"
            + " WHERE o.amount > 10 ORDER BY o.id",
        ORDERS,
        CUSTS);
    assertSame(
        "SELECT c.name, count(*), sum(o.amount) FROM %s o JOIN %s c ON o.cust = c.cust"
            + " WHERE o.p = 'a' GROUP BY c.name ORDER BY c.name",
        ORDERS, CUSTS);
  }

  @Test
  void aggregatesWithPushedFilters() throws Exception {
    // With deletes, Iceberg reads the rows; without, it can answer from the column stats.
    for (String query :
        new String[] {
          "SELECT count(*), min(amount), max(amount), sum(amount) FROM %s WHERE p = 'a'",
          "SELECT count(*) FROM %s",
          "SELECT p, count(*), max(id) FROM %s WHERE amount BETWEEN 10 AND 70 GROUP BY p ORDER BY p",
          "SELECT count(*), min(id), max(id) FROM %s WHERE id >= 9"
        }) {
      assertSame(query, ORDERS);
    }
    for (String query :
        new String[] {
          "SELECT count(*), min(cust), max(cust) FROM %s",
          "SELECT count(*), max(name) FROM %s WHERE cust > 1"
        }) {
      assertSame(query, CUSTS);
    }
  }

  @Test
  void timeTravelBySnapshotIdAndByTimestamp() throws Exception {
    for (int i = 0; i < snapshots.length; i++) {
      assertSame("SELECT * FROM %s VERSION AS OF " + snapshots[i] + " ORDER BY id", ORDERS);
    }
    List<List<Object>> committed =
        rows("SELECT committed_at FROM " + CLIENT + "." + ORDERS + ".snapshots ORDER BY 1");
    for (List<Object> at : committed) {
      long millis = ((Timestamp) at.get(0)).getTime();
      assertSame(
          "SELECT id, amount FROM %s TIMESTAMP AS OF timestamp_millis(" + millis + ") ORDER BY id",
          ORDERS);
    }
  }

  @Test
  void branchAndTagReads() throws Exception {
    List<List<Object>> branch =
        assertSame("SELECT * FROM %s VERSION AS OF 'b1' ORDER BY id", ORDERS);
    List<List<Object>> tag = assertSame("SELECT * FROM %s VERSION AS OF 't1' ORDER BY id", ORDERS);
    // The branch reads with the table's schema (the added column, null), the tag with its own.
    Assertions.assertEquals(5, branch.get(0).size(), branch.toString());
    Assertions.assertEquals(4, tag.get(0).size(), tag.toString());
    Assertions.assertEquals(24, branch.size());
    assertSame("SELECT id FROM %s WHERE p = 'b' ORDER BY id", ORDERS + ".branch_b1");
    assertSame("SELECT id, amount FROM %s ORDER BY id", ORDERS + ".tag_t1");
  }

  @Test
  void metadataTablesPlanOnTheClientAndReadTheSame() throws Exception {
    assertPlannedOnServer(
        false,
        "SELECT content, file_path, record_count FROM %s ORDER BY file_path",
        ORDERS + ".files");
    assertSame(
        "SELECT snapshot_id, parent_id, operation FROM %s ORDER BY committed_at",
        ORDERS + ".snapshots");
    assertSame(
        "SELECT status, snapshot_id, sequence_number, data_file.file_path FROM %s ORDER BY 4, 2",
        ORDERS + ".entries");
    assertSame(
        "SELECT CAST(partition AS STRING), record_count, file_count FROM %s ORDER BY 1",
        ORDERS + ".partitions");
  }

  @Test
  void anIncrementalReadPlansOnTheClientAndReadsTheSame() throws Exception {
    SparkSession spark = IrcSpark.session();
    List<List<Object>> both = new ArrayList<>();
    int before = settledPlanRequests();
    for (String catalog : new String[] {CLIENT, SERVER}) {
      List<List<Object>> read = new ArrayList<>();
      for (Row row :
          spark
              .read()
              .format("iceberg")
              .option("start-snapshot-id", String.valueOf(snapshots[0]))
              .option("end-snapshot-id", String.valueOf(snapshots[3]))
              .load(catalog + "." + ORDERS)
              .orderBy("id")
              .collectAsList()) {
        read.add(Arrays.asList(row.get(0), row.get(1)));
      }
      Assertions.assertEquals(24, read.size(), catalog);
      both.add(read.stream().flatMap(List::stream).collect(Collectors.toList()));
    }
    Assertions.assertEquals(both.get(0), both.get(1));
    Assertions.assertEquals(before, settledPlanRequests(), "incremental reads plan on the client");
  }

  @Test
  void aTableOfAFewThousandFiles() throws Exception {
    List<List<Object>> files = rows("SELECT count(*) FROM " + CLIENT + "." + MANY + ".files");
    Assertions.assertEquals(Arrays.asList(Arrays.asList((Object) (long) MANY_FILES)), files);
    assertPlannedOnServer(true, "SELECT count(*), sum(id), max(k) FROM %s", MANY);
    assertSame("SELECT * FROM %s WHERE k IN (7, 1999) ORDER BY id", MANY);
    assertSame("SELECT k, count(*) FROM %s WHERE id > 4000 GROUP BY k ORDER BY k LIMIT 5", MANY);
    Assertions.assertEquals(
        Arrays.asList(Arrays.asList((Object) 10L)),
        rows("SELECT count(*) FROM (SELECT * FROM " + SERVER + "." + MANY + " LIMIT 10)"));
  }

  /** A LIMIT without ORDER BY over files whose rows are partly deleted: never fewer rows. */
  @Test
  void aLimitOverPartlyDeletedFiles() throws Exception {
    List<List<Object>> all = rows("SELECT * FROM " + CLIENT + "." + ORDERS);
    Assertions.assertEquals(24, all.size());
    for (int limit = 1; limit <= all.size() + 1; limit++) {
      for (String catalog : new String[] {CLIENT, SERVER}) {
        List<List<Object>> limited =
            rows("SELECT * FROM " + catalog + "." + ORDERS + " LIMIT " + limit);
        Assertions.assertEquals(Math.min(limit, all.size()), limited.size(), catalog + " " + limit);
        Assertions.assertTrue(all.containsAll(limited), limited.toString());
      }
      List<List<Object>> inB =
          rows("SELECT id FROM " + SERVER + "." + ORDERS + " WHERE p = 'b' LIMIT " + limit);
      long allInB = all.stream().filter(r -> "b".equals(r.get(3))).count();
      Assertions.assertEquals(Math.min(limit, allInB), inB.size(), "b " + limit);
    }
  }

  /** What a statement gives: its rows, or the class of its failure, as one comparable value. */
  private static String outcome(String sql) {
    try {
      return "rows " + rows(sql);
    } catch (Throwable t) {
      return "fails " + t.getClass().getSimpleName();
    }
  }

  private static String assertSameOutcome(String query, String table) {
    String onClient = outcome(String.format(query, CLIENT + "." + table));
    Assertions.assertEquals(onClient, outcome(String.format(query, SERVER + "." + table)), query);
    return onClient;
  }

  /**
   * A column added after the snapshot a read pins (V11e). Spark 4.2 pins a read of main to the
   * current snapshot and projects the table's current schema, so Iceberg 1.12's {@code
   * RESTTableScan} sends {@code use-snapshot-schema=true} with names the snapshot's schema lacks.
   * The service now projects the current schema's columns there, as client planning does, so each
   * statement gives what client planning gives: with no snapshot since the column was added (a
   * filter true for nulls, one false for nulls, a projection only), with one snapshot written
   * since, and time travel to the older snapshot naming the newer column. Until V11e the filters
   * with no snapshot since were a 400 with server planning.
   */
  @Test
  void aColumnAddedAfterTheCurrentSnapshotReadsTheSameAsClientPlanning() throws Exception {
    String table = DB + ".r_evolve";
    rows("DROP TABLE IF EXISTS " + CLIENT + "." + table);
    rows("CREATE TABLE " + CLIENT + "." + table + " (id bigint) USING iceberg");
    rows("INSERT INTO " + CLIENT + "." + table + " VALUES (1), (2)");
    long first =
        (Long) rows("SELECT snapshot_id FROM " + CLIENT + "." + table + ".snapshots").get(0).get(0);
    rows("ALTER TABLE " + CLIENT + "." + table + " ADD COLUMN extra int");
    rows("REFRESH TABLE " + SERVER + "." + table);
    try {
      String trueForNulls = "SELECT id, extra FROM %s WHERE extra IS NULL ORDER BY id";
      String falseForNulls = "SELECT id FROM %s WHERE extra = 7 ORDER BY id";
      String projection = "SELECT id, extra FROM %s ORDER BY id";
      // No snapshot since the column was added.
      Assertions.assertEquals(
          "rows [[1, null], [2, null]]", assertSameOutcome(trueForNulls, table));
      Assertions.assertEquals("rows []", assertSameOutcome(falseForNulls, table));
      Assertions.assertEquals("rows [[1, null], [2, null]]", assertSameOutcome(projection, table));
      // One snapshot written since.
      rows("INSERT INTO " + CLIENT + "." + table + " VALUES (3, 7)");
      rows("REFRESH TABLE " + SERVER + "." + table);
      Assertions.assertEquals(
          "rows [[1, null], [2, null]]", assertSameOutcome(trueForNulls, table));
      Assertions.assertEquals("rows [[3]]", assertSameOutcome(falseForNulls, table));
      Assertions.assertEquals(
          "rows [[1, null], [2, null], [3, 7]]", assertSameOutcome(projection, table));
      // Time travel to the snapshot before the column: client planning's answer is the reference.
      for (String query :
          new String[] {
            "SELECT id, extra FROM %s VERSION AS OF " + first + " ORDER BY id",
            "SELECT id FROM %s VERSION AS OF " + first + " WHERE extra IS NULL ORDER BY id",
            "SELECT id FROM %s VERSION AS OF " + first + " ORDER BY id"
          }) {
        System.out.println("TIME-TRAVEL " + query + ": " + assertSameOutcome(query, table));
      }
    } finally {
      rows("DROP TABLE IF EXISTS " + CLIENT + "." + table);
    }
  }
}
