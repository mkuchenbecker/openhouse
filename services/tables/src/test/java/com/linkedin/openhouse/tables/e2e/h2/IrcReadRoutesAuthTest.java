package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.body;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Authentication and authorization on the IRC read routes, with the same fixture as {@link
 * CommitPathAuthorizationTest}: the token interceptor and authorization are on, and only the {@link
 * AuthorizationHandler} decision is switched per test. Each IRC answer is compared with the native
 * route's answer to the same principal.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
public class IrcReadRoutesAuthTest {

  private static final String DB = "irc_auth";

  /** Sends the default test token on every request (see {@code MockMvcBuilderConfig}). */
  @Autowired MockMvc mvc;

  @Autowired WebApplicationContext webApplicationContext;
  @MockBean AuthorizationHandler authorizationHandler;
  @Autowired io.micrometer.core.instrument.MeterRegistry meterRegistry;

  private final List<String[]> created = new ArrayList<>();
  private String token;

  @BeforeEach
  void allowEverything() throws Exception {
    reset(authorizationHandler);
    stubDecision(true);
    token = new DummyTokenInterceptor.DummySecurityJWT("irc_user").buildNoopJWT();
  }

  @AfterEach
  void cleanUp() throws Exception {
    stubDecision(true);
    for (String[] key : created) {
      delete(mvc, key[0], key[1]);
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
    created.add(new String[] {DB, table});
    post(mvc, newTable(DB, table)).andExpect(status().isCreated());
  }

  private MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request) {
    return request.header("Authorization", "Bearer " + token);
  }

