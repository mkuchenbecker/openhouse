package com.linkedin.openhouse.internal.catalog.commit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.util.PropertyUtil;

/**
 * Translates OpenHouse's whole-document commit into the change-list shape the Iceberg REST
 * specification uses, so that {@link MetadataUpdateApplier} and {@link UpdateRequirementValidator}
 * -- not hand-written {@link TableMetadata} surgery -- produce the metadata OpenHouse writes.
 *
 * <p><b>What "the document" means here.</b> OpenHouse's v1 API is a PUT of the client's whole
 * intended table state. By the time a commit reaches {@code OpenHouseInternalTableOperations}, that
 * intent has been folded into a single {@link TableMetadata} (the {@code document}) plus three
 * side-channels carried as reserved table properties: the final property map, a serialised sort
 * order, and a serialised snapshot list with its refs. The old code applied each side-channel with
 * a bespoke {@code TableMetadata} call ({@code replaceProperties}, {@code replaceSortOrder}, a
 * hand-rolled snapshot diff). This class derives the equivalent {@link MetadataUpdate}s instead,
 * and the applier does the rest.
 *
 * <p><b>Why derive rather than rewrite?</b> The engine has to be exercised by real traffic before a
 * REST route depends on it. Deriving under the existing API means OpenHouse's own {@code
 * services:tables} suite -- which already covers every one of these cases end to end -- is the
 * regression test for the translation. A derivation that is wrong fails there, today, instead of
 * failing later behind an HTTP surface whose tests would have to be written from scratch to notice.
 *
 * <p><b>Fidelity is the whole point.</b> Every rule below is a deliberate restatement of what
 * Iceberg's own {@code TableMetadata} helpers do, not an improvement on them; the "mirrors" notes
 * on each method say which helper is being matched. Where a rule looks over-careful -- reserved
 * property filtering, the format-version read from the raw map, the sort-order reuse branch -- it
 * is because Iceberg's helper does exactly that and a simpler derivation would change behaviour.
 */
public final class WholeDocumentCommitDeriver {

  /** The preconditions and changes derived from one whole-document commit. */
  public static final class DerivedCommit {
    private final List<UpdateRequirement> requirements;
    private final List<MetadataUpdate> updates;

    DerivedCommit(List<UpdateRequirement> requirements, List<MetadataUpdate> updates) {
      this.requirements = Collections.unmodifiableList(requirements);
      this.updates = Collections.unmodifiableList(updates);
    }

    /**
     * Preconditions to check against the <em>catalog's</em> current metadata (the {@code base}),
     * never against the document.
     */
    public List<UpdateRequirement> requirements() {
      return requirements;
    }

    /** Changes to apply to the document, in order. */
    public List<MetadataUpdate> updates() {
      return updates;
    }
  }

  private WholeDocumentCommitDeriver() {}

  /**
   * Derives the preconditions and changes equivalent to the whole-document commit described by
   * {@code document} and its side-channels.
   *
   * @param base the catalog's current metadata, or {@code null} when the table does not yet exist
   * @param document the client's intended table state, as assembled by the commit path
   * @param finalProperties the property map the commit intends the table to end up with
   * @param sortOrderJson the serialised replacement sort order, or {@code null} for no change
   * @param snapshotsToPut the client's full snapshot list, or {@code null} when the commit carries
   *     no snapshot section at all, which is not the same as an empty list
   * @param snapshotRefs the client's refs, keyed by name; ignored when {@code snapshotsToPut} is
   *     {@code null}
   */
  public static DerivedCommit derive(
      TableMetadata base,
      TableMetadata document,
      Map<String, String> finalProperties,
      String sortOrderJson,
      List<Snapshot> snapshotsToPut,
      Map<String, SnapshotRef> snapshotRefs) {
    if (document == null) {
      throw new IllegalArgumentException("Invalid table metadata document: null");
    }

    List<MetadataUpdate> updates = new ArrayList<>();
    updates.addAll(derivePropertyUpdates(document, finalProperties));
    updates.addAll(deriveSortOrderUpdates(document, sortOrderJson));
    updates.addAll(deriveSnapshotUpdates(document, snapshotsToPut, snapshotRefs));

    return new DerivedCommit(deriveRequirements(base), admit(updates));
  }

