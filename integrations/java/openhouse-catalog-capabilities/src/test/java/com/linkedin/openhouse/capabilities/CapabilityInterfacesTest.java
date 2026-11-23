package com.linkedin.openhouse.capabilities;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The feature interfaces are what a catalog implements (or is adapted to) to offer a capability;
 * this pins their shape with a minimal implementation of each.
 */
class CapabilityInterfacesTest {

  @Test
  void grantsCapabilityCarriesTableAndDatabaseCalls() {
    List<String> calls = new ArrayList<>();
    GrantsCapability grants =
        new GrantsCapability() {
          @Override
          public void updateTableAclPolicies(
              TableIdentifier table, boolean isGrant, String privilege, String principal) {
            calls.add("table " + table + " " + isGrant + " " + privilege + " " + principal);
          }

          @Override
          public List<AclPolicy> getTableAclPolicies(TableIdentifier table) {
            return Collections.singletonList(new AclPolicy("SELECT", "*"));
          }

          @Override
          public void updateDatabaseAclPolicies(
              Namespace database, boolean isGrant, String privilege, String principal) {
            calls.add("database " + database + " " + isGrant + " " + privilege + " " + principal);
          }

          @Override
          public List<AclPolicy> getDatabaseAclPolicies(Namespace database) {
            return Collections.emptyList();
          }
        };
    grants.updateTableAclPolicies(TableIdentifier.of("db", "t"), true, "SELECT", "a");
    grants.updateDatabaseAclPolicies(Namespace.of("db"), false, "CREATE TABLE", "b");
    Assertions.assertEquals(
        java.util.Arrays.asList("table db.t true SELECT a", "database db false CREATE TABLE b"),
        calls);
    Assertions.assertEquals(
        Collections.singletonList(new AclPolicy("SELECT", "*")),
        grants.getTableAclPolicies(TableIdentifier.of("db", "t")));
    Assertions.assertTrue(grants.getDatabaseAclPolicies(Namespace.of("db")).isEmpty());
  }

  @Test
  void unlockCapabilityTakesATargetAndAReason() throws UnlockValidationException {
    List<String> calls = new ArrayList<>();
    UnlockCapability unlock = (target, reason) -> calls.add(target + " " + reason);
    unlock.unlockTable(UnlockTableTarget.of(new String[] {"db"}, "t"), UnlockReason.SYSTEM_ONLY);
    Assertions.assertEquals(Collections.singletonList("db.t SYSTEM_ONLY"), calls);
  }
}
