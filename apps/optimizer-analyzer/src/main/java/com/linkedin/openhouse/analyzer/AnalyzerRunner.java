package com.linkedin.openhouse.analyzer;

import com.linkedin.openhouse.analyzer.client.OptimizerServiceClient;
import com.linkedin.openhouse.analyzer.client.TablesServiceClient;
import com.linkedin.openhouse.analyzer.model.TableOperationView;
import com.linkedin.openhouse.tables.client.model.GetTableResponseBody;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

    for (OperationAnalyzer analyzer : analyzers) {
      String operationType = analyzer.getOperationType();
      Map<String, TableOperationView> opsByUuid =
          optimizerClient.getOperationsByType(operationType);
      log.info("Analyzer {} found {} active operations", operationType, opsByUuid.size());

      for (String database : databases) {
        List<GetTableResponseBody> tables = tablesClient.getAllTables(database);
        for (GetTableResponseBody table : tables) {
          if (!analyzer.isEnabled(table)) {
            continue;
          }
          String tableUuid = table.getTableUUID();
          if (tableUuid == null) {
            log.warn("Table {}.{} has no UUID, skipping", database, table.getTableId());
            continue;
          }
          Optional<TableOperationView> currentOp = Optional.ofNullable(opsByUuid.get(tableUuid));
          if (analyzer.shouldSchedule(table, currentOp)) {
            String operationId =
                currentOp.map(TableOperationView::getId).orElse(UUID.randomUUID().toString());
            optimizerClient.upsertOperation(
                operationId, tableUuid, database, table.getTableId(), operationType);
            log.info(
                "Upserted {} operation {} for table {}.{}",
                operationType,
                operationId,
                database,
                table.getTableId());
          }
        }
      }
    }
    log.info("Analysis complete");
  }
}
