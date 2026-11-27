package com.linkedin.openhouse.spark.smoke;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileContent;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ManifestFiles;
import org.apache.iceberg.ManifestReader;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Spark SQL's row-level statements (DELETE, UPDATE, MERGE) on format v3 tables, with a format v2
 * table next to each v3 case, as one list that every Spark line and catalog path runs: Spark 3.5
 * with the OpenHouse catalog, the REST-shaped shim and plain Iceberg REST over HTTP, and Spark 4.2
 * over the Iceberg REST routes. Each platform runs it against a tables service with the cluster
 * switch {@code cluster.iceberg.format-v3.enabled} on.
 *
 * <p>After every statement a test checks, against what the specification and the fork's Spark
 * integration require (the baseline, the same on every platform): the rows, read through the path
 * under test, through every other path of the platform ({@link #otherReaders()}) and through
 * Iceberg's generic reader in the test JVM, which has no Spark in it; the delete files a reader
 * applies to each data file and the live entries of the delete manifests (on v3: one deletion
 * vector per data file, replaced and never added a second time, its cardinality the rows deleted
 * from the file; on v2: one file-scoped position delete file per data file in the table's format,
 * which the Iceberg 1.12 writer replaces as the vector is replaced, except that an Avro delete file
 * has no column bounds and so is partition-scoped, one more per statement); that a merge-on-read
 * statement wrote deletes for exactly the rows it removed (and the table carries the shape's write
 * modes); the snapshot summary's {@code added-dvs} and {@code removed-dvs}; and the row lineage of
 * every live row: {@code _row_id} and {@code _last_updated_sequence_number}. The lineage contract
 * is the fork's own (TestRowLevelOperationsWithLineage, the same for copy-on-write and
 * merge-on-read, Spark 3.5 and 4.x): a row the statement does not change keeps both; an updated row
 * keeps its row id and gets the statement's sequence number; an inserted row gets a fresh id from
 * the table's next-row-id and the statement's sequence number; no two live rows share an id.
 *
 * <p>The checks use the declared-difference mechanism of {@link BaselineChecks}. The format v3
 * cases need the Iceberg 1.12 line: on LinkedIn's Iceberg 1.5.2 reference line they are skipped by
 * an assumption that says why, and the format v2 cases still run. This file uses only Iceberg API
 * both lines have; the few v3 accessors (row lineage of snapshots and metadata, the data file a
 * deletion vector references) are read reflectively ({@link #v3Value}).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class SparkSqlRowLevelStatements extends BaselineChecks {

  /** The catalog the statements name: the platform's path, on a service with v3 on. */
  protected static final String CATALOG = "rowlevel";

  /** The database the statements write to. */
  protected static final String DB = "rowlevel_v3";

  static final String MOR = "merge-on-read";
  static final String COW = "copy-on-write";

  /** Why the v3 cases do not run on LinkedIn's Iceberg 1.5.2 line. */
  static final String NO_V3_ON_152 =
      "Format v3 needs the Iceberg 1.12 line: LinkedIn's Iceberg 1.5.2 cannot build format v3"
          + " metadata, so the v3 cases do not run on the -PicebergLinkedIn reference line;"
          + " the v2 cases do.";

  // ---------- what a platform supplies ----------

  /**
   * Whether the build runs this on an Iceberg line with format v3 (the 1.12 line), as the build
   * says, independent of what is on the classpath: the v3 cases are skipped exactly when it is
   * false, and a classpath that disagrees fails them.
   */
  protected abstract boolean buildLineHasFormatV3();

  /** A session whose catalog {@value #CATALOG} is the platform's catalog path. */
  protected abstract SparkSession sparkSession() throws Exception;

  /** The table {@code <DB>.<table>} freshly loaded without Spark, through an Iceberg catalog. */
  protected abstract Table plainTable(String table) throws Exception;

  /**
   * The session's other catalogs on the same service, each another catalog path, with no table
   * cache: every statement's rows are read through each of them too. None by default.
   */
  protected List<String> otherReaders() {
    return Collections.emptyList();
  }

  // ---------- the table shapes ----------

  /** One table shape: format version, write mode, partitioning and data file format. */
  static final class Shape {
    final int formatVersion;
    final String mode;
    final boolean partitioned;
    final String fileFormat;

    Shape(int formatVersion, String mode, boolean partitioned, String fileFormat) {
      this.formatVersion = formatVersion;
      this.mode = mode;
      this.partitioned = partitioned;
      this.fileFormat = fileFormat;
    }

    boolean mergeOnRead() {
      return MOR.equals(mode);
    }

    String table() {
      return "v"
          + formatVersion
          + (mergeOnRead() ? "_mor_" : "_cow_")
          + fileFormat
          + (partitioned ? "_part" : "");
    }

    String create(String name) {
      return "CREATE TABLE "
          + name
          + " (id bigint, data string, p string)"
          + (partitioned ? " PARTITIONED BY (p)" : "")
          + " TBLPROPERTIES ("
          + (formatVersion == 3 ? "'format-version'='3', " : "")
          + "'write.format.default'='"
          + fileFormat
          + "', 'write.delete.mode'='"
          + mode
          + "', 'write.update.mode'='"
          + mode
          + "', 'write.merge.mode'='"
          + mode
          + "')";
    }
  }

  // ---------------------------------------------------------------- the statements, per shape

  @Test
  public void v3MergeOnReadParquet() throws Exception {
    statements(new Shape(3, MOR, false, "parquet"));
  }

  @Test
  public void v2MergeOnReadParquet() throws Exception {
    statements(new Shape(2, MOR, false, "parquet"));
  }

  @Test
  public void v3MergeOnReadParquetPartitioned() throws Exception {
    statements(new Shape(3, MOR, true, "parquet"));
  }

  @Test
  public void v2MergeOnReadParquetPartitioned() throws Exception {
    statements(new Shape(2, MOR, true, "parquet"));
  }

  @Test
  public void v3CopyOnWriteParquet() throws Exception {
    statements(new Shape(3, COW, false, "parquet"));
  }

  @Test
  public void v2CopyOnWriteParquet() throws Exception {
    statements(new Shape(2, COW, false, "parquet"));
  }

  @Test
  public void v3CopyOnWriteParquetPartitioned() throws Exception {
    statements(new Shape(3, COW, true, "parquet"));
  }

  @Test
  public void v2CopyOnWriteParquetPartitioned() throws Exception {
    statements(new Shape(2, COW, true, "parquet"));
  }

  /** ORC is the cluster's default data file format. */
  @Test
  public void v3MergeOnReadOrc() throws Exception {
    statements(new Shape(3, MOR, false, "orc"));
  }

  @Test
  public void v2MergeOnReadOrc() throws Exception {
    statements(new Shape(2, MOR, false, "orc"));
  }

  @Test
  public void v3CopyOnWriteOrc() throws Exception {
    statements(new Shape(3, COW, false, "orc"));
  }

  @Test
  public void v3MergeOnReadAvro() throws Exception {
    statements(new Shape(3, MOR, false, "avro"));
  }

  @Test
  public void v2MergeOnReadAvro() throws Exception {
    statements(new Shape(2, MOR, false, "avro"));
  }

  @Test
  public void v3CopyOnWriteAvro() throws Exception {
    statements(new Shape(3, COW, false, "avro"));
  }

  /**
   * Six rows, then six row-level statements, three of them on the data file of partition x (all
   * rows when unpartitioned): DELETE, UPDATE, MERGE with all three clauses, then MERGE with only a
   * matched update, only a matched delete and only a not-matched insert.
   */
  private void statements(Shape shape) throws Exception {
    if (shape.formatVersion == 3) {
      requireFormatV3();
    }
    String table = shape.table();
    String name = fresh(table);
    sql(shape.create(name));
    check("format version", shape.formatVersion, formatVersion(table));
    checkWriteModes(shape, table);
    Model model = new Model(shape, table);
    if (!step(
        model,
        "INSERT",
        "INSERT INTO "
            + name
            + " VALUES (1,'a','x'),(2,'b','x'),(3,'c','x'),(4,'d','y'),(5,'e','y'),(6,'f','y')",
        expect().insert(1, "a", "x").insert(2, "b", "x").insert(3, "c", "x"),
        expect().insert(4, "d", "y").insert(5, "e", "y").insert(6, "f", "y"))) {
      return;
    }
    if (!step(model, "DELETE", "DELETE FROM " + name + " WHERE id = 2", expect().delete(2))) {
      return;
    }
    if (!step(
        model,
        "UPDATE",
        "UPDATE " + name + " SET data = 'bb' WHERE id = 1",
        expect().update(1, "bb"))) {
      return;
    }
    if (!step(
        model,
        "MERGE with a matched delete, a matched update and a not-matched insert",
        "MERGE INTO "
            + name
            + " t USING (SELECT * FROM VALUES (3L,'c','x',true),(4L,'dd','y',false),"
            + "(7L,'g','x',false) AS s(id, data, p, del)) s ON t.id = s.id"
            + " WHEN MATCHED AND s.del THEN DELETE"
            + " WHEN MATCHED THEN UPDATE SET t.data = s.data"
            + " WHEN NOT MATCHED THEN INSERT (id, data, p) VALUES (s.id, s.data, s.p)",
        expect().delete(3).update(4, "dd").insert(7, "g", "x"))) {
      return;
    }
    if (!step(
        model,
        "MERGE with only a matched update",
        "MERGE INTO "
            + name
            + " t USING (SELECT * FROM VALUES (5L,'ee'),(99L,'zz') AS s(id, data)) s"
            + " ON t.id = s.id WHEN MATCHED THEN UPDATE SET t.data = s.data",
        expect().update(5, "ee"))) {
      return;
    }
    if (!step(
        model,
        "MERGE with only a matched delete",
        "MERGE INTO "
            + name
            + " t USING (SELECT * FROM VALUES (6L),(98L) AS s(id)) s"
            + " ON t.id = s.id WHEN MATCHED THEN DELETE",
        expect().delete(6))) {
      return;
    }
    if (!step(
        model,
        "MERGE with only a not-matched insert",
        "MERGE INTO "
            + name
            + " t USING (SELECT * FROM VALUES (1L,'no','x'),(8L,'h','y') AS s(id, data, p)) s"
            + " ON t.id = s.id WHEN NOT MATCHED THEN INSERT *",
        expect().insert(8, "h", "y"))) {
      return;
    }
    checkPositionDeleteFiles(model);
  }

  /**
   * The write modes the loaded table carries: what the shape asked for, so that a path that drops
   * the properties (and so writes copy-on-write, the default) fails here and not only as missing
   * deletes.
   */
  private void checkWriteModes(Shape shape, String table) throws Exception {
    Map<String, String> properties = plainTable(table).properties();
    List<String> modes = new ArrayList<>();
    for (String key : new String[] {"write.delete.mode", "write.update.mode", "write.merge.mode"}) {
      modes.add(key + "=" + properties.get(key));
    }
    check(
        "write modes of the created table",
        java.util.Arrays.asList(
            "write.delete.mode=" + shape.mode,
            "write.update.mode=" + shape.mode,
            "write.merge.mode=" + shape.mode),
        modes);
  }

  /**
   * For a v2 table: the live position delete files after each statement. On the Iceberg 1.12 line
   * one per data file that lost rows (the Spark writer rewrites a file-scoped one); an Avro table,
   * whose position delete files are partition-scoped, one per statement that deleted.
   */
  private void checkPositionDeleteFiles(Model model) {
    if (model.shape.formatVersion == 2) {
      check(
          "live position delete files after each statement",
          model.expectedPositionDeleteFiles,
          model.positionDeleteFiles);
    }
  }

  // ---------------------------------------------------------------- time travel and rollback

  @Test
  public void v3TimeTravelAndRollbackAroundARowLevelStatement() throws Exception {
    requireFormatV3();
    timeTravelAndRollback(new Shape(3, MOR, false, "parquet"));
  }

  @Test
  public void v2TimeTravelAndRollbackAroundARowLevelStatement() throws Exception {
    timeTravelAndRollback(new Shape(2, MOR, false, "parquet"));
  }

  /**
   * INSERT, DELETE, UPDATE; time travel to the snapshots before the DELETE and before the UPDATE;
   * rollback to the one before the UPDATE; then a DELETE and an INSERT: the rolled-back update
   * leaves nothing behind, and no row id is given twice.
   */
  private void timeTravelAndRollback(Shape shape) throws Exception {
    String table = shape.table() + "_rollback";
    String name = fresh(table);
    sql(shape.create(name));
    checkWriteModes(shape, table);
    Model model = new Model(shape, table);
    boolean v3 = shape.formatVersion == 3;
    if (!step(
        model,
        "INSERT",
        "INSERT INTO " + name + " VALUES (1,'a','x'),(2,'b','x'),(3,'c','x')",
        expect().insert(1, "a", "x").insert(2, "b", "x").insert(3, "c", "x"))) {
      return;
    }
    long inserted = plainTable(table).currentSnapshot().snapshotId();
    Map<Long, String> rowsAfterInsert = new TreeMap<>(model.rows);
    Map<Long, String> lineageAfterInsert = v3 ? lineage(name, "") : null;
    if (!step(model, "DELETE", "DELETE FROM " + name + " WHERE id = 2", expect().delete(2))) {
      return;
    }
    long deleted = plainTable(table).currentSnapshot().snapshotId();
    Map<Long, String> rowsAfterDelete = new TreeMap<>(model.rows);
    Map<Long, String> lineageAfterDelete = v3 ? lineage(name, "") : null;
    Observed beforeUpdate = observe(model);
    if (!step(
        model,
        "UPDATE",
        "UPDATE " + name + " SET data = 'aa' WHERE id = 1",
        expect().update(1, "aa"))) {
      return;
    }
    long nextRowIdAfterUpdate = shape.formatVersion == 3 ? nextRowId(table) : 0;

    check(
        "rows as of the snapshot before the DELETE",
        values(rowsAfterInsert),
        rows("SELECT id, data, p FROM " + name + " VERSION AS OF " + inserted));
    check(
        "rows as of the snapshot before the UPDATE",
        values(rowsAfterDelete),
        rows("SELECT id, data, p FROM " + name + " VERSION AS OF " + deleted));
    if (v3) {
      check(
          "lineage as of the snapshot before the DELETE",
          lineageAfterInsert,
          lineage(name, " VERSION AS OF " + inserted));
      check(
          "lineage as of the snapshot before the UPDATE",
          lineageAfterDelete,
          lineage(name, " VERSION AS OF " + deleted));
    }

    sql(
        "CALL "
            + CATALOG
            + ".system.rollback_to_snapshot('"
            + DB
            + "."
            + table
            + "', "
            + deleted
            + ")");
    check(
        "current snapshot after the rollback",
        deleted,
        plainTable(table).currentSnapshot().snapshotId());
    model.rows.clear();
    model.rows.putAll(rowsAfterDelete);
    check("rows after the rollback", values(rowsAfterDelete), rows(liveRowsQuery(name)));
    check(
        "deletes a reader applies after the rollback",
        beforeUpdate.appliedDeletes(),
        observe(model).appliedDeletes());
    if (shape.formatVersion == 3) {
      check("lineage after the rollback", lineageAfterDelete, lineage(name, ""));
      model.lineage = beforeUpdate.lineage;
    }

    if (!step(
        model,
        "DELETE after the rollback",
        "DELETE FROM " + name + " WHERE id = 3",
        expect().delete(3))) {
      return;
    }
    if (!step(
        model,
        "INSERT after the rollback",
        "INSERT INTO " + name + " VALUES (4,'d','x')",
        expect().insert(4, "d", "x"))) {
      return;
    }
    if (shape.formatVersion == 3) {
      long rowIdOf4 = rowIds(lineage(name, "")).get(4L);
      check(
          "the row inserted after the rollback has an id the rolled-back UPDATE did not use",
          true,
          rowIdOf4 >= nextRowIdAfterUpdate);
    }
    checkPositionDeleteFiles(model);
  }

  // ---------------------------------------------------------------- a table upgraded from v2

  /**
   * A v2 merge-on-read table with a position delete file, upgraded to v3 with {@code ALTER TABLE
   * ... SET TBLPROPERTIES ('format-version'='3')}: the next DELETE on the same data file writes a
   * deletion vector that holds the position delete as well, and the position delete file is no
   * longer live; the rows get row ids, which an UPDATE then keeps.
   */
  @Test
  public void aTableUpgradedWithPositionDeletesGetsOneDeletionVectorOnItsNextStatement()
      throws Exception {
    requireFormatV3();
    Shape v2 = new Shape(2, MOR, false, "parquet");
    String table = "upgraded_with_position_deletes";
    String name = fresh(table);
    sql(v2.create(name));
    checkWriteModes(v2, table);
    Model model = new Model(v2, table);
    if (!step(
        model,
        "INSERT on v2",
        "INSERT INTO " + name + " VALUES (1,'a','x'),(2,'b','x'),(3,'c','x'),(4,'d','x')",
        expect().insert(1, "a", "x").insert(2, "b", "x").insert(3, "c", "x").insert(4, "d", "x"))) {
      return;
    }
    long insertSequenceNumber = plainTable(table).currentSnapshot().sequenceNumber();
    if (!step(model, "DELETE on v2", "DELETE FROM " + name + " WHERE id = 2", expect().delete(2))) {
      return;
    }

    checkPositionDeleteFiles(model);
    sql("ALTER TABLE " + name + " SET TBLPROPERTIES ('format-version'='3')");
    check("format version after the upgrade", 3, formatVersion(table));
    check(
        "deletes a reader applies after the upgrade",
        Collections.singletonList("ids [1, 3, 4] of 4 rows: 1 PARQUET holding 1 positions"),
        observe(model).appliedDeletes());

    // From here the table is v3: one deletion vector per data file, which takes in the position
    // delete file's position, and every live row has a row id.
    Model v3 = new Model(new Shape(3, MOR, false, "parquet"), table);
    v3.rows.putAll(model.rows);
    // The old rows' sequence number is the v2 INSERT's (the data sequence number of their file);
    // their row ids are given by the first commit on v3, from next-row-id 0, to all four rows of
    // the data file.
    v3.upgradedRowsSequenceNumber = insertSequenceNumber;
    v3.upgradedRows = 4;
    if (!step(v3, "DELETE on v3", "DELETE FROM " + name + " WHERE id = 3", expect().delete(3))) {
      return;
    }
    check(
        "position delete files live after the first DELETE on v3",
        0L,
        observe(v3).positionDeleteFiles);
    if (!step(
        v3,
        "UPDATE on v3",
        "UPDATE " + name + " SET data = 'aa' WHERE id = 1",
        expect().update(1, "aa"))) {
      return;
    }
    if (!step(
        v3,
        "MERGE on v3",
        "MERGE INTO "
            + name
            + " t USING (SELECT * FROM VALUES (4L,'dd','x'),(5L,'e','x') AS s(id, data, p)) s"
            + " ON t.id = s.id WHEN MATCHED THEN UPDATE SET t.data = s.data"
            + " WHEN NOT MATCHED THEN INSERT *",
        expect().update(4, "dd").insert(5, "e", "x"))) {
      return;
    }
  }

  // ---------------------------------------------------------------- one statement and its checks

  /** What a statement does to the rows: deleted, updated and inserted ids. */
  static final class Change {
    final Set<Long> deleted = new TreeSet<>();
    final Map<Long, String> updated = new TreeMap<>();
    final Map<Long, String> inserted = new TreeMap<>();

    Change delete(long id) {
      deleted.add(id);
      return this;
    }

    Change update(long id, String data) {
      updated.put(id, data);
      return this;
    }

    Change insert(long id, String data, String p) {
      inserted.put(id, id + "," + data + "," + p);
      return this;
    }
  }

  private static Change expect() {
    return new Change();
  }

  /** What the checks expect of a table, kept across its statements. */
  final class Model {
    final Shape shape;
    final String table;
    final String name;
    final Map<Long, String> rows = new TreeMap<>();
    /** For a v3 table: id to {@code row id/sequence number}, as the last statement left it. */
    Map<Long, String> lineage = new TreeMap<>();
    /** The statements run so far, and at which of them each data file appeared. */
    int statements;

    final Map<String, Integer> dataFileAddedAt = new HashMap<>();
    /** Partition-scoped position delete files: the statement that wrote each, and its rows. */
    final List<long[]> partitionScoped = new ArrayList<>();
    /** For a v2 table: the live position delete files after each statement, and the expected. */
    final List<Long> positionDeleteFiles = new ArrayList<>();

    final List<Long> expectedPositionDeleteFiles = new ArrayList<>();
    /**
     * For a table upgraded from v2: the sequence number its old rows keep, and how many rows the
     * first commit on v3 gives ids to (0 once given).
     */
    long upgradedRowsSequenceNumber = -1;

    long upgradedRows;

    Model(Shape shape, String table) {
      this.shape = shape;
      this.table = table;
      this.name = CATALOG + "." + DB + "." + table;
    }
  }

  /**
   * Runs one statement and checks the table it leaves. A statement that fails is a check too
   * (baseline: it succeeds), so that a platform can declare the failure; the test's later
   * statements are then not run, as they build on this one.
   */
  private boolean step(Model model, String label, String statement, Change... changes)
      throws Exception {
    Change change = new Change();
    for (Change c : changes) {
      change.deleted.addAll(c.deleted);
      change.updated.putAll(c.updated);
      change.inserted.putAll(c.inserted);
    }
    Observed before = observe(model);
    String at = label + ": ";
    String failure = failure(statement);
    check(at + "the statement", SUCCEEDS, failure);
    if (!SUCCEEDS.equals(failure)) {
      return false;
    }
    Observed after = observe(model);

    // The rows, through every reader.
    for (Long id : change.deleted) {
      model.rows.remove(id);
    }
    for (Map.Entry<Long, String> update : change.updated.entrySet()) {
      String[] old = model.rows.get(update.getKey()).split(",");
      model.rows.put(update.getKey(), old[0] + "," + update.getValue() + "," + old[2]);
    }
    model.rows.putAll(change.inserted);
    List<String> expected = values(model.rows);
    check(at + "rows", expected, after.rows());
    check(at + "rows of Iceberg's generic reader", expected, plainRows(model.table));
    for (String reader : otherReaders()) {
      check(
          at + "rows through catalog " + reader,
          expected,
          rows(liveRowsQuery(reader + "." + DB + "." + model.table)));
    }

    // The deletes a reader applies to each data file, and the delete manifests. On v3: one
    // deletion vector (Puffin) per data file that lost rows, holding every row it lost. On v2: a
    // position delete file in the table's format, file-scoped where the file's column bounds name
    // its one data file (Parquet, ORC), and Iceberg 1.12's Spark writer then rewrites it rather
    // than adds another; an Avro delete file has no column bounds, so it is partition-scoped: each
    // statement adds one, and a reader applies every one to every data file of the table that is
    // not newer than it.
    model.statements++;
    long deletedByStatement = 0;
    for (Map.Entry<String, DataFileState> file : after.dataFiles.entrySet()) {
      model.dataFileAddedAt.putIfAbsent(file.getKey(), model.statements);
      DataFileState previous = before.dataFiles.get(file.getKey());
      long deletedNow = file.getValue().recordCount - file.getValue().liveIds.size();
      long deletedBefore = previous == null ? 0 : previous.recordCount - previous.liveIds.size();
      deletedByStatement += deletedNow - deletedBefore;
    }
    // What the statement removed from existing data files must be what the model says it deletes
    // or rewrites: on merge-on-read, the rows a reader now drops through delete files; on
    // copy-on-write none (the files are rewritten). Independent of the table, so a merge-on-read
    // table written copy-on-write fails here.
    check(
        at + "rows the statement removed from existing data files through delete files",
        model.shape.mergeOnRead() ? (long) (change.deleted.size() + change.updated.size()) : 0L,
        deletedByStatement);
    boolean fileScoped = model.shape.formatVersion == 3 || !"avro".equals(model.shape.fileFormat);
    if (!fileScoped && model.shape.mergeOnRead() && deletedByStatement > 0) {
      model.partitionScoped.add(new long[] {model.statements, deletedByStatement});
    }
    // How many delete files a reader applies per data file is checked per statement on v3 and
    // for partition-scoped files; for file-scoped v2 position delete files it is checked once,
    // over all the statements (#checkPositionDeleteFiles), as the one place where the Iceberg
    // lines differ (1.5.2 adds a file per statement).
    boolean count = model.shape.formatVersion == 3 || !fileScoped;
    List<String> expectedDeletes = new ArrayList<>();
    long filesWithDeletes = 0;
    String deleteFormat =
        model.shape.formatVersion == 3 ? "PUFFIN" : formatName(model.shape.fileFormat);
    for (Map.Entry<String, DataFileState> file : after.dataFiles.entrySet()) {
      DataFileState state = file.getValue();
      long deleted = state.recordCount - state.liveIds.size();
      filesWithDeletes += deleted > 0 ? 1 : 0;
      if (fileScoped) {
        expectedDeletes.add(describe(state, deleted > 0 ? 1 : 0, deleteFormat, deleted, count));
      } else {
        int applied = 0;
        long rows = 0;
        for (long[] scoped : model.partitionScoped) {
          if (scoped[0] >= model.dataFileAddedAt.get(file.getKey())) {
            applied++;
            rows += scoped[1];
          }
        }
        expectedDeletes.add(describe(state, applied, deleteFormat, rows, count));
      }
    }
    if (!fileScoped) {
      filesWithDeletes = model.partitionScoped.size();
    }
    Collections.sort(expectedDeletes);
    check(
        at + "deletes a reader applies, per data file",
        expectedDeletes,
        after.appliedDeletes(count));
    if (model.shape.formatVersion == 3) {
      check(at + "position delete files live", 0L, after.positionDeleteFiles);
      check(
          at + "data files with more than one live deletion vector in the delete manifests",
          Collections.emptyList(),
          after.dataFilesWithSeveralVectors());
      check(
          at + "live deletion vectors whose data file is not live",
          Collections.emptyList(),
          after.danglingVectors());
      if (!model.shape.mergeOnRead()) {
        check(at + "deletion vectors live", 0L, after.deletionVectors);
      } else {
        check(
            at + "deletion vectors live, one per data file with deletes",
            filesWithDeletes,
            after.deletionVectors);
        if (!change.deleted.isEmpty() || !change.updated.isEmpty()) {
          check(at + "a deletion vector is live", true, after.deletionVectors > 0);
        }
      }
      // Replaced, not added again: the commit removes the vector of every data file that had one
      // and lost more rows, and adds one per data file that lost rows.
      long replaced = 0;
      long added = 0;
      for (Map.Entry<String, DataFileState> file : after.dataFiles.entrySet()) {
        DataFileState previous = before.dataFiles.get(file.getKey());
        long deletedNow = file.getValue().recordCount - file.getValue().liveIds.size();
        long deletedBefore = previous == null ? 0 : previous.recordCount - previous.liveIds.size();
        if (deletedNow > deletedBefore) {
          added++;
          if (before.vectors.containsKey(file.getKey())) {
            replaced++;
          }
        }
      }
      if (before.snapshotId != after.snapshotId) {
        check(at + "added-dvs of the snapshot", added, summaryCount(after, "added-dvs"));
        check(at + "removed-dvs of the snapshot", replaced, summaryCount(after, "removed-dvs"));
      }
      lineageChecks(model, change, before, after, at);
    } else {
      check(at + "deletion vectors live", 0L, after.deletionVectors);
      model.positionDeleteFiles.add(after.positionDeleteFiles);
      model.expectedPositionDeleteFiles.add(filesWithDeletes);
    }
    return true;
  }

  private void lineageChecks(Model model, Change change, Observed before, Observed after, String at)
      throws Exception {
    Map<Long, String> now = after.lineage;
    Map<Long, String> expected = new TreeMap<>();
    long sequenceNumber = after.sequenceNumber;
    for (Long id : model.rows.keySet()) {
      String earlier = model.lineage.get(id);
      if (change.inserted.containsKey(id)) {
        expected.put(id, "fresh row id/" + sequenceNumber);
      } else if (change.updated.containsKey(id)) {
        expected.put(id, rowIdOf(earlier) + "/" + sequenceNumber);
      } else if (earlier != null) {
        expected.put(id, earlier);
      } else {
        // First statement after an upgrade: the old rows get ids from the first commit on v3, and
        // keep the sequence number of the v2 commit that wrote them.
        expected.put(id, "assigned row id/" + model.upgradedRowsSequenceNumber);
      }
    }
    Map<Long, String> actual = new TreeMap<>();
    for (Map.Entry<Long, String> row : now.entrySet()) {
      String value = row.getValue();
      long rowId = number(rowIdOf(value));
      String exp = expected.get(row.getKey());
      if (exp != null && exp.startsWith("fresh row id/")) {
        boolean fresh = rowId >= before.nextRowId && rowId < after.nextRowId;
        actual.put(row.getKey(), (fresh ? "fresh row id" : rowIdOf(value)) + "/" + seqOf(value));
      } else if (exp != null && exp.startsWith("assigned row id/")) {
        // Not null (number() is -1 then), and among the ids the first commit on v3 gave out.
        boolean assigned = rowId >= before.nextRowId && rowId < after.nextRowId;
        actual.put(
            row.getKey(), (assigned ? "assigned row id" : rowIdOf(value)) + "/" + seqOf(value));
      } else {
        actual.put(row.getKey(), value);
      }
    }
    check(at + "_row_id/_last_updated_sequence_number of every live row", expected, actual);
    Set<String> distinct = new TreeSet<>();
    for (String value : now.values()) {
      distinct.add(rowIdOf(value));
    }
    check(at + "live rows sharing a _row_id", 0, now.size() - distinct.size());
    check(
        at + "live rows with a null _row_id or _last_updated_sequence_number",
        Collections.emptyList(),
        now.entrySet().stream()
            .filter(e -> e.getValue().contains("null"))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList()));
    if (before.snapshotId != after.snapshotId) {
      // The snapshot's added-rows against the data it added: the rows of its new data files, and
      // on the first commit after an upgrade also the old rows it gives ids to.
      long addedByFiles = model.upgradedRows;
      for (Map.Entry<String, DataFileState> file : after.dataFiles.entrySet()) {
        if (!before.dataFiles.containsKey(file.getKey())) {
          addedByFiles += file.getValue().recordCount;
        }
      }
      check(
          at + "the snapshot's added-rows are the rows of the data it added",
          addedByFiles,
          after.addedRows);
      check(
          at + "next-row-id grew by the snapshot's added-rows",
          before.nextRowId + after.addedRows,
          after.nextRowId);
    }
    model.lineage = new TreeMap<>(now);
    model.upgradedRows = 0;
  }

  private static String rowIdOf(String lineage) {
    return String.valueOf(lineage).split("/")[0];
  }

  private static String seqOf(String lineage) {
    String[] parts = String.valueOf(lineage).split("/");
    return parts.length > 1 ? parts[1] : "none";
  }

  /** The number, or -1 when the value is not one (a null row id). */
  private static long number(String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static long summaryCount(Observed observed, String key) {
    return Long.parseLong(observed.summary.getOrDefault(key, "0"));
  }

  /**
   * A data file as a reader sees it: its live ids and row count, and the delete files applied to it
   * with the positions they hold (for a file-scoped delete file or a deletion vector, the rows
   * deleted from this data file).
   */
  private static String describe(
      DataFileState state, int deleteFiles, String format, long positions, boolean count) {
    return "ids "
        + state.liveIds
        + " of "
        + state.recordCount
        + " rows: "
        + (deleteFiles == 0
            ? "no deletes"
            : (count ? deleteFiles + " " : "") + format + " holding " + positions + " positions");
  }

  private static String formatName(String fileFormat) {
    return fileFormat.toUpperCase(Locale.ROOT);
  }

  // ---------------------------------------------------------------- observing a table

  /** A live data file: its record count and the ids of its live rows. */
  static final class DataFileState {
    long recordCount;
    final Set<Long> liveIds = new TreeSet<>();
    /** The delete files a reader applies to it: format and record count of each. */
    final List<String> deletes = new ArrayList<>();

    long deletedByDeleteFiles;
  }

  /** A table as one statement left it, read without Spark except for the live rows. */
  final class Observed {
    long snapshotId = -1;
    long sequenceNumber;
    long addedRows;
    long nextRowId;
    Map<String, String> summary = new HashMap<>();
    final Map<String, DataFileState> dataFiles = new TreeMap<>();
    final Map<Long, String> rowsById = new TreeMap<>();
    Map<Long, String> lineage = new TreeMap<>();
    /** Live deletion vectors of the delete manifests, by the data file each references. */
    final Map<String, List<String>> vectors = new TreeMap<>();

    long positionDeleteFiles;
    long deletionVectors;

    List<String> rows() {
      return values(rowsById);
    }

    List<String> appliedDeletes() {
      return appliedDeletes(true);
    }

    /** Per data file, the delete files a reader applies; their number only if {@code count}. */
    List<String> appliedDeletes(boolean count) {
      List<String> described = new ArrayList<>();
      for (DataFileState state : dataFiles.values()) {
        String format = state.deletes.isEmpty() ? "" : state.deletes.get(0).split(":")[0];
        boolean oneFormat = state.deletes.stream().allMatch(d -> d.startsWith(format + ":"));
        described.add(
            describe(
                state,
                state.deletes.size(),
                oneFormat ? format : state.deletes.toString(),
                state.deletedByDeleteFiles,
                count));
      }
      Collections.sort(described);
      return described;
    }

    List<String> dataFilesWithSeveralVectors() {
      return vectors.entrySet().stream()
          .filter(e -> e.getValue().size() > 1)
          .map(e -> e.getKey() + " has " + e.getValue())
          .collect(Collectors.toList());
    }

    List<String> danglingVectors() {
      return vectors.keySet().stream()
          .filter(file -> !dataFiles.containsKey(file))
          .collect(Collectors.toList());
    }
  }

  private Observed observe(Model model) throws Exception {
    Observed observed = new Observed();
    Table table = plainTable(model.table);
    Snapshot snapshot = table.currentSnapshot();
    if (snapshot == null) {
      return observed;
    }
    observed.snapshotId = snapshot.snapshotId();
    observed.sequenceNumber = snapshot.sequenceNumber();
    observed.summary = new HashMap<>(snapshot.summary());
    if (model.shape.formatVersion == 3) {
      observed.addedRows = v3Long(snapshot, "addedRows");
      observed.nextRowId = v3Long(((HasTableOperations) table).operations().current(), "nextRowId");
    }
    // What a reader applies: the scan's tasks, one per data file here (the files are small).
    try (CloseableIterable<FileScanTask> tasks = table.newScan().planFiles()) {
      for (FileScanTask task : tasks) {
        DataFileState state = new DataFileState();
        state.recordCount = task.file().recordCount();
        for (DeleteFile delete : task.deletes()) {
          state.deletes.add(delete.format() + ":" + delete.recordCount());
          state.deletedByDeleteFiles += delete.recordCount();
        }
        observed.dataFiles.put(task.file().path().toString(), state);
      }
    }
    // The live entries of the delete manifests.
    for (ManifestFile manifest : snapshot.deleteManifests(table.io())) {
      try (ManifestReader<DeleteFile> reader =
          ManifestFiles.readDeleteManifest(manifest, table.io(), table.specs())) {
        for (DeleteFile delete : reader) {
          if (delete.content() != FileContent.POSITION_DELETES) {
            continue;
          }
          if ("PUFFIN".equals(delete.format().name())) {
            observed.deletionVectors++;
            String referenced = String.valueOf(v3Value(delete, "referencedDataFile"));
            observed
                .vectors
                .computeIfAbsent(referenced, k -> new ArrayList<>())
                .add(delete.path() + "@" + v3Value(delete, "contentOffset"));
          } else {
            observed.positionDeleteFiles++;
          }
        }
      }
    }
    // The live rows, through the path under test, and the data file of each.
    for (Row row :
        sparkSession().sql("SELECT id, data, p, _file FROM " + model.name).collectAsList()) {
      long id = row.getLong(0);
      observed.rowsById.put(id, id + "," + row.get(1) + "," + row.get(2));
      DataFileState state = observed.dataFiles.get(row.getString(3));
      if (state != null) {
        state.liveIds.add(id);
      }
    }
    if (model.shape.formatVersion == 3) {
      observed.lineage = lineage(model.name, "");
    }
    return observed;
  }

  /** Each live row's {@code _row_id/_last_updated_sequence_number}, by id. */
  private Map<Long, String> lineage(String name, String asOf) throws Exception {
    Map<Long, String> lineage = new TreeMap<>();
    for (Row row :
        sparkSession()
            .sql("SELECT id, _row_id, _last_updated_sequence_number FROM " + name + asOf)
            .collectAsList()) {
      lineage.put(row.getLong(0), row.get(1) + "/" + row.get(2));
    }
    return lineage;
  }

  private static Map<Long, Long> rowIds(Map<Long, String> lineage) {
    Map<Long, Long> ids = new TreeMap<>();
    lineage.forEach((id, value) -> ids.put(id, number(rowIdOf(value))));
    return ids;
  }

  /** The live rows of Iceberg's generic reader, deletes applied, in the test JVM without Spark. */
  private List<String> plainRows(String table) throws Exception {
    List<String> rows = new ArrayList<>();
    try (CloseableIterable<Record> records = IcebergGenerics.read(plainTable(table)).build()) {
      for (Record record : records) {
        rows.add(
            record.getField("id") + "," + record.getField("data") + "," + record.getField("p"));
      }
    }
    Collections.sort(rows);
    return rows;
  }

  private int formatVersion(String table) throws Exception {
    return ((HasTableOperations) plainTable(table)).operations().current().formatVersion();
  }

  private long nextRowId(String table) throws Exception {
    return v3Long(((HasTableOperations) plainTable(table)).operations().current(), "nextRowId");
  }

  // ---------------------------------------------------------------- helpers

  /** True when this line's Iceberg can build format v3 metadata (it has row lineage). */
  protected static boolean formatV3Available() {
    try {
      TableMetadata.class.getMethod("nextRowId");
      return true;
    } catch (NoSuchMethodException e) {
      return false;
    }
  }

  private void requireFormatV3() {
    Assertions.assertEquals(
        buildLineHasFormatV3(),
        formatV3Available(),
        "the classpath's Iceberg and the build's Iceberg line disagree on format v3");
    Assumptions.assumeTrue(buildLineHasFormatV3(), NO_V3_ON_152);
  }

  /** A v3 accessor that LinkedIn's Iceberg 1.5.2 does not have, called reflectively. */
  static Object v3Value(Object target, String method) {
    try {
      Method accessor = target.getClass().getMethod(method);
      accessor.setAccessible(true);
      return accessor.invoke(target);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(method + " of " + target.getClass(), e);
    }
  }

  private static long v3Long(Object target, String method) {
    Object value = v3Value(target, method);
    return value == null ? -1 : ((Number) value).longValue();
  }

  private void sql(String statement) throws Exception {
    sparkSession().sql(statement).collectAsList();
  }

  private static final String SUCCEEDS = "succeeds";

  /** {@value #SUCCEEDS}, or the root cause of the statement's failure: class and message. */
  private String failure(String statement) throws Exception {
    try {
      sql(statement);
      return SUCCEEDS;
    } catch (Exception e) {
      Throwable root = e;
      while (root.getCause() != null && root.getCause() != root) {
        root = root.getCause();
      }
      return root.getClass().getName() + ": " + String.valueOf(root.getMessage()).split("\n")[0];
    }
  }

  private String fresh(String table) throws Exception {
    String name = CATALOG + "." + DB + "." + table;
    sql("DROP TABLE IF EXISTS " + name);
    return name;
  }

  private static String liveRowsQuery(String name) {
    return "SELECT id, data, p FROM " + name;
  }

  /** The rows of the query, comma-joined and sorted. */
  private List<String> rows(String query) throws Exception {
    List<String> rows =
        sparkSession().sql(query).collectAsList().stream()
            .map(row -> row.mkString(","))
            .collect(Collectors.toList());
    Collections.sort(rows);
    return rows;
  }

  private static List<String> values(Map<Long, String> rows) {
    List<String> values = new ArrayList<>(rows.values());
    Collections.sort(values);
    return values;
  }
}
