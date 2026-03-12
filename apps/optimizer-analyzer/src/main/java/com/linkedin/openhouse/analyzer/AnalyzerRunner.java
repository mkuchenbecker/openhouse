package com.linkedin.openhouse.analyzer;

import com.linkedin.openhouse.analyzer.client.HtsClient;
import com.linkedin.openhouse.analyzer.entity.TableOperationEntity;
import com.linkedin.openhouse.analyzer.model.TableOperationRecord;
import com.linkedin.openhouse.analyzer.model.TableSummary;
import com.linkedin.openhouse.analyzer.repository.TableOperationsRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Runs the analysis loop once per process invocation. For each {@link OperationAnalyzer}, loads
 * active operation records from the optimizer DB via JPA, then iterates every table returned by the
 * HTS bulk endpoint and inserts PENDING rows for eligible tables.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyzerRunner implements CommandLineRunner {

  private final List<OperationAnalyzer> analyzers;
  private final HtsClient htsClient;
  private final TableOperationsRepository repo;

  @Override
  public void run(String... args) {
    List<TableSummary> allTables = htsClient.getAllTableStats();
    log.info("Found {} tables in HTS", allTables.size());

    analyzers.forEach(
        analyzer -> {
          String operationType = analyzer.getOperationType();

          Map<String, TableOperationRecord> opsByUuid =
              repo.findActiveByType(operationType).stream()
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
                      repo.save(entity);
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
