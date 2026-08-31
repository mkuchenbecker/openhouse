package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.INITIAL_TABLE_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.Gson;
import com.jayway.jsonpath.JsonPath;
import com.linkedin.openhouse.common.security.DummyTokenInterceptor;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * Nesting turned on, end to end, over real HTTP.
 *
 * <p>Two encodings of a multi-level namespace meet on these routes and are not interchangeable: the
 * Iceberg REST wire form joins levels with the {@code 0x1F} unit separator, and OpenHouse's
 * persisted form joins them with {@code .}. Every assertion here is about a request that crosses
 * that boundary, which is why it runs against a real servlet container rather than MockMvc — the
 * percent-encoding of {@code 0x1F} in a path segment is exactly the part MockMvc would not
 * exercise.
 *
 * <p>{@link DepthOneRoutingIdentityTest} is the other half: the same routes at the shipped depth of
 * 1, where none of this may be observable.
 */
@SpringBootTest(
    classes = SpringH2Application.class,
    properties = {
      "cluster.tables.iceberg-rest.enabled=true",
      "cluster.tables.namespace.max-depth=2"
    },
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class NestedNamespaceRoutingTest {

  private static final String PARENT = "nesteddb";
  private static final String CHILD = "sub";
  private static final Namespace NESTED = Namespace.of(PARENT, CHILD);
  private static final String ENCODED_NESTED = PARENT + "." + CHILD;

  /** The same one-column schema as {@link #SCHEMA}, for the routes that take it as an object. */
  private static final Schema REST_SCHEMA =
      new Schema(Types.NestedField.required(1, "id", Types.LongType.get()));

  private static final String SCHEMA =
      "{\"type\": \"struct\", \"fields\": ["
          + "{\"id\": 1, \"required\": true, \"name\": \"id\", \"type\": \"long\"}"
          + "]}";

  @LocalServerPort private int port;

  private RESTCatalog restCatalog;
  private String authToken;
  private final RestTemplate restTemplate = new RestTemplate();

  @BeforeAll
  void setUp() throws Exception {
    authToken = new DummyTokenInterceptor.DummySecurityJWT("testuser").buildNoopJWT();
    restCatalog = buildRestCatalog();
  }

  @AfterAll
  void tearDown() throws IOException {
    if (restCatalog != null) {
      restCatalog.close();
    }
  }

  /**
   * The whole round trip a nested namespace has to survive: created, listed under its parent,
   * loaded back, and dropped. The parent is occupied while the child is there, which is the
   * assertion the Iceberg conformance suite makes about nesting and the one a listing that silently
   * missed children would pass anyway.
   */
  @Test
  void aNestedNamespaceIsCreatedListedLoadedAndDropped() {
    Namespace parent = Namespace.of(PARENT);
    restCatalog.createNamespace(parent);
    restCatalog.createNamespace(NESTED, Collections.singletonMap("owner", "nesting"));

    assertThat(restCatalog.listNamespaces(parent)).containsExactly(NESTED);
    assertThat(restCatalog.loadNamespaceMetadata(NESTED)).containsEntry("owner", "nesting");
    assertThat(restCatalog.namespaceExists(NESTED)).isTrue();
    // The roots listing is unchanged by the child: a nested namespace is not a root.
    assertThat(restCatalog.listNamespaces()).contains(parent).doesNotContain(NESTED);

    assertThat(restCatalog.dropNamespace(NESTED)).isTrue();
    assertThat(restCatalog.namespaceExists(NESTED)).isFalse();
    assertThat(restCatalog.listNamespaces(parent)).isEmpty();
    assertThat(restCatalog.dropNamespace(parent)).isTrue();
  }

  /**
   * The wire form is the unit separator, and only the unit separator.
   *
   * <p>Calibration: reading the path variable as the persisted dot-joined form instead turns the
   * first assertion into a 404 and the second into a 200 — the two swap, which is precisely the
   * confusion this pins down.
   */
  @Test
  void theWireFormIsTheUnitSeparatorAndNotTheDotJoin() {
    restCatalog.createNamespace(Namespace.of("wiredb"));
    restCatalog.createNamespace(Namespace.of("wiredb", "leaf"));

    ResponseEntity<String> unitSeparated =
        restTemplate.exchange(
            baseUrl() + "/v1/iceberg/namespaces/wiredb%1Fleaf",
            HttpMethod.GET,
            authorizedRequest(),
            String.class);
    assertThat(unitSeparated.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<String> levels = JsonPath.read(unitSeparated.getBody(), "$.namespace");
    assertThat(levels).containsExactly("wiredb", "leaf");

    // The persisted encoding is not a wire encoding: as a path segment "wiredb.leaf" is one level
    // whose name contains a '.', which no identifier may.
    assertThatThrownBy(
            () ->
                restTemplate.exchange(
                    baseUrl() + "/v1/iceberg/namespaces/wiredb.leaf",
                    HttpMethod.GET,
                    authorizedRequest(),
                    String.class))
        .isInstanceOf(HttpClientErrorException.NotFound.class);

    restCatalog.dropNamespace(Namespace.of("wiredb", "leaf"));
    restCatalog.dropNamespace(Namespace.of("wiredb"));
  }

  /**
   * A table in a nested namespace, reached through both APIs, and stored flat.
   *
   * <p>Storage is the part worth pinning: {@code nesteddb.sub} has to be one directory name. A
   * layout that turned the separator into a path separator would break the UUID the table location
   * carries and the orphan-directory walk that reads it back.
   */
  @Test
  void aTableInANestedNamespaceIsAddressableAndStoredFlat() {
    restCatalog.createNamespace(Namespace.of(PARENT));
    restCatalog.createNamespace(NESTED);
    String location = createTable(ENCODED_NESTED, "flat_tbl");

    assertThat(location).contains("/" + ENCODED_NESTED + "/");
    assertThat(location).doesNotContain("/" + PARENT + "/" + CHILD + "/");

    assertThat(restCatalog.listTables(NESTED))
        .extracting(TableIdentifier::name)
        .contains("flat_tbl");
    assertThat(restCatalog.tableExists(TableIdentifier.of(NESTED, "flat_tbl"))).isTrue();
    assertThat(restCatalog.loadTable(TableIdentifier.of(NESTED, "flat_tbl")).name())
        .contains("flat_tbl");

    deleteTable(ENCODED_NESTED, "flat_tbl");
    restCatalog.dropNamespace(NESTED);
    restCatalog.dropNamespace(Namespace.of(PARENT));
  }

  /**
   * The create-time half of the metadata-table discriminator, at the depth where it bites.
   *
   * <p>Calibration: dropping the depth floor from the predicate leaves this green and turns the
   * depth-1 case in {@link DepthOneRoutingIdentityTest} red, which is the trade the floor exists to
   * prevent.
   */
  @Test
  void aTableNamedAfterAMetadataTableIsRefusedInANestedNamespace() {
    restCatalog.createNamespace(Namespace.of("shadowdb"));
    restCatalog.createNamespace(Namespace.of("shadowdb", "sub"));

    assertThatThrownBy(() -> createTable("shadowdb.sub", "history"))
        .isInstanceOf(HttpClientErrorException.BadRequest.class)
        .hasMessageContaining("history");

    // A name that is not a metadata table type is unaffected at the same depth.
    String location = createTable("shadowdb.sub", "histories");
    assertThat(location).contains("/shadowdb.sub/");
    deleteTable("shadowdb.sub", "histories");

    restCatalog.dropNamespace(Namespace.of("shadowdb", "sub"));
    restCatalog.dropNamespace(Namespace.of("shadowdb"));
  }

  /**
   * The rename-time half of the metadata-table discriminator, over the {@code /v1} REST route, at
   * the depth where it bites.
   *
   * <p>The REST rename route and this rule were built separately and had never run together. The
   * route goes through {@link
   * com.linkedin.openhouse.tables.api.handler.TablesApiHandler#renameTable} deliberately, so that
   * the audit aspect's pointcut still fires, and the rule lives on the validator that handler
   * invokes -- which reads as though composing the two grants the rule to REST renames for free. It
   * does not follow on its own: the handler is told which database the destination is in by name,
   * so a route that names only the first level of a nested namespace hands it a one-level database
   * and the rule's depth floor is never cleared. Renaming a table in {@code renamedb.sub} onto
   * {@code history} is the case that separates the two readings, because it is refused under one
   * and accepted under the other.
   *
   * <p>Calibration: encoding only {@code destination.namespace().level(0)} on the way to the
   * handler turns the first assertion green -- the rename succeeds -- and leaves {@code
   * renamedb.sub.history} occupied by a base table that {@code loadTable} would then read as the
   * {@code history} metadata table of {@code renamedb.sub}.
   */
  @Test
  void aRestRenameOntoAMetadataTableNameIsRefusedInANestedNamespace() {
    Namespace parent = Namespace.of("renamedb");
    Namespace nested = Namespace.of("renamedb", "sub");
    restCatalog.createNamespace(parent);
    restCatalog.createNamespace(nested);
    restCatalog.createTable(TableIdentifier.of(nested, "renamable"), REST_SCHEMA);

    assertThatThrownBy(
            () ->
                restCatalog.renameTable(
                    TableIdentifier.of(nested, "renamable"), TableIdentifier.of(nested, "history")))
        // The validator's own sentence, reported faithfully: the refusal is the service's, and the
        // facade does not reword it. Iceberg's client raises IllegalArgumentException for the 400
        // it carries.
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("is an Iceberg metadata table name")
        .hasMessageContaining("renamedb.sub.history would be ambiguous");

    // The table is still where it was: a refused rename moves nothing.
    assertThat(restCatalog.tableExists(TableIdentifier.of(nested, "renamable"))).isTrue();
    assertThat(restCatalog.tableExists(TableIdentifier.of(nested, "history"))).isFalse();

    // A destination that is not a metadata table type is renamed at the same depth, so the refusal
    // above is this rule and not a rename route that refuses everything nested.
    restCatalog.renameTable(
        TableIdentifier.of(nested, "renamable"), TableIdentifier.of(nested, "histories"));
    assertThat(restCatalog.tableExists(TableIdentifier.of(nested, "histories"))).isTrue();

    restCatalog.dropTable(TableIdentifier.of(nested, "histories"), false);
    restCatalog.dropNamespace(nested);
    restCatalog.dropNamespace(parent);
  }

  /**
   * A metadata-table identifier is not a user table, and the {@code /v1} table routes say so.
   *
   * <p>This is the read-path counterpart of the admission rules, and it only becomes reachable once
   * nesting is on: at depth 2 the namespace {@code ns.tbl} is one the routes will accept, so {@code
   * ns.tbl.files} reaches the repository instead of being turned away as too deep. The catalog
   * already declines to read it as a base table -- but Iceberg answers that decline by building the
   * {@code files} metadata table rather than by raising {@code NoSuchTableException}, so the
   * repository has to say "no user table here" itself.
   *
   * <p>404 is also the answer the contract needs rather than merely a safe one: a stock Iceberg
   * client that gets it loads the base table and derives the metadata table on its own, which is
   * what {@code CatalogTests.testLoadMetadataTable} asserts. A 500 stops that fallback dead.
   *
   * <p>Calibration: without the repository's guard this route answers 500, because the mapper is
   * handed a {@code FilesTable} and reads OpenHouse properties a metadata table does not carry.
   */
  @Test
  void aMetadataTableIdentifierIsNotAUserTableOnTheV1Routes() {
    restCatalog.createNamespace(Namespace.of("metadb"));
    createTable("metadb", "tbl");

    assertThatThrownBy(
            () ->
                restTemplate.exchange(
                    baseUrl() + "/v1/iceberg/namespaces/metadb%1Ftbl/tables/files",
                    HttpMethod.GET,
                    authorizedRequest(),
                    String.class))
        .isInstanceOf(HttpClientErrorException.NotFound.class)
        .hasMessageContaining("Table does not exist: metadb.tbl.files");

    // HEAD answers the same way, so tableExists() does not report a metadata table as a base table.
    assertThatThrownBy(
            () ->
                restTemplate.exchange(
                    baseUrl() + "/v1/iceberg/namespaces/metadb%1Ftbl/tables/files",
                    HttpMethod.HEAD,
                    authorizedRequest(),
                    String.class))
        .isInstanceOf(HttpClientErrorException.NotFound.class);

    deleteTable("metadb", "tbl");
    restCatalog.dropNamespace(Namespace.of("metadb"));
  }

  /** The configured depth is still a bound: one level past it is not routable. */
  @Test
  void aNamespaceDeeperThanTheConfiguredMaximumIsNotRoutable() {
    assertThatThrownBy(() -> restCatalog.createNamespace(Namespace.of("toodeep", "a", "b")))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> restCatalog.listTables(Namespace.of("toodeep", "a", "b")))
        .isInstanceOf(NoSuchNamespaceException.class);
  }

  private String createTable(String databaseId, String tableId) {
    String url = baseUrl() + "/v1/databases/" + databaseId + "/tables/";
    CreateUpdateTableRequestBody body =
        CreateUpdateTableRequestBody.builder()
            .tableId(tableId)
            .databaseId(databaseId)
            .baseTableVersion(INITIAL_TABLE_VERSION)
            .clusterId("local-cluster")
            .schema(SCHEMA)
            .tableProperties(Collections.emptyMap())
            .build();
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Authorization", "Bearer " + authToken);
    ResponseEntity<String> response =
        restTemplate.postForEntity(
            url, new HttpEntity<>(new Gson().toJson(body), headers), String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return JsonPath.read(response.getBody(), "$.tableLocation");
  }

  private void deleteTable(String databaseId, String tableId) {
    restTemplate.exchange(
        baseUrl() + "/v1/databases/" + databaseId + "/tables/" + tableId,
        HttpMethod.DELETE,
        authorizedRequest(),
        String.class);
  }

  private RESTCatalog buildRestCatalog() {
    RESTCatalog catalog = new RESTCatalog();
    Map<String, String> properties = new HashMap<>();
    properties.put("uri", baseUrl());
    properties.put("warehouse", "openhouse");
    properties.put("token", authToken);
    properties.put("header.Authorization", "Bearer " + authToken);
    catalog.initialize("openhouse", properties);
    return catalog;
  }

  private String baseUrl() {
    return "http://localhost:" + port;
  }

  private HttpEntity<Void> authorizedRequest() {
    HttpHeaders headers = new HttpHeaders();
    headers.set("Authorization", "Bearer " + authToken);
    return new HttpEntity<>(headers);
  }

  @SuppressWarnings("unused")
  private static List<String> names(List<TableIdentifier> identifiers) {
    return identifiers.stream().map(TableIdentifier::name).collect(Collectors.toList());
  }
}
