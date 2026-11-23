package com.linkedin.openhouse.spark42.extensions;

import org.apache.spark.sql.SparkSession;

/**
 * One Spark 4.2 session for every test in this JVM, with Iceberg's own SQL extensions and the
 * OpenHouse SQL extensions ({@code spark.sql.extensions}, in that order, as Spark 3.5 users
 * configure them) and Iceberg's own {@code SparkCatalog} over Iceberg's own {@code RESTCatalog}
 * ({@code type=rest}) pointed at the service's IRC base path. No OpenHouse catalog class is
 * configured. Its own JVM: the extensions are fixed when the session is built, and the IRC read
 * tests' session ({@code IrcSpark}) has none.
 *
 * <ul>
 *   <li>{@code irc}: a principal the service allows, configured as a user would.
 *   <li>{@code irc_adv}: the same, and it also claims {@code
 *       openhouse.capabilities=policies,grants,unlock} on the client side; the server's own config
 *       override wins, so it must behave as {@code irc}.
 *   <li>{@code local}: Iceberg's Hadoop catalog on a local directory, for tables that are not
 *       OpenHouse tables.
 * </ul>
 */
final class ExtensionsSpark {

  static final String IRC = "irc";
  static final String IRC_ADVERTISED = "irc_adv";
  static final String LOCAL = "local";

  private static SparkSession spark;

  private ExtensionsSpark() {}

  static synchronized SparkSession session() throws Exception {
    if (spark == null) {
      ExtensionsTestService service = ExtensionsTestService.get();
      SparkSession.Builder builder =
          SparkSession.builder()
              .master("local[1]")
              .appName("openhouse-spark-4.2-irc-extensions")
              .config("spark.ui.enabled", "false")
              .config("spark.sql.shuffle.partitions", "1")
              .config("spark.driver.bindAddress", "127.0.0.1")
              .config("spark.driver.host", "127.0.0.1")
              .config("spark.sql.session.timeZone", "UTC")
              .config(
                  "spark.sql.extensions",
                  "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions,"
                      + "com.linkedin.openhouse.spark.extensions.OpenhouseSparkSessionExtensions")
              .config(
                  "spark.sql.warehouse.dir",
                  service.work().resolve("spark-warehouse").toUri().toString());
      for (String name : new String[] {IRC, IRC_ADVERTISED}) {
        String prefix = "spark.sql.catalog." + name;
        builder
            .config(prefix, "org.apache.iceberg.spark.SparkCatalog")
            .config(prefix + ".type", "rest")
            .config(prefix + ".uri", service.uri() + "/iceberg")
            .config(prefix + ".token", service.token(ExtensionsTestService.WRITER));
      }
      builder.config(
          "spark.sql.catalog." + IRC_ADVERTISED + ".openhouse.capabilities",
          "policies,grants,unlock");
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

  /** "ok", or the failure's class and message, as the statement's outcome. */
  static String outcome(String sql) throws Exception {
    SparkSession session = session();
    try {
      session.sql(sql).collectAsList();
      return "ok";
    } catch (Throwable failure) {
      return failure.getClass().getName() + ": " + failure.getMessage();
    }
  }
}
