package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * <p>As it is today (work item V3c): an IRC request that succeeds is audited by the same advice,
 * with the service named {@code UNRECOGNIZED}, because the aspect names the service from the
 * request path and knows only the native paths. An IRC request that fails or is refused is not
 * audited at all: its error is rendered by the IRC exception handler, which the aspect does not
 * advise.
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

  // ---------------------------------------------------------------- requests that succeed

  @Test
  void aTableLoadIsAuditedLikeTheNativeGetButForTheServiceName() throws Exception {
    created.add("r1");
    post(mvc, nativeCreate("r1"));

    ServiceAuditEvent nativeEvent =
        theAuditOf(
            MockMvcRequestBuilders.get(tableUrl(DB, "r1")).accept(MediaType.APPLICATION_JSON), 200);
    ServiceAuditEvent ircEvent =
        theAuditOf(IrcHttp.request(HttpMethod.GET, IrcWrite.table(DB, "r1")), 200);

    assertSameAudit(nativeEvent, ircEvent, "serviceName");
    assertEquals("/v1/databases/" + DB + "/tables/r1", nativeEvent.getUri());
    assertEquals("/iceberg/v1/namespaces/" + DB + "/tables/r1", ircEvent.getUri());
    assertEquals(HttpMethod.GET, ircEvent.getMethod());
    assertEquals(200, ircEvent.getStatusCode());
    assertEquals(JsonNull.INSTANCE, ircEvent.getRequestPayload());
    assertNull(ircEvent.getResponseErrorMessage());
    assertNull(ircEvent.getStacktrace());
    assertNull(ircEvent.getCause());
    assertEquals(ServiceName.TABLES_SERVICE, nativeEvent.getServiceName());
    assertEquals(ServiceName.UNRECOGNIZED, ircEvent.getServiceName());
  }

  @Test
  void aCreateIsAuditedLikeTheNativeCreateButForTheServiceName() throws Exception {
    created.add("w1");
    created.add("w2");
    CreateUpdateTableRequestBody nativeBody = nativeCreate("w1");
    CreateTableRequest ircBody = ircCreate("w2");

    ServiceAuditEvent nativeEvent = theAuditOf(nativePost(nativeBody), 201);
    ServiceAuditEvent ircEvent = theAuditOf(ircPost(ircBody), 200);

    // Each API's own success status and its own request body.
    assertSameAudit(nativeEvent, ircEvent, "serviceName", "statusCode", "requestPayload");
    assertEquals("/v1/databases/" + DB + "/tables/", nativeEvent.getUri());
    assertEquals("/iceberg/v1/namespaces/" + DB + "/tables", ircEvent.getUri());
    assertEquals(HttpMethod.POST, ircEvent.getMethod());
    assertEquals(JsonParser.parseString(nativeBody.toJson()), nativeEvent.getRequestPayload());
    assertEquals(
        JsonParser.parseString(IrcClientParsers.toJson(ircBody)), ircEvent.getRequestPayload());
    assertEquals(ServiceName.TABLES_SERVICE, nativeEvent.getServiceName());
    assertEquals(ServiceName.UNRECOGNIZED, ircEvent.getServiceName());
  }

  // ---------------------------------------------------------------- requests that are refused

  @Test
  void aCreateRefusedForAuthorizationIsAuditedNativelyAndNotOverIrc() throws Exception {
    created.add("a0");
    post(mvc, nativeCreate("a0"));
    stubDecision(false);

    ServiceAuditEvent nativeEvent = theAuditOf(nativePost(nativeCreate("a1")), 403);
    assertEquals(ServiceName.TABLES_SERVICE, nativeEvent.getServiceName());
    assertNotNull(nativeEvent.getResponseErrorMessage());
    assertNotNull(nativeEvent.getStacktrace());

    assertEquals(Collections.emptyList(), audited(ircPost(ircCreate("a1")), 403));
  }

  @Test
  void aCreateRefusedForValidationIsAuditedNativelyAndNotOverIrc() throws Exception {
    String tooLong = String.join("", Collections.nCopies(129, "v"));

    ServiceAuditEvent nativeEvent = theAuditOf(nativePost(nativeCreate(tooLong)), 400);
    assertEquals(
        "CreateUpdateTableRequestBody.tableId : size must be between 0 and 128",
        nativeEvent.getResponseErrorMessage());

    assertEquals(Collections.emptyList(), audited(ircPost(ircCreate(tooLong)), 400));
  }

  @Test
  void aCreateRefusedForAConflictIsAuditedNativelyAndNotOverIrc() throws Exception {
    created.add("c1");
    post(mvc, nativeCreate("c1"));

    ServiceAuditEvent nativeEvent = theAuditOf(nativePost(nativeCreate("c1")), 409);
    assertNotNull(nativeEvent.getResponseErrorMessage());

    assertEquals(Collections.emptyList(), audited(ircPost(ircCreate("c1")), 409));
  }

  @Test
  void aRouteThisServerDoesNotServeIsNotAudited() throws Exception {
    assertEquals(
        Collections.emptyList(),
        audited(IrcHttp.request(HttpMethod.GET, "/v1/namespaces/" + DB + "/functions"), 406));
  }
}
