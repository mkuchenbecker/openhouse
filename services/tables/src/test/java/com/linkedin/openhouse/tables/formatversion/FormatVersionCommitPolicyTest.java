package com.linkedin.openhouse.tables.formatversion;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.common.exception.CommitRejectedException;
import com.linkedin.openhouse.internal.catalog.PendingCommit;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.TableIdentifier;
import org.junit.jupiter.api.Test;

/**
 * Below format v3, on a cluster with the format v3 switch on, a commit is refused with {@code
 * COLUMN_DEFAULT_REQUIRES_V3} only when it introduces or changes a non-null {@code write-default}.
 * A v2 table whose field already carries one, which the Iceberg 1.12 line allowed before this rule,
 * is not blocked from commits that leave that default as it is. With the switch off the check does
 * not apply to a commit that finds and leaves a table below v3, so such a commit is stored as it
 * always was.
 */
class FormatVersionCommitPolicyTest {

  private static final TableIdentifier TABLE = TableIdentifier.of("db", "t");

  private static final String WITHOUT_DEFAULT =
      "{\"type\":\"struct\",\"schema-id\":0,\"fields\":["
          + "{\"id\":1,\"name\":\"id\",\"required\":false,\"type\":\"long\"},"
          + "{\"id\":2,\"name\":\"country\",\"required\":false,\"type\":\"string\"}]}";

  private static String withWriteDefault(String value, boolean addColumn) {
    return "{\"type\":\"struct\",\"schema-id\":0,\"fields\":["
        + "{\"id\":1,\"name\":\"id\",\"required\":false,\"type\":\"long\"},"
        + "{\"id\":2,\"name\":\"country\",\"required\":false,\"type\":\"string\""
        + (value == null ? "" : ",\"write-default\":\"" + value + "\"")
        + "}"
        + (addColumn ? ",{\"id\":3,\"name\":\"tier\",\"required\":false,\"type\":\"int\"}" : "")
        + "]}";
  }

  private static FormatVersionCommitPolicy policy() {
    return policy(true);
  }

  private static FormatVersionCommitPolicy policy(boolean switchOn) {
    ClusterProperties cluster = mock(ClusterProperties.class);
    when(cluster.getClusterIcebergFormatVersion()).thenReturn(2);
    when(cluster.isClusterIcebergFormatV3Enabled()).thenReturn(switchOn);
    return new FormatVersionCommitPolicy(new FormatVersionRule(cluster));
  }

  /** A v2 table whose column {@code country} carries the write-default "US". */
  private static TableMetadata v2TableWithAWriteDefault() {
    Map<String, String> properties = new HashMap<>();
    properties.put(TableProperties.FORMAT_VERSION, "2");
    TableMetadata created =
        TableMetadata.newTableMetadata(
            SchemaParser.fromJson(WITHOUT_DEFAULT),
            PartitionSpec.unpartitioned(),
            "file:/tmp/format-version-commit-policy-test",
            properties);
    TableMetadata withDefault = evolve(created, withWriteDefault("US", false), 2);
    assertEquals(2, withDefault.formatVersion());
    assertEquals("US", String.valueOf(withDefault.schema().findField("country").writeDefault()));
    return withDefault;
  }

  private static TableMetadata evolve(TableMetadata base, String schemaJson, int lastColumnId) {
    return TableMetadata.buildFrom(base)
        .setCurrentSchema(SchemaParser.fromJson(schemaJson), lastColumnId)
        .build();
  }

  @Test
  void aWriteDefaultAlreadyOnAV2TableDoesNotBlockCommitsThatLeaveItAsItIs() {
    FormatVersionCommitPolicy policy = policy();
    TableMetadata base = v2TableWithAWriteDefault();

    // A property change: no schema is added.
    Map<String, String> properties = new HashMap<>(base.properties());
    properties.put("user.note", "x");
    TableMetadata propertyChange = base.replaceProperties(properties);
    assertDoesNotThrow(() -> policy.check(TABLE, base, propertyChange, properties));

    // A schema evolution that adds a column and carries the existing default over unchanged.
    TableMetadata added = evolve(base, withWriteDefault("US", true), 3);
    assertDoesNotThrow(() -> policy.check(TABLE, base, added, added.properties()));

    // Removing the write-default introduces nothing.
    TableMetadata removed = evolve(base, withWriteDefault(null, false), 2);
    assertNull(removed.schema().findField("country").writeDefault());
    assertDoesNotThrow(() -> policy.check(TABLE, base, removed, removed.properties()));
  }

  @Test
  void changingOrIntroducingAWriteDefaultBelowV3IsRefused() {
    FormatVersionCommitPolicy policy = policy();
    TableMetadata base = v2TableWithAWriteDefault();

    TableMetadata changed = evolve(base, withWriteDefault("CA", false), 2);
    CommitRejectedException refused =
        assertThrows(
            CommitRejectedException.class,
            () -> policy.check(TABLE, base, changed, changed.properties()));
    assertTrue(
        refused.getMessage().startsWith(FormatVersionCommitPolicy.REQUIRES_V3),
        refused.getMessage());
    assertTrue(refused.getMessage().contains("column country"), refused.getMessage());

    TableMetadata plain =
        TableMetadata.newTableMetadata(
            SchemaParser.fromJson(WITHOUT_DEFAULT),
            PartitionSpec.unpartitioned(),
            "file:/tmp/format-version-commit-policy-test",
            Collections.singletonMap(TableProperties.FORMAT_VERSION, "2"));
    TableMetadata introduced = evolve(plain, withWriteDefault("US", false), 2);
    assertThrows(
        CommitRejectedException.class,
        () -> policy.check(TABLE, plain, introduced, introduced.properties()));
  }

  private static PendingCommit commit(TableMetadata base, TableMetadata committed) {
    return new PendingCommit(
        TABLE,
        Optional.ofNullable(base),
        committed,
        ImmutableMap.copyOf(committed.properties()),
        false);
  }

  @Test
  void withTheSwitchOffAV2CommitIsNotJudgedAndAWriteDefaultIsStored() {
    FormatVersionCommitPolicy policy = policy(false);
    TableMetadata plain =
        TableMetadata.newTableMetadata(
            SchemaParser.fromJson(WITHOUT_DEFAULT),
            PartitionSpec.unpartitioned(),
            "file:/tmp/format-version-commit-policy-test",
            Collections.singletonMap(TableProperties.FORMAT_VERSION, "2"));
    TableMetadata introduced = evolve(plain, withWriteDefault("US", false), 2);
    PendingCommit commit = commit(plain, introduced);
    assertFalse(policy.appliesTo(commit));
    assertFalse(policy.appliesTo(commit(null, plain)));
    assertDoesNotThrow(() -> policy.check(TABLE, plain, introduced, introduced.properties()));
    assertTrue(policy(true).appliesTo(commit));
  }
}
