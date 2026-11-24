package com.linkedin.openhouse.spark42.irc;

import static com.linkedin.openhouse.spark42.irc.IrcSpark.rows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Spark 4.2's row-level writes leave the same table whether their scans are planned on the client
 * or on the server (work item V11b). Each scenario is a list of statements run twice, from scratch:
 * once through a catalog that plans on the client, on tables named {@code <scenario>_c}, and once
 * through a catalog of the same service with {@code scan-planning-mode=server}, on tables named
 * {@code <scenario>_s}. Every statement of a run goes through that run's catalog, so each scan of a
 * write (the copy-on-write rewrite, the merge-on-read delete scan, the MERGE join, the SELECT of an
 * INSERT OVERWRITE or a CTAS) is planned the one way or the other. Then the two tables must be the
 * same: their rows, their data and delete files (content, format, record count, partition, and for
 * a deletion vector its size), and the operation and counts of every snapshot. Both tables are read
 * back through the client-planning catalog, so what is compared is what the writes stored.
 *
 * <p>Format v3: the service refuses to plan v3 tables (work item V11e; a scan task carries no
 * sequence number, so row lineage would be wrong). The same scenarios on v3 assert that the
 * statement under test fails visibly with the refusal and leaves the table, its row lineage
 * included, as it was. Until V11e they compared the stored tables, and two tests pinned the wrong
 * lineage; that subject no longer exists.
 *
 * <p>Every table first gets a merge-on-read DELETE of one row, so the statement under test plans
 * files that already have a position delete or a deletion vector: a copy-on-write rewrite must not
 * bring the deleted row back, and a second merge-on-read delete on format v3 must merge with the
 * deletion vector the file has.
 *
 * <p>Format v2 on the shared service, format v3 on the service with the cluster switch on. The
 * client catalogs are set to {@code scan-planning-mode=client} explicitly, so the comparison holds
 * in the {@code testServerPlanning} run too, where every other catalog plans on the server. Tables
 * are in {@link SparkIrcScanPlanningTest#DB}, the database whose plan requests {@link
 * SparkIrcNoPlanRequestTest} expects.
 */
public class SparkIrcServerPlanningWritesTest {

  private static final String DB = SparkIrcScanPlanningTest.DB;

  enum Format {
    V2("pw_client", "pw_server", "2"),
    V3("pw3_client", "pw3_server", "3");

    final String client;
    final String server;
    final String version;

    Format(String client, String server, String version) {
      this.client = client;
      this.server = server;
      this.version = version;
    }
  }

  @BeforeAll
  static void addTheCatalogs() throws Exception {
    SparkSession spark = IrcSpark.session();
    IrcReadTestServiceProcess v3 = IrcReadTestServiceProcess.getWithFormatV3();
    catalog(spark, Format.V2.client, IrcSpark.service(), "client");
    catalog(spark, Format.V2.server, IrcSpark.service(), "server");
    catalog(spark, Format.V3.client, v3, "client");
    catalog(spark, Format.V3.server, v3, "server");
  }

  static void catalog(
      SparkSession spark, String name, IrcReadTestServiceProcess service, String mode) {
    String prefix = "spark.sql.catalog." + name;
    spark.conf().set(prefix, "org.apache.iceberg.spark.SparkCatalog");
    spark.conf().set(prefix + ".type", "rest");
    spark.conf().set(prefix + ".uri", service.uri() + "/iceberg");
    spark.conf().set(prefix + ".token", service.token(IrcSpark.READER));
    spark.conf().set(prefix + ".scan-planning-mode", mode);
  }

  /** The statements every scenario starts with; {@code %t} is the table. */
  private static List<String> seed(Format format, String mode) {
    return Arrays.asList(
        "DROP TABLE IF EXISTS %t",
        "CREATE TABLE %t (id bigint, name string, p string) USING iceberg PARTITIONED BY (p)"
            + " TBLPROPERTIES ('format-version'='"
            + format.version
            + "', 'write.delete.mode'='merge-on-read', 'write.update.mode'='merge-on-read',"
            + " 'write.merge.mode'='merge-on-read')",
        "INSERT INTO %t VALUES (1,'n1','a'),(2,'n2','a'),(3,'n3','b'),(4,'n4','b')",
        "INSERT INTO %t VALUES (5,'n5','a'),(6,'n6','b'),(7,'n7','c')",
        "INSERT INTO %t VALUES (8,'n8','a'),(9,'n9','c'),(10,'n10','c')",
        // A position delete (v2) or a deletion vector (v3) on the second file of partition a.
        "DELETE FROM %t WHERE id = 5 OR id = 3",
        "ALTER TABLE %t SET TBLPROPERTIES ('write.delete.mode'='"
            + mode
            + "', 'write.update.mode'='"
            + mode
            + "', 'write.merge.mode'='"
            + mode
            + "')");
  }

