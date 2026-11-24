package com.linkedin.openhouse.tables.formatversion;

import com.linkedin.openhouse.common.exception.CommitRejectedException;
import com.linkedin.openhouse.internal.catalog.CatalogConstants;
import com.linkedin.openhouse.internal.catalog.PendingCommit;
import com.linkedin.openhouse.internal.catalog.PreCommitCheck;
import com.linkedin.openhouse.tables.common.TableType;
import com.linkedin.openhouse.tables.readbridge.ReadBridgeConfigResolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.expressions.Literal;
import org.apache.iceberg.types.TypeUtil;
import org.apache.iceberg.types.Types;

/**
 * What a commit must hold for the table's format version, checked by the catalog on every commit
 * path (create, staged create, replace, update, snapshots, rename, lock, replication) after its
 * stale-base check and before anything is written.
 *
 * <ol>
 *   <li>The format version the commit leaves is one {@link FormatVersionRule#checkCommitted}
 *       allows.
 *   <li>A commit that leaves a format v3 table may not carry what OpenHouse does not yet handle on
 *       v3, and is refused with the work item that will: a replica or replicated table (X7), table
 *       encryption; and the format v3 types are checked type by type ({@link FormatV3Types}, X5).
 *   <li>Column defaults (work item W6). On v3, defaults are Iceberg's native ones and are stored as
 *       committed: a field the table already has keeps its {@code initial-default}, only a field
 *       new in the commit may carry one, and the read-bridge opt-in {@code
 *       required-capability.column-defaults} is refused. Below v3, a commit may not introduce or
 *       change a non-null {@code write-default}.
 * </ol>
 *
 * <p>The column-default policy bypass does not skip these: they keep metadata valid, they are not a
 * policy on who may write.
 */
public class FormatVersionCommitPolicy implements PreCommitCheck {

  static final String V3_REPLICATION = "FORMAT_V3_REPLICATION_NOT_SUPPORTED";

  static final String V3_ENCRYPTION = "FORMAT_V3_ENCRYPTION_NOT_SUPPORTED";

  static final String OPT_IN_ON_V3 = "COLUMN_DEFAULTS_OPT_IN_ON_V3";

  static final String INITIAL_DEFAULT_IMMUTABLE = "COLUMN_INITIAL_DEFAULT_IMMUTABLE";

  static final String REQUIRES_V3 = "COLUMN_DEFAULT_REQUIRES_V3";

  /** Iceberg's table properties that turn table encryption on. */
  private static final String[] ENCRYPTION_PROPERTIES = {
    "encryption.key-id", "encryption.data-key-length"
  };

  private static final String TABLE_TYPE_KEY = "openhouse.tableType";

  private final FormatVersionRule rule;

  public FormatVersionCommitPolicy(FormatVersionRule rule) {
    this.rule = rule;
  }

  @Override
  public String getName() {
    return "format-version";
  }

  @Override
  public void check(PendingCommit commit) {
    check(
        commit.getTableIdentifier(),
        commit.getBase().orElse(null),
        commit.getMetadata(),
        commit.getProperties());
  }

  /**
   * The check, for a caller that builds metadata without committing it (a staged create).
   *
   * @param base the table's current metadata; {@code null} for a create
   * @param committed the metadata the commit proposes
   * @param properties the properties the commit leaves
   * @throws CommitRejectedException if the commit breaks one of the rules
   */
  public void check(
      TableIdentifier table,
      TableMetadata base,
      TableMetadata committed,
      Map<String, String> properties) {
    rule.checkCommitted(
        table.toString(), base == null ? null : base.formatVersion(), committed.formatVersion());
    List<Schema> added = addedSchemas(base, committed);
    if (committed.formatVersion() >= IcebergV3.VERSION) {
      refuseWhatV3DoesNotHandleYet(table, committed, properties, added);
      keepInitialDefaults(table, base, added);
    } else {
      refuseNewWriteDefaults(table, base, committed, added);
    }
  }

  private static void refuseWhatV3DoesNotHandleYet(
      TableIdentifier table,
      TableMetadata committed,
      Map<String, String> properties,
      List<Schema> added) {
    if (isTrue(properties.get(ReadBridgeConfigResolver.COLUMN_DEFAULTS_CAPABILITY_PROP))) {
      throw new CommitRejectedException(
          String.format(
              "%s: table %s is, or would become, format version 3 and sets %s=true. Column defaults"
                  + " on a format v3 table are Iceberg's native initial-default and write-default,"
                  + " stored in the table's metadata; the read bridge that property turns on is for"
                  + " format v2 tables only. A table that uses it moves to v3 only through the"
                  + " migration that translates its defaults (work item W8).",
              OPT_IN_ON_V3, table, ReadBridgeConfigResolver.COLUMN_DEFAULTS_CAPABILITY_PROP));
    }
    if (TableType.REPLICA_TABLE.name().equals(properties.get(TABLE_TYPE_KEY))
        || isTrue(properties.get(CatalogConstants.OPENHOUSE_IS_TABLE_REPLICATED_KEY))) {
      throw new CommitRejectedException(
          String.format(
              "%s: table %s is, or would become, format version 3 and is a replica or replicated"
                  + " table. Replication of format v3 tables is not supported yet (work item X7):"
                  + " a replica is rebuilt from the snapshots it is sent, which cannot keep the"
                  + " source's next-row-id.",
              V3_REPLICATION, table));
    }
    for (String key : ENCRYPTION_PROPERTIES) {
      if (properties.containsKey(key)) {
        throw new CommitRejectedException(
            String.format(
                "%s: table %s sets the table property %s. Table encryption is not supported by"
                    + " OpenHouse: it does not store encryption keys.",
                V3_ENCRYPTION, table, key));
      }
    }
    if (IcebergV3.hasEncryptionKeys(committed)) {
      throw new CommitRejectedException(
          String.format(
              "%s: the commit on table %s carries encryption keys. Table encryption is not"
                  + " supported by OpenHouse: it does not store encryption keys.",
              V3_ENCRYPTION, table));
    }
    for (Schema schema : added) {
      FormatV3Types.checkOnV3(table, schema, properties);
    }
    // A property change (to ORC) can make the current schema's variant column unwritable too.
    FormatV3Types.checkOnV3(table, committed.schema(), properties);
  }

