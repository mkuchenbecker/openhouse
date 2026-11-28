package com.linkedin.openhouse.spark42.smoke;

import com.linkedin.openhouse.spark.smoke.SparkSqlRowLevelStatements;
import java.util.HashMap;
import java.util.Map;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;

/**
 * The row-level statements on format v3 tables ({@link SparkSqlRowLevelStatements}, the one list
 * the Spark 3.5 paths run too) from Spark 4.2 on Iceberg 1.12 over IRC, with Iceberg's and
 * OpenHouse's SQL extensions in the session ({@link SmokeSpark}), against a second smoke service
 * with the format v3 switch on ({@link SmokeTestService#getWithFormatV3()}). No check differs here.
 * What the service stored is loaded through Iceberg's own {@code RESTCatalog} in this JVM, without
 * Spark.
 *
 * <p>Not in the server-planning run ({@code smokeTestServerPlanning}, see the build file): the plan
 * route refuses format v3 tables, so the list would run there on client planning again.
 */
public class SparkIrcRowLevelStatementsTest extends SparkSqlRowLevelStatements {

  private SparkSession spark;
  private Catalog plain;

  @Override
  protected synchronized SparkSession sparkSession() throws Exception {
    if (spark == null) {
      SmokeTestService service = SmokeTestService.getWithFormatV3();
      SparkSession session = SmokeSpark.session();
      String prefix = "spark.sql.catalog." + CATALOG;
      session.conf().set(prefix, "org.apache.iceberg.spark.SparkCatalog");
      session.conf().set(prefix + ".type", "rest");
      session.conf().set(prefix + ".uri", service.uri() + "/iceberg");
      session.conf().set(prefix + ".token", service.token());
      Map<String, String> properties = new HashMap<>();
      properties.put("uri", service.uri() + "/iceberg");
      properties.put("token", service.token());
      plain =
          CatalogUtil.loadCatalog(
              "org.apache.iceberg.rest.RESTCatalog",
              "rowlevel_plain",
              properties,
              session.sparkContext().hadoopConfiguration());
      spark = session;
    }
    return spark;
  }

  @Override
  protected Table plainTable(String table) throws Exception {
    sparkSession();
    return plain.loadTable(TableIdentifier.of(DB, table));
  }

  /** This itest exists only on the Iceberg 1.12 line (not in the -PicebergLinkedIn build). */
  @Override
  protected boolean buildLineHasFormatV3() {
    return true;
  }

  /** Next to the smoke suite's outcomes file. */
  @Override
  protected String outcomesFile() {
    String smoke = System.getProperty("smoke.outcomes.file");
    return smoke == null ? null : smoke.replace("-outcomes.tsv", "-row-level-outcomes.tsv");
  }

  /** Drops the tables and closes the plain catalog; the service stops with the JVM. */
  @AfterAll
  void dropTheTables() throws Exception {
    if (plain != null) {
      for (TableIdentifier table : plain.listTables(Namespace.of(DB))) {
        plain.dropTable(table);
      }
      ((java.io.Closeable) plain).close();
    }
  }
}
