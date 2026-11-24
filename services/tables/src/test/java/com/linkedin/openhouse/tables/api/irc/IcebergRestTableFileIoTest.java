package com.linkedin.openhouse.tables.api.irc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.linkedin.openhouse.cluster.storage.StorageType;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Which per-table {@code io-impl} a table is given, for each pair of the table's storage type and
 * the cluster's default (work item V20). A table is given its own storage's class exactly when that
 * class is known and is not the class the config route advertises for the default.
 */
class IcebergRestTableFileIoTest {

  private static final StorageType.Type TAPE = new StorageType.Type("tape");

  private static Map<String, String> ioImpl(String fileIo) {
    return Collections.singletonMap("io-impl", fileIo);
  }

  @Test
  void aTableOnTheDefaultStorageGetsNoEntry() {
    for (StorageType.Type type :
        new StorageType.Type[] {
          StorageType.HDFS, StorageType.LOCAL, StorageType.S3, StorageType.ADLS, TAPE
        }) {
      assertEquals(
          Collections.emptyMap(), IcebergRestTableFileIo.config(type, type), type.getValue());
    }
  }

  @Test
  void aTableOnAStorageWithTheDefaultsClassGetsNoEntry() {
    assertEquals(
        Collections.emptyMap(), IcebergRestTableFileIo.config(StorageType.LOCAL, StorageType.HDFS));
    assertEquals(
        Collections.emptyMap(), IcebergRestTableFileIo.config(StorageType.HDFS, StorageType.LOCAL));
  }

  @Test
  void aTableOnAStorageWithAnotherClassGetsItsOwnClass() {
    String hadoop = "org.apache.iceberg.hadoop.HadoopFileIO";
    String s3 = "org.apache.iceberg.aws.s3.S3FileIO";
    String adls = "org.apache.iceberg.azure.adlsv2.ADLSFileIO";
    assertEquals(ioImpl(hadoop), IcebergRestTableFileIo.config(StorageType.LOCAL, StorageType.S3));
    assertEquals(ioImpl(hadoop), IcebergRestTableFileIo.config(StorageType.HDFS, StorageType.ADLS));
    assertEquals(ioImpl(s3), IcebergRestTableFileIo.config(StorageType.S3, StorageType.HDFS));
    assertEquals(ioImpl(s3), IcebergRestTableFileIo.config(StorageType.S3, StorageType.ADLS));
    assertEquals(ioImpl(adls), IcebergRestTableFileIo.config(StorageType.ADLS, StorageType.LOCAL));
    assertEquals(ioImpl(adls), IcebergRestTableFileIo.config(StorageType.ADLS, StorageType.S3));
    // The default has no known class, so the catalog has none: the table's class is still given.
    assertEquals(ioImpl(s3), IcebergRestTableFileIo.config(StorageType.S3, TAPE));
  }

  /** A storage type with no known client class gets no entry rather than a wrong one. */
  @Test
  void aTableOnAStorageWithNoKnownClassGetsNoEntry() {
    for (StorageType.Type defaultType :
        new StorageType.Type[] {
          StorageType.HDFS, StorageType.LOCAL, StorageType.S3, StorageType.ADLS
        }) {
      assertEquals(
          Collections.emptyMap(),
          IcebergRestTableFileIo.config(TAPE, defaultType),
          defaultType.getValue());
    }
  }
}
