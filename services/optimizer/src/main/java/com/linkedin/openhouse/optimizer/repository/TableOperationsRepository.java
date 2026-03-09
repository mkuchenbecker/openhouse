package com.linkedin.openhouse.optimizer.repository;

import com.linkedin.openhouse.optimizer.api.model.OperationType;
import com.linkedin.openhouse.optimizer.entity.TableOperationsRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link TableOperationsRow}. PK is the UUID {@code id}. */
@Repository
public interface TableOperationsRepository extends JpaRepository<TableOperationsRow, String> {

  /**
   * Find the operation recommendation for a specific {@code (database, table, operationType)}
   * triple. Used by the Analyzer to upsert and by the Scheduler to claim.
   *
   * @param databaseName the database namespace
   * @param tableName the table name
   * @param operationType the maintenance operation type
   * @return the matching row, or empty if none exists
   */
  Optional<TableOperationsRow> findByDatabaseNameAndTableNameAndOperationType(
      String databaseName, String tableName, OperationType operationType);

  /**
   * Return all operations of a given type created after {@code since}. Used by the Scheduler to
   * find pending work within a lookback window.
   *
   * @param since lower bound (exclusive) on {@code created_at}
   * @return matching rows, unordered
   */
  List<TableOperationsRow> findByCreatedAtAfter(Instant since);

  /**
   * Return operations of a specific type created after {@code since}. Used by the Scheduler when
   * processing a single operation type per cycle.
   *
   * @param operationType the maintenance operation type to filter by
   * @param since lower bound (exclusive) on {@code created_at}
   * @return matching rows, unordered
   */
  List<TableOperationsRow> findByOperationTypeAndCreatedAtAfter(
      OperationType operationType, Instant since);
}
