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
}
