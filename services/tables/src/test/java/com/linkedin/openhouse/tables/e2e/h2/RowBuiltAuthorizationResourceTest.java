package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.NativeViewHttp.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jayway.jsonpath.JsonPath;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.tables.authorization.AuthorizationHandler;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.toggle.repository.ToggleStatusesRepository;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.iceberg.view.ViewMetadata;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * KNOWN DEFECT, pinned: the paths below decide a table-level privilege on a resource built from the
 * entity's House Tables row, and House Tables keeps no UUID ({@code UserTable} has no such field,
 * {@code user_table_row} no such column), so in production the resource names no UUID. An
 * authorization handler that keys table-level grants on the UUID (the OPA handler does) then sees
 * none, and only database-level grants can decide. The resource of every other table decision is
 * read from the table's metadata, which does carry the UUID.
 *
 * <p>Each test asserts what production passes: a null UUID, while the entity's own UUID (in its
 * metadata) is set. A fix makes these tests fail on purpose. The IRC view load is pinned the same
 * way in {@code IrcViewRoutesAuthTest.anIrcViewLoadNeedsTheReadPrivilege}.
 *
 * <p>Where the resource is built: {@code OpenHouseInternalRepositoryImpl.findTableRefById} (the
 * native table drop, and the IRC metrics report), and {@code ViewsServiceImpl
 * .authorizationReference} (the native view replace and drop, the IRC view load and commit). The
 * native drop and the native view replace and drop are LinkedIn main's code.
 *
 * <p>Same Spring context as {@code IrcViewRoutesAuthTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(IrcViewRoutesTest.ViewAudit.class)
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
public class RowBuiltAuthorizationResourceTest {
  private static final String DB = "row_auth";

  @Autowired MockMvc mvc;
  @Autowired ToggleStatusesRepository toggles;
  @Autowired HouseTableRepository houseTables;
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
    enableViews(toggles, DB);
    assertEquals(201, code(CommitPathHttp.post(mvc, CommitPathHttp.newTable(DB, "t")).andReturn()));
    NativeViewHttp.delete(mvc, DB, "v");
    assertEquals(
        201,
        code(post(mvc, createBody(DB, "v", SCHEMA_ID_NAME, "SELECT 1", Collections.emptyMap()))));
  }

  @AfterEach
  void cleanUp() throws Exception {
    NativeViewHttp.delete(mvc, DB, "v");
    CommitPathHttp.delete(mvc, DB, "t");
  }

  /**
   * The table-level decisions made since the last clear, on {@code name}, with their privileges.
   */
  private List<TableDto> decisionsOn(String name) {
    ArgumentCaptor<TableDto> resources = ArgumentCaptor.forClass(TableDto.class);
    verify(authorizationHandler, atLeastOnce())
        .checkAccessDecision(any(), resources.capture(), any(Privileges.class));
    return resources.getAllValues().stream()
        .filter(resource -> name.equals(resource.getTableId()))
        .collect(Collectors.toList());
  }

  private static void assertNoUuid(List<TableDto> decisions) {
    assertFalse(decisions.isEmpty(), "a table-level decision is made");
    for (TableDto resource : decisions) {
      assertNull(resource.getTableUUID(), "House Tables keeps no UUID: " + resource);
    }
  }

  private String tableUuid() throws Exception {
    MvcResult got = CommitPathHttp.get(mvc, DB, "t").andReturn();
    String uuid = JsonPath.read(got.getResponse().getContentAsString(), "$.tableUUID");
    assertNotNull(uuid, "the table's metadata carries its UUID");
    return uuid;
  }

  private String viewUuid() throws Exception {
    ViewMetadata view = IrcViewWrite.load(mvc, DB, "v");
    assertNotNull(view.uuid(), "the view's metadata carries its UUID");
    return view.uuid();
  }

  /** The native DROP TABLE decides DELETE_TABLE on the row-built resource. */
  @Test
  void theNativeTableDropDecidesOnAResourceWithNoUuid() throws Exception {
    tableUuid();
    clearInvocations(authorizationHandler);
    assertEquals(204, code(CommitPathHttp.delete(mvc, DB, "t").andReturn()));
    verify(authorizationHandler)
        .checkAccessDecision(
            any(),
            org.mockito.ArgumentMatchers.argThat(
                (TableDto resource) ->
                    resource != null
                        && "t".equals(resource.getTableId())
                        && resource.getTableUUID() == null),
            org.mockito.ArgumentMatchers.eq(Privileges.DELETE_TABLE));
    assertNoUuid(decisionsOn("t"));
  }

  /** The IRC metrics report decides GET_TABLE_METADATA on the row-built resource. */
  @Test
  void theIrcMetricsReportDecidesOnAResourceWithNoUuid() throws Exception {
    tableUuid();
    clearInvocations(authorizationHandler);
    MvcResult reported =
        IrcHttp.perform(
            mvc,
            IrcHttp.request(HttpMethod.POST, IrcMetricsRouteTest.metrics(DB, "t"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(IrcMetricsRouteTest.SCAN_REPORT.replace("irc_metrics", DB)));
    assertEquals(204, reported.getResponse().getStatus(), IrcHttp.body(reported));
    List<TableDto> decisions = decisionsOn("t");
    assertNoUuid(decisions);
    verify(authorizationHandler)
        .checkAccessDecision(
            any(),
            org.mockito.ArgumentMatchers.argThat(
                (TableDto resource) -> resource != null && "t".equals(resource.getTableId())),
            org.mockito.ArgumentMatchers.eq(Privileges.GET_TABLE_METADATA));
  }

  /** The native view replace decides on the row-built resource of the view it replaces. */
  @Test
  void theNativeViewReplaceDecidesOnAResourceWithNoUuid() throws Exception {
    ViewMetadata base = IrcViewWrite.load(mvc, DB, "v");
    viewUuid();
    clearInvocations(authorizationHandler);
    MvcResult replaced =
        put(
            mvc,
            replaceBody(
                DB,
                "v",
                base.metadataFileLocation(),
                SCHEMA_ID_NAME,
                Collections.singletonList(spark("SELECT 2")),
                StoredViewMetadataGoldenTest.storedProperties(houseTables, DB)));
    assertEquals(200, code(replaced), body(replaced));
    assertNoUuid(decisionsOn("v"));
  }

  /** The native view drop decides on the row-built resource. */
  @Test
  void theNativeViewDropDecidesOnAResourceWithNoUuid() throws Exception {
    viewUuid();
    clearInvocations(authorizationHandler);
    MvcResult dropped = NativeViewHttp.delete(mvc, DB, "v");
    assertEquals(204, code(dropped), body(dropped));
    assertNoUuid(decisionsOn("v"));
  }

  /** The IRC view commit decides the read and the replace on the row-built resource. */
  @Test
  void theIrcViewCommitDecidesOnAResourceWithNoUuid() throws Exception {
    ViewMetadata base = IrcViewWrite.load(mvc, DB, "v");
    viewUuid();
    clearInvocations(authorizationHandler);
    MvcResult committed =
        IrcViewWrite.commit(
            mvc,
            DB,
            "v",
            IrcViewWrite.commitRequest(
                base, IrcViewWrite.properties(base, Collections.singletonMap("k", "v"))));
    assertEquals(200, committed.getResponse().getStatus(), IrcHttp.body(committed));
    List<TableDto> decisions = decisionsOn("v");
    assertNoUuid(decisions);
    assertTrue(decisions.size() >= 2, "the read and the replace: " + decisions);
  }
}
