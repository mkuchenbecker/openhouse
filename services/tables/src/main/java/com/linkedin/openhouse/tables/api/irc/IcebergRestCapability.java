package com.linkedin.openhouse.tables.api.irc;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an IRC route that serves an OpenHouse feature (a server capability). The config response
 * advertises, in {@value IcebergRestApiHandler#CAPABILITIES}, the names on the routes that are
 * registered, so a name is advertised exactly where its routes exist ({@link
 * IcebergRestServedRoutes}).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface IcebergRestCapability {

  /** A policy patch ({@code updated.openhouse.policy}) in a create or commit is applied. */
  String POLICIES = "policies";

  /** GRANT, REVOKE and SHOW GRANTS, on tables and databases. */
  String GRANTS = "grants";

  /** UNLOCK, with or without a reason. */
  String UNLOCK = "unlock";

  /** The order names are advertised in. */
  String[] ORDER = {POLICIES, GRANTS, UNLOCK};

  String value();
}
