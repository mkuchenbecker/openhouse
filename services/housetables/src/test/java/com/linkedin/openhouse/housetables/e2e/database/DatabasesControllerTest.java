package com.linkedin.openhouse.housetables.e2e.database;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.housetables.api.spec.model.Database;
import com.linkedin.openhouse.housetables.api.spec.request.CreateUpdateEntityRequestBody;
import com.linkedin.openhouse.housetables.api.spec.request.UpdateDatabasePropertiesRequestBody;
import com.linkedin.openhouse.housetables.model.DatabaseRow;
import com.linkedin.openhouse.housetables.model.UserTableRow;
import com.linkedin.openhouse.housetables.repository.impl.jdbc.DatabaseHtsJdbcRepository;
import com.linkedin.openhouse.housetables.repository.impl.jdbc.UserTableHtsJdbcRepository;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@SpringBootTest
@ContextConfiguration(initializers = PropertyOverrideContextInitializer.class)
@AutoConfigureMockMvc
public class DatabasesControllerTest {

  private static final String DATABASES = "/hts/databases";
  private static final String DATABASE_PROPERTIES = "/hts/databases/properties";

  @Autowired MockMvc mvc;

  @Autowired DatabaseHtsJdbcRepository databaseRepository;

  @Autowired UserTableHtsJdbcRepository userTableRepository;

  @AfterEach
  public void tearDown() {
    databaseRepository.deleteAll();
    userTableRepository.deleteAll();
  }

