package com.linkedin.openhouse.analyzer;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linkedin.openhouse.analyzer.client.OptimizerServiceClient;
import com.linkedin.openhouse.analyzer.client.TablesServiceClient;
import com.linkedin.openhouse.analyzer.model.TableOperationRecord;
import com.linkedin.openhouse.analyzer.model.TableSummary;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnalyzerRunnerTest {

  @Mock private TablesServiceClient tablesClient;
  @Mock private OptimizerServiceClient optimizerClient;
  @Mock private OperationAnalyzer analyzer;

  private AnalyzerRunner runner;

  @BeforeEach
  void setUp() {
    runner = new AnalyzerRunner(List.of(analyzer), tablesClient, optimizerClient);
  }

  @Test
  void run_upsertsOperation_forEligibleTable() {
    TableSummary table =
        TableSummary.builder().tableUuid("uuid-1").databaseId("db1").tableId("tbl1").build();

    when(tablesClient.getDatabases()).thenReturn(List.of("db1"));
    when(tablesClient.getAllTables("db1")).thenReturn(List.of(table));
    when(analyzer.getOperationType()).thenReturn("ORPHAN_FILES_DELETION");
    when(optimizerClient.getOperationsByType("ORPHAN_FILES_DELETION"))
        .thenReturn(Collections.emptyMap());
    when(analyzer.isEnabled(table)).thenReturn(true);
    when(analyzer.shouldSchedule(eq(table), eq(Optional.empty()))).thenReturn(true);

    runner.run();

    verify(optimizerClient)
        .upsertOperation(
            anyString(), eq("uuid-1"), eq("db1"), eq("tbl1"), eq("ORPHAN_FILES_DELETION"));
  }

  @Test
  void run_reusesExistingId_forPendingOperation() {
    TableSummary table =
        TableSummary.builder().tableUuid("uuid-1").databaseId("db1").tableId("tbl1").build();

    TableOperationRecord existingOp = new TableOperationRecord();
    existingOp.setId("existing-op-id");
    existingOp.setStatus("PENDING");
    existingOp.setTableUuid("uuid-1");

    Map<String, TableOperationRecord> opsByUuid = new HashMap<>();
    opsByUuid.put("uuid-1", existingOp);

    when(tablesClient.getDatabases()).thenReturn(List.of("db1"));
    when(tablesClient.getAllTables("db1")).thenReturn(List.of(table));
    when(analyzer.getOperationType()).thenReturn("ORPHAN_FILES_DELETION");
    when(optimizerClient.getOperationsByType("ORPHAN_FILES_DELETION")).thenReturn(opsByUuid);
    when(analyzer.isEnabled(table)).thenReturn(true);
    when(analyzer.shouldSchedule(eq(table), eq(Optional.of(existingOp)))).thenReturn(true);

    runner.run();

    verify(optimizerClient)
        .upsertOperation(
            eq("existing-op-id"), eq("uuid-1"), eq("db1"), eq("tbl1"), eq("ORPHAN_FILES_DELETION"));
  }

  @Test
  void run_skipsTable_whenNotEnabled() {
    TableSummary table = TableSummary.builder().tableUuid("uuid-1").build();

    when(tablesClient.getDatabases()).thenReturn(List.of("db1"));
    when(tablesClient.getAllTables("db1")).thenReturn(List.of(table));
    when(analyzer.getOperationType()).thenReturn("ORPHAN_FILES_DELETION");
    when(optimizerClient.getOperationsByType("ORPHAN_FILES_DELETION"))
        .thenReturn(Collections.emptyMap());
    when(analyzer.isEnabled(table)).thenReturn(false);

    runner.run();

    verify(optimizerClient, never())
        .upsertOperation(anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void run_skipsTable_whenShouldScheduleReturnsFalse() {
    TableSummary table = TableSummary.builder().tableUuid("uuid-1").build();

    TableOperationRecord scheduled = new TableOperationRecord();
    scheduled.setId("op-id");
    scheduled.setStatus("SCHEDULED");
    scheduled.setTableUuid("uuid-1");

    Map<String, TableOperationRecord> opsByUuid = new HashMap<>();
    opsByUuid.put("uuid-1", scheduled);

    when(tablesClient.getDatabases()).thenReturn(List.of("db1"));
    when(tablesClient.getAllTables("db1")).thenReturn(List.of(table));
    when(analyzer.getOperationType()).thenReturn("ORPHAN_FILES_DELETION");
    when(optimizerClient.getOperationsByType("ORPHAN_FILES_DELETION")).thenReturn(opsByUuid);
    when(analyzer.isEnabled(table)).thenReturn(true);
    when(analyzer.shouldSchedule(eq(table), eq(Optional.of(scheduled)))).thenReturn(false);

    runner.run();

    verify(optimizerClient, never())
        .upsertOperation(anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void run_skipsTable_whenTableUuidIsNull() {
    TableSummary table = TableSummary.builder().tableUuid(null).build();

    when(tablesClient.getDatabases()).thenReturn(List.of("db1"));
    when(tablesClient.getAllTables("db1")).thenReturn(List.of(table));
    when(analyzer.getOperationType()).thenReturn("ORPHAN_FILES_DELETION");
    when(optimizerClient.getOperationsByType("ORPHAN_FILES_DELETION"))
        .thenReturn(Collections.emptyMap());
    when(analyzer.isEnabled(table)).thenReturn(true);

    runner.run();

    verify(optimizerClient, never())
        .upsertOperation(anyString(), anyString(), anyString(), anyString(), anyString());
  }
}
