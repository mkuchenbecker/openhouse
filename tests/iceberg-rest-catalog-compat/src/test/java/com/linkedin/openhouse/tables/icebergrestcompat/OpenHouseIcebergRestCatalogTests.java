package com.linkedin.openhouse.tables.icebergrestcompat;

import com.linkedin.openhouse.tablestest.OpenHouseLocalServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.catalog.CatalogTests;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Runs Apache Iceberg's reference catalog test suite ({@link CatalogTests}) against OpenHouse's
 * read-only Iceberg REST facade, following the pattern used by Apache Polaris ({@code
 * PolarisRestCatalogIntegrationBase}) and Iceberg's own REST Compatibility Kit ({@code
 * RESTCompatibilityKitCatalogTests}).
 *
 * <p>The server under test is the real OpenHouse tables service, booted in-process through {@link
 * OpenHouseLocalServer} from the relocated ("uber") tables-test-fixtures jar with {@code
 * cluster.tables.iceberg-rest.enabled=true}. Relocation moves the server's pinned
 * com.linkedin.iceberg 1.5.2 classes out of the {@code org.apache.iceberg} namespace, which is what
 * allows the stock Apache Iceberg 1.11 {@link RESTCatalog} client (and the 1.11 test suite itself)
 * to sit on the same classpath. The server runtime is not upgraded by this module; the 1.11
 * dependency is test-scoped only.
 *
 * <p>The facade implements {@code GET /v1/config}, the six namespace routes (create, load, exists,
 * drop, list and update properties), the read-only table routes (list tables, load table, table
 * exists), the table write routes (create, including staged create; commit; drop; rename) and the
 * metrics-reporting route, which accepts a report and discards it. Every test the facade cannot
 * honestly satisfy is overridden and disabled with a reason. No reason left here names a route that
 * is merely missing: each names a capability OpenHouse deliberately declines -- registering a
 * metadata file it does not own, partition-spec evolution, schema narrowing, a client-chosen table
 * location or format version -- or a divergence in wording rather than behaviour. None of them will
 * be deleted by adding an endpoint, and each says exactly what the catalog does instead. Further
 * tests skip themselves through the suite's built-in capability flags ({@code
 * supportsEmptyNamespace()}, {@code supportsNestedNamespaces()}, {@code supportsNamesWithDot()},
 * {@code supportsNamesWithSlashes()}).
 */
public class OpenHouseIcebergRestCatalogTests extends CatalogTests<RESTCatalog> {

  private static final String FACADE_ENABLED_FLAG = "cluster.tables.iceberg-rest.enabled";

  private static final String NAMESPACE_MAX_DEPTH_FLAG = "cluster.tables.namespace.max-depth";

  /**
   * The suite's nesting tests work in {@code parent} and {@code parent.child}, so the server under
   * test has to admit two levels for them to mean anything. Two rather than more: it is the
   * shallowest configuration in which nesting is on at all, so anything that only works because
   * there is room to spare would still show up here.
   */
  private static final String NAMESPACE_MAX_DEPTH = "2";

  private static OpenHouseLocalServer server;
  private static RESTCatalog restCatalog;
  private static String authToken;

  private static final String DECLINES_REGISTER_TABLE =
      "OpenHouse declines to adopt an existing metadata file as a table: POST /v1/{prefix}/namespaces/{namespace}/register is deliberately not implemented and not advertised in /v1/config. A register points the catalog at a metadata.json it did not write. OpenHouse allocates every table's location itself through the cluster's storage selector, and its only drop purges everything under that location -- so an adopted file outside managed storage would be one the catalog can delete but never placed, carrying schema, partitioning and properties that never passed the write path's checks. Refusing the capability outright is the honest answer; a half-register that stored the pointer without owning the files would be worse than none. Note too that this test registers the metadata of a table it has just dropped with purge=false, which OpenHouse purges anyway (see IcebergRestTableWriteAdapter#dropTable), so the file it names is already gone";

  private static final String DECLINES_PARTITION_EVOLUTION =
      "OpenHouse declines partition-spec evolution on an existing table (OpenHouseInternalRepositoryImpl.checkPartitionSpecEvolution): a table's partitioning is fixed at creation, and a commit that changes it is refused with 400. The commit route reports that refusal faithfully; the declined capability is the divergence, and it is deliberate";

  private static final String DECLINES_SCHEMA_NARROWING =
      "OpenHouse declines a schema change that drops or narrows a column (BaseIcebergSchemaValidator), and this test reverts a schema or replaces a table with one that removes columns; the commit is refused with 400. The declined capability is the divergence, and it is deliberate";