  private static Map<String, String> props(String... kv) {
    Map<String, String> map = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      map.put(kv[i], kv[i + 1]);
    }
    return map;
  }

  private static String createBody(String databaseId, Map<String, String> properties) {
    return CreateUpdateEntityRequestBody.<Database>builder()
        .entity(Database.builder().databaseId(databaseId).properties(properties).build())
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

  private MvcResult create(String databaseId, Map<String, String> properties) throws Exception {
    return mvc.perform(
            MockMvcRequestBuilders.post(DATABASES)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(databaseId, properties))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isCreated())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.entity.databaseId", is(equalTo(databaseId))))
        .andExpect(jsonPath("$.entity.databaseUuid", not(emptyOrNullString())))
        .andExpect(jsonPath("$.entity.creationTime", notNullValue()))
        .andReturn();
  }

  @Test
  public void testCreateReturnsServerGeneratedUuidAndRejectsDuplicatesIgnoringCase()
      throws Exception {
    create("ctl_db", props("k", "v"));
    Assertions.assertTrue(databaseRepository.existsByDatabaseIdIgnoreCase("ctl_db"));

    mvc.perform(
            MockMvcRequestBuilders.post(DATABASES)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("ctl_db", props()))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status", is(equalTo("CONFLICT"))))
        .andExpect(jsonPath("$.message", containsString("ctl_db")));

    mvc.perform(
            MockMvcRequestBuilders.post(DATABASES)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("CTL_DB", props()))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isConflict());
  }

  @Test
  public void testCreateIgnoresClientSuppliedUuid() throws Exception {
    String body =
        "{\"entity\":{\"databaseUuid\":\"client-uuid\",\"databaseId\":\"ctl_uuid_db\","
            + "\"creationTime\":1,\"properties\":{\"a\":\"1\"}}}";
    mvc.perform(
            MockMvcRequestBuilders.post(DATABASES)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.entity.databaseUuid", not(equalTo("client-uuid"))))
        .andExpect(jsonPath("$.entity.creationTime", not(equalTo(1))))
        .andExpect(jsonPath("$.entity.properties.a", is(equalTo("1"))));
    DatabaseRow stored = databaseRepository.findByDatabaseIdIgnoreCase("ctl_uuid_db").get();
    Assertions.assertNotEquals("client-uuid", stored.getDatabaseUuid());
  }

  @Test
  public void testCreateWithInvalidNameIsBadRequest() throws Exception {
    mvc.perform(
            MockMvcRequestBuilders.post(DATABASES)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("bad-name!", props()))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status", is(equalTo("BAD_REQUEST"))));
    mvc.perform(
            MockMvcRequestBuilders.post(DATABASES)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"entity\":{\"properties\":{}}}")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
    Assertions.assertEquals(0, databaseRepository.count());
  }

  @Test
  public void testGetReturnsStoredRowIgnoringCaseAndNotFoundOtherwise() throws Exception {
    create("Ctl_Get_Db", props("a", "1", "b", "2"));

    mvc.perform(
            MockMvcRequestBuilders.get(DATABASES)
                .param("databaseId", "CTL_GET_DB")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.entity.databaseId", is(equalTo("Ctl_Get_Db"))))
        .andExpect(jsonPath("$.entity.databaseUuid", not(emptyOrNullString())))
        .andExpect(jsonPath("$.entity.properties.a", is(equalTo("1"))))
        .andExpect(jsonPath("$.entity.properties.b", is(equalTo("2"))));

    mvc.perform(
            MockMvcRequestBuilders.get(DATABASES)
                .param("databaseId", "ctl_nope")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status", is(equalTo("NOT_FOUND"))))
        .andExpect(jsonPath("$.error", is(equalTo("Not Found"))))
        .andExpect(jsonPath("$.message", containsString("ctl_nope")));

    mvc.perform(
            MockMvcRequestBuilders.get(DATABASES)
                .param("databaseId", "bad name")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
  }

  @Test
  public void testDeleteLeavesUserTablesUntouched() throws Exception {
    UserTableRow tableRow =
        UserTableRow.builder()
            .databaseId("ctl_del_db")
            .tableId("t1")
            .metadataLocation("/tmp/ctl_del_db/t1/metadata.json")
            .storageType("hdfs")
            .creationTime(1L)
            .build();
    userTableRepository.save(tableRow);
    create("ctl_del_db", props("a", "1"));

    mvc.perform(MockMvcRequestBuilders.delete(DATABASES).param("databaseId", "CTL_DEL_DB"))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));

    mvc.perform(MockMvcRequestBuilders.delete(DATABASES).param("databaseId", "ctl_del_db"))
        .andExpect(status().isNotFound());

    mvc.perform(
            MockMvcRequestBuilders.get(DATABASES)
                .param("databaseId", "ctl_del_db")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound());

    // The table row is unchanged and still served by the user table endpoint.
    Assertions.assertTrue(
        userTableRepository.existsByDatabaseIdIgnoreCaseAndTableIdIgnoreCase("ctl_del_db", "t1"));
    mvc.perform(
            MockMvcRequestBuilders.get("/hts/tables")
                .param("databaseId", "ctl_del_db")
                .param("tableId", "t1")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entity.databaseId", is(equalTo("ctl_del_db"))))
        .andExpect(jsonPath("$.entity.tableId", is(equalTo("t1"))))
        .andExpect(
            jsonPath("$.entity.metadataLocation", is(equalTo("/tmp/ctl_del_db/t1/metadata.json"))));
  }

  @Test
  public void testCreatingDatabaseDoesNotCreateOrAlterUserTables() throws Exception {
    long tablesBefore = userTableRepository.count();
    create("ctl_no_tables", props());
    Assertions.assertEquals(tablesBefore, userTableRepository.count());
  }

  @Test
  public void testUpdatePropertiesSparseSemanticsAndVersioning() throws Exception {
    create("ctl_upd", props());
    String uuid = databaseRepository.findByDatabaseIdIgnoreCase("ctl_upd").get().getDatabaseUuid();
    long version0 = databaseRepository.findById(uuid).get().getVersion();

    mvc.perform(
            MockMvcRequestBuilders.patch(DATABASE_PROPERTIES)
                .param("databaseId", "ctl_upd")
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody(props("a", "1", "b", "2"), Collections.emptyList()))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.updated", containsInAnyOrder("a", "b")))
        .andExpect(jsonPath("$.removed", empty()))
        .andExpect(jsonPath("$.missing", empty()));
    Assertions.assertEquals(version0 + 1, databaseRepository.findById(uuid).get().getVersion());

    mvc.perform(
            MockMvcRequestBuilders.patch(DATABASE_PROPERTIES)
                .param("databaseId", "CTL_UPD")
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody(props("a", "9"), Arrays.asList("b", "c")))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updated", contains("a")))
        .andExpect(jsonPath("$.removed", contains("b")))
        .andExpect(jsonPath("$.missing", contains("c")));
    Assertions.assertEquals(version0 + 2, databaseRepository.findById(uuid).get().getVersion());
    Assertions.assertEquals(
        props("a", "9"), databaseRepository.findById(uuid).get().getProperties());

    mvc.perform(
            MockMvcRequestBuilders.get(DATABASES)
                .param("databaseId", "ctl_upd")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entity.properties", aMapWithSize(1)))
        .andExpect(jsonPath("$.entity.properties.a", is(equalTo("9"))));
  }

  @Test
  public void testUpdatePropertiesKeyInBothSetsIsUnprocessableAndChangesNothing() throws Exception {
    create("ctl_upd_both", props("a", "1"));
    String uuid =
        databaseRepository.findByDatabaseIdIgnoreCase("ctl_upd_both").get().getDatabaseUuid();
    long versionBefore = databaseRepository.findById(uuid).get().getVersion();

    mvc.perform(
            MockMvcRequestBuilders.patch(DATABASE_PROPERTIES)
                .param("databaseId", "ctl_upd_both")
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody(props("a", "2"), Collections.singletonList("a")))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.status", is(equalTo("UNPROCESSABLE_ENTITY"))))
        .andExpect(jsonPath("$.message", containsString("a")));

    DatabaseRow after = databaseRepository.findById(uuid).get();
    Assertions.assertEquals(versionBefore, after.getVersion());
    Assertions.assertEquals(props("a", "1"), after.getProperties());
  }

  @Test
  public void testUpdatePropertiesOnMissingDatabaseIsNotFoundAndBadKeysAreBadRequest()
      throws Exception {
    mvc.perform(
            MockMvcRequestBuilders.patch(DATABASE_PROPERTIES)
                .param("databaseId", "ctl_missing")
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody(props("a", "1"), Collections.emptyList()))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound());

    create("ctl_upd_bad", props());
    mvc.perform(
            MockMvcRequestBuilders.patch(DATABASE_PROPERTIES)
                .param("databaseId", "ctl_upd_bad")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"updates\":{\"\":\"v\"}}")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
    mvc.perform(
            MockMvcRequestBuilders.patch(DATABASE_PROPERTIES)
                .param("databaseId", "ctl_upd_bad")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"removals\":[\"\"]}")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
  }
}
