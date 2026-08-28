package com.linkedin.openhouse.tables.icebergrestcompat;

import com.linkedin.openhouse.tablestest.OpenHouseLocalServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.catalog.CatalogTests;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
 * <p>The facade currently implements four endpoints: {@code GET /v1/config}, list tables, load
 * table, and table exists (HEAD). {@link CatalogTests} has 101 test methods and almost all of them
 * create namespaces or tables through the catalog under test, which a read-only facade cannot do.
 * Every test the facade cannot honestly satisfy is therefore overridden and disabled with a reason
 * naming the missing endpoint or blocking defect; the set of {@code @Disabled} reasons below is the
 * implementation roadmap for the facade. Five further tests skip themselves through the suite's
 * built-in capability flags ({@code supportsEmptyNamespace()}, {@code requiresNamespaceCreate()}).
 * As endpoints are added to the facade, deleting the corresponding overrides re-enables the
 * reference tests for them.
 */
public class OpenHouseIcebergRestCatalogTests extends CatalogTests<RESTCatalog> {

  private static final String FACADE_ENABLED_FLAG = "cluster.tables.iceberg-rest.enabled";

  private static OpenHouseLocalServer server;
  private static RESTCatalog restCatalog;
  private static String authToken;

  private static final String NEEDS_CREATE_NAMESPACE =
      "needs createNamespace endpoint (POST /v1/{prefix}/namespaces); the read-only facade exposes no namespace endpoints";

  private static final String NEEDS_LIST_NAMESPACES =
      "needs listNamespaces endpoint (GET /v1/{prefix}/namespaces); when it is not advertised the 1.11 client short-circuits listNamespaces() to an empty list, so namespace assertions cannot run";

  private static final String NEEDS_LOAD_NAMESPACE =
      "needs loadNamespaceMetadata endpoint (GET /v1/{prefix}/namespaces/{namespace}) plus createNamespace for setup";

  private static final String NEEDS_NAMESPACE_PROPERTIES =
      "needs updateNamespaceProperties endpoint (POST /v1/{prefix}/namespaces/{namespace}/properties) plus createNamespace for setup";

  private static final String NEEDS_DROP_NAMESPACE =
      "needs dropNamespace endpoint (DELETE /v1/{prefix}/namespaces/{namespace}) plus createNamespace for setup";

  private static final String NEEDS_CREATE_TABLE =
      "needs createTable endpoint (POST /v1/{prefix}/namespaces/{namespace}/tables) to create the fixture table";

  private static final String NEEDS_COMMIT_TABLE =
      "needs commitTable endpoint (POST /v1/{prefix}/namespaces/{namespace}/tables/{table}) plus createTable for setup";

  private static final String NEEDS_TRANSACTIONS =
      "needs staged createTable (POST /v1/{prefix}/namespaces/{namespace}/tables with stage-create) and commitTransaction (POST /v1/{prefix}/transactions/commit) endpoints";

  private static final String NEEDS_RENAME_TABLE =
      "needs renameTable endpoint (POST /v1/{prefix}/tables/rename); when it is not advertised the 1.11 client throws UnsupportedOperationException before reaching the server";

  private static final String NEEDS_DROP_TABLE =
      "needs dropTable endpoint (DELETE /v1/{prefix}/namespaces/{namespace}/tables/{table}); when it is not advertised the 1.11 client throws UnsupportedOperationException instead of returning false";

  private static final String NEEDS_REGISTER_TABLE =
      "needs registerTable endpoint (POST /v1/{prefix}/namespaces/{namespace}/register) plus createTable for setup";

  private static final String NEEDS_METRICS_ENDPOINT =
      "needs createTable for setup and the metrics endpoint (POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/metrics) to receive scan reports";

  private static final String BLOCKED_IDENTIFIER_CHARSET =
      "blocked by known facade defect: OpenHouse restricts database identifiers to [A-Za-z0-9_], so the 'non-existing' namespace used by this test is rejected with 400 (IllegalArgumentException) instead of 404 (NoSuchTableException)";

