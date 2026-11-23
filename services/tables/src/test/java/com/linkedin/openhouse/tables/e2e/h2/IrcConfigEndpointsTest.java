package com.linkedin.openhouse.tables.e2e.h2;

import static com.linkedin.openhouse.tables.e2e.h2.IrcHttp.body;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jayway.jsonpath.JsonPath;
import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import com.linkedin.openhouse.tables.api.irc.IcebergRestCatalogController;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.apache.iceberg.rest.IrcClientParsers;
import org.apache.iceberg.rest.responses.ConfigResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The config response's {@code endpoints}: exactly the spec routes this server serves on its
 * Iceberg line, in the spec's format ({@code "<METHOD> /v1/{prefix}/<path>"}, the strings of
 * Iceberg 1.12's {@code Endpoint}). An Iceberg 1.12 client uses only the routes listed once a list
 * is sent; with none it assumes a fixed default set that this server does not match (namespace
 * writes it does not serve; HEAD, views and transactions it does).
 *
 * <p>Three checks keep the list and the routes from drifting: the list equals a fixed expectation
 * (so adding or removing a route changes this test); every listed route is served (a request does
 * not get the "not served" 406 or the catch-all's 400); and every spec route not listed answers
 * 406. The OpenHouse routes under the base path are not spec routes and are not listed: a client
 * finds them through {@code openhouse.capabilities}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IrcReadRoutesTest.StubDefaults.class)
@ContextConfiguration(initializers = {PropertyOverrideContextInitializer.class})
public class IrcConfigEndpointsTest {

  /**
   * What this service serves on Iceberg 1.5 (its test classpath): reads, metrics reports, table
   * writes (register among them), views, and the multi-table commit route. That route commits a
   * transaction of one table and refuses one of several (IrcTransactionRouteTest). It is listed all
   * the same: an Iceberg 1.12 client refuses {@code commitTransaction} locally when it is not
   * listed and has no fallback to per-table commits, so leaving it out would only take the
   * one-table transaction away (SparkIrcEndpointsTest, on Spark 4.2).
   */
  static final List<String> SERVED_ON_ICEBERG_1_5 =
      Arrays.asList(
          "GET /v1/{prefix}/namespaces",
          "GET /v1/{prefix}/namespaces/{namespace}",
          "HEAD /v1/{prefix}/namespaces/{namespace}",
          "POST /v1/{prefix}/namespaces/{namespace}/register",
          "GET /v1/{prefix}/namespaces/{namespace}/tables",
          "POST /v1/{prefix}/namespaces/{namespace}/tables",
          "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}",
          "HEAD /v1/{prefix}/namespaces/{namespace}/tables/{table}",
          "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}",
          "DELETE /v1/{prefix}/namespaces/{namespace}/tables/{table}",
          "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/metrics",
          "GET /v1/{prefix}/namespaces/{namespace}/views",
          "POST /v1/{prefix}/namespaces/{namespace}/views",
          "GET /v1/{prefix}/namespaces/{namespace}/views/{view}",
          "HEAD /v1/{prefix}/namespaces/{namespace}/views/{view}",
          "POST /v1/{prefix}/namespaces/{namespace}/views/{view}",
          "DELETE /v1/{prefix}/namespaces/{namespace}/views/{view}",
          "POST /v1/{prefix}/tables/rename",
          "POST /v1/{prefix}/transactions/commit");

