package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static com.linkedin.openhouse.tables.e2e.h2.NativeViewHttp.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
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
import java.util.Map;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.view.ViewMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Authorization on the IRC view routes, with the fixture of {@link IrcWriteRoutesAuthTest} (same
 * Spring context): only the {@link AuthorizationHandler} decision is switched. Each refusal is the
 * native view route's refusal of the same principal, as 403 {@code ForbiddenException}, and changes
 * nothing. Reads make no authorization decision natively, so none over IRC either.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(
    initializers = {
      PropertyOverrideContextInitializer.class,
      AuthorizationPropertiesInitializer.class
    })
public class IrcViewRoutesAuthTest {
  private static final String DB = "irc_vauth";

  @Autowired MockMvc mvc;
  @Autowired HouseTableRepository houseTables;
  @Autowired ToggleStatusesRepository toggles;
  @MockBean AuthorizationHandler authorizationHandler;

  @BeforeEach
  void setUp() throws Exception {
    reset(authorizationHandler);
    stubDecision(true);
    enableViews(toggles, DB);
    CommitPathHttp.post(mvc, CommitPathHttp.newTable(DB, "t"));
    NativeViewHttp.delete(mvc, DB, "v");
    assertEquals(
        201,
        code(post(mvc, createBody(DB, "v", SCHEMA_ID_NAME, "SELECT 1", Collections.emptyMap()))));
  }

  @AfterEach
  void cleanUp() throws Exception {
    stubDecision(true);
    NativeViewHttp.delete(mvc, DB, "v");
    NativeViewHttp.delete(mvc, DB, "w");
    CommitPathHttp.delete(mvc, DB, "t");
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

  private static String message(MvcResult result) throws Exception {
    return JsonPath.read(body(result), "$.message");
  }

  @Test
  void aDeniedCreateIs403AsNatively() throws Exception {
    stubDecision(false);
    MvcResult nativeResult =
        post(mvc, createBody(DB, "w", SCHEMA_ID_NAME, "SELECT 1", Collections.emptyMap()));
    assertEquals(403, code(nativeResult));
    assertEquals(
        message(nativeResult),
        assertIrcError(
                IrcViewWrite.create(
                    mvc,
                    DB,
                    IrcViewWrite.createRequest(
                        "w",
                        IrcViewRoutesTest.SCHEMA,
                        "SELECT 1",
                        Namespace.of(DB),
                        Collections.emptyMap())),
                403,
                "ForbiddenException")
            .message());
    stubDecision(true);
    assertEquals(404, code(get(mvc, DB, "w")));
  }

  @Test
  void aDeniedReplaceIs403AsNativelyAndChangesNothing() throws Exception {
    ViewMetadata base = IrcViewWrite.load(mvc, DB, "v");
    String before = StoredViewMetadata.normalized(houseTables, DB, "v");
    stubDecision(false);
    Map<String, String> properties = StoredViewMetadataGoldenTest.storedProperties(houseTables, DB);
    properties.put("k", "v");
    MvcResult nativeResult =
        put(
            mvc,
            replaceBody(
                DB,
                "v",
                base.metadataFileLocation(),
                SCHEMA_ID_NAME,
                Collections.singletonList(spark("SELECT 1")),
                properties));
    assertEquals(403, code(nativeResult));
    assertEquals(
        message(nativeResult),
        assertIrcError(
                IrcViewWrite.commit(
                    mvc,
                    DB,
                    "v",
                    IrcViewWrite.commitRequest(
                        base, IrcViewWrite.properties(base, Collections.singletonMap("k", "v")))),
                403,
                "ForbiddenException")
            .message());
    assertEquals(before, StoredViewMetadata.normalized(houseTables, DB, "v"));
  }

  @Test
  void aDeniedDropIs403AsNatively() throws Exception {
    stubDecision(false);
    MvcResult nativeResult = NativeViewHttp.delete(mvc, DB, "v");
    assertEquals(403, code(nativeResult));
    assertEquals(
        message(nativeResult),
        assertIrcError(IrcViewWrite.drop(mvc, DB, "v"), 403, "ForbiddenException").message());
    stubDecision(true);
    assertEquals(200, code(get(mvc, DB, "v")));
  }

  @Test
  void readsMakeNoAuthorizationDecisionAsNatively() throws Exception {
    stubDecision(false);
    assertEquals(200, code(get(mvc, DB, "v")));
    assertEquals(200, IrcHttp.get(mvc, IrcViewWrite.view(DB, "v")).getResponse().getStatus());
    assertEquals(200, IrcHttp.get(mvc, IrcViewWrite.views(DB)).getResponse().getStatus());
  }
}
