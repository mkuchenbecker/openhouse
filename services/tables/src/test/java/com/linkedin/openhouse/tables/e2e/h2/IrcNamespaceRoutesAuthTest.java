package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.body;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linkedin.openhouse.common.exception.checked.NotFoundException;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.repository.HouseNamespaceRepository;
import com.linkedin.openhouse.tables.authorization.AuthorizationHandler;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.model.TableDto;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.iceberg.rest.IrcClientParsers;
import org.apache.iceberg.rest.responses.GetNamespaceResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Authorization on the IRC namespace routes, with the fixture of {@link IrcReadRoutesAuthTest}
 * (token interceptor and authorization on): one privilege is denied and every other allowed, so a
 * refusal shows which decision was asked for. The writes ask the namespace service's write
 * privilege ({@code SYSTEM_ADMIN} on the database) before anything else is checked, read or
 * answered, so a refused principal is answered 403 whether or not the namespace exists, holds
 * tables or is named validly. A load asks the namespace service's read privilege ({@code
 * GET_TABLE_METADATA}) for the record only: a principal refused it is answered as if there were no
 * record, as the route answered before records existed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
public class IrcNamespaceRoutesAuthTest {

  @Autowired MockMvc mvc;
  @Autowired HouseNamespaceRepository records;
  @MockBean AuthorizationHandler authorizationHandler;

  private final List<String[]> tables = new ArrayList<>();
  private final List<String> namespaces = new ArrayList<>();

  @BeforeEach
  void allowEverything() {
    reset(authorizationHandler);
    denyOnly(null);
  }

  @AfterEach
  void cleanUp() throws Exception {
    denyOnly(null);
    for (String[] key : tables) {
      CommitPathHttp.delete(mvc, key[0], key[1]);
    }
    for (String namespace : namespaces) {
      try {
        records.delete(namespace);
      } catch (NotFoundException e) {
        // dropped by the test
      }
    }
    tables.clear();
    namespaces.clear();
  }

  /** Denies {@code denied} (none if null) and allows every other privilege. */
  private void denyOnly(Privileges denied) {
    when(authorizationHandler.checkAccessDecision(
            any(), any(TableDto.class), any(Privileges.class)))
        .thenAnswer(call -> call.getArgument(2) != denied);
    when(authorizationHandler.checkAccessDecision(
            any(), any(DatabaseDto.class), any(Privileges.class)))
        .thenAnswer(call -> call.getArgument(2) != denied);
    when(authorizationHandler.checkSystemOnlyLockAccess(any(), any(), any())).thenCallRealMethod();
  }

  private void table(String db) throws Exception {
    tables.add(new String[] {db, "t1"});
    CommitPathHttp.post(mvc, CommitPathHttp.newTable(db, "t1")).andExpect(status().isCreated());
  }

  private void record(String name) throws Exception {
    namespaces.add(name);
    MvcResult result =
        IrcNamespaceRoutesTest.createNamespace(mvc, name, Collections.singletonMap("k", "v"));
    assertEquals(200, result.getResponse().getStatus(), body(result));
  }

  private boolean hasRecord(String name) {
    try {
      records.getByName(name);
      return true;
    } catch (NotFoundException e) {
      return false;
    }
  }

  private static String forbidden(MvcResult result) throws Exception {
    String message = assertIrcError(result, 403, "ForbiddenException").message();
    assertTrue(message.contains("unauthorized"), message);
    return message;
  }

  /**
   * Allowed, a create asks for {@code SYSTEM_ADMIN} on the database by its lower-case name. Denied
   * that one privilege, every create is 403: of a new name (nothing stored), of a name that has a
   * record (not the 409), of a name that holds tables (not the 409) and of an invalid name (not the
   * 400). The decision comes first; only a body that does not parse, which names no namespace to
   * decide on, is refused before it.
   */
  @Test
  void aCreateIsDecidedFirstOnTheWritePrivilege() throws Exception {
    record("Irc_Ns_Auth_Rec");
    verify(authorizationHandler, Mockito.atLeastOnce())
        .checkAccessDecision(
            any(),
            argThat(
                (DatabaseDto database) ->
                    database != null && "irc_ns_auth_rec".equals(database.getDatabaseId())),
            eq(Privileges.SYSTEM_ADMIN));
    table("irc_ns_auth_tabled");

    denyOnly(Privileges.SYSTEM_ADMIN);
    forbidden(
        IrcNamespaceRoutesTest.createNamespace(mvc, "irc_ns_auth_new", Collections.emptyMap()));
    assertFalse(hasRecord("irc_ns_auth_new"));
    forbidden(
        IrcNamespaceRoutesTest.createNamespace(mvc, "IRC_NS_AUTH_REC", Collections.emptyMap()));
    forbidden(
        IrcNamespaceRoutesTest.createNamespace(mvc, "irc_ns_auth_tabled", Collections.emptyMap()));
    forbidden(IrcNamespaceRoutesTest.createNamespace(mvc, "irc-ns-bad", Collections.emptyMap()));
    assertIrcError(
        IrcNamespaceRoutesTest.post(mvc, "/v1/namespaces", "not json"), 400, "BadRequestException");

    // The read privilege is not what a create asks for.
    denyOnly(Privileges.GET_TABLE_METADATA);
    namespaces.add("irc_ns_auth_new");
    assertEquals(
        200,
        IrcNamespaceRoutesTest.createNamespace(mvc, "irc_ns_auth_new", Collections.emptyMap())
            .getResponse()
            .getStatus());
  }

