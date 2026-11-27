package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static com.linkedin.openhouse.tables.e2e.h2.NativeViewHttp.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.jayway.jsonpath.JsonPath;
import com.linkedin.openhouse.common.audit.AuditHandler;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.tables.audit.model.OperationStatus;
import com.linkedin.openhouse.tables.audit.model.ViewAuditEvent;
import com.linkedin.openhouse.tables.audit.model.ViewOperationType;
import com.linkedin.openhouse.tables.authorization.AuthorizationHandler;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.toggle.repository.ToggleStatusesRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.view.ViewMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Authorization on the IRC view routes, with the fixture of {@link IrcWriteRoutesAuthTest} (same
 * Spring context): only the {@link AuthorizationHandler} decision is switched. Each refusal is the
 * native view route's refusal of the same principal, as 403 {@code ForbiddenException}, and changes
 * nothing. Reads make no authorization decision natively, so none over IRC either.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(IrcViewRoutesTest.ViewAudit.class)
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
public class IrcViewRoutesAuthTest {
  private static final String DB = "irc_vauth";

  @Autowired MockMvc mvc;
  @Autowired HouseTableRepository houseTables;
  @Autowired ToggleStatusesRepository toggles;
  @MockBean AuthorizationHandler authorizationHandler;
  @Autowired AuditHandler<ViewAuditEvent> viewAuditHandler;

  @BeforeEach
  void setUp() throws Exception {
    reset(authorizationHandler);
    stubDecision(true);
    enableViews(toggles, DB);
    CommitPathHttp.post(mvc, CommitPathHttp.newTable(DB, "t"));
    NativeViewHttp.delete(mvc, DB, "v");
    assertEquals(
        201,
        code(post(mvc, createBody(DB, "v", SCHEMA_ID_NAME, "SELECT 1", Collections.emptyMap()))));
  }

  @AfterEach
  void cleanUp() throws Exception {
    stubDecision(true);
    NativeViewHttp.delete(mvc, DB, "v");
    NativeViewHttp.delete(mvc, DB, "w");
    CommitPathHttp.delete(mvc, DB, "t");
  }

  private void stubDecision(boolean allowed) {
    when(authorizationHandler.checkAccessDecision(
            any(), any(TableDto.class), any(Privileges.class)))
        .thenReturn(allowed);
    when(authorizationHandler.checkAccessDecision(
            any(), any(DatabaseDto.class), any(Privileges.class)))
        .thenReturn(allowed);
    when(authorizationHandler.checkSystemOnlyLockAccess(any(), any(), any())).thenCallRealMethod();
  }

  private static String message(MvcResult result) throws Exception {
    return JsonPath.read(body(result), "$.message");
  }

  @Test
  void aDeniedCreateIs403AsNatively() throws Exception {
    stubDecision(false);
    MvcResult nativeResult =
        post(mvc, createBody(DB, "w", SCHEMA_ID_NAME, "SELECT 1", Collections.emptyMap()));
    assertEquals(403, code(nativeResult));
    assertEquals(
        message(nativeResult),
        assertIrcError(
                IrcViewWrite.create(
                    mvc,
                    DB,
                    IrcViewWrite.createRequest(
                        "w",
                        IrcViewRoutesTest.SCHEMA,
                        "SELECT 1",
                        Namespace.of(DB),
                        Collections.emptyMap())),
                403,
                "ForbiddenException")
            .message());
    stubDecision(true);
    assertEquals(404, code(get(mvc, DB, "w")));
  }

  @Test
  void aDeniedReplaceIs403AsNativelyAndChangesNothing() throws Exception {
    ViewMetadata base = IrcViewWrite.load(mvc, DB, "v");
    String before = StoredViewMetadata.normalized(houseTables, DB, "v");
    stubDecision(false);
    Map<String, String> properties = StoredViewMetadataGoldenTest.storedProperties(houseTables, DB);
    properties.put("k", "v");
    MvcResult nativeResult =
        put(
            mvc,
            replaceBody(
                DB,
                "v",
                base.metadataFileLocation(),
                SCHEMA_ID_NAME,
                Collections.singletonList(spark("SELECT 1")),
                properties));
    assertEquals(403, code(nativeResult));
    assertEquals(
        message(nativeResult),
        assertIrcError(
                IrcViewWrite.commit(
                    mvc,
                    DB,
                    "v",
                    IrcViewWrite.commitRequest(
                        base, IrcViewWrite.properties(base, Collections.singletonMap("k", "v")))),
                403,
                "ForbiddenException")
            .message());
    assertEquals(before, StoredViewMetadata.normalized(houseTables, DB, "v"));
  }

