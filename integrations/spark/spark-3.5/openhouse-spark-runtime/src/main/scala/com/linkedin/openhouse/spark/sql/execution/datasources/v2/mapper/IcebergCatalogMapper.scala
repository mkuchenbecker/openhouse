package com.linkedin.openhouse.spark.sql.execution.datasources.v2.mapper

import com.linkedin.openhouse.spark.capabilities.OpenHouseCapabilities
import org.apache.iceberg.catalog.Catalog
import org.apache.spark.sql.connector.catalog.TableCatalog

/**
 * The statements find OpenHouse features through {@link OpenHouseCapabilities}; this object stays for callers of its
 * old entry point and does the same unwrapping.
 */
object IcebergCatalogMapper {

  /**
   * Convert Spark's {@link TableCatalog} to Iceberg's {@link Catalog}: the catalog behind Iceberg's
   * {@link org.apache.iceberg.spark.SparkCatalog} or {@link org.apache.iceberg.spark.SparkSessionCatalog}, unwrapped
   * from {@link org.apache.iceberg.CachingCatalog} when caching is enabled.
   *
   * @return null :if it is not iceberg based catalog
   *         catalog :if iceberg catalog
   */
  def toIcebergCatalog(catalog: TableCatalog): Catalog = OpenHouseCapabilities.icebergCatalog(catalog)
}
