package com.linkedin.openhouse.housetables.repository.impl.jdbc;

import com.linkedin.openhouse.housetables.model.DatabaseRow;
import com.linkedin.openhouse.housetables.repository.HtsRepository;
import java.util.Optional;

/**
 * JDBC-backed {@link HtsRepository} for CRUDing {@link DatabaseRow}.
 *
 * <p>Like {@link ToggleStatusHtsJdbcRepository}, this entity has no Iceberg-backed implementation.
 * It is picked up by {@code @EnableJpaRepositories} in {@link
 * com.linkedin.openhouse.housetables.config.db.DatabaseConfiguration}. Lookups by name ignore case
 * because every existing HTS lookup on {@code databaseId} does, and the production collation is
 * case-insensitive.
 */
public interface DatabaseHtsJdbcRepository extends HtsRepository<DatabaseRow, String> {

  Optional<DatabaseRow> findByDatabaseIdIgnoreCase(String databaseId);

  boolean existsByDatabaseIdIgnoreCase(String databaseId);
}
