package com.linkedin.openhouse.jobs.spark;

import com.linkedin.openhouse.common.metrics.DefaultOtelConfig;
import com.linkedin.openhouse.common.metrics.OtelEmitter;
import com.linkedin.openhouse.jobs.util.AppsOtelEmitter;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.util.Collections;
import org.apache.iceberg.Table;
import org.apache.iceberg.relocated.com.google.common.collect.Iterables;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Spark 4.2 only: the jobs' catalog named by {@code type=rest} alone, as a platform that reaches
 * OpenHouse through the Iceberg REST routes typically configures it (the rest of this suite names
 * it by {@code catalog-impl=org.apache.iceberg.rest.RESTCatalog}, because the concurrency tests
 * swap the implementation). {@link Operations#getTable} and a job run on it.
 */
public class Spark42TypeRestCatalogTest extends OpenHouseSparkITest {
  private final OtelEmitter otelEmitter =
      new AppsOtelEmitter(Collections.singletonList(DefaultOtelConfig.getOpenTelemetry()));

  @Test
  void snapshotExpiryRunsOnACatalogNamedByTypeRest() throws Exception {
    SparkSession base = getSparkSession();
    String fqtn = "db.j3_type_rest";
    base.sql("DROP TABLE IF EXISTS openhouse." + fqtn);
    base.sql("CREATE TABLE openhouse." + fqtn + " (id int)");
    base.sql("INSERT INTO openhouse." + fqtn + " VALUES (1)");
    base.sql("INSERT INTO openhouse." + fqtn + " VALUES (2)");
    SparkSession session = base.newSession();
    for (String catalog : new String[] {"openhouse", "default_iceberg"}) {
      session.conf().unset("spark.sql.catalog." + catalog + ".catalog-impl");
      session.conf().set("spark.sql.catalog." + catalog + ".type", "rest");
    }
    Assertions.assertNull(session.conf().get("spark.sql.catalog.openhouse.catalog-impl", null));
    Assertions.assertTrue(JobsLine.restCatalog(session));
    Operations ops = Operations.withCatalog(session, otelEmitter);
    Assertions.assertEquals(2, Iterables.size(ops.getTable(fqtn).snapshots()));
    new SnapshotsExpirationSparkApp("j3", null, fqtn, 0, "", 1, false, ".backup", otelEmitter)
        .runInner(ops);
    Table table = ops.getTable(fqtn);
    Assertions.assertEquals(1, Iterables.size(table.snapshots()));
    Assertions.assertEquals(2L, session.sql("SELECT * FROM openhouse." + fqtn).count());
    base.sql("DROP TABLE openhouse." + fqtn);
  }
}
