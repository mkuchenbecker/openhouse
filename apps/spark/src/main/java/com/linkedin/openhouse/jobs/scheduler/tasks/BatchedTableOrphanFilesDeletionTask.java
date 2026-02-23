package com.linkedin.openhouse.jobs.scheduler.tasks;

import com.linkedin.openhouse.jobs.client.JobsClient;
import com.linkedin.openhouse.jobs.client.TablesClient;
import com.linkedin.openhouse.jobs.client.model.JobConf;
import com.linkedin.openhouse.jobs.util.BatchMetadata;
import com.linkedin.openhouse.jobs.util.TableMetadata;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A task to remove orphan files from multiple tables in a single batch job.
 *
 * <p>Processes multiple tables in parallel within one Spark application to reduce job overhead.
 * Individual table failures do not cause the batch job to fail.
 *
 * @see <a href="https://iceberg.apache.org/docs/latest/maintenance/#delete-orphan-files">Delete
 *     orphan files</a>
 */
public class BatchedTableOrphanFilesDeletionTask extends OperationTask<BatchMetadata> {
  public static final JobConf.JobTypeEnum OPERATION_TYPE =
      JobConf.JobTypeEnum.ORPHAN_FILES_DELETION;

  private final int parallelism;

  public BatchedTableOrphanFilesDeletionTask(
      JobsClient jobsClient,
      TablesClient tablesClient,
      List<TableMetadata> metadataList,
      int parallelism,
      long pollIntervalMs,
      long queuedTimeoutMs,
      long taskTimeoutMs) {
    super(
        jobsClient,
        tablesClient,
        BatchMetadata.builder().tables(metadataList).creator("system").build(),
        pollIntervalMs,
        queuedTimeoutMs,
        taskTimeoutMs);
    this.parallelism = parallelism;
  }

  public BatchedTableOrphanFilesDeletionTask(
      JobsClient jobsClient,
      TablesClient tablesClient,
      List<TableMetadata> metadataList,
      int parallelism) {
    super(
        jobsClient,
        tablesClient,
        BatchMetadata.builder().tables(metadataList).creator("system").build());
    this.parallelism = parallelism;
  }

  @Override
  public JobConf.JobTypeEnum getType() {
    return OPERATION_TYPE;
  }

  @Override
  protected List<String> getArgs() {
    String tableNames =
        metadata.getTables().stream().map(TableMetadata::fqtn).collect(Collectors.joining(","));

    return Arrays.asList("--tableNames", tableNames, "--parallelism", String.valueOf(parallelism));
  }

  @Override
  protected boolean shouldRun() {
    return !metadata.getTables().isEmpty();
  }

  @Override
  protected boolean launchJob() {
    String jobName = String.format("%s_batch_%d_tables", getType(), metadata.getTables().size());
    jobId = jobsClient.launch(jobName, getType(), metadata.getCreator(), getArgs()).orElse(null);
    return jobId != null;
  }
}
