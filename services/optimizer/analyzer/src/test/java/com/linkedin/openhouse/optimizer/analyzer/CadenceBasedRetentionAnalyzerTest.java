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

class CadenceBasedRetentionAnalyzerTest {

  private static final Duration TEST_SUCCESS_INTERVAL = Duration.ofHours(24);
  private static final Duration TEST_FAILURE_INTERVAL = Duration.ofHours(1);

  /** A policies blob with a retention block whose columnPattern names a string time column. */
  private static final String ELIGIBLE_POLICIES =
      "{\"retention\":{\"count\":3,\"granularity\":\"DAY\","
          + "\"columnPattern\":{\"columnName\":\"datepartition\",\"pattern\":\"yyyy-MM-dd\"}}}";

  /** Retention configured but no columnPattern — i.e. a non-partitioned string-less table. */
  private static final String NON_PARTITIONED_POLICIES =
      "{\"retention\":{\"count\":3,\"granularity\":\"DAY\"}}";

  /** Policies present but no retention block at all — retention-unset. */
  private static final String RETENTION_UNSET_POLICIES = "{\"sharingEnabled\":true}";

  private CadenceBasedRetentionAnalyzer analyzer;

  @BeforeEach
  void setUp() {
    analyzer =
        new CadenceBasedRetentionAnalyzer(
            new CadencePolicy(TEST_SUCCESS_INTERVAL, TEST_FAILURE_INTERVAL));
  }

  // --- getOperationType ---

  @Test
  void getOperationType_isRetention() {
    assertThat(analyzer.getOperationType()).isEqualTo(OperationTypeDto.RETENTION);
  }

  // --- isEnabled: opt-in flag combined with retention-eligibility guard ---

  @Test
  void isEnabled_returnsTrue_whenOptedInAndEligible() {
    assertThat(analyzer.isEnabled(table(true, ELIGIBLE_POLICIES))).isTrue();
  }

  @Test
  void isEnabled_returnsFalse_whenPropertyFalse_evenIfEligible() {
    assertThat(analyzer.isEnabled(table(false, ELIGIBLE_POLICIES))).isFalse();
  }

  @Test
  void isEnabled_returnsFalse_whenTablePropertiesEmpty() {
    TableDto table = TableDto.builder().tableUuid("uuid").build();
    assertThat(analyzer.isEnabled(table)).isFalse();
  }

  // --- isEnabled: retention-eligibility guard (opted in, but ineligible → skipped) ---

  @Test
  void isEnabled_returnsFalse_whenOptedInButNonPartitionedNoColumnPattern() {
    // Opt-in flag is set, but the table has no resolvable time column visible on the DTO.
    assertThat(analyzer.isEnabled(table(true, NON_PARTITIONED_POLICIES))).isFalse();
  }

  @Test
  void isEnabled_returnsFalse_whenOptedInButRetentionUnset() {
    assertThat(analyzer.isEnabled(table(true, RETENTION_UNSET_POLICIES))).isFalse();
  }

  @Test
  void isEnabled_returnsFalse_whenOptedInButNoPoliciesProperty() {
    assertThat(analyzer.isEnabled(tableWithOnlyEnabled(true))).isFalse();
  }

  @Test
  void isEnabled_returnsFalse_whenOptedInButPoliciesBlank() {
    assertThat(analyzer.isEnabled(table(true, "   "))).isFalse();
  }

  @Test
  void isEnabled_returnsFalse_whenOptedInButPoliciesMalformed() {
    assertThat(analyzer.isEnabled(table(true, "{not valid json"))).isFalse();
  }

  // --- shouldSchedule: no existing op (mirrors OFD) ---

  @Test
  void shouldSchedule_noOp_noHistory_returnsTrue() {
    assertThat(
            analyzer.shouldSchedule(
                table(true, ELIGIBLE_POLICIES), Optional.empty(), Optional.empty()))
        .isTrue();
  }

