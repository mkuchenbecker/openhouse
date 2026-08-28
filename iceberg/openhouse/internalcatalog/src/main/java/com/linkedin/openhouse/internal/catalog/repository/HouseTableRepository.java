package com.linkedin.openhouse.internal.catalog.repository;

import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableConcurrentUpdateException;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

/**
 * Base interface for repository backed by HouseTableService for storing and retrieving {@link
 * HouseTable} object.
 */
@Repository
public interface HouseTableRepository
    extends PagingAndSortingRepository<HouseTable, HouseTablePrimaryKey> {

  List<HouseTable> findAllByDatabaseId(String databaseId);

  /**
   * Delete a table by its primary key with purge option
   *
   * @param houseTablePrimaryKey the primary key of the table
   * @param purge true if table should be deleted permanently, otherwise retain with soft delete
   */
  void deleteById(HouseTablePrimaryKey houseTablePrimaryKey, boolean purge);

  Page<HouseTable> findAllByDatabaseId(String databaseId, Pageable pageable);

  /**
   * Rename a table, updating its metadata location.
   *
   * @param fromDatabaseId databaseId of the table to rename
   * @param fromTableId tableId of the table to rename
   * @param toDatabaseId destination databaseId
   * @param toTableId destination tableId
   * @param metadataLocation the new metadata file reflecting the renamed identifiers
   * @param expectedMetadataLocation the metadata location the caller observed when it initiated the
   *     rename (optimistic-concurrency token). When non-null, the rename fails with a concurrent
   *     update conflict if the table has advanced past this location, instead of silently
   *     overwriting the newer metadata. May be null when the caller has no base to declare.
   * @throws HouseTableConcurrentUpdateException if the table advanced past {@code
   *     expectedMetadataLocation} before the rename landed. Every implementation must signal the
   *     conflict with this type: it is what {@code OpenHouseInternalTableOperations.doCommit}
   *     catches to convert the conflict into a retriable {@code CommitFailedException}. A conflict
   *     raised as any other unchecked type escapes that catch and surfaces as a non-retriable
   *     failure.
   */
  void rename(
      String fromDatabaseId,
      String fromTableId,
      String toDatabaseId,
      String toTableId,
      String metadataLocation,
      String expectedMetadataLocation);

  /**
   * Find all soft-deleted tables by database ID with pagination and optional filtering
   *
   * @param databaseId The database ID to filter by
   * @param tableId The table ID to filter by (optional, can be null)
   * @param pageable Pagination information
   * @return List of soft-deleted HouseTable objects matching the criteria
   */
  Page<HouseTable> searchSoftDeletedTables(String databaseId, String tableId, Pageable pageable);

  /**
   * Delete soft-deleted tables that are older than the specified timestamp.
   *
   * @param databaseId
   * @param tableId
   * @param purgeAfterMs timestamp in milliseconds where tables older than this will be permanently
   *     deleted
   */
  void purgeSoftDeletedTables(String databaseId, String tableId, long purgeAfterMs);

  /**
   * Restore a soft deleted table
   *
   * @param databaseId The database ID
   * @param tableId The table ID
   * @param deletedAtMs The timestamp when the table was deleted
   */
  void restoreTable(String databaseId, String tableId, long deletedAtMs);
}
