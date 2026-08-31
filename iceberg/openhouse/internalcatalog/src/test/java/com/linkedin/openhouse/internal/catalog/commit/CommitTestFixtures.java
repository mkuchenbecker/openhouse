package com.linkedin.openhouse.internal.catalog.commit;

import java.util.Collections;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotParser;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.types.Types;

/** Shared fixtures for the commit-engine tests. */
final class CommitTestFixtures {

  static final String LOCATION = "/tmp/openhouse/db/tbl";

  static final Schema SCHEMA =
      new Schema(
          Types.NestedField.required(1, "id", Types.IntegerType.get()),
          Types.NestedField.optional(2, "data", Types.StringType.get()));

  static final Schema EVOLVED_SCHEMA =
      new Schema(
          Types.NestedField.required(1, "id", Types.IntegerType.get()),
          Types.NestedField.optional(2, "data", Types.StringType.get()),
          Types.NestedField.optional(3, "extra", Types.StringType.get()));

  private CommitTestFixtures() {}

  static PartitionSpec spec() {
    return PartitionSpec.builderFor(SCHEMA).identity("data").build();
  }

  static SortOrder sortOrder() {
    return SortOrder.builderFor(SCHEMA).asc("id").build();
  }

  /** A realistic starting point: schema, partition spec, sort order and a location. */
  static TableMetadata baseMetadata() {
    return TableMetadata.newTableMetadata(
        SCHEMA, spec(), sortOrder(), LOCATION, Collections.emptyMap());
  }

  /**
   * Builds a snapshot whose timestamp is "now" so it can be appended to freshly built metadata --
   * Iceberg rejects snapshots older than the metadata's last-updated timestamp.
   */
  static Snapshot snapshot(long snapshotId, long sequenceNumber) {
    String json =
        String.format(
            "{\"snapshot-id\":%d,\"sequence-number\":%d,\"timestamp-ms\":%d,"
                + "\"summary\":{\"operation\":\"append\"},"
                + "\"manifest-list\":\"%s/metadata/snap-%d.avro\",\"schema-id\":0}",
            snapshotId, sequenceNumber, System.currentTimeMillis(), LOCATION, snapshotId);
    return SnapshotParser.fromJson(json);
  }
}
