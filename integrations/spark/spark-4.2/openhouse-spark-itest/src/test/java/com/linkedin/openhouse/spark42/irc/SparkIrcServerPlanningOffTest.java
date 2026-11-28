package com.linkedin.openhouse.spark42.irc;

import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Against a service with the cluster default, scan planning off, a Spark 4.2 catalog configured to
 * plan on the server ({@code scan-planning-mode=server}) fails clearly, before it reads anything:
 * Iceberg 1.12's client refuses to load the table, because the config answer does not list the plan
 * route. A default catalog reads the table as always. The default run only: in {@code
 * testServerPlanning} the service serves planning.
 */
public class SparkIrcServerPlanningOffTest {

  private static final String SERVER = "off_server";

  @Test
  void aServerPlanningClientFailsClearlyWhenTheServiceDoesNotPlan() throws Exception {
    Assumptions.assumeFalse(ServerPlanning.selected(), "the service serves planning in this run");
    SparkSession spark = IrcSpark.session();
    SparkIrcServerPlanningWritesTest.catalog(spark, SERVER, IrcSpark.service(), "server");
    String table = IrcSpark.DB + ".flat";
    Throwable failure = IrcSpark.failure("SELECT * FROM " + SERVER + "." + table);
    Throwable cause = failure;
    while (cause != null && !(cause instanceof IllegalStateException)) {
      cause = cause.getCause();
    }
    Assertions.assertNotNull(cause, String.valueOf(failure));
    Assertions.assertEquals(
        "Server requires server-side scan planning for table "
            + table
            + " but does not support endpoint POST"
            + " /v1/{prefix}/namespaces/{namespace}/tables/{table}/plan",
        cause.getMessage());
    // A default catalog of the same service reads the table.
    Assertions.assertFalse(IrcSpark.rows("SELECT * FROM irc." + table).isEmpty());
  }
}
