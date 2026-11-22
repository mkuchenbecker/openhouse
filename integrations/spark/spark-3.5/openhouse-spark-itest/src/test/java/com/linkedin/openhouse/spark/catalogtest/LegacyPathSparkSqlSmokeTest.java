package com.linkedin.openhouse.spark.catalogtest;

/** Runs the Spark SQL smoke suite against the legacy catalog path, the only path today. */
public class LegacyPathSparkSqlSmokeTest extends SparkSqlSmokeTest {
  @Override
  protected String catalogPath() {
    return LEGACY;
  }
}
