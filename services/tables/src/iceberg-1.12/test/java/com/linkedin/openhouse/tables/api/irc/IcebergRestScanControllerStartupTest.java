package com.linkedin.openhouse.tables.api.irc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * The plan route's start-up check in a real Spring context: the cluster properties bound from the
 * environment as the service binds them, the route's registration conditions, and its start-up
 * callback. With planning on, a bound that is not positive stops the context from starting, naming
 * the property; with planning off, or with planning on and the Iceberg REST routes off, the route
 * is not created, so no bound is checked. Only the route's collaborator, the read handler, is a
 * mock: the context has no Spring Boot application, no database and no component scan.
 */
class IcebergRestScanControllerStartupTest {

  private static final String ENABLED = ClusterProperties.IRC_SCAN_PLANNING_ENABLED;

  private static AnnotationConfigApplicationContext context(Map<String, Object> properties) {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(new MapPropertySource("startup-test", properties));
    context.getBeanFactory().registerSingleton("readHandler", mock(IcebergRestApiHandler.class));
    context.register(ClusterProperties.class, IcebergRestScanController.class);
    return context;
  }

  private static Map<String, Object> planningOnWith(String property, Object value) {
    Map<String, Object> properties = new HashMap<>();
    properties.put(ClusterProperties.IRC_ENABLED, "true");
    properties.put(ENABLED, "true");
    if (property != null) {
      properties.put(property, value);
    }
    return properties;
  }

  private static IllegalStateException refusal(Throwable thrown) {
    for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
      if (cause instanceof IllegalStateException) {
        return (IllegalStateException) cause;
      }
    }
    throw new AssertionError("no IllegalStateException in the cause chain", thrown);
  }

  @Test
  void withPlanningOnAndTheDefaultBoundsTheRouteStarts() {
    try (AnnotationConfigApplicationContext context = context(planningOnWith(null, null))) {
      context.refresh();
      assertEquals(1, context.getBeansOfType(IcebergRestScanController.class).size());
    }
  }

  @Test
  void withPlanningOnABoundThatIsNotPositiveStopsTheStart() {
    String[][] cases = {
      {IcebergRestScanController.MAX_FILE_SCAN_TASKS, "0"},
      {IcebergRestScanController.MAX_CONCURRENT_PLANS, "0"},
      {IcebergRestScanController.MAX_CONCURRENT_PLANS, "-1"},
      {IcebergRestScanController.TIMEOUT_MS, "0"},
    };
    for (String[] bound : cases) {
      try (AnnotationConfigApplicationContext context =
          context(planningOnWith(bound[0], bound[1]))) {
        BeanCreationException thrown =
            assertThrows(BeanCreationException.class, context::refresh, bound[0]);
        assertEquals(
            String.format("%s is %s: with %s on it must be positive", bound[0], bound[1], ENABLED),
            refusal(thrown).getMessage());
      }
    }
  }

  @Test
  void withPlanningOffNoBoundIsChecked() {
    Map<String, Object> properties = new HashMap<>();
    properties.put(IcebergRestScanController.MAX_CONCURRENT_PLANS, "0");
    try (AnnotationConfigApplicationContext context = context(properties)) {
      context.refresh();
      assertTrue(context.getBeansOfType(IcebergRestScanController.class).isEmpty());
    }
  }

  /** Scan planning is one of the Iceberg REST routes: with those off, its switch has no effect. */
  @Test
  void withPlanningOnAndTheIcebergRestRoutesOffTheRouteIsNotCreatedAndNoBoundIsChecked() {
    for (String routes : new String[] {null, "false"}) {
      Map<String, Object> properties = planningOnWith(IcebergRestScanController.TIMEOUT_MS, "0");
      if (routes == null) {
        properties.remove(ClusterProperties.IRC_ENABLED);
      } else {
        properties.put(ClusterProperties.IRC_ENABLED, routes);
      }
      try (AnnotationConfigApplicationContext context = context(properties)) {
        context.refresh();
        assertTrue(
            context.getBeansOfType(IcebergRestScanController.class).isEmpty(),
            String.valueOf(routes));
      }
    }
  }
}
