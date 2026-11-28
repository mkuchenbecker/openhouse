package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.NativeViewHttp.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * The resource a table-level privilege is decided on when the path builds it from the entity's
 * House Tables row. House Tables keeps no UUID ({@code UserTable} has no such field, {@code
 * user_table_row} no such column). An authorization handler that keys table-level grants on the
 * UUID (the OPA handler does) sees none on such a resource, and only database-level grants can
 * decide.
 *
 * <p>KNOWN DEFECT, pinned: the native DROP TABLE ({@code findTableRefById}), the native view
 * replace and the native view drop ({@code ViewsServiceImpl.authorizationReference}) decide on a
 * resource with no UUID. They are LinkedIn main's code; a fix makes these tests fail on purpose.
 * The IRC view drop and the IRC view commit's write reach the native view drop and replace, so
 * those two decisions name no UUID either.
 *
 * <p>The Iceberg REST paths that build the resource themselves take the UUID from the name of the
 * directory the row's location lies in ({@code <name>-<uuid>}, as OpenHouse allocates it): the
 * metrics report, the view load, and the view commit's own decisions (its first read and replace
 * decisions, and the reads of its base and of its answer). The tests with a grant keyed on the
 * entity's UUID show what that means for a principal who holds only a table-level or view-level
 * grant.
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

  private void allowAll() {
    reset(authorizationHandler);
    when(authorizationHandler.checkAccessDecision(
            any(), any(TableDto.class), any(Privileges.class)))
        .thenReturn(true);
    when(authorizationHandler.checkAccessDecision(
            any(), any(DatabaseDto.class), any(Privileges.class)))
        .thenReturn(true);
    when(authorizationHandler.checkSystemOnlyLockAccess(any(), any(), any())).thenCallRealMethod();
  }

  @BeforeEach
  void setUp() throws Exception {
    allowAll();
    enableViews(toggles, DB);
    assertEquals(201, code(CommitPathHttp.post(mvc, CommitPathHttp.newTable(DB, "t")).andReturn()));
    NativeViewHttp.delete(mvc, DB, "v");
    assertEquals(
        201,
        code(post(mvc, createBody(DB, "v", SCHEMA_ID_NAME, "SELECT 1", Collections.emptyMap()))));
  }

  @AfterEach
  void cleanUp() throws Exception {
    allowAll();
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

  /**
   * The IRC metrics report decides GET_TABLE_METADATA on the row's resource with the table's UUID,
   * taken from the name of the directory the row's location lies in.
   */
  @Test
  void theIrcMetricsReportDecidesOnTheTablesUuid() throws Exception {
    String uuid = tableUuid();
    clearInvocations(authorizationHandler);
    MvcResult reported = report();
    assertEquals(204, reported.getResponse().getStatus(), IrcHttp.body(reported));
    verify(authorizationHandler)
        .checkAccessDecision(
            any(),
            org.mockito.ArgumentMatchers.argThat(
                (TableDto resource) ->
                    resource != null
                        && "t".equals(resource.getTableId())
                        && uuid.equals(resource.getTableUUID())),
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

  /**
   * The IRC view commit: its own decisions (the read and replace decided first, and the reads of
   * the base and of the answer) carry the view's UUID; the native replace it reaches through {@code
   * updateView} still decides on a resource with none (LinkedIn main's code, pinned above).
   */
  @Test
  void theIrcViewCommitDecidesOnTheViewsUuidExceptInTheNativeReplace() throws Exception {
    ViewMetadata base = IrcViewWrite.load(mvc, DB, "v");
    String uuid = viewUuid();
    clearInvocations(authorizationHandler);
    MvcResult committed = commit(base);
    assertEquals(200, committed.getResponse().getStatus(), IrcHttp.body(committed));
    assertEquals(
        java.util.Arrays.asList(
            "GET_TABLE_METADATA " + uuid,
            "UPDATE_TABLE_METADATA " + uuid,
            "GET_TABLE_METADATA " + uuid,
            "UPDATE_TABLE_METADATA null",
            "GET_TABLE_METADATA " + uuid),
        decisionsInOrder("v"));
  }

  // a grant keyed on the entity's UUID, as the OPA handler keys table-level roles

  /**
   * Allows exactly {@code privileges}, on the entity whose UUID is {@code uuid}, and nothing else:
   * no database-level privilege, no other entity. A resource that names no UUID is refused.
   */
  private void grantOnlyOn(String uuid, Privileges... privileges) {
    java.util.Set<Privileges> granted =
        new java.util.HashSet<>(java.util.Arrays.asList(privileges));
    when(authorizationHandler.checkAccessDecision(
            any(), any(TableDto.class), any(Privileges.class)))
        .thenAnswer(
            call -> {
              TableDto resource = call.getArgument(1);
              return uuid.equals(resource.getTableUUID()) && granted.contains(call.getArgument(2));
            });
    when(authorizationHandler.checkAccessDecision(
            any(), any(DatabaseDto.class), any(Privileges.class)))
        .thenReturn(false);
  }

  private MvcResult report() throws Exception {
    return IrcHttp.perform(
        mvc,
        IrcHttp.request(HttpMethod.POST, IrcMetricsRouteTest.metrics(DB, "t"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(IrcMetricsRouteTest.SCAN_REPORT.replace("irc_metrics", DB)));
  }

  private MvcResult commit(ViewMetadata base) throws Exception {
    return IrcViewWrite.commit(
        mvc,
        DB,
        "v",
        IrcViewWrite.commitRequest(
            base, IrcViewWrite.properties(base, Collections.singletonMap("k", "v"))));
  }

  /** The table-level decisions on {@code name}, in order, as {@code <privilege> <uuid>}. */
  private List<String> decisionsInOrder(String name) {
    ArgumentCaptor<TableDto> resources = ArgumentCaptor.forClass(TableDto.class);
    ArgumentCaptor<Privileges> privileges = ArgumentCaptor.forClass(Privileges.class);
    verify(authorizationHandler, atLeastOnce())
        .checkAccessDecision(any(), resources.capture(), privileges.capture());
    List<String> decisions = new java.util.ArrayList<>();
    for (int i = 0; i < resources.getAllValues().size(); i++) {
      TableDto resource = resources.getAllValues().get(i);
      if (name.equals(resource.getTableId())) {
        decisions.add(privileges.getAllValues().get(i) + " " + resource.getTableUUID());
      }
    }
    return decisions;
  }

  @Test
  void aGrantOnTheTableAloneAllowsItsMetricsReport() throws Exception {
    String uuid = tableUuid();
    grantOnlyOn(uuid, Privileges.GET_TABLE_METADATA);
    assertEquals(204, report().getResponse().getStatus());

    grantOnlyOn(java.util.UUID.randomUUID().toString(), Privileges.GET_TABLE_METADATA);
    assertEquals(403, report().getResponse().getStatus());
  }

  @Test
  void aGrantOnTheViewAloneAllowsItsIrcLoad() throws Exception {
    String uuid = viewUuid();
    grantOnlyOn(uuid, Privileges.GET_TABLE_METADATA);
    assertEquals(200, IrcHttp.get(mvc, IrcViewWrite.view(DB, "v")).getResponse().getStatus());

    grantOnlyOn(java.util.UUID.randomUUID().toString(), Privileges.GET_TABLE_METADATA);
    assertEquals(403, IrcHttp.get(mvc, IrcViewWrite.view(DB, "v")).getResponse().getStatus());
  }

  /**
   * A grant on the view alone passes the IRC commit's own decisions, and the commit is then refused
   * by the native replace it reaches, which decides on a resource with no UUID (LinkedIn main's
   * code): the remaining gap, pinned. Without the grant the first decision refuses it.
   */
  @Test
  void aGrantOnTheViewAlonePassesTheIrcCommitsDecisionsAndNotTheNativeReplace() throws Exception {
    ViewMetadata base = IrcViewWrite.load(mvc, DB, "v");
    String uuid = viewUuid();
    grantOnlyOn(uuid, Privileges.GET_TABLE_METADATA, Privileges.UPDATE_TABLE_METADATA);
    clearInvocations(authorizationHandler);
    assertEquals(403, commit(base).getResponse().getStatus());
    assertEquals(
        java.util.Arrays.asList(
            "GET_TABLE_METADATA " + uuid,
            "UPDATE_TABLE_METADATA " + uuid,
            "GET_TABLE_METADATA " + uuid,
            "UPDATE_TABLE_METADATA null"),
        decisionsInOrder("v"));

    grantOnlyOn(java.util.UUID.randomUUID().toString(), Privileges.GET_TABLE_METADATA);
    clearInvocations(authorizationHandler);
    assertEquals(403, commit(base).getResponse().getStatus());
    assertEquals(Collections.singletonList("GET_TABLE_METADATA " + uuid), decisionsInOrder("v"));
  }
}
