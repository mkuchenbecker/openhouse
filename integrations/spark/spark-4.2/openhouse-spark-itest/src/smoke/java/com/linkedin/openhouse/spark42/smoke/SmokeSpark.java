package com.linkedin.openhouse.spark42.smoke;

import com.linkedin.openhouse.spark.smoke.CommitRaceRestCatalog;
import org.apache.spark.sql.SparkSession;

/**
 * One Spark 4.2 session for every test in this JVM, built as the Spark 3.5 smoke suite's session is
 * ({@code TestSparkSessionUtil}: Iceberg's SQL extensions then OpenHouse's, UTC, no broadcast
 * joins), except for the catalogs: each is Iceberg's own {@code SparkCatalog} over Iceberg's own
 * {@code RESTCatalog} ({@code type=rest}) pointed at the service's IRC base path, where the Spark
 * 3.5 suite names the OpenHouse catalog class. No OpenHouse catalog class is configured.
 *
 * <ul>
 *   <li>{@code openhouse}: the catalog the smoke statements name.
 *   <li>{@code openhouse_views}: the same with {@code cache-enabled=false}, as the Spark 3.5 suite
 *       configures the catalog of its views scenarios. The service has one database with views
 *       enabled.
 *   <li>{@code openhouse_second}: the smoke suite's second catalog: Iceberg's {@code RESTCatalog}
 *       over the same routes, with no table caching, whose table commits run the step a scenario
 *       armed first ({@code CommitRaceRestCatalog}).
 *   <li>{@code local}: Iceberg's Hadoop catalog on a local directory. It has nothing of OpenHouse,
 *       so what a statement does there is what Spark 4.2 and Iceberg 1.12 do by themselves.
 * </ul>
 */
final class SmokeSpark {

  static final String OPENHOUSE = "openhouse";
  static final String OPENHOUSE_VIEWS = "openhouse_views";
  static final String LOCAL = "local";
  static final String OPENHOUSE_SECOND = "openhouse_second";

  private static SparkSession spark;

  private SmokeSpark() {}

  static synchronized SparkSession session() throws Exception {
    if (spark == null) {
      SmokeTestService service = SmokeTestService.get();
      SparkSession.Builder builder =
          SparkSession.builder()
              .master("local[1]")
              .appName("openhouse-spark-4.2-irc-smoke")
              .config("spark.ui.enabled", "false")
              .config(
                  "spark.sql.extensions",
                  "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions,"
                      + "com.linkedin.openhouse.spark.extensions.OpenhouseSparkSessionExtensions")
              .config("spark.hadoop.fs.defaultFS", "file:///")
              .config("spark.sql.session.timeZone", "UTC")
              .config("spark.sql.autoBroadcastJoinThreshold", "-1")
              .config("spark.driver.bindAddress", "127.0.0.1")
              .config("spark.driver.host", "127.0.0.1")
              .config(
                  "spark.sql.warehouse.dir",
                  service.work().resolve("spark-warehouse").toUri().toString());
      for (String name : new String[] {OPENHOUSE, OPENHOUSE_VIEWS}) {
        String prefix = "spark.sql.catalog." + name;
        builder
            .config(prefix, "org.apache.iceberg.spark.SparkCatalog")
            .config(prefix + ".type", "rest")
            .config(prefix + ".uri", service.uri() + "/iceberg")
            .config(prefix + ".token", service.token());
        ServerPlanning.apply(prefix, builder::config);
      }
      builder.config("spark.sql.catalog." + OPENHOUSE_VIEWS + ".cache-enabled", "false");
      String second = "spark.sql.catalog." + OPENHOUSE_SECOND;
      builder
          .config(second, "org.apache.iceberg.spark.SparkCatalog")
          .config(second + ".catalog-impl", CommitRaceRestCatalog.class.getName())
          .config(second + ".uri", service.uri() + "/iceberg")
          .config(second + ".token", service.token())
          .config(second + ".cache-enabled", "false");
      ServerPlanning.apply(second, builder::config);
      builder
          .config("spark.sql.catalog." + LOCAL, "org.apache.iceberg.spark.SparkCatalog")
          .config("spark.sql.catalog." + LOCAL + ".type", "hadoop")
          .config(
              "spark.sql.catalog." + LOCAL + ".warehouse",
              service.work().resolve("local-warehouse").toString());
      spark = builder.getOrCreate();
    }
    return spark;
  }
}
