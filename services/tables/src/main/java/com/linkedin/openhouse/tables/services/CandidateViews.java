package com.linkedin.openhouse.tables.services;

import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import com.linkedin.openhouse.common.schema.IcebergSchemaHelper;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Policies;
import com.linkedin.openhouse.tables.dto.mapper.iceberg.PartitionSpecMapper;
import com.linkedin.openhouse.tables.model.TableDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.PartitionField;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotParser;
import org.apache.iceberg.SnapshotRefParser;
import org.apache.iceberg.SortOrderParser;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.LocationProvider;

/**
 * Converts a candidate given as Iceberg {@link TableMetadata} into the form the commit core reads
 * (see {@link com.linkedin.openhouse.tables.commit.TableCommit}): the stored table's {@link
 * TableDto} with what the candidate proposes, spelled as a native get-modify-put request would
 * spell it.
 *
 * <p>The update commit persists the current schema, the sort order, the properties, the policies
 * and the snapshots and refs; it never writes a partition spec, location, UUID, format version or
 * statistics. A candidate that changes one of those is refused here rather than silently stored
 * without it. Partition specs are compared by what the native API can express (time partitioning
 * and clustering), and then checked by the same partition-evolution rule as a native PUT.
 */
final class CandidateViews {
  private CandidateViews() {}

  static TableDto toView(
      TableDto base,
      TableMetadata baseMetadata,
      String baseVersion,
      TableMetadata candidate,
      Policies policies,
      PartitionSpecMapper partitionSpecMapper,
      boolean replace) {
    refuseUnpersistedChanges(base, baseMetadata, candidate, partitionSpecMapper);
    Table candidateTable = staticTable(candidate);
    TableDto.TableDtoBuilder view =
        base.toBuilder()
            .schema(IcebergSchemaHelper.getSchemaJsonFromSchema(candidate.schema()))
            .timePartitioning(partitionSpecMapper.toTimePartitionSpec(candidateTable))
            .clustering(partitionSpecMapper.toClusteringSpec(candidateTable))
            .tableProperties(new HashMap<>(candidate.properties()))
            .policies(policies)
            .tableVersion(baseVersion)
            .sortOrder(SortOrderParser.toJson(candidate.sortOrder()))
            .newIntermediateSchemas(null)
            .stageCreate(false)
            .stageReplace(false)
            .replaceCommit(replace)
            // The native mapper never carries these over from the stored table.
            .lastModifiedTime(0)
            .creationTime(0)
            .jsonSnapshots(null)
            .snapshotRefs(null);
    // A replace always carries the candidate's snapshots and refs, as the native client sends
    // a replace commit: the replace rebuilds the table from them.
    if (replace || snapshotsChanged(baseMetadata, candidate)) {
      view.jsonSnapshots(
              candidate.snapshots().stream()
                  .map(SnapshotParser::toJson)
                  .collect(Collectors.toList()))
          .snapshotRefs(
              candidate.refs().entrySet().stream()
                  .collect(
                      Collectors.toMap(
                          Map.Entry::getKey, e -> SnapshotRefParser.toJson(e.getValue()))));
    }
    return view.build();
  }

  static boolean snapshotsChanged(TableMetadata base, TableMetadata candidate) {
    return !snapshotIds(base).equals(snapshotIds(candidate))
        || !Objects.equals(base.refs(), candidate.refs());
  }

  private static List<Long> snapshotIds(TableMetadata metadata) {
    List<Long> ids = new ArrayList<>();
    for (Snapshot snapshot : metadata.snapshots()) {
      ids.add(snapshot.snapshotId());
    }
    return ids;
  }

  private static void refuseUnpersistedChanges(
      TableDto base,
      TableMetadata baseMetadata,
      TableMetadata candidate,
      PartitionSpecMapper partitionSpecMapper) {
    List<String> changed = new ArrayList<>();
    if (!Objects.equals(baseMetadata.location(), candidate.location())) {
      changed.add("location");
    }
    if (!Objects.equals(baseMetadata.uuid(), candidate.uuid())) {
      changed.add("table UUID");
    }
    if (baseMetadata.formatVersion() != candidate.formatVersion()) {
      changed.add("format version");
    }
    if (!Objects.equals(baseMetadata.statisticsFiles(), candidate.statisticsFiles())
        || !Objects.equals(
            baseMetadata.partitionStatisticsFiles(), candidate.partitionStatisticsFiles())) {
      changed.add("statistics files");
    }
    if (!baseMetadata.snapshots().isEmpty() && candidate.snapshots().isEmpty()) {
      changed.add("snapshots (all removed)");
    }
    if (!sameFields(candidate.spec(), expressible(candidate, partitionSpecMapper))) {
      changed.add("partition spec (not expressible as time partitioning and clustering)");
    }
    if (!changed.isEmpty()) {
      throw new RequestValidationFailureException(
          String.format(
              "Commit on table %s.%s changes what an OpenHouse table commit does not store: %s",
              base.getDatabaseId(), base.getTableId(), String.join(", ", changed)));
    }
  }

  /** The candidate's spec as the native API would rebuild it from what it can express. */
  static PartitionSpec expressible(
      TableMetadata candidate, PartitionSpecMapper partitionSpecMapper) {
    Table table = staticTable(candidate);
    TableDto spelled =
        TableDto.builder()
            .schema(IcebergSchemaHelper.getSchemaJsonFromSchema(candidate.schema()))
            .timePartitioning(partitionSpecMapper.toTimePartitionSpec(table))
            .clustering(partitionSpecMapper.toClusteringSpec(table))
            .build();
    return partitionSpecMapper.toPartitionSpec(spelled);
  }

  static boolean sameFields(PartitionSpec a, PartitionSpec b) {
    return describe(a).equals(describe(b));
  }

  private static List<String> describe(PartitionSpec spec) {
    List<String> fields = new ArrayList<>();
    for (PartitionField field : spec.fields()) {
      fields.add(
          field.name()
              + "|"
              + field.transform()
              + "|"
              + spec.schema().findColumnName(field.sourceId()));
    }
    return fields;
  }

  /** A read-only {@link Table} over {@code metadata}, for the mappers that read one. */
  static Table staticTable(TableMetadata metadata) {
    return new BaseTable(
        new TableOperations() {
          @Override
          public TableMetadata current() {
            return metadata;
          }

          @Override
          public TableMetadata refresh() {
            return metadata;
          }

          @Override
          public void commit(TableMetadata base, TableMetadata updated) {
            throw new UnsupportedOperationException("read-only candidate view");
          }

          @Override
          public FileIO io() {
            throw new UnsupportedOperationException("read-only candidate view");
          }

          @Override
          public String metadataFileLocation(String fileName) {
            throw new UnsupportedOperationException("read-only candidate view");
          }

          @Override
          public LocationProvider locationProvider() {
            throw new UnsupportedOperationException("read-only candidate view");
          }
        },
        "candidate");
  }
}
