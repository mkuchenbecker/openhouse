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
   * Return the most recent history rows for a table UUID, newest first, up to {@code limit} rows.
   *
   * @param tableUuid the stable table UUID
   * @param limit maximum number of rows to return
   * @return history rows ordered by {@code submitted_at} descending
   */
  @Query(
      value =
          "SELECT * FROM table_operations_history "
              + "WHERE table_uuid = :tableUuid "
              + "ORDER BY submitted_at DESC LIMIT :limit",
      nativeQuery = true)
  List<TableOperationsHistoryRow> find(
      @Param("tableUuid") String tableUuid, @Param("limit") int limit);
}
