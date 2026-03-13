package com.linkedin.openhouse.scheduler.repository;

import com.linkedin.openhouse.scheduler.entity.SchedulerOperationRow;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** JPA repository for reading and claiming {@code table_operations} rows in the optimizer DB. */
public interface SchedulerOperationsRepository
    extends JpaRepository<SchedulerOperationRow, String> {

  @Query(
      "SELECT r FROM SchedulerOperationRow r WHERE r.operationType = :type AND r.status = 'PENDING'")
  List<SchedulerOperationRow> findPendingByType(@Param("type") String operationType);

  /**
   * Atomically claim a PENDING row by flipping its status to SCHEDULED.
   *
   * <p>The {@code version} guard prevents double-scheduling when multiple scheduler instances run
   * concurrently. Returns 1 if the claim succeeded, 0 if the row was already claimed by another
   * instance.
   */
  @Modifying
  @Query(
      "UPDATE SchedulerOperationRow r SET r.status = 'SCHEDULED', r.scheduledAt = :now"
          + " WHERE r.id = :id AND r.version = :version")
  int claimOperation(
      @Param("id") String id, @Param("version") Long version, @Param("now") Instant now);
}
