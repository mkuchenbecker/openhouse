package com.linkedin.openhouse.tables.api.irc;

import java.util.List;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.rest.requests.UpdateTableRequest;

/**
 * The requirements of an IRC commit: checking them against the base, and reading the commit's shape
 * from them.
 *
 * <p>This is deliberately not a Spring bean and the beans name none of its types. {@link
 * UpdateRequirement} exists from Iceberg 1.4 on, and the tables service is also embedded with
 * Iceberg 1.2 at runtime ({@code tables-test-fixtures_2.12}, used by the Spark 3.1 apps and the
 * data-layout library). Spring introspects every declared method of a bean while creating it, the
 * synthetic lambda methods included, so a bean method that names this type would stop the embedded
 * server from starting under Iceberg 1.2. Here the type is resolved only when an IRC write runs.
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
