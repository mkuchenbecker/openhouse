package com.linkedin.openhouse.jobs.spark;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionKey;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.Table;
import org.apache.iceberg.data.GenericFileWriterFactory;
import org.apache.iceberg.data.InternalRecordWrapper;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.deletes.EqualityDeleteWriter;
import org.apache.iceberg.encryption.EncryptedOutputFile;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.OutputFileFactory;

/**
 * Data files and equality delete files written with Iceberg's own generic writers (this copy: the
 * Iceberg 1.12 line, {@code GenericFileWriterFactory}; the reference line has a same-named copy on
 * {@code GenericAppenderFactory}, which 1.12 removed). Spark writes neither a data file under a
 * partition spec other than the table's current one, nor equality deletes; an Iceberg API writer (a
 * REST client, Flink) does both. Files are Parquet; the caller commits them.
 */
final class IcebergFiles {
  private static final AtomicLong TASK = new AtomicLong();

  private IcebergFiles() {}

  /** The partition of {@code record} under {@code spec}, or null for an unpartitioned spec. */
  static StructLike partition(PartitionSpec spec, Schema schema, Record record) {
    if (spec.isUnpartitioned()) {
      return null;
    }
    PartitionKey key = new PartitionKey(spec, schema);
    key.partition(new InternalRecordWrapper(schema.asStruct()).wrap(record));
    return key;
  }

  private static EncryptedOutputFile outputFile(
      Table table, PartitionSpec spec, StructLike partition) {
    OutputFileFactory files =
        OutputFileFactory.builderFor(table, 7, TASK.incrementAndGet())
            .format(FileFormat.PARQUET)
            .build();
    return partition == null ? files.newOutputFile() : files.newOutputFile(spec, partition);
  }

  /** One Parquet data file of {@code records} (all in one partition of {@code spec}). */
  static DataFile writeData(Table table, PartitionSpec spec, List<Record> records) {
    StructLike partition = partition(spec, table.schema(), records.get(0));
    GenericFileWriterFactory factory =
        new GenericFileWriterFactory.Builder(table)
            .dataSchema(table.schema())
            .dataFileFormat(FileFormat.PARQUET)
            .build();
    DataWriter<Record> writer =
        factory.newDataWriter(outputFile(table, spec, partition), spec, partition);
    try {
      for (Record record : records) {
        writer.write(record);
      }
      writer.close();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return writer.toDataFile();
  }

  /**
   * One Parquet equality delete file in {@code partition} of {@code spec}, deleting every row of
   * that partition whose {@code deleteSchema} columns equal one of {@code keys}.
   */
  static DeleteFile writeEqualityDeletes(
      Table table,
      PartitionSpec spec,
      StructLike partition,
      Schema deleteSchema,
      List<Record> keys) {
    int[] fieldIds = deleteSchema.columns().stream().mapToInt(f -> f.fieldId()).toArray();
    GenericFileWriterFactory factory =
        new GenericFileWriterFactory.Builder(table)
            .deleteFileFormat(FileFormat.PARQUET)
            .equalityFieldIds(fieldIds)
            .equalityDeleteRowSchema(deleteSchema)
            .build();
    EqualityDeleteWriter<Record> writer =
        factory.newEqualityDeleteWriter(outputFile(table, spec, partition), spec, partition);
    try {
      for (Record key : keys) {
        writer.write(key);
      }
      writer.close();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return writer.toDeleteFile();
  }
}
