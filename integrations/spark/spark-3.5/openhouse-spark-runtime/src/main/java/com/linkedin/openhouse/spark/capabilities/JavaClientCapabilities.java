package com.linkedin.openhouse.spark.capabilities;

import com.linkedin.openhouse.capabilities.AclPolicy;
import com.linkedin.openhouse.capabilities.GrantsCapability;
import com.linkedin.openhouse.capabilities.UnlockCapability;
import com.linkedin.openhouse.capabilities.UnlockReason;
import com.linkedin.openhouse.capabilities.UnlockTableTarget;
import com.linkedin.openhouse.gen.tables.client.model.CreateUpdateLockRequestBody.ReasonEnum;
import com.linkedin.openhouse.javaclient.api.SupportsGrantRevoke;
import com.linkedin.openhouse.javaclient.api.SupportsUnlock;
import com.linkedin.openhouse.javaclient.exception.UnlockValidationException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;

/**
 * Adapts the OpenHouse Java client's catalog interfaces ({@link SupportsGrantRevoke}, {@link
 * SupportsUnlock}), which today's legacy {@code OpenHouseCatalog} and the in-process {@code
 * OpenHouseRestCatalog} implement, to the capability interfaces. Every call goes to the client's
 * method unchanged, so the request, its headers and its errors are exactly today's.
 *
 * <p>This is the only place in the SQL extensions' execution layer that names the Java client's
 * types; an extensions line without the Java client (Spark 4.2 over Iceberg's own REST catalog)
 * leaves it out.
 */
public final class JavaClientCapabilities {

  private JavaClientCapabilities() {}

  /** {@code catalog} as {@link GrantsCapability}, if it implements {@link SupportsGrantRevoke}. */
  public static Optional<GrantsCapability> grants(Object catalog) {
    return catalog instanceof SupportsGrantRevoke
        ? Optional.of(new Grants((SupportsGrantRevoke) catalog))
        : Optional.empty();
  }

  /** {@code catalog} as {@link UnlockCapability}, if it implements {@link SupportsUnlock}. */
  public static Optional<UnlockCapability> unlock(Object catalog) {
    return catalog instanceof SupportsUnlock
        ? Optional.of(new Unlock((SupportsUnlock) catalog))
        : Optional.empty();
  }

  /** The capability reason for the Java client's lock reason. */
  public static UnlockReason toUnlockReason(ReasonEnum reason) {
    return Objects.requireNonNull(
        UnlockReason.fromValue(reason.getValue()), () -> "Unknown lock reason " + reason);
  }

  /** The Java client's lock reason for a capability reason. */
  public static ReasonEnum toReasonEnum(UnlockReason reason) {
    return Objects.requireNonNull(
        ReasonEnum.fromValue(reason.getValue()), () -> "Unknown lock reason " + reason);
  }

  /** A capability target that carries the Java client's target it was made from. */
  public static UnlockTableTarget toUnlockTableTarget(
      com.linkedin.openhouse.javaclient.api.UnlockTableTarget target) {
    return new JavaClientTarget(target);
  }

  /**
   * The Java client's target for a capability target: the very one it was made from when it came
   * from {@link #toUnlockTableTarget}, else a new one with the same components.
   */
  public static com.linkedin.openhouse.javaclient.api.UnlockTableTarget toJavaClientTarget(
      UnlockTableTarget target) {
    if (target instanceof JavaClientTarget) {
      return ((JavaClientTarget) target).origin;
    }
    try {
      return com.linkedin.openhouse.javaclient.api.UnlockTableTarget.of(
          new String[] {target.getDatabaseId()}, target.getTableId());
    } catch (UnlockValidationException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
  }

  static final class Grants implements GrantsCapability {
    private final SupportsGrantRevoke catalog;

    Grants(SupportsGrantRevoke catalog) {
      this.catalog = catalog;
    }

    @Override
    public void updateTableAclPolicies(
        TableIdentifier table, boolean isGrant, String privilege, String principal) {
      catalog.updateTableAclPolicies(table, isGrant, privilege, principal);
    }

    @Override
    public List<AclPolicy> getTableAclPolicies(TableIdentifier table) {
      return toAclPolicies(catalog.getTableAclPolicies(table));
    }

    @Override
    public void updateDatabaseAclPolicies(
        Namespace database, boolean isGrant, String privilege, String principal) {
      catalog.updateDatabaseAclPolicies(database, isGrant, privilege, principal);
    }

    @Override
    public List<AclPolicy> getDatabaseAclPolicies(Namespace database) {
      return toAclPolicies(catalog.getDatabaseAclPolicies(database));
    }

    private static List<AclPolicy> toAclPolicies(List<SupportsGrantRevoke.AclPolicyDto> dtos) {
      return dtos.stream()
          .map(dto -> new AclPolicy(dto.getPrivilege(), dto.getPrincipal()))
          .collect(Collectors.toList());
    }
  }

  static final class Unlock implements UnlockCapability {
    private final SupportsUnlock catalog;

    Unlock(SupportsUnlock catalog) {
      this.catalog = catalog;
    }

    @Override
    public void unlockTable(UnlockTableTarget target, UnlockReason reason) {
      catalog.unlockTable(toJavaClientTarget(target), toReasonEnum(reason));
    }
  }

  static final class JavaClientTarget implements UnlockTableTarget {
    private final com.linkedin.openhouse.javaclient.api.UnlockTableTarget origin;

    JavaClientTarget(com.linkedin.openhouse.javaclient.api.UnlockTableTarget origin) {
      this.origin = Objects.requireNonNull(origin, "target");
    }

    @Override
    public String getDatabaseId() {
      return origin.getDatabaseId();
    }

    @Override
    public String getTableId() {
      return origin.getTableId();
    }

    @Override
    public String toString() {
      return origin.toString();
    }
  }
}
