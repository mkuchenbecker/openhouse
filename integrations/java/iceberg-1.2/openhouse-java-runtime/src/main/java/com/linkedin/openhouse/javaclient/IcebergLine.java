package com.linkedin.openhouse.javaclient;

import java.util.Collections;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.types.Types;

/**
 * The Iceberg line this client runs on, which decides how column defaults work (decisions D5 and
 * D11 of the IRC plan).
 *
 * <ul>
 *   <li>{@link #LEGACY}: LinkedIn's Iceberg 1.2 and 1.5.2 releases. Tables are format v2 at most,
 *       and OpenHouse supplies column defaults through the read bridge: the server stamps {@code
 *       openhouse.read-bridge.column-default.*} into the table config, the client overlays them,
 *       and engine catalogs declare the {@code column-defaults} capability.
 *   <li>{@link #V3}: the fork's Iceberg 1.12 line. Column defaults are native format v3 defaults,
 *       so the client applies no overlay and declares no {@code column-defaults} capability; a
 *       table that requires it is refused by the server (422 {@code CLIENT_CAPABILITY_MISSING})
 *       rather than read differently by legacy and new clients.
 * </ul>
 *
 * <p>The line is detected once, by asking the Iceberg on the classpath to build format v3 table
 * metadata in memory: LinkedIn's lines reject format v3 ({@code Unsupported format version: v3}),
 * the 1.12 line builds it. This is the property the decision rests on (native defaults need format
 * v3), it uses only public Iceberg API that exists on every line, and nothing a user configures can
 * change it. Nothing is written anywhere.
 */
public enum IcebergLine {
  /** LinkedIn's Iceberg 1.2 / 1.5.2 releases: format v2 at most, OpenHouse read bridge. */
  LEGACY,
  /** An Iceberg that builds format v3 metadata (the fork's 1.12 line): native column defaults. */
  V3;

  /** The line of the Iceberg on this classpath. */
  public static IcebergLine current() {
    return Detected.LINE;
  }

  /**
   * Whether the client overlays {@code openhouse.read-bridge.column-default.*} config onto loaded
   * metadata, and an engine catalog that fills defaults declares {@code column-defaults}.
   */
  public boolean usesReadBridge() {
    return this == LEGACY;
  }

  private static final class Detected {
    private static final IcebergLine LINE = detect();

    private static IcebergLine detect() {
      try {
        TableMetadata.newTableMetadata(
            new Schema(Types.NestedField.optional(1, "probe", Types.IntegerType.get())),
            PartitionSpec.unpartitioned(),
            SortOrder.unsorted(),
            "openhouse:/iceberg-line-probe",
            Collections.singletonMap(TableProperties.FORMAT_VERSION, "3"));
        return V3;
      } catch (IllegalArgumentException e) {
        // Preconditions.checkArgument: "Unsupported format version: v3".
        return LEGACY;
      }
    }
  }
}
