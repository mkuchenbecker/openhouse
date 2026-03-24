package com.linkedin.openhouse.optimizer.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.linkedin.openhouse.optimizer.api.model.OperationMetrics;
import com.linkedin.openhouse.optimizer.api.model.OperationStatus;
import com.linkedin.openhouse.optimizer.api.model.OperationType;
import com.linkedin.openhouse.optimizer.api.model.PatchTableOperationRequest;
import com.linkedin.openhouse.optimizer.entity.TableOperationsRow;
import com.linkedin.openhouse.optimizer.service.OptimizerDataService;
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
  @Autowired OptimizerDataService service;

  @Test
  void saveAndFindById() {
    OperationMetrics metrics = OperationMetrics.builder().tableSizeBytes(2048L).build();
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
    assertThat(found.get().getMetrics().getTableSizeBytes()).isEqualTo(2048L);
  }

  @Test
  void patch_scheduledToSuccess_updatesStatus() {
    String id = UUID.randomUUID().toString();
    repository.save(
        TableOperationsRow.builder()
            .id(id)
            .tableUuid(UUID.randomUUID().toString())
            .databaseName("db1")
            .tableName("tbl1")
            .operationType(OperationType.ORPHAN_FILES_DELETION)
            .status(OperationStatus.SCHEDULED)
            .createdAt(Instant.now())
            .scheduledAt(Instant.now())
            .build());

    Optional<com.linkedin.openhouse.optimizer.api.model.TableOperationsDto> result =
        service.patchTableOperation(
            id, PatchTableOperationRequest.builder().status(OperationStatus.SUCCESS).build());

    assertThat(result).isPresent();
    assertThat(result.get().getStatus()).isEqualTo(OperationStatus.SUCCESS);
    assertThat(repository.findById(id).get().getStatus()).isEqualTo(OperationStatus.SUCCESS);
  }

  @Test
  void patch_nonexistentId_returnsEmpty() {
    Optional<com.linkedin.openhouse.optimizer.api.model.TableOperationsDto> result =
        service.patchTableOperation(
            UUID.randomUUID().toString(),
            PatchTableOperationRequest.builder().status(OperationStatus.FAILED).build());

    assertThat(result).isEmpty();
  }

  @Test
  void patch_invalidStatus_throwsInvalidPatchStatus() {
    org.junit.jupiter.api.Assertions.assertThrows(
        com.linkedin.openhouse.optimizer.api.exception.InvalidPatchStatusException.class,
        () ->
            service.patchTableOperation(
                UUID.randomUUID().toString(),
                PatchTableOperationRequest.builder().status(OperationStatus.PENDING).build()));
  }

  @Test
  void patch_pendingRow_throwsConflict() {
    String id = UUID.randomUUID().toString();
    repository.save(
        TableOperationsRow.builder()
            .id(id)
            .tableUuid(UUID.randomUUID().toString())
            .databaseName("db1")
            .tableName("tbl1")
            .operationType(OperationType.ORPHAN_FILES_DELETION)
            .status(OperationStatus.PENDING)
            .createdAt(Instant.now())
            .build());

    org.junit.jupiter.api.Assertions.assertThrows(
        com.linkedin.openhouse.optimizer.api.exception.OperationStateConflictException.class,
        () ->
            service.patchTableOperation(
                id, PatchTableOperationRequest.builder().status(OperationStatus.SUCCESS).build()));
  }

  @Test
  void findFiltered_noParams_returnsAll() {
    repository.save(
        TableOperationsRow.builder()
            .id(UUID.randomUUID().toString())
            .tableUuid(UUID.randomUUID().toString())
            .databaseName("db1")
            .tableName("tbl1")
            .operationType(OperationType.ORPHAN_FILES_DELETION)
            .status(OperationStatus.PENDING)
            .createdAt(Instant.now())
            .build());
    repository.save(
        TableOperationsRow.builder()
            .id(UUID.randomUUID().toString())
            .tableUuid(UUID.randomUUID().toString())
            .databaseName("db1")
            .tableName("tbl2")
            .operationType(OperationType.ORPHAN_FILES_DELETION)
            .status(OperationStatus.SUCCESS)
            .createdAt(Instant.now())
            .build());

    List<TableOperationsRow> rows = repository.findFiltered(null, null, null, null, null);
    assertThat(rows).hasSize(2);
  }

  @Test
  void findFiltered_byStatus() {
    repository.save(
        TableOperationsRow.builder()
            .id(UUID.randomUUID().toString())
            .tableUuid(UUID.randomUUID().toString())
            .databaseName("db1")
            .tableName("tbl1")
            .operationType(OperationType.ORPHAN_FILES_DELETION)
            .status(OperationStatus.PENDING)
            .createdAt(Instant.now())
            .build());
    repository.save(
        TableOperationsRow.builder()
            .id(UUID.randomUUID().toString())
            .tableUuid(UUID.randomUUID().toString())
            .databaseName("db1")
            .tableName("tbl2")
            .operationType(OperationType.ORPHAN_FILES_DELETION)
            .status(OperationStatus.SUCCESS)
            .createdAt(Instant.now())
            .build());

    List<TableOperationsRow> pending =
        repository.findFiltered(null, OperationStatus.PENDING, null, null, null);
    assertThat(pending).hasSize(1);
    assertThat(pending.get(0).getStatus()).isEqualTo(OperationStatus.PENDING);

    List<TableOperationsRow> success =
        repository.findFiltered(null, OperationStatus.SUCCESS, null, null, null);
    assertThat(success).hasSize(1);
    assertThat(success.get(0).getStatus()).isEqualTo(OperationStatus.SUCCESS);
  }

  @Test
  void findFiltered_byDatabaseAndTable() {
    repository.save(
        TableOperationsRow.builder()
            .id(UUID.randomUUID().toString())
            .tableUuid(UUID.randomUUID().toString())
            .databaseName("db1")
            .tableName("tbl1")
            .operationType(OperationType.ORPHAN_FILES_DELETION)
            .status(OperationStatus.PENDING)
            .createdAt(Instant.now())
            .build());
    repository.save(
        TableOperationsRow.builder()
            .id(UUID.randomUUID().toString())
            .tableUuid(UUID.randomUUID().toString())
            .databaseName("db2")
            .tableName("tbl2")
            .operationType(OperationType.ORPHAN_FILES_DELETION)
            .status(OperationStatus.PENDING)
            .createdAt(Instant.now())
            .build());

    assertThat(repository.findFiltered(null, null, "db1", null, null)).hasSize(1);
    assertThat(repository.findFiltered(null, null, "db2", "tbl2", null)).hasSize(1);
    assertThat(repository.findFiltered(null, null, "db1", "tbl2", null)).isEmpty();
  }
}
