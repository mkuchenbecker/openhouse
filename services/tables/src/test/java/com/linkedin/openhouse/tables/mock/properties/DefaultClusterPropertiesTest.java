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

  /** The default is the production store's column, which {@link HouseTableRowLimits} states. */
  @Test
  public void testHouseTablesMetadataLocationMaxLengthDefaultsToTheProductionColumn() {
    Assertions.assertEquals(
        HouseTableRowLimits.DEFAULT_METADATA_LOCATION_MAX_LENGTH,
        clusterProperties.getClusterHouseTablesMetadataLocationMaxLength());
    Assertions.assertEquals(
        255, new HouseTableRowLimits(clusterProperties).getMetadataLocationMaxLength());
  }

  /** The rename target's limit defaults to the production store's name column (work item V19). */
  @Test
  public void testHouseTablesTableIdMaxLengthDefaultsToTheProductionColumn() {
    Assertions.assertEquals(
        HouseTableRowLimits.DEFAULT_TABLE_ID_MAX_LENGTH,
        clusterProperties.getClusterHouseTablesTableIdMaxLength());
    Assertions.assertEquals(255, new HouseTableRowLimits(clusterProperties).getTableIdMaxLength());
  }

  @AfterAll
  static void unsetSysProp() {
    System.clearProperty("OPENHOUSE_CLUSTER_CONFIG_PATH");
  }
}
