package com.linkedin.openhouse.housetables.e2e.database;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.gson.Gson;
import com.linkedin.openhouse.common.exception.NoSuchEntityException;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.housetables.api.spec.model.Database;
import com.linkedin.openhouse.housetables.api.spec.request.CreateUpdateEntityRequestBody;
import com.linkedin.openhouse.housetables.repository.impl.jdbc.DatabaseHtsJdbcRepository;
import com.linkedin.openhouse.housetables.services.DatabasesService;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.util.Pair;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** End-to-end coverage for the /hts/databases surface: the stored form of a namespace. */
@SpringBootTest
@ContextConfiguration(initializers = PropertyOverrideContextInitializer.class)
@AutoConfigureMockMvc
public class DatabaseHouseTablesTest {

  private static final String ENDPOINT = "/hts/databases";

  @Autowired DatabasesService databasesService;

  @Autowired DatabaseHtsJdbcRepository databaseRepository;

  @Autowired MockMvc mvc;

  @AfterEach
  public void tearDown() {
    databaseRepository.deleteAll();
  }

  @Test
  public void putCreatesThenReplacesAndCarriesTheProperties() {
    Map<String, String> properties = new HashMap<>();
    properties.put("owner", "user");

    Pair<Database, Boolean> created =
        databasesService.putDatabase(
            Database.builder().databaseId("d1").properties(properties).build());
    Assertions.assertFalse(created.getSecond(), "first put is a create");
    Assertions.assertEquals("user", created.getFirst().getProperties().get("owner"));
    Assertions.assertNotNull(created.getFirst().getCreationTime());

    Pair<Database, Boolean> replaced =
        databasesService.putDatabase(
            Database.builder()
                .databaseId("d1")
                .properties(Collections.singletonMap("owner", "other"))
                .build());
    Assertions.assertTrue(replaced.getSecond(), "second put is a replace");
    Assertions.assertEquals(
        "other", databasesService.getDatabase("d1").getProperties().get("owner"));
    Assertions.assertEquals(
        created.getFirst().getCreationTime(),
        replaced.getFirst().getCreationTime(),
        "creation time survives a replace");
  }

  @Test
  public void aDatabaseWithNoPropertiesRoundTripsAsAnEmptyMap() {
    databasesService.putDatabase(Database.builder().databaseId("d_empty").build());
    Assertions.assertEquals(
        Collections.emptyMap(), databasesService.getDatabase("d_empty").getProperties());
  }

  @Test
  public void getAndDeleteOfAnAbsentDatabaseAreNotFound() {
    Assertions.assertThrows(
        NoSuchEntityException.class, () -> databasesService.getDatabase("d_absent"));
    Assertions.assertThrows(
        NoSuchEntityException.class, () -> databasesService.deleteDatabase("d_absent"));
  }

  @Test
  public void listingIsOrderedByDatabaseId() {
    for (String databaseId : Arrays.asList("d_c", "d_a", "d_b")) {
      databasesService.putDatabase(Database.builder().databaseId(databaseId).build());
    }
    List<String> ids =
        databasesService.getAllDatabases().stream()
            .map(Database::getDatabaseId)
            .collect(Collectors.toList());
    Assertions.assertEquals(Arrays.asList("d_a", "d_b", "d_c"), ids);
  }

  @Test
  public void deleteRemovesTheRow() {
    databasesService.putDatabase(Database.builder().databaseId("d_drop").build());
    databasesService.deleteDatabase("d_drop");
    Assertions.assertThrows(
        NoSuchEntityException.class, () -> databasesService.getDatabase("d_drop"));
  }

  @Test
  public void putOverHttpReturns201ThenGetReturnsTheEntity() throws Exception {
    CreateUpdateEntityRequestBody<Database> body =
        CreateUpdateEntityRequestBody.<Database>builder()
            .entity(
                Database.builder()
                    .databaseId("d_http")
                    .properties(Collections.singletonMap("owner", "user"))
                    .build())
            .build();

    mvc.perform(
            MockMvcRequestBuilders.put(ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(new Gson().toJson(body))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.entity.databaseId", is("d_http")));

    mvc.perform(
            MockMvcRequestBuilders.get(ENDPOINT)
                .param("databaseId", "d_http")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entity.properties.owner", is("user")));

    mvc.perform(MockMvcRequestBuilders.delete(ENDPOINT).param("databaseId", "d_http"))
        .andExpect(status().isNoContent());

    mvc.perform(MockMvcRequestBuilders.get(ENDPOINT).param("databaseId", "d_http"))
        .andExpect(status().isNotFound());
  }

  @Test
  public void anIdentifierOutsideTheColumnCharsetIsRejectedAtTheHtsBoundary() throws Exception {
    // House Tables is reachable independently of the Tables Service, so it re-validates.
    mvc.perform(
            MockMvcRequestBuilders.get(ENDPOINT)
                .param("databaseId", "not-legal")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
  }
}
