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
import com.linkedin.openhouse.common.schema.IcebergSchemaHelper;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.Policies;
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
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
public class IrcWriteRoutesAuthTest {
  private static final String DB = "irc_wauth";

  @Autowired MockMvc mvc;
  @MockBean AuthorizationHandler authorizationHandler;

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
}
