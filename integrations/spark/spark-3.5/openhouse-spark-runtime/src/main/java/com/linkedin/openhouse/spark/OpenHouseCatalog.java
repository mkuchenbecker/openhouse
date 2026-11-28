package com.linkedin.openhouse.spark;

import com.linkedin.openhouse.javaclient.IcebergLine;
import java.util.Collections;
import java.util.Set;

/**
 * Catalog implementation to create, read, update and delete tables in OpenHouse. This class
 * leverages Openhouse tableclient to perform CRUD operations on Tables resource in the Catalog
 * service. This implementation provides client side catalog implementation for Iceberg tables in
 * Spark.
 *
 * <p>Catalog can be instantiated as a Iceberg catalog, with following configurations:
 * spark.sql.catalog.openhouse=org.apache.iceberg.spark.SparkCatalog
 * spark.sql.catalog.openhouse.catalog-impl=com.linkedin.openhouse.spark.OpenHouseCatalog
 * spark.sql.catalog.openhouse.metrics-reporter-impl=com.linkedin.openhouse.javaclient.OpenHouseMetricsReporter
 * spark.sql.catalog.openhouse.uri=http://[openhouse service host]:[openhouse service port]
 * spark.sql.catalog.openhouse.cluster=[openhouse cluster name]
 *
 * <p>It can be used in spark shell as follows: spark.sql("USE openhouse")
 */
public class OpenHouseCatalog extends com.linkedin.openhouse.javaclient.OpenHouseCatalog {

  /**
   * Declares {@link #COLUMN_DEFAULTS_CAPABILITY} on the legacy Iceberg line: Spark reads rows
   * through Iceberg's readers, which apply the column defaults this catalog loads into the schema.
   *
   * <p>On the v3 line ({@link IcebergLine}) it declares nothing. That line applies no read-bridge
   * overlay, so a table that requires the capability is refused by the service (422 {@code
   * CLIENT_CAPABILITY_MISSING}) instead of being read differently by legacy and new clients. Its
   * column defaults are native format v3 defaults, which need no capability.
   */
  @Override
  protected Set<String> capabilities() {
    return IcebergLine.current().usesReadBridge()
        ? Collections.singleton(COLUMN_DEFAULTS_CAPABILITY)
        : Collections.emptySet();
  }
}
