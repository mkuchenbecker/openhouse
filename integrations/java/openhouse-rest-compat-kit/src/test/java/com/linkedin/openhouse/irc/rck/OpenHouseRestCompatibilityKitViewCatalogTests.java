package com.linkedin.openhouse.irc.rck;

import org.apache.iceberg.rest.RESTCompatibilityKitViewCatalogTests;

/**
 * The kit's view suite, unchanged, with the capability flags of Iceberg's {@code ViewCatalogTests}
 * set to what OpenHouse is, as {@link OpenHouseRestCompatibilityKitCatalogTests} does for tables.
 */
public class OpenHouseRestCompatibilityKitViewCatalogTests
    extends RESTCompatibilityKitViewCatalogTests {

  /**
   * {@code rck.requires-namespace-create=false}: a database comes into being with its first view.
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

  /** Not exposed by the kit; the default. A database exists only while it holds a table or view. */
  @Override
  protected boolean supportsEmptyNamespace() {
    return false;
  }
}
