package com.linkedin.openhouse.jobs.spark;

import static com.linkedin.openhouse.jobs.spark.FormatV3Tables.DELETED_IDS;
import static com.linkedin.openhouse.jobs.spark.FormatV3Tables.liveDataFiles;
import static com.linkedin.openhouse.jobs.spark.FormatV3Tables.liveDeleteFiles;
import static com.linkedin.openhouse.jobs.spark.FormatV3Tables.snapshotIds;

import com.linkedin.openhouse.datalayout.config.DataCompactionConfig;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.iceberg.ContentFile;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotRef;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The maintenance jobs on a table with branches and tags (work item X10), format v3 and v2.
 *
 * <p>What OpenHouse allows: a table can have branches and tags through the native API (Spark SQL
 * {@code CREATE BRANCH}/{@code CREATE TAG} through the OpenHouse catalog; the snapshots PUT carries
 * the client's refs and the service sets exactly those) and through the Iceberg REST routes; both
 * are pinned here. The table property {@code history.expire.max-ref-age-ms} is held at 7 days at
 * most, so snapshot expiry drops a ref whose head is older; every ref here is minutes old and must
 * survive.
 *
 * <p>The shape: the base fixture on main (12 snapshots, deletes in every data file; deletion
 * vectors on v3), then a tag {@code before_branch} at main's head and a tag {@code old_main} at the
 * fifth snapshot (after the inserts, before any delete: a snapshot expiry would remove but for the
 * tag), a branch {@code dev} at main's head on which rows are inserted, deleted (a deletion vector
 * or position delete file only {@code dev} references) and updated, with a tag {@code dev_mid} on
 * one of its snapshots; then main diverges: a data file in its own partition that main adds and
 * removes again (referenced by one main snapshot only), and a DELETE that replaces, on v3, a
 * deletion vector the tags and {@code dev} still read.
 */
public class FormatV3BranchesJobsTest extends FormatV3ShapeJobsTestBase {
  @Override
  String db() {
    return "x10branch";
  }

  @Override
  Shape createShape(int formatVersion, String name) {
    SparkSession spark = session(formatVersion);
    String fqtn = tables.createTable(formatVersion, name, "orc");
    String t = "openhouse." + fqtn;
    Table table = table(formatVersion, fqtn);
    List<Long> mainSnapshots = snapshotIds(table);
    spark.sql("ALTER TABLE " + t + " CREATE TAG before_branch");
    spark.sql("ALTER TABLE " + t + " CREATE TAG old_main AS OF VERSION " + mainSnapshots.get(4));
    spark.sql("ALTER TABLE " + t + " CREATE BRANCH dev");
    String dev = t + ".branch_dev";
    spark.sql(
        "INSERT INTO "
            + dev
            + " VALUES (300, 'dev-300', "
            + ts(0, 30)
            + "), (301, 'dev-301', "
            + ts(0, 31)
            + ")");
    spark.sql("DELETE FROM " + dev + " WHERE id IN (1, 300)");
    spark.sql("UPDATE " + dev + " SET data = 'dev-updated' WHERE id = 17");
    long devMid = table(formatVersion, fqtn).refs().get("dev").snapshotId();
    spark.sql("ALTER TABLE " + t + " CREATE TAG dev_mid AS OF VERSION " + devMid);
    spark.sql("DELETE FROM " + dev + " WHERE id = 25");
    // Main diverges: a file in a partition of its own (2 days ago) that main adds and then drops by
    // a metadata-only DELETE, so only one main snapshot references it; then a DELETE on a data
    // file whose deletes the tags and dev still read.
    spark.sql("INSERT INTO " + t + " VALUES (400, 'main-400', " + ts(2, 1) + ")");
    spark.sql("DELETE FROM " + t + " WHERE ts >= " + ts(2, 0) + " AND ts < " + ts(1, 0));
    spark.sql("DELETE FROM " + t + " WHERE id IN (4, 5)");

    table = table(formatVersion, fqtn);
    Map<String, SnapshotRef> refs = table.refs();
    Assertions.assertEquals(
        new TreeSet<>(java.util.Arrays.asList(MAIN, "dev", "before_branch", "old_main", "dev_mid")),
        new TreeSet<>(refs.keySet()));
    Assertions.assertTrue(refs.get("dev").isBranch() && refs.get("old_main").isTag());
    Assertions.assertEquals(19, snapshotIds(table).size(), "12 + 3 on main, 4 on dev");
    // Not vacuous: dev's head references delete files and data files that main's head does not.
    Snapshot mainHead = table.currentSnapshot();
    Snapshot devHead = table.snapshot(refs.get("dev").snapshotId());
    Set<String> devOnly = paths(liveDeleteFiles(table, devHead));
    devOnly.removeAll(paths(liveDeleteFiles(table, mainHead)));
    Assertions.assertFalse(devOnly.isEmpty(), "delete files only dev references");
    Set<String> devData = paths(liveDataFiles(table, devHead));
    devData.removeAll(paths(liveDataFiles(table, mainHead)));
    Assertions.assertFalse(devData.isEmpty(), "data files only dev references");
    if (formatVersion == 3) {
      Assertions.assertTrue(
          devOnly.stream().allMatch(p -> p.endsWith(".puffin")), "dev's deletes are DVs");
    }
    Set<Long> base = new TreeSet<>(DELETED_IDS);
    return new Shape(
        fqtn,
        deleted(
            MAIN,
            union(base, 4, 5, 400),
            "dev",
            union(base, 1, 300, 25),
            "dev_mid",
            union(base, 1, 300),
            "before_branch",
            base));
  }

