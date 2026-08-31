package com.linkedin.openhouse.tables.e2e.h2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import com.linkedin.openhouse.common.security.DummyTokenInterceptor;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * End-to-end coverage of the Iceberg REST write routes, driven by a stock {@link RESTCatalog}
 * client against the real service.
 *
 * <p>The conformance suite in {@code tests/iceberg-rest-catalog-compat} is the outer gate on these
 * routes, but it only knows about Iceberg. The behaviour that is specifically OpenHouse's -- server
 * defaults for the three fields a stock client cannot send, the policy patch key the server now has
 * to understand, soft-delete semantics behind {@code dropTable} -- has no reference test anywhere,
 * so it is pinned here.
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
public class IcebergRestCatalogWriteTest {

  private static final String DB = "icebergrestwritedb";

  private static final Schema SCHEMA =
      new Schema(
          Types.NestedField.required(1, "id", Types.LongType.get()),
          Types.NestedField.optional(2, "data", Types.StringType.get()));

  @LocalServerPort private int port;

  private RESTCatalog restCatalog;
  private String authToken;
  private final RestTemplate restTemplate = new RestTemplate();

  @BeforeAll
  void setUp() throws Exception {
    authToken = new DummyTokenInterceptor.DummySecurityJWT("testuser").buildNoopJWT();
    restCatalog = buildRestCatalog();
    restCatalog.createNamespace(Namespace.of(DB));
  }

  @AfterAll
  void tearDown() throws IOException {
    if (restCatalog != null) {
      restCatalog.close();
    }
  }

  /**
   * The three fields OpenHouse's own API demands and the REST specification has no room for --
   * cluster, base table version and table type -- are defaulted by the server. A create that sends
   * none of them has to succeed, and the table it produces has to carry the cluster's own values.
   */
  @Test
  void createDefaultsTheFieldsAStockClientCannotSend() {
    TableIdentifier identifier = TableIdentifier.of(DB, "defaults_t");
    Table created = restCatalog.buildTable(identifier, SCHEMA).create();

    assertThat(created.schema().asStruct()).isEqualTo(SCHEMA.asStruct());
    assertThat(created.properties())
        .containsEntry("openhouse.clusterId", "local-cluster")
        .containsEntry("openhouse.tableType", "PRIMARY_TABLE")
        .containsEntry("openhouse.databaseId", DB)
        .containsEntry("openhouse.tableId", "defaults_t");
    assertThat(restCatalog.tableExists(identifier)).isTrue();
  }

  /**
   * A property the client sends on create is stored and comes back. Pinned because it is the
   * premise behind the conformance suite's catalog-level {@code table-default.*} tests: if a
   * property the create request carries survives, a default that does not arrive was never sent.
   */
  @Test
  void createStoresThePropertiesTheRequestCarries() {
    TableIdentifier identifier = TableIdentifier.of(DB, "props_t");
    Table created =
        restCatalog.buildTable(identifier, SCHEMA).withProperty("prop1", "val1").create();

    assertThat(created.properties()).containsEntry("prop1", "val1");
    assertThat(restCatalog.loadTable(identifier).properties()).containsEntry("prop1", "val1");
  }

  @Test
  void createIntoAMissingNamespaceIsNotFound() {
    assertThatThrownBy(
            () ->
                restCatalog
                    .buildTable(TableIdentifier.of("no_such_database", "t"), SCHEMA)
                    .create())
        .isInstanceOf(NoSuchNamespaceException.class);
  }

  @Test
  void createOfAnExistingTableConflicts() {
    TableIdentifier identifier = TableIdentifier.of(DB, "conflict_t");
    restCatalog.buildTable(identifier, SCHEMA).create();

    assertThatThrownBy(() -> restCatalog.buildTable(identifier, SCHEMA).create())
        .isInstanceOf(AlreadyExistsException.class)
        .hasMessageStartingWith("Table already exists: " + DB + ".conflict_t");
  }

  /**
   * A staged create returns metadata for a table the catalog does not yet hold; the transaction's
   * commit -- which lands on the table commit route, not on any transaction route -- is what
   * creates it.
   */
  @Test
  void stagedCreateDoesNotCommitUntilTheTransactionDoes() {
    TableIdentifier identifier = TableIdentifier.of(DB, "staged_t");
    Transaction transaction = restCatalog.buildTable(identifier, SCHEMA).createTransaction();

    assertThat(restCatalog.tableExists(identifier)).isFalse();

    transaction.updateProperties().set("staged", "yes").commit();
    transaction.commitTransaction();

    assertThat(restCatalog.tableExists(identifier)).isTrue();
    assertThat(restCatalog.loadTable(identifier).properties()).containsEntry("staged", "yes");
  }

