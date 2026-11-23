package com.linkedin.openhouse.spark42.irc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

/**
 * One Spark 4.2 session for every test in this JVM, with Iceberg's own {@code SparkCatalog} over
 * Iceberg's own {@code RESTCatalog} ({@code type=rest}) pointed at the service's IRC base path. No
 * OpenHouse catalog or client class is configured or on the classpath. The OpenHouse SQL extensions
 * jar is on the classpath (for the extensions tests, which build their own session in their own
 * JVM), but this session does not load it. The catalogs differ only in the bearer token they send:
 *
 * <ul>
 *   <li>{@code irc}: a principal the service allows ({@value #READER}).
 *   <li>{@code irc_stranger}: a principal the service's authorization denies ({@value #STRANGER}).
 *   <li>{@code irc_badtoken}: a token the service's token interceptor does not accept.
 * </ul>
 */
final class IrcSpark {

  static final String DB = "irc42";
  static final String READER = "irc_reader";
  static final String STRANGER = "irc_stranger";

  private static SparkSession spark;

  private IrcSpark() {}

  static synchronized SparkSession session() throws Exception {
    if (spark == null) {
      IrcReadTestServiceProcess service = IrcReadTestServiceProcess.get();
      SparkSession.Builder builder =
          SparkSession.builder()
              .master("local[1]")
              .appName("openhouse-spark-4.2-irc-read")
              .config("spark.ui.enabled", "false")
              .config("spark.sql.shuffle.partitions", "1")
              .config("spark.driver.bindAddress", "127.0.0.1")
              .config("spark.driver.host", "127.0.0.1")
              .config("spark.sql.session.timeZone", "UTC")
              .config(
                  "spark.sql.warehouse.dir",
                  service.work().resolve("spark-warehouse").toUri().toString());
      catalog(builder, "irc", service, service.token(READER));
      catalog(builder, "irc_stranger", service, service.token(STRANGER));
      catalog(builder, "irc_badtoken", service, "not-a-token");
      spark = builder.getOrCreate();
    }
    return spark;
  }

  private static void catalog(
      SparkSession.Builder builder, String name, IrcReadTestServiceProcess service, String token) {
    String prefix = "spark.sql.catalog." + name;
    builder
        .config(prefix, "org.apache.iceberg.spark.SparkCatalog")
        .config(prefix + ".type", "rest")
        .config(prefix + ".uri", service.uri() + "/iceberg")
        .config(prefix + ".token", token);
  }

  static IrcReadTestServiceProcess service() throws Exception {
    return IrcReadTestServiceProcess.get();
  }

  /** The rows of {@code sql}, each as a list of its values. */
  static List<List<Object>> rows(String sql) throws Exception {
    List<List<Object>> rows = new ArrayList<>();
    for (Row row : session().sql(sql).collectAsList()) {
      List<Object> values = new ArrayList<>();
      for (int i = 0; i < row.size(); i++) {
        values.add(row.get(i));
      }
      rows.add(values);
    }
    return rows;
  }

  static List<Object> row(Object... values) {
    return Arrays.asList(values);
  }

  /** The first column of every row of {@code sql}, as strings. */
  static List<String> column(String sql) throws Exception {
    return rows(sql).stream().map(r -> String.valueOf(r.get(0))).collect(Collectors.toList());
  }

  /** What {@code sql} throws; fails if it succeeds. */
  static Throwable failure(String sql) throws Exception {
    SparkSession session = session();
    try {
      session.sql(sql).collectAsList();
    } catch (Throwable t) {
      return t;
    }
    throw new AssertionError("expected " + sql + " to fail, but it succeeded");
  }
}
