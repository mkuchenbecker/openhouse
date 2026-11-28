package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.spark.smoke.LinkedInMainComparison;
import com.linkedin.openhouse.spark.smoke.LinkedInMainRecording;
import com.linkedin.openhouse.spark.smoke.SparkSqlWriteStatements;
import com.linkedin.openhouse.spark.smoke.StoredTable;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.util.ArrayList;
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
 * Records LinkedIn main's value of every write-statement check on main's only catalog path, the
 * OpenHouse catalog of Spark 3.5 over the native tables API, reading what the service stored as the
 * suite's other platforms do: the metadata through the OpenHouse Java client, the rows through a
 * second OpenHouse catalog with no cache. Test-side code only, copied into a checkout of main by the
 * recording script; it never runs on the branch.
 */
public class LinkedInMainWriteStatementsRecorder extends SparkSqlWriteStatements {

  private static final String PATH =
      "the OpenHouse catalog of Spark 3.5 over the native tables API";

  private static final String CHECK = "openhouse_check";

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
  private final LinkedInMainComparison recorder;

  public LinkedInMainWriteStatementsRecorder() throws Exception {
    recorder = new LinkedInMainComparison("write-statements", Collections.emptyList());
  }

  @Override
  protected void value(String test, String key, String actual) {
    recorder.observe(test, key, actual);
  }

  @Override
  protected synchronized SparkSession sparkSession() throws Exception {
    SparkSession base = FIXTURE.session();
    if (spark == null || spark.sparkContext() != base.sparkContext()) {
      SparkSession session = base.newSession();
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

  private List<String> rows(String query) throws Exception {
    List<String> rows =
        sparkSession().sql(query).collectAsList().stream()
            .map(row -> row.mkString(","))
            .collect(Collectors.toList());
    Collections.sort(rows);
    return rows;
  }

  @AfterAll
  void writeRecording() throws Exception {
    recorder.endSuite(LinkedInMainRecording.header(PATH), null);
  }
}
