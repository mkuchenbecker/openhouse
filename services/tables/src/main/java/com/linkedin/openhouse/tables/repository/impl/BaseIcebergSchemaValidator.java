package com.linkedin.openhouse.tables.repository.impl;

import com.linkedin.openhouse.common.exception.InvalidSchemaEvolutionException;
import com.linkedin.openhouse.tables.repository.SchemaValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.Schema;
import org.apache.iceberg.relocated.com.google.common.collect.MapDifference;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.TypeUtil;
import org.apache.iceberg.types.Types;
import org.springframework.stereotype.Component;

/**
 * A base implementation which delegates the validation to iceberg library that is configured to: -
 * not allow to write optional values to a required field. - allow input schema to have different
 * ordering than table.
 *
 * <p>Also provides static helpers used by {@link OpenHouseInternalRepositoryImpl} to normalize a
 * write schema's column names — at top level and at any nesting depth — to the casing already
 * present in the table schema, enabling case-insensitive writes without mutating the table's
 * existing column casing.
 */
@Component
public class BaseIcebergSchemaValidator implements SchemaValidator {

  /**
   * Rewrites every field name in {@code writeSchema} — at top level <em>and</em> inside nested
   * structs, list elements, and map keys/values — to use the casing from {@code tableSchema},
   * matched by Iceberg field ID. Fields whose ID is absent from {@code tableSchema} (genuinely new
   * columns) are left unchanged. All other field attributes (type, doc, optional/required, column
   * defaults, identifier-field-ids) are preserved.
   *
   * <p>Uses {@code TypeUtil.indexById} to build a single flat {@code Map<Integer, NestedField>} for
   * the entire table schema in one O(n) walk, giving O(1) name lookup at any depth, then recurses
   * through the write schema's structs, list elements and map keys and values.
   */
  static Schema normalizeSchemaCasingToTable(Schema writeSchema, Schema tableSchema) {
    return normalizeSchemaCasingToTable(writeSchema, tableSchema, true);
  }

  /**
   * {@link #normalizeSchemaCasingToTable(Schema, Schema)}, where a re-cased field keeps its column
   * defaults only when {@code keepDefaults}: on a table whose defaults are Iceberg's native ones.
   * Elsewhere a re-cased field is rebuilt from its id, name, type, optionality and doc, and so
   * loses a default it carried, as before this service kept native defaults.
   */
  static Schema normalizeSchemaCasingToTable(
      Schema writeSchema, Schema tableSchema, boolean keepDefaults) {
    // One O(n) walk over the full table schema tree; lookup at any depth is then O(1).
    final Map<Integer, Types.NestedField> tableById = TypeUtil.indexById(tableSchema.asStruct());
    Types.StructType normalizedStruct =
        (Types.StructType) normalizeCasing(writeSchema.asStruct(), tableById, keepDefaults);
    return new Schema(
        writeSchema.schemaId(), normalizedStruct.fields(), writeSchema.identifierFieldIds());
  }

