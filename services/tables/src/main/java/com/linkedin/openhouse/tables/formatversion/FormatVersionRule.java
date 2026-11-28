package com.linkedin.openhouse.tables.formatversion;

import com.linkedin.openhouse.cluster.configs.ClusterProperties;
import com.linkedin.openhouse.common.exception.CommitRejectedException;
import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import com.linkedin.openhouse.tables.model.TableDto;
import java.util.Map;
import java.util.OptionalInt;
import org.apache.iceberg.TableProperties;

/**
 * The one rule for a table's Iceberg format version.
 *
 * <p>A cluster creates tables at {@code cluster.iceberg.format-version}. Format version 3 is a
 * per-table choice behind the cluster switch {@code cluster.iceberg.format-v3.enabled}: a table is
 * v3 only when its create, or a replace of it, asks for format version 3, or when an update asks to
 * upgrade it. Nothing else changes a table's format version, and it is never lowered.
 *
 * <p>The rule reads one thing from a write: the format version it asks for, which both APIs spell
 * as the table property {@value #PROPERTY} by the time the write reaches the repository (the native
 * API carries it in {@code tableProperties}; the Iceberg REST routes translate an {@code
 * upgrade-format-version} update into it). The upgrade itself is Iceberg's: the property reaches
 * {@code TableMetadata.replaceProperties}, which upgrades.
 *
 * <p>With the switch off, which is the default, every write answers as it did before this service
 * knew format v3, when LinkedIn's Iceberg 1.5.2 could build nothing above v2:
 *
 * <ul>
 *   <li>A create (and a replace) is stored at the cluster's version whatever it asks for: the
 *       property is overwritten.
 *   <li>An update is left to Iceberg, which upgrades a table to v2 and refuses a version it cannot
 *       build. The Iceberg 1.12 line can build v3 and v4, so there an update that asks for a
 *       version above 2 is given the refusal Iceberg 1.5.2 gave it ({@link #checkUpdate}).
 *   <li>{@code cluster.iceberg.format-version} may not be above 2 ({@link #requireClusterVersion}):
 *       on Iceberg 1.5.2 such a cluster could create no table at all.
 * </ul>
 *
 * <p>With the switch on:
 *
 * <ul>
 *   <li>A write that asks for a version above the one the table has, or on create above the
 *       cluster's, is refused unless it asks for 3 and v3 is enabled.
 *   <li>A write that asks for a version below a v3 table's is refused.
 *   <li>A write that asks for nothing, for the version the table has, or for a version at or below
 *       the cluster's on a table that is not v3, is not touched by this rule and behaves as it
 *       always has (a create overwrites the property with the cluster's version).
 * </ul>
 *
 * <p>{@link #checkCommitted} states the result as an invariant, which the catalog checks before
 * anything is written on every commit that leaves or finds a table at v3 or above, and with the
 * switch on on every commit.
 */
public class FormatVersionRule {

  /** Iceberg's reserved table property that asks for a format version. */
  public static final String PROPERTY = TableProperties.FORMAT_VERSION;

  /** The token every refusal of this rule starts with. */
  public static final String REFUSED = "FORMAT_VERSION_REFUSED";

  /** The cluster property that allows v3 tables. */
  public static final String SWITCH = "cluster.iceberg.format-v3.enabled";

  private final ClusterProperties clusterProperties;

  public FormatVersionRule(ClusterProperties clusterProperties) {
    this.clusterProperties = clusterProperties;
  }

  /**
   * The highest format version LinkedIn's Iceberg 1.5.2 builds, and so the highest a table reaches
   * with the switch off.
   */
  static final int HIGHEST_WITHOUT_V3 = 2;

  /** Whether v3 tables may be created or upgraded to: the switch is on and Iceberg builds v3. */
  public boolean v3Enabled() {
    return switchOn() && IcebergV3.supported();
  }

  /** Whether the cluster switch {@value #SWITCH} is on, whatever this Iceberg can build. */
  public boolean switchOn() {
    return clusterProperties.isClusterIcebergFormatV3Enabled();
  }