  private static Set<String> paths(List<? extends ContentFile<?>> files) {
    return files.stream()
        .map(f -> f.path().toString())
        .collect(Collectors.toCollection(HashSet::new));
  }

  @Override
  void afterJob(String job, int formatVersion, Shape shape, RefsReading before) {
    Table table = table(formatVersion, shape.fqtn);
    if ("expiry".equals(job)) {
      // retainLast applies per branch: main and dev keep their last 2 each, the tags their own,
      // and dev's two older snapshots expired too.
      Snapshot mainHead = table.currentSnapshot();
      Snapshot devHead = table.snapshot(table.refs().get("dev").snapshotId());
      Set<Long> expected =
          new TreeSet<>(
              java.util.Arrays.asList(
                  mainHead.snapshotId(),
                  mainHead.parentId(),
                  devHead.snapshotId(),
                  devHead.parentId(),
                  table.refs().get("before_branch").snapshotId(),
                  table.refs().get("old_main").snapshotId()));
      Assertions.assertEquals(
          table.refs().get("dev_mid").snapshotId(), (long) devHead.parentId(), "dev_mid kept");
      Assertions.assertEquals(expected, new TreeSet<>(snapshotIds(table)), "retained snapshots");
    }
  }

  /** The branch and the tags as built, through the native API: what the jobs then work on. */
  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void branchesAndTagsAreAcceptedNatively(int formatVersion) {
    Shape shape = createShape(formatVersion, "native_refs");
    Assertions.assertEquals(5, table(formatVersion, shape.fqtn).refs().size());
  }

  /** The Iceberg REST routes accept a branch, a tag and a commit to the branch too. */
  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void branchesAndTagsAreAcceptedOverRest(int formatVersion) {
    String fqtn = tables.createTable(formatVersion, "rest_refs", "orc");
    RESTCatalog rest = rest(formatVersion);
    Table table = rest.loadTable(TableIdentifier.parse(fqtn));
    long head = table.currentSnapshot().snapshotId();
    table.manageSnapshots().createBranch("rest_dev", head).createTag("rest_tag", head).commit();
    session(formatVersion).sql("REFRESH TABLE openhouse." + fqtn);
    session(formatVersion)
        .sql(
            "INSERT INTO openhouse."
                + fqtn
                + ".branch_rest_dev VALUES (500, 'rest-500', "
                + ts(0, 50)
                + ")");
    Table loaded = table(formatVersion, fqtn);
    Assertions.assertEquals(head, loaded.refs().get("rest_tag").snapshotId());
    Assertions.assertNotEquals(head, loaded.refs().get("rest_dev").snapshotId());
    Assertions.assertEquals(head, loaded.currentSnapshot().snapshotId(), "main unchanged");
  }

  /**
   * Compaction rewrites main's files; dev and the tags still reference the replaced files. Expiry
   * (deleting files) and orphan removal (no age margin) then run: every file dev and the tags read
   * must survive, while files only expired main snapshots referenced go.
   */
  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void compactionThenExpiryThenOrphanRemovalKeepFilesBranchesShareWithMain(int formatVersion)
      throws Exception {
    Shape shape = createShape(formatVersion, "compact_expire");
    RefsReading before = read(formatVersion, shape.fqtn);
    Table table = table(formatVersion, shape.fqtn);
    Set<String> mainFilesBefore = paths(liveDataFiles(table, table.currentSnapshot()));
    Snapshot devHead = table.snapshot(table.refs().get("dev").snapshotId());
    Set<String> sharedWithDev = paths(liveDataFiles(table, devHead));
    sharedWithDev.retainAll(mainFilesBefore);
    Assertions.assertFalse(sharedWithDev.isEmpty(), "dev shares data files with main");

    Operations ops = ops(formatVersion);
    DataCompactionSparkApp compaction =
        new DataCompactionSparkApp(
            "x10b-compaction-branch",
            null,
            shape.fqtn,
            DataCompactionConfig.builder().minInputFiles(2).deleteFileThreshold(1).build(),
            otelEmitter);
    compaction.runInner(ops);
    compaction.runValidations(ops);
    table = table(formatVersion, shape.fqtn);
    Set<String> mainFilesAfter = paths(liveDataFiles(table, table.currentSnapshot()));
    Set<String> replacedShared = new HashSet<>(sharedWithDev);
    replacedShared.removeAll(mainFilesAfter);
    Assertions.assertFalse(
        replacedShared.isEmpty(), "compaction replaced on main files dev still reads");

    new SnapshotsExpirationSparkApp(
            "x10b-expiry-branch", null, shape.fqtn, 3, "days", 2, true, ".backup", otelEmitter)
        .runInner(ops);
    new OrphanFilesDeletionSparkApp(
            "x10b-ofd-branch", null, shape.fqtn, 0, otelEmitter, ".backup", 2, false, 1000)
        .runInner(ops);
    table = table(formatVersion, shape.fqtn);
    for (String file : replacedShared) {
      Assertions.assertTrue(table.io().newInputFile(file).exists(), "dev's file kept: " + file);
    }
    assertNoLiveDataLost(formatVersion, shape, before);
  }
}
