package com.linkedin.openhouse.housetables.services;

import com.linkedin.openhouse.housetables.dto.model.TableStatsDto;
import java.util.Optional;

/** Service for reading and writing table stats. */
public interface TableStatsService {

  /**
   * Return the latest stats for a table, or empty if none have been recorded.
   *
   * @param databaseId the database namespace
   * @param tableId the table name
   */
  Optional<TableStatsDto> getTableStats(String databaseId, String tableId);

  /**
   * Upsert stats for a table.
   *
   * <p>Delta fields ({@code numFilesAdded}, {@code numFilesDeleted}) are accumulated; all other
   * fields are overwritten with the incoming values.
   *
   * @param dto the incoming stats payload; {@code databaseId} and {@code tableId} must be set
   * @return the persisted stats
   */
  TableStatsDto upsertTableStats(TableStatsDto dto);
}
