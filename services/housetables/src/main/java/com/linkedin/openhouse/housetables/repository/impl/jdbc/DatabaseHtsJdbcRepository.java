package com.linkedin.openhouse.housetables.repository.impl.jdbc;

import com.linkedin.openhouse.housetables.model.DatabaseRow;
import com.linkedin.openhouse.housetables.repository.HtsRepository;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC-backed {@link HtsRepository} for CRUDing {@link DatabaseRow}.
 *
 * <p>Configured in {@link com.linkedin.openhouse.housetables.config.db.DatabaseConfiguration}
 * with @EnableJpaRepositories, alongside the user-table repository.
 */
public interface DatabaseHtsJdbcRepository extends HtsRepository<DatabaseRow, String> {

  Optional<DatabaseRow> findByDatabaseIdIgnoreCase(String databaseId);

  /**
   * Every stored namespace in the subtree under a parent, as a half-open range over the encoded id.
   *
   * <p>Callers pass {@code parent + "."} and {@code parent + "/"}: {@code .} is {@code 0x2E} and
   * {@code /} is {@code 0x2F}, and no level may contain either, so the range is exactly the set of
   * ids prefixed by {@code parent + "."}. The parent's own id sorts strictly below the lower bound,
   * so it is never returned as its own descendant.
   *
   * <p>A range rather than a {@code LIKE} prefix on purpose: the identifier charset admits {@code
   * _}, which SQL {@code LIKE} reads as a single-character wildcard, so {@code LIKE 'my_db.%'}
   * would also match {@code myXdb.a}. Narrowing the subtree to direct children is left to the
   * caller, which does it by counting separators rather than by a second pattern.
   */
  Iterable<DatabaseRow> findAllByDatabaseIdGreaterThanEqualAndDatabaseIdLessThan(
      String lowerBoundInclusive, String upperBoundExclusive);

  boolean existsByDatabaseIdIgnoreCase(String databaseId);

  @Transactional
  @Modifying
  void deleteByDatabaseIdIgnoreCase(String databaseId);

  @Override
  default @NotNull Optional<DatabaseRow> findById(String databaseId) {
    return findByDatabaseIdIgnoreCase(databaseId);
  }

  @Override
  default boolean existsById(String databaseId) {
    return existsByDatabaseIdIgnoreCase(databaseId);
  }

  @Override
  default void deleteById(String databaseId) {
    deleteByDatabaseIdIgnoreCase(databaseId);
  }
}
