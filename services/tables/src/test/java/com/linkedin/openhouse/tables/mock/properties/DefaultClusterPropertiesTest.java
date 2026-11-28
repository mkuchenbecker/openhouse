package com.linkedin.openhouse.tables.mock.properties;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.internal.catalog.HouseTableRowLimits;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

@SpringBootTest
@ContextConfiguration(initializers = DefaultClusterPropertiesInitializer.class)
public class DefaultClusterPropertiesTest {

  @Autowired private ClusterProperties clusterProperties;

  @Test
  public void testClusterProperties() {
    Assertions.assertEquals("local-cluster", clusterProperties.getClusterName());
  }

  @Test
  public void testClusterPropertiesIcebergOrcCompressionCodecDefaultsToNull() {
    Assertions.assertNull(clusterProperties.getClusterIcebergWriteOrcCompressionCodec());
  }

  /**
   * By default no metadata location length is checked: writes reach House Tables as they did before
   * the check existed, and the store decides.
   */
  @Test
  public void testHouseTablesMetadataLocationMaxLengthDefaultsToNoCheck() {
    Assertions.assertEquals(0, clusterProperties.getClusterHouseTablesMetadataLocationMaxLength());
    Assertions.assertEquals(
        HouseTableRowLimits.NO_LIMIT,
        new HouseTableRowLimits(clusterProperties).getMetadataLocationMaxLength());
  }

  /** By default no rename target's length is checked either. */
  @Test
  public void testHouseTablesTableIdMaxLengthDefaultsToNoCheck() {
    Assertions.assertEquals(0, clusterProperties.getClusterHouseTablesTableIdMaxLength());
    Assertions.assertEquals(
        HouseTableRowLimits.NO_LIMIT,
        new HouseTableRowLimits(clusterProperties).getTableIdMaxLength());
  }

  @AfterAll
  static void unsetSysProp() {
    System.clearProperty("OPENHOUSE_CLUSTER_CONFIG_PATH");
  }
}
