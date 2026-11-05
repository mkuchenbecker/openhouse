package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.ValidationUtilities.CURRENT_MAJOR_VERSION_PREFIX;
import static com.linkedin.openhouse.tables.model.DatabaseModelConstants.GET_DATABASE_RESPONSE_BODY;
import static com.linkedin.openhouse.tables.model.DatabaseModelConstants.GET_DATABASE_RESPONSE_BODY_DIFF_DB;
import static com.linkedin.openhouse.tables.model.TableModelConstants.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.linkedin.openhouse.cluster.storage.StorageManager;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateDatabaseRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.UpdateDatabasePropertiesRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.response.GetAllDatabasesResponseBody;
import com.linkedin.openhouse.tables.api.spec.v0.response.GetDatabaseResponseBody;
import com.linkedin.openhouse.tables.api.spec.v0.response.GetTableResponseBody;
import com.linkedin.openhouse.tables.mock.RequestConstants;
import com.linkedin.openhouse.tables.repository.DatabaseEntityRepository;
import com.linkedin.openhouse.tables.repository.OpenHouseInternalRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.iceberg.catalog.Catalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@Slf4j
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = PropertyOverrideContextInitializer.class)
@WithMockUser(username = "testUser")
public class DatabasesControllerTest {

  @Autowired OpenHouseInternalRepository openHouseInternalRepository;

  @Autowired Catalog catalog;

  @Autowired MockMvc mvc;

  @Autowired StorageManager storageManager;

