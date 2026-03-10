package com.linkedin.openhouse.housetables.e2e.tablestats;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.housetables.e2e.SpringH2HtsApplication;
import com.linkedin.openhouse.housetables.repository.impl.jdbc.TableStatsHtsJdbcRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@SpringBootTest(classes = SpringH2HtsApplication.class)
@ContextConfiguration(initializers = PropertyOverrideContextInitializer.class)
@AutoConfigureMockMvc
public class TableStatsControllerTest {

  @Autowired MockMvc mvc;

  @Autowired TableStatsHtsJdbcRepository repository;

  @AfterEach
  void tearDown() {
    repository.deleteAll();
  }

  @Test
  void testPutTableStats_createsRowKeyedByUuid() throws Exception {
    String body =
        "{"
            + "\"databaseId\":\"db1\","
            + "\"tableName\":\"t1\","
            + "\"stats\":{"
            + "\"snapshot\":{\"clusterId\":\"cl1\",\"tableVersion\":\"v1\",\"tableSizeBytes\":2048},"
            + "\"delta\":{\"numFilesAdded\":5,\"numFilesDeleted\":1}"
            + "}"
            + "}";

    mvc.perform(
            MockMvcRequestBuilders.put("/v1/hts/table-stats/uuid-abc")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tableUuid").value("uuid-abc"))
        .andExpect(jsonPath("$.databaseId").value("db1"))
        .andExpect(jsonPath("$.tableName").value("t1"))
        .andExpect(jsonPath("$.stats.snapshot.tableSizeBytes").value(2048))
        .andExpect(jsonPath("$.stats.delta.numFilesAdded").value(5))
        .andExpect(jsonPath("$.stats.delta.numFilesDeleted").value(1));
  }

  @Test
  void testPutTableStats_accumulatesDelta() throws Exception {
    String first =
        "{"
            + "\"databaseId\":\"db1\","
            + "\"tableName\":\"t1\","
            + "\"stats\":{"
            + "\"delta\":{\"numFilesAdded\":3,\"numFilesDeleted\":0}"
            + "}"
            + "}";
    String second =
        "{"
            + "\"databaseId\":\"db1\","
            + "\"tableName\":\"t1\","
            + "\"stats\":{"
            + "\"delta\":{\"numFilesAdded\":7,\"numFilesDeleted\":2}"
            + "}"
            + "}";

    mvc.perform(
        MockMvcRequestBuilders.put("/v1/hts/table-stats/uuid-acc")
            .contentType(MediaType.APPLICATION_JSON)
            .content(first));

    mvc.perform(
            MockMvcRequestBuilders.put("/v1/hts/table-stats/uuid-acc")
                .contentType(MediaType.APPLICATION_JSON)
                .content(second))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stats.delta.numFilesAdded").value(10))
        .andExpect(jsonPath("$.stats.delta.numFilesDeleted").value(2));
  }

  @Test
  void testPutTableStats_differentUuids_createSeparateRows() throws Exception {
    String body =
        "{"
            + "\"databaseId\":\"db1\","
            + "\"tableName\":\"t1\","
            + "\"stats\":{\"delta\":{\"numFilesAdded\":1,\"numFilesDeleted\":0}}"
            + "}";

    mvc.perform(
        MockMvcRequestBuilders.put("/v1/hts/table-stats/uuid-111")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    mvc.perform(
        MockMvcRequestBuilders.put("/v1/hts/table-stats/uuid-222")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));

    mvc.perform(MockMvcRequestBuilders.get("/v1/hts/table-stats/uuid-111"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tableUuid").value("uuid-111"));

    mvc.perform(MockMvcRequestBuilders.get("/v1/hts/table-stats/uuid-222"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tableUuid").value("uuid-222"));
  }

  @Test
  void testGetTableStats_returnsNotFoundWhenMissing() throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/v1/hts/table-stats/does-not-exist"))
        .andExpect(status().isNotFound());
  }

  @Test
  void testPutTableStats_snapshotFieldsOverwritten() throws Exception {
    String first =
        "{"
            + "\"databaseId\":\"db1\","
            + "\"tableName\":\"t1\","
            + "\"stats\":{"
            + "\"snapshot\":{\"tableSizeBytes\":100},"
            + "\"delta\":{\"numFilesAdded\":1,\"numFilesDeleted\":0}"
            + "}"
            + "}";
    String second =
        "{"
            + "\"databaseId\":\"db1\","
            + "\"tableName\":\"t1\","
            + "\"stats\":{"
            + "\"snapshot\":{\"tableSizeBytes\":999},"
            + "\"delta\":{\"numFilesAdded\":0,\"numFilesDeleted\":0}"
            + "}"
            + "}";

    mvc.perform(
        MockMvcRequestBuilders.put("/v1/hts/table-stats/uuid-snap")
            .contentType(MediaType.APPLICATION_JSON)
            .content(first));

    mvc.perform(
            MockMvcRequestBuilders.put("/v1/hts/table-stats/uuid-snap")
                .contentType(MediaType.APPLICATION_JSON)
                .content(second))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stats.snapshot.tableSizeBytes").value(999))
        // delta accumulated: 1 + 0 = 1
        .andExpect(jsonPath("$.stats.delta.numFilesAdded").value(1));
  }
}
