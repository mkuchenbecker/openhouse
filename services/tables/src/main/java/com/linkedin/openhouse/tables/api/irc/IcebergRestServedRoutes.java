package com.linkedin.openhouse.tables.api.irc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * What the IRC routes registered in this server serve, read from Spring's handler mappings, so that
 * what the config response advertises cannot drift from what is registered: the scan planning and
 * credentials routes register only where their Iceberg API exists (the 1.12 line), so the list
 * differs between Iceberg lines.
 *
 * <ul>
 *   <li>{@link #endpoints()}: the spec routes served, in the spec's format ({@code "<METHOD>
 *       /v1/{prefix}/<path>"}, the strings of Iceberg's {@code Endpoint}): every method and pattern
 *       an IRC controller maps under {@code /iceberg/v1}, except the config route (not an endpoint
 *       in the spec), the OpenHouse routes ({@code .../openhouse/...}, not spec routes) and routes
 *       marked {@link IcebergRestRefusedRoute}. The catch-all maps no method and is not a route.
 *       Path variables already carry the spec's names ({@code namespace}, {@code table}, {@code
 *       view}).
 *   <li>{@link #capabilities()}: the {@link IcebergRestCapability} names on the routes mapped.
 * </ul>
 *
 * Read once, on first use, when every controller is registered. Spring types only: this bean is
 * created on every Iceberg line, including LinkedIn's 1.5.2, which lacks {@code Endpoint}.
 */
@Component
public class IcebergRestServedRoutes {

  private static final String V1 = IcebergRestCatalogController.BASE_PATH + "/v1";

  private static final String CONFIG = V1 + "/config";

  private static final String OPENHOUSE_SEGMENT = "/openhouse/";

  /** The spec's spelling of the routes' base: a client's prefix, empty on this server. */
  private static final String SPEC_V1 = "/v1/{prefix}";

  private static final List<RequestMethod> METHOD_ORDER =
      Arrays.asList(
          RequestMethod.GET, RequestMethod.HEAD, RequestMethod.POST, RequestMethod.DELETE);

  @Autowired private ApplicationContext context;

  private volatile Served served;

  /** The spec routes served, sorted by path, then GET, HEAD, POST, DELETE. */
  public List<String> endpoints() {
    return served().endpoints;
  }

  /** The OpenHouse capabilities served, in {@link IcebergRestCapability#ORDER}. */
  public List<String> capabilities() {
    return served().capabilities;
  }

  private Served served() {
    Served current = served;
    if (current == null) {
      synchronized (this) {
        if (served == null) {
          served =
              read(
                  context
                      .getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class)
                      .getHandlerMethods());
        }
        current = served;
      }
    }
    return current;
  }

  static Served read(Map<RequestMappingInfo, HandlerMethod> mappings) {
    String ircPackage = IcebergRestServedRoutes.class.getPackage().getName();
    Set<String[]> endpoints =
        new TreeSet<>(
            Comparator.<String[], String>comparing(e -> e[1])
                .thenComparing(e -> METHOD_ORDER.indexOf(RequestMethod.valueOf(e[0]))));
    Set<String> capabilities = new TreeSet<>();
    mappings.forEach(
        (info, method) -> {
          if (!ircPackage.equals(method.getBeanType().getPackage().getName())) {
            return;
          }
          IcebergRestCapability capability =
              method.getMethodAnnotation(IcebergRestCapability.class);
          if (capability != null) {
            capabilities.add(capability.value());
          }
          if (method.hasMethodAnnotation(IcebergRestRefusedRoute.class)) {
            return;
          }
          for (String pattern : info.getPatternValues()) {
            if (!pattern.startsWith(V1 + "/")
                || pattern.equals(CONFIG)
                || pattern.contains(OPENHOUSE_SEGMENT)) {
              continue;
            }
            for (RequestMethod httpMethod : info.getMethodsCondition().getMethods()) {
              endpoints.add(
                  new String[] {httpMethod.name(), SPEC_V1 + pattern.substring(V1.length())});
            }
          }
        });
    List<String> endpointList = new ArrayList<>();
    endpoints.forEach(e -> endpointList.add(e[0] + " " + e[1]));
    List<String> capabilityList = new ArrayList<>();
    for (String name : IcebergRestCapability.ORDER) {
      if (capabilities.remove(name)) {
        capabilityList.add(name);
      }
    }
    capabilityList.addAll(capabilities);
    return new Served(
        Collections.unmodifiableList(endpointList), Collections.unmodifiableList(capabilityList));
  }

  static final class Served {
    final List<String> endpoints;
    final List<String> capabilities;

    Served(List<String> endpoints, List<String> capabilities) {
      this.endpoints = endpoints;
      this.capabilities = capabilities;
    }
  }
}
