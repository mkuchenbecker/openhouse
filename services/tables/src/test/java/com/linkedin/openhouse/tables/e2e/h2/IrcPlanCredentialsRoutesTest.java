package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.delete;
import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.newTable;
import static com.linkedin.openhouse.tables.e2e.h2.CommitPathHttp.post;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.assertIrcError;
import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.body;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import java.util.ArrayList;
import java.util.List;
import org.apache.iceberg.Schema;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The IRC scan planning route ({@code POST .../tables/{table}/plan}) and the credentials route
 * ({@code GET .../tables/{table}/credentials}) at the HTTP boundary, and the access-delegation
 * header ({@code X-Iceberg-Access-Delegation}) on the load and create routes, which vends nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class IrcPlanCredentialsRoutesTest {

  static final String DB = "irc_plan_routes";

  /** The header a client sends to ask for vended credentials or remote signing. */
  static final String ACCESS_DELEGATION = "X-Iceberg-Access-Delegation";

  private static final Schema SCHEMA =
      new Schema(
          Types.NestedField.optional(1, "id", Types.LongType.get()),
          Types.NestedField.optional(2, "name", Types.StringType.get()));

  @Autowired MockMvc mvc;

  private final List<String> created = new ArrayList<>();

  @AfterEach
  void cleanUp() throws Exception {
    for (String table : created) {
      delete(mvc, DB, table);
    }
    created.clear();
  }

  private void createNative(String table) throws Exception {
    created.add(table);
    post(mvc, newTable(DB, table)).andExpect(status().isCreated());
  }

  static String plan(String db, String table) {
    return "/v1/namespaces/" + db + "/tables/" + table + "/plan";
  }

  static String credentials(String db, String table) {
    return "/v1/namespaces/" + db + "/tables/" + table + "/credentials";
  }

  private MvcResult postPlan(String path, String json) throws Exception {
    return IrcHttp.perform(
        mvc,
        IrcHttp.request(HttpMethod.POST, path)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  @SuppressWarnings("unchecked")
  private List<String> endpoints() throws Exception {
    return (List<String>) JsonPath.read(body(IrcHttp.get(mvc, "/v1/config")), "$.endpoints");
  }

  // ---------------------------------------------------------------- the routes

  /** Not served (yet): the spec's 406, and not listed in the config's endpoints. */
  @Test
  void planningIsNotServed() throws Exception {
    createNative("t1");
    assertIrcError(postPlan(plan(DB, "t1"), "{}"), 406, "UnsupportedOperationException");
    assertFalse(
        endpoints().contains("POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/plan"));
  }

  @Test
  void credentialsAreNotServed() throws Exception {
    createNative("t1");
    assertIrcError(IrcHttp.get(mvc, credentials(DB, "t1")), 406, "UnsupportedOperationException");
    assertFalse(
        endpoints().contains("GET /v1/{prefix}/namespaces/{namespace}/tables/{table}/credentials"));
  }

  // ---------------------------------------------------------------- the header

  private MockHttpServletRequestBuilder delegating(MockHttpServletRequestBuilder request) {
    return request.header(ACCESS_DELEGATION, "vended-credentials,remote-signing");
  }

  /** A load asking for vended credentials or remote signing is answered as one that does not. */
  @Test
  void theAccessDelegationHeaderLeavesALoadByteForByteUnchanged() throws Exception {
    createNative("t1");
    String path = "/v1/namespaces/" + DB + "/tables/t1";
    MvcResult plain = IrcHttp.get(mvc, path);
    assertEquals(200, plain.getResponse().getStatus(), body(plain));
    for (String value :
        new String[] {
          "vended-credentials", "remote-signing", "vended-credentials,remote-signing"
        }) {
      MvcResult asked =
          IrcHttp.perform(
              mvc, IrcHttp.request(HttpMethod.GET, path).header(ACCESS_DELEGATION, value));
      assertEquals(200, asked.getResponse().getStatus(), body(asked));
      assertEquals(body(plain), body(asked), value);
      assertEquals(plain.getResponse().getContentType(), asked.getResponse().getContentType());
    }
  }

  /**
   * A create asking for vended credentials or remote signing is answered as one that does not: the
   * two responses differ only in what differs between any two tables (name, UUID, location,
   * timestamps).
   */
  @Test
  void theAccessDelegationHeaderLeavesACreateUnchanged() throws Exception {
    created.add("plain");
    MvcResult plain = IrcWrite.create(mvc, DB, createRequest("plain"));
    assertEquals(200, plain.getResponse().getStatus(), body(plain));
    created.add("asked");
    MvcResult asked =
        IrcHttp.perform(
            mvc, delegating(IrcWrite.post(IrcWrite.tables(DB), createRequest("asked"))));
    assertEquals(200, asked.getResponse().getStatus(), body(asked));
    assertEquals(
        FormatV3.withoutUuids(body(plain)).replace("plain", "TABLE"),
        FormatV3.withoutUuids(body(asked)).replace("asked", "TABLE"));
    assertFalse(body(asked).contains("storage-credentials"), body(asked));
  }

  private static CreateTableRequest createRequest(String table) {
    return CreateTableRequest.builder()
        .withName(table)
        .withSchema(SCHEMA)
        .setProperties(newTable(DB, table).getTableProperties())
        .build();
  }
}
