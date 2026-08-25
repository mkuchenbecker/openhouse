package com.linkedin.openhouse.optimizer.scheduler.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Client for the OpenHouse Jobs Service.
 *
 * <p>Submits one {@code BatchedOrphanFilesDeletionSparkApp} job per bin via {@code POST /jobs}.
 */
@Slf4j
public class JobsServiceClient {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final WebClient webClient;
  private final String clusterId;

  public JobsServiceClient(WebClient webClient, String clusterId) {
    this.webClient = webClient;
    this.clusterId = clusterId;
  }

  /**
   * Submit a batched Spark job for the given tables.
   *
   * @param jobName human-readable name for the job
   * @param jobType operation type string (e.g. "ORPHAN_FILES_DELETION")
   * @param tableNames fully-qualified table names (db.table)
   * @param operationIds operation UUIDs — parallel to tableNames
   * @param resultsEndpoint base URL the Spark app PATCHes results back to
   * @return job ID if the submission succeeded, empty if an error occurred
   */
  public Optional<String> launch(
      String jobName,
      String jobType,
      List<String> tableNames,
      List<String> operationIds,
      String resultsEndpoint) {
    try {
      ObjectNode body = MAPPER.createObjectNode();
      body.put("jobName", jobName);
      body.put("clusterId", clusterId);

      ObjectNode jobConf = body.putObject("jobConf");
      jobConf.put("jobType", jobType);

      ArrayNode args = jobConf.putArray("args");
      args.add("--tableNames");
      args.add(String.join(",", tableNames));
      args.add("--operationIds");
      args.add(String.join(",", operationIds));
      args.add("--resultsEndpoint");
      args.add(resultsEndpoint);

      String responseBody =
          webClient
              .post()
              .uri("/jobs")
              .bodyValue(body)
              .retrieve()
              .bodyToMono(String.class)
              .timeout(TIMEOUT)
              .block();

      String jobId = MAPPER.readTree(responseBody).path("jobId").asText(null);
      return Optional.ofNullable(jobId);
    } catch (Exception e) {
      log.error("Failed to submit job '{}': {}", jobName, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Submit a batched <em>directory/database-scoped</em> Spark job. Identical envelope to {@link
   * #launch}, but the work targets are databases: the args carry {@code --databaseNames} (parallel
   * to {@code --operationIds}) instead of {@code --tableNames}. The launched {@code
   * Batched*DirectoryDeletionSparkApp} scans each named database's storage for the directories to
   * delete.
   *
   * @param jobName human-readable name for the job
   * @param jobType {@code <OPERATION>_BATCH} JobType string
   * @param databaseNames databases to process in this batch
   * @param operationIds operation UUIDs — parallel to databaseNames
   * @param resultsEndpoint base URL the Spark app PATCHes results back to
   * @return job ID if the submission succeeded, empty if an error occurred
   */
  public Optional<String> launchDirectory(
      String jobName,
      String jobType,
      List<String> databaseNames,
      List<String> operationIds,
      String resultsEndpoint) {
    try {
      ObjectNode body = MAPPER.createObjectNode();
      body.put("jobName", jobName);
      body.put("clusterId", clusterId);

      ObjectNode jobConf = body.putObject("jobConf");
      jobConf.put("jobType", jobType);

      ArrayNode args = jobConf.putArray("args");
      args.add("--databaseNames");
      args.add(String.join(",", databaseNames));
      args.add("--operationIds");
      args.add(String.join(",", operationIds));
      args.add("--resultsEndpoint");
      args.add(resultsEndpoint);

      String responseBody =
          webClient
              .post()
              .uri("/jobs")
              .bodyValue(body)
              .retrieve()
              .bodyToMono(String.class)
              .timeout(TIMEOUT)
              .block();

      String jobId = MAPPER.readTree(responseBody).path("jobId").asText(null);
      return Optional.ofNullable(jobId);
    } catch (Exception e) {
      log.error("Failed to submit directory job '{}': {}", jobName, e.getMessage());
      return Optional.empty();
    }
  }
}
