package com.linkedin.openhouse.tables.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class HtsTableStatsClientTest {

  private HtsTableStatsClient client;

  @BeforeEach
  void setUp() {
    ClusterProperties props = Mockito.mock(ClusterProperties.class);
    Mockito.when(props.getClusterHouseTablesBaseUri()).thenReturn("http://localhost:8080");
    client = new HtsTableStatsClient(props);
    // skip @PostConstruct — WebClient not needed for body-building tests
  }

  @Test
  void testSingleSnapshot_parsesFilesAddedAndSize() {
    String snapshot =
        "{"
            + "\"snapshot-id\":1,"
            + "\"summary\":{"
            + "\"added-data-files\":\"3\","
            + "\"deleted-data-files\":\"1\","
            + "\"total-files-size\":\"1024\""
            + "}"
            + "}";

    JsonObject body = client.buildRequestBody("db1", "t1", "cl1", "v1", "/loc", List.of(snapshot));

    assertThat(body.get("databaseId").getAsString()).isEqualTo("db1");
    assertThat(body.get("tableName").getAsString()).isEqualTo("t1");

    JsonObject delta = body.getAsJsonObject("stats").getAsJsonObject("delta");
    assertThat(delta.get("numFilesAdded").getAsLong()).isEqualTo(3L);
    assertThat(delta.get("numFilesDeleted").getAsLong()).isEqualTo(1L);

    JsonObject snap = body.getAsJsonObject("stats").getAsJsonObject("snapshot");
    assertThat(snap.get("tableSizeBytes").getAsLong()).isEqualTo(1024L);
    assertThat(snap.get("clusterId").getAsString()).isEqualTo("cl1");
    assertThat(snap.get("tableVersion").getAsString()).isEqualTo("v1");
    assertThat(snap.get("tableLocation").getAsString()).isEqualTo("/loc");
  }

  @Test
  void testMultipleSnapshots_sumsDeltas_lastSnapshotWinsForSize() {
    String snap1 =
        "{"
            + "\"snapshot-id\":1,"
            + "\"summary\":{"
            + "\"added-data-files\":\"2\","
            + "\"deleted-data-files\":\"0\","
            + "\"total-files-size\":\"500\""
            + "}"
            + "}";
    String snap2 =
        "{"
            + "\"snapshot-id\":2,"
            + "\"summary\":{"
            + "\"added-data-files\":\"5\","
            + "\"deleted-data-files\":\"1\","
            + "\"total-files-size\":\"900\""
            + "}"
            + "}";

    JsonObject body =
        client.buildRequestBody("db1", "t1", "cl1", "v2", "/loc", Arrays.asList(snap1, snap2));

    JsonObject delta = body.getAsJsonObject("stats").getAsJsonObject("delta");
    assertThat(delta.get("numFilesAdded").getAsLong()).isEqualTo(7L);
    assertThat(delta.get("numFilesDeleted").getAsLong()).isEqualTo(1L);

    JsonObject snap = body.getAsJsonObject("stats").getAsJsonObject("snapshot");
    assertThat(snap.get("tableSizeBytes").getAsLong()).isEqualTo(900L);
  }

  @Test
  void testMissingSummaryFields_defaultToZero() {
    String snapshot = "{\"snapshot-id\":1,\"summary\":{\"operation\":\"append\"}}";

    JsonObject body = client.buildRequestBody("db1", "t1", "cl1", "v1", "/loc", List.of(snapshot));

    JsonObject delta = body.getAsJsonObject("stats").getAsJsonObject("delta");
    assertThat(delta.get("numFilesAdded").getAsLong()).isEqualTo(0L);
    assertThat(delta.get("numFilesDeleted").getAsLong()).isEqualTo(0L);

    JsonObject snap = body.getAsJsonObject("stats").getAsJsonObject("snapshot");
    assertThat(snap.has("tableSizeBytes")).isFalse();
  }

  @Test
  void testSnapshotWithoutSummary_isSkipped() {
    String snapshot = "{\"snapshot-id\":1}";

    JsonObject body = client.buildRequestBody("db1", "t1", "cl1", "v1", "/loc", List.of(snapshot));

    JsonObject delta = body.getAsJsonObject("stats").getAsJsonObject("delta");
    assertThat(delta.get("numFilesAdded").getAsLong()).isEqualTo(0L);
  }

  @Test
  void testRealSnapshotJson_parsesCorrectly() {
    // Same JSON used in RequestConstants.TEST_ICEBERG_SNAPSHOT_JSON
    String snapshot =
        "{\n"
            + "\"snapshot-id\" : 2151407017102313398,\n"
            + "\"timestamp-ms\" : 1669126937912,\n"
            + "\"summary\" : {\n"
            + "\"operation\" : \"append\",\n"
            + "\"added-data-files\" : \"1\",\n"
            + "\"added-records\" : \"1\",\n"
            + "\"added-files-size\" : \"673\",\n"
            + "\"total-files-size\" : \"673\",\n"
            + "\"total-data-files\" : \"1\"\n"
            + "}\n"
            + "}\n";

    JsonObject body = client.buildRequestBody("db1", "tbl", "cl1", "v1", "/loc", List.of(snapshot));

    JsonObject delta = body.getAsJsonObject("stats").getAsJsonObject("delta");
    assertThat(delta.get("numFilesAdded").getAsLong()).isEqualTo(1L);
    assertThat(delta.get("numFilesDeleted").getAsLong()).isEqualTo(0L);

    JsonObject snap = body.getAsJsonObject("stats").getAsJsonObject("snapshot");
    assertThat(snap.get("tableSizeBytes").getAsLong()).isEqualTo(673L);
  }

  @Test
  void testReportCommitStats_noopOnEmptyList() {
    // Should not throw; fire-and-forget path is never reached
    client.reportCommitStats("uuid", "db", "tbl", "cl", "v1", "/loc", Collections.emptyList());
    client.reportCommitStats("uuid", "db", "tbl", "cl", "v1", "/loc", null);
  }
}
