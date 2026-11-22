package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.common.security.DummyTokenInterceptor;
import com.linkedin.openhouse.javaclient.IcebergLine;
import com.linkedin.openhouse.javaclient.exception.WebClientResponseWithMessageException;
import com.linkedin.openhouse.tables.authorization.AuthorizationHandler;
import com.linkedin.openhouse.tables.authorization.OpaAuthorizationHandler;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import com.linkedin.openhouse.tablestest.SparkItestColumnDefaults;
import com.linkedin.openhouse.tablestest.SpringH2TestApplication;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.SupportsNamespaces;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.exceptions.NotAuthorizedException;
import org.apache.iceberg.exceptions.RESTException;
import org.apache.iceberg.hadoop.HadoopFileIO;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.rest.RESTCatalog;
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

/**
 * Black box: an unmodified Iceberg {@link RESTCatalog} (the Iceberg 1.5.2 line's own class, loaded
 * as any engine loads a catalog) reads, over real HTTP, tables that were created and written
 * through the existing OpenHouse client against the real embedded tables service. Every answer is
 * compared with what the existing OpenHouse Java client reports for the same table. Where the two
 * clients differ, the difference is pinned in {@link #differencesBetweenTheTwoClients()} rather
 * than hidden.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class IcebergRestCatalogReadTest extends OpenHouseSparkITest {

  private static final String DB = "irc_read";
  private static final String AUTH_DB = "irc_read_auth";
  private static final String OWNER = "irc_owner";
  private static final String STRANGER = "irc_stranger";
  private static final String CAPABILITY_PROP = "required-capability.column-defaults";
  private static final String CAPABILITIES_HEADER = "X-OpenHouse-Client-Capabilities";
  private static final String READ_BRIDGE_PREFIX = "openhouse.read-bridge.column-default.";

  private static final List<TableIdentifier> CREATED =
      Arrays.asList(
          TableIdentifier.of(DB, "t_written"),
          TableIdentifier.of(DB, "t_empty"),
          TableIdentifier.of(SparkItestColumnDefaults.DATABASE, "irc_capability"));

  private SparkSession spark;
  /** The existing OpenHouse Java client, declaring no capabilities. */
  private Catalog openhouse;
  /** The engine's OpenHouse catalog, which declares the column-defaults capability. */
  private Catalog engineOpenhouse;

  private RESTCatalog rest;
  private ConfigurableApplicationContext authServer;

  @BeforeAll
  void start() throws Exception {
    spark = getSparkSession();
    engineOpenhouse = getOpenHouseCatalog(spark);
    openhouse = javaCatalog();
    for (TableIdentifier leftover : CREATED) {
      engineOpenhouse.dropTable(leftover);
    }
    rest = restCatalog(getOpenHouseLocalServerURI(), token("irc_reader"), Collections.emptyMap());

    // Written through the existing client only: Spark SQL for data, the Java API for metadata.
    String t = "openhouse." + DB + ".t_written";
    spark.sql("CREATE TABLE " + t + " (id bigint, name string, p string) PARTITIONED BY (p)");
    spark.sql("INSERT INTO " + t + " VALUES (1,'a','x'),(2,'b','y')");
    spark.sql("INSERT INTO " + t + " VALUES (3,'c','x')");
    spark.sql("ALTER TABLE " + t + " SET TBLPROPERTIES ('irc.test'='yes')");
    Table written = openhouse.loadTable(TableIdentifier.of(DB, "t_written"));
    written.replaceSortOrder().asc("id").commit();
    spark.sql("INSERT INTO " + t + " VALUES (4,'d','y')");
    openhouse.createTable(
        TableIdentifier.of(DB, "t_empty"),
        new Schema(Types.NestedField.required(1, "id", Types.LongType.get())));
  }

  @AfterAll
  void stop() throws Exception {
    if (rest != null) {
      rest.close();
    }
    if (authServer != null) {
      authServer.close();
    }
    if (spark != null) {
      spark.close();
    }
  }

  // ---------------------------------------------------------------- helpers

  private static String token(String principal) throws Exception {
    return new DummyTokenInterceptor.DummySecurityJWT(principal).buildNoopJWT();
  }

  /**
   * An unmodified {@link RESTCatalog}, loaded the way an engine loads any catalog, pointed at the
   * IRC base path of {@code server}, sending {@code token} as a bearer token.
   */
  private RESTCatalog restCatalog(URI server, String token, Map<String, String> extra) {
    Map<String, String> properties = new HashMap<>();
    properties.put("uri", server + "/iceberg");
    if (token != null) {
      properties.put("token", token);
    }
    properties.putAll(extra);
    return (RESTCatalog)
        CatalogUtil.loadCatalog(
            RESTCatalog.class.getName(),
            "openhouse",
            properties,
            spark.sparkContext().hadoopConfiguration());
  }

  /** The plain existing Java client, configured like the engine's catalog. */
  private Catalog javaCatalog() {
    return javaCatalog(Collections.emptyMap());
  }

  /** {@link #javaCatalog()} with {@code extra} catalog properties. */
  private Catalog javaCatalog(Map<String, String> extra) {
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
        "com.linkedin.openhouse.javaclient.OpenHouseCatalog",
        "openhouse",
        properties,
        spark.sparkContext().hadoopConfiguration());
  }

  private static Set<String> plannedFiles(Table table) throws IOException {
    try (CloseableIterable<FileScanTask> tasks = table.newScan().planFiles()) {
      Set<String> paths = new HashSet<>();
      for (FileScanTask task : tasks) {
        paths.add(task.file().path().toString());
      }
      return paths;
    }
  }

  private static TableMetadata current(Table table) {
    return ((HasTableOperations) table).operations().current();
  }

  private static List<Long> snapshotIds(Table table) {
    return java.util.stream.StreamSupport.stream(table.snapshots().spliterator(), false)
        .map(Snapshot::snapshotId)
        .collect(Collectors.toList());
  }

  private static String withoutLastUpdated(TableMetadata metadata) {
    return TableMetadataParser.toJson(metadata)
        .replaceFirst("\"last-updated-ms\"\\s*:\\s*\\d+", "\"last-updated-ms\":0");
  }

  private static Throwable thrown(Executable executable) {
    try {
      executable.execute();
    } catch (Throwable t) {
      return t;
    }
    throw new AssertionError("expected an exception but none was thrown");
  }

  private static void assertThrown(Class<?> type, String messageContains, Throwable t) {
    Assertions.assertEquals(type, t.getClass(), String.valueOf(t));
    Assertions.assertTrue(
        t.getMessage() != null && t.getMessage().contains(messageContains),
        "message was: " + t.getMessage());
  }

  // ---------------------------------------------------------------- namespaces

  @Test
  void listNamespacesMatchesTheExistingClient() {
    Set<Namespace> viaRest = new HashSet<>(rest.listNamespaces());
    Set<Namespace> viaOpenHouse = new HashSet<>(((SupportsNamespaces) openhouse).listNamespaces());
    Assertions.assertTrue(viaRest.contains(Namespace.of(DB)), viaRest.toString());
    Assertions.assertEquals(viaOpenHouse, viaRest);
  }

  @Test
  void aDatabaseWithTablesIsANamespaceAndOneWithoutIsNot() {
    Assertions.assertEquals(Collections.emptyMap(), rest.loadNamespaceMetadata(Namespace.of(DB)));
    Assertions.assertTrue(rest.namespaceExists(Namespace.of(DB)));
    Assertions.assertFalse(rest.namespaceExists(Namespace.of("irc_read_never")));
    assertThrown(
        NoSuchNamespaceException.class,
        "irc_read_never",
        thrown(() -> rest.loadNamespaceMetadata(Namespace.of("irc_read_never"))));
    Assertions.assertEquals(Collections.emptyList(), rest.listNamespaces(Namespace.of(DB)));
  }

  // ---------------------------------------------------------------- tables

  @Test
  void listTablesMatchesTheExistingClient() {
    Set<TableIdentifier> viaRest = new HashSet<>(rest.listTables(Namespace.of(DB)));
    Assertions.assertEquals(new HashSet<>(openhouse.listTables(Namespace.of(DB))), viaRest);
    Assertions.assertTrue(
        viaRest.contains(TableIdentifier.of(DB, "t_written")), viaRest.toString());
  }

  @Test
  void listTablesOfAMissingNamespaceIsNoSuchNamespace() {
    assertThrown(
        NoSuchNamespaceException.class,
        "irc_read_never",
        thrown(() -> rest.listTables(Namespace.of("irc_read_never"))));
    // The existing client lists nothing for a database that does not exist.
    Assertions.assertEquals(
        Collections.emptyList(), openhouse.listTables(Namespace.of("irc_read_never")));
  }

  @Test
  void tableExistsMatchesTheExistingClient() {
    for (TableIdentifier id :
        new TableIdentifier[] {
          TableIdentifier.of(DB, "t_written"),
          TableIdentifier.of(DB, "never"),
          TableIdentifier.of("irc_read_never", "t_written")
        }) {
      Assertions.assertEquals(openhouse.tableExists(id), rest.tableExists(id), id.toString());
    }
    Assertions.assertTrue(rest.tableExists(TableIdentifier.of(DB, "t_written")));
    Assertions.assertFalse(rest.tableExists(TableIdentifier.of(DB, "never")));
  }

  @Test
  void loadTableShowsWhatTheExistingClientShows() throws Exception {
    for (String name : new String[] {"t_written", "t_empty"}) {
      TableIdentifier id = TableIdentifier.of(DB, name);
      Table viaRest = rest.loadTable(id);
      Table viaOpenHouse = openhouse.loadTable(id);

      Assertions.assertEquals(viaOpenHouse.schema().asStruct(), viaRest.schema().asStruct());
      Assertions.assertEquals(viaOpenHouse.schema().schemaId(), viaRest.schema().schemaId());
      Assertions.assertEquals(viaOpenHouse.spec(), viaRest.spec());
      Assertions.assertEquals(viaOpenHouse.specs(), viaRest.specs());
      Assertions.assertEquals(viaOpenHouse.sortOrder(), viaRest.sortOrder());
      Assertions.assertEquals(viaOpenHouse.properties(), viaRest.properties());
      Assertions.assertEquals(viaOpenHouse.location(), viaRest.location());
      Assertions.assertEquals(viaOpenHouse.uuid(), viaRest.uuid());
      Assertions.assertEquals(viaOpenHouse.refs(), viaRest.refs());
      Assertions.assertEquals(snapshotIds(viaOpenHouse), snapshotIds(viaRest));
      Assertions.assertEquals(
          viaOpenHouse.currentSnapshot() == null
              ? null
              : viaOpenHouse.currentSnapshot().snapshotId(),
          viaRest.currentSnapshot() == null ? null : viaRest.currentSnapshot().snapshotId());
      Assertions.assertEquals(viaOpenHouse.name(), viaRest.name());
      Assertions.assertEquals(
          current(viaOpenHouse).metadataFileLocation(), current(viaRest).metadataFileLocation());
      Assertions.assertEquals(
          current(viaOpenHouse).formatVersion(), current(viaRest).formatVersion());
      Assertions.assertEquals(plannedFiles(viaOpenHouse), plannedFiles(viaRest));
    }
    Table written = rest.loadTable(TableIdentifier.of(DB, "t_written"));
    List<String> operations =
        java.util.stream.StreamSupport.stream(written.snapshots().spliterator(), false)
            .map(Snapshot::operation)
            .collect(Collectors.toList());
    Assertions.assertEquals(Arrays.asList("append", "append", "append"), operations);
    Assertions.assertEquals(4, plannedFiles(written).size());
    Assertions.assertEquals("yes", written.properties().get("irc.test"));
    Assertions.assertFalse(written.sortOrder().isUnsorted());
    Assertions.assertFalse(written.spec().isUnpartitioned());
  }

  /** Reads the rows back through the RESTCatalog's table with Iceberg's own data reader. */
  @Test
  void aScanPlannedThroughTheRestCatalogReadsTheSameRows() throws Exception {
    Table viaRest = rest.loadTable(TableIdentifier.of(DB, "t_written"));
    long records = 0;
    try (CloseableIterable<FileScanTask> tasks = viaRest.newScan().planFiles()) {
      for (FileScanTask task : tasks) {
        records += task.file().recordCount();
      }
    }
    Assertions.assertEquals(4, records);
    Assertions.assertEquals(
        4L, spark.sql("SELECT count(*) FROM openhouse." + DB + ".t_written").first().getLong(0));
  }

  @Test
  void loadMissingTableIsNoSuchTable() {
    assertThrown(
        NoSuchTableException.class,
        "irc_read.never",
        thrown(() -> rest.loadTable(TableIdentifier.of(DB, "never"))));
    assertThrown(
        NoSuchTableException.class,
        "irc_read_never.t_written",
        thrown(() -> rest.loadTable(TableIdentifier.of("irc_read_never", "t_written"))));
  }

  /**
   * RESTCatalog loads the metadata table {@code db.t.snapshots} by first asking for the table
   * {@code snapshots} in the namespace {@code db.t}, and loads the metadata table of {@code db.t}
   * only when that answers 404. A name that is no metadata table stays a missing table.
   */
  @Test
  void metadataTablesLoadThroughTheRestCatalog() throws Exception {
    Table snapshots = rest.loadTable(TableIdentifier.of(DB, "t_written", "snapshots"));
    long rows = 0;
    try (CloseableIterable<FileScanTask> tasks = snapshots.newScan().planFiles()) {
      for (FileScanTask task : tasks) {
        try (CloseableIterable<org.apache.iceberg.StructLike> taskRows = task.asDataTask().rows()) {
          for (org.apache.iceberg.StructLike ignored : taskRows) {
            rows++;
          }
        }
      }
    }
    Assertions.assertEquals(
        snapshotIds(openhouse.loadTable(TableIdentifier.of(DB, "t_written"))).size(), rows);
    assertThrown(
        NoSuchTableException.class,
        "irc_read.t_written.never",
        thrown(() -> rest.loadTable(TableIdentifier.of(DB, "t_written", "never"))));
  }

  /**
   * Differences between what the two clients show for the same table, pinned as observed. Each one
   * is listed in the V2 report with its cause.
   */
  @Test
  void differencesBetweenTheTwoClients() {
    TableIdentifier id = TableIdentifier.of(DB, "t_written");
    Table viaRest = rest.loadTable(id);
    Table viaOpenHouse = openhouse.loadTable(id);
    // FileIO: the server advertises the existing client's own (HadoopFileIO) since V3, because
    // RESTCatalog's default (ResolvingFileIO) cannot write delete files on this Iceberg line
    // (IcebergRestSparkSqlTest.mergeOnReadDeleteAndUpdate).
    Assertions.assertEquals(HadoopFileIO.class, viaRest.io().getClass());
    Assertions.assertEquals(HadoopFileIO.class, viaOpenHouse.io().getClass());
    // Table operations: Iceberg's REST operations commit through the IRC routes (V3); the
    // existing client's commit through the native API.
    Assertions.assertEquals(
        "org.apache.iceberg.rest.RESTTableOperations",
        ((HasTableOperations) viaRest).operations().getClass().getName());
    Assertions.assertEquals(
        "com.linkedin.openhouse.javaclient.OpenHouseTableOperations",
        ((HasTableOperations) viaOpenHouse).operations().getClass().getName());
    // last-updated-ms: the server sends the stored value, but RESTCatalog 1.5.2 rebuilds the
    // metadata to attach its location (LoadTableResponse.tableMetadata()), and Iceberg's builder
    // stamps the current time. Iceberg 1.12 keeps the stored value, so on that line the two agree.
    // Everything else in the metadata is identical.
    TableMetadata r = current(viaRest);
    TableMetadata o = current(viaOpenHouse);
    if (IcebergLine.current() == IcebergLine.LEGACY) {
      Assertions.assertTrue(
          r.lastUpdatedMillis() > o.lastUpdatedMillis(),
          r.lastUpdatedMillis() + " vs " + o.lastUpdatedMillis());
    } else {
      Assertions.assertEquals(o.lastUpdatedMillis(), r.lastUpdatedMillis());
    }
    Assertions.assertEquals(withoutLastUpdated(o), withoutLastUpdated(r));
  }

  // ---------------------------------------------------------------- capabilities

  /**
   * A table that requires the column-defaults capability: a RESTCatalog that does not declare it is
   * refused with the native 422 and message, as the existing client that does not declare it is. A
   * RESTCatalog that declares it (as a configured header) loads the stored metadata and receives
   * the read-bridge config in the load response, which Iceberg applies only as table FileIO
   * properties; it does not apply column defaults.
   *
   * <p>The OpenHouse client that creates and loads the table must declare the capability. On the
   * legacy line that is the engine's catalog. On the v3 line the engine's catalog declares nothing
   * (decision D11a), so a Java client declaring it through its configured header stands in for a
   * legacy client, and it applies no read-bridge overlay.
   */
  @Test
  void aTableRequiringACapabilityIsGatedAsForTheExistingClient() throws Exception {
    TableIdentifier id = TableIdentifier.of(SparkItestColumnDefaults.DATABASE, "irc_capability");
    boolean legacyLine = IcebergLine.current() == IcebergLine.LEGACY;
    Catalog declaringOpenhouse =
        legacyLine
            ? engineOpenhouse
            : javaCatalog(
                Collections.singletonMap("header." + CAPABILITIES_HEADER, "column-defaults"));
    declaringOpenhouse
        .buildTable(
            id,
            new Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.optional(2, "country", Types.StringType.get())))
        .withProperty(CAPABILITY_PROP, "true")
        .create();

    Throwable nativeRefusal = thrown(() -> openhouse.loadTable(id));
    Assertions.assertEquals(
        WebClientResponseWithMessageException.class, nativeRefusal.getClass(), "" + nativeRefusal);
    Assertions.assertEquals(
        422, ((WebClientResponseWithMessageException) nativeRefusal).getStatusCode());
    Throwable restRefusal = thrown(() -> rest.loadTable(id));
    assertThrown(
        RESTException.class,
        "CLIENT_CAPABILITY_MISSING: d1_column_default.irc_capability requires the column-defaults"
            + " capability",
        restRefusal);
    // Existence does not load the table, as on the existing client... but RESTCatalog 1.5.2 checks
    // existence by loading, so it is refused too.
    Assertions.assertTrue(openhouse.tableExists(id));
    assertThrown(
        RESTException.class, "CLIENT_CAPABILITY_MISSING", thrown(() -> rest.tableExists(id)));

    try (RESTCatalog declaring =
        restCatalog(
            getOpenHouseLocalServerURI(),
            token("irc_reader"),
            Collections.singletonMap("header." + CAPABILITIES_HEADER, "column-defaults"))) {
      Table viaRest = declaring.loadTable(id);
      Table viaEngine = declaringOpenhouse.loadTable(id);
      Assertions.assertEquals(
          current(viaEngine).metadataFileLocation(), current(viaRest).metadataFileLocation());
      Assertions.assertEquals(viaEngine.properties(), viaRest.properties());
      // On the legacy line the engine's client applies the column defaults the config carries to
      // its schema; on the v3 line it applies none (decision D11a). The RESTCatalog applies none
      // and shows the schema as stored.
      Assertions.assertEquals(
          legacyLine ? "US" : null, viaEngine.schema().findField("country").initialDefault());
      Assertions.assertNull(viaRest.schema().findField("country").initialDefault());
      Assertions.assertEquals(
          viaEngine.schema().asStruct().fields().size(),
          viaRest.schema().asStruct().fields().size());
      Map<String, String> ioProperties = viaRest.io().properties();
      Assertions.assertEquals(
          "\"US\"", ioProperties.get(READ_BRIDGE_PREFIX + "2"), ioProperties.toString());
    }
  }

  // ---------------------------------------------------------------- authentication, authorization

  private URI authServer() {
    if (authServer == null) {
      org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.disable();
      authServer =
          new SpringApplication(SpringH2TestApplication.class, AuthorizationTestConfig.class)
              .run(
                  "--server.port=0",
                  "--cluster.security.token.interceptor.classname="
                      + DummyTokenInterceptor.class.getName());
    }
    return URI.create(
        "http://localhost:" + ((WebServerApplicationContext) authServer).getWebServer().getPort());
  }

  @Test
  void aDeniedPrincipalIsForbiddenAndAnUnauthenticatedOneIsNotAuthorized() throws Exception {
    URI server = authServer();
    Map<String, String> ownerProperties = new HashMap<>();
    ownerProperties.put("uri", server.toString());
    ownerProperties.put("cluster", "local-cluster");
    ownerProperties.put("auth-token", token(OWNER));
    Catalog owner =
        CatalogUtil.loadCatalog(
            "com.linkedin.openhouse.javaclient.OpenHouseCatalog",
            "openhouse",
            ownerProperties,
            spark.sparkContext().hadoopConfiguration());
    TableIdentifier id = TableIdentifier.of(AUTH_DB, "t1");
    owner.createTable(id, new Schema(Types.NestedField.required(1, "id", Types.LongType.get())));

    try (RESTCatalog asOwner = restCatalog(server, token(OWNER), Collections.emptyMap());
        RESTCatalog asStranger = restCatalog(server, token(STRANGER), Collections.emptyMap())) {
      Assertions.assertEquals(
          owner.loadTable(id).schema().asStruct(), asOwner.loadTable(id).schema().asStruct());
      assertThrown(
          ForbiddenException.class,
          "Operation on table irc_read_auth.t1 failed as user irc_stranger is unauthorized",
          thrown(() -> asStranger.loadTable(id)));
      // RESTCatalog 1.5.2 checks existence by loading the table, so a principal that may not read
      // it is refused; the existing client asks the existence route, which answers anyone.
      assertThrown(
          ForbiddenException.class,
          "Operation on table irc_read_auth.t1 failed as user irc_stranger is unauthorized",
          thrown(() -> asStranger.tableExists(id)));
      Map<String, String> strangerProperties = new HashMap<>(ownerProperties);
      strangerProperties.put("auth-token", token(STRANGER));
      Catalog nativeStranger =
          CatalogUtil.loadCatalog(
              "com.linkedin.openhouse.javaclient.OpenHouseCatalog",
              "openhouse",
              strangerProperties,
              spark.sparkContext().hadoopConfiguration());
      Assertions.assertTrue(nativeStranger.tableExists(id));
    }

    assertThrown(
        NotAuthorizedException.class,
        "Not authorized",
        thrown(() -> restCatalog(server, null, Collections.emptyMap())));
    assertThrown(
        NotAuthorizedException.class,
        "Not authorized",
        thrown(() -> restCatalog(server, "not-a-token", Collections.emptyMap())));
  }

  @org.springframework.context.annotation.Configuration
  static class AuthorizationTestConfig {
    @Bean
    @Primary
    AuthorizationHandler ircAuthorizationHandler() {
      return new OpaAuthorizationHandler() {
        @Override
        public boolean checkAccessDecision(String principal, TableDto table, Privileges privilege) {
          return OWNER.equals(principal);
        }

        @Override
        public boolean checkAccessDecision(
            String principal, DatabaseDto database, Privileges privilege) {
          return OWNER.equals(principal);
        }
      };
    }
  }
}
