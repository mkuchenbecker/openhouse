package com.linkedin.openhouse.tables.resthandler;

import com.linkedin.openhouse.tables.repository.impl.BasePreservedKeyChecker;
import java.util.Collections;
import java.util.List;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableSet;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RestUpdateValidator}: preserved-key protection across the generic
 * set/remove-properties vocabulary, assign-uuid rejection, and locked-table detection from the raw
 * {@code policies} metadata property.
 */
public class RestUpdateValidatorTest {

  private static final TableIdentifier TEST_TABLE = TableIdentifier.of("d1", "t1");
  private static final Schema TEST_SCHEMA =
      new Schema(Types.NestedField.required(1, "data", Types.StringType.get()));

  private final RestUpdateValidator validator =
      new RestUpdateValidator(new BasePreservedKeyChecker());

  private static TableMetadata metadataWithProperties(ImmutableMap<String, String> properties) {
    return TableMetadata.newTableMetadata(
        TEST_SCHEMA, PartitionSpec.unpartitioned(), "/tmp/rest-validator-test", properties);
  }

  // ---------------------------------------------------------------------------------------------
  // Request-shape checks: preserved keys and assign-uuid
  // ---------------------------------------------------------------------------------------------

  @Test
  void setOpenhousePrefixedPropertyRejected() {
    List<MetadataUpdate> updates =
        ImmutableList.of(
            new MetadataUpdate.SetProperties(
                ImmutableMap.of("openhouse.tableLocation", "/somewhere/else")));
    BadRequestException e =
        Assertions.assertThrows(
            BadRequestException.class, () -> validator.validateRequestShape(TEST_TABLE, updates));
    Assertions.assertTrue(e.getMessage().contains("openhouse.tableLocation"));
  }

  @Test
  void setPoliciesPropertyRejected() {
    List<MetadataUpdate> updates =
        ImmutableList.of(new MetadataUpdate.SetProperties(ImmutableMap.of("policies", "{}")));
    Assertions.assertThrows(
        BadRequestException.class, () -> validator.validateRequestShape(TEST_TABLE, updates));
  }

  @Test
  void removePreservedPropertyRejected() {
    List<String> preservedKeys =
        ImmutableList.of(
            "policies",
            "openhouse.tableUUID",
            "openhouse.tableVersion",
            "openhouse.lastModifiedTime");
    for (String preservedKey : preservedKeys) {
      List<MetadataUpdate> updates =
          ImmutableList.of(new MetadataUpdate.RemoveProperties(ImmutableSet.of(preservedKey)));
      Assertions.assertThrows(
          BadRequestException.class,
          () -> validator.validateRequestShape(TEST_TABLE, updates),
          "Expected rejection for preserved key: " + preservedKey);
    }
  }

  @Test
  void mixedPreservedAndUserKeysStillRejected() {
    List<MetadataUpdate> updates =
        ImmutableList.of(
            new MetadataUpdate.SetProperties(
                ImmutableMap.of("user.ok", "v", "openhouse.clusterId", "evil")));
    Assertions.assertThrows(
        BadRequestException.class, () -> validator.validateRequestShape(TEST_TABLE, updates));
  }

  @Test
  void userPropertiesAllowed() {
    List<MetadataUpdate> updates =
        ImmutableList.of(
            new MetadataUpdate.SetProperties(
                ImmutableMap.of("user.key", "value", "retention.days", "7")),
            new MetadataUpdate.RemoveProperties(ImmutableSet.of("some.other.key")));
    Assertions.assertDoesNotThrow(() -> validator.validateRequestShape(TEST_TABLE, updates));
  }

  @Test
  void assignUuidRejected() {
    List<MetadataUpdate> updates =
        ImmutableList.of(new MetadataUpdate.AssignUUID("11111111-2222-3333-4444-555555555555"));
    Assertions.assertThrows(
        BadRequestException.class, () -> validator.validateRequestShape(TEST_TABLE, updates));
  }

  @Test
  void nonPropertyUpdatesPassRequestShapeCheck() {
    // Snapshot-typed updates carry no property payload; the shape check must not reject them.
    List<MetadataUpdate> updates =
        ImmutableList.of(
            new MetadataUpdate.RemoveSnapshot(42L), new MetadataUpdate.SetLocation("/x"));
    Assertions.assertDoesNotThrow(() -> validator.validateRequestShape(TEST_TABLE, updates));
  }

  @Test
  void emptyUpdateListPasses() {
    Assertions.assertDoesNotThrow(
        () -> validator.validateRequestShape(TEST_TABLE, Collections.emptyList()));
  }

  // ---------------------------------------------------------------------------------------------
  // Per-attempt base checks: lock state
  // ---------------------------------------------------------------------------------------------

  @Test
  void lockedTableRejected() {
    TableMetadata base =
        metadataWithProperties(ImmutableMap.of("policies", "{\"lockState\":{\"locked\":true}}"));
    BadRequestException e =
        Assertions.assertThrows(
            BadRequestException.class, () -> validator.validateAgainstBase(TEST_TABLE, base));
    Assertions.assertTrue(e.getMessage().contains("locked"));
  }

  @Test
  void unlockedTableAllowed() {
    TableMetadata base =
        metadataWithProperties(ImmutableMap.of("policies", "{\"lockState\":{\"locked\":false}}"));
    Assertions.assertDoesNotThrow(() -> validator.validateAgainstBase(TEST_TABLE, base));
  }

  @Test
  void policiesWithoutLockStateAllowed() {
    TableMetadata base =
        metadataWithProperties(
            ImmutableMap.of("policies", "{\"retention\":{\"count\":3,\"granularity\":\"HOUR\"}}"));
    Assertions.assertDoesNotThrow(() -> validator.validateAgainstBase(TEST_TABLE, base));
  }

  @Test
  void missingPoliciesAllowed() {
    TableMetadata base = metadataWithProperties(ImmutableMap.of());
    Assertions.assertDoesNotThrow(() -> validator.validateAgainstBase(TEST_TABLE, base));
  }

  @Test
  void malformedPoliciesDoesNotBlockWrites() {
    for (String malformed : ImmutableList.of("not-json{{{", "", "[1,2,3]", "\"string\"")) {
      TableMetadata base = metadataWithProperties(ImmutableMap.of("policies", malformed));
      Assertions.assertDoesNotThrow(
          () -> validator.validateAgainstBase(TEST_TABLE, base),
          "Malformed policies should not block: " + malformed);
    }
  }

  @Test
  void nullBasePasses() {
    Assertions.assertDoesNotThrow(() -> validator.validateAgainstBase(TEST_TABLE, null));
  }
}
