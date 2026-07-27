package com.linkedin.openhouse.optimizer.analyzer;

import com.linkedin.openhouse.optimizer.db.OperationScope;
import com.linkedin.openhouse.optimizer.db.OperationType;
import com.linkedin.openhouse.optimizer.model.OperationStatusDto;
import com.linkedin.openhouse.optimizer.model.TableOperationDto;
import com.linkedin.openhouse.optimizer.model.TableOperationsHistoryDto;
import com.linkedin.openhouse.optimizer.repository.TableOperationsHistoryRepository;
import com.linkedin.openhouse.optimizer.repository.TableOperationsRepository;
import com.linkedin.openhouse.optimizer.repository.TableStatsRepository;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Database-scoped discovery loop for the directory-deletion operations — the sibling of {@link
 * AnalyzerRunner} for work that has no live table.
 *
 * <p>Where {@link AnalyzerRunner} iterates {@code table_stats} rows and keys a PENDING operation by
 * {@code table_uuid}, this runner enumerates the <em>databases</em> present in {@code table_stats}
 * (via {@link TableStatsRepository#findDistinctDatabaseNames()}) and keys a PENDING operation by
 * {@code database_name} with a null {@code table_uuid} and {@code operation_scope = DATABASE}. That
 * matches how the reference directory-deletion jobs actually discover work (per-database storage
 * scan), and is the closest the Optimizer Service can get without direct storage access.
 *
 * <p>Everything stays behind the per-operation opt-in ({@link
 * DirectoryOperationAnalyzer#isEnabled()}): a disabled operation emits nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DirectoryDeletionAnalyzerRunner {

  /** Statuses that mean "an operation is already in flight for this (database, type)". */
  private static final Set<OperationStatusDto> ACTIVE =
      EnumSet.of(
          OperationStatusDto.PENDING, OperationStatusDto.SCHEDULING, OperationStatusDto.SCHEDULED);

  private final List<DirectoryOperationAnalyzer> analyzers;
  private final TableStatsRepository statsRepo;
  private final TableOperationsRepository operationsRepo;
  private final TableOperationsHistoryRepository historyRepo;

  /** Run the discovery loop for one operation type, if a matching analyzer is registered. */
  public void analyze(com.linkedin.openhouse.optimizer.model.OperationTypeDto operationType) {
    DirectoryOperationAnalyzer analyzer =
        analyzers.stream()
            .filter(a -> a.getOperationType() == operationType)
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No directory analyzer registered for operation type " + operationType));
    if (!analyzer.isEnabled()) {
      log.info("Directory analyzer for {} is disabled; skipping", operationType);
      return;
    }
    analyzeInternal(analyzer);
  }

  @Transactional
  void analyzeInternal(DirectoryOperationAnalyzer analyzer) {
    OperationType dbType = analyzer.getOperationType().toDb();

    // Active database-scoped ops per database — a database already in flight must not be re-queued.
    Map<String, TableOperationDto> activeByDatabase =
        operationsRepo
            .findByScope(dbType, Optional.empty(), OperationScope.DATABASE, Pageable.unpaged())
            .stream()
            .map(TableOperationDto::fromRow)
            .filter(op -> ACTIVE.contains(op.getStatus()))
            .collect(
                Collectors.toMap(
                    TableOperationDto::getDatabaseName, op -> op, TableOperationDto::mostRecent));

    // Latest completed history per database, to drive the cadence check.
    Map<String, TableOperationsHistoryDto> latestHistory =
        historyRepo.findLatestByDatabaseScope(dbType, Pageable.unpaged()).stream()
            .map(TableOperationsHistoryDto::fromRow)
            .collect(
                Collectors.toMap(
                    TableOperationsHistoryDto::getDatabaseName,
                    h -> h,
                    TableOperationsHistoryDto::after));

    List<String> databases = statsRepo.findDistinctDatabaseNames();
    log.info(
        "Directory analyzer {} evaluating {} database(s)",
        analyzer.getOperationType(),
        databases.size());

    int created = 0;
    int failed = 0;
    for (String database : databases) {
      Optional<TableOperationDto> currentOp = Optional.ofNullable(activeByDatabase.get(database));
      Optional<TableOperationsHistoryDto> entry = Optional.ofNullable(latestHistory.get(database));
      if (!analyzer.shouldSchedule(database, currentOp, entry)) {
        continue;
      }
      try {
        TableOperationDto op =
            TableOperationDto.pendingForDatabase(database, analyzer.getOperationType());
        operationsRepo.save(op.toRow());
        log.debug(
            "Created PENDING {} (database-scoped) operation for database {}",
            analyzer.getOperationType(),
            database);
        created++;
      } catch (RuntimeException e) {
        log.error(
            "Failed to create PENDING {} operation for database {}: {}",
            analyzer.getOperationType(),
            database,
            e.toString(),
            e);
        failed++;
      }
    }
    log.info(
        "Finished directory analysis for {}: created {} PENDING operation(s) ({} failed)",
        analyzer.getOperationType(),
        created,
        failed);
  }
}
