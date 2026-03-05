package com.linkedin.openhouse.optimizer.repository;

import com.linkedin.openhouse.optimizer.entity.TableOperationsRow;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link TableOperationsRow}. PK is the UUID {@code id}. */
@Repository
public interface TableOperationsRepository extends JpaRepository<TableOperationsRow, String> {

  /**
   * Find the operation recommendation for a specific {@code (database, table, operationType)}
   * triple, used by the Analyzer to upsert and by the Scheduler to claim.
   */
  Optional<TableOperationsRow> findByDatabaseNameAndTableNameAndOperationType(
      String databaseName, String tableName, String operationType);

  /**
   * List all operations, optionally filtered by operation type. Used by the Scheduler for
   * bin-packing.
   */
  List<TableOperationsRow> findByOperationType(String operationType);
}
