package com.linkedin.openhouse.tablestest;

import com.linkedin.openhouse.jobs42.JobsTestServiceProcess;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.catalog.Catalog;
import org.apache.spark.sql.SparkSession;
import scala.collection.JavaConverters;

/**
 * The Spark 4.2 counterpart of the tables-test-fixtures class of the same name, with the same
 * methods, so that the shared apps tests compile and run unchanged on Spark 4.2. Two differences:
 *
 * <ul>
 *   <li>the tables service runs in its own JVM ({@link JobsTestServiceProcess}), not embedded: it
 *       cannot share the Spark 4.2 JVM;
 *   <li>the {@code openhouse} and {@code default_iceberg} catalogs are Iceberg's own SparkCatalog
 *       over Iceberg's RESTCatalog at the service's {@code /iceberg} routes, not the OpenHouse
 *       catalog (Spark 4.2 has no OpenHouse catalog class: the REST-only platform).
 * </ul>
 *
 * <p>System property {@code jobs42.catalog.mode}: {@code impl} (default) configures the catalogs
 * with {@code catalog-impl=org.apache.iceberg.rest.RESTCatalog}; {@code type} configures them with
 * {@code type=rest}, as a platform typically would.
 */
public class OpenHouseSparkITest {
  /** The token the fixtures' {@code dummy.token} holds (the services' dummy interceptor's user). */
  public static final String DUMMY_TOKEN =
      "eyJhbGciOiJIUzI1NiJ9.eyJpYXQiOjE2NTk2ODI4MDAsImp0aSI6IkRVTU1ZX0FOT05ZTU9VU19VU0VSZGJjNDk3MTMt"
          + "MzM5ZC00Y2ZkLTkwMDgtZDY4NzlhZDQwZjE2Iiwic3ViIjoie1wiQ09ERVwiOlwiRFVNTVlfQ09ERVwiLFwiVVNF"
          + "Ui1JRFwiOlwiRFVNTVlfQU5PTllNT1VTX1VTRVJcIn0ifQ.W2WVBrMacFrXS8Xa29k_V_yD0yca2nEet5mSYq27Ayo";

  private static volatile JobsTestServiceProcess service = null;

  protected SparkSession getSparkSession() throws Exception {
    return getBuilder().getOrCreate();
  }

  protected SparkSession getSparkSession(String overrideCatalogName, Map<String, String> overrides)
      throws Exception {
    SparkSession.Builder builder = getBuilder();
    configureCatalog(builder, overrideCatalogName, service().ircUri());
    for (Map.Entry<String, String> entry : overrides.entrySet()) {
      builder.config(entry.getKey(), entry.getValue());
    }
    return builder.getOrCreate();
  }

  static JobsTestServiceProcess service() throws Exception {
    if (service == null) {
      synchronized (OpenHouseSparkITest.class) {
        if (service == null) {
          service = JobsTestServiceProcess.start();
        }
      }
    }
    return service;
  }

  protected SparkSession.Builder getBuilder() throws Exception {
    JobsTestServiceProcess s = service();
    SparkSession.Builder builder =
        SparkSession.builder()
            .master("local[1]")
            .config(
                "spark.sql.extensions",
                "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions,"
                    + "com.linkedin.openhouse.spark.extensions.OpenhouseSparkSessionExtensions")
            .config("spark.hadoop.fs.defaultFS", "file:///")
            .config("spark.sql.session.timeZone", "UTC")
            .config("spark.sql.autoBroadcastJoinThreshold", "-1")
            .config("spark.driver.memory", "2g")
            .config("spark.ui.enabled", "false")
            .config("spark.driver.bindAddress", "127.0.0.1")
            .config("spark.driver.host", "127.0.0.1")
            .config(
                "spark.sql.warehouse.dir", s.work().resolve("spark-warehouse").toUri().toString());
    configureCatalog(builder, "openhouse", s.ircUri());
    configureCatalog(builder, "default_iceberg", s.ircUri());
    return builder;
  }

  /** {@code name} as Iceberg's SparkCatalog over RESTCatalog at {@code ircUri}. */
  public static void configureCatalog(SparkSession.Builder builder, String name, String ircUri) {
    String prefix = "spark.sql.catalog." + name;
    builder.config(prefix, "org.apache.iceberg.spark.SparkCatalog");
    if ("type".equals(System.getProperty("jobs42.catalog.mode", "impl"))) {
      builder.config(prefix + ".type", "rest");
    } else {
      builder.config(prefix + ".catalog-impl", "org.apache.iceberg.rest.RESTCatalog");
    }
    builder.config(prefix + ".uri", ircUri).config(prefix + ".token", DUMMY_TOKEN);
  }

  /** The native API's base URI (the jobs' tables client and the tests' direct API calls use it). */
  protected URI getOpenHouseLocalServerURI() {
    if (service == null) {
      throw new RuntimeException(
          "OH server is not running locally. Start it by calling getSparkSession() method.");
    }
    return URI.create(service.uri());
  }

  /** The session's {@code openhouse} catalog as an Iceberg catalog (here a RESTCatalog). */
  protected Catalog getOpenHouseCatalog(SparkSession spark) {
    final Map<String, String> catalogProperties = new HashMap<>();
    final String catalogPropertyPrefix = "spark.sql.catalog.openhouse.";
    final Map<String, String> sparkProperties = JavaConverters.mapAsJavaMap(spark.conf().getAll());
    for (Map.Entry<String, String> entry : sparkProperties.entrySet()) {
      if (entry.getKey().startsWith(catalogPropertyPrefix)) {
        catalogProperties.put(
            entry.getKey().substring(catalogPropertyPrefix.length()), entry.getValue());
      }
    }
    return CatalogUtil.buildIcebergCatalog(
        "openhouse", catalogProperties, spark.sparkContext().hadoopConfiguration());
  }
}
