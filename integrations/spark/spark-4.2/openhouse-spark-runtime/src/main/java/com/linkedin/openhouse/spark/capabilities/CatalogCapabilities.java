package com.linkedin.openhouse.spark.capabilities;

import com.linkedin.openhouse.capabilities.CapabilityNames;
import com.linkedin.openhouse.capabilities.GrantsCapability;
import com.linkedin.openhouse.capabilities.UnlockCapability;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * What one Spark catalog offers, as {@link OpenHouseCapabilities#resolve} found it: which path the
 * catalog is on, the capabilities its server advertises (on the Iceberg REST path), and a handle
 * for each capability that needs one.
 */
public final class CatalogCapabilities {

  /** How the capabilities were found. */
  public enum Path {
    /**
     * Not Iceberg's {@code SparkCatalog} or {@code SparkSessionCatalog}: no grants or unlock, and
     * policies are not refused here. The statements are not planned for such a catalog at all.
     */
    NOT_ICEBERG,
    /**
     * An OpenHouse catalog class (on Spark 3.5 the legacy {@code OpenHouseCatalog} and the
     * in-process {@code OpenHouseRestCatalog}; on both lines a catalog implementing the capability
     * interfaces): each capability is offered through the interface the catalog implements;
     * policies are translated by the catalog.
     */
    OPENHOUSE_CATALOG,
    /**
     * Iceberg's own {@code RESTCatalog}: the capabilities are the names the server advertises in
     * {@value CapabilityNames#ADVERTISED_PROPERTY}.
     */
    ICEBERG_REST,
    /**
     * Any other Iceberg catalog: no grants or unlock; policy statements are left to their own check
     * of the table, as before capabilities existed.
     */
    OTHER_ICEBERG
  }

  private final Path path;
  private final Set<String> advertised;
  private final Set<String> offered;
  private final Optional<GrantsCapability> grants;
  private final Optional<UnlockCapability> unlock;

  CatalogCapabilities(
      Path path,
      Set<String> advertised,
      boolean policies,
      Optional<GrantsCapability> grants,
      Optional<UnlockCapability> unlock) {
    this.path = path;
    this.advertised = advertised;
    this.grants = grants;
    this.unlock = unlock;
    Set<String> names = new LinkedHashSet<>();
    if (policies) {
      names.add(CapabilityNames.POLICIES);
    }
    if (grants.isPresent()) {
      names.add(CapabilityNames.GRANTS);
    }
    if (unlock.isPresent()) {
      names.add(CapabilityNames.UNLOCK);
    }
    this.offered = Collections.unmodifiableSet(names);
  }

  public Path path() {
    return path;
  }

  /**
   * Whether the catalog is Iceberg's own {@code RESTCatalog} on an OpenHouse server: the path is
   * {@link Path#ICEBERG_REST} and the server advertises at least one OpenHouse capability ({@link
   * #advertised} is not empty). Its tables are OpenHouse tables, but nothing in the engine does
   * what the OpenHouse catalog class did there, so the extensions do it for such a catalog and for
   * no other. A catalog on {@link Path#OPENHOUSE_CATALOG} is an OpenHouse catalog class, with its
   * own behavior.
   */
  public boolean isOpenHouseRest() {
    return path == Path.ICEBERG_REST && !advertised.isEmpty();
  }

  /**
   * The names the catalog's server advertises; empty except on {@link Path#ICEBERG_REST}. A name
   * here is not necessarily usable: see {@link #offers}.
   */
  public Set<String> advertised() {
    return advertised;
  }

  /** The capabilities a statement can use on this catalog. */
  public Set<String> offered() {
    return offered;
  }

  /** Whether a statement can use capability {@code name} on this catalog. */
  public boolean offers(String name) {
    return offered.contains(name);
  }

  /** The handle for {@link CapabilityNames#GRANTS}, if offered. */
  public Optional<GrantsCapability> grants() {
    return grants;
  }

  /** The handle for {@link CapabilityNames#UNLOCK}, if offered. */
  public Optional<UnlockCapability> unlock() {
    return unlock;
  }

  @Override
  public String toString() {
    return "CatalogCapabilities("
        + path
        + ", offered="
        + offered
        + ", advertised="
        + advertised
        + ")";
  }
}
