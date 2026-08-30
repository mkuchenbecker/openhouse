package com.linkedin.openhouse.housetables.e2e.database;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.gson.Gson;
import com.linkedin.openhouse.common.exception.EntityConcurrentModificationException;
import com.linkedin.openhouse.common.exception.NoSuchEntityException;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.common.utils.NamespacePropertiesValidator;
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
                .version(created.getFirst().getVersion())
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
        NoSuchEntityException.class, () -> databasesService.deleteDatabase("d_absent", null));
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
    databasesService.deleteDatabase("d_drop", null);
    Assertions.assertThrows(
        NoSuchEntityException.class, () -> databasesService.getDatabase("d_drop"));
  }

  /**
   * B4: the version a writer read is the version its write is conditional on. Re-reading the stored
   * version inside this service and handing it to save() would compare the row against itself, so
   * both of two concurrent updates would return 200 and one would be silently discarded.
   */
  @Test
  public void twoUpdatesFromTheSameReadConflictInsteadOfBothSucceeding() {
    Pair<Database, Boolean> created =
        databasesService.putDatabase(
            Database.builder()
                .databaseId("d_race")
                .properties(Collections.singletonMap("owner", "first"))
                .build());
    Long readVersion = created.getFirst().getVersion();
    Assertions.assertNotNull(readVersion, "a stored database carries a version");

    // Writer A commits against the version both writers read.
    databasesService.putDatabase(
        Database.builder()
            .databaseId("d_race")
            .version(readVersion)
            .properties(Collections.singletonMap("owner", "a"))
            .build());

    // Writer B still holds the same version. Its write is based on state that no longer exists.
    Assertions.assertThrows(
        EntityConcurrentModificationException.class,
        () ->
            databasesService.putDatabase(
                Database.builder()
                    .databaseId("d_race")
                    .version(readVersion)
                    .properties(Collections.singletonMap("owner", "b"))
                    .build()));

    Assertions.assertEquals(
        "a",
        databasesService.getDatabase("d_race").getProperties().get("owner"),
        "the write that won is the one that is stored");
  }

  /** B4: a create asserts "there was no row", so the second of two concurrent creates conflicts. */
  @Test
  public void aSecondCreateOfTheSameDatabaseConflicts() {
    databasesService.putDatabase(Database.builder().databaseId("d_twice").build());
    Assertions.assertThrows(
        EntityConcurrentModificationException.class,
        () -> databasesService.putDatabase(Database.builder().databaseId("d_twice").build()));
  }

  /** B4: a write carrying a version for a row that is gone is a conflict, not a resurrection. */
  @Test
  public void anUpdateOfADeletedDatabaseConflicts() {
    Pair<Database, Boolean> created =
        databasesService.putDatabase(Database.builder().databaseId("d_gone").build());
    databasesService.deleteDatabase("d_gone", null);
    Assertions.assertThrows(
        EntityConcurrentModificationException.class,
        () ->
            databasesService.putDatabase(
                Database.builder()
                    .databaseId("d_gone")
                    .version(created.getFirst().getVersion())
                    .build()));
  }

  /** B4: a conditional delete is refused when the row has moved on. */
  @Test
  public void aConditionalDeleteAtTheWrongVersionConflicts() {
    Pair<Database, Boolean> created =
        databasesService.putDatabase(Database.builder().databaseId("d_cd").build());
    Long stale = created.getFirst().getVersion();
    databasesService.putDatabase(
        Database.builder()
            .databaseId("d_cd")
            .version(stale)
            .properties(Collections.singletonMap("k", "v"))
            .build());
    Assertions.assertThrows(
        EntityConcurrentModificationException.class,
        () -> databasesService.deleteDatabase("d_cd", stale));
    Assertions.assertNotNull(databasesService.getDatabase("d_cd"));
  }

  /**
   * B4 over HTTP: the conflict has to be a 409 the caller can act on, not a 500. This is the status
   * {@code HouseNamespaceRepositoryImpl} translates into {@code
   * HouseTableConcurrentUpdateException}.
   */
  @Test
  public void aConflictingPutOverHttpIs409() throws Exception {
    databasesService.putDatabase(Database.builder().databaseId("d_409").build());
    CreateUpdateEntityRequestBody<Database> body =
        CreateUpdateEntityRequestBody.<Database>builder()
            .entity(Database.builder().databaseId("d_409").build())
            .build();
    mvc.perform(
            MockMvcRequestBuilders.put(ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(new Gson().toJson(body))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isConflict());
  }

  /**
   * B7: the property bounds hold here too. House Tables is reachable without the Tables Service in
   * front of it, so a bound only the caller enforces is not a bound.
   */
  @Test
  public void anOversizedPropertyBagIsRejectedAtTheHtsBoundary() throws Exception {
    Map<String, String> tooMany = new HashMap<>();
    for (int i = 0; i <= NamespacePropertiesValidator.MAX_PROPERTY_ENTRIES; i++) {
      tooMany.put("k" + i, "v");
    }
    mvc.perform(putRequest(tooMany)).andExpect(status().isBadRequest());

    Map<String, String> tooLong = new HashMap<>();
    tooLong.put("k", repeat("v", NamespacePropertiesValidator.MAX_PROPERTY_ENTRY_BYTES + 1));
    mvc.perform(putRequest(tooLong)).andExpect(status().isBadRequest());

    Map<String, String> tooBig = new HashMap<>();
    for (int i = 0; i < 20; i++) {
      tooBig.put("k" + i, repeat("v", 900));
    }
    mvc.perform(putRequest(tooBig)).andExpect(status().isBadRequest());

    Assertions.assertTrue(
        databasesService.getAllDatabases().isEmpty(), "nothing oversized was stored");
  }

  /** B7: a null value is acknowledged and then dropped by the JSON encoding, so it is rejected. */
  @Test
  public void aNullPropertyValueIsRejectedAtTheHtsBoundary() throws Exception {
    Map<String, String> withNull = new HashMap<>();
    withNull.put("k", null);
    mvc.perform(putRequest(withNull)).andExpect(status().isBadRequest());
    Assertions.assertTrue(databasesService.getAllDatabases().isEmpty());
  }

  private org.springframework.test.web.servlet.RequestBuilder putRequest(
      Map<String, String> properties) {
    CreateUpdateEntityRequestBody<Database> body =
        CreateUpdateEntityRequestBody.<Database>builder()
            .entity(Database.builder().databaseId("d_bounds").properties(properties).build())
            .build();
    return MockMvcRequestBuilders.put(ENDPOINT)
        .contentType(MediaType.APPLICATION_JSON)
        .content(new com.google.gson.GsonBuilder().serializeNulls().create().toJson(body))
        .accept(MediaType.APPLICATION_JSON);
  }

  private static String repeat(String unit, int times) {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < times; i++) {
      builder.append(unit);
    }
    return builder.toString();
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
