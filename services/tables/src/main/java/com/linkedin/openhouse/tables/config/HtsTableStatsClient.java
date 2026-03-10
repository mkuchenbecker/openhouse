package com.linkedin.openhouse.tables.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import java.util.List;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Fire-and-forget client that pushes per-commit stats to the HTS table_stats endpoint.
 *
 * <p>Failures are logged and swallowed — a stats update failure must never fail a table commit.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class HtsTableStatsClient {

  private static final String SUMMARY_KEY = "summary";
  private static final String ADDED_DATA_FILES_KEY = "added-data-files";
  private static final String DELETED_DATA_FILES_KEY = "deleted-data-files";
  private static final String TOTAL_FILES_SIZE_KEY = "total-files-size";

  private final ClusterProperties clusterProperties;

  private WebClient webClient;

  @PostConstruct
  void init() {
    webClient =
        WebClient.builder().baseUrl(clusterProperties.getClusterHouseTablesBaseUri()).build();
  }

  /**
   * Parse per-commit stats from the snapshot list and push them to HTS asynchronously.
   *
   * <p>Sums {@code added-data-files} and {@code deleted-data-files} across all snapshots in the
   * batch. Snapshot metrics ({@code tableSizeBytes}) come from the last snapshot's {@code
   * total-files-size}.
   *
   * @param tableUuid stable Iceberg UUID — primary key of the stats row
   * @param databaseId denormalized display column
   * @param tableName denormalized display column
   * @param clusterId cluster the table lives on
   * @param tableVersion metadata location returned by the tables service after save
   * @param tableLocation base table location
   * @param jsonSnapshots serialized Iceberg snapshot JSON strings from the commit request
   */
  public void reportCommitStats(
      String tableUuid,
      String databaseId,
      String tableName,
      String clusterId,
      String tableVersion,
      String tableLocation,
      List<String> jsonSnapshots) {
    if (jsonSnapshots == null || jsonSnapshots.isEmpty()) {
      return;
    }

    JsonObject body =
        buildRequestBody(
            databaseId, tableName, clusterId, tableVersion, tableLocation, jsonSnapshots);

    webClient
        .put()
        .uri("/v1/hts/table-stats/{tableUuid}", tableUuid)
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
   * Build the JSON request body for {@code PUT /v1/hts/table-stats/{tableUuid}}.
   *
   * <p>Package-private for testing.
   */
  JsonObject buildRequestBody(
      String databaseId,
      String tableName,
      String clusterId,
      String tableVersion,
      String tableLocation,
      List<String> jsonSnapshots) {
    Gson gson = new Gson();
    long numFilesAdded = 0;
    long numFilesDeleted = 0;
    Long tableSizeBytes = null;

    for (String snapshotJson : jsonSnapshots) {
      try {
        JsonObject snapshot = gson.fromJson(snapshotJson, JsonObject.class);
        if (!snapshot.has(SUMMARY_KEY)) {
          continue;
        }
        JsonObject summary = snapshot.getAsJsonObject(SUMMARY_KEY);
        numFilesAdded += parseLong(summary, ADDED_DATA_FILES_KEY);
        numFilesDeleted += parseLong(summary, DELETED_DATA_FILES_KEY);
        // total-files-size is a running total; last snapshot wins
        if (summary.has(TOTAL_FILES_SIZE_KEY)) {
          tableSizeBytes = parseLong(summary, TOTAL_FILES_SIZE_KEY);
        }
      } catch (Exception e) {
        log.warn("Failed to parse snapshot summary: {}", e.getMessage());
      }
    }

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

    return body;
  }

  private long parseLong(JsonObject obj, String key) {
    if (!obj.has(key)) {
      return 0L;
    }
    try {
      return Long.parseLong(obj.get(key).getAsString());
    } catch (NumberFormatException e) {
      return 0L;
    }
  }
}