  /**
   * {@code type} with every field name inside it in the table's casing. Written as an explicit walk
   * rather than with Iceberg's schema visitor, whose default refuses a variant: a type that is not
   * a struct, list or map has no names inside it and is returned as it is.
   */
  private static Type normalizeCasing(
      Type type, Map<Integer, Types.NestedField> tableById, boolean keepDefaults) {
    if (type.isStructType()) {
      List<Types.NestedField> originals = type.asStructType().fields();
      List<Types.NestedField> rebuilt = new ArrayList<>(originals.size());
      for (Types.NestedField original : originals) {
        Types.NestedField tableField = tableById.get(original.fieldId());
        // Only normalize *casing*: adopt the table's spelling when the write name differs from it
        // by case alone. A genuinely different name (a real column rename) is left intact so
        // schema validation rejects it loudly, instead of it being silently reverted to the old
        // name and dropped as a no-op.
        String name =
            (tableField != null && tableField.name().equalsIgnoreCase(original.name()))
                ? tableField.name()
                : original.name();
        Type fieldType = normalizeCasing(original.type(), tableById, keepDefaults);
        // Reuse the original if nothing changed (cheap reference-equality short-circuit).
        if (name.equals(original.name()) && fieldType == original.type()) {
          rebuilt.add(original);
        } else if (keepDefaults
            && (original.initialDefault() != null || original.writeDefault() != null)) {
          // A field with column defaults keeps them: on a format v3 table they are the table's
          // native defaults, stored as committed.
          rebuilt.add(Types.NestedField.from(original).withName(name).ofType(fieldType).build());
        } else {
          rebuilt.add(
              original.isOptional()
                  ? Types.NestedField.optional(original.fieldId(), name, fieldType, original.doc())
                  : Types.NestedField.required(
                      original.fieldId(), name, fieldType, original.doc()));
        }
      }
      return Types.StructType.of(rebuilt);
    }
    if (type.isListType()) {
      Types.NestedField elem = type.asListType().fields().get(0);
      Type elementResult = normalizeCasing(elem.type(), tableById, keepDefaults);
      return elem.isOptional()
          ? Types.ListType.ofOptional(elem.fieldId(), elementResult)
          : Types.ListType.ofRequired(elem.fieldId(), elementResult);
    }
    if (type.isMapType()) {
      Types.NestedField k = type.asMapType().fields().get(0);
      Types.NestedField v = type.asMapType().fields().get(1);
      Type keyResult = normalizeCasing(k.type(), tableById, keepDefaults);
      Type valueResult = normalizeCasing(v.type(), tableById, keepDefaults);
      return v.isOptional()
          ? Types.MapType.ofOptional(k.fieldId(), v.fieldId(), keyResult, valueResult)
          : Types.MapType.ofRequired(k.fieldId(), v.fieldId(), keyResult, valueResult);
    }
    return type;
  }

  @Override
  public void validateWriteSchema(Schema oldSchema, Schema newSchema, String tableUri)
      throws InvalidSchemaEvolutionException, IllegalArgumentException {
    validateFields(oldSchema, newSchema, tableUri);
    enforceDeleteColumnFailure(oldSchema, newSchema, tableUri);
    TypeUtil.validateSchema(
        "OpenHouse Server Schema validation Validation", newSchema, oldSchema, true, false);
  }

  private void enforceDeleteColumnFailure(Schema oldSchema, Schema newSchema, String tableUri) {
    // if old schema couldn't find a key in the new schema map, it should fail
    MapDifference<Integer, String> diff =
        Maps.difference(oldSchema.idToName(), newSchema.idToName());

    if (!diff.entriesOnlyOnLeft().isEmpty()) {
      throw new InvalidSchemaEvolutionException(
          tableUri, newSchema.toString(), oldSchema.toString(), "Some columns are dropped");
    }
    if (!diff.entriesDiffering().isEmpty()) {
      throw new InvalidSchemaEvolutionException(
          tableUri,
          newSchema.toString(),
          oldSchema.toString(),
          "Given same id, column name is different");
    }
  }

  /**
   * Since @param newSchema is considered to be the new source-of-truth table schema, OpenHouse just
   * need to ensure all field-Id are still matching before directly use it to assemble {@link
   * org.apache.iceberg.TableMetadata}
   *
   * <p>Note that Iceberg by itself is case-sensitive by default, the casing info can be lost
   * through the SQL engine layer. Here OH is honoring the default case sensitivity for column
   * name-based resolution.
   *
   * @param oldSchema existing schema in table catalog
   * @param newSchema user-provided schema that may contain evolution
   * @param tableUri tableUri
   */
  protected void validateFields(Schema oldSchema, Schema newSchema, String tableUri) {
    for (Types.NestedField field : oldSchema.columns()) {
      Types.NestedField columnInNewSchema = newSchema.findField(field.name());
      if (columnInNewSchema == null) {
        throw new InvalidSchemaEvolutionException(
            tableUri,
            newSchema.toString(),
            oldSchema.toString(),
            String.format("Column[%s] not found in newSchema", field.name()));
      }

      if (columnInNewSchema.fieldId() != field.fieldId()) {
        throw new InvalidSchemaEvolutionException(
            tableUri,
            newSchema.toString(),
            oldSchema.toString(),
            String.format(
                "Internal Error: Column name [%s] in newSchema has a different fieldId",
                field.name()));
      }
    }
  }
}
