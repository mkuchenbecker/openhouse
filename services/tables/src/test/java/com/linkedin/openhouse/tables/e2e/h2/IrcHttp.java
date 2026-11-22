package com.linkedin.openhouse.tables.e2e.h2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import org.apache.iceberg.rest.IrcClientParsers;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * HTTP-only helpers for the Iceberg REST Catalog (IRC) read routes, which the service serves under
 * {@link #BASE}. Everything goes through {@link MockMvc}; error bodies are parsed with Iceberg's
 * own parser and checked against the spec's required fields.
 */
final class IrcHttp {
  /** Base path of the IRC routes; a client sets {@code uri=<server>/iceberg}. */
  static final String BASE = "/iceberg";

  private IrcHttp() {}

  static MockHttpServletRequestBuilder request(HttpMethod method, String path) {
    return MockMvcRequestBuilders.request(method, BASE + path);
  }

  static MvcResult perform(MockMvc mvc, MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request).andReturn();
  }

  static MvcResult get(MockMvc mvc, String path) throws Exception {
    return perform(mvc, request(HttpMethod.GET, path));
  }

  static MvcResult head(MockMvc mvc, String path) throws Exception {
    return perform(mvc, request(HttpMethod.HEAD, path));
  }

  static String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString();
  }

  /**
   * Asserts {@code result} is an IRC error: the status, a JSON body with the spec's required {@code
   * error.message}, {@code error.type} and {@code error.code} (equal to the status), and the given
   * type. Returns the parsed error.
   */
  static ErrorResponse assertIrcError(MvcResult result, int status, String type) throws Exception {
    String json = body(result);
    assertEquals(status, result.getResponse().getStatus(), json);
    assertTrue(
        result.getResponse().getContentType() != null
            && result.getResponse().getContentType().startsWith("application/json"),
        "content type " + result.getResponse().getContentType());
    Object errorNode = JsonPath.read(json, "$.error");
    assertTrue(errorNode instanceof Map, "`error` is not an object: " + json);
    Map<?, ?> error = (Map<?, ?>) errorNode;
    assertNotNull(error.get("message"), json);
    assertEquals(type, error.get("type"), json);
    assertEquals(status, error.get("code"), json);
    assertEquals(1, ((Map<?, ?>) JsonPath.read(json, "$")).size(), "only `error`: " + json);
    ErrorResponse parsed = IrcClientParsers.parseError(json);
    assertEquals(status, parsed.code());
    assertEquals(type, parsed.type());
    return parsed;
  }
}