  /** Every route of Iceberg 1.12's {@code Endpoint} (its public constants, as strings). */
  static final List<String> SPEC_ENDPOINTS_OF_ICEBERG_1_12 =
      Arrays.asList(
          "GET /v1/{prefix}/namespaces",
          "GET /v1/{prefix}/namespaces/{namespace}",
          "HEAD /v1/{prefix}/namespaces/{namespace}",
          "POST /v1/{prefix}/namespaces",
          "POST /v1/{prefix}/namespaces/{namespace}/properties",
          "DELETE /v1/{prefix}/namespaces/{namespace}",
          "POST /v1/{prefix}/transactions/commit",
          "GET /v1/{prefix}/namespaces/{namespace}/tables",
          "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}",
          "HEAD /v1/{prefix}/namespaces/{namespace}/tables/{table}",
          "POST /v1/{prefix}/namespaces/{namespace}/tables",
          "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}",
          "DELETE /v1/{prefix}/namespaces/{namespace}/tables/{table}",
          "POST /v1/{prefix}/tables/rename",
          "POST /v1/{prefix}/namespaces/{namespace}/register",
          "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/metrics",
          "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}/credentials",
          "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/sign",
          "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/plan",
          "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}/plan/{plan-id}",
          "DELETE /v1/{prefix}/namespaces/{namespace}/tables/{table}/plan/{plan-id}",
          "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/tasks",
          "GET /v1/{prefix}/namespaces/{namespace}/views",
          "GET /v1/{prefix}/namespaces/{namespace}/views/{view}",
          "HEAD /v1/{prefix}/namespaces/{namespace}/views/{view}",
          "POST /v1/{prefix}/namespaces/{namespace}/views",
          "POST /v1/{prefix}/namespaces/{namespace}/views/{view}",
          "DELETE /v1/{prefix}/namespaces/{namespace}/views/{view}",
          "POST /v1/{prefix}/views/rename",
          "POST /v1/{prefix}/namespaces/{namespace}/register-view");

  @Autowired MockMvc mvc;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping mappings;

  private Map<String, Object> config() throws Exception {
    MvcResult result = IrcHttp.get(mvc, "/v1/config");
    assertEquals(200, result.getResponse().getStatus(), body(result));
    return JsonPath.read(body(result), "$");
  }

  @SuppressWarnings("unchecked")
  private List<String> endpoints() throws Exception {
    return (List<String>) config().get("endpoints");
  }