  @Test
  void shouldSchedule_noOp_successHistoryAfterCooldown_returnsTrue() {
    Instant longAgo = Instant.now().minus(TEST_SUCCESS_INTERVAL).minusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                table(true, ELIGIBLE_POLICIES),
                Optional.empty(),
                Optional.of(historyWithStatus(HistoryStatusDto.SUCCESS, longAgo))))
        .isTrue();
  }

  @Test
  void shouldSchedule_noOp_successHistoryBeforeCooldown_returnsFalse() {
    Instant recent = Instant.now().minus(TEST_SUCCESS_INTERVAL).plusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                table(true, ELIGIBLE_POLICIES),
                Optional.empty(),
                Optional.of(historyWithStatus(HistoryStatusDto.SUCCESS, recent))))
        .isFalse();
  }

  @Test
  void shouldSchedule_noOp_failedHistoryAfterRetry_returnsTrue() {
    Instant longAgo = Instant.now().minus(TEST_FAILURE_INTERVAL).minusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                table(true, ELIGIBLE_POLICIES),
                Optional.empty(),
                Optional.of(historyWithStatus(HistoryStatusDto.FAILED, longAgo))))
        .isTrue();
  }

  @Test
  void shouldSchedule_noOp_failedHistoryBeforeRetry_returnsFalse() {
    Instant recent = Instant.now().minus(TEST_FAILURE_INTERVAL).plusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                table(true, ELIGIBLE_POLICIES),
                Optional.empty(),
                Optional.of(historyWithStatus(HistoryStatusDto.FAILED, recent))))
        .isFalse();
  }

  // --- shouldSchedule: active op (non-CANCELED) → analyzer stays out ---

  @Test
  void shouldSchedule_pending_returnsFalse() {
    assertThat(
            analyzer.shouldSchedule(
                table(true, ELIGIBLE_POLICIES),
                Optional.of(opWithStatus(OperationStatusDto.PENDING)),
                Optional.empty()))
        .isFalse();
  }

  @Test
  void shouldSchedule_scheduling_returnsFalse() {
    assertThat(
            analyzer.shouldSchedule(
                table(true, ELIGIBLE_POLICIES),
                Optional.of(opWithStatus(OperationStatusDto.SCHEDULING)),
                Optional.empty()))
        .isFalse();
  }

  @Test
  void shouldSchedule_scheduled_returnsFalse_regardlessOfHistory() {
    Instant historyAt = Instant.now().minus(TEST_SUCCESS_INTERVAL).minusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                table(true, ELIGIBLE_POLICIES),
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
                table(true, ELIGIBLE_POLICIES),
                Optional.of(opWithStatus(OperationStatusDto.CANCELED)),
                Optional.of(historyWithStatus(HistoryStatusDto.SUCCESS, longAgo))))
        .isTrue();
  }

  @Test
  void shouldSchedule_canceled_successHistoryBeforeCooldown_returnsFalse() {
    Instant recent = Instant.now().minus(TEST_SUCCESS_INTERVAL).plusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                table(true, ELIGIBLE_POLICIES),
                Optional.of(opWithStatus(OperationStatusDto.CANCELED)),
                Optional.of(historyWithStatus(HistoryStatusDto.SUCCESS, recent))))
        .isFalse();
  }

  @Test
  void shouldSchedule_canceled_noHistory_returnsTrue() {
    assertThat(
            analyzer.shouldSchedule(
                table(true, ELIGIBLE_POLICIES),
                Optional.of(opWithStatus(OperationStatusDto.CANCELED)),
                Optional.empty()))
        .isTrue();
  }

  // --- helpers ---

  private TableDto table(boolean enabled, String policies) {
    Map<String, String> props = new HashMap<>();
    props.put(CadenceBasedRetentionAnalyzer.RETENTION_ENABLED_PROPERTY, Boolean.toString(enabled));
    props.put(CadenceBasedRetentionAnalyzer.POLICIES_PROPERTY, policies);
    return TableDto.builder()
        .tableUuid("test-uuid")
        .databaseName("db1")
        .tableId("tbl1")
        .tableProperties(props)
        .build();
  }

  private TableDto tableWithOnlyEnabled(boolean enabled) {
    return TableDto.builder()
        .tableUuid("test-uuid")
        .databaseName("db1")
        .tableId("tbl1")
        .tableProperties(
            Map.of(
                CadenceBasedRetentionAnalyzer.RETENTION_ENABLED_PROPERTY,
                Boolean.toString(enabled)))
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
        .operationType(OperationTypeDto.RETENTION)
        .completedAt(completedAt)
        .status(status)
        .build();
  }
}
