package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.ScanPlanning.dataFile;
import static com.linkedin.openhouse.tables.e2e.h2.ScanPlanning.plan;
import static com.linkedin.openhouse.tables.e2e.h2.ScanPlanning.record;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;

/**
 * A scan that names a column added after the snapshot it reads. Spark 4.2 pins a read of main to
 * the current snapshot: it builds {@code filter(f).project(tableSchema) .useSnapshot(current)}, so
 * Iceberg 1.12's {@code RESTTableScan} sends {@code use-snapshot-schema=true} with the names of the
 * table's current schema. Each case here makes exactly those calls on a client that plans itself
 * and on one that plans on the server, and compares what each gets: the planned tasks (data files,
 * deletes and residuals), or the failure. Client planning is the reference.
 *
 * <p>The table: {@code id} with two rows in one file, then the column {@code extra} added, then (in
 * some cases) one more file with {@code extra = 7}.
 */
@SpringBootTest(
    classes = SpringH2Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ScanPlanning.ENABLED)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
@Import({IrcReadRoutesTest.StubDefaults.class, ScanPlanning.RecordRequests.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IrcScanPlanningLateColumnTest {
  private static final String DB = "irc_scan_plan_late";
  private static final Schema BEFORE =
      new Schema(Types.NestedField.optional(1, "id", Types.LongType.get()));

  @LocalServerPort private int port;

  private RESTCatalog client;
  private RESTCatalog server;
  private final List<TableIdentifier> created = new ArrayList<>();

  @BeforeEach
  void start() {
    client = ScanPlanning.clientPlanning(port);
    server = ScanPlanning.serverPlanning(port);
  }

  @AfterEach
  void stop() throws Exception {
    try {
      for (TableIdentifier identifier : created) {
        client.dropTable(identifier);
      }
      created.clear();
    } finally {
      client.close();
      server.close();
    }
  }

  /** The table, its first snapshot written before {@code extra} was added; returns it. */
  private TableIdentifier table(String name, boolean snapshotAfter) {
    TableIdentifier identifier = TableIdentifier.of(DB, name);
    created.add(identifier);
    Table table = client.createTable(identifier, BEFORE, PartitionSpec.unpartitioned());
    table.newAppend().appendFile(dataFile(table, record(BEFORE, 1L), record(BEFORE, 2L))).commit();
    table.updateSchema().addColumn("extra", Types.IntegerType.get()).commit();
    if (snapshotAfter) {
      table.refresh();
      table.newAppend().appendFile(dataFile(table, record(table.schema(), 3L, 7))).commit();
    }
    return identifier;
  }

  /**
   * What a scan of {@code catalog}'s table, narrowed by {@code narrow}, plans: tasks or failure.
   */
  private static String outcome(
      RESTCatalog catalog, TableIdentifier identifier, UnaryOperator<TableScan> narrow) {
    try {
      return "plans " + plan(narrow.apply(catalog.loadTable(identifier).newScan()));
    } catch (RuntimeException e) {
      // Only that it fails is compared: the client's own failure and the service's 400 have
      // different classes.
      System.out.println("LATE-COLUMN " + identifier + " fails: " + e);
      return "fails";
    }
  }

  /**
   * Spark's calls for a read of {@code snapshot} (null: the current one) with {@code filter} (null:
   * none), projecting {@code columns} of the table's current schema.
   */
  private UnaryOperator<TableScan> spark(
      TableIdentifier identifier, Long snapshot, Expression filter, String... columns) {
    Table table = client.loadTable(identifier);
    long id = snapshot != null ? snapshot : table.currentSnapshot().snapshotId();
    Schema projection = table.schema().select(columns);
    return scan -> {
      TableScan narrowed = filter == null ? scan : scan.filter(filter);
      return narrowed.project(projection).useSnapshot(id);
    };
  }

  /** The server-planned outcome equals the client-planned one; returns it. */
  private String assertSameAsClientPlanning(
      TableIdentifier identifier, UnaryOperator<TableScan> narrow) {
    String onClient = outcome(client, identifier, narrow);
    assertEquals(onClient, outcome(server, identifier, narrow));
    return onClient;
  }

  /**
   * With no snapshot since the column was added, a filter that is true for nulls, one that is false
   * for nulls, and a projection alone plan what client planning plans (the snapshot's schema lacks
   * the column, so the service resolves the names as the client meant them).
   */
  @Test
  void noSnapshotSinceTheColumnWasAdded() {
    TableIdentifier id = table("no_snapshot_since", false);
    for (Expression filter :
        new Expression[] {Expressions.isNull("extra"), Expressions.equal("extra", 7), null}) {
      String outcome = assertSameAsClientPlanning(id, spark(id, null, filter, "id", "extra"));
      assertTrue(outcome.startsWith("plans [") && outcome.contains(".parquet"), outcome);
    }
    // Case-insensitive names resolve the same way.
    String outcome =
        assertSameAsClientPlanning(
            id,
            scan ->
                spark(id, null, Expressions.isNull("EXTRA"), "id", "extra")
                    .apply(scan.caseSensitive(false)));
    assertTrue(outcome.startsWith("plans ["), outcome);
  }

  @Test
  void oneSnapshotWrittenSinceTheColumnWasAdded() {
    TableIdentifier id = table("one_snapshot_since", true);
    for (Expression filter :
        new Expression[] {Expressions.isNull("extra"), Expressions.equal("extra", 7), null}) {
      String outcome = assertSameAsClientPlanning(id, spark(id, null, filter, "id", "extra"));
      assertTrue(outcome.startsWith("plans"), outcome);
    }
  }

  /** Time travel to the snapshot before the column existed, naming the column. */
  @Test
  void timeTravelToAnOlderSnapshotNamingTheNewerColumn() {
    TableIdentifier id = table("time_travel", true);
    Table table = client.loadTable(id);
    long first = table.snapshots().iterator().next().snapshotId();
    assertFalse(first == table.currentSnapshot().snapshotId());
    for (Expression filter :
        new Expression[] {Expressions.isNull("extra"), Expressions.equal("extra", 7), null}) {
      assertSameAsClientPlanning(id, spark(id, first, filter, "id", "extra"));
    }
  }

  /** A scan that names only columns the snapshot has plans as before, whichever schema. */
  @Test
  void aScanOfTheOlderColumnsOnlyIsUnchanged() {
    TableIdentifier id = table("old_columns", false);
    String outcome =
        assertSameAsClientPlanning(id, spark(id, null, Expressions.equal("id", 1L), "id"));
    assertTrue(outcome.startsWith("plans"), outcome);
  }
}
