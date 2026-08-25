package com.linkedin.openhouse.jobs.spark;

import com.linkedin.openhouse.jobs.util.AppConstants;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Pure-Java unit tests for {@link BatchedOrphanTableDirectoryDeletionSparkApp#buildEntries}. No
 * Spark session, no HTTP — exercises the directory-path CLI parsing that decides whether the app
 * can start. The batch unit is a filesystem directory path (not a {@code db.table}); {@code fqtn}
 * holds the path and {@code databaseName}/{@code tableName} are best-effort echo values.
 */
public class BatchedOrphanTableDirectoryDeletionSparkAppArgsTest {

  @Test
  public void buildEntriesParsesDirectoryPaths() {
    List<BatchedMaintenanceSparkApp.BatchEntry> entries =
        BatchedOrphanTableDirectoryDeletionSparkApp.buildEntries(
            "/data/oh/db1/uuid-dir-1,/data/oh/db2/uuid-dir-2", null);

    Assertions.assertEquals(2, entries.size());
    Assertions.assertEquals("/data/oh/db1/uuid-dir-1", entries.get(0).getFqtn());
    Assertions.assertEquals("db1", entries.get(0).getDatabaseName());
    Assertions.assertEquals("uuid-dir-1", entries.get(0).getTableName());
    Assertions.assertFalse(entries.get(0).getOperationId().isPresent());
    Assertions.assertFalse(entries.get(0).getTableUuid().isPresent());
    Assertions.assertEquals("/data/oh/db2/uuid-dir-2", entries.get(1).getFqtn());
    Assertions.assertEquals("db2", entries.get(1).getDatabaseName());
  }

  @Test
  public void buildEntriesPairsParallelOperationIds() {
    List<BatchedMaintenanceSparkApp.BatchEntry> entries =
        BatchedOrphanTableDirectoryDeletionSparkApp.buildEntries(
            "/data/oh/db/d1,/data/oh/db/d2", "op-1,op-2");

    Assertions.assertEquals(Optional.of("op-1"), entries.get(0).getOperationId());
    Assertions.assertEquals(Optional.of("op-2"), entries.get(1).getOperationId());
  }

  @Test
  public void buildEntriesTrimsWhitespace() {
    List<BatchedMaintenanceSparkApp.BatchEntry> entries =
        BatchedOrphanTableDirectoryDeletionSparkApp.buildEntries(
            " /data/oh/db/d1 , /data/oh/db/d2 ", " op-1 , op-2 ");

    Assertions.assertEquals("/data/oh/db/d1", entries.get(0).getFqtn());
    Assertions.assertEquals(Optional.of("op-1"), entries.get(0).getOperationId());
  }

  @Test
  public void buildEntriesRejectsNullOrEmptyPaths() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> BatchedOrphanTableDirectoryDeletionSparkApp.buildEntries(null, null));
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> BatchedOrphanTableDirectoryDeletionSparkApp.buildEntries("", null));
  }

  @Test
  public void buildEntriesRejectsMismatchedOperationIdLength() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            BatchedOrphanTableDirectoryDeletionSparkApp.buildEntries(
                "/data/oh/db/d1,/data/oh/db/d2", "op-1"));
  }

  @Test
  public void buildEntriesAcceptsAtMaxBatchSize() {
    String paths = generatePathCsv(AppConstants.ORPHAN_DIRECTORY_DELETION_MAX_BATCH_SIZE);
    List<BatchedMaintenanceSparkApp.BatchEntry> entries =
        BatchedOrphanTableDirectoryDeletionSparkApp.buildEntries(paths, null);
    Assertions.assertEquals(AppConstants.ORPHAN_DIRECTORY_DELETION_MAX_BATCH_SIZE, entries.size());
  }

  @Test
  public void buildEntriesRejectsAboveMaxBatchSize() {
    String paths = generatePathCsv(AppConstants.ORPHAN_DIRECTORY_DELETION_MAX_BATCH_SIZE + 1);
    IllegalArgumentException ex =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> BatchedOrphanTableDirectoryDeletionSparkApp.buildEntries(paths, null));
    Assertions.assertTrue(
        ex.getMessage().contains("ORPHAN_DIRECTORY_DELETION_MAX_BATCH_SIZE"),
        "error should reference the constant name");
  }

  // --- buildDatabaseEntries (optimizer database-scoped dispatch) ---

  @Test
  public void buildDatabaseEntriesParsesDatabases() {
    List<BatchedMaintenanceSparkApp.BatchEntry> entries =
        BatchedOrphanTableDirectoryDeletionSparkApp.buildDatabaseEntries(
            "db1,db2", "op-1,op-2", AppConstants.ORPHAN_DIRECTORY_DELETION_MAX_BATCH_SIZE, "CAP");

    Assertions.assertEquals(2, entries.size());
    Assertions.assertEquals("db1", entries.get(0).getFqtn());
    Assertions.assertEquals("db1", entries.get(0).getDatabaseName());
    Assertions.assertEquals(Optional.of("op-1"), entries.get(0).getOperationId());
    Assertions.assertFalse(entries.get(0).getTableUuid().isPresent());
    Assertions.assertEquals("db2", entries.get(1).getDatabaseName());
  }

  @Test
  public void buildDatabaseEntriesRejectsNullOrEmpty() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            BatchedOrphanTableDirectoryDeletionSparkApp.buildDatabaseEntries(
                null, null, AppConstants.ORPHAN_DIRECTORY_DELETION_MAX_BATCH_SIZE, "CAP"));
  }

  @Test
  public void buildDatabaseEntriesRejectsMismatchedOperationIds() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            BatchedOrphanTableDirectoryDeletionSparkApp.buildDatabaseEntries(
                "db1,db2", "op-1", AppConstants.ORPHAN_DIRECTORY_DELETION_MAX_BATCH_SIZE, "CAP"));
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
