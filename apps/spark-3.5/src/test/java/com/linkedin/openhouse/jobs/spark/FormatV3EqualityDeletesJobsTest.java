package com.linkedin.openhouse.jobs.spark;

import static com.linkedin.openhouse.jobs.spark.FormatV3Tables.liveDeleteFiles;
import static com.linkedin.openhouse.jobs.spark.FormatV3Tables.snapshotIds;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileContent;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.RowDelta;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The maintenance jobs on a table with equality deletes (work item X10), format v3 and v2. Spark
 * does not write equality deletes; an Iceberg API writer does (Flink, over the Iceberg REST
 * routes), and format v3 still allows them. Here they are written with Iceberg's own writer ({@link
 * IcebergFiles}) and committed through the Iceberg REST routes; the native API accepts them too
 * (pinned below). No OpenHouse check refers to equality deletes.
 *
 * <p>The shape: the base layout (4 daily partitions, 8 ORC data files, ids 1 to 32), then one row
 * delta of four equality delete files on {@code id} (2, 10, 18, 26, one per partition), a Spark
 * DELETE (deletion vectors on v3, position delete files on v2), a second row delta with an equality
 * delete on {@code id} (3) and one on another column ({@code data = 'row-11'}), a Spark UPDATE, and
 * an INSERT that writes id 26 again after its equality delete: an equality delete applies only to
 * data with a lower sequence number, so the new row 26 is live. 13 snapshots.
 */
public class FormatV3EqualityDeletesJobsTest extends FormatV3ShapeJobsTestBase {
  @Override
  String db() {
    return "x10eqdel";
  }

  /** The partition of the data file holding {@code id}, by the file's id bounds. */
  private static FileScanTask fileOf(Table table, long id) throws IOException {
    try (CloseableIterable<FileScanTask> tasks =
        table.newScan().filter(Expressions.equal("id", id)).planFiles()) {
      List<FileScanTask> found = new ArrayList<>();
      tasks.forEach(found::add);
      Assertions.assertEquals(1, found.size(), "one data file holds id " + id);
      return found.get(0);
    }
  }

  private static DeleteFile equalityDelete(Table table, long fileOfId, String column, Object key)
      throws IOException {
    FileScanTask task = fileOf(table, fileOfId);
    Schema deleteSchema = table.schema().select(column);
    Record record = GenericRecord.create(deleteSchema);
    record.setField(column, key);
    return IcebergFiles.writeEqualityDeletes(
        table,
        task.spec(),
        task.file().partition(),
        deleteSchema,
        Collections.singletonList(record));
  }

  @Override
  Shape createShape(int formatVersion, String name) throws Exception {
    SparkSession spark = session(formatVersion);
    String fqtn = tables.createAndInsert(formatVersion, name, "orc");
    String t = "openhouse." + fqtn;
    Table rest = rest(formatVersion).loadTable(TableIdentifier.parse(fqtn));
    RowDelta first = rest.newRowDelta();
    for (long id : new long[] {2, 10, 18, 26}) {
      first.addDeletes(equalityDelete(rest, id, "id", id));
    }
    first.commit();
    spark.sql("REFRESH TABLE " + t);
    spark.sql("DELETE FROM " + t + " WHERE id IN (6, 14, 22, 30)");
    rest.refresh();
    rest.newRowDelta()
        .addDeletes(equalityDelete(rest, 3, "id", 3L))
        .addDeletes(equalityDelete(rest, 11, "data", "row-11"))
        .commit();
    spark.sql("REFRESH TABLE " + t);
    spark.sql("UPDATE " + t + " SET data = concat(data, '-updated') WHERE id IN (7, 23)");
    spark.sql("INSERT INTO " + t + " VALUES (26, 'row-26-again', " + ts(0, 26) + ")");

    Table table = table(formatVersion, fqtn);
    Assertions.assertEquals(13, snapshotIds(table).size());
    long equalityDeletes =
        liveDeleteFiles(table, table.currentSnapshot()).stream()
            .filter(d -> d.content() == FileContent.EQUALITY_DELETES)
            .count();
    Assertions.assertEquals(6, equalityDeletes, "six live equality delete files");
    List<String> live = FormatV3Tables.rows(spark, "SELECT id, data FROM " + t);
    Assertions.assertTrue(live.contains("[26,row-26-again]"), live.toString());
    Assertions.assertEquals(32 - 10 + 1, live.size(), live.toString());
    return new Shape(fqtn, deleted(MAIN, ids(2, 10, 18, 6, 14, 22, 30, 3, 11)));
  }

  @Override
  void afterJob(String job, int formatVersion, Shape shape, RefsReading before) throws IOException {
    Table table = table(formatVersion, shape.fqtn);
    long liveEqualityDeletes =
        liveDeleteFiles(table, table.currentSnapshot()).stream()
            .filter(d -> d.content() == FileContent.EQUALITY_DELETES)
            .count();
    long applying = 0;
    try (CloseableIterable<FileScanTask> tasks = table.newScan().planFiles()) {
      for (FileScanTask task : tasks) {
        applying +=
            task.deletes().stream()
                .filter(d -> d.content() == FileContent.EQUALITY_DELETES)
                .count();
      }
    }
    System.out.println(
        "X10B-EQ "
            + job
            + " v"
            + formatVersion
            + " liveEqualityDeleteFiles="
            + liveEqualityDeletes
            + " applyingToLiveDataFiles="
            + applying);
    if ("compaction".equals(job)) {
      // Every data file the equality deletes applied to was rewritten with them applied; the new
      // files carry the starting snapshot's sequence number, so no equality delete applies to them.
      Assertions.assertEquals(0, applying, "no equality delete applies after compaction");
    }
  }

  /** The native API (OpenHouse catalog, Iceberg Java API) accepts an equality delete as well. */
  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void equalityDeletesAreAcceptedNatively(int formatVersion) throws Exception {
    String fqtn = tables.createAndInsert(formatVersion, "native_eq", "orc");
    Table table = table(formatVersion, fqtn);
    table.newRowDelta().addDeletes(equalityDelete(table, 5, "id", 5L)).commit();
    session(formatVersion).sql("REFRESH TABLE openhouse." + fqtn);
    List<String> ids =
        FormatV3Tables.rows(session(formatVersion), "SELECT id FROM openhouse." + fqtn);
    Assertions.assertEquals(31, ids.size());
    Assertions.assertFalse(ids.contains("[5]"));
  }
}
