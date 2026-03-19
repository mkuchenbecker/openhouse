package com.linkedin.openhouse.optimizer.service;

import com.linkedin.openhouse.optimizer.api.mapper.OptimizerMapper;
import com.linkedin.openhouse.optimizer.api.model.OperationStatus;
import com.linkedin.openhouse.optimizer.api.model.OperationType;
import com.linkedin.openhouse.optimizer.api.model.PatchTableOperationRequest;
import com.linkedin.openhouse.optimizer.api.model.TableOperationsDto;
import com.linkedin.openhouse.optimizer.api.model.TableOperationsHistoryDto;
import com.linkedin.openhouse.optimizer.api.model.TableStatsDto;
import com.linkedin.openhouse.optimizer.api.model.UpsertTableStatsRequest;
import com.linkedin.openhouse.optimizer.entity.TableOperationsHistoryRow;
import com.linkedin.openhouse.optimizer.entity.TableStatsRow;
import com.linkedin.openhouse.optimizer.repository.TableOperationsHistoryRepository;
import com.linkedin.openhouse.optimizer.repository.TableOperationsRepository;
import com.linkedin.openhouse.optimizer.repository.TableStatsRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implementation of {@link OptimizerDataService}. */
@Service
@RequiredArgsConstructor
public class OptimizerDataServiceImpl implements OptimizerDataService {

  private final TableOperationsRepository operationsRepository;
  private final TableOperationsHistoryRepository historyRepository;
  private final TableStatsRepository statsRepository;
  private final OptimizerMapper mapper;

  // --- TableOperations ---

  @Override
  public List<TableOperationsDto> getAllTableOperations(OperationType operationType) {
    if (operationType != null) {
      return operationsRepository.find(operationType).stream()
          .map(mapper::toDto)
          .collect(Collectors.toList());
    }
    return operationsRepository.findAll().stream()
        .filter(
            r ->
                r.getStatus() == OperationStatus.PENDING
                    || r.getStatus() == OperationStatus.SCHEDULED)
        .map(mapper::toDto)
        .collect(Collectors.toList());
  }

  // --- TableStats ---

  @Override
  @Transactional
  public TableStatsDto upsertTableStats(String tableUuid, UpsertTableStatsRequest request) {
    Instant now = Instant.now();
    TableStatsRow row =
        statsRepository
            .findById(tableUuid)
            .map(
                existing ->
                    existing
                        .toBuilder()
                        .databaseId(request.getDatabaseId())
                        .tableName(request.getTableName())
                        .stats(request.getStats())
                        .tableProperties(request.getTableProperties())
                        .updatedAt(now)
                        .build())
            .orElse(
                TableStatsRow.builder()
                    .tableUuid(tableUuid)
                    .databaseId(request.getDatabaseId())
                    .tableName(request.getTableName())
                    .stats(request.getStats())
                    .tableProperties(request.getTableProperties())
                    .updatedAt(now)
                    .build());
    return mapper.toDto(statsRepository.save(row));
  }

  @Override
  public Optional<TableOperationsDto> patchTableOperation(
      String id, PatchTableOperationRequest request) {
    if (request.getStatus() != OperationStatus.SUCCESS
        && request.getStatus() != OperationStatus.FAILED) {
      throw new IllegalArgumentException(
          "Only SUCCESS or FAILED are valid patch targets, got: " + request.getStatus());
    }
    return operationsRepository
        .findById(id)
        .map(
            row ->
                operationsRepository.save(
                    row.toBuilder()
                        .status(request.getStatus())
                        .metrics(request.getMetrics())
                        .build()))
        .map(mapper::toDto);
  }

  @Override
  public Optional<TableOperationsDto> getTableOperation(String id) {
    return operationsRepository.findById(id).map(mapper::toDto);
  }

  @Override
  public Optional<TableStatsDto> getTableStats(String tableUuid) {
    return statsRepository.findById(tableUuid).map(mapper::toDto);
  }

  // --- TableOperationsHistory ---

  @Override
  @Transactional
  public TableOperationsHistoryDto appendHistory(TableOperationsHistoryDto dto) {
    TableOperationsHistoryRow row =
        TableOperationsHistoryRow.builder()
            .tableUuid(dto.getTableUuid())
            .databaseName(dto.getDatabaseName())
            .tableName(dto.getTableName())
            .operationType(dto.getOperationType())
            .submittedAt(dto.getSubmittedAt() != null ? dto.getSubmittedAt() : Instant.now())
            .status(dto.getStatus())
            .jobId(dto.getJobId())
            .result(dto.getResult())
            .build();
    return mapper.toDto(historyRepository.save(row));
  }

  @Override
  public List<TableOperationsHistoryDto> getHistory(String tableUuid, int limit) {
    return historyRepository.find(tableUuid, limit).stream()
        .map(mapper::toDto)
        .collect(Collectors.toList());
  }
}
