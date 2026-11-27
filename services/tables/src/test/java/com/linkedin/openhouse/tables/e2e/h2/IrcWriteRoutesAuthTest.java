package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.*;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static com.linkedin.openhouse.tables.model.TableModelConstants.HEALTH_SCHEMA_LITERAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.gson.Gson;
import com.linkedin.openhouse.common.audit.AuditHandler;
import com.linkedin.openhouse.common.schema.IcebergSchemaHelper;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Policies;
import com.linkedin.openhouse.tables.audit.model.OperationStatus;
import com.linkedin.openhouse.tables.audit.model.OperationType;
import com.linkedin.openhouse.tables.audit.model.TableAuditEvent;
import com.linkedin.openhouse.tables.authorization.AuthorizationHandler;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.model.TableDto;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
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

/**
 * Authorization on the IRC write routes, with the fixture of {@link IrcReadRoutesAuthTest} (same
 * Spring context): only the {@link AuthorizationHandler} decision is switched. Each refusal is the
 * native route's refusal of the same principal, as 403 {@code ForbiddenException}, and changes
 * nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(IrcViewRoutesTest.ViewAudit.class)
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
public class IrcWriteRoutesAuthTest {
  private static final String DB = "irc_wauth";

  @Autowired MockMvc mvc;
  @MockBean AuthorizationHandler authorizationHandler;
  @Autowired AuditHandler<TableAuditEvent> tableAuditHandler;

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
    post(mvc, newTable(DB, table).toBuilder().policies(null).build())
        .andExpect(status().isCreated());
  }

  @Test
  void aDeniedCreateIs403AsNatively() throws Exception {
    create("t0");
    stubDecision(false);
    String nativeMessage = message(post(mvc, newTable(DB, "t1")).andExpect(status().isForbidden()));
    assertEquals(
        nativeMessage,
        assertIrcError(
                IrcWrite.create(
                    mvc,
                    DB,
                    CreateTableRequest.builder()
                        .withName("t1")
                        .withSchema(
                            IcebergSchemaHelper.getSchemaFromSchemaJson(HEALTH_SCHEMA_LITERAL))
                        .build()),
                403,
                "ForbiddenException")
            .message());
    assertEquals(404, CommitPathHttp.status(get(mvc, DB, "t1")));
  }

  /**
   * Register is authorized as a create in that database, with the native create's refusal, before
   * anything is read from storage: the refusal is the same whether or not the file exists.
   */
  @Test
  void aDeniedRegisterIs403AsADeniedCreateAndReadsNothing() throws Exception {
    String location =
        "/tmp/" + DB + "/t9-" + java.util.UUID.randomUUID() + "/00000-none.metadata.json";
    // Allowed, the request gets as far as the file, which is not there.
    assertIrcError(register("t9", location), 400, "BadRequestException");
    org.mockito.Mockito.verify(authorizationHandler)
        .checkAccessDecision(
            any(),
            org.mockito.ArgumentMatchers.argThat(
                (DatabaseDto database) -> database != null && DB.equals(database.getDatabaseId())),
            org.mockito.ArgumentMatchers.eq(Privileges.CREATE_TABLE));

    stubDecision(false);
    String nativeMessage = message(post(mvc, newTable(DB, "t9")).andExpect(status().isForbidden()));
    assertEquals(
        nativeMessage,
        assertIrcError(register("t9", location), 403, "ForbiddenException").message());
    assertEquals(404, CommitPathHttp.status(get(mvc, DB, "t9")));
  }

  /**
   * The commit that completes a staged create is authorized as a create in that database before the
   * house-table rows are looked at for the directory it names (as register is): a principal who may
   * not create tables there gets the native create's refusal, which does not say whose directory
   * that is.
   */
  @Test
  void aDeniedCompletionNamingALiveTablesDirectoryIs403AndDoesNotSayWhoseItIs() throws Exception {
    create("t5");
    assertEquals(
        204,
        IrcWrite.rename(mvc, TableIdentifier.of(DB, "t5"), TableIdentifier.of(DB, "t6"))
            .getResponse()
            .getStatus());
    created.add("t6");
    String directory = IrcWrite.load(mvc, DB, "t6").location();
    TableMetadata staged =
        IrcWrite.parsed(
            IrcWrite.create(
                mvc,
                DB,
                CreateTableRequest.builder()
                    .withName("t5")
                    .withSchema(IcebergSchemaHelper.getSchemaFromSchemaJson(HEALTH_SCHEMA_LITERAL))
                    .stageCreate()
                    .build()));
    org.apache.iceberg.rest.requests.UpdateTableRequest completion =
        IrcWrite.createCommit(
            TableMetadata.buildFrom(staged).discardChanges().setLocation(directory).build(),
            Collections.emptyList());
    // Allowed to create, the principal is told whose directory it is.
    assertEquals(
        "Cannot create table " + DB + ".t5: that directory belongs to " + DB + ".t6",
        assertIrcError(IrcWrite.commit(mvc, DB, "t5", completion), 400, "BadRequestException")
            .message());

    stubDecision(false);
    String nativeMessage = message(post(mvc, newTable(DB, "t5")).andExpect(status().isForbidden()));
    String message =
        assertIrcError(IrcWrite.commit(mvc, DB, "t5", completion), 403, "ForbiddenException")
            .message();
    assertEquals(nativeMessage, message);
    assertTrue(!message.contains("t6"), message);
    stubDecision(true);
    assertEquals(404, CommitPathHttp.status(get(mvc, DB, "t5")));
    assertEquals(200, CommitPathHttp.status(get(mvc, DB, "t6")));
  }

  private org.springframework.test.web.servlet.MvcResult register(String name, String location)
      throws Exception {
    return IrcHttp.perform(
        mvc,
        IrcHttp.request(
                org.springframework.http.HttpMethod.POST, IrcRegisterRouteTest.registerPath(DB))
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content(IrcRegisterRouteTest.registerBody(name, location)));
  }

  /**
   * A policy change by a principal without the privilege: the native refusal of the client's
   * request (the key stripped, the patch merged into its policies) is the IRC refusal.
   */
  @Test
  void aDeniedPolicyChangeIs403AsNatively() throws Exception {
    create("t3");
    TableMetadata base = IrcWrite.load(mvc, DB, "t3");
    String getJson = json(get(mvc, DB, "t3"));
    stubDecision(false);
    String nativeMessage =
        message(
            put(
                    mvc,
                    bodyFromGet(getJson)
                        .toBuilder()
                        .policies(new Gson().fromJson("{\"sharingEnabled\":true}", Policies.class))
                        .build())
                .andExpect(status().isForbidden()));
    String message =
        assertIrcError(
                IrcWrite.commit(
                    mvc,
                    DB,
                    "t3",
                    IrcWrite.simple(
                        base,
                        Collections.singletonList(
                            new MetadataUpdate.SetProperties(
                                Collections.singletonMap(
                                    "updated.openhouse.policy", "{\"sharingEnabled\": TRUE}"))))),
                403,
                "ForbiddenException")
            .message();
    assertEquals(nativeMessage, message);
    stubDecision(true);
    assertEquals(base.metadataFileLocation(), IrcWrite.load(mvc, DB, "t3").metadataFileLocation());
  }

  @Test
  void aDeniedCommitIs403AsNatively() throws Exception {
    create("t2");
    TableMetadata base = IrcWrite.load(mvc, DB, "t2");
    String getJson = json(get(mvc, DB, "t2"));
    stubDecision(false);
    String nativeMessage =
        message(
            put(mvc, bodyFromGet(getJson).toBuilder().sortOrder(null).build())
                .andExpect(status().isForbidden()));
    String message =
        assertIrcError(
                IrcWrite.commit(
                    mvc,
                    DB,
                    "t2",
                    IrcWrite.simple(
                        base,
                        Collections.singletonList(
                            new MetadataUpdate.SetProperties(
                                Collections.singletonMap("user.k", "v"))))),
                403,
                "ForbiddenException")
            .message();
    assertEquals(nativeMessage, message);
    stubDecision(true);
    assertEquals(base.metadataFileLocation(), IrcWrite.load(mvc, DB, "t2").metadataFileLocation());
  }

  private static org.apache.iceberg.rest.requests.UpdateTableRequest setProperty(
      TableMetadata base) {
    return IrcWrite.simple(
        base,
        Collections.singletonList(
            new MetadataUpdate.SetProperties(Collections.singletonMap("user.k", "v"))));
  }

  /**
   * A transaction of one table (the multi-table commit route) is authorized as the ordinary commit
   * of that table, table by table: the principal is refused on the table it may not write, with the
   * ordinary commit's refusal (the native one), and commits the table it may.
   */
  @Test
  void aTransactionIsAuthorizedAsTheOrdinaryCommitOfItsTable() throws Exception {
    create("tx1");
    create("tx2");
    TableMetadata allowed = IrcWrite.load(mvc, DB, "tx1");
    TableMetadata denied = IrcWrite.load(mvc, DB, "tx2");
    String getJson = json(get(mvc, DB, "tx2"));
    when(authorizationHandler.checkAccessDecision(
            any(),
            org.mockito.ArgumentMatchers.argThat(
                (TableDto table) -> table != null && "tx2".equals(table.getTableId())),
            any(Privileges.class)))
        .thenReturn(false);
    String nativeMessage =
        message(
            put(mvc, bodyFromGet(getJson).toBuilder().sortOrder(null).build())
                .andExpect(status().isForbidden()));
    String ordinary =
        assertIrcError(
                IrcWrite.commit(mvc, DB, "tx2", setProperty(denied)), 403, "ForbiddenException")
            .message();
    assertEquals(nativeMessage, ordinary);
    org.mockito.Mockito.clearInvocations(authorizationHandler);
    assertEquals(
        ordinary,
        assertIrcError(
                IrcWrite.transaction(mvc, IrcWrite.change(DB, "tx2", setProperty(denied))),
                403,
                "ForbiddenException")
            .message());
    // The decision asked for is the ordinary commit's: the write privilege on that table.
    org.mockito.Mockito.verify(authorizationHandler)
        .checkAccessDecision(
            any(),
            org.mockito.ArgumentMatchers.argThat(
                (TableDto table) -> table != null && "tx2".equals(table.getTableId())),
            org.mockito.ArgumentMatchers.eq(Privileges.UPDATE_TABLE_METADATA));
    assertEquals(
        204,
        IrcWrite.transaction(mvc, IrcWrite.change(DB, "tx1", setProperty(allowed)))
            .getResponse()
            .getStatus());
    stubDecision(true);
    assertEquals(
        denied.metadataFileLocation(), IrcWrite.load(mvc, DB, "tx2").metadataFileLocation());
    assertEquals("v", IrcWrite.load(mvc, DB, "tx1").properties().get("user.k"));
  }

  /**
   * A transaction of several tables is refused for what it is (400), before any table is read and
   * before any authorization decision is asked for: the refusal is the same for a principal who may
   * write none of the tables, and for tables that do not exist, so it tells nothing about them.
   */
  @Test
  void aTransactionOfSeveralTablesIsRefusedBeforeAnyAuthorizationDecision() throws Exception {
    create("tx3");
    create("tx4");
    TableMetadata first = IrcWrite.load(mvc, DB, "tx3");
    TableMetadata second = IrcWrite.load(mvc, DB, "tx4");
    String allowedMessage =
        assertIrcError(
                IrcWrite.transaction(
                    mvc,
                    IrcWrite.change(DB, "tx3", setProperty(first)),
                    IrcWrite.change(DB, "tx4", setProperty(second))),
                400,
                "BadRequestException")
            .message();
    stubDecision(false);
    org.mockito.Mockito.clearInvocations(authorizationHandler);
    assertEquals(
        allowedMessage,
        assertIrcError(
                IrcWrite.transaction(
                    mvc,
                    IrcWrite.change(DB, "tx3", setProperty(first)),
                    IrcWrite.change(DB, "tx4", setProperty(second))),
                400,
                "BadRequestException")
            .message());
    assertIrcError(
        IrcWrite.transaction(
            mvc,
            IrcWrite.change(DB, "tx_missing_a", setProperty(first)),
            IrcWrite.change(DB, "tx_missing_b", setProperty(second))),
        400,
        "BadRequestException");
    org.mockito.Mockito.verify(authorizationHandler, org.mockito.Mockito.never())
        .checkAccessDecision(any(), any(TableDto.class), any(Privileges.class));
    org.mockito.Mockito.verify(authorizationHandler, org.mockito.Mockito.never())
        .checkAccessDecision(any(), any(DatabaseDto.class), any(Privileges.class));
    stubDecision(true);
    assertEquals(
        first.metadataFileLocation(), IrcWrite.load(mvc, DB, "tx3").metadataFileLocation());
    assertEquals(
        second.metadataFileLocation(), IrcWrite.load(mvc, DB, "tx4").metadataFileLocation());
  }

  @Test
  void aDeniedDropAndRenameAre403AsNatively() throws Exception {
    create("t3");
    stubDecision(false);
    String nativeDrop = message(delete(mvc, DB, "t3").andExpect(status().isForbidden()));
    assertEquals(
        nativeDrop,
        assertIrcError(IrcWrite.drop(mvc, DB, "t3"), 403, "ForbiddenException").message());
    String nativeRename =
        message(rename(mvc, DB, "t3", DB, "t4").andExpect(status().isForbidden()));
    assertEquals(
        nativeRename,
        assertIrcError(
                IrcWrite.rename(mvc, TableIdentifier.of(DB, "t3"), TableIdentifier.of(DB, "t4")),
                403,
                "ForbiddenException")
            .message());
    stubDecision(true);
    assertTrue(CommitPathHttp.status(get(mvc, DB, "t3")) == 200);
  }

  // ------------------------------------------------- authorization before the stored table is used

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

  private static final UpdateTableRequest EMPTY =
      new UpdateTableRequest(Collections.emptyList(), Collections.emptyList());

  /** A requirement the stored table fails, whose message would carry the stored UUID. */
  private static UpdateRequirement wrongUuid() {
    return new UpdateRequirement.AssertTableUUID(java.util.UUID.randomUUID().toString());
  }

  private static UpdateTableRequest request(
      List<UpdateRequirement> requirements, List<org.apache.iceberg.MetadataUpdate> updates) {
    return new UpdateTableRequest(requirements, updates);
  }

  private static List<org.apache.iceberg.MetadataUpdate> setUserProperty() {
    return Collections.singletonList(
        new MetadataUpdate.SetProperties(Collections.singletonMap("user.k", "v")));
  }

  /** The one table audit event of {@code db.table} sent since the last clear. */
  private TableAuditEvent onlyAuditEvent(String table) {
    ArgumentCaptor<TableAuditEvent> events = ArgumentCaptor.forClass(TableAuditEvent.class);
    Mockito.verify(tableAuditHandler, Mockito.atLeast(0)).audit(events.capture());
    List<TableAuditEvent> ofTable = new ArrayList<>();
    for (TableAuditEvent event : events.getAllValues()) {
      if (DB.equals(event.getDatabaseName()) && table.equals(event.getTableName())) {
        ofTable.add(event);
      }
    }
    assertEquals(1, ofTable.size(), String.valueOf(ofTable));
    return ofTable.get(0).toBuilder().eventTimestamp(null).build();
  }

  private void assertNoStoredIdIn(String body, TableMetadata stored) {
    assertTrue(!body.contains(stored.uuid()), body);
    assertTrue(!body.contains(stored.location()), body);
    assertTrue(!body.contains(stored.metadataFileLocation()), body);
  }

  /**
   * A commit with no updates answers the table's metadata, so it is a load of the table: a
   * principal who may write the table but not read it (every privilege but GET_TABLE_METADATA) is
   * refused as the native load refuses it, with no metadata, and audited as the native load's
   * refusal (a FAILED READ). Allowed, the answer is the stored metadata, as before.
   */
  @Test
  void anEmptyCommitIsALoadAndIsRefusedWithoutTheReadPrivilege() throws Exception {
    create("e1");
    TableMetadata stored = IrcWrite.load(mvc, DB, "e1");
    assertEquals(
        stored.metadataFileLocation(),
        IrcWrite.parsed(IrcWrite.commit(mvc, DB, "e1", EMPTY)).metadataFileLocation());

    denyOnly(Privileges.GET_TABLE_METADATA);
    Mockito.clearInvocations(tableAuditHandler);
    String nativeMessage = message(get(mvc, DB, "e1").andExpect(status().isForbidden()));
    TableAuditEvent nativeEvent = onlyAuditEvent("e1");
    Mockito.clearInvocations(tableAuditHandler, authorizationHandler);
    org.springframework.test.web.servlet.MvcResult result = IrcWrite.commit(mvc, DB, "e1", EMPTY);
    assertEquals(nativeMessage, assertIrcError(result, 403, "ForbiddenException").message());
    assertNoStoredIdIn(IrcHttp.body(result), stored);
    assertEquals(nativeEvent, onlyAuditEvent("e1"));
    assertEquals(OperationType.READ, nativeEvent.getOperationType());
    assertEquals(OperationStatus.FAILED, nativeEvent.getOperationStatus());
    Mockito.verify(authorizationHandler)
        .checkAccessDecision(
            any(),
            org.mockito.ArgumentMatchers.argThat(
                (TableDto table) -> table != null && "e1".equals(table.getTableId())),
            org.mockito.ArgumentMatchers.eq(Privileges.GET_TABLE_METADATA));

    // Write privilege alone does not make the empty commit a read either: denied it, the read
    // privilege is enough, as for a load.
    denyOnly(Privileges.UPDATE_TABLE_METADATA);
    assertEquals(
        stored.metadataFileLocation(),
        IrcWrite.parsed(IrcWrite.commit(mvc, DB, "e1", EMPTY)).metadataFileLocation());
  }

  /** With no privilege at all, the empty commit is the native load's refusal. */
  @Test
  void anEmptyCommitWithNoPrivilegeIs403AsTheNativeLoad() throws Exception {
    create("e2");
    TableMetadata stored = IrcWrite.load(mvc, DB, "e2");
    stubDecision(false);
    String nativeMessage = message(get(mvc, DB, "e2").andExpect(status().isForbidden()));
    org.springframework.test.web.servlet.MvcResult result = IrcWrite.commit(mvc, DB, "e2", EMPTY);
    assertEquals(nativeMessage, assertIrcError(result, 403, "ForbiddenException").message());
    assertNoStoredIdIn(IrcHttp.body(result), stored);
  }

  /**
   * A commit whose requirement the stored table fails is refused 403 for a principal who may not
   * write it (every privilege but UPDATE_TABLE_METADATA), with the native PUT's refusal and nothing
   * of the stored table, and audited as the commit's FAILED UPDATE. Allowed, it is the 409 it was.
   */
  @Test
  void aFailingRequirementOfADeniedCommitIs403AndTellsNothingStored() throws Exception {
    create("r1");
    TableMetadata stored = IrcWrite.load(mvc, DB, "r1");
    String getJson = json(get(mvc, DB, "r1"));
    UpdateTableRequest commit = request(Collections.singletonList(wrongUuid()), setUserProperty());
    String allowed =
        assertIrcError(IrcWrite.commit(mvc, DB, "r1", commit), 409, "CommitFailedException")
            .message();
    assertTrue(allowed.contains(stored.uuid()), allowed);

    denyOnly(Privileges.UPDATE_TABLE_METADATA);
    String nativeMessage =
        message(
            put(mvc, bodyFromGet(getJson).toBuilder().sortOrder(null).build())
                .andExpect(status().isForbidden()));
    Mockito.clearInvocations(tableAuditHandler);
    org.springframework.test.web.servlet.MvcResult result = IrcWrite.commit(mvc, DB, "r1", commit);
    assertEquals(nativeMessage, assertIrcError(result, 403, "ForbiddenException").message());
    assertNoStoredIdIn(IrcHttp.body(result), stored);
    TableAuditEvent event = onlyAuditEvent("r1");
    assertEquals(OperationType.UPDATE, event.getOperationType());
    assertEquals(OperationStatus.FAILED, event.getOperationStatus());

    // The same requirement on a commit with no updates is a read: refused without the read
    // privilege, before the requirement is checked.
    UpdateTableRequest read =
        request(Collections.singletonList(wrongUuid()), Collections.emptyList());
    denyOnly(Privileges.GET_TABLE_METADATA);
    result = IrcWrite.commit(mvc, DB, "r1", read);
    assertIrcError(result, 403, "ForbiddenException");
    assertNoStoredIdIn(IrcHttp.body(result), stored);
    stubDecision(true);
    assertEquals(
        stored.metadataFileLocation(), IrcWrite.load(mvc, DB, "r1").metadataFileLocation());
  }

  /**
   * An update that cannot be applied to the stored table (a schema id it does not have) is a 400
   * for a principal who may write it, and the native refusal for one who may not: the update is not
   * applied before the decision.
   */
  @Test
  void anUpdateThatCannotApplyIs403ForADeniedPrincipal() throws Exception {
    create("u1");
    UpdateTableRequest commit =
        request(
            Collections.emptyList(),
            Collections.singletonList(new MetadataUpdate.SetCurrentSchema(99)));
    assertIrcError(IrcWrite.commit(mvc, DB, "u1", commit), 400, "BadRequestException");
    denyOnly(Privileges.UPDATE_TABLE_METADATA);
    assertIrcError(IrcWrite.commit(mvc, DB, "u1", commit), 403, "ForbiddenException");
  }

  /**
   * A snapshots commit refused for the write privilege is audited as the native snapshots PUT's
   * kind (COMMIT), and a replace as REPLACE_COMMIT, as an allowed one of each is.
   */
  @Test
  void aDeniedCommitIsAuditedAsItsKind() throws Exception {
    create("k1");
    TableMetadata base = IrcWrite.load(mvc, DB, "k1");
    UpdateTableRequest append =
        IrcWrite.simple(
            base,
            IrcWrite.withMain(base, IrcWrite.append(base, "/tmp/" + DB + "_k1_a.orc")).changes());
    denyOnly(Privileges.UPDATE_TABLE_METADATA);
    Mockito.clearInvocations(tableAuditHandler);
    assertIrcError(IrcWrite.commit(mvc, DB, "k1", append), 403, "ForbiddenException");
    assertEquals(OperationType.COMMIT, onlyAuditEvent("k1").getOperationType());
    Mockito.clearInvocations(tableAuditHandler);
    assertIrcError(
        IrcWrite.commit(
            mvc,
            DB,
            "k1",
            IrcWrite.replace(
                base,
                IrcWrite.replacement(
                    base, base.schema(), base.spec(), Collections.singletonMap("user.r", "1")),
                Collections.emptyList())),
        403,
        "ForbiddenException");
    assertEquals(OperationType.REPLACE_COMMIT, onlyAuditEvent("k1").getOperationType());
  }

  /**
   * The one-table transaction is authorized as the ordinary commit: before its requirements are
   * checked, with the read privilege for a change with no updates.
   */
  @Test
  void aTransactionChecksItsRequirementsOnlyForAnAuthorizedPrincipal() throws Exception {
    create("x1");
    TableMetadata stored = IrcWrite.load(mvc, DB, "x1");
    UpdateTableRequest write =
        IrcWrite.change(
            DB, "x1", request(Collections.singletonList(wrongUuid()), setUserProperty()));
    UpdateTableRequest read =
        IrcWrite.change(
            DB, "x1", request(Collections.singletonList(wrongUuid()), Collections.emptyList()));
    assertIrcError(IrcWrite.transaction(mvc, write), 409, "CommitFailedException");
    assertIrcError(IrcWrite.transaction(mvc, read), 409, "CommitFailedException");

    denyOnly(Privileges.UPDATE_TABLE_METADATA);
    org.springframework.test.web.servlet.MvcResult result = IrcWrite.transaction(mvc, write);
    assertIrcError(result, 403, "ForbiddenException");
    assertNoStoredIdIn(IrcHttp.body(result), stored);
    denyOnly(Privileges.GET_TABLE_METADATA);
    result = IrcWrite.transaction(mvc, read);
    assertIrcError(result, 403, "ForbiddenException");
    assertNoStoredIdIn(IrcHttp.body(result), stored);
  }

  /**
   * A completion of a staged create names a table that does not exist ({@code assert-create}); on
   * one that does, the answer is that assertion's own failure, which tells only that the table
   * exists (as the native create's 409 does, to anyone), even when the request lists another
   * requirement first: that one is not checked against the stored table.
   */
  @Test
  void aCompletionOnAnExistingTableTellsOnlyThatItExists() throws Exception {
    create("c1");
    TableMetadata stored = IrcWrite.load(mvc, DB, "c1");
    UpdateTableRequest completion =
        request(
            java.util.Arrays.asList(wrongUuid(), new UpdateRequirement.AssertTableDoesNotExist()),
            setUserProperty());
    for (boolean allowed : new boolean[] {true, false}) {
      stubDecision(allowed);
      org.springframework.test.web.servlet.MvcResult result =
          IrcWrite.commit(mvc, DB, "c1", completion);
      assertIrcError(result, 409, "CommitFailedException");
      assertNoStoredIdIn(IrcHttp.body(result), stored);
    }
  }
}
