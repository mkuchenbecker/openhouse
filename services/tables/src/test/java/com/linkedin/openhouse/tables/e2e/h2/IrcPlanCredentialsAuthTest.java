package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.delete;
import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.lock;
import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.newTable;
import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.post;
import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.unlock;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.body;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linkedin.openhouse.common.security.DummyTokenInterceptor;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.authorization.AuthorizationHandler;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.model.TableDto;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Authentication and authorization on the IRC scan planning and credentials routes, with the
 * fixture of {@link IrcReadRoutesAuthTest}: each answer is compared with the IRC load's answer to
 * the same principal for the same table, byte for byte, since both routes run the load's checks.
 */
@SpringBootTest(properties = IrcPlanCredentialsRoutesTest.PLANNING_ON)
// A context of its own (scan planning on), closed after the class rather than kept in the cache
// with the shared ones, as the other planning tests' contexts are.
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
public class IrcPlanCredentialsAuthTest {

  private static final String DB = "irc_plan_auth";

  @Autowired MockMvc mvc;
  @MockBean AuthorizationHandler authorizationHandler;

  private final List<String> created = new ArrayList<>();
  private String token;

  @BeforeEach
  void allowEverything() throws Exception {
    reset(authorizationHandler);
    stubDecision(true);
    token = new DummyTokenInterceptor.DummySecurityJWT("irc_user").buildNoopJWT();
  }

  @AfterEach
  void cleanUp() throws Exception {
    reset(authorizationHandler);
    stubDecision(true);
    for (String table : created) {
      unlock(mvc, DB, table);
      delete(mvc, DB, table);
    }
    created.clear();
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

  private void create(String table) throws Exception {
    created.add(table);
    post(mvc, newTable(DB, table)).andExpect(status().isCreated());
  }

  private MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request) {
    return request.header("Authorization", "Bearer " + token);
  }

  private MvcResult load(String table) throws Exception {
    return IrcHttp.perform(
        mvc,
        withToken(IrcHttp.request(HttpMethod.GET, "/v1/namespaces/" + DB + "/tables/" + table)));
  }

  private MvcResult plan(String table) throws Exception {
    return IrcHttp.perform(
        mvc,
        withToken(IrcHttp.request(HttpMethod.POST, IrcPlanCredentialsRoutesTest.plan(DB, table)))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"));
  }

  private MvcResult credentials(String table) throws Exception {
    return IrcHttp.perform(
        mvc,
        withToken(
            IrcHttp.request(HttpMethod.GET, IrcPlanCredentialsRoutesTest.credentials(DB, table))));
  }

  /** Both routes answer {@code expected}'s status and body, which is an IRC error. */
  private void assertAnsweredAsLoad(String table, int status, String type) throws Exception {
    MvcResult load = load(table);
    assertIrcError(load, status, type);
    for (MvcResult other : new MvcResult[] {plan(table), credentials(table)}) {
      assertEquals(status, other.getResponse().getStatus(), body(other));
      assertEquals(body(load), body(other));
    }
  }

  @Test
  void aDeniedPrincipalIs403AsForALoad() throws Exception {
    assumeTrue(IrcPlanCredentialsRoutesTest.served());
    create("t1");
    assertEquals(200, plan("t1").getResponse().getStatus(), body(plan("t1")));
    assertEquals(200, credentials("t1").getResponse().getStatus());
    stubDecision(false);
    assertAnsweredAsLoad("t1", 403, "ForbiddenException");
    String message = assertIrcError(plan("t1"), 403, "ForbiddenException").message();
    assertTrue(message.contains("unauthorized"), message);
  }

  /**
   * A locked table needs the lock-admin privilege to be loaded, so to be planned or asked for
   * credentials: a principal with the read privilege only is refused as a load is.
   */
  @Test
  void aLockedTableNeedsTheLockAdminPrivilegeAsForALoad() throws Exception {
    assumeTrue(IrcPlanCredentialsRoutesTest.served());
    create("locked");
    lock(mvc, DB, "locked").andExpect(status().is2xxSuccessful());
    when(authorizationHandler.checkAccessDecision(
            any(), any(TableDto.class), eq(Privileges.LOCK_ADMIN)))
        .thenReturn(false);
    assertAnsweredAsLoad("locked", 403, "ForbiddenException");
    assertTrue(
        assertIrcError(plan("locked"), 403, "ForbiddenException")
            .message()
            .contains("Locked table"));
  }

  /** A missing table is 404 before any decision, as for a load, allowed or not. */
  @Test
  void aMissingTableIs404AsForALoad() throws Exception {
    assumeTrue(IrcPlanCredentialsRoutesTest.served());
    assertAnsweredAsLoad("no_such_table", 404, "NoSuchTableException");
    stubDecision(false);
    assertAnsweredAsLoad("no_such_table", 404, "NoSuchTableException");
  }

  /** Without a token, both routes are refused before they run, on either Iceberg line. */
  @Test
  void aMissingTokenIs401() throws Exception {
    assertIrcError(
        IrcHttp.perform(
            mvc,
            IrcHttp.request(HttpMethod.POST, IrcPlanCredentialsRoutesTest.plan(DB, "t1"))
                .header("Authorization", "")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")),
        401,
        "NotAuthorizedException");
    assertIrcError(
        IrcHttp.perform(
            mvc,
            IrcHttp.request(HttpMethod.GET, IrcPlanCredentialsRoutesTest.credentials(DB, "t1"))
                .header("Authorization", "")),
        401,
        "NotAuthorizedException");
  }
}
