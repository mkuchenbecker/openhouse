package com.linkedin.openhouse.jobs.spark;

import com.linkedin.openhouse.common.metrics.DefaultOtelConfig;
import com.linkedin.openhouse.common.metrics.OtelEmitter;
import com.linkedin.openhouse.jobs.util.AppsOtelEmitter;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.io.Closeable;
import java.util.Arrays;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.types.Types;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The catalog {@link Operations} loads tables through (work item N16).
 *
 * <p>Each catalog holds a client: on Spark 3.5 the OpenHouse catalog, whose client keeps a
 * connection pool that evicts in the background and is never disposed, so it stays reachable for
 * the life of the JVM; on Spark 4.2 Iceberg's RESTCatalog, which asks the service for its config
 * ({@code GET /config}) when it is built. The number of catalogs an {@link Operations} builds is
 * the observable: it does not depend on the collector.
 *
 * <p>The same outcomes on both lines (OpenHouse catalog and RESTCatalog).
 */
public class OperationsCatalogTest extends OpenHouseSparkITest {
  /** Table loads in one run, as a batched job over a few hundred tables makes. */
  private static final int LOADS = 300;

  private final OtelEmitter otelEmitter =
      new AppsOtelEmitter(Arrays.asList(DefaultOtelConfig.getOpenTelemetry()));

  /**
   * A run that loads tables hundreds of times, through {@link Operations#getTable} and through a
   * job entry point that loads by name, builds the catalogs counted here.
   */
  @Test
  void manyTableLoadsBuildCatalogs() throws Exception {
    String fqtn = "db.catalog_reuse_loads";
    try (Operations ops = Operations.withCatalog(getSparkSession(), otelEmitter)) {
      createTable(ops.spark(), fqtn);
      for (int i = 0; i < LOADS; i++) {
        Assertions.assertNotNull(ops.getTable(fqtn).currentSnapshot());
      }
      ops.expireSnapshots(fqtn, 3, "DAYS", 1, false);
      // Pinned to today: one catalog (and one never-disposed client) per table load.
      Assertions.assertEquals(LOADS + 1, ops.catalogsBuilt());
    }
  }

  /**
   * {@link Operations#getTable} returns the table as it is now: after another client commits a
   * snapshot, changes the schema or sets a property, the next load on the same {@link Operations}
   * sees it, whatever the {@link Operations} loaded before.
   */
  @Test
  void getTableSeesWhatOtherClientsCommitted() throws Exception {
    String fqtn = "db.catalog_reuse_fresh";
    TableIdentifier id = TableIdentifier.parse(fqtn);
    try (Operations ops = Operations.withCatalog(getSparkSession(), otelEmitter)) {
      SparkSession spark = ops.spark();
      createTable(spark, fqtn);
      Table before = ops.getTable(fqtn);
      long first = before.currentSnapshot().snapshotId();

      // A snapshot committed through the session's own catalog (another catalog instance).
      spark.sql(String.format("INSERT INTO openhouse.%s VALUES ('b', current_timestamp())", fqtn));
      Table afterInsert = ops.getTable(fqtn);
      Assertions.assertNotEquals(first, afterInsert.currentSnapshot().snapshotId());
      Assertions.assertEquals(first, afterInsert.currentSnapshot().parentId());

      Catalog other = getOpenHouseCatalog(spark);
      try {
        // A schema change, then a property, committed by another client.
        other.loadTable(id).updateSchema().addColumn("extra", Types.StringType.get()).commit();
        Assertions.assertNotNull(ops.getTable(fqtn).schema().findField("extra"));

        other.loadTable(id).updateProperties().set("n16.fresh", "yes").commit();
        Table now = ops.getTable(fqtn);
        Assertions.assertEquals("yes", now.properties().get("n16.fresh"));
        Assertions.assertNotNull(now.schema().findField("extra"));
        Assertions.assertEquals(
            other.loadTable(id).currentSnapshot().snapshotId(), now.currentSnapshot().snapshotId());
      } finally {
        if (other instanceof Closeable) {
          ((Closeable) other).close();
        }
      }
    }
  }

  private static void createTable(SparkSession spark, String fqtn) {
    spark.sql("DROP TABLE IF EXISTS openhouse." + fqtn);
    spark.sql(String.format("CREATE TABLE openhouse.%s (data string, ts timestamp)", fqtn));
    spark.sql(String.format("INSERT INTO openhouse.%s VALUES ('a', current_timestamp())", fqtn));
  }
}
