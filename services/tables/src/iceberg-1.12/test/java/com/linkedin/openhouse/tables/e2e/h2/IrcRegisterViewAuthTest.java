package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static com.linkedin.openhouse.tables.e2e.h2.NativeViewHttp.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jayway.jsonpath.JsonPath;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.authorization.AuthorizationHandler;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.toggle.repository.ToggleStatusesRepository;
import java.util.Collections;
import org.apache.iceberg.catalog.Namespace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Authorization on the IRC register-view route, with the fixture of {@link IrcViewRoutesAuthTest}
 * (same Spring context): a registration is a view create, so it is decided as the native view
 * create is, the create privilege on the database, and a denial is the native create's refusal of
 * the same principal, as 403 {@code ForbiddenException}, and stores nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(IrcViewRoutesTest.ViewAudit.class)
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
public class IrcRegisterViewAuthTest {
  private static final String DB = "irc_rvauth";

  @Autowired MockMvc mvc;
  @Autowired ToggleStatusesRepository toggles;
  @MockBean AuthorizationHandler authorizationHandler;

  @Autowired
  com.linkedin.openhouse.common.audit.AuditHandler<
          com.linkedin.openhouse.tables.audit.model.ViewAuditEvent>
      viewAuditHandler;

  private String location;

  @BeforeEach
  void setUp() throws Exception {
    reset(authorizationHandler);
    stubDecision(true);
    enableViews(toggles, DB);
    CommitPathHttp.post(mvc, CommitPathHttp.newTable(DB, "t"));
    NativeViewHttp.delete(mvc, DB, "v");
    location =
        IrcViewWrite.parsed(
                IrcViewWrite.create(
                    mvc,
                    DB,
                    IrcViewWrite.createRequest(
                        "v",
                        IrcViewRoutesTest.SCHEMA,
                        "SELECT 1",
                        Namespace.of(DB),
                        Collections.emptyMap())))
            .metadataLocation();
    assertEquals(204, IrcViewWrite.drop(mvc, DB, "v").getResponse().getStatus());
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

  private MvcResult register(String name) throws Exception {
    return IrcHttp.perform(
        mvc,
        IrcWrite.post(
            IrcRegisterViewRouteTest.registerPath(DB),
            IrcRegisterViewRouteTest.request(name, location)));
  }

  @Test
  void aDeniedRegistrationIs403AsTheNativeCreateAndStoresNothing() throws Exception {
    stubDecision(false);
    MvcResult nativeResult =
        post(mvc, createBody(DB, "w", SCHEMA_ID_NAME, "SELECT 1", Collections.emptyMap()));
    assertEquals(403, code(nativeResult));
    assertEquals(
        (String) JsonPath.read(IrcHttp.body(nativeResult), "$.message"),
        assertIrcError(register("v"), 403, "ForbiddenException").message());
    stubDecision(true);
    assertEquals(404, code(get(mvc, DB, "v")));
  }

  @Test
  void anAllowedRegistrationAsksForTheCreatePrivilegeOnTheDatabase() throws Exception {
    reset(authorizationHandler);
    stubDecision(true);
    assertEquals(200, register("v").getResponse().getStatus());
    verify(authorizationHandler)
        .checkAccessDecision(any(), any(DatabaseDto.class), eq(Privileges.CREATE_TABLE));
    assertEquals(200, code(get(mvc, DB, "v")));
  }

  private MvcResult register(String name, String file) throws Exception {
    return IrcHttp.perform(
        mvc,
        IrcWrite.post(
            IrcRegisterViewRouteTest.registerPath(DB),
            IrcRegisterViewRouteTest.request(name, file)));
  }

  /**
   * Nothing is read before the decision: a denied caller gets the same 403 whether the file is
   * valid, missing or garbage, and on a name that is already a view (not the 409).
   */
  @Test
  void aDeniedCallerLearnsNothingAboutTheFileOrTheName() throws Exception {
    stubDecision(false);
    String denied = assertIrcError(register("v"), 403, "ForbiddenException").message();
    String directory = location.substring(0, location.lastIndexOf('/'));
    String missing = directory + "/00009-" + java.util.UUID.randomUUID() + ".metadata.json";
    assertEquals(
        denied, assertIrcError(register("v", missing), 403, "ForbiddenException").message());
    String garbage = directory + "/00008-" + java.util.UUID.randomUUID() + ".metadata.json";
    java.nio.file.Path garbagePath =
        java.nio.file.Paths.get(
            garbage.startsWith("file:") ? garbage.substring("file:".length()) : garbage);
    java.nio.file.Files.write(
        garbagePath, "not json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(
        denied, assertIrcError(register("v", garbage), 403, "ForbiddenException").message());
    stubDecision(true);
    assertEquals(200, register("v").getResponse().getStatus());
    stubDecision(false);
    assertEquals(denied, assertIrcError(register("v"), 403, "ForbiddenException").message());
  }
  // ---------------------------------------------------------------- who

  private static String bearer(String user) throws Exception {
    return "Bearer "
        + new com.linkedin.openhouse.common.security.DummyTokenInterceptor.DummySecurityJWT(user)
            .buildNoopJWT();
  }

  /**
   * Created by one principal, dropped, registered by another: the view keeps its creator and
   * creation time (the file's), and the view audit names the registrant, as a CREATE.
   */
  @Test
  void theRegistrantIsAuditedAndTheViewKeepsItsCreatorAndCreationTime() throws Exception {
    String location =
        IrcViewWrite.parsed(
                IrcHttp.perform(
                    mvc,
                    IrcWrite.post(
                            IrcViewWrite.views(DB),
                            IrcViewWrite.createRequest(
                                "w",
                                IrcViewRoutesTest.SCHEMA,
                                "SELECT 1",
                                Namespace.of(DB),
                                Collections.emptyMap()))
                        .header("Authorization", bearer("irc_rv_alice"))))
            .metadataLocation();
    MvcResult before = get(mvc, DB, "w");
    assertEquals("irc_rv_alice", JsonPath.read(IrcHttp.body(before), "$.viewCreator"));
    Object creationTime = JsonPath.read(IrcHttp.body(before), "$.creationTime");
    assertEquals(204, IrcViewWrite.drop(mvc, DB, "w").getResponse().getStatus());
    org.mockito.Mockito.clearInvocations(viewAuditHandler);

    MvcResult registered =
        IrcHttp.perform(
            mvc,
            IrcWrite.post(
                    IrcRegisterViewRouteTest.registerPath(DB),
                    IrcRegisterViewRouteTest.request("w", location))
                .header("Authorization", bearer("irc_rv_bob")));
    assertEquals(location, IrcViewWrite.parsed(registered).metadataLocation());

    MvcResult after = get(mvc, DB, "w");
    assertEquals("irc_rv_alice", JsonPath.read(IrcHttp.body(after), "$.viewCreator"));
    assertEquals(creationTime, JsonPath.read(IrcHttp.body(after), "$.creationTime"));
    verify(viewAuditHandler)
        .audit(
            org.mockito.ArgumentMatchers.argThat(
                (com.linkedin.openhouse.tables.audit.model.ViewAuditEvent event) ->
                    event != null
                        && DB.equals(event.getDatabaseName())
                        && "w".equals(event.getViewName())
                        && "irc_rv_bob".equals(event.getUser())
                        && event.getOperationType()
                            == com.linkedin.openhouse.tables.audit.model.ViewOperationType.CREATE
                        && event.getOperationStatus()
                            == com.linkedin.openhouse.tables.audit.model.OperationStatus.SUCCESS
                        && location.equals(event.getNewMetadataLocation())));

    // A refused registration is audited as a failed create of the caller's.
    org.mockito.Mockito.clearInvocations(viewAuditHandler);
    IrcHttp.perform(
        mvc,
        IrcWrite.post(
                IrcRegisterViewRouteTest.registerPath(DB),
                IrcRegisterViewRouteTest.request("w", location))
            .header("Authorization", bearer("irc_rv_bob")));
    verify(viewAuditHandler)
        .audit(
            org.mockito.ArgumentMatchers.argThat(
                (com.linkedin.openhouse.tables.audit.model.ViewAuditEvent event) ->
                    event != null
                        && "irc_rv_bob".equals(event.getUser())
                        && event.getOperationType()
                            == com.linkedin.openhouse.tables.audit.model.ViewOperationType.CREATE
                        && event.getOperationStatus()
                            == com.linkedin.openhouse.tables.audit.model.OperationStatus.FAILED
                        && "VIEW_ALREADY_EXISTS".equals(event.getFailureCode())));
  }

  /**
   * A view brought back by registration is read as any other: its IRC load needs the read
   * privilege, though the registration's own answer, decided on the create privilege, carries it.
   */
  @Test
  void aRegisteredViewsLoadNeedsTheReadPrivilege() throws Exception {
    assertEquals(200, register("v").getResponse().getStatus());
    when(authorizationHandler.checkAccessDecision(
            any(), any(TableDto.class), any(Privileges.class)))
        .thenAnswer(call -> call.getArgument(2) != Privileges.GET_TABLE_METADATA);
    MvcResult denied = IrcHttp.get(mvc, IrcViewWrite.view(DB, "v"));
    assertIrcError(denied, 403, "ForbiddenException");
    org.junit.jupiter.api.Assertions.assertFalse(IrcHttp.body(denied).contains("SELECT 1"));
    stubDecision(true);
    assertEquals(200, IrcHttp.get(mvc, IrcViewWrite.view(DB, "v")).getResponse().getStatus());
  }
}
