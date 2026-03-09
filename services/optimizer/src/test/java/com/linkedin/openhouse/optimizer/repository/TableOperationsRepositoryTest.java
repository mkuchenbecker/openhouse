package com.linkedin.openhouse.optimizer.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.linkedin.openhouse.optimizer.api.model.OperationMetrics;
import com.linkedin.openhouse.optimizer.api.model.OperationStatus;
import com.linkedin.openhouse.optimizer.api.model.OperationType;
import com.linkedin.openhouse.optimizer.entity.TableOperationsRow;
import java.time.Instant;
import java.util.List;
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
    OperationMetrics metrics =
        OperationMetrics.builder().numSnapshots(10).tableSizeBytes(2048L).build();

    TableOperationsRow row =
        TableOperationsRow.builder()
            .id(UUID.randomUUID().toString())
            .databaseName("db1")
            .tableName("tbl1")
            .operationType(OperationType.ORPHAN_FILES_DELETION)
            .status(OperationStatus.PENDING)
            .createdAt(Instant.now())
            .metrics(metrics)
            .build();

    repository.save(row);

    Optional<TableOperationsRow> found =
        repository.findByDatabaseNameAndTableNameAndOperationType(
            "db1", "tbl1", OperationType.ORPHAN_FILES_DELETION);
    assertThat(found).isPresent();
    assertThat(found.get().getStatus()).isEqualTo(OperationStatus.PENDING);
    assertThat(found.get().getMetrics().getNumSnapshots()).isEqualTo(10L);
  }

  @Test
  void findByOperationTypeAndCreatedAtAfter_returnsMatchingRows() {
    Instant base = Instant.parse("2024-01-01T00:00:00Z");

    repository.save(
        TableOperationsRow.builder()
            .id(UUID.randomUUID().toString())
            .databaseName("db1")
            .tableName("tbl1")
            .operationType(OperationType.ORPHAN_FILES_DELETION)
            .status(OperationStatus.PENDING)
            .createdAt(base.plusSeconds(10))
            .build());
    repository.save(
        TableOperationsRow.builder()
            .id(UUID.randomUUID().toString())
            .databaseName("db1")
            .tableName("tbl2")
            .operationType(OperationType.ORPHAN_FILES_DELETION)
            .status(OperationStatus.PENDING)
            .createdAt(base.plusSeconds(20))
            .build());

    List<TableOperationsRow> rows =
        repository.findByOperationTypeAndCreatedAtAfter(OperationType.ORPHAN_FILES_DELETION, base);
    assertThat(rows).hasSize(2);
  }
}
