package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static com.linkedin.openhouse.tables.e2e.h2.NativeViewHttp.SCHEMA_ID_NAME;
import static com.linkedin.openhouse.tables.e2e.h2.NativeViewHttp.code;
import static com.linkedin.openhouse.tables.e2e.h2.NativeViewHttp.createBody;
import static com.linkedin.openhouse.tables.e2e.h2.NativeViewHttp.enableViews;
import static com.linkedin.openhouse.tables.e2e.h2.NativeViewHttp.get;
import static com.linkedin.openhouse.tables.e2e.h2.NativeViewHttp.pointer;
import static com.linkedin.openhouse.tables.e2e.h2.NativeViewHttp.post;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.linkedin.openhouse.cluster.storage.selector.StorageSelector;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.CatalogConstants;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.tables.toggle.repository.ToggleStatusesRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.rest.requests.ImmutableRegisterViewRequest;
import org.apache.iceberg.rest.requests.RegisterViewRequest;
import org.apache.iceberg.rest.responses.LoadViewResponse;
import org.apache.iceberg.view.ViewMetadata;
import org.apache.iceberg.view.ViewMetadataParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The IRC register-view route ({@code POST /v1/namespaces/{namespace}/register-view}) at the HTTP
 * boundary, on the context of {@link IrcViewRoutesTest}. Requests are built as Iceberg 1.12's
 * {@code RESTCatalog.registerView} builds them and responses parsed with the client's parsers.
 *
 * <p>A view is registered in place: the stored row points at the metadata file named, which must be
 * one OpenHouse wrote for a view of that name in the directory it allocates to that view, and no
 * other row may use that directory. So a registration gives back a view that was dropped, exactly
 * as it was (its UUID, location, versions, history and properties); it cannot give a view another
 * view's or table's files. Every refusal stores nothing.
 *
 * <p>The cluster states the production store's location width ({@code
 * cluster.housetables.metadata-location-max-length=255}), so a location the row cannot hold is
 * refused before anything is written.
 */
