package com.linkedin.openhouse.housetables.repository.impl.jdbc;

import com.linkedin.openhouse.housetables.model.TableStatsRow;
import com.linkedin.openhouse.housetables.model.TableStatsRowPrimaryKey;
import com.linkedin.openhouse.housetables.repository.HtsRepository;
import java.util.Optional;

/**
 * JDBC-backed {@link HtsRepository} for {@link TableStatsRow}.
 *
 * <p>Discovered automatically by the {@code @EnableJpaRepositories} in {@link
 * com.linkedin.openhouse.housetables.config.db.DatabaseConfiguration}.
 */
public interface TableStatsHtsJdbcRepository
    extends HtsRepository<TableStatsRow, TableStatsRowPrimaryKey> {

  /**
   * Find the stats row for {@code (databaseId, tableId)}.
   *
   * @return the matching row, or empty if no stats have been recorded for this table
   */
  Optional<TableStatsRow> findByDatabaseIdAndTableId(String databaseId, String tableId);
}
