package com.linkedin.openhouse.analyzer.repository;

import com.linkedin.openhouse.analyzer.entity.TableOperationEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data JPA repository for reading and writing {@code table_operations} rows. */
public interface TableOperationsRepository extends JpaRepository<TableOperationEntity, String> {

  /**
   * Returns active (PENDING or SCHEDULED) operation rows for the given operation type. Used by the
   * analyzer to check for existing work before inserting new rows.
   */
  @Query(
      "SELECT r FROM TableOperationEntity r WHERE r.operationType = :type"
          + " AND r.status IN ('PENDING','SCHEDULED')")
  List<TableOperationEntity> findActiveByType(@Param("type") String operationType);
}
