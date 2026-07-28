package com.linkedin.openhouse.jobs.spark;

import com.linkedin.openhouse.jobs.util.AppConstants;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Pure-Java unit tests for {@link BatchedDataCompactionSparkApp#buildEntries}. No Spark session, no
 * HTTP — exercises the CLI-parsing edges that decide whether the app can even start, and confirms
 * the data-compaction batch-size cap is enforced (and named) independently of the OFD cap.
 */
public class BatchedDataCompactionSparkAppArgsTest {

  @Test
  public void buildEntriesParsesParallelLists() {
    List<BatchedDataCompactionSparkApp.BatchEntry> entries =
        BatchedDataCompactionSparkApp.buildEntries("db1.t1,db2.t2", "op-1,op-2", "uuid-1,uuid-2");

    Assertions.assertEquals(2, entries.size());
    Assertions.assertEquals("db1.t1", entries.get(0).getFqtn());
    Assertions.assertEquals("db1", entries.get(0).getDatabaseName());
    Assertions.assertEquals("t1", entries.get(0).getTableName());
    Assertions.assertEquals(Optional.of("op-1"), entries.get(0).getOperationId());
    Assertions.assertEquals(Optional.of("uuid-1"), entries.get(0).getTableUuid());
    Assertions.assertEquals("db2.t2", entries.get(1).getFqtn());
    Assertions.assertEquals(Optional.of("op-2"), entries.get(1).getOperationId());
  }

  @Test
  public void buildEntriesTrimsWhitespaceInEachEntry() {
    List<BatchedDataCompactionSparkApp.BatchEntry> entries =
        BatchedDataCompactionSparkApp.buildEntries(
            " db1.t1 , db2.t2 ", " op-1 , op-2 ", " uuid-1 , uuid-2 ");

    Assertions.assertEquals("db1.t1", entries.get(0).getFqtn());
    Assertions.assertEquals(Optional.of("op-1"), entries.get(0).getOperationId());
    Assertions.assertEquals(Optional.of("uuid-1"), entries.get(0).getTableUuid());
  }

  @Test
  public void buildEntriesRejectsMismatchedLengths() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> BatchedDataCompactionSparkApp.buildEntries("db.a,db.b", "op-1", "uuid-1,uuid-2"));
  }

  @Test
  public void buildEntriesRejectsNullOrEmptyTableNames() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> BatchedDataCompactionSparkApp.buildEntries(null, "op-1", "uuid-1"));
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> BatchedDataCompactionSparkApp.buildEntries("", "op-1", "uuid-1"));
  }

  @Test
  public void buildEntriesAllowsAbsentOperationIdsAndTableUuids() {
    List<BatchedDataCompactionSparkApp.BatchEntry> entriesNull =
        BatchedDataCompactionSparkApp.buildEntries("db.a,db.b", null, null);
    List<BatchedDataCompactionSparkApp.BatchEntry> entriesEmpty =
        BatchedDataCompactionSparkApp.buildEntries("db.a,db.b", "", "");

    for (List<BatchedDataCompactionSparkApp.BatchEntry> entries :
        java.util.Arrays.asList(entriesNull, entriesEmpty)) {
      Assertions.assertEquals(2, entries.size());
      Assertions.assertEquals("db.a", entries.get(0).getFqtn());
      Assertions.assertFalse(entries.get(0).getOperationId().isPresent());
      Assertions.assertFalse(entries.get(0).getTableUuid().isPresent());
      Assertions.assertEquals("db.b", entries.get(1).getFqtn());
    }
  }

  @Test
  public void buildEntriesRejectsNonFqtn() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> BatchedDataCompactionSparkApp.buildEntries("just_a_table", "op-1", "uuid-1"));
  }

  @Test
  public void buildEntriesAcceptsAtMaxBatchSize() {
    String tableNames = generateFqtnCsv(AppConstants.DATA_COMPACTION_MAX_BATCH_SIZE);
    List<BatchedDataCompactionSparkApp.BatchEntry> entries =
        BatchedDataCompactionSparkApp.buildEntries(tableNames, null, null);
    Assertions.assertEquals(AppConstants.DATA_COMPACTION_MAX_BATCH_SIZE, entries.size());
  }

  @Test
  public void buildEntriesRejectsAboveMaxBatchSize() {
    String tableNames = generateFqtnCsv(AppConstants.DATA_COMPACTION_MAX_BATCH_SIZE + 1);
    IllegalArgumentException ex =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> BatchedDataCompactionSparkApp.buildEntries(tableNames, null, null));
    Assertions.assertTrue(
        ex.getMessage().contains("DATA_COMPACTION_MAX_BATCH_SIZE"),
        "error should reference the constant name");
  }

  private static String generateFqtnCsv(int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      if (i > 0) sb.append(',');
      sb.append("db.t").append(i);
    }
    return sb.toString();
  }
}
