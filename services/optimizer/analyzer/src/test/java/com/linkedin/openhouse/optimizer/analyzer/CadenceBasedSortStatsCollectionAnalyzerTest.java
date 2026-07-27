package com.linkedin.openhouse.optimizer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.linkedin.openhouse.optimizer.model.HistoryStatusDto;
import com.linkedin.openhouse.optimizer.model.OperationStatusDto;
import com.linkedin.openhouse.optimizer.model.OperationTypeDto;
import com.linkedin.openhouse.optimizer.model.TableDto;
import com.linkedin.openhouse.optimizer.model.TableOperationDto;
import com.linkedin.openhouse.optimizer.model.TableOperationsHistoryDto;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CadenceBasedSortStatsCollectionAnalyzerTest {

  private static final Duration TEST_SUCCESS_INTERVAL = Duration.ofHours(20);
  private static final Duration TEST_FAILURE_INTERVAL = Duration.ofHours(1);

  private CadenceBasedSortStatsCollectionAnalyzer analyzer;

  @BeforeEach
  void setUp() {
    analyzer =
        new CadenceBasedSortStatsCollectionAnalyzer(
            new CadencePolicy(TEST_SUCCESS_INTERVAL, TEST_FAILURE_INTERVAL));
  }

  @Test
  void getOperationType_isSortStatsCollection() {
    assertThat(analyzer.getOperationType()).isEqualTo(OperationTypeDto.SORT_STATS_COLLECTION);
  }

  // --- isEnabled: opt-in AND primary (primary = NOT replica, absent ⇒ primary) ---

  @Test
  void isEnabled_returnsTrue_whenEnabledAndExplicitPrimary() {
    assertThat(analyzer.isEnabled(table(true, "PRIMARY_TABLE"))).isTrue();
  }

  @Test
  void isEnabled_returnsTrue_whenEnabledAndTableTypeAbsent() {
    // Absent openhouse.tableType defaults to primary, matching the rest of the codebase.
    TableDto table =
        TableDto.builder()
            .tableUuid("test-uuid")
            .tableProperties(
                Map.of(
                    CadenceBasedSortStatsCollectionAnalyzer.SORT_STATS_COLLECTION_ENABLED_PROPERTY,
                    "true"))
            .build();
    assertThat(analyzer.isEnabled(table)).isTrue();
  }

  @Test
  void isEnabled_returnsFalse_whenEnabledButReplica() {
    assertThat(analyzer.isEnabled(table(true, "REPLICA_TABLE"))).isFalse();
  }

  @Test
  void isEnabled_returnsFalse_whenPropertyFalse() {
    assertThat(analyzer.isEnabled(table(false, "PRIMARY_TABLE"))).isFalse();
  }

  @Test
  void isEnabled_returnsFalse_whenTablePropertiesEmpty() {
    // No opt-in property → skipped regardless of the primary-by-default rule.
    TableDto table = TableDto.builder().tableUuid("uuid").build();
    assertThat(analyzer.isEnabled(table)).isFalse();
  }

  // --- shouldSchedule: no existing op ---

  @Test
  void shouldSchedule_noOp_noHistory_returnsTrue() {
    assertThat(
            analyzer.shouldSchedule(
                table(true, "PRIMARY_TABLE"), Optional.empty(), Optional.empty()))
        .isTrue();
  }

  @Test
  void shouldSchedule_noOp_successHistoryAfterCooldown_returnsTrue() {
    Instant longAgo = Instant.now().minus(TEST_SUCCESS_INTERVAL).minusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                table(true, "PRIMARY_TABLE"),
                Optional.empty(),
                Optional.of(historyWithStatus(HistoryStatusDto.SUCCESS, longAgo))))
        .isTrue();
  }

  @Test
  void shouldSchedule_noOp_successHistoryBeforeCooldown_returnsFalse() {
    Instant recent = Instant.now().minus(TEST_SUCCESS_INTERVAL).plusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                table(true, "PRIMARY_TABLE"),
                Optional.empty(),
                Optional.of(historyWithStatus(HistoryStatusDto.SUCCESS, recent))))
        .isFalse();
  }

  @Test
  void shouldSchedule_noOp_failedHistoryAfterRetry_returnsTrue() {
    Instant longAgo = Instant.now().minus(TEST_FAILURE_INTERVAL).minusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                table(true, "PRIMARY_TABLE"),
                Optional.empty(),
                Optional.of(historyWithStatus(HistoryStatusDto.FAILED, longAgo))))
        .isTrue();
  }

  @Test
  void shouldSchedule_noOp_failedHistoryBeforeRetry_returnsFalse() {
    Instant recent = Instant.now().minus(TEST_FAILURE_INTERVAL).plusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                table(true, "PRIMARY_TABLE"),
                Optional.empty(),
                Optional.of(historyWithStatus(HistoryStatusDto.FAILED, recent))))
        .isFalse();
  }

  // --- shouldSchedule: active op (non-CANCELED) → analyzer stays out ---

  @Test
  void shouldSchedule_pending_returnsFalse() {
    assertThat(
            analyzer.shouldSchedule(
                table(true, "PRIMARY_TABLE"),
                Optional.of(opWithStatus(OperationStatusDto.PENDING)),
                Optional.empty()))
        .isFalse();
  }

  @Test
  void shouldSchedule_scheduling_returnsFalse() {
    assertThat(
            analyzer.shouldSchedule(
                table(true, "PRIMARY_TABLE"),
                Optional.of(opWithStatus(OperationStatusDto.SCHEDULING)),
                Optional.empty()))
        .isFalse();
  }

  @Test
  void shouldSchedule_scheduled_returnsFalse_regardlessOfHistory() {
    Instant historyAt = Instant.now().minus(TEST_SUCCESS_INTERVAL).minusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                table(true, "PRIMARY_TABLE"),
                Optional.of(opWithStatus(OperationStatusDto.SCHEDULED)),
                Optional.of(historyWithStatus(HistoryStatusDto.SUCCESS, historyAt))))
        .isFalse();
  }

  // --- shouldSchedule: CANCELED → cadence on history ---

  @Test
  void shouldSchedule_canceled_successHistoryAfterCooldown_returnsTrue() {
    Instant longAgo = Instant.now().minus(TEST_SUCCESS_INTERVAL).minusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                table(true, "PRIMARY_TABLE"),
                Optional.of(opWithStatus(OperationStatusDto.CANCELED)),
                Optional.of(historyWithStatus(HistoryStatusDto.SUCCESS, longAgo))))
        .isTrue();
  }

  @Test
  void shouldSchedule_canceled_noHistory_returnsTrue() {
    assertThat(
            analyzer.shouldSchedule(
                table(true, "PRIMARY_TABLE"),
                Optional.of(opWithStatus(OperationStatusDto.CANCELED)),
                Optional.empty()))
        .isTrue();
  }

  // --- helpers ---

  private TableDto table(boolean enabled, String tableType) {
    Map<String, String> props = new HashMap<>();
    props.put(
        CadenceBasedSortStatsCollectionAnalyzer.SORT_STATS_COLLECTION_ENABLED_PROPERTY,
        Boolean.toString(enabled));
    props.put(CadenceBasedSortStatsCollectionAnalyzer.TABLE_TYPE_PROPERTY, tableType);
    return TableDto.builder()
        .tableUuid("test-uuid")
        .databaseName("db1")
        .tableId("tbl1")
        .tableProperties(props)
        .build();
  }

  private TableOperationDto opWithStatus(OperationStatusDto status) {
    return TableOperationDto.builder().status(status).build();
  }

  private TableOperationsHistoryDto historyWithStatus(
      HistoryStatusDto status, Instant completedAt) {
    return TableOperationsHistoryDto.builder()
        .id("hist-id")
        .tableUuid("test-uuid")
        .operationType(OperationTypeDto.SORT_STATS_COLLECTION)
        .completedAt(completedAt)
        .status(status)
        .build();
  }
}
