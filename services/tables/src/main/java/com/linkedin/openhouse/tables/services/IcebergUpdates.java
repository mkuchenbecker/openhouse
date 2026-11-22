package com.linkedin.openhouse.tables.services;

import com.linkedin.openhouse.common.schema.IcebergSchemaHelper;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.dto.mapper.iceberg.PartitionSpecMapper;
import com.linkedin.openhouse.tables.model.TableDto;
import java.util.HashMap;
import org.apache.iceberg.SortOrderParser;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.TableIdentifier;

/**
 * Spells a commit given as Iceberg metadata (the stored table and the candidate) as the native
 * update request the OpenHouse Java client sends for the same commit ({@code
 * OpenHouseTableOperations.constructMetadataRequestBody}), so that the native update validation
 * ({@code TablesApiValidator.validateUpdateTable}) can run on it unchanged, as it does on the
 * native table PUT and snapshots PUT.
 *
 * <p>The request carries the candidate's schema, partition spec (as time partitioning and
 * clustering, every clustering column, as the client sends them, so that too many are refused by
 * the validator's own rule), sort order and properties; the stored table's policies (which the
 * client parses from the stored {@code policies} property) and table type; and the stored version
 * as base.
 */
public final class IcebergUpdates {
  private IcebergUpdates() {}

  /**
   * The native update request for committing {@code candidate} on the stored table {@code stored}
   * at {@code baseVersion}.
   */
  public static CreateUpdateTableRequestBody toUpdateBody(
      TableIdentifier identifier,
      String clusterId,
      TableDto stored,
      String baseVersion,
      TableMetadata candidate,
      boolean replace,
      PartitionSpecMapper partitionSpecMapper) {
    Table table = CandidateViews.staticTable(candidate);
    CreateUpdateTableRequestBody.CreateUpdateTableRequestBodyBuilder body =
        CreateUpdateTableRequestBody.builder();
    if (stored.getTableType() != null) {
      body.tableType(stored.getTableType());
    }
    return body.databaseId(identifier.namespace().toString())
        .tableId(identifier.name())
        .clusterId(clusterId)
        .schema(IcebergSchemaHelper.getSchemaJsonFromSchema(candidate.schema()))
        .timePartitioning(partitionSpecMapper.toTimePartitionSpec(table))
        .clustering(partitionSpecMapper.toClusteringColumns(table))
        .tableProperties(new HashMap<>(candidate.properties()))
        .policies(stored.getPolicies())
        .sortOrder(SortOrderParser.toJson(candidate.sortOrder()))
        .baseTableVersion(baseVersion)
        .replaceCommit(replace)
        .build();
  }
}