  /** A client on the same web context that sends no Authorization header at all. */
  private MockMvc withoutCredentials() {
    return MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  private MvcResult irc(HttpMethod method, String path) throws Exception {
    return IrcHttp.perform(mvc, withToken(IrcHttp.request(method, path)));
  }

  @Test
  void aDeniedPrincipalCannotLoadATableAs403Forbidden() throws Exception {
    create("t1");
    stubDecision(false);

    String nativeMessage =
        message(
            mvc.perform(withToken(MockMvcRequestBuilders.get(tableUrl(DB, "t1"))))
                .andExpect(status().isForbidden()));
    assertTrue(nativeMessage.contains("unauthorized"), nativeMessage);
    assertEquals(
        nativeMessage,
        assertIrcError(
                irc(HttpMethod.GET, "/v1/namespaces/" + DB + "/tables/t1"),
                403,
                "ForbiddenException")
            .message());
  }

  /**
   * A metrics report is authorized like a read of the table: the read privilege is asked for, and a
   * principal without it is refused 403 and its report is not counted.
   */
  @Test
  void aDeniedPrincipalCannotReportMetricsAs403Forbidden() throws Exception {
    create("t1");
    String path = IrcMetricsRouteTest.metrics(DB, "t1");
    MvcResult allowed = reportMetrics(path);
    assertEquals(204, allowed.getResponse().getStatus(), body(allowed));
    org.mockito.Mockito.verify(authorizationHandler)
        .checkAccessDecision(
            org.mockito.ArgumentMatchers.eq("irc_user"),
            org.mockito.ArgumentMatchers.argThat(
                (TableDto table) ->
                    table != null
                        && DB.equals(table.getDatabaseId())
                        && "t1".equals(table.getTableId())),
            org.mockito.ArgumentMatchers.eq(Privileges.GET_TABLE_METADATA));

    stubDecision(false);
    double counted =
        meterRegistry
            .counter(IrcMetricsRouteTest.COUNTER, IrcMetricsRouteTest.TYPE_TAG, "scan_report")
            .count();
    String message = assertIrcError(reportMetrics(path), 403, "ForbiddenException").message();
    assertTrue(message.contains("unauthorized"), message);
    assertEquals(
        counted,
        meterRegistry
            .counter(IrcMetricsRouteTest.COUNTER, IrcMetricsRouteTest.TYPE_TAG, "scan_report")
            .count());
    // A missing table is 404 before any decision, as for a load.
    assertIrcError(
        reportMetrics(IrcMetricsRouteTest.metrics(DB, "no_such_table")),
        404,
        "NoSuchTableException");
  }

  private MvcResult reportMetrics(String path) throws Exception {
    return IrcHttp.perform(
        mvc,
        withToken(IrcHttp.request(HttpMethod.POST, path))
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content(IrcMetricsRouteTest.SCAN_REPORT));
  }

  /**
   * The native routes that list databases and tables and check existence make no authorization
   * decision, so the IRC routes that answer the same questions make none either.
   */
  @Test
  void listingAndExistenceAreAnsweredForADeniedPrincipalAsNatively() throws Exception {
    create("t1");
    stubDecision(false);

    assertEquals(200, irc(HttpMethod.GET, "/v1/namespaces").getResponse().getStatus());
    mvc.perform(withToken(MockMvcRequestBuilders.get("/v1/databases"))).andExpect(status().isOk());

    assertEquals(
        200, irc(HttpMethod.GET, "/v1/namespaces/" + DB + "/tables").getResponse().getStatus());
    mvc.perform(withToken(MockMvcRequestBuilders.post("/v1/databases/" + DB + "/tables/search")))
        .andExpect(status().isOk());

    assertEquals(
        204, irc(HttpMethod.HEAD, "/v1/namespaces/" + DB + "/tables/t1").getResponse().getStatus());
    mvc.perform(withToken(MockMvcRequestBuilders.get(tableUrl(DB, "t1") + "/exists")))
        .andExpect(status().isOk());

    assertEquals(200, irc(HttpMethod.GET, "/v1/namespaces/" + DB).getResponse().getStatus());
  }

  @Test
  void aMissingTokenIs401NotAuthorized() throws Exception {
    for (String path :
        new String[] {
          "/v1/config", "/v1/namespaces", "/v1/namespaces/" + DB + "/tables/t1", "/v1/unknown"
        }) {
      assertIrcError(IrcHttp.get(withoutCredentials(), path), 401, "NotAuthorizedException");
      assertIrcError(
          IrcHttp.perform(mvc, IrcHttp.request(HttpMethod.GET, path).header("Authorization", "")),
          401,
          "NotAuthorizedException");
    }
    assertIrcError(
        IrcHttp.perform(
            withoutCredentials(),
            IrcHttp.request(HttpMethod.POST, IrcMetricsRouteTest.metrics(DB, "t1"))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(IrcMetricsRouteTest.SCAN_REPORT)),
        401,
        "NotAuthorizedException");
  }

  @Test
  void aBadTokenIs401NotAuthorized() throws Exception {
    assertIrcError(
        IrcHttp.perform(
            mvc,
            IrcHttp.request(HttpMethod.GET, "/v1/config")
                .header("Authorization", "Bearer not-a-token")),
        401,
        "NotAuthorizedException");
    assertIrcError(
        IrcHttp.perform(
            mvc, IrcHttp.request(HttpMethod.GET, "/v1/config").header("Authorization", "Basic x")),
        401,
        "NotAuthorizedException");
  }

  /** The native routes keep the token interceptor's own answer: 401 with an empty body. */
  @Test
  void aMissingTokenOnANativeRouteIsUnchanged() throws Exception {
    MvcResult result =
        withoutCredentials().perform(MockMvcRequestBuilders.get("/v1/databases")).andReturn();
    assertEquals(401, result.getResponse().getStatus());
    assertEquals("", body(result));
  }

  @Test
  void aValidTokenReachesTheRoutes() throws Exception {
    create("t1");
    MvcResult result = irc(HttpMethod.GET, "/v1/namespaces/" + DB + "/tables/t1");
    assertEquals(200, result.getResponse().getStatus(), body(result));
  }
}