  private static final String LOCATION_CHOSEN_BY_CATALOG =
      "OpenHouse allocates a table's location itself, through the cluster's storage selector, and ignores the location a create request asks for; this test asserts the created table sits at the requested location";

  private static final String FORMAT_VERSION_CHOSEN_BY_CATALOG =
      "OpenHouse creates every table at the format version its cluster is configured for and ignores the format-version a create carries; this test asserts the created table is at the requested version";

  private static final String COLUMN_DEFAULTS_NOT_STORED =
      "OpenHouse does not store Iceberg column default values (ReadBridgeStripProtection strips them), so the created table's schema comes back without the default this test sets";

  private static final String INTERMEDIATE_SCHEMAS_NOT_KEPT =
      "OpenHouse keeps a table's intermediate schema history only for replica tables, so a create transaction that adds two schemas lands as one and the current schema id is 0 rather than 1";

  private static final String CONCURRENT_COMMIT_DELETES_A_COLUMN =
      "the conflict this test sets up is created by deleting a column, and OpenHouse declines that (DECLINES_SCHEMA_NARROWING): the concurrent commit is refused with 400 before the stale commit under test is ever sent. The refusal arrives from the applier rather than from BaseIcebergSchemaValidator -- a 1.11 client sends add-schema with no last-column-id and the 1.5.2 applier reads the narrowed schema's own highest field id, so the update is rejected as 'Invalid last column ID: 1 < 2' -- but either way the commit does not land. The stale-commit half of this test is exercised, and passes, in testUpdateTableSchemaAssignmentConflict, whose concurrent commit adds a column instead";

  @BeforeAll
  static void startServerAndCatalog() {
    System.setProperty(FACADE_ENABLED_FLAG, "true");
    System.setProperty(NAMESPACE_MAX_DEPTH_FLAG, NAMESPACE_MAX_DEPTH);
    server = new OpenHouseLocalServer();
    server.start();
    authToken = readDummyToken();
    restCatalog = buildRestCatalog("openhouse", Collections.emptyMap());
  }

  @AfterAll
  static void stopServerAndCatalog() throws IOException {
    try {
      if (restCatalog != null) {
        restCatalog.close();
      }
    } finally {
      if (server != null) {
        server.stop();
      }
      System.clearProperty(FACADE_ENABLED_FLAG);
      System.clearProperty(NAMESPACE_MAX_DEPTH_FLAG);
    }
  }

  /**
   * {@link CatalogTests} assumes a catalog that starts each test empty, and every namespace test
   * asserts as much before it does anything. The server here is shared across the class, so what a
   * test creates is dropped again once it finishes -- tables first, because a namespace with
   * occupants cannot be dropped and one leaked namespace fails every test that runs after it.
   */
  @AfterEach
  void dropTablesAndNamespacesCreatedByTest() {
    for (Namespace namespace : restCatalog.listNamespaces()) {
      dropTree(namespace);
    }
  }

  /**
   * Depth-first, because a namespace with children cannot be dropped. Dropping only the roots was
   * enough while namespaces were single-level; with nesting on it would leave every child of every
   * failed root behind, and the next test asserts on an empty catalog.
   *
   * <p>Tables go before children, because a namespace with occupants cannot be dropped either --
   * and the write routes mean a test can now leave one behind at any level, not just at a root.
   */
  private static void dropTree(Namespace namespace) {
    for (TableIdentifier table : restCatalog.listTables(namespace)) {
      try {
        restCatalog.dropTable(table, /*purge*/ false);
      } catch (RuntimeException e) {
        // Left for the next test's own assertions to report, which they do far more precisely
        // than a failure here would.
      }
    }
    try {
      for (Namespace child : restCatalog.listNamespaces(namespace)) {
        dropTree(child);
      }
      restCatalog.dropNamespace(namespace);
    } catch (RuntimeException e) {
      // A namespace that is not empty belongs to a test that left occupants behind; the next
      // test's own assertions will report that far more precisely than a failure here would.
    }
  }

  @Override
  protected RESTCatalog catalog() {
    return restCatalog;
  }

  @Override
  protected RESTCatalog initCatalog(String catalogName, Map<String, String> additionalProperties) {
    return buildRestCatalog(catalogName, additionalProperties);
  }

  /** Namespaces are stored objects: a table's namespace has to be created before the table is. */
  @Override
  protected boolean requiresNamespaceCreate() {
    return true;
  }

  /** Stored namespaces carry a user-settable property map. */
  @Override
  protected boolean supportsNamespaceProperties() {
    return true;
  }

