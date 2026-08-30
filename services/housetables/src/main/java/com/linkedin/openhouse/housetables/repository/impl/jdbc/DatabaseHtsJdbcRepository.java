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