  private void deleteTableAndValidateResponse(GetTableResponseBody getTableResponseBody)
      throws Exception {
    mvc.perform(
            MockMvcRequestBuilders.delete(
                String.format(
                    CURRENT_MAJOR_VERSION_PREFIX + "/databases/%s/tables/%s",
                    getTableResponseBody.getDatabaseId(),
                    getTableResponseBody.getTableId())))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));
  }

  @Test
  @Tag("cleanUp")
  public void testGetAllDatabases() throws Exception {
    RequestAndValidateHelper.createTableAndValidateResponse(
        GET_TABLE_RESPONSE_BODY, mvc, storageManager);
    RequestAndValidateHelper.createTableAndValidateResponse(
        GET_TABLE_RESPONSE_BODY_SAME_DB, mvc, storageManager);
    RequestAndValidateHelper.createTableAndValidateResponse(
        GET_TABLE_RESPONSE_BODY_DIFF_DB, mvc, storageManager);

    mvc.perform(
            MockMvcRequestBuilders.get(CURRENT_MAJOR_VERSION_PREFIX + "/databases")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(
            content()
                .json(
                    GetAllDatabasesResponseBody.builder()
                        .results(
                            new ArrayList<>(
                                Arrays.asList(
                                    GET_DATABASE_RESPONSE_BODY,
                                    GET_DATABASE_RESPONSE_BODY_DIFF_DB)))
                        .build()
                        .toJson()));
  }

  @Test
  public void testGetAllDatabasesEmptyResult() throws Exception {
    mvc.perform(
            MockMvcRequestBuilders.get(CURRENT_MAJOR_VERSION_PREFIX + "/databases")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(
            content()
                .json(
                    GetAllDatabasesResponseBody.builder()
                        .results(new ArrayList<>())
                        .build()
                        .toJson()));
  }

  @Test
  public void testGetAllDatabasesPaginated() throws Exception {
    List<GetTableResponseBody> tables = new ArrayList<>();
    List<GetDatabaseResponseBody> databases = new ArrayList<>();
    // Create 10 tables in different databases.
    for (int i = 0; i < 10; i++) {
      String databaseId = "d" + i;
      GetTableResponseBody table = buildGetTableResponseBodyWithDbTbl(databaseId, "t1");
      tables.add(table);
      RequestAndValidateHelper.createTableAndValidateResponse(table, mvc, storageManager);
      databases.add(
          GetDatabaseResponseBody.builder()
              .databaseId(databaseId)
              .clusterId("test-cluster")
              .build());
    }
    // Get all databases with page size = 4. Number of databases in each page should be 4,4,2.
    int pageSize = 4;
    for (int i = 0; i < 3; i++) {
      int fromIndex = i * pageSize;
      int toIndex = Math.min(fromIndex + pageSize, databases.size());
      Page<GetDatabaseResponseBody> expectedResults =
          new PageImpl<>(databases.subList(fromIndex, toIndex), PageRequest.of(i, pageSize), 10);
      mvc.perform(
              MockMvcRequestBuilders.get("/v2/databases")
                  .param("page", String.valueOf(i))
                  .param("size", String.valueOf(pageSize))
                  .contentType(MediaType.APPLICATION_JSON)
                  .accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isOk())
          .andExpect(content().contentType(MediaType.APPLICATION_JSON))
          .andExpect(
              content()
                  .json(
                      GetAllDatabasesResponseBody.builder()
                          .pageResults(expectedResults)
                          .build()
                          .toJson()));
    }
    for (int i = 0; i < 10; i++) {
      RequestAndValidateHelper.deleteTableAndValidateResponse(mvc, tables.get(i));
    }
  }

  @Test
  public void testGetUpdateAclPoliciesOnDatabasesEmptyResult() throws Exception {
    mvc.perform(
            MockMvcRequestBuilders.patch(CURRENT_MAJOR_VERSION_PREFIX + "/databases/db/aclPolicies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(RequestConstants.TEST_UPDATE_ACL_POLICIES_REQUEST_BODY.toJson())
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().is(204));
  }

  // The same request other than making the URL wrong on purpose
  // Should expect BAD_REQUEST instead of RESOURCE_NOT_FOUND
  @Test
  public void testIncorrectPathThrowsSpecificException() throws Exception {
    mvc.perform(
            MockMvcRequestBuilders.patch(CURRENT_MAJOR_VERSION_PREFIX + "/databases/db/aclPolicy/")
                .contentType(MediaType.APPLICATION_JSON)
                .content(RequestConstants.TEST_UPDATE_ACL_POLICIES_REQUEST_BODY.toJson())
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().is(400));
  }

  @AfterEach
  private void cleanUpHelper(TestInfo info) {
    if (!info.getTags().contains("cleanUp")) {
      return;
    }
    try {
      // clean up the table if exists
      deleteTableAndValidateResponse(GET_TABLE_RESPONSE_BODY);
      deleteTableAndValidateResponse(GET_TABLE_RESPONSE_BODY_SAME_DB);
      deleteTableAndValidateResponse(GET_TABLE_RESPONSE_BODY_DIFF_DB);
    } catch (Exception exception) {
      log.warn("Cleaning up process interrupted with exception: {}", exception);
    }
  }

  @Autowired DatabaseEntityRepository databaseEntityRepository;

  @AfterEach
  public void cleanUpDatabaseEntities() {
    databaseEntityRepository.deleteAll();
  }

  private static Map<String, String> props(String... kv) {
    Map<String, String> map = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      map.put(kv[i], kv[i + 1]);
    }
    return map;
  }

  private static String createBody(String databaseId, Map<String, String> properties) {
    return CreateDatabaseRequestBody.builder()
        .databaseId(databaseId)
        .properties(properties)
        .build()
        .toJson();
  }

  private static String updateBody(Map<String, String> updates, List<String> removals) {
    return UpdateDatabasePropertiesRequestBody.builder()
        .updates(updates)
        .removals(removals)
        .build()
        .toJson();
  }

  private MvcResult createDatabaseEntity(String databaseId, Map<String, String> properties)
      throws Exception {
    return mvc.perform(
            MockMvcRequestBuilders.post(CURRENT_MAJOR_VERSION_PREFIX + "/databases")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(databaseId, properties))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isCreated())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.databaseId", is(equalTo(databaseId))))
        .andExpect(jsonPath("$.clusterId", is(equalTo("local-cluster"))))
        .andExpect(jsonPath("$.databaseUUID", not(emptyOrNullString())))
        .andReturn();
  }

  @Test
  public void testCreateDatabaseEntityReturnsUuidAndConflictsOnRepeat() throws Exception {
    createDatabaseEntity("db_entity_1", props("k", "v"));
    mvc.perform(
            MockMvcRequestBuilders.post(CURRENT_MAJOR_VERSION_PREFIX + "/databases")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("db_entity_1", props()))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status", is(equalTo("CONFLICT"))));
    mvc.perform(
            MockMvcRequestBuilders.post(CURRENT_MAJOR_VERSION_PREFIX + "/databases")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("DB_ENTITY_1", props()))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isConflict());
    mvc.perform(
            MockMvcRequestBuilders.post(CURRENT_MAJOR_VERSION_PREFIX + "/databases")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("bad-id!", props()))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status", is(equalTo("BAD_REQUEST"))));
  }

  @Test
  public void testGetDatabaseEntityIgnoresCaseAndNotFoundOtherwise() throws Exception {
    MvcResult created = createDatabaseEntity("Db_Entity_Get", props("a", "1", "b", "2"));
    String uuid =
        com.jayway.jsonpath.JsonPath.read(
            created.getResponse().getContentAsString(), "$.databaseUUID");

    mvc.perform(
            MockMvcRequestBuilders.get(CURRENT_MAJOR_VERSION_PREFIX + "/databases/DB_ENTITY_GET")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.databaseId", is(equalTo("Db_Entity_Get"))))
        .andExpect(jsonPath("$.databaseUUID", is(equalTo(uuid))))
        .andExpect(jsonPath("$.properties.a", is(equalTo("1"))))
        .andExpect(jsonPath("$.properties.b", is(equalTo("2"))));

    mvc.perform(
            MockMvcRequestBuilders.get(CURRENT_MAJOR_VERSION_PREFIX + "/databases/nope")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status", is(equalTo("NOT_FOUND"))))
        .andExpect(jsonPath("$.message", containsString("nope")));
  }

  @Test
  public void testDeleteDatabaseEntityLeavesTablesUntouched() throws Exception {
    // A table in d1 and a database entity for d1.
    RequestAndValidateHelper.createTableAndValidateResponse(
        GET_TABLE_RESPONSE_BODY, mvc, storageManager);
    String tablePath =
        CURRENT_MAJOR_VERSION_PREFIX
            + "/databases/"
            + GET_TABLE_RESPONSE_BODY.getDatabaseId()
            + "/tables/"
            + GET_TABLE_RESPONSE_BODY.getTableId();
    String tableJsonBefore =
        mvc.perform(MockMvcRequestBuilders.get(tablePath).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // Creating the database entity does not alter the table (E4).
    createDatabaseEntity(GET_TABLE_RESPONSE_BODY.getDatabaseId(), props("db.k", "v"));
    String tableJsonAfterCreate =
        mvc.perform(MockMvcRequestBuilders.get(tablePath).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Assertions.assertEquals(tableJsonBefore, tableJsonAfterCreate);

    // Deleting the entity succeeds even though the database is not empty, then 404 (R3 vs E6).
    mvc.perform(
            MockMvcRequestBuilders.delete(
                CURRENT_MAJOR_VERSION_PREFIX
                    + "/databases/"
                    + GET_TABLE_RESPONSE_BODY.getDatabaseId()))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));
    mvc.perform(
            MockMvcRequestBuilders.delete(
                CURRENT_MAJOR_VERSION_PREFIX
                    + "/databases/"
                    + GET_TABLE_RESPONSE_BODY.getDatabaseId()))
        .andExpect(status().isNotFound());

    // The table still loads with identical tableProperties (E6).
    String tableJsonAfterDelete =
        mvc.perform(MockMvcRequestBuilders.get(tablePath).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Assertions.assertEquals(tableJsonBefore, tableJsonAfterDelete);

    RequestAndValidateHelper.deleteTableAndValidateResponse(mvc, GET_TABLE_RESPONSE_BODY);
  }

  @Test
  public void testCreatingTableDoesNotCreateDatabaseEntity() throws Exception {
    // E5: a table in a database with no entity neither creates one nor fails.
    RequestAndValidateHelper.createTableAndValidateResponse(
        GET_TABLE_RESPONSE_BODY_DIFF_DB, mvc, storageManager);
    mvc.perform(
            MockMvcRequestBuilders.get(
                    CURRENT_MAJOR_VERSION_PREFIX
                        + "/databases/"
                        + GET_TABLE_RESPONSE_BODY_DIFF_DB.getDatabaseId())
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound());
    Assertions.assertFalse(
        databaseEntityRepository.existsByDatabaseIdIgnoreCase(
            GET_TABLE_RESPONSE_BODY_DIFF_DB.getDatabaseId()));
    // The listing still derives the database from the table and carries no entity fields.
    mvc.perform(
            MockMvcRequestBuilders.get(CURRENT_MAJOR_VERSION_PREFIX + "/databases")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                    "$.results[?(@.databaseId == '"
                        + GET_TABLE_RESPONSE_BODY_DIFF_DB.getDatabaseId()
                        + "')].databaseUUID")
                .isEmpty())
        .andExpect(
            jsonPath(
                    "$.results[?(@.databaseId == '"
                        + GET_TABLE_RESPONSE_BODY_DIFF_DB.getDatabaseId()
                        + "')].properties")
                .isEmpty());
    RequestAndValidateHelper.deleteTableAndValidateResponse(mvc, GET_TABLE_RESPONSE_BODY_DIFF_DB);
  }

  @Test
  public void testUpdateDatabasePropertiesFollowsIcebergContract() throws Exception {
    createDatabaseEntity("db_entity_upd", props());
    String propertiesPath = CURRENT_MAJOR_VERSION_PREFIX + "/databases/db_entity_upd/properties";

    mvc.perform(
            MockMvcRequestBuilders.post(propertiesPath)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody(props("a", "1", "b", "2"), Collections.emptyList()))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.updated", containsInAnyOrder("a", "b")))
        .andExpect(jsonPath("$.removed", empty()))
        .andExpect(jsonPath("$.missing", empty()));

    mvc.perform(
            MockMvcRequestBuilders.post(propertiesPath)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody(props("a", "9"), Arrays.asList("b", "c")))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updated", contains("a")))
        .andExpect(jsonPath("$.removed", contains("b")))
        .andExpect(jsonPath("$.missing", contains("c")));

    mvc.perform(
            MockMvcRequestBuilders.get(CURRENT_MAJOR_VERSION_PREFIX + "/databases/db_entity_upd")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.properties", aMapWithSize(1)))
        .andExpect(jsonPath("$.properties.a", is(equalTo("9"))));

    // A key in both sets is 422 and changes nothing.
    mvc.perform(
            MockMvcRequestBuilders.post(propertiesPath)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody(props("a", "2"), Collections.singletonList("a")))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.status", is(equalTo("UNPROCESSABLE_ENTITY"))));
    mvc.perform(
            MockMvcRequestBuilders.get(CURRENT_MAJOR_VERSION_PREFIX + "/databases/db_entity_upd")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.properties.a", is(equalTo("9"))));

    mvc.perform(
            MockMvcRequestBuilders.post(CURRENT_MAJOR_VERSION_PREFIX + "/databases/nope/properties")
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody(props("a", "1"), Collections.emptyList()))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound());
    mvc.perform(
            MockMvcRequestBuilders.post(propertiesPath)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"updates\":{\"\":\"v\"}}")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
  }
}
