package com.linkedin.openhouse.tables.api.irc;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * The switch of the Iceberg REST Catalog routes: a bean so annotated is created only with {@link
 * ClusterProperties#IRC_ENABLED} set to {@code true}. Every bean that exists only for the routes
 * under {@code /iceberg} carries it (the controllers, their handlers, their error advice, and what
 * only they call), so that with the property unset none of them is created and nothing is mapped
 * there.
 *
 * <p>The routes' beans are found by component scanning, which a condition on one configuration
 * class could not hold back; each carries the condition instead. A bean with a switch of its own
 * (scan planning) carries both, and is created only with both on.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ConditionalOnProperty(name = ClusterProperties.IRC_ENABLED, havingValue = "true")
public @interface ConditionalOnIcebergRest {}
