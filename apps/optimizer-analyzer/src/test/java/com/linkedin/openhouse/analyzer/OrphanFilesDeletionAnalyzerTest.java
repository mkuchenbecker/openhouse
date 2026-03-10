package com.linkedin.openhouse.analyzer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.linkedin.openhouse.analyzer.model.TableOperationView;
import com.linkedin.openhouse.tables.client.model.GetTableResponseBody;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrphanFilesDeletionAnalyzerTest {

  private OrphanFilesDeletionAnalyzer analyzer;

  @BeforeEach
  void setUp() {
    analyzer = new OrphanFilesDeletionAnalyzer();
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
  void isEnabled_returnsFalse_whenTablePropertiesNull() {
    GetTableResponseBody table = mock(GetTableResponseBody.class);
    when(table.getTableProperties()).thenReturn(null);
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
  void shouldSchedule_scheduled_returnsFalse() {
    assertThat(
            analyzer.shouldSchedule(
                tableWithProperty("true"), Optional.of(opWithStatus("SCHEDULED", null))))
        .isFalse();
  }

  @Test
  void shouldSchedule_successBeforeInterval_returnsFalse() {
    Instant recentlyScheduled =
        Instant.now().minus(OrphanFilesDeletionAnalyzer.SUCCESS_RETRY_INTERVAL).plusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                tableWithProperty("true"), Optional.of(opWithStatus("SUCCESS", recentlyScheduled))))
        .isFalse();
  }

  @Test
  void shouldSchedule_successAfterInterval_returnsTrue() {
    Instant longAgo =
        Instant.now().minus(OrphanFilesDeletionAnalyzer.SUCCESS_RETRY_INTERVAL).minusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                tableWithProperty("true"), Optional.of(opWithStatus("SUCCESS", longAgo))))
        .isTrue();
  }

  @Test
  void shouldSchedule_failedBeforeInterval_returnsFalse() {
    Instant recentlyScheduled =
        Instant.now().minus(OrphanFilesDeletionAnalyzer.FAILURE_RETRY_INTERVAL).plusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                tableWithProperty("true"), Optional.of(opWithStatus("FAILED", recentlyScheduled))))
        .isFalse();
  }

  @Test
  void shouldSchedule_failedAfterInterval_returnsTrue() {
    Instant longAgo =
        Instant.now().minus(OrphanFilesDeletionAnalyzer.FAILURE_RETRY_INTERVAL).minusSeconds(60);
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

  private GetTableResponseBody tableWithProperty(String value) {
    GetTableResponseBody table = mock(GetTableResponseBody.class);
    if (value == null) {
      when(table.getTableProperties()).thenReturn(Collections.emptyMap());
    } else {
      when(table.getTableProperties())
          .thenReturn(Map.of(OrphanFilesDeletionAnalyzer.OFD_ENABLED_PROPERTY, value));
    }
    return table;
  }

  private TableOperationView opWithStatus(String status, Instant scheduledAt) {
    TableOperationView op = new TableOperationView();
    op.setStatus(status);
    op.setScheduledAt(scheduledAt);
    return op;
  }
}
