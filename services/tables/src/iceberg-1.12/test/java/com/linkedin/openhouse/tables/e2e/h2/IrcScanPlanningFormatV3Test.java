package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.FormatV3.SWITCH_ON;
import static com.linkedin.openhouse.tables.e2e.h2.ScanPlanning.dataFile;
import static com.linkedin.openhouse.tables.e2e.h2.ScanPlanning.plan;
import static com.linkedin.openhouse.tables.e2e.h2.ScanPlanning.planRequests;
import static com.linkedin.openhouse.tables.e2e.h2.ScanPlanning.record;
import static com.linkedin.openhouse.tables.e2e.h2.ScanPlanning.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import java.util.List;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.RowDelta;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;

/**
 * Scan planning on the server equals scan planning on the client for a format v3 table with
 * deletion vectors (the cluster switch {@code cluster.iceberg.format-v3.enabled} on): each data
 * file comes with its deletion vector, a Puffin blob named by content offset and size and by the
 * data file it applies to, and the rows read are the same.
 */
@SpringBootTest(
    classes = SpringH2Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = SWITCH_ON)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
@Import({IrcReadRoutesTest.StubDefaults.class, ScanPlanning.RecordRequests.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IrcScanPlanningFormatV3Test {
  private static final String DB = "irc_scan_plan_v3";

  @LocalServerPort private int port;

  private RESTCatalog client;
  private RESTCatalog server;

  @BeforeEach
  void start() {
    client = ScanPlanning.clientPlanning(port);
    server = ScanPlanning.serverPlanning(port);
  }

  @AfterEach
  void stop() throws Exception {
    try {
      client.dropTable(TableIdentifier.of(DB, "dv"));
    } finally {
      client.close();
      server.close();
    }
  }

  @Test
  void deletionVectorsArePlannedTheSame() {
    TableIdentifier id = TableIdentifier.of(DB, "dv");
    Table table =
        client
            .buildTable(id, IrcScanPlanningTest.SCHEMA)
            .withPartitionSpec(
                PartitionSpec.builderFor(IrcScanPlanningTest.SCHEMA).identity("p").build())
            .withProperty("format-version", "3")
            .create();
    TableMetadata metadata = ((HasTableOperations) table).operations().current();
    assertEquals(3, metadata.formatVersion());
    DataFile a =
        dataFile(
            table,
            record(IrcScanPlanningTest.SCHEMA, 1L, "a", "p0"),
            record(IrcScanPlanningTest.SCHEMA, 2L, "b", "p0"),
            record(IrcScanPlanningTest.SCHEMA, 3L, "c", "p0"));
    DataFile b =
        dataFile(
            table,
            record(IrcScanPlanningTest.SCHEMA, 4L, "d", "p1"),
            record(IrcScanPlanningTest.SCHEMA, 5L, "e", "p1"));
    DataFile c = dataFile(table, record(IrcScanPlanningTest.SCHEMA, 6L, "f", "p2"));
    table.newAppend().appendFile(a).appendFile(b).appendFile(c).commit();
    RowDelta delta = table.newRowDelta();
    ScanPlanning.deletionVector(table, a, 0L, 2L).forEach(delta::addDeletes);
    List<DeleteFile> onB = ScanPlanning.deletionVector(table, b, 1L);
    onB.forEach(delta::addDeletes);
    delta.commit();
    assertEquals(FileFormat.PUFFIN, onB.get(0).format());

    int before = planRequests().size();
    List<String> onClient = plan(client.loadTable(id).newScan());
    assertEquals(before, planRequests().size());
    List<String> onServer = plan(server.loadTable(id).newScan());
    assertEquals(before + 1, planRequests().size());
    assertEquals(onClient, onServer);
    assertEquals(3, onServer.size());
    assertEquals(
        2, onServer.stream().filter(t -> t.contains("PUFFIN") && t.contains("offset=")).count());
    for (String task : onServer) {
      if (task.contains("PUFFIN")) {
        assertTrue(task.contains("referenced=" + task.substring(0, task.indexOf(" | "))), task);
      }
    }

    List<String> read = rows(client.loadTable(id), Expressions.alwaysTrue());
    assertEquals(3, read.size(), read.toString());
    assertEquals(read, rows(server.loadTable(id), Expressions.alwaysTrue()));
    assertEquals(
        plan(client.loadTable(id).newScan().filter(Expressions.equal("p", "p1"))),
        plan(server.loadTable(id).newScan().filter(Expressions.equal("p", "p1"))));
    assertEquals(
        rows(client.loadTable(id), Expressions.greaterThan("id", 1L), "id"),
        rows(server.loadTable(id), Expressions.greaterThan("id", 1L), "id"));
  }
}
