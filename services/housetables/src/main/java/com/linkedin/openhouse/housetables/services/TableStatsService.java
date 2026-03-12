package com.linkedin.openhouse.housetables.services;

import com.linkedin.openhouse.housetables.dto.model.TableStatsDto;
import java.util.List;
import java.util.Optional;

/** Service for reading and writing table stats. */
public interface TableStatsService {

  /**
   * Return the latest stats for a table, or empty if none have been recorded.
   *
   * @param tableUuid Iceberg's stable UUID for the table
   */
  Optional<TableStatsDto> getTableStats(String tableUuid);

  /** Return stats for all tables. */
  List<TableStatsDto> getAllTableStats();

  /**
   * Upsert stats for a table.
   *
   * <p>{@link TableStatsDto#getStats()} snapshot fields are overwritten; delta fields are
   * accumulated across commit events.
   *
   * @param dto the incoming stats; {@code tableUuid}, {@code databaseId}, and {@code tableName}
   *     must be set
   * @return the persisted stats
   */
  TableStatsDto upsertTableStats(TableStatsDto dto);
}