  /**
   * On v3: a field the table already has keeps its {@code initial-default}; a field new in the
   * commit carries the same one in every schema the commit adds.
   */
  private static void keepInitialDefaults(
      TableIdentifier table, TableMetadata base, List<Schema> added) {
    Map<Integer, Types.NestedField> known = fieldsOf(base);
    int lastColumnId = base == null ? 0 : base.lastColumnId();
    Map<Integer, Object> ofNewFields = new HashMap<>();
    for (Schema schema : added) {
      for (Types.NestedField field : TypeUtil.indexById(schema.asStruct()).values()) {
        Object initial = field.initialDefault();
        int id = field.fieldId();
        if (known.containsKey(id) || id <= lastColumnId) {
          Types.NestedField before = known.get(id);
          Object stored = before == null ? null : before.initialDefault();
          if (!sameInitialDefault(before, field)) {
            throw new CommitRejectedException(
                String.format(
                    "%s: the commit on table %s changes the initial-default of the existing column"
                        + " %s (field-id %d) from %s to %s. A column's initial-default is what"
                        + " rows written before the column existed read, so it can only be set"
                        + " when the column is added and never changes; change write-default to"
                        + " change what new rows get.",
                    INITIAL_DEFAULT_IMMUTABLE,
                    table,
                    schema.findColumnName(id),
                    id,
                    stored,
                    initial));
          }
        } else if (ofNewFields.containsKey(id) && !Objects.equals(ofNewFields.get(id), initial)) {
          throw new CommitRejectedException(
              String.format(
                  "%s: the commit on table %s gives the new column %s (field-id %d) two"
                      + " initial-defaults, %s and %s, in the schemas it adds.",
                  INITIAL_DEFAULT_IMMUTABLE,
                  table,
                  schema.findColumnName(id),
                  id,
                  ofNewFields.get(id),
                  initial));
        } else {
          ofNewFields.put(id, initial);
        }
      }
    }
  }

  /**
   * Whether {@code after} keeps the {@code initial-default} of {@code before}. A type promotion
   * (int to long, float to double, a wider decimal) converts the value to the new type, as Iceberg
   * itself does when it builds the promoted field, so it compares the stored value in the new type.
   */
  private static boolean sameInitialDefault(Types.NestedField before, Types.NestedField after) {
    Object stored = before == null ? null : before.initialDefault();
    Object initial = after.initialDefault();
    if (Objects.equals(stored, initial)) {
      return true;
    }
    if (stored == null || initial == null || before.type().equals(after.type())) {
      return false;
    }
    Literal<?> promoted = before.initialDefaultLiteral().to(after.type());
    return promoted != null && Objects.equals(promoted.value(), initial);
  }

  /** Below v3: a commit may not introduce or change a non-null {@code write-default}. */
  private static void refuseNewWriteDefaults(
      TableIdentifier table, TableMetadata base, TableMetadata committed, List<Schema> added) {
    Map<Integer, Types.NestedField> known = fieldsOf(base);
    for (Schema schema : added) {
      for (Types.NestedField field : TypeUtil.indexById(schema.asStruct()).values()) {
        Object writeDefault = field.writeDefault();
        Types.NestedField before = known.get(field.fieldId());
        if (writeDefault != null
            && !(before != null && Objects.equals(before.writeDefault(), writeDefault))) {
          throw new CommitRejectedException(
              String.format(
                  "%s: %s is format v%d; native column defaults need format v3, and upgrading is"
                      + " irreversible. The commit sets the write-default of column %s (field-id"
                      + " %d). Ask the OpenHouse team.",
                  REQUIRES_V3,
                  table,
                  committed.formatVersion(),
                  schema.findColumnName(field.fieldId()),
                  field.fieldId()));
        }
      }
    }
  }

  /** The schemas the commit adds: all of them on create, else those the base does not hold. */
  private static List<Schema> addedSchemas(TableMetadata base, TableMetadata committed) {
    List<Schema> added = new ArrayList<>();
    for (Schema schema : committed.schemas()) {
      Schema before = base == null ? null : base.schemasById().get(schema.schemaId());
      if (before == null || !before.sameSchema(schema)) {
        added.add(schema);
      }
    }
    return added;
  }

  /** Every field of every schema {@code metadata} holds, later schemas winning, by field id. */
  private static Map<Integer, Types.NestedField> fieldsOf(TableMetadata metadata) {
    Map<Integer, Types.NestedField> fields = new HashMap<>();
    if (metadata != null) {
      for (Schema schema : metadata.schemas()) {
        fields.putAll(TypeUtil.indexById(schema.asStruct()));
      }
    }
    return fields;
  }

  private static boolean isTrue(String value) {
    return value != null && "true".equalsIgnoreCase(value.trim());
  }
}
