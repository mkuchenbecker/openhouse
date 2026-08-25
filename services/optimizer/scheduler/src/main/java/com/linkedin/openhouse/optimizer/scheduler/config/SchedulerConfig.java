package com.linkedin.openhouse.optimizer.scheduler.config;

import com.linkedin.openhouse.optimizer.binpack.FirstFitDecreasingBinPacker;
import com.linkedin.openhouse.optimizer.binpack.TableSizeBinItem;
import com.linkedin.openhouse.optimizer.binpack.TableSizeBytesBinItem;
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
   *   <li><b>Retention</b>: same packer over {@link TotalFilesBinItem}. A retention pass rewrites
   *       the affected partitions/files, so file count is again the dominant cost driver; it reuses
   *       the file-count bin item with its own per-bin caps.
   *   <li><b>Data compaction</b>: a {@link FirstFitDecreasingBinPacker} over {@link
   *       TableSizeBinItem}. Cost scales with data volume: every byte is read, re-sorted, and
   *       written back, so bins are capped on {@code tableSizeBytes} rather than file count.
   *   <li><b>Data-layout-strategy generation</b>: {@link TotalFilesBinItem}; generation is a
   *       per-table stats scan whose cost tracks the number of files/manifests read.
   *   <li><b>Data-layout-strategy execution</b>: {@link TableSizeBytesBinItem}; execution rewrites
   *       data files (compaction), so the dominant cost is the volume of bytes shuffled, packed by
   *       table size.
   *   <li><b>Table stats collection</b>: same packer over {@link TotalFilesBinItem}. Produces the
   *       {@code table_stats} rows other analyzers consume; cost tracks the number of files
   *       scanned.
   *   <li><b>Sort stats collection</b>: same packer over {@link TotalFilesBinItem}. Samples and
   *       rewrites to estimate sort compression, so file count is again the dominant cost driver.
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
      @Value("${optimizer.scheduler.retention.max-files-per-bin}") long retentionMaxFilesPerBin,
      @Value("${optimizer.scheduler.retention.max-tables-per-bin}") int retentionMaxTablesPerBin,
      @Value("${optimizer.scheduler.dataCompaction.max-bytes-per-bin}") long dcMaxBytesPerBin,
      @Value("${optimizer.scheduler.dataCompaction.max-tables-per-bin}") int dcMaxTablesPerBin,
      @Value("${optimizer.scheduler.dls-generation.max-files-per-bin}") long dlsGenMaxFilesPerBin,
      @Value("${optimizer.scheduler.dls-generation.max-tables-per-bin}") int dlsGenMaxTablesPerBin,
      @Value("${optimizer.scheduler.dls-execution.max-bytes-per-bin}") long dlsExecMaxBytesPerBin,
      @Value("${optimizer.scheduler.dls-execution.max-tables-per-bin}") int dlsExecMaxTablesPerBin,
      @Value("${optimizer.scheduler.tableStatsCollection.max-files-per-bin}")
          long tableStatsCollectionMaxFilesPerBin,
      @Value("${optimizer.scheduler.tableStatsCollection.max-tables-per-bin}")
          int tableStatsCollectionMaxTablesPerBin,
      @Value("${optimizer.scheduler.sortStatsCollection.max-files-per-bin}")
          long sortStatsCollectionMaxFilesPerBin,
      @Value("${optimizer.scheduler.sortStatsCollection.max-tables-per-bin}")
          int sortStatsCollectionMaxTablesPerBin,
      @Value("${optimizer.scheduler.orphan-directory-deletion.max-databases-per-bin:25}")
          int orphanDirMaxDatabasesPerBin,
      @Value("${optimizer.scheduler.table-directory-deletion.max-databases-per-bin:25}")
          int tableDirMaxDatabasesPerBin) {
    // ORPHAN_DIRECTORY_DELETION and TABLE_DIRECTORY_DELETION are database-scoped (M6): registered
    // via
    // registerDirectoryOperation, they dispatch through SchedulerRunner.scheduleDirectory, which
    // launches the <OP>_BATCH Spark app with --databaseNames (no table_stats bin-packing — bins are
    // formed by database count). See services/optimizer/DIRECTORY-DELETION-DESIGN.md.
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
            OperationTypeDto.RETENTION,
            FirstFitDecreasingBinPacker.<TotalFilesBinItem>builder()
                .binItemSupplier(TotalFilesBinItem::new)
                .maxWeightPerBin(retentionMaxFilesPerBin)
                .maxItemsPerBin(retentionMaxTablesPerBin)
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
                .build())
        .registerOperation(
            OperationTypeDto.DATA_LAYOUT_STRATEGY_GENERATION,
            FirstFitDecreasingBinPacker.<TotalFilesBinItem>builder()
                .binItemSupplier(TotalFilesBinItem::new)
                .maxWeightPerBin(dlsGenMaxFilesPerBin)
                .maxItemsPerBin(dlsGenMaxTablesPerBin)
                .build())
        .registerOperation(
            OperationTypeDto.DATA_LAYOUT_STRATEGY_EXECUTION,
            FirstFitDecreasingBinPacker.<TableSizeBytesBinItem>builder()
                .binItemSupplier(TableSizeBytesBinItem::new)
                .maxWeightPerBin(dlsExecMaxBytesPerBin)
                .maxItemsPerBin(dlsExecMaxTablesPerBin)
                .build())
        .registerOperation(
            OperationTypeDto.TABLE_STATS_COLLECTION,
            FirstFitDecreasingBinPacker.<TotalFilesBinItem>builder()
                .binItemSupplier(TotalFilesBinItem::new)
                .maxWeightPerBin(tableStatsCollectionMaxFilesPerBin)
                .maxItemsPerBin(tableStatsCollectionMaxTablesPerBin)
                .build())
        .registerOperation(
            OperationTypeDto.SORT_STATS_COLLECTION,
            FirstFitDecreasingBinPacker.<TotalFilesBinItem>builder()
                .binItemSupplier(TotalFilesBinItem::new)
                .maxWeightPerBin(sortStatsCollectionMaxFilesPerBin)
                .maxItemsPerBin(sortStatsCollectionMaxTablesPerBin)
                .build())
        .registerDirectoryOperation(
            OperationTypeDto.ORPHAN_DIRECTORY_DELETION, orphanDirMaxDatabasesPerBin)
        .registerDirectoryOperation(
            OperationTypeDto.TABLE_DIRECTORY_DELETION, tableDirMaxDatabasesPerBin);
  }
}
