package com.linkedin.openhouse.jobs.spark;

import com.linkedin.openhouse.jobs.util.AppConstants;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Pure-Java unit tests for {@link BatchedTableDirectoryDeletionSparkApp#buildEntries}. Confirms the
 * dropped-table-directory scaffold parses its {@code --tableDirectoryPaths} CSV and enforces its
 * own {@link AppConstants#TABLE_DIRECTORY_DELETION_MAX_BATCH_SIZE} cap (distinct from the orphan
 * app's).
 */
public class BatchedTableDirectoryDeletionSparkAppArgsTest {

  @Test
  public void buildEntriesParsesDirectoryPaths() {
    List<BatchedMaintenanceSparkApp.BatchEntry> entries =
        BatchedTableDirectoryDeletionSparkApp.buildEntries(
            "/data/oh/db1/dropped-1,/data/oh/db2/dropped-2", null);

    Assertions.assertEquals(2, entries.size());
    Assertions.assertEquals("/data/oh/db1/dropped-1", entries.get(0).getFqtn());
    Assertions.assertEquals("db1", entries.get(0).getDatabaseName());
    Assertions.assertEquals("dropped-1", entries.get(0).getTableName());
  }

  @Test
  public void buildEntriesPairsParallelOperationIds() {
    List<BatchedMaintenanceSparkApp.BatchEntry> entries =
        BatchedTableDirectoryDeletionSparkApp.buildEntries(
            "/data/oh/db/d1,/data/oh/db/d2", "op-1,op-2");

    Assertions.assertEquals(Optional.of("op-1"), entries.get(0).getOperationId());
    Assertions.assertEquals(Optional.of("op-2"), entries.get(1).getOperationId());
  }

  @Test
  public void buildEntriesRejectsNullOrEmptyPaths() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> BatchedTableDirectoryDeletionSparkApp.buildEntries(null, null));
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> BatchedTableDirectoryDeletionSparkApp.buildEntries("", null));
  }

  @Test
  public void buildEntriesRejectsAboveMaxBatchSize() {
    String paths = generatePathCsv(AppConstants.TABLE_DIRECTORY_DELETION_MAX_BATCH_SIZE + 1);
    IllegalArgumentException ex =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> BatchedTableDirectoryDeletionSparkApp.buildEntries(paths, null));
    Assertions.assertTrue(
        ex.getMessage().contains("TABLE_DIRECTORY_DELETION_MAX_BATCH_SIZE"),
        "error should reference the constant name");
  }

  private static String generatePathCsv(int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      if (i > 0) sb.append(',');
      sb.append("/data/oh/db/dir").append(i);
    }
    return sb.toString();
  }
}
