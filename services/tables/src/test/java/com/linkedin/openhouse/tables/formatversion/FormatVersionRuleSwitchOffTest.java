package com.linkedin.openhouse.tables.formatversion;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.tables.api.ApiConfig;
import com.linkedin.openhouse.tables.model.TableDto;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The format version rule with the format v3 switch off: every write answers as it did before the
 * service knew format v3, and the cluster's own format version cannot make tables v3.
 */
class FormatVersionRuleSwitchOffTest {

  private static ClusterProperties cluster(int version, boolean switchOn) {
    ClusterProperties cluster = mock(ClusterProperties.class);
    when(cluster.getClusterIcebergFormatVersion()).thenReturn(version);
    when(cluster.isClusterIcebergFormatV3Enabled()).thenReturn(switchOn);
    return cluster;
  }

  private static Map<String, String> asking(String version) {
    return Collections.singletonMap(FormatVersionRule.PROPERTY, version);
  }

  /** A cluster at format version 3 with the switch off does not start. */
  @Test
  void aClusterVersionAboveTwoNeedsTheSwitch() {
    for (int version : new int[] {3, 4}) {
      IllegalStateException refused =
          assertThrows(
              IllegalStateException.class,
              () -> new ApiConfig().formatVersionCommitPolicy(cluster(version, false)));
      assertEquals(
          "cluster.iceberg.format-version is "
              + version
              + ", and cluster.iceberg.format-v3.enabled is false: tables above format version 2"
              + " need cluster.iceberg.format-v3.enabled=true. Set cluster.iceberg.format-version"
              + " to 2 or lower, or turn format version 3 on.",
          refused.getMessage());
    }
    for (int version : new int[] {1, 2}) {
      assertDoesNotThrow(() -> new ApiConfig().formatVersionCommitPolicy(cluster(version, false)));
    }
    assertDoesNotThrow(() -> new ApiConfig().formatVersionCommitPolicy(cluster(3, true)));
  }

  /** A create is stored at the cluster's version whatever it asks for. */
  @Test
  void aCreateIsAtTheClustersVersionWhateverItAsksFor() {
    FormatVersionRule rule = new FormatVersionRule(cluster(2, false));
    for (String asked : new String[] {"1", "2", "3", "4", "x"}) {
      assertEquals(2, rule.versionOfCreate("Create", "db.t", asking(asked)));
    }
  }

  /**
   * An update that asks for a version above 2 gets Iceberg 1.5.2's refusal, from the rule where
   * Iceberg could build the version, and from Iceberg itself where it cannot; any other update is
   * Iceberg's to answer.
   */
  @Test
  void anUpdateAboveTwoGetsIcebergsRefusalAndAnyOtherIsLeftToIceberg() {
    FormatVersionRule rule = new FormatVersionRule(cluster(2, false));
    for (int asked : new int[] {3, 4, 5}) {
      if (IcebergV3.supported()) {
        IllegalArgumentException refused =
            assertThrows(
                IllegalArgumentException.class,
                () -> rule.checkUpdate("db.t", 2, asking(Integer.toString(asked))));
        assertEquals(
            "Cannot upgrade table to unsupported format version: v" + asked + " (supported: v2)",
            refused.getMessage());
      } else {
        assertDoesNotThrow(() -> rule.checkUpdate("db.t", 2, asking(Integer.toString(asked))));
      }
    }
    for (String asked : new String[] {"1", "2", "x"}) {
      assertDoesNotThrow(() -> rule.checkUpdate("db.t", 2, asking(asked)));
    }
    assertDoesNotThrow(() -> rule.checkUpdate("db.t", 1, asking("2")));
    // A table at v3, made while the switch was on, is left to Iceberg too.
    assertDoesNotThrow(() -> rule.checkUpdate("db.t", 3, asking("2")));
  }

  /** Asking for v3 does not make a write a write to a v3 table while the switch is off. */
  @Test
  void askingForV3IsNotAV3WriteWithTheSwitchOff() {
    TableDto incoming = TableDto.builder().tableProperties(asking("3")).build();
    assertFalse(FormatVersionRule.writesFormatV3(cluster(2, false), null, incoming));
    assertFalse(FormatVersionRule.writesFormatV3(null, null, incoming));
    assertTrue(FormatVersionRule.writesFormatV3(cluster(2, true), null, incoming));
  }
}
