package com.linkedin.openhouse.tables.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import java.util.Map;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Fire-and-forget client that pushes per-commit stats to the optimizer {@code table_stats}
 * endpoint.
 *
 * <p>Failures are logged and swallowed — a stats update failure must never fail a table commit.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OptimizerTableStatsClient {

  private final ClusterProperties clusterProperties;

  private WebClient webClient;

  @PostConstruct
  void init() {
    webClient = WebClient.builder().baseUrl(clusterProperties.getClusterOptimizerBaseUri()).build();
  }

  /**
   * Push per-commit stats to the optimizer table_stats endpoint asynchronously.
   *
   * @param tableUuid stable Iceberg UUID — primary key of the stats row
   * @param databaseId denormalized display column
   * @param tableName denormalized display column
   * @param clusterId cluster the table lives on
   * @param tableVersion metadata location returned by the tables service after save
   * @param tableLocation base table location
   * @param numFilesAdded number of data files added across all snapshots in this commit
   * @param numFilesDeleted number of data files deleted across all snapshots in this commit
   * @param tableSizeBytes total size of all data files as of the last snapshot, or {@code null} if
   *     unavailable
   * @param tableProperties current table properties snapshot
   */
  public void reportCommitStats(
      String tableUuid,
      String databaseId,
      String tableName,
      String clusterId,
      String tableVersion,
      String tableLocation,
      long numFilesAdded,
      long numFilesDeleted,
      Long tableSizeBytes,
      Map<String, String> tableProperties) {
    JsonObject body =
        buildRequestBody(
            databaseId,
            tableName,
            clusterId,
            tableVersion,
            tableLocation,
            numFilesAdded,
            numFilesDeleted,
            tableSizeBytes,
            tableProperties);

    webClient
        .put()
        .uri("/v1/table-stats/{tableUuid}", tableUuid)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body.toString())
        .retrieve()
        .bodyToMono(String.class)
        .doOnError(
            e ->
                log.warn(
                    "Failed to report commit stats for table {}: {}", tableUuid, e.getMessage()))
        .onErrorResume(e -> reactor.core.publisher.Mono.empty())
        .subscribe();
  }

  /**
   * Build the JSON request body for {@code PUT /v1/table-stats/{tableUuid}}.
   *
   * <p>Package-private for testing.
   */
  JsonObject buildRequestBody(
      String databaseId,
      String tableName,
      String clusterId,
      String tableVersion,
      String tableLocation,
      long numFilesAdded,
      long numFilesDeleted,
      Long tableSizeBytes,
      Map<String, String> tableProperties) {
    JsonObject snapshotMetrics = new JsonObject();
    snapshotMetrics.addProperty("clusterId", clusterId);
    snapshotMetrics.addProperty("tableVersion", tableVersion);
    snapshotMetrics.addProperty("tableLocation", tableLocation);
    if (tableSizeBytes != null) {
      snapshotMetrics.addProperty("tableSizeBytes", tableSizeBytes);
    }

    JsonObject commitDelta = new JsonObject();
    commitDelta.addProperty("numFilesAdded", numFilesAdded);
    commitDelta.addProperty("numFilesDeleted", numFilesDeleted);

    JsonObject stats = new JsonObject();
    stats.add("snapshot", snapshotMetrics);
    stats.add("delta", commitDelta);

    JsonObject body = new JsonObject();
    body.addProperty("databaseId", databaseId);
    body.addProperty("tableName", tableName);
    body.add("stats", stats);
    if (tableProperties != null && !tableProperties.isEmpty()) {
      body.add("tableProperties", new Gson().toJsonTree(tableProperties));
    }

    return body;
  }
}
