package com.linkedin.openhouse.optimizer.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.linkedin.openhouse.optimizer.model.HistoryStatusDto;
import com.linkedin.openhouse.optimizer.model.OperationStatusDto;
import com.linkedin.openhouse.optimizer.model.OperationTypeDto;
import com.linkedin.openhouse.optimizer.model.TableDto;
import com.linkedin.openhouse.optimizer.model.TableOperationDto;
import com.linkedin.openhouse.optimizer.model.TableOperationsHistoryDto;
import com.linkedin.openhouse.optimizer.model.TableStatsDto;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StatsBasedDataCompactionAnalyzerTest {

  private static final long MIN_FILES = 100L;
  private static final long TARGET_FILE_SIZE_BYTES = 512L * 1024 * 1024; // 512 MiB
  private static final Duration TEST_SUCCESS_INTERVAL = Duration.ofHours(24);
  private static final Duration TEST_FAILURE_INTERVAL = Duration.ofHours(1);

  private StatsBasedDataCompactionAnalyzer analyzer;

  @BeforeEach
  void setUp() {
    analyzer =
        new StatsBasedDataCompactionAnalyzer(
            MIN_FILES,
            TARGET_FILE_SIZE_BYTES,
            new CadencePolicy(TEST_SUCCESS_INTERVAL, TEST_FAILURE_INTERVAL));
  }

  // --- getOperationType ---

  @Test
  void getOperationType_isDataCompaction() {
    assertThat(analyzer.getOperationType()).isEqualTo(OperationTypeDto.DATA_COMPACTION);
  }

  // --- isEnabled ---

  @Test
  void isEnabled_returnsTrue_whenPropertySet() {
    assertThat(analyzer.isEnabled(table(true, fragmentedStats()))).isTrue();
  }

  @Test
  void isEnabled_returnsFalse_whenPropertyFalse() {
    assertThat(analyzer.isEnabled(table(false, fragmentedStats()))).isFalse();
  }

  @Test
  void isEnabled_returnsFalse_whenTablePropertiesEmpty() {
    TableDto table = TableDto.builder().tableUuid("uuid").build();
    assertThat(analyzer.isEnabled(table)).isFalse();
  }

  // --- shouldSchedule: stats-driven trigger ---

  @Test
  void shouldSchedule_fires_whenManySmallFiles() {
    // 1000 files averaging 1 MiB each — well below the 512 MiB target, above the 100-file floor.
    assertThat(
            analyzer.shouldSchedule(
                table(true, stats(1000L, 1000L * 1024 * 1024)), Optional.empty(), Optional.empty()))
        .isTrue();
  }

  @Test
  void shouldSchedule_skips_whenFileCountBelowMinFiles() {
    // Only 50 files — below the 100-file floor — even though they are tiny.
    assertThat(
            analyzer.shouldSchedule(
                table(true, stats(50L, 50L * 1024 * 1024)), Optional.empty(), Optional.empty()))
        .isFalse();
  }

  @Test
  void shouldSchedule_skips_whenAverageFileSizeAtOrAboveTarget() {
    // 200 files each ~1 GiB — average file size exceeds the 512 MiB target, nothing to gain.
    assertThat(
            analyzer.shouldSchedule(
                table(true, stats(200L, 200L * 1024 * 1024 * 1024)),
                Optional.empty(),
                Optional.empty()))
        .isFalse();
  }

  @Test
  void shouldSchedule_skips_whenNoSnapshot() {
    TableDto table =
        table(true, TableStatsDto.builder().build()); // stats present but snapshot null
    assertThat(analyzer.shouldSchedule(table, Optional.empty(), Optional.empty())).isFalse();
  }

  @Test
  void shouldSchedule_skips_whenNoStats() {
    TableDto table =
        TableDto.builder()
            .tableUuid("uuid")
            .tableProperties(
                Map.of(StatsBasedDataCompactionAnalyzer.DATA_COMPACTION_ENABLED_PROPERTY, "true"))
            .build();
    assertThat(analyzer.shouldSchedule(table, Optional.empty(), Optional.empty())).isFalse();
  }

  // --- shouldSchedule: cadence floor gates an otherwise-fragmented table ---

  @Test
  void shouldSchedule_skips_whenRecentSuccess_evenIfFragmented() {
    Instant recent = Instant.now().minus(TEST_SUCCESS_INTERVAL).plusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                table(true, fragmentedStats()),
                Optional.empty(),
                Optional.of(historyWithStatus(HistoryStatusDto.SUCCESS, recent))))
        .isFalse();
  }

  @Test
  void shouldSchedule_fires_whenSuccessCooldownElapsed_andFragmented() {
    Instant longAgo = Instant.now().minus(TEST_SUCCESS_INTERVAL).minusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                table(true, fragmentedStats()),
                Optional.empty(),
                Optional.of(historyWithStatus(HistoryStatusDto.SUCCESS, longAgo))))
        .isTrue();
  }

  @Test
  void shouldSchedule_skips_whenRecentFailure_evenIfFragmented() {
    Instant recent = Instant.now().minus(TEST_FAILURE_INTERVAL).plusSeconds(60);
    assertThat(
            analyzer.shouldSchedule(
                table(true, fragmentedStats()),
                Optional.empty(),
                Optional.of(historyWithStatus(HistoryStatusDto.FAILED, recent))))
        .isFalse();
  }

  // --- shouldSchedule: active-op guard ---

  @Test
  void shouldSchedule_skips_whenActiveOpPending_evenIfFragmented() {
    assertThat(
            analyzer.shouldSchedule(
                table(true, fragmentedStats()),
                Optional.of(opWithStatus(OperationStatusDto.PENDING)),
                Optional.empty()))
        .isFalse();
  }

  @Test
  void shouldSchedule_skips_whenActiveOpScheduled_evenIfFragmented() {
    assertThat(
            analyzer.shouldSchedule(
                table(true, fragmentedStats()),
                Optional.of(opWithStatus(OperationStatusDto.SCHEDULED)),
                Optional.empty()))
        .isFalse();
  }

  @Test
  void shouldSchedule_fires_whenCanceledOp_andFragmented() {
    // A CANCELED op does not block; with no history and a fragmented layout, schedule.
    assertThat(
            analyzer.shouldSchedule(
                table(true, fragmentedStats()),
                Optional.of(opWithStatus(OperationStatusDto.CANCELED)),
                Optional.empty()))
        .isTrue();
  }

  // --- helpers ---

  private static TableStatsDto fragmentedStats() {
    // 500 files averaging ~2 MiB each — far below the 512 MiB target.
    return stats(500L, 500L * 2 * 1024 * 1024);
  }

  private static TableStatsDto stats(Long numFiles, long sizeBytes) {
    return TableStatsDto.builder()
        .snapshot(
            TableStatsDto.SnapshotMetrics.builder()
                .numCurrentFiles(numFiles)
                .tableSizeBytes(sizeBytes)
                .build())
        .build();
  }

  private static TableDto table(boolean enabled, TableStatsDto stats) {
    return TableDto.builder()
        .tableUuid("test-uuid")
        .databaseName("db1")
        .tableId("tbl1")
        .tableProperties(
            enabled
                ? Map.of(StatsBasedDataCompactionAnalyzer.DATA_COMPACTION_ENABLED_PROPERTY, "true")
                : Collections.emptyMap())
        .stats(stats)
        .build();
  }

  private static TableOperationDto opWithStatus(OperationStatusDto status) {
    return TableOperationDto.builder().status(status).build();
  }

  private static TableOperationsHistoryDto historyWithStatus(
      HistoryStatusDto status, Instant completedAt) {
    return TableOperationsHistoryDto.builder()
        .id("hist-id")
        .tableUuid("test-uuid")
        .operationType(OperationTypeDto.DATA_COMPACTION)
        .completedAt(completedAt)
        .status(status)
        .build();
  }
}
