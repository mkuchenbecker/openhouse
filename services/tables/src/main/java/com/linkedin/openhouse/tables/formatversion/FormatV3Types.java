package com.linkedin.openhouse.tables.formatversion;

import com.linkedin.openhouse.common.exception.CommitRejectedException;
import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.TypeUtil;
import org.apache.iceberg.types.Types;

/**
 * What OpenHouse does with each column type that exists only from Iceberg format version 3 on (work
 * item X5), decided type by type.
 *
 * <ul>
 *   <li>{@code unknown}: supported. Spark 3.5 and Spark 4 spell it {@code void} (a CTAS of {@code
 *       NULL} makes one) and read it as nulls; no file format stores it.
 *   <li>{@code variant}: supported on a table that writes Parquet or Avro data files. Iceberg's
 *       Spark 4 integration writes and reads it there; it has no ORC writer for it, and ORC is the
 *       cluster's default, so a v3 table that writes ORC is refused a variant column. Spark 3.5 has
 *       no variant type and cannot read such a table (a Spark 3.5 limitation, not refused).
 *   <li>{@code timestamp_ns}, {@code timestamptz_ns}: refused. No Spark integration of Iceberg 1.12
 *       has a type for them, so no Spark could read the table.
 *   <li>{@code geometry}, {@code geography}: refused. Spark 3.5 cannot read them, Spark 4 has them
 *       only behind its {@code spark.sql.geospatial.enabled} flag and writes them only to Parquet,
 *       and their bounds make the table's metadata tables unreadable from Spark 3.5, where
 *       OpenHouse's maintenance jobs run.
 * </ul>
 *
 * <p>Types are recognised by the name of their type id, so this class links on LinkedIn's Iceberg
 * 1.5.2 reference line, which has none of them.
 */
public final class FormatV3Types {

  /** The token every refusal of a format v3 type starts with. */
  public static final String REFUSED = "FORMAT_V3_TYPE_NOT_SUPPORTED";

  static final String VARIANT = "VARIANT";

  /** The refused type ids, with why. */
  private static final Map<String, String> REFUSALS;

  static {
    Map<String, String> refusals = new LinkedHashMap<>();
    String nanos =
        "no Spark integration of Iceberg 1.12 (Spark 3.5 or Spark 4) has a nanosecond timestamp"
            + " type, so no Spark could read the table, and Spark SQL cannot declare one";
    refusals.put("TIMESTAMP_NANO", nanos);
    String geo =
        "Spark 3.5 cannot read a table with this column, Spark 4 has the type only behind its"
            + " spark.sql.geospatial.enabled flag and writes it only to Parquet, and its bounds"
            + " make the table's metadata tables unreadable from Spark 3.5, where the maintenance"
            + " jobs run";
    refusals.put("GEOMETRY", geo);
    refusals.put("GEOGRAPHY", geo);
    REFUSALS = Collections.unmodifiableMap(refusals);
  }

  /** The message of the old request-validation answer to a variant column, kept on v2 tables. */
  static final String VARIANT_BELOW_V3 = "schema : Unsupported type: variant";

  private FormatV3Types() {}

  /** Whether {@code type} is Iceberg's variant. */
  public static boolean isVariant(Type type) {
    return VARIANT.equals(type.typeId().name());
  }

  /** Whether {@code schema} has a variant column at any depth. */
  public static boolean hasVariant(Schema schema) {
    return TypeUtil.indexById(schema.asStruct()).values().stream()
        .anyMatch(field -> isVariant(field.type()));
  }

  /**
   * Refuses what a schema the commit leaves on a format v3 table may not hold: a refused type, or a
   * variant column on a table that writes ORC.
   *
   * @param properties the table properties the commit leaves
   * @throws CommitRejectedException naming the column, its type and why
   */
  public static void checkOnV3(
      TableIdentifier table, Schema schema, Map<String, String> properties) {
    Optional<Types.NestedField> refused =
        fieldsInIdOrder(schema).filter(f -> REFUSALS.containsKey(name(f.type()))).findFirst();
    if (refused.isPresent()) {
      Types.NestedField field = refused.get();
      throw new CommitRejectedException(
          String.format(
              "%s: column %s (%s) of table %s: OpenHouse does not take the type %s on format v3"
                  + " tables (work item X5): %s.",
              REFUSED,
              schema.findColumnName(field.fieldId()),
              field.type(),
              table,
              field.type(),
              REFUSALS.get(name(field.type()))));
    }
    String format =
        properties
            .getOrDefault(
                TableProperties.DEFAULT_FILE_FORMAT, TableProperties.DEFAULT_FILE_FORMAT_DEFAULT)
            .trim()
            .toLowerCase(Locale.ROOT);
    if ("orc".equals(format)) {
      fieldsInIdOrder(schema)
          .filter(f -> isVariant(f.type()))
          .findFirst()
          .ifPresent(
              field -> {
                throw new CommitRejectedException(
                    String.format(
                        "%s: column %s (variant) of table %s: the table writes ORC data files"
                            + " (%s=orc), and Iceberg's Spark integration cannot write a variant"
                            + " column to ORC. A variant column needs a format v3 table that"
                            + " writes Parquet: TBLPROPERTIES ('%s'='parquet').",
                        REFUSED,
                        schema.findColumnName(field.fieldId()),
                        table,
                        TableProperties.DEFAULT_FILE_FORMAT,
                        TableProperties.DEFAULT_FILE_FORMAT));
              });
    }
  }

  /**
   * Below format v3 a variant column is refused with the answer the request validation gave before
   * format v3 types could pass it ({@code schema : Unsupported type: variant}, 400), so a format v2
   * table answers exactly as it did.
   */
  public static void refuseVariantBelowV3(Schema schema, int formatVersion) {
    if (formatVersion < IcebergV3.VERSION && hasVariant(schema)) {
      throw new RequestValidationFailureException(Collections.singletonList(VARIANT_BELOW_V3));
    }
  }

  private static java.util.stream.Stream<Types.NestedField> fieldsInIdOrder(Schema schema) {
    return TypeUtil.indexById(schema.asStruct()).values().stream()
        .sorted(Comparator.comparingInt(Types.NestedField::fieldId));
  }

  private static String name(Type type) {
    return type.typeId().name();
  }
}