  /**
   * The server under test is booted with {@code cluster.tables.namespace.max-depth} above 1, so
   * {@code testListNestedNamespaces} and {@code testDropNamespaceWithNestedNamespace} execute
   * rather than skip themselves on this flag.
   */
  @Override
  protected boolean supportsNestedNamespaces() {
    return true;
  }

  @Override
  protected boolean supportsServerSideRetry() {
    return false;
  }

  /** OpenHouse identifiers are restricted to [A-Za-z0-9_]. */
  @Override
  protected boolean supportsNamesWithSlashes() {
    return false;
  }

  /** OpenHouse identifiers are restricted to [A-Za-z0-9_]. */
  @Override
  protected boolean supportsNamesWithDot() {
    return false;
  }

  /**
   * The catalog-level table properties {@link CatalogTests}'s default- and override-property tests
   * assert about. They are configuration of the catalog under test, not of the server: the Iceberg
   * REST client reads {@code table-default.*} and {@code table-override.*} out of its own merged
   * properties (client-supplied ones merged over whatever {@code GET /v1/config} returned) and puts
   * them into the create request it sends. Iceberg's own REST harness ({@code
   * TestRESTCatalog#initCatalog}) configures exactly these five, and a catalog that does not carry
   * them cannot satisfy the four tests that name their values -- there is nowhere else for {@code
   * catalog-default-key1} to come from.
   *
   * <p>{@code override-key3} deliberately appears in both maps: the suite uses it to pin that an
   * override outranks a default of the same name.
   */
  private static final Map<String, String> CATALOG_LEVEL_TABLE_PROPERTIES =
      Map.of(
          CatalogProperties.TABLE_DEFAULT_PREFIX + "default-key1", "catalog-default-key1",
          CatalogProperties.TABLE_DEFAULT_PREFIX + "default-key2", "catalog-default-key2",
          CatalogProperties.TABLE_DEFAULT_PREFIX + "override-key3", "catalog-default-key3",
          CatalogProperties.TABLE_OVERRIDE_PREFIX + "override-key3", "catalog-override-key3",
          CatalogProperties.TABLE_OVERRIDE_PREFIX + "override-key4", "catalog-override-key4");

  private static RESTCatalog buildRestCatalog(
      String catalogName, Map<String, String> additionalProperties) {
    RESTCatalog catalog = new RESTCatalog();
    Map<String, String> properties = new HashMap<>();
    properties.put(CatalogProperties.URI, "http://localhost:" + server.getPort());
    properties.put(CatalogProperties.WAREHOUSE_LOCATION, "openhouse");
    properties.putAll(CATALOG_LEVEL_TABLE_PROPERTIES);
    if (authToken != null) {
      properties.put("token", authToken);
      properties.put("header.Authorization", "Bearer " + authToken);
    }
    properties.putAll(additionalProperties);
    catalog.initialize(catalogName, properties);
    return catalog;
  }

