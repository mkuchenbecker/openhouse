package com.linkedin.openhouse.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linkedin.openhouse.optimizer.entity.TableOperationRow;
import com.linkedin.openhouse.optimizer.entity.TableStatsRow;
import com.linkedin.openhouse.optimizer.model.TableStats;
import com.linkedin.openhouse.optimizer.repository.TableOperationsRepository;
import com.linkedin.openhouse.optimizer.repository.TableStatsRepository;
import com.linkedin.openhouse.scheduler.client.JobsServiceClient;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SchedulerRunnerTest {

  @Mock private TableOperationsRepository operationsRepo;
  @Mock private TableStatsRepository statsRepo;
  @Mock private JobsServiceClient jobsClient;

  @InjectMocks private SchedulerRunner runner;

  @BeforeEach
  void injectConfig() {
    ReflectionTestUtils.setField(runner, "maxFiles", 1_000_000L);
    ReflectionTestUtils.setField(runner, "operationType", "ORPHAN_FILES_DELETION");
    ReflectionTestUtils.setField(
        runner, "resultsEndpoint", "http://localhost:8080/v1/table-operations");
  }

  private TableOperationRow pendingRow(String uuid, String db, String table) {
    return TableOperationRow.builder()
        .id(UUID.randomUUID().toString())
        .tableUuid(uuid)
        .databaseName(db)
        .tableName(table)
        .operationType("ORPHAN_FILES_DELETION")
        .status("PENDING")
        .version(0L)
        .build();
  }

  private TableStatsRow statsRow(String uuid, long numCurrentFiles) {
    TableStats stats =
        TableStats.builder()
            .snapshot(TableStats.SnapshotMetrics.builder().numCurrentFiles(numCurrentFiles).build())
            .build();
    return TableStatsRow.builder().tableUuid(uuid).stats(stats).build();
  }

  @Test
  void schedule_noPendingOps_noJobSubmitted() {
    when(operationsRepo.findByTypeAndStatuses("ORPHAN_FILES_DELETION", List.of("PENDING")))
        .thenReturn(List.of());

    runner.schedule();

    verify(jobsClient, never()).launch(anyString(), anyString(), anyList(), anyList(), anyString());
  }

  @Test
  void schedule_claimsBeforeSubmit() {
    String uuid = UUID.randomUUID().toString();
    TableOperationRow row = pendingRow(uuid, "db1", "tbl1");

    when(operationsRepo.findByTypeAndStatuses("ORPHAN_FILES_DELETION", List.of("PENDING")))
        .thenReturn(List.of(row));
    when(statsRepo.findAllById(any())).thenReturn(List.of(statsRow(uuid, 100_000L)));
    when(operationsRepo.claimOperation(anyString(), anyLong(), any())).thenReturn(1);
    when(jobsClient.launch(anyString(), anyString(), anyList(), anyList(), anyString()))
        .thenReturn(Optional.of("job-123"));

    runner.schedule();

    // Claim happens first; only then is the job submitted.
    verify(operationsRepo).claimOperation(eq(row.getId()), eq(0L), any());

    ArgumentCaptor<List<String>> tableNamesCaptor = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<List<String>> opIdsCaptor = ArgumentCaptor.forClass(List.class);
    verify(jobsClient)
        .launch(
            anyString(),
            eq("ORPHAN_FILES_DELETION"),
            tableNamesCaptor.capture(),
            opIdsCaptor.capture(),
            anyString());

    assertThat(tableNamesCaptor.getValue()).containsExactly("db1.tbl1");
    assertThat(opIdsCaptor.getValue()).containsExactly(row.getId());
  }

  @Test
  void schedule_jobLaunchFails_rowsStayScheduled() {
    String uuid = UUID.randomUUID().toString();
    TableOperationRow row = pendingRow(uuid, "db1", "tbl1");

    when(operationsRepo.findByTypeAndStatuses("ORPHAN_FILES_DELETION", List.of("PENDING")))
        .thenReturn(List.of(row));
    when(statsRepo.findAllById(any())).thenReturn(List.of());
    when(operationsRepo.claimOperation(anyString(), anyLong(), any())).thenReturn(1);
    when(jobsClient.launch(anyString(), anyString(), anyList(), anyList(), anyString()))
        .thenReturn(Optional.empty());

    runner.schedule();

    // Rows are claimed (SCHEDULED) before launch; launch failure leaves them SCHEDULED for
    // a watchdog to reset.
    verify(operationsRepo).claimOperation(eq(row.getId()), eq(0L), any());
    verify(jobsClient).launch(anyString(), anyString(), anyList(), anyList(), anyString());
  }

  @Test
  void schedule_rowAlreadyClaimed_skipsSubmit() {
    String uuid = UUID.randomUUID().toString();
    TableOperationRow row = pendingRow(uuid, "db1", "tbl1");

    when(operationsRepo.findByTypeAndStatuses("ORPHAN_FILES_DELETION", List.of("PENDING")))
        .thenReturn(List.of(row));
    when(statsRepo.findAllById(any())).thenReturn(List.of());
    // claimOperation returns 0 — another instance already claimed this row.
    when(operationsRepo.claimOperation(anyString(), anyLong(), any())).thenReturn(0);

    runner.schedule();

    verify(jobsClient, never()).launch(anyString(), anyString(), anyList(), anyList(), anyString());
  }

  @Test
  void schedule_cancelsDuplicatePendingBeforeClaim() {
    String uuid = UUID.randomUUID().toString();
    TableOperationRow row1 = pendingRow(uuid, "db1", "tbl1");
    TableOperationRow row2 = pendingRow(uuid, "db1", "tbl1");

    when(operationsRepo.findByTypeAndStatuses("ORPHAN_FILES_DELETION", List.of("PENDING")))
        .thenReturn(List.of(row1, row2));
    when(statsRepo.findAllById(any())).thenReturn(List.of());
    when(operationsRepo.claimOperation(anyString(), anyLong(), any())).thenReturn(1);
    when(jobsClient.launch(anyString(), anyString(), anyList(), anyList(), anyString()))
        .thenReturn(Optional.of("job-789"));

    runner.schedule();

    // cancelDuplicatePending is called once for the shared UUID, keeping the first row.
    verify(operationsRepo)
        .cancelDuplicatePending(eq(uuid), eq("ORPHAN_FILES_DELETION"), eq(row1.getId()));
  }

  @Test
  void schedule_claimsAllRowsInBin() {
    String uuid1 = UUID.randomUUID().toString();
    String uuid2 = UUID.randomUUID().toString();
    TableOperationRow row1 = pendingRow(uuid1, "db1", "tbl1");
    TableOperationRow row2 = pendingRow(uuid2, "db1", "tbl2");

    when(operationsRepo.findByTypeAndStatuses("ORPHAN_FILES_DELETION", List.of("PENDING")))
        .thenReturn(List.of(row1, row2));
    when(statsRepo.findAllById(any())).thenReturn(List.of());
    when(operationsRepo.claimOperation(anyString(), anyLong(), any())).thenReturn(1);
    when(jobsClient.launch(anyString(), anyString(), anyList(), anyList(), anyString()))
        .thenReturn(Optional.of("job-456"));

    runner.schedule();

    verify(operationsRepo, times(2)).claimOperation(anyString(), eq(0L), any());
  }
}
