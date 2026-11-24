package com.linkedin.openhouse.spark42.irc;

import static com.linkedin.openhouse.spark42.irc.IrcSpark.rows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Spark 4.2 reads the same rows whether Iceberg plans its scans on the client (catalog {@code irc},
 * the default) or on the server (catalog {@code irc_plan}: the same service and token, with the
 * catalog property {@code scan-planning-mode=server}), for SELECTs with filters, projections, a
 * LIMIT and time travel, on a format v2 table with position deletes (merge-on-read DELETE). On the
 * format v3 service, a table with deletion vectors is refused with server planning (V11e). The
 * service's access log shows that the server-planning catalog sent plan requests and the
 * client-planning one sent none.
 *
 * <p>Every table is in {@value #DB}, so that {@link SparkIrcNoPlanRequestTest} can tell this test's
 * plan requests from any other.
 */
public class SparkIrcScanPlanningTest {

  /** The only database whose tables are planned on the server in this JVM. */
  static final String DB = "irc42_plan";

  private static final String V2 = DB + ".deletes_v2";
  private static final String V3 = DB + ".deletes_v3";

  @BeforeAll
  static void addTheCatalogs() throws Exception {
    SparkSession spark = IrcSpark.session();
    catalog(spark, "irc_plan", IrcSpark.service(), true);
    IrcReadTestServiceProcess v3 = IrcReadTestServiceProcess.getWithFormatV3();
    catalog(spark, "ircv3_client", v3, false);
    catalog(spark, "ircv3_plan", v3, true);

    rows(
        "CREATE TABLE irc."
            + V2
            + " (id bigint, name string, p string) USING iceberg PARTITIONED BY (p)"
            + " TBLPROPERTIES ('write.delete.mode'='merge-on-read',"
            + " 'write.update.mode'='merge-on-read', 'write.merge.mode'='merge-on-read')");
    rows("INSERT INTO irc." + V2 + " VALUES (1,'n1','a'),(2,'n2','a'),(3,'n3','a'),(4,'n4','b')");
    rows("INSERT INTO irc." + V2 + " VALUES (5,'n5','a'),(6,'n6','b'),(7,'n7','b'),(8,'n8','c')");
    rows("INSERT INTO irc." + V2 + " VALUES (9,'n9','c'),(10,'n10','a'),(11,'n11','b')");
    // Two DELETEs that each hit part of a file, so both write position deletes (a DELETE that
    // covers a whole file drops the file instead). Together they delete every row of the first
    // file of partition a: a LIMIT on that partition must still find rows in its other files.
    rows("DELETE FROM irc." + V2 + " WHERE id = 1");
    rows("DELETE FROM irc." + V2 + " WHERE id IN (2, 3, 6)");

    rows(
        "CREATE TABLE ircv3_client."
            + V3
            + " (id bigint, name string, p string) USING iceberg PARTITIONED BY (p)"
            + " TBLPROPERTIES ('format-version'='3', 'write.delete.mode'='merge-on-read',"
            + " 'write.update.mode'='merge-on-read', 'write.merge.mode'='merge-on-read')");
    rows(
        "INSERT INTO ircv3_client."
            + V3
            + " VALUES (1,'n1','a'),(2,'n2','a'),(3,'n3','a'),(4,'n4','b')");
    rows("INSERT INTO ircv3_client." + V3 + " VALUES (5,'n5','a'),(6,'n6','b'),(7,'n7','b')");
    rows("DELETE FROM ircv3_client." + V3 + " WHERE id = 2");
    rows("DELETE FROM ircv3_client." + V3 + " WHERE id IN (1, 6)");
  }

  @AfterAll
  static void dropTheTables() throws Exception {
    rows("DROP TABLE IF EXISTS irc." + V2);
    rows("DROP TABLE IF EXISTS ircv3_client." + V3);
  }

  private static void catalog(
      SparkSession spark, String name, IrcReadTestServiceProcess service, boolean onServer) {
    String prefix = "spark.sql.catalog." + name;
    spark.conf().set(prefix, "org.apache.iceberg.spark.SparkCatalog");
    spark.conf().set(prefix + ".type", "rest");
    spark.conf().set(prefix + ".uri", service.uri() + "/iceberg");
    spark.conf().set(prefix + ".token", service.token(IrcSpark.READER));
    if (onServer) {
      spark.conf().set(prefix + ".scan-planning-mode", "server");
    }
  }

  /** The SELECTs compared; {@code %s} is the table. Ordered, so the rows are comparable. */
  private static final List<String> QUERIES =
      Arrays.asList(
          "SELECT * FROM %s ORDER BY id",
          "SELECT * FROM %s WHERE p = 'a' ORDER BY id",
          "SELECT id, name FROM %s WHERE id > 3 AND id < 9 ORDER BY id",
          "SELECT name FROM %s WHERE name LIKE 'n%%' AND p <> 'c' ORDER BY name",
          "SELECT p, count(*) FROM %s GROUP BY p ORDER BY p",
          "SELECT * FROM %s ORDER BY id LIMIT 3",
          "SELECT * FROM %s WHERE p = 'a' ORDER BY id LIMIT 1");

  /** The plan requests the service of {@code work} logged for {@code table}. */
  private static List<String> planRequests(Path work, String table) throws IOException {
    String route = "/iceberg/v1/namespaces/" + DB + "/tables/" + table + "/plan";
    List<String> lines = new ArrayList<>();
    try (Stream<Path> files = Files.list(work.resolve("access"))) {
      for (Path file : files.collect(Collectors.toList())) {
        lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
      }
    }
    return lines.stream().filter(l -> l.contains(route)).collect(Collectors.toList());
  }

  private static void assertSameRows(String client, String server, String table) throws Exception {
    for (String query : QUERIES) {
      List<List<Object>> onClient = rows(String.format(query, client + "." + table));
      List<List<Object>> onServer = rows(String.format(query, server + "." + table));
      Assertions.assertEquals(onClient, onServer, query);
      Assertions.assertFalse(onClient.isEmpty(), query);
    }
  }

  /** The rows of a LIMIT without ORDER BY: as many either way, and rows the table has. */
  private static void assertLimitWithoutOrder(String client, String server, String table)
      throws Exception {
    List<List<Object>> all = rows("SELECT * FROM " + client + "." + table);
    for (int limit : new int[] {1, 2, 4}) {
      for (String catalog : new String[] {client, server}) {
        List<List<Object>> limited =
            rows("SELECT * FROM " + catalog + "." + table + " WHERE p = 'a' LIMIT " + limit);
        long inA = all.stream().filter(r -> "a".equals(r.get(2))).count();
        Assertions.assertEquals(Math.min(limit, inA), limited.size(), catalog + " " + limit);
        Assertions.assertTrue(all.containsAll(limited), limited.toString());
      }
    }
  }

  @Test
  void aFormatV2TableWithPositionDeletesReadsTheSameRows() throws Exception {
    Path work = IrcSpark.service().work();
    int before = planRequests(work, "deletes_v2").size();
    assertSameRows("irc", "irc_plan", V2);
    assertLimitWithoutOrder("irc", "irc_plan", V2);
    Assertions.assertEquals(
        Arrays.asList(
            Arrays.asList(4L, "n4", "b"),
            Arrays.asList(5L, "n5", "a"),
            Arrays.asList(7L, "n7", "b"),
            Arrays.asList(8L, "n8", "c"),
            Arrays.asList(9L, "n9", "c"),
            Arrays.asList(10L, "n10", "a"),
            Arrays.asList(11L, "n11", "b")),
        rows("SELECT * FROM irc_plan." + V2 + " ORDER BY id"));
    List<List<Object>> deletes = rows("SELECT content FROM irc." + V2 + ".delete_files");
    Assertions.assertFalse(deletes.isEmpty(), "the table has delete files");

    List<String> logged = planRequests(work, "deletes_v2");
    Assertions.assertTrue(logged.size() > before, logged.toString());
    for (String line : logged) {
      Assertions.assertEquals(
          "POST /iceberg/v1/namespaces/" + DB + "/tables/deletes_v2/plan 200", line);
    }
  }

  /** Time travel to the snapshot before the DELETEs reads the rows it had, either way. */
  @Test
  void timeTravelReadsTheSameRows() throws Exception {
    List<List<Object>> snapshots =
        rows("SELECT snapshot_id FROM irc." + V2 + ".snapshots ORDER BY committed_at");
    Assertions.assertEquals(5, snapshots.size(), snapshots.toString());
    Object beforeDelete = snapshots.get(2).get(0);
    for (String query :
        new String[] {
          "SELECT * FROM %s VERSION AS OF " + beforeDelete + " ORDER BY id",
          "SELECT id FROM %s VERSION AS OF " + beforeDelete + " WHERE p = 'b' ORDER BY id"
        }) {
      List<List<Object>> onClient = rows(String.format(query, "irc." + V2));
      Assertions.assertEquals(onClient, rows(String.format(query, "irc_plan." + V2)), query);
      Assertions.assertFalse(onClient.isEmpty());
    }
    Assertions.assertEquals(
        11, rows("SELECT * FROM irc_plan." + V2 + " VERSION AS OF " + beforeDelete).size());
  }

  /**
   * The service refuses to plan a format v3 table (V11e): every server-planned read of the table
   * with deletion vectors fails visibly with the refusal, and client planning reads it. Until V11e
   * this test compared the rows read either way; a server-planned v3 read is no longer served.
   */
  @Test
  void aFormatV3TableIsRefusedWithServerPlanning() throws Exception {
    Path work = IrcReadTestServiceProcess.getWithFormatV3().work();
    int before = planRequests(work, "deletes_v3").size();
    List<List<Object>> deletes =
        rows("SELECT file_format FROM ircv3_client." + V3 + ".delete_files");
    Assertions.assertFalse(deletes.isEmpty(), "the table has deletion vectors");
    Assertions.assertEquals(
        Arrays.asList(
            Arrays.asList(3L, "n3", "a"),
            Arrays.asList(4L, "n4", "b"),
            Arrays.asList(5L, "n5", "a"),
            Arrays.asList(7L, "n7", "b")),
        rows("SELECT * FROM ircv3_client." + V3 + " ORDER BY id"));
    for (String query : QUERIES) {
      assertRefusedAsFormatV3(String.format(query, "ircv3_plan." + V3));
    }
    Assertions.assertTrue(planRequests(work, "deletes_v3").size() > before);
  }

  /** {@code sql} fails visibly with the service's refusal of format v3 tables. */
  static void assertRefusedAsFormatV3(String sql) {
    Throwable failure = Assertions.assertThrows(Throwable.class, () -> rows(sql), sql);
    StringBuilder all = new StringBuilder();
    for (Throwable t = failure; t != null; t = t.getCause()) {
      all.append(t.getClass().getName()).append(": ").append(t.getMessage()).append(" <- ");
    }
    String chain = all.toString();
    Assertions.assertTrue(
        chain.contains("java.lang.IllegalStateException")
            && chain.contains("Remote scan planning failed")
            && chain.contains("ScanPlanFormatVersionException")
            && chain.contains("scan-planning-mode=client"),
        chain);
  }
}