  private static String readDummyToken() {
    try (InputStream stream =
        OpenHouseIcebergRestCatalogTests.class
            .getClassLoader()
            .getResourceAsStream("dummy.token")) {
      if (stream == null) {
        return null;
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Tests this facade cannot satisfy. Each @Disabled reason names the capability OpenHouse
  // declines and what it does instead.
  // ---------------------------------------------------------------------------------------------

  @Override
  @Disabled(DECLINES_REGISTER_TABLE)
  @Test
  public void testRegisterTable() {
    super.testRegisterTable();
  }

  @Override
  @Disabled(DECLINES_REGISTER_TABLE)
  @Test
  public void testRegisterExistingTable() {
    super.testRegisterExistingTable();
  }

  @Override
  @Disabled(DECLINES_PARTITION_EVOLUTION)
  @Test
  public void testUpdateTableSpec() {
    super.testUpdateTableSpec();
  }

  @Override
  @Disabled(DECLINES_PARTITION_EVOLUTION)
  @Test
  public void testUpdateTableSpecConflict() {
    super.testUpdateTableSpecConflict();
  }

  @Override
  @Disabled(DECLINES_PARTITION_EVOLUTION)
  @Test
  public void testUpdateTableSpecThenRevert() {
    super.testUpdateTableSpecThenRevert();
  }

  @Override
  @Disabled(DECLINES_PARTITION_EVOLUTION)
  @Test
  public void testUpdateTableAssignmentSpecConflict() {
    super.testUpdateTableAssignmentSpecConflict();
  }

  @Override
  @Disabled(DECLINES_PARTITION_EVOLUTION)
  @Test
  public void testUpdateTransaction() {
    super.testUpdateTransaction();
  }

  @Override
  @Disabled(DECLINES_PARTITION_EVOLUTION)
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testRemoveUnusedSpec(boolean withBranch) {
    super.testRemoveUnusedSpec(withBranch);
  }

  @Override
  @Disabled(DECLINES_PARTITION_EVOLUTION)
  @Test
  public void testConcurrentReplaceTransactionPartitionSpec() {
    super.testConcurrentReplaceTransactionPartitionSpec();
  }

  @Override
  @Disabled(DECLINES_PARTITION_EVOLUTION)
  @Test
  public void testConcurrentReplaceTransactionPartitionSpec2() {
    super.testConcurrentReplaceTransactionPartitionSpec2();
  }

  @Override
  @Disabled(DECLINES_PARTITION_EVOLUTION)
  @Test
  public void testCompleteReplaceTransaction() {
    super.testCompleteReplaceTransaction();
  }

  @Override
  @Disabled(DECLINES_PARTITION_EVOLUTION)
  @Test
  public void testCompleteCreateOrReplaceTransactionReplace() {
    super.testCompleteCreateOrReplaceTransactionReplace();
  }

  @Override
  @Disabled(DECLINES_SCHEMA_NARROWING)
  @Test
  public void testUpdateTableSchemaThenRevert() {
    super.testUpdateTableSchemaThenRevert();
  }

  @Override
  @Disabled(DECLINES_SCHEMA_NARROWING)
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testRemoveUnusedSchemas(boolean withBranch) {
    super.testRemoveUnusedSchemas(withBranch);
  }

  @Override
  @Disabled(DECLINES_SCHEMA_NARROWING)
  @Test
  public void testConcurrentReplaceTransactionSchema() {
    super.testConcurrentReplaceTransactionSchema();
  }

  @Override
  @Disabled(DECLINES_SCHEMA_NARROWING)
  @Test
  public void testConcurrentReplaceTransactionSchema2() {
    super.testConcurrentReplaceTransactionSchema2();
  }

  @Override
  @Disabled(DECLINES_SCHEMA_NARROWING)
  @Test
  public void testCreateOrReplaceReplaceTransactionReplace() {
    super.testCreateOrReplaceReplaceTransactionReplace();
  }

  @Override
  @Disabled(DECLINES_SCHEMA_NARROWING)
  @Test
  public void testReplaceTransaction() {
    super.testReplaceTransaction();
  }

  @Override
  @Disabled(DECLINES_SCHEMA_NARROWING)
  @Test
  public void testDefaultTablePropertiesReplaceTransaction() {
    super.testDefaultTablePropertiesReplaceTransaction();
  }

  @Override
  @Disabled(DECLINES_SCHEMA_NARROWING)
  @Test
  public void testOverrideTablePropertiesReplaceTransaction() {
    super.testOverrideTablePropertiesReplaceTransaction();
  }

  @Override
  @Disabled(LOCATION_CHOSEN_BY_CATALOG)
  @Test
  public void testCompleteCreateTransaction() {
    super.testCompleteCreateTransaction();
  }

  @Override
  @Disabled(LOCATION_CHOSEN_BY_CATALOG)
  @Test
  public void testCompleteCreateTransactionV2() {
    super.testCompleteCreateTransactionV2();
  }

  @Override
  @Disabled(LOCATION_CHOSEN_BY_CATALOG)
  @Test
  public void testCompleteCreateOrReplaceTransactionCreate() {
    super.testCompleteCreateOrReplaceTransactionCreate();
  }

  @Override
  @Disabled(FORMAT_VERSION_CHOSEN_BY_CATALOG)
  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3})
  public void createTableTransaction(int formatVersion) {
    super.createTableTransaction(formatVersion);
  }

  @Override
  @Disabled(FORMAT_VERSION_CHOSEN_BY_CATALOG)
  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  public void replaceTableTransaction(int formatVersion) {
    super.replaceTableTransaction(formatVersion);
  }

  @Override
  @Disabled(COLUMN_DEFAULTS_NOT_STORED)
  @Test
  public void testCreateTableWithDefaultColumnValue() {
    super.testCreateTableWithDefaultColumnValue();
  }

  @Override
  @Disabled(INTERMEDIATE_SCHEMAS_NOT_KEPT)
  @Test
  public void testCompleteCreateTransactionMultipleSchemas() {
    super.testCompleteCreateTransactionMultipleSchemas();
  }

  @Override
  @Disabled(CONCURRENT_COMMIT_DELETES_A_COLUMN)
  @Test
  public void testUpdateTableSchemaConflict() {
    super.testUpdateTableSchemaConflict();
  }
}
