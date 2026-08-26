package com.linkedin.openhouse.internal.catalog;

import static org.mockito.Mockito.*;

import com.linkedin.openhouse.cluster.metrics.micrometer.MetricsReporter;
import com.linkedin.openhouse.internal.catalog.cache.TableMetadataCache;
import com.linkedin.openhouse.internal.catalog.fileio.FileIOManager;
import com.linkedin.openhouse.internal.catalog.mapper.HouseTableMapper;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableConcurrentUpdateException;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableRepositoryStateUnknownException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
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
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotRef;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.UpdateRequirements;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.hadoop.HadoopFileIO;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
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
 * Ops-level tests for the Iceberg REST-native commit primitive: {@code
 * OpenHouseInternalTableOperations.doCommit} driven exactly the way the REST commit service drives
 * it — metadata rebuilt from a fresh base via typed {@link MetadataUpdate#applyTo}, requirements
 * validated via {@link UpdateRequirement#validate}, and NO legacy smuggled properties ({@code
 * SNAPSHOTS_JSON}, {@code COMMIT_KEY}, ...) present.
 *
 * <p>Complements the e2e controller tests with white-box assertions on the primitive itself: the
 * typed vocabulary structurally cannot drop a racing snapshot, requirement derivation matches the
 * touched refs, and store-failure exceptions keep their commit-status semantics on the typed path.
 */
public class RestNativeCommitOperationsTest {

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

  private OpenHouseInternalTableOperations ops;

