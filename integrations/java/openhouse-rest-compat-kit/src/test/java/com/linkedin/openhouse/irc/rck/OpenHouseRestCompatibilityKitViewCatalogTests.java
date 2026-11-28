package com.linkedin.openhouse.irc.rck;

import org.apache.iceberg.rest.RESTCompatibilityKitViewCatalogTests;

/**
 * The kit's view suite, unchanged, with the capability flags of Iceberg's {@code ViewCatalogTests}
 * set to what OpenHouse is, as {@link OpenHouseRestCompatibilityKitCatalogTests} does for tables.
 */
public class OpenHouseRestCompatibilityKitViewCatalogTests
    extends RESTCompatibilityKitViewCatalogTests {

  /**
   * {@code rck.requires-namespace-create=false}: a view is created in a database that holds a table
   * or a view (the native view create's rule); a namespace record does not make one, so creating
   * the namespace first would not help.
   */
  @Override
  protected boolean requiresNamespaceCreate() {
    return false;
  }

  /** {@code rck.overrides-requested-location=true}: the server owns every view's location. */
  @Override
  protected boolean overridesRequestedLocation() {
    return true;
  }

  /**
   * Not exposed by the kit; the default. The empty namespace (no level) is not an OpenHouse
   * namespace.
   */
  @Override
  protected boolean supportsEmptyNamespace() {
    return false;
  }
}
