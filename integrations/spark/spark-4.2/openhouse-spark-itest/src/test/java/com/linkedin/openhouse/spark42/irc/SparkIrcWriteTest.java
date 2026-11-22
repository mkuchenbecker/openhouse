package com.linkedin.openhouse.spark42.irc;

import static com.linkedin.openhouse.spark42.irc.IrcSpark.DB;
import static com.linkedin.openhouse.spark42.irc.IrcSpark.failure;
import static com.linkedin.openhouse.spark42.irc.IrcSpark.row;
import static com.linkedin.openhouse.spark42.irc.IrcSpark.rows;
import static com.linkedin.openhouse.spark42.irc.SparkIrcFailureTest.assertFailure;

import java.util.Arrays;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Writes over IRC are not served yet: the service answers 406 on every write route. This pins what
 * a Spark 4.2 user sees today for {@code INSERT} and {@code CREATE TABLE}, so this test fails on
 * purpose when the write routes land and must then be rewritten to show the writes working.
 */
public class SparkIrcWriteTest {

  @Test
  void insertIsRefusedAndTheTableIsUnchanged() throws Exception {
    long first = IrcSpark.service().table("flat").get("snapshotIds").get(0).asLong();
    long current = IrcSpark.service().table("flat").get("snapshotIds").get(1).asLong();
    assertFailure(
        "org.apache.iceberg.exceptions.RESTException",
        "Unable to process (code: 406, type: UnsupportedOperationException): This server does not"
            + " support POST /v1/namespaces/irc42/tables/flat yet",
        failure("INSERT INTO irc." + DB + ".flat VALUES (9, 9.5)"));
    Assertions.assertEquals(
        Arrays.asList(row(first), row(current)),
        rows("SELECT snapshot_id FROM irc." + DB + ".flat.snapshots ORDER BY committed_at"));
    Assertions.assertEquals(
        Arrays.asList(row(3L)), rows("SELECT count(*) FROM irc." + DB + ".flat"));
  }

  @Test
  void createTableIsRefusedAndNoTableExists() throws Exception {
    assertFailure(
        "org.apache.iceberg.exceptions.RESTException",
        "Unable to process (code: 406, type: UnsupportedOperationException): This server does not"
            + " support POST /v1/namespaces/irc42/tables yet",
        failure("CREATE TABLE irc." + DB + ".created (id bigint) USING iceberg"));
    Assertions.assertTrue(
        rows("SHOW TABLES IN irc." + DB).stream().noneMatch(row -> "created".equals(row.get(1))));
  }
}
