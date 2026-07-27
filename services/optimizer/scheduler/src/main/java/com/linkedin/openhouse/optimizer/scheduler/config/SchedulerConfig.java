package com.linkedin.openhouse.optimizer.scheduler.config;

import com.linkedin.openhouse.optimizer.binpack.FirstFitDecreasingBinPacker;
import com.linkedin.openhouse.optimizer.binpack.TableSizeBinItem;
import com.linkedin.openhouse.optimizer.binpack.TotalFilesBinItem;
import com.linkedin.openhouse.optimizer.model.OperationTypeDto;
import com.linkedin.openhouse.optimizer.repository.TableOperationsRepository;
import com.linkedin.openhouse.optimizer.repository.TableStatsRepository;
import com.linkedin.openhouse.optimizer.scheduler.SchedulerRunner;
import com.linkedin.openhouse.optimizer.scheduler.client.JobsServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Cross-cutting wiring (jobs-service client) plus the {@link SchedulerRunner} bean. Each operation
 * type's identity (type, packing strategy, item supplier) is composed in {@link #schedulerRunner};
 * the runner itself never names an operation type beyond the keys in its registry.
 */
@Configuration
public class SchedulerConfig {

  @Value("${optimizer.scheduler.jobs.base-uri}")
  private String jobsBaseUri;

  @Value("${optimizer.scheduler.cluster-id}")
  private String clusterId;

  @Bean
  public WebClient jobsWebClient() {
    return WebClient.builder().baseUrl(jobsBaseUri).build();
  }

  @Bean
  public JobsServiceClient jobsServiceClient(WebClient jobsWebClient) {
    return new JobsServiceClient(jobsWebClient, clusterId);
  }

  /**
   * Registers the operation types this scheduler can pack and launch.
   *
   * <ul>
   *   <li><b>Orphan files deletion</b>: a {@link FirstFitDecreasingBinPacker} over {@link
   *       TotalFilesBinItem}. Cost scales with file count — per-file list, manifest joins, and
   *       delete calls dominate independent of file size.
   *   <li><b>Staged files deletion</b>: same {@link FirstFitDecreasingBinPacker} over {@link
   *       TotalFilesBinItem}. Deleting abandoned staged/trash files is likewise a per-file list +
   *       delete workload, so file count is the right cost driver.
   *   <li><b>Data compaction</b>: a {@link FirstFitDecreasingBinPacker} over {@link
   *       TableSizeBinItem}. Cost scales with data volume: every byte is read, re-sorted, and
   *       written back, so bins are capped on {@code tableSizeBytes} rather than file count.
   * </ul>
   */
  @Bean
  public SchedulerRunner schedulerRunner(
      TableOperationsRepository operationsRepo,
      TableStatsRepository statsRepo,
      JobsServiceClient jobsClient,
      @Value("${optimizer.scheduler.results-endpoint}") String resultsEndpoint,
      @Value("${optimizer.scheduler.ofd.max-files-per-bin}") long ofdMaxFilesPerBin,
      @Value("${optimizer.scheduler.ofd.max-tables-per-bin}") int ofdMaxTablesPerBin,
      @Value("${optimizer.scheduler.sfd.max-files-per-bin}") long sfdMaxFilesPerBin,
      @Value("${optimizer.scheduler.sfd.max-tables-per-bin}") int sfdMaxTablesPerBin,
      @Value("${optimizer.scheduler.snapshotsExpiration.max-files-per-bin}")
          long snapshotsExpirationMaxFilesPerBin,
      @Value("${optimizer.scheduler.snapshotsExpiration.max-tables-per-bin}")
          int snapshotsExpirationMaxTablesPerBin,
      @Value("${optimizer.scheduler.dataCompaction.max-bytes-per-bin}") long dcMaxBytesPerBin,
      @Value("${optimizer.scheduler.dataCompaction.max-tables-per-bin}") int dcMaxTablesPerBin) {
    return new SchedulerRunner(operationsRepo, statsRepo, jobsClient, resultsEndpoint)
        .registerOperation(
            OperationTypeDto.ORPHAN_FILES_DELETION,
            FirstFitDecreasingBinPacker.<TotalFilesBinItem>builder()
                .binItemSupplier(TotalFilesBinItem::new)
                .maxWeightPerBin(ofdMaxFilesPerBin)
                .maxItemsPerBin(ofdMaxTablesPerBin)
                .build())
        .registerOperation(
            OperationTypeDto.STAGED_FILES_DELETION,
            FirstFitDecreasingBinPacker.<TotalFilesBinItem>builder()
                .binItemSupplier(TotalFilesBinItem::new)
                .maxWeightPerBin(sfdMaxFilesPerBin)
                .maxItemsPerBin(sfdMaxTablesPerBin)
                .build())
        .registerOperation(
            OperationTypeDto.SNAPSHOTS_EXPIRATION,
            FirstFitDecreasingBinPacker.<TotalFilesBinItem>builder()
                .binItemSupplier(TotalFilesBinItem::new)
                .maxWeightPerBin(snapshotsExpirationMaxFilesPerBin)
                .maxItemsPerBin(snapshotsExpirationMaxTablesPerBin)
                .build())
        .registerOperation(
            OperationTypeDto.DATA_COMPACTION,
            // Weight bins on tableSizeBytes (TableSizeBinItem), not file count: compaction reads
            // and
            // rewrites every byte of each table, so its Spark cost — shuffle, I/O, commit time —
            // scales with data volume. Bytes-per-bin therefore bounds a batch's true work far
            // better
            // than a file-count cap, which would let a bin of a few very large tables blow past the
            // driver's budget while a bin of many tiny-file tables stays trivially cheap.
            FirstFitDecreasingBinPacker.<TableSizeBinItem>builder()
                .binItemSupplier(TableSizeBinItem::new)
                .maxWeightPerBin(dcMaxBytesPerBin)
                .maxItemsPerBin(dcMaxTablesPerBin)
                .build());
  }
}
