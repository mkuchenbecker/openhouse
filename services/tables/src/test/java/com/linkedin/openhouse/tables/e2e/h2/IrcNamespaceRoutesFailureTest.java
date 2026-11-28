package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.body;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linkedin.openhouse.common.exception.EntityConcurrentModificationException;
import com.linkedin.openhouse.common.exception.checked.NotFoundException;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.internal.catalog.model.NamespaceMetadata;
import com.linkedin.openhouse.internal.catalog.repository.HouseNamespaceRepository;
import com.linkedin.openhouse.tables.authorization.AuthorizationHandler;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.mock.properties.AuthorizationPropertiesInitializer;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.model.TableDto;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.NamespaceNotEmptyException;
import org.apache.iceberg.rest.ErrorHandlers;
import org.apache.iceberg.rest.IrcClientParsers;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.apache.iceberg.rest.responses.GetNamespaceResponse;
import org.apache.iceberg.rest.responses.ListNamespacesResponse;
import org.apache.iceberg.rest.responses.ListTablesResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AuthorizationServiceException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * The IRC namespace routes when the namespace-record store or the authorization service fails, and
 * when a record write loses a race. A namespace that holds a table answers its reads exactly as
 * before namespace records (load with no properties, exists, list tables, list views, children); a
 * name that holds nothing answers the misses as before (404), and its load and the writes answer
 * the failure.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
public class IrcNamespaceRoutesFailureTest {

  private static final String TABLED = "irc_nsf_tabled";
  private static final String NONE = "irc_nsf_none";

  @Autowired MockMvc mvc;
  @MockBean HouseNamespaceRepository records;
  @MockBean AuthorizationHandler authorizationHandler;

  private final List<String[]> tables = new ArrayList<>();

  @BeforeEach
  void healthy() throws Exception {
    reset(records, authorizationHandler);
    when(records.getByName(anyString()))
        .thenThrow(new NotFoundException("Namespace does not exist"));
    decide(null);
    tables.add(new String[] {TABLED, "t1"});
    CommitPathHttp.post(mvc, CommitPathHttp.newTable(TABLED, "t1")).andExpect(status().isCreated());
  }

  @AfterEach
  void cleanUp() throws Exception {
    reset(records);
    decide(null);
    for (String[] key : tables) {
      CommitPathHttp.delete(mvc, key[0], key[1]);
    }
    tables.clear();
  }

  /** Allows every decision, except that one on a database throws {@code failure} if given. */
  private void decide(RuntimeException failure) {
    when(authorizationHandler.checkAccessDecision(
            any(), any(TableDto.class), any(Privileges.class)))
        .thenReturn(true);
    if (failure == null) {
      when(authorizationHandler.checkAccessDecision(
              any(), any(DatabaseDto.class), any(Privileges.class)))
          .thenReturn(true);
    } else {
      when(authorizationHandler.checkAccessDecision(
              any(), any(DatabaseDto.class), any(Privileges.class)))
          .thenThrow(failure);
    }
    when(authorizationHandler.checkSystemOnlyLockAccess(any(), any(), any())).thenCallRealMethod();
  }

  private static WebClientResponseException storeFailure() {
    return WebClientResponseException.create(
        500, "Internal Server Error", HttpHeaders.EMPTY, new byte[0], null);
  }

  private void storeFails() throws Exception {
    reset(records);
    when(records.getByName(anyString())).thenThrow(storeFailure());
    when(records.create(anyString(), any())).thenThrow(storeFailure());
    when(records.update(anyString(), any())).thenThrow(storeFailure());
    org.mockito.Mockito.doThrow(storeFailure()).when(records).delete(anyString());
  }

  private void authorizationServiceFails() {
    decide(new AuthorizationServiceException("authorization service unavailable"));
  }

  private MvcResult children(String parent) throws Exception {
    return IrcHttp.perform(
        mvc, IrcHttp.request(HttpMethod.GET, "/v1/namespaces").param("parent", parent));
  }

  /** The reads of a namespace that holds a table, as before namespace records. */
  private void tabledReadsAsBefore() throws Exception {
    MvcResult load = IrcHttp.get(mvc, "/v1/namespaces/" + TABLED);
    assertEquals(200, load.getResponse().getStatus(), body(load));
    assertEquals(
        Collections.emptyMap(),
        IrcClientParsers.parse(body(load), GetNamespaceResponse.class).properties());
    assertEquals(204, IrcHttp.head(mvc, "/v1/namespaces/" + TABLED).getResponse().getStatus());
    MvcResult list = IrcHttp.get(mvc, "/v1/namespaces/" + TABLED + "/tables");
    assertEquals(200, list.getResponse().getStatus(), body(list));
    assertEquals(
        Collections.singletonList(TableIdentifier.of(TABLED, "t1")),
        IrcClientParsers.parse(body(list), ListTablesResponse.class).identifiers());
    MvcResult kids = children(TABLED);
    assertEquals(200, kids.getResponse().getStatus(), body(kids));
    assertTrue(
        IrcClientParsers.parse(body(kids), ListNamespacesResponse.class).namespaces().isEmpty());
    MvcResult views = IrcHttp.get(mvc, IrcViewWrite.views(TABLED));
    assertEquals(200, views.getResponse().getStatus(), body(views));
  }

