package com.linkedin.openhouse.javaclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.iceberg.IcebergBuild;
import org.junit.jupiter.api.Test;

/**
 * {@link IcebergLine} detects the Iceberg line this client runs on. The expectation comes from an
 * independent source, the version Iceberg's own build stamped into {@code iceberg-build.properties}
 * ({@link IcebergBuild#version()}): LinkedIn's 1.2 and 1.5 releases are the legacy line, the fork's
 * 1.12 builds are the v3 line. This test checks the default build's 1.12 line and the {@code
 * -PicebergLinkedIn} build's 1.5 line; it also ran on the 1.2 line until that module was deleted.
 */
class IcebergLineTest {

  private static final Pattern MAJOR_MINOR = Pattern.compile("^(\\d+)\\.(\\d+)\\.");

  @Test
  void detectsTheLineOfTheIcebergOnTheClasspath() {
    assertEquals(
        expectedFromBuildVersion(IcebergBuild.version()),
        IcebergLine.current(),
        "Iceberg build version " + IcebergBuild.version());
  }

  @Test
  void detectionIsStable() {
    assertSame(IcebergLine.current(), IcebergLine.current());
  }

  @Test
  void onlyTheLegacyLineUsesTheReadBridge() {
    assertTrue(IcebergLine.LEGACY.usesReadBridge());
    assertFalse(IcebergLine.V3.usesReadBridge());
  }

  private static IcebergLine expectedFromBuildVersion(String version) {
    Matcher m = MAJOR_MINOR.matcher(version);
    if (!m.find()) {
      throw new AssertionError("Unrecognized Iceberg build version: " + version);
    }
    int major = Integer.parseInt(m.group(1));
    int minor = Integer.parseInt(m.group(2));
    if (major == 1 && (minor == 2 || minor == 5)) {
      return IcebergLine.LEGACY;
    }
    if (major == 1 && minor >= 12) {
      return IcebergLine.V3;
    }
    throw new AssertionError("No expected line for Iceberg build version " + version);
  }
}