  /**
   * Refuses a cluster whose {@code cluster.iceberg.format-version} is above 2 while the switch is
   * off: it would make every new table v3 with the switch that is meant to allow v3 tables off. On
   * LinkedIn's Iceberg 1.5.2, which builds nothing above v2, such a cluster could create no table.
   *
   * @throws IllegalStateException naming both properties
   */
  public void requireClusterVersion() {
    if (!switchOn() && clusterVersion() > HIGHEST_WITHOUT_V3) {
      throw new IllegalStateException(
          String.format(
              "cluster.iceberg.format-version is %d, and %s is false: tables above format version"
                  + " %d need %s=true. Set cluster.iceberg.format-version to %d or lower, or turn"
                  + " format version 3 on.",
              clusterVersion(), SWITCH, HIGHEST_WITHOUT_V3, SWITCH, HIGHEST_WITHOUT_V3));
    }
  }

  /** The cluster property that allows variant columns on v3 tables. */
  public static final String VARIANT_SWITCH = "cluster.iceberg.format-v3.variant.enabled";

  /** Whether a format v3 table may get a variant column (work item X5); off by default. */
  public boolean variantEnabled() {
    return clusterProperties.isClusterIcebergFormatV3VariantEnabled();
  }

  /** The format version the cluster creates tables at. */
  public int clusterVersion() {
    return clusterProperties.getClusterIcebergFormatVersion();
  }

  /** The version {@code properties} ask for; empty when they ask for none or it is no number. */
  public static OptionalInt requested(Map<String, String> properties) {
    String value = properties == null ? null : properties.get(PROPERTY);
    if (value == null) {
      return OptionalInt.empty();
    }
    try {
      return OptionalInt.of(Integer.parseInt(value.trim()));
    } catch (NumberFormatException e) {
      return OptionalInt.empty();
    }
  }

  /**
   * The format version a create stores: 3 when the create asks for it and v3 is enabled, the
   * cluster's otherwise. With the switch off, the cluster's whatever the create asks for.
   *
   * @param operation how a refusal names the write: {@code Create}, or {@code Replace} for a
   *     replace, whose properties are computed as a create's before {@link #versionOfReplace}
   * @throws RequestValidationFailureException if the switch is on and the create asks for a version
   *     above the cluster's that it cannot have
   */
  public int versionOfCreate(String operation, String table, Map<String, String> properties) {
    OptionalInt asked = requested(properties);
    if (!switchOn() || !asked.isPresent() || asked.getAsInt() <= clusterVersion()) {
      return clusterVersion();
    }
    requireV3(operation, table, asked.getAsInt());
    return IcebergV3.VERSION;
  }

  /**
   * Whether a write of {@code incoming} on the stored table {@code stored} ({@code null} for a
   * create) is a write to a format v3 table: the table is v3, or the write asks for v3 while the
   * switch is on. Read before the write is checked, by callers that treat column defaults
   * differently on v3; a write that asks for v3 and may not have it is refused later, whatever they
   * did. With the switch off a write that asks for v3 is not one: a create is stored at the
   * cluster's version and an update is refused, so it is treated as every write was before.
   *
   * @param clusterProperties the cluster's configuration; {@code null} reads as the switch off
   */
  public static boolean writesFormatV3(
      ClusterProperties clusterProperties, TableDto stored, TableDto incoming) {
    if (stored != null && stored.getFormatVersion() >= IcebergV3.VERSION) {
      return true;
    }
    if (clusterProperties == null || !clusterProperties.isClusterIcebergFormatV3Enabled()) {
      return false;
    }
    OptionalInt asked = requested(incoming == null ? null : incoming.getTableProperties());
    return asked.isPresent() && asked.getAsInt() >= IcebergV3.VERSION;
  }

  /**
   * The format version a replace leaves the table at: 3 when the replace asks for it and v3 is
   * enabled, the version the table has otherwise.
   *
   * @throws RequestValidationFailureException if the replace asks for a version above the table's
   *     that it cannot have, or below a v3 table's
   */
  public int versionOfReplace(String table, int stored, Map<String, String> properties) {
    checkUpdate("Replace", table, stored, properties);
    OptionalInt asked = requested(properties);
    return asked.isPresent() && asked.getAsInt() > stored ? asked.getAsInt() : stored;
  }

