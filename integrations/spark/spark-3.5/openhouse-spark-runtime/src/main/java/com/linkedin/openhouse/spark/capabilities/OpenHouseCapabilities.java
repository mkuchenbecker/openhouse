package com.linkedin.openhouse.spark.capabilities;

import com.linkedin.openhouse.capabilities.CapabilityNames;
import com.linkedin.openhouse.capabilities.GrantsCapability;
import com.linkedin.openhouse.capabilities.UnlockCapability;
import com.linkedin.openhouse.capabilities.rest.RestCapabilityClient;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import org.apache.iceberg.CachingCatalog;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.common.DynFields;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.spark.SparkCatalog;
import org.apache.iceberg.spark.SparkSessionCatalog;
import org.apache.iceberg.spark.source.HasIcebergCatalog;
import org.apache.spark.sql.connector.catalog.CatalogPlugin;

/**
 * The one place the OpenHouse SQL extensions find an OpenHouse feature: given the Spark catalog a
 * statement targets, does it offer capability X, and through what handle.
 *
 * <ol>
 *   <li>The legacy {@code OpenHouseCatalog} and the in-process {@code OpenHouseRestCatalog}: the
 *       capabilities come from the interfaces they implement ({@link JavaClientCapabilities}),
 *       checked before anything else, so these paths behave exactly as before capabilities existed.
 *       A catalog implementing {@link GrantsCapability} or {@link UnlockCapability} itself is on
 *       this path too.
 *   <li>Iceberg's own {@code RESTCatalog}: the server's advertised names, read from the catalog's
 *       properties ({@value CapabilityNames#ADVERTISED_PROPERTY}). Policies need nothing beyond the
 *       name. Grants and unlock are offered where advertised, through a {@link
 *       RestCapabilityClient} built from the catalog's own name and merged properties, so it
 *       reaches the same server with the same authentication.
 *   <li>Any other Iceberg catalog: no grants or unlock; policies are not refused here.
 *   <li>A catalog that is not Iceberg's {@link SparkCatalog} or {@link SparkSessionCatalog}: the
 *       same as any other Iceberg catalog. The statements are planned only for those two classes,
 *       so on such a catalog they fail at planning before they get here; a caller that asks anyway
 *       is not told that a server does not advertise policies, since there is no such server.
 * </ol>
 *
 * <p>Policies are refused only on Iceberg's {@code RESTCatalog} whose server does not advertise
 * them. Everywhere else a policy statement commits only to a table that loads as an Iceberg {@code
 * SparkTable} carrying {@code openhouse.tableId}, and refuses any other table itself.
 *
 * <p>A capability that is not offered fails with {@link UnsupportedOperationException}; for grants
 * and unlock the message is the one the statements have always given.
 */
public final class OpenHouseCapabilities {

  private OpenHouseCapabilities() {}

  /** What {@code catalog} offers. */
  public static CatalogCapabilities resolve(CatalogPlugin catalog) {
    Catalog iceberg = icebergCatalog(catalog);
    if (iceberg == null) {
      return new CatalogCapabilities(
          CatalogCapabilities.Path.NOT_ICEBERG,
          Collections.emptySet(),
          true,
          Optional.empty(),
          Optional.empty());
    }
    Optional<GrantsCapability> grants = JavaClientCapabilities.grants(iceberg);
    if (!grants.isPresent() && iceberg instanceof GrantsCapability) {
      grants = Optional.of((GrantsCapability) iceberg);
    }
    Optional<UnlockCapability> unlock = JavaClientCapabilities.unlock(iceberg);
    if (!unlock.isPresent() && iceberg instanceof UnlockCapability) {
      unlock = Optional.of((UnlockCapability) iceberg);
    }
    if (grants.isPresent() || unlock.isPresent()) {
      return new CatalogCapabilities(
          CatalogCapabilities.Path.OPENHOUSE_CATALOG, Collections.emptySet(), true, grants, unlock);
    }
    if (iceberg instanceof RESTCatalog) {
      RESTCatalog rest = (RESTCatalog) iceberg;
      Set<String> advertised =
          CapabilityNames.parse(rest.properties().get(CapabilityNames.ADVERTISED_PROPERTY));
      // Grants and unlock over REST go through the capability client. It holds no connection or
      // token between calls, so building one per resolution is cheap.
      RestCapabilityClient client = new RestCapabilityClient(rest.name(), rest.properties());
      Optional<GrantsCapability> restGrants =
          advertised.contains(CapabilityNames.GRANTS) ? Optional.of(client) : Optional.empty();
      Optional<UnlockCapability> restUnlock =
          advertised.contains(CapabilityNames.UNLOCK) ? Optional.of(client) : Optional.empty();
      return new CatalogCapabilities(
          CatalogCapabilities.Path.ICEBERG_REST,
          advertised,
          advertised.contains(CapabilityNames.POLICIES),
          restGrants,
          restUnlock);
    }
    return new CatalogCapabilities(
        CatalogCapabilities.Path.OTHER_ICEBERG,
        Collections.emptySet(),
        true,
        Optional.empty(),
        Optional.empty());
  }

