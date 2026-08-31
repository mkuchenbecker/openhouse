package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.INITIAL_TABLE_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.Gson;
import com.jayway.jsonpath.JsonPath;
import com.linkedin.openhouse.common.security.DummyTokenInterceptor;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.common.utils.NamespaceUtil;
import com.linkedin.openhouse.internal.catalog.repository.HouseNamespaceRepository;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
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
 * The shipped configuration, asserted rather than assumed.
 *
 * <p>Everything the nesting work added is supposed to be invisible at {@code
 * cluster.tables.namespace.max-depth} of 1, and "invisible" is a claim about specific bytes: the
 * key a namespace is stored under, the directory a table's data lands in, the routes that answer,
 * and the message a rejected route answers with. This class boots the service with the property
 * left at its default and pins each of those.
 *
 * <p>{@link NestedNamespaceRoutingTest} is the other half, with the property raised.
 */
@SpringBootTest(
    classes = SpringH2Application.class,
    properties = "cluster.tables.iceberg-rest.enabled=true",
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DepthOneRoutingIdentityTest {

  private static final String DB = "depthonedb";

  private static final String SCHEMA =
      "{\"type\": \"struct\", \"fields\": ["
          + "{\"id\": 1, \"required\": true, \"name\": \"id\", \"type\": \"long\"}"
          + "]}";

  @LocalServerPort private int port;

  @Autowired private HouseNamespaceRepository houseNamespaceRepository;

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
   * The metadata-table discriminator must not reach down to depth 1. {@code depthonedb.history} is
   * a table someone called {@code history}, it is creatable, and it is loadable under that name
   * through the same route any other table uses.
   *
   * <p>Calibration: dropping the depth floor from {@code isMetadataTableIdentifier} turns the
   * create into a 400 and the load into a metadata-table lookup, which is the regression this
   * exists to catch.
   */
  @Test
  void aTableNamedAfterAMetadataTableIsStillCreatableAndLoadableAtDepthOne() {
    String location = createTable(DB, "history");

    assertThat(location).contains("/" + DB + "/history-");
    assertThat(restCatalog.tableExists(TableIdentifier.of(Namespace.of(DB), "history"))).isTrue();
    assertThat(restCatalog.loadTable(TableIdentifier.of(Namespace.of(DB), "history")).name())
        .contains("history");

    deleteTable(DB, "history");
  }

  /**
   * The persisted key and the storage path a depth-1 database produces. Both are the identity case
   * of an encoding that now has a separator in it, and both are compared against the plain database
   * name rather than against another call to the encoder.
   */
  @Test
  void theStoredKeyAndTheStoragePathAreThePlainDatabaseName() {
    String location = createTable("keydb", "t");

    assertThat(NamespaceUtil.encode(Namespace.of("keydb"))).isEqualTo("keydb");
    assertThat(houseNamespaceRepository.findById("keydb")).isPresent();
    assertThat(location).contains("/keydb/t-");

    deleteTable("keydb", "t");
  }

  /**
   * A nested path is not routable at depth 1, and says so in the words it has always used. The
   * message is part of the contract here: widening the route to a configured depth must not change
   * what a client sees when the configuration has not moved.
   */
  @Test
  void aNestedPathIsRejectedAtDepthOneWithTheOriginalMessage() {
    assertThatThrownBy(
            () ->
                restTemplate.exchange(
                    baseUrl() + "/v1/iceberg/namespaces/a%1Fb/tables",
                    HttpMethod.GET,
                    authorizedRequest(),
                    String.class))
        .isInstanceOf(HttpClientErrorException.NotFound.class)
        .hasMessageContaining("Only single-level namespaces are supported");

    assertThatThrownBy(
            () ->
                restTemplate.exchange(
                    baseUrl() + "/v1/iceberg/namespaces/a%1Fb",
                    HttpMethod.GET,
                    authorizedRequest(),
                    String.class))
        .isInstanceOf(HttpClientErrorException.NotFound.class);
  }

  /** The single-level routes themselves are untouched. */
  @Test
  void theSingleLevelRoutesStillAnswer() {
    createTable("routedb", "t");

    ResponseEntity<String> namespace =
        restTemplate.exchange(
            baseUrl() + "/v1/iceberg/namespaces/routedb",
            HttpMethod.GET,
            authorizedRequest(),
            String.class);
    assertThat(namespace.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JsonPath.<java.util.List<String>>read(namespace.getBody(), "$.namespace"))
        .containsExactly("routedb");

    assertThat(restCatalog.listTables(Namespace.of("routedb")))
        .contains(TableIdentifier.of(Namespace.of("routedb"), "t"));

    deleteTable("routedb", "t");
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
}