  private static final String COW = "copy-on-write";
  private static final String MOR = "merge-on-read";

  private static List<String> with(List<String> first, String... more) {
    List<String> all = new ArrayList<>(first);
    all.addAll(Arrays.asList(more));
    return all;
  }

  /** The source of the MERGE scenarios; {@code %src} is its table. */
  private static final List<String> SOURCE =
      Arrays.asList(
          "DROP TABLE IF EXISTS %src",
          "CREATE TABLE %src (id bigint, name string, p string, op string) USING iceberg",
          "INSERT INTO %src VALUES (1,'m1','a','u'),(2,'x','a','d'),(4,'m4','b','u'),"
              + "(6,'x','b','d'),(11,'m11','c','i'),(12,'m12','a','i')");

  private static final String MERGE =
      "MERGE INTO %t t USING %src s ON t.id = s.id"
          + " WHEN MATCHED AND s.op = 'd' THEN DELETE"
          + " WHEN MATCHED THEN UPDATE SET t.name = s.name"
          + " WHEN NOT MATCHED THEN INSERT (id, name, p) VALUES (s.id, s.name, s.p)";

  /** Runs {@code statements} through {@code catalog} on tables named with {@code suffix}. */
  private static void run(List<String> statements, String catalog, String scenario, String suffix)
      throws Exception {
    String table = catalog + "." + DB + "." + scenario + suffix;
    String source = catalog + "." + DB + "." + scenario + "_src" + suffix;
    String created = catalog + "." + DB + "." + scenario + "_new" + suffix;
    for (String statement : statements) {
      rows(statement.replace("%src", source).replace("%new", created).replace("%t", table));
    }
  }

  /** What is compared of a table: rows, files, snapshots, and on v3 the row lineage. */
  private static List<List<List<Object>>> outcome(Format format, String table) throws Exception {
    List<List<List<Object>>> outcome = new ArrayList<>();
    outcome.add(rows("SELECT * FROM " + table + " ORDER BY id"));
    outcome.add(
        rows(
            "SELECT content, file_format, record_count, CAST(partition AS STRING),"
                + " content_size_in_bytes, referenced_data_file IS NOT NULL"
                + " FROM "
                + table
                + ".files ORDER BY 1, 2, 3, 4, 5, 6"));
    outcome.add(
        rows(
            "SELECT operation, CAST(array_sort(map_entries(map_filter(summary, (k, v) ->"
                + " NOT (k LIKE '%size%' OR k LIKE '%spark%' OR k LIKE '%app-id%'"
                + " OR k LIKE '%version%')))) AS STRING) FROM "
                + table
                + ".snapshots ORDER BY committed_at"));
    if (format == Format.V3) {
      outcome.add(
          rows("SELECT id, _row_id, _last_updated_sequence_number FROM " + table + " ORDER BY id"));
    }
    return outcome;
  }

  /**
   * Format v2: runs the scenario both ways and asserts the two tables are the same. Format v3: the
   * service refuses to plan v3 tables (V11e), so the scenario's last statement, through the
   * server-planning catalog, must fail visibly with the refusal and leave the table exactly as the
   * statements before it left it. Returns the rows of the client-planned run, for the scenario's
   * own checks.
   */
  private static List<List<Object>> assertSameTable(
      Format format, String scenario, List<String> statements, String compared) throws Exception {
    String name = scenario + "_" + format.name().toLowerCase();
    run(statements, format.client, name, "_c");
    String suffix = compared == null ? "" : "_" + compared;
    String onClient = format.client + "." + DB + "." + name + suffix + "_c";
    // Both tables are read back through the client-planning catalog: what is compared is what the
    // writes stored, not how a server-planned read reads it.
    String onServer = format.client + "." + DB + "." + name + suffix + "_s";
    List<List<List<Object>>> expected = outcome(format, onClient);
    Assertions.assertFalse(expected.get(0).isEmpty(), "no rows");
    if (format == Format.V3) {
      List<String> before = statements.subList(0, statements.size() - 1);
      run(before, format.client, name, "_s");
      List<List<List<Object>>> unchanged = outcome(format, onServer);
      String last = statements.get(statements.size() - 1);
      Throwable refused =
          Assertions.assertThrows(
              Throwable.class, () -> run(Arrays.asList(last), format.server, name, "_s"), last);
      StringBuilder chain = new StringBuilder();
      for (Throwable t = refused; t != null; t = t.getCause()) {
        chain.append(t.getClass().getName()).append(": ").append(t.getMessage()).append(" <- ");
      }
      Assertions.assertTrue(
          chain.toString().contains("Remote scan planning failed")
              && chain.toString().contains("ScanPlanFormatVersionException"),
          chain.toString());
      rows("REFRESH TABLE " + onServer);
      Assertions.assertEquals(unchanged, outcome(format, onServer), scenario + " unchanged");
      return expected.get(0);
    }
    run(statements, format.server, name, "_s");
    Assertions.assertEquals(expected, outcome(format, onServer), scenario + " " + format);
    return expected.get(0);
  }

