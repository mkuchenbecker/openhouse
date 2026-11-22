package com.linkedin.openhouse.javaclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.javaclient.OpenHouseRestAdapter.Method;
import com.linkedin.openhouse.javaclient.OpenHouseRestAdapter.Route;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.iceberg.rest.responses.ConfigResponse;
import org.junit.jupiter.api.Test;

/**
 * The endpoints the adapter advertises in its config response (work item C5).
 *
 * <p>Iceberg 1.12's {@code RESTSessionCatalog} calls only the endpoints a server advertises, and
 * assumes a default set without views when it advertises none. So on the v3 line ({@link
 * IcebergLine}) the adapter advertises exactly the routes it dispatches: a route it serves but does
 * not list is refused on the client ("Server does not support endpoint"), and one it lists but does
 * not serve fails on the adapter instead. Iceberg 1.5.2 has no endpoints in its config response, so
 * on the legacy line the response is as before.
 *
 * <p>These tests run on both lines; only the expected config response differs.
 */
public class OpenHouseRestAdapterEndpointsTest {

  private static final String ENDPOINT_CLASS = "org.apache.iceberg.rest.Endpoint";
  private static final String PREFIXED_V1 = "/v1/{prefix}/";

  /** The config call needs no backing catalog. */
  private static ConfigResponse config() {
    return new OpenHouseRestAdapter(null)
        .get("v1/config", null, ConfigResponse.class, Collections.emptyMap(), error -> {});
  }

  /**
   * Every advertised endpoint, as a request with each path variable filled in, reaches exactly one
   * route, and every route but the config route is reached. So the advertised list and the dispatch
   * table cannot drift apart.
   */
  @Test
  void advertisedEndpointsAreExactlyTheDispatchedRoutes() {
    Set<Route> reached = EnumSet.noneOf(Route.class);
    for (String endpoint : OpenHouseRestAdapter.advertisedEndpoints()) {
      String[] methodAndPath = endpoint.split(" ", -1);
      assertEquals(2, methodAndPath.length, endpoint);
      String path = methodAndPath[1];
      assertTrue(path.startsWith(PREFIXED_V1), endpoint);
      String request = "v1/" + path.substring(PREFIXED_V1.length()).replaceAll("\\{[^/}]+}", "x");
      Route route = OpenHouseRestAdapter.route(Method.valueOf(methodAndPath[0]), request);
      assertNotNull(route, "advertised but not dispatched: " + endpoint);
      assertTrue(reached.add(route), "advertised twice: " + route);
    }
    assertEquals(EnumSet.complementOf(EnumSet.of(Route.CONFIG)), reached);
  }

  /**
   * The config response carries the endpoints on the v3 line and none on the legacy line, and on
   * both the server capability the tables service's IRC config advertises ({@code
   * openhouse.capabilities=policies}, an override), so every REST-shaped path reads the same
   * capability: here the backing OpenHouse catalog applies a policy patch itself.
   */
  @Test
  void configAdvertisesTheEndpointsOnTheV3LineOnly() {
    ConfigResponse config = config();
    assertEquals(OpenHouseRestAdapter.CONFIG_DEFAULTS, config.defaults());
    assertEquals(
        Collections.singletonMap("openhouse.capabilities", "policies"), config.overrides());
    List<String> advertised = endpoints(config);
    if (IcebergLine.current() == IcebergLine.V3) {
      assertEquals(OpenHouseRestAdapter.advertisedEndpoints().size(), advertised.size());
      assertEquals(
          new HashSet<>(OpenHouseRestAdapter.advertisedEndpoints()), new HashSet<>(advertised));
    } else {
      assertEquals(Collections.emptyList(), advertised);
    }
  }

  /**
   * On the v3 line every advertised endpoint is one of Iceberg's own {@code Endpoint} constants,
   * which is what the client compares against, so a misspelled path variable cannot pass. Iceberg
   * 1.5.2 has no {@code Endpoint} class, which is why the legacy line advertises nothing.
   */
  @Test
  void advertisedEndpointsAreIcebergEndpointsOnTheV3Line() throws Exception {
    if (IcebergLine.current() != IcebergLine.V3) {
      assertThrows(ClassNotFoundException.class, () -> Class.forName(ENDPOINT_CLASS));
      return;
    }
    Class<?> endpointClass = Class.forName(ENDPOINT_CLASS);
    Set<String> icebergEndpoints = new HashSet<>();
    for (Field field : endpointClass.getFields()) {
      if (Modifier.isStatic(field.getModifiers()) && field.getType() == endpointClass) {
        icebergEndpoints.add(field.get(null).toString());
      }
    }
    for (String endpoint : endpoints(config())) {
      assertTrue(icebergEndpoints.contains(endpoint), "not an Iceberg endpoint: " + endpoint);
    }
  }

  /** {@code ConfigResponse.endpoints()} as strings; empty on a line without it (1.5.2). */
  private static List<String> endpoints(ConfigResponse config) {
    java.lang.reflect.Method accessor;
    try {
      accessor = ConfigResponse.class.getMethod("endpoints");
    } catch (NoSuchMethodException e) {
      return Collections.emptyList();
    }
    List<String> endpoints = new ArrayList<>();
    try {
      for (Object endpoint : (List<?>) accessor.invoke(config)) {
        endpoints.add(endpoint.toString());
      }
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
    return endpoints;
  }
}
