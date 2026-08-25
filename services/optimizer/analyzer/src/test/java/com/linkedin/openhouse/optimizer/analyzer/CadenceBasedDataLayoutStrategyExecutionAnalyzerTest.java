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

class CadenceBasedDataLayoutStrategyExecutionAnalyzerTest {

  private static final Duration TEST_SUCCESS_INTERVAL = Duration.ofHours(24);
  private static final Duration TEST_FAILURE_INTERVAL = Duration.ofHours(1);

  /** A realistic (escaped) single-strategy JSON blob, as the generation half would store it. */
  private static final String STRATEGY_JSON =
      "[{\\\"score\\\":8.5,\\\"cost\\\":5.5,\\\"gain\\\":47.0,\\\"config\\\":{\\\"targetByteSize\\\":526385152}}]";

  private CadenceBasedDataLayoutStrategyExecutionAnalyzer analyzer;

  @BeforeEach
  void setUp() {
    analyzer =
        new CadenceBasedDataLayoutStrategyExecutionAnalyzer(
            new CadencePolicy(TEST_SUCCESS_INTERVAL, TEST_FAILURE_INTERVAL));
  }

  @Test
  void operationType_isExecution() {
    assertThat(analyzer.getOperationType())
        .isEqualTo(OperationTypeDto.DATA_LAYOUT_STRATEGY_EXECUTION);
  }

  // --- isEnabled: requires opt-in AND a present, non-empty strategy (the gen→exec signal) ---

  @Test
  void isEnabled_true_whenOptedInAndStrategyPresent() {
    assertThat(analyzer.isEnabled(table(true, STRATEGY_JSON))).isTrue();
  }

  @Test
  void isEnabled_false_whenOptedInButNoStrategyProperty() {
    assertThat(analyzer.isEnabled(table(true, null))).isFalse();
  }

  @Test
  void isEnabled_false_whenOptedInButStrategyIsEmptyList() {
    assertThat(analyzer.isEnabled(table(true, "[]"))).isFalse();
  }

  @Test
  void isEnabled_false_whenOptedInButStrategyIsBlank() {
    assertThat(analyzer.isEnabled(table(true, "   "))).isFalse();
  }

  @Test
  void isEnabled_false_whenStrategyPresentButNotOptedIn() {
    assertThat(analyzer.isEnabled(table(false, STRATEGY_JSON))).isFalse();
  }

  @Test
  void isEnabled_false_whenTablePropertiesEmpty() {
    assertThat(analyzer.isEnabled(TableDto.builder().tableUuid("uuid").build())).isFalse();
  }

  // --- shouldSchedule: cadence contract (delegates to CadencePolicy) ---

  @Test
  void shouldSchedule_noOp_noHistory_returnsTrue() {
    assertThat(
            analyzer.shouldSchedule(table(true, STRATEGY_JSON), Optional.empty(), Optional.empty()))
        .isTrue();
  }

  @Test
  void shouldSchedule_noOp_successHistoryAfterCooldown_returnsTrue() {
    Instant longAgo = Instant.now().minus(TEST_SUCCESS_INTERVAL).minusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                table(true, STRATEGY_JSON),
                Optional.empty(),
                Optional.of(historyWithStatus(HistoryStatusDto.SUCCESS, longAgo))))
        .isTrue();
  }

  @Test
  void shouldSchedule_noOp_successHistoryBeforeCooldown_returnsFalse() {
    Instant recent = Instant.now().minus(TEST_SUCCESS_INTERVAL).plusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                table(true, STRATEGY_JSON),
                Optional.empty(),
                Optional.of(historyWithStatus(HistoryStatusDto.SUCCESS, recent))))
        .isFalse();
  }

  @Test
  void shouldSchedule_noOp_failedHistoryAfterRetry_returnsTrue() {
    Instant longAgo = Instant.now().minus(TEST_FAILURE_INTERVAL).minusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                table(true, STRATEGY_JSON),
                Optional.empty(),
                Optional.of(historyWithStatus(HistoryStatusDto.FAILED, longAgo))))
        .isTrue();
  }

  @Test
  void shouldSchedule_pending_returnsFalse() {
    assertThat(
            analyzer.shouldSchedule(
                table(true, STRATEGY_JSON),
                Optional.of(opWithStatus(OperationStatusDto.PENDING)),
                Optional.empty()))
        .isFalse();
  }

  @Test
  void shouldSchedule_scheduled_returnsFalse_regardlessOfHistory() {
    Instant historyAt = Instant.now().minus(TEST_SUCCESS_INTERVAL).minusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                table(true, STRATEGY_JSON),
                Optional.of(opWithStatus(OperationStatusDto.SCHEDULED)),
                Optional.of(historyWithStatus(HistoryStatusDto.SUCCESS, historyAt))))
        .isFalse();
  }

  @Test
  void shouldSchedule_canceled_noHistory_returnsTrue() {
    assertThat(
            analyzer.shouldSchedule(
                table(true, STRATEGY_JSON),
                Optional.of(opWithStatus(OperationStatusDto.CANCELED)),
                Optional.empty()))
        .isTrue();
  }

  // --- helpers ---

  private TableDto table(boolean enabled, String strategyJson) {
    Map<String, String> props = new HashMap<>();
    props.put(
        CadenceBasedDataLayoutStrategyExecutionAnalyzer.DLS_EXECUTION_ENABLED_PROPERTY,
        Boolean.toString(enabled));
    if (strategyJson != null) {
      props.put(
          CadenceBasedDataLayoutStrategyExecutionAnalyzer.DATA_LAYOUT_STRATEGIES_PROPERTY_KEY,
          strategyJson);
    }
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
        .operationType(OperationTypeDto.DATA_LAYOUT_STRATEGY_EXECUTION)
        .completedAt(completedAt)
        .status(status)
        .build();
  }
}
