package com.linkedin.openhouse.optimizer.scheduler;

import static com.linkedin.openhouse.optimizer.model.OperationTypeDto.ORPHAN_DIRECTORY_DELETION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linkedin.openhouse.optimizer.db.OperationScope;
import com.linkedin.openhouse.optimizer.db.OperationStatus;
import com.linkedin.openhouse.optimizer.db.TableOperationsRow;
import com.linkedin.openhouse.optimizer.repository.TableOperationsRepository;
import com.linkedin.openhouse.optimizer.repository.TableStatsRepository;
import com.linkedin.openhouse.optimizer.scheduler.client.JobsServiceClient;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Covers {@link SchedulerRunner#scheduleDirectory} — the database-scoped dispatch path. */
@ExtendWith(MockitoExtension.class)
class SchedulerRunnerDirectoryTest {

  private static final String RESULTS_ENDPOINT = "http://localhost:8080/v1/optimizer/operations";

  @Mock private TableOperationsRepository operationsRepo;
  @Mock private TableStatsRepository statsRepo;
  @Mock private JobsServiceClient jobsClient;

  private SchedulerRunner runner;

  @BeforeEach
  void setUp() {
    runner =
        new SchedulerRunner(operationsRepo, statsRepo, jobsClient, RESULTS_ENDPOINT)
            .registerDirectoryOperation(ORPHAN_DIRECTORY_DELETION, 25);
  }

  private TableOperationsRow pendingDbRow(String id, String db) {
    return TableOperationsRow.builder()
        .id(id)
        .databaseName(db)
        .operationType(ORPHAN_DIRECTORY_DELETION.toDb())
        .operationScope(OperationScope.DATABASE)
        .status(OperationStatus.PENDING)
        .createdAt(Instant.now())
        .build();
  }

  @Test
  void scheduleDirectory_launchesBatchJobWithDatabaseNamesAndBatchJobType() {
    TableOperationsRow db1 = pendingDbRow("op-1", "db1");
    TableOperationsRow db2 = pendingDbRow("op-2", "db2");

    when(operationsRepo.findByScope(
            eq(ORPHAN_DIRECTORY_DELETION.toDb()),
            eq(Optional.of(OperationStatus.PENDING)),
            eq(OperationScope.DATABASE),
            any()))
        .thenReturn(List.of(db1, db2));
    // Claim narrowing: both rows come back SCHEDULING under this caller's watermark.
    when(operationsRepo.find(
            eq(Optional.empty()),
            eq(Optional.of(OperationStatus.SCHEDULING)),
            eq(Optional.empty()),
            eq(Optional.empty()),
            eq(Optional.empty()),
            any(),
            any(),
            any()))
        .thenReturn(
            List.of(
                db1.toBuilder().status(OperationStatus.SCHEDULING).build(),
                db2.toBuilder().status(OperationStatus.SCHEDULING).build()));
    when(jobsClient.launchDirectory(anyString(), anyString(), anyList(), anyList(), anyString()))
        .thenReturn(Optional.of("job-xyz"));

    runner.scheduleDirectory(ORPHAN_DIRECTORY_DELETION);

    ArgumentCaptor<String> jobType = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<List<String>> databaseNames = ArgumentCaptor.forClass(List.class);
    verify(jobsClient)
        .launchDirectory(
            anyString(), jobType.capture(), databaseNames.capture(), anyList(), anyString());
    assertThat(jobType.getValue()).isEqualTo(ORPHAN_DIRECTORY_DELETION.toJobType());
    assertThat(databaseNames.getValue()).containsExactlyInAnyOrder("db1", "db2");

    // Successful launch marks the claimed rows SCHEDULED.
    verify(operationsRepo)
        .updateBatch(
            anyList(),
            eq(OperationStatus.SCHEDULING),
            eq(OperationStatus.SCHEDULED),
            eq(Optional.empty()),
            eq(Optional.of("job-xyz")));
  }

  @Test
  void scheduleDirectory_noPending_noLaunch() {
    when(operationsRepo.findByScope(
            eq(ORPHAN_DIRECTORY_DELETION.toDb()),
            eq(Optional.of(OperationStatus.PENDING)),
            eq(OperationScope.DATABASE),
            any()))
        .thenReturn(List.of());

    runner.scheduleDirectory(ORPHAN_DIRECTORY_DELETION);

    verify(jobsClient, never())
        .launchDirectory(anyString(), anyString(), anyList(), anyList(), anyString());
  }

  @Test
  void scheduleDirectory_launchFails_revertsToPending() {
    TableOperationsRow db1 = pendingDbRow("op-1", "db1");
    when(operationsRepo.findByScope(
            eq(ORPHAN_DIRECTORY_DELETION.toDb()),
            eq(Optional.of(OperationStatus.PENDING)),
            eq(OperationScope.DATABASE),
            any()))
        .thenReturn(List.of(db1));
    when(operationsRepo.find(
            eq(Optional.empty()),
            eq(Optional.of(OperationStatus.SCHEDULING)),
            eq(Optional.empty()),
            eq(Optional.empty()),
            eq(Optional.empty()),
            any(),
            any(),
            any()))
        .thenReturn(List.of(db1.toBuilder().status(OperationStatus.SCHEDULING).build()));
    when(jobsClient.launchDirectory(anyString(), anyString(), anyList(), anyList(), anyString()))
        .thenReturn(Optional.empty());

    runner.scheduleDirectory(ORPHAN_DIRECTORY_DELETION);

    verify(operationsRepo)
        .updateBatch(
            anyList(),
            eq(OperationStatus.SCHEDULING),
            eq(OperationStatus.PENDING),
            eq(Optional.empty()),
            eq(Optional.empty()));
  }

  @Test
  void scheduleDirectory_unregisteredType_noOp() {
    runner.scheduleDirectory(
        com.linkedin.openhouse.optimizer.model.OperationTypeDto.TABLE_DIRECTORY_DELETION);
    verify(operationsRepo, never()).findByScope(any(), any(), any(), any());
  }
}
