package com.linkedin.openhouse.tablestest.rest;

import com.linkedin.openhouse.tablestest.OpenHouseLocalServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.io.IOUtils;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.catalog.Catalog;
import org.apache.spark.sql.SparkSession;
import scala.collection.JavaConverters;

/**
 * Base class for Spark 4.0 / Iceberg 1.11 integration tests running against an embedded OpenHouse
 * server through the REST-first path.
 *
 * <p>This is the Spark-4.0 counterpart of {@link
 * com.linkedin.openhouse.tablestest.OpenHouseSparkITest}. The key difference is the catalog wiring:
 * the 3.5 lane points a custom {@code com.linkedin.openhouse.spark.OpenHouseCatalog} at the {@code
 * /tables} service, whereas the 4.0 lane points the STOCK {@code
 * org.apache.iceberg.rest.RESTCatalog} at the OpenHouse {@code /iceberg/v1/*} endpoint ({@code
 * IcebergRestCatalogController}). Client and server therefore share ONE Iceberg (1.11.0-openhouse)
 * — no custom Spark runtime jar — so the in-JVM two-Iceberg-version collision that sinks the legacy
 * 1.5-client/1.11-server e2e lane cannot arise here.
 *
 * <p>The catalog configuration mirrors the delta-harness ({@code
 * harness.OpenHouseEnv.wireCatalog}), which validates this same stack.
 *
 * <p>The singleton {@link OpenHouseLocalServer} is lazily started (double-checked locking) and
 * shuts down when the JVM exits. Do not {@link SparkSession#close()} a SparkSession you did not
 * create locally; the tests below create per-test sessions in try-with-resources.
 */
public class OpenHouseRestSparkITest {
  private static final String LOCALHOST = "http://localhost:";
  private static final String LOCAL_FS = "file:///";
  private static final String CATALOG = "openhouse";

  private static volatile OpenHouseLocalServer openHouseLocalServer = null;

  private static void startOpenHouseLocalServer() {
    if (openHouseLocalServer == null) {
      synchronized (OpenHouseRestSparkITest.class) {
        if (openHouseLocalServer == null) {
          OpenHouseLocalServer server = new OpenHouseLocalServer();
          server.start();
          openHouseLocalServer = server;
        }
      }
    }
  }

  /** @return the base {@link String} URI of the embedded OpenHouse server. */
  protected String getOpenHouseServerBaseUri() {
    startOpenHouseLocalServer();
    return LOCALHOST + openHouseLocalServer.getPort();
  }

  /** REST bearer token the {@code RESTCatalog} presents (matches the harness / dummy.token). */
  private static String authToken() {
    try {
      return IOUtils.toString(
          Objects.requireNonNull(
              OpenHouseRestSparkITest.class.getClassLoader().getResourceAsStream("dummy.token")),
          StandardCharsets.UTF_8);
    } catch (IOException | NullPointerException e) {
      return "default-token";
    }
  }

  protected SparkSession getSparkSession() {
    startOpenHouseLocalServer();
    return getBuilder().getOrCreate();
  }

  /**
   * @return a SparkSession.Builder wired to the OpenHouse REST catalog. Subclasses may add config
   *     overrides before calling {@code getOrCreate()}.
   */
  protected SparkSession.Builder getBuilder() {
    startOpenHouseLocalServer();
    String restUri = getOpenHouseServerBaseUri() + "/iceberg";
    return SparkSession.builder()
        .master("local[1]")
        // Currently only the stock Iceberg extension is loaded. The OpenHouse SQL extension
        // (SET/UNSET POLICY, GRANT, column tags) is INTENDED to be loaded here too and mapped to
        // table-property operations on the stock RESTCatalog, but that is not yet wired: the
        // extension exists only as a Scala-2.12 spark-3.x runtime, and the /iceberg server has no
        // translation for the policy property + reserves policies/openhouse.* props. Tracked as a
        // real backlog item (NOT a permanent design choice) — see
        // spark4-e2e-tests/backlog-triage.md.
        .config(
            "spark.sql.extensions",
            "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
        .config("spark.sql.catalog." + CATALOG, "org.apache.iceberg.spark.SparkCatalog")
        .config(
            "spark.sql.catalog." + CATALOG + ".catalog-impl", "org.apache.iceberg.rest.RESTCatalog")
        .config("spark.sql.catalog." + CATALOG + ".uri", restUri)
        .config("spark.sql.catalog." + CATALOG + ".token", authToken())
        .config("spark.hadoop.fs.defaultFS", LOCAL_FS)
        .config("spark.sql.session.timeZone", "UTC")
        .config("spark.sql.autoBroadcastJoinThreshold", "-1")
        .config("spark.driver.bindAddress", "127.0.0.1")
        // Spark 4.0: the local executor fetches generated (codegen) classes over the driver's netty
        // RPC. Without pinning the driver host, the driver advertises the box hostname while
        // binding
        // to 127.0.0.1, so the fetch is refused (RemoteClassLoaderError) — which in turn poisons
        // Iceberg 1.11's FormatModelRegistry.<clinit> (it reflectively probes an optional Flink
        // format-model class on the executor thread, whose ExecutorClassLoader converts the miss
        // into a fatal remote-load error instead of a swallowable ClassNotFoundException).
        // bindAddress alone sufficed on Spark 3.5; Spark 4.0 needs the host pinned too. Mirrors
        // harness.OpenHouseEnv.
        .config("spark.driver.host", "127.0.0.1")
        .config("spark.ui.enabled", "false");
  }

  /**
   * Loads the stock Iceberg {@link Catalog} (a {@code RESTCatalog}) backing the {@code openhouse}
   * Spark catalog, for tests that drive the Iceberg Java API directly rather than Spark SQL.
   */
  protected Catalog getOpenHouseCatalog(SparkSession spark) {
    final Map<String, String> catalogProperties = new HashMap<>();
    final String prefix = "spark.sql.catalog." + CATALOG + ".";
    final Map<String, String> sparkProperties = JavaConverters.mapAsJavaMap(spark.conf().getAll());
    for (Map.Entry<String, String> entry : sparkProperties.entrySet()) {
      if (entry.getKey().startsWith(prefix) && !entry.getKey().equals(prefix + "catalog-impl")) {
        catalogProperties.put(entry.getKey().substring(prefix.length()), entry.getValue());
      }
    }
    return CatalogUtil.loadCatalog(
        "org.apache.iceberg.rest.RESTCatalog",
        CATALOG,
        catalogProperties,
        spark.sparkContext().hadoopConfiguration());
  }
}
