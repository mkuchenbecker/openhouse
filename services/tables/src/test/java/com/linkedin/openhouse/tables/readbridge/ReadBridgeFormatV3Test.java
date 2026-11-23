package com.linkedin.openhouse.tables.readbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.TextNode;
import com.linkedin.openhouse.tables.model.TableDto;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * No read-bridge config is resolved for a format v3 table (work item W6): its column defaults are
 * Iceberg's native ones, stored in its metadata. A v3 table cannot carry the opt-in at all (the
 * commit path refuses it), so this pins the resolver for a table that somehow does.
 */
class ReadBridgeFormatV3Test {

  private static TableDto bridgedTable(long formatVersion) {
    return TableDto.builder()
        .databaseId("db")
        .tableId("t")
        .tableProperties(
            Collections.singletonMap(
                ReadBridgeConfigResolver.COLUMN_DEFAULTS_CAPABILITY_PROP, "true"))
        .formatVersion(formatVersion)
        .build();
  }

  @Test
  void aFormatV3TableGetsNoReadBridgeConfigAndAV2TableStillDoes() {
    ReadBridgeConfigResolver resolver =
        new ReadBridgeConfigResolver(table -> Collections.singletonMap(2, TextNode.valueOf("US")));
    assertTrue(resolver.resolve(bridgedTable(3)).isEmpty());
    assertEquals(
        Collections.singletonMap(ReadBridgeConfigResolver.COLUMN_DEFAULT_PREFIX + 2, "\"US\""),
        resolver.resolve(bridgedTable(2)));
    // A table whose version is not known (not read from stored metadata) is treated as before.
    assertEquals(1, resolver.resolve(bridgedTable(0)).size());
  }
}
