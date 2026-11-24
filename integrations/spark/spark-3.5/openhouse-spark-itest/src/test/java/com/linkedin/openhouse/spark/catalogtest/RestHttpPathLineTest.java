package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.javaclient.IcebergLine;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The rest-http smoke path and its write checks run on both Iceberg lines the build has, with no
 * assumption that skips them (work item N10; before, the path was skipped on the LinkedIn Iceberg
 * 1.5.2 line). Their line-specific declarations follow {@link IcebergLine#current()}: this pins it
 * to the line the build states ({@code smoke.iceberg.build}, set from {@code -PicebergLinkedIn}),
 * so a build cannot run one line's declarations against the other line.
 */
public class RestHttpPathLineTest {

  @Test
  void theLineTheDeclarationsFollowIsTheBuildsLine() {
    String build = System.getProperty("smoke.iceberg.build");
    Assertions.assertNotNull(build, "smoke.iceberg.build is not set by the build");
    boolean iceberg112 = build.equals("iceberg-1.12");
    Assertions.assertTrue(iceberg112 || build.equals("linkedin-1.5.2"), build);
    Assertions.assertEquals(
        iceberg112 ? IcebergLine.V3 : IcebergLine.LEGACY, IcebergLine.current());
  }
}
