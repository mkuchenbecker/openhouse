package com.linkedin.openhouse.spark42.irc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.linkedin.openhouse.common.security.DummyTokenInterceptor;
import com.linkedin.openhouse.housetables.client.model.ToggleStatus;
import com.linkedin.openhouse.spark.grants.GrantStoringAuthorization;
import com.linkedin.openhouse.tables.services.ViewsFeatureGate;
import com.linkedin.openhouse.tables.toggle.model.TableToggleStatus;
import com.linkedin.openhouse.tables.toggle.repository.ToggleStatusesRepository;
import com.linkedin.openhouse.tablestest.SpringH2TestApplication;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotRef;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.types.Types;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The tables service for the Spark 4.2 run of the Spark SQL smoke suite ({@code smokeTest}), in its
 * own JVM. It is the service the Spark 3.5 smoke suite embeds ({@link SpringH2TestApplication} with
 * the cluster defaults: ORC, format version 2, and the authorization backend that stores grants,
 * {@link GrantStoringAuthorization}, work item T6), so that a statement that behaves differently
 * here differs because of the engine or the catalog path and not because of the service. These
 * things are not the Spark 3.5 suite's service's:
 *
 * <ul>
 *   <li>tables are kept in a directory of this run ({@link ServiceWarehouse}), not in {@code /tmp};
 *   <li>the dummy token interceptor is on, so the catalog's {@code token} names a principal, as for
 *       the other Spark 4.2 test services;
 *   <li>it is one service for the whole run (the Spark 3.5 suite's service, which also opens the
 *       views feature gate for {@value #VIEWS_DB}, is one per test class);
 *   <li>every request is written to an access log ({@code access} in the work directory), so a test
 *       can see which routes the suite called.
 * </ul>
 *
 * <p>A control API on a second local port shows what the service stored, through the existing
 * OpenHouse Java client over the native API, without Spark:
 *
 * <ul>
 *   <li>{@code GET /table?name=<db>.<table>}: the freshly loaded table's schema, partition spec,
 *       properties, snapshots (in the order they were committed), refs and, per ref, the rows a
 *       reader of that ref gets, each row as comma-joined values, sorted. A snapshot that no ref
 *       reaches (a staged write, or what a replace left behind) is listed with its rows as well.
 *       400 with the failure's class and message when the table cannot be loaded.
 * </ul>
 *
 * <p>It writes {@code ready.json} (both ports, the warehouse and the token) and serves until its
 * standard input closes. Usage: {@code IrcSmokeTestService <work directory> [<Spring
 * argument>...]}: the Spring arguments after the work directory are added to the service's own, as
 * for the row-level statements' service, which turns on {@code cluster.iceberg.format-v3.enabled}.
 */
public final class IrcSmokeTestService {

  /** The database whose views feature gate is open, as in the Spark 3.5 smoke suite. */
  static final String VIEWS_DB = "smoke_t1_views";

  private static final ObjectMapper JSON = new ObjectMapper();

  private IrcSmokeTestService() {}

  public static void main(String[] args) throws Exception {
    Path work = Paths.get(args[0]).toAbsolutePath();
    // Not under the work directory: a metadata location has to fit House Tables' 255 characters.
    Path warehouse = ServiceWarehouse.create();
    List<String> arguments = new ArrayList<>();
    Collections.addAll(
        arguments,
        "--server.port=0",
        "--server.shutdown=immediate",
        "--server.tomcat.basedir=" + work.resolve("tomcat"),
        "--cluster.security.token.interceptor.classname=" + DummyTokenInterceptor.class.getName(),
        "--cluster.storages.default-type=local",
        "--cluster.storages.types.local.endpoint=file://",
        "--cluster.storages.types.local.rootpath=" + warehouse,
        // Scan planning, off by default: smokeTestServerPlanning runs the suite with it.
        "--cluster.tables.irc.scan-planning.enabled=true",
        // Every request, unbuffered, so a test can see which routes the suite called.
        "--server.tomcat.accesslog.enabled=true",
        "--server.tomcat.accesslog.directory=" + work.resolve("access"),
        "--server.tomcat.accesslog.prefix=access",
        "--server.tomcat.accesslog.suffix=.log",
        "--server.tomcat.accesslog.rotate=false",
        "--server.tomcat.accesslog.buffered=false",
        "--server.tomcat.accesslog.pattern=%m %U %s");
    // Further Spring arguments after the work directory, for a second service with other cluster
    // settings (the row-level statements' service has the format v3 switch on).
    for (int i = 1; i < args.length; i++) {
      arguments.add(args[i]);
    }
    ConfigurableApplicationContext context =
        new SpringApplication(SpringH2TestApplication.class, GrantStoringAuthorization.Config.class)
            .run(arguments.toArray(new String[0]));
    context
        .getBean(ToggleStatusesRepository.class)
        .save(
            TableToggleStatus.builder()
                .databaseId(VIEWS_DB)
                .tableId(ViewsFeatureGate.GATE_PROBE_TABLE_ID)
                .featureId("views")
                .toggleStatusEnum(ToggleStatus.StatusEnum.ACTIVE)
                .build());
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    String token =
        new DummyTokenInterceptor.DummySecurityJWT(IrcReadTestService.WRITER).buildNoopJWT();
    Catalog openhouse = javaClient(port, token);

    HttpServer control =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    control.createContext("/table", exchange -> handle(exchange, openhouse));
    control.start();

    Map<String, Object> ready = new LinkedHashMap<>();
    ready.put("port", port);
    ready.put("warehouse", warehouse.toString());
    ready.put("controlPort", control.getAddress().getPort());
    ready.put("token", token);
    Path tmp = work.resolve("ready.json.tmp");
    new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(tmp.toFile(), ready);
    Files.move(tmp, work.resolve("ready.json"), StandardCopyOption.ATOMIC_MOVE);
    System.out.println("IRC smoke test service ready on port " + port);

    // Serve until the parent closes our standard input (it stopped us, or it died).
    InputStream in = System.in;
    while (in.read() != -1) {
      // ignore
    }
    control.stop(0);
    SpringApplication.exit(context);
    System.exit(0);
  }

  /** Answers 200 with the table's stored state, or 400 with the failure's class and message. */
  private static void handle(HttpExchange exchange, Catalog openhouse) throws IOException {
    int status;
    Map<String, Object> result;
    try {
      String name =
          URLDecoder.decode(
              exchange.getRequestURI().getRawQuery().replaceFirst("^name=", ""),
              StandardCharsets.UTF_8);
      result = stored(openhouse.loadTable(TableIdentifier.parse(name)));
      status = 200;
    } catch (Exception e) {
      result = new LinkedHashMap<>();
      result.put("error", e.getClass().getName() + ": " + e.getMessage());
      status = 400;
    }
    byte[] response = JSON.writeValueAsBytes(result);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, response.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(response);
    }
  }

  /** What the existing Java client shows for a freshly loaded table. */
  private static Map<String, Object> stored(Table table) throws IOException {
    Map<String, Object> stored = new LinkedHashMap<>();
    List<String> columns = new ArrayList<>();
    for (Types.NestedField column : table.schema().columns()) {
      columns.add(
          column.name()
              + " "
              + column.type()
              + (column.isRequired() ? " not null" : "")
              + (column.doc() == null ? "" : " '" + column.doc() + "'"));
    }
    stored.put("columns", columns);
    stored.put("partitioning", table.spec().fields().toString());
    stored.put("properties", new TreeMap<>(table.properties()));

    List<Map<String, Object>> snapshots = new ArrayList<>();
    List<Long> reached = new ArrayList<>();
    Map<String, Object> refs = new TreeMap<>();
    Map<String, Object> rows = new TreeMap<>();
    for (Map.Entry<String, SnapshotRef> ref : table.refs().entrySet()) {
      refs.put(ref.getKey(), ref.getValue().isBranch() ? "branch" : "tag");
      // A reader of a branch or tag gets the table's current columns.
      rows.put(ref.getKey(), rows(table, ref.getValue().snapshotId(), table.schema()));
      for (Snapshot ancestor = table.snapshot(ref.getValue().snapshotId());
          ancestor != null;
          ancestor = ancestor.parentId() == null ? null : table.snapshot(ancestor.parentId())) {
        reached.add(ancestor.snapshotId());
      }
    }
    int position = 0;
    for (Snapshot snapshot : table.snapshots()) {
      position++;
      Map<String, Object> described = new LinkedHashMap<>();
      described.put("id", snapshot.snapshotId());
      described.put("operation", snapshot.operation());
      described.put("summary", new TreeMap<>(snapshot.summary()));
      boolean reachable = reached.contains(snapshot.snapshotId());
      described.put("reachable", reachable);
      if (!reachable) {
        // A reader of a snapshot by its id gets the columns the snapshot was written with.
        Schema written =
            snapshot.schemaId() == null ? table.schema() : table.schemas().get(snapshot.schemaId());
        rows.put("snapshot " + position, rows(table, snapshot.snapshotId(), written));
      }
      snapshots.add(described);
    }
    stored.put("snapshots", snapshots);
    stored.put("refs", refs);
    stored.put("rows", rows);
    return stored;
  }

  /**
   * The rows of {@code snapshotId} with its deletes applied, in the columns of {@code schema},
   * comma-joined and sorted.
   */
  private static List<String> rows(Table table, long snapshotId, Schema schema) throws IOException {
    List<String> rows = new ArrayList<>();
    try (CloseableIterable<Record> records =
        IcebergGenerics.read(table).useSnapshot(snapshotId).project(schema).build()) {
      for (Record record : records) {
        List<String> values = new ArrayList<>();
        // By name: a record read from a file that has deletes also carries the row's position.
        for (Types.NestedField column : schema.columns()) {
          values.add(String.valueOf(record.getField(column.name())));
        }
        rows.add(String.join(",", values));
      }
    }
    Collections.sort(rows);
    return rows;
  }

  /** The existing OpenHouse Java client, as the Spark 3.5 tests configure it. */
  private static Catalog javaClient(int port, String token) {
    Map<String, String> properties = new HashMap<>();
    properties.put("uri", "http://localhost:" + port);
    properties.put("cluster", "local-cluster");
    properties.put("auth-token", token);
    return CatalogUtil.loadCatalog(
        "com.linkedin.openhouse.javaclient.OpenHouseCatalog",
        "openhouse",
        properties,
        new Configuration());
  }
}