  private static final String NEEDS_CREATE_TABLE_AND_LIST_FIX =
      "needs createNamespace/createTable endpoints for setup; additionally blocked by known facade defect: the empty pageToken sent by every Iceberg Java client since 1.6.0 is rejected with 400, so RESTCatalog.listTables() cannot succeed against this facade";

  @BeforeAll
  static void startServerAndCatalog() {
    System.setProperty(FACADE_ENABLED_FLAG, "true");
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

  /** OpenHouse databases are implicit; the facade has no createNamespace endpoint. */
  @Override
  protected boolean requiresNamespaceCreate() {
    return false;
  }

  /** OpenHouse namespaces carry no user-settable properties over this facade. */
  @Override
  protected boolean supportsNamespaceProperties() {
    return false;
  }

  /** OpenHouse database identifiers are single-level. */
  @Override
  protected boolean supportsNestedNamespaces() {
    return false;
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

  private static RESTCatalog buildRestCatalog(
      String catalogName, Map<String, String> additionalProperties) {
    RESTCatalog catalog = new RESTCatalog();
    Map<String, String> properties = new HashMap<>();
    properties.put(CatalogProperties.URI, "http://localhost:" + server.getPort());
    properties.put(CatalogProperties.WAREHOUSE_LOCATION, "openhouse");
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
  // Tests the read-only four-endpoint facade cannot satisfy. Each @Disabled reason names the
  // missing endpoint or the blocking defect; together they are the facade's endpoint roadmap.
  // ---------------------------------------------------------------------------------------------

  @Override
  @Disabled(NEEDS_CREATE_NAMESPACE)
  @Test
  public void testCreateNamespace() {
    super.testCreateNamespace();
  }

  @Override
  @Disabled(NEEDS_CREATE_NAMESPACE)
  @Test
  public void testCreateExistingNamespace() {
    super.testCreateExistingNamespace();
  }

  @Override
  @Disabled(NEEDS_CREATE_NAMESPACE)
  @Test
  public void testCreateNamespaceWithProperties() {
    super.testCreateNamespaceWithProperties();
  }

  @Override
  @Disabled(NEEDS_LOAD_NAMESPACE)
  @Test
  public void testLoadNamespaceMetadata() {
    super.testLoadNamespaceMetadata();
  }

  @Override
  @Disabled(NEEDS_NAMESPACE_PROPERTIES)
  @Test
  public void testSetNamespaceProperties() {
    super.testSetNamespaceProperties();
  }

  @Override
  @Disabled(NEEDS_NAMESPACE_PROPERTIES)
  @Test
  public void testUpdateNamespaceProperties() {
    super.testUpdateNamespaceProperties();
  }

  @Override
  @Disabled(NEEDS_NAMESPACE_PROPERTIES)
  @Test
  public void testUpdateAndSetNamespaceProperties() {
    super.testUpdateAndSetNamespaceProperties();
  }

  @Override
  @Disabled(NEEDS_NAMESPACE_PROPERTIES)
  @Test
  public void testSetNamespacePropertiesNamespaceDoesNotExist() {
    super.testSetNamespacePropertiesNamespaceDoesNotExist();
  }

  @Override
  @Disabled(NEEDS_NAMESPACE_PROPERTIES)
  @Test
  public void testRemoveNamespaceProperties() {
    super.testRemoveNamespaceProperties();
  }

  @Override
  @Disabled(NEEDS_NAMESPACE_PROPERTIES)
  @Test
  public void testRemoveNamespacePropertiesNamespaceDoesNotExist() {
    super.testRemoveNamespacePropertiesNamespaceDoesNotExist();
  }

  @Override
  @Disabled(NEEDS_DROP_NAMESPACE)
  @Test
  public void testDropNamespace() {
    super.testDropNamespace();
  }

  @Override
  @Disabled(NEEDS_DROP_NAMESPACE)
  @Test
  public void testDropNonexistentNamespace() {
    super.testDropNonexistentNamespace();
  }

  @Override
  @Disabled(NEEDS_DROP_NAMESPACE)
  @Test
  public void testDropNonEmptyNamespace() {
    super.testDropNonEmptyNamespace();
  }

  @Override
  @Disabled(NEEDS_DROP_NAMESPACE)
  @Test
  public void testDropNamespaceWithNestedNamespace() {
    super.testDropNamespaceWithNestedNamespace();
  }

  @Override
  @Disabled(NEEDS_LIST_NAMESPACES)
  @Test
  public void testListNamespaces() {
    super.testListNamespaces();
  }

  @Override
  @Disabled(NEEDS_LIST_NAMESPACES)
  @Test
  public void testListNestedNamespaces() {
    super.testListNestedNamespaces();
  }

  @Override
  @Disabled(NEEDS_CREATE_NAMESPACE)
  @Test
  public void testNamespaceWithSlash() {
    super.testNamespaceWithSlash();
  }

  @Override
  @Disabled(NEEDS_CREATE_NAMESPACE)
  @Test
  public void testNamespaceWithDot() {
    super.testNamespaceWithDot();
  }

  @Override
  @Disabled(NEEDS_CREATE_TABLE)
  @Test
  public void testBasicCreateTable() {
    super.testBasicCreateTable();
  }

  @Override
  @Disabled(NEEDS_CREATE_TABLE)
  @Test
  public void testTableNameWithSlash() {
    super.testTableNameWithSlash();
  }

  @Override
  @Disabled(NEEDS_CREATE_TABLE)
  @Test
  public void testTableNameWithDot() {
    super.testTableNameWithDot();
  }

  @Override
  @Disabled(NEEDS_CREATE_TABLE)
  @Test
  public void testBasicCreateTableThatAlreadyExists() {
    super.testBasicCreateTableThatAlreadyExists();
  }

  @Override
  @Disabled(NEEDS_CREATE_TABLE)
  @Test
  public void testCompleteCreateTable() {
    super.testCompleteCreateTable();
  }

  @Override
  @Disabled(NEEDS_CREATE_TABLE)
  @Test
  public void testDefaultTableProperties() {
    super.testDefaultTableProperties();
  }

  @Override
  @Disabled(NEEDS_TRANSACTIONS)
  @Test
  public void testDefaultTablePropertiesCreateTransaction() {
    super.testDefaultTablePropertiesCreateTransaction();
  }

  @Override
  @Disabled(NEEDS_TRANSACTIONS)
  @Test
  public void testDefaultTablePropertiesReplaceTransaction() {
    super.testDefaultTablePropertiesReplaceTransaction();
  }

  @Override
  @Disabled(NEEDS_CREATE_TABLE)
  @Test
  public void testOverrideTableProperties() {
    super.testOverrideTableProperties();
  }

  @Override
  @Disabled(NEEDS_TRANSACTIONS)
  @Test
  public void testOverrideTablePropertiesCreateTransaction() {
    super.testOverrideTablePropertiesCreateTransaction();
  }

  @Override
  @Disabled(NEEDS_TRANSACTIONS)
  @Test
  public void testOverrideTablePropertiesReplaceTransaction() {
    super.testOverrideTablePropertiesReplaceTransaction();
  }

  @Override
  @Disabled(NEEDS_CREATE_TABLE)
  @Test
  public void testCreateTableWithDefaultColumnValue() {
    super.testCreateTableWithDefaultColumnValue();
  }

  @Override
  @Disabled(NEEDS_CREATE_TABLE)
  @Test
  public void testLoadTable() {
    super.testLoadTable();
  }

  @Override
  @Disabled(BLOCKED_IDENTIFIER_CHARSET)
  @Test
  public void testLoadTableWithNonExistingNamespace() {
    super.testLoadTableWithNonExistingNamespace();
  }

  @Override
  @Disabled(NEEDS_CREATE_TABLE)
  @Test
  public void testLoadMetadataTable() {
    super.testLoadMetadataTable();
  }

  @Override
  @Disabled(NEEDS_CREATE_TABLE)
  @Test
  public void testLoadTableWithMissingMetadataFile(@TempDir Path tempDir) throws IOException {
    super.testLoadTableWithMissingMetadataFile(tempDir);
  }

  @Override
  @Disabled(NEEDS_RENAME_TABLE)
  @Test
  public void testRenameTable() {
    super.testRenameTable();
  }

  @Override
  @Disabled(NEEDS_CREATE_TABLE)
  @Test
  public void createTableInUniqueLocation() {
    super.createTableInUniqueLocation();
  }

  @Override
  @Disabled(NEEDS_RENAME_TABLE)
  @Test
  public void dropAfterRenameDoesntCorruptTable() throws IOException {
    super.dropAfterRenameDoesntCorruptTable();
  }

  @Override
  @Disabled(NEEDS_RENAME_TABLE)
  @Test
  public void testRenameTableMissingSourceTable() {
    super.testRenameTableMissingSourceTable();
  }

  @Override
  @Disabled(NEEDS_RENAME_TABLE)
  @Test
  public void renameTableNamespaceMissing() {
    super.renameTableNamespaceMissing();
  }

  @Override
  @Disabled(NEEDS_RENAME_TABLE)
  @Test
  public void testRenameTableDestinationTableAlreadyExists() {
    super.testRenameTableDestinationTableAlreadyExists();
  }

  @Override
  @Disabled(NEEDS_DROP_TABLE)
  @Test
  public void testDropTable() {
    super.testDropTable();
  }

  @Override
  @Disabled(NEEDS_DROP_TABLE)
  @Test
  public void testDropTableWithPurge() {
    super.testDropTableWithPurge();
  }

  @Override
  @Disabled(NEEDS_DROP_TABLE)
  @Test
  public void testDropTableWithoutPurge() {
    super.testDropTableWithoutPurge();
  }

  @Override
  @Disabled(NEEDS_DROP_TABLE)
  @Test
  public void testDropMissingTable() {
    super.testDropMissingTable();
  }

  @Override
  @Disabled(NEEDS_CREATE_TABLE_AND_LIST_FIX)
  @Test
  public void testListTables() {
    super.testListTables();
  }

  @Override
  @Disabled(NEEDS_LIST_NAMESPACES)
  @Test
  public void testListNonExistingNamespace() {
    super.testListNonExistingNamespace();
  }

  @Override
  @Disabled(NEEDS_COMMIT_TABLE)
  @Test
  public void testUpdateTableSchema() {
    super.testUpdateTableSchema();
  }

  @Override
  @Disabled(NEEDS_CREATE_TABLE)
  @Test
  public void testUUIDValidation() {
    super.testUUIDValidation();
  }

  @Override
  @Disabled(NEEDS_COMMIT_TABLE)
  @Test
  public void testUpdateTableSchemaServerSideRetry() {
    super.testUpdateTableSchemaServerSideRetry();
  }

  @Override
  @Disabled(NEEDS_COMMIT_TABLE)
  @Test
  public void testUpdateTableSchemaConflict() {
    super.testUpdateTableSchemaConflict();
  }

  @Override
  @Disabled(NEEDS_COMMIT_TABLE)
  @Test
  public void testUpdateTableSchemaAssignmentConflict() {
    super.testUpdateTableSchemaAssignmentConflict();
  }

  @Override
  @Disabled(NEEDS_COMMIT_TABLE)
  @Test
  public void testUpdateTableSchemaThenRevert() {
    super.testUpdateTableSchemaThenRevert();
  }

  @Override
  @Disabled(NEEDS_COMMIT_TABLE)
  @Test
  public void testUpdateTableSpec() {
    super.testUpdateTableSpec();
  }

  @Override
  @Disabled(NEEDS_COMMIT_TABLE)
  @Test
  public void testUpdateTableSpecServerSideRetry() {
    super.testUpdateTableSpecServerSideRetry();
  }

  @Override
  @Disabled(NEEDS_COMMIT_TABLE)
  @Test
  public void testUpdateTableSpecConflict() {
    super.testUpdateTableSpecConflict();
  }

  @Override
  @Disabled(NEEDS_COMMIT_TABLE)
  @Test
  public void testUpdateTableAssignmentSpecConflict() {
    super.testUpdateTableAssignmentSpecConflict();
  }

  @Override
  @Disabled(NEEDS_COMMIT_TABLE)
  @Test
  public void testUpdateTableSpecThenRevert() {
    super.testUpdateTableSpecThenRevert();
  }

  @Override
  @Disabled(NEEDS_COMMIT_TABLE)
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testRemoveUnusedSpec(boolean withBranch) {
    super.testRemoveUnusedSpec(withBranch);
  }

  @Override
  @Disabled(NEEDS_COMMIT_TABLE)
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testRemoveUnusedSchemas(boolean withBranch) {
    super.testRemoveUnusedSchemas(withBranch);
  }

  @Override
  @Disabled(NEEDS_COMMIT_TABLE)
  @Test
  public void testUpdateTableSortOrder() {
    super.testUpdateTableSortOrder();
  }

  @Override
  @Disabled(NEEDS_COMMIT_TABLE)
  @Test
  public void testUpdateTableSortOrderServerSideRetry() {
    super.testUpdateTableSortOrderServerSideRetry();
  }

  @Override
  @Disabled(NEEDS_COMMIT_TABLE)
  @Test
  public void testUpdateTableOrderThenRevert() {
    super.testUpdateTableOrderThenRevert();
  }

  @Override
  @Disabled(NEEDS_COMMIT_TABLE)
  @Test
  public void testAppend() throws IOException {
    super.testAppend();
  }

  @Override
  @Disabled(NEEDS_COMMIT_TABLE)
  @Test
  public void testConcurrentAppendEmptyTable() {
    super.testConcurrentAppendEmptyTable();
  }

  @Override
  @Disabled(NEEDS_COMMIT_TABLE)
  @Test
  public void testConcurrentAppendNonEmptyTable() {
    super.testConcurrentAppendNonEmptyTable();
  }

  @Override
  @Disabled(NEEDS_COMMIT_TABLE)
  @Test
  public void testUpdateTransaction() {
    super.testUpdateTransaction();
  }

  @Override
  @Disabled(NEEDS_TRANSACTIONS)
  @Test
  public void testCreateTransaction() {
    super.testCreateTransaction();
  }

  @Override
  @Disabled(NEEDS_TRANSACTIONS)
  @Test
  public void testCompleteCreateTransaction() {
    super.testCompleteCreateTransaction();
  }

  @Override
  @Disabled(NEEDS_TRANSACTIONS)
  @Test
  public void testCompleteCreateTransactionMultipleSchemas() {
    super.testCompleteCreateTransactionMultipleSchemas();
  }

  @Override
  @Disabled(NEEDS_TRANSACTIONS)
  @Test
  public void testCompleteCreateTransactionV2() {
    super.testCompleteCreateTransactionV2();
  }

  @Override
  @Disabled(NEEDS_TRANSACTIONS)
  @Test
  public void testConcurrentCreateTransaction() {
    super.testConcurrentCreateTransaction();
  }

  @Override
  @Disabled(NEEDS_TRANSACTIONS)
  @Test
  public void testCreateOrReplaceTransactionCreate() {
    super.testCreateOrReplaceTransactionCreate();
  }

  @Override
  @Disabled(NEEDS_TRANSACTIONS)
  @Test
  public void testCompleteCreateOrReplaceTransactionCreate() {
    super.testCompleteCreateOrReplaceTransactionCreate();
  }

  @Override
  @Disabled(NEEDS_TRANSACTIONS)
  @Test
  public void testCreateOrReplaceReplaceTransactionReplace() {
    super.testCreateOrReplaceReplaceTransactionReplace();
  }

  @Override
  @Disabled(NEEDS_TRANSACTIONS)
  @Test
  public void testCompleteCreateOrReplaceTransactionReplace() {
    super.testCompleteCreateOrReplaceTransactionReplace();
  }

  @Override
  @Disabled(NEEDS_TRANSACTIONS)
  @Test
  public void testCreateOrReplaceTransactionConcurrentCreate() {
    super.testCreateOrReplaceTransactionConcurrentCreate();
  }

  @Override
  @Disabled(NEEDS_TRANSACTIONS)
  @Test
  public void testReplaceTransaction() {
    super.testReplaceTransaction();
  }

  @Override
  @Disabled(NEEDS_TRANSACTIONS)
  @Test
  public void testCompleteReplaceTransaction() {
    super.testCompleteReplaceTransaction();
  }

  @Override
  @Disabled(NEEDS_TRANSACTIONS)
  @Test
  public void testReplaceTransactionRequiresTableExists() {
    super.testReplaceTransactionRequiresTableExists();
  }

  @Override
  @Disabled(NEEDS_TRANSACTIONS)
  @Test
  public void testReplaceTableKeepsSnapshotLog() {
    super.testReplaceTableKeepsSnapshotLog();
  }

  @Override
  @Disabled(NEEDS_TRANSACTIONS)
  @Test
  public void testConcurrentReplaceTransactions() {
    super.testConcurrentReplaceTransactions();
  }

  @Override
  @Disabled(NEEDS_TRANSACTIONS)
  @Test
  public void testConcurrentReplaceTransactionSchema() {
    super.testConcurrentReplaceTransactionSchema();
  }

  @Override
  @Disabled(NEEDS_TRANSACTIONS)
  @Test
  public void testConcurrentReplaceTransactionSchema2() {
    super.testConcurrentReplaceTransactionSchema2();
  }

  @Override
  @Disabled(NEEDS_TRANSACTIONS)
  @Test
  public void testConcurrentReplaceTransactionSchemaConflict() {
    super.testConcurrentReplaceTransactionSchemaConflict();
  }

  @Override
  @Disabled(NEEDS_TRANSACTIONS)
  @Test
  public void testConcurrentReplaceTransactionPartitionSpec() {
    super.testConcurrentReplaceTransactionPartitionSpec();
  }

  @Override
  @Disabled(NEEDS_TRANSACTIONS)
  @Test
  public void testConcurrentReplaceTransactionPartitionSpec2() {
    super.testConcurrentReplaceTransactionPartitionSpec2();
  }

  @Override
  @Disabled(NEEDS_TRANSACTIONS)
  @Test
  public void testConcurrentReplaceTransactionPartitionSpecConflict() {
    super.testConcurrentReplaceTransactionPartitionSpecConflict();
  }

  @Override
  @Disabled(NEEDS_TRANSACTIONS)
  @Test
  public void testConcurrentReplaceTransactionSortOrder() {
    super.testConcurrentReplaceTransactionSortOrder();
  }

  @Override
  @Disabled(NEEDS_TRANSACTIONS)
  @Test
  public void testConcurrentReplaceTransactionSortOrderConflict() {
    super.testConcurrentReplaceTransactionSortOrderConflict();
  }

  @Override
  @Disabled(NEEDS_TRANSACTIONS)
  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3})
  public void createTableTransaction(int formatVersion) {
    super.createTableTransaction(formatVersion);
  }

  @Override
  @Disabled(NEEDS_TRANSACTIONS)
  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  public void replaceTableTransaction(int formatVersion) {
    super.replaceTableTransaction(formatVersion);
  }

  @Override
  @Disabled(NEEDS_COMMIT_TABLE)
  @Test
  public void testMetadataFileLocationsRemovalAfterCommit() {
    super.testMetadataFileLocationsRemovalAfterCommit();
  }

  @Override
  @Disabled(NEEDS_REGISTER_TABLE)
  @Test
  public void testRegisterTable() {
    super.testRegisterTable();
  }

  @Override
  @Disabled(NEEDS_REGISTER_TABLE)
  @Test
  public void testRegisterExistingTable() {
    super.testRegisterExistingTable();
  }

  @Override
  @Disabled(NEEDS_METRICS_ENDPOINT)
  @Test
  public void testCatalogWithCustomMetricsReporter() throws IOException {
    super.testCatalogWithCustomMetricsReporter();
  }
}
