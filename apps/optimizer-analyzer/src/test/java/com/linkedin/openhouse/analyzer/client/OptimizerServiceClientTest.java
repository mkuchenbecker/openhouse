package com.linkedin.openhouse.analyzer.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.linkedin.openhouse.analyzer.model.TableOperationRecord;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class OptimizerServiceClientTest {

  private MockWebServer server;
  private OptimizerServiceClient client;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    WebClient webClient = WebClient.builder().baseUrl(server.url("/").toString()).build();
    client = new OptimizerServiceClient(webClient);
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  @Test
  void getOperationsByType_parsesResponseAndIndexesByTableUuid() throws Exception {
    server.enqueue(
        new MockResponse()
            .setBody(
                "[{\"id\":\"op-1\",\"tableUuid\":\"uuid-1\",\"operationType\":\"ORPHAN_FILES_DELETION\",\"status\":\"PENDING\"},"
                    + "{\"id\":\"op-2\",\"tableUuid\":\"uuid-2\",\"operationType\":\"ORPHAN_FILES_DELETION\",\"status\":\"SCHEDULED\"}]")
            .addHeader("Content-Type", "application/json"));

    Map<String, TableOperationRecord> result = client.getOperationsByType("ORPHAN_FILES_DELETION");

    assertThat(result).hasSize(2);
    assertThat(result.get("uuid-1").getStatus()).isEqualTo("PENDING");
    assertThat(result.get("uuid-2").getStatus()).isEqualTo("SCHEDULED");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getMethod()).isEqualTo("GET");
    assertThat(request.getPath()).contains("/v1/table-operations");
    assertThat(request.getPath()).contains("operationType=ORPHAN_FILES_DELETION");
  }

  @Test
  void getOperationsByType_returnsEmptyMap_onServerError() {
    server.enqueue(new MockResponse().setResponseCode(500));

    Map<String, TableOperationRecord> result = client.getOperationsByType("ORPHAN_FILES_DELETION");

    assertThat(result).isEmpty();
  }

  @Test
  void getOperationsByType_returnsEmptyMap_onConnectionError() {
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

    Map<String, TableOperationRecord> result = client.getOperationsByType("ORPHAN_FILES_DELETION");

    assertThat(result).isEmpty();
  }

  @Test
  void upsertOperation_sendsPutWithCorrectShape() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody("{}")
            .addHeader("Content-Type", "application/json"));

    client.upsertOperation("op-id-1", "uuid-1", "db1", "tbl1", "ORPHAN_FILES_DELETION");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getMethod()).isEqualTo("PUT");
    assertThat(request.getPath()).isEqualTo("/v1/table-operations/op-id-1");

    String body = request.getBody().readUtf8();
    assertThat(body).contains("\"tableUuid\":\"uuid-1\"");
    assertThat(body).contains("\"databaseName\":\"db1\"");
    assertThat(body).contains("\"tableName\":\"tbl1\"");
    assertThat(body).contains("\"operationType\":\"ORPHAN_FILES_DELETION\"");
  }

  @Test
  void upsertOperation_doesNotThrow_onServerError() {
    server.enqueue(new MockResponse().setResponseCode(500));

    // should log and swallow, not throw
    client.upsertOperation("op-id-1", "uuid-1", "db1", "tbl1", "ORPHAN_FILES_DELETION");
  }

  @Test
  void upsertOperation_doesNotThrow_onConnectionError() {
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

    // should log and swallow, not throw
    client.upsertOperation("op-id-1", "uuid-1", "db1", "tbl1", "ORPHAN_FILES_DELETION");
  }
}
