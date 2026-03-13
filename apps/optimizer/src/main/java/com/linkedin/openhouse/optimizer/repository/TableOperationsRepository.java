package com.linkedin.openhouse.optimizer.repository;

import com.linkedin.openhouse.optimizer.entity.TableOperationRow;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data JPA repository for {@code table_operations} rows in the optimizer DB. */
public interface TableOperationsRepository extends JpaRepository<TableOperationRow, String> {

  /**
   * Returns rows for the given operation type whose status is in {@code statuses}. Used by the
   * Analyzer to load all active (PENDING or SCHEDULED) rows in one query.
   */
  @Query(
      "SELECT r FROM TableOperationRow r WHERE r.operationType = :type"
          + " AND r.status IN :statuses")
  List<TableOperationRow> findByTypeAndStatuses(
      @Param("type") String operationType, @Param("statuses") Collection<String> statuses);

  /**
   * Returns all PENDING rows for the given operation type. Used by the Scheduler to find work to
   * submit.
   */
  @Query("SELECT r FROM TableOperationRow r WHERE r.operationType = :type AND r.status = 'PENDING'")
  List<TableOperationRow> findPendingByType(@Param("type") String operationType);

  /**
   * Atomically claim a PENDING row by flipping its status to SCHEDULED.
   *
   * <p>The {@code version} guard prevents double-scheduling when multiple scheduler instances run
   * concurrently. Returns 1 if the claim succeeded, 0 if the row was already claimed by another
   * instance.
   */
  @Modifying
  @Query(
      "UPDATE TableOperationRow r SET r.status = 'SCHEDULED', r.scheduledAt = :now"
          + " WHERE r.id = :id AND r.version = :version")
  int claimOperation(
      @Param("id") String id, @Param("version") Long version, @Param("now") Instant now);
}
