package com.linkedin.openhouse.optimizer.repository;

import com.linkedin.openhouse.optimizer.entity.TableOperationsHistoryRow;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link TableOperationsHistoryRow}. Append-only; PK is auto-increment {@code id}.
 */
@Repository
public interface TableOperationsHistoryRepository
    extends JpaRepository<TableOperationsHistoryRow, Long> {

  /**
   * Return the most recent history rows for a table, newest first, up to {@code limit} rows.
   *
   * @param databaseName the database namespace
   * @param tableName the table name
   * @param limit maximum number of rows to return
   * @return history rows ordered by {@code submitted_at} descending
   */
  @Query(
      value =
          "SELECT * FROM table_operations_history "
              + "WHERE database_name = :databaseName AND table_name = :tableName "
              + "ORDER BY submitted_at DESC LIMIT :limit",
      nativeQuery = true)
  List<TableOperationsHistoryRow> find(
      @Param("databaseName") String databaseName,
      @Param("tableName") String tableName,
      @Param("limit") int limit);
}