  @Test
  void aDeniedDropIs403AsNatively() throws Exception {
    stubDecision(false);
    MvcResult nativeResult = NativeViewHttp.delete(mvc, DB, "v");
    assertEquals(403, code(nativeResult));
    assertEquals(
        message(nativeResult),
        assertIrcError(IrcViewWrite.drop(mvc, DB, "v"), 403, "ForbiddenException").message());
    stubDecision(true);
    assertEquals(200, code(get(mvc, DB, "v")));
  }

  /**
   * The native view reads make no authorization decision: the native GET answers a pointer and the
   * list names. The IRC load answers the definition, which the service reads for the caller, so it
   * is decided as the IRC table load is (below); the IRC list (names) and exists (no body) make no
   * decision, as the native list and the IRC table exists route do.
   */
  @Test
  void nativeReadsListAndExistsMakeNoDecisionAndTheIrcLoadIsRefused() throws Exception {
    stubDecision(false);
    assertEquals(200, code(get(mvc, DB, "v")));
    assertIrcError(IrcHttp.get(mvc, IrcViewWrite.view(DB, "v")), 403, "ForbiddenException");
    assertEquals(204, IrcHttp.head(mvc, IrcViewWrite.view(DB, "v")).getResponse().getStatus());
    assertEquals(200, IrcHttp.get(mvc, IrcViewWrite.views(DB)).getResponse().getStatus());
  }

  // ------------------------------------------------- authorization before the stored view is used

  /**
   * Denies {@code denied} and allows every other privilege, on tables and databases alike, so a
   * refusal shows which decision was asked for.
   */
  private void denyOnly(Privileges denied) {
    when(authorizationHandler.checkAccessDecision(
            any(), any(TableDto.class), any(Privileges.class)))
        .thenAnswer(call -> call.getArgument(2) != denied);
    when(authorizationHandler.checkAccessDecision(
            any(), any(DatabaseDto.class), any(Privileges.class)))
        .thenAnswer(call -> call.getArgument(2) != denied);
  }

  /** The view audit events of {@code db.view} sent since the last clear, without timestamps. */
  private List<ViewAuditEvent> auditEvents(String view) {
    ArgumentCaptor<ViewAuditEvent> events = ArgumentCaptor.forClass(ViewAuditEvent.class);
    Mockito.verify(viewAuditHandler, Mockito.atLeast(0)).audit(events.capture());
    List<ViewAuditEvent> ofView = new ArrayList<>();
    for (ViewAuditEvent event : events.getAllValues()) {
      if (DB.equals(event.getDatabaseName()) && view.equals(event.getViewName())) {
        ofView.add(event.toBuilder().eventTimestamp(null).build());
      }
    }
    return ofView;
  }

  private static void assertNothingStoredIn(String body, ViewMetadata stored) {
    org.junit.jupiter.api.Assertions.assertFalse(body.contains(stored.uuid()), body);
    org.junit.jupiter.api.Assertions.assertFalse(body.contains("SELECT 1"), body);
    org.junit.jupiter.api.Assertions.assertFalse(body.contains(stored.location()), body);
  }

  /**
   * The IRC view load answers the view's definition, so it needs what the IRC table load needs, the
   * read privilege (GET_TABLE_METADATA) on the view, decided before the definition is read: denied
   * that one privilege and allowed every other, the caller gets the refusal and no definition. A
   * missing view is 404 before any decision, as a missing table is. Allowed, the answer is the same
   * as before.
   */
  @Test
  void anIrcViewLoadNeedsTheReadPrivilege() throws Exception {
    MvcResult allowed = IrcHttp.get(mvc, IrcViewWrite.view(DB, "v"));
    assertEquals(200, allowed.getResponse().getStatus());
    ViewMetadata stored = IrcViewWrite.parsed(allowed).metadata();

    denyOnly(Privileges.GET_TABLE_METADATA);
    Mockito.clearInvocations(authorizationHandler);
    MvcResult denied = IrcHttp.get(mvc, IrcViewWrite.view(DB, "v"));
    String message = assertIrcError(denied, 403, "ForbiddenException").message();
    assertEquals(
        "Operation on table " + DB + ".v failed as user testUser is unauthorized", message);
    assertNothingStoredIn(IrcHttp.body(denied), stored);
    Mockito.verify(authorizationHandler)
        .checkAccessDecision(
            any(),
            org.mockito.ArgumentMatchers.argThat(
                (TableDto view) ->
                    view != null
                        && "v".equals(view.getTableId())
                        && DB.equals(view.getDatabaseId())
                        && stored.uuid().equals(view.getTableUUID())),
            org.mockito.ArgumentMatchers.eq(Privileges.GET_TABLE_METADATA));
    // The native GET is unchanged: a pointer, with no decision.
    assertEquals(200, code(get(mvc, DB, "v")));

    stubDecision(false);
    Mockito.clearInvocations(authorizationHandler);
    assertIrcError(IrcHttp.get(mvc, IrcViewWrite.view(DB, "nope")), 404, "NoSuchViewException");
    Mockito.verify(authorizationHandler, Mockito.never())
        .checkAccessDecision(any(), any(TableDto.class), any(Privileges.class));

    denyOnly(Privileges.UPDATE_TABLE_METADATA);
    assertEquals(IrcHttp.body(allowed), IrcHttp.body(IrcHttp.get(mvc, IrcViewWrite.view(DB, "v"))));
  }

