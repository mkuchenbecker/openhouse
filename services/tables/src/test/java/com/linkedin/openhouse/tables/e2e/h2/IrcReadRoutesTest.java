package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.body;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.createDummyDataFile;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.getPartitionSpec;
import static com.linkedin.openhouse.tables.model.IcebergSnapshotsModelTestUtilities.preparePutSnapshotsWithAppendRequest;
import static com.linkedin.openhouse.tables.model.TableModelConstants.buildGetTableResponseBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.TextNode;
import com.jayway.jsonpath.JsonPath;
import com.linkedin.openhouse.common.audit.AuditHandler;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.api.spec.v0.response.GetTableResponseBody;
import com.linkedin.openhouse.tables.audit.model.OperationStatus;
import com.linkedin.openhouse.tables.audit.model.OperationType;
import com.linkedin.openhouse.tables.audit.model.TableAuditEvent;
import com.linkedin.openhouse.tables.config.TablesMvcConstants;
import com.linkedin.openhouse.tables.readbridge.ClientCapabilityGate;
import com.linkedin.openhouse.tables.readbridge.ColumnDefaultsSource;
import com.linkedin.openhouse.tables.readbridge.ReadBridgeConfigResolver;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.hadoop.HadoopFileIO;
import org.apache.iceberg.rest.IrcClientParsers;
import org.apache.iceberg.rest.responses.ConfigResponse;
import org.apache.iceberg.rest.responses.GetNamespaceResponse;
import org.apache.iceberg.rest.responses.ListNamespacesResponse;
import org.apache.iceberg.rest.responses.ListTablesResponse;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Wire shape of the Iceberg REST Catalog (IRC) read routes at the HTTP boundary, on the full Spring
 * context with the real internal catalog. Tables are created and written through the native API
 * only; every IRC response is parsed with the object mapper Iceberg's own {@code RESTCatalog} uses
 * and checked for the spec's required fields, and every answer is compared with what the native API
 * reports for the same table or database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class IrcReadRoutesTest {

  private static final String CAPABILITIES = TablesMvcConstants.HTTP_HEADER_CLIENT_CAPABILITIES;
  private static final String DEFAULTS_DB = "irc_defaults";

  /** Column defaults for tables in {@link #DEFAULTS_DB} only, as the Spark itests stub them. */
  @TestConfiguration
  static class StubDefaults {
    @Bean
    ColumnDefaultsSource ircStubColumnDefaults() {
      return tableDto ->
          DEFAULTS_DB.equals(tableDto.getDatabaseId())
              ? Collections.singletonMap(2, TextNode.valueOf("US"))
              : Collections.emptyMap();
    }
  }

  @Autowired MockMvc mvc;
  @Autowired Catalog catalog;
  @Autowired AuditHandler<TableAuditEvent> tableAuditHandler;

  private final List<String[]> created = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String[] key : created) {
      mvc.perform(
          MockMvcRequestBuilders.delete(tableUrl(key[0], key[1]))
              .header(CAPABILITIES, ClientCapabilityGate.COLUMN_DEFAULTS));
    }
    created.clear();
  }

  private void create(String db, String table) throws Exception {
    created.add(new String[] {db, table});
    post(mvc, newTable(db, table)).andExpect(status().isCreated());
  }

  /** Appends one data file through the native snapshots PUT. */
  private void append(String db, String table, String file) throws Exception {
    MvcResult current = get(mvc, db, table).andReturn();
    GetTableResponseBody body = buildGetTableResponseBody(current);
    putSnapshots(
            mvc,
            db,
            table,
            preparePutSnapshotsWithAppendRequest(
                current,
                body,
                body.getTableLocation(),
                catalog,
                Collections.singletonList(
                    createDummyDataFile("/tmp/" + file + ".orc", getPartitionSpec(body)))))
        .andExpect(status().isOk());
  }

  private static TableMetadata stored(String metadataLocation) {
    return TableMetadataParser.read(new HadoopFileIO(new Configuration()), metadataLocation);
  }

  // ---------------------------------------------------------------- config

  @Test
  void configHasEmptyDefaultsAndOverrides() throws Exception {
    MvcResult result = IrcHttp.get(mvc, "/v1/config");
    assertEquals(200, result.getResponse().getStatus(), body(result));
    String json = body(result);
    assertEquals(Collections.emptyMap(), JsonPath.read(json, "$.defaults"));
    assertEquals(Collections.emptyMap(), JsonPath.read(json, "$.overrides"));
    ConfigResponse config = IrcClientParsers.parse(json, ConfigResponse.class);
    assertTrue(config.defaults().isEmpty());
    assertTrue(config.overrides().isEmpty());
  }

  @Test
  void configIgnoresAWarehouse() throws Exception {
    MvcResult result =
        IrcHttp.perform(
            mvc, IrcHttp.request(HttpMethod.GET, "/v1/config").param("warehouse", "anything"));
    assertEquals(200, result.getResponse().getStatus(), body(result));
    assertTrue(IrcClientParsers.parse(body(result), ConfigResponse.class).defaults().isEmpty());
  }

  // ---------------------------------------------------------------- namespaces

  @Test
  void listNamespacesIsTheNativeDatabaseList() throws Exception {
    create("irc_ns_a", "t1");
    create("irc_ns_b", "t1");
    create("irc_ns_b", "t2");

    MvcResult result = IrcHttp.get(mvc, "/v1/namespaces");
    assertEquals(200, result.getResponse().getStatus(), body(result));
    List<List<String>> raw = JsonPath.read(body(result), "$.namespaces");
    ListNamespacesResponse parsed =
        IrcClientParsers.parse(body(result), ListNamespacesResponse.class);
    assertEquals(raw.size(), parsed.namespaces().size());
    assertTrue(parsed.namespaces().contains(Namespace.of("irc_ns_a")), parsed.toString());
    assertTrue(parsed.namespaces().contains(Namespace.of("irc_ns_b")), parsed.toString());
    assertEquals(
        parsed.namespaces().size(),
        new HashSet<>(parsed.namespaces()).size(),
        "each database once: " + parsed);

    List<String> nativeDatabases =
        JsonPath.read(
            body(mvc.perform(MockMvcRequestBuilders.get("/v1/databases")).andReturn()),
            "$.results[*].databaseId");
    assertEquals(
        new HashSet<>(nativeDatabases),
        parsed.namespaces().stream().map(Namespace::toString).collect(Collectors.toSet()));
  }

  @Test
  void listNamespacesUnderADatabaseIsEmptyAndUnderAMissingOneIs404() throws Exception {
    create("irc_ns_parent", "t1");

    MvcResult result =
        IrcHttp.perform(
            mvc,
            IrcHttp.request(HttpMethod.GET, "/v1/namespaces").param("parent", "irc_ns_parent"));
    assertEquals(200, result.getResponse().getStatus(), body(result));
    assertTrue(
        IrcClientParsers.parse(body(result), ListNamespacesResponse.class).namespaces().isEmpty());

    assertIrcError(
        IrcHttp.perform(
            mvc, IrcHttp.request(HttpMethod.GET, "/v1/namespaces").param("parent", "irc_ns_never")),
        404,
        "NoSuchNamespaceException");
  }

  @Test
  void anEmptyParentListsTopLevelNamespaces() throws Exception {
    create("irc_ns_emptyparent", "t1");
    MvcResult result =
        IrcHttp.perform(mvc, IrcHttp.request(HttpMethod.GET, "/v1/namespaces").param("parent", ""));
    assertEquals(200, result.getResponse().getStatus(), body(result));
    assertTrue(
        IrcClientParsers.parse(body(result), ListNamespacesResponse.class)
            .namespaces()
            .contains(Namespace.of("irc_ns_emptyparent")));
  }

  @Test
  void aDatabaseWithATableExistsAsANamespace() throws Exception {
    create("irc_ns_get", "t1");

    MvcResult result = IrcHttp.get(mvc, "/v1/namespaces/irc_ns_get");
    assertEquals(200, result.getResponse().getStatus(), body(result));
    assertEquals(
        Collections.singletonList("irc_ns_get"), JsonPath.read(body(result), "$.namespace"));
    GetNamespaceResponse parsed = IrcClientParsers.parse(body(result), GetNamespaceResponse.class);
    assertEquals(Namespace.of("irc_ns_get"), parsed.namespace());
    assertEquals(Collections.emptyMap(), parsed.properties());

    MvcResult head = IrcHttp.head(mvc, "/v1/namespaces/irc_ns_get");
    assertEquals(204, head.getResponse().getStatus());
    assertEquals("", body(head));
  }

  /**
   * The native API has no empty databases: a database is listed only while it holds a table. So a
   * database whose last table was dropped is not a namespace any more.
   */
  @Test
  void aDatabaseWithoutTablesIsNotANamespace() throws Exception {
    assertIrcError(
        IrcHttp.get(mvc, "/v1/namespaces/irc_ns_never"), 404, "NoSuchNamespaceException");
    assertEquals(404, IrcHttp.head(mvc, "/v1/namespaces/irc_ns_never").getResponse().getStatus());

    create("irc_ns_dropped", "t1");
    assertEquals(204, IrcHttp.head(mvc, "/v1/namespaces/irc_ns_dropped").getResponse().getStatus());
    delete(mvc, "irc_ns_dropped", "t1").andExpect(status().isNoContent());
    created.clear();
    assertIrcError(
        IrcHttp.get(mvc, "/v1/namespaces/irc_ns_dropped"), 404, "NoSuchNamespaceException");
    assertEquals(404, IrcHttp.head(mvc, "/v1/namespaces/irc_ns_dropped").getResponse().getStatus());
  }

  @Test
  void aMultiLevelNamespaceIsABadRequest() throws Exception {
    assertIrcError(IrcHttp.get(mvc, "/v1/namespaces/a%1Fb"), 400, "BadRequestException");
    assertIrcError(IrcHttp.get(mvc, "/v1/namespaces/a%1Fb/tables"), 400, "BadRequestException");
    assertIrcError(IrcHttp.get(mvc, "/v1/namespaces/a%1Fb/tables/t"), 400, "BadRequestException");
  }

  // ---------------------------------------------------------------- tables

  @Test
  void listTablesIsTheNativeTableList() throws Exception {
    create("irc_list", "t1");
    create("irc_list", "t2");

    MvcResult result = IrcHttp.get(mvc, "/v1/namespaces/irc_list/tables");
    assertEquals(200, result.getResponse().getStatus(), body(result));
    List<Map<String, Object>> raw = JsonPath.read(body(result), "$.identifiers");
    assertEquals(2, raw.size());
    assertEquals(Collections.singletonList("irc_list"), raw.get(0).get("namespace"));
    ListTablesResponse parsed = IrcClientParsers.parse(body(result), ListTablesResponse.class);
    assertEquals(
        new HashSet<>(
            Arrays.asList(
                TableIdentifier.of("irc_list", "t1"), TableIdentifier.of("irc_list", "t2"))),
        new HashSet<>(parsed.identifiers()));

    List<String> nativeTables =
        JsonPath.read(
            body(
                mvc.perform(MockMvcRequestBuilders.post("/v1/databases/irc_list/tables/search"))
                    .andReturn()),
            "$.results[*].tableId");
    assertEquals(
        new HashSet<>(nativeTables),
        parsed.identifiers().stream().map(TableIdentifier::name).collect(Collectors.toSet()));
  }

  @Test
  void listTablesOfAMissingNamespaceIs404() throws Exception {
    assertIrcError(
        IrcHttp.get(mvc, "/v1/namespaces/irc_list_never/tables"), 404, "NoSuchNamespaceException");
  }

  @Test
  void tableExistsIs204Or404WithTheNativeAnswer() throws Exception {
    create("irc_exists", "t1");
    MvcResult head = IrcHttp.head(mvc, "/v1/namespaces/irc_exists/tables/t1");
    assertEquals(204, head.getResponse().getStatus());
    assertEquals("", body(head));
    mvc.perform(MockMvcRequestBuilders.get(tableUrl("irc_exists", "t1") + "/exists"))
        .andExpect(status().isOk());

    assertEquals(
        404, IrcHttp.head(mvc, "/v1/namespaces/irc_exists/tables/never").getResponse().getStatus());
    mvc.perform(MockMvcRequestBuilders.get(tableUrl("irc_exists", "never") + "/exists"))
        .andExpect(status().isNotFound());
    assertEquals(
        404, IrcHttp.head(mvc, "/v1/namespaces/irc_never/tables/t1").getResponse().getStatus());
  }

  @Test
  void loadTableReturnsTheStoredMetadataAtTheNativeMetadataLocation() throws Exception {
    create("irc_load", "t1");
    append("irc_load", "t1", "irc_load_a");
    append("irc_load", "t1", "irc_load_b");
    String nativeJson = json(get(mvc, "irc_load", "t1"));
    String metadataLocation = str(nativeJson, "$.tableLocation");
    TableMetadata stored = stored(metadataLocation);
    assertEquals(2, stored.snapshots().size());

    MvcResult result = IrcHttp.get(mvc, "/v1/namespaces/irc_load/tables/t1");
    assertEquals(200, result.getResponse().getStatus(), body(result));
    String json = body(result);
    assertEquals(metadataLocation, JsonPath.read(json, "$.metadata-location"));
    // The metadata is the stored file's content, field by field as Iceberg writes it.
    assertEquals(
        JsonPath.parse(TableMetadataParser.toJson(stored)).jsonString(),
        JsonPath.parse((Object) JsonPath.read(json, "$.metadata")).jsonString());
    assertFalse(
        ((Map<?, ?>) JsonPath.read(json, "$")).containsKey("config")
            && !((Map<?, ?>) JsonPath.read(json, "$.config")).isEmpty(),
        "no config for a table without column defaults: " + json);

    LoadTableResponse parsed = IrcClientParsers.parse(json, LoadTableResponse.class);
    assertEquals(metadataLocation, parsed.metadataLocation());
    TableMetadata loaded = parsed.tableMetadata();
    assertEquals(stored.uuid(), loaded.uuid());
    assertEquals(stored.location(), loaded.location());
    assertEquals(stored.formatVersion(), loaded.formatVersion());
    assertEquals(stored.schema().asStruct(), loaded.schema().asStruct());
    assertEquals(stored.spec(), loaded.spec());
    assertEquals(stored.sortOrder(), loaded.sortOrder());
    assertEquals(stored.properties(), loaded.properties());
    assertEquals(stored.currentSnapshot().snapshotId(), loaded.currentSnapshot().snapshotId());
    assertEquals(snapshotIds(stored), snapshotIds(loaded));
    assertEquals(stored.refs(), loaded.refs());
    assertEquals(str(nativeJson, "$.tableProperties['openhouse.tableUUID']"), loaded.uuid());
  }

  @Test
  void loadTableWithSnapshotsAllIsTheDefault() throws Exception {
    create("irc_snap_all", "t1");
    append("irc_snap_all", "t1", "irc_snap_all_a");
    String plain = body(IrcHttp.get(mvc, "/v1/namespaces/irc_snap_all/tables/t1"));
    MvcResult all =
        IrcHttp.perform(
            mvc,
            IrcHttp.request(HttpMethod.GET, "/v1/namespaces/irc_snap_all/tables/t1")
                .param("snapshots", "all"));
    assertEquals(200, all.getResponse().getStatus(), body(all));
    assertEquals(plain, body(all));
  }

  @Test
  void loadTableWithSnapshotsRefsReturnsOnlyReferencedSnapshots() throws Exception {
    create("irc_snap_refs", "t1");
    append("irc_snap_refs", "t1", "irc_snap_refs_a");
    append("irc_snap_refs", "t1", "irc_snap_refs_b");
    TableMetadata stored = stored(str(json(get(mvc, "irc_snap_refs", "t1")), "$.tableLocation"));

    MvcResult refs =
        IrcHttp.perform(
            mvc,
            IrcHttp.request(HttpMethod.GET, "/v1/namespaces/irc_snap_refs/tables/t1")
                .param("snapshots", "refs"));
    assertEquals(200, refs.getResponse().getStatus(), body(refs));
    TableMetadata loaded =
        IrcClientParsers.parse(body(refs), LoadTableResponse.class).tableMetadata();
    assertEquals(
        Collections.singletonList(stored.currentSnapshot().snapshotId()), snapshotIds(loaded));
    assertEquals(stored.currentSnapshot().snapshotId(), loaded.currentSnapshot().snapshotId());
    assertEquals(stored.refs(), loaded.refs());
    // Everything but the snapshot list is as stored.
    String storedJson = TableMetadataParser.toJson(stored);
    Map<String, Object> storedTree = JsonPath.read(storedJson, "$");
    Map<String, Object> loadedTree = JsonPath.read(body(refs), "$.metadata");
    storedTree.remove("snapshots");
    loadedTree.remove("snapshots");
    assertEquals(storedTree, loadedTree);
  }

  @Test
  void loadTableWithAnUnknownSnapshotsModeIsABadRequest() throws Exception {
    create("irc_snap_bad", "t1");
    assertIrcError(
        IrcHttp.perform(
            mvc,
            IrcHttp.request(HttpMethod.GET, "/v1/namespaces/irc_snap_bad/tables/t1")
                .param("snapshots", "some")),
        400,
        "BadRequestException");
  }

  @Test
  void loadMissingTableIs404NoSuchTable() throws Exception {
    create("irc_load_missing", "t1");
    assertIrcError(
        IrcHttp.get(mvc, "/v1/namespaces/irc_load_missing/tables/never"),
        404,
        "NoSuchTableException");
    assertIrcError(
        IrcHttp.get(mvc, "/v1/namespaces/irc_never_db/tables/t1"), 404, "NoSuchTableException");
    get(mvc, "irc_load_missing", "never").andExpect(status().isNotFound());
  }

  @Test
  void loadTableWithAnInvalidIdentifierIsABadRequestAsOnTheNativeApi() throws Exception {
    assertIrcError(
        IrcHttp.get(mvc, "/v1/namespaces/irc_bad/tables/a%3Bb"), 400, "BadRequestException");
    get(mvc, "irc_bad", "a;b").andExpect(status().isBadRequest());
  }

  @Test
  void loadTableIsAuditedAsATableRead() throws Exception {
    create("irc_audit", "t1");
    MvcResult result = IrcHttp.get(mvc, "/v1/namespaces/irc_audit/tables/t1");
    assertEquals(200, result.getResponse().getStatus(), body(result));
    String metadataLocation = JsonPath.read(body(result), "$.metadata-location");
    verify(tableAuditHandler, atLeastOnce())
        .audit(
            argThat(
                (TableAuditEvent event) ->
                    event != null
                        && "irc_audit".equals(event.getDatabaseName())
                        && "t1".equals(event.getTableName())
                        && event.getOperationType() == OperationType.READ
                        && event.getOperationStatus() == OperationStatus.SUCCESS
                        && metadataLocation.equals(event.getCurrentTableRoot())));
  }

  // ---------------------------------------------------------------- capabilities

  /**
   * A table that requires a capability is gated on the IRC route exactly as on the native route: a
   * client that does not declare the capability gets the native 422 and message; one that declares
   * it gets the per-table config the native route gives it.
   */
  @Test
  void aTableRequiringACapabilityIsGatedAsOnTheNativeRoute() throws Exception {
    created.add(new String[] {DEFAULTS_DB, "t1"});
    mvc.perform(
            MockMvcRequestBuilders.post("/v1/databases/" + DEFAULTS_DB + "/tables/")
                .header(CAPABILITIES, ClientCapabilityGate.COLUMN_DEFAULTS)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(
                    newTable(DEFAULTS_DB, "t1")
                        .toBuilder()
                        .tableProperties(
                            withCapability(newTable(DEFAULTS_DB, "t1").getTableProperties()))
                        .build()
                        .toJson()))
        .andExpect(status().isCreated());

    String path = "/v1/namespaces/" + DEFAULTS_DB + "/tables/t1";
    String nativeMessage = message(get(mvc, DEFAULTS_DB, "t1").andExpect(status().is(422)));
    assertTrue(nativeMessage.startsWith("CLIENT_CAPABILITY_MISSING: "), nativeMessage);
    assertEquals(
        nativeMessage,
        assertIrcError(IrcHttp.get(mvc, path), 422, "ClientCapabilityMissingException").message());

    MvcResult declared =
        IrcHttp.perform(
            mvc,
            IrcHttp.request(HttpMethod.GET, path)
                .header(CAPABILITIES, ClientCapabilityGate.COLUMN_DEFAULTS));
    assertEquals(200, declared.getResponse().getStatus(), body(declared));
    String nativeJson =
        json(
            mvc.perform(
                MockMvcRequestBuilders.get(tableUrl(DEFAULTS_DB, "t1"))
                    .header(CAPABILITIES, ClientCapabilityGate.COLUMN_DEFAULTS)));
    Map<String, String> nativeConfig = JsonPath.read(nativeJson, "$.config");
    assertEquals(
        Collections.singletonMap(ReadBridgeConfigResolver.COLUMN_DEFAULT_PREFIX + "2", "\"US\""),
        nativeConfig);
    LoadTableResponse parsed = IrcClientParsers.parse(body(declared), LoadTableResponse.class);
    assertEquals(nativeConfig, parsed.config());
    // No server-side overlay: the schema is the stored one.
    assertEquals(
        stored(str(nativeJson, "$.tableLocation")).schema().asStruct(),
        parsed.tableMetadata().schema().asStruct());

    // Existence is answered without loading the table, as natively.
    assertEquals(204, IrcHttp.head(mvc, path).getResponse().getStatus());
  }

  private static Map<String, String> withCapability(Map<String, String> properties) {
    Map<String, String> copy = new java.util.HashMap<>(properties);
    copy.put(ReadBridgeConfigResolver.COLUMN_DEFAULTS_CAPABILITY_PROP, "true");
    return copy;
  }

  // ---------------------------------------------------------------- out of scope

  /** Spec routes this server does not serve yet answer 406 in the spec's error shape. */
  @Test
  void unservedSpecRoutesAre406UnsupportedOperation() throws Exception {
    Object[][] routes = {
      {HttpMethod.POST, "/v1/namespaces"},
      {HttpMethod.DELETE, "/v1/namespaces/irc_x"},
      {HttpMethod.POST, "/v1/namespaces/irc_x/properties"},
      {HttpMethod.POST, "/v1/namespaces/irc_x/tables"},
      {HttpMethod.POST, "/v1/namespaces/irc_x/tables/t"},
      {HttpMethod.DELETE, "/v1/namespaces/irc_x/tables/t"},
      {HttpMethod.POST, "/v1/namespaces/irc_x/register"},
      {HttpMethod.POST, "/v1/namespaces/irc_x/tables/t/metrics"},
      {HttpMethod.POST, "/v1/tables/rename"},
      {HttpMethod.POST, "/v1/transactions/commit"},
      {HttpMethod.GET, "/v1/namespaces/irc_x/views"},
      {HttpMethod.POST, "/v1/namespaces/irc_x/views"},
      {HttpMethod.GET, "/v1/namespaces/irc_x/views/v"},
      {HttpMethod.HEAD, "/v1/namespaces/irc_x/views/v"},
      {HttpMethod.POST, "/v1/namespaces/irc_x/views/v"},
      {HttpMethod.DELETE, "/v1/namespaces/irc_x/views/v"},
      {HttpMethod.POST, "/v1/views/rename"},
      {HttpMethod.POST, "/v1/oauth/tokens"},
      {HttpMethod.POST, "/v1/namespaces/irc_x/tables/t/plan"},
      {HttpMethod.GET, "/v1/namespaces/irc_x/tables/t/plan/p1"},
      {HttpMethod.POST, "/v1/namespaces/irc_x/tables/t/tasks"},
      {HttpMethod.GET, "/v1/namespaces/irc_x/tables/t/credentials"},
    };
    for (Object[] route : routes) {
      MvcResult result =
          IrcHttp.perform(mvc, IrcHttp.request((HttpMethod) route[0], (String) route[1]));
      if (route[0] == HttpMethod.HEAD) {
        assertEquals(406, result.getResponse().getStatus(), route[1].toString());
      } else {
        assertIrcError(result, 406, "UnsupportedOperationException");
      }
    }
  }

  /** A path that is not an IRC route answers 400 in the spec's error shape, not a Spring page. */
  @Test
  void unknownIrcPathsAre400BadRequest() throws Exception {
    for (String path : new String[] {"/v1/unknown", "/v2/config", "/v1/namespaces/a/b/c/d"}) {
      assertIrcError(IrcHttp.get(mvc, path), 400, "BadRequestException");
    }
  }

  /** Native routes keep their own error body, whose {@code error} is the status phrase. */
  @Test
  void nativeErrorBodiesAreUnchanged() throws Exception {
    String nativeBody = json(get(mvc, "irc_native_never", "t1").andExpect(status().isNotFound()));
    assertEquals("NOT_FOUND", JsonPath.read(nativeBody, "$.status"));
    assertEquals("Not Found", JsonPath.read(nativeBody, "$.error"));
    assertEquals(
        "User table irc_native_never.t1 cannot be found", JsonPath.read(nativeBody, "$.message"));
  }

  private static List<Long> snapshotIds(TableMetadata metadata) {
    return metadata.snapshots().stream().map(Snapshot::snapshotId).collect(Collectors.toList());
  }
}
