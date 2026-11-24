package com.linkedin.openhouse.jobs.spark;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.MetadataColumns;
import org.apache.iceberg.PartitionKey;
import org.apache.iceberg.RowDelta;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.data.GenericFileWriterFactory;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.InternalRecordWrapper;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.deletes.BaseDVFileWriter;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.OutputFileFactory;
import org.apache.iceberg.types.TypeUtil;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.variants.ShreddedObject;
import org.apache.iceberg.variants.ValueArray;
import org.apache.iceberg.variants.Variant;
import org.apache.iceberg.variants.VariantMetadata;
import org.apache.iceberg.variants.Variants;

/**
 * Data in a format v3 table with a variant column, written and read with Iceberg's own generic
 * writers and readers (Iceberg 1.12 line), because Spark 3.5 can do neither. Data files are Parquet
 * files written by Iceberg's generic Parquet writer ({@code GenericFileWriterFactory}) and
 * committed by {@code table.newAppend()}; deletion vectors are Puffin files written by Iceberg's
 * {@code BaseDVFileWriter} and committed by {@code table.newRowDelta()}. Both commits go through
 * the table's catalog (the tables service), as any Iceberg API writer's would. The reference line
 * has its own copy of this class, which cannot do any of it.
 *
 * <p>Columns are filled by name: {@code id} (long), {@code v} (variant), {@code ts} (timestamptz),
 * {@code data} (string), {@code header} (struct of {@code memberId}, {@code time}), {@code
 * datepartition} (string).
 */
final class VariantData {
  /** Per data file written here, the ids in the order they were written: row position = index. */
  private static final Map<String, long[]> IDS_BY_FILE = new ConcurrentHashMap<>();

  private static final AtomicLong TASK = new AtomicLong();

  private static final VariantMetadata METADATA = Variants.metadata("id", "name", "nested", "k");

  private VariantData() {}

  static boolean supported() {
    return true;
  }

  /**
   * The variant value of row {@code id}: an object (with a nested object) for {@code id % 4 == 0},
   * an array for 1, a string for 2, and a variant null for 3.
   */
  static Variant variant(long id) {
    switch ((int) (id % 4)) {
      case 0:
        ShreddedObject object = Variants.object(METADATA);
        object.put("id", Variants.of(id));
        object.put("name", Variants.of("row-" + id));
        ShreddedObject nested = Variants.object(METADATA);
        nested.put("k", Variants.of(id * 10));
        object.put("nested", nested);
        return Variant.of(METADATA, object);
      case 1:
        ValueArray array = Variants.array();
        array.add(Variants.of(id));
        array.add(Variants.of("x"));
        return Variant.of(Variants.emptyMetadata(), array);
      case 2:
        return Variant.of(Variants.emptyMetadata(), Variants.of("v-" + id));
      default:
        return Variant.of(Variants.emptyMetadata(), Variants.ofNull());
    }
  }

  private static Record record(Schema schema, long id, long tsMicros, String datepartition) {
    GenericRecord record = GenericRecord.create(schema);
    for (Types.NestedField field : schema.columns()) {
      switch (field.name()) {
        case "id":
          record.setField("id", id);
          break;
        case "v":
          record.setField("v", variant(id));
          break;
        case "ts":
          record.setField(
              "ts",
              OffsetDateTime.ofInstant(
                  Instant.ofEpochSecond(0).plusNanos(tsMicros * 1000), ZoneOffset.UTC));
          break;
        case "data":
          record.setField("data", "row-" + id);
          break;
        case "header":
          GenericRecord header = GenericRecord.create(field.type().asStructType());
          header.setField("memberId", id % 3);
          header.setField("time", 100 - id);
          record.setField("header", header);
          break;
        case "datepartition":
          record.setField("datepartition", datepartition);
          break;
        default:
          throw new IllegalArgumentException("no value for column " + field.name());
      }
    }
    return record;
  }

