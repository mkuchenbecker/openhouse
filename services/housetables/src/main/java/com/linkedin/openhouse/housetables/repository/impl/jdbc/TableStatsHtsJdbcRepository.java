package com.linkedin.openhouse.housetables.repository.impl.jdbc;

import com.linkedin.openhouse.housetables.model.TableStatsRow;
import com.linkedin.openhouse.housetables.repository.HtsRepository;

/**
 * JDBC-backed {@link HtsRepository} for {@link TableStatsRow}.
 *
 * <p>Discovered automatically by the {@code @EnableJpaRepositories} in {@link
 * com.linkedin.openhouse.housetables.config.db.DatabaseConfiguration}.
 *
 * <p>All access is by {@code tableUuid} (Iceberg's stable identifier). Name-based lookup is not
 * exposed: callers always have the UUID from Iceberg metadata, and a re-created table gets a new
 * UUID so its old stats are never returned.
 */
public interface TableStatsHtsJdbcRepository extends HtsRepository<TableStatsRow, String> {}
