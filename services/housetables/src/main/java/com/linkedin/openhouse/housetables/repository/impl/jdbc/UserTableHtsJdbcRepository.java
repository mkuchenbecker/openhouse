package com.linkedin.openhouse.housetables.repository.impl.jdbc;

import com.linkedin.openhouse.housetables.config.db.jdbc.JdbcProviderConfiguration;
import com.linkedin.openhouse.housetables.model.UserTableRow;
import com.linkedin.openhouse.housetables.model.UserTableRowPrimaryKey;
import com.linkedin.openhouse.housetables.repository.HtsRepository;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC-backed {@link HtsRepository} for CRUDing {@link UserTableRow}
 *
 * <p>This class gets configured in {@link
 * com.linkedin.openhouse.housetables.config.db.DatabaseConfiguration} with @EnableJpaRepositories.
 * The datasource for the Jpa repository is provided in {@link JdbcProviderConfiguration}.
 */
public interface UserTableHtsJdbcRepository
    extends HtsRepository<UserTableRow, UserTableRowPrimaryKey> {
  /**
   * Look up the entity in a case-insensitive way as a framework-provided feature. Details: 1. All
   * keys required in lookup need to be explicitly added in the arguments. Composite keys doesn't
   * work. 2. When naming the method, all keys that are used to looked-up in a case-insensitive way
   * need to be postfixed with `ignoreCase` explicitly.
   *
   * @param databaseId
   * @param tableId
   * @return The object {@link UserTableRow} looked-up in a case-insensitive way.
   */
  Optional<UserTableRow> findByDatabaseIdIgnoreCaseAndTableIdIgnoreCase(
      String databaseId, String tableId);

  boolean existsByDatabaseIdIgnoreCaseAndTableIdIgnoreCase(String databaseId, String tableId);

  void deleteByDatabaseIdIgnoreCaseAndTableIdIgnoreCase(String databaseId, String tableId);

  @Query("SELECT DISTINCT databaseId FROM UserTableRow")
  Iterable<String> findAllDistinctDatabaseIds();

  Iterable<UserTableRow> findAllByDatabaseIdIgnoreCase(String databaseId);

  Iterable<UserTableRow> findAllByDatabaseIdAndTableIdLikeAllIgnoreCase(
      String databaseId, String tableIdPattern);

  @Query(
      "SELECT DISTINCT databaseId FROM UserTableRow u where "
          + "(:databaseId IS NULL OR lower(u.databaseId) = lower(:databaseId))")
  Page<String> findAllDistinctDatabaseIds(String databaseId, Pageable pageable);

  /**
   * The same distinct-database projection as {@link #findAllDistinctDatabaseIds()}, seeked rather
   * than offset: it returns the page of distinct databaseIds that sort strictly after {@code
   * after}, or the first page when {@code after} is null.
   *
   * <p>Seeking rather than counting pages is what makes a resumed backfill exact. The comparison
   * and the ordering are then both the database's, evaluated under one collation on one column, so
   * a resume cannot step over a database because the server and the client disagreed about where
   * {@code after} sits. It also keeps the cost of a resume proportional to what is left rather than
   * to what has already been done, and it needs no count query, which a {@code DISTINCT} projection
   * does not answer correctly anyway — hence {@link Slice}, whose {@code hasNext} is decided by
   * reading one row past the page.
   *
   * <p>Callers must pass a {@code Pageable} sorted ascending by {@code databaseId}; the seek is
   * meaningless under any other order.
   */
  @Query(
      "SELECT DISTINCT u.databaseId FROM UserTableRow u where "
          + "(:after IS NULL OR u.databaseId > :after)")
  Slice<String> findDistinctDatabaseIdsAfter(@Param("after") String after, Pageable pageable);

  Page<UserTableRow> findAllByDatabaseIdIgnoreCase(String databaseId, Pageable pageable);

  Page<UserTableRow> findAllByDatabaseIdAndTableIdLikeAllIgnoreCase(
      String databaseId, String tableIdPattern, Pageable pageable);

  @Query(
      "select DISTINCT u from UserTableRow u where "
          + "(:databaseId IS NULL OR lower(u.databaseId) = lower(:databaseId)) AND "
          + "(:tableId IS NULL OR lower(u.tableId) = lower(:tableId)) AND "
          + "(:tableVersion IS NULL OR u.version = :tableVersion) AND "
          + "(:metadataLocation IS NULL OR u.metadataLocation = :metadataLocation) AND "
          + "(:storageType IS NULL OR u.storageType = :storageType) AND "
          + "(:creationTime IS NULL OR u.creationTime = :creationTime)")
  Page<UserTableRow> findAllByFilters(
      String databaseId,
      String tableId,
      String tableVersion,
      String metadataLocation,
      String storageType,
      Long creationTime,
      Pageable pageable);

  @Query(
      "select DISTINCT u from UserTableRow u where "
          + "(:databaseId IS NULL OR lower(u.databaseId) = lower(:databaseId)) AND "
          + "(:tableId IS NULL OR lower(u.tableId) = lower(:tableId)) AND "
          + "(:tableVersion IS NULL OR u.version = :tableVersion) AND "
          + "(:metadataLocation IS NULL OR u.metadataLocation = :metadataLocation) AND "
          + "(:storageType IS NULL OR u.storageType = :storageType) AND "
          + "(:creationTime IS NULL OR u.creationTime = :creationTime)")
  Iterable<UserTableRow> findAllByFilters(
      String databaseId,
      String tableId,
      String tableVersion,
      String metadataLocation,
      String storageType,
      Long creationTime);

  /*
   * The following methods are required to maintain the generality of the interface {@link com.linkedin.openhouse.housetables.repository.HtsRepository}
   */

  @Override
  default @NotNull Optional<UserTableRow> findById(UserTableRowPrimaryKey userTableRowPrimaryKey) {
    return findByDatabaseIdIgnoreCaseAndTableIdIgnoreCase(
        userTableRowPrimaryKey.getDatabaseId(), userTableRowPrimaryKey.getTableId());
  }

  @Override
  default boolean existsById(UserTableRowPrimaryKey userTableRowPrimaryKey) {
    return existsByDatabaseIdIgnoreCaseAndTableIdIgnoreCase(
        userTableRowPrimaryKey.getDatabaseId(), userTableRowPrimaryKey.getTableId());
  }

  @Override
  default void deleteById(UserTableRowPrimaryKey userTableRowPrimaryKey) {
    deleteByDatabaseIdIgnoreCaseAndTableIdIgnoreCase(
        userTableRowPrimaryKey.getDatabaseId(), userTableRowPrimaryKey.getTableId());
  }

  @Transactional
  @Modifying
  @Query(
      "UPDATE UserTableRow table SET table.tableId = :toTableId, table.metadataLocation = :metadataLocation, table.databaseId = :toDatabaseId "
          + "WHERE lower(table.databaseId) = lower(:fromDatabaseId) AND lower(table.tableId) = lower(:fromTableId)")
  void renameTableId(
      @Param("fromDatabaseId") String fromDatabaseId,
      @Param("fromTableId") String fromTableId,
      @Param("toDatabaseId") String toDatabaseId,
      @Param("toTableId") String toTableId,
      @Param("metadataLocation") String metadataLocation);
}
