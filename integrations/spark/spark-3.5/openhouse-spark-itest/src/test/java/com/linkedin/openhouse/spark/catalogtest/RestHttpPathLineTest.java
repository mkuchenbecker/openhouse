package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.javaclient.IcebergLine;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The rest-http smoke path is skipped on purpose, not by accident: its assumption holds exactly
 * when the build is the Iceberg 1.12 line, as the build itself states it ({@code
 * smoke.iceberg.build}, set from {@code -PicebergLinkedIn}).
 */
public class RestHttpPathLineTest {

  @Test
  void theRestHttpPathRunsExactlyOnTheIceberg112Build() {
    String build = System.getProperty("smoke.iceberg.build");
    Assertions.assertNotNull(build, "smoke.iceberg.build is not set by the build");
    boolean fork = build.equals("iceberg-1.12");
    Assertions.assertTrue(fork || build.equals("linkedin-1.5.2"), build);
    Assertions.assertEquals(fork ? IcebergLine.V3 : IcebergLine.LEGACY, IcebergLine.current());
    Assertions.assertEquals(fork, RestHttpPathSparkSqlSmokeTest.runsOnThisLine());
  }
}
