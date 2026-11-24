package com.linkedin.openhouse.jobs.spark;

import com.linkedin.openhouse.jobs42.JobsTestServiceProcess;

/**
 * The Spark 4.2 counterpart of apps/spark-3.5's class of the same name: a second tables service
 * with {@code cluster.iceberg.format-v3.enabled} and variant columns on, in its own JVM. {@link
 * #uri()} is what {@link FormatV3Tables} sets as the catalogs' {@code uri}: the service's Iceberg
 * REST Catalog routes, since on Spark 4.2 those catalogs are Iceberg's RESTCatalog.
 */
final class FormatV3TablesServer implements AutoCloseable {
  private final JobsTestServiceProcess service;

  FormatV3TablesServer() {
    try {
      service =
          JobsTestServiceProcess.start(
              "--cluster.iceberg.format-v3.enabled=true",
              "--cluster.iceberg.format-v3.variant.enabled=true");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  String uri() {
    return service.ircUri();
  }

  @Override
  public void close() {
    service.close();
  }
}
