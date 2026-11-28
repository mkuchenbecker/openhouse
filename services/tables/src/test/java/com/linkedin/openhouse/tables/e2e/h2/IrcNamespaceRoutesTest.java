package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.body;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.linkedin.openhouse.common.exception.checked.NotFoundException;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.repository.HouseNamespaceRepository;
import com.linkedin.openhouse.tables.toggle.repository.ToggleStatusesRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.IrcClientParsers;
import org.apache.iceberg.rest.responses.CreateNamespaceResponse;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.apache.iceberg.rest.responses.GetNamespaceResponse;
import org.apache.iceberg.rest.responses.ListNamespacesResponse;
import org.apache.iceberg.rest.responses.ListTablesResponse;
import org.apache.iceberg.rest.responses.UpdateNamespacePropertiesResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The Iceberg REST Catalog (IRC) namespace routes over stored namespace records: create, update
 * properties and drop store and change a record through the namespace service, and load, exists and
 * the existence checks of the list routes see it. A namespace exists if it has a record or holds a
 * table or a view. Every response is parsed with the object mapper of Iceberg's {@code
 * RESTCatalog}; the record store is the embedded service's stand-in, checked directly where a route
 * must not have written.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class IrcNamespaceRoutesTest {

  @Autowired MockMvc mvc;
  @Autowired HouseNamespaceRepository records;
  @Autowired ToggleStatusesRepository toggles;

  private final List<String[]> tables = new ArrayList<>();
  private final List<String[]> views = new ArrayList<>();
  private final List<String> namespaces = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String[] key : views) {
      NativeViewHttp.delete(mvc, key[0], key[1]);
    }
    for (String[] key : tables) {
      CommitPathHttp.delete(mvc, key[0], key[1]);
    }
    for (String namespace : namespaces) {
      try {
        records.delete(namespace);
      } catch (NotFoundException e) {
        // dropped by the test
      }
    }
    views.clear();
    tables.clear();
    namespaces.clear();
  }

  private void table(String db, String table) throws Exception {
    tables.add(new String[] {db, table});
    CommitPathHttp.post(mvc, CommitPathHttp.newTable(db, table)).andExpect(status().isCreated());
  }

  static MvcResult post(MockMvc mvc, String path, String json) throws Exception {
    return IrcHttp.perform(
        mvc,
        IrcHttp.request(HttpMethod.POST, path)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  static MvcResult createNamespace(MockMvc mvc, String name, Map<String, String> properties)
      throws Exception {
    return post(
        mvc,
        "/v1/namespaces",
        "{\"namespace\":[\"" + name + "\"],\"properties\":" + json(properties) + "}");
  }

  static MvcResult updateProperties(
      MockMvc mvc, String name, Map<String, String> updates, List<String> removals)
      throws Exception {
    StringBuilder removed = new StringBuilder("[");
    for (int i = 0; i < removals.size(); i++) {
      removed.append(i == 0 ? "" : ",").append('"').append(removals.get(i)).append('"');
    }
    return post(
        mvc,
        "/v1/namespaces/" + name + "/properties",
        "{\"updates\":" + json(updates) + ",\"removals\":" + removed + "]}");
  }

  static MvcResult dropNamespace(MockMvc mvc, String name) throws Exception {
    return IrcHttp.perform(mvc, IrcHttp.request(HttpMethod.DELETE, "/v1/namespaces/" + name));
  }

  private static String json(Map<String, String> map) {
    StringBuilder json = new StringBuilder("{");
    map.forEach(
        (k, v) ->
            json.append(json.length() > 1 ? "," : "")
                .append('"')
                .append(k)
                .append("\":\"")
                .append(v)
                .append('"'));
    return json.append('}').toString();
  }

  private MvcResult created(String name, Map<String, String> properties) throws Exception {
    namespaces.add(name);
    MvcResult result = createNamespace(mvc, name, properties);
    assertEquals(200, result.getResponse().getStatus(), body(result));
    return result;
  }

  private boolean hasRecord(String name) {
    try {
      records.getByName(name);
      return true;
    } catch (NotFoundException e) {
      return false;
    }
  }

  // ------------------------------------------------------------- a namespace with a record only

  /**
   * A namespace created over IRC has a record and no table: it exists, loads with the spelling and
   * properties it was created with (by any case of its name), and its tables and views are empty
   * lists. Its properties are set and removed sparsely, and a drop removes it.
   */
  @Test
  void aCreatedNamespaceExistsLoadsItsPropertiesAndIsDropped() throws Exception {
    MvcResult create = created("Irc_Ns_Rec", Collections.singletonMap("k", "v"));
    CreateNamespaceResponse createdBody =
        IrcClientParsers.parse(body(create), CreateNamespaceResponse.class);
    assertEquals(Namespace.of("Irc_Ns_Rec"), createdBody.namespace());
    assertEquals(Collections.singletonMap("k", "v"), createdBody.properties());

    MvcResult load = IrcHttp.get(mvc, "/v1/namespaces/irc_ns_rec");
    assertEquals(200, load.getResponse().getStatus(), body(load));
    GetNamespaceResponse loaded = IrcClientParsers.parse(body(load), GetNamespaceResponse.class);
    assertEquals(Namespace.of("Irc_Ns_Rec"), loaded.namespace());
    assertEquals(Collections.singletonMap("k", "v"), loaded.properties());
    assertEquals(204, IrcHttp.head(mvc, "/v1/namespaces/IRC_NS_REC").getResponse().getStatus());

    MvcResult listTables = IrcHttp.get(mvc, "/v1/namespaces/Irc_Ns_Rec/tables");
    assertEquals(200, listTables.getResponse().getStatus(), body(listTables));
    assertTrue(
        IrcClientParsers.parse(body(listTables), ListTablesResponse.class).identifiers().isEmpty());
    MvcResult listViews = IrcHttp.get(mvc, IrcViewWrite.views("Irc_Ns_Rec"));
    assertEquals(200, listViews.getResponse().getStatus(), body(listViews));
    assertTrue(
        IrcClientParsers.parse(body(listViews), ListTablesResponse.class).identifiers().isEmpty());
    MvcResult children =
        IrcHttp.perform(
            mvc, IrcHttp.request(HttpMethod.GET, "/v1/namespaces").param("parent", "Irc_Ns_Rec"));
    assertEquals(200, children.getResponse().getStatus(), body(children));

    MvcResult update =
        updateProperties(
            mvc,
            "Irc_Ns_Rec",
            Collections.singletonMap("a", "1"),
            Arrays.asList("k", "missing_key"));
    assertEquals(200, update.getResponse().getStatus(), body(update));
    UpdateNamespacePropertiesResponse updated =
        IrcClientParsers.parse(body(update), UpdateNamespacePropertiesResponse.class);
    assertEquals(Collections.singletonList("a"), updated.updated());
    assertEquals(Collections.singletonList("k"), updated.removed());
    assertEquals(Collections.singletonList("missing_key"), updated.missing());
    assertEquals(
        Collections.singletonMap("a", "1"),
        IrcClientParsers.parse(
                body(IrcHttp.get(mvc, "/v1/namespaces/Irc_Ns_Rec")), GetNamespaceResponse.class)
            .properties());

    MvcResult drop = dropNamespace(mvc, "irc_ns_rec");
    assertEquals(204, drop.getResponse().getStatus(), body(drop));
    assertEquals("", body(drop));
    assertFalse(hasRecord("Irc_Ns_Rec"));
    assertEquals(
        "Namespace does not exist: irc_ns_rec",
        assertIrcError(
                IrcHttp.get(mvc, "/v1/namespaces/irc_ns_rec"), 404, "NoSuchNamespaceException")
            .message());
    assertEquals(404, IrcHttp.head(mvc, "/v1/namespaces/irc_ns_rec").getResponse().getStatus());
  }

  /** A namespace with a record and tables loads with the record's properties. */
  @Test
  void aNamespaceWithARecordAndTablesLoadsTheRecord() throws Exception {
    created("irc_ns_both", Collections.singletonMap("owner", "o"));
    table("irc_ns_both", "t1");
    GetNamespaceResponse loaded =
        IrcClientParsers.parse(
            body(IrcHttp.get(mvc, "/v1/namespaces/irc_ns_both")), GetNamespaceResponse.class);
    assertEquals(Collections.singletonMap("owner", "o"), loaded.properties());
  }

  /**
   * KNOWN GAP: the namespace list stays the native database list, which comes from tables. House
   * Tables has no list of namespace records, so a namespace that has a record and no table is not
   * listed, although it exists.
   */
  @Test
  void aNamespaceWithARecordAndNoTableIsNotListedKnownGap() throws Exception {
    created("irc_ns_unlisted", Collections.emptyMap());
    assertEquals(
        204, IrcHttp.head(mvc, "/v1/namespaces/irc_ns_unlisted").getResponse().getStatus());
    MvcResult list = IrcHttp.get(mvc, "/v1/namespaces");
    assertEquals(200, list.getResponse().getStatus(), body(list));
    assertFalse(
        IrcClientParsers.parse(body(list), ListNamespacesResponse.class)
            .namespaces()
            .contains(Namespace.of("irc_ns_unlisted")));
  }

  // ------------------------------------------------------------- conflicts and missing namespaces

  @Test
  void aSecondCreateInAnyCaseIs409AndKeepsTheFirst() throws Exception {
    created("Irc_Ns_Dup", Collections.singletonMap("k", "first"));
    ErrorResponse error =
        assertIrcError(
            createNamespace(mvc, "IRC_NS_DUP", Collections.singletonMap("k", "second")),
            409,
            "AlreadyExistsException");
    assertEquals("Namespace already exists: IRC_NS_DUP", error.message());
    GetNamespaceResponse loaded =
        IrcClientParsers.parse(
            body(IrcHttp.get(mvc, "/v1/namespaces/irc_ns_dup")), GetNamespaceResponse.class);
    assertEquals(Namespace.of("Irc_Ns_Dup"), loaded.namespace());
    assertEquals(Collections.singletonMap("k", "first"), loaded.properties());
  }

  /**
   * A namespace that holds a table exists already, so a create of it is the spec's conflict, and no
   * record is stored: a database that holds tables cannot get a record through these routes.
   */
  @Test
  void aCreateOfANamespaceThatHoldsATableIs409AndStoresNoRecord() throws Exception {
    table("irc_ns_tabled", "t1");
    namespaces.add("irc_ns_tabled");
    ErrorResponse error =
        assertIrcError(
            createNamespace(mvc, "irc_ns_tabled", Collections.singletonMap("k", "v")),
            409,
            "AlreadyExistsException");
    assertEquals("Namespace already exists: irc_ns_tabled", error.message());
    assertFalse(hasRecord("irc_ns_tabled"));
  }

  /**
   * A consequence of the two existence rules (a record, or a table): a namespace that exists by
   * holding tables has no record, and an update of its properties is the namespace service's answer
   * for a missing record, 404, although the namespace loads. No record is created.
   */
  @Test
  void updatingPropertiesOfANamespaceThatOnlyHoldsTablesIs404AsAConsequenceOfTheTwoExistenceRules()
      throws Exception {
    table("irc_ns_upd_tabled", "t1");
    namespaces.add("irc_ns_upd_tabled");
    assertEquals(
        200, IrcHttp.get(mvc, "/v1/namespaces/irc_ns_upd_tabled").getResponse().getStatus());
    ErrorResponse error =
        assertIrcError(
            updateProperties(
                mvc, "irc_ns_upd_tabled", Collections.singletonMap("k", "v"), List.of()),
            404,
            "NoSuchNamespaceException");
    assertEquals("Namespace does not exist: irc_ns_upd_tabled", error.message());
    assertFalse(hasRecord("irc_ns_upd_tabled"));
  }

  @Test
  void updatingAMissingNamespaceIs404() throws Exception {
    assertEquals(
        "Namespace does not exist: irc_ns_upd_none",
        assertIrcError(
                updateProperties(
                    mvc, "irc_ns_upd_none", Collections.singletonMap("k", "v"), List.of()),
                404,
                "NoSuchNamespaceException")
            .message());
  }

  @Test
  void droppingAMissingNamespaceIs404() throws Exception {
    assertEquals(
        "Namespace does not exist: irc_ns_drop_none",
        assertIrcError(dropNamespace(mvc, "irc_ns_drop_none"), 404, "NoSuchNamespaceException")
            .message());
  }

  /**
   * The spec refuses the drop of a namespace that is not empty. A namespace that holds tables and
   * has no record is not empty (nothing is dropped, its tables stay); one with a record and a table
   * keeps its record until the table is gone, and is dropped then.
   */
  @Test
  void aNamespaceThatHoldsATableIsNotDropped() throws Exception {
    table("irc_ns_drop_tabled", "t1");
    ErrorResponse error =
        assertIrcError(dropNamespace(mvc, "irc_ns_drop_tabled"), 409, "NamespaceNotEmptyException");
    assertEquals("Namespace irc_ns_drop_tabled is not empty", error.message());
    assertEquals(200, CommitPathHttp.status(CommitPathHttp.get(mvc, "irc_ns_drop_tabled", "t1")));

    created("irc_ns_drop_both", Collections.emptyMap());
    table("irc_ns_drop_both", "t1");
    assertIrcError(dropNamespace(mvc, "irc_ns_drop_both"), 409, "NamespaceNotEmptyException");
    assertTrue(hasRecord("irc_ns_drop_both"));
    CommitPathHttp.delete(mvc, "irc_ns_drop_both", "t1").andExpect(status().isNoContent());
    tables.removeIf(key -> key[0].equals("irc_ns_drop_both"));
    assertEquals(204, dropNamespace(mvc, "irc_ns_drop_both").getResponse().getStatus());
    assertFalse(hasRecord("irc_ns_drop_both"));
  }

  /** A database {@code db} that holds view {@code v} and no table, with its views enabled. */
  private void viewOnly(String db) throws Exception {
    NativeViewHttp.enableViews(toggles, db);
    table(db, "t");
    views.add(new String[] {db, "v"});
    MvcResult view =
        NativeViewHttp.post(
            mvc,
            NativeViewHttp.createBody(
                db,
                "v",
                NativeViewHttp.SCHEMA_ID_NAME,
                "SELECT id, name FROM " + db + ".t",
                Collections.emptyMap()));
    assertEquals(201, NativeViewHttp.code(view), NativeViewHttp.body(view));
    CommitPathHttp.delete(mvc, db, "t").andExpect(status().isNoContent());
    tables.removeIf(key -> key[0].equals(db));
  }

  /**
   * HEAD, load, create and drop of {@code db}, in that order, with the answers expected, and the
   * rule that ties them: one existence rule for every route. If HEAD finds the namespace, load
   * finds it, create is the conflict of an existing namespace, and drop never says it is missing;
   * if HEAD does not, load is 404, drop is 404 and create stores a record. Returns the drop's
   * status.
   */
  private int assertOneExistenceRule(String db, int expectedDrop) throws Exception {
    int head = IrcHttp.head(mvc, "/v1/namespaces/" + db).getResponse().getStatus();
    assertEquals(
        expectedDrop == 404,
        head == 404,
        db + ": a drop answering " + expectedDrop + " was expected, but HEAD answered " + head);
    MvcResult load = IrcHttp.get(mvc, "/v1/namespaces/" + db);
    if (head == 204) {
      assertEquals(200, load.getResponse().getStatus(), db + ": " + body(load));
      assertIrcError(
          createNamespace(mvc, db, Collections.emptyMap()), 409, "AlreadyExistsException");
      MvcResult drop = dropNamespace(mvc, db);
      int dropped = drop.getResponse().getStatus();
      assertTrue(dropped != 404, db + ": exists, but drop says it does not: " + body(drop));
      assertEquals(expectedDrop, dropped, db + ": " + body(drop));
      return dropped;
    }
    assertEquals(404, head, db);
    assertIrcError(load, 404, "NoSuchNamespaceException");
    assertIrcError(dropNamespace(mvc, db), 404, "NoSuchNamespaceException");
    namespaces.add(db);
    assertEquals(
        200, createNamespace(mvc, db, Collections.emptyMap()).getResponse().getStatus(), db);
    return 404;
  }

  /**
   * One existence rule on every route, for each kind of namespace: a database that holds only a
   * view (views enabled, and views disabled, when its view list is empty but it still holds the
   * view), one that holds a table, one with a record only, and a name with nothing. A namespace
   * that holds something is not empty (drop 409) whether or not the caller can list what it holds.
   */
  @Test
  void everyRouteFollowsOneExistenceRule() throws Exception {
    viewOnly("irc_ns_one_view_on");
    assertEquals(
        204, IrcHttp.head(mvc, "/v1/namespaces/irc_ns_one_view_on").getResponse().getStatus());
    assertOneExistenceRule("irc_ns_one_view_on", 409);
    assertFalse(hasRecord("irc_ns_one_view_on"));

    viewOnly("irc_ns_one_view_off");
    toggles.delete(viewsToggle("irc_ns_one_view_off"));
    try {
      MvcResult listViews = IrcHttp.get(mvc, IrcViewWrite.views("irc_ns_one_view_off"));
      assertEquals(200, listViews.getResponse().getStatus(), body(listViews));
      assertTrue(
          IrcClientParsers.parse(body(listViews), ListTablesResponse.class)
              .identifiers()
              .isEmpty());
      assertEquals(
          204, IrcHttp.head(mvc, "/v1/namespaces/irc_ns_one_view_off").getResponse().getStatus());
      assertOneExistenceRule("irc_ns_one_view_off", 409);
      assertFalse(hasRecord("irc_ns_one_view_off"));
    } finally {
      NativeViewHttp.enableViews(toggles, "irc_ns_one_view_off");
    }

    table("irc_ns_one_table", "t1");
    assertOneExistenceRule("irc_ns_one_table", 409);

    created("irc_ns_one_record", Collections.emptyMap());
    assertOneExistenceRule("irc_ns_one_record", 204);
    assertFalse(hasRecord("irc_ns_one_record"));

    assertOneExistenceRule("irc_ns_one_none", 404);
    assertTrue(hasRecord("irc_ns_one_none"));
  }

  /**
   * The one existence rule ignores letter case, as House Tables' lookups and namespace records do:
   * a database is the same namespace under any spelling of its name, whether it holds views, holds
   * tables or has a record. A namespace found only through the database list loads with its stored
   * spelling.
   */
  @Test
  void theOneExistenceRuleIgnoresLetterCase() throws Exception {
    // Views in irc_ns_mix_vr and a record created as IRC_NS_MIX_VR: the drop of the upper-case
    // spelling is "not empty", and the record stays.
    created("IRC_NS_MIX_VR", Collections.singletonMap("k", "v"));
    viewOnly("irc_ns_mix_vr");
    assertOneExistenceRule("IRC_NS_MIX_VR", 409);
    assertTrue(hasRecord("irc_ns_mix_vr"));
    assertEquals(
        Namespace.of("IRC_NS_MIX_VR"),
        IrcClientParsers.parse(
                body(IrcHttp.get(mvc, "/v1/namespaces/irc_ns_mix_vr")), GetNamespaceResponse.class)
            .namespace());

    // Views in irc_ns_mix_v, no record: found under any spelling, with its stored spelling.
    viewOnly("irc_ns_mix_v");
    assertOneExistenceRule("IRC_NS_MIX_V", 409);
    assertFalse(hasRecord("irc_ns_mix_v"));
    assertEquals(
        Namespace.of("irc_ns_mix_v"),
        IrcClientParsers.parse(
                body(IrcHttp.get(mvc, "/v1/namespaces/IRC_NS_MIX_V")), GetNamespaceResponse.class)
            .namespace());

    // Tables in irc_ns_mix_t: already true before; pinned.
    table("irc_ns_mix_t", "t1");
    assertOneExistenceRule("IRC_NS_MIX_T", 409);
    assertFalse(hasRecord("irc_ns_mix_t"));

    // A record created as IRC_NS_MIX_R only, through the lower-case spelling.
    created("IRC_NS_MIX_R", Collections.emptyMap());
    assertOneExistenceRule("irc_ns_mix_r", 204);
    assertFalse(hasRecord("irc_ns_mix_r"));
  }

  /** The toggle row {@link NativeViewHttp#enableViews} saves for {@code db}. */
  private static com.linkedin.openhouse.tables.toggle.model.TableToggleStatus viewsToggle(
      String db) {
    return com.linkedin.openhouse.tables.toggle.model.TableToggleStatus.builder()
        .databaseId(db)
        .tableId(com.linkedin.openhouse.tables.services.ViewsFeatureGate.GATE_PROBE_TABLE_ID)
        .featureId("views")
        .toggleStatusEnum(
            com.linkedin.openhouse.housetables.client.model.ToggleStatus.StatusEnum.ACTIVE)
        .build();
  }

  /**
   * A rename into another database whose namespace exists only by its record gets the
   * cross-database refusal (406), not the missing namespace (404): the target namespace exists.
   */
  @Test
  void aRenameIntoANamespaceThatHasOnlyARecordIsTheCrossDatabaseRefusal() throws Exception {
    table("irc_ns_ren_src", "t1");
    created("irc_ns_ren_dst", Collections.emptyMap());
    assertIrcError(
        IrcWrite.rename(
            mvc,
            TableIdentifier.of("irc_ns_ren_src", "t1"),
            TableIdentifier.of("irc_ns_ren_dst", "t1")),
        406,
        "UnsupportedOperationException");
  }

  // ------------------------------------------------------------- validation

  /** Names and properties are held to the namespace service's rules; one level, as everywhere. */
  @Test
  void invalidRequestsAre400AndStoreNothing() throws Exception {
    assertEquals(
        "Namespace 'irc-ns-bad' must contain exactly one name of 1 to 128 letters, digits, or"
            + " underscores",
        assertIrcError(
                createNamespace(mvc, "irc-ns-bad", Collections.emptyMap()),
                400,
                "BadRequestException")
            .message());
    assertFalse(hasRecord("irc-ns-bad"));
    char[] longKey = new char[513];
    Arrays.fill(longKey, 'k');
    assertIrcError(
        createNamespace(mvc, "irc_ns_long_key", Collections.singletonMap(new String(longKey), "v")),
        400,
        "BadRequestException");
    assertFalse(hasRecord("irc_ns_long_key"));
    assertIrcError(
        post(mvc, "/v1/namespaces", "{\"namespace\":[\"a\",\"b\"]}"), 400, "BadRequestException");
    assertIrcError(post(mvc, "/v1/namespaces", "{\"namespace\":[]}"), 400, "BadRequestException");
    assertIrcError(post(mvc, "/v1/namespaces", "{}"), 400, "BadRequestException");
    assertIrcError(post(mvc, "/v1/namespaces", "not json"), 400, "BadRequestException");
    assertIrcError(
        IrcHttp.perform(
            mvc,
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                HttpMethod.DELETE, java.net.URI.create(IrcHttp.BASE + "/v1/namespaces/a%1Fb"))),
        400,
        "BadRequestException");
  }

  /** A key both set and removed is the namespace service's refusal, 422, and changes nothing. */
  @Test
  void aKeySetAndRemovedIs422() throws Exception {
    created("irc_ns_overlap", Collections.singletonMap("k", "v"));
    assertIrcError(
        updateProperties(mvc, "irc_ns_overlap", Collections.singletonMap("k", "w"), List.of("k")),
        422,
        "UnprocessableEntityException");
    Map<String, String> expected = new HashMap<>();
    expected.put("k", "v");
    assertEquals(expected, records.getByName("irc_ns_overlap").getProperties());
    assertEquals(
        "v",
        JsonPath.read(body(IrcHttp.get(mvc, "/v1/namespaces/irc_ns_overlap")), "$.properties.k"));
  }
}
