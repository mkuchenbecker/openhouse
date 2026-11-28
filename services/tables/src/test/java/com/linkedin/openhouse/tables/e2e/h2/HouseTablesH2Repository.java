package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.INITIAL_TABLE_VERSION;
import static com.linkedin.openhouse.internal.catalog.CatalogConstants.ENTITY_TYPE_TABLE;
import static com.linkedin.openhouse.internal.catalog.CatalogConstants.ENTITY_TYPE_VIEW;

import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.model.SoftDeletedTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableConcurrentUpdateException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The {@link org.springframework.context.annotation.Bean} injected into /tables e2e tests when
 * communication to the implementation of {@link HouseTableRepository} is not needed. With {@link
 * Primary} annotation, this repository will be the default injection.
 *
 * <p>Faithful to the real store (HouseTableRepositoryImpl calling UserTablesServiceImpl in the
 * house tables service) in three respects:
 *
 * <ol>
 *   <li>Save is a compare-and-swap on the metadata location. The incoming {@code tableVersion} is
 *       the location the writer built on. With no row at the key it must be INITIAL_VERSION; with a
 *       row it must equal the row's current {@code tableLocation}. Anything else is a 409 in the
 *       real store, which the client surfaces as {@link HouseTableConcurrentUpdateException} and
 *       the commit path turns into a CommitFailedException (HTTP 409). Any occupant of the key
 *       counts, whatever its entity type. Rename and restore carry no version check there either.
 *   <li>Rename: tables and views share the key, so a rename onto a key any entity holds is refused
 *       with {@link HouseTableConcurrentUpdateException}, as the real store's 409 is, and changes
 *       nothing.
 *   <li>Delete: {@code deleteById(key, purge)}. Purge (what a tables-service DELETE sends) is a
 *       hard delete. Not purge is a soft delete: the row moves to the soft-deleted store, stamped
 *       with {@code deletedAtMs} = now and {@code purgeAfterMs} = now + 7 days, which are set by
 *       the house tables service, not the caller. Nothing in the tables service HTTP surface
 *       soft-deletes; only the soft-deleted listing, restore and purge are exposed.
 * </ol>
 */
@Repository
@Primary
public interface HouseTablesH2Repository extends HouseTableRepository {

  Map<SoftDeletedTablePrimaryKey, HouseTable> softDeletedTables = new HashMap<>();

  /** Soft-deleted rows are kept for 7 days, as in the house tables service. */
  long SOFT_DELETE_RETENTION_MS = 7L * 24 * 60 * 60 * 1000;

  /** Makes the read-compare-write of {@link #save} atomic, as a row version does in the store. */
  ReentrantLock SAVE_LOCK = new ReentrantLock();

  /**
   * The stored metadata location of whatever occupies the key. A scalar, so it reads the row and
   * not the entity a request-scoped persistence context may already hold from before a concurrent
   * commit.
   */
  @Query(
      "select h.tableLocation from HouseTable h where h.databaseId = :databaseId and h.tableId = :tableId")
  Optional<String> findStoredLocation(
      @Param("databaseId") String databaseId, @Param("tableId") String tableId);

  /** Routed by Spring Data to the plain JPA write; {@link #save} adds the version check. */
  <S extends HouseTable> S saveAndFlush(S entity);

  /* Default bodies throughout: Spring Data would derive a query from any abstract method name,
   * and none of these predicates is derivable. */

  Optional<HouseTable> findByDatabaseIdAndTableId(String databaseId, String tableId);

  /** Untyped, so a typed list can filter before it paginates. */
  List<HouseTable> findByDatabaseId(String databaseId);

  /**
   * Sorting stays in the derived query; a comparator would drop the SQL-side {@link Sort} parts.
   */
  List<HouseTable> findByDatabaseId(String databaseId, Sort sort);

  /**
   * The rows of a database by any spelling of its name, as House Tables' table listing compares the
   * upper-cased names.
   */
  List<HouseTable> findByDatabaseIdIgnoreCase(String databaseId);

  List<HouseTable> findByDatabaseIdIgnoreCase(String databaseId, Sort sort);

  /** Mirrors the column converter: a pre-discriminator row is a table. */
  static HouseTable hydrateEntityType(HouseTable houseTable) {
    return houseTable.getEntityType() == null
        ? houseTable.toBuilder().entityType(ENTITY_TYPE_TABLE).build()
        : houseTable;
  }

  /** The null arm is load-bearing: legacy rows are tables. */
  static boolean isTableOrLegacy(HouseTable houseTable) {
    return houseTable.getEntityType() == null
        || ENTITY_TYPE_TABLE.equalsIgnoreCase(houseTable.getEntityType());
  }

  static boolean isView(HouseTable houseTable) {
    return ENTITY_TYPE_VIEW.equalsIgnoreCase(houseTable.getEntityType());
  }

