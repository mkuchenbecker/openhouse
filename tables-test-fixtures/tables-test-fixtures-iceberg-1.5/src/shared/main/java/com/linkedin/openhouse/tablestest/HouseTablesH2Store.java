package com.linkedin.openhouse.tablestest;

import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.model.SoftDeletedTablePrimaryKey;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * The operations {@link HouseTablesH2StoreImpl} implements for {@link HouseTablesH2Repository}:
 * every {@code HouseTableRepository} operation the tables service calls, the CRUD methods it calls,
 * and two helpers for tests. Spring Data routes a call of any method declared here to the
 * implementation, ahead of its own.
 */
public interface HouseTablesH2Store {

  <S extends HouseTable> S save(S entity);

  HouseTable saveView(HouseTable houseTable);

  Optional<HouseTable> findById(HouseTablePrimaryKey key);

  Optional<HouseTable> findEntityById(HouseTablePrimaryKey key);

  Optional<HouseTable> findViewById(HouseTablePrimaryKey key);

  /** Not served by the real repository; here for tests, ignoring letter case as every read does. */
  boolean existsById(HouseTablePrimaryKey key);

  List<HouseTable> findAllByDatabaseId(String databaseId);

  Page<HouseTable> findAllByDatabaseId(String databaseId, Pageable pageable);

  Page<HouseTable> findAllViewsByDatabaseId(String databaseId, Pageable pageable);

  /** The database list: one row per database, naming only the database. */
  Iterable<HouseTable> findAll();

  Page<HouseTable> findAll(Pageable pageable);

  void deleteById(HouseTablePrimaryKey key);

  void deleteById(HouseTablePrimaryKey key, boolean purge);

  /** Refused, as by the real repository. */
  void delete(HouseTable entity);

  /**
   * Not served by the real repository; empties this store, soft-deleted rows included, for tests.
   */
  void deleteAll();

  boolean deleteViewById(HouseTablePrimaryKey key);

  void rename(
      String fromDatabaseId,
      String fromTableId,
      String toDatabaseId,
      String toTableId,
      String metadataLocation);

  Page<HouseTable> searchSoftDeletedTables(String databaseId, String tableId, Pageable pageable);

  void purgeSoftDeletedTables(String databaseId, String tableId, long purgeAfterMs);

  void restoreTable(String databaseId, String tableId, long deletedAtMs);

  /** Refused, as by the real repository. */
  <S extends HouseTable> Iterable<S> saveAll(Iterable<S> entities);

  /** Refused, as by the real repository. */
  long count();

  /** Refused, as by the real repository. */
  Iterable<HouseTable> findAll(Sort sort);

  /** Refused, as by the real repository. */
  Iterable<HouseTable> findAllById(Iterable<HouseTablePrimaryKey> keys);

  /** Refused, as by the real repository. */
  void deleteAllById(Iterable<? extends HouseTablePrimaryKey> keys);

  /** Refused, as by the real repository. */
  void deleteAll(Iterable<? extends HouseTable> entities);

  /**
   * This store's soft-deleted rows, live, for tests that seed or inspect them. One store per
   * application context: two services in one JVM do not share it.
   */
  Map<SoftDeletedTablePrimaryKey, HouseTable> softDeletedTables();
}
