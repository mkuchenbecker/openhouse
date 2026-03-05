package com.linkedin.openhouse.optimizer.repository;

import com.linkedin.openhouse.optimizer.entity.TableStatsRow;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link TableStatsRow}. PK is {@code table_uuid}. */
@Repository
public interface TableStatsRepository extends JpaRepository<TableStatsRow, String> {

  /** Find the stats row for a table by its human-readable name. */
  Optional<TableStatsRow> findByDatabaseNameAndTableName(String databaseName, String tableName);
}
