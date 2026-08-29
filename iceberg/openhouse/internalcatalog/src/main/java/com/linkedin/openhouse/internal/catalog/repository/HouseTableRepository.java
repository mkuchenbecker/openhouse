package com.linkedin.openhouse.internal.catalog.repository;

import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import java.util.List;
import java.util.Optional;
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
   * Point read of a <b>view</b> row.
   *
   * <p>Separate from {@link #findById} because House Tables scopes reads by entity type: the
   * inherited {@code findById} resolves through the table-scoped route, where a view at the key
   * reads as absent. Calling it for a view would make a freshly created view invisible to its own
   * refresh, so the view path has its own read.
   *
   * @param key database and view identifier
   * @return the view row, or empty if no view holds that key (a table there is also empty)
   */
  Optional<HouseTable> findViewById(HouseTablePrimaryKey key);

  /**
   * Writes a <b>view</b> row, with the same compare-and-swap semantics as {@link #save}: House
   * Tables rejects the write when the row's {@code tableVersion} does not match what it holds.
   *
   * @param entity the view row to persist
   * @return the persisted row as House Tables returned it
   */
  HouseTable saveView(HouseTable entity);

  /**
   * Deletes a <b>view</b> row.
   *
   * <p>There is no purge flag: soft delete is a table-only concept in House Tables, so a dropped
   * view is gone rather than retained.
   *
   * @param key database and view identifier
   */
  void deleteViewById(HouseTablePrimaryKey key);

  /**
   * Every view in a database, in House Tables' order.
   *
   * @param databaseId the database to list
   * @return view rows only; tables in the same database are not returned
   */
  List<HouseTable> findAllViewsByDatabaseId(String databaseId);

  /**
   * Delete a table by its primary key with purge option
   *
   * @param houseTablePrimaryKey the primary key of the table
   * @param purge true if table should be deleted permanently, otherwise retain with soft delete
   */
  void deleteById(HouseTablePrimaryKey houseTablePrimaryKey, boolean purge);

  Page<HouseTable> findAllByDatabaseId(String databaseId, Pageable pageable);

  void rename(
      String fromDatabaseId,
      String fromTableId,
      String toDatabaseId,
      String toTableId,
      String metadataLocation);

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