  /**
   * Screens a derived update list and returns the list to apply.
   *
   * <p><b>This returns the updates rather than {@code void} on purpose.</b> OpenHouse does not
   * merely accept or reject a commit: it rewrites parts of it on the way in -- most visibly {@code
   * BaseIcebergSchemaValidator}, which normalises a writer's column casing to the casing the table
   * already uses so that a writer sending {@code id} for a column named {@code ID} does not change
   * the table's casing. Admission that returned {@code void} could not express that, and the
   * rewritten result would have to travel in a side-channel. The rewrite is upstream of here today;
   * the signature is what lets it move down without changing every caller.
   *
   * <p>The rule enforced here is partition-spec immutability, which OpenHouse enforces upstream in
   * {@code OpenHouseInternalRepositoryImpl.checkPartitionSpecEvolution} by comparing partition
   * column names. Expressed against an update list it is simply: a whole-document commit derives no
   * partition-spec update. That holds by construction for every derivation below, so this check
   * cannot fire today -- it is here so that a future derivation rule cannot quietly introduce spec
   * evolution through this path without the assertion, and its test, objecting.
   */
  static List<MetadataUpdate> admit(List<MetadataUpdate> updates) {
    for (MetadataUpdate update : updates) {
      if (update instanceof MetadataUpdate.AddPartitionSpec
          || update instanceof MetadataUpdate.SetDefaultPartitionSpec) {
        throw new ValidationException(
            "Cannot evolve table partitioning through a whole-document commit: derived %s",
            update.getClass().getSimpleName());
      }
    }
    return updates;
  }

  /**
   * Derives the preconditions the whole-document path relies on.
   *
   * <p>Deliberately narrow. The precondition that actually rejects concurrent writers here --
   * comparing the writer's declared base metadata location against the catalog's -- is
   * conflict-detection, is not shaped like any Iceberg {@link UpdateRequirement}, and is left
   * exactly where it is. What remains is the table-identity precondition the path already assumes:
   * a commit with no base is creating a table that must not exist, and a commit with a base is
   * changing the table that base identifies.
   */
  private static List<UpdateRequirement> deriveRequirements(TableMetadata base) {
    if (base == null) {
      return Collections.singletonList(new UpdateRequirement.AssertTableDoesNotExist());
    }
    if (base.uuid() == null) {
      // Metadata old enough to predate table UUIDs, or constructed without one. There is no
      // identity to assert, and asserting a null uuid would fail a commit that succeeds today.
      return Collections.emptyList();
    }
    return Collections.singletonList(new UpdateRequirement.AssertTableUUID(base.uuid()));
  }

