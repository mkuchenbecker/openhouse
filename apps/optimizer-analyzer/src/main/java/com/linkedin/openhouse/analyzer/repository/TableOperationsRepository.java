package com.linkedin.openhouse.analyzer.repository;

import com.linkedin.openhouse.analyzer.entity.TableOperationEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data JPA repository for reading and writing {@code table_operations} rows. */
public interface TableOperationsRepository extends JpaRepository<TableOperationEntity, String> {

  /**
   * Returns operation rows for the given operation type whose status is in {@code statuses}. The
   * caller determines which statuses are considered "active" — this query is generic.
   */
  @Query(
      "SELECT r FROM TableOperationEntity r WHERE r.operationType = :type"
          + " AND r.status IN :statuses")
  List<TableOperationEntity> findByTypeAndStatuses(
      @Param("type") String operationType, @Param("statuses") Collection<String> statuses);
}
