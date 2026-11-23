package com.linkedin.openhouse.tables.e2e.h2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.linkedin.openhouse.common.security.DummyTokenInterceptor.DummySecurityJWT;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;

/**
 * An unmodified Iceberg {@link RESTCatalog}, over HTTP. Configured to report ({@code
 * rest-metrics-reporting-enabled=true}), after a commit and after a scan it posts the report
 * Iceberg builds to the metrics route, and the service counts each by its type (the route used to
 * answer 406). Configured with nothing but the server's address, it takes the config's default and
 * posts nothing.
 */
@SpringBootTest(
    classes = SpringH2Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
@Import(IrcReadRoutesTest.StubDefaults.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IrcRestCatalogMetricsTest {
  private static final TableIdentifier ID = TableIdentifier.of("irc_metrics_rest", "t");

  @LocalServerPort private int port;

  @Autowired MeterRegistry meterRegistry;

  /** The catalog a client gets with nothing configured but the server's address. */
  private RESTCatalog rest;

  /** The same, with reporting turned on by the client. */
  private RESTCatalog reporting;

  private RESTCatalog catalog(boolean report) throws Exception {
    Map<String, String> properties = new HashMap<>();
    properties.put("uri", "http://localhost:" + port + "/iceberg");
    properties.put("token", new DummySecurityJWT("irc_reporter").buildNoopJWT());
    if (report) {
      properties.put("rest-metrics-reporting-enabled", "true");
    }
    return (RESTCatalog)
        CatalogUtil.loadCatalog(
            RESTCatalog.class.getName(), "openhouse", properties, new Configuration());
  }

  @BeforeEach
  void start() throws Exception {
    rest = catalog(false);
    reporting = catalog(true);
    rest.createTable(
        ID,
        new Schema(Types.NestedField.required(1, "id", Types.LongType.get())),
        PartitionSpec.unpartitioned());
  }

  @AfterEach
  void stop() throws Exception {
    try {
      rest.dropTable(ID);
    } finally {
      rest.close();
      reporting.close();
    }
  }

  private double received(String type) {
    return meterRegistry
        .counter(IrcMetricsRouteTest.COUNTER, IrcMetricsRouteTest.TYPE_TAG, type)
        .count();
  }

  /** Iceberg 1.12's client posts reports from a background thread; 1.5.2's posts them inline. */
  private void awaitReceived(String type, double expected) throws Exception {
    long deadline = System.nanoTime() + 20_000_000_000L;
    while (received(type) < expected && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
    assertEquals(expected, received(type), type);
  }

  private static DataFile file(Table table, String name) {
    return DataFiles.builder(table.spec())
        .withPath(table.location() + "/data/" + name + ".orc")
        .withFileSizeInBytes(10)
        .withRecordCount(2)
        .build();
  }

  private static int scan(Table table) throws Exception {
    int planned = 0;
    try (CloseableIterable<FileScanTask> tasks = table.newScan().planFiles()) {
      for (FileScanTask ignored : tasks) {
        planned++;
      }
    }
    return planned;
  }

  @Test
  void aClientThatAsksToReportHasItsCommitAndScanReportsCounted() throws Exception {
    assertEquals("true", reporting.properties().get("rest-metrics-reporting-enabled"));
    Table table = reporting.loadTable(ID);
    double commits = received("commit_report");
    double scans = received("scan_report");

    table.newFastAppend().appendFile(file(table, "irc_metrics_rest_a")).commit();
    awaitReceived("commit_report", commits + 1);

    assertEquals(1, scan(table));
    awaitReceived("scan_report", scans + 1);
    assertEquals(commits + 1, received("commit_report"));
  }

  @Test
  void aClientConfiguredWithNothingTakesTheDefaultAndPostsNoReport() throws Exception {
    assertEquals("false", rest.properties().get("rest-metrics-reporting-enabled"));
    Table table = rest.loadTable(ID);
    double commits = received("commit_report");
    double scans = received("scan_report");

    table.newFastAppend().appendFile(file(table, "irc_metrics_rest_b")).commit();
    assertEquals(1, scan(table));
    // Long enough for Iceberg 1.12's background reporter, had it been given a report.
    Thread.sleep(1500);
    assertEquals(commits, received("commit_report"));
    assertEquals(scans, received("scan_report"));
  }
}
