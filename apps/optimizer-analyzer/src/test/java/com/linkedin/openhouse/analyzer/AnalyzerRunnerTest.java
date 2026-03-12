package com.linkedin.openhouse.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linkedin.openhouse.analyzer.client.HtsClient;
import com.linkedin.openhouse.analyzer.entity.TableOperationEntity;
import com.linkedin.openhouse.analyzer.model.TableOperationRecord;
import com.linkedin.openhouse.analyzer.model.TableSummary;
import com.linkedin.openhouse.analyzer.repository.TableOperationsRepository;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnalyzerRunnerTest {

  @Mock private HtsClient htsClient;
  @Mock private TableOperationsRepository repo;
  @Mock private OperationAnalyzer analyzer;

  private AnalyzerRunner runner;

  @BeforeEach
  void setUp() {
    runner = new AnalyzerRunner(List.of(analyzer), htsClient, repo);
  }

  @Test
  void run_insertsNewRow_forEligibleTableWithNoActiveOp() {
    TableSummary table =
        TableSummary.builder().tableUuid("uuid-1").databaseId("db1").tableId("tbl1").build();

    when(htsClient.getAllTableStats()).thenReturn(List.of(table));
    when(analyzer.getOperationType()).thenReturn("ORPHAN_FILES_DELETION");
    when(repo.findActiveByType("ORPHAN_FILES_DELETION")).thenReturn(Collections.emptyList());
    when(analyzer.isEnabled(table)).thenReturn(true);
    when(analyzer.shouldSchedule(table, Optional.empty())).thenReturn(true);

    runner.run();

    ArgumentCaptor<TableOperationEntity> captor =
        ArgumentCaptor.forClass(TableOperationEntity.class);
    verify(repo).save(captor.capture());
    TableOperationEntity saved = captor.getValue();
    assertThat(saved.getTableUuid()).isEqualTo("uuid-1");
    assertThat(saved.getDatabaseName()).isEqualTo("db1");
    assertThat(saved.getTableName()).isEqualTo("tbl1");
    assertThat(saved.getOperationType()).isEqualTo("ORPHAN_FILES_DELETION");
    assertThat(saved.getStatus()).isEqualTo("PENDING");
    assertThat(saved.getId()).isNotNull();
  }

  @Test
  void run_noOp_whenActivePendingOperationExists() {
    TableSummary table =
        TableSummary.builder().tableUuid("uuid-1").databaseId("db1").tableId("tbl1").build();

    TableOperationEntity existingEntity = new TableOperationEntity();
    existingEntity.setId("existing-op-id");
    existingEntity.setStatus("PENDING");
    existingEntity.setTableUuid("uuid-1");
    existingEntity.setOperationType("ORPHAN_FILES_DELETION");

    when(htsClient.getAllTableStats()).thenReturn(List.of(table));
    when(analyzer.getOperationType()).thenReturn("ORPHAN_FILES_DELETION");
    when(repo.findActiveByType("ORPHAN_FILES_DELETION")).thenReturn(List.of(existingEntity));
    when(analyzer.isEnabled(table)).thenReturn(true);

    TableOperationRecord existingRecord = new TableOperationRecord();
    existingRecord.setId("existing-op-id");
    existingRecord.setStatus("PENDING");
    existingRecord.setTableUuid("uuid-1");
    existingRecord.setOperationType("ORPHAN_FILES_DELETION");
    when(analyzer.shouldSchedule(table, Optional.of(existingRecord))).thenReturn(true);

    runner.run();

    // PENDING already exists — no new row should be inserted
    verify(repo, never()).save(any());
  }

  @Test
  void run_skipsTable_whenNotEnabled() {
    TableSummary table = TableSummary.builder().tableUuid("uuid-1").build();

    when(htsClient.getAllTableStats()).thenReturn(List.of(table));
    when(analyzer.getOperationType()).thenReturn("ORPHAN_FILES_DELETION");
    when(repo.findActiveByType("ORPHAN_FILES_DELETION")).thenReturn(Collections.emptyList());
    when(analyzer.isEnabled(table)).thenReturn(false);

    runner.run();

    verify(repo, never()).save(any());
  }

  @Test
  void run_skipsTable_whenShouldScheduleReturnsFalse() {
    TableSummary table = TableSummary.builder().tableUuid("uuid-1").build();

    TableOperationEntity scheduled = new TableOperationEntity();
    scheduled.setId("op-id");
    scheduled.setStatus("SCHEDULED");
    scheduled.setTableUuid("uuid-1");
    scheduled.setOperationType("ORPHAN_FILES_DELETION");

    when(htsClient.getAllTableStats()).thenReturn(List.of(table));
    when(analyzer.getOperationType()).thenReturn("ORPHAN_FILES_DELETION");
    when(repo.findActiveByType("ORPHAN_FILES_DELETION")).thenReturn(List.of(scheduled));
    when(analyzer.isEnabled(table)).thenReturn(true);

    TableOperationRecord scheduledRecord = new TableOperationRecord();
    scheduledRecord.setId("op-id");
    scheduledRecord.setStatus("SCHEDULED");
    scheduledRecord.setTableUuid("uuid-1");
    scheduledRecord.setOperationType("ORPHAN_FILES_DELETION");
    when(analyzer.shouldSchedule(table, Optional.of(scheduledRecord))).thenReturn(false);

    runner.run();

    verify(repo, never()).save(any());
  }

  @Test
  void run_skipsTable_whenTableUuidIsNull() {
    TableSummary table = TableSummary.builder().tableUuid(null).build();

    when(htsClient.getAllTableStats()).thenReturn(List.of(table));
    when(analyzer.getOperationType()).thenReturn("ORPHAN_FILES_DELETION");
    when(repo.findActiveByType("ORPHAN_FILES_DELETION")).thenReturn(Collections.emptyList());
    when(analyzer.isEnabled(table)).thenReturn(true);

    runner.run();

    verify(repo, never()).save(any());
  }
}
