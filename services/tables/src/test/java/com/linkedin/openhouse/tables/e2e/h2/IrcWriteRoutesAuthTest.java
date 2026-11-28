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
  @Autowired com.linkedin.openhouse.tables.services.TableCommitService tableCommitService;
  @Autowired com.linkedin.openhouse.tables.api.irc.IcebergRestTableFileIo tableFileIo;

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

  // ------------------------------------------------- the answer to a rename's commit conflict

  /**
   * Makes the native rename fail, once, with the failure its House Tables write fails with when
   * House Tables refuses it: the commit failure the table operations throw, caused by the
   * repository's concurrent-update refusal. The failure is thrown from the first of the native
   * rename's three decisions (CREATE_TABLE on the database), after it has looked for the source and
   * the target and before its write, after running {@code meanwhile}; nothing of the rename's own
   * write path runs. So these tests pin how the Iceberg REST rename answers that failure and what
   * it looks up to choose the answer, not that a real House Tables conflict reaches the route as
   * this failure (by reading, the table operations rethrow it as this failure after Iceberg's
   * commit retries). Every other decision is allowed.
   */
  private void theNativeRenameFailsWithACommitConflict(Runnable meanwhile) {
    java.util.concurrent.atomic.AtomicBoolean refused =
        new java.util.concurrent.atomic.AtomicBoolean();
    when(authorizationHandler.checkAccessDecision(
            any(), any(DatabaseDto.class), any(Privileges.class)))
        .thenAnswer(
            call -> {
              if (call.getArgument(2) == Privileges.CREATE_TABLE
                  && refused.compareAndSet(false, true)) {
                meanwhile.run();
                throw new org.apache.iceberg.exceptions.CommitFailedException(
                    new com.linkedin.openhouse.internal.catalog.repository.exception
                        .HouseTableConcurrentUpdateException("injected conflict", null));
              }
              return true;
            });
  }

  /**
   * A rename whose commit conflicts while the target's key is free: the table was changed
   * concurrently, a conflict the client may retry, answered 409 {@code CommitFailedException} (the
   * native route answers the unmapped commit failure 500). Nothing is renamed. Both Iceberg client
   * lines read any 409 on the rename route as {@code AlreadyExistsException}.
   */
  @Test
  void aRenameCommitConflictOnAFreeTargetIs409ChangedConcurrently() throws Exception {
    create("rn1");
    theNativeRenameFailsWithACommitConflict(() -> {});
    org.apache.iceberg.rest.responses.ErrorResponse error =
        assertIrcError(
            IrcWrite.rename(mvc, TableIdentifier.of(DB, "rn1"), TableIdentifier.of(DB, "rn2")),
            409,
            "CommitFailedException");
    assertTrue(error.message().contains("the table was changed concurrently"), error.message());
    stubDecision(true);
    assertEquals(200, CommitPathHttp.status(get(mvc, DB, "rn1")));
    assertEquals(404, CommitPathHttp.status(get(mvc, DB, "rn2")));
    org.junit.jupiter.api.Assertions.assertThrows(
        org.apache.iceberg.exceptions.AlreadyExistsException.class,
        () -> org.apache.iceberg.rest.ErrorHandlers.tableErrorHandler().accept(error));
  }

  /**
   * A rename whose commit conflicts after a table of the target's name was created since the native
   * rename looked for one: answered 409 {@code AlreadyExistsException} naming a table. The source
   * is not renamed and the target is the table created meanwhile.
   */
  @Test
  void aRenameCommitConflictOnATargetCreatedMeanwhileIs409AlreadyExists() throws Exception {
    create("rn3");
    created.add("rn4");
    theNativeRenameFailsWithACommitConflict(
        () -> {
          java.util.concurrent.atomic.AtomicReference<Exception> failed =
              new java.util.concurrent.atomic.AtomicReference<>();
          // Another client's create, on a thread of its own as another request would be.
          Thread creator =
              new Thread(
                  () -> {
                    try {
                      post(mvc, newTable(DB, "rn4").toBuilder().policies(null).build())
                          .andExpect(status().isCreated());
                    } catch (Exception e) {
                      failed.set(e);
                    }
                  });
          creator.start();
          try {
            creator.join(30_000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
          if (creator.isAlive()) {
            throw new IllegalStateException("the create of the target did not finish in 30 s");
          }
          if (failed.get() != null) {
            throw new IllegalStateException(failed.get());
          }
          // The House Tables stand-in serves this request's reads from the request's own
          // persistence context; the real House Tables service is read afresh each time.
          entityManager.clear();
        });
    String message =
        assertIrcError(
                IrcWrite.rename(mvc, TableIdentifier.of(DB, "rn3"), TableIdentifier.of(DB, "rn4")),
                409,
                "AlreadyExistsException")
            .message();
    assertTrue(message.contains("Table already exists"), message);
    stubDecision(true);
    assertEquals(200, CommitPathHttp.status(get(mvc, DB, "rn3")));
    assertEquals(200, CommitPathHttp.status(get(mvc, DB, "rn4")));
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
    assertEquals(nativeEvent.toJson(), onlyAuditEvent("e1").toJson());
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

  /**
   * That answer needs only that the table exists: the table's metadata file is not read for it. So
   * the answer is the same when the file cannot be read (here it is moved away).
   */
  @Test
  void aCompletionOnAnExistingTableReadsNoMetadataFile() throws Exception {
    create("c2");
    TableMetadata stored = IrcWrite.load(mvc, DB, "c2");
    java.nio.file.Path current = file(stored.metadataFileLocation());
    java.nio.file.Path aside = current.resolveSibling(current.getFileName() + ".aside");
    UpdateTableRequest completion =
        request(
            Collections.singletonList(new UpdateRequirement.AssertTableDoesNotExist()),
            setUserProperty());
    java.nio.file.Files.move(current, aside);
    try {
      org.springframework.test.web.servlet.MvcResult result =
          IrcWrite.commit(mvc, DB, "c2", completion);
      String message = assertIrcError(result, 409, "CommitFailedException").message();
      assertTrue(message.contains("table already exists"), message);
    } finally {
      java.nio.file.Files.move(aside, current);
    }
  }

  // ------------------------------------------------- a commit that changes nothing is a load

  /**
   * A table with a lock, LEGACY when {@code reason} is null, made as the native lock route does.
   */
  private void lockTable(String table, String reason) throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    tableUrl(DB, table) + "/lock")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(
                    reason == null
                        ? "{\"locked\":true,\"message\":\"m\"}"
                        : "{\"locked\":true,\"message\":\"m\",\"reason\":\"" + reason + "\"}"))
        .andExpect(status().isCreated());
  }

  private void unlockTable(String table) throws Exception {
    stubDecision(true);
    mvc.perform(
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
            tableUrl(DB, table) + "/lock/SYSTEM_ONLY"));
    mvc.perform(
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
            tableUrl(DB, table) + "/lock"));
  }

  /**
   * Commits whose updates Iceberg's metadata builder records as no change: they write nothing and
   * answer the stored table, so they are loads, whatever the list of updates holds. The empty list
   * is one of them.
   */
  private static java.util.Map<String, UpdateTableRequest> noChangeCommits(TableMetadata base) {
    java.util.Map<String, UpdateTableRequest> commits = new java.util.LinkedHashMap<>();
    commits.put("no updates", EMPTY);
    commits.put(
        "set-properties {}",
        request(
            Collections.emptyList(),
            Collections.singletonList(new MetadataUpdate.SetProperties(Collections.emptyMap()))));
    commits.put(
        "remove-properties []",
        request(
            Collections.emptyList(),
            Collections.singletonList(
                new MetadataUpdate.RemoveProperties(Collections.emptySet()))));
    commits.put(
        "assign-uuid (the stored one)",
        request(
            Collections.emptyList(),
            Collections.singletonList(new MetadataUpdate.AssignUUID(base.uuid()))));
    commits.put(
        "upgrade-format-version (the stored one)",
        request(
            Collections.emptyList(),
            Collections.singletonList(
                new MetadataUpdate.UpgradeFormatVersion(base.formatVersion()))));
    commits.put(
        "set-current-schema (the current one)",
        request(
            Collections.emptyList(),
            Collections.singletonList(
                new MetadataUpdate.SetCurrentSchema(base.currentSchemaId()))));
    commits.put(
        "set-location (the stored one)",
        request(
            Collections.emptyList(),
            Collections.singletonList(new MetadataUpdate.SetLocation(base.location()))));
    return commits;
  }

  /**
   * A principal who may write the table but not read it (every privilege but GET_TABLE_METADATA)
   * gets the native load's refusal, and no metadata, for every commit that changes nothing; a
   * refusal is audited as the load's (a FAILED READ). Allowed, each answers the stored table and
   * leaves the READ event the IRC load leaves.
   */
  @Test
  void aCommitThatChangesNothingIsALoad() throws Exception {
    create("n1");
    TableMetadata stored = IrcWrite.load(mvc, DB, "n1");
    Mockito.clearInvocations(tableAuditHandler);
    IrcWrite.load(mvc, DB, "n1");
    String loadEvent = onlyAuditEvent("n1").toJson();
    for (java.util.Map.Entry<String, UpdateTableRequest> commit :
        noChangeCommits(stored).entrySet()) {
      stubDecision(true);
      Mockito.clearInvocations(tableAuditHandler);
      assertEquals(
          stored.metadataFileLocation(),
          IrcWrite.parsed(IrcWrite.commit(mvc, DB, "n1", commit.getValue())).metadataFileLocation(),
          commit.getKey());
      assertEquals(loadEvent, onlyAuditEvent("n1").toJson(), commit.getKey());

      denyOnly(Privileges.GET_TABLE_METADATA);
      String nativeMessage = message(get(mvc, DB, "n1").andExpect(status().isForbidden()));
      Mockito.clearInvocations(tableAuditHandler);
      org.springframework.test.web.servlet.MvcResult result =
          IrcWrite.commit(mvc, DB, "n1", commit.getValue());
      assertEquals(
          nativeMessage,
          assertIrcError(result, 403, "ForbiddenException").message(),
          commit.getKey());
      assertNoStoredIdIn(IrcHttp.body(result), stored);
      TableAuditEvent event = onlyAuditEvent("n1");
      // With no updates the request is a load; with updates it is decided, read privilege
      // included, before its updates are applied, as the commit it asks for.
      assertEquals(
          commit.getValue().updates().isEmpty() ? OperationType.READ : OperationType.UPDATE,
          event.getOperationType(),
          commit.getKey());
      assertEquals(OperationStatus.FAILED, event.getOperationStatus(), commit.getKey());

      // On the one-table transaction the same commit is a load too.
      result = IrcWrite.transaction(mvc, IrcWrite.change(DB, "n1", commit.getValue()));
      assertIrcError(result, 403, "ForbiddenException");
      assertNoStoredIdIn(IrcHttp.body(result), stored);
    }
    stubDecision(true);
    assertEquals(
        stored.metadataFileLocation(), IrcWrite.load(mvc, DB, "n1").metadataFileLocation());
  }

  /**
   * On a table with a legacy lock, a commit with no updates needs what the load needs there,
   * LOCK_ADMIN: denied that one privilege, the caller gets the native load's refusal and no
   * metadata. A commit with updates, whether they change the table or not, is refused as the native
   * PUT refuses a locked table, before anything else and for every caller, also one who may not
   * write.
   */
  @Test
  void onALegacyLockedTableACommitThatChangesNothingNeedsLockAdmin() throws Exception {
    create("l1");
    TableMetadata stored = IrcWrite.load(mvc, DB, "l1");
    lockTable("l1", null);
    try {
      denyOnly(Privileges.LOCK_ADMIN);
      String nativeMessage = message(get(mvc, DB, "l1").andExpect(status().isForbidden()));
      for (java.util.Map.Entry<String, UpdateTableRequest> commit :
          noChangeCommits(stored).entrySet()) {
        org.springframework.test.web.servlet.MvcResult result =
            IrcWrite.commit(mvc, DB, "l1", commit.getValue());
        if (commit.getValue().updates().isEmpty()) {
          assertEquals(
              nativeMessage,
              assertIrcError(result, 403, "ForbiddenException").message(),
              commit.getKey());
        } else {
          String locked = assertIrcError(result, 400, "BadRequestException").message();
          assertTrue(locked.contains("locked state"), commit.getKey() + ": " + locked);
        }
        assertNoStoredIdIn(IrcHttp.body(result), stored);
      }
      UpdateTableRequest change = request(Collections.emptyList(), setUserProperty());
      String locked =
          assertIrcError(IrcWrite.commit(mvc, DB, "l1", change), 400, "BadRequestException")
              .message();
      assertTrue(locked.contains("locked state"), locked);
      denyOnly(Privileges.UPDATE_TABLE_METADATA);
      assertEquals(
          locked,
          assertIrcError(IrcWrite.commit(mvc, DB, "l1", change), 400, "BadRequestException")
              .message());
    } finally {
      unlockTable("l1");
    }
  }

  /**
   * On a table with a SYSTEM_ONLY lock, a commit that changes nothing is refused as the load
   * refuses a caller the system-only decision denies (423), with no metadata.
   */
  @Test
  void onASystemOnlyLockedTableACommitThatChangesNothingIsRefusedAsTheLoad() throws Exception {
    create("s1");
    TableMetadata stored = IrcWrite.load(mvc, DB, "s1");
    lockTable("s1", "SYSTEM_ONLY");
    try {
      // Every privilege allowed; the system-only decision is the default handler's, which denies a
      // request that does not declare itself a system one.
      org.apache.iceberg.rest.responses.ErrorResponse load =
          assertIrcError(
              IrcHttp.get(mvc, IrcWrite.table(DB, "s1")),
              423,
              "SystemOnlyLockAccessDeniedException");
      for (java.util.Map.Entry<String, UpdateTableRequest> commit :
          noChangeCommits(stored).entrySet()) {
        org.springframework.test.web.servlet.MvcResult result =
            IrcWrite.commit(mvc, DB, "s1", commit.getValue());
        assertEquals(
            load.message(),
            assertIrcError(result, 423, "SystemOnlyLockAccessDeniedException").message(),
            commit.getKey());
        assertNoStoredIdIn(IrcHttp.body(result), stored);
      }
    } finally {
      unlockTable("s1");
    }
  }

  /**
   * Updates Iceberg's builder records as a change though the stored values would not change (a
   * property set to its current value, a property removed that is not there) are writes: they go
   * through the commit core. So a legacy-locked table refuses them as the native PUT does, and a
   * principal who may write but not read is refused, as for every commit (the commit answers the
   * table's whole metadata).
   */
  @Test
  void updatesIcebergRecordsAsAChangeAreWrites() throws Exception {
    create("w1");
    IrcWrite.parsed(
        IrcWrite.commit(mvc, DB, "w1", request(Collections.emptyList(), setUserProperty())));
    java.util.List<UpdateTableRequest> writes =
        java.util.Arrays.asList(
            request(Collections.emptyList(), setUserProperty()),
            request(
                Collections.emptyList(),
                Collections.singletonList(
                    new MetadataUpdate.RemoveProperties(Collections.singleton("user.absent")))));
    for (UpdateTableRequest write : writes) {
      assertEquals(200, IrcWrite.commit(mvc, DB, "w1", write).getResponse().getStatus());
    }
    denyOnly(Privileges.GET_TABLE_METADATA);
    for (UpdateTableRequest write : writes) {
      assertIrcError(IrcWrite.commit(mvc, DB, "w1", write), 403, "ForbiddenException");
    }
    stubDecision(true);
    lockTable("w1", null);
    try {
      for (UpdateTableRequest write : writes) {
        org.springframework.test.web.servlet.MvcResult result =
            IrcWrite.commit(mvc, DB, "w1", write);
        assertIrcError(result, 400, "BadRequestException");
        assertNoStoredIdIn(IrcHttp.body(result), IrcWrite.load(mvc, DB, "w1"));
      }
    } finally {
      unlockTable("w1");
    }
  }

  /**
   * On a locked table, a commit with updates is refused as the native PUT refuses it before any of
   * its requirements is checked against the stored table, whether its updates change the table or
   * not: a legacy lock is refused for every caller (here one denied only LOCK_ADMIN, and one denied
   * only the write privilege), a SYSTEM_ONLY lock for a caller the system-only decision denies. The
   * answer is the native refusal, with none of the stored ids a failing requirement would name.
   */
  @Test
  void onALockedTableAFailingRequirementIsTheNativeRefusalAndTellsNothingStored() throws Exception {
    java.util.Map<String, UpdateTableRequest> commits = new java.util.LinkedHashMap<>();
    commits.put("a change", request(Collections.singletonList(wrongUuid()), setUserProperty()));
    commits.put(
        "no change",
        request(
            Collections.singletonList(wrongUuid()),
            Collections.singletonList(new MetadataUpdate.SetProperties(Collections.emptyMap()))));

    create("lk1");
    TableMetadata legacy = IrcWrite.load(mvc, DB, "lk1");
    lockTable("lk1", null);
    try {
      String getJson = json(get(mvc, DB, "lk1"));
      for (Privileges denied :
          new Privileges[] {Privileges.LOCK_ADMIN, Privileges.UPDATE_TABLE_METADATA}) {
        denyOnly(denied);
        String nativeMessage =
            message(put(mvc, bodyFromGet(getJson)).andExpect(status().isBadRequest()));
        assertTrue(nativeMessage.contains("locked state and cannot be updated"), nativeMessage);
        for (java.util.Map.Entry<String, UpdateTableRequest> commit : commits.entrySet()) {
          org.springframework.test.web.servlet.MvcResult result =
              IrcWrite.commit(mvc, DB, "lk1", commit.getValue());
          assertEquals(
              nativeMessage,
              assertIrcError(result, 400, "BadRequestException").message(),
              denied + ", " + commit.getKey());
          assertNoStoredIdIn(IrcHttp.body(result), legacy);
        }
      }
    } finally {
      unlockTable("lk1");
    }

    create("lk2");
    TableMetadata systemOnly = IrcWrite.load(mvc, DB, "lk2");
    String getJson = json(get(mvc, DB, "lk2"));
    lockTable("lk2", "SYSTEM_ONLY");
    try {
      // Every privilege allowed; the system-only decision is the default handler's, which denies a
      // request that does not declare itself a system one.
      String nativeMessage = message(put(mvc, bodyFromGet(getJson)).andExpect(status().isLocked()));
      for (java.util.Map.Entry<String, UpdateTableRequest> commit : commits.entrySet()) {
        org.springframework.test.web.servlet.MvcResult result =
            IrcWrite.commit(mvc, DB, "lk2", commit.getValue());
        assertEquals(
            nativeMessage,
            assertIrcError(result, 423, "SystemOnlyLockAccessDeniedException").message(),
            commit.getKey());
        assertNoStoredIdIn(IrcHttp.body(result), systemOnly);
      }
    } finally {
      unlockTable("lk2");
    }
  }

  // ------------------------------------------------- what a commit answers

  /**
   * A commit answers the table's whole Iceberg metadata, so a principal who may write the table but
   * not read it (every privilege but GET_TABLE_METADATA) is refused a commit that changes the
   * table, on the commit route and on the one-table transaction, with the native load's refusal,
   * audited as the commit's refusal, before anything is checked against the stored table, and
   * nothing is written. Allowed to read too, the same commit succeeds.
   */
  @Test
  void aWriterThatMayNotReadIsRefusedACommitAndNothingIsWritten() throws Exception {
    create("rw1");
    TableMetadata stored = IrcWrite.load(mvc, DB, "rw1");
    UpdateTableRequest change = request(Collections.emptyList(), setUserProperty());
    UpdateTableRequest failing = request(Collections.singletonList(wrongUuid()), setUserProperty());
    denyOnly(Privileges.GET_TABLE_METADATA);
    String nativeMessage = message(get(mvc, DB, "rw1").andExpect(status().isForbidden()));
    for (UpdateTableRequest commit : java.util.Arrays.asList(change, failing)) {
      Mockito.clearInvocations(tableAuditHandler);
      org.springframework.test.web.servlet.MvcResult result =
          IrcWrite.commit(mvc, DB, "rw1", commit);
      assertEquals(nativeMessage, assertIrcError(result, 403, "ForbiddenException").message());
      assertNoStoredIdIn(IrcHttp.body(result), stored);
      TableAuditEvent event = onlyAuditEvent("rw1");
      assertEquals(OperationType.UPDATE, event.getOperationType());
      assertEquals(OperationStatus.FAILED, event.getOperationStatus());
      Mockito.clearInvocations(tableAuditHandler);
      result = IrcWrite.transaction(mvc, IrcWrite.change(DB, "rw1", commit));
      assertEquals(nativeMessage, assertIrcError(result, 403, "ForbiddenException").message());
      assertNoStoredIdIn(IrcHttp.body(result), stored);
      // The one-table transaction's refusal is audited exactly as the commit's: one FAILED UPDATE.
      assertEquals(event.toJson(), onlyAuditEvent("rw1").toJson());
    }
    stubDecision(true);
    TableMetadata after = IrcWrite.load(mvc, DB, "rw1");
    assertEquals(stored.metadataFileLocation(), after.metadataFileLocation());
    assertTrue(!after.properties().containsKey("user.k"), String.valueOf(after.properties()));
    denyOnly(Privileges.UPDATE_TABLE_METADATA);
    assertIrcError(IrcWrite.commit(mvc, DB, "rw1", change), 403, "ForbiddenException");
    stubDecision(true);
    assertEquals(
        "v", IrcWrite.parsed(IrcWrite.commit(mvc, DB, "rw1", change)).properties().get("user.k"));
  }

  /** The answer the commit routes gave before: the table as stored when the answer is written. */
  private byte[] storedAnswer(String table) {
    com.linkedin.openhouse.tables.model.TableDtoWithMetadata stored =
        tableCommitService.loadBase(TableIdentifier.of(DB, table));
    return com.linkedin.openhouse.tables.api.irc.IcebergRestJson.loadTable(
            new com.linkedin.openhouse.tables.api.irc.IcebergRestLoadedTable(
                stored.getTableDto().getTableLocation(),
                stored.getMetadata(),
                tableFileIo.config(stored.getTable()),
                false))
        .getBody();
  }

  /**
   * An allowed write answers exactly what it answered before, the table as stored after it, byte
   * for byte: a commit that changes a property, a snapshot commit, a create and the commit that
   * completes a staged create.
   */
  @Test
  void anAllowedWritesAnswerIsTheStoredTableByteForByte() throws Exception {
    create("b1");
    TableMetadata base = IrcWrite.load(mvc, DB, "b1");
    org.springframework.test.web.servlet.MvcResult result =
        IrcWrite.commit(mvc, DB, "b1", request(Collections.emptyList(), setUserProperty()));
    assertEquals(200, result.getResponse().getStatus());
    org.junit.jupiter.api.Assertions.assertArrayEquals(
        storedAnswer("b1"), result.getResponse().getContentAsByteArray());
    base = IrcWrite.load(mvc, DB, "b1");
    result =
        IrcWrite.commit(
            mvc,
            DB,
            "b1",
            IrcWrite.simple(
                base,
                IrcWrite.withMain(base, IrcWrite.append(base, "/tmp/" + DB + "_b1_a.orc"))
                    .changes()));
    assertEquals(200, result.getResponse().getStatus());
    org.junit.jupiter.api.Assertions.assertArrayEquals(
        storedAnswer("b1"), result.getResponse().getContentAsByteArray());

    created.add("b2");
    result =
        IrcWrite.create(
            mvc,
            DB,
            CreateTableRequest.builder()
                .withName("b2")
                .withSchema(IcebergSchemaHelper.getSchemaFromSchemaJson(HEALTH_SCHEMA_LITERAL))
                .build());
    assertEquals(200, result.getResponse().getStatus());
    org.junit.jupiter.api.Assertions.assertArrayEquals(
        storedAnswer("b2"), result.getResponse().getContentAsByteArray());

    created.add("b3");
    TableMetadata staged =
        IrcWrite.parsed(
            IrcWrite.create(
                mvc,
                DB,
                CreateTableRequest.builder()
                    .withName("b3")
                    .withSchema(IcebergSchemaHelper.getSchemaFromSchemaJson(HEALTH_SCHEMA_LITERAL))
                    .stageCreate()
                    .build()));
    result = IrcWrite.commit(mvc, DB, "b3", IrcWrite.createCommit(staged, Collections.emptyList()));
    assertEquals(200, result.getResponse().getStatus());
    org.junit.jupiter.api.Assertions.assertArrayEquals(
        storedAnswer("b3"), result.getResponse().getContentAsByteArray());
  }

  /** What another writer does to the table between a commit and the commit's answer. */
  private interface Race {
    void run() throws Exception;
  }

  /**
   * Commits {@code user.k=v} on {@code table}; between that commit and its answer, {@code race}
   * runs on a thread of its own, as another request would.
   */
  private org.springframework.test.web.servlet.MvcResult commitWithARace(String table, Race race)
      throws Exception {
    java.util.concurrent.atomic.AtomicBoolean raced =
        new java.util.concurrent.atomic.AtomicBoolean();
    Mockito.doAnswer(
            call -> {
              TableAuditEvent event = call.getArgument(0);
              if (table.equals(event.getTableName())
                  && event.getOperationStatus() == OperationStatus.SUCCESS
                  && event.getOperationType() == OperationType.UPDATE
                  && raced.compareAndSet(false, true)) {
                java.util.concurrent.atomic.AtomicReference<Exception> failed =
                    new java.util.concurrent.atomic.AtomicReference<>();
                Thread writer =
                    new Thread(
                        () -> {
                          try {
                            race.run();
                          } catch (Exception e) {
                            failed.set(e);
                          }
                        });
                writer.start();
                writer.join();
                if (failed.get() != null) {
                  throw failed.get();
                }
              }
              return null;
            })
        .when(tableAuditHandler)
        .audit(any(TableAuditEvent.class));
    try {
      org.springframework.test.web.servlet.MvcResult result =
          IrcWrite.commit(mvc, DB, table, request(Collections.emptyList(), setUserProperty()));
      assertTrue(raced.get());
      return result;
    } finally {
      Mockito.doNothing().when(tableAuditHandler).audit(any(TableAuditEvent.class));
    }
  }

  /** Another writer's commit of {@code user.later=1} on {@code table}. */
  private void commitLater(String table) throws Exception {
    TableMetadata now = IrcWrite.load(mvc, DB, table);
    assertEquals(
        200,
        IrcWrite.commit(
                mvc,
                DB,
                table,
                IrcWrite.simple(
                    now,
                    Collections.singletonList(
                        new MetadataUpdate.SetProperties(
                            Collections.singletonMap("user.later", "1")))))
            .getResponse()
            .getStatus());
  }

  /** The local file at {@code location}, spelled with or without the storage's scheme. */
  private static java.nio.file.Path file(String location) {
    return java.nio.file.Paths.get(java.net.URI.create(location).getPath());
  }

  /**
   * A write that lands on the table after a commit, and before the commit's answer is written, is
   * not answered as the commit's result: the answer is the version the commit stored, read from its
   * own metadata file, where it was the table as stored at the time of the answer.
   */
  @Test
  void aCommitAnswersTheVersionItStoredNotALaterOne() throws Exception {
    create("race1");
    TableMetadata base = IrcWrite.load(mvc, DB, "race1");
    TableMetadata answered = IrcWrite.parsed(commitWithARace("race1", () -> commitLater("race1")));
    TableMetadata now = IrcWrite.load(mvc, DB, "race1");
    assertEquals("1", now.properties().get("user.later"));
    assertEquals("v", answered.properties().get("user.k"));
    assertTrue(
        !answered.properties().containsKey("user.later"), String.valueOf(answered.properties()));
    // The version answered is the one right after the commit's base (the metadata log names the
    // base file, spelled with or without the storage's scheme).
    assertEquals(
        file(base.metadataFileLocation()),
        file(answered.previousFiles().get(answered.previousFiles().size() - 1).file()));
  }

  /**
   * When the version the commit stored can no longer be read (a later commit's metadata cleanup
   * deleted it) but the table is still the same table, the commit that landed is answered with the
   * table as stored now, which holds it, not as a failure.
   */
  @Test
  void aCommitWhoseStoredVersionIsGoneIsAnsweredWithTheSameTableAsStoredNow() throws Exception {
    create("race2");
    org.springframework.test.web.servlet.MvcResult result =
        commitWithARace(
            "race2",
            () -> {
              commitLater("race2");
              // The commit's own file, the later version's predecessor, deleted as a metadata
              // cleanup deletes it.
              TableMetadata later = IrcWrite.load(mvc, DB, "race2");
              java.nio.file.Files.delete(
                  file(later.previousFiles().get(later.previousFiles().size() - 1).file()));
            });
    assertEquals(200, result.getResponse().getStatus(), IrcHttp.body(result));
    TableMetadata answered = IrcWrite.parsed(result);
    TableMetadata now = IrcWrite.load(mvc, DB, "race2");
    assertEquals(now.metadataFileLocation(), answered.metadataFileLocation());
    assertEquals("v", answered.properties().get("user.k"));
    assertEquals("1", answered.properties().get("user.later"));
  }

  /**
   * When the table was dropped and another created under its name between the commit and its
   * answer, nothing the commit was decided on can be answered: the answer is the spec's 500 for an
   * unknown commit state, which a client reads as "the commit may have landed", not a 200 with the
   * other table and not an unexplained server failure.
   */
  @Test
  void aCommitWhoseTableWasReplacedIsAnsweredCommitStateUnknown() throws Exception {
    create("race3");
    TableMetadata first = IrcWrite.load(mvc, DB, "race3");
    org.springframework.test.web.servlet.MvcResult result =
        commitWithARace(
            "race3",
            () -> {
              delete(mvc, DB, "race3");
              post(mvc, newTable(DB, "race3").toBuilder().policies(null).build())
                  .andExpect(status().isCreated());
            });
    String message = assertIrcError(result, 500, "CommitStateUnknownException").message();
    assertTrue(message.startsWith("The write to " + DB + ".race3 landed"), message);
    assertNoStoredIdIn(IrcHttp.body(result), IrcWrite.load(mvc, DB, "race3"));
    assertTrue(!IrcWrite.load(mvc, DB, "race3").uuid().equals(first.uuid()));
  }

  /** A replica table's write decision is SYSTEM_ADMIN, as the native PUT's, decided first. */
  @Test
  void aReplicaTablesCommitIsDecidedOnSystemAdminFirst() throws Exception {
    String uuid = java.util.UUID.randomUUID().toString();
    java.util.Map<String, String> props =
        new java.util.HashMap<>(newTable(DB, "rp").getTableProperties());
    props.put("openhouse.tableUUID", uuid);
    props.put("openhouse.databaseId", DB);
    props.put("openhouse.tableId", "rp");
    props.put("openhouse.tableType", "REPLICA_TABLE");
    props.put("openhouse.tableLocation", "/tmp/unused/" + DB + "/rp-" + uuid + "/metadata.json");
    created.add("rp");
    post(
            mvc,
            newTable(DB, "rp")
                .toBuilder()
                .policies(null)
                .tableType(com.linkedin.openhouse.tables.common.TableType.REPLICA_TABLE)
                .tableProperties(props)
                .build())
        .andExpect(status().isCreated());
    TableMetadata stored = IrcWrite.load(mvc, DB, "rp");
    String getJson = json(get(mvc, DB, "rp"));
    denyOnly(Privileges.SYSTEM_ADMIN);
    java.util.Map<String, String> updated =
        new java.util.HashMap<>(bodyFromGet(getJson).getTableProperties());
    updated.put("user.k", "v");
    String nativeMessage =
        message(
            put(mvc, bodyFromGet(getJson).toBuilder().tableProperties(updated).build())
                .andExpect(status().isForbidden()));
    Mockito.clearInvocations(authorizationHandler);
    org.springframework.test.web.servlet.MvcResult result =
        IrcWrite.commit(
            mvc, DB, "rp", request(Collections.singletonList(wrongUuid()), setUserProperty()));
    assertEquals(nativeMessage, assertIrcError(result, 403, "ForbiddenException").message());
    assertNoStoredIdIn(IrcHttp.body(result), stored);
    Mockito.verify(authorizationHandler)
        .checkAccessDecision(
            any(),
            org.mockito.ArgumentMatchers.argThat(
                (TableDto table) -> table != null && "rp".equals(table.getTableId())),
            org.mockito.ArgumentMatchers.eq(Privileges.SYSTEM_ADMIN));
  }

  /**
   * When the authorization backend itself fails, the IRC table routes answer what the native route
   * answers, 503 with the backend's message (a client may retry it), not the 403 of a refusal, on
   * the load, the empty commit, a commit with updates and the one-table transaction. It fails
   * closed: nothing is answered and nothing is written.
   */
  @Test
  void anAuthorizationBackendFailureIs503OnTheIrcTableRoutesAsNatively() throws Exception {
    create("f1");
    TableMetadata stored = IrcWrite.load(mvc, DB, "f1");
    when(authorizationHandler.checkAccessDecision(
            any(), any(TableDto.class), any(Privileges.class)))
        .thenThrow(
            new org.springframework.security.access.AuthorizationServiceException(
                "Cannot perform Opa access check"));
    String nativeMessage = message(get(mvc, DB, "f1").andExpect(status().isServiceUnavailable()));
    assertEquals("Cannot perform Opa access check", nativeMessage);
    List<org.springframework.test.web.servlet.MvcResult> results = new ArrayList<>();
    results.add(IrcHttp.get(mvc, IrcWrite.table(DB, "f1")));
    results.add(IrcWrite.commit(mvc, DB, "f1", EMPTY));
    results.add(
        IrcWrite.commit(mvc, DB, "f1", request(Collections.emptyList(), setUserProperty())));
    results.add(
        IrcWrite.transaction(
            mvc, IrcWrite.change(DB, "f1", request(Collections.emptyList(), setUserProperty()))));
    for (org.springframework.test.web.servlet.MvcResult result : results) {
      assertEquals(
          nativeMessage,
          assertIrcError(result, 503, "ServiceUnavailableException").message(),
          result.getRequest().getRequestURI());
      assertNoStoredIdIn(IrcHttp.body(result), stored);
    }
    reset(authorizationHandler);
    stubDecision(true);
    assertEquals(
        stored.metadataFileLocation(), IrcWrite.load(mvc, DB, "f1").metadataFileLocation());
  }
}
