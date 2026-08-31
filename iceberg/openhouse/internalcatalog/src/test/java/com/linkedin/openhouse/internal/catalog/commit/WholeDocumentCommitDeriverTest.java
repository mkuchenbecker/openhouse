package com.linkedin.openhouse.internal.catalog.commit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotRef;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.SortOrderParser;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.ValidationException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Pins the translation from OpenHouse's whole-document commit to an Iceberg change list.
 *
 * <p>Two kinds of test live here and they answer different questions. The <b>shape</b> tests assert
 * which {@link MetadataUpdate}s a given document derives, so that a future edit cannot quietly
 * change what the commit means. The <b>equivalence</b> tests re-implement the exact {@code
 * TableMetadata} calls the old {@code doCommit} made -- see {@link #legacyApply} -- and assert the
 * derived-and-applied result matches them field for field. The second kind is the one that would
 * catch a faithful-looking derivation that is subtly wrong, which is the risk this slice exists to
 * retire.
 *
 * <p>The equivalence assertions deliberately do not compare serialised metadata: {@code
 * last-updated-ms} is wall-clock and would make the comparison flaky without proving anything.
 * Every field a commit can actually change is compared instead.
 */
public class WholeDocumentCommitDeriverTest {

  private static Map<String, String> propsOf(TableMetadata metadata, String... overrides) {
    Map<String, String> properties = new LinkedHashMap<>(metadata.properties());
    for (int i = 0; i < overrides.length; i += 2) {
      properties.put(overrides[i], overrides[i + 1]);
    }
    return properties;
  }

  /**
   * The commit path as it stood before the engine: {@code replaceProperties}, then {@code
   * replaceSortOrder}, then a hand-rolled snapshot diff on a {@code TableMetadata.Builder}. Kept
   * verbatim so the equivalence tests below compare against what actually shipped.
   */
  private static TableMetadata legacyApply(
      TableMetadata document,
      Map<String, String> properties,
      String sortOrderJson,
      List<Snapshot> snapshotsToPut,
      Map<String, SnapshotRef> snapshotRefs) {
    TableMetadata metadataToCommit = document.replaceProperties(properties);

    if (sortOrderJson != null) {
      SortOrder sortOrder = SortOrderParser.fromJson(metadataToCommit.schema(), sortOrderJson);
      metadataToCommit = metadataToCommit.replaceSortOrder(sortOrder);
    }

    if (snapshotsToPut != null) {
      TableMetadata.Builder builder = TableMetadata.buildFrom(metadataToCommit);
      Set<Long> existingSnapshotIds =
          metadataToCommit.snapshots().stream()
              .map(Snapshot::snapshotId)
              .collect(Collectors.toSet());
      Set<Long> newSnapshotIds =
          snapshotsToPut.stream().map(Snapshot::snapshotId).collect(Collectors.toSet());

      snapshotsToPut.stream()
          .filter(s -> !existingSnapshotIds.contains(s.snapshotId()))
          .forEach(builder::addSnapshot);

      List<Long> toRemove =
          existingSnapshotIds.stream()
              .filter(id -> !newSnapshotIds.contains(id))
              .collect(Collectors.toList());
      if (!toRemove.isEmpty()) {
        builder.removeSnapshots(toRemove);
      }

      metadataToCommit.refs().keySet().stream()
          .filter(ref -> !snapshotRefs.containsKey(ref))
          .forEach(builder::removeRef);
      snapshotRefs.forEach(builder::setRef);

      metadataToCommit = builder.build();
    }
    return metadataToCommit;
  }

  private static TableMetadata derivedApply(
      TableMetadata document,
      Map<String, String> properties,
      String sortOrderJson,
      List<Snapshot> snapshotsToPut,
      Map<String, SnapshotRef> snapshotRefs) {
    WholeDocumentCommitDeriver.DerivedCommit derived =
        WholeDocumentCommitDeriver.derive(
            document, document, properties, sortOrderJson, snapshotsToPut, snapshotRefs);
    return MetadataUpdateApplier.apply(document, derived.updates());
  }

  /** Compares every part of a {@link TableMetadata} a whole-document commit is able to change. */
  private static void assertSameCommitResult(TableMetadata expected, TableMetadata actual) {
    assertThat(actual.properties()).isEqualTo(expected.properties());
    assertThat(actual.formatVersion()).isEqualTo(expected.formatVersion());
    assertThat(actual.location()).isEqualTo(expected.location());
    assertThat(actual.uuid()).isEqualTo(expected.uuid());
    assertThat(actual.schema().sameSchema(expected.schema())).isTrue();
    assertThat(actual.currentSchemaId()).isEqualTo(expected.currentSchemaId());
    assertThat(actual.lastColumnId()).isEqualTo(expected.lastColumnId());
    assertThat(actual.defaultSpecId()).isEqualTo(expected.defaultSpecId());
    assertThat(actual.defaultSortOrderId()).isEqualTo(expected.defaultSortOrderId());
    assertThat(actual.sortOrder().sameOrder(expected.sortOrder())).isTrue();
    assertThat(actual.sortOrders().stream().map(SortOrder::orderId).collect(Collectors.toList()))
        .isEqualTo(
            expected.sortOrders().stream().map(SortOrder::orderId).collect(Collectors.toList()));
    assertThat(actual.snapshots().stream().map(Snapshot::snapshotId).collect(Collectors.toSet()))
        .isEqualTo(
            expected.snapshots().stream().map(Snapshot::snapshotId).collect(Collectors.toSet()));
    assertThat(currentSnapshotIdOf(actual)).isEqualTo(currentSnapshotIdOf(expected));
    assertThat(actual.refs()).isEqualTo(expected.refs());
  }

  private static Long currentSnapshotIdOf(TableMetadata metadata) {
    return metadata.currentSnapshot() == null ? null : metadata.currentSnapshot().snapshotId();
  }

  // ---------------------------------------------------------------- shape

  @Test
  public void testPropertyOnlyCommitDerivesSetAndRemove() {
    TableMetadata document =
        TableMetadata.buildFrom(CommitTestFixtures.baseMetadata())
            .setProperties(mapOf("keep", "same", "change", "old", "drop", "gone"))
            .build();

    // The whole document, with one property changed, one added and one dropped. Built from the
    // document's own properties so the test asserts the intended diff rather than also dropping
    // whatever defaults Iceberg seeded the table with.
    Map<String, String> intended = propsOf(document, "change", "new", "added", "value");
    intended.remove("drop");

    List<MetadataUpdate> updates =
        WholeDocumentCommitDeriver.derive(document, document, intended, null, null, null).updates();

    assertThat(updates).hasSize(2);
    assertThat(updates.get(0)).isInstanceOf(MetadataUpdate.SetProperties.class);
    // "keep" is unchanged, so it is not re-set: a whole-document PUT that changes nothing derives
    // nothing.
    assertThat(((MetadataUpdate.SetProperties) updates.get(0)).updated())
        .containsExactlyInAnyOrderEntriesOf(mapOf("change", "new", "added", "value"));
    assertThat(updates.get(1)).isInstanceOf(MetadataUpdate.RemoveProperties.class);
    assertThat(((MetadataUpdate.RemoveProperties) updates.get(1)).removed())
        .containsExactly("drop");
  }

  @Test
  public void testUnchangedDocumentDerivesNoUpdates() {
    TableMetadata document = CommitTestFixtures.baseMetadata();
    assertThat(
            WholeDocumentCommitDeriver.derive(
                    document, document, document.properties(), null, null, null)
                .updates())
        .isEmpty();
  }

  @Test
  public void testReservedPropertyIsNotSetButStillDrivesFormatVersion() {
    TableMetadata document = metadataAtFormatVersion(1);
    Map<String, String> intended = propsOf(document, "format-version", "2");

    List<MetadataUpdate> updates =
        WholeDocumentCommitDeriver.derive(document, document, intended, null, null, null).updates();

    // format-version is reserved, so it is never written as a table property...
    assertThat(updates)
        .noneMatch(
            u ->
                u instanceof MetadataUpdate.SetProperties
                    && ((MetadataUpdate.SetProperties) u).updated().containsKey("format-version"));
    // ...it is read out of the raw map and becomes a format upgrade instead.
    assertThat(updates)
        .anyMatch(
            u ->
                u instanceof MetadataUpdate.UpgradeFormatVersion
                    && ((MetadataUpdate.UpgradeFormatVersion) u).formatVersion() == 2);
  }

  @Test
  public void testSortOrderChangeDerivesAddAndSetDefault() {
    TableMetadata document = CommitTestFixtures.baseMetadata();
    SortOrder newOrder = SortOrder.builderFor(document.schema()).desc("data").build();

    List<MetadataUpdate> updates =
        WholeDocumentCommitDeriver.derive(
                document,
                document,
                document.properties(),
                SortOrderParser.toJson(newOrder),
                null,
                null)
            .updates();

    assertThat(updates).hasSize(2);
    assertThat(updates.get(0)).isInstanceOf(MetadataUpdate.AddSortOrder.class);
    assertThat(updates.get(1)).isInstanceOf(MetadataUpdate.SetDefaultSortOrder.class);
    // -1 is Iceberg's "the order just added" sentinel; pinning it keeps the pair meaningful.
    assertThat(((MetadataUpdate.SetDefaultSortOrder) updates.get(1)).sortOrderId()).isEqualTo(-1);
  }

  @Test
  public void testReselectingAnExistingSortOrderDerivesOnlySetDefault() {
    TableMetadata document = CommitTestFixtures.baseMetadata();
    SortOrder existing = document.sortOrder();

    List<MetadataUpdate> updates =
        WholeDocumentCommitDeriver.derive(
                document,
                document,
                document.properties(),
                SortOrderParser.toJson(existing),
                null,
                null)
            .updates();

    // Adding an order the table already has adds nothing, which would leave the -1 sentinel with
    // nothing to resolve to. The existing id is named directly instead.
    assertThat(updates).hasSize(1);
    assertThat(((MetadataUpdate.SetDefaultSortOrder) updates.get(0)).sortOrderId())
        .isEqualTo(existing.orderId());
  }

  @Test
  public void testSnapshotSectionDerivesAddRemoveAndRefSync() {
    Snapshot kept = CommitTestFixtures.snapshot(1L, 1L);
    Snapshot expired = CommitTestFixtures.snapshot(2L, 2L);
    Snapshot fresh = CommitTestFixtures.snapshot(3L, 3L);

    TableMetadata document =
        TableMetadata.buildFrom(CommitTestFixtures.baseMetadata())
            .addSnapshot(kept)
            .addSnapshot(expired)
            .setRef("stale", SnapshotRef.branchBuilder(expired.snapshotId()).build())
            .build();

    Map<String, SnapshotRef> refs =
        mapOfRefs("main", SnapshotRef.branchBuilder(fresh.snapshotId()).build());

    List<MetadataUpdate> updates =
        WholeDocumentCommitDeriver.derive(
                document, document, document.properties(), null, Arrays.asList(kept, fresh), refs)
            .updates();

    assertThat(updates.stream().map(u -> u.getClass().getSimpleName()).collect(Collectors.toList()))
        .containsExactly("AddSnapshot", "RemoveSnapshot", "RemoveSnapshotRef", "SetSnapshotRef");
    assertThat(((MetadataUpdate.AddSnapshot) updates.get(0)).snapshot().snapshotId()).isEqualTo(3L);
    assertThat(((MetadataUpdate.RemoveSnapshot) updates.get(1)).snapshotId()).isEqualTo(2L);
    assertThat(((MetadataUpdate.RemoveSnapshotRef) updates.get(2)).name()).isEqualTo("stale");
    assertThat(((MetadataUpdate.SetSnapshotRef) updates.get(3)).name()).isEqualTo("main");
  }

  @Test
  public void testAbsentSnapshotSectionIsNotAnEmptyOne() {
    Snapshot existing = CommitTestFixtures.snapshot(1L, 1L);
    TableMetadata document =
        TableMetadata.buildFrom(CommitTestFixtures.baseMetadata()).addSnapshot(existing).build();

    // No snapshot section at all: the commit is not about snapshots and must not touch them.
    assertThat(
            WholeDocumentCommitDeriver.derive(
                    document, document, document.properties(), null, null, null)
                .updates())
        .isEmpty();

    // An empty section is a client asking for a table with no snapshots, which expires the one it
    // has. Collapsing these two cases would silently drop data.
    assertThat(
            WholeDocumentCommitDeriver.derive(
                    document,
                    document,
                    document.properties(),
                    null,
                    Collections.emptyList(),
                    new HashMap<>())
                .updates())
        .anyMatch(u -> u instanceof MetadataUpdate.RemoveSnapshot);
  }

  // ---------------------------------------------------------- requirements

  @Test
  public void testCreateRequiresTableDoesNotExist() {
    TableMetadata document = CommitTestFixtures.baseMetadata();

    List<UpdateRequirement> requirements =
        WholeDocumentCommitDeriver.derive(null, document, document.properties(), null, null, null)
            .requirements();

    assertThat(requirements).hasSize(1);
    assertThat(requirements.get(0)).isInstanceOf(UpdateRequirement.AssertTableDoesNotExist.class);
    // The create precondition holds against an absent table and fails against a present one.
    UpdateRequirementValidator.validate(null, requirements);
    assertThatThrownBy(() -> UpdateRequirementValidator.validate(document, requirements))
        .isInstanceOf(CommitFailedException.class);
  }

  @Test
  public void testUpdateAssertsTheCatalogTableIdentity() {
    TableMetadata base = CommitTestFixtures.baseMetadata();
    TableMetadata document = TableMetadata.buildFrom(base).build();

    List<UpdateRequirement> requirements =
        WholeDocumentCommitDeriver.derive(base, document, document.properties(), null, null, null)
            .requirements();

    assertThat(requirements).hasSize(1);
    assertThat(((UpdateRequirement.AssertTableUUID) requirements.get(0)).uuid())
        .isEqualTo(base.uuid());
    UpdateRequirementValidator.validate(base, requirements);
  }

  @Test
  public void testDerivedIdentityRequirementDiscriminates() {
    TableMetadata base = CommitTestFixtures.baseMetadata();
    List<UpdateRequirement> requirements =
        WholeDocumentCommitDeriver.derive(base, base, base.properties(), null, null, null)
            .requirements();

    // The requirement is derived from the base it is checked against, so the wiring in doCommit
    // cannot make it fail. It is still a real precondition, not a formality: pointed at a
    // different table it rejects the commit.
    TableMetadata otherTable = CommitTestFixtures.baseMetadata();
    assertThat(otherTable.uuid()).isNotEqualTo(base.uuid());
    assertThatThrownBy(() -> UpdateRequirementValidator.validate(otherTable, requirements))
        .isInstanceOf(CommitFailedException.class);
  }

  @Test
  public void testBaseWithoutUuidDerivesNoRequirement() {
    // v1 metadata written before Iceberg assigned table uuids parses back with a null uuid, and
    // has no identity to assert. Deriving AssertTableUUID(null) would fail a commit that succeeds
    // today, so nothing is derived at all. Iceberg's builder always stamps a uuid, so the only way
    // to hold such metadata is to stub it -- reality cannot produce one on demand.
    TableMetadata document = CommitTestFixtures.baseMetadata();
    TableMetadata uuidlessBase = Mockito.mock(TableMetadata.class);
    Mockito.when(uuidlessBase.uuid()).thenReturn(null);

    assertThat(
            WholeDocumentCommitDeriver.derive(
                    uuidlessBase, document, document.properties(), null, null, null)
                .requirements())
        .isEmpty();
  }

  // ------------------------------------------------------------- admission

  @Test
  public void testAdmissionRejectsPartitionSpecEvolution() {
    // OpenHouse forbids partition evolution. Against an update list the rule is simply that no
    // spec update may be derived; no derivation produces one today, so this asserts the invariant
    // rather than a reachable path.
    assertThatThrownBy(
            () ->
                WholeDocumentCommitDeriver.admit(
                    Collections.singletonList(
                        new MetadataUpdate.AddPartitionSpec(CommitTestFixtures.spec()))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Cannot evolve table partitioning");

    assertThatThrownBy(
            () ->
                WholeDocumentCommitDeriver.admit(
                    Collections.singletonList(new MetadataUpdate.SetDefaultPartitionSpec(1))))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  public void testAdmissionReturnsTheUpdatesItAdmits() {
    // Admission returns a list rather than void because OpenHouse rewrites commits on the way in
    // (schema case-normalisation), not merely accepts or rejects them.
    List<MetadataUpdate> updates =
        Collections.singletonList(new MetadataUpdate.SetProperties(mapOf("a", "b")));
    assertThat(WholeDocumentCommitDeriver.admit(updates)).isEqualTo(updates);
  }

  // ----------------------------------------------------------- equivalence

  @Test
  public void testEquivalentToLegacyForPropertyChanges() {
    TableMetadata document =
        TableMetadata.buildFrom(CommitTestFixtures.baseMetadata())
            .setProperties(mapOf("keep", "same", "change", "old", "drop", "gone"))
            .build();
    Map<String, String> intended = mapOf("keep", "same", "change", "new", "added", "value");

    assertSameCommitResult(
        legacyApply(document, intended, null, null, null),
        derivedApply(document, intended, null, null, null));
  }

  @Test
  public void testEquivalentToLegacyForSortOrderChange() {
    TableMetadata document = CommitTestFixtures.baseMetadata();
    String sortOrderJson =
        SortOrderParser.toJson(SortOrder.builderFor(document.schema()).desc("data").build());

    assertSameCommitResult(
        legacyApply(document, document.properties(), sortOrderJson, null, null),
        derivedApply(document, document.properties(), sortOrderJson, null, null));
  }

  @Test
  public void testEquivalentToLegacyForUnsortedSortOrder() {
    TableMetadata document = CommitTestFixtures.baseMetadata();
    String sortOrderJson = SortOrderParser.toJson(SortOrder.unsorted());

    assertSameCommitResult(
        legacyApply(document, document.properties(), sortOrderJson, null, null),
        derivedApply(document, document.properties(), sortOrderJson, null, null));
  }

  @Test
  public void testEquivalentToLegacyForSnapshotSync() {
    Snapshot kept = CommitTestFixtures.snapshot(1L, 1L);
    Snapshot expired = CommitTestFixtures.snapshot(2L, 2L);
    Snapshot fresh = CommitTestFixtures.snapshot(3L, 3L);

    TableMetadata document =
        TableMetadata.buildFrom(CommitTestFixtures.baseMetadata())
            .addSnapshot(kept)
            .addSnapshot(expired)
            .setRef("main", SnapshotRef.branchBuilder(expired.snapshotId()).build())
            .setRef("stale", SnapshotRef.branchBuilder(kept.snapshotId()).build())
            .build();

    List<Snapshot> snapshots = new ArrayList<>(Arrays.asList(kept, fresh));
    Map<String, SnapshotRef> refs =
        mapOfRefs("main", SnapshotRef.branchBuilder(fresh.snapshotId()).build());

    assertSameCommitResult(
        legacyApply(document, document.properties(), null, snapshots, refs),
        derivedApply(document, document.properties(), null, snapshots, refs));
  }

  @Test
  public void testEquivalentToLegacyWhenEverythingChangesAtOnce() {
    Snapshot kept = CommitTestFixtures.snapshot(1L, 1L);
    Snapshot fresh = CommitTestFixtures.snapshot(2L, 2L);
    TableMetadata document =
        TableMetadata.buildFrom(CommitTestFixtures.baseMetadata())
            .addSnapshot(kept)
            .setProperties(mapOf("drop", "gone", "change", "old"))
            .build();

    Map<String, String> intended = mapOf("change", "new", "added", "value");
    String sortOrderJson =
        SortOrderParser.toJson(SortOrder.builderFor(document.schema()).desc("data").build());
    List<Snapshot> snapshots = Arrays.asList(kept, fresh);
    Map<String, SnapshotRef> refs =
        mapOfRefs("main", SnapshotRef.branchBuilder(fresh.snapshotId()).build());

    assertSameCommitResult(
        legacyApply(document, intended, sortOrderJson, snapshots, refs),
        derivedApply(document, intended, sortOrderJson, snapshots, refs));
  }

  // --------------------------------------------------- rejections preserved

  @Test
  public void testFormatVersionDowngradeIsStillRejected() {
    TableMetadata document = metadataAtFormatVersion(2);
    Map<String, String> intended = propsOf(document, "format-version", "1");

    // Same failure, from the same Iceberg check, as document.replaceProperties(intended).
    assertThatThrownBy(() -> legacyApply(document, intended, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Cannot downgrade");
    assertThatThrownBy(() -> derivedApply(document, intended, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Cannot downgrade");
  }

  @Test
  public void testNullPropertyMapIsStillRejected() {
    TableMetadata document = CommitTestFixtures.baseMetadata();

    assertThatThrownBy(() -> legacyApply(document, null, null, null, null))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Cannot set properties to null");
    assertThatThrownBy(
            () -> WholeDocumentCommitDeriver.derive(document, document, null, null, null, null))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Cannot set properties to null");
  }

  @Test
  public void testNullDocumentIsRejected() {
    assertThatThrownBy(
            () ->
                WholeDocumentCommitDeriver.derive(
                    null, null, Collections.emptyMap(), null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * A document pinned to an explicit format version, so upgrade/downgrade tests do not depend on
   * whatever Iceberg's default happens to be.
   */
  private static TableMetadata metadataAtFormatVersion(int formatVersion) {
    return TableMetadata.newTableMetadata(
        CommitTestFixtures.SCHEMA,
        CommitTestFixtures.spec(),
        CommitTestFixtures.sortOrder(),
        CommitTestFixtures.LOCATION,
        mapOf("format-version", Integer.toString(formatVersion)));
  }

  private static Map<String, String> mapOf(String... kvs) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i < kvs.length; i += 2) {
      map.put(kvs[i], kvs[i + 1]);
    }
    return map;
  }

  private static Map<String, SnapshotRef> mapOfRefs(String name, SnapshotRef ref) {
    Map<String, SnapshotRef> map = new LinkedHashMap<>();
    map.put(name, ref);
    return map;
  }
}
