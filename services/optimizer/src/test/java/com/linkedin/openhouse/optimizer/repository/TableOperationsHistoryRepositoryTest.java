package com.linkedin.openhouse.optimizer.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.linkedin.openhouse.optimizer.api.model.JobResult;
import com.linkedin.openhouse.optimizer.api.model.OperationHistoryStatus;
import com.linkedin.openhouse.optimizer.api.model.OperationType;
import com.linkedin.openhouse.optimizer.entity.TableOperationsHistoryRow;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TableOperationsHistoryRepositoryTest {

  @Autowired TableOperationsHistoryRepository repository;

  @Test
  void appendAndFindByTable() {
    Instant t1 = Instant.parse("2024-01-01T10:00:00Z");
    Instant t2 = Instant.parse("2024-01-02T10:00:00Z");

    repository.save(
        TableOperationsHistoryRow.builder()
            .databaseName("db1")
            .tableName("tbl1")
            .operationType(OperationType.ORPHAN_FILES_DELETION)
            .submittedAt(t1)
            .status(OperationHistoryStatus.SUCCESS)
            .jobId("job-001")
            .build());

    repository.save(
        TableOperationsHistoryRow.builder()
            .databaseName("db1")
            .tableName("tbl1")
            .operationType(OperationType.ORPHAN_FILES_DELETION)
            .submittedAt(t2)
            .status(OperationHistoryStatus.FAILED)
            .jobId("job-002")
            .result(JobResult.builder().errorMessage("out of memory").errorType("OOM").build())
            .build());

    List<TableOperationsHistoryRow> rows = repository.find("db1", "tbl1", 10);

    assertThat(rows).hasSize(2);
    // Newest first
    assertThat(rows.get(0).getJobId()).isEqualTo("job-002");
    assertThat(rows.get(1).getJobId()).isEqualTo("job-001");
  }

  @Test
  void appendIsNonDestructive_multipleRunsRetained() {
    Instant now = Instant.now();
    for (int i = 0; i < 3; i++) {
      repository.save(
          TableOperationsHistoryRow.builder()
              .databaseName("db1")
              .tableName("tbl2")
              .operationType(OperationType.ORPHAN_FILES_DELETION)
              .submittedAt(now.plusSeconds(i))
              .status(OperationHistoryStatus.SUCCESS)
              .build());
    }

    List<TableOperationsHistoryRow> rows = repository.find("db1", "tbl2", 10);
    assertThat(rows).hasSize(3);
  }

  @Test
  void find_respectsLimit() {
    Instant now = Instant.now();
    for (int i = 0; i < 5; i++) {
      repository.save(
          TableOperationsHistoryRow.builder()
              .databaseName("db1")
              .tableName("tbl3")
              .operationType(OperationType.ORPHAN_FILES_DELETION)
              .submittedAt(now.plusSeconds(i))
              .status(OperationHistoryStatus.SUCCESS)
              .build());
    }

    List<TableOperationsHistoryRow> rows = repository.find("db1", "tbl3", 3);
    assertThat(rows).hasSize(3);
  }
}
