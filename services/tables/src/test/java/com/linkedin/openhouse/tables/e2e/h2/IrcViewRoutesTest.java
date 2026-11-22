package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static com.linkedin.openhouse.tables.e2e.h2.NativeViewHttp.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.jayway.jsonpath.JsonPath;
import com.linkedin.openhouse.common.audit.AuditHandler;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateViewRequestBody;
import com.linkedin.openhouse.tables.audit.model.OperationStatus;
import com.linkedin.openhouse.tables.audit.model.ViewAuditEvent;
import com.linkedin.openhouse.tables.audit.model.ViewOperationType;
import com.linkedin.openhouse.tables.toggle.repository.ToggleStatusesRepository;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.Schema;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.IrcClientParsers;
import org.apache.iceberg.rest.requests.CreateViewRequest;
import org.apache.iceberg.rest.requests.ImmutableCreateViewRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.responses.LoadViewResponse;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.view.ImmutableSQLViewRepresentation;
import org.apache.iceberg.view.ImmutableViewVersion;
import org.apache.iceberg.view.SQLViewRepresentation;
import org.apache.iceberg.view.ViewMetadata;
import org.apache.iceberg.view.ViewMetadataParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The Iceberg REST Catalog (IRC) view routes at the HTTP boundary, on the full Spring context with
 * the real view commit engine, the same context as {@link StoredViewMetadataGoldenTest}.
 *
 * <p>Equivalence: the same view write is made through the native view API in one database and
 * through IRC in another, and the pointer row and every metadata file each leaves are compared
 * after the golden test's normalization (database names aside). Rules: a request that breaks a
 * native view rule is refused with the mapped IRC error, carrying the native message, and changes
 * nothing. Each IRC request is built as Iceberg's {@code RESTCatalog} builds it ({@link
 * IrcViewWrite}) and every response is parsed with the client's own parsers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({IrcReadRoutesTest.StubDefaults.class, IrcViewRoutesTest.ViewAudit.class})
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class IrcViewRoutesTest {

  /** Captures view audit events, which the native view handler emits. */
  @TestConfiguration
  static class ViewAudit {
    @Bean
    @SuppressWarnings("unchecked")
    AuditHandler<ViewAuditEvent> viewAuditHandler() {
      return Mockito.mock(AuditHandler.class);
    }
  }

  @Autowired MockMvc mvc;
  @Autowired HouseTableRepository houseTables;
  @Autowired ToggleStatusesRepository toggles;
  @Autowired AuditHandler<ViewAuditEvent> viewAuditHandler;

  private final List<String[]> views = new ArrayList<>();
  private final List<String> tables = new ArrayList<>();

  static final Schema SCHEMA =
      new Schema(
          Types.NestedField.optional(1, "id", Types.LongType.get()),
          Types.NestedField.optional(2, "name", Types.StringType.get()));

  static final Schema SCHEMA_EXTRA =
      new Schema(
          Types.NestedField.optional(1, "id", Types.LongType.get()),
          Types.NestedField.optional(2, "name", Types.StringType.get()),
          Types.NestedField.optional(3, "extra", Types.IntegerType.get()));

  @BeforeEach
  void resetAudit() {
    clearInvocations(viewAuditHandler);
  }

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

  /** A database with views enabled, holding table {@code t}, as the native API requires. */
  private void database(String db) throws Exception {
    enableViews(toggles, db);
    tableOnly(db);
  }

  /** A database holding table {@code t} only; views stay disabled unless enabled. */
  private void tableOnly(String db) throws Exception {
    tables.add(db);
    CommitPathHttp.post(mvc, CommitPathHttp.newTable(db, "t")).andExpect(status().isCreated());
  }

  private static String sql(String db) {
    return "SELECT id, name FROM " + db + ".t";
  }

  private void createNative(String db, String view) throws Exception {
    views.add(new String[] {db, view});
    MvcResult result =
        post(
            mvc,
            createBody(db, view, SCHEMA_ID_NAME, sql(db), Collections.singletonMap("owner", "o")));
    assertEquals(201, code(result), body(result));
  }

  private static CreateViewRequest ircCreate(String db, String view) {
    return IrcViewWrite.createRequest(
        view, SCHEMA, sql(db), Namespace.of(db), Collections.singletonMap("owner", "o"));
  }

  private LoadViewResponse createIrc(String db, String view) throws Exception {
    views.add(new String[] {db, view});
    return IrcViewWrite.parsed(IrcViewWrite.create(mvc, db, ircCreate(db, view)));
  }

  private void assertSameStored(String nativeDb, String ircDb) throws Exception {
    assertEquals(
        StoredViewMetadata.normalizedWithoutDb(houseTables, nativeDb, "v"),
        StoredViewMetadata.normalizedWithoutDb(houseTables, ircDb, "v"));
  }

  private String stored(String db, String view) throws Exception {
    return StoredViewMetadata.normalized(houseTables, db, view);
  }

  private static String nativeMessage(MvcResult result) throws Exception {
    return JsonPath.read(body(result), "$.message");
  }

  private static String fileJson(String location) throws Exception {
    return new String(
        Files.readAllBytes(
            Paths.get(
                location.startsWith("file:") ? location.substring("file:".length()) : location)));
  }

  private void expectAudited(String db, ViewOperationType type, OperationStatus status) {
    verify(viewAuditHandler, atLeastOnce())
        .audit(
            argThat(
                (ViewAuditEvent event) ->
                    event != null
                        && db.equals(event.getDatabaseName())
                        && "v".equals(event.getViewName())
                        && event.getOperationType() == type
                        && event.getOperationStatus() == status));
  }

  // ---------------------------------------------------------------- reads

  @Test
  void loadReturnsTheStoredMetadataAtTheNativePointer() throws Exception {
    database("irc_v_load");
    createNative("irc_v_load", "v");
    MvcResult result = IrcHttp.get(mvc, IrcViewWrite.view("irc_v_load", "v"));
    LoadViewResponse response = IrcViewWrite.parsed(result);
    String pointer = pointer(mvc, "irc_v_load", "v");
    assertEquals(pointer, response.metadataLocation());
    JsonNode wire = StoredMetadata.MAPPER.readTree(IrcHttp.body(result)).get("metadata");
    assertEquals(StoredMetadata.MAPPER.readTree(fileJson(pointer)), wire);
    ViewMetadata metadata = response.metadata();
    assertEquals(
        sql("irc_v_load"),
        ((SQLViewRepresentation) metadata.currentVersion().representations().get(0)).sql());
    assertEquals("spark", metadata.properties().get(SOURCE_DIALECT));
    assertEquals(Namespace.of("irc_v_load"), metadata.currentVersion().defaultNamespace());
  }

  @Test
  void headIs204ForAViewAnd404Otherwise() throws Exception {
    database("irc_v_head");
    createNative("irc_v_head", "v");
    assertEquals(
        204, IrcHttp.head(mvc, IrcViewWrite.view("irc_v_head", "v")).getResponse().getStatus());
    assertEquals(
        404,
        IrcHttp.head(mvc, IrcViewWrite.view("irc_v_head", "missing")).getResponse().getStatus());
    // A table is not a view.
    assertEquals(
        404, IrcHttp.head(mvc, IrcViewWrite.view("irc_v_head", "t")).getResponse().getStatus());
  }

  @Test
  void aMissingViewIs404NoSuchView() throws Exception {
    database("irc_v_missing");
    String nativeMsg = nativeMessage(get(mvc, "irc_v_missing", "nope"));
    assertEquals(
        nativeMsg,
        assertIrcError(
                IrcHttp.get(mvc, IrcViewWrite.view("irc_v_missing", "nope")),
                404,
                "NoSuchViewException")
            .message());
  }

  /**
   * The native API keeps tables and views apart by the House Tables row's entity type: the table
   * search reads TABLE rows and the view list VIEW rows. So the IRC lists do too.
   */
  @Test
  void listingViewsListsOnlyViewsAndListingTablesOnlyTables() throws Exception {
    database("irc_v_list");
    createNative("irc_v_list", "v");
    createNative("irc_v_list", "w");
    assertEquals(
        Arrays.asList(TableIdentifier.of("irc_v_list", "v"), TableIdentifier.of("irc_v_list", "w")),
        IrcViewWrite.list(mvc, "irc_v_list"));
    MvcResult tablesResult = IrcHttp.get(mvc, IrcWrite.tables("irc_v_list"));
    assertEquals(
        Collections.singletonList(TableIdentifier.of("irc_v_list", "t")),
        IrcClientParsers.parse(
                IrcHttp.body(tablesResult),
                org.apache.iceberg.rest.responses.ListTablesResponse.class)
            .identifiers());
  }

  @Test
  void listingViewsOfAMissingNamespaceIs404() throws Exception {
    enableViews(toggles, "irc_v_nodb");
    assertIrcError(
        IrcHttp.get(mvc, IrcViewWrite.views("irc_v_nodb")), 404, "NoSuchNamespaceException");
  }

  @Test
  void listingViewsOfADatabaseWithoutViewsIsEmpty() throws Exception {
    database("irc_v_noviews");
    assertEquals(Collections.emptyList(), IrcViewWrite.list(mvc, "irc_v_noviews"));
  }

  // ---------------------------------------------------------------- create

  @Test
  void createStoresWhatTheNativeCreateStores() throws Exception {
    database("irc_v_n_create");
    database("irc_v_i_create");
    createNative("irc_v_n_create", "v");
    LoadViewResponse response = createIrc("irc_v_i_create", "v");
    assertSameStored("irc_v_n_create", "irc_v_i_create");
    // The response is the stored view at the native pointer; the server owns UUID and location.
    assertEquals(pointer(mvc, "irc_v_i_create", "v"), response.metadataLocation());
    assertEquals(
        StoredMetadata.MAPPER.readTree(fileJson(response.metadataLocation())),
        StoredMetadata.MAPPER.readTree(ViewMetadataParser.toJson(response.metadata())));
    expectAudited("irc_v_i_create", ViewOperationType.CREATE, OperationStatus.SUCCESS);
  }

  @Test
  void createOfAnExistingViewIs409AlreadyExistsAndChangesNothing() throws Exception {
    database("irc_v_exists");
    createNative("irc_v_exists", "v");
    String before = stored("irc_v_exists", "v");
    String nativeMsg =
        nativeMessage(
            post(
                mvc,
                createBody(
                    "irc_v_exists",
                    "v",
                    SCHEMA_ID_NAME,
                    sql("irc_v_exists"),
                    Collections.emptyMap())));
    assertEquals(
        nativeMsg,
        assertIrcError(
                IrcViewWrite.create(mvc, "irc_v_exists", ircCreate("irc_v_exists", "v")),
                409,
                "AlreadyExistsException")
            .message());
    assertEquals(before, stored("irc_v_exists", "v"));
    expectAudited("irc_v_exists", ViewOperationType.CREATE, OperationStatus.FAILED);
  }

  @Test
  void createOverATableIs409AlreadyExists() throws Exception {
    database("irc_v_overt");
    String nativeMsg =
        nativeMessage(
            post(
                mvc,
                createBody(
                    "irc_v_overt",
                    "t",
                    SCHEMA_ID_NAME,
                    sql("irc_v_overt"),
                    Collections.emptyMap())));
    assertEquals(
        nativeMsg,
        assertIrcError(
                IrcViewWrite.create(mvc, "irc_v_overt", ircCreate("irc_v_overt", "t")),
                409,
                "AlreadyExistsException")
            .message());
  }

  @Test
  void createInAMissingNamespaceIs404NoSuchNamespace() throws Exception {
    enableViews(toggles, "irc_v_cnodb");
    String nativeMsg =
        nativeMessage(
            post(
                mvc,
                createBody(
                    "irc_v_cnodb",
                    "v",
                    SCHEMA_ID_NAME,
                    sql("irc_v_cnodb"),
                    Collections.emptyMap())));
    assertEquals(
        nativeMsg,
        assertIrcError(
                IrcViewWrite.create(mvc, "irc_v_cnodb", ircCreate("irc_v_cnodb", "v")),
                404,
                "NoSuchNamespaceException")
            .message());
  }

  @Test
  void createThatNamesALocationIsRefusedAndNamesTheField() throws Exception {
    database("irc_v_cloc");
    CreateViewRequest request =
        ImmutableCreateViewRequest.builder()
            .from(ircCreate("irc_v_cloc", "v"))
            .location("/tmp/elsewhere")
            .build();
    String message =
        assertIrcError(IrcViewWrite.create(mvc, "irc_v_cloc", request), 400, "BadRequestException")
            .message();
    assertTrue(message.contains("location"), message);
    assertEquals(404, code(get(mvc, "irc_v_cloc", "v")));
  }

  @Test
  void createWithAReservedPropertyGetsTheNative400() throws Exception {
    database("irc_v_cres");
    Map<String, String> reserved = Collections.singletonMap("openhouse.tableId", "x");
    String nativeMsg =
        nativeMessage(
            post(mvc, createBody("irc_v_cres", "v", SCHEMA_ID_NAME, sql("irc_v_cres"), reserved)));
    assertEquals(
        nativeMsg,
        assertIrcError(
                IrcViewWrite.create(
                    mvc,
                    "irc_v_cres",
                    IrcViewWrite.createRequest(
                        "v", SCHEMA, sql("irc_v_cres"), Namespace.of("irc_v_cres"), reserved)),
                400,
                "BadRequestException")
            .message());
    assertEquals(404, code(get(mvc, "irc_v_cres", "v")));
  }

  @Test
  void createWithAnUnsupportedDialectGetsTheNative400() throws Exception {
    database("irc_v_cdia");
    CreateUpdateViewRequestBody nativeBody =
        createBody("irc_v_cdia", "v", SCHEMA_ID_NAME, sql("irc_v_cdia"), Collections.emptyMap());
    Map<String, String> nativeProps = new HashMap<>(nativeBody.getViewProperties());
    nativeProps.put(SOURCE_DIALECT, "presto");
    String nativeMsg =
        nativeMessage(
            post(
                mvc,
                nativeBody
                    .toBuilder()
                    .representations(
                        Collections.singletonList(
                            spark(sql("irc_v_cdia")).toBuilder().dialect("presto").build()))
                    .viewProperties(nativeProps)
                    .build()));
    CreateViewRequest base = ircCreate("irc_v_cdia", "v");
    CreateViewRequest presto =
        ImmutableCreateViewRequest.builder()
            .from(base)
            .properties(Collections.emptyMap())
            .viewVersion(
                ImmutableViewVersion.builder()
                    .from(base.viewVersion())
                    .representations(
                        Collections.singletonList(
                            ImmutableSQLViewRepresentation.builder()
                                .dialect("presto")
                                .sql(sql("irc_v_cdia"))
                                .build()))
                    .build())
            .build();
    assertEquals(
        nativeMsg,
        assertIrcError(IrcViewWrite.create(mvc, "irc_v_cdia", presto), 400, "BadRequestException")
            .message());
    assertEquals(404, code(get(mvc, "irc_v_cdia", "v")));
  }

  @Test
  void createInAMultiLevelNamespaceIs400() throws Exception {
    assertIrcError(
        IrcHttp.perform(
            mvc, IrcWrite.post(IrcViewWrite.views("irc_v_a\u001fb"), ircCreate("irc_v_a", "v"))),
        400,
        "BadRequestException");
  }

  // ---------------------------------------------------------------- commit

  @Test
  void replaceStoresWhatTheNativeReplaceStores() throws Exception {
    database("irc_v_n_repl");
    database("irc_v_i_repl");
    createNative("irc_v_n_repl", "v");
    createNative("irc_v_i_repl", "v");
    String newSql = "SELECT id, name, 1 AS extra FROM ";
    assertEquals(
        200,
        code(
            put(
                mvc,
                replaceBody(
                    "irc_v_n_repl",
                    "v",
                    pointer(mvc, "irc_v_n_repl", "v"),
                    SCHEMA_ID_NAME_EXTRA,
                    Collections.singletonList(spark(newSql + "irc_v_n_repl.t")),
                    StoredViewMetadataGoldenTest.storedProperties(houseTables, "irc_v_n_repl")))));
    ViewMetadata base = IrcViewWrite.load(mvc, "irc_v_i_repl", "v");
    ViewMetadata replacement =
        IrcViewWrite.replacement(
            base, SCHEMA_EXTRA, newSql + "irc_v_i_repl.t", Collections.emptyMap());
    LoadViewResponse response =
        IrcViewWrite.parsed(
            IrcViewWrite.commit(
                mvc, "irc_v_i_repl", "v", IrcViewWrite.commitRequest(base, replacement)));
    assertSameStored("irc_v_n_repl", "irc_v_i_repl");
    assertEquals(pointer(mvc, "irc_v_i_repl", "v"), response.metadataLocation());
    assertEquals(2, response.metadata().currentVersionId());
    expectAudited("irc_v_i_repl", ViewOperationType.REPLACE, OperationStatus.SUCCESS);
  }

  @Test
  void propertySetAndRemoveStoreWhatTheNativeWritesStore() throws Exception {
    database("irc_v_n_props");
    database("irc_v_i_props");
    createNative("irc_v_n_props", "v");
    createNative("irc_v_i_props", "v");
    Map<String, String> properties =
        StoredViewMetadataGoldenTest.storedProperties(houseTables, "irc_v_n_props");
    properties.put("user.key", "v1");
    put(
        mvc,
        replaceBody(
            "irc_v_n_props",
            "v",
            pointer(mvc, "irc_v_n_props", "v"),
            SCHEMA_ID_NAME,
            Collections.singletonList(spark(sql("irc_v_n_props"))),
            properties));
    properties = StoredViewMetadataGoldenTest.storedProperties(houseTables, "irc_v_n_props");
    properties.remove("user.key");
    properties.put("user.other", "v2");
    put(
        mvc,
        replaceBody(
            "irc_v_n_props",
            "v",
            pointer(mvc, "irc_v_n_props", "v"),
            SCHEMA_ID_NAME,
            Collections.singletonList(spark(sql("irc_v_n_props"))),
            properties));

    ViewMetadata base = IrcViewWrite.load(mvc, "irc_v_i_props", "v");
    IrcViewWrite.parsed(
        IrcViewWrite.commit(
            mvc,
            "irc_v_i_props",
            "v",
            IrcViewWrite.commitRequest(
                base, IrcViewWrite.properties(base, Collections.singletonMap("user.key", "v1")))));
    base = IrcViewWrite.load(mvc, "irc_v_i_props", "v");
    IrcViewWrite.parsed(
        IrcViewWrite.commit(
            mvc,
            "irc_v_i_props",
            "v",
            IrcViewWrite.commitRequest(
                base,
                IrcViewWrite.properties(
                    base,
                    Collections.singletonMap("user.other", "v2"),
                    Collections.singleton("user.key")))));
    assertSameStored("irc_v_n_props", "irc_v_i_props");
  }

  @Test
  void aCommitThatChangesNothingWritesNothing() throws Exception {
    database("irc_v_noop");
    createNative("irc_v_noop", "v");
    String before = stored("irc_v_noop", "v");
    ViewMetadata base = IrcViewWrite.load(mvc, "irc_v_noop", "v");
    LoadViewResponse response =
        IrcViewWrite.parsed(
            IrcViewWrite.commit(
                mvc,
                "irc_v_noop",
                "v",
                IrcViewWrite.commitRequest(
                    base, IrcViewWrite.properties(base, Collections.singletonMap("owner", "o")))));
    assertEquals(base.metadataFileLocation(), response.metadataLocation());
    assertEquals(before, stored("irc_v_noop", "v"));
  }

  @Test
  void aFailedRequirementIs409CommitFailedAndChangesNothing() throws Exception {
    database("irc_v_req");
    createNative("irc_v_req", "v");
    String before = stored("irc_v_req", "v");
    ViewMetadata base = IrcViewWrite.load(mvc, "irc_v_req", "v");
    ViewMetadata updated = IrcViewWrite.properties(base, Collections.singletonMap("k", "v"));
    UpdateTableRequest request =
        IrcViewWrite.commitRequest(
            Collections.singletonList(
                new UpdateRequirement.AssertViewUUID("00000000-0000-0000-0000-000000000000")),
            updated.changes());
    assertIrcError(
        IrcViewWrite.commit(mvc, "irc_v_req", "v", request), 409, "CommitFailedException");
    assertEquals(before, stored("irc_v_req", "v"));
  }

  @Test
  void aTableRequirementOnAViewIs400() throws Exception {
    database("irc_v_treq");
    createNative("irc_v_treq", "v");
    ViewMetadata base = IrcViewWrite.load(mvc, "irc_v_treq", "v");
    UpdateTableRequest request =
        IrcViewWrite.commitRequest(
            Collections.singletonList(new UpdateRequirement.AssertCurrentSchemaID(0)),
            IrcViewWrite.properties(base, Collections.singletonMap("k", "v")).changes());
    assertIrcError(
        IrcViewWrite.commit(mvc, "irc_v_treq", "v", request), 400, "BadRequestException");
  }

  /** Two writers on one view: each property change, made on a base that moved, is kept. */
  @Test
  void twoWritersOnOneViewBothLand() throws Exception {
    database("irc_v_race");
    createNative("irc_v_race", "v");
    ViewMetadata baseA = IrcViewWrite.load(mvc, "irc_v_race", "v");
    ViewMetadata baseB = IrcViewWrite.load(mvc, "irc_v_race", "v");
    IrcViewWrite.parsed(
        IrcViewWrite.commit(
            mvc,
            "irc_v_race",
            "v",
            IrcViewWrite.commitRequest(
                baseA, IrcViewWrite.properties(baseA, Collections.singletonMap("a", "1")))));
    LoadViewResponse b =
        IrcViewWrite.parsed(
            IrcViewWrite.commit(
                mvc,
                "irc_v_race",
                "v",
                IrcViewWrite.commitRequest(
                    baseB, IrcViewWrite.properties(baseB, Collections.singletonMap("b", "2")))));
    assertEquals("1", b.metadata().properties().get("a"));
    assertEquals("2", b.metadata().properties().get("b"));
  }

  @Test
  void aCommitThatSetsTheLocationIsRefusedAndNamesTheField() throws Exception {
    assertRefusedNaming(
        "irc_v_sloc", base -> new MetadataUpdate.SetLocation("/tmp/elsewhere"), "location");
  }

  @Test
  void aCommitThatAssignsAnotherUuidIsRefusedAndNamesTheField() throws Exception {
    assertRefusedNaming(
        "irc_v_suuid",
        base -> new MetadataUpdate.AssignUUID("00000000-0000-0000-0000-000000000000"),
        "UUID");
  }

  private void assertRefusedNaming(
      String db, java.util.function.Function<ViewMetadata, MetadataUpdate> update, String field)
      throws Exception {
    database(db);
    createNative(db, "v");
    String before = stored(db, "v");
    ViewMetadata base = IrcViewWrite.load(mvc, db, "v");
    UpdateTableRequest request =
        IrcViewWrite.commitRequest(
            Collections.singletonList(new UpdateRequirement.AssertViewUUID(base.uuid())),
            Collections.singletonList(update.apply(base)));
    String message =
        assertIrcError(IrcViewWrite.commit(mvc, db, "v", request), 400, "BadRequestException")
            .message();
    assertTrue(message.contains(field), message);
    assertEquals(before, stored(db, "v"));
  }

  @Test
  void aCommitThatChangesASystemPropertyGetsTheNative400() throws Exception {
    database("irc_v_sys");
    createNative("irc_v_sys", "v");
    String before = stored("irc_v_sys", "v");
    Map<String, String> properties =
        StoredViewMetadataGoldenTest.storedProperties(houseTables, "irc_v_sys");
    properties.put("openhouse.tableId", "other");
    String nativeMsg =
        nativeMessage(
            put(
                mvc,
                replaceBody(
                    "irc_v_sys",
                    "v",
                    pointer(mvc, "irc_v_sys", "v"),
                    SCHEMA_ID_NAME,
                    Collections.singletonList(spark(sql("irc_v_sys"))),
                    properties)));
    ViewMetadata base = IrcViewWrite.load(mvc, "irc_v_sys", "v");
    assertEquals(
        nativeMsg,
        assertIrcError(
                IrcViewWrite.commit(
                    mvc,
                    "irc_v_sys",
                    "v",
                    IrcViewWrite.commitRequest(
                        base,
                        IrcViewWrite.properties(
                            base, Collections.singletonMap("openhouse.tableId", "other")))),
                400,
                "BadRequestException")
            .message());
    assertEquals(before, stored("irc_v_sys", "v"));
    expectAudited("irc_v_sys", ViewOperationType.REPLACE, OperationStatus.FAILED);
  }

  @Test
  void aCommitThatRemovesTheSourceDialectGetsTheNative400() throws Exception {
    database("irc_v_rmdia");
    createNative("irc_v_rmdia", "v");
    String before = stored("irc_v_rmdia", "v");
    Map<String, String> properties =
        StoredViewMetadataGoldenTest.storedProperties(houseTables, "irc_v_rmdia");
    properties.remove(SOURCE_DIALECT);
    String nativeMsg =
        nativeMessage(
            put(
                mvc,
                replaceBody(
                    "irc_v_rmdia",
                    "v",
                    pointer(mvc, "irc_v_rmdia", "v"),
                    SCHEMA_ID_NAME,
                    Collections.singletonList(spark(sql("irc_v_rmdia"))),
                    properties)));
    ViewMetadata base = IrcViewWrite.load(mvc, "irc_v_rmdia", "v");
    assertEquals(
        nativeMsg,
        assertIrcError(
                IrcViewWrite.commit(
                    mvc,
                    "irc_v_rmdia",
                    "v",
                    IrcViewWrite.commitRequest(
                        base,
                        IrcViewWrite.properties(
                            base, Collections.emptyMap(), Collections.singleton(SOURCE_DIALECT)))),
                400,
                "BadRequestException")
            .message());
    assertEquals(before, stored("irc_v_rmdia", "v"));
  }

  @Test
  void aCommitOnAMissingViewIs404NoSuchView() throws Exception {
    database("irc_v_cmiss");
    createNative("irc_v_cmiss", "v");
    ViewMetadata base = IrcViewWrite.load(mvc, "irc_v_cmiss", "v");
    UpdateTableRequest request =
        IrcViewWrite.commitRequest(
            base, IrcViewWrite.properties(base, Collections.singletonMap("k", "v")));
    assertIrcError(
        IrcViewWrite.commit(mvc, "irc_v_cmiss", "gone", request), 404, "NoSuchViewException");
    // A commit addressed to a table name is a missing view too.
    assertIrcError(
        IrcViewWrite.commit(mvc, "irc_v_cmiss", "t", request), 404, "NoSuchViewException");
  }

  // ---------------------------------------------------------------- drop and rename

  @Test
  void dropIsTheNativeDrop() throws Exception {
    database("irc_v_drop");
    createNative("irc_v_drop", "v");
    assertEquals(204, IrcViewWrite.drop(mvc, "irc_v_drop", "v").getResponse().getStatus());
    assertEquals(404, code(get(mvc, "irc_v_drop", "v")));
    expectAudited("irc_v_drop", ViewOperationType.DROP, OperationStatus.SUCCESS);
    String nativeMsg = nativeMessage(NativeViewHttp.delete(mvc, "irc_v_drop", "v"));
    assertEquals(
        nativeMsg,
        assertIrcError(IrcViewWrite.drop(mvc, "irc_v_drop", "v"), 404, "NoSuchViewException")
            .message());
    // A drop addressed to a table name drops nothing.
    assertIrcError(IrcViewWrite.drop(mvc, "irc_v_drop", "t"), 404, "NoSuchViewException");
    assertEquals(200, CommitPathHttp.status(CommitPathHttp.get(mvc, "irc_v_drop", "t")));
  }

  /** The native view API has no rename, so neither has IRC: 406, the spec's unsupported. */
  @Test
  void renameIsUnsupportedAsNatively() throws Exception {
    database("irc_v_ren");
    createNative("irc_v_ren", "v");
    String before = stored("irc_v_ren", "v");
    assertIrcError(
        IrcViewWrite.rename(
            mvc, TableIdentifier.of("irc_v_ren", "v"), TableIdentifier.of("irc_v_ren", "w")),
        406,
        "UnsupportedOperationException");
    assertEquals(before, stored("irc_v_ren", "v"));
    assertEquals(404, code(get(mvc, "irc_v_ren", "w")));
  }

  // ---------------------------------------------------------------- views disabled

  /**
   * With views disabled for a database, every native view route answers 404 "Views are disabled".
   * IRC answers the same 404 with the type that makes {@code RESTCatalog} raise the exception a
   * catalog without views raises: {@code NoSuchViewException} on the view's own routes (so Spark's
   * view probe falls back to tables, and a drop answers false), {@code NoSuchNamespaceException} on
   * the namespace's routes (list and create).
   */
  @Test
  void withViewsDisabledEveryRouteIsTheNative404() throws Exception {
    tableOnly("irc_v_off");
    String nativeMsg = nativeMessage(get(mvc, "irc_v_off", "v"));
    assertEquals("Views are disabled", nativeMsg);
    assertEquals(404, code(list(mvc, "irc_v_off")));
    assertEquals(
        404,
        code(
            post(
                mvc,
                createBody(
                    "irc_v_off", "v", SCHEMA_ID_NAME, sql("irc_v_off"), Collections.emptyMap()))));

    assertEquals(
        nativeMsg,
        assertIrcError(
                IrcHttp.get(mvc, IrcViewWrite.view("irc_v_off", "v")), 404, "NoSuchViewException")
            .message());
    assertEquals(
        404, IrcHttp.head(mvc, IrcViewWrite.view("irc_v_off", "v")).getResponse().getStatus());
    assertEquals(
        nativeMsg,
        assertIrcError(
                IrcHttp.get(mvc, IrcViewWrite.views("irc_v_off")), 404, "NoSuchNamespaceException")
            .message());
    assertEquals(
        nativeMsg,
        assertIrcError(
                IrcViewWrite.create(mvc, "irc_v_off", ircCreate("irc_v_off", "v")),
                404,
                "NoSuchNamespaceException")
            .message());
    assertEquals(
        nativeMsg,
        assertIrcError(IrcViewWrite.drop(mvc, "irc_v_off", "v"), 404, "NoSuchViewException")
            .message());
    UpdateTableRequest request =
        IrcViewWrite.commitRequest(
            Collections.emptyList(),
            Collections.singletonList(
                new MetadataUpdate.SetProperties(Collections.singletonMap("k", "v"))));
    assertEquals(
        nativeMsg,
        assertIrcError(
                IrcViewWrite.commit(mvc, "irc_v_off", "v", request), 404, "NoSuchViewException")
            .message());
    // Tables are untouched.
    assertEquals(200, IrcHttp.get(mvc, IrcWrite.table("irc_v_off", "t")).getResponse().getStatus());
  }

  // ---------------------------------------------------------------- tables versus views

  /**
   * Each kind of route asked for the other kind's name answers "not found" with its own kind's
   * type, which is what lets {@code RESTCatalog} and Spark's resolution (probing {@code loadView}
   * and {@code loadTable}) fall through to the right one.
   */
  @Test
  void aTableRouteAskedForAViewAndAViewRouteAskedForATable() throws Exception {
    database("irc_v_kinds");
    createNative("irc_v_kinds", "v");
    assertIrcError(
        IrcHttp.get(mvc, IrcViewWrite.view("irc_v_kinds", "t")), 404, "NoSuchViewException");
    assertIrcError(
        IrcHttp.get(mvc, IrcWrite.table("irc_v_kinds", "v")), 404, "NoSuchTableException");
    assertEquals(
        404, IrcHttp.head(mvc, IrcWrite.table("irc_v_kinds", "v")).getResponse().getStatus());
    assertIrcError(IrcWrite.drop(mvc, "irc_v_kinds", "v"), 404, "NoSuchTableException");
    assertEquals(200, code(get(mvc, "irc_v_kinds", "v")));
    // A table cannot be created, or renamed, onto a view's name.
    String before = stored("irc_v_kinds", "v");
    assertIrcError(
        IrcWrite.create(
            mvc,
            "irc_v_kinds",
            org.apache.iceberg.rest.requests.CreateTableRequest.builder()
                .withName("v")
                .withSchema(SCHEMA)
                .build()),
        409,
        "AlreadyExistsException");
    assertIrcError(
        IrcWrite.rename(
            mvc, TableIdentifier.of("irc_v_kinds", "t"), TableIdentifier.of("irc_v_kinds", "v")),
        409,
        "AlreadyExistsException");
    assertEquals(before, stored("irc_v_kinds", "v"));
    assertEquals(200, CommitPathHttp.status(CommitPathHttp.get(mvc, "irc_v_kinds", "t")));
  }

  @Test
  void viewRoutesInAMultiLevelNamespaceAreMissingViews() throws Exception {
    assertIrcError(
        IrcHttp.getEncoded(mvc, IrcViewWrite.view("irc_v_ml%1Fx", "v")),
        404,
        "NoSuchViewException");
    assertIrcError(
        IrcHttp.getEncoded(mvc, IrcViewWrite.views("irc_v_ml%1Fx")), 400, "BadRequestException");
  }
}
