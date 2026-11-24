package com.linkedin.openhouse.jobs.spark;

import org.apache.spark.sql.SparkSession;

/**
 * Which line the apps tests run on, for the outcomes that differ between lines. The same test
 * sources run on two lines:
 *
 * <ul>
 *   <li>Spark 3.5 ({@code :apps:openhouse-spark-apps-1.5_2.12}): the session's {@code openhouse}
 *       catalog is the OpenHouse catalog, over the tables service's native API;
 *   <li>Spark 4.2 ({@code :apps:openhouse-spark-apps-4.2_2.13}): it is Iceberg's own RESTCatalog at
 *       the tables service's Iceberg REST routes; Spark 4.2 has no OpenHouse catalog.
 * </ul>
 *
 * <p>A test that expects a different outcome on one line asks here and states the difference and
 * its reason where it asserts it. Every other outcome is the same on both lines.
 */
final class JobsLine {
  private static final String CATALOG = "spark.sql.catalog.openhouse";

  private JobsLine() {}

  /**
   * Whether Spark has the {@code variant} type (Spark 4 does, Spark 3.5 does not), so that it can
   * read a table with a variant column, as the jobs and their closing validation do.
   */
  static boolean sparkReadsVariant() {
    try {
      Class.forName("org.apache.spark.sql.types.VariantType");
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  /**
   * Whether {@code spark}'s {@code openhouse} catalog is Iceberg's RESTCatalog ({@code type=rest},
   * or a {@code catalog-impl} that is a RESTCatalog), rather than the OpenHouse catalog.
   */
  static boolean restCatalog(SparkSession spark) {
    if ("rest".equals(spark.conf().get(CATALOG + ".type", null))) {
      return true;
    }
    String impl = spark.conf().get(CATALOG + ".catalog-impl", null);
    if (impl == null) {
      return false;
    }
    try {
      return org.apache.iceberg.rest.RESTCatalog.class.isAssignableFrom(Class.forName(impl));
    } catch (ClassNotFoundException e) {
      return false;
    }
  }
}
