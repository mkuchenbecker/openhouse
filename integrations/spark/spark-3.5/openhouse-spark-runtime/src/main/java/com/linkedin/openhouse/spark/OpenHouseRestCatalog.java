package com.linkedin.openhouse.spark;

/**
 * The Spark catalog plugin over the REST-shaped path: Iceberg's {@code RESTCatalog} over an
 * in-process client that dispatches each request to today's Spark {@link OpenHouseCatalog}.
 *
 * <p>Opt-in, selected by {@code catalog-impl}:
 * spark.sql.catalog.openhouse=org.apache.iceberg.spark.SparkCatalog
 * spark.sql.catalog.openhouse.catalog-impl=com.linkedin.openhouse.spark.OpenHouseRestCatalog (other
 * properties as for {@link OpenHouseCatalog}). Today's {@link OpenHouseCatalog} stays the default
 * and is unchanged.
 */
public class OpenHouseRestCatalog extends com.linkedin.openhouse.javaclient.OpenHouseRestCatalog {

  /**
   * The Spark {@link OpenHouseCatalog}, so every request still declares the {@code column-defaults}
   * capability, as it does today.
   */
  @Override
  protected com.linkedin.openhouse.javaclient.OpenHouseCatalog newBackingCatalog() {
    return new OpenHouseCatalog();
  }
}
