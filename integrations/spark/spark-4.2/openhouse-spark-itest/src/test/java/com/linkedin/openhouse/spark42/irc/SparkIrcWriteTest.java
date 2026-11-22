package com.linkedin.openhouse.spark42.irc;

import static com.linkedin.openhouse.spark42.irc.IrcSpark.DB;
import static com.linkedin.openhouse.spark42.irc.IrcSpark.row;
import static com.linkedin.openhouse.spark42.irc.IrcSpark.rows;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Writes over IRC, since the service serves the IRC table write routes (work item V3): what a Spark
 * 4.2 user on Iceberg 1.12 sees for {@code CREATE TABLE} and {@code INSERT}. Both succeed and the
 * rows read back over the same catalog.
 *
 * <p>Each case writes to a table it creates, never to the seeded tables, which the other classes
 * compare with what the OpenHouse client recorded when the service seeded them, and drops it
 * afterwards, because they also list the namespace's tables exactly. This is not a write matrix:
 * only these two statements are pinned.
 */
public class SparkIrcWriteTest {

  @Test
  void createTableSucceedsAndTheTableIsListedAndEmpty() throws Exception {
    try {
      rows("CREATE TABLE irc." + DB + ".created (id bigint) USING iceberg");
      Assertions.assertTrue(
          rows("SHOW TABLES IN irc." + DB).stream().anyMatch(row -> "created".equals(row.get(1))));
      Assertions.assertEquals(
          Collections.singletonList(row(0L)), rows("SELECT count(*) FROM irc." + DB + ".created"));
    } finally {
      rows("DROP TABLE IF EXISTS irc." + DB + ".created");
    }
  }

  @Test
  void insertSucceedsAndTheRowsReadBack() throws Exception {
    try {
      rows("CREATE TABLE irc." + DB + ".inserted (id bigint, v double) USING iceberg");
      rows("INSERT INTO irc." + DB + ".inserted VALUES (9, 9.5)");
      rows("INSERT INTO irc." + DB + ".inserted VALUES (10, 10.5)");
      Assertions.assertEquals(
          Arrays.asList(row(9L, 9.5), row(10L, 10.5)),
          rows("SELECT id, v FROM irc." + DB + ".inserted ORDER BY id"));
      Assertions.assertEquals(
          Collections.singletonList(row(2L)),
          rows("SELECT count(*) FROM irc." + DB + ".inserted.snapshots"));
    } finally {
      rows("DROP TABLE IF EXISTS irc." + DB + ".inserted");
    }
  }
}
