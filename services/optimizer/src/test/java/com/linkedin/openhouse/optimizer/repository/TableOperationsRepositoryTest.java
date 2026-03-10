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
  void saveAndFindById() {
    OperationMetrics metrics =
        OperationMetrics.builder().numSnapshots(10).tableSizeBytes(2048L).build();
    String id = UUID.randomUUID().toString();

    TableOperationsRow row =
        TableOperationsRow.builder()
            .id(id)
            .tableUuid(UUID.randomUUID().toString())
            .databaseName("db1")
            .tableName("tbl1")
            .operationType(OperationType.ORPHAN_FILES_DELETION)
            .status(OperationStatus.PENDING)
            .createdAt(Instant.now())
            .metrics(metrics)
            .build();

    repository.save(row);

    Optional<TableOperationsRow> found = repository.findById(id);
    assertThat(found).isPresent();
    assertThat(found.get().getStatus()).isEqualTo(OperationStatus.PENDING);
    assertThat(found.get().getMetrics().getNumSnapshots()).isEqualTo(10L);
  }

  @Test
  void find_returnsPendingAndScheduledOnly() {
    String tableUuid1 = UUID.randomUUID().toString();
    String tableUuid2 = UUID.randomUUID().toString();
    String tableUuid3 = UUID.randomUUID().toString();

    repository.save(
        TableOperationsRow.builder()
            .id(UUID.randomUUID().toString())
            .tableUuid(tableUuid1)
            .databaseName("db1")
            .tableName("tbl1")
            .operationType(OperationType.ORPHAN_FILES_DELETION)
            .status(OperationStatus.PENDING)
            .createdAt(Instant.now())
            .build());
    repository.save(
        TableOperationsRow.builder()
            .id(UUID.randomUUID().toString())
            .tableUuid(tableUuid2)
            .databaseName("db1")
            .tableName("tbl2")
            .operationType(OperationType.ORPHAN_FILES_DELETION)
            .status(OperationStatus.SCHEDULED)
            .createdAt(Instant.now())
            .build());
    repository.save(
        TableOperationsRow.builder()
            .id(UUID.randomUUID().toString())
            .tableUuid(tableUuid3)
            .databaseName("db1")
            .tableName("tbl3")
            .operationType(OperationType.ORPHAN_FILES_DELETION)
            .status(OperationStatus.SUCCESS)
            .createdAt(Instant.now())
            .build());

    List<TableOperationsRow> rows = repository.find(OperationType.ORPHAN_FILES_DELETION);
    assertThat(rows).hasSize(2);
    assertThat(rows)
        .extracting(TableOperationsRow::getStatus)
        .containsExactlyInAnyOrder(OperationStatus.PENDING, OperationStatus.SCHEDULED);
  }
}
