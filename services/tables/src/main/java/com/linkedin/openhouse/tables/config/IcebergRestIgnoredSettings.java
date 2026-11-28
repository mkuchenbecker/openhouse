package com.linkedin.openhouse.tables.config;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Says at start-up that Iceberg REST scan planning ({@link
 * ClusterProperties#IRC_SCAN_PLANNING_ENABLED}) is set on while the Iceberg REST routes ({@link
 * ClusterProperties#IRC_ENABLED}) are off, so it has no effect: nothing is served under {@code
 * /iceberg}, its bounds are not checked, and the service starts as it would without it. Created
 * only in that case.
 *
 * <p>A warning and not a refusal to start: with the routes off the service answers as it does
 * without any Iceberg REST setting, and a setting it ignores must not stop it from starting.
 */
@Slf4j
@Configuration
@Conditional(IcebergRestIgnoredSettings.ScanPlanningWithoutRoutes.class)
public class IcebergRestIgnoredSettings {

  static final String MESSAGE =
      ClusterProperties.IRC_SCAN_PLANNING_ENABLED
          + " is true but "
          + ClusterProperties.IRC_ENABLED
          + " is not: the Iceberg REST routes are off, and scan planning with them";

  public IcebergRestIgnoredSettings() {
    log.warn(MESSAGE);
  }

  /**
   * Scan planning on and the routes off, each read as {@code @ConditionalOnProperty(havingValue =
   * "true")} reads it, the condition the routes' beans are created under.
   */
  static final class ScanPlanningWithoutRoutes extends SpringBootCondition {
    @Override
    public ConditionOutcome getMatchOutcome(
        ConditionContext context, AnnotatedTypeMetadata metadata) {
      Environment environment = context.getEnvironment();
      boolean routes =
          "true".equalsIgnoreCase(environment.getProperty(ClusterProperties.IRC_ENABLED));
      boolean planning =
          "true"
              .equalsIgnoreCase(
                  environment.getProperty(ClusterProperties.IRC_SCAN_PLANNING_ENABLED));
      return planning && !routes
          ? ConditionOutcome.match(MESSAGE)
          : ConditionOutcome.noMatch("scan planning off, or the Iceberg REST routes on");
    }
  }
}
