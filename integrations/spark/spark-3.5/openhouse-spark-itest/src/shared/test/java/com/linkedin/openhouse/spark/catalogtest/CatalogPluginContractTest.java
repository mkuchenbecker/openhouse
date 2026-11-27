package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.common.security.DummyTokenInterceptor;
import com.linkedin.openhouse.gen.tables.client.api.TableApi;
import com.linkedin.openhouse.gen.tables.client.invoker.ApiClient;
import com.linkedin.openhouse.gen.tables.client.model.CreateUpdateLockRequestBody;
import com.linkedin.openhouse.javaclient.IcebergLine;
import com.linkedin.openhouse.javaclient.api.SupportsGrantRevoke;
import com.linkedin.openhouse.javaclient.exception.WebClientResponseWithMessageException;
import com.linkedin.openhouse.tables.authorization.AuthorizationHandler;
import com.linkedin.openhouse.tables.authorization.OpaAuthorizationHandler;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import com.linkedin.openhouse.tablestest.SparkItestColumnDefaults;
import com.linkedin.openhouse.tablestest.SpringH2TestApplication;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.SupportsNamespaces;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.hadoop.HadoopFileIO;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.types.Types;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Black-box contract of the engine-side catalog plugin, observed through the public Iceberg API
 * against the real embedded tables service. Every assertion records what the plugin does today; it
 * is not a statement of what it should do.
 *
 * <p>The catalog path is a parameter, as in the Spark SQL smoke suite: {@link #catalogPath()} names
 * the path and {@link #sparkSession()}, {@link #engineCatalogImpl()} and {@link #javaCatalogImpl()}
 * are the only places that turn it into configuration. This class runs the {@link #LEGACY} path
 * (the only path on the Iceberg 1.2 / Spark 3.1 line); the Spark 3.5 module adds a subclass for the
 * REST-shaped path.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CatalogPluginContractTest extends OpenHouseSparkITest {

  private static final Schema SCHEMA =
      new Schema(
          Types.NestedField.required(1, "id", Types.LongType.get()),
          Types.NestedField.optional(2, "data", Types.StringType.get()));
  private static final String CAPABILITY_PROP = "required-capability.column-defaults";
  private static final String AUTH_DB = "cpc_auth_db";
  private static final String OWNER = "cpc_owner";
  private static final String READER = "cpc_reader";
  private static final String STRANGER = "cpc_stranger";

  /** The catalog path in use today. */
  protected static final String LEGACY = "legacy";

  /** Iceberg's RESTCatalog over the in-process REST-shaped client (Iceberg 1.5 line only). */
  protected static final String REST_SHIM = "rest-shim";

  private SparkSession spark;
  private Catalog catalog;
  private ConfigurableApplicationContext authServer;
  private URI authServerUri;

  /** The catalog path under test. */
  protected String catalogPath() {
    return LEGACY;
  }

  /** The engine (Spark) catalog class for the path. */
  protected String engineCatalogImpl() {
    switch (catalogPath()) {
      case LEGACY:
        return "com.linkedin.openhouse.spark.OpenHouseCatalog";
      case REST_SHIM:
        return "com.linkedin.openhouse.spark.OpenHouseRestCatalog";
      default:
        throw new IllegalArgumentException("Unknown catalog path: " + catalogPath());
    }
  }

  /** The plain Java client catalog class (declares no capabilities) for the path. */
  protected String javaCatalogImpl() {
    switch (catalogPath()) {
      case LEGACY:
        return "com.linkedin.openhouse.javaclient.OpenHouseCatalog";
      case REST_SHIM:
        return "com.linkedin.openhouse.javaclient.OpenHouseRestCatalog";
      default:
        throw new IllegalArgumentException("Unknown catalog path: " + catalogPath());
    }
  }

  /** The session whose {@code openhouse} catalog is built by {@link #engineCatalogImpl()}. */
  protected SparkSession sparkSession() throws Exception {
    SparkSession base = getSparkSession();
    switch (catalogPath()) {
      case LEGACY:
        return base;
      case REST_SHIM:
        SparkSession session = base.newSession();
        session.conf().set("spark.sql.catalog.openhouse.catalog-impl", engineCatalogImpl());
        return session;
      default:
        throw new IllegalArgumentException("Unknown catalog path: " + catalogPath());
    }
  }

  /**
   * Tables this suite creates on the shared embedded server. Each path's run drops them first so
   * the suite can run once per path in one JVM (the Spark 3.5 module runs both paths).
   */
  private static final List<TableIdentifier> CREATED_TABLES =
      Arrays.asList(
          id("cpc_ns_a", "t1"),
          id("cpc_ns_b", "t1"),
          id("cpc_create_twice", "t1"),
          id("cpc_overwrite", "t1"),
          id("cpc_props", "t1"),
          id("cpc_reserved_update", "t1"),
          id("cpc_reserved_create", "t1"),
          id("cpc_spec", "t1"),
          id("cpc_concurrent", "t1"),
          id("cpc_raw_commit", "t1"),
          id("cpc_drop", "t_nopurge"),
          id("cpc_drop", "t_purge"),
          id("cpc_rename", "t_from"),
          id("cpc_rename", "t_to"),
          id("cpc_grants", "t1"),
          id("cpc_locked", "t1"),
          id("cpc_table_handle", "t1"),
          id(SparkItestColumnDefaults.DATABASE, "cpc_capability"),
          id(SparkItestColumnDefaults.DATABASE, "cpc_capability_plain"));

  @BeforeAll
  void start() throws Exception {
    spark = sparkSession();
    catalog = getOpenHouseCatalog(spark);
    for (TableIdentifier leftover : CREATED_TABLES) {
      catalog.dropTable(leftover);
    }
  }

  @AfterAll
  void stop() {
    if (authServer != null) {
      authServer.close();
    }
    if (spark != null) {
      spark.close();
    }
  }

  // ---------------------------------------------------------------- helpers

  private static TableIdentifier id(String db, String table) {
    return TableIdentifier.of(db, table);
  }

  private static DataFile file(String path) {
    return DataFiles.builder(PartitionSpec.unpartitioned())
        .withPath(path)
        .withFileSizeInBytes(10)
        .withRecordCount(1)
        .build();
  }

  private static Set<String> dataFilePaths(Table table) {
    try (CloseableIterable<FileScanTask> tasks = table.newScan().planFiles()) {
      Set<String> paths = new HashSet<>();
      for (FileScanTask task : tasks) {
        paths.add(task.file().path().toString());
      }
      return paths;
    } catch (java.io.IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static Throwable thrown(Executable executable) {
    try {
      executable.execute();
    } catch (Throwable t) {
      return t;
    }
    throw new AssertionError("expected an exception but none was thrown");
  }

  private static void assertThrowsExactly(
      Class<? extends Throwable> type, String messagePrefix, Executable executable) {
    Throwable t = thrown(executable);
    Assertions.assertEquals(type, t.getClass(), String.valueOf(t));
    Assertions.assertTrue(
        t.getMessage() != null && t.getMessage().startsWith(messagePrefix),
        "message was: " + t.getMessage());
  }

  private static void assertWebClient(
      int status, String messagePrefix, String messageContains, Throwable t) {
    Assertions.assertEquals(
        WebClientResponseWithMessageException.class, t.getClass(), String.valueOf(t));
    Assertions.assertEquals(
        status, ((WebClientResponseWithMessageException) t).getStatusCode(), t.getMessage());
    Assertions.assertTrue(t.getMessage().startsWith(messagePrefix), t.getMessage());
    Assertions.assertTrue(t.getMessage().contains(messageContains), t.getMessage());
  }

  private static int statusOf(Throwable t) {
    Assertions.assertEquals(
        WebClientResponseWithMessageException.class, t.getClass(), String.valueOf(t));
    return ((WebClientResponseWithMessageException) t).getStatusCode();
  }

  private Catalog catalogWith(String impl, Map<String, String> extra) {
    Map<String, String> properties = new HashMap<>();
    String prefix = "spark.sql.catalog.openhouse.";
    scala.collection.JavaConverters.mapAsJavaMap(spark.conf().getAll())
        .forEach(
            (k, v) -> {
              if (k.startsWith(prefix)) {
                properties.put(k.substring(prefix.length()), v);
              }
            });
    properties.putAll(extra);
    return CatalogUtil.loadCatalog(
        impl, "openhouse", properties, spark.sparkContext().hadoopConfiguration());
  }

  // ------------------------------------------------------------- 1. namespaces

  @Test
  void listNamespacesContainsDatabasesOfCreatedTables() {
    catalog.createTable(id("cpc_ns_a", "t1"), SCHEMA);
    catalog.createTable(id("cpc_ns_b", "t1"), SCHEMA);
    List<Namespace> namespaces = ((SupportsNamespaces) catalog).listNamespaces();
    Assertions.assertTrue(namespaces.contains(Namespace.of("cpc_ns_a")), namespaces.toString());
    Assertions.assertTrue(namespaces.contains(Namespace.of("cpc_ns_b")), namespaces.toString());
  }

  // ------------------------------------------------- 2. namespace records (upstream 871)

  /**
   * Before upstream 871 every call here but listNamespaces() was refused with an
   * UnsupportedOperationException. With it, the catalog reads and writes the namespace record the
   * owner's /v1/{prefix}/namespaces routes keep (upstream 870), independent of tables:
   * namespaceExists and listNamespaces(namespace) stay refused, and listNamespaces() stays the
   * table-derived database list, so a created record is not listed. A name per path, as the paths
   * share one service.
   */
  @Test
  void namespaceOperationsUseTheNamespaceRecord() {
    SupportsNamespaces ns = (SupportsNamespaces) catalog;
    Namespace n = Namespace.of("cpc_ns_record_" + catalogPath().replace('-', '_'));
    assertThrowsExactly(
        UnsupportedOperationException.class,
        "Checking if database exists is not supported",
        () -> ns.namespaceExists(n));
    Assertions.assertThrows(NoSuchNamespaceException.class, () -> ns.loadNamespaceMetadata(n));
    ns.createNamespace(n);
    Assertions.assertThrows(
        AlreadyExistsException.class,
        () -> ns.createNamespace(n, Collections.singletonMap("k", "v")));
    Assertions.assertFalse(ns.listNamespaces().contains(n), ns.listNamespaces().toString());
    Assertions.assertTrue(ns.setProperties(n, Collections.singletonMap("k", "v")));
    Assertions.assertEquals(Collections.singletonMap("k", "v"), ns.loadNamespaceMetadata(n));
    // The rest-shim path answers false although the property is removed (checked below).
    Assertions.assertEquals(
        !REST_SHIM.equals(catalogPath()), ns.removeProperties(n, Collections.singleton("k")));
    Assertions.assertEquals(Collections.emptyMap(), ns.loadNamespaceMetadata(n));
    Assertions.assertTrue(ns.dropNamespace(n));
    Assertions.assertFalse(ns.dropNamespace(n));
    assertThrowsExactly(
        UnsupportedOperationException.class,
        "Openhouse supports 2-lvl namespace <schema>.<table>",
        () -> ns.listNamespaces(n));
  }

  // ------------------------------------------------------------- 3. load missing

  @Test
  void loadTableOfNeverCreatedTable() {
    assertThrowsExactly(
        org.apache.iceberg.exceptions.NoSuchTableException.class,
        "Table does not exist: cpc_load_missing.never_created",
        () -> catalog.loadTable(id("cpc_load_missing", "never_created")));
  }

  // ------------------------------------------------------------- 4. create twice

  @Test
  void createTableTwiceFails() {
    TableIdentifier tid = id("cpc_create_twice", "t1");
    catalog.createTable(tid, SCHEMA);
    assertThrowsExactly(
        org.apache.iceberg.exceptions.AlreadyExistsException.class,
        "Table already exists: cpc_create_twice.t1",
        () -> catalog.createTable(tid, SCHEMA));
    assertThrowsExactly(
        org.apache.iceberg.exceptions.AlreadyExistsException.class,
        "Table already exists: cpc_create_twice.t1",
        () -> catalog.buildTable(tid, SCHEMA).createTransaction());
  }

  // ------------------------------------------------------------- 5. overwrite

  @Test
  void overwriteCommitReplacesDataFiles() {
    TableIdentifier tid = id("cpc_overwrite", "t1");
    Table table = catalog.createTable(tid, SCHEMA);
    DataFile a = file("/path/to/a.parquet");
    DataFile b = file("/path/to/b.parquet");
    table.newAppend().appendFile(a).commit();
    table.newOverwrite().deleteFile(a).addFile(b).commit();

    Table reloaded = catalog.loadTable(tid);
    Assertions.assertEquals("overwrite", reloaded.currentSnapshot().operation());
    Assertions.assertEquals(2, reloaded.snapshots().spliterator().getExactSizeIfKnown());
    Assertions.assertEquals(Collections.singleton("/path/to/b.parquet"), dataFilePaths(reloaded));
  }

  // ------------------------------------------------------------- 6. property removal

  @Test
  void userPropertyCanBeSetAndRemoved() {
    TableIdentifier tid = id("cpc_props", "t1");
    Table table = catalog.createTable(tid, SCHEMA);
    table.updateProperties().set("cpc.user.key", "v1").commit();
    Assertions.assertEquals("v1", catalog.loadTable(tid).properties().get("cpc.user.key"));
    catalog.loadTable(tid).updateProperties().remove("cpc.user.key").commit();
    Assertions.assertFalse(catalog.loadTable(tid).properties().containsKey("cpc.user.key"));
  }

  // ------------------------------------------------------------- 7. reserved properties

  @Test
  void reservedPropertyViaUpdateProperties() {
    TableIdentifier tid = id("cpc_reserved_update", "t1");
    Table table = catalog.createTable(tid, SCHEMA);
    Throwable t =
        thrown(() -> table.updateProperties().set("openhouse.tableId", "hijack").commit());
    Assertions.assertEquals(org.apache.iceberg.exceptions.BadRequestException.class, t.getClass());
    Assertions.assertTrue(
        t.getMessage().startsWith("400 , {\"status\":\"BAD_REQUEST\""), t.getMessage());
    Assertions.assertTrue(
        t.getMessage()
            .contains(
                "table properties starting with `openhouse.` and the `policies` key cannot be modified"),
        t.getMessage());
    Table reloaded = catalog.loadTable(tid);
    Assertions.assertEquals("t1", reloaded.properties().get("openhouse.tableId"));
  }

  @Test
  void reservedPropertyOnCreate() {
    TableIdentifier tid = id("cpc_reserved_create", "t1");
    Table created =
        catalog.createTable(
            tid,
            SCHEMA,
            PartitionSpec.unpartitioned(),
            Collections.singletonMap("openhouse.custom", "x"));
    Assertions.assertFalse(created.properties().containsKey("openhouse.custom"));
    Assertions.assertFalse(catalog.loadTable(tid).properties().containsKey("openhouse.custom"));
    Assertions.assertEquals("t1", catalog.loadTable(tid).properties().get("openhouse.tableId"));
  }

  // ------------------------------------------------------------- 8. updateSpec

  @Test
  void updateSpecOnExistingTableIsRejected() {
    TableIdentifier tid = id("cpc_spec", "t1");
    Table table = catalog.createTable(tid, SCHEMA);
    PartitionSpec before = catalog.loadTable(tid).spec();
    Throwable t = thrown(() -> table.updateSpec().addField("data").commit());
    Assertions.assertEquals(org.apache.iceberg.exceptions.BadRequestException.class, t.getClass());
    Assertions.assertTrue(
        t.getMessage()
            .contains(
                "Evolution of table partitioning and clustering columns are not supported, recreate the table with new partition spec."),
        t.getMessage());
    Assertions.assertEquals(before, catalog.loadTable(tid).spec());
  }

  // ------------------------------------------------------------- 9. concurrent commit

  @Test
  void staleHandleAppendRetriesAndSucceeds() {
    TableIdentifier tid = id("cpc_concurrent", "t1");
    catalog.createTable(tid, SCHEMA);
    Table first = catalog.loadTable(tid);
    Table second = catalog.loadTable(tid);
    first.newAppend().appendFile(file("/path/to/c1.parquet")).commit();
    second.newAppend().appendFile(file("/path/to/c2.parquet")).commit();
    Table reloaded = catalog.loadTable(tid);
    Assertions.assertEquals(2, reloaded.snapshots().spliterator().getExactSizeIfKnown());
    Assertions.assertEquals(
        new HashSet<>(Arrays.asList("/path/to/c1.parquet", "/path/to/c2.parquet")),
        dataFilePaths(reloaded));
  }

  @Test
  void rawCommitWithStaleBaseFails() {
    TableIdentifier tid = id("cpc_raw_commit", "t1");
    catalog.createTable(tid, SCHEMA);
    Table table = catalog.loadTable(tid);
    TableOperations ops = ((HasTableOperations) table).operations();
    TableMetadata staleBase = ops.current();
    table.updateProperties().set("cpc.first", "1").commit();
    TableMetadata proposed =
        TableMetadata.buildFrom(staleBase)
            .setProperties(Collections.singletonMap("cpc.second", "2"))
            .build();
    assertThrowsExactly(
        org.apache.iceberg.exceptions.CommitFailedException.class,
        "Cannot commit: stale table metadata",
        () -> ops.commit(staleBase, proposed));
    Assertions.assertFalse(catalog.loadTable(tid).properties().containsKey("cpc.second"));
  }

  // ------------------------------------------------------------- 10. drop

  @Test
  void dropTableOfMissingTable() {
    Assertions.assertFalse(catalog.dropTable(id("cpc_drop_missing", "never_created")));
  }

  @Test
  void dropTableWithAndWithoutPurge() {
    TableIdentifier keep = id("cpc_drop", "t_nopurge");
    TableIdentifier purge = id("cpc_drop", "t_purge");
    catalog.createTable(keep, SCHEMA);
    catalog.createTable(purge, SCHEMA);
    Assertions.assertTrue(catalog.dropTable(keep, false));
    Assertions.assertTrue(catalog.dropTable(purge, true));
    Assertions.assertFalse(catalog.tableExists(keep));
    Assertions.assertFalse(catalog.tableExists(purge));
  }

  // ------------------------------------------------------------- 11. rename

  @Test
  void renameOntoExistingTableFails() {
    TableIdentifier from = id("cpc_rename", "t_from");
    TableIdentifier to = id("cpc_rename", "t_to");
    catalog.createTable(from, SCHEMA);
    catalog.createTable(to, SCHEMA);
    assertWebClient(
        409,
        "409 Conflict , {\"status\":\"CONFLICT\"",
        "Table local-cluster.cpc_rename.t_to already exists",
        thrown(() -> catalog.renameTable(from, to)));
    Assertions.assertTrue(catalog.tableExists(from));
    Assertions.assertTrue(catalog.tableExists(to));
  }

  // ------------------------------------------------------------- 12. grants

  @Test
  void grantsRoundTrip() {
    TableIdentifier tid = id("cpc_grants", "t1");
    catalog.createTable(tid, SCHEMA);
    SupportsGrantRevoke grants = (SupportsGrantRevoke) catalog;
    Namespace db = Namespace.of("cpc_grants");
    Throwable unshared =
        thrown(() -> grants.updateTableAclPolicies(tid, true, "SELECT", "someone"));
    Assertions.assertEquals(IllegalArgumentException.class, unshared.getClass());
    Assertions.assertTrue(
        unshared.getMessage().startsWith("400 , {\"status\":\"BAD_REQUEST\""),
        unshared.getMessage());
    Assertions.assertTrue(
        unshared.getMessage().contains("cpc_grants.t1 is not a shared table"),
        unshared.getMessage());
    spark.sql("ALTER TABLE openhouse.cpc_grants.t1 SET POLICY (SHARING=TRUE)");
    grants.updateTableAclPolicies(tid, true, "SELECT", "someone");
    grants.updateDatabaseAclPolicies(db, true, "CREATE TABLE", "someone");
    // The embedded server's authorization does not store grants: the update calls are accepted but
    // nothing round-trips, so both getters list nothing. This pins that, it is not a grant store.
    Assertions.assertEquals(Collections.emptyList(), grants.getTableAclPolicies(tid));
    Assertions.assertEquals(Collections.emptyList(), grants.getDatabaseAclPolicies(db));
    grants.updateTableAclPolicies(tid, false, "SELECT", "someone");
    grants.updateDatabaseAclPolicies(db, false, "CREATE TABLE", "someone");
    Assertions.assertEquals(Collections.emptyList(), grants.getTableAclPolicies(tid));
    Assertions.assertEquals(Collections.emptyList(), grants.getDatabaseAclPolicies(db));
  }

  // ------------------------------------------------------------- 13. capability mismatch

  @Test
  void capabilityMismatch() {
    String db = SparkItestColumnDefaults.DATABASE;
    TableIdentifier tid = id(db, "cpc_capability");
    TableIdentifier plain = id(db, "cpc_capability_plain");
    // The engine catalog declares column-defaults on the legacy Iceberg line only (decision D11a).
    // On the v3 line a catalog that declares it explicitly stands in for a legacy client.
    Catalog declaring;
    if (IcebergLine.current() == IcebergLine.LEGACY) {
      declaring = catalog;
    } else {
      assertWebClient(
          422,
          "422 Unprocessable Entity , {\"status\":\"UNPROCESSABLE_ENTITY\"",
          "CLIENT_CAPABILITY_MISSING: d1_column_default.cpc_capability requires the column-defaults capability",
          thrown(
              () ->
                  catalog.buildTable(tid, SCHEMA).withProperty(CAPABILITY_PROP, "true").create()));
      declaring =
          catalogWith(
              javaCatalogImpl(),
              Collections.singletonMap(
                  "header.X-OpenHouse-Client-Capabilities", "column-defaults"));
    }
    declaring.buildTable(tid, SCHEMA).withProperty(CAPABILITY_PROP, "true").create();
    catalog.createTable(plain, SCHEMA);

    Catalog undeclared = catalogWith(javaCatalogImpl(), Collections.emptyMap());
    assertWebClient(
        422,
        "422 Unprocessable Entity , {\"status\":\"UNPROCESSABLE_ENTITY\"",
        "CLIENT_CAPABILITY_MISSING: d1_column_default.cpc_capability requires the column-defaults capability",
        thrown(() -> undeclared.loadTable(tid)));

    // Handle loaded through the non-declaring catalog while the table did not yet require the
    // capability; the table then starts requiring it, and the stale handle commits.
    Table handle = undeclared.loadTable(plain);
    declaring.loadTable(plain).updateProperties().set(CAPABILITY_PROP, "true").commit();
    Throwable commit =
        thrown(() -> handle.newAppend().appendFile(file("/path/to/cap.parquet")).commit());
    assertWebClient(
        422,
        "422 Unprocessable Entity , {\"status\":\"UNPROCESSABLE_ENTITY\"",
        "CLIENT_CAPABILITY_MISSING: d1_column_default.cpc_capability_plain requires the column-defaults capability",
        commit);
    Assertions.assertNull(commit.getCause());
    Assertions.assertTrue(dataFilePaths(declaring.loadTable(plain)).isEmpty());
  }

  // ------------------------------------------------------------- 14. authorization

  @Test
  void enforcingAuthorization() throws Exception {
    org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.disable();
    authServer =
        new SpringApplication(SpringH2TestApplication.class, AuthorizationTestConfig.class)
            .run("--server.port=0");
    authServerUri =
        URI.create(
            "http://localhost:"
                + ((WebServerApplicationContext) authServer).getWebServer().getPort());
    Catalog owner = authCatalog(OWNER);
    Catalog reader = authCatalog(READER);
    Catalog stranger = authCatalog(STRANGER);
    TableIdentifier tid = id(AUTH_DB, "t1");
    owner.createTable(tid, SCHEMA);

    assertWebClient(
        403,
        "403 Forbidden , {\"status\":\"FORBIDDEN\"",
        "Operation on table cpc_auth_db.t1 failed as user cpc_stranger is unauthorized",
        thrown(() -> stranger.loadTable(tid)));

    Table readerTable = reader.loadTable(tid);
    Throwable readerCommit =
        thrown(() -> readerTable.newAppend().appendFile(file("/path/to/auth.parquet")).commit());
    assertWebClient(
        403,
        "403 Forbidden , {\"status\":\"FORBIDDEN\"",
        "Operation on table cpc_auth_db.t1 failed as user cpc_reader is unauthorized",
        readerCommit);
    Assertions.assertTrue(dataFilePaths(owner.loadTable(tid)).isEmpty());
  }

  private Catalog authCatalog(String principal) throws Exception {
    Map<String, String> properties = new HashMap<>();
    properties.put("uri", authServerUri.toString());
    properties.put("cluster", "local-cluster");
    properties.put(
        "auth-token", new DummyTokenInterceptor.DummySecurityJWT(principal).buildNoopJWT());
    properties.put("cache-enabled", "false");
    return CatalogUtil.loadCatalog(
        engineCatalogImpl(), "openhouse", properties, new Configuration());
  }

  @org.springframework.context.annotation.Configuration
  static class AuthorizationTestConfig implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
      registry.addInterceptor(new DummyTokenInterceptor());
    }

    @Bean
    @Primary
    AuthorizationHandler contractAuthorizationHandler() {
      return new OpaAuthorizationHandler() {
        @Override
        public boolean checkAccessDecision(String principal, TableDto table, Privileges privilege) {
          return OWNER.equals(principal)
              || READER.equals(principal) && privilege == Privileges.GET_TABLE_METADATA;
        }

        @Override
        public boolean checkAccessDecision(
            String principal, DatabaseDto database, Privileges privilege) {
          return OWNER.equals(principal);
        }
      };
    }
  }

  // ------------------------------------------------------------- 16. locked table

  @Test
  void tableExistsOnTableLockedAgainstReads() {
    TableIdentifier tid = id("cpc_locked", "t1");
    catalog.createTable(tid, SCHEMA);
    ApiClient client = new ApiClient();
    client.setBasePath(getOpenHouseLocalServerURI().toString());
    client.addDefaultHeader(
        "Authorization", "Bearer " + spark.conf().get("spark.sql.catalog.openhouse.auth-token"));
    TableApi controls = new TableApi(client);
    controls
        .createLockV1(
            "cpc_locked",
            "t1",
            new CreateUpdateLockRequestBody()
                .locked(true)
                .reason(CreateUpdateLockRequestBody.ReasonEnum.SYSTEM_ONLY))
        .block();
    try {
      // Existence is answered without loading the table, which the lock blocks.
      Assertions.assertTrue(catalog.tableExists(tid));
      Assertions.assertEquals(423, statusOf(thrown(() -> catalog.loadTable(tid))));
    } finally {
      controls.deleteLockByReasonV1("cpc_locked", "t1", "SYSTEM_ONLY").block();
    }
    Assertions.assertNotNull(catalog.loadTable(tid));
  }

  // ------------------------------------------------------------- 17. table handle

  @Test
  void loadedTableNameAndFileIO() {
    TableIdentifier tid = id("cpc_table_handle", "t1");
    catalog.createTable(tid, SCHEMA);
    Table table = catalog.loadTable(tid);
    Assertions.assertEquals("openhouse.cpc_table_handle.t1", table.name());
    Assertions.assertEquals(HadoopFileIO.class, table.io().getClass());
  }

  // ------------------------------------------------------------- 15. invalid identifier

  @Test
  void loadTableWithInvalidIdentifierReportsNoSuchTable() {
    // The plugin turns a 400 as well as a 404 from the service into "no such table".
    for (String bad : new String[] {"bad name!", "a;b", "a%b", "a$b", "a?b", "a#b"}) {
      assertThrowsExactly(
          org.apache.iceberg.exceptions.NoSuchTableException.class,
          "Table does not exist: cpc_invalid." + bad,
          () -> catalog.loadTable(id("cpc_invalid", bad)));
    }
    assertThrowsExactly(
        org.apache.iceberg.exceptions.NoSuchTableException.class,
        "Table does not exist: bad db!.t",
        () -> catalog.loadTable(id("bad db!", "t")));
  }
}
