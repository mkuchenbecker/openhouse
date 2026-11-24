package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.spark.smoke.SparkSqlWriteStatements;
import com.linkedin.openhouse.spark.smoke.StoredTable;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotRef;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.types.Types;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;

/**
 * The write statements ({@link SparkSqlWriteStatements}, Spark 4.2's checks, work item N10) on the
 * Spark 3.5 plain REST path: Iceberg's own {@code SparkCatalog} with {@code type=rest} over HTTP to
 * the shared embedded service's Iceberg REST routes, as {@code RestHttpPathSparkSqlSmokeTest}
 * configures it. {@link #differences()} is the complete list of checks on which it differs from
 * Spark 4.2 over IRC. On both Iceberg lines.
 *
 * <p>What the service stored is read without the path under test: the table's metadata through the
 * OpenHouse Java client, the rows of each ref and of each snapshot no ref reaches through the
 * OpenHouse catalog in Spark 3.5 (catalog {@value #CHECK}, no cache), the baseline catalog path of
 * the smoke suite. Spark 4.2 reads the rows with Iceberg's generic reader in the service's JVM;
 * both apply delete files.
 */
public class RestHttpPathWriteStatementsTest extends SparkSqlWriteStatements {

  /** The OpenHouse catalog the stored rows are read through. */
  private static final String CHECK = "openhouse_check";

  /** The shared embedded service and session of the catalog tests. */
  private static final class Fixture extends OpenHouseSparkITest {
    SparkSession session() throws Exception {
      return getSparkSession();
    }

    String uri() {
      return getOpenHouseLocalServerURI().toString();
    }
  }

  private static final Fixture FIXTURE = new Fixture();

  private SparkSession spark;
  private Catalog javaClient;

  @Override
  protected synchronized SparkSession sparkSession() throws Exception {
    SparkSession base = FIXTURE.session();
    if (spark == null || spark.sparkContext() != base.sparkContext()) {
      SparkSession session = base.newSession();
      SparkSqlSmokeTest.restCatalog(session, CATALOG, FIXTURE.uri(), base);
      String check = "spark.sql.catalog." + CHECK;
      session.conf().set(check, "org.apache.iceberg.spark.SparkCatalog");
      session.conf().set(check + ".catalog-impl", "com.linkedin.openhouse.spark.OpenHouseCatalog");
      session.conf().set(check + ".uri", FIXTURE.uri());
      session.conf().set(check + ".cluster", "local-cluster");
      session.conf().set(check + ".auth-token", token(base));
      session.conf().set(check + ".cache-enabled", "false");
      Map<String, String> client = new HashMap<>();
      client.put("uri", FIXTURE.uri());
      client.put("cluster", "local-cluster");
      client.put("auth-token", token(base));
      javaClient =
          CatalogUtil.loadCatalog(
              "com.linkedin.openhouse.javaclient.OpenHouseCatalog",
              "openhouse_client",
              client,
              base.sparkContext().hadoopConfiguration());
      spark = session;
    }
    return spark;
  }

  private static String token(SparkSession base) {
    return base.conf().get("spark.sql.catalog.openhouse.auth-token");
  }

  @Override
  protected StoredTable stored(String name) throws Exception {
    sparkSession();
    Table table = javaClient.loadTable(TableIdentifier.parse(name));
    Map<String, Object> stored = new LinkedHashMap<>();
    List<String> columns = new ArrayList<>();
    for (Types.NestedField column : table.schema().columns()) {
      columns.add(
          column.name()
              + " "
              + column.type()
              + (column.isRequired() ? " not null" : "")
              + (column.doc() == null ? "" : " '" + column.doc() + "'"));
    }
    stored.put("columns", columns);
    stored.put("partitioning", table.spec().fields().toString());
    stored.put("properties", new TreeMap<>(table.properties()));

    String read = CHECK + "." + name;
    List<Long> reached = new ArrayList<>();
    Map<String, Object> refs = new TreeMap<>();
    Map<String, Object> rows = new TreeMap<>();
    for (Map.Entry<String, SnapshotRef> ref : table.refs().entrySet()) {
      boolean branch = ref.getValue().isBranch();
      refs.put(ref.getKey(), branch ? "branch" : "tag");
      // A reader of a branch or tag gets the table's current columns.
      String source =
          ref.getKey().equals("main")
              ? read
              : read + "." + (branch ? "branch_" : "tag_") + ref.getKey();
      rows.put(ref.getKey(), rows("SELECT * FROM " + source));
      for (Snapshot ancestor = table.snapshot(ref.getValue().snapshotId());
          ancestor != null;
          ancestor = ancestor.parentId() == null ? null : table.snapshot(ancestor.parentId())) {
        reached.add(ancestor.snapshotId());
      }
    }
    List<Map<String, Object>> snapshots = new ArrayList<>();
    int position = 0;
    for (Snapshot snapshot : table.snapshots()) {
      position++;
      Map<String, Object> described = new LinkedHashMap<>();
      described.put("id", snapshot.snapshotId());
      described.put("operation", snapshot.operation());
      described.put("summary", new TreeMap<>(snapshot.summary()));
      boolean reachable = reached.contains(snapshot.snapshotId());
      described.put("reachable", reachable);
      if (!reachable) {
        // A reader of a snapshot by its id gets the columns the snapshot was written with.
        rows.put(
            "snapshot " + position,
            rows("SELECT * FROM " + read + " VERSION AS OF " + snapshot.snapshotId()));
      }
      snapshots.add(described);
    }
    stored.put("snapshots", snapshots);
    stored.put("refs", refs);
    stored.put("rows", rows);
    return new StoredTable(stored);
  }

  /** The rows of the query, comma-joined and sorted. */
  private List<String> rows(String query) throws Exception {
    List<String> rows =
        sparkSession().sql(query).collectAsList().stream()
            .map(row -> row.mkString(","))
            .collect(Collectors.toList());
    Collections.sort(rows);
    return rows;
  }

  @Override
  protected String outcomesFile() {
    String dir = System.getProperty("smoke.outcomes.dir");
    return dir == null ? null : dir + "/spark-3.5-rest-http-write-outcomes.tsv";
  }

  /**
   * A difference that Spark 3.5 makes on any catalog: the smoke suite's s22 shows it on every path.
   */
  static final String SPARK_35 = "Spark 3.5";

  /**
   * The checks on which this path differs from Spark 4.2 over IRC: one, the engine's. The same on
   * both Iceberg lines.
   */
  @Override
  protected List<Difference> differences() {
    List<Difference> differences = new ArrayList<>();
    differences.add(
        Difference.on(
                "replaceTableAsSelectReplacesSchemaAndRowsAndKeepsPropertiesWhereReplaceIsEnabled",
                "operations")
            .baseline(Arrays.asList("append", "overwrite"))
            .here(Arrays.asList("append", "append"))
            .because(
                SPARK_35,
                "Spark 3.5 plans the write of a REPLACE TABLE AS SELECT as an append, where Spark"
                    + " 4 plans an overwrite (V2CreateTableAsSelectBaseExec.writeToTable), and"
                    + " Iceberg names the snapshot after it. The smoke suite's s22 has the append on"
                    + " every Spark 3.5 path, the OpenHouse catalog included, and declares the"
                    + " overwrite for Spark 4.2 (kind b). Schema, rows, properties and the kept"
                    + " snapshot are the same."));
    return differences;
  }

  /** As the other catalog tests: close the shared session, so the next class builds its own. */
  @AfterAll
  static void releaseSession() {
    scala.Option<SparkSession> active = SparkSession.getDefaultSession();
    if (active.isDefined()) {
      active.get().close();
    }
  }
}
