package com.linkedin.openhouse.spark.capabilities;

import com.linkedin.openhouse.capabilities.AclPolicy;
import com.linkedin.openhouse.capabilities.GrantsCapability;
import com.linkedin.openhouse.capabilities.UnlockCapability;
import com.linkedin.openhouse.capabilities.UnlockReason;
import com.linkedin.openhouse.capabilities.UnlockTableTarget;
import com.linkedin.openhouse.gen.tables.client.model.CreateUpdateLockRequestBody.ReasonEnum;
import com.linkedin.openhouse.javaclient.api.SupportsUnlock;
import java.util.Collections;
import java.util.List;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.hadoop.HadoopCatalog;

/** Catalog classes for {@link OpenHouseCapabilitiesTest}, built by Spark from catalog-impl. */
public final class CapabilityTestCatalogs {
  private CapabilityTestCatalogs() {}

  /** A catalog that implements only the Java client's unlock interface. */
  public static class UnlockOnly extends HadoopCatalog implements SupportsUnlock {
    @Override
    public void unlockTable(
        com.linkedin.openhouse.javaclient.api.UnlockTableTarget target, ReasonEnum reason) {}
  }

  /** A catalog that implements the capability interfaces itself. */
  public static class Native extends HadoopCatalog implements GrantsCapability, UnlockCapability {
    @Override
    public void updateTableAclPolicies(
        TableIdentifier table, boolean isGrant, String privilege, String principal) {}

    @Override
    public List<AclPolicy> getTableAclPolicies(TableIdentifier table) {
      return Collections.emptyList();
    }

    @Override
    public void updateDatabaseAclPolicies(
        Namespace database, boolean isGrant, String privilege, String principal) {}

    @Override
    public List<AclPolicy> getDatabaseAclPolicies(Namespace database) {
      return Collections.emptyList();
    }

    @Override
    public void unlockTable(UnlockTableTarget target, UnlockReason reason) {}
  }
}
