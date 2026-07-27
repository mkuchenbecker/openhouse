package com.linkedin.openhouse.optimizer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linkedin.openhouse.optimizer.db.OperationScope;
import com.linkedin.openhouse.optimizer.db.OperationStatus;
import com.linkedin.openhouse.optimizer.db.OperationType;
import com.linkedin.openhouse.optimizer.db.TableOperationsRow;
import com.linkedin.openhouse.optimizer.model.OperationTypeDto;
import com.linkedin.openhouse.optimizer.model.TableOperationDto;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DirectoryDeletionAnalyzerRunnerTest {

  private static final OperationTypeDto TYPE = OperationTypeDto.ORPHAN_DIRECTORY_DELETION;
  private static final OperationType DB_TYPE = OperationType.ORPHAN_DIRECTORY_DELETION;
  private static final String DB = "db1";

  @Mock private com.linkedin.openhouse.optimizer.repository.TableStatsRepository statsRepo;

  @Mock
  private com.linkedin.openhouse.optimizer.repository.TableOperationsRepository operationsRepo;

  @Mock
  private com.linkedin.openhouse.optimizer.repository.TableOperationsHistoryRepository historyRepo;

  @Mock private DirectoryOperationAnalyzer analyzer;

  private DirectoryDeletionAnalyzerRunner runner() {
    return new DirectoryDeletionAnalyzerRunner(
        List.of(analyzer), statsRepo, operationsRepo, historyRepo);
  }

  @Test
  void analyze_disabled_doesNothing() {
    when(analyzer.getOperationType()).thenReturn(TYPE);
    when(analyzer.isEnabled()).thenReturn(false);

    runner().analyze(TYPE);

    verify(operationsRepo, never()).save(any());
    verify(statsRepo, never()).findDistinctDatabaseNames();
  }

  @Test
  void analyze_enabledEligibleDatabase_persistsDatabaseScopedPendingOp() {
    when(analyzer.getOperationType()).thenReturn(TYPE);
    when(analyzer.isEnabled()).thenReturn(true);
    when(operationsRepo.findByScope(
            eq(DB_TYPE), eq(Optional.empty()), eq(OperationScope.DATABASE), any()))
        .thenReturn(Collections.emptyList());
    when(historyRepo.findLatestByDatabaseScope(eq(DB_TYPE), any()))
        .thenReturn(Collections.emptyList());
    when(statsRepo.findDistinctDatabaseNames()).thenReturn(List.of(DB));
    when(analyzer.shouldSchedule(eq(DB), eq(Optional.empty()), eq(Optional.empty())))
        .thenReturn(true);

    runner().analyze(TYPE);

    ArgumentCaptor<TableOperationsRow> captor = ArgumentCaptor.forClass(TableOperationsRow.class);
    verify(operationsRepo).save(captor.capture());
    TableOperationsRow saved = captor.getValue();
    assertThat(saved.getTableUuid()).isNull();
    assertThat(saved.getTableName()).isNull();
    assertThat(saved.getDatabaseName()).isEqualTo(DB);
    assertThat(saved.getOperationType()).isEqualTo(DB_TYPE);
    assertThat(saved.getOperationScope()).isEqualTo(OperationScope.DATABASE);
    assertThat(saved.getStatus()).isEqualTo(OperationStatus.PENDING);
  }

  @Test
  void analyze_cadenceSaysNo_doesNotPersist() {
    when(analyzer.getOperationType()).thenReturn(TYPE);
    when(analyzer.isEnabled()).thenReturn(true);
    when(operationsRepo.findByScope(
            eq(DB_TYPE), eq(Optional.empty()), eq(OperationScope.DATABASE), any()))
        .thenReturn(Collections.emptyList());
    when(historyRepo.findLatestByDatabaseScope(eq(DB_TYPE), any()))
        .thenReturn(Collections.emptyList());
    when(statsRepo.findDistinctDatabaseNames()).thenReturn(List.of(DB));
    when(analyzer.shouldSchedule(eq(DB), any(), any())).thenReturn(false);

    runner().analyze(TYPE);

    verify(operationsRepo, never()).save(any());
  }

  @Test
  void analyze_activeOpForDatabase_passedAsCurrentOp() {
    when(analyzer.getOperationType()).thenReturn(TYPE);
    when(analyzer.isEnabled()).thenReturn(true);
    TableOperationsRow active =
        TableOperationDto.pendingForDatabase(DB, TYPE)
            .toRow()
            .toBuilder()
            .createdAt(Instant.now())
            .build();
    when(operationsRepo.findByScope(
            eq(DB_TYPE), eq(Optional.empty()), eq(OperationScope.DATABASE), any()))
        .thenReturn(List.of(active));
    when(historyRepo.findLatestByDatabaseScope(eq(DB_TYPE), any()))
        .thenReturn(Collections.emptyList());
    when(statsRepo.findDistinctDatabaseNames()).thenReturn(List.of(DB));
    // Analyzer sees a present current op and declines (mirrors CadencePolicy blocking on active
    // op).
    when(analyzer.shouldSchedule(eq(DB), any(Optional.class), eq(Optional.empty())))
        .thenReturn(false);

    runner().analyze(TYPE);

    verify(operationsRepo, never()).save(any());
  }

  @Test
  void analyze_noAnalyzerForType_throws() {
    when(analyzer.getOperationType()).thenReturn(TYPE);
    try {
      runner().analyze(OperationTypeDto.TABLE_DIRECTORY_DELETION);
      assertThat(false).as("expected IllegalStateException").isTrue();
    } catch (IllegalStateException expected) {
      assertThat(expected).hasMessageContaining("No directory analyzer registered");
    }
  }
}
