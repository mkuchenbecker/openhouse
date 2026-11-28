package com.linkedin.openhouse.internal.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Test;

/** What {@link CreateHistory} hands over and keeps. */
class CreateHistoryTest {
  private static final Schema SCHEMA =
      new Schema(
          Types.NestedField.required(1, "id", Types.IntegerType.get()),
          Types.NestedField.required(2, "data", Types.StringType.get()));

  private static TableMetadata created(Schema schema, PartitionSpec spec, SortOrder order) {
    Map<String, String> properties = new HashMap<>();
    properties.put("user.k", "v");
    return TableMetadata.newTableMetadata(schema, spec, order, "/tmp/created", properties);
  }

  /** A client's metadata: created, then a column, the spec and the order on it. */
  private static TableMetadata evolved() {
    TableMetadata start =
        created(SCHEMA, PartitionSpec.builderFor(SCHEMA).bucket("id", 16).build(), unsorted());
    Schema added =
        new Schema(
            Types.NestedField.required(1, "id", Types.IntegerType.get()),
            Types.NestedField.required(2, "data", Types.StringType.get()),
            Types.NestedField.optional(3, "new_col", Types.LongType.get()));
    TableMetadata withSchema = TableMetadata.buildFrom(start).setCurrentSchema(added, 3).build();
    PartitionSpec spec =
        PartitionSpec.builderFor(withSchema.schema()).bucket("id", 16).identity("new_col").build();
    SortOrder order = SortOrder.builderFor(withSchema.schema()).asc("new_col").build();
    return TableMetadata.buildFrom(withSchema)
        .setDefaultPartitionSpec(spec)
        .setDefaultSortOrder(order)
        .build();
  }

  private static SortOrder unsorted() {
    return SortOrder.unsorted();
  }

  private static TableMetadata handed(TableMetadata built, String history) {
    Map<String, String> properties = new HashMap<>(built.properties());
    properties.put(CreateHistory.KEY, history);
    return built.replaceProperties(properties);
  }

  @Test
  void aCreateWithoutHistoryHandsNothingOver() {
    assertNull(
        CreateHistory.of(
            created(
                SCHEMA,
                PartitionSpec.builderFor(SCHEMA).bucket("id", 16).build(),
                SortOrder.builderFor(SCHEMA).asc("id").build())));
    assertNull(CreateHistory.of(created(SCHEMA, PartitionSpec.unpartitioned(), unsorted())));
  }

  @Test
  void aCreateWithHistoryKeepsItAndOnlyIt() {
    TableMetadata client = evolved();
    String history = CreateHistory.of(client);
    assertNotNull(history);
    // What the create builds from the client's final parts: renumbered, one of each.
    TableMetadata built = created(client.schema(), client.spec(), client.sortOrder());
    assertEquals(0, built.currentSchemaId());

    TableMetadata kept = CreateHistory.apply(null, handed(built, history));

    assertEquals(client.schemas().size(), kept.schemas().size());
    assertEquals(client.currentSchemaId(), kept.currentSchemaId());
    assertEquals(client.specs().size(), kept.specs().size());
    assertEquals(client.defaultSpecId(), kept.defaultSpecId());
    assertEquals(client.sortOrders().size(), kept.sortOrders().size());
    assertEquals(client.defaultSortOrderId(), kept.defaultSortOrderId());
    assertEquals(client.lastColumnId(), kept.lastColumnId());
    assertEquals(client.lastAssignedPartitionId(), kept.lastAssignedPartitionId());
    // Everything else is the create's own, and the property is gone.
    assertEquals(built.properties(), kept.properties());
    assertEquals(built.location(), kept.location());
    assertEquals(built.uuid(), kept.uuid());
    assertEquals(built.formatVersion(), kept.formatVersion());
    assertFalse(kept.properties().containsKey(CreateHistory.KEY));
  }

  @Test
  void aCreateWhoseBuiltFinalPartsDifferFromTheHistoryIsRefused() {
    String history = CreateHistory.of(evolved());
    TableMetadata other = created(SCHEMA, PartitionSpec.unpartitioned(), unsorted());
    assertThrows(
        IllegalStateException.class, () -> CreateHistory.apply(null, handed(other, history)));
  }

  @Test
  void theHistoryIsHonoredOnACreateOnlyAndNeverStored() {
    TableMetadata built = created(SCHEMA, PartitionSpec.unpartitioned(), unsorted());
    assertSame(built, CreateHistory.apply(null, built));
    TableMetadata update = CreateHistory.apply(built, handed(built, CreateHistory.of(evolved())));
    assertEquals(built.schemas().size(), update.schemas().size());
    assertEquals(built.properties(), update.properties());
  }
}
