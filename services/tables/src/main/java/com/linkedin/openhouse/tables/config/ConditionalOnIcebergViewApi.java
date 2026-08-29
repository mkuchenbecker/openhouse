package com.linkedin.openhouse.tables.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;

/**
 * Registers a bean only where Iceberg has a view API.
 *
 * <h2>What this is for</h2>
 *
 * <p>The views surface is written against {@code org.apache.iceberg.view}, which arrived in Iceberg
 * 1.4. The tables application is also booted, by the iceberg-1.2 test fixture, against Iceberg 1.2
 * — that is how every Spark 3.1 integration test gets a server to talk to. There, those types are
 * simply absent.
 *
 * <p>Absent types are not a runtime inconvenience that shows up when a view route is called. Spring
 * introspects a component's declared methods while creating it, and its supertypes while parsing
 * it, so a component whose signatures name a missing class fails <b>context startup</b> — the whole
 * application, not the views routes. That is what this annotation prevents: the condition is
 * evaluated from annotation metadata during the classpath scan, before the class is ever loaded, so
 * an annotated component on an Iceberg-1.2 classpath is never introspected at all.
 *
 * <h2>What it does not change</h2>
 *
 * <p>On any Iceberg that has views — every deployment, and the 1.5 fixture — the condition is
 * satisfied and the annotated bean is registered exactly as it would be without it. This is not the
 * views feature switch; that is {@code cluster.tables.views.enabled}, which decides whether views
 * can be stored. This decides only whether the classes can exist.
 *
 * <p>The class named here is deliberately {@code ViewMetadata} rather than something more central:
 * it is the type the service contract is written in, so if it is present the rest of the view API
 * is too, and naming it by string keeps this annotation loadable where it is not.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnClass(name = "org.apache.iceberg.view.ViewMetadata")
public @interface ConditionalOnIcebergViewApi {}
