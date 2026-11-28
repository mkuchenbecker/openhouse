package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.delete;
import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.newTable;
import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.post;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.body;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.api.irc.IcebergRestScanController;
import com.linkedin.openhouse.tables.api.irc.ScanPlanBoundsSwap;
import com.linkedin.openhouse.tables.authorization.AuthorizationHandler;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.model.TableDto;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The plan route's two bounds answered through HTTP (Iceberg 1.12 line only, where the route is
 * served): a plan asked for while as many plans run as the cluster allows is answered 503 {@code
 * ServiceUnavailableException} with a {@code Retry-After} header; a plan that takes longer than its
 * time bound is answered {@code failed} with the time-out error and no task. The bounds are swapped
 * for the test's own ({@link ScanPlanBoundsSwap}); everything else is the route as served.
 */
@SpringBootTest(properties = IrcPlanCredentialsRoutesTest.PLANNING_ON)
// The context configuration of IrcPlanCredentialsRoutesTest and IrcPlanCredentialsAuthTest, so the
// three share one cached context.
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
public class IrcScanPlanBoundsRoutesTest {

  private static final String DB = "irc_plan_bounds";

  @Autowired MockMvc mvc;
  @Autowired IcebergRestScanController scanController;
  @MockBean AuthorizationHandler authorizationHandler;

  @BeforeEach
  void setUp() throws Exception {
    reset(authorizationHandler);
    when(authorizationHandler.checkAccessDecision(
            any(), any(TableDto.class), any(Privileges.class)))
        .thenReturn(true);
    when(authorizationHandler.checkAccessDecision(
            any(), any(DatabaseDto.class), any(Privileges.class)))
        .thenReturn(true);
    when(authorizationHandler.checkSystemOnlyLockAccess(any(), any(), any())).thenCallRealMethod();
    post(mvc, newTable(DB, "t")).andExpect(status().isCreated());
  }

  @AfterEach
  void cleanUp() throws Exception {
    delete(mvc, DB, "t");
  }

  private MvcResult postPlan() throws Exception {
    return IrcHttp.perform(
        mvc,
        IrcHttp.request(HttpMethod.POST, IrcPlanCredentialsRoutesTest.plan(DB, "t"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"));
  }

  @Test
  void aPlanAskedForWhileEveryPlannerIsBusyIs503WithRetryAfter() throws Exception {
    MvcResult busy;
    try (ScanPlanBoundsSwap swap = ScanPlanBoundsSwap.busy(scanController)) {
      busy = postPlan();
    }
    String message = assertIrcError(busy, 503, "ServiceUnavailableException").message();
    assertEquals("1", busy.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
    assertTrue(message.contains("planning 1 scans, as many as it plans at once"), message);
    assertTrue(message.contains("retry later"), message);
    // With the planner free again, the same plan is answered.
    MvcResult planned = postPlan();
    assertEquals(200, planned.getResponse().getStatus(), body(planned));
    assertEquals("completed", JsonPath.read(body(planned), "$.status"));
  }

  @Test
  void aPlanThatTakesLongerThanItsBoundIsAnsweredFailedWithNoTask() throws Exception {
    MvcResult timedOut;
    try (ScanPlanBoundsSwap swap = ScanPlanBoundsSwap.timingOut(scanController)) {
      timedOut = postPlan();
    }
    assertEquals(200, timedOut.getResponse().getStatus(), body(timedOut));
    Map<String, Object> answer = JsonPath.read(body(timedOut), "$");
    assertEquals("failed", answer.get("status"), body(timedOut));
    assertEquals(422, (int) JsonPath.read(body(timedOut), "$.error.code"));
    assertEquals("ScanPlanTimeoutException", JsonPath.read(body(timedOut), "$.error.type"));
    String message = JsonPath.read(body(timedOut), "$.error.message");
    assertTrue(message.contains("took longer to plan than the"), message);
    assertTrue(message.contains("cluster.tables.irc.scan-planning.timeout-ms"), message);
    assertTrue(message.contains("scan-planning-mode=client"), message);
    assertFalse(answer.containsKey("plan-id"), body(timedOut));
    assertFalse(answer.containsKey("file-scan-tasks"), body(timedOut));
    assertFalse(answer.containsKey("plan-tasks"), body(timedOut));
  }
}