  /**
   * A replace whose requirement the stored view fails is refused 403 for a principal who may not
   * replace it (every privilege but UPDATE_TABLE_METADATA), with the native PUT's refusal, nothing
   * of the stored view, and the native PUT's audit event of that refusal. An update that cannot be
   * applied is refused alike. Allowed, they are the 409 and 400 they were.
   */
  @Test
  void aFailingReplaceOfADeniedPrincipalIs403AuditedAsTheNativeRefusal() throws Exception {
    ViewMetadata base = IrcViewWrite.load(mvc, DB, "v");
    java.util.List<MetadataUpdate> setK =
        IrcViewWrite.properties(base, Collections.singletonMap("k", "v")).changes();
    org.apache.iceberg.rest.requests.UpdateTableRequest wrongUuid =
        IrcViewWrite.commitRequest(
            Collections.singletonList(
                new UpdateRequirement.AssertViewUUID(java.util.UUID.randomUUID().toString())),
            setK);
    org.apache.iceberg.rest.requests.UpdateTableRequest unknownVersion =
        IrcViewWrite.commitRequest(
            Collections.emptyList(),
            Collections.singletonList(new MetadataUpdate.SetCurrentViewVersion(99)));
    String conflict =
        assertIrcError(IrcViewWrite.commit(mvc, DB, "v", wrongUuid), 409, "CommitFailedException")
            .message();
    org.junit.jupiter.api.Assertions.assertTrue(conflict.contains(base.uuid()), conflict);
    assertIrcError(IrcViewWrite.commit(mvc, DB, "v", unknownVersion), 400, "BadRequestException");

    String before = StoredViewMetadata.normalized(houseTables, DB, "v");
    denyOnly(Privileges.UPDATE_TABLE_METADATA);
    Map<String, String> properties = StoredViewMetadataGoldenTest.storedProperties(houseTables, DB);
    properties.put("k", "v");
    Mockito.clearInvocations(viewAuditHandler);
    MvcResult nativeResult =
        put(
            mvc,
            replaceBody(
                DB,
                "v",
                base.metadataFileLocation(),
                SCHEMA_ID_NAME,
                Collections.singletonList(spark("SELECT 1")),
                properties));
    assertEquals(403, code(nativeResult));
    List<ViewAuditEvent> nativeEvents = auditEvents("v");
    assertEquals(1, nativeEvents.size(), String.valueOf(nativeEvents));
    assertEquals(ViewOperationType.REPLACE, nativeEvents.get(0).getOperationType());
    assertEquals(OperationStatus.FAILED, nativeEvents.get(0).getOperationStatus());
    assertEquals("ACCESS_DENIED", nativeEvents.get(0).getFailureCode());

    for (org.apache.iceberg.rest.requests.UpdateTableRequest commit :
        java.util.Arrays.asList(wrongUuid, unknownVersion)) {
      Mockito.clearInvocations(viewAuditHandler);
      MvcResult result = IrcViewWrite.commit(mvc, DB, "v", commit);
      assertEquals(
          message(nativeResult), assertIrcError(result, 403, "ForbiddenException").message());
      assertNothingStoredIn(IrcHttp.body(result), base);
      assertEquals(nativeEvents, auditEvents("v"));
    }
    assertEquals(before, StoredViewMetadata.normalized(houseTables, DB, "v"));
  }

  /**
   * A replace with no updates answers the definition and changes nothing, so it is a load too: a
   * principal who may replace the view but not read it is refused.
   */
  @Test
  void anEmptyViewCommitNeedsTheReadPrivilege() throws Exception {
    ViewMetadata base = IrcViewWrite.load(mvc, DB, "v");
    org.apache.iceberg.rest.requests.UpdateTableRequest empty =
        IrcViewWrite.commitRequest(Collections.emptyList(), Collections.emptyList());
    denyOnly(Privileges.GET_TABLE_METADATA);
    MvcResult result = IrcViewWrite.commit(mvc, DB, "v", empty);
    assertIrcError(result, 403, "ForbiddenException");
    assertNothingStoredIn(IrcHttp.body(result), base);
  }
}
