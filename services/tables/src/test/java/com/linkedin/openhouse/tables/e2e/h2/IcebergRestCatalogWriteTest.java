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
import org.apache.iceberg.exceptions.NoSuchTableException;
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
  private static final String OTHER_DB = "icebergrestwriteotherdb";

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
    restCatalog.createNamespace(Namespace.of(OTHER_DB));
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

  @Test
  void renameMovesTheTableAndFreesItsOldName() {
    TableIdentifier from = TableIdentifier.of(DB, "rename_from_t");
    TableIdentifier to = TableIdentifier.of(DB, "rename_to_t");
    Table original = restCatalog.buildTable(from, SCHEMA).withProperty("prop1", "val1").create();
    String uuid = original.uuid().toString();

    restCatalog.renameTable(from, to);

    assertThat(restCatalog.tableExists(from)).isFalse();
    Table renamed = restCatalog.loadTable(to);
    assertThat(renamed.uuid().toString()).isEqualTo(uuid);
    assertThat(renamed.properties())
        .containsEntry("prop1", "val1")
        .containsEntry("openhouse.tableId", "rename_to_t");
  }

  @Test
  void renameOfAMissingTableIsNotFound() {
    assertThatThrownBy(
            () ->
                restCatalog.renameTable(
                    TableIdentifier.of(DB, "never_existed_t"),
                    TableIdentifier.of(DB, "wherever_t")))
        .isInstanceOf(NoSuchTableException.class);
  }

  @Test
  void renameOntoAnExistingTableConflicts() {
    TableIdentifier from = TableIdentifier.of(DB, "rename_conflict_from_t");
    TableIdentifier to = TableIdentifier.of(DB, "rename_conflict_to_t");
    restCatalog.buildTable(from, SCHEMA).create();
    restCatalog.buildTable(to, SCHEMA).create();

    assertThatThrownBy(() -> restCatalog.renameTable(from, to))
        .isInstanceOf(AlreadyExistsException.class);

    assertThat(restCatalog.tableExists(from)).isTrue();
    assertThat(restCatalog.tableExists(to)).isTrue();
  }

  /**
   * The two ways a cross-database rename can fail have to stay distinguishable, and the order of
   * the checks is what keeps them so: a destination namespace that does not exist is the
   * specification's 404, decided before OpenHouse's refusal to move a table between databases is
   * ever reached. Without the ordering a client with a typo in the namespace would be told the
   * catalog does not support what it asked for.
   */
  @Test
  void renameIntoAMissingNamespaceIsNotFoundRatherThanRefused() {
    TableIdentifier from = TableIdentifier.of(DB, "rename_missing_ns_t");
    restCatalog.buildTable(from, SCHEMA).create();

    assertThatThrownBy(
            () ->
                restCatalog.renameTable(from, TableIdentifier.of("no_such_database", "anywhere_t")))
        .isInstanceOf(NoSuchNamespaceException.class);

    assertThat(restCatalog.tableExists(from)).isTrue();
  }

  /**
   * OpenHouse declines a rename that changes the table's database, and the REST route does not
   * widen that: the refusal is the service-layer validator's, reported verbatim as a 400. Pinned
   * because the facade calls the validator rather than the service, and going straight to the
   * service -- which does not apply this rule itself -- would silently make REST callers able to do
   * something {@code /v1} callers cannot.
   */
  @Test
  void renameAcrossDatabasesIsRefusedWithTheValidatorsOwnReason() {
    TableIdentifier from = TableIdentifier.of(DB, "rename_cross_db_t");
    restCatalog.buildTable(from, SCHEMA).create();

    assertThatThrownBy(
            () ->
                restTemplate.exchange(
                    baseUrl() + "/v1/iceberg/tables/rename",
                    HttpMethod.POST,
                    jsonRequest(
                        "{\"source\":{\"namespace\":[\""
                            + DB
                            + "\"],\"name\":\"rename_cross_db_t\"},"
                            + "\"destination\":{\"namespace\":[\""
                            + OTHER_DB
                            + "\"],\"name\":\"rename_cross_db_t\"}}"),
                    String.class))
        .isInstanceOf(HttpClientErrorException.BadRequest.class)
        .hasMessageContaining("Rename table across databases is not supported");

    assertThat(restCatalog.tableExists(from)).isTrue();
    assertThat(restCatalog.tableExists(TableIdentifier.of(OTHER_DB, "rename_cross_db_t")))
        .isFalse();
  }

  /**
   * The metrics route accepts a report and discards it. There is nothing to observe afterwards --
   * that is the point -- so what this pins is that a report in the shape a stock client sends is
   * accepted at all, rather than rejected as malformed or falling through to a 500. A client's
   * reporter suppresses its own failures, so nothing else in the suite would notice if it did.
   */
  @Test
  void aMetricsReportIsAcceptedAndDiscarded() {
    TableIdentifier identifier = TableIdentifier.of(DB, "metrics_t");
    restCatalog.buildTable(identifier, SCHEMA).create();

    assertThat(
            restTemplate
                .exchange(
                    baseUrl() + "/v1/iceberg/namespaces/" + DB + "/tables/metrics_t/metrics",
                    HttpMethod.POST,
                    jsonRequest(scanReportJson(DB + ".metrics_t")),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
  }

  /**
   * The route does not look the table up, so a report about a table that is not there is accepted
   * like any other. The specification lists a 404 for this case and OpenHouse never answers it; the
   * divergence is deliberate (see {@code OpenHouseIcebergRestApiHandler#reportMetrics}) and is
   * pinned here so it cannot change unnoticed.
   */
  @Test
  void aMetricsReportAboutAnUnknownTableIsAcceptedToo() {
    assertThat(
            restTemplate
                .exchange(
                    baseUrl() + "/v1/iceberg/namespaces/" + DB + "/tables/no_such_table/metrics",
                    HttpMethod.POST,
                    jsonRequest(scanReportJson(DB + ".no_such_table")),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
  }

  /** A scan report in the shape {@code RESTMetricsReporter} sends one. */
  private static String scanReportJson(String tableName) {
    return "{\"report-type\":\"scan-report\","
        + "\"table-name\":\""
        + tableName
        + "\","
        + "\"snapshot-id\":1,"
        + "\"filter\":true,"
        + "\"schema-id\":0,"
        + "\"projected-field-ids\":[1],"
        + "\"projected-field-names\":[\"id\"],"
        + "\"metrics\":{},"
        + "\"metadata\":{\"engine-name\":\"test\"}}";
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