  /**
   * Mirrors {@link TableMetadata#replaceProperties(Map)}: the document's property map is replaced
   * wholesale by {@code finalProperties}, which means keys absent from {@code finalProperties} are
   * removed and not merely left alone.
   *
   * <p>Three details are Iceberg's, not ours, and all three matter. Reserved property names are
   * filtered out of the incoming map, so they can neither be set nor protect themselves from
   * removal. {@code format-version} is read from the <em>unfiltered</em> map even though it is
   * reserved, because that is how a format upgrade is requested. And a key is only re-set when its
   * value actually differs, so a PUT of an unchanged document derives no update at all rather than
   * a {@code SetProperties} that would rewrite every key to the value it already has.
   */
  private static List<MetadataUpdate> derivePropertyUpdates(
      TableMetadata document, Map<String, String> finalProperties) {
    // Matches TableMetadata.replaceProperties, which rejects a null map the same way.
    ValidationException.check(finalProperties != null, "Cannot set properties to null");

    Map<String, String> unreserved =
        finalProperties.entrySet().stream()
            .filter(entry -> !TableProperties.RESERVED_PROPERTIES.contains(entry.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    Set<String> removed = new HashSet<>(document.properties().keySet());
    Map<String, String> updated = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : unreserved.entrySet()) {
      removed.remove(entry.getKey());
      String current = document.properties().get(entry.getKey());
      if (current == null || !current.equals(entry.getValue())) {
        updated.put(entry.getKey(), entry.getValue());
      }
    }

    int formatVersion =
        PropertyUtil.propertyAsInt(
            finalProperties, TableProperties.FORMAT_VERSION, document.formatVersion());

    List<MetadataUpdate> updates = new ArrayList<>();
    if (!updated.isEmpty()) {
      updates.add(new MetadataUpdate.SetProperties(updated));
    }
    if (!removed.isEmpty()) {
      updates.add(new MetadataUpdate.RemoveProperties(removed));
    }
    if (formatVersion != document.formatVersion()) {
      updates.add(new MetadataUpdate.UpgradeFormatVersion(formatVersion));
    }
    return updates;
  }

  /**
   * Mirrors {@link TableMetadata#replaceSortOrder(SortOrder)}, which resolves to Iceberg's {@code
   * Builder.setDefaultSortOrder(SortOrder)}.
   *
   * <p>The branch matters. {@code SetDefaultSortOrder(-1)} means "the order just added", and an
   * {@code AddSortOrder} for an order the table already carries adds nothing -- so pairing them
   * unconditionally would leave the {@code -1} sentinel with nothing to resolve to. Reusing the
   * existing order's id when the document already has an equivalent order is what Iceberg's builder
   * does internally, and it is what keeps a commit that merely re-selects an existing sort order
   * from failing.
   */
  private static List<MetadataUpdate> deriveSortOrderUpdates(
      TableMetadata document, String sortOrderJson) {
    if (sortOrderJson == null) {
      return Collections.emptyList();
    }
    SortOrder sortOrder = SortOrderParser.fromJson(document.schema(), sortOrderJson);

    Integer existingId = null;
    for (SortOrder candidate : document.sortOrders()) {
      boolean sameOrder =
          sortOrder.isUnsorted() ? candidate.isUnsorted() : candidate.sameOrder(sortOrder);
      if (sameOrder) {
        existingId = candidate.orderId();
        break;
      }
    }

    if (existingId != null) {
      return Collections.singletonList(new MetadataUpdate.SetDefaultSortOrder(existingId));
    }
    List<MetadataUpdate> updates = new ArrayList<>();
    updates.add(new MetadataUpdate.AddSortOrder(sortOrder));
    // -1 is Iceberg's "the sort order just added" sentinel.
    updates.add(new MetadataUpdate.SetDefaultSortOrder(-1));
    return updates;
  }

  /**
   * Derives the snapshot changes from the client's full snapshot list.
   *
   * <p>This is where "whole document" bites hardest. The client sends the snapshots it believes the
   * table should have, so the derivation is a set difference in both directions: snapshots the
   * client sent that the document lacks are added, snapshots the document has that the client did
   * not send are removed, refs the client did not send are dropped, and every ref the client did
   * send is set. A {@code null} list means the commit carried no snapshot section at all and
   * touches no snapshot; an <em>empty</em> list means the client is asking for a table with no
   * snapshots, which expires every one it has. Collapsing those two would silently expire data.
   */
  private static List<MetadataUpdate> deriveSnapshotUpdates(
      TableMetadata document,
      List<Snapshot> snapshotsToPut,
      Map<String, SnapshotRef> snapshotRefs) {
    if (snapshotsToPut == null) {
      return Collections.emptyList();
    }
    Map<String, SnapshotRef> refs = snapshotRefs == null ? new HashMap<>() : snapshotRefs;

    Set<Long> existingSnapshotIds =
        document.snapshots().stream().map(Snapshot::snapshotId).collect(Collectors.toSet());
    Set<Long> newSnapshotIds =
        snapshotsToPut.stream().map(Snapshot::snapshotId).collect(Collectors.toSet());

    List<MetadataUpdate> updates = new ArrayList<>();
    snapshotsToPut.stream()
        .filter(snapshot -> !existingSnapshotIds.contains(snapshot.snapshotId()))
        .forEach(snapshot -> updates.add(new MetadataUpdate.AddSnapshot(snapshot)));

    // Iterated over the document's snapshot list rather than the id set so the derived list is
    // deterministic; removals are independent of one another, so the order carries no meaning.
    document.snapshots().stream()
        .map(Snapshot::snapshotId)
        .filter(id -> !newSnapshotIds.contains(id))
        .forEach(id -> updates.add(new MetadataUpdate.RemoveSnapshot(id)));

    document.refs().keySet().stream()
        .filter(ref -> !refs.containsKey(ref))
        .forEach(ref -> updates.add(new MetadataUpdate.RemoveSnapshotRef(ref)));

    refs.forEach(
        (name, ref) ->
            updates.add(
                new MetadataUpdate.SetSnapshotRef(
                    name,
                    ref.snapshotId(),
                    ref.type(),
                    ref.minSnapshotsToKeep(),
                    ref.maxSnapshotAgeMs(),
                    ref.maxRefAgeMs())));

    return updates;
  }
}
