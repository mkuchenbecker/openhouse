package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import com.linkedin.openhouse.common.audit.AuditHandler;
import com.linkedin.openhouse.common.audit.model.ServiceAuditEvent;
import com.linkedin.openhouse.common.audit.model.ServiceName;
import com.linkedin.openhouse.common.schema.IcebergSchemaHelper;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.authorization.AuthorizationHandler;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.model.TableModelConstants;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.rest.IrcClientParsers;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The service audit of requests on the Iceberg REST Catalog (IRC) routes, next to the audit of the
 * equivalent native request, through real MVC and the request-audit aspect.
 *
 * <p>The service audit is one {@link ServiceAuditEvent} per request, handed to the service's {@code
 * AuditHandler<ServiceAuditEvent>} (here a recording mock). The aspect emits it for a native
 * request that succeeds (around the controller method) and for one that fails (around the native
 * exception handler, whose error body carries the message, stack trace and cause).
 *
 * <p>An IRC request is audited as the equivalent native request is (work item V3c): the same event
 * class from the same aspect, with the same fields. One that succeeds always was, by the advice
 * around the controller method. One that fails or is refused is rendered by the IRC exception
 * handler, which the aspect does not advise and whose body is the specification's and not the
 * native one; that handler now hands the audit the status and message it answers and the failure's
 * stack trace and cause, as the native handler's body carries them. Both name the tables service.
 *
 * <p>What differs is the request itself: the {@code uri} names the IRC route, the payload is the
 * IRC body, and the status is the one IRC answers (a create is 200, where the native one is 201).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
public class IrcServiceAuditTest {
  private static final String DB = "irc_saudit";
  private static final String SESSION = "session-id";
  private static final String ACTION_TYPE = "X-OpenHouse-Action-Type";
  private static final Schema SCHEMA =
      IcebergSchemaHelper.getSchemaFromSchemaJson(TableModelConstants.HEALTH_SCHEMA_LITERAL);

  /** Sends the default test token on every request (see {@code MockMvcBuilderConfig}). */
  @Autowired MockMvc mvc;

  @MockBean AuthorizationHandler authorizationHandler;
  @Autowired AuditHandler<ServiceAuditEvent> serviceAuditHandler;

  private final List<String> created = new ArrayList<>();

  @BeforeEach
  void allowEverything() {
    reset(authorizationHandler);
    stubDecision(true);
  }

  @AfterEach
  void cleanUp() throws Exception {
    stubDecision(true);
    for (String table : created) {
      delete(mvc, DB, table);
    }
    created.clear();
  }

  // ---------------------------------------------------------------- fixtures

  private void stubDecision(boolean allowed) {
    when(authorizationHandler.checkAccessDecision(
            any(), any(TableDto.class), any(Privileges.class)))
        .thenReturn(allowed);
    when(authorizationHandler.checkAccessDecision(
            any(), any(DatabaseDto.class), any(Privileges.class)))
        .thenReturn(allowed);
    when(authorizationHandler.checkSystemOnlyLockAccess(any(), any(), any())).thenCallRealMethod();
  }

  private static CreateUpdateTableRequestBody nativeCreate(String table) {
    return newTable(DB, table)
        .toBuilder()
        .policies(null)
        .timePartitioning(null)
        .clustering(null)
        .build();
  }

  private static CreateTableRequest ircCreate(String table) {
    return CreateTableRequest.builder()
        .withName(table)
        .withSchema(SCHEMA)
        .withPartitionSpec(PartitionSpec.unpartitioned())
        .build();
  }