  @Test
  void commitAppliesUpdatesThroughTheSharedEngine() {
    TableIdentifier identifier = TableIdentifier.of(DB, "commit_t");
    Table table = restCatalog.buildTable(identifier, SCHEMA).create();

    table.updateSchema().addColumn("added", Types.StringType.get()).commit();

    assertThat(restCatalog.loadTable(identifier).schema().findField("added")).isNotNull();
  }

  @Test
  void commitOnAMissingTableIsNotFound() {
    assertThatThrownBy(
            () ->
                restTemplate.exchange(
                    baseUrl() + "/v1/iceberg/namespaces/" + DB + "/tables/no_such_table",
                    HttpMethod.POST,
                    jsonRequest("{\"requirements\":[],\"updates\":[]}"),
                    String.class))
        .isInstanceOf(HttpClientErrorException.NotFound.class);
  }

  /**
   * {@code SET POLICY} reaches a REST server as a property patch under {@code
   * updated.openhouse.policy}, a key that only the OpenHouse client used to understand. The failure
   * this pins is not an error but a silence: without the server-side merge the commit would
   * succeed, the policy would be unchanged, and the patch would be stored as an ordinary property.
   */
  @Test
  void aPolicyPatchOverRestChangesThePolicyRatherThanBeingStored() {
    TableIdentifier identifier = TableIdentifier.of(DB, "policy_t");
    Table table = restCatalog.buildTable(identifier, SCHEMA).create();
    assertThat(openHouseTableJson(DB, "policy_t")).doesNotContain("\"sharingEnabled\":true");

    table.updateProperties().set("updated.openhouse.policy", "{\"sharingEnabled\":true}").commit();

    assertThat(
            JsonPath.<Boolean>read(openHouseTableJson(DB, "policy_t"), "$.policies.sharingEnabled"))
        .isTrue();
    assertThat(restCatalog.loadTable(identifier).properties())
        .doesNotContainKey("updated.openhouse.policy");
  }

  /**
   * OpenHouse has one drop and it purges: the catalog entry goes and so do the files under the
   * table's location. The specification's {@code purgeRequested} parameter is accepted and changes
   * nothing, so both spellings of drop have to remove the table.
   */
  @Test
  void dropRemovesTheTableWhicheverWayPurgeIsAsked() {
    TableIdentifier withPurge = TableIdentifier.of(DB, "drop_purge_t");
    TableIdentifier withoutPurge = TableIdentifier.of(DB, "drop_nopurge_t");
    restCatalog.buildTable(withPurge, SCHEMA).create();
    restCatalog.buildTable(withoutPurge, SCHEMA).create();

    assertThat(restCatalog.dropTable(withPurge, /*purge*/ true)).isTrue();
    assertThat(restCatalog.dropTable(withoutPurge, /*purge*/ false)).isTrue();

    assertThat(restCatalog.tableExists(withPurge)).isFalse();
    assertThat(restCatalog.tableExists(withoutPurge)).isFalse();
  }

  @Test
  void dropOfAMissingTableReportsThatItWasNotThere() {
    assertThat(restCatalog.dropTable(TableIdentifier.of(DB, "never_existed"))).isFalse();
  }

  /**
   * The whole-document {@code /v1} path still declares its own base version, so the checks that
   * defend that declaration must keep working there. A REST-created table updated through {@code
   * /v1} with a stale base version is still rejected.
   */
  @Test
  void theWholeDocumentPathStillRejectsAStaleDeclaredBase() {
    TableIdentifier identifier = TableIdentifier.of(DB, "stale_base_t");
    restCatalog.buildTable(identifier, SCHEMA).create();

    assertThatThrownBy(
            () ->
                restTemplate.exchange(
                    baseUrl() + "/v1/databases/" + DB + "/tables/",
                    HttpMethod.POST,
                    jsonRequest(
                        "{\"tableId\":\"stale_base_t\",\"databaseId\":\""
                            + DB
                            + "\",\"clusterId\":\"local-cluster\",\"baseTableVersion\":\"/tmp/bogus.metadata.json\","
                            + "\"schema\":\"{\\\"type\\\":\\\"struct\\\",\\\"schema-id\\\":0,\\\"fields\\\":["
                            + "{\\\"id\\\":1,\\\"name\\\":\\\"id\\\",\\\"required\\\":true,\\\"type\\\":\\\"long\\\"}]}\","
                            + "\"tableProperties\":{}}"),
                    String.class))
        .isInstanceOf(HttpClientErrorException.class);
  }

  private String openHouseTableJson(String databaseId, String tableId) {
    return restTemplate
        .exchange(
            baseUrl() + "/v1/databases/" + databaseId + "/tables/" + tableId,
            HttpMethod.GET,
            authorizedRequest(),
            String.class)
        .getBody();
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

  private HttpEntity<String> jsonRequest(String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Authorization", "Bearer " + authToken);
    return new HttpEntity<>(body, headers);
  }
}