  private static List<List<Object>> ids(List<List<Object>> rows) {
    List<List<Object>> ids = new ArrayList<>();
    for (List<Object> row : rows) {
      ids.add(Arrays.asList(row.get(0), row.get(1)));
    }
    return ids;
  }

  private static List<Object> row(Object... values) {
    return Arrays.asList(values);
  }

  @ParameterizedTest
  @EnumSource(Format.class)
  void deleteCopyOnWrite(Format format) throws Exception {
    List<List<Object>> rows =
        assertSameTable(
            format, "del_cow", with(seed(format, COW), "DELETE FROM %t WHERE id IN (2, 7)"), null);
    Assertions.assertEquals(
        Arrays.asList(
            row(1L, "n1"),
            row(4L, "n4"),
            row(6L, "n6"),
            row(8L, "n8"),
            row(9L, "n9"),
            row(10L, "n10")),
        ids(rows));
  }

  @ParameterizedTest
  @EnumSource(Format.class)
  void deleteMergeOnReadTwiceOnTheSameFiles(Format format) throws Exception {
    List<List<Object>> rows =
        assertSameTable(
            format,
            "del_mor",
            with(
                seed(format, MOR),
                "DELETE FROM %t WHERE id = 1",
                "DELETE FROM %t WHERE id IN (8, 10)"),
            null);
    Assertions.assertEquals(
        Arrays.asList(row(2L, "n2"), row(4L, "n4"), row(6L, "n6"), row(7L, "n7"), row(9L, "n9")),
        ids(rows));
  }

  @ParameterizedTest
  @EnumSource(Format.class)
  void updateCopyOnWrite(Format format) throws Exception {
    List<List<Object>> rows =
        assertSameTable(
            format,
            "upd_cow",
            with(
                seed(format, COW),
                "UPDATE %t SET name = concat(name, '-u') WHERE p = 'a' AND id > 1"),
            null);
    Assertions.assertEquals(
        Arrays.asList(
            row(1L, "n1"),
            row(2L, "n2-u"),
            row(4L, "n4"),
            row(6L, "n6"),
            row(7L, "n7"),
            row(8L, "n8-u"),
            row(9L, "n9"),
            row(10L, "n10")),
        ids(rows));
  }

  @ParameterizedTest
  @EnumSource(Format.class)
  void updateMergeOnRead(Format format) throws Exception {
    List<List<Object>> rows =
        assertSameTable(
            format,
            "upd_mor",
            with(
                seed(format, MOR),
                "UPDATE %t SET name = concat(name, '-u') WHERE p = 'a' AND id > 1",
                "UPDATE %t SET name = concat(name, '-v') WHERE id IN (2, 9)"),
            null);
    Assertions.assertEquals(
        Arrays.asList(
            row(1L, "n1"),
            row(2L, "n2-u-v"),
            row(4L, "n4"),
            row(6L, "n6"),
            row(7L, "n7"),
            row(8L, "n8-u"),
            row(9L, "n9-v"),
            row(10L, "n10")),
        ids(rows));
  }

  @ParameterizedTest
  @EnumSource(Format.class)
  void mergeCopyOnWrite(Format format) throws Exception {
    List<String> statements = with(seed(format, COW));
    statements.addAll(SOURCE);
    statements.add(MERGE);
    assertMerged(assertSameTable(format, "merge_cow", statements, null));
  }

  @ParameterizedTest
  @EnumSource(Format.class)
  void mergeMergeOnRead(Format format) throws Exception {
    List<String> statements = with(seed(format, MOR));
    statements.addAll(SOURCE);
    statements.add(MERGE);
    assertMerged(assertSameTable(format, "merge_mor", statements, null));
  }

  private static void assertMerged(List<List<Object>> rows) {
    Assertions.assertEquals(
        Arrays.asList(
            row(1L, "m1"),
            row(4L, "m4"),
            row(7L, "n7"),
            row(8L, "n8"),
            row(9L, "n9"),
            row(10L, "n10"),
            row(11L, "m11"),
            row(12L, "m12")),
        ids(rows));
  }