  /**
   * The grants handle of {@code catalog}.
   *
   * @throws UnsupportedOperationException {@code Catalog '<name>' does not support Grant Revoke
   *     Statements}
   */
  public static GrantsCapability requireGrants(CatalogPlugin catalog) {
    return resolve(catalog)
        .grants()
        .orElseThrow(
            () ->
                new UnsupportedOperationException(
                    "Catalog '" + catalog.name() + "' does not support Grant Revoke Statements"));
  }

  /**
   * The unlock handle of {@code catalog}.
   *
   * @throws UnsupportedOperationException {@code Catalog '<name>' does not support UNLOCK}
   */
  public static UnlockCapability requireUnlock(CatalogPlugin catalog) {
    return resolve(catalog)
        .unlock()
        .orElseThrow(
            () ->
                new UnsupportedOperationException(
                    "Catalog '" + catalog.name() + "' does not support UNLOCK"));
  }

  /**
   * Fails unless {@code catalog} offers policies. Called by every policy statement before it
   * commits, so that on an Iceberg REST server that does not advertise policies the statement fails
   * instead of storing the patch as an ordinary property.
   *
   * @throws UnsupportedOperationException if policies are not offered
   */
  public static void requirePolicies(CatalogPlugin catalog) {
    CatalogCapabilities capabilities = resolve(catalog);
    if (!capabilities.offers(CapabilityNames.POLICIES)) {
      throw new UnsupportedOperationException(
          "Catalog '"
              + catalog.name()
              + "' does not support OpenHouse policies: its server does not advertise '"
              + CapabilityNames.POLICIES
              + "' in "
              + CapabilityNames.ADVERTISED_PROPERTY
              + " (advertised: "
              + capabilities.advertised()
              + ")");
    }
  }

  /**
   * The Iceberg catalog behind a Spark catalog, or null if it is not Iceberg's {@link SparkCatalog}
   * or {@link SparkSessionCatalog}.
   *
   * <p>It uses the public {@link HasIcebergCatalog#icebergCatalog()} accessor, which both classes
   * have on Iceberg 1.5 and later (including the Spark 4.2 line, where the {@code cacheEnabled}
   * field the old reflection read no longer exists). The only field read by reflection is the
   * private {@code catalog} of {@link CachingCatalog}, which Iceberg wraps around the catalog when
   * caching is enabled.
   */
  public static Catalog icebergCatalog(CatalogPlugin catalog) {
    if (!(catalog instanceof SparkCatalog || catalog instanceof SparkSessionCatalog)) {
      return null;
    }
    Catalog iceberg = ((HasIcebergCatalog) catalog).icebergCatalog();
    if (iceberg instanceof CachingCatalog) {
      iceberg =
          DynFields.builder()
              .hiddenImpl(CachingCatalog.class, "catalog")
              .<Catalog>build(iceberg)
              .get();
    }
    return iceberg;
  }
}
