package com.linkedin.openhouse.javaclient;

import java.util.HashMap;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.hadoop.HadoopFileIO;
import org.apache.iceberg.io.FileIO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The FileIO the catalog builds when no {@code io-impl} is configured. Spark always hands the
 * catalog a Hadoop configuration (setConf) before initialize; plain Java callers may not.
 */
public class OpenHouseCatalogFileIOTest {

  private static Map<String, String> properties() {
    Map<String, String> properties = new HashMap<>();
    properties.put(CatalogProperties.URI, "http://localhost:1");
    return properties;
  }

  private static FileIO fileIO(OpenHouseCatalog catalog) {
    return catalog.newTableOps(TableIdentifier.of("db", "t")).io();
  }

  @Test
  public void initializesWithoutAHadoopConfiguration() {
    OpenHouseCatalog catalog = new OpenHouseCatalog();
    Assertions.assertDoesNotThrow(() -> catalog.initialize("openhouse", properties()));
    Assertions.assertTrue(fileIO(catalog) instanceof HadoopFileIO);
  }

  @Test
  public void usesTheHadoopConfigurationItWasGiven() {
    Configuration conf = new Configuration(false);
    conf.set("openhouse.test.key", "openhouse-test-value");
    OpenHouseCatalog catalog = new OpenHouseCatalog();
    catalog.setConf(conf);
    catalog.initialize("openhouse", properties());
    FileIO io = fileIO(catalog);
    Assertions.assertTrue(io instanceof HadoopFileIO);
    Assertions.assertEquals(
        "openhouse-test-value", ((HadoopFileIO) io).conf().get("openhouse.test.key"));
  }
}
