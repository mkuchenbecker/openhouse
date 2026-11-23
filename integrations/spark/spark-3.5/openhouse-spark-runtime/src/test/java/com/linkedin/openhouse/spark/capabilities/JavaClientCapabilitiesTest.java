package com.linkedin.openhouse.spark.capabilities;

import com.linkedin.openhouse.capabilities.AclPolicy;
import com.linkedin.openhouse.capabilities.CapabilityNames;
import com.linkedin.openhouse.capabilities.GrantsCapability;
import com.linkedin.openhouse.capabilities.UnlockCapability;
import com.linkedin.openhouse.capabilities.UnlockReason;
import com.linkedin.openhouse.capabilities.UnlockTableTarget;
import com.linkedin.openhouse.gen.tables.client.model.CreateUpdateLockRequestBody.ReasonEnum;
import com.linkedin.openhouse.javaclient.OpenHouseCatalog;
import com.linkedin.openhouse.javaclient.api.SupportsGrantRevoke;
import com.linkedin.openhouse.javaclient.api.SupportsUnlock;
import java.util.Arrays;
import java.util.Collections;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Today's catalogs satisfy the capability interfaces through {@link JavaClientCapabilities}: each
 * call reaches the Java client's method with the same arguments, and its results and failures come
 * back unchanged.
 */
class JavaClientCapabilitiesTest {

  @Test
  void bothOpenHouseCatalogClassesImplementTheJavaClientInterfaces() {
    // The resolver finds these two classes through these interfaces, before any other check.
    for (Class<?> catalog :
        Arrays.asList(
            OpenHouseCatalog.class,
            com.linkedin.openhouse.javaclient.OpenHouseRestCatalog.class,
            com.linkedin.openhouse.spark.OpenHouseCatalog.class,
            com.linkedin.openhouse.spark.OpenHouseRestCatalog.class)) {
      Assertions.assertTrue(SupportsGrantRevoke.class.isAssignableFrom(catalog), catalog.getName());
      Assertions.assertTrue(SupportsUnlock.class.isAssignableFrom(catalog), catalog.getName());
    }
  }

  @Test
  void theClientCapabilityNameIsTheJavaClientOne() {
    Assertions.assertEquals(
        OpenHouseCatalog.COLUMN_DEFAULTS_CAPABILITY, CapabilityNames.COLUMN_DEFAULTS);
  }

  @Test
  void anythingElseOffersNoHandle() {
    Assertions.assertFalse(JavaClientCapabilities.grants(new Object()).isPresent());
    Assertions.assertFalse(JavaClientCapabilities.unlock(new Object()).isPresent());
    Assertions.assertFalse(JavaClientCapabilities.grants(null).isPresent());
  }

  @Test
  void grantsDelegateUnchanged() {
    SupportsGrantRevoke catalog = Mockito.mock(SupportsGrantRevoke.class);
    GrantsCapability grants = JavaClientCapabilities.grants(catalog).get();
    TableIdentifier table = TableIdentifier.of("db", "t");
    Namespace database = Namespace.of("db");

    grants.updateTableAclPolicies(table, true, "SELECT", "*");
    grants.updateDatabaseAclPolicies(database, false, "CREATE TABLE", "sraikar");
    Mockito.verify(catalog).updateTableAclPolicies(table, true, "SELECT", "*");
    Mockito.verify(catalog).updateDatabaseAclPolicies(database, false, "CREATE TABLE", "sraikar");

    Mockito.when(catalog.getTableAclPolicies(table))
        .thenReturn(
            Arrays.asList(
                new SupportsGrantRevoke.AclPolicyDto("SELECT", "*"),
                new SupportsGrantRevoke.AclPolicyDto("ALTER", "a")));
    Mockito.when(catalog.getDatabaseAclPolicies(database)).thenReturn(Collections.emptyList());
    Assertions.assertEquals(
        Arrays.asList(new AclPolicy("SELECT", "*"), new AclPolicy("ALTER", "a")),
        grants.getTableAclPolicies(table));
    Assertions.assertTrue(grants.getDatabaseAclPolicies(database).isEmpty());
  }

  @Test
  void grantFailuresPassThroughUnchanged() {
    SupportsGrantRevoke catalog = Mockito.mock(SupportsGrantRevoke.class);
    IllegalArgumentException failure = new IllegalArgumentException("400 , body");
    Mockito.doThrow(failure)
        .when(catalog)
        .updateTableAclPolicies(TableIdentifier.of("db", "t"), true, "SELECT", "a");
    IllegalArgumentException thrown =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                JavaClientCapabilities.grants(catalog)
                    .get()
                    .updateTableAclPolicies(TableIdentifier.of("db", "t"), true, "SELECT", "a"));
    Assertions.assertSame(failure, thrown);
  }

  @Test
  void unlockPassesTheAnalyzedTargetThroughAsTheSameObject() throws Exception {
    SupportsUnlock catalog = Mockito.mock(SupportsUnlock.class);
    UnlockCapability unlock = JavaClientCapabilities.unlock(catalog).get();
    com.linkedin.openhouse.javaclient.api.UnlockTableTarget analyzed =
        com.linkedin.openhouse.javaclient.api.UnlockTableTarget.of(new String[] {"my db"}, "t`");
    UnlockTableTarget target = JavaClientCapabilities.toUnlockTableTarget(analyzed);
    Assertions.assertEquals("my db", target.getDatabaseId());
    Assertions.assertEquals("t`", target.getTableId());
    Assertions.assertEquals(analyzed.toString(), target.toString());

    unlock.unlockTable(target, UnlockReason.SYSTEM_ONLY);
    Mockito.verify(catalog).unlockTable(Mockito.same(analyzed), Mockito.eq(ReasonEnum.SYSTEM_ONLY));
  }

  @Test
  void unlockOfAPlainTargetBuildsTheJavaClientTarget() throws Exception {
    SupportsUnlock catalog = Mockito.mock(SupportsUnlock.class);
    JavaClientCapabilities.unlock(catalog)
        .get()
        .unlockTable(UnlockTableTarget.of(new String[] {"db"}, "t"), UnlockReason.LEGACY);
    Mockito.verify(catalog)
        .unlockTable(
            Mockito.argThat(t -> "db".equals(t.getDatabaseId()) && "t".equals(t.getTableId())),
            Mockito.eq(ReasonEnum.LEGACY));
  }

  @Test
  void everyReasonMapsBothWays() {
    Assertions.assertEquals(ReasonEnum.values().length, UnlockReason.values().length);
    for (ReasonEnum reason : ReasonEnum.values()) {
      UnlockReason mapped = JavaClientCapabilities.toUnlockReason(reason);
      Assertions.assertEquals(reason.getValue(), mapped.getValue());
      Assertions.assertEquals(reason.toString(), mapped.toString());
      Assertions.assertSame(reason, JavaClientCapabilities.toReasonEnum(mapped));
    }
  }

  @Test
  void unlockFailuresPassThroughUnchanged() throws Exception {
    SupportsUnlock catalog = Mockito.mock(SupportsUnlock.class);
    SecurityException failure = new SecurityException("no");
    Mockito.doThrow(failure).when(catalog).unlockTable(Mockito.any(), Mockito.any());
    SecurityException thrown =
        Assertions.assertThrows(
            SecurityException.class,
            () ->
                JavaClientCapabilities.unlock(catalog)
                    .get()
                    .unlockTable(
                        UnlockTableTarget.of(new String[] {"db"}, "t"), UnlockReason.LEGACY));
    Assertions.assertSame(failure, thrown);
  }
}
