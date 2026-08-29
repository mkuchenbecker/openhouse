package com.linkedin.openhouse.javaclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchViewException;
import org.apache.iceberg.exceptions.ServiceFailureException;
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

  private static final String SERVICE_FAILURE_ENVELOPE =
      "{\"error\":{\"message\":\"Internal failure\",\"type\":\"InternalServerError\","
          + "\"code\":500}}";

  private static final String AUTH_TOKEN_PROPERTY = "auth-token";

  private static final String VIEWS_ENABLED_PROPERTY = "iceberg-views-enabled";

  /** The spec's {@code CreateViewRequest} top-level key set (location omitted when unset). */
  private static final Set<String> SPEC_CREATE_VIEW_KEYS =
      new HashSet<>(Arrays.asList("name", "location", "schema", "view-version", "properties"));

  private MockWebServer server;
  private String url;
  private OpenHouseCatalog catalog;

  @BeforeEach
  void setup() throws IOException {
    server = new MockWebServer();
    server.start();
    url = String.format("http://%s:%s", server.getHostName(), server.getPort());
  }

  @AfterEach
  void teardown() throws IOException {
    if (catalog != null) {
      catalog.close();
      catalog = null;
    }
    server.shutdown();
  }

  private void initViewsEnabledCatalog(String token) {
    initViewsEnabledCatalog(token, Collections.emptyMap());
  }

  private void initViewsEnabledCatalog(String token, Map<String, String> extraProps) {
    initCatalog(true, token, extraProps);
  }

  private void initViewsDisabledCatalog(String token) {
    initCatalog(false, token, Collections.emptyMap());
  }

  private void initCatalog(boolean viewsEnabled, String token, Map<String, String> extraProps) {
    OpenHouseCatalog newCatalog = new OpenHouseCatalog();
    Map<String, String> properties = new HashMap<>();
    properties.put(CatalogProperties.URI, url);
    if (token != null) {
      properties.put(AUTH_TOKEN_PROPERTY, token);
    }
    properties.put(VIEWS_ENABLED_PROPERTY, Boolean.toString(viewsEnabled));
    properties.putAll(extraProps);
    newCatalog.initialize("openhouse", properties);
    this.catalog = newCatalog;
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

  /** Takes the next recorded request, failing (instead of NPEing) when none arrived in time. */
  private RecordedRequest takeRecordedRequest() throws InterruptedException {
    RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
    Assertions.assertNotNull(request, "Expected another recorded request, but none arrived");
    return request;
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
    initViewsDisabledCatalog("token");
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
    initViewsEnabledCatalog("token");
    Assertions.assertEquals(
        0, server.getRequestCount(), "initialize() must not touch the views REST surface");

    enqueueConfig();
    enqueueJson(404, VIEWS_DISABLED_ENVELOPE);
    Assertions.assertThrows(
        NoSuchViewException.class, () -> catalog.loadView(TableIdentifier.of("db", "v_wire")));

    Assertions.assertEquals(2, server.getRequestCount());
    RecordedRequest config = takeRecordedRequest();
    Assertions.assertEquals("GET", config.getMethod());
    Assertions.assertEquals("/v1/config", config.getRequestUrl().encodedPath());
    RecordedRequest load = takeRecordedRequest();
    Assertions.assertEquals("GET", load.getMethod());
    Assertions.assertEquals("/v1/namespaces/db/views/v_wire", load.getPath());
  }

  /**
   * F4 failure containment: a failed {@code /v1/config} bootstrap fails that view operation only —
   * exactly one request went out, nothing was cached, and the next view operation retries the
   * bootstrap from scratch (the SECOND {@code /v1/config} proves it) and then behaves normally.
   * Table operations never enter this code path at all.
   */
  @Test
  public void testEmbeddedCatalogBootstrapFailureIsContainedAndRetried()
      throws InterruptedException {
    initViewsEnabledCatalog("token");
    TableIdentifier viewId = TableIdentifier.of("db", "v_wire");

    enqueueJson(500, SERVICE_FAILURE_ENVELOPE);
    Assertions.assertThrows(ServiceFailureException.class, () -> catalog.loadView(viewId));
    Assertions.assertEquals(
        1, server.getRequestCount(), "bootstrap failure must stop after the config request");

    enqueueConfig();
    enqueueJson(404, VIEWS_DISABLED_ENVELOPE);
    Assertions.assertThrows(NoSuchViewException.class, () -> catalog.loadView(viewId));
    Assertions.assertEquals(3, server.getRequestCount());

    takeRecordedRequest(); // the failed bootstrap's config request
    RecordedRequest retriedConfig = takeRecordedRequest();
    Assertions.assertEquals(
        "/v1/config",
        retriedConfig.getRequestUrl().encodedPath(),
        "second view op must re-run the bootstrap (nothing cached from the failure)");
  }

  /**
   * {@code loadView} parses a complete spec {@code LoadViewResult} (metadata inline, no FileIO).
   */
  @Test
  public void testLoadViewParsesFullLoadViewResult() {
    initViewsEnabledCatalog("token");
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
    initViewsEnabledCatalog("token");
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
   * error. Both envelope fixtures pin the wire contract (the spec's list-route 404 type and the
   * views-disabled envelope); client-side, 1.5.2.17's {@code namespaceErrorHandler} switches on the
   * status code, not the envelope type, so both arrive as {@code NoSuchNamespaceException} — the
   * two-type catch in {@code listViews} is F1 plus future-proofing, not a behavioral split.
   */
  @Test
  public void testListViews404ReturnsEmptyList() throws InterruptedException {
    initViewsEnabledCatalog("token");
    enqueueConfig();
    enqueueJson(404, NO_SUCH_NAMESPACE_ENVELOPE);
    Assertions.assertTrue(catalog.listViews(Namespace.of("db")).isEmpty());

    enqueueJson(404, VIEWS_DISABLED_ENVELOPE);
    Assertions.assertTrue(catalog.listViews(Namespace.of("db")).isEmpty());

    Assertions.assertEquals(3, server.getRequestCount());
    takeRecordedRequest(); // config
    RecordedRequest list = takeRecordedRequest();
    Assertions.assertEquals("GET", list.getMethod());
    Assertions.assertEquals("/v1/namespaces/db/views", list.getRequestUrl().encodedPath());
  }

  /**
   * F1's negative space: only 404s are absorbed into an empty listing. A server-side failure (500
   * Iceberg envelope) must propagate as an exception — masking it as "no views" would hide real
   * outages from {@code SHOW VIEWS}.
   */
  @Test
  public void testListViews500Propagates() {
    initViewsEnabledCatalog("token");
    enqueueConfig();
    enqueueJson(500, SERVICE_FAILURE_ENVELOPE);

    Assertions.assertThrows(
        ServiceFailureException.class, () -> catalog.listViews(Namespace.of("db")));
  }

  /** A populated list response parses into identifiers (single GET; no paging in 1.5.2.17). */
  @Test
  public void testListViewsParsesIdentifiers() {
    initViewsEnabledCatalog("token");
    enqueueConfig();
    enqueueJson(200, "{\"identifiers\":[{\"namespace\":[\"db\"],\"name\":\"v_wire\"}]}");

    List<TableIdentifier> views = catalog.listViews(Namespace.of("db"));
    Assertions.assertEquals(1, views.size());
    Assertions.assertEquals(TableIdentifier.of("db", "v_wire"), views.get(0));
  }

  /**
   * {@code viewExists} on the enabled path delegates to the embedded catalog: {@code false} for the
   * views-disabled 404 envelope, {@code true} for a 200 {@code LoadViewResult}. The recorded
   * request pins F2's note: 1.5.2.17 issues {@code GET} on the view path (the load-and-catch {@code
   * ViewCatalog} default), not the spec's {@code HEAD}.
   */
  @Test
  public void testViewExistsDelegatesGet() throws InterruptedException {
    initViewsEnabledCatalog("token");
    TableIdentifier viewId = TableIdentifier.of("db", "v_wire");
    enqueueConfig();
    enqueueJson(404, VIEWS_DISABLED_ENVELOPE);
    Assertions.assertFalse(catalog.viewExists(viewId));

    enqueueJson(200, loadViewResultJson("fa6506c3-7681-40c8-86dc-e36561f83385", "SELECT 1 AS id"));
    Assertions.assertTrue(catalog.viewExists(viewId));

    takeRecordedRequest(); // config
    RecordedRequest exists = takeRecordedRequest();
    Assertions.assertEquals("GET", exists.getMethod(), "1.5.2.17 viewExists issues GET, not HEAD");
    Assertions.assertEquals("/v1/namespaces/db/views/v_wire", exists.getRequestUrl().encodedPath());
  }

  /**
   * {@code CREATE VIEW} sends a spec-shaped {@code CreateViewRequest}: kebab-case keys, the
   * definition under {@code view-version}, and no OpenHouse-isms — no {@code clusterId}, and the
   * path identity (namespace) is not repeated in the body.
   */
  @Test
  public void testCreateSendsSpecShapedCreateViewRequest() throws Exception {
    initViewsEnabledCatalog("token");
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

    takeRecordedRequest(); // config
    RecordedRequest create = takeRecordedRequest();
    Assertions.assertEquals("POST", create.getMethod());
    Assertions.assertEquals("/v1/namespaces/db/views", create.getRequestUrl().encodedPath());

    JsonNode body = JSON.readTree(create.getBody().readUtf8());
    Assertions.assertEquals("v_wire", body.get("name").asText());
    Assertions.assertTrue(body.has("schema"), "structured schema object expected");
    Assertions.assertTrue(body.has("view-version"), "kebab-case view-version expected");
    Assertions.assertTrue(body.has("properties"));
    for (Iterator<String> fields = body.fieldNames(); fields.hasNext(); ) {
      String field = fields.next();
      Assertions.assertTrue(
          SPEC_CREATE_VIEW_KEYS.contains(field), "Unexpected CreateViewRequest key: " + field);
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
    initViewsEnabledCatalog("token");
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
   * REPLACE probes that the name is not a table, loads the current view, then commits a spec-shaped
   * commit request: {@code assert-view-uuid} requirement plus typed updates — commit semantics are
   * owned by iceberg-core, not OpenHouse code.
   */
  @Test
  public void testReplaceSendsRequirementsAndUpdates() throws Exception {
    initViewsEnabledCatalog("token");
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
    takeRecordedRequest(); // config
    RecordedRequest tableProbe = takeRecordedRequest();
    Assertions.assertEquals("GET", tableProbe.getMethod());
    Assertions.assertEquals(
        "/v1/namespaces/db/tables/v_wire", tableProbe.getRequestUrl().encodedPath());
    RecordedRequest load = takeRecordedRequest();
    Assertions.assertEquals("GET", load.getMethod());
    RecordedRequest commit = takeRecordedRequest();
    Assertions.assertEquals("POST", commit.getMethod());
    Assertions.assertEquals("/v1/namespaces/db/views/v_wire", commit.getRequestUrl().encodedPath());

    JsonNode body = JSON.readTree(commit.getBody().readUtf8());
    Assertions.assertTrue(body.has("requirements"));
    Assertions.assertTrue(body.has("updates"));
    JsonNode assertUuid =
        StreamSupport.stream(body.get("requirements").spliterator(), false)
            .filter(requirement -> "assert-view-uuid".equals(requirement.path("type").asText()))
            .findFirst()
            .orElse(null);
    Assertions.assertNotNull(assertUuid, "commit must carry an assert-view-uuid requirement");
    Assertions.assertEquals(uuid, assertUuid.get("uuid").asText());
    Assertions.assertTrue(body.get("updates").size() > 0, "commit must carry typed updates");
  }

  /** {@code dropView} delegates to the REST {@code DELETE}; a 404 answers {@code false}. */
  @Test
  public void testDropViewDelegatesDelete() throws Exception {
    initViewsEnabledCatalog("token");
    enqueueConfig();
    server.enqueue(new MockResponse().setResponseCode(204));
    Assertions.assertTrue(catalog.dropView(TableIdentifier.of("db", "v_wire")));

    enqueueJson(404, VIEWS_DISABLED_ENVELOPE);
    Assertions.assertFalse(catalog.dropView(TableIdentifier.of("db", "v_wire")));

    takeRecordedRequest(); // config
    RecordedRequest drop = takeRecordedRequest();
    Assertions.assertEquals("DELETE", drop.getMethod());
    Assertions.assertEquals("/v1/namespaces/db/views/v_wire", drop.getRequestUrl().encodedPath());
  }

  /**
   * {@code renameView} is unsupported on the enabled path too (the server deliberately leaves the
   * spec's {@code rename-view} route unclaimed), and must not touch the wire.
   */
  @Test
  public void testRenameViewUnsupportedEvenWhenEnabledAndMakesNoRestCall() {
    initViewsEnabledCatalog("token");
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
    initViewsEnabledCatalog("the-token");
    enqueueConfig();
    enqueueJson(404, VIEWS_DISABLED_ENVELOPE);
    Assertions.assertThrows(
        NoSuchViewException.class, () -> catalog.loadView(TableIdentifier.of("db", "v_wire")));

    Assertions.assertEquals(2, server.getRequestCount());
    for (int i = 0; i < 2; i++) {
      RecordedRequest request = takeRecordedRequest();
      Assertions.assertEquals(
          "Bearer the-token",
          request.getHeader("Authorization"),
          "Missing/wrong Authorization header on " + request.getPath());
      Assertions.assertFalse(request.getPath().contains("oauth"));
    }
  }

  /**
   * The identity headers on view calls mirror the tables WebClient: {@code X-Client-Name} from
   * {@code client-name}, {@code session-id} from {@code app-id}, and {@code User-Agent} as {@code
   * openhouse-java-client/<client-version>}.
   */
  @Test
  public void testIdentityHeadersMirroredOnConfigRequest() throws Exception {
    Map<String, String> identity = new HashMap<>();
    identity.put(OpenHouseCatalog.CLIENT_NAME, "my-client");
    identity.put(OpenHouseCatalog.CLIENT_VERSION, "1.2.3");
    identity.put(CatalogProperties.APP_ID, "my-session");
    initViewsEnabledCatalog("token", identity);
    enqueueConfig();
    enqueueJson(404, VIEWS_DISABLED_ENVELOPE);
    Assertions.assertThrows(
        NoSuchViewException.class, () -> catalog.loadView(TableIdentifier.of("db", "v_wire")));

    RecordedRequest config = takeRecordedRequest();
    Assertions.assertEquals("/v1/config", config.getRequestUrl().encodedPath());
    Assertions.assertEquals("my-client", config.getHeader("X-Client-Name"));
    Assertions.assertEquals("my-session", config.getHeader("session-id"));
    Assertions.assertEquals("openhouse-java-client/1.2.3", config.getHeader("User-Agent"));
  }

  /**
   * {@code User-Agent} is set unconditionally: without a {@code client-version} property it falls
   * back to the jar manifest's Implementation-Version, else {@code unknown} — but always carries
   * the {@code openhouse-java-client/} product token. {@code session-id} stays absent without
   * {@code app-id} (a fabricated UUID would break correlation with the tables WebClient session).
   */
  @Test
  public void testDefaultUserAgentAlwaysAdvertisesClientProduct() throws Exception {
    initViewsEnabledCatalog("token");
    enqueueConfig();
    enqueueJson(404, VIEWS_DISABLED_ENVELOPE);
    Assertions.assertThrows(
        NoSuchViewException.class, () -> catalog.loadView(TableIdentifier.of("db", "v_wire")));

    RecordedRequest config = takeRecordedRequest();
    String userAgent = config.getHeader("User-Agent");
    Assertions.assertNotNull(userAgent);
    Assertions.assertTrue(
        userAgent.startsWith("openhouse-java-client/"),
        "User-Agent must always advertise the client product, got: " + userAgent);
    Assertions.assertNull(config.getHeader("session-id"));
  }

  /**
   * {@code updateAuthToken} propagates to the embedded catalog: the stale embedded catalog is
   * displaced (reclaimed later by {@code close()}) and the next view operation rebuilds it (fresh
   * {@code /v1/config}) carrying the new bearer token.
   */
  @Test
  public void testUpdateAuthTokenPropagatesToEmbeddedCatalog() throws Exception {
    initViewsEnabledCatalog("token-1");
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
    Assertions.assertEquals("Bearer token-1", takeRecordedRequest().getHeader("Authorization"));
    Assertions.assertEquals("Bearer token-1", takeRecordedRequest().getHeader("Authorization"));
    RecordedRequest refreshedConfig = takeRecordedRequest();
    Assertions.assertEquals("/v1/config", refreshedConfig.getRequestUrl().encodedPath());
    Assertions.assertEquals("Bearer token-2", refreshedConfig.getHeader("Authorization"));
    Assertions.assertEquals("Bearer token-2", takeRecordedRequest().getHeader("Authorization"));
  }

  /**
   * {@code close()} discards the embedded catalog (and drains any instances displaced by token
   * refreshes); a subsequent view operation re-initializes a fresh embedded catalog — the third
   * request being a fresh {@code /v1/config} proves the discard-and-rebuild.
   */
  @Test
  public void testCloseDiscardsEmbeddedCatalogAndNextViewOpRebuilds() throws Exception {
    initViewsEnabledCatalog("token");
    TableIdentifier viewId = TableIdentifier.of("db", "v_wire");
    enqueueConfig();
    enqueueJson(404, VIEWS_DISABLED_ENVELOPE);
    Assertions.assertThrows(NoSuchViewException.class, () -> catalog.loadView(viewId));

    catalog.close();

    enqueueConfig();
    enqueueJson(404, VIEWS_DISABLED_ENVELOPE);
    Assertions.assertThrows(NoSuchViewException.class, () -> catalog.loadView(viewId));

    Assertions.assertEquals(4, server.getRequestCount());
    takeRecordedRequest(); // first config
    takeRecordedRequest(); // first view GET
    RecordedRequest rebuiltConfig = takeRecordedRequest();
    Assertions.assertEquals(
        "/v1/config",
        rebuiltConfig.getRequestUrl().encodedPath(),
        "view op after close() must re-bootstrap a fresh embedded catalog");
  }

  /**
   * Blast-radius pin for a malformed (non-JSON) 404 body: iceberg-core's error handler falls back
   * to a synthesized error response carrying the raw body, and the 404 still maps to {@link
   * NoSuchViewException} on the view route — i.e. even a proxy's HTML 404 page keeps Spark's
   * loadView fall-through intact. This test pins that observed behavior, it does not specify it.
   */
  @Test
  public void testMalformed404BodySurfacesAsNoSuchViewException() {
    initViewsEnabledCatalog("token");
    enqueueConfig();
    server.enqueue(
        new MockResponse()
            .setResponseCode(404)
            .setBody("<html>not found</html>")
            .addHeader("Content-Type", "text/html"));

    Assertions.assertThrows(
        NoSuchViewException.class, () -> catalog.loadView(TableIdentifier.of("db", "v_wire")));
  }

  /** {@code newViewOps} is unreachable now that view operations delegate to the REST catalog. */
  @Test
  public void testNewViewOpsIsUnreachable() {
    initViewsEnabledCatalog("token");
    Assertions.assertThrows(
        IllegalStateException.class, () -> catalog.newViewOps(TableIdentifier.of("db", "v_wire")));
    Assertions.assertEquals(0, server.getRequestCount());
  }
}
