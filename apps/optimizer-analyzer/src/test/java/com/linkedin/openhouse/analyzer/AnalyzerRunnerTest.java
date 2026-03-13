package com.linkedin.openhouse.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linkedin.openhouse.analyzer.model.TableOperationRecord;
import com.linkedin.openhouse.analyzer.model.TableSummary;
import com.linkedin.openhouse.optimizer.entity.TableOperationRow;
import com.linkedin.openhouse.optimizer.entity.TableStatsRow;
import com.linkedin.openhouse.optimizer.repository.TableOperationsRepository;
import com.linkedin.openhouse.optimizer.repository.TableStatsRepository;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnalyzerRunnerTest {

  @Mock private TableStatsRepository statsRepo;
  @Mock private TableOperationsRepository operationsRepo;
  @Mock private OperationAnalyzer analyzer;

  private AnalyzerRunner runner;

  @BeforeEach
  void setUp() {
    runner = new AnalyzerRunner(List.of(analyzer), statsRepo, operationsRepo);
  }

  @Test
  void analyze_insertsNewRow_forEligibleTableWithNoActiveOp() {
    TableStatsRow statsEntity = new TableStatsRow();
    statsEntity.setTableUuid("uuid-1");
    statsEntity.setDatabaseId("db1");
    statsEntity.setTableName("tbl1");

    TableSummary expectedTable =
        TableSummary.builder().tableUuid("uuid-1").databaseId("db1").tableId("tbl1").build();

    when(statsRepo.findAll()).thenReturn(List.of(statsEntity));
    when(analyzer.getOperationType()).thenReturn("ORPHAN_FILES_DELETION");
    when(operationsRepo.findByTypeAndStatuses(
            "ORPHAN_FILES_DELETION", Set.of("PENDING", "SCHEDULED")))
        .thenReturn(Collections.emptyList());
    when(analyzer.isEnabled(expectedTable)).thenReturn(true);
    when(analyzer.shouldSchedule(expectedTable, Optional.empty())).thenReturn(true);

    runner.analyze();

    ArgumentCaptor<TableOperationRow> captor = ArgumentCaptor.forClass(TableOperationRow.class);
    verify(operationsRepo).save(captor.capture());
    TableOperationRow saved = captor.getValue();
    assertThat(saved.getTableUuid()).isEqualTo("uuid-1");
    assertThat(saved.getDatabaseName()).isEqualTo("db1");
    assertThat(saved.getTableName()).isEqualTo("tbl1");
    assertThat(saved.getOperationType()).isEqualTo("ORPHAN_FILES_DELETION");
    assertThat(saved.getStatus()).isEqualTo("PENDING");
    assertThat(saved.getId()).isNotNull();
  }

  @Test
  void analyze_noOp_whenActivePendingOperationExists() {
    TableStatsRow statsEntity = new TableStatsRow();
    statsEntity.setTableUuid("uuid-1");
    statsEntity.setDatabaseId("db1");
    statsEntity.setTableName("tbl1");

    TableSummary expectedTable =
        TableSummary.builder().tableUuid("uuid-1").databaseId("db1").tableId("tbl1").build();

    TableOperationRow existingEntity = new TableOperationRow();
    existingEntity.setId("existing-op-id");
    existingEntity.setStatus("PENDING");
    existingEntity.setTableUuid("uuid-1");
    existingEntity.setOperationType("ORPHAN_FILES_DELETION");

    when(statsRepo.findAll()).thenReturn(List.of(statsEntity));
    when(analyzer.getOperationType()).thenReturn("ORPHAN_FILES_DELETION");
    when(operationsRepo.findByTypeAndStatuses(
            "ORPHAN_FILES_DELETION", Set.of("PENDING", "SCHEDULED")))
        .thenReturn(List.of(existingEntity));
    when(analyzer.isEnabled(expectedTable)).thenReturn(true);

    TableOperationRecord existingRecord = new TableOperationRecord();
    existingRecord.setId("existing-op-id");
    existingRecord.setStatus("PENDING");
    existingRecord.setTableUuid("uuid-1");
    existingRecord.setOperationType("ORPHAN_FILES_DELETION");
    when(analyzer.shouldSchedule(expectedTable, Optional.of(existingRecord))).thenReturn(true);

    runner.analyze();

    verify(operationsRepo, never()).save(any());
  }

  @Test
  void analyze_skipsTable_whenNotEnabled() {
    TableStatsRow statsEntity = new TableStatsRow();
    statsEntity.setTableUuid("uuid-1");

    TableSummary expectedTable = TableSummary.builder().tableUuid("uuid-1").build();

    when(statsRepo.findAll()).thenReturn(List.of(statsEntity));
    when(analyzer.getOperationType()).thenReturn("ORPHAN_FILES_DELETION");
    when(operationsRepo.findByTypeAndStatuses(
            "ORPHAN_FILES_DELETION", Set.of("PENDING", "SCHEDULED")))
        .thenReturn(Collections.emptyList());
    when(analyzer.isEnabled(expectedTable)).thenReturn(false);

    runner.analyze();

    verify(operationsRepo, never()).save(any());
  }

  @Test
  void analyze_skipsTable_whenShouldScheduleReturnsFalse() {
    TableStatsRow statsEntity = new TableStatsRow();
    statsEntity.setTableUuid("uuid-1");

    TableSummary expectedTable = TableSummary.builder().tableUuid("uuid-1").build();

    TableOperationRow scheduled = new TableOperationRow();
    scheduled.setId("op-id");
    scheduled.setStatus("SCHEDULED");
    scheduled.setTableUuid("uuid-1");
    scheduled.setOperationType("ORPHAN_FILES_DELETION");

    when(statsRepo.findAll()).thenReturn(List.of(statsEntity));
    when(analyzer.getOperationType()).thenReturn("ORPHAN_FILES_DELETION");
    when(operationsRepo.findByTypeAndStatuses(
            "ORPHAN_FILES_DELETION", Set.of("PENDING", "SCHEDULED")))
        .thenReturn(List.of(scheduled));
    when(analyzer.isEnabled(expectedTable)).thenReturn(true);

    TableOperationRecord scheduledRecord = new TableOperationRecord();
    scheduledRecord.setId("op-id");
    scheduledRecord.setStatus("SCHEDULED");
    scheduledRecord.setTableUuid("uuid-1");
    scheduledRecord.setOperationType("ORPHAN_FILES_DELETION");
    when(analyzer.shouldSchedule(expectedTable, Optional.of(scheduledRecord))).thenReturn(false);

    runner.analyze();

    verify(operationsRepo, never()).save(any());
  }

  @Test
  void analyze_skipsTable_whenTableUuidIsNull() {
    TableStatsRow statsEntity = new TableStatsRow();
    statsEntity.setTableUuid(null);

    TableSummary expectedTable = TableSummary.builder().tableUuid(null).build();

    when(statsRepo.findAll()).thenReturn(List.of(statsEntity));
    when(analyzer.getOperationType()).thenReturn("ORPHAN_FILES_DELETION");
    when(operationsRepo.findByTypeAndStatuses(
            "ORPHAN_FILES_DELETION", Set.of("PENDING", "SCHEDULED")))
        .thenReturn(Collections.emptyList());
    when(analyzer.isEnabled(expectedTable)).thenReturn(true);

    runner.analyze();

    verify(operationsRepo, never()).save(any());
  }
}
