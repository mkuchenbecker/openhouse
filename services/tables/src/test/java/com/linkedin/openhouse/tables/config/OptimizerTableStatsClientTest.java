package com.linkedin.openhouse.tables.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class OptimizerTableStatsClientTest {

  private OptimizerTableStatsClient client;

  @BeforeEach
  void setUp() {
    ClusterProperties props = Mockito.mock(ClusterProperties.class);
    Mockito.when(props.getClusterOptimizerBaseUri()).thenReturn("http://localhost:8003");
    client = new OptimizerTableStatsClient(props);
    // skip @PostConstruct — WebClient not needed for body-building tests
  }

  @Test
  void testBuildRequestBody_setsAllFields() {
    Map<String, String> props = Map.of("maintenance.optimizer.ofd.enabled", "true");
    JsonObject body =
        client.buildRequestBody("db1", "t1", "cl1", "v1", "/loc", 3L, 1L, 1024L, props);

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

    assertThat(
            body.getAsJsonObject("tableProperties")
                .get("maintenance.optimizer.ofd.enabled")
                .getAsString())
        .isEqualTo("true");
  }

  @Test
  void testBuildRequestBody_nullTableSizeBytes_omitsField() {
    JsonObject body = client.buildRequestBody("db1", "t1", "cl1", "v1", "/loc", 0L, 0L, null, null);

    JsonObject snap = body.getAsJsonObject("stats").getAsJsonObject("snapshot");
    assertThat(snap.has("tableSizeBytes")).isFalse();
    assertThat(body.has("tableProperties")).isFalse();
  }
}
