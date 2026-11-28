package com.linkedin.openhouse.tables.api.irc;

import java.util.Collections;
import java.util.List;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.rest.requests.UpdateTableRequest;

/**
 * The requirements of an IRC commit: checking them against the base, and reading the commit's shape
 * from them.
 *
 * <p>Static helpers of {@link IcebergRestWriteHandler}, kept apart because the replace-shape rule
 * and its reasoning read on their own.
 */
final class IcebergRestRequirements {

  private IcebergRestRequirements() {}

  /** Checks every requirement of the commit against {@code base} ({@code null}: no table). */
  static void validate(UpdateTableRequest request, TableMetadata base) {
    for (UpdateRequirement requirement : request.requirements()) {
      requirement.validate(base);
    }
  }

  /**
   * Checks the commit's {@code assert-create} requirements on a table that exists, where they fail
   * with the requirement's own message, which names no stored value. The requirement asks only
   * whether there is a table, so it is checked against a placeholder: nothing of the stored table
   * is read for it.
   */
  static void refuseCreateOfExisting(UpdateTableRequest request) {
    TableMetadata exists =
        TableMetadata.newTableMetadata(
            new Schema(),
            PartitionSpec.unpartitioned(),
            SortOrder.unsorted(),
            "placeholder:/",
            Collections.emptyMap());
    for (UpdateRequirement requirement : request.requirements()) {
      if (requirement instanceof UpdateRequirement.AssertTableDoesNotExist) {
        requirement.validate(exists);
      }
    }
  }

  /**
   * A commit that completes a staged create: it requires that the table does not exist ({@code
   * assert-create}), which only a create transaction asserts.
   */
  static boolean isCreate(UpdateTableRequest request) {
    return request.requirements().stream()
        .anyMatch(UpdateRequirement.AssertTableDoesNotExist.class::isInstance);
  }

  /**
   * A replace commit, as Iceberg's {@code RESTTableOperations} sends one (1.5 and 1.12 alike). The
   * client sends no replace intent; the shape reveals it. A replace always sets the current schema,
   * the default spec and the default sort order ({@code RESTSessionCatalog.replaceTransaction} adds
   * any of the three the replacement lacks), and {@code UpdateRequirements.forReplaceTable} never
   * asserts the current schema, default spec or default sort order. An ordinary commit that sets
   * any of the three always asserts the matching id ({@code UpdateRequirements.forUpdateTable}), so
   * Iceberg's own clients cannot send an ordinary commit of this shape. The same rule as the
   * in-process seam's adapter ({@code OpenHouseRestAdapter.isReplace}).
   */
  static boolean isReplace(UpdateTableRequest request) {
    if (isCreate(request)) {
      return false;
    }
    List<MetadataUpdate> updates = request.updates();
    List<UpdateRequirement> requirements = request.requirements();
    return updates.stream().anyMatch(MetadataUpdate.SetCurrentSchema.class::isInstance)
        && updates.stream().anyMatch(MetadataUpdate.SetDefaultPartitionSpec.class::isInstance)
        && updates.stream().anyMatch(MetadataUpdate.SetDefaultSortOrder.class::isInstance)
        && requirements.stream()
            .noneMatch(
                requirement ->
                    requirement instanceof UpdateRequirement.AssertCurrentSchemaID
                        || requirement instanceof UpdateRequirement.AssertDefaultSpecID
                        || requirement instanceof UpdateRequirement.AssertDefaultSortOrderID);
  }
}
