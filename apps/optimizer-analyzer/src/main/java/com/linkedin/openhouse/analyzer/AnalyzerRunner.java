package com.linkedin.openhouse.analyzer;

import com.linkedin.openhouse.analyzer.client.OptimizerServiceClient;
import com.linkedin.openhouse.analyzer.client.TablesServiceClient;
import com.linkedin.openhouse.analyzer.model.TableOperationRecord;
import com.linkedin.openhouse.analyzer.model.TableSummary;
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
 * Runs the analysis loop once per process invocation. For each {@link OperationAnalyzer},
 * bulk-loads all active operation records (one GET to the Optimizer Service), then iterates every
 * table (one GET per database to the Tables Service) and upserts records for eligible tables.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyzerRunner implements CommandLineRunner {

  private final List<OperationAnalyzer> analyzers;
  private final TablesServiceClient tablesClient;
  private final OptimizerServiceClient optimizerClient;

  @Override
  public void run(String... args) {
    List<String> databases = tablesClient.getDatabases();
    log.info("Found {} databases", databases.size());

    // Fetch all tables once; reused across all analyzers.
    List<TableSummary> allTables =
        databases.stream()
            .flatMap(db -> tablesClient.getAllTables(db).stream())
            .collect(Collectors.toList());

    analyzers.forEach(
        analyzer -> {
          String operationType = analyzer.getOperationType();
          Map<String, TableOperationRecord> opsByUuid =
              optimizerClient.getOperationsByType(operationType);
          log.info("Analyzer {} found {} active operations", operationType, opsByUuid.size());

          allTables.stream()
              .filter(analyzer::isEnabled)
              .filter(table -> table.getTableUuid() != null)
              .forEach(
                  table -> {
                    String tableUuid = table.getTableUuid();
                    Optional<TableOperationRecord> currentOp =
                        Optional.ofNullable(opsByUuid.get(tableUuid));
                    if (analyzer.shouldSchedule(table, currentOp)) {
                      String operationId =
                          currentOp
                              .map(TableOperationRecord::getId)
                              .orElse(UUID.randomUUID().toString());
                      optimizerClient.upsertOperation(
                          operationId,
                          tableUuid,
                          table.getDatabaseId(),
                          table.getTableId(),
                          operationType);
                      log.info(
                          "Upserted {} operation {} for table {}.{}",
                          operationType,
                          operationId,
                          table.getDatabaseId(),
                          table.getTableId());
                    }
                  });
        });

    log.info("Analysis complete");
  }
}
