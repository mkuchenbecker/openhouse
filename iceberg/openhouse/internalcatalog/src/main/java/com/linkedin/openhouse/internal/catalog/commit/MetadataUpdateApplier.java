package com.linkedin.openhouse.internal.catalog.commit;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.ValidationException;

/**
 * Applies the {@link MetadataUpdate}s of an Iceberg REST {@code UpdateTableRequest} to a table's
 * current {@link TableMetadata}, producing the metadata the commit intends to publish.
 *
 * <p>This is the engine only: it has no knowledge of storage, of OpenHouse's repositories, or of
 * any HTTP route. It is pure in the sense that matters here -- {@code base} is never mutated, so a
 * caller that fails a precondition or aborts mid-commit still holds the untouched original.
 *
 * <p><b>Why delegate to {@link MetadataUpdate#applyTo(TableMetadata.Builder)} instead of switching
 * on the update type?</b> Each Iceberg {@code MetadataUpdate} implementation already knows how to
 * mutate a {@link TableMetadata.Builder}, including the parts that are easy to get subtly wrong
 * (last-assigned field and partition ids, the {@code -1} "last added" sentinel used by {@code
 * SetCurrentSchema} / {@code SetDefaultPartitionSpec} / {@code SetDefaultSortOrder}, snapshot log
 * bookkeeping). Restating that logic here would fork the Iceberg specification into OpenHouse and
 * let the two drift apart on every library upgrade. So the per-type semantics are delegated, and
 * this class owns only the decision of <em>which</em> updates may be applied to a table at all.
 *
 * <p>That decision is an allow-list rather than a fall-through. An update type this class does not
 * recognise -- a view-only update, or one introduced by a future Iceberg release -- is rejected
 * with a message naming it. Falling through to {@link MetadataUpdate}'s interface default would
 * raise {@link UnsupportedOperationException} from library code with no indication of which commit
 * failed, and quietly skipping it would be worse still: the commit would report success while the
 * client's change was dropped. Silently ignoring an update is the one outcome this class rules out.
 */
public final class MetadataUpdateApplier {

  /**
   * The {@link MetadataUpdate} types that carry table semantics in the Iceberg version on our
   * compile classpath. Every one of these delegates cleanly to {@code
   * applyTo(TableMetadata.Builder)}.
   */
  static final Set<Class<? extends MetadataUpdate>> SUPPORTED_TABLE_UPDATES =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  MetadataUpdate.AddPartitionSpec.class,
                  MetadataUpdate.AddSchema.class,
                  MetadataUpdate.AddSnapshot.class,
                  MetadataUpdate.AddSortOrder.class,
                  MetadataUpdate.AssignUUID.class,
                  MetadataUpdate.RemovePartitionStatistics.class,
                  MetadataUpdate.RemoveProperties.class,
                  MetadataUpdate.RemoveSnapshot.class,
                  MetadataUpdate.RemoveSnapshotRef.class,
                  MetadataUpdate.RemoveStatistics.class,
                  MetadataUpdate.SetCurrentSchema.class,
                  MetadataUpdate.SetDefaultPartitionSpec.class,
                  MetadataUpdate.SetDefaultSortOrder.class,
                  MetadataUpdate.SetLocation.class,
                  MetadataUpdate.SetPartitionStatistics.class,
                  MetadataUpdate.SetProperties.class,
                  MetadataUpdate.SetSnapshotRef.class,
                  MetadataUpdate.SetStatistics.class,
                  MetadataUpdate.UpgradeFormatVersion.class)));

  /** {@link MetadataUpdate} types that only make sense against a view, never a table. */
  static final Set<Class<? extends MetadataUpdate>> VIEW_ONLY_UPDATES =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  MetadataUpdate.AddViewVersion.class,
                  MetadataUpdate.SetCurrentViewVersion.class)));

  private MetadataUpdateApplier() {}

  /**
   * Validates {@code requirements} against {@code base} and, only if all of them hold, applies
   * {@code updates}.
   *
   * <p>Ordering is part of the contract: nothing is applied unless every precondition passes, and
   * because {@code base} is never mutated a failed precondition leaves the caller's metadata
   * exactly as it found it.
   *
   * @param base the table's current metadata, or {@code null} to build a new table from nothing
   * @param requirements the preconditions to check; must not be {@code null}, may be empty
   * @param updates the changes to apply; must not be {@code null}
   * @return the resulting metadata
   * @throws CommitFailedException if any precondition does not hold
   * @throws ValidationException if an update or requirement cannot be applied to a table
   * @throws IllegalArgumentException if either list, or any element of them, is {@code null}
   */
  public static TableMetadata applyChecked(
      TableMetadata base, List<UpdateRequirement> requirements, List<MetadataUpdate> updates) {
    UpdateRequirementValidator.validate(base, requirements);
    return apply(base, updates);
  }

  /**
   * Applies {@code updates} to {@code base} without checking any precondition.
   *
   * @param base the table's current metadata, or {@code null} to build a new table from nothing
   * @param updates the changes to apply; must not be {@code null}
   * @return the resulting metadata
   * @throws ValidationException if an update cannot be applied to a table, or if a create produced
   *     no metadata at all
   * @throws IllegalArgumentException if {@code updates} or any element is {@code null}
   */
  public static TableMetadata apply(TableMetadata base, List<MetadataUpdate> updates) {
    if (updates == null) {
      throw new IllegalArgumentException("Invalid metadata updates: null");
    }

    // A null base is the create case: the request is expected to carry the full set of updates
    // (assign-uuid, add-schema, set-current-schema, ...) that a table needs to exist at all.
    TableMetadata.Builder builder =
        base == null ? TableMetadata.buildFromEmpty() : TableMetadata.buildFrom(base);

    for (MetadataUpdate update : updates) {
      applyOne(builder, update);
    }

    TableMetadata result = builder.build();
    if (result == null) {
      // Iceberg's builder returns the metadata it started from when nothing changed, which for a
      // create is null. Returning that would hand a caller a "successful" commit with no table.
      throw new ValidationException(
          "Cannot create table metadata: no updates were supplied to build a table from");
    }
    return result;
  }

  private static void applyOne(TableMetadata.Builder builder, MetadataUpdate update) {
    if (update == null) {
      throw new IllegalArgumentException("Invalid metadata update: null");
    }

    if (VIEW_ONLY_UPDATES.contains(update.getClass())) {
      throw new ValidationException(
          "Cannot apply view update %s to a table", update.getClass().getSimpleName());
    }

    if (!SUPPORTED_TABLE_UPDATES.contains(update.getClass())) {
      throw new ValidationException(
          "Cannot apply unsupported metadata update %s to a table", update.getClass().getName());
    }

    update.applyTo(builder);
  }
}
