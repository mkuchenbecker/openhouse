package com.linkedin.openhouse.spark.catalogtest;

/**
 * Runs the Spark SQL smoke suite against the REST-shaped path: Iceberg's {@code RESTCatalog} over
 * an in-process client that dispatches each request to today's {@code OpenHouseCatalog}.
 */
public class RestShimPathSparkSqlSmokeTest extends SparkSqlSmokeTest {
  @Override
  protected String catalogPath() {
    return REST_SHIM;
  }
}