  /**
   * Refuses an update of a table at format version {@code stored} that asks for another version,
   * unless it is the upgrade to 3 and v3 is enabled.
   *
   * <p>With the switch off the update is left to Iceberg, as it was before this service knew format
   * v3: Iceberg upgrades a table to v2, and refuses a version it cannot build or a downgrade with
   * its own failure. Iceberg 1.5.2 builds nothing above v2; the Iceberg 1.12 line builds v3 and v4,
   * so there an update that asks for a version above 2 is refused here with the failure Iceberg
   * 1.5.2 throws for it, before anything is changed.
   *
   * @throws RequestValidationFailureException if the switch is on and it asks for a version above
   *     the table's that it cannot have, or below a v3 table's
   * @throws IllegalArgumentException if the switch is off, Iceberg builds v3, and it asks for a
   *     version above the table's and above 2
   */
  public void checkUpdate(String table, int stored, Map<String, String> properties) {
    OptionalInt asked = requested(properties);
    if (!switchOn()) {
      if (IcebergV3.supported()
          && asked.isPresent()
          && asked.getAsInt() > stored
          && asked.getAsInt() > HIGHEST_WITHOUT_V3) {
        throw new IllegalArgumentException(
            String.format(
                "Cannot upgrade table to unsupported format version: v%s (supported: v%s)",
                asked.getAsInt(), HIGHEST_WITHOUT_V3));
      }
      return;
    }
    checkUpdate("Update", table, stored, properties);
  }

  /**
   * Refuses, whatever the switch, an update that asks for a version above the table's that it
   * cannot have, or below a v3 table's, with this rule's own 400 ({@value #REFUSED}). For the
   * Iceberg REST commit routes, which have no answer of LinkedIn main to keep and on which a server
   * failure would tell the client that the commit's state is unknown.
   *
   * @throws RequestValidationFailureException as {@link #checkUpdate} does with the switch on
   */
  public void refuseUpdate(String table, int stored, Map<String, String> properties) {
    checkUpdate("Update", table, stored, properties);
  }

  private void checkUpdate(
      String operation, String table, int stored, Map<String, String> properties) {
    OptionalInt asked = requested(properties);
    if (!asked.isPresent() || asked.getAsInt() == stored) {
      return;
    }
    if (asked.getAsInt() > stored) {
      if (asked.getAsInt() > clusterVersion()) {
        requireV3(operation, table, asked.getAsInt());
      }
      return;
    }
    if (stored > clusterVersion()) {
      throw new RequestValidationFailureException(
          String.format(
              "%s: %s of table %s asks for format version %d: the table is format version %d, and "
                  + "a table's format version is never lowered",
              REFUSED, operation, table, asked.getAsInt(), stored));
    }
  }

  /**
   * The version an update that passed {@link #checkUpdate} leaves the table at, for callers that
   * must know before the commit whether the table will be v3.
   */
  public int versionAfterUpdate(int stored, Map<String, String> properties) {
    OptionalInt asked = requested(properties);
    return asked.isPresent() && asked.getAsInt() > stored ? asked.getAsInt() : stored;
  }

  /**
   * Whether an update of a table at {@code stored} that asks for {@code asked} is the upgrade this
   * rule allows.
   */
  public boolean isAllowedUpgrade(int stored, int asked) {
    return v3Enabled() && asked == IcebergV3.VERSION && stored < asked;
  }

  /**
   * The invariant every commit must keep, whatever path it took: a table's format version is the
   * one it had (a create: the cluster's), or 3 where v3 is enabled.
   *
   * @param stored the version the table has; {@code null} when the commit creates it
   * @param committed the version the commit would leave
   * @throws CommitRejectedException if the commit would leave another version
   */
  public void checkCommitted(String table, Integer stored, int committed) {
    int floor = stored == null ? clusterVersion() : Math.max(stored, clusterVersion());
    if (committed <= floor || (committed == IcebergV3.VERSION && v3Enabled())) {
      return;
    }
    throw new CommitRejectedException(
        String.format(
            "%s: the commit would leave table %s at format version %d: %s",
            REFUSED, table, committed, whyNot(committed)));
  }

  private void requireV3(String operation, String table, int asked) {
    if (asked == IcebergV3.VERSION && v3Enabled()) {
      return;
    }
    throw new RequestValidationFailureException(
        String.format(
            "%s: %s of table %s asks for format version %d: %s",
            REFUSED, operation, table, asked, whyNot(asked)));
  }

  private String whyNot(int asked) {
    if (asked != IcebergV3.VERSION) {
      return String.format(
          "OpenHouse tables are format version %d, or 3 where the cluster enables it",
          clusterVersion());
    }
    if (clusterProperties.isClusterIcebergFormatV3Enabled()) {
      return "this tables service runs an Iceberg that cannot read or write format version 3; "
          + "format version 3 needs the service on the Iceberg 1.12 line";
    }
    return String.format(
        "format version 3 is not enabled on this cluster (%s is false), which keeps tables at "
            + "format version %d",
        SWITCH, clusterVersion());
  }
}
