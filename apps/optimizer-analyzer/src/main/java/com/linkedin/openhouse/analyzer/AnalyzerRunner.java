package com.linkedin.openhouse.analyzer;

import com.linkedin.openhouse.analyzer.entity.TableOperationEntity;
import com.linkedin.openhouse.analyzer.entity.TableStatsEntity;
import com.linkedin.openhouse.analyzer.model.TableOperationRecord;
import com.linkedin.openhouse.analyzer.model.TableSummary;
import com.linkedin.openhouse.analyzer.repository.TableOperationsRepository;
import com.linkedin.openhouse.analyzer.repository.TableStatsRepository;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Core analysis logic. For each {@link OperationAnalyzer}, loads active operation records from the
 * optimizer DB via JPA, then iterates every table in {@code table_stats} and inserts PENDING rows
 * for eligible tables.
 *
 * <p>Invoked by {@link AnalyzerApplication}'s {@code CommandLineRunner} once per process run.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyzerRunner {

  private static final Set<String> ACTIVE_STATUSES = Set.of("PENDING", "SCHEDULED");

  private final List<OperationAnalyzer> analyzers;
  private final TableStatsRepository statsRepo;
  private final TableOperationsRepository operationsRepo;

  /** Run the full analysis loop once. */
  public void analyze() {
    List<TableSummary> allTables =
        statsRepo.findAll().stream().map(this::toSummary).collect(Collectors.toList());
    log.info("Found {} tables in optimizer table_stats", allTables.size());

    analyzers.forEach(
        analyzer -> {
          String operationType = analyzer.getOperationType();

          Map<String, TableOperationRecord> opsByUuid =
              operationsRepo.findByTypeAndStatuses(operationType, ACTIVE_STATUSES).stream()
                  .filter(e -> e.getTableUuid() != null)
                  .collect(
                      Collectors.toMap(
                          TableOperationEntity::getTableUuid,
                          AnalyzerRunner::toRecord,
                          (a, b) -> b));
          log.info("Analyzer {} found {} active operations", operationType, opsByUuid.size());

          allTables.stream()
              .filter(analyzer::isEnabled)
              .filter(table -> table.getTableUuid() != null)
              .forEach(
                  table -> {
                    String tableUuid = table.getTableUuid();
                    Optional<TableOperationRecord> currentOp =
                        Optional.ofNullable(opsByUuid.get(tableUuid));
                    if (analyzer.shouldSchedule(table, currentOp) && currentOp.isEmpty()) {
                      TableOperationEntity entity =
                          TableOperationEntity.builder()
                              .id(UUID.randomUUID().toString())
                              .tableUuid(tableUuid)
                              .databaseName(table.getDatabaseId())
                              .tableName(table.getTableId())
                              .operationType(operationType)
                              .status("PENDING")
                              .createdAt(Instant.now())
                              .build();
                      operationsRepo.save(entity);
                      log.info(
                          "Created PENDING {} operation for table {}.{}",
                          operationType,
                          table.getDatabaseId(),
                          table.getTableId());
                    }
                  });
        });

    log.info("Analysis complete");
  }

  private TableSummary toSummary(TableStatsEntity e) {
    return TableSummary.builder()
        .tableUuid(e.getTableUuid())
        .databaseId(e.getDatabaseId())
        .tableId(e.getTableName())
        .tableProperties(
            e.getTableProperties() != null ? e.getTableProperties() : Collections.emptyMap())
        .stats(e.getStats())
        .build();
  }

  private static TableOperationRecord toRecord(TableOperationEntity e) {
    TableOperationRecord r = new TableOperationRecord();
    r.setId(e.getId());
    r.setTableUuid(e.getTableUuid());
    r.setDatabaseName(e.getDatabaseName());
    r.setTableName(e.getTableName());
    r.setOperationType(e.getOperationType());
    r.setStatus(e.getStatus());
    r.setCreatedAt(e.getCreatedAt());
    r.setScheduledAt(e.getScheduledAt());
    return r;
  }
}
