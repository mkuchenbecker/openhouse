package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.body;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.rest.IrcClientParsers;
import org.apache.iceberg.rest.requests.ReportMetricsRequest;
import org.apache.iceberg.rest.responses.ConfigResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The metrics route of the Iceberg REST Catalog (IRC) protocol at the HTTP boundary: {@code POST
 * /v1/namespaces/{namespace}/tables/{table}/metrics}, the route {@code RESTCatalog} posts a scan
 * report to after every scan and a commit report to after every commit.
 *
 * <p>On the native path a report goes to the client's own reporter ({@code metrics-reporter-impl},
 * {@code OpenHouseMetricsReporter}, which logs it) and never reaches the service, which has no
 * store for one. So a report that arrives here is validated, counted by its type in the service's
 * meter registry and answered 204; nothing about the table changes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class IrcMetricsRouteTest {
  private static final String DB = "irc_metrics";

  /** The counter of reports received, tagged by the report's type. */
  static final String COUNTER = "irc_metrics_report";

  static final String TYPE_TAG = "report_type";

  /** A scan report as Iceberg's {@code ScanReportParser} writes one (the spec's example). */
  static final String SCAN_REPORT =
      "{\"report-type\":\"scan-report\",\"table-name\":\"openhouse.irc_metrics.t\","
          + "\"snapshot-id\":23,\"filter\":true,\"schema-id\":0,\"projected-field-ids\":[1,2],"
          + "\"projected-field-names\":[\"id\",\"name\"],\"metrics\":{"
          + "\"total-planning-duration\":{\"count\":1,\"time-unit\":\"nanoseconds\","
          + "\"total-duration\":2644235116},"
          + "\"result-data-files\":{\"unit\":\"count\",\"value\":1}},"
          + "\"metadata\":{\"engine-name\":\"spark\"}}";

  /** A commit report as Iceberg's {@code CommitReportParser} writes one. */
  static final String COMMIT_REPORT =
      "{\"report-type\":\"commit-report\",\"table-name\":\"openhouse.irc_metrics.t\","
          + "\"snapshot-id\":23,\"sequence-number\":4,\"operation\":\"append\",\"metrics\":{"
          + "\"total-duration\":{\"count\":1,\"time-unit\":\"nanoseconds\","
          + "\"total-duration\":2644235116},"
          + "\"attempts\":{\"unit\":\"count\",\"value\":1},"
          + "\"added-data-files\":{\"unit\":\"count\",\"value\":1}}}";

  @Autowired MockMvc mvc;
  @Autowired MeterRegistry meterRegistry;

  private final List<String[]> created = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String[] key : created) {
      delete(mvc, key[0], key[1]);
    }
    created.clear();
  }

  private void create(String table) throws Exception {
    created.add(new String[] {DB, table});
    post(mvc, newTable(DB, table)).andExpect(status().isCreated());
  }

  static String metrics(String db, String table) {
    return "/v1/namespaces/" + db + "/tables/" + table + "/metrics";
  }

  private MvcResult report(String db, String table, String json) throws Exception {
    return IrcHttp.perform(
        mvc,
        IrcHttp.request(HttpMethod.POST, metrics(db, table))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private double received(String type) {
    return meterRegistry.counter(COUNTER, TYPE_TAG, type).count();
  }

  /** The client's own mapper reads each test body as the report type it names. */
  @Test
  void theTestReportsAreWhatTheClientSends() throws Exception {
    for (String json : new String[] {SCAN_REPORT, COMMIT_REPORT}) {
      ReportMetricsRequest parsed = IrcClientParsers.parseRequest(json, ReportMetricsRequest.class);
      assertEquals(
          JsonPath.<String>read(json, "$.report-type"),
          JsonPath.<String>read(IrcClientParsers.toJson(parsed), "$.report-type"));
      assertEquals(
          JsonPath.<Object>read(json, "$.metrics"),
          JsonPath.<Object>read(IrcClientParsers.toJson(parsed), "$.metrics"));
    }
  }

  @Test
  void aScanReportIsCountedAndAnswered204() throws Exception {
    create("t");
    String before = json(get(mvc, DB, "t"));
    double scans = received("scan_report");
    double commits = received("commit_report");
    MvcResult result = report(DB, "t", SCAN_REPORT);
    assertEquals(204, result.getResponse().getStatus(), body(result));
    assertEquals("", body(result));
    assertEquals(scans + 1, received("scan_report"));
    assertEquals(commits, received("commit_report"));
    // A report changes nothing about the table.
    assertEquals(before, json(get(mvc, DB, "t")));
  }

  @Test
  void aCommitReportIsCountedAndAnswered204() throws Exception {
    create("t");
    double scans = received("scan_report");
    double commits = received("commit_report");
    MvcResult result = report(DB, "t", COMMIT_REPORT);
    assertEquals(204, result.getResponse().getStatus(), body(result));
    assertEquals(scans, received("scan_report"));
    assertEquals(commits + 1, received("commit_report"));
  }

  @Test
  void aReportForAMissingTableIs404NoSuchTable() throws Exception {
    double scans = received("scan_report");
    assertIrcError(report(DB, "no_such_table", SCAN_REPORT), 404, "NoSuchTableException");
    // No table exists in a namespace of more than one level, as on the other table routes.
    assertIrcError(
        IrcHttp.perform(
            mvc,
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                    HttpMethod.POST,
                    java.net.URI.create(IrcHttp.BASE + metrics(DB + "%1Fx", "no_such_table")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(SCAN_REPORT)),
        404,
        "NoSuchTableException");
    assertEquals(scans, received("scan_report"));
  }

  @Test
  void aBodyThatIsNotAReportIs400BadRequest() throws Exception {
    create("t");
    double scans = received("scan_report");
    double commits = received("commit_report");
    assertIrcError(report(DB, "t", ""), 400, "BadRequestException");
    assertIrcError(report(DB, "t", "not json"), 400, "BadRequestException");
    // A scan report without its required snapshot id and metrics.
    assertIrcError(
        report(DB, "t", "{\"report-type\":\"scan-report\",\"table-name\":\"x\"}"),
        400,
        "BadRequestException");
    // No report type at all.
    assertIrcError(report(DB, "t", "{\"table-name\":\"x\"}"), 400, "BadRequestException");
    assertEquals(scans, received("scan_report"));
    assertEquals(commits, received("commit_report"));
  }

  @Test
  void anInvalidTableIdIs400AsOnTheOtherTableRoutes() throws Exception {
    assertEquals(
        IrcHttp.get(mvc, "/v1/namespaces/" + DB + "/tables/not-a-valid-id")
            .getResponse()
            .getStatus(),
        report(DB, "not-a-valid-id", SCAN_REPORT).getResponse().getStatus());
    assertIrcError(report(DB, "not-a-valid-id", SCAN_REPORT), 400, "BadRequestException");
  }

  /**
   * The config no longer tells clients not to report: the route is served, so {@code RESTCatalog}'s
   * own default (report) stands, and a client that does not want to report sets {@code
   * rest-metrics-reporting-enabled=false} itself.
   */
  @Test
  void theConfigListsTheRouteAndLeavesReportingToTheClient() throws Exception {
    String config = body(IrcHttp.get(mvc, "/v1/config"));
    Map<String, String> defaults = IrcClientParsers.parse(config, ConfigResponse.class).defaults();
    assertFalse(defaults.containsKey("rest-metrics-reporting-enabled"), defaults.toString());
    List<String> endpoints = JsonPath.read(config, "$.endpoints");
    assertTrue(
        endpoints.contains("POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/metrics"),
        endpoints.toString());
  }
}
