package com.linkedin.openhouse.optimizer.repository;

import com.linkedin.openhouse.optimizer.entity.TableStatsRow;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository for {@link TableStatsRow}. PK is {@code table_uuid}. */
@Repository
public interface TableStatsRepository extends JpaRepository<TableStatsRow, String> {

  /**
   * Find the stats row for a table by its human-readable {@code (databaseName, tableName)}.
   *
   * @param databaseName the database namespace
   * @param tableName the table name
   * @return the stats row, or empty if no stats have been recorded for this table
   */
  @Query(
      "SELECT s FROM TableStatsRow s "
          + "WHERE s.databaseName = :databaseName AND s.tableName = :tableName")
  Optional<TableStatsRow> find(
      @Param("databaseName") String databaseName, @Param("tableName") String tableName);
}
