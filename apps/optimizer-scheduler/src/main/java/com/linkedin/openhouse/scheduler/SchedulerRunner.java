package com.linkedin.openhouse.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkedin.openhouse.scheduler.client.JobsServiceClient;
import com.linkedin.openhouse.scheduler.entity.SchedulerOperationRow;
import com.linkedin.openhouse.scheduler.entity.SchedulerStatsRow;
import com.linkedin.openhouse.scheduler.repository.SchedulerOperationsRepository;
import com.linkedin.openhouse.scheduler.repository.SchedulerStatsRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Reads PENDING rows from the optimizer DB, bin-packs them, and submits one Spark job per bin. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulerRunner {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final SchedulerOperationsRepository operationsRepo;
  private final SchedulerStatsRepository statsRepo;
  private final JobsServiceClient jobsClient;

  @Value("${scheduler.bin-size-max-files}")
  private long maxFiles;

  @Value("${scheduler.operation-type}")
  private String operationType;

  @Value("${scheduler.results-endpoint}")
  private String resultsEndpoint;

  @Transactional
  public void schedule() {
    List<SchedulerOperationRow> pending = operationsRepo.findPendingByType(operationType);
    if (pending.isEmpty()) {
      log.info("No PENDING operations of type {}; nothing to schedule", operationType);
      return;
    }

    Set<String> uuids =
        pending.stream().map(SchedulerOperationRow::getTableUuid).collect(Collectors.toSet());

    Map<String, Long> fileCountByUuid =
        statsRepo.findAllById(uuids).stream()
            .collect(Collectors.toMap(SchedulerStatsRow::getTableUuid, r -> extractFileCount(r)));

    List<List<SchedulerOperationRow>> bins = BinPacker.pack(pending, fileCountByUuid, maxFiles);
    log.info(
        "Packed {} PENDING operations into {} bins (maxFiles={})",
        pending.size(),
        bins.size(),
        maxFiles);

    bins.forEach(this::submitBin);
  }

  private void submitBin(List<SchedulerOperationRow> bin) {
    List<String> tableNames =
        bin.stream()
            .map(r -> r.getDatabaseName() + "." + r.getTableName())
            .collect(Collectors.toList());
    List<String> opIds =
        bin.stream().map(SchedulerOperationRow::getId).collect(Collectors.toList());

    String jobName = "batched-" + operationType.toLowerCase() + "-" + Instant.now().toEpochMilli();
    Optional<String> jobId =
        jobsClient.launch(jobName, operationType, tableNames, opIds, resultsEndpoint);

    if (jobId.isPresent()) {
      log.info("Submitted job {} for {} tables", jobId.get(), bin.size());
      bin.forEach(
          r -> {
            int claimed = operationsRepo.claimOperation(r.getId(), r.getVersion(), Instant.now());
            if (claimed == 0) {
              log.warn(
                  "Could not claim operation {} (already claimed by another instance)", r.getId());
            }
          });
    } else {
      log.warn("Job submission failed for bin of {} tables; rows remain PENDING", bin.size());
    }
  }

  private long extractFileCount(SchedulerStatsRow row) {
    if (row.getStats() == null) {
      return 0L;
    }
    try {
      return MAPPER.readTree(row.getStats()).at("/snapshot/numCurrentFiles").asLong(0L);
    } catch (Exception e) {
      log.warn(
          "Could not parse numCurrentFiles for tableUuid={}: {}",
          row.getTableUuid(),
          e.getMessage());
      return 0L;
    }
  }
}
