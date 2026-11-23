package com.linkedin.openhouse.tables.e2e.h2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
 * An unmodified Iceberg {@link RESTCatalog}, over HTTP and configured with nothing but the server's
 * address: after a commit and after a scan it posts the report Iceberg builds to the metrics route,
 * and the service counts each by its type. (Until the route was served the config told the client
 * not to report, and the route answered 406.)
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

  private RESTCatalog rest;

  @BeforeEach
  void start() throws Exception {
    Map<String, String> properties = new HashMap<>();
    properties.put("uri", "http://localhost:" + port + "/iceberg");
    properties.put("token", new DummySecurityJWT("irc_reporter").buildNoopJWT());
    rest =
        (RESTCatalog)
            CatalogUtil.loadCatalog(
                RESTCatalog.class.getName(), "openhouse", properties, new Configuration());
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

  @Test
  void theClientIsNotToldToKeepItsReports() {
    assertFalse(
        rest.properties().containsKey("rest-metrics-reporting-enabled"),
        rest.properties().toString());
  }

  @Test
  void aCommitReportAndAScanReportArrive() throws Exception {
    Table table = rest.loadTable(ID);
    double commits = received("commit_report");
    double scans = received("scan_report");

    DataFile file =
        DataFiles.builder(table.spec())
            .withPath(table.location() + "/data/irc_metrics_rest_a.orc")
            .withFileSizeInBytes(10)
            .withRecordCount(2)
            .build();
    table.newFastAppend().appendFile(file).commit();
    awaitReceived("commit_report", commits + 1);

    int planned = 0;
    try (CloseableIterable<FileScanTask> tasks = table.newScan().planFiles()) {
      for (FileScanTask ignored : tasks) {
        planned++;
      }
    }
    assertEquals(1, planned);
    awaitReceived("scan_report", scans + 1);
    assertEquals(commits + 1, received("commit_report"));
  }
}
