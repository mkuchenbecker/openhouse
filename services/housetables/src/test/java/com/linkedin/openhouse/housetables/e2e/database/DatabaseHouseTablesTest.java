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

  private static final String CHILDREN_ENDPOINT = "/hts/databases/children";

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

  /**
   * The seam that made an encoded multi-level namespace unable to cross the wire at all. A '.' is
   * now inside the /hts/databases charset, end to end: accepted on the write, readable back, and
   * listable.
   */
  @Test
  public void anEncodedMultiLevelNamespaceCrossesTheHtsBoundary() throws Exception {
    CreateUpdateEntityRequestBody<Database> body =
        CreateUpdateEntityRequestBody.<Database>builder()
            .entity(Database.builder().databaseId("parent.child").build())
            .build();

    mvc.perform(
            MockMvcRequestBuilders.put(ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(new Gson().toJson(body))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.entity.databaseId", is("parent.child")));

    mvc.perform(
            MockMvcRequestBuilders.get(ENDPOINT)
                .param("databaseId", "parent.child")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entity.databaseId", is("parent.child")));
  }

  /**
   * The widening added the separator and nothing else. Each of these was rejected before and must
   * still be; a charset that let one of them through would be a widening, not a separator.
   */
  @Test
  public void theWidenedCharsetStillRejectsEverythingElse() throws Exception {
    for (String rejected :
        new String[] {"not-legal", "has space", "db;drop", "db/sub", "a.", ".a"}) {
      mvc.perform(
              MockMvcRequestBuilders.get(ENDPOINT)
                  .param("databaseId", rejected)
                  .accept(MediaType.APPLICATION_JSON))
          .andExpect(status().isBadRequest());
    }
  }

  /**
   * The listing primitive nesting needs. Direct children only: a grandchild is in the subtree but
   * is not a child, the parent is not its own child, and a namespace whose name merely starts with
   * the parent's is not underneath it at all.
   *
   * <p>Calibration: dropping the {@code isDirectChild} filter in the service adds {@code a.b.c} to
   * the result and turns this red; widening the range's upper bound to the end of the key space
   * adds {@code ab} and {@code b}.
   */
  @Test
  public void childrenOfReturnsTheDirectChildrenAndNothingElse() {
    for (String namespaceId :
        new String[] {"a", "a.b", "a.c", "a.b.c", "ab", "ab.d", "b", "a_x", "a_x.y"}) {
      databasesService.putDatabase(Database.builder().databaseId(namespaceId).build());
    }

    Assertions.assertEquals(
        Arrays.asList("a.b", "a.c"),
        databasesService.getChildDatabases("a").stream()
            .map(Database::getDatabaseId)
            .collect(Collectors.toList()));
    Assertions.assertEquals(
        Collections.singletonList("a.b.c"),
        databasesService.getChildDatabases("a.b").stream()
            .map(Database::getDatabaseId)
            .collect(Collectors.toList()));
    Assertions.assertEquals(
        Collections.singletonList("a_x.y"),
        databasesService.getChildDatabases("a_x").stream()
            .map(Database::getDatabaseId)
            .collect(Collectors.toList()),
        "an underscore in the parent name is a literal, not a LIKE wildcard");
    Assertions.assertTrue(databasesService.getChildDatabases("a.c").isEmpty(), "a leaf has none");
  }

  /**
   * Listing the children of a namespace that is not there is an empty list, not a 404: existence is
   * the Tables Service's question, and answering it here would give one call two failure modes for
   * the same state.
   */
  @Test
  public void childrenOfAnAbsentParentIsEmptyRatherThanNotFound() throws Exception {
    Assertions.assertTrue(databasesService.getChildDatabases("nothing_here").isEmpty());

    mvc.perform(
            MockMvcRequestBuilders.get(CHILDREN_ENDPOINT)
                .param("databaseId", "nothing_here")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results.length()", is(0)));
  }

  /** The route exists, is charset-validated like the rest of the surface, and returns the rows. */
  @Test
  public void theChildrenRouteAnswersOverHttp() throws Exception {
    databasesService.putDatabase(Database.builder().databaseId("p").build());
    databasesService.putDatabase(Database.builder().databaseId("p.q").build());
    databasesService.putDatabase(Database.builder().databaseId("p.q.r").build());

    mvc.perform(
            MockMvcRequestBuilders.get(CHILDREN_ENDPOINT)
                .param("databaseId", "p")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results.length()", is(1)))
        .andExpect(jsonPath("$.results[0].databaseId", is("p.q")));

    mvc.perform(
            MockMvcRequestBuilders.get(CHILDREN_ENDPOINT)
                .param("databaseId", "not-legal")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
  }
}
