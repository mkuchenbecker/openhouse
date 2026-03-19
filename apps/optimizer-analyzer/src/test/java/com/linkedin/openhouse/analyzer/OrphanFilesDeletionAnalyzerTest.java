package com.linkedin.openhouse.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.linkedin.openhouse.analyzer.model.TableOperationRecord;
import com.linkedin.openhouse.analyzer.model.TableSummary;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrphanFilesDeletionAnalyzerTest {

  private static final Duration SUCCESS_INTERVAL = Duration.ofHours(24);
  private static final Duration FAILURE_INTERVAL = Duration.ofHours(1);
  private static final Duration SCHEDULED_TIMEOUT = Duration.ofHours(6);

  private OrphanFilesDeletionAnalyzer analyzer;

  @BeforeEach
  void setUp() {
    analyzer =
        new OrphanFilesDeletionAnalyzer(
            new CadencePolicy(SUCCESS_INTERVAL, FAILURE_INTERVAL, SCHEDULED_TIMEOUT));
  }

  // --- isEnabled ---

  @Test
  void isEnabled_returnsTrue_whenPropertySet() {
    assertThat(analyzer.isEnabled(tableWithProperty("true"))).isTrue();
  }

  @Test
  void isEnabled_returnsFalse_whenPropertyAbsent() {
    assertThat(analyzer.isEnabled(tableWithProperty(null))).isFalse();
  }

  @Test
  void isEnabled_returnsFalse_whenPropertyFalse() {
    assertThat(analyzer.isEnabled(tableWithProperty("false"))).isFalse();
  }

  @Test
  void isEnabled_returnsFalse_whenTablePropertiesEmpty() {
    TableSummary table = TableSummary.builder().tableUuid("uuid").build();
    assertThat(analyzer.isEnabled(table)).isFalse();
  }

  // --- shouldSchedule ---

  @Test
  void shouldSchedule_noExistingOp_returnsTrue() {
    assertThat(analyzer.shouldSchedule(tableWithProperty("true"), Optional.empty())).isTrue();
  }

  @Test
  void shouldSchedule_pending_returnsTrue() {
    assertThat(
            analyzer.shouldSchedule(
                tableWithProperty("true"), Optional.of(opWithStatus("PENDING", null))))
        .isTrue();
  }

  @Test
  void shouldSchedule_scheduledWithinTimeout_returnsFalse() {
    Instant recentlyScheduled = Instant.now().minus(SCHEDULED_TIMEOUT).plusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                tableWithProperty("true"),
                Optional.of(opWithStatus("SCHEDULED", recentlyScheduled))))
        .isFalse();
  }

  @Test
  void shouldSchedule_scheduledPastTimeout_returnsTrue() {
    Instant longAgo = Instant.now().minus(SCHEDULED_TIMEOUT).minusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                tableWithProperty("true"), Optional.of(opWithStatus("SCHEDULED", longAgo))))
        .isTrue();
  }

  @Test
  void shouldSchedule_scheduledWithNullScheduledAt_returnsTrue() {
    assertThat(
            analyzer.shouldSchedule(
                tableWithProperty("true"), Optional.of(opWithStatus("SCHEDULED", null))))
        .isTrue();
  }

  @Test
  void shouldSchedule_successBeforeInterval_returnsFalse() {
    Instant recentlyScheduled = Instant.now().minus(SUCCESS_INTERVAL).plusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                tableWithProperty("true"), Optional.of(opWithStatus("SUCCESS", recentlyScheduled))))
        .isFalse();
  }

  @Test
  void shouldSchedule_successAfterInterval_returnsTrue() {
    Instant longAgo = Instant.now().minus(SUCCESS_INTERVAL).minusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                tableWithProperty("true"), Optional.of(opWithStatus("SUCCESS", longAgo))))
        .isTrue();
  }

  @Test
  void shouldSchedule_failedBeforeInterval_returnsFalse() {
    Instant recentlyScheduled = Instant.now().minus(FAILURE_INTERVAL).plusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                tableWithProperty("true"), Optional.of(opWithStatus("FAILED", recentlyScheduled))))
        .isFalse();
  }

  @Test
  void shouldSchedule_failedAfterInterval_returnsTrue() {
    Instant longAgo = Instant.now().minus(FAILURE_INTERVAL).minusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                tableWithProperty("true"), Optional.of(opWithStatus("FAILED", longAgo))))
        .isTrue();
  }

  @Test
  void shouldSchedule_successWithNullScheduledAt_returnsTrue() {
    assertThat(
            analyzer.shouldSchedule(
                tableWithProperty("true"), Optional.of(opWithStatus("SUCCESS", null))))
        .isTrue();
  }

  // --- helpers ---

  private TableSummary tableWithProperty(String value) {
    Map<String, String> props =
        value == null
            ? Collections.emptyMap()
            : Map.of(OrphanFilesDeletionAnalyzer.OFD_ENABLED_PROPERTY, value);
    return TableSummary.builder()
        .tableUuid("test-uuid")
        .databaseId("db1")
        .tableId("tbl1")
        .tableProperties(props)
        .build();
  }

  private TableOperationRecord opWithStatus(String status, Instant scheduledAt) {
    TableOperationRecord op = new TableOperationRecord();
    op.setStatus(status);
    op.setScheduledAt(scheduledAt);
    return op;
  }
}