  /** The existence misses of a name that holds nothing, as before namespace records. */
  private void missesAsBefore() throws Exception {
    assertEquals(404, IrcHttp.head(mvc, "/v1/namespaces/" + NONE).getResponse().getStatus());
    assertIrcError(
        IrcHttp.get(mvc, "/v1/namespaces/" + NONE + "/tables"), 404, "NoSuchNamespaceException");
    assertIrcError(children(NONE), 404, "NoSuchNamespaceException");
    assertIrcError(IrcHttp.get(mvc, IrcViewWrite.views(NONE)), 404, "NoSuchNamespaceException");
    assertIrcError(
        IrcWrite.rename(mvc, TableIdentifier.of(TABLED, "t1"), TableIdentifier.of(NONE, "t1")),
        404,
        "NoSuchNamespaceException");
  }

  @Test
  void whenTheRecordStoreFailsANamespaceThatHoldsATableReadsAsBefore() throws Exception {
    storeFails();
    tabledReadsAsBefore();
    missesAsBefore();
    // A name that holds nothing has no answer but the failure on load and on the writes.
    assertIrcError(IrcHttp.get(mvc, "/v1/namespaces/" + NONE), 500, "ServiceFailureException");
    assertIrcError(
        IrcNamespaceRoutesTest.createNamespace(mvc, NONE, Collections.emptyMap()),
        500,
        "ServiceFailureException");
    assertIrcError(
        IrcNamespaceRoutesTest.updateProperties(
            mvc, NONE, Collections.singletonMap("k", "v"), List.of()),
        500,
        "ServiceFailureException");
    assertIrcError(IrcNamespaceRoutesTest.dropNamespace(mvc, NONE), 500, "ServiceFailureException");
    // The drop guard reads no record: a namespace that holds a table is still not empty.
    assertIrcError(
        IrcNamespaceRoutesTest.dropNamespace(mvc, TABLED), 409, "NamespaceNotEmptyException");
  }

  /**
   * The authorization service's failure ({@link AuthorizationServiceException}) on a name that
   * holds nothing is the answer of load and of the writes. The IRC error mapping answers it 403
   * {@code ForbiddenException}, as on every IRC route that asks the authorization service (the
   * failure type is a subtype of the denial, which the mapping tests first); pinned as it is.
   */
  @Test
  void whenTheAuthorizationServiceFailsANamespaceThatHoldsATableReadsAsBefore() throws Exception {
    authorizationServiceFails();
    tabledReadsAsBefore();
    missesAsBefore();
    for (MvcResult result :
        new MvcResult[] {
          IrcHttp.get(mvc, "/v1/namespaces/" + NONE),
          IrcNamespaceRoutesTest.createNamespace(mvc, NONE, Collections.emptyMap()),
          IrcNamespaceRoutesTest.updateProperties(
              mvc, NONE, Collections.singletonMap("k", "v"), List.of()),
          IrcNamespaceRoutesTest.dropNamespace(mvc, NONE)
        }) {
      assertEquals(
          "authorization service unavailable",
          assertIrcError(result, 403, "ForbiddenException").message());
    }
  }

  /** Whether this Iceberg line's client has an error handler of its own for a namespace drop. */
  @SuppressWarnings("unchecked")
  private static Consumer<ErrorResponse> dropHandler() {
    try {
      return (Consumer<ErrorResponse>)
          ErrorHandlers.class.getMethod("dropNamespaceErrorHandler").invoke(null);
    } catch (ReflectiveOperationException e) {
      return ErrorHandlers.namespaceErrorHandler();
    }
  }

  /**
   * A record write that loses a race (the store's conditional replace or delete conflicts) is the
   * namespace service's conflict, answered 409 {@code CommitFailedException}. The specification
   * lists no 409 for a property update. Iceberg's clients read a 409 on a namespace route as {@code
   * AlreadyExistsException}, and Iceberg 1.12's client reads a 409 on a drop as {@code
   * NamespaceNotEmptyException}: neither says the write lost a race.
   */
  @Test
  void aLostRaceIs409CommitFailedWhichClientsReadAsAnotherException() throws Exception {
    reset(records);
    when(records.getByName(anyString()))
        .thenReturn(
            new NamespaceMetadata("u", "irc_nsf_race", Collections.singletonMap("k", "v"), "0"));
    when(records.update(anyString(), any()))
        .thenThrow(new EntityConcurrentModificationException("irc_nsf_race", null));
    org.mockito.Mockito.doThrow(new EntityConcurrentModificationException("irc_nsf_race", null))
        .when(records)
        .delete(anyString());

    ErrorResponse update =
        assertIrcError(
            IrcNamespaceRoutesTest.updateProperties(
                mvc, "irc_nsf_race", Collections.singletonMap("k", "w"), List.of()),
            409,
            "CommitFailedException");
    assertTrue(update.message().contains("irc_nsf_race"), update.message());
    ErrorResponse drop =
        assertIrcError(
            IrcNamespaceRoutesTest.dropNamespace(mvc, "irc_nsf_race"),
            409,
            "CommitFailedException");

    assertThrows(
        AlreadyExistsException.class, () -> ErrorHandlers.namespaceErrorHandler().accept(update));
    boolean dropHandlerOfItsOwn;
    try {
      ErrorHandlers.class.getMethod("dropNamespaceErrorHandler");
      dropHandlerOfItsOwn = true;
    } catch (NoSuchMethodException e) {
      dropHandlerOfItsOwn = false;
    }
    Class<? extends RuntimeException> onDrop =
        dropHandlerOfItsOwn ? NamespaceNotEmptyException.class : AlreadyExistsException.class;
    assertThrows(onDrop, () -> dropHandler().accept(drop));
  }
}
