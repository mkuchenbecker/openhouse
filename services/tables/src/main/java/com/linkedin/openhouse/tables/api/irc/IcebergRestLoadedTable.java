package com.linkedin.openhouse.tables.api.irc;

import java.util.Map;
import lombok.NonNull;
import lombok.Value;
import org.apache.iceberg.TableMetadata;

/** What an IRC load-table response carries, before it is written. */
@Value
public class IcebergRestLoadedTable {
  /** The metadata file location, as the native API reports it for the same table. */
  @NonNull String metadataLocation;

  /** The table metadata as stored at that location. */
  @NonNull TableMetadata metadata;

  /** The per-table config the native API gives the same client (read-bridge settings). */
  @NonNull Map<String, String> config;

  /** Whether the client asked only for the snapshots that refs point at. */
  boolean refsOnly;
}
