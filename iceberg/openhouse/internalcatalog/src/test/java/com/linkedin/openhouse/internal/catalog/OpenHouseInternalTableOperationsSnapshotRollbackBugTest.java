package com.linkedin.openhouse.internal.catalog;

import static com.linkedin.openhouse.internal.catalog.mapper.HouseTableSerdeUtils.getCanonicalFieldName;

import com.google.common.collect.ImmutableList;
import com.linkedin.openhouse.cluster.metrics.micrometer.MetricsReporter;
import com.linkedin.openhouse.cluster.storage.StorageType;
import com.linkedin.openhouse.cluster.storage.local.LocalStorage;
import com.linkedin.openhouse.internal.catalog.cache.TableMetadataCache;
import com.linkedin.openhouse.internal.catalog.fileio.FileIOManager;
import com.linkedin.openhouse.internal.catalog.mapper.HouseTableMapper;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import org.apache.commons.compress.utils.Lists;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.hadoop.HadoopFileIO;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Reproduces the silent-snapshot-drop bug observed on {@code
 * lva1-war-openhouse-dr.tracking_live_daily.PropItemActionEvent} on 2026-05-25.
 *
 * <p>Production timeline (all from the same Spark app {@code spark-c3ae5d9a-...}, set {@code
 * spark.app.name=IncrementalCompaction}):
 *
 * <ul>
 *   <li>08:30:55 — append snapshot A = 3635817277608242413 (141,901 records, total_files_size =
 *       256,971,963,123)
 *   <li>08:31:15 — append snapshot C = 7701923871999342588 (0 records, total_files_size =
 *       256,937,433,977 — byte-for-byte revert to pre-A)
 *   <li>08:31:25 — append snapshot B = 5646807110737817965 (26 records, built on top of C, not A)
 * </ul>
 *
 * <p>The 33 MB parquet that A added at {@code
 * /data/openhouse/tracking_live_daily/PropItemActionEvent-3ba224bc-.../data/datepartition=2026-05-25-00/hourpartition=2026-05-25-00/}
 * was committed, then orphaned: snapshot A was silently removed when C landed with a stale-base
 * view that did not include A in its snapshot-list payload.
 *
 * <p>Mechanism: {@link OpenHouseInternalTableOperations#doCommit(TableMetadata, TableMetadata)}
 * does not enforce a parent-snapshot CAS. Its snapshot-list reconciliation (see lines around
 * "Identify which snapshots are new vs existing" / "Remove snapshots that are no longer present in
 * the client payload") takes the client payload as authoritative. When two commits race with the
 * same base — i.e., when both writers built their proposed metadata from the same pre-A view — the
 * second commit silently produces a metadata.json that simply does not reference A, regardless of
 * whether A landed in the catalog in between. Any backstop must come from above (HTS / mysql CAS),
 * and the production incident demonstrates that the backstop did not catch this case.
 *
 * <p>This test invokes {@code doCommit} directly to isolate the catalog-operations-layer behavior.
 * It shows that the operations layer accepts both commits without complaint, and that the final
 * committed metadata does not contain A.
 */
public class OpenHouseInternalTableOperationsSnapshotRollbackBugTest {

  private static final String TEST_LOCATION_AFTER_A = "test_location_v1_with_A";
  private static final String TEST_LOCATION_AFTER_C = "test_location_v2_with_C";

  private static final TableIdentifier TEST_TABLE_IDENTIFIER =
      TableIdentifier.of("test_db", "test_table");

  private static final TableMetadata BASE_TABLE_METADATA =
      TableMetadata.newTableMetadata(
          new Schema(
              Types.NestedField.required(1, "data", Types.StringType.get()),
              Types.NestedField.required(2, "ts", Types.TimestampType.withoutZone())),
          PartitionSpec.unpartitioned(),
          getTempLocation(),
          ImmutableMap.of("format-version", "2"));

  @Mock private HouseTableRepository mockHouseTableRepository;
  @Mock private HouseTableMapper mockHouseTableMapper;
  @Mock private HouseTable mockHouseTable;
  @Mock private FileIOManager fileIOManager;
  @Captor private ArgumentCaptor<TableMetadata> tblMetadataCaptor;

  private OpenHouseInternalTableOperations openHouseInternalTableOperations;

  @SneakyThrows
  private static String getTempLocation() {
    return Files.createTempDirectory(UUID.randomUUID().toString()).toString();
  }

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    TableMetadataCache tableMetadataCache = new InMemoryTableMetadataCache();
    Mockito.when(mockHouseTableMapper.toHouseTable(Mockito.any(TableMetadata.class), Mockito.any()))
        .thenReturn(mockHouseTable);
    HadoopFileIO fileIO = new HadoopFileIO(new Configuration());
    MetricsReporter metricsReporter =
        new MetricsReporter(new SimpleMeterRegistry(), "TEST_CATALOG", Lists.newArrayList());
    openHouseInternalTableOperations =
        new OpenHouseInternalTableOperations(
            mockHouseTableRepository,
            fileIO,
            mockHouseTableMapper,
            TEST_TABLE_IDENTIFIER,
            metricsReporter,
            fileIOManager,
            tableMetadataCache);
    LocalStorage localStorage = Mockito.mock(LocalStorage.class);
    Mockito.when(fileIOManager.getStorage(fileIO)).thenReturn(localStorage);
    Mockito.when(localStorage.getType()).thenReturn(StorageType.LOCAL);
  }

  /**
   * Drives two sequential {@code doCommit} calls with the same pre-A base, mirroring the production
   * pattern where two Iceberg {@code TableOperations} instances in the same SparkSession each
   * cached the metadata before writer 1's append landed. The bug fires: writer 2's resulting
   * metadata does not contain snapshot A.
   */
  @Test
  void snapshotALandsThenIsSilentlyDroppedByStaleBaseSecondCommit() throws Exception {
    List<Snapshot> snapshots = IcebergTestUtil.getSnapshots();
    Snapshot snapshotA = snapshots.get(0); // production analog: 3635817277608242413
    Snapshot snapshotC = snapshots.get(1); // production analog: 7701923871999342588

    // ----------------------------------------------------------------------------------
    // Writer 1: appends snapshot A on top of the empty base. Its snapshot-list payload is
    // [A], its main ref points at A. This is the 08:30:55 IncrementalCompaction commit.
    // ----------------------------------------------------------------------------------
    Map<String, String> writer1Props = new HashMap<>(BASE_TABLE_METADATA.properties());
    writer1Props.put(
        CatalogConstants.SNAPSHOTS_JSON_KEY,
        SnapshotsUtil.serializedSnapshots(ImmutableList.of(snapshotA)));
    writer1Props.put(
        CatalogConstants.SNAPSHOTS_REFS_KEY,
        SnapshotsUtil.serializeMap(IcebergTestUtil.createMainBranchRefPointingTo(snapshotA)));
    writer1Props.put(getCanonicalFieldName("tableLocation"), TEST_LOCATION_AFTER_A);
    TableMetadata writer1Metadata = BASE_TABLE_METADATA.replaceProperties(writer1Props);

    try (MockedStatic<TableMetadataParser> ignored =
        Mockito.mockStatic(TableMetadataParser.class)) {
      openHouseInternalTableOperations.doCommit(BASE_TABLE_METADATA, writer1Metadata);
    }

    Mockito.verify(mockHouseTableMapper, Mockito.times(1))
        .toHouseTable(tblMetadataCaptor.capture(), Mockito.any());
    TableMetadata afterWriter1 = tblMetadataCaptor.getValue();
    Set<Long> snapshotsAfterWriter1 =
        afterWriter1.snapshots().stream().map(Snapshot::snapshotId).collect(Collectors.toSet());
    Assertions.assertTrue(
        snapshotsAfterWriter1.contains(snapshotA.snapshotId()),
        "Snapshot A must be present after writer 1's commit");
    Assertions.assertEquals(
        snapshotA.snapshotId(),
        afterWriter1.currentSnapshot().snapshotId(),
        "main must point to A after writer 1's commit");

    // ----------------------------------------------------------------------------------
    // Writer 2: still holds the pre-A base in its TableOperations cache. Its proposed
    // metadata is built from BASE_TABLE_METADATA — A is not in writer 2's view at all.
    // Its snapshot-list payload is [C], its main ref points at C. This is the 08:31:15
    // commit that, in production, byte-for-byte reverted total_files_size to pre-A.
    //
    // Critically: writer 2 passes BASE_TABLE_METADATA (pre-A) as the `base` argument,
    // not the post-A state. Production parity — writer 2's Iceberg client never knew
    // about A because it never refreshed.
    // ----------------------------------------------------------------------------------
    Map<String, String> writer2Props = new HashMap<>(BASE_TABLE_METADATA.properties());
    writer2Props.put(
        CatalogConstants.SNAPSHOTS_JSON_KEY,
        SnapshotsUtil.serializedSnapshots(ImmutableList.of(snapshotC)));
    writer2Props.put(
        CatalogConstants.SNAPSHOTS_REFS_KEY,
        SnapshotsUtil.serializeMap(IcebergTestUtil.createMainBranchRefPointingTo(snapshotC)));
    writer2Props.put(getCanonicalFieldName("tableLocation"), TEST_LOCATION_AFTER_C);
    TableMetadata writer2Metadata = BASE_TABLE_METADATA.replaceProperties(writer2Props);

    try (MockedStatic<TableMetadataParser> ignored =
        Mockito.mockStatic(TableMetadataParser.class)) {
      openHouseInternalTableOperations.doCommit(BASE_TABLE_METADATA, writer2Metadata);
    }

    // doCommit accepted writer 2's commit without throwing, even though writer 1 had
    // landed A. There is no parent-snapshot CAS at this layer.
    Mockito.verify(mockHouseTableMapper, Mockito.times(2))
        .toHouseTable(tblMetadataCaptor.capture(), Mockito.any());
    TableMetadata afterWriter2 = tblMetadataCaptor.getValue();
    Set<Long> snapshotsAfterWriter2 =
        afterWriter2.snapshots().stream().map(Snapshot::snapshotId).collect(Collectors.toSet());

    // ----------------------------------------------------------------------------------
    // BUG: writer 2's committed metadata contains only C. Snapshot A is silently gone.
    // ----------------------------------------------------------------------------------
    Assertions.assertFalse(
        snapshotsAfterWriter2.contains(snapshotA.snapshotId()),
        "BUG REPRO: snapshot A was silently dropped by writer 2's stale-base commit. "
            + "OpenHouseInternalTableOperations.doCommit does not enforce parent-snapshot CAS.");
    Assertions.assertTrue(
        snapshotsAfterWriter2.contains(snapshotC.snapshotId()),
        "Snapshot C should be present after writer 2's commit");
    Assertions.assertEquals(
        snapshotC.snapshotId(),
        afterWriter2.currentSnapshot().snapshotId(),
        "main now points to C — A's lineage is unreachable");
  }

  /**
   * Negative control: if writer 2 first refreshes (its proposed metadata includes A in both base
   * and payload), the second commit lands cleanly on top of A and both A and C remain. This shows
   * the bug is specifically triggered by the stale-base condition, not by anything else in the
   * commit path.
   */
  @Test
  void refreshedSecondCommitPreservesBothSnapshots() throws Exception {
    List<Snapshot> snapshots = IcebergTestUtil.getSnapshots();
    Snapshot snapshotA = snapshots.get(0);
    Snapshot snapshotC = snapshots.get(1);

    // Writer 1 commits A.
    Map<String, String> writer1Props = new HashMap<>(BASE_TABLE_METADATA.properties());
    writer1Props.put(
        CatalogConstants.SNAPSHOTS_JSON_KEY,
        SnapshotsUtil.serializedSnapshots(ImmutableList.of(snapshotA)));
    writer1Props.put(
        CatalogConstants.SNAPSHOTS_REFS_KEY,
        SnapshotsUtil.serializeMap(IcebergTestUtil.createMainBranchRefPointingTo(snapshotA)));
    writer1Props.put(getCanonicalFieldName("tableLocation"), TEST_LOCATION_AFTER_A);
    TableMetadata writer1Metadata = BASE_TABLE_METADATA.replaceProperties(writer1Props);
    try (MockedStatic<TableMetadataParser> ignored =
        Mockito.mockStatic(TableMetadataParser.class)) {
      openHouseInternalTableOperations.doCommit(BASE_TABLE_METADATA, writer1Metadata);
    }
    Mockito.verify(mockHouseTableMapper, Mockito.times(1))
        .toHouseTable(tblMetadataCaptor.capture(), Mockito.any());
    TableMetadata afterWriter1 = tblMetadataCaptor.getValue();

    // Writer 2 *refreshes* — its proposed metadata is built from the post-A state and
    // its payload is [A, C]. Now A survives.
    Map<String, String> writer2Props = new HashMap<>(afterWriter1.properties());
    writer2Props.put(
        CatalogConstants.SNAPSHOTS_JSON_KEY,
        SnapshotsUtil.serializedSnapshots(ImmutableList.of(snapshotA, snapshotC)));
    writer2Props.put(
        CatalogConstants.SNAPSHOTS_REFS_KEY,
        SnapshotsUtil.serializeMap(IcebergTestUtil.createMainBranchRefPointingTo(snapshotC)));
    writer2Props.put(getCanonicalFieldName("tableLocation"), TEST_LOCATION_AFTER_C);
    TableMetadata writer2Metadata = afterWriter1.replaceProperties(writer2Props);
    try (MockedStatic<TableMetadataParser> ignored =
        Mockito.mockStatic(TableMetadataParser.class)) {
      openHouseInternalTableOperations.doCommit(afterWriter1, writer2Metadata);
    }
    Mockito.verify(mockHouseTableMapper, Mockito.times(2))
        .toHouseTable(tblMetadataCaptor.capture(), Mockito.any());
    TableMetadata afterWriter2 = tblMetadataCaptor.getValue();
    Set<Long> snapshotsAfterWriter2 =
        afterWriter2.snapshots().stream().map(Snapshot::snapshotId).collect(Collectors.toSet());

    Assertions.assertTrue(
        snapshotsAfterWriter2.contains(snapshotA.snapshotId()),
        "After a proper refresh, A must survive");
    Assertions.assertTrue(
        snapshotsAfterWriter2.contains(snapshotC.snapshotId()), "C must also be present");
    Assertions.assertEquals(
        snapshotC.snapshotId(),
        afterWriter2.currentSnapshot().snapshotId(),
        "main now points to C, with A as its parent ancestor");
  }

  /**
   * Exercises the path-comparison branch of PR #611's fix that the PR's own test does not — both
   * {@code base.metadataFileLocation()} and the writer's {@code COMMIT_KEY} are non-null, distinct,
   * real metadata file paths. This is the production-realistic shape: server's loadTable returns a
   * metadata with a concrete non-null metadataFileLocation; writer's request body carries a
   * different non-null tableVersion (their stale view of the catalog).
   *
   * <p>The PR's existing {@code testDoCommitAbortsOnStaleClaimedBase} works because {@code
   * TableMetadata.buildFrom(...).build()} produces metadata with {@code metadataFileLocation() ==
   * null}, and {@code isSameMetadataPath("/path", null)} returns false via the null short-circuit —
   * bypassing the URI-normalization branch. This test covers the production shape.
   */
  @org.junit.jupiter.api.Test
  void abortFiresWhenBothPathsAreNonNullAndDifferent() throws Exception {
    List<Snapshot> snapshots = IcebergTestUtil.getSnapshots();
    Snapshot writerKnown = snapshots.get(0);
    Snapshot racing = snapshots.get(1);

    // Build a base metadata with a real non-null metadataFileLocation. Only way is to write
    // it via TableMetadataParser then read back — buildFrom(...).build() leaves it null.
    java.nio.file.Path tmpDir = Files.createTempDirectory("oh-bug-test");
    String basePath = tmpDir.resolve("00010-post-race.metadata.json").toString();
    TableMetadata baseWithKnownAndRacing =
        TableMetadata.buildFrom(BASE_TABLE_METADATA)
            .setBranchSnapshot(writerKnown, org.apache.iceberg.SnapshotRef.MAIN_BRANCH)
            .setBranchSnapshot(racing, org.apache.iceberg.SnapshotRef.MAIN_BRANCH)
            .build();
    org.apache.hadoop.fs.Path basePathFs = new org.apache.hadoop.fs.Path(basePath);
    org.apache.hadoop.fs.FileSystem fs =
        basePathFs.getFileSystem(new org.apache.hadoop.conf.Configuration());
    try (java.io.OutputStream out = fs.create(basePathFs, true)) {
      out.write(TableMetadataParser.toJson(baseWithKnownAndRacing).getBytes());
    }
    org.apache.iceberg.io.FileIO realFileIO =
        new org.apache.iceberg.hadoop.HadoopFileIO(new org.apache.hadoop.conf.Configuration());
    TableMetadata postRefreshBase = TableMetadataParser.read(realFileIO, basePath);

    Assertions.assertNotNull(
        postRefreshBase.metadataFileLocation(),
        "Test precondition: base must have non-null metadataFileLocation");

    String writerClaimedBase = tmpDir.resolve("00009-pre-race.metadata.json").toString();
    Assertions.assertNotEquals(writerClaimedBase, postRefreshBase.metadataFileLocation());

    Map<String, String> properties = new HashMap<>();
    properties.put(
        CatalogConstants.SNAPSHOTS_JSON_KEY,
        SnapshotsUtil.serializedSnapshots(java.util.Arrays.asList(writerKnown)));
    properties.put(
        CatalogConstants.SNAPSHOTS_REFS_KEY,
        SnapshotsUtil.serializeMap(IcebergTestUtil.createMainBranchRefPointingTo(writerKnown)));
    properties.put(getCanonicalFieldName("tableLocation"), TEST_LOCATION_AFTER_C);
    properties.put(CatalogConstants.COMMIT_KEY, writerClaimedBase);

    TableMetadata metadata = postRefreshBase.replaceProperties(properties);

    try (MockedStatic<TableMetadataParser> ignored =
        Mockito.mockStatic(TableMetadataParser.class)) {
      org.apache.iceberg.exceptions.CommitFailedException thrown =
          Assertions.assertThrows(
              org.apache.iceberg.exceptions.CommitFailedException.class,
              () -> openHouseInternalTableOperations.doCommit(postRefreshBase, metadata),
              "Fix must abort when writer's COMMIT_KEY (T_X) differs from the catalog's actual "
                  + "post-refresh base.metadataFileLocation() (T_Y) — even when both are non-null "
                  + "concrete file paths (the production shape).");
      Assertions.assertTrue(
          thrown.getMessage().contains("Cannot commit"),
          "Expected stale-base abort message, got: " + thrown.getMessage());
      Mockito.verify(mockHouseTableRepository, Mockito.never()).save(Mockito.any());
    }
  }

  /**
   * Validates the URI-normalization branch of {@code isSameMetadataPath}: when the writer's claimed
   * base carries a scheme like {@code file://} and the catalog's {@code
   * base.metadataFileLocation()} is scheme-less (or vice versa), the same logical path must NOT be
   * flagged as a mismatch. Non-regression — protects legitimate non-racing commits with scheme
   * variation from being falsely aborted.
   */
  @org.junit.jupiter.api.Test
  void abortDoesNotFireWhenPathsDifferOnlyInUriScheme() throws Exception {
    List<Snapshot> snapshots = IcebergTestUtil.getSnapshots();
    Snapshot writerSnap = snapshots.get(0);

    java.nio.file.Path tmpDir = Files.createTempDirectory("oh-bug-scheme-test");
    String basePath = tmpDir.resolve("00010-base.metadata.json").toString();
    TableMetadata baseMeta =
        TableMetadata.buildFrom(BASE_TABLE_METADATA)
            .setBranchSnapshot(writerSnap, org.apache.iceberg.SnapshotRef.MAIN_BRANCH)
            .build();
    org.apache.hadoop.fs.Path basePathFs = new org.apache.hadoop.fs.Path(basePath);
    org.apache.hadoop.fs.FileSystem fs =
        basePathFs.getFileSystem(new org.apache.hadoop.conf.Configuration());
    try (java.io.OutputStream out = fs.create(basePathFs, true)) {
      out.write(TableMetadataParser.toJson(baseMeta).getBytes());
    }
    org.apache.iceberg.io.FileIO realFileIO =
        new org.apache.iceberg.hadoop.HadoopFileIO(new org.apache.hadoop.conf.Configuration());
    TableMetadata base = TableMetadataParser.read(realFileIO, basePath);

    String schemelessBase = base.metadataFileLocation();
    String writerClaimedBaseWithScheme = "file://" + schemelessBase;

    Map<String, String> properties = new HashMap<>();
    properties.put(
        CatalogConstants.SNAPSHOTS_JSON_KEY,
        SnapshotsUtil.serializedSnapshots(java.util.Arrays.asList(writerSnap)));
    properties.put(
        CatalogConstants.SNAPSHOTS_REFS_KEY,
        SnapshotsUtil.serializeMap(IcebergTestUtil.createMainBranchRefPointingTo(writerSnap)));
    properties.put(getCanonicalFieldName("tableLocation"), TEST_LOCATION_AFTER_A);
    properties.put(CatalogConstants.COMMIT_KEY, writerClaimedBaseWithScheme);

    TableMetadata metadata = base.replaceProperties(properties);

    try (MockedStatic<TableMetadataParser> ignored =
        Mockito.mockStatic(TableMetadataParser.class)) {
      Assertions.assertDoesNotThrow(
          () -> openHouseInternalTableOperations.doCommit(base, metadata),
          "Fix must NOT falsely abort when the only difference is URI scheme (e.g. file:// vs "
              + "scheme-less) — that's the same logical metadata file.");
    }
  }

  private static final class InMemoryTableMetadataCache implements TableMetadataCache {
    private final Map<String, TableMetadata> cache = new ConcurrentHashMap<>();

    @Override
    public TableMetadata load(String metadataLocation, Supplier<TableMetadata> metadataLoader) {
      return cache.computeIfAbsent(metadataLocation, ignored -> metadataLoader.get());
    }

    @Override
    public TableMetadata seed(String metadataLocation, TableMetadata tableMetadata) {
      cache.put(metadataLocation, tableMetadata);
      return tableMetadata;
    }
  }
}
