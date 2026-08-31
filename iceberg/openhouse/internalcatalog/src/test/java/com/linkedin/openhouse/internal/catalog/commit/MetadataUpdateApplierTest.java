package com.linkedin.openhouse.internal.catalog.commit;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.iceberg.GenericStatisticsFile;
import org.apache.iceberg.ImmutableGenericPartitionStatisticsFile;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.MetadataUpdateParser;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.PartitionStatisticsFile;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotRef;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.StatisticsFile;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.ValidationException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MetadataUpdateApplierTest {

  /**
   * Every {@link MetadataUpdate} implementation Iceberg ships on our compile classpath. The applier
   * classifies each one as table-applicable or view-only; this asserts that the classification is
   * exhaustive, so an Iceberg upgrade that adds a type fails the build and forces a human to place
   * it, rather than letting a new update reach the rejection branch unexamined.
   */
  @Test
  public void testEveryClasspathUpdateTypeIsClassified() {
    Set<String> onClasspath =
        Arrays.stream(MetadataUpdate.class.getDeclaredClasses())
            .map(Class::getSimpleName)
            .collect(Collectors.toSet());
    Set<String> classified =
        Stream.concat(
                MetadataUpdateApplier.SUPPORTED_TABLE_UPDATES.stream(),
                MetadataUpdateApplier.VIEW_ONLY_UPDATES.stream())
            .map(Class::getSimpleName)
            .collect(Collectors.toSet());
    Assertions.assertEquals(
        onClasspath,
        classified,
        "Iceberg's MetadataUpdate types changed; review MetadataUpdateApplier");
  }

  @Test
  public void testSupportedAndViewOnlyUpdatesDoNotOverlap() {
    Assertions.assertTrue(
        Collections.disjoint(
            MetadataUpdateApplier.SUPPORTED_TABLE_UPDATES,
            MetadataUpdateApplier.VIEW_ONLY_UPDATES));
  }

  private TableMetadata base;

  @BeforeEach
  void setUp() {
    base = CommitTestFixtures.baseMetadata();
  }

  // ---------------------------------------------------------------------------------------------
  // Argument handling
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testEmptyUpdatesReturnTheSameMetadataInstance() {
    // Iceberg's builder hands back the metadata it started from when nothing changed. Pinning the
    // identity here is what makes the warning in apply()'s contract checkable: a caller cannot use
    // "is this a new object?" to decide whether a commit changed anything.
    Assertions.assertSame(base, MetadataUpdateApplier.apply(base, Collections.emptyList()));
  }

  @Test
  public void testNullUpdateListRejected() {
    Assertions.assertThrows(
        IllegalArgumentException.class, () -> MetadataUpdateApplier.apply(base, null));
  }

  @Test
  public void testNullUpdateElementRejected() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> MetadataUpdateApplier.apply(base, Collections.singletonList(null)));
  }

  // ---------------------------------------------------------------------------------------------
  // One test per update type that carries table semantics
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testAssignUuid() {
    // Iceberg forbids reassigning a table's uuid; re-asserting the same one is the legal case here.
    // Assigning a fresh uuid is exercised by the create-from-nothing test below.
    TableMetadata result = apply(new MetadataUpdate.AssignUUID(base.uuid()));
    Assertions.assertEquals(base.uuid(), result.uuid());
  }

  @Test
  public void testUpgradeFormatVersion() {
    Map<String, String> v1 = new HashMap<>();
    v1.put("format-version", "1");
    TableMetadata v1Metadata =
        TableMetadata.newTableMetadata(
            CommitTestFixtures.SCHEMA,
            CommitTestFixtures.spec(),
            CommitTestFixtures.sortOrder(),
            CommitTestFixtures.LOCATION,
            v1);
    Assertions.assertEquals(1, v1Metadata.formatVersion());

    TableMetadata result =
        MetadataUpdateApplier.apply(
            v1Metadata, Collections.singletonList(new MetadataUpdate.UpgradeFormatVersion(2)));
    Assertions.assertEquals(2, result.formatVersion());
  }

  @Test
  public void testAddSchemaAndSetCurrentSchema() {
    TableMetadata result =
        MetadataUpdateApplier.apply(
            base,
            Arrays.asList(
                new MetadataUpdate.AddSchema(CommitTestFixtures.EVOLVED_SCHEMA, 3),
                new MetadataUpdate.SetCurrentSchema(-1)));
    Assertions.assertEquals(2, result.schemas().size());
    Assertions.assertEquals(3, result.lastColumnId());
    Assertions.assertNotNull(result.schema().findField("extra"));
  }

  @Test
  public void testAddPartitionSpecAndSetDefaultSpec() {
    PartitionSpec newSpec =
        PartitionSpec.builderFor(CommitTestFixtures.SCHEMA).identity("id").withSpecId(1).build();
    TableMetadata result =
        MetadataUpdateApplier.apply(
            base,
            Arrays.asList(
                new MetadataUpdate.AddPartitionSpec(newSpec),
                new MetadataUpdate.SetDefaultPartitionSpec(-1)));
    Assertions.assertEquals(2, result.specs().size());
    Assertions.assertNotEquals(base.defaultSpecId(), result.defaultSpecId());
    Assertions.assertEquals("id", result.spec().fields().get(0).name().replace("_identity", ""));
  }

  @Test
  public void testAddSortOrderAndSetDefaultSortOrder() {
    SortOrder newOrder = SortOrder.builderFor(CommitTestFixtures.SCHEMA).desc("id").build();
    TableMetadata result =
        MetadataUpdateApplier.apply(
            base,
            Arrays.asList(
                new MetadataUpdate.AddSortOrder(newOrder),
                new MetadataUpdate.SetDefaultSortOrder(-1)));
    Assertions.assertNotEquals(base.defaultSortOrderId(), result.defaultSortOrderId());
    Assertions.assertEquals(
        org.apache.iceberg.SortDirection.DESC, result.sortOrder().fields().get(0).direction());
  }

  @Test
  public void testSetLocation() {
    TableMetadata result = apply(new MetadataUpdate.SetLocation("/tmp/openhouse/db/moved"));
    Assertions.assertEquals("/tmp/openhouse/db/moved", result.location());
  }

  @Test
  public void testSetAndRemoveProperties() {
    TableMetadata withProps =
        apply(new MetadataUpdate.SetProperties(Collections.singletonMap("owner", "openhouse")));
    Assertions.assertEquals("openhouse", withProps.properties().get("owner"));

    TableMetadata withoutProps =
        MetadataUpdateApplier.apply(
            withProps,
            Collections.singletonList(
                new MetadataUpdate.RemoveProperties(Collections.singleton("owner"))));
    Assertions.assertFalse(withoutProps.properties().containsKey("owner"));
  }

  @Test
  public void testAddSnapshot() {
    Snapshot snapshot = CommitTestFixtures.snapshot(1L, 1L);
    TableMetadata result = apply(new MetadataUpdate.AddSnapshot(snapshot));
    Assertions.assertEquals(1, result.snapshots().size());
    Assertions.assertEquals(snapshot.snapshotId(), result.snapshots().get(0).snapshotId());
  }

  @Test
  public void testRemoveSnapshot() {
    Snapshot snapshot = CommitTestFixtures.snapshot(1L, 1L);
    TableMetadata result =
        MetadataUpdateApplier.apply(
            base,
            Arrays.asList(
                new MetadataUpdate.AddSnapshot(snapshot),
                new MetadataUpdate.RemoveSnapshot(snapshot.snapshotId())));
    Assertions.assertTrue(result.snapshots().isEmpty());
  }

  @Test
  public void testSetAndRemoveSnapshotRef() {
    Snapshot snapshot = CommitTestFixtures.snapshot(1L, 1L);
    TableMetadata withRef =
        MetadataUpdateApplier.apply(
            base,
            Arrays.asList(
                new MetadataUpdate.AddSnapshot(snapshot),
                setSnapshotRef(SnapshotRef.MAIN_BRANCH, snapshot.snapshotId(), "branch"),
                setSnapshotRef("audit", snapshot.snapshotId(), "tag")));
    Assertions.assertEquals(snapshot.snapshotId(), withRef.ref("audit").snapshotId());
    Assertions.assertEquals(snapshot.snapshotId(), withRef.currentSnapshot().snapshotId());

    TableMetadata withoutRef =
        MetadataUpdateApplier.apply(
            withRef, Collections.singletonList(new MetadataUpdate.RemoveSnapshotRef("audit")));
    Assertions.assertNull(withoutRef.ref("audit"));
  }

  @Test
  public void testSetAndRemoveStatistics() {
    Snapshot snapshot = CommitTestFixtures.snapshot(1L, 1L);
    StatisticsFile statisticsFile =
        new GenericStatisticsFile(
            snapshot.snapshotId(),
            CommitTestFixtures.LOCATION + "/metadata/stats.puffin",
            128L,
            32L,
            Collections.emptyList());

    TableMetadata withStats =
        MetadataUpdateApplier.apply(
            base,
            Arrays.asList(
                new MetadataUpdate.AddSnapshot(snapshot),
                new MetadataUpdate.SetStatistics(snapshot.snapshotId(), statisticsFile)));
    Assertions.assertEquals(1, withStats.statisticsFiles().size());

    TableMetadata withoutStats =
        MetadataUpdateApplier.apply(
            withStats,
            Collections.singletonList(new MetadataUpdate.RemoveStatistics(snapshot.snapshotId())));
    Assertions.assertTrue(withoutStats.statisticsFiles().isEmpty());
  }

  @Test
  public void testSetAndRemovePartitionStatistics() {
    Snapshot snapshot = CommitTestFixtures.snapshot(1L, 1L);
    PartitionStatisticsFile partitionStats =
        ImmutableGenericPartitionStatisticsFile.builder()
            .snapshotId(snapshot.snapshotId())
            .path(CommitTestFixtures.LOCATION + "/metadata/partition-stats.parquet")
            .fileSizeInBytes(64L)
            .build();

    TableMetadata withStats =
        MetadataUpdateApplier.apply(
            base,
            Arrays.asList(
                new MetadataUpdate.AddSnapshot(snapshot),
                new MetadataUpdate.SetPartitionStatistics(partitionStats)));
    Assertions.assertEquals(1, withStats.partitionStatisticsFiles().size());

    TableMetadata withoutStats =
        MetadataUpdateApplier.apply(
            withStats,
            Collections.singletonList(
                new MetadataUpdate.RemovePartitionStatistics(snapshot.snapshotId())));
    Assertions.assertTrue(withoutStats.partitionStatisticsFiles().isEmpty());
  }

  // ---------------------------------------------------------------------------------------------
  // Create path
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testCreateTableFromNothing() {
    String uuid = UUID.randomUUID().toString();
    List<MetadataUpdate> createUpdates =
        Arrays.asList(
            new MetadataUpdate.AssignUUID(uuid),
            new MetadataUpdate.UpgradeFormatVersion(2),
            new MetadataUpdate.AddSchema(CommitTestFixtures.SCHEMA, 2),
            new MetadataUpdate.SetCurrentSchema(-1),
            new MetadataUpdate.AddPartitionSpec(CommitTestFixtures.spec()),
            new MetadataUpdate.SetDefaultPartitionSpec(-1),
            new MetadataUpdate.AddSortOrder(CommitTestFixtures.sortOrder()),
            new MetadataUpdate.SetDefaultSortOrder(-1),
            new MetadataUpdate.SetLocation(CommitTestFixtures.LOCATION),
            new MetadataUpdate.SetProperties(Collections.singletonMap("owner", "openhouse")));

    TableMetadata created =
        MetadataUpdateApplier.validateAndApply(
            null,
            Collections.singletonList(new UpdateRequirement.AssertTableDoesNotExist()),
            createUpdates);

    Assertions.assertEquals(uuid, created.uuid());
    Assertions.assertEquals(2, created.formatVersion());
    Assertions.assertEquals(CommitTestFixtures.LOCATION, created.location());
    Assertions.assertEquals(2, created.lastColumnId());
    Assertions.assertNotNull(created.schema().findField("data"));
    Assertions.assertEquals(1, created.spec().fields().size());
    Assertions.assertEquals(1, created.sortOrder().fields().size());
    Assertions.assertEquals("openhouse", created.properties().get("owner"));
    Assertions.assertTrue(created.snapshots().isEmpty());
  }

  @Test
  public void testCreateWithoutAnyUpdatesFailsLoudly() {
    // Iceberg's builder returns the metadata it started from when nothing changed, which on the
    // create path is null. Handing that back would look like a successful commit with no table.
    ValidationException e =
        Assertions.assertThrows(
            ValidationException.class,
            () -> MetadataUpdateApplier.apply(null, Collections.emptyList()));
    Assertions.assertTrue(e.getMessage().contains("no updates were supplied"), e.getMessage());
  }

  @Test
  public void testAppendCommitAgainstAnExistingTable() {
    // The shape a real append arrives in: assert the table and the branch head we read, then add a
    // snapshot and move the branch. Exercised end to end because that is the sequence the wiring
    // slice will hand this engine.
    Snapshot first = CommitTestFixtures.snapshot(1L, 1L);
    TableMetadata afterFirst =
        MetadataUpdateApplier.validateAndApply(
            base,
            Arrays.asList(
                new UpdateRequirement.AssertTableUUID(base.uuid()),
                new UpdateRequirement.AssertRefSnapshotID(SnapshotRef.MAIN_BRANCH, null)),
            Arrays.asList(
                new MetadataUpdate.AddSnapshot(first),
                setSnapshotRef(SnapshotRef.MAIN_BRANCH, first.snapshotId(), "branch")));
    Assertions.assertEquals(first.snapshotId(), afterFirst.currentSnapshot().snapshotId());

    Snapshot second = CommitTestFixtures.snapshot(2L, 2L);
    TableMetadata afterSecond =
        MetadataUpdateApplier.validateAndApply(
            afterFirst,
            Arrays.asList(
                new UpdateRequirement.AssertTableUUID(afterFirst.uuid()),
                new UpdateRequirement.AssertRefSnapshotID(
                    SnapshotRef.MAIN_BRANCH, first.snapshotId())),
            Arrays.asList(
                new MetadataUpdate.AddSnapshot(second),
                setSnapshotRef(SnapshotRef.MAIN_BRANCH, second.snapshotId(), "branch")));
    Assertions.assertEquals(second.snapshotId(), afterSecond.currentSnapshot().snapshotId());
    Assertions.assertEquals(2, afterSecond.snapshots().size());

    // A second writer that still believes the branch points at the first snapshot loses.
    Snapshot stale = CommitTestFixtures.snapshot(3L, 3L);
    Assertions.assertThrows(
        CommitFailedException.class,
        () ->
            MetadataUpdateApplier.validateAndApply(
                afterSecond,
                Arrays.asList(
                    new UpdateRequirement.AssertTableUUID(afterSecond.uuid()),
                    new UpdateRequirement.AssertRefSnapshotID(
                        SnapshotRef.MAIN_BRANCH, first.snapshotId())),
                Arrays.asList(
                    new MetadataUpdate.AddSnapshot(stale),
                    setSnapshotRef(SnapshotRef.MAIN_BRANCH, stale.snapshotId(), "branch"))));
  }

  // ---------------------------------------------------------------------------------------------
  // Failure modes
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testFailedRequirementLeavesMetadataUntouched() {
    TableMetadata before = base;
    String beforeLocation = before.location();
    int beforeSchemaId = before.currentSchemaId();

    Assertions.assertThrows(
        CommitFailedException.class,
        () ->
            MetadataUpdateApplier.validateAndApply(
                before,
                Collections.singletonList(
                    new UpdateRequirement.AssertTableUUID("00000000-0000-0000-0000-000000000000")),
                Arrays.asList(
                    new MetadataUpdate.SetLocation("/tmp/openhouse/db/should-not-happen"),
                    new MetadataUpdate.SetProperties(
                        Collections.singletonMap("should", "not-happen")))));

    Assertions.assertSame(base, before);
    Assertions.assertEquals(beforeLocation, before.location());
    Assertions.assertEquals(beforeSchemaId, before.currentSchemaId());
    Assertions.assertFalse(before.properties().containsKey("should"));
  }

  @Test
  public void testApplyDoesNotMutateBase() {
    TableMetadata result =
        apply(new MetadataUpdate.SetProperties(Collections.singletonMap("owner", "openhouse")));
    Assertions.assertNotSame(base, result);
    Assertions.assertFalse(base.properties().containsKey("owner"));
    Assertions.assertEquals("openhouse", result.properties().get("owner"));
  }

  @Test
  public void testViewOnlyUpdatesAreRejected() {
    for (MetadataUpdate update :
        Arrays.asList(
            new MetadataUpdate.AddViewVersion(null), new MetadataUpdate.SetCurrentViewVersion(0))) {
      ValidationException e =
          Assertions.assertThrows(
              ValidationException.class,
              () -> MetadataUpdateApplier.apply(base, Collections.singletonList(update)));
      Assertions.assertTrue(e.getMessage().contains("to a table"), e.getMessage());
    }
  }

  @Test
  public void testUnknownUpdateTypeIsNotSilentlyIgnored() {
    // Stands in for an update type a future Iceberg release could introduce: it must abort the
    // commit rather than let the caller believe a change it never applied has landed.
    MetadataUpdate unknown = new UnknownMetadataUpdate();

    ValidationException e =
        Assertions.assertThrows(
            ValidationException.class,
            () -> MetadataUpdateApplier.apply(base, Collections.singletonList(unknown)));
    Assertions.assertTrue(
        e.getMessage().contains(UnknownMetadataUpdate.class.getName()), e.getMessage());

    // ...including when it trails an update that would otherwise have succeeded, in which case
    // nothing from the batch is published.
    Assertions.assertThrows(
        ValidationException.class,
        () ->
            MetadataUpdateApplier.apply(
                base,
                Arrays.asList(new MetadataUpdate.SetLocation("/tmp/openhouse/db/moved"), unknown)));
    Assertions.assertEquals(CommitTestFixtures.LOCATION, base.location());
  }

  /**
   * SnapshotRefType is package-private in Iceberg, so build this update the way a REST request
   * would deliver it: through the wire format.
   */
  private static MetadataUpdate setSnapshotRef(String refName, long snapshotId, String type) {
    return MetadataUpdateParser.fromJson(
        String.format(
            "{\"action\":\"set-snapshot-ref\",\"ref-name\":\"%s\","
                + "\"snapshot-id\":%d,\"type\":\"%s\"}",
            refName, snapshotId, type));
  }

  private TableMetadata apply(MetadataUpdate update) {
    return MetadataUpdateApplier.apply(base, Collections.singletonList(update));
  }

  /** A MetadataUpdate this Iceberg version has no table-side handling for. */
  private static final class UnknownMetadataUpdate implements MetadataUpdate {
    private static final long serialVersionUID = 1L;
  }
}