  @SneakyThrows
  private static String getTempLocation() {
    return Files.createTempDirectory(UUID.randomUUID().toString()).toString();
  }

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    Mockito.when(mockHouseTableMapper.toHouseTable(Mockito.any(TableMetadata.class), Mockito.any()))
        .thenReturn(mockHouseTable);
    ops =
        new OpenHouseInternalTableOperations(
            mockHouseTableRepository,
            new HadoopFileIO(new Configuration()),
            mockHouseTableMapper,
            TEST_TABLE_IDENTIFIER,
            new MetricsReporter(new SimpleMeterRegistry(), "TEST_CATALOG", Lists.newArrayList()),
            fileIOManager,
            new InMemoryTableMetadataCache());
  }

  /**
   * The structural #612 elimination, at the primitive: a writer that derived {@code [add-snapshot
   * S_w, set-ref main→S_w]} against T_X has NO vocabulary to remove the racing snapshot S_r that
   * landed in T_Y. Re-applying the typed updates onto the fresh base T_Y keeps every snapshot, and
   * the committed metadata retains S_r.
   */
  @Test
  void testTypedReapplyOnFreshBaseCannotDropRacingSnapshot() throws IOException {
    List<Snapshot> snapshots = IcebergTestUtil.getSnapshots();
    Snapshot s1 = snapshots.get(0);
    Snapshot racing = snapshots.get(1);
    Snapshot writerNew = snapshots.get(2);

    // discardChanges(): a base loaded from metadata.json carries no pending changes; mirror that
    // for in-memory constructed bases.
    TableMetadata metadataAtTx =
        TableMetadata.buildFrom(BASE_TABLE_METADATA)
            .setBranchSnapshot(s1, SnapshotRef.MAIN_BRANCH)
            .discardChanges()
            .build();
    // The writer derives typed updates against T_X.
    List<MetadataUpdate> writerUpdates =
        TableMetadata.buildFrom(metadataAtTx)
            .setBranchSnapshot(writerNew, SnapshotRef.MAIN_BRANCH)
            .build()
            .changes();

    // Meanwhile the catalog advances to T_Y with the racing snapshot on main.
    TableMetadata metadataAtTy =
        TableMetadata.buildFrom(metadataAtTx)
            .setBranchSnapshot(racing, SnapshotRef.MAIN_BRANCH)
            .discardChanges()
            .build();

    // Server-side re-apply on the FRESH base (what the commit loop does after a refresh).
    TableMetadata.Builder rebuilt = TableMetadata.buildFrom(metadataAtTy);
    writerUpdates.forEach(update -> update.applyTo(rebuilt));
    TableMetadata reapplied = rebuilt.build();

    Assertions.assertEquals(
        ImmutableList.of(s1.snapshotId(), racing.snapshotId(), writerNew.snapshotId()),
        reapplied.snapshots().stream().map(Snapshot::snapshotId).collect(Collectors.toList()),
        "Typed re-apply must retain the racing snapshot — appends are monotone");

    // And the primitive persists exactly that snapshot set (no subtractive merge on this path).
    try (MockedStatic<TableMetadataParser> ignored =
        Mockito.mockStatic(TableMetadataParser.class)) {
      ops.doCommit(metadataAtTy, reapplied);
    }
    Mockito.verify(mockHouseTableMapper).toHouseTable(tblMetadataCaptor.capture(), Mockito.any());
    Set<Long> committedIds =
        tblMetadataCaptor.getValue().snapshots().stream()
            .map(Snapshot::snapshotId)
            .collect(Collectors.toSet());
    Assertions.assertTrue(committedIds.contains(racing.snapshotId()));
    Assertions.assertTrue(committedIds.contains(writerNew.snapshotId()));
    Assertions.assertTrue(committedIds.contains(s1.snapshotId()));
    Mockito.verify(mockHouseTableRepository, Mockito.times(1)).save(Mockito.any());
  }

  /**
   * Requirement checks happen at the commit point against the fresh base: the stale writer's {@code
   * assert-ref-snapshot-id(main, S1)} holds at T_X but fails at T_Y — the 409 that replaces the
   * legacy silent rebase.
   */
  @Test
  void testAssertRefSnapshotIdValidatesAgainstFreshBase() throws IOException {
    List<Snapshot> snapshots = IcebergTestUtil.getSnapshots();
    Snapshot s1 = snapshots.get(0);
    Snapshot racing = snapshots.get(1);

    TableMetadata metadataAtTx =
        TableMetadata.buildFrom(BASE_TABLE_METADATA)
            .setBranchSnapshot(s1, SnapshotRef.MAIN_BRANCH)
            .build();
    TableMetadata metadataAtTy =
        TableMetadata.buildFrom(metadataAtTx)
            .setBranchSnapshot(racing, SnapshotRef.MAIN_BRANCH)
            .build();

    UpdateRequirement staleAssertion =
        new UpdateRequirement.AssertRefSnapshotID(SnapshotRef.MAIN_BRANCH, s1.snapshotId());

    Assertions.assertDoesNotThrow(() -> staleAssertion.validate(metadataAtTx));
    Assertions.assertThrows(
        CommitFailedException.class, () -> staleAssertion.validate(metadataAtTy));
  }

  /** Requirement derivation matches what a stock client sends for each update shape. */
  @Test
  void testRequirementDerivationPerUpdateShape() throws IOException {
    List<Snapshot> snapshots = IcebergTestUtil.getSnapshots();
    Snapshot s1 = snapshots.get(0);
    Snapshot s2 = snapshots.get(1);
    TableMetadata base =
        TableMetadata.buildFrom(BASE_TABLE_METADATA)
            .setBranchSnapshot(s1, SnapshotRef.MAIN_BRANCH)
            .discardChanges()
            .build();

    // Append + set-ref on main: assert-table-uuid + assert-ref-snapshot-id(main, S1).
    List<MetadataUpdate> appendUpdates =
        TableMetadata.buildFrom(base)
            .setBranchSnapshot(s2, SnapshotRef.MAIN_BRANCH)
            .build()
            .changes();
    List<UpdateRequirement> appendRequirements =
        UpdateRequirements.forUpdateTable(base, appendUpdates);
    Assertions.assertEquals(2, appendRequirements.size());
    Assertions.assertTrue(appendRequirements.get(0) instanceof UpdateRequirement.AssertTableUUID);
    Assertions.assertEquals(
        base.uuid(), ((UpdateRequirement.AssertTableUUID) appendRequirements.get(0)).uuid());
    UpdateRequirement.AssertRefSnapshotID refAssertion =
        (UpdateRequirement.AssertRefSnapshotID) appendRequirements.get(1);
    Assertions.assertEquals(SnapshotRef.MAIN_BRANCH, refAssertion.refName());
    Assertions.assertEquals(s1.snapshotId(), refAssertion.snapshotId());

    // Property-only: assert-table-uuid only — no ref is asserted, so property commits are
    // logically independent of snapshot commits.
    List<MetadataUpdate> propUpdates =
        ImmutableList.of(new MetadataUpdate.SetProperties(ImmutableMap.of("user.k", "v")));
    List<UpdateRequirement> propRequirements = UpdateRequirements.forUpdateTable(base, propUpdates);
    Assertions.assertEquals(1, propRequirements.size());
    Assertions.assertTrue(propRequirements.get(0) instanceof UpdateRequirement.AssertTableUUID);

    // Set-ref on a NEW branch: assert-ref-snapshot-id(branch, null) — the ref must not exist.
    MetadataUpdate newBranchSetRef =
        TableMetadata.buildFrom(base).setBranchSnapshot(s2, "wap_branch").build().changes().stream()
            .filter(update -> update instanceof MetadataUpdate.SetSnapshotRef)
            .findFirst()
            .orElseThrow(IllegalStateException::new);
    List<MetadataUpdate> branchUpdates =
        ImmutableList.of(new MetadataUpdate.AddSnapshot(s2), newBranchSetRef);
    List<UpdateRequirement> branchRequirements =
        UpdateRequirements.forUpdateTable(base, branchUpdates);
    Assertions.assertEquals(2, branchRequirements.size());
    UpdateRequirement.AssertRefSnapshotID branchAssertion =
        (UpdateRequirement.AssertRefSnapshotID) branchRequirements.get(1);
    Assertions.assertEquals("wap_branch", branchAssertion.refName());
    Assertions.assertNull(branchAssertion.snapshotId());

    // assert-ref-snapshot-id(branch, null) fails once the branch exists — a concurrent creation
    // of the same branch is caught at the commit point.
    TableMetadata withBranch =
        TableMetadata.buildFrom(base).setBranchSnapshot(s2, "wap_branch").build();
    Assertions.assertThrows(
        CommitFailedException.class, () -> branchAssertion.validate(withBranch));
    // assert-table-uuid catches a table that was dropped and recreated under the same name.
    UpdateRequirement.AssertTableUUID uuidAssertion =
        new UpdateRequirement.AssertTableUUID(UUID.randomUUID().toString());
    Assertions.assertThrows(CommitFailedException.class, () -> uuidAssertion.validate(base));
  }

  /**
   * Typed removal is explicit: {@code remove-snapshots [id]} removes exactly the named snapshot and
   * nothing else, and the commit primitive persists exactly that.
   */
  @Test
  void testExplicitRemoveSnapshotRemovesOnlyNamedSnapshot() throws IOException {
    List<Snapshot> snapshots = IcebergTestUtil.getSnapshots();
    Snapshot s1 = snapshots.get(0);
    Snapshot s2 = snapshots.get(1);
    TableMetadata base =
        TableMetadata.buildFrom(BASE_TABLE_METADATA)
            .setBranchSnapshot(s1, SnapshotRef.MAIN_BRANCH)
            .setBranchSnapshot(s2, SnapshotRef.MAIN_BRANCH)
            .build();

    TableMetadata.Builder builder = TableMetadata.buildFrom(base);
    new MetadataUpdate.RemoveSnapshot(s1.snapshotId()).applyTo(builder);
    TableMetadata updated = builder.build();

    try (MockedStatic<TableMetadataParser> ignored =
        Mockito.mockStatic(TableMetadataParser.class)) {
      ops.doCommit(base, updated);
    }
    Mockito.verify(mockHouseTableMapper).toHouseTable(tblMetadataCaptor.capture(), Mockito.any());
    TableMetadata committed = tblMetadataCaptor.getValue();
    Assertions.assertEquals(
        ImmutableList.of(s2.snapshotId()),
        committed.snapshots().stream().map(Snapshot::snapshotId).collect(Collectors.toList()));
    Assertions.assertEquals(s2.snapshotId(), committed.ref(SnapshotRef.MAIN_BRANCH).snapshotId());
  }

  /**
   * Store-failure semantics on the typed path (no smuggled props): the HTS concurrent-update signal
   * is a retryable {@link CommitFailedException}; an HTS 5xx keeps {@link
   * CommitStateUnknownException} so callers never treat ambiguity as failure.
   */
  @Test
  void testStoreFailureSemanticsOnTypedPath() throws IOException {
    List<Snapshot> snapshots = IcebergTestUtil.getSnapshots();
    TableMetadata base =
        TableMetadata.buildFrom(BASE_TABLE_METADATA)
            .setBranchSnapshot(snapshots.get(0), SnapshotRef.MAIN_BRANCH)
            .build();
    // Speed up the ambiguous-failure commit-status probe.
    TableMetadata.Builder builder =
        TableMetadata.buildFrom(base)
            .setProperties(
                ImmutableMap.of(
                    "commit.status-check.num-retries", "1",
                    "commit.status-check.min-wait-ms", "10",
                    "commit.status-check.max-wait-ms", "20",
                    "commit.status-check.total-timeout-ms", "100"));
    new MetadataUpdate.SetProperties(ImmutableMap.of("user.k", "v")).applyTo(builder);
    TableMetadata updated = builder.build();

    try (MockedStatic<TableMetadataParser> ignored =
        Mockito.mockStatic(TableMetadataParser.class)) {
      when(mockHouseTableRepository.save(Mockito.any(HouseTable.class)))
          .thenThrow(new HouseTableConcurrentUpdateException("race", null));
      Assertions.assertThrows(CommitFailedException.class, () -> ops.doCommit(base, updated));

      Mockito.reset(mockHouseTableRepository);
      when(mockHouseTableRepository.save(Mockito.any(HouseTable.class)))
          .thenThrow(new HouseTableRepositoryStateUnknownException("hts 5xx", null));
      Assertions.assertThrows(CommitStateUnknownException.class, () -> ops.doCommit(base, updated));
    }
  }

  /**
   * On the typed path the committed properties carry no legacy transport keys, and the
   * catalog-owned bookkeeping ({@code openhouse.tableVersion} == prior metadata location) is
   * stamped server-side.
   */
  @Test
  void testNoLegacyTransportPropertiesAndVersionStamping() throws IOException {
    java.nio.file.Path tmpDir = Files.createTempDirectory("rest-native-ops-test");
    String baseLocation = tmpDir.resolve("00001-base.metadata.json").toString();
    // A production base always carries the catalog-stamped openhouse.tableLocation of the
    // metadata.json it was loaded from; seed it so version stamping is observable.
    TableMetadata seededBase =
        TableMetadata.buildFrom(BASE_TABLE_METADATA)
            .setProperties(ImmutableMap.of("openhouse.tableLocation", baseLocation))
            .build();
    java.nio.file.Files.write(
        tmpDir.resolve("00001-base.metadata.json"),
        TableMetadataParser.toJson(seededBase).getBytes());
    TableMetadata base =
        TableMetadataParser.read(new HadoopFileIO(new Configuration()), baseLocation);

    TableMetadata.Builder builder = TableMetadata.buildFrom(base);
    List<MetadataUpdate> updates =
        ImmutableList.of(new MetadataUpdate.SetProperties(ImmutableMap.of("user.k", "v")));
    updates.forEach(update -> update.applyTo(builder));
    TableMetadata updated = builder.build();

    try (MockedStatic<TableMetadataParser> ignored =
        Mockito.mockStatic(TableMetadataParser.class)) {
      ops.doCommit(base, updated);
    }
    Mockito.verify(mockHouseTableMapper).toHouseTable(tblMetadataCaptor.capture(), Mockito.any());
    Map<String, String> committedProps = tblMetadataCaptor.getValue().properties();

    Assertions.assertFalse(committedProps.containsKey(CatalogConstants.SNAPSHOTS_JSON_KEY));
    Assertions.assertFalse(committedProps.containsKey(CatalogConstants.SNAPSHOTS_REFS_KEY));
    Assertions.assertFalse(committedProps.containsKey(CatalogConstants.COMMIT_KEY));
    Assertions.assertEquals(baseLocation, committedProps.get("openhouse.tableVersion"));
    Assertions.assertTrue(committedProps.containsKey("openhouse.tableLocation"));
    Assertions.assertNotEquals(
        baseLocation,
        committedProps.get("openhouse.tableLocation"),
        "A new metadata location must be allocated for the committed version");
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