  /**
   * Writes one Parquet data file of the rows {@code ids} (all in the partition of {@code tsMicros})
   * with Iceberg's generic writer and commits it as an append.
   *
   * @return the data file's path
   */
  static String append(Table table, long[] ids, long tsMicros, String datepartition) {
    Schema schema = table.schema();
    PartitionKey key = new PartitionKey(table.spec(), schema);
    key.partition(
        new InternalRecordWrapper(schema.asStruct())
            .wrap(record(schema, ids[0], tsMicros, datepartition)));
    OutputFileFactory files =
        OutputFileFactory.builderFor(table, 1, TASK.incrementAndGet())
            .format(FileFormat.PARQUET)
            .build();
    GenericFileWriterFactory factory =
        new GenericFileWriterFactory.Builder(table).dataFileFormat(FileFormat.PARQUET).build();
    DataWriter<Record> writer =
        factory.newDataWriter(
            table.spec().isUnpartitioned()
                ? files.newOutputFile()
                : files.newOutputFile(table.spec(), key),
            table.spec(),
            table.spec().isUnpartitioned() ? null : key);
    try {
      for (long id : ids) {
        writer.write(record(schema, id, tsMicros, datepartition));
      }
      writer.close();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    DataFile file = writer.toDataFile();
    table.newAppend().appendFile(file).commit();
    IDS_BY_FILE.put(file.path().toString(), ids.clone());
    return file.path().toString();
  }

  /**
   * Deletes the rows {@code ids} by deletion vectors: one Puffin file for the call, with one vector
   * per data file that holds any of them, committed as a row delta. Each data file may get a vector
   * once (no vector here replaces an earlier one).
   */
  static void delete(Table table, Collection<Long> ids) {
    OutputFileFactory files =
        OutputFileFactory.builderFor(table, 2, TASK.incrementAndGet())
            .format(FileFormat.PUFFIN)
            .build();
    BaseDVFileWriter writer = new BaseDVFileWriter(files, path -> null);
    try (CloseableIterable<org.apache.iceberg.FileScanTask> tasks = table.newScan().planFiles()) {
      for (org.apache.iceberg.FileScanTask task : tasks) {
        long[] written = IDS_BY_FILE.get(task.file().path().toString());
        if (written == null) {
          continue;
        }
        for (int pos = 0; pos < written.length; pos++) {
          if (ids.contains(written[pos])) {
            writer.delete(task.file().path().toString(), pos, task.spec(), task.file().partition());
          }
        }
      }
      writer.close();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    RowDelta delta = table.newRowDelta();
    for (DeleteFile file : writer.result().deleteFiles()) {
      delta.addDeletes(file);
    }
    delta.commit();
  }

  /**
   * Every live row of the table (at {@code snapshotId}, or the current snapshot when null), read
   * with Iceberg's generic reader, as {@code id | v | other columns | _row_id |
   * _last_updated_sequence_number}, in id order. The variant is spelled by Iceberg's {@code
   * Variant.toString}.
   */
  static List<String> read(Table table, Long snapshotId) {
    Schema projection =
        TypeUtil.join(
            table.schema(),
            new Schema(MetadataColumns.ROW_ID, MetadataColumns.LAST_UPDATED_SEQUENCE_NUMBER));
    IcebergGenerics.ScanBuilder scan = IcebergGenerics.read(table).project(projection);
    if (snapshotId != null) {
      scan = scan.useSnapshot(snapshotId);
    }
    List<String> rows = new ArrayList<>();
    try (CloseableIterable<Record> records = scan.build()) {
      for (Record record : records) {
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < record.size(); i++) {
          row.append(i == 0 ? "" : " | ").append(record.get(i));
        }
        rows.add(row.toString());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    rows.sort(
        (a, b) ->
            Long.compare(
                Long.parseLong(a.substring(0, a.indexOf(' '))),
                Long.parseLong(b.substring(0, b.indexOf(' ')))));
    return rows;
  }
}
