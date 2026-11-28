package com.linkedin.openhouse.tables.formatversion;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.linkedin.openhouse.common.exception.CommitRejectedException;
import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ManifestFiles;
import org.apache.iceberg.ManifestReader;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.io.FileIO;
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
 *   <li>{@code variant}: off by default, behind {@code cluster.iceberg.format-v3.variant.enabled};
 *       with it on, supported on a table that writes Parquet or Avro data files. Iceberg's Spark 4
 *       integration writes and reads it there; it has no ORC writer for it, and ORC is the
 *       cluster's default, so a v3 table that writes ORC is refused a variant column; and its ORC
 *       reader cannot read a variant column from older ORC files, so a table that still has ORC
 *       data files is refused one by schema evolution. Spark 3.5 has no variant type and cannot
 *       read such a table (a Spark 3.5 limitation, not refused); because OpenHouse's retention job
 *       runs on Spark 3.5, a table with a variant column may not have a retention policy.
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

  /** The table property that holds the table's OpenHouse policies, as JSON. */
  private static final String POLICIES = "policies";

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
    String policies = properties.get(POLICIES);
    if (policies != null && hasRetention(policies)) {
      fieldsInIdOrder(schema)
          .filter(f -> isVariant(f.type()))
          .findFirst()
          .ifPresent(
              field -> {
                throw new CommitRejectedException(
                    String.format(
                        "%s: column %s (variant) of table %s: the table has a retention policy,"
                            + " and OpenHouse's retention job runs on Spark 3.5, which cannot read"
                            + " a table with a variant column, so the policy would not be"
                            + " enforced. A table with a variant column cannot have a retention"
                            + " policy yet.",
                        REFUSED, schema.findColumnName(field.fieldId()), table));
              });
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
   * Refuses a variant column the table did not have while {@value FormatVersionRule#VARIANT_SWITCH}
   * is off (the default), on create and on evolution. A table that already has one (made while the
   * property was on) keeps it and its other commits are not touched.
   *
   * @param base the table's current metadata; {@code null} for a create
   * @param added the schemas the commit adds
   */
  public static void refuseNewVariantWhenOff(
      TableIdentifier table, TableMetadata base, List<Schema> added, boolean variantEnabled) {
    if (variantEnabled) {
      return;
    }
    Set<Integer> before = variantFieldIds(base);
    for (Schema schema : added) {
      fieldsInIdOrder(schema)
          .filter(f -> isVariant(f.type()) && !before.contains(f.fieldId()))
          .findFirst()
          .ifPresent(
              field -> {
                throw new CommitRejectedException(
                    String.format(
                        "%s: column %s (variant) of table %s: variant columns are off on this"
                            + " cluster (%s is false): a variant column can never be dropped, and"
                            + " the maintenance jobs, which run on Spark 3.5, cannot read, compact"
                            + " or apply retention to a table that has one.",
                        REFUSED,
                        schema.findColumnName(field.fieldId()),
                        table,
                        FormatVersionRule.VARIANT_SWITCH));
              });
    }
  }

  /** The field ids of every variant column in any schema of {@code metadata}. */
  private static Set<Integer> variantFieldIds(TableMetadata metadata) {
    Set<Integer> ids = new HashSet<>();
    if (metadata != null) {
      for (Schema schema : metadata.schemas()) {
        TypeUtil.indexById(schema.asStruct()).values().stream()
            .filter(f -> isVariant(f.type()))
            .forEach(f -> ids.add(f.fieldId()));
      }
    }
    return ids;
  }

  /**
   * Refuses a commit that gives a table with data a variant column it did not have, while a branch
   * or tag of the table still reads ORC data files: Iceberg's Spark ORC reader cannot project a
   * variant column into an ORC file that lacks it (it fails reading every such file), whatever the
   * table writes from now on. A table whose data files are Parquet or Avro takes the column.
   *
   * @param base the table's current metadata; {@code null} for a create, which has no data files
   * @param io the table's file IO; {@code null} where the caller has none, which refuses such a
   *     commit rather than guess
   */
  public static void refuseVariantOverOrcFiles(
      TableIdentifier table, TableMetadata base, TableMetadata committed, FileIO io) {
    if (base == null || base.snapshots().isEmpty()) {
      return;
    }
    Set<Integer> before = variantFieldIds(base);
    Optional<Types.NestedField> added =
        fieldsInIdOrder(committed.schema())
            .filter(f -> isVariant(f.type()) && !before.contains(f.fieldId()))
            .findFirst();
    if (!added.isPresent()) {
      return;
    }
    String column = committed.schema().findColumnName(added.get().fieldId());
    Optional<String> orcFile = io == null ? Optional.of("") : firstOrcDataFile(base, io);
    if (orcFile.isPresent()) {
      throw new CommitRejectedException(
          String.format(
              "%s: column %s (variant) of table %s: the table has ORC data files%s, and Iceberg's"
                  + " Spark integration cannot read a variant column from an ORC file that lacks"
                  + " it. A variant column can be added to a table whose data files are all"
                  + " Parquet or Avro; rewrite the table's data to Parquet first, or create a"
                  + " table with the column.",
              REFUSED, column, table, orcFile.get().isEmpty() ? "" : " (" + orcFile.get() + ")"));
    }
  }

  /** The first ORC data file a branch or tag of {@code metadata} reads, if any. */
  private static Optional<String> firstOrcDataFile(TableMetadata metadata, FileIO io) {
    Set<Long> heads = new HashSet<>();
    metadata.refs().values().forEach(ref -> heads.add(ref.snapshotId()));
    if (metadata.currentSnapshot() != null) {
      heads.add(metadata.currentSnapshot().snapshotId());
    }
    Set<String> seen = new HashSet<>();
    for (Long id : heads) {
      Snapshot snapshot = metadata.snapshot(id);
      if (snapshot == null) {
        continue;
      }
      for (ManifestFile manifest : snapshot.dataManifests(io)) {
        if (!seen.add(manifest.path())) {
          continue;
        }
        try (ManifestReader<DataFile> reader =
            ManifestFiles.read(manifest, io, metadata.specsById())) {
          for (DataFile file : reader) {
            if (file.format() == FileFormat.ORC) {
              return Optional.of(file.path().toString());
            }
          }
        } catch (IOException e) {
          throw new UncheckedIOException("Cannot read manifest " + manifest.path(), e);
        }
      }
    }
    return Optional.empty();
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

  /** The ids of the types that exist only from format version 3 on, by name. */
  private static final Set<String> FORMAT_V3_ONLY =
      Collections.unmodifiableSet(
          new HashSet<>(
              java.util.Arrays.asList(
                  "TIMESTAMP_NANO", "UNKNOWN", VARIANT, "GEOMETRY", "GEOGRAPHY")));

  /**
   * The first column type of {@code schema} that exists only from format version 3 on, in the order
   * a schema's JSON is read: a struct's fields in order, each with everything inside it, a list's
   * element, a map's key then its value.
   */
  public static Optional<Type> firstFormatV3Type(Schema schema) {
    return firstFormatV3Type(schema.asStruct());
  }

  private static Optional<Type> firstFormatV3Type(Type type) {
    if (FORMAT_V3_ONLY.contains(name(type))) {
      return Optional.of(type);
    }
    if (type.isNestedType()) {
      for (Types.NestedField field : type.asNestedType().fields()) {
        Optional<Type> found = firstFormatV3Type(field.type());
        if (found.isPresent()) {
          return found;
        }
      }
    }
    return Optional.empty();
  }

  /**
   * The request validation's failure for a v3-only type below format v3: what it answered where the
   * schema parser did not know the type.
   */
  public static String belowV3SchemaFailure(Type type) {
    return "schema : Cannot parse type string to primitive: " + spelled(type);
  }

  /**
   * The request validation's failure for a sort order whose schema holds a v3-only type below
   * format v3: where the schema parser did not know the type, the sort order could not be read.
   */
  public static String belowV3SortOrderFailure(String sortOrder) {
    return String.format("sortOrder : provided %s is not a valid sort order", sortOrder);
  }

  /**
   * {@code type} as a schema's JSON spells it, the short name for a geometry or geography of the
   * default reference system and algorithm, as a request most often spells it.
   */
  public static String spelled(Type type) {
    String spelled = type.toString();
    if ("geometry(OGC:CRS84)".equalsIgnoreCase(spelled)) {
      return "geometry";
    }
    if ("geography(OGC:CRS84, spherical)".equalsIgnoreCase(spelled)) {
      return "geography";
    }
    return spelled;
  }

  /** Whether the table's {@code policies} property sets a retention policy. */
  private static boolean hasRetention(String policies) {
    try {
      JsonElement parsed = JsonParser.parseString(policies);
      if (!parsed.isJsonObject()) {
        return false;
      }
      JsonElement retention = parsed.getAsJsonObject().get("retention");
      return retention != null && !retention.isJsonNull();
    } catch (RuntimeException e) {
      return false;
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