  /**
   * Denied {@code SYSTEM_ADMIN}, an update is 403 for a namespace with a record (unchanged), one
   * without (not the 404), and a body the service would refuse (not the 422 or 400).
   */
  @Test
  void anUpdateIsDecidedFirstOnTheWritePrivilege() throws Exception {
    record("irc_ns_auth_upd");
    denyOnly(Privileges.SYSTEM_ADMIN);
    forbidden(
        IrcNamespaceRoutesTest.updateProperties(
            mvc, "irc_ns_auth_upd", Collections.singletonMap("k", "w"), List.of()));
    forbidden(
        IrcNamespaceRoutesTest.updateProperties(
            mvc, "irc_ns_auth_none", Collections.singletonMap("k", "w"), List.of()));
    forbidden(
        IrcNamespaceRoutesTest.updateProperties(
            mvc, "irc_ns_auth_upd", Collections.singletonMap("k", "w"), List.of("k")));
    forbidden(IrcNamespaceRoutesTest.post(mvc, "/v1/namespaces/irc_ns_auth_upd/properties", "x"));
    assertEquals(
        Collections.singletonMap("k", "v"), records.getByName("irc_ns_auth_upd").getProperties());

    denyOnly(Privileges.GET_TABLE_METADATA);
    assertEquals(
        200,
        IrcNamespaceRoutesTest.updateProperties(
                mvc, "irc_ns_auth_upd", Collections.singletonMap("k", "w"), List.of())
            .getResponse()
            .getStatus());
  }

  /**
   * Denied {@code SYSTEM_ADMIN}, a drop is 403 for a namespace with a record (kept), a missing one
   * (not the 404) and one that holds tables (not the 409).
   */
  @Test
  void aDropIsDecidedFirstOnTheWritePrivilege() throws Exception {
    record("irc_ns_auth_drop");
    table("irc_ns_auth_drop_tabled");
    denyOnly(Privileges.SYSTEM_ADMIN);
    forbidden(IrcNamespaceRoutesTest.dropNamespace(mvc, "irc_ns_auth_drop"));
    assertTrue(hasRecord("irc_ns_auth_drop"));
    forbidden(IrcNamespaceRoutesTest.dropNamespace(mvc, "irc_ns_auth_drop_none"));
    forbidden(IrcNamespaceRoutesTest.dropNamespace(mvc, "irc_ns_auth_drop_tabled"));

    denyOnly(Privileges.GET_TABLE_METADATA);
    assertEquals(
        204,
        IrcNamespaceRoutesTest.dropNamespace(mvc, "irc_ns_auth_drop").getResponse().getStatus());
  }

  /**
   * A load asks for {@code GET_TABLE_METADATA} on the record. Denied it, the principal is answered
   * as if there were no record: a namespace with a record and tables loads with no properties, one
   * with a record only does not exist (404 on load and exists). Denied another privilege, the
   * record's properties are answered.
   */
  @Test
  void aLoadShowsTheRecordOnlyWithTheReadPrivilege() throws Exception {
    record("irc_ns_auth_both");
    table("irc_ns_auth_both");
    record("irc_ns_auth_only");

    denyOnly(Privileges.SYSTEM_ADMIN);
    assertEquals(
        Collections.singletonMap("k", "v"),
        IrcClientParsers.parse(
                body(IrcHttp.get(mvc, "/v1/namespaces/irc_ns_auth_both")),
                GetNamespaceResponse.class)
            .properties());
    assertEquals(
        204, IrcHttp.head(mvc, "/v1/namespaces/irc_ns_auth_only").getResponse().getStatus());

    denyOnly(Privileges.GET_TABLE_METADATA);
    MvcResult both = IrcHttp.get(mvc, "/v1/namespaces/irc_ns_auth_both");
    assertEquals(200, both.getResponse().getStatus(), body(both));
    assertEquals(
        Collections.emptyMap(),
        IrcClientParsers.parse(body(both), GetNamespaceResponse.class).properties());
    assertIrcError(
        IrcHttp.get(mvc, "/v1/namespaces/irc_ns_auth_only"), 404, "NoSuchNamespaceException");
    assertEquals(
        404, IrcHttp.head(mvc, "/v1/namespaces/irc_ns_auth_only").getResponse().getStatus());
  }
}
