package com.linkedin.openhouse.spark42.smoke;

import com.linkedin.openhouse.spark.smoke.SparkSqlWriteStatements;
import com.linkedin.openhouse.spark.smoke.StoredTable;
import org.apache.spark.sql.SparkSession;

/**
 * The write statements ({@link SparkSqlWriteStatements}, the one list the Spark 3.5 plain REST path
 * runs too) from Spark 4.2 on Iceberg 1.12 over IRC, the platform they were written for: their
 * baseline, so no check differs here. What the service stored is read through the OpenHouse Java
 * client in the service's JVM ({@link SmokeTestService#stored}), without Spark.
 */
public class SparkIrcWriteStatementsTest extends SparkSqlWriteStatements {

  @Override
  protected SparkSession sparkSession() throws Exception {
    return SmokeSpark.session();
  }

  @Override
  protected StoredTable stored(String name) throws Exception {
    return SmokeTestService.get().stored(name);
  }

  /** Next to the smoke suite's outcomes file. */
  @Override
  protected String outcomesFile() {
    String smoke = System.getProperty("smoke.outcomes.file");
    return smoke == null ? null : smoke.replace("-outcomes.tsv", "-write-outcomes.tsv");
  }
}
