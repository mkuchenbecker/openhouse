package com.linkedin.openhouse.tables.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * The start-up warning of scan planning set on with the Iceberg REST routes off: created in that
 * case only, with the two properties read as the routes' own conditions read them.
 */
class IcebergRestIgnoredSettingsTest {

  private static int created(String routes, String planning) {
    Map<String, Object> properties = new HashMap<>();
    if (routes != null) {
      properties.put(ClusterProperties.IRC_ENABLED, routes);
    }
    if (planning != null) {
      properties.put(ClusterProperties.IRC_SCAN_PLANNING_ENABLED, planning);
    }
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(new MapPropertySource("ignored-settings-test", properties));
      context.register(IcebergRestIgnoredSettings.class);
      context.refresh();
      return context.getBeansOfType(IcebergRestIgnoredSettings.class).size();
    }
  }

  @Test
  void scanPlanningOnWithTheRoutesOffIsWarnedOf() {
    assertEquals(1, created(null, "true"));
    assertEquals(1, created("false", "true"));
    assertEquals(1, created("false", "TRUE"));
  }

  @Test
  void nothingIsWarnedOfOtherwise() {
    assertEquals(0, created(null, null));
    assertEquals(0, created("true", "true"));
    assertEquals(0, created("true", null));
    assertEquals(0, created(null, "false"));
  }

  @Test
  void theWarningNamesBothProperties() {
    assertEquals(
        "cluster.tables.irc.scan-planning.enabled is true but cluster.tables.irc.enabled is not:"
            + " the Iceberg REST routes are off, and scan planning with them",
        IcebergRestIgnoredSettings.MESSAGE);
  }
}
