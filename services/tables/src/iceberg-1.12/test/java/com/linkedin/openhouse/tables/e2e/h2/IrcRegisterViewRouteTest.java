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
import java.util.Map;
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
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({IrcReadRoutesTest.StubDefaults.class, IrcViewRoutesTest.ViewAudit.class})
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class IrcRegisterViewRouteTest {

  @Autowired MockMvc mvc;
  @Autowired HouseTableRepository houseTables;
  @Autowired ToggleStatusesRepository toggles;

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
    assertEquals(created.schemas(), registered.schemas());
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
    assertEquals(replaced.schemas(), registered.schemas());
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

  /** The directory of another view of the database, of a view of another database, or none. */
  @Test
  void aFileOutsideTheDirectoryOpenHouseAllocatesToThatViewIs400() throws Exception {
    database("irc_rv_where");
    database("irc_rv_where2");
    ViewMetadata created = createIrc("irc_rv_where", "v");
    String location = created.metadataFileLocation();
    drop("irc_rv_where", "v");
    // Another name in the same database.
    String message =
        assertIrcError(register("irc_rv_where", "w", location), 400, "BadRequestException")
            .message();
    assertTrue(
        message.contains("is not a metadata file in a directory OpenHouse assigned to a view"),
        message);
    // The same name in another database.
    assertIrcError(register("irc_rv_where2", "v", location), 400, "BadRequestException");
    // A directory that is not one OpenHouse allocates: no UUID, or not under the database.
    assertIrcError(
        register("irc_rv_where", "v", directoryOf(directoryOf(location)) + "/v/x.metadata.json"),
        400,
        "BadRequestException");
    assertIrcError(
        register("irc_rv_where", "v", "/tmp/v-" + created.uuid() + "/x.metadata.json"),
        400,
        "BadRequestException");
    // Not a metadata file by name; a path that climbs out of the directory.
    assertIrcError(
        register("irc_rv_where", "v", directoryOf(location) + "/x.json"),
        400,
        "BadRequestException");
    assertIrcError(
        register(
            "irc_rv_where",
            "v",
            directoryOf(location) + "/../v-" + created.uuid() + "/" + fileName(location)),
        400,
        "BadRequestException");
    assertAbsent("irc_rv_where", "v");
    assertAbsent("irc_rv_where", "w");
    assertAbsent("irc_rv_where2", "v");
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

    Map<String, String> otherUuid = new HashMap<>(created.properties());
    otherUuid.put("openhouse.tableUUID", UUID.randomUUID().toString());
    assertRefused(
        "irc_rv_ident", directory, ViewMetadata.buildFrom(created).setProperties(otherUuid));

    Map<String, String> otherName = new HashMap<>(created.properties());
    otherName.put("openhouse.tableId", "w");
    assertRefused(
        "irc_rv_ident", directory, ViewMetadata.buildFrom(created).setProperties(otherName));

    assertRefused(
        "irc_rv_ident",
        directory,
        ViewMetadata.buildFrom(created).setLocation(directoryOf(directory) + "/elsewhere"));

    assertRefused(
        "irc_rv_ident",
        directory,
        ViewMetadata.buildFrom(created).assignUUID(UUID.randomUUID().toString()));

    String copy = directory + "/00007-" + UUID.randomUUID() + ".metadata.json";
    write(copy, new String(Files.readAllBytes(path(location)), StandardCharsets.UTF_8));
    assertIrcError(register("irc_rv_ident", "v", copy), 400, "BadRequestException");
    assertAbsent("irc_rv_ident", "v");
    // The original file still registers.
    IrcViewWrite.parsed(register("irc_rv_ident", "v", location));
  }

  private void assertRefused(String db, String directory, ViewMetadata.Builder builder)
      throws Exception {
    String file = directory + "/00005-" + UUID.randomUUID() + ".metadata.json";
    ViewMetadata metadata = builder.build();
    Map<String, String> properties = new HashMap<>(metadata.properties());
    String json = ViewMetadataParser.toJson(metadata);
    if (file.equals(properties.get("openhouse.tableLocation"))) {
      throw new AssertionError("unexpected");
    }
    // Pointing the pointer property at the file itself, so only the change under test differs.
    json = json.replace(properties.get("openhouse.tableLocation"), file);
    write(file, json);
    String message = assertIrcError(register(db, "v", file), 400, "BadRequestException").message();
    assertTrue(message.startsWith("Cannot register view " + db + ".v: "), message);
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
}
