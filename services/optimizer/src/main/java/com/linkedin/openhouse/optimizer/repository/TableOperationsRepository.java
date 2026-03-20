package com.linkedin.openhouse.optimizer.repository;

import com.linkedin.openhouse.optimizer.api.model.OperationType;
import com.linkedin.openhouse.optimizer.entity.TableOperationsRow;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository for {@link TableOperationsRow}. PK is the client-generated UUID {@code id}. */
@Repository
public interface TableOperationsRepository extends JpaRepository<TableOperationsRow, String> {

  /**
   * Return all active (PENDING or SCHEDULED) rows for the given operation type.
   *
   * <p>Used by the Analyzer to pre-load current state before iterating tables.
   */
  /**
   * Return all active (PENDING or SCHEDULED) rows for the given operation type.
   *
   * <p>Used by the Analyzer to pre-load current state before iterating tables.
   */
  @Query(
      "SELECT r FROM TableOperationsRow r "
          + "WHERE r.operationType = :type "
          + "AND r.status IN ('PENDING', 'SCHEDULED')")
  List<TableOperationsRow> find(@Param("type") OperationType type);

  /** Return all active (PENDING or SCHEDULED) rows across all operation types. */
  @Query("SELECT r FROM TableOperationsRow r WHERE r.status IN ('PENDING', 'SCHEDULED')")
  List<TableOperationsRow> findAllActive();
}
