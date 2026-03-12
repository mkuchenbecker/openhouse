package com.linkedin.openhouse.analyzer.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.linkedin.openhouse.analyzer.model.TableSummary;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class HtsClientTest {

  private MockWebServer mockServer;
  private HtsClient client;

  @BeforeEach
  void setUp() throws Exception {
    mockServer = new MockWebServer();
    mockServer.start();
    WebClient webClient = WebClient.builder().baseUrl(mockServer.url("/").toString()).build();
    client = new HtsClient(webClient);
  }

  @AfterEach
  void tearDown() throws Exception {
    mockServer.shutdown();
  }

  @Test
  void getAllTableStats_parsesResponse() throws Exception {
    String json =
        "["
            + "{\"tableUuid\":\"uuid-1\",\"databaseId\":\"db1\",\"tableName\":\"t1\","
            + "\"tableProperties\":{\"maintenance.optimizer.ofd.enabled\":\"true\"}},"
            + "{\"tableUuid\":\"uuid-2\",\"databaseId\":\"db2\",\"tableName\":\"t2\","
            + "\"tableProperties\":{}}"
            + "]";
    mockServer.enqueue(
        new MockResponse().setBody(json).addHeader("Content-Type", "application/json"));

    List<TableSummary> result = client.getAllTableStats();

    assertThat(result).hasSize(2);
    assertThat(result.get(0).getTableUuid()).isEqualTo("uuid-1");
    assertThat(result.get(0).getDatabaseId()).isEqualTo("db1");
    assertThat(result.get(0).getTableId()).isEqualTo("t1");
    assertThat(result.get(0).getTableProperties())
        .containsEntry("maintenance.optimizer.ofd.enabled", "true");
    assertThat(result.get(1).getTableUuid()).isEqualTo("uuid-2");

    RecordedRequest request = mockServer.takeRequest();
    assertThat(request.getMethod()).isEqualTo("GET");
    assertThat(request.getPath()).isEqualTo("/v1/hts/table-stats");
  }

  @Test
  void getAllTableStats_returnsEmpty_onServerError() {
    mockServer.enqueue(new MockResponse().setResponseCode(500));

    List<TableSummary> result = client.getAllTableStats();

    assertThat(result).isEmpty();
  }

  @Test
  void getAllTableStats_filtersNullUuids() {
    String json =
        "["
            + "{\"tableUuid\":null,\"databaseId\":\"db1\",\"tableName\":\"t1\"},"
            + "{\"tableUuid\":\"uuid-2\",\"databaseId\":\"db2\",\"tableName\":\"t2\"}"
            + "]";
    mockServer.enqueue(
        new MockResponse().setBody(json).addHeader("Content-Type", "application/json"));

    List<TableSummary> result = client.getAllTableStats();

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getTableUuid()).isEqualTo("uuid-2");
  }
}
