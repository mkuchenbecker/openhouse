package com.linkedin.openhouse.optimizer.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.linkedin.openhouse.optimizer.entity.TableOperationsHistoryRow;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
            .operationType("ORPHAN_FILES_DELETION")
            .submittedAt(t1)
            .status("SUCCESS")
            .jobId("job-001")
            .build());

    Map<String, Object> errorResult = new HashMap<>();
    errorResult.put("error_message", "out of memory");
    errorResult.put("error_type", "OOM");

    repository.save(
        TableOperationsHistoryRow.builder()
            .databaseName("db1")
            .tableName("tbl1")
            .operationType("ORPHAN_FILES_DELETION")
            .submittedAt(t2)
            .status("FAILED")
            .jobId("job-002")
            .result(errorResult)
            .build());

    List<TableOperationsHistoryRow> rows =
        repository.findByDatabaseNameAndTableNameOrderBySubmittedAtDesc("db1", "tbl1");

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
              .operationType("ORPHAN_FILES_DELETION")
              .submittedAt(now.plusSeconds(i))
              .status("SUCCESS")
              .build());
    }

    List<TableOperationsHistoryRow> rows =
        repository.findByDatabaseNameAndTableNameOrderBySubmittedAtDesc("db1", "tbl2");
    assertThat(rows).hasSize(3);
  }
}
