package com.linkedin.openhouse.optimizer.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.linkedin.openhouse.optimizer.entity.OperationStatus;
import com.linkedin.openhouse.optimizer.entity.TableOperationsRow;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TableOperationsRepositoryTest {

  @Autowired TableOperationsRepository repository;

  @Test
  void saveAndFindByTriple() {
    Map<String, Object> metrics = new HashMap<>();
    metrics.put("num_snapshots", 10);

    TableOperationsRow row =
        TableOperationsRow.builder()
            .id(UUID.randomUUID().toString())
            .databaseName("db1")
            .tableName("tbl1")
            .operationType("ORPHAN_FILES_DELETION")
            .status(OperationStatus.PENDING.name())
            .createdAt(Instant.now())
            .metrics(metrics)
            .build();

    repository.save(row);

    Optional<TableOperationsRow> found =
        repository.findByDatabaseNameAndTableNameAndOperationType(
            "db1", "tbl1", "ORPHAN_FILES_DELETION");
    assertThat(found).isPresent();
    assertThat(found.get().getStatus()).isEqualTo("PENDING");
  }

  @Test
  void findByOperationType_returnsMatchingRows() {
    repository.save(
        TableOperationsRow.builder()
            .id(UUID.randomUUID().toString())
            .databaseName("db1")
            .tableName("tbl1")
            .operationType("ORPHAN_FILES_DELETION")
            .status(OperationStatus.PENDING.name())
            .createdAt(Instant.now())
            .build());
    repository.save(
        TableOperationsRow.builder()
            .id(UUID.randomUUID().toString())
            .databaseName("db1")
            .tableName("tbl2")
            .operationType("ORPHAN_FILES_DELETION")
            .status(OperationStatus.PENDING.name())
            .createdAt(Instant.now())
            .build());

    List<TableOperationsRow> rows = repository.findByOperationType("ORPHAN_FILES_DELETION");
    assertThat(rows).hasSize(2);
  }
}
