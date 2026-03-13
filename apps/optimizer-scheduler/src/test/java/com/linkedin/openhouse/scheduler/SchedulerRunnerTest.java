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

import com.linkedin.openhouse.scheduler.client.JobsServiceClient;
import com.linkedin.openhouse.scheduler.entity.SchedulerOperationRow;
import com.linkedin.openhouse.scheduler.entity.SchedulerStatsRow;
import com.linkedin.openhouse.scheduler.repository.SchedulerOperationsRepository;
import com.linkedin.openhouse.scheduler.repository.SchedulerStatsRepository;
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

  @Mock private SchedulerOperationsRepository operationsRepo;
  @Mock private SchedulerStatsRepository statsRepo;
  @Mock private JobsServiceClient jobsClient;

  @InjectMocks private SchedulerRunner runner;

  @BeforeEach
  void injectConfig() {
    ReflectionTestUtils.setField(runner, "maxFiles", 1_000_000L);
    ReflectionTestUtils.setField(runner, "operationType", "ORPHAN_FILES_DELETION");
    ReflectionTestUtils.setField(
        runner, "resultsEndpoint", "http://localhost:8080/v1/table-operations");
  }

  private SchedulerOperationRow pendingRow(String uuid, String db, String table) {
    return SchedulerOperationRow.builder()
        .id(UUID.randomUUID().toString())
        .tableUuid(uuid)
        .databaseName(db)
        .tableName(table)
        .operationType("ORPHAN_FILES_DELETION")
        .status("PENDING")
        .version(0L)
        .build();
  }

  private SchedulerStatsRow statsRow(String uuid, long numCurrentFiles) {
    String statsJson = "{\"snapshot\":{\"numCurrentFiles\":" + numCurrentFiles + "}}";
    return SchedulerStatsRow.builder().tableUuid(uuid).stats(statsJson).build();
  }

  @Test
  void schedule_noPendingOps_noJobSubmitted() {
    when(operationsRepo.findPendingByType("ORPHAN_FILES_DELETION")).thenReturn(List.of());

    runner.schedule();

    verify(jobsClient, never()).launch(anyString(), anyString(), anyList(), anyList(), anyString());
  }

  @Test
  void schedule_pendingOps_submitsBatchedJob() {
    String uuid = UUID.randomUUID().toString();
    SchedulerOperationRow row = pendingRow(uuid, "db1", "tbl1");

    when(operationsRepo.findPendingByType("ORPHAN_FILES_DELETION")).thenReturn(List.of(row));
    when(statsRepo.findAllById(any())).thenReturn(List.of(statsRow(uuid, 100_000L)));
    when(jobsClient.launch(anyString(), anyString(), anyList(), anyList(), anyString()))
        .thenReturn(Optional.of("job-123"));
    when(operationsRepo.claimOperation(anyString(), anyLong(), any())).thenReturn(1);

    runner.schedule();

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
  void schedule_jobLaunchFails_rowsRemainPending() {
    String uuid = UUID.randomUUID().toString();
    SchedulerOperationRow row = pendingRow(uuid, "db1", "tbl1");

    when(operationsRepo.findPendingByType("ORPHAN_FILES_DELETION")).thenReturn(List.of(row));
    when(statsRepo.findAllById(any())).thenReturn(List.of());
    when(jobsClient.launch(anyString(), anyString(), anyList(), anyList(), anyString()))
        .thenReturn(Optional.empty());

    runner.schedule();

    verify(operationsRepo, never()).claimOperation(anyString(), anyLong(), any());
  }

  @Test
  void schedule_claimsRowsAfterLaunch() {
    String uuid1 = UUID.randomUUID().toString();
    String uuid2 = UUID.randomUUID().toString();
    SchedulerOperationRow row1 = pendingRow(uuid1, "db1", "tbl1");
    SchedulerOperationRow row2 = pendingRow(uuid2, "db1", "tbl2");

    when(operationsRepo.findPendingByType("ORPHAN_FILES_DELETION")).thenReturn(List.of(row1, row2));
    when(statsRepo.findAllById(any())).thenReturn(List.of());
    when(jobsClient.launch(anyString(), anyString(), anyList(), anyList(), anyString()))
        .thenReturn(Optional.of("job-456"));
    when(operationsRepo.claimOperation(anyString(), anyLong(), any())).thenReturn(1);

    runner.schedule();

    verify(operationsRepo, times(2)).claimOperation(anyString(), eq(0L), any());
  }
}
