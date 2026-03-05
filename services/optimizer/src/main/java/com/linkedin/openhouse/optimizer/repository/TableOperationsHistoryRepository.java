package com.linkedin.openhouse.optimizer.repository;

import com.linkedin.openhouse.optimizer.entity.TableOperationsHistoryRow;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link TableOperationsHistoryRow}. Append-only; PK is auto-increment {@code id}.
 */
@Repository
public interface TableOperationsHistoryRepository
    extends JpaRepository<TableOperationsHistoryRow, Long> {

  /** Return all history rows for a table, newest first. */
  List<TableOperationsHistoryRow> findByDatabaseNameAndTableNameOrderBySubmittedAtDesc(
      String databaseName, String tableName);
}
