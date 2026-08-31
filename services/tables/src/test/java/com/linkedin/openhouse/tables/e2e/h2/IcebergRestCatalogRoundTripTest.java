package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.INITIAL_TABLE_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.Gson;
import com.jayway.jsonpath.JsonPath;
import com.linkedin.openhouse.common.audit.AuditHandler;
import com.linkedin.openhouse.common.security.DummyTokenInterceptor;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.TimePartitionSpec;
import com.linkedin.openhouse.tables.audit.model.OperationType;
import com.linkedin.openhouse.tables.audit.model.TableAuditEvent;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;
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
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * Comprehensive read-only round-trip integration tests for the Iceberg REST Catalog endpoints.
 *
 * <p>Follows the same validation pattern as Apache Polaris's {@code
 * PolarisRestCatalogIntegrationBase} (which extends Iceberg's {@code CatalogTests<RESTCatalog>}).
 * Since OpenHouse exposes a read-only Iceberg REST surface, we cannot extend {@code CatalogTests}
 * directly (it requires write operations for test setup). Instead, tables are created via the
 * OpenHouse API and all read operations are validated through a standard Iceberg {@link
 * RESTCatalog} client.
 *
 * <p>TODO: When Iceberg is upgraded to 1.7+, this test should extend {@code
 * CatalogTests<RESTCatalog>} with write tests disabled via {@code @Override} stubs, matching the
 * Polaris pattern. {@code CatalogTests} was introduced in Iceberg 1.7 and is not available in our
 * current 1.5.2 fork.
 *
 * <p>Test coverage mirrors the read-only subset of {@code CatalogTests}:
 *
 * <ul>
 *   <li>Config — prefix, defaults, overrides
 *   <li>List tables — single/multiple tables, empty namespace, cross-namespace isolation
 *   <li>Load table — schema fidelity, partition spec, metadata location, table location
 *   <li>Table exists — HEAD endpoint for existing and missing tables
 *   <li>Error handling — NoSuchTableException for missing tables
 * </ul>
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
public class IcebergRestCatalogRoundTripTest {

  private static final String DB1 = "icebergrestdb1";
  private static final String DB2 = "icebergrestdb2";
  private static final String EMPTY_DB = "icebergemptydb";

  private static final String T1 = "roundtrip_t1";
  private static final String T2 = "roundtrip_t2";
  private static final String T3 = "roundtrip_t3";

  /** Three-column schema: id (long), name (string), timestampCol (timestamp). */
  private static final String SCHEMA_3COL =
      "{\"type\": \"struct\", \"fields\": ["
          + "{\"id\": 1, \"required\": true, \"name\": \"id\", \"type\": \"long\"},"
          + "{\"id\": 2, \"required\": true, \"name\": \"name\", \"type\": \"string\"},"
          + "{\"id\": 3, \"required\": true, \"name\": \"timestampCol\", \"type\": \"timestamp\"}"
          + "]}";

  /** Two-column schema: key (long), value (string). */
  private static final String SCHEMA_2COL =
      "{\"type\": \"struct\", \"fields\": ["
          + "{\"id\": 1, \"required\": true, \"name\": \"key\", \"type\": \"long\"},"
          + "{\"id\": 2, \"required\": false, \"name\": \"value\", \"type\": \"string\"}"
          + "]}";

  @LocalServerPort private int port;

  @Autowired private AuditHandler<TableAuditEvent> tableAuditHandler;

  @Autowired
  private com.linkedin.openhouse.internal.catalog.repository.HouseNamespaceRepository
      houseNamespaceRepository;

  @Autowired private MeterRegistry meterRegistry;

  private RESTCatalog restCatalog;
  private String authToken;
  private final RestTemplate restTemplate = new RestTemplate();

  @BeforeAll
  void setUp() throws Exception {
    authToken = new DummyTokenInterceptor.DummySecurityJWT("testuser").buildNoopJWT();

    // DB1: two tables with different schemas
    createOpenHouseTable(DB1, T1, SCHEMA_3COL, "timestampCol", TimePartitionSpec.Granularity.HOUR);
    createOpenHouseTable(DB1, T2, SCHEMA_2COL, null, null);

    // DB2: one table
    createOpenHouseTable(DB2, T3, SCHEMA_3COL, "timestampCol", TimePartitionSpec.Granularity.DAY);

    // EMPTY_DB: create and immediately delete to ensure the namespace exists but has no tables
    createOpenHouseTable(EMPTY_DB, "tmp", SCHEMA_2COL, null, null);
    deleteOpenHouseTable(EMPTY_DB, "tmp");

    restCatalog = buildRESTCatalog();
  }

  @AfterAll
  void tearDown() throws IOException {
    if (restCatalog != null) {
      restCatalog.close();
    }
    deleteOpenHouseTable(DB1, T1);
    deleteOpenHouseTable(DB1, T2);
    deleteOpenHouseTable(DB2, T3);
  }

  // ---------------------------------------------------------------------------
  // Configuration and capabilities
  // ---------------------------------------------------------------------------

  @Test
  void configAdvertisesOnlySupportedEndpoints() {
    ResponseEntity<String> response =
        restTemplate.exchange(
            baseUrl() + "/v1/config", HttpMethod.GET, authorizedRequest(), String.class);

    List<String> endpoints = JsonPath.read(response.getBody(), "$.endpoints");
    assertThat(endpoints)
        .containsExactly(
            "GET /v1/{prefix}/namespaces",
            "POST /v1/{prefix}/namespaces",
            "GET /v1/{prefix}/namespaces/{namespace}",
            "HEAD /v1/{prefix}/namespaces/{namespace}",
            "DELETE /v1/{prefix}/namespaces/{namespace}",
            "POST /v1/{prefix}/namespaces/{namespace}/properties",
            "GET /v1/{prefix}/namespaces/{namespace}/tables",
            "POST /v1/{prefix}/namespaces/{namespace}/tables",
            "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}",
            "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}",
            "DELETE /v1/{prefix}/namespaces/{namespace}/tables/{table}",
            "HEAD /v1/{prefix}/namespaces/{namespace}/tables/{table}");
    assertThat(meterRegistry.get("http.server.requests").tag("uri", "/v1/config").timer().count())
        .isGreaterThan(0);
  }

  // ---------------------------------------------------------------------------
  // List tables
  // ---------------------------------------------------------------------------

  @Test
  void listTablesSingleNamespace() {
    List<TableIdentifier> tables = restCatalog.listTables(Namespace.of(DB1));
    List<String> names = tables.stream().map(TableIdentifier::name).collect(Collectors.toList());
    assertThat(names).contains(T1, T2);
  }

  @Test
  void listTablesReturnsCorrectNamespace() {
    List<TableIdentifier> tables = restCatalog.listTables(Namespace.of(DB1));
    for (TableIdentifier tid : tables) {
      assertThat(tid.namespace()).isEqualTo(Namespace.of(DB1));
    }
  }

  @Test
  void listTablesCrossNamespaceIsolation() {
    List<TableIdentifier> db1Tables = restCatalog.listTables(Namespace.of(DB1));
    List<TableIdentifier> db2Tables = restCatalog.listTables(Namespace.of(DB2));

    assertThat(db1Tables).extracting(TableIdentifier::name).contains(T1, T2).doesNotContain(T3);
    assertThat(db2Tables).extracting(TableIdentifier::name).contains(T3).doesNotContain(T1, T2);
  }

  /**
   * Every Iceberg Java client since 1.6.0 sends {@code pageToken=} on a first list request. It used
   * to be answered with 400, which made {@code RESTCatalog.listTables()} fail against this facade
   * outright -- the namespace list route already read it as "the first page".
   */
  @Test
  void listTablesAcceptsTheEmptyPageTokenClientsSend() {
    ResponseEntity<String> response =
        restTemplate.exchange(
            baseUrl() + "/v1/iceberg/namespaces/" + DB1 + "/tables?pageToken=",
            HttpMethod.GET,
            authorizedRequest(),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<String> names = JsonPath.read(response.getBody(), "$.identifiers[*].name");
    assertThat(names).contains(T1, T2);
  }

  @Test
  void listTablesEmptyNamespace() {
    List<TableIdentifier> tables = restCatalog.listTables(Namespace.of(EMPTY_DB));
    assertThat(tables).isEmpty();
  }

  @Test
  void listTablesSupportsOpaquePagination() {
    ResponseEntity<String> first =
        restTemplate.exchange(
            baseUrl() + "/v1/iceberg/namespaces/" + DB1 + "/tables?pageSize=1",
            HttpMethod.GET,
            authorizedRequest(),
            String.class);
    String token = JsonPath.read(first.getBody(), "$.next-page-token");
    List<String> firstNames = JsonPath.read(first.getBody(), "$.identifiers[*].name");

    ResponseEntity<String> second =
        restTemplate.exchange(
            baseUrl() + "/v1/iceberg/namespaces/" + DB1 + "/tables?pageToken=" + token,
            HttpMethod.GET,
            authorizedRequest(),
            String.class);
    List<String> secondNames = JsonPath.read(second.getBody(), "$.identifiers[*].name");

    assertThat(firstNames).hasSize(1);
    assertThat(secondNames).hasSize(1);
    assertThat(firstNames).doesNotContainAnyElementsOf(secondNames);
  }

  // ---------------------------------------------------------------------------
  // Load table — schema fidelity
  // ---------------------------------------------------------------------------

  @Test
  void loadTableSchemaColumns() {
    Table table = restCatalog.loadTable(TableIdentifier.of(DB1, T1));
    Schema schema = table.schema();
    assertThat(schema.columns()).hasSize(3);
    assertThat(schema.findField("id").type()).isEqualTo(Types.LongType.get());
    assertThat(schema.findField("name").type()).isEqualTo(Types.StringType.get());
    assertThat(schema.findField("timestampCol").type())
        .isEqualTo(Types.TimestampType.withoutZone());
  }

  @Test
  void loadTableSchemaFieldIds() {
    Table table = restCatalog.loadTable(TableIdentifier.of(DB1, T1));
    Schema schema = table.schema();
    // Verify field IDs are positive and unique
    List<Integer> ids =
        schema.columns().stream().map(Types.NestedField::fieldId).collect(Collectors.toList());
    assertThat(ids).doesNotHaveDuplicates();
    assertThat(ids).allMatch(id -> id > 0);
  }

  @Test
  void loadTableSchemaRequiredFields() {
    Table table = restCatalog.loadTable(TableIdentifier.of(DB1, T1));
    assertThat(table.schema().findField("id").isRequired()).isTrue();
    assertThat(table.schema().findField("name").isRequired()).isTrue();
  }

  @Test
  void loadTableDifferentSchema() {
    Table table = restCatalog.loadTable(TableIdentifier.of(DB1, T2));
    Schema schema = table.schema();
    assertThat(schema.columns()).hasSize(2);
    assertThat(schema.findField("key").type()).isEqualTo(Types.LongType.get());
    assertThat(schema.findField("value").type()).isEqualTo(Types.StringType.get());
    assertThat(schema.findField("value").isOptional()).isTrue();
  }

  // ---------------------------------------------------------------------------
  // Load table — partition spec
  // ---------------------------------------------------------------------------

  @Test
  void loadTablePartitionSpec() {
    Table table = restCatalog.loadTable(TableIdentifier.of(DB1, T1));
    PartitionSpec spec = table.spec();
    assertThat(spec.isPartitioned()).isTrue();
    assertThat(spec.fields()).hasSize(1);
    assertThat(spec.fields().get(0).name()).contains("timestampCol");
  }

  @Test
  void loadTableUnpartitioned() {
    Table table = restCatalog.loadTable(TableIdentifier.of(DB1, T2));
    assertThat(table.spec().isUnpartitioned()).isTrue();
  }

  // ---------------------------------------------------------------------------
  // Load table — metadata
  // ---------------------------------------------------------------------------

  @Test
  void loadTableLocation() {
    Table table = restCatalog.loadTable(TableIdentifier.of(DB1, T1));
    assertThat(table.location()).isNotEmpty();
  }

  @Test
  void loadTableProperties() {
    Table table = restCatalog.loadTable(TableIdentifier.of(DB1, T1));
    // Table should have properties (at minimum, internal OpenHouse properties)
    assertThat(table.properties()).isNotNull();
  }

  @Test
  void loadTableSortOrder() {
    Table table = restCatalog.loadTable(TableIdentifier.of(DB1, T1));
    // Default sort order is unsorted
    assertThat(table.sortOrder().isUnsorted()).isTrue();
  }

  @Test
  void loadTableCurrentSnapshot() {
    Table table = restCatalog.loadTable(TableIdentifier.of(DB1, T1));
    // Newly created table has no data, so current snapshot may be null
    // but the table object must be valid
    assertThat(table.schema()).isNotNull();
  }

  @Test
  void loadTableFromDifferentNamespace() {
    Table table = restCatalog.loadTable(TableIdentifier.of(DB2, T3));
    assertThat(table).isNotNull();
    assertThat(table.schema().findField("id")).isNotNull();
  }

  // ---------------------------------------------------------------------------
  // Table exists (HEAD)
  // ---------------------------------------------------------------------------

  @Test
  void tableExistsReturnsTrue() {
    assertThat(restCatalog.tableExists(TableIdentifier.of(DB1, T1))).isTrue();
    assertThat(restCatalog.tableExists(TableIdentifier.of(DB1, T2))).isTrue();
    assertThat(restCatalog.tableExists(TableIdentifier.of(DB2, T3))).isTrue();
  }

  @Test
  void tableExistsReturnsFalseForMissing() {
    assertThat(restCatalog.tableExists(TableIdentifier.of(DB1, "nonexistent"))).isFalse();
  }

  @Test
  void tableExistsReturnsFalseForWrongNamespace() {
    // T1 exists in DB1, not DB2
    assertThat(restCatalog.tableExists(TableIdentifier.of(DB2, T1))).isFalse();
  }

  // ---------------------------------------------------------------------------
  // Error handling
  // ---------------------------------------------------------------------------

  @Test
  void loadNonexistentTableThrows() {
    assertThatThrownBy(() -> restCatalog.loadTable(TableIdentifier.of(DB1, "nonexistent")))
        .isInstanceOf(NoSuchTableException.class);
  }

  @Test
  void loadTableFromNonexistentNamespaceThrows() {
    assertThatThrownBy(() -> restCatalog.loadTable(TableIdentifier.of("no_such_db", T1)))
        .isInstanceOf(NoSuchTableException.class);
  }

  @Test
  void invalidPrefixAndUnsupportedSnapshotProjectionFailExplicitly() {
    assertThatThrownBy(
            () ->
                restTemplate.exchange(
                    baseUrl() + "/v1/not-iceberg/namespaces/" + DB1 + "/tables",
                    HttpMethod.GET,
                    authorizedRequest(),
                    String.class))
        .isInstanceOf(HttpClientErrorException.BadRequest.class);

    assertThatThrownBy(
            () ->
                restTemplate.exchange(
                    baseUrl()
                        + "/v1/iceberg/namespaces/"
                        + DB1
                        + "/tables/"
                        + T1
                        + "?snapshots=refs",
                    HttpMethod.GET,
                    authorizedRequest(),
                    String.class))
        .isInstanceOf(HttpServerErrorException.NotImplemented.class);
  }

  @Test
  void existingOpenHouseReadApiRemainsAvailable() {
    ResponseEntity<String> response =
        restTemplate.exchange(
            baseUrl() + "/v1/databases/" + DB1 + "/tables/" + T1,
            HttpMethod.GET,
            authorizedRequest(),
            String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(JsonPath.<String>read(response.getBody(), "$.tableId")).isEqualTo(T1);
  }

  // ---------------------------------------------------------------------------
  // Consistency — multiple loads return same metadata
  // ---------------------------------------------------------------------------

  @Test
  void multipleLoadsReturnConsistentSchema() {
    Table first = restCatalog.loadTable(TableIdentifier.of(DB1, T1));
    Table second = restCatalog.loadTable(TableIdentifier.of(DB1, T1));

    assertThat(first.schema().asStruct()).isEqualTo(second.schema().asStruct());
    assertThat(first.location()).isEqualTo(second.location());
    assertThat(first.spec()).isEqualTo(second.spec());
  }

  @Test
  void tableLoadRetainsExistingReadAuditAttribution() {
    org.mockito.Mockito.clearInvocations(tableAuditHandler);

    restCatalog.loadTable(TableIdentifier.of(DB1, T1));

    ArgumentCaptor<TableAuditEvent> eventCaptor = ArgumentCaptor.forClass(TableAuditEvent.class);
    org.mockito.Mockito.verify(tableAuditHandler).audit(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getDatabaseName()).isEqualTo(DB1);
    assertThat(eventCaptor.getValue().getTableName()).isEqualTo(T1);
    assertThat(eventCaptor.getValue().getOperationType()).isEqualTo(OperationType.READ);
  }

  // ---------------------------------------------------------------------------
  // Namespaces
  // ---------------------------------------------------------------------------

  @Test
  void namespaceCreateLoadListDropRoundTrip() {
    Namespace ns = Namespace.of("icebergrestns1");

    assertThat(restCatalog.namespaceExists(ns)).isFalse();
    restCatalog.createNamespace(ns, Collections.singletonMap("owner", "testuser"));

    assertThat(restCatalog.namespaceExists(ns)).isTrue();
    assertThat(restCatalog.loadNamespaceMetadata(ns)).containsEntry("owner", "testuser");
    assertThat(restCatalog.listNamespaces()).contains(ns);

    assertThat(restCatalog.dropNamespace(ns)).isTrue();
    assertThat(restCatalog.namespaceExists(ns)).isFalse();
    assertThat(restCatalog.listNamespaces()).doesNotContain(ns);
  }

  @Test
  void namespacePropertiesAreSetAndRemoved() {
    Namespace ns = Namespace.of("icebergrestns2");
    restCatalog.createNamespace(ns);
    try {
      restCatalog.setProperties(ns, Collections.singletonMap("owner", "testuser"));
      assertThat(restCatalog.loadNamespaceMetadata(ns)).containsEntry("owner", "testuser");

      restCatalog.removeProperties(ns, Collections.singleton("owner"));
      assertThat(restCatalog.loadNamespaceMetadata(ns)).doesNotContainKey("owner");
    } finally {
      restCatalog.dropNamespace(ns);
    }
  }

  @Test
  void updatePropertiesPartitionsUpdatedRemovedAndMissing() {
    Namespace ns = Namespace.of("icebergrestns3");
    restCatalog.createNamespace(ns, Collections.singletonMap("owner", "testuser"));
    try {
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.set("Authorization", "Bearer " + authToken);
      ResponseEntity<String> response =
          restTemplate.exchange(
              baseUrl() + "/v1/iceberg/namespaces/" + ns + "/properties",
              HttpMethod.POST,
              new HttpEntity<>(
                  "{\"removals\":[\"owner\",\"absent\"],\"updates\":{\"team\":\"openhouse\"}}",
                  headers),
              String.class);

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(JsonPath.<List<String>>read(response.getBody(), "$.updated"))
          .containsExactly("team");
      assertThat(JsonPath.<List<String>>read(response.getBody(), "$.removed"))
          .containsExactly("owner");
      assertThat(JsonPath.<List<String>>read(response.getBody(), "$.missing"))
          .containsExactly("absent");
    } finally {
      restCatalog.dropNamespace(ns);
    }
  }

  @Test
  void creatingAnExistingNamespaceConflicts() {
    Namespace ns = Namespace.of("icebergrestns4");
    restCatalog.createNamespace(ns);
    try {
      assertThatThrownBy(() -> createNamespaceOverHttp(ns.toString()))
          .isInstanceOf(HttpClientErrorException.Conflict.class)
          .hasMessageContaining("AlreadyExistsException")
          .hasMessageContaining("Namespace already exists");
    } finally {
      restCatalog.dropNamespace(ns);
    }
  }

  @Test
  void loadingAMissingNamespaceIsNotFound() {
    assertThatThrownBy(
            () ->
                restTemplate.exchange(
                    baseUrl() + "/v1/iceberg/namespaces/icebergrestmissing",
                    HttpMethod.GET,
                    authorizedRequest(),
                    String.class))
        .isInstanceOf(HttpClientErrorException.NotFound.class)
        .hasMessageContaining("NoSuchNamespaceException")
        .hasMessageContaining("Namespace does not exist: icebergrestmissing");
  }

  @Test
  void aNamespaceOutsideTheIdentifierCharsetReadsAsMissingRatherThanMalformed() {
    assertThatThrownBy(
            () ->
                restTemplate.exchange(
                    baseUrl() + "/v1/iceberg/namespaces/not-a-legal-name",
                    HttpMethod.GET,
                    authorizedRequest(),
                    String.class))
        .isInstanceOf(HttpClientErrorException.NotFound.class)
        .hasMessageContaining("Namespace does not exist: not-a-legal-name");
  }

  @Test
  void droppingANamespaceThatStillHoldsTablesIsRejected() {
    // DB1 holds two tables created through the OpenHouse API. No namespace row is created here:
    // creating the table registered one, and a database that predates the namespace store is
    // derived from its tables anyway. Either way the drop reaches the emptiness check.
    Namespace ns = Namespace.of(DB1);

    assertThatThrownBy(
            () ->
                restTemplate.exchange(
                    baseUrl() + "/v1/iceberg/namespaces/" + DB1,
                    HttpMethod.DELETE,
                    authorizedRequest(),
                    String.class))
        .isInstanceOf(HttpClientErrorException.Conflict.class)
        .hasMessageContaining("NamespaceNotEmptyException")
        .hasMessageContaining("is not empty");

    assertThat(restCatalog.namespaceExists(ns)).isTrue();
  }

  @Test
  void reservedNamespacePropertiesCannotBeWrittenByAClient() {
    assertThatThrownBy(
            () ->
                createNamespaceOverHttp(
                    "icebergrestns5", "\"properties\":{\"openhouse.reserved\":\"nope\"}"))
        .isInstanceOf(HttpClientErrorException.BadRequest.class)
        .hasMessageContaining("ValidationException");
    assertThat(restCatalog.namespaceExists(Namespace.of("icebergrestns5"))).isFalse();
  }

  /**
   * B3: a database that exists because tables were written into it is a namespace. Before the fix
   * only an explicit createNamespace wrote a row, so on a real cluster SHOW NAMESPACES was empty
   * and GET /namespaces/prod was a 404 while GET /namespaces/prod/tables listed its tables.
   */
  @Test
  void aDatabaseCreatedByWritingATableIsANamespace() {
    Namespace ns = Namespace.of(DB1);
    assertThat(restCatalog.namespaceExists(ns)).isTrue();
    assertThat(restCatalog.listNamespaces()).contains(ns);
    assertThat(restCatalog.loadNamespaceMetadata(ns)).isNotNull();
  }

  /**
   * The inverse of what this used to assert. A database that predates the namespace store has no
   * row, and the API used to cover for that by deriving its existence from the tables it holds.
   * That derivation is gone: the row is what makes the namespace, and without one the namespace is
   * not there, whatever the table store says. Deleting the row here is exactly the unbackfilled
   * state, and pinning it is the point -- a read path that quietly kept the fallback would pass
   * every other test in this class.
   */
  @Test
  void aDatabaseWithNoStoredRowIsNotANamespace() {
    String db = "icebergrestderived";
    createOpenHouseTable(db, "derived_t", SCHEMA_2COL, null, null);
    Namespace ns = Namespace.of(db);
    try {
      // Simulate a database created before the namespace store existed.
      houseNamespaceRepository.deleteById(db);
      assertThat(houseNamespaceRepository.findById(db)).isEmpty();

      assertThat(restCatalog.namespaceExists(ns)).isFalse();
      assertThat(restCatalog.listNamespaces()).doesNotContain(ns);
      assertThatThrownBy(() -> restCatalog.loadNamespaceMetadata(ns))
          .isInstanceOf(NoSuchNamespaceException.class);
      // Nothing holds the name, so it can be created -- and the table it already holds is then
      // under a namespace that exists again.
      createNamespaceOverHttp(db);
      assertThat(restCatalog.namespaceExists(ns)).isTrue();
      // And it is still not empty, because the table store still holds a table under it: existence
      // moved to the namespace store, occupancy did not.
      assertThatThrownBy(
              () ->
                  restTemplate.exchange(
                      baseUrl() + "/v1/iceberg/namespaces/" + db,
                      HttpMethod.DELETE,
                      authorizedRequest(),
                      String.class))
          .isInstanceOf(HttpClientErrorException.Conflict.class)
          .hasMessageContaining("NamespaceNotEmptyException");
    } finally {
      deleteOpenHouseTable(db, "derived_t");
      houseNamespaceRepository.deleteById(db);
    }
  }

  /**
   * B1: a reserved key inside updates on an existing namespace is a bad request, not a false 404.
   * The handler used to rewrite every ValidationException from the service into "Namespace does not
   * exist", which for a namespace that plainly does exist is a statement that is not true.
   */
  @Test
  void aReservedKeyInUpdatesOnAnExistingNamespaceIsABadRequest() {
    Namespace ns = Namespace.of("icebergrestns6");
    restCatalog.createNamespace(ns);
    try {
      assertThatThrownBy(
              () ->
                  updatePropertiesOverHttp(
                      ns.toString(), "{\"updates\":{\"openhouse.foo\":\"x\"}}"))
          .isInstanceOf(HttpClientErrorException.BadRequest.class)
          .hasMessageContaining("ValidationException")
          .hasMessageContaining("reserved");

      // S5: policies is reserved for the same reason openhouse.* is, via BasePreservedKeyChecker.
      assertThatThrownBy(
              () -> updatePropertiesOverHttp(ns.toString(), "{\"updates\":{\"policies\":\"x\"}}"))
          .isInstanceOf(HttpClientErrorException.BadRequest.class)
          .hasMessageContaining("ValidationException");

      assertThat(restCatalog.loadNamespaceMetadata(ns)).doesNotContainKey("openhouse.foo");
    } finally {
      restCatalog.dropNamespace(ns);
    }
  }

  /**
   * B2: a key in both removals and updates is unprocessable, which is what
   * UpdateNamespacePropertiesRequest.validate() already says. Its exception shares a simple name
   * with an unrelated OpenHouse class, so only the other one was mapped and this fell to the
   * catch-all as a 500 the client could not tell from an outage.
   */
  @Test
  void aKeyInBothRemovalsAndUpdatesIsUnprocessable() {
    Namespace ns = Namespace.of("icebergrestns7");
    restCatalog.createNamespace(ns, Collections.singletonMap("owner", "testuser"));
    try {
      assertThatThrownBy(
              () ->
                  updatePropertiesOverHttp(
                      ns.toString(),
                      "{\"removals\":[\"owner\"],\"updates\":{\"owner\":\"other\"}}"))
          .isInstanceOf(HttpClientErrorException.class)
          .satisfies(
              e ->
                  assertThat(((HttpClientErrorException) e).getStatusCode())
                      .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

      assertThat(restCatalog.loadNamespaceMetadata(ns)).containsEntry("owner", "testuser");
    } finally {
      restCatalog.dropNamespace(ns);
    }
  }

  /**
   * B6: repository lookups fold case and listings do not, so a response that echoed the requested
   * spelling named a namespace no listing contained and that DELETE would then destroy. The stored
   * name is the answer.
   */
  @Test
  void aNamespaceIsAnsweredForUnderTheNameItIsStoredAs() {
    Namespace stored = Namespace.of("IcebergRestMixedCase");
    Namespace requested = Namespace.of("icebergrestmixedcase");
    restCatalog.createNamespace(stored, Collections.singletonMap("owner", "testuser"));
    try {
      ResponseEntity<String> response =
          restTemplate.exchange(
              baseUrl() + "/v1/iceberg/namespaces/" + requested,
              HttpMethod.GET,
              authorizedRequest(),
              String.class);
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(JsonPath.<List<String>>read(response.getBody(), "$.namespace"))
          .containsExactly("IcebergRestMixedCase");

      assertThat(restCatalog.listNamespaces()).contains(stored).doesNotContain(requested);
    } finally {
      // The drop still resolves case-insensitively; what changed is that the answer no longer
      // pretends the requested spelling is a namespace of its own.
      restTemplate.exchange(
          baseUrl() + "/v1/iceberg/namespaces/" + requested,
          HttpMethod.DELETE,
          authorizedRequest(),
          String.class);
    }
    assertThat(restCatalog.listNamespaces()).doesNotContain(stored);
  }

  /** B7: the property bag is bounded, and a null value is a rejection rather than a lost write. */
  @Test
  void namespacePropertiesAreBounded() {
    Namespace ns = Namespace.of("icebergrestns8");
    restCatalog.createNamespace(ns);
    try {
      StringBuilder tooMany = new StringBuilder("{\"updates\":{");
      for (int i = 0; i <= 100; i++) {
        tooMany.append(i == 0 ? "" : ",").append("\"k").append(i).append("\":\"v\"");
      }
      tooMany.append("}}");
      assertThatThrownBy(() -> updatePropertiesOverHttp(ns.toString(), tooMany.toString()))
          .isInstanceOf(HttpClientErrorException.BadRequest.class);

      StringBuilder longValue = new StringBuilder();
      for (int i = 0; i < 1100; i++) {
        longValue.append("v");
      }
      assertThatThrownBy(
              () ->
                  updatePropertiesOverHttp(
                      ns.toString(), "{\"updates\":{\"k\":\"" + longValue + "\"}}"))
          .isInstanceOf(HttpClientErrorException.BadRequest.class);

      assertThatThrownBy(
              () -> updatePropertiesOverHttp(ns.toString(), "{\"updates\":{\"k\":null}}"))
          .isInstanceOf(HttpClientErrorException.BadRequest.class);

      assertThat(restCatalog.loadNamespaceMetadata(ns)).isEmpty();
    } finally {
      restCatalog.dropNamespace(ns);
    }
  }

  private void updatePropertiesOverHttp(String namespace, String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Authorization", "Bearer " + authToken);
    restTemplate.exchange(
        baseUrl() + "/v1/iceberg/namespaces/" + namespace + "/properties",
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        String.class);
  }

  private void createNamespaceOverHttp(String namespace) {
    createNamespaceOverHttp(namespace, null);
  }

  private void createNamespaceOverHttp(String namespace, String extraJsonFields) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Authorization", "Bearer " + authToken);
    String body =
        "{\"namespace\":[\""
            + namespace
            + "\"]"
            + (extraJsonFields == null ? "" : "," + extraJsonFields)
            + "}";
    restTemplate.exchange(
        baseUrl() + "/v1/iceberg/namespaces",
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        String.class);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private RESTCatalog buildRESTCatalog() {
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

  private void createOpenHouseTable(
      String databaseId,
      String tableId,
      String schema,
      String partitionColumn,
      TimePartitionSpec.Granularity granularity) {
    String url = baseUrl() + "/v1/databases/" + databaseId + "/tables/";
    CreateUpdateTableRequestBody.CreateUpdateTableRequestBodyBuilder builder =
        CreateUpdateTableRequestBody.builder()
            .tableId(tableId)
            .databaseId(databaseId)
            .baseTableVersion(INITIAL_TABLE_VERSION)
            .clusterId("local-cluster")
            .schema(schema)
            .tableProperties(Collections.emptyMap());

    if (partitionColumn != null && granularity != null) {
      builder.timePartitioning(
          TimePartitionSpec.builder().columnName(partitionColumn).granularity(granularity).build());
    }

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Authorization", "Bearer " + authToken);
    HttpEntity<String> request = new HttpEntity<>(new Gson().toJson(builder.build()), headers);
    ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  private void deleteOpenHouseTable(String databaseId, String tableId) {
    String url = baseUrl() + "/v1/databases/" + databaseId + "/tables/" + tableId;
    try {
      HttpHeaders headers = new HttpHeaders();
      headers.set("Authorization", "Bearer " + authToken);
      restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
    } catch (Exception e) {
      // best-effort cleanup
    }
  }
}