  /** Slices already-ordered rows, so filtering in Java cannot make page two arbitrary. */
  static Page<HouseTable> pageOf(List<HouseTable> sortedRows, Pageable pageable) {
    int page = pageable.getPageNumber();
    int size = pageable.getPageSize();
    List<HouseTable> pageContent =
        sortedRows.subList(
            Math.min(page * size, sortedRows.size()),
            Math.min((page + 1) * size, sortedRows.size()));
    return new PageImpl<>(pageContent, pageable, sortedRows.size());
  }

  /**
   * Compare-and-swap on the metadata location; see the class comment. Spring Data runs this inside
   * the repository's transaction, so the lock is held until that transaction completes: releasing
   * it on return would let a second saver read the row before the first one commits.
   */
  @Override
  default <S extends HouseTable> S save(S entity) {
    SAVE_LOCK.lock();
    boolean inTransaction = TransactionSynchronizationManager.isSynchronizationActive();
    if (inTransaction) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              SAVE_LOCK.unlock();
            }
          });
    }
    try {
      String current =
          this.findStoredLocation(entity.getDatabaseId(), entity.getTableId())
              .orElse(INITIAL_TABLE_VERSION);
      if (!current.equals(entity.getTableVersion())) {
        throw new HouseTableConcurrentUpdateException(
            String.format(
                "databaseId : %s, tableId : %s, version: %s The requested user table has been"
                    + " modified/created by other processes.",
                entity.getDatabaseId(), entity.getTableId(), entity.getTableVersion()),
            null);
      }
      return this.saveAndFlush(stored(entity));
    } finally {
      if (!inTransaction) {
        SAVE_LOCK.unlock();
      }
    }
  }

  /**
   * The store keeps no version of its own: it reports the current metadata location as the table
   * version, so a row read back and saved again carries the version a writer would build on.
   */
  @SuppressWarnings("unchecked")
  static <S extends HouseTable> S stored(S entity) {
    return (S) entity.toBuilder().tableVersion(entity.getTableLocation()).build();
  }

  /** A view at a shared key is absent here, keeping it out of every table path. */
  @Override
  default Optional<HouseTable> findById(HouseTablePrimaryKey houseTablePrimaryKey) {
    return this.findByDatabaseIdAndTableId(
            houseTablePrimaryKey.getDatabaseId(), houseTablePrimaryKey.getTableId())
        .filter(HouseTablesH2Repository::isTableOrLegacy)
        .map(HouseTablesH2Repository::hydrateEntityType);
  }

  /** Filtered before the page is cut, so a view never consumes a slot or inflates a total. */
  @Override
  default List<HouseTable> findAllByDatabaseId(String databaseId) {
    return this.findByDatabaseIdIgnoreCase(databaseId).stream()
        .filter(HouseTablesH2Repository::isTableOrLegacy)
        .map(HouseTablesH2Repository::hydrateEntityType)
        .collect(Collectors.toList());
  }

  @Override
  default Page<HouseTable> findAllByDatabaseId(String databaseId, Pageable pageable) {
    return pageOf(
        this.findByDatabaseIdIgnoreCase(databaseId, pageable.getSort()).stream()
            .filter(HouseTablesH2Repository::isTableOrLegacy)
            .map(HouseTablesH2Repository::hydrateEntityType)
            .collect(Collectors.toList()),
        pageable);
  }

  @Override
  default Optional<HouseTable> findEntityById(HouseTablePrimaryKey houseTablePrimaryKey) {
    return this.findByDatabaseIdAndTableId(
            houseTablePrimaryKey.getDatabaseId(), houseTablePrimaryKey.getTableId())
        .map(HouseTablesH2Repository::hydrateEntityType);
  }

  /** Queries the shared key space directly; the table read would find nothing. */
  @Override
  default Optional<HouseTable> findViewById(HouseTablePrimaryKey houseTablePrimaryKey) {
    return this.findByDatabaseIdAndTableId(
            houseTablePrimaryKey.getDatabaseId(), houseTablePrimaryKey.getTableId())
        .filter(HouseTablesH2Repository::isView);
  }

  @Override
  default Page<HouseTable> findAllViewsByDatabaseId(String databaseId, Pageable pageable) {
    return pageOf(
        this.findByDatabaseId(databaseId, pageable.getSort()).stream()
            .filter(HouseTablesH2Repository::isView)
            .collect(Collectors.toList()),
        pageable);
  }

  @Override
  default HouseTable saveView(HouseTable houseTable) {
    // Mirrors House Table stamping the type from the route.
    return this.save(houseTable.toBuilder().entityType(ENTITY_TYPE_VIEW).build());
  }

  @Override
  default boolean deleteViewById(HouseTablePrimaryKey houseTablePrimaryKey) {
    if (!this.findViewById(houseTablePrimaryKey).isPresent()) {
      return false;
    }
    this.deleteById(houseTablePrimaryKey);
    return true;
  }

  @Override
  default void rename(
      String fromDatabaseId,
      String fromTableId,
      String toDatabaseId,
      String toTableId,
      String metadataLocation) {
    HouseTablePrimaryKey fromKey =
        HouseTablePrimaryKey.builder().databaseId(fromDatabaseId).tableId(fromTableId).build();
    HouseTablePrimaryKey toKey =
        HouseTablePrimaryKey.builder().databaseId(toDatabaseId).tableId(toTableId).build();
    // Tables and views share the key, as in the real store, whose rename updates the row's primary
    // key: an occupied destination of either type is a key violation there (409), which its client
    // raises as HouseTableConcurrentUpdateException, and neither row changes.
    if (this.findEntityById(toKey)
        .filter(
            occupant ->
                !(occupant.getDatabaseId().equalsIgnoreCase(fromDatabaseId)
                    && occupant.getTableId().equalsIgnoreCase(fromTableId)))
        .isPresent()) {
      throw new HouseTableConcurrentUpdateException(
          String.format(
              "Cannot rename %s.%s to %s.%s: the key is held by another entity",
              fromDatabaseId, fromTableId, toDatabaseId, toTableId),
          null);
    }
    this.findById(fromKey)
        .ifPresent(
            houseTable -> {
              HouseTable renamedTable =
                  houseTable
                      .toBuilder()
                      .databaseId(toDatabaseId)
                      .tableId(toTableId)
                      .tableLocation(metadataLocation)
                      .build();
              this.saveAndFlush(stored(renamedTable));
              this.delete(houseTable);
            });
  }

  /**
   * The real client sends {@code !purge} as the soft-delete flag, so {@code purge} is a hard
   * delete.
   */
  @Override
  default void deleteById(HouseTablePrimaryKey houseTablePrimaryKey, boolean purge) {
    Optional<HouseTable> existing = this.findById(houseTablePrimaryKey);
    if (existing.isPresent()) {
      if (!purge) {
        long deletedAtMs = System.currentTimeMillis();
        SoftDeletedTablePrimaryKey key =
            SoftDeletedTablePrimaryKey.builder()
                .databaseId(houseTablePrimaryKey.getDatabaseId())
                .tableId(houseTablePrimaryKey.getTableId())
                .deletedAtMs(deletedAtMs)
                .build();
        softDeletedTables.put(
            key,
            existing
                .get()
                .toBuilder()
                .deletedAtMs(deletedAtMs)
                .purgeAfterMs(deletedAtMs + SOFT_DELETE_RETENTION_MS)
                .build());
      }
      deleteById(houseTablePrimaryKey);
    }
  }

  default Page<HouseTable> searchSoftDeletedTables(
      String databaseId, String tableId, Pageable pageable) {
    List<HouseTable> foundTables = new ArrayList<>();
    for (HouseTable table : softDeletedTables.values()) {
      if (table.getDatabaseId().equals(databaseId)) {
        if (tableId != null && !table.getTableId().equalsIgnoreCase(tableId)) {
          continue; // Filter by tableId if provided
        }
        foundTables.add(table);
      }
    }
    int page = pageable.getPageNumber();
    int size = pageable.getPageSize();
    List<HouseTable> pageContent =
        foundTables.subList(
            Math.min(page * size, foundTables.size()),
            Math.min((page + 1) * size, foundTables.size()));
    int numPages = (int) Math.ceil((double) foundTables.size() / size); // make sure at least 1
    return new PageImpl<>(
        pageContent, PageRequest.of(page, numPages == 0 ? 1 : numPages), foundTables.size());
  }

  default void purgeSoftDeletedTables(String databaseId, String tableId, long purgeAfterMs) {
    // Mock the purge logic on HTS for soft deleted tables
    softDeletedTables
        .entrySet()
        .removeIf(
            entry ->
                entry.getKey().getTableId().equals(tableId)
                    && entry.getKey().getDatabaseId().equals(databaseId)
                    && entry.getValue().getPurgeAfterMs() < purgeAfterMs);
  }

  default void restoreTable(String databaseId, String tableId, long deletedAtMs) {
    SoftDeletedTablePrimaryKey key =
        SoftDeletedTablePrimaryKey.builder()
            .databaseId(databaseId)
            .tableId(tableId)
            .deletedAtMs(deletedAtMs)
            .build();

    if (softDeletedTables.containsKey(key)) {
      HouseTable restoredTable = softDeletedTables.remove(key);
      // Restore the table to the main repository
      this.saveAndFlush(stored(restoredTable.toBuilder().deletedAtMs(0).purgeAfterMs(0).build()));
    } else {
      // Throw NoSuchUserTableException when table is not found in soft deleted tables
      throw new com.linkedin.openhouse.common.exception.NoSuchUserTableException(
          databaseId, tableId);
    }
  }
}
