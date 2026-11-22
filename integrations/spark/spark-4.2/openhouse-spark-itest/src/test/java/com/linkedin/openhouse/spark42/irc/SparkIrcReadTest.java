package com.linkedin.openhouse.spark42.irc;

import static com.linkedin.openhouse.spark42.irc.IrcSpark.DB;
import static com.linkedin.openhouse.spark42.irc.IrcSpark.column;
import static com.linkedin.openhouse.spark42.irc.IrcSpark.row;
import static com.linkedin.openhouse.spark42.irc.IrcSpark.rows;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Spark 4.2 on the fork's Iceberg 1.12 runtime reads tables that were created and written through
 * OpenHouse's native API, over the service's IRC read routes, with Spark SQL only. Every result is
 * what the seeding wrote ({@code IrcReadTestService}):
 *
 * <ul>
 *   <li>{@code parted}, partitioned by {@code p}, Parquet: (1,a,x),(2,b,y), then (3,c,x).
 *   <li>{@code flat}, unpartitioned, Parquet: (1,1.5),(2,2.5), then (3,3.5).
 *   <li>{@code flat_orc}, as {@code flat} in ORC.
 * </ul>
 */
public class SparkIrcReadTest {

  private static final String PARTED = "irc." + DB + ".parted";

  private static long snapshot(String table, int index) throws Exception {
    return IrcSpark.service().table(table).get("snapshotIds").get(index).asLong();
  }

  @Test
  void theSparkJvmHasNoOpenHouseCatalogClasses() {
    for (String name :
        Arrays.asList(
            "com.linkedin.openhouse.javaclient.OpenHouseCatalog",
            "com.linkedin.openhouse.spark.OpenHouseCatalog",
            "com.linkedin.openhouse.tables.client.api.TableApi",
            "com.linkedin.openhouse.tables.api.irc.IcebergRestCatalogController")) {
      Assertions.assertThrows(ClassNotFoundException.class, () -> Class.forName(name), name);
    }
  }

  @Test
  void showNamespacesListsTheDatabase() throws Exception {
    Assertions.assertEquals(Arrays.asList(DB), column("SHOW NAMESPACES IN irc"));
  }

  @Test
  void showTablesListsTheSeededTables() throws Exception {
    Assertions.assertEquals(
        Arrays.asList(row(DB, "flat", false), row(DB, "flat_orc", false), row(DB, "parted", false)),
        rows("SHOW TABLES IN irc." + DB + " ").stream()
            .sorted((a, b) -> String.valueOf(a.get(1)).compareTo(String.valueOf(b.get(1))))
            .collect(java.util.stream.Collectors.toList()));
  }

  @Test
  void describeShowsTheColumnsAndThePartitioning() throws Exception {
    Assertions.assertEquals(
        Arrays.asList(
            row("id", "bigint", null),
            row("name", "string", null),
            row("p", "string", null),
            row("# Partition Information", "", ""),
            row("# col_name", "data_type", "comment"),
            row("p", "string", null)),
        rows("DESCRIBE TABLE " + PARTED));
    Assertions.assertEquals(
        Arrays.asList(row("id", "bigint", null), row("v", "double", null)),
        rows("DESCRIBE TABLE irc." + DB + ".flat"));
  }

  @Test
  void selectWithAFilterAndAnAggregateOnThePartitionedTable() throws Exception {
    Assertions.assertEquals(
        Arrays.asList(row(1L, "a"), row(3L, "c")),
        rows("SELECT id, name FROM " + PARTED + " WHERE p = 'x' ORDER BY id"));
    Assertions.assertEquals(
        Arrays.asList(row("x", 2L, 4L), row("y", 1L, 2L)),
        rows("SELECT p, count(*), sum(id) FROM " + PARTED + " GROUP BY p ORDER BY p"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"flat", "flat_orc"})
  void selectWithAFilterAndAnAggregateOnTheUnpartitionedTables(String table) throws Exception {
    String name = "irc." + DB + "." + table;
    Assertions.assertEquals(
        Arrays.asList(row(2L, 2.5), row(3L, 3.5)),
        rows("SELECT id, v FROM " + name + " WHERE v > 2.0 ORDER BY id"));
    Assertions.assertEquals(
        Arrays.asList(row(3L, 7.5, 3L)), rows("SELECT count(*), sum(v), max(id) FROM " + name));
  }

  @Test
  void timeTravelBySnapshotIdReadsTheFirstSnapshot() throws Exception {
    Assertions.assertEquals(
        Arrays.asList(row(1L, "a", "x"), row(2L, "b", "y")),
        rows(
            "SELECT * FROM "
                + PARTED
                + " VERSION AS OF "
                + snapshot("parted", 0)
                + " ORDER BY id"));
    for (String table : Arrays.asList("flat", "flat_orc")) {
      Assertions.assertEquals(
          Arrays.asList(row(1L, 1.5), row(2L, 2.5)),
          rows(
              "SELECT * FROM irc."
                  + DB
                  + "."
                  + table
                  + " VERSION AS OF "
                  + snapshot(table, 0)
                  + " ORDER BY id"),
          table);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"parted", "flat", "flat_orc"})
  void theSnapshotsMetadataTableShowsBothAppends(String table) throws Exception {
    List<List<Object>> snapshots =
        rows(
            "SELECT snapshot_id, parent_id, operation FROM irc."
                + DB
                + "."
                + table
                + ".snapshots ORDER BY committed_at");
    Assertions.assertEquals(
        Arrays.asList(
            row(snapshot(table, 0), null, "append"),
            row(snapshot(table, 1), snapshot(table, 0), "append")),
        snapshots);
  }

  @ParameterizedTest
  @ValueSource(strings = {"parted", "flat", "flat_orc"})
  void theHistoryMetadataTableShowsBothSnapshotsAsCurrentAncestors(String table) throws Exception {
    Assertions.assertEquals(
        Arrays.asList(
            row(snapshot(table, 0), null, true), row(snapshot(table, 1), snapshot(table, 0), true)),
        rows(
            "SELECT snapshot_id, parent_id, is_current_ancestor FROM irc."
                + DB
                + "."
                + table
                + ".history ORDER BY made_current_at"));
  }

  @Test
  void theFilesMetadataTableShowsEveryDataFile() throws Exception {
    Assertions.assertEquals(
        Arrays.asList(row("x", 1L), row("x", 1L), row("y", 1L)),
        rows(
            "SELECT partition.p, record_count FROM "
                + PARTED
                + ".files ORDER BY partition.p, record_count"));
    for (String table : Arrays.asList("flat", "flat_orc")) {
      String format = table.equals("flat") ? "PARQUET" : "ORC";
      Assertions.assertEquals(
          Arrays.asList(row(format, 1L), row(format, 2L)),
          rows(
              "SELECT file_format, record_count FROM irc."
                  + DB
                  + "."
                  + table
                  + ".files ORDER BY record_count"),
          table);
    }
  }
}
