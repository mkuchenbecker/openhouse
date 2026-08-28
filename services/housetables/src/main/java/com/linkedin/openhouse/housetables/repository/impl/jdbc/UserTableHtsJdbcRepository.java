package com.linkedin.openhouse.housetables.repository.impl.jdbc;

import com.linkedin.openhouse.housetables.config.db.jdbc.JdbcProviderConfiguration;
import com.linkedin.openhouse.housetables.model.UserTableRow;
import com.linkedin.openhouse.housetables.model.UserTableRowPrimaryKey;
import com.linkedin.openhouse.housetables.repository.HtsRepository;
import edu.umd.cs.findbugs.annotations.CheckReturnValue;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

  /**
   * Renames a table row, participating in the optimistic-lock protocol: the update only matches a
   * row still at both {@code expectedVersion} and {@code expectedMetadataLocation}, and it
   * atomically bumps {@link UserTableRow}'s {@literal @}Version column. A concurrent modification
   * (e.g. a table commit) that advances the row between the caller's read and this update makes the
   * update match 0 rows; callers must treat a 0 return value as a concurrent-modification conflict
   * instead of assuming the rename landed. That obligation is marked with {@link CheckReturnValue}:
   * discarding the result is a static analysis error, so a caller cannot silently reopen the
   * lost-update window this method closes.
   *
   * <p>The metadata location is part of the condition because {@literal @}Version alone cannot
   * identify a row across incarnations. It is a per-row counter that restarts at 0 when a row is
   * deleted and reinserted, so a drop and recreate between the caller's read and this update would
   * present a different table at the same identity and the same version, and a version-only
   * condition would match it. Metadata locations are {@code NNNNN-<uuid>.metadata.json} files that
   * are never reused, so conditioning on the observed location makes the guard identify the exact
   * row state the caller read. It also makes the guard self-contained rather than dependent on
   * every other writer bumping the version.
   *
   * @param expectedVersion the version the caller observed on the row it intends to rename.
   * @param expectedMetadataLocation the metadata location the caller observed on that same row. A
   *     user table row always carries one: {@link
   *     com.linkedin.openhouse.housetables.api.spec.model.UserTable#getMetadataLocation()} is
   *     {@literal @}NotEmpty and validated on the only path that writes a row, so this is never
   *     null and the condition never degenerates into a never-matching null comparison.
   * @return the number of rows updated: 1 if the rename landed, 0 if the row was concurrently
   *     modified (version or metadata location mismatch) or no longer exists.
   */
  @CheckReturnValue
  @Transactional
  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE UserTableRow table SET table.tableId = :toTableId, table.metadataLocation = :metadataLocation, table.databaseId = :toDatabaseId, table.version = table.version + 1 "
          + "WHERE lower(table.databaseId) = lower(:fromDatabaseId) AND lower(table.tableId) = lower(:fromTableId) AND table.version = :expectedVersion "
          + "AND table.metadataLocation = :expectedMetadataLocation")
  int renameTableId(
      @Param("fromDatabaseId") String fromDatabaseId,
      @Param("fromTableId") String fromTableId,
      @Param("toDatabaseId") String toDatabaseId,
      @Param("toTableId") String toTableId,
      @Param("metadataLocation") String metadataLocation,
      @Param("expectedVersion") Long expectedVersion,
      @Param("expectedMetadataLocation") String expectedMetadataLocation);
}
