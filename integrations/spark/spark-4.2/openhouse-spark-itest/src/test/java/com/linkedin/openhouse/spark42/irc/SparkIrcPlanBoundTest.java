package com.linkedin.openhouse.spark42.irc;

import static com.linkedin.openhouse.spark42.irc.IrcSpark.rows;

import java.util.Arrays;
import java.util.List;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Spark 4.2 over IRC against a service whose plans are bounded to four file scan tasks (the cluster
 * property {@value #BOUND}), on a table of five data files: with server planning, every statement
 * whose scan plans more than four tasks fails visibly with the service's message (a SELECT, a
 * LIMIT, an UPDATE, a MERGE), and a write that fails leaves the table as it was; a scan that a
 * filter prunes to four or fewer reads the rows client planning reads; with client planning nothing
 * is bounded. Never fewer rows.
 *
 * <p>The service is this class's own (started and stopped here), so no other test meets the bound.
 */
public class SparkIrcPlanBoundTest {

  static final String BOUND = "cluster.tables.irc.scan-planning.max-file-scan-tasks";
  private static final String DB = SparkIrcScanPlanningTest.DB;
  private static final String CLIENT = "pb_client";
  private static final String SERVER = "pb_server";
  private static final String TABLE = DB + ".bounded";

  private static IrcReadTestServiceProcess service;

  @BeforeAll
  static void startTheService() throws Exception {
    service =
        IrcReadTestServiceProcess.startOwn(
            Arrays.asList("-D" + ServerPlanning.SERVICE_PROPERTY + "=true", "-D" + BOUND + "=4"));
    SparkSession spark = IrcSpark.session();
    SparkIrcServerPlanningWritesTest.catalog(spark, CLIENT, service, "client");
    SparkIrcServerPlanningWritesTest.catalog(spark, SERVER, service, "server");
    String t = CLIENT + "." + TABLE;
    rows(
        "CREATE TABLE "
            + t
            + " (id bigint, name string, p string) USING iceberg PARTITIONED BY (p)");
    // Five appends, five data files: three in partition a, two in b.
    rows("INSERT INTO " + t + " VALUES (1,'n1','a')");
    rows("INSERT INTO " + t + " VALUES (2,'n2','a')");
    rows("INSERT INTO " + t + " VALUES (3,'n3','a')");
    rows("INSERT INTO " + t + " VALUES (4,'n4','b')");
    rows("INSERT INTO " + t + " VALUES (5,'n5','b')");
  }

  @AfterAll
  static void stopTheService() throws Exception {
    try {
      rows("DROP TABLE IF EXISTS " + CLIENT + "." + TABLE);
    } finally {
      service.stop();
    }
  }

  private static String chain(Throwable thrown) {
    StringBuilder all = new StringBuilder();
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      all.append(t.getClass().getName()).append(": ").append(t.getMessage()).append(" <- ");
    }
    return all.toString();
  }

  private static void assertRefused(String sql) {
    Throwable failure = Assertions.assertThrows(Throwable.class, () -> rows(sql), sql);
    String all = chain(failure);
    Assertions.assertTrue(all.contains("java.lang.IllegalStateException"), all);
    Assertions.assertTrue(all.contains("Remote scan planning failed"), all);
    Assertions.assertTrue(all.contains("more than 4 file scan tasks"), all);
    Assertions.assertTrue(all.contains("scan-planning-mode=client"), all);
  }

  private static List<List<Object>> state() throws Exception {
    List<List<Object>> state = rows("SELECT * FROM " + CLIENT + "." + TABLE + " ORDER BY id");
    state.addAll(rows("SELECT snapshot_id FROM " + CLIENT + "." + TABLE + ".snapshots"));
    return state;
  }

  @Test
  void aScanAboveTheBoundFailsVisiblyWithServerPlanningOnly() throws Exception {
    Assertions.assertEquals(5, rows("SELECT * FROM " + CLIENT + "." + TABLE).size());
    assertRefused("SELECT * FROM " + SERVER + "." + TABLE);
    assertRefused("SELECT * FROM " + SERVER + "." + TABLE + " LIMIT 1");
    assertRefused("SELECT count(*) FROM " + SERVER + "." + TABLE + " WHERE name <> 'x'");
  }

  @Test
  void aScanPrunedToTheBoundReadsTheSameRows() throws Exception {
    for (String query :
        new String[] {
          "SELECT * FROM %s WHERE p = 'a' ORDER BY id",
          "SELECT id FROM %s WHERE id >= 2 ORDER BY id"
        }) {
      List<List<Object>> onClient = rows(String.format(query, CLIENT + "." + TABLE));
      Assertions.assertEquals(onClient, rows(String.format(query, SERVER + "." + TABLE)), query);
      Assertions.assertFalse(onClient.isEmpty());
    }
  }

  @Test
  void aWriteAboveTheBoundFailsAndLeavesTheTableAsItWas() throws Exception {
    List<List<Object>> before = state();
    assertRefused("UPDATE " + SERVER + "." + TABLE + " SET name = upper(name)");
    assertRefused(
        "MERGE INTO "
            + SERVER
            + "."
            + TABLE
            + " t USING (SELECT 1L AS id, 'm' AS name) s ON t.id = s.id"
            + " WHEN MATCHED THEN UPDATE SET t.name = s.name");
    Assertions.assertEquals(before, state());
  }
}
