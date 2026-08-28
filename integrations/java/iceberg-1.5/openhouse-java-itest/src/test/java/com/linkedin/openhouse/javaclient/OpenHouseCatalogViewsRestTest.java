package com.linkedin.openhouse.javaclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchViewException;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.view.View;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Wire-contract tests for the gated OpenHouse view support in the iceberg-1.5 {@link
 * OpenHouseCatalog}: view operations delegate to an embedded Iceberg {@code RESTCatalog} speaking
 * the Iceberg REST catalog protocol. A {@link MockWebServer} plays the OpenHouse service with
 * canned spec-JSON responses; the recorded requests freeze the client's side of the contract.
 *
 * <p>The embedded REST catalog bootstraps itself with {@code GET /v1/config} when it is lazily
 * initialized on the FIRST view operation (never in {@code initialize}), so every test that crosses
 * the wire enqueues the config response before the view response(s).
 *
 * <p>Note (iceberg 1.5.2.17 client capabilities): {@code RESTSessionCatalog.listViews} performs a
 * single {@code GET} with no {@code next-page-token} paging, and {@code viewExists} issues {@code
 * GET} (the load-and-catch {@code ViewCatalog} default), not the spec's {@code HEAD} — so neither
 * pagination nor {@code HEAD} appears in these fixtures.
 */
public class OpenHouseCatalogViewsRestTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String CONFIG_JSON = "{\"defaults\":{},\"overrides\":{}}";

  private static final String VIEWS_DISABLED_ENVELOPE =
      "{\"error\":{\"message\":\"Views are disabled\",\"type\":\"NoSuchViewException\","
          + "\"code\":404}}";

  private static final String NO_SUCH_NAMESPACE_ENVELOPE =
      "{\"error\":{\"message\":\"Database does not exist\",\"type\":\"NoSuchNamespaceException\","
          + "\"code\":404}}";

  private static final String AUTH_TOKEN_PROPERTY = "auth-token";

  private MockWebServer server;
  private String url;

  @BeforeEach
  void setup() throws IOException {
    server = new MockWebServer();
    server.start();
    url = String.format("http://%s:%s", server.getHostName(), server.getPort());
  }

  @AfterEach
  void teardown() throws IOException {
    server.shutdown();
  }

  private OpenHouseCatalog newCatalog(boolean viewsEnabled, String token) {
    OpenHouseCatalog catalog = new OpenHouseCatalog();
    Map<String, String> properties = new HashMap<>();
    properties.put(CatalogProperties.URI, url);
    if (token != null) {
      properties.put(AUTH_TOKEN_PROPERTY, token);
    }
    properties.put("iceberg-views-enabled", Boolean.toString(viewsEnabled));
    catalog.initialize("openhouse", properties);
    return catalog;
  }

  private void enqueueJson(int code, String body) {
    server.enqueue(
        new MockResponse()
            .setResponseCode(code)
            .setBody(body)
            .addHeader("Content-Type", "application/json"));
  }

  private void enqueueConfig() {
    enqueueJson(200, CONFIG_JSON);
  }

  /** A spec-shaped {@code LoadViewResult}: complete view metadata inline, kebab-case keys. */
  private static String loadViewResultJson(String viewUuid, String sql) {
    return "{"
        + "\"metadata-location\":\"/data/openhouse/db/views/v_wire/metadata/00001.metadata.json\","
        + "\"metadata\":{"
        + ("\"view-uuid\":\"" + viewUuid + "\",")
        + "\"format-version\":1,"
        + "\"location\":\"/data/openhouse/db/views/v_wire\","
        + "\"current-version-id\":1,"
        + "\"versions\":[{"
        + "\"version-id\":1,"
        + "\"timestamp-ms\":1573518431292,"
        + "\"schema-id\":1,"
        + "\"default-catalog\":\"openhouse\","
        + "\"default-namespace\":[\"db\"],"
        + "\"summary\":{\"openhouse.source-dialect\":\"spark\"},"
        + ("\"representations\":[{\"type\":\"sql\",\"sql\":\"" + sql + "\",")
        + "\"dialect\":\"spark\"}]"
        + "}],"
        + "\"version-log\":[{\"timestamp-ms\":1573518431292,\"version-id\":1}],"
        + "\"schemas\":[{"
        + "\"schema-id\":1,"
        + "\"type\":\"struct\","
        + "\"fields\":[{\"id\":1,\"name\":\"id\",\"required\":true,\"type\":\"int\"}]"
        + "}],"
        + "\"properties\":{\"comment\":\"wire fixture\"}"
        + "}}";
  }

  private static Schema testSchema() {
    return new Schema(Types.NestedField.required(1, "id", Types.IntegerType.get()));
  }

  /**
   * With the gate off (the default), no view REST call — not even the {@code /v1/config} bootstrap
   * — is ever made, and every {@code ViewCatalog} method answers with its table-only (non-{@code
   * ViewCatalog}) behavior.
   */
  @Test
  public void testDisabledGateMakesNoRestCalls() {
    OpenHouseCatalog catalog = newCatalog(false, "token");
    TableIdentifier viewId = TableIdentifier.of("db", "v_wire");

    Assertions.assertThrows(NoSuchViewException.class, () -> catalog.loadView(viewId));
    Assertions.assertTrue(catalog.listViews(Namespace.of("db")).isEmpty());
    Assertions.assertFalse(catalog.dropView(viewId));
    Assertions.assertFalse(catalog.viewExists(viewId));
    Assertions.assertThrows(NoSuchNamespaceException.class, () -> catalog.buildView(viewId));
    Assertions.assertThrows(
        UnsupportedOperationException.class,
        () -> catalog.renameView(viewId, TableIdentifier.of("db", "v2")));

    Assertions.assertEquals(0, server.getRequestCount());
  }

  /**
   * With the gate on, the embedded REST catalog is initialized lazily: nothing crosses the wire at
   * {@code initialize} time, and the first view operation triggers the {@code GET /v1/config}
   * bootstrap followed by the view call itself.
   */
  @Test
  public void testEnabledGateIsLazyUntilFirstViewOperation() throws InterruptedException {
    OpenHouseCatalog catalog = newCatalog(true, "token");
    Assertions.assertEquals(
        0, server.getRequestCount(), "initialize() must not touch the views REST surface");

    enqueueConfig();
    enqueueJson(404, VIEWS_DISABLED_ENVELOPE);
    Assertions.assertThrows(
        NoSuchViewException.class, () -> catalog.loadView(TableIdentifier.of("db", "v_wire")));

    Assertions.assertEquals(2, server.getRequestCount());
    RecordedRequest config = server.takeRequest(1, TimeUnit.SECONDS);
    Assertions.assertEquals("GET", config.getMethod());
    Assertions.assertEquals("/v1/config", config.getRequestUrl().encodedPath());
    RecordedRequest load = server.takeRequest(1, TimeUnit.SECONDS);
    Assertions.assertEquals("GET", load.getMethod());
    Assertions.assertEquals("/v1/namespaces/db/views/v_wire", load.getPath());
  }

  /**
   * {@code loadView} parses a complete spec {@code LoadViewResult} (metadata inline, no FileIO).
   */
  @Test
  public void testLoadViewParsesFullLoadViewResult() {
    OpenHouseCatalog catalog = newCatalog(true, "token");
    enqueueConfig();
    enqueueJson(200, loadViewResultJson("fa6506c3-7681-40c8-86dc-e36561f83385", "SELECT 1 AS id"));

    View view = catalog.loadView(TableIdentifier.of("db", "v_wire"));

    Assertions.assertEquals("SELECT 1 AS id", view.sqlFor("spark").sql());
    Assertions.assertEquals("/data/openhouse/db/views/v_wire", view.location());
    Assertions.assertEquals(1, view.currentVersion().versionId());
    Assertions.assertEquals("wire fixture", view.properties().get("comment"));
    Assertions.assertEquals("id", view.schema().columns().get(0).name());
  }

  /**
   * The server's views-disabled envelope {@code
   * {"error":{"message":...,"type":"NoSuchViewException","code":404}}} surfaces as Iceberg's {@link
   * NoSuchViewException} purely via iceberg-core's {@code ErrorHandlers} — no OpenHouse-specific
   * mapping code — which is exactly what lets Spark's {@code ResolveViews} fall through to table
   * resolution.
   */
  @Test
  public void testViewsDisabledEnvelopeSurfacesAsNoSuchViewException() {
    OpenHouseCatalog catalog = newCatalog(true, "token");
    enqueueConfig();
    enqueueJson(404, VIEWS_DISABLED_ENVELOPE);

    NoSuchViewException e =
        Assertions.assertThrows(
            NoSuchViewException.class, () -> catalog.loadView(TableIdentifier.of("db", "v_wire")));
    Assertions.assertTrue(e.getMessage().contains("Views are disabled"));
  }

  /**
   * A 404 from the list route must surface as an EMPTY listing, not an exception: Spark's {@code
   * SparkCatalog.listViews} catches nothing, so {@code SHOW VIEWS} would otherwise leak a raw
   * error. Covers both 404 types the route can produce: {@code NoSuchNamespaceException} (spec's
   * list-route 404) and a {@code NoSuchViewException} views-disabled envelope.
   */
  @Test
  public void testListViews404ReturnsEmptyList() throws InterruptedException {
    OpenHouseCatalog catalog = newCatalog(true, "token");
    enqueueConfig();
    enqueueJson(404, NO_SUCH_NAMESPACE_ENVELOPE);
    Assertions.assertTrue(catalog.listViews(Namespace.of("db")).isEmpty());

    enqueueJson(404, VIEWS_DISABLED_ENVELOPE);
    Assertions.assertTrue(catalog.listViews(Namespace.of("db")).isEmpty());

    Assertions.assertEquals(3, server.getRequestCount());
    server.takeRequest(1, TimeUnit.SECONDS); // config
    RecordedRequest list = server.takeRequest(1, TimeUnit.SECONDS);
    Assertions.assertEquals("GET", list.getMethod());
    Assertions.assertEquals("/v1/namespaces/db/views", list.getRequestUrl().encodedPath());
  }

  /** A populated list response parses into identifiers (single GET; no paging in 1.5.2.17). */
  @Test
  public void testListViewsParsesIdentifiers() {
    OpenHouseCatalog catalog = newCatalog(true, "token");
    enqueueConfig();
    enqueueJson(200, "{\"identifiers\":[{\"namespace\":[\"db\"],\"name\":\"v_wire\"}]}");

    List<TableIdentifier> views = catalog.listViews(Namespace.of("db"));
    Assertions.assertEquals(1, views.size());
    Assertions.assertEquals(TableIdentifier.of("db", "v_wire"), views.get(0));
  }

  /**
   * {@code CREATE VIEW} sends a spec-shaped {@code CreateViewRequest}: kebab-case keys, the
   * definition under {@code view-version}, and no OpenHouse-isms — no {@code clusterId}, and the
   * path identity (namespace) is not repeated in the body.
   */
  @Test
  public void testCreateSendsSpecShapedCreateViewRequest() throws Exception {
    OpenHouseCatalog catalog = newCatalog(true, "token");
    enqueueConfig();
    enqueueJson(200, loadViewResultJson("fa6506c3-7681-40c8-86dc-e36561f83385", "SELECT 1 AS id"));

    catalog
        .buildView(TableIdentifier.of("db", "v_wire"))
        .withSchema(testSchema())
        .withDefaultNamespace(Namespace.of("db"))
        .withDefaultCatalog("openhouse")
        .withQuery("spark", "SELECT 1 AS id")
        .withProperty("comment", "wire fixture")
        .create();

    server.takeRequest(1, TimeUnit.SECONDS); // config
    RecordedRequest create = server.takeRequest(1, TimeUnit.SECONDS);
    Assertions.assertEquals("POST", create.getMethod());
    Assertions.assertEquals("/v1/namespaces/db/views", create.getRequestUrl().encodedPath());

    JsonNode body = JSON.readTree(create.getBody().readUtf8());
    // Exact top-level key set of the spec's CreateViewRequest (location omitted when unset).
    Assertions.assertEquals("v_wire", body.get("name").asText());
    Assertions.assertTrue(body.has("schema"), "structured schema object expected");
    Assertions.assertTrue(body.has("view-version"), "kebab-case view-version expected");
    Assertions.assertTrue(body.has("properties"));
    for (Iterator<String> fields = body.fieldNames(); fields.hasNext(); ) {
      String field = fields.next();
      Assertions.assertTrue(
          field.equals("name")
              || field.equals("location")
              || field.equals("schema")
              || field.equals("view-version")
              || field.equals("properties"),
          "Unexpected CreateViewRequest key: " + field);
    }
    // No OpenHouse-isms: no clusterId, no repeated path identity in the body.
    Assertions.assertFalse(body.has("clusterId"));
    Assertions.assertFalse(body.has("namespace"));
    Assertions.assertFalse(body.has("databaseId"));
    Assertions.assertFalse(body.has("viewId"));

    JsonNode viewVersion = body.get("view-version");
    Assertions.assertEquals("sql", viewVersion.get("representations").get(0).get("type").asText());
    Assertions.assertEquals(
        "spark", viewVersion.get("representations").get(0).get("dialect").asText());
    Assertions.assertEquals("db", viewVersion.get("default-namespace").get(0).asText());
    Assertions.assertEquals("wire fixture", body.get("properties").get("comment").asText());
  }

  /**
   * On the create route the server renders 404s (views disabled / missing database) with type
   * {@code NoSuchNamespaceException}, which iceberg-core maps to {@link NoSuchNamespaceException} —
   * the exception {@code SparkCatalog.createView} normalizes into Spark's {@code AnalysisException}
   * (asserted at the SQL layer by the Spark itests).
   */
  @Test
  public void testCreateRoute404SurfacesAsNoSuchNamespaceException() {
    OpenHouseCatalog catalog = newCatalog(true, "token");
    enqueueConfig();
    enqueueJson(404, NO_SUCH_NAMESPACE_ENVELOPE);

    Assertions.assertThrows(
        NoSuchNamespaceException.class,
        () ->
            catalog
                .buildView(TableIdentifier.of("db", "v_wire"))
                .withSchema(testSchema())
                .withDefaultNamespace(Namespace.of("db"))
                .withQuery("spark", "SELECT 1 AS id")
                .create());
  }

  /**
   * REPLACE loads the current view then commits a spec-shaped commit request: {@code
   * assert-view-uuid} requirement plus typed updates — commit semantics are owned by iceberg-core,
   * not OpenHouse code.
   */
  @Test
  public void testReplaceSendsRequirementsAndUpdates() throws Exception {
    OpenHouseCatalog catalog = newCatalog(true, "token");
    String uuid = "fa6506c3-7681-40c8-86dc-e36561f83385";
    enqueueConfig();
    // RESTViewBuilder.replace first probes tableExists (view name must not be a table): 404 = no.
    enqueueJson(
        404,
        "{\"error\":{\"message\":\"Table does not exist\",\"type\":\"NoSuchTableException\","
            + "\"code\":404}}");
    enqueueJson(200, loadViewResultJson(uuid, "SELECT 1 AS id")); // GET current view
    enqueueJson(200, loadViewResultJson(uuid, "SELECT 2 AS id")); // POST commit result

    catalog
        .buildView(TableIdentifier.of("db", "v_wire"))
        .withSchema(testSchema())
        .withDefaultNamespace(Namespace.of("db"))
        .withQuery("spark", "SELECT 2 AS id")
        .replace();

    Assertions.assertEquals(4, server.getRequestCount());
    server.takeRequest(1, TimeUnit.SECONDS); // config
    server.takeRequest(1, TimeUnit.SECONDS); // tableExists probe (404)
    RecordedRequest load = server.takeRequest(1, TimeUnit.SECONDS);
    Assertions.assertEquals("GET", load.getMethod());
    RecordedRequest commit = server.takeRequest(1, TimeUnit.SECONDS);
    Assertions.assertEquals("POST", commit.getMethod());
    Assertions.assertEquals("/v1/namespaces/db/views/v_wire", commit.getRequestUrl().encodedPath());

    JsonNode body = JSON.readTree(commit.getBody().readUtf8());
    Assertions.assertTrue(body.has("requirements"));
    Assertions.assertTrue(body.has("updates"));
    boolean hasAssertUuid = false;
    for (JsonNode requirement : body.get("requirements")) {
      if ("assert-view-uuid".equals(requirement.get("type").asText())) {
        hasAssertUuid = true;
        Assertions.assertEquals(uuid, requirement.get("uuid").asText());
      }
    }
    Assertions.assertTrue(hasAssertUuid, "commit must carry an assert-view-uuid requirement");
    Assertions.assertTrue(body.get("updates").size() > 0, "commit must carry typed updates");
  }

  /** {@code dropView} delegates to the REST {@code DELETE}; a 404 answers {@code false}. */
  @Test
  public void testDropViewDelegatesDelete() throws Exception {
    OpenHouseCatalog catalog = newCatalog(true, "token");
    enqueueConfig();
    server.enqueue(new MockResponse().setResponseCode(204));
    Assertions.assertTrue(catalog.dropView(TableIdentifier.of("db", "v_wire")));

    enqueueJson(404, VIEWS_DISABLED_ENVELOPE);
    Assertions.assertFalse(catalog.dropView(TableIdentifier.of("db", "v_wire")));

    server.takeRequest(1, TimeUnit.SECONDS); // config
    RecordedRequest drop = server.takeRequest(1, TimeUnit.SECONDS);
    Assertions.assertEquals("DELETE", drop.getMethod());
    Assertions.assertEquals("/v1/namespaces/db/views/v_wire", drop.getRequestUrl().encodedPath());
  }

  /**
   * {@code renameView} is unsupported on the enabled path too (the server deliberately leaves the
   * spec's {@code rename-view} route unclaimed), and must not touch the wire.
   */
  @Test
  public void testRenameViewUnsupportedEvenWhenEnabledAndMakesNoRestCall() {
    OpenHouseCatalog catalog = newCatalog(true, "token");
    Assertions.assertThrows(
        UnsupportedOperationException.class,
        () ->
            catalog.renameView(
                TableIdentifier.of("db", "v_wire"), TableIdentifier.of("db", "v_wire2")));
    Assertions.assertEquals(0, server.getRequestCount());
  }

  /**
   * The {@code auth-token} catalog property rides along as a plain {@code Authorization: Bearer}
   * header on EVERY view call, the {@code /v1/config} bootstrap included — via the REST catalog's
   * {@code header.} passthrough, never Iceberg's OAuth token machinery (no {@code /v1/oauth/tokens}
   * call may appear).
   */
  @Test
  public void testAuthorizationHeaderPresentOnEveryViewCall() throws Exception {
    OpenHouseCatalog catalog = newCatalog(true, "the-token");
    enqueueConfig();
    enqueueJson(404, VIEWS_DISABLED_ENVELOPE);
    Assertions.assertThrows(
        NoSuchViewException.class, () -> catalog.loadView(TableIdentifier.of("db", "v_wire")));

    Assertions.assertEquals(2, server.getRequestCount());
    for (int i = 0; i < 2; i++) {
      RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
      Assertions.assertEquals(
          "Bearer the-token",
          request.getHeader("Authorization"),
          "Missing/wrong Authorization header on " + request.getPath());
      Assertions.assertFalse(request.getPath().contains("oauth"));
    }
  }

  /**
   * {@code updateAuthToken} propagates to the embedded catalog: the stale embedded catalog is
   * discarded and the next view operation rebuilds it (fresh {@code /v1/config}) carrying the new
   * bearer token.
   */
  @Test
  public void testUpdateAuthTokenPropagatesToEmbeddedCatalog() throws Exception {
    OpenHouseCatalog catalog = newCatalog(true, "token-1");
    enqueueConfig();
    enqueueJson(404, VIEWS_DISABLED_ENVELOPE);
    Assertions.assertThrows(
        NoSuchViewException.class, () -> catalog.loadView(TableIdentifier.of("db", "v_wire")));

    catalog.updateAuthToken("token-2");

    enqueueConfig();
    enqueueJson(404, VIEWS_DISABLED_ENVELOPE);
    Assertions.assertThrows(
        NoSuchViewException.class, () -> catalog.loadView(TableIdentifier.of("db", "v_wire")));

    Assertions.assertEquals(4, server.getRequestCount());
    Assertions.assertEquals(
        "Bearer token-1", server.takeRequest(1, TimeUnit.SECONDS).getHeader("Authorization"));
    Assertions.assertEquals(
        "Bearer token-1", server.takeRequest(1, TimeUnit.SECONDS).getHeader("Authorization"));
    RecordedRequest refreshedConfig = server.takeRequest(1, TimeUnit.SECONDS);
    Assertions.assertEquals("/v1/config", refreshedConfig.getRequestUrl().encodedPath());
    Assertions.assertEquals("Bearer token-2", refreshedConfig.getHeader("Authorization"));
    Assertions.assertEquals(
        "Bearer token-2", server.takeRequest(1, TimeUnit.SECONDS).getHeader("Authorization"));
  }

  /** {@code newViewOps} is unreachable now that view operations delegate to the REST catalog. */
  @Test
  public void testNewViewOpsIsUnreachable() {
    OpenHouseCatalog catalog = newCatalog(true, "token");
    Assertions.assertThrows(
        IllegalStateException.class, () -> catalog.newViewOps(TableIdentifier.of("db", "v_wire")));
    Assertions.assertEquals(0, server.getRequestCount());
  }
}
