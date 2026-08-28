package com.linkedin.openhouse.tables.controller;

import com.linkedin.openhouse.tables.authorization.Privileges;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Pins the privilege each Iceberg REST views route is guarded by.
 *
 * <p>{@code @Secured} is enforced by a proxy at runtime, so a route that loses its annotation, or
 * has it silently retargeted at the wrong privilege, still compiles and still serves traffic. This
 * test freezes the mapping so that drift is a build failure rather than an authorization hole.
 *
 * <p>Runs as a plain JUnit 5 reflection test: no Spring context is loaded, so the mapping stays
 * pinned independently of how method security happens to be wired.
 */
public class IcebergRestViewsPrivilegeTest {

  @Test
  public void everyViewRouteDeclaresItsExpectedPrivilege() throws NoSuchMethodException {
    Map<Method, String> expected = new LinkedHashMap<>();
    expected.put(
        IcebergRestViewsController.class.getMethod(
            "listViews", String.class, String.class, Integer.class),
        Privileges.Privilege.LIST_VIEW);
    expected.put(
        IcebergRestViewsController.class.getMethod("createView", String.class, byte[].class),
        Privileges.Privilege.CREATE_VIEW);
    expected.put(
        IcebergRestViewsController.class.getMethod("loadView", String.class, String.class),
        Privileges.Privilege.SELECT);
    expected.put(
        IcebergRestViewsController.class.getMethod(
            "replaceView", String.class, String.class, byte[].class),
        Privileges.Privilege.UPDATE_VIEW_METADATA);
    expected.put(
        IcebergRestViewsController.class.getMethod("dropView", String.class, String.class),
        Privileges.Privilege.DELETE_VIEW);
    expected.put(
        IcebergRestViewsController.class.getMethod("viewExists", String.class, String.class),
        Privileges.Privilege.SELECT);

    for (Map.Entry<Method, String> route : expected.entrySet()) {
      Secured secured = route.getKey().getAnnotation(Secured.class);
      Assertions.assertNotNull(
          secured,
          "IcebergRestViewsController."
              + route.getKey().getName()
              + " must stay guarded by @Secured.");
      Assertions.assertArrayEquals(
          new String[] {route.getValue()},
          secured.value(),
          "IcebergRestViewsController."
              + route.getKey().getName()
              + " must require exactly the "
              + route.getValue()
              + " privilege.");
    }

    // /v1/config is deliberately authenticated-only: it is client bootstrap, served before any
    // resource privilege can apply, and it exposes no data beyond the endpoint list.
    Method config = IcebergRestViewsController.class.getMethod("getConfig");
    Assertions.assertNull(
        config.getAnnotation(Secured.class),
        "getConfig is authenticated-only by design; guard changes must be deliberate.");

    Set<String> mappedMethods = handlerMethodNames();
    mappedMethods.remove("getConfig");
    Assertions.assertEquals(
        expected.keySet().stream().map(Method::getName).collect(Collectors.toSet()),
        mappedMethods,
        "Every request-mapped view route must have its privilege pinned above.");
  }

  /**
   * Names of the methods Spring MVC would expose as routes. Derived from the mapping annotations
   * rather than a hard-coded list, so adding a route without pinning its privilege fails here.
   */
  private static Set<String> handlerMethodNames() {
    return Arrays.stream(IcebergRestViewsController.class.getDeclaredMethods())
        .filter(
            method ->
                Arrays.stream(method.getAnnotations())
                    .anyMatch(
                        annotation ->
                            annotation.annotationType().isAnnotationPresent(RequestMapping.class)
                                || annotation.annotationType().equals(RequestMapping.class)))
        .map(Method::getName)
        .collect(Collectors.toCollection(java.util.TreeSet::new));
  }
}
