package com.linkedin.openhouse.tables.api.irc;

import com.linkedin.openhouse.capabilities.CapabilityNames;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an IRC route that serves an OpenHouse feature (a server capability). The config response
 * advertises, in {@value IcebergRestApiHandler#CAPABILITIES}, the names on the routes that are
 * registered, so a name is advertised exactly where its routes exist ({@link
 * IcebergRestServedRoutes}). The names are the capabilities module's ({@code CapabilityNames}, a
 * compile-only dependency whose constants javac inlines).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface IcebergRestCapability {

  /** A policy patch ({@code updated.openhouse.policy}) in a create or commit is applied. */
  String POLICIES = CapabilityNames.POLICIES;

  /** GRANT, REVOKE and SHOW GRANTS, on tables and databases. */
  String GRANTS = CapabilityNames.GRANTS;

  /** UNLOCK, with or without a reason. */
  String UNLOCK = CapabilityNames.UNLOCK;

  /** The order names are advertised in. */
  String[] ORDER = {POLICIES, GRANTS, UNLOCK};

  String value();
}
