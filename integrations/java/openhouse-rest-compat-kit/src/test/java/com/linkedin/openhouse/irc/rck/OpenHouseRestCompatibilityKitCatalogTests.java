package com.linkedin.openhouse.irc.rck;

import org.apache.iceberg.rest.RESTCompatibilityKitCatalogTests;

/**
 * The kit's table suite, unchanged, with the capability flags of Iceberg's {@code CatalogTests} set
 * to what OpenHouse is. Every test of the suite runs; this class only answers the flags. A flag the
 * kit also reads from an {@code rck.*} property (README of the fork's {@code open-api}) is answered
 * here instead, so that all of them are in one place; the answer is what that property would set.
 * The tests that still do not pass are listed in {@code expected-outcomes.txt}.
 */
public class OpenHouseRestCompatibilityKitCatalogTests extends RESTCompatibilityKitCatalogTests {

  /**
   * {@code rck.requires-namespace-create=false}. OpenHouse has no create-namespace: a namespace is
   * a database, and a database comes into being with its first table (or view) and is gone when its
   * last one is dropped. Creating a table in a namespace never created must therefore work.
   */
  @Override
  protected boolean requiresNamespaceCreate() {
    return false;
  }

  /**
   * Not exposed by the kit. OpenHouse databases carry no properties: load namespace answers an
   * empty map, and the update-properties route is not served.
   */
  @Override
  protected boolean supportsNamespaceProperties() {
    return false;
  }

  /** Not exposed by the kit; the default. OpenHouse namespaces have exactly one level. */
  @Override
  protected boolean supportsNestedNamespaces() {
    return false;
  }

  /**
   * Not exposed by the kit; the default. A database exists only while it holds a table or a view,
   * so an empty namespace cannot exist.
   */
  @Override
  protected boolean supportsEmptyNamespace() {
    return false;
  }

  /**
   * {@code rck.overrides-requested-location=true}. The server owns every table's location: a
   * location in a create request is not used, and the table lives where the server puts it.
   */
  @Override
  protected boolean overridesRequestedLocation() {
    return true;
  }

  /**
   * {@code rck.supports-names-with-dot=false}; the kit's default. OpenHouse database and table ids
   * are letters, digits and underscores.
   */
  @Override
  protected boolean supportsNamesWithDot() {
    return false;
  }

  /** The kit's own answer (false); OpenHouse ids cannot contain a slash either. */
  @Override
  protected boolean supportsNamesWithSlashes() {
    return false;
  }

  /**
   * {@code rck.supports-variant=false}; the kit's default. The kit's service creates format v3
   * tables when asked (its cluster switch is on), but refuses a variant column on them: variant
   * columns are off by default ({@code cluster.iceberg.format-v3.variant.enabled}, work item X5).
   */
  @Override
  protected boolean supportsVariant() {
    return false;
  }
}
