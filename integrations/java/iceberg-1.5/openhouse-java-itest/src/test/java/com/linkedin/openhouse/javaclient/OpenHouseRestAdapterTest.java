package com.linkedin.openhouse.javaclient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.MetadataUpdateParser;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.UpdateRequirements;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Test;

/**
 * The adapter tells a replace commit from an ordinary one by the request shape Iceberg 1.5's {@code
 * RESTTableOperations} builds. These cases build requests the way it does.
 */
public class OpenHouseRestAdapterTest {

  private static final Schema SCHEMA =
      new Schema(
          Types.NestedField.optional(1, "id", Types.LongType.get()),
          Types.NestedField.optional(2, "name", Types.StringType.get()));

  private static final TableMetadata BASE =
      TableMetadata.newTableMetadata(
          SCHEMA, PartitionSpec.unpartitioned(), "file:/tmp/t", Collections.emptyMap());

  /** What RESTSessionCatalog's replaceTransaction sends: forced Set* changes plus replacement. */
  private static UpdateTableRequest replaceRequest(Schema newSchema) {
    TableMetadata replacement =
        BASE.buildReplacement(
            newSchema,
            PartitionSpec.unpartitioned(),
            SortOrder.unsorted(),
            BASE.location(),
            Collections.emptyMap());
    ImmutableList.Builder<MetadataUpdate> updates = ImmutableList.builder();
    if (replacement.changes().stream()
        .noneMatch(MetadataUpdate.SetCurrentSchema.class::isInstance)) {
      updates.add(new MetadataUpdate.SetCurrentSchema(replacement.currentSchemaId()));
    }
    if (replacement.changes().stream()
        .noneMatch(MetadataUpdate.SetDefaultPartitionSpec.class::isInstance)) {
      updates.add(new MetadataUpdate.SetDefaultPartitionSpec(replacement.defaultSpecId()));
    }
    if (replacement.changes().stream()
        .noneMatch(MetadataUpdate.SetDefaultSortOrder.class::isInstance)) {
      updates.add(new MetadataUpdate.SetDefaultSortOrder(replacement.defaultSortOrderId()));
    }
    updates.addAll(replacement.changes());
    List<MetadataUpdate> all = updates.build();
    return new UpdateTableRequest(UpdateRequirements.forReplaceTable(BASE, all), all);
  }

  private static UpdateTableRequest simpleRequest(TableMetadata updated) {
    return new UpdateTableRequest(
        UpdateRequirements.forUpdateTable(BASE, updated.changes()), updated.changes());
  }

  @Test
  void replaceWithSameSchemaIsReplace() {
    UpdateTableRequest request = replaceRequest(SCHEMA);
    assertTrue(OpenHouseRestAdapter.isReplace(request));
    assertFalse(OpenHouseRestAdapter.isCreate(request));
  }

  @Test
  void replaceWithNewSchemaIsReplace() {
    Schema other =
        new Schema(
            Types.NestedField.optional(1, "id", Types.IntegerType.get()),
            Types.NestedField.optional(2, "other", Types.StringType.get()));
    assertTrue(OpenHouseRestAdapter.isReplace(replaceRequest(other)));
  }

  @Test
  void schemaEvolutionIsNotReplace() {
    Schema evolved =
        new Schema(
            Types.NestedField.optional(1, "id", Types.LongType.get()),
            Types.NestedField.optional(2, "name", Types.StringType.get()),
            Types.NestedField.optional(3, "extra", Types.StringType.get()));
    TableMetadata updated =
        TableMetadata.buildFrom(BASE).setCurrentSchema(evolved, evolved.highestFieldId()).build();
    assertFalse(OpenHouseRestAdapter.isReplace(simpleRequest(updated)));
  }

  @Test
  void propertyUpdateIsNotReplace() {
    TableMetadata updated =
        TableMetadata.buildFrom(BASE).setProperties(Collections.singletonMap("k", "v")).build();
    assertFalse(OpenHouseRestAdapter.isReplace(simpleRequest(updated)));
  }

  @Test
  void createIsNotReplace() {
    List<MetadataUpdate> updates =
        ImmutableList.of(
            addSchema(SCHEMA),
            new MetadataUpdate.SetCurrentSchema(-1),
            new MetadataUpdate.AddPartitionSpec(PartitionSpec.unpartitioned()),
            new MetadataUpdate.SetDefaultPartitionSpec(-1),
            new MetadataUpdate.AddSortOrder(SortOrder.unsorted()),
            new MetadataUpdate.SetDefaultSortOrder(-1));
    UpdateTableRequest request =
        new UpdateTableRequest(UpdateRequirements.forCreateTable(updates), updates);
    assertTrue(OpenHouseRestAdapter.isCreate(request));
    assertFalse(OpenHouseRestAdapter.isReplace(request));
  }

  /**
   * An {@code add-schema} update. Built from its JSON form because Iceberg 1.5.2 and 1.12 share no
   * {@code AddSchema} constructor (1.5.2 takes the last column id, 1.12 does not).
   */
  private static MetadataUpdate addSchema(Schema schema) {
    return MetadataUpdateParser.fromJson(
        "{\"action\":\"add-schema\",\"schema\":"
            + SchemaParser.toJson(schema)
            + ",\"last-column-id\":"
            + schema.highestFieldId()
            + "}");
  }
}
