package com.linkedin.openhouse.spark.catalogtest;

/**
 * Runs the catalog plugin contract against the REST-shaped path: Iceberg's {@code RESTCatalog} over
 * an in-process client that dispatches each request to today's {@code OpenHouseCatalog}. Spark 3.5
 * / Iceberg 1.5 only; the seam does not exist on the 1.2 line.
 */
public class RestShimPathCatalogPluginContractTest extends CatalogPluginContractTest {
  @Override
  protected String catalogPath() {
    return REST_SHIM;
  }
}