  /** A request for {@code endpoint} on this server, which has no prefix. */
  private MvcResult call(String endpoint) throws Exception {
    String[] parts = endpoint.split(" ");
    String path =
        parts[1]
            .replace("/{prefix}", "")
            .replace("{namespace}", "irc_cfg_none")
            .replace("{table}", "t")
            .replace("{view}", "v")
            .replace("{plan-id}", "p");
    return IrcHttp.perform(
        mvc,
        IrcHttp.request(HttpMethod.valueOf(parts[0]), path)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                !parts[0].equals("POST")
                    ? ""
                    : path.endsWith("/rename")
                        // A valid rename, so that a route that parses its body before refusing
                        // (view rename) answers its refusal.
                        ? "{\"source\":{\"namespace\":[\"irc_cfg_none\"],\"name\":\"a\"},"
                            + "\"destination\":{\"namespace\":[\"irc_cfg_none\"],\"name\":\"b\"}}"
                        : "{}"));
  }

  /** Whether the server answered "not served": the 406, or the catch-all's 400 for no route. */
  private static boolean unserved(MvcResult result) throws Exception {
    int status = result.getResponse().getStatus();
    return status == 406
        || (status == 400 && body(result).contains("No IRC route for "))
        || body(result).contains("does not support");
  }

  @Test
  void theEndpointsAreTheServedSpecRoutesInTheSpecsFormat() throws Exception {
    assertEquals(SERVED_ON_ICEBERG_1_5, endpoints());
    for (String endpoint : endpoints()) {
      assertTrue(endpoint.matches("(GET|HEAD|POST|DELETE) /v1/\\{prefix}/[a-z{}/-]+"), endpoint);
      assertTrue(SPEC_ENDPOINTS_OF_ICEBERG_1_12.contains(endpoint), "a spec route: " + endpoint);
    }
  }

  @Test
  void everyListedEndpointIsServed() throws Exception {
    for (String endpoint : endpoints()) {
      MvcResult result = call(endpoint);
      assertFalse(
          unserved(result), endpoint + " -> " + result.getResponse().getStatus() + body(result));
    }
  }

  @Test
  void everySpecRouteNotListedAnswers406() throws Exception {
    List<String> listed = endpoints();
    for (String endpoint : SPEC_ENDPOINTS_OF_ICEBERG_1_12) {
      if (!listed.contains(endpoint)) {
        assertEquals(406, call(endpoint).getResponse().getStatus(), endpoint);
      }
    }
  }

  /**
   * Read independently of the server's derivation: every route the IRC controllers map is either
   * listed, or the config route, or an OpenHouse route, or the catch-all, or the one spec route
   * mapped only to refuse it (view rename, D27).
   */
  @Test
  void everyMappedIrcRouteIsListedOrAccountedFor() throws Exception {
    TreeSet<String> mapped = new TreeSet<>();
    TreeSet<String> accounted = new TreeSet<>();
    for (Map.Entry<RequestMappingInfo, HandlerMethod> entry :
        mappings.getHandlerMethods().entrySet()) {
      if (!entry
          .getValue()
          .getBeanType()
          .getPackage()
          .getName()
          .equals(IcebergRestCatalogController.class.getPackage().getName())) {
        continue;
      }
      for (String pattern : entry.getKey().getPatternValues()) {
        if (entry.getKey().getMethodsCondition().getMethods().isEmpty()) {
          accounted.add("ANY " + pattern);
          continue;
        }
        for (RequestMethod method : entry.getKey().getMethodsCondition().getMethods()) {
          String route = method + " " + pattern.replace("/iceberg/v1", "/v1/{prefix}");
          if (pattern.equals("/iceberg/v1/config")
              || pattern.contains("/openhouse/")
              || route.equals("POST /v1/{prefix}/views/rename")) {
            accounted.add(route);
          } else {
            mapped.add(route);
          }
        }
      }
    }
    assertEquals(new TreeSet<>(endpoints()), mapped);
    assertEquals(
        new TreeSet<>(
            Arrays.asList(
                "ANY /iceberg/**",
                "GET /v1/{prefix}/config",
                "GET /v1/{prefix}/namespaces/{namespace}/openhouse/acl-policies",
                "POST /v1/{prefix}/namespaces/{namespace}/openhouse/acl-policies",
                "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}/openhouse/acl-policies",
                "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/openhouse/acl-policies",
                "DELETE /v1/{prefix}/namespaces/{namespace}/tables/{table}/openhouse/lock",
                "DELETE /v1/{prefix}/namespaces/{namespace}/tables/{table}/openhouse/lock/{reason}",
                "POST /v1/{prefix}/views/rename")),
        accounted);
  }

  /**
   * The OpenHouse routes are advertised through the capability override only, which names what is
   * registered here: the policy patch (with the write routes), grants and unlock.
   */
  @Test
  void theOpenHouseRoutesAreCapabilitiesNotEndpoints() throws Exception {
    for (String endpoint : endpoints()) {
      assertFalse(endpoint.contains("openhouse"), endpoint);
    }
    assertEquals(
        Collections.singletonMap("openhouse.capabilities", "policies,grants,unlock"),
        config().get("overrides"));
  }

  /**
   * The client of this Iceberg line reads the config: Iceberg 1.5.2's {@code ConfigResponse} has no
   * {@code endpoints} and its mapper ignores unknown fields, so it reads what it read before;
   * Iceberg 1.12's (the opt-in build) reads the list. (A real 1.5.2 {@code RESTCatalog}
   * initializing against this config: IrcRestCatalogPolicyKeyTest and the IRC itests.)
   */
  @Test
  void thisIcebergLinesClientReadsTheConfig() throws Exception {
    MvcResult result = IrcHttp.get(mvc, "/v1/config");
    ConfigResponse parsed = IrcClientParsers.parse(body(result), ConfigResponse.class);
    assertEquals("policies,grants,unlock", parsed.overrides().get("openhouse.capabilities"));
    assertEquals(2, parsed.defaults().size());
    java.lang.reflect.Method endpoints;
    try {
      endpoints = ConfigResponse.class.getMethod("endpoints");
    } catch (NoSuchMethodException e) {
      List<String> fields = new ArrayList<>();
      for (java.lang.reflect.Field field : ConfigResponse.class.getDeclaredFields()) {
        fields.add(field.getName());
      }
      assertFalse(fields.contains("endpoints"), "Iceberg 1.5.2's ConfigResponse: " + fields);
      return;
    }
    List<String> read = new ArrayList<>();
    for (Object endpoint : (List<?>) endpoints.invoke(parsed)) {
      read.add(endpoint.toString());
    }
    assertEquals(SERVED_ON_ICEBERG_1_5, read);
  }
}
