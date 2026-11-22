package com.linkedin.openhouse.hts.catalog.data;

import com.linkedin.openhouse.hts.catalog.api.IcebergRow;
import com.linkedin.openhouse.hts.catalog.api.IcebergRowPrimaryKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.MetricsConfig;
import org.apache.iceberg.Schema;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.Table;
import org.apache.iceberg.avro.Avro;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.deletes.EqualityDeleteWriter;
import org.apache.iceberg.encryption.EncryptedOutputFile;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.OutputFileFactory;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.ArrayUtil;
import org.apache.iceberg.util.Pair;

/**
 * Internal Helper class to create DataFiles and DeleteFiles required for {@link
 * org.apache.iceberg.RowDelta} operations.
 *
 * <p>Supports row inserts, equality deletes, but not position deletes.
 *
 * <p>Assumptions: - Files generated are AVRO files (can be enhanced to support others) - Table is
 * un-partitioned (can be enhanced to support partitioned)
 */
public final class IcebergDataUtils {

  private IcebergDataUtils() {}

  /**
   * The equality-delete row schema for {@code icebergRowPrimaryKey}: the table's primary-key
   * columns.
   */
  private static Schema deleteSchema(Table table, IcebergRowPrimaryKey icebergRowPrimaryKey) {
    List<String> icebergRowPrimaryKeyColumnNames =
        icebergRowPrimaryKey.getSchema().columns().stream()
            .map(Types.NestedField::name)
            .collect(Collectors.toList());
    return table.schema().select(icebergRowPrimaryKeyColumnNames);
  }

  /*
   * The two writers below are what GenericAppenderFactory(schema, spec, equalityFieldIds,
   * eqDeleteRowSchema, null) built for AVRO, written against the Avro builders directly because
   * GenericAppenderFactory is gone from newer Iceberg. Both builders exist on every Iceberg line
   * this module compiles against.
   */
  private static EqualityDeleteWriter<Record> newEqualityDeleteWriter(
      Table table,
      IcebergRowPrimaryKey icebergRowPrimaryKey,
      EncryptedOutputFile outputFile,
      StructLike partition)
      throws IOException {
    Schema deleteSchema = deleteSchema(table, icebergRowPrimaryKey);
    List<Integer> deleteSchemaFieldIds =
        deleteSchema.columns().stream()
            .map(Types.NestedField::fieldId)
            .collect(Collectors.toList());
    return Avro.writeDeletes(outputFile)
        .createWriterFunc(org.apache.iceberg.data.avro.DataWriter::create)
        .withPartition(partition)
        .overwrite()
        .rowSchema(deleteSchema)
        .withSpec(table.spec())
        .withKeyMetadata(outputFile.keyMetadata())
        .equalityFieldIds(ArrayUtil.toIntArray(deleteSchemaFieldIds))
        .buildEqualityWriter();
  }

  private static DataWriter<Record> newDataWriter(
      Table table, EncryptedOutputFile outputFile, StructLike partition) throws IOException {
    return new DataWriter<>(
        Avro.write(outputFile)
            .schema(table.schema())
            .createWriterFunc(org.apache.iceberg.data.avro.DataWriter::create)
            .metricsConfig(MetricsConfig.fromProperties(Collections.emptyMap()))
            .overwrite()
            .build(),
        FileFormat.AVRO,
        outputFile.encryptingOutputFile().location(),
        table.spec(),
        partition,
        outputFile.keyMetadata());
  }

  /**
   * Create a delete file that will delete a row corresponding to the primary key ({@param
   * icebergRowPrimaryKey}) from the table ({@param table})
   *
   * @return new DeleteFile for the row identified by {@param icebergRowPrimaryKey}
   */
  public static DeleteFile createRowDeltaDeleteFile(
      Table table, IcebergRowPrimaryKey icebergRowPrimaryKey) {
    EncryptedOutputFile outputFile =
        OutputFileFactory.builderFor(
                table,
                ThreadLocalRandom.current().nextInt(),
                ThreadLocalRandom.current().nextLong())
            .format(FileFormat.AVRO)
            .build()
            .newOutputFile();
    StructLike partitionKey = null;
    if (!table.spec().isUnpartitioned()) {
      throw new UnsupportedOperationException(
          String.format(
              "Table %s is partitioned, deleting rows from partition table is not supported",
              table.name()));
    }
    EqualityDeleteWriter<Record> deleteWriter;
    try {
      deleteWriter = newEqualityDeleteWriter(table, icebergRowPrimaryKey, outputFile, partitionKey);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    deleteWriter.write(icebergRowPrimaryKey.getRecord());
    try {
      deleteWriter.close();
    } catch (IOException e) {
      throw new UncheckedIOException(
          String.format("Couldn't create delete file for table %s", table.name()), e);
    }
    return deleteWriter.toDeleteFile();
  }

  /**
   * Create a data file from a IcebergRow ({@param icebergRow}) that would be inserted to the table
   * ({@param table})
   *
   * <p>Also contains logic to set NextVersion in the IcebergRow upon update.
   *
   * @return Pair of DataFile: new DataFile for the row identified by {@param icebergRow}
   *     IcebergRow: updated IcebergRow with new version set
   */
  public static Pair<DataFile, IcebergRow> createRowDeltaDataFileWithNextVersion(
      Table table, IcebergRow icebergRow) {
    EncryptedOutputFile outputFile =
        OutputFileFactory.builderFor(
                table,
                ThreadLocalRandom.current().nextInt(),
                ThreadLocalRandom.current().nextLong())
            .format(FileFormat.AVRO)
            .build()
            .newOutputFile();
    StructLike partitionKey = null;
    if (!table.spec().isUnpartitioned()) {
      throw new UnsupportedOperationException(
          String.format(
              "Table %s is partitioned, inserting rows to partition table is not supported",
              table.name()));
    }
    DataWriter<Record> dataWriter;
    try {
      dataWriter = newDataWriter(table, outputFile, partitionKey);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    GenericRecord genericRecord = icebergRow.getRecord();
    genericRecord.setField(icebergRow.getVersionColumnName(), icebergRow.getNextVersion());

    IcebergRow updatedIceberg = icebergRow.getIcebergRowPrimaryKey().buildIcebergRow(genericRecord);
    dataWriter.write(genericRecord);
    try {
      dataWriter.close();
    } catch (IOException e) {
      throw new UncheckedIOException(
          String.format("Couldn't create data file for table %s", table.name()), e);
    }
    return Pair.of(dataWriter.toDataFile(), updatedIceberg);
  }

  /**
   * Utility used by {@link GenericIcebergRowReadersWriters#searchByPartialId(Table,
   * IcebergRowPrimaryKey)} to search the table for partial keys.
   *
   * <p>Example use case is as follows: Find all tables for a given databaseId in {@link
   * com.linkedin.openhouse.hts.catalog.model.usertable.UserTableIcebergRow}
   *
   * @return Expression to search for partial keys
   */
  static Expression createPartialSearchExpression(IcebergRowPrimaryKey icebergRowPrimaryKey) {
    return icebergRowPrimaryKey.getSchema().columns().stream()
        .map(Types.NestedField::name)
        .map(
            name -> {
              Object val = icebergRowPrimaryKey.getRecord().getField(name);
              if (val == null) {
                return Expressions.alwaysTrue();
              } else {
                return Expressions.equal(name, icebergRowPrimaryKey.getRecord().getField(name));
              }
            })
        .reduce(Expressions.alwaysTrue(), Expressions::and);
  }
}
