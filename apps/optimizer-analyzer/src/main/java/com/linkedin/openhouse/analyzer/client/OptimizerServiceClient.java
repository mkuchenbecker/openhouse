package com.linkedin.openhouse.analyzer.client;

import com.linkedin.openhouse.analyzer.model.TableOperationView;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;

/** Client for the Optimizer Service's {@code /v1/table-operations} endpoint. */
@Slf4j
@RequiredArgsConstructor
public class OptimizerServiceClient {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final WebClient webClient;

  /**
   * Returns active (PENDING or SCHEDULED) operation records for the given operation type, indexed
   * by {@code tableUuid}.
   */
  public Map<String, TableOperationView> getOperationsByType(String operationType) {
    try {
      List<TableOperationView> ops =
          webClient
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/v1/table-operations")
                          .queryParam("operationType", operationType)
                          .build())
              .retrieve()
              .bodyToMono(new ParameterizedTypeReference<List<TableOperationView>>() {})
              .block(TIMEOUT);

      if (ops == null) {
        return Collections.emptyMap();
      }

      return ops.stream()
          .filter(op -> op.getTableUuid() != null)
          .collect(Collectors.toMap(TableOperationView::getTableUuid, op -> op, (a, b) -> b));
    } catch (Exception e) {
      log.error("Failed to fetch operations for type {}", operationType, e);
      return Collections.emptyMap();
    }
  }

  /**
   * Creates or refreshes an operation record.
   *
   * @param id client-generated UUID for this operation (stable across retries)
   * @param tableUuid stable UUID of the table
   * @param databaseName display name of the database
   * @param tableName display name of the table
   * @param operationType the operation type string
   */
  public void upsertOperation(
      String id, String tableUuid, String databaseName, String tableName, String operationType) {
    try {
      Map<String, Object> body = new HashMap<>();
      body.put("tableUuid", tableUuid);
      body.put("databaseName", databaseName);
      body.put("tableName", tableName);
      body.put("operationType", operationType);

      webClient
          .put()
          .uri("/v1/table-operations/{id}", id)
          .bodyValue(body)
          .retrieve()
          .bodyToMono(Void.class)
          .block(TIMEOUT);
    } catch (Exception e) {
      log.error("Failed to upsert operation {} for table {}", operationType, tableUuid, e);
    }
  }
}
