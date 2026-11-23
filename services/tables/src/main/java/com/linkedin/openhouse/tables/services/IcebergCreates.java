package com.linkedin.openhouse.tables.services;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.INITIAL_TABLE_VERSION;

import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import com.linkedin.openhouse.common.schema.IcebergSchemaHelper;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.dto.mapper.iceberg.PartitionSpecMapper;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.SortOrderParser;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.TableIdentifier;

/**
 * Spells a create given as Iceberg parts (schema, partition spec, sort order, properties) as the
 * native create request the OpenHouse Java client sends for the same table, so that it goes through
 * the native create's validation and rules unchanged.
 *
 * <p>The native API stores a partition spec only as time partitioning plus clustering. A spec it
 * cannot express that way (a bucket transform, a second time transform, fields in another order or
 * under other names) is refused here rather than silently stored as something else, as {@code
 * CandidateViews} refuses one on a commit.
 */
public final class IcebergCreates {
  private IcebergCreates() {}

  /**
   * The native create request for {@code identifier}, as the OpenHouse Java client builds it from a
   * table's metadata: the schema as JSON, the spec as time partitioning and clustering, the sort
   * order as JSON (unsorted when none), the properties as given, no policies, the initial version.
   */
  public static CreateUpdateTableRequestBody toCreateBody(
      TableIdentifier identifier,
      String clusterId,
      Schema schema,
      PartitionSpec spec,
      SortOrder sortOrder,
      Map<String, String> properties,
      PartitionSpecMapper partitionSpecMapper) {
    TableMetadata metadata =
        TableMetadata.newTableMetadata(
            schema,
            spec != null ? spec : PartitionSpec.unpartitioned(),
            sortOrder != null ? sortOrder : SortOrder.unsorted(),
            "candidate",
            Collections.emptyMap());
    Table table = CandidateViews.staticTable(metadata);
    CreateUpdateTableRequestBody body;
    try {
      body =
          CreateUpdateTableRequestBody.builder()
              .databaseId(identifier.namespace().toString())
              .tableId(identifier.name())
              .clusterId(clusterId)
              // The new table's schema, with the ids the sort order below is bound to (the
              // request's ids may differ: newTableMetadata assigns fresh ones), as the Java
              // client sends a new table's metadata.
              .schema(IcebergSchemaHelper.getSchemaJsonFromSchema(metadata.schema()))
              .timePartitioning(partitionSpecMapper.toTimePartitionSpec(table))
              .clustering(partitionSpecMapper.toClusteringSpec(table))
              .tableProperties(properties != null ? new HashMap<>(properties) : new HashMap<>())
              .sortOrder(SortOrderParser.toJson(metadata.sortOrder()))
              .baseTableVersion(INITIAL_TABLE_VERSION)
              .build();
    } catch (RuntimeException e) {
      throw inexpressible(identifier, e);
    }
    PartitionSpec rebuilt;
    try {
      rebuilt = CandidateViews.expressible(metadata, partitionSpecMapper);
    } catch (RuntimeException e) {
      throw inexpressible(identifier, e);
    }
    if (!CandidateViews.sameFields(metadata.spec(), rebuilt)) {
      throw inexpressible(identifier, null);
    }
    return body;
  }

  private static RequestValidationFailureException inexpressible(
      TableIdentifier identifier, RuntimeException cause) {
    String message =
        String.format(
            "Create of table %s asks for what an OpenHouse table does not store: "
                + "partition spec (not expressible as time partitioning and clustering)",
            identifier);
    return cause == null
        ? new RequestValidationFailureException(message)
        : new RequestValidationFailureException(message + ": " + cause.getMessage(), cause);
  }
}