  private static MockHttpServletRequestBuilder nativePost(CreateUpdateTableRequestBody body) {
    return MockMvcRequestBuilders.post(
            String.format("/v1/databases/%s/tables/", body.getDatabaseId()))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body.toJson())
        .accept(MediaType.APPLICATION_JSON);
  }

  private static MockHttpServletRequestBuilder ircPost(CreateTableRequest body) throws Exception {
    return IrcWrite.post(IrcWrite.tables(DB), body);
  }

  /**
   * Sends {@code request} with a session id and an action type, and returns the service audit
   * events it produced.
   */
  private List<ServiceAuditEvent> audited(MockHttpServletRequestBuilder request, int status)
      throws Exception {
    clearInvocations(serviceAuditHandler);
    int answered =
        mvc.perform(request.header(SESSION, "session-1").header(ACTION_TYPE, "USER"))
            .andReturn()
            .getResponse()
            .getStatus();
    assertEquals(status, answered);
    ArgumentCaptor<ServiceAuditEvent> events = ArgumentCaptor.forClass(ServiceAuditEvent.class);
    verify(serviceAuditHandler, atLeast(0)).audit(events.capture());
    return events.getAllValues();
  }

  private ServiceAuditEvent theAuditOf(MockHttpServletRequestBuilder request, int status)
      throws Exception {
    List<ServiceAuditEvent> events = audited(request, status);
    assertEquals(1, events.size(), "one service audit event per request");
    return events.get(0);
  }

  /**
   * The two events agree on every field of {@link ServiceAuditEvent} but {@code differing}, and
   * carry what a request's event always carries: both timestamps, the cluster, the session and
   * action type sent, and the method.
   */
  private static void assertSameAudit(
      ServiceAuditEvent nativeEvent, ServiceAuditEvent ircEvent, String... differing)
      throws Exception {
    assertEquals(ServiceAuditEvent.class, nativeEvent.getClass());
    assertEquals(ServiceAuditEvent.class, ircEvent.getClass());
    Set<String> skipped = new HashSet<>(Arrays.asList(differing));
    skipped.addAll(Arrays.asList("startTimestamp", "endTimestamp", "uri", "stacktrace"));
    for (Field field : ServiceAuditEvent.class.getDeclaredFields()) {
      if (field.isSynthetic() || skipped.contains(field.getName())) {
        continue;
      }
      field.setAccessible(true);
      assertEquals(field.get(nativeEvent), field.get(ircEvent), field.getName());
    }
    for (ServiceAuditEvent event : Arrays.asList(nativeEvent, ircEvent)) {
      assertNotNull(event.getStartTimestamp());
      assertNotNull(event.getEndTimestamp());
      assertFalse(event.getEndTimestamp().isBefore(event.getStartTimestamp()));
      assertEquals("session-1", event.getSessionId());
      assertEquals("USER", event.getActionType());
      assertEquals("local-cluster", event.getClusterName());
      assertNotNull(event.getUser());
    }
  }

  /** The first line of a stack trace: the failure's class and message. */
  private static String failure(ServiceAuditEvent event) {
    assertNotNull(event.getStacktrace());
    return event.getStacktrace().split("\\R", 2)[0];
  }

  /**
   * A refusal's two events agree on everything but the path and the body sent: the status, the
   * message, the cause, and the failure the stack trace starts with.
   */
  private static void assertSameRefusal(
      ServiceAuditEvent nativeEvent, ServiceAuditEvent ircEvent, int status) throws Exception {
    assertSameAudit(nativeEvent, ircEvent, "requestPayload");
    assertEquals(status, ircEvent.getStatusCode());
    assertNotNull(ircEvent.getResponseErrorMessage());
    assertNotNull(ircEvent.getCause());
    assertEquals(failure(nativeEvent), failure(ircEvent));
    assertEquals(ServiceName.TABLES_SERVICE, ircEvent.getServiceName());
  }

  // ---------------------------------------------------------------- requests that succeed

  @Test
  void aTableLoadIsAuditedAsTheNativeGetIs() throws Exception {
    created.add("r1");
    post(mvc, nativeCreate("r1"));

    ServiceAuditEvent nativeEvent =
        theAuditOf(
            MockMvcRequestBuilders.get(tableUrl(DB, "r1")).accept(MediaType.APPLICATION_JSON), 200);
    ServiceAuditEvent ircEvent =
        theAuditOf(IrcHttp.request(HttpMethod.GET, IrcWrite.table(DB, "r1")), 200);

    assertSameAudit(nativeEvent, ircEvent);
    assertEquals("/v1/databases/" + DB + "/tables/r1", nativeEvent.getUri());
    assertEquals("/iceberg/v1/namespaces/" + DB + "/tables/r1", ircEvent.getUri());
    assertEquals(HttpMethod.GET, ircEvent.getMethod());
    assertEquals(200, ircEvent.getStatusCode());
    assertEquals(JsonNull.INSTANCE, ircEvent.getRequestPayload());
    assertNull(ircEvent.getResponseErrorMessage());
    assertNull(ircEvent.getStacktrace());
    assertNull(ircEvent.getCause());
    assertEquals(ServiceName.TABLES_SERVICE, ircEvent.getServiceName());
  }

  @Test
  void aCreateIsAuditedAsTheNativeCreateIs() throws Exception {
    created.add("w1");
    created.add("w2");
    CreateUpdateTableRequestBody nativeBody = nativeCreate("w1");
    CreateTableRequest ircBody = ircCreate("w2");

    ServiceAuditEvent nativeEvent = theAuditOf(nativePost(nativeBody), 201);
    ServiceAuditEvent ircEvent = theAuditOf(ircPost(ircBody), 200);

    // Each API's own success status and its own request body.
    assertSameAudit(nativeEvent, ircEvent, "statusCode", "requestPayload");
    assertEquals("/v1/databases/" + DB + "/tables/", nativeEvent.getUri());
    assertEquals("/iceberg/v1/namespaces/" + DB + "/tables", ircEvent.getUri());
    assertEquals(HttpMethod.POST, ircEvent.getMethod());
    assertEquals(JsonParser.parseString(nativeBody.toJson()), nativeEvent.getRequestPayload());
    assertEquals(
        JsonParser.parseString(IrcClientParsers.toJson(ircBody)), ircEvent.getRequestPayload());
    assertEquals(ServiceName.TABLES_SERVICE, ircEvent.getServiceName());
  }

  // ---------------------------------------------------------------- requests that are refused

  @Test
  void aCreateRefusedForAuthorizationIsAuditedAsTheNativeRefusalIs() throws Exception {
    created.add("a0");
    post(mvc, nativeCreate("a0"));
    stubDecision(false);

    ServiceAuditEvent nativeEvent = theAuditOf(nativePost(nativeCreate("a1")), 403);
    ServiceAuditEvent ircEvent = theAuditOf(ircPost(ircCreate("a1")), 403);

    assertSameRefusal(nativeEvent, ircEvent, 403);
    assertEquals("/iceberg/v1/namespaces/" + DB + "/tables", ircEvent.getUri());
    assertEquals(
        JsonParser.parseString(IrcClientParsers.toJson(ircCreate("a1"))),
        ircEvent.getRequestPayload());
    assertTrue(
        failure(ircEvent).startsWith("org.springframework.security.access.AccessDeniedException"),
        failure(ircEvent));
  }

  @Test
  void aCreateRefusedForValidationIsAuditedAsTheNativeRefusalIs() throws Exception {
    String tooLong = String.join("", Collections.nCopies(129, "v"));

    ServiceAuditEvent nativeEvent = theAuditOf(nativePost(nativeCreate(tooLong)), 400);
    ServiceAuditEvent ircEvent = theAuditOf(ircPost(ircCreate(tooLong)), 400);

    assertSameRefusal(nativeEvent, ircEvent, 400);
    assertEquals(
        "CreateUpdateTableRequestBody.tableId : size must be between 0 and 128",
        ircEvent.getResponseErrorMessage());
  }

  @Test
  void aCreateRefusedForAConflictIsAuditedAsTheNativeRefusalIs() throws Exception {
    created.add("c1");
    post(mvc, nativeCreate("c1"));

    ServiceAuditEvent nativeEvent = theAuditOf(nativePost(nativeCreate("c1")), 409);
    ServiceAuditEvent ircEvent = theAuditOf(ircPost(ircCreate("c1")), 409);

    assertSameRefusal(nativeEvent, ircEvent, 409);
  }

  /** A failure of the IRC layer itself, with no native request to compare with. */
  @Test
  void aRouteThisServerDoesNotServeIsAudited() throws Exception {
    ServiceAuditEvent event =
        theAuditOf(IrcHttp.request(HttpMethod.GET, "/v1/namespaces/" + DB + "/functions"), 406);

    assertEquals(ServiceName.TABLES_SERVICE, event.getServiceName());
    assertEquals(HttpMethod.GET, event.getMethod());
    assertEquals("/iceberg/v1/namespaces/" + DB + "/functions", event.getUri());
    assertEquals(406, event.getStatusCode());
    assertEquals(
        "This server does not support GET /v1/namespaces/" + DB + "/functions yet",
        event.getResponseErrorMessage());
    assertEquals("Not Available", event.getCause());
    assertNotNull(event.getStacktrace());
  }

  /**
   * The audit takes the stack trace; the client still gets the specification's error and no more.
   */
  @Test
  void theStackTraceGoesToTheAuditAndNotToTheClient() throws Exception {
    created.add("s1");
    post(mvc, nativeCreate("s1"));
    clearInvocations(serviceAuditHandler);

    org.apache.iceberg.rest.responses.ErrorResponse error =
        IrcHttp.assertIrcError(
            IrcHttp.perform(mvc, ircPost(ircCreate("s1"))), 409, "AlreadyExistsException");

    assertNull(error.stack());
    ArgumentCaptor<ServiceAuditEvent> events = ArgumentCaptor.forClass(ServiceAuditEvent.class);
    verify(serviceAuditHandler).audit(events.capture());
    assertEquals(error.message(), events.getValue().getResponseErrorMessage());
    assertTrue(events.getValue().getStacktrace().contains("\tat "));
  }

  // ---------------------------------------------------------------- metrics reports

  private static MockHttpServletRequestBuilder metricsPost(String table, String body) {
    return IrcHttp.request(HttpMethod.POST, IrcMetricsRouteTest.metrics(DB, table))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  /**
   * A metrics report is audited by what identifies it and its size, not its content (its metrics,
   * projected field names and metadata map are the client's telemetry): a scan report and a commit
   * report alike.
   */
  @Test
  void aMetricsReportIsAuditedByItsIdentityAndSizeNotItsContent() throws Exception {
    created.add("m1");
    post(mvc, nativeCreate("m1"));
    String[][] reports = {
      {IrcMetricsRouteTest.SCAN_REPORT, "scan-report"},
      {IrcMetricsRouteTest.COMMIT_REPORT, "commit-report"}
    };
    for (String[] report : reports) {
      ServiceAuditEvent event = theAuditOf(metricsPost("m1", report[0]), 204);
      com.google.gson.JsonObject expected = new com.google.gson.JsonObject();
      expected.addProperty("report-type", report[1]);
      expected.addProperty("table-name", "openhouse.irc_metrics.t");
      expected.addProperty("snapshot-id", 23);
      expected.addProperty(
          "bytes", report[0].getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
      assertEquals(expected, event.getRequestPayload(), report[1]);
      assertEquals("/iceberg" + IrcMetricsRouteTest.metrics(DB, "m1"), event.getUri(), report[1]);
    }
  }

  /**
   * A report body larger than the route accepts (1 MiB) is refused with the spec's 400 before more
   * of it is read, counted as no report, and audited by its size alone.
   */
  @Test
  void anOversizedMetricsReportIs400AndAuditedByItsSizeAlone() throws Exception {
    created.add("m2");
    post(mvc, nativeCreate("m2"));
    StringBuilder padded = new StringBuilder(IrcMetricsRouteTest.SCAN_REPORT);
    padded.insert(padded.length() - 1, ",\"padding\":\"");
    while (padded.length() < 1024 * 1024 + 16) {
      padded.insert(padded.length() - 1, "xxxxxxxxxxxxxxxx");
    }
    padded.insert(padded.length() - 1, "\"");
    String body = padded.toString();
    int bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    clearInvocations(serviceAuditHandler);
    org.apache.iceberg.rest.responses.ErrorResponse error =
        IrcHttp.assertIrcError(
            IrcHttp.perform(mvc, metricsPost("m2", body)), 400, "BadRequestException");
    assertEquals(
        "Invalid ReportMetricsRequest: the report is "
            + bytes
            + " bytes, larger than the 1048576 bytes this server accepts",
        error.message());
    ArgumentCaptor<ServiceAuditEvent> events = ArgumentCaptor.forClass(ServiceAuditEvent.class);
    verify(serviceAuditHandler).audit(events.capture());
    com.google.gson.JsonObject expected = new com.google.gson.JsonObject();
    expected.addProperty("bytes", bytes);
    assertEquals(expected, events.getValue().getRequestPayload());
    assertEquals(400, events.getValue().getStatusCode());
  }
}