  /** An INSERT OVERWRITE in dynamic mode whose SELECT reads the table it overwrites. */
  @ParameterizedTest
  @EnumSource(Format.class)
  void insertOverwriteWithDynamicPartitions(Format format) throws Exception {
    SparkSession spark = IrcSpark.session();
    spark.conf().set("spark.sql.sources.partitionOverwriteMode", "dynamic");
    List<List<Object>> rows;
    try {
      rows =
          assertSameTable(
              format,
              "iow",
              with(
                  seed(format, COW),
                  "INSERT OVERWRITE %t SELECT id + 100, name, p FROM %t WHERE p IN ('a', 'c')"),
              null);
    } finally {
      spark.conf().unset("spark.sql.sources.partitionOverwriteMode");
    }
    Assertions.assertEquals(
        Arrays.asList(
            row(4L, "n4"),
            row(6L, "n6"),
            row(101L, "n1"),
            row(102L, "n2"),
            row(107L, "n7"),
            row(108L, "n8"),
            row(109L, "n9"),
            row(110L, "n10")),
        ids(rows));
  }

  /** A CTAS and an RTAS whose SELECT reads a planned table with deletes. */
  @ParameterizedTest
  @EnumSource(Format.class)
  void ctasAndRtasReadingAPlannedTable(Format format) throws Exception {
    List<String> statements =
        with(
            seed(format, MOR),
            "DROP TABLE IF EXISTS %new",
            "CREATE TABLE %new USING iceberg PARTITIONED BY (p) TBLPROPERTIES ('format-version'='"
                + format.version
                + "') AS SELECT * FROM %t WHERE id > 1",
            "ALTER TABLE %new SET TBLPROPERTIES ('replace.enabled'='true')",
            "DELETE FROM %t WHERE id = 2",
            "CREATE OR REPLACE TABLE %new USING iceberg PARTITIONED BY (p)"
                + " AS SELECT id, upper(name) AS name, p FROM %t WHERE p <> 'c'");
    List<List<Object>> rows = assertSameTable(format, "ctas", statements, "new");
    Assertions.assertEquals(
        Arrays.asList(row(1L, "N1"), row(4L, "N4"), row(6L, "N6"), row(8L, "N8")), ids(rows));
  }

  // ---------------------------------------------------------------- format v3: refused

  /**
   * The three statements of the V11e decision on a format v3 table with deletion vectors, through
   * the server-planning catalog: a SELECT, a copy-on-write UPDATE and a merge-on-read MERGE. Each
   * fails visibly with the service's refusal, and the table is unchanged (rows, files, snapshots
   * and row lineage). The same statements on a format v2 table plan on the server and succeed.
   * Until V11e two tests here pinned the wrong row lineage a server-planned v3 read and rewrite
   * produced; the route no longer plans v3 tables.
   */
  @Test
  void aSelectAnUpdateAndAMergeOnAFormatV3TableAreRefusedAndChangeNothing() throws Exception {
    for (Format format : Format.values()) {
      String name = "refused_" + format.name().toLowerCase();
      List<String> setup = with(seed(format, MOR));
      setup.addAll(SOURCE);
      run(setup, format.client, name, "_s");
      String table = DB + "." + name + "_s";
      String source = DB + "." + name + "_src_s";
      List<String> statements =
          Arrays.asList(
              "SELECT * FROM " + format.server + "." + table + " ORDER BY id",
              "ALTER TABLE "
                  + format.client
                  + "."
                  + table
                  + " SET TBLPROPERTIES ('write.update.mode'='copy-on-write')",
              "UPDATE " + format.server + "." + table + " SET name = upper(name) WHERE p = 'a'",
              "MERGE INTO "
                  + format.server
                  + "."
                  + table
                  + " t USING "
                  + format.server
                  + "."
                  + source
                  + " s ON t.id = s.id"
                  + " WHEN MATCHED AND s.op = 'd' THEN DELETE"
                  + " WHEN MATCHED THEN UPDATE SET t.name = s.name"
                  + " WHEN NOT MATCHED THEN INSERT (id, name, p) VALUES (s.id, s.name, s.p)");
      for (String statement : statements) {
        if (statement.startsWith("ALTER") || format == Format.V2) {
          rows(statement);
          continue;
        }
        List<List<List<Object>>> before = outcome(format, format.client + "." + table);
        SparkIrcScanPlanningTest.assertRefusedAsFormatV3(statement);
        rows("REFRESH TABLE " + format.client + "." + table);
        Assertions.assertEquals(
            before, outcome(format, format.client + "." + table), statement + ": unchanged");
      }
      if (format == Format.V2) {
        // The client catalog cached the table before the server catalog wrote it.
        rows("REFRESH TABLE " + format.client + "." + table);
        Assertions.assertEquals(
            Arrays.asList(
                row(1L, "m1"),
                row(4L, "m4"),
                row(7L, "n7"),
                row(8L, "N8"),
                row(9L, "n9"),
                row(10L, "n10"),
                row(11L, "m11"),
                row(12L, "m12")),
            ids(rows("SELECT * FROM " + format.client + "." + table + " ORDER BY id")));
      }
    }
  }
}