@SpringBootTest(properties = "cluster.housetables.metadata-location-max-length=255")
@AutoConfigureMockMvc
@Import({IrcReadRoutesTest.StubDefaults.class, IrcViewRoutesTest.ViewAudit.class})
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class IrcRegisterViewRouteTest {

  @Autowired MockMvc mvc;
  @Autowired HouseTableRepository houseTables;
  @Autowired ToggleStatusesRepository toggles;
  @Autowired StorageSelector storageSelector;

  private final List<String[]> views = new ArrayList<>();
  private final List<String> tables = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String[] key : views) {
      NativeViewHttp.delete(mvc, key[0], key[1]);
    }
    for (String db : tables) {
      CommitPathHttp.delete(mvc, db, "t");
    }
    views.clear();
    tables.clear();
  }

  // ---------------------------------------------------------------- fixtures

  /** A database with views enabled, holding table {@code t}. */
  private void database(String db) throws Exception {
    enableViews(toggles, db);
    tableOnly(db);
  }

  private void tableOnly(String db) throws Exception {
    tables.add(db);
    CommitPathHttp.post(mvc, CommitPathHttp.newTable(db, "t")).andExpect(status().isCreated());
  }

  private static String sql(String db) {
    return "SELECT id, name FROM " + db + ".t";
  }

  /** {@code db.view} created over IRC; its metadata as created. */
  private ViewMetadata createIrc(String db, String view) throws Exception {
    views.add(new String[] {db, view});
    return IrcViewWrite.parsed(
            IrcViewWrite.create(
                mvc,
                db,
                IrcViewWrite.createRequest(
                    view,
                    IrcViewRoutesTest.SCHEMA,
                    sql(db),
                    Namespace.of(db),
                    Collections.singletonMap("owner", "o"))))
        .metadata();
  }

  private void drop(String db, String view) throws Exception {
    assertEquals(204, IrcViewWrite.drop(mvc, db, view).getResponse().getStatus());
    assertEquals(404, code(get(mvc, db, view)));
  }

  static String registerPath(String db) {
    return "/v1/namespaces/" + db + "/register-view";
  }

  /** The request {@code RESTSessionCatalog.registerView} sends. */
  static RegisterViewRequest request(String name, String metadataLocation) {
    return ImmutableRegisterViewRequest.builder()
        .name(name)
        .metadataLocation(metadataLocation)
        .build();
  }

  private MvcResult register(String db, String name, String metadataLocation) throws Exception {
    views.add(new String[] {db, name});
    return IrcHttp.perform(mvc, IrcWrite.post(registerPath(db), request(name, metadataLocation)));
  }

  private static Path path(String location) {
    return Paths.get(
        location.startsWith("file:") ? location.substring("file:".length()) : location);
  }

  /** {@code location}'s directory spelled as {@code location} spells it. */
  private static String directoryOf(String location) {
    return location.substring(0, location.lastIndexOf('/'));
  }

  /** Writes {@code json} to {@code location}, creating its directory. */
  private static void write(String location, String json) throws Exception {
    Files.createDirectories(path(location).getParent());
    Files.write(path(location), json.getBytes(StandardCharsets.UTF_8));
  }

  private String stored(String db, String view) throws Exception {
    return StoredViewMetadata.normalized(houseTables, db, view);
  }

  private void assertAbsent(String db, String view) throws Exception {
    assertEquals(404, code(get(mvc, db, view)));
    assertEquals(404, IrcHttp.head(mvc, IrcViewWrite.view(db, view)).getResponse().getStatus());
  }

  /** Schemas have no equals: the same ids, each the same schema. */
  private static void assertSameSchemas(ViewMetadata expected, ViewMetadata actual) {
    assertEquals(expected.schemasById().keySet(), actual.schemasById().keySet());
    expected
        .schemasById()
        .forEach((id, schema) -> assertTrue(schema.sameSchema(actual.schemasById().get(id))));
  }

  private static String message(MvcResult result) throws Exception {
    return JsonPath.read(IrcHttp.body(result), "$.message");
  }

  // ---------------------------------------------------------------- served

  @Test
  void aDroppedViewRegisteredFromItsMetadataFileIsTheViewItWas() throws Exception {
    database("irc_rv_back");
    ViewMetadata created = createIrc("irc_rv_back", "v");
    String location = created.metadataFileLocation();
    String before = stored("irc_rv_back", "v");
    drop("irc_rv_back", "v");

    LoadViewResponse response = IrcViewWrite.parsed(register("irc_rv_back", "v", location));

    // Kept in place: the row points at the file named, which is unchanged and is the response.
    assertEquals(location, response.metadataLocation());
    assertEquals(location, pointer(mvc, "irc_rv_back", "v"));
    assertEquals(
        StoredMetadata.MAPPER.readTree(new String(Files.readAllBytes(path(location)))),
        StoredMetadata.MAPPER.readTree(ViewMetadataParser.toJson(response.metadata())));
    ViewMetadata registered = response.metadata();
    assertEquals(created.uuid(), registered.uuid());
    assertEquals(created.location(), registered.location());
    assertSameSchemas(created, registered);
    assertTrue(created.schema().sameSchema(registered.schema()));
    assertEquals(created.versions(), registered.versions());
    assertEquals(created.currentVersion(), registered.currentVersion());
    assertEquals(created.history(), registered.history());
    assertEquals(created.properties(), registered.properties());
    // The stored view (row and files) is the one that was dropped.
    assertEquals(before, stored("irc_rv_back", "v"));
    // Load and HEAD see it, and it can be replaced like any view.
    assertEquals(location, IrcViewWrite.load(mvc, "irc_rv_back", "v").metadataFileLocation());
    assertEquals(
        204, IrcHttp.head(mvc, IrcViewWrite.view("irc_rv_back", "v")).getResponse().getStatus());
    ViewMetadata replaced =
        IrcViewWrite.parsed(
                IrcViewWrite.commit(
                    mvc,
                    "irc_rv_back",
                    "v",
                    IrcViewWrite.commitRequest(
                        registered,
                        IrcViewWrite.properties(registered, Collections.singletonMap("k", "v2")))))
            .metadata();
    assertEquals(created.uuid(), replaced.uuid());
    assertEquals("v2", replaced.properties().get("k"));
    assertEquals(directoryOf(location), directoryOf(replaced.metadataFileLocation()));
  }

  @Test
  void aViewWithSeveralVersionsComesBackWithAllOfThem() throws Exception {
    database("irc_rv_versions");
    ViewMetadata created = createIrc("irc_rv_versions", "v");
    ViewMetadata replaced =
        IrcViewWrite.parsed(
                IrcViewWrite.commit(
                    mvc,
                    "irc_rv_versions",
                    "v",
                    IrcViewWrite.commitRequest(
                        created,
                        IrcViewWrite.replacement(
                            created,
                            IrcViewRoutesTest.SCHEMA_EXTRA,
                            "SELECT id, name, 1 AS extra FROM irc_rv_versions.t",
                            Collections.singletonMap("owner", "o")))))
            .metadata();
    assertEquals(2, replaced.versions().size());
    drop("irc_rv_versions", "v");

    ViewMetadata registered =
        IrcViewWrite.parsed(register("irc_rv_versions", "v", replaced.metadataFileLocation()))
            .metadata();
    assertEquals(replaced.versions(), registered.versions());
    assertEquals(replaced.history(), registered.history());
    assertSameSchemas(replaced, registered);
    assertEquals(replaced.currentVersionId(), registered.currentVersionId());
    assertEquals(replaced.properties(), registered.properties());
  }

  // ---------------------------------------------------------------- refused: the name

  @Test
  void aNameThatIsAViewIs409AsTheCreateAnswersAndChangesNothing() throws Exception {
    database("irc_rv_isview");
    ViewMetadata created = createIrc("irc_rv_isview", "v");
    String before = stored("irc_rv_isview", "v");
    String nativeMsg =
        message(
            post(
                mvc,
                createBody(
                    "irc_rv_isview",
                    "v",
                    SCHEMA_ID_NAME,
                    sql("irc_rv_isview"),
                    Collections.emptyMap())));
    assertEquals(
        nativeMsg,
        assertIrcError(
                register("irc_rv_isview", "v", created.metadataFileLocation()),
                409,
                "AlreadyExistsException")
            .message());
    assertEquals("View already exists: irc_rv_isview.v", nativeMsg);
    assertEquals(before, stored("irc_rv_isview", "v"));
  }

  @Test
  void aNameThatIsATableIs409AsTheCreateAnswersAndChangesNothing() throws Exception {
    database("irc_rv_istable");
    ViewMetadata created = createIrc("irc_rv_istable", "v");
    drop("irc_rv_istable", "v");
    String nativeMsg =
        message(
            post(
                mvc,
                createBody(
                    "irc_rv_istable",
                    "t",
                    SCHEMA_ID_NAME,
                    sql("irc_rv_istable"),
                    Collections.emptyMap())));
    assertEquals(
        nativeMsg,
        assertIrcError(
                register("irc_rv_istable", "t", created.metadataFileLocation()),
                409,
                "AlreadyExistsException")
            .message());
    assertEquals("Name already exists as a table: irc_rv_istable.t", nativeMsg);
    assertEquals(
        200, IrcHttp.get(mvc, IrcWrite.table("irc_rv_istable", "t")).getResponse().getStatus());
    assertAbsent("irc_rv_istable", "v");
  }

  @Test
  void aMissingNamespaceIs404NoSuchNamespace() throws Exception {
    database("irc_rv_hasdb");
    ViewMetadata created = createIrc("irc_rv_hasdb", "v");
    drop("irc_rv_hasdb", "v");
    enableViews(toggles, "irc_rv_nodb");
    assertEquals(
        "Database not found: irc_rv_nodb",
        assertIrcError(
                register("irc_rv_nodb", "v", created.metadataFileLocation()),
                404,
                "NoSuchNamespaceException")
            .message());
    assertAbsent("irc_rv_hasdb", "v");
  }

  /** As the create route: the native 404 "Views are disabled", typed as no such namespace. */
  @Test
  void withViewsDisabledItIsTheCreatesAnswer() throws Exception {
    database("irc_rv_hasviews");
    ViewMetadata created = createIrc("irc_rv_hasviews", "v");
    drop("irc_rv_hasviews", "v");
    tableOnly("irc_rv_off");
    assertEquals(
        "Views are disabled",
        assertIrcError(
                register("irc_rv_off", "v", created.metadataFileLocation()),
                404,
                "NoSuchNamespaceException")
            .message());
  }

  @Test
  void anInvalidNameIs400() throws Exception {
    database("irc_rv_badname");
    assertIrcError(
        register("irc_rv_badname", "not-an-id", "/tmp/x.metadata.json"),
        400,
        "BadRequestException");
  }

  // ---------------------------------------------------------------- refused: the file

  @Test
  void aFileThatIsNotThereIs400AndChangesNothing() throws Exception {
    database("irc_rv_nofile");
    ViewMetadata created = createIrc("irc_rv_nofile", "v");
    drop("irc_rv_nofile", "v");
    String missing =
        directoryOf(created.metadataFileLocation())
            + "/00009-"
            + UUID.randomUUID()
            + ".metadata.json";
    String message =
        assertIrcError(register("irc_rv_nofile", "v", missing), 400, "BadRequestException")
            .message();
    assertTrue(message.startsWith("Cannot register view irc_rv_nofile.v: "), message);
    assertTrue(message.contains("there is no file at " + missing), message);
    assertAbsent("irc_rv_nofile", "v");
  }

  @Test
  void aFileThatIsNotViewMetadataIs400() throws Exception {
    database("irc_rv_notview");
    ViewMetadata created = createIrc("irc_rv_notview", "v");
    drop("irc_rv_notview", "v");
    String directory = directoryOf(created.metadataFileLocation());
    String table = IrcWrite.load(mvc, "irc_rv_notview", "t").metadataFileLocation();
    String copied = directory + "/00002-" + UUID.randomUUID() + ".metadata.json";
    write(copied, new String(Files.readAllBytes(path(table)), StandardCharsets.UTF_8));
    String message =
        assertIrcError(register("irc_rv_notview", "v", copied), 400, "BadRequestException")
            .message();
    assertTrue(message.contains("is not view metadata"), message);
    String garbage = directory + "/00003-" + UUID.randomUUID() + ".metadata.json";
    write(garbage, "{\"not\": \"metadata\"}");
    assertIrcError(register("irc_rv_notview", "v", garbage), 400, "BadRequestException");
    assertAbsent("irc_rv_notview", "v");
  }

  /**
   * The directory rule, for each way a path can miss the directory the server allocates to that
   * view: another view's directory, the same name in another database, no UUID, not under the
   * database, not a metadata file by name, a path that climbs out, and another scheme or authority
   * than the storage's. Each is refused by that rule (its message), before anything is read.
   */
  @Test
  void aFileOutsideTheDirectoryOpenHouseAllocatesToThatViewIs400() throws Exception {
    database("irc_rv_where");
    database("irc_rv_where2");
    ViewMetadata created = createIrc("irc_rv_where", "v");
    String location = created.metadataFileLocation();
    String path = path(location).toString();
    drop("irc_rv_where", "v");
    assertDirectoryRule("irc_rv_where", "w", location);
    assertDirectoryRule("irc_rv_where2", "v", location);
    assertDirectoryRule(
        "irc_rv_where", "v", directoryOf(directoryOf(location)) + "/v/x.metadata.json");
    assertDirectoryRule("irc_rv_where", "v", "/tmp/v-" + created.uuid() + "/x.metadata.json");
    assertDirectoryRule("irc_rv_where", "v", directoryOf(location) + "/x.json");
    assertDirectoryRule(
        "irc_rv_where",
        "v",
        directoryOf(location) + "/../v-" + created.uuid() + "/" + fileName(location));
    assertDirectoryRule("irc_rv_where", "v", "hdfs://other:8020" + path);
    assertDirectoryRule("irc_rv_where", "v", "s3://bucket" + path);
    assertDirectoryRule("irc_rv_where", "v", "file://otherhost" + path);
    assertAbsent("irc_rv_where", "v");
    assertAbsent("irc_rv_where", "w");
    assertAbsent("irc_rv_where2", "v");
  }

  private void assertDirectoryRule(String db, String view, String location) throws Exception {
    assertEquals(
        "Cannot register view "
            + db
            + "."
            + view
            + ": "
            + location
            + " is not a metadata file in a directory OpenHouse assigned to a view of that name in"
            + " that database: OpenHouse assigns the location of a view",
        assertIrcError(register(db, view, location), 400, "BadRequestException").message());
  }

  /**
   * The registered file spelled otherwise than the engine stores it, with or without the storage's
   * endpoint: the same file, refused with the spelling to use, which is the load route's (F3).
   */
  @Test
  void theSameFileSpelledOtherwiseIs400NamingTheSpellingToUse() throws Exception {
    database("irc_rv_spell");
    ViewMetadata created = createIrc("irc_rv_spell", "v");
    String location = created.metadataFileLocation();
    drop("irc_rv_spell", "v");
    String endpoint = storageSelector.selectStorage("irc_rv_spell", "v").getClient().getEndpoint();
    String respelled =
        location.startsWith(endpoint) ? location.substring(endpoint.length()) : endpoint + location;
    assertEquals(
        "Cannot register view irc_rv_spell.v: "
            + respelled
            + " is not spelled as OpenHouse stores it: name the file as the view's load returned"
            + " it, "
            + location,
        assertIrcError(register("irc_rv_spell", "v", respelled), 400, "BadRequestException")
            .message());
    assertAbsent("irc_rv_spell", "v");
    assertEquals(
        location, IrcViewWrite.parsed(register("irc_rv_spell", "v", location)).metadataLocation());
  }

  private static String fileName(String location) {
    return location.substring(location.lastIndexOf('/') + 1);
  }

  /**
   * Metadata in the right directory that is not what OpenHouse wrote for this view there: its
   * identity properties, its location or its UUID name another view, or the file was copied to
   * another name (its own pointer property names the original).
   */
  @Test
  void metadataOpenHouseDidNotWriteForThatViewThereIs400() throws Exception {
    database("irc_rv_ident");
    ViewMetadata created = createIrc("irc_rv_ident", "v");
    String location = created.metadataFileLocation();
    String directory = directoryOf(location);
    drop("irc_rv_ident", "v");

    assertRefused(
        location,
        json -> properties(json).put("openhouse.tableUUID", UUID.randomUUID().toString()),
        "its OpenHouse properties name another view");
    assertRefused(
        location,
        json -> properties(json).put("openhouse.tableId", "w"),
        "its OpenHouse properties name another view");
    assertRefused(
        location,
        json -> properties(json).put("openhouse.databaseId", "irc_rv_other"),
        "its OpenHouse properties name another view");
    assertRefused(
        location,
        json -> json.put("location", directoryOf(directory) + "/elsewhere"),
        "its location is not the directory it lies in");
    assertRefused(
        location,
        json -> json.put("view-uuid", UUID.randomUUID().toString()),
        "its UUID is not the one its directory is named for");
    assertRefused(
        location,
        json -> properties(json).remove("openhouse.view.sourceDialect"),
        "it records no source dialect");
    assertRefused(
        location,
        json -> properties(json).remove("openhouse.tableCreator"),
        "it records no creator");
    assertRefused(
        location,
        json -> properties(json).put("openhouse.creationTime", "yesterday"),
        "its creation time is not a number");
    // The view's load reads it as a number: refused before the row is written, not after (F1).
    assertRefused(
        location,
        json -> properties(json).put("openhouse.lastModifiedTime", "today"),
        "its last modified time is not a number");
    assertRefused(
        location,
        json -> properties(json).put("OPENHOUSE.TABLEID", "v"),
        "it holds the OpenHouse property OPENHOUSE.TABLEID twice, in different cases");

    // A copy under another name: its pointer property names the original.
    String copy = directory + "/00007-" + UUID.randomUUID() + ".metadata.json";
    write(copy, new String(Files.readAllBytes(path(location)), StandardCharsets.UTF_8));
    String message =
        assertIrcError(register("irc_rv_ident", "v", copy), 400, "BadRequestException").message();
    assertTrue(
        message.endsWith("its OpenHouse properties name another file as the view's metadata"),
        message);
    assertAbsent("irc_rv_ident", "v");
    // The control: the same copy pointing at itself, with nothing else changed, registers.
    String control = edited(location, json -> {});
    assertEquals(
        control, IrcViewWrite.parsed(register("irc_rv_ident", "v", control)).metadataLocation());
  }

  private static com.fasterxml.jackson.databind.node.ObjectNode properties(
      com.fasterxml.jackson.databind.node.ObjectNode json) {
    return (com.fasterxml.jackson.databind.node.ObjectNode) json.get("properties");
  }

  /**
   * A copy of the metadata at {@code location}, next to it, edited by {@code edit}, and with its
   * pointer property naming the copy, so that only the edit differs from what OpenHouse writes.
   */
  private static String edited(
      String location,
      java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> edit)
      throws Exception {
    String file = directoryOf(location) + "/00005-" + UUID.randomUUID() + ".metadata.json";
    com.fasterxml.jackson.databind.node.ObjectNode json =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            StoredMetadata.MAPPER.readTree(Files.readAllBytes(path(location)));
    properties(json).put("openhouse.tableLocation", file);
    edit.accept(json);
    write(file, StoredMetadata.MAPPER.writeValueAsString(json));
    return file;
  }

  private void assertRefused(
      String location,
      java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> edit,
      String reason)
      throws Exception {
    String file = edited(location, edit);
    String message =
        assertIrcError(register("irc_rv_ident", "v", file), 400, "BadRequestException").message();
    assertEquals(
        "Cannot register view irc_rv_ident.v: the metadata at "
            + file
            + " is not what OpenHouse wrote for this view: "
            + reason,
        message);
    assertAbsent("irc_rv_ident", "v");
  }

  /**
   * A directory another House Tables row uses is not free, as for the table register: here a view
   * row that points into the dropped view's directory (written to House Tables directly: no route
   * makes one).
   */
  @Test
  void aDirectoryAnotherRowUsesIs400() throws Exception {
    database("irc_rv_inuse");
    ViewMetadata created = createIrc("irc_rv_inuse", "v");
    String location = created.metadataFileLocation();
    drop("irc_rv_inuse", "v");
    views.add(new String[] {"irc_rv_inuse", "w"});
    houseTables.saveView(
        HouseTable.builder()
            .databaseId("irc_rv_inuse")
            .tableId("w")
            .tableUUID(UUID.randomUUID().toString())
            .tableVersion(CatalogConstants.INITIAL_VERSION)
            .tableLocation(location)
            .tableCreator("someone")
            .creationTime(1L)
            .storageType(houseTables.findAllByDatabaseId("irc_rv_inuse").get(0).getStorageType())
            .build());
    try {
      String message =
          assertIrcError(register("irc_rv_inuse", "v", location), 400, "BadRequestException")
              .message();
      assertTrue(message.contains("that directory belongs to irc_rv_inuse.w"), message);
      assertAbsent("irc_rv_inuse", "v");
    } finally {
      houseTables.deleteViewById(
          HouseTablePrimaryKey.builder().databaseId("irc_rv_inuse").tableId("w").build());
    }
  }

  // ---------------------------------------------------------------- forged files

  /**
   * A copy of the view metadata at {@code template}, made into what the engine would have written
   * for {@code db.view} with {@code uuid} in the directory the server allocates to it, then edited
   * by {@code edit}. Returns the copy's location, spelled as the engine spells it.
   */
  private String forged(
      String template,
      String db,
      String view,
      UUID uuid,
      java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> edit)
      throws Exception {
    String directory =
        storageSelector
            .selectStorage(db, view)
            .allocateTableLocation(db, view, uuid.toString(), "someone", new HashMap<>());
    String file = directory + "/00001-" + UUID.randomUUID() + ".metadata.json";
    com.fasterxml.jackson.databind.node.ObjectNode json =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            StoredMetadata.MAPPER.readTree(Files.readAllBytes(path(template)));
    json.put("location", directory);
    json.put("view-uuid", uuid.toString());
    properties(json).put("openhouse.tableUUID", uuid.toString());
    properties(json).put("openhouse.databaseId", db);
    properties(json).put("openhouse.tableId", view);
    properties(json).put("openhouse.tableLocation", file);
    edit.accept(json);
    write(file, StoredMetadata.MAPPER.writeValueAsString(json));
    return file;
  }

  /** A view's metadata to forge from: {@code db.template}, created and dropped. */
  private String template(String db) throws Exception {
    String location = createIrc(db, "template").metadataFileLocation();
    drop(db, "template");
    return location;
  }

  // ---------------------------------------------------------------- the directory in use (U1)

  /**
   * A table renamed away keeps the directory of its first name: a view file of that name in that
   * directory is the table's directory, refused by the live table rows' check (location and UUID).
   */
  @Test
  void aDirectoryARenamedAwayTableKeepsIs400() throws Exception {
    database("irc_rv_renamed");
    String template = template("irc_rv_renamed");
    CommitPathHttp.post(mvc, CommitPathHttp.newTable("irc_rv_renamed", "a"))
        .andExpect(status().isCreated());
    try {
      assertEquals(
          204,
          IrcWrite.rename(
                  mvc,
                  org.apache.iceberg.catalog.TableIdentifier.of("irc_rv_renamed", "a"),
                  org.apache.iceberg.catalog.TableIdentifier.of("irc_rv_renamed", "b"))
              .getResponse()
              .getStatus());
      HouseTable b = row("irc_rv_renamed", "b");
      String file =
          forged(template, "irc_rv_renamed", "a", UUID.fromString(b.getTableUUID()), json -> {});
      assertTrue(file.contains("/a-" + b.getTableUUID() + "/"), file);
      assertInUse("irc_rv_renamed", "a", file, "irc_rv_renamed.b");
    } finally {
      CommitPathHttp.delete(mvc, "irc_rv_renamed", "b");
    }
  }

  /** A live table's UUID alone, in another name's directory, is refused by the same check. */
  @Test
  void aDirectoryNamedForALiveTablesUuidIs400() throws Exception {
    database("irc_rv_uuid");
    String template = template("irc_rv_uuid");
    HouseTable t = row("irc_rv_uuid", "t");
    String file = forged(template, "irc_rv_uuid", "w", UUID.fromString(t.getTableUUID()), j -> {});
    assertInUse("irc_rv_uuid", "w", file, "irc_rv_uuid.t");
  }

  /** A soft-deleted table's directory can be restored into: refused by the soft-deleted check. */
  @Test
  void aDirectoryOfASoftDeletedTableIs400() throws Exception {
    database("irc_rv_soft");
    String template = template("irc_rv_soft");
    CommitPathHttp.post(mvc, CommitPathHttp.newTable("irc_rv_soft", "s"))
        .andExpect(status().isCreated());
    HouseTable s = row("irc_rv_soft", "s");
    houseTables.deleteById(
        HouseTablePrimaryKey.builder().databaseId("irc_rv_soft").tableId("s").build(), false);
    try {
      String file =
          forged(template, "irc_rv_soft", "s", UUID.fromString(s.getTableUUID()), j -> {});
      assertInUse("irc_rv_soft", "s", file, "the soft-deleted table irc_rv_soft.s");
    } finally {
      HouseTablesH2Repository.softDeletedTables.clear();
    }
  }

  private HouseTable row(String db, String table) {
    return houseTables
        .findEntityById(HouseTablePrimaryKey.builder().databaseId(db).tableId(table).build())
        .orElseThrow(() -> new AssertionError("no row " + db + "." + table));
  }

  private void assertInUse(String db, String view, String file, String owner) throws Exception {
    assertEquals(
        "Cannot register view " + db + "." + view + ": that directory belongs to " + owner,
        assertIrcError(register(db, view, file), 400, "BadRequestException").message());
    assertAbsent(db, view);
  }

  // ---------------------------------------------------------------- older files (decision B)

  /** An older file of a dropped view registers too, and the view is that version. */
  @Test
  void anOlderFileOfADroppedViewRegistersAsThatVersion() throws Exception {
    database("irc_rv_older");
    ViewMetadata first = createIrc("irc_rv_older", "v");
    ViewMetadata second =
        IrcViewWrite.parsed(
                IrcViewWrite.commit(
                    mvc,
                    "irc_rv_older",
                    "v",
                    IrcViewWrite.commitRequest(
                        first,
                        IrcViewWrite.replacement(
                            first,
                            IrcViewRoutesTest.SCHEMA_EXTRA,
                            "SELECT id, name, 1 AS extra FROM irc_rv_older.t",
                            Collections.singletonMap("owner", "o")))))
            .metadata();
    assertEquals(2, second.currentVersionId());
    drop("irc_rv_older", "v");

    register("irc_rv_older", "v", first.metadataFileLocation());
    ViewMetadata loaded = IrcViewWrite.load(mvc, "irc_rv_older", "v");
    assertEquals(first.metadataFileLocation(), loaded.metadataFileLocation());
    assertEquals(1, loaded.currentVersionId());
    assertEquals(1, loaded.versions().size());
    assertEquals(first.currentVersion(), loaded.currentVersion());
  }

  // ---------------------------------------------------------------- drop (U6)

  /**
   * A registered view drops like any view: the row goes, the files stay, and it registers again.
   */
  @Test
  void aRegisteredViewDropsLikeAnyView() throws Exception {
    database("irc_rv_redrop");
    String location = createIrc("irc_rv_redrop", "v").metadataFileLocation();
    drop("irc_rv_redrop", "v");
    IrcViewWrite.parsed(register("irc_rv_redrop", "v", location));
    drop("irc_rv_redrop", "v");
    assertAbsent("irc_rv_redrop", "v");
    assertTrue(Files.exists(path(location)));
    IrcViewWrite.parsed(register("irc_rv_redrop", "v", location));
  }

  // ---------------------------------------------------------------- what a create accepts (C)

  private String createRefusal(String db, String file) throws Exception {
    String message = assertIrcError(register(db, "v", file), 400, "BadRequestException").message();
    assertAbsent(db, "v");
    return message;
  }

  private static com.fasterxml.jackson.databind.node.ObjectNode currentVersion(
      com.fasterxml.jackson.databind.node.ObjectNode json) {
    int id = json.get("current-version-id").asInt();
    for (com.fasterxml.jackson.databind.JsonNode version : json.get("versions")) {
      if (version.get("version-id").asInt() == id) {
        return (com.fasterxml.jackson.databind.node.ObjectNode) version;
      }
    }
    throw new AssertionError("no current version");
  }

  private static com.fasterxml.jackson.databind.node.ObjectNode representation(
      com.fasterxml.jackson.databind.node.ObjectNode json) {
    return (com.fasterxml.jackson.databind.node.ObjectNode)
        currentVersion(json).get("representations").get(0);
  }

  /** A dialect the deployment does not accept: the native create's 400, in the route's words. */
  @Test
  void aDialectACreateRefusesIs400() throws Exception {
    database("irc_rv_dialect");
    String template = template("irc_rv_dialect");
    String file =
        forged(
            template,
            "irc_rv_dialect",
            "v",
            UUID.randomUUID(),
            json -> {
              representation(json).put("dialect", "trino");
              properties(json).put("openhouse.view.sourceDialect", "trino");
            });
    String message = createRefusal("irc_rv_dialect", file);
    assertTrue(
        message.startsWith(
            "Cannot register view irc_rv_dialect.v: its current version is not one a create"
                + " accepts: "),
        message);
    assertTrue(message.contains("dialect"), message);
  }

  /** Two queries for one dialect, in different cases (Iceberg's reader takes the file). */
  @Test
  void duplicateDialectsAre400() throws Exception {
    database("irc_rv_dupdia");
    String template = template("irc_rv_dupdia");
    String file =
        forged(
            template,
            "irc_rv_dupdia",
            "v",
            UUID.randomUUID(),
            json -> {
              com.fasterxml.jackson.databind.node.ObjectNode copy = representation(json).deepCopy();
              copy.put("dialect", "SPARK");
              ((com.fasterxml.jackson.databind.node.ArrayNode)
                      currentVersion(json).get("representations"))
                  .add(copy);
            });
    String message = createRefusal("irc_rv_dupdia", file);
    assertTrue(message.contains("its current version is not one a create accepts"), message);
    assertTrue(message.contains("dialects must be unique"), message);
  }

  /** A default namespace a create refuses. */
  @Test
  void aDefaultNamespaceACreateRefusesIs400() throws Exception {
    database("irc_rv_defns");
    String template = template("irc_rv_defns");
    String file =
        forged(
            template,
            "irc_rv_defns",
            "v",
            UUID.randomUUID(),
            json ->
                ((com.fasterxml.jackson.databind.node.ArrayNode)
                        currentVersion(json).putArray("default-namespace"))
                    .add("not-an-id"));
    String message = createRefusal("irc_rv_defns", file);
    assertTrue(message.contains("its current version is not one a create accepts"), message);
    assertTrue(message.contains("defaultNamespace[0]"), message);
  }

  /** A schema a create refuses (over the native size limit). */
  @Test
  void aSchemaACreateRefusesIs400() throws Exception {
    database("irc_rv_schema");
    String template = template("irc_rv_schema");
    StringBuilder name = new StringBuilder();
    for (int i = 0; i < 250; i++) {
      name.append('c');
    }
    String file =
        forged(
            template,
            "irc_rv_schema",
            "v",
            UUID.randomUUID(),
            json -> {
              com.fasterxml.jackson.databind.node.ArrayNode fields =
                  (com.fasterxml.jackson.databind.node.ArrayNode)
                      json.get("schemas").get(0).get("fields");
              for (int id = 3; id < 3000; id++) {
                fields
                    .addObject()
                    .put("id", id)
                    .put("name", name.toString() + id)
                    .put("required", false)
                    .put("type", "string");
              }
            });
    String message = createRefusal("irc_rv_schema", file);
    assertTrue(message.contains("its current version is not one a create accepts"), message);
    assertTrue(message.contains("schema : exceeds maximum UTF-8 size"), message);
  }

  /**
   * A location longer than the House Tables row holds: the engine's own refusal of such a create,
   * before the row is written (F2). The name is the longest a create may carry; such a view could
   * not have been created, so its file is forged.
   */
  @Test
  void aLocationLongerThanTheRowHoldsIsTheEnginesRefusal() throws Exception {
    String db = "irc_rv_long_";
    while (db.length() < 64) {
      db += "d";
    }
    final String DB = db;
    database(DB);
    String template = template(DB);
    StringBuilder name = new StringBuilder("v");
    while (name.length() < 128) {
      name.append('x');
    }
    String view = name.toString();
    String file = forged(template, DB, view, UUID.randomUUID(), json -> {});
    assertTrue(file.length() > 255, file);
    String message = assertIrcError(register(DB, view, file), 400, "BadRequestException").message();
    assertEquals(
        "View "
            + DB
            + "."
            + view
            + " cannot be written: its metadata location would be "
            + file.length()
            + " characters long and House Tables holds at most 255"
            + " (user_table_row.metadata_location). The location is made of the storage root,"
            + " the database name, the name and UUID it was created with, and the metadata"
            + " file's name.",
        message);
    assertAbsent(DB, view);
  }
}
