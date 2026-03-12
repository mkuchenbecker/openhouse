package com.linkedin.openhouse.analyzer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.linkedin.openhouse.analyzer.model.TableSummary;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Client for the HTS {@code GET /v1/hts/table-stats} bulk endpoint.
 *
 * <p>Returns stats for all tables, including {@code tableProperties}, so the analyzer can check
 * opt-in flags without calling the Tables Service.
 */
@Slf4j
@RequiredArgsConstructor
public class HtsClient {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final WebClient webClient;

  /**
   * Fetches stats for all tables and maps them to {@link TableSummary} objects used by the
   * analyzer. Returns an empty list on any error.
   */
  public List<TableSummary> getAllTableStats() {
    try {
      List<TableStatsResponse> rows =
          webClient
              .get()
              .uri("/v1/hts/table-stats")
              .retrieve()
              .bodyToMono(new ParameterizedTypeReference<List<TableStatsResponse>>() {})
              .block(TIMEOUT);

      if (rows == null) {
        return Collections.emptyList();
      }

      return rows.stream()
          .filter(r -> r.getTableUuid() != null)
          .map(
              r ->
                  TableSummary.builder()
                      .tableUuid(r.getTableUuid())
                      .databaseId(r.getDatabaseId())
                      .tableId(r.getTableName())
                      .tableProperties(
                          r.getTableProperties() != null
                              ? r.getTableProperties()
                              : Collections.emptyMap())
                      .build())
          .collect(Collectors.toList());
    } catch (WebClientResponseException | WebClientRequestException | IllegalStateException e) {
      log.error("Failed to fetch table stats from HTS", e);
      return Collections.emptyList();
    }
  }

  /** JSON response shape from {@code GET /v1/hts/table-stats}. */
  @Data
  @NoArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  static class TableStatsResponse {
    private String tableUuid;
    private String databaseId;
    private String tableName;
    private Map<String, String> tableProperties;
  }
}
