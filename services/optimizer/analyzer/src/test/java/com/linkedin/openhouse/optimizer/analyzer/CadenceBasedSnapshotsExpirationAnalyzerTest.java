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

class CadenceBasedSnapshotsExpirationAnalyzerTest {

  private static final Duration TEST_SUCCESS_INTERVAL = Duration.ofHours(24);
  private static final Duration TEST_FAILURE_INTERVAL = Duration.ofHours(1);

  private CadenceBasedSnapshotsExpirationAnalyzer analyzer;

  @BeforeEach
  void setUp() {
    analyzer =
        new CadenceBasedSnapshotsExpirationAnalyzer(
            new CadencePolicy(TEST_SUCCESS_INTERVAL, TEST_FAILURE_INTERVAL));
  }

  // --- getOperationType ---

  @Test
  void getOperationType_isSnapshotsExpiration() {
    assertThat(analyzer.getOperationType()).isEqualTo(OperationTypeDto.SNAPSHOTS_EXPIRATION);
  }

  // --- isEnabled ---

  @Test
  void isEnabled_returnsTrue_whenPropertySet_andPrimaryByDefault() {
    assertThat(analyzer.isEnabled(tableWithProperty(true))).isTrue();
  }

  @Test
  void isEnabled_returnsFalse_whenPropertyFalse() {
    assertThat(analyzer.isEnabled(tableWithProperty(false))).isFalse();
  }

  @Test
  void isEnabled_returnsFalse_whenTablePropertiesEmpty() {
    TableDto table = TableDto.builder().tableUuid("uuid").build();
    assertThat(analyzer.isEnabled(table)).isFalse();
  }

  @Test
  void isEnabled_returnsTrue_whenExplicitlyPrimary() {
    assertThat(analyzer.isEnabled(tableWithPropertyAndType(true, "PRIMARY_TABLE"))).isTrue();
  }

  @Test
  void isEnabled_returnsFalse_whenReplica_evenIfOptedIn() {
    assertThat(analyzer.isEnabled(tableWithPropertyAndType(true, "REPLICA_TABLE"))).isFalse();
  }

  // --- shouldSchedule: no existing op ---

  @Test
  void shouldSchedule_noOp_noHistory_returnsTrue() {
    assertThat(analyzer.shouldSchedule(tableWithProperty(true), Optional.empty(), Optional.empty()))
        .isTrue();
  }

  @Test
  void shouldSchedule_noOp_successHistoryAfterCooldown_returnsTrue() {
    Instant longAgo = Instant.now().minus(TEST_SUCCESS_INTERVAL).minusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                tableWithProperty(true),
                Optional.empty(),
                Optional.of(historyWithStatus(HistoryStatusDto.SUCCESS, longAgo))))
        .isTrue();
  }

  @Test
  void shouldSchedule_noOp_successHistoryBeforeCooldown_returnsFalse() {
    Instant recent = Instant.now().minus(TEST_SUCCESS_INTERVAL).plusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                tableWithProperty(true),
                Optional.empty(),
                Optional.of(historyWithStatus(HistoryStatusDto.SUCCESS, recent))))
        .isFalse();
  }

  @Test
  void shouldSchedule_noOp_failedHistoryAfterRetry_returnsTrue() {
    Instant longAgo = Instant.now().minus(TEST_FAILURE_INTERVAL).minusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                tableWithProperty(true),
                Optional.empty(),
                Optional.of(historyWithStatus(HistoryStatusDto.FAILED, longAgo))))
        .isTrue();
  }

  @Test
  void shouldSchedule_noOp_failedHistoryBeforeRetry_returnsFalse() {
    Instant recent = Instant.now().minus(TEST_FAILURE_INTERVAL).plusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                tableWithProperty(true),
                Optional.empty(),
                Optional.of(historyWithStatus(HistoryStatusDto.FAILED, recent))))
        .isFalse();
  }

  // --- shouldSchedule: active op (non-CANCELED) → analyzer stays out ---

  @Test
  void shouldSchedule_pending_returnsFalse() {
    assertThat(
            analyzer.shouldSchedule(
                tableWithProperty(true),
                Optional.of(opWithStatus(OperationStatusDto.PENDING)),
                Optional.empty()))
        .isFalse();
  }

  @Test
  void shouldSchedule_scheduling_returnsFalse() {
    assertThat(
            analyzer.shouldSchedule(
                tableWithProperty(true),
                Optional.of(opWithStatus(OperationStatusDto.SCHEDULING)),
                Optional.empty()))
        .isFalse();
  }

  @Test
  void shouldSchedule_scheduled_returnsFalse_regardlessOfHistory() {
    Instant historyAt = Instant.now().minus(TEST_SUCCESS_INTERVAL).minusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                tableWithProperty(true),
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
                tableWithProperty(true),
                Optional.of(opWithStatus(OperationStatusDto.CANCELED)),
                Optional.of(historyWithStatus(HistoryStatusDto.SUCCESS, longAgo))))
        .isTrue();
  }

  @Test
  void shouldSchedule_canceled_successHistoryBeforeCooldown_returnsFalse() {
    Instant recent = Instant.now().minus(TEST_SUCCESS_INTERVAL).plusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                tableWithProperty(true),
                Optional.of(opWithStatus(OperationStatusDto.CANCELED)),
                Optional.of(historyWithStatus(HistoryStatusDto.SUCCESS, recent))))
        .isFalse();
  }

  @Test
  void shouldSchedule_canceled_noHistory_returnsTrue() {
    assertThat(
            analyzer.shouldSchedule(
                tableWithProperty(true),
                Optional.of(opWithStatus(OperationStatusDto.CANCELED)),
                Optional.empty()))
        .isTrue();
  }

  // --- helpers ---

  private TableDto tableWithProperty(boolean enabled) {
    return TableDto.builder()
        .tableUuid("test-uuid")
        .databaseName("db1")
        .tableId("tbl1")
        .tableProperties(
            Map.of(
                CadenceBasedSnapshotsExpirationAnalyzer.SNAPSHOTS_EXPIRATION_ENABLED_PROPERTY,
                Boolean.toString(enabled)))
        .build();
  }

  private TableDto tableWithPropertyAndType(boolean enabled, String tableType) {
    Map<String, String> props = new HashMap<>();
    props.put(
        CadenceBasedSnapshotsExpirationAnalyzer.SNAPSHOTS_EXPIRATION_ENABLED_PROPERTY,
        Boolean.toString(enabled));
    props.put(CadenceBasedSnapshotsExpirationAnalyzer.OPENHOUSE_TABLE_TYPE_KEY, tableType);
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
        .operationType(OperationTypeDto.SNAPSHOTS_EXPIRATION)
        .completedAt(completedAt)
        .status(status)
        .build();
  }
}
