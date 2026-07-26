package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.tablestest.rest.OpenHouseRestSparkITest;
import java.util.HashMap;
import java.util.Map;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.types.Types;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Spark-4.0 / Iceberg-1.11 / REST-first verification that OpenHouse table POLICIES can be SET and
 * READ back through the stock {@code org.apache.iceberg.rest.RESTCatalog} (no custom client jar),
 * driving the OpenHouse {@code /iceberg/v1/*} endpoint ({@code IcebergRestCatalogController}).
 *
 * <p><b>Client property contract.</b> A stock RESTCatalog sets a policy by carrying it as the
 * reserved table property {@code updated.openhouse.policy}, whose value is the JSON serialization
 * of a (partial) OpenHouse {@code Policies} object -- exactly the key + encoding the legacy Spark
 * {@code Set*PolicyExec} emitted, e.g. {@code {"retention":{"count":3,"granularity":"DAY",...}}} or
 * {@code {"sharingEnabled":true}}. The server intercepts this property on both create and
 * update/commit, translates it into the structured OpenHouse {@code Policies} model, runs the SAME
 * server-side policy validation the native {@code /tables} path runs (e.g. the retention
 * column-pattern requirement asserted below), and persists it into the reserved {@code policies}
 * property. It is NEVER persisted verbatim, and a legitimate policy set is NOT rejected as a
 * reserved-property violation.
 *
 * <p><b>Readback.</b> A stock RESTCatalog load surfaces the persisted policy as the {@code
 * policies} property of the returned table metadata ({@code table.properties().get("policies")}).
 *
 * <p>See {@code docs/spark4-iceberg-upgrade/spark4-e2e-tests/policy-rest-lane.md} for the full
 * audit.
 */
public class PolicyPropertyTestSpark4_0 extends OpenHouseRestSparkITest {

  private static final String DATABASE = "policy_rest";
  private static final String POLICY_KEY = "updated.openhouse.policy";
  private static final String POLICIES_KEY = "policies";

  /**
   * A retention policy is only meaningful on a non-timestamp-partitioned table when it names a
   * string column + date pattern; the server validates this exactly as the native /tables path
   * does, so the schema carries a suitable string column.
   */
  private static Schema schemaWithDateColumn() {
    return new Schema(
        Types.NestedField.required(1, "id", Types.IntegerType.get()),
        Types.NestedField.required(2, "datecol", Types.StringType.get()));
  }

  private static Schema simpleSchema() {
    return new Schema(Types.NestedField.required(1, "id", Types.IntegerType.get()));
  }

  private static final String RETENTION_POLICY =
      "{\"retention\":{\"count\":3,\"granularity\":\"DAY\","
          + "\"columnPattern\":{\"columnName\":\"datecol\",\"pattern\":\"yyyy-MM-dd\"}}}";

  /**
   * SET a retention policy on an existing table through the stock RESTCatalog's {@code
   * updateProperties()} API (the same call the legacy {@code SetRetentionPolicyExec} makes), then
   * assert the OpenHouse server folded it into the reserved {@code policies} property on readback.
   */
  @Test
  public void testSetRetentionPolicyOnUpdate() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      Catalog catalog = getOpenHouseCatalog(spark);
      TableIdentifier ident = TableIdentifier.of(DATABASE, "retention_update");
      catalog.createTable(
          ident, schemaWithDateColumn(), PartitionSpec.unpartitioned(), new HashMap<>());

      catalog.loadTable(ident).updateProperties().set(POLICY_KEY, RETENTION_POLICY).commit();

      Table reloaded = catalog.loadTable(ident);
      // The carrier property must NOT be persisted verbatim...
      Assertions.assertNull(reloaded.properties().get(POLICY_KEY));
      // ...it must be folded into the reserved `policies` property.
      String policies = reloaded.properties().get(POLICIES_KEY);
      Assertions.assertNotNull(policies, "policies property should be present after SET POLICY");
      Assertions.assertTrue(
          policies.contains("retention"),
          "policies should contain the retention block: " + policies);
      Assertions.assertTrue(
          policies.contains("\"count\": 3") || policies.contains("\"count\":3"),
          "policies should carry the retention count: " + policies);
      Assertions.assertTrue(
          policies.contains("DAY"), "policies should carry the retention granularity: " + policies);
      Assertions.assertTrue(
          policies.contains("yyyy-MM-dd"),
          "policies should carry the retention column pattern: " + policies);
    }
  }

  /**
   * SET a policy at CREATE time by carrying the policy property in the create request's properties.
   * The server translates it during the reused create pipeline and persists it into {@code
   * policies}.
   */
  @Test
  public void testSetSharingPolicyOnCreate() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      Catalog catalog = getOpenHouseCatalog(spark);
      TableIdentifier ident = TableIdentifier.of(DATABASE, "sharing_create");
      Map<String, String> props = new HashMap<>();
      props.put(POLICY_KEY, "{\"sharingEnabled\":true}");
      catalog.createTable(ident, simpleSchema(), PartitionSpec.unpartitioned(), props);

      Table reloaded = catalog.loadTable(ident);
      Assertions.assertNull(reloaded.properties().get(POLICY_KEY));
      String policies = reloaded.properties().get(POLICIES_KEY);
      Assertions.assertNotNull(
          policies, "policies property should be present after create-with-policy");
      Assertions.assertTrue(
          policies.contains("\"sharingEnabled\": true")
              || policies.contains("\"sharingEnabled\":true"),
          "policies should carry sharingEnabled=true: " + policies);
    }
  }

  /**
   * SET sharing on an existing table through {@code updateProperties()}, verifying the update path
   * (not just create) translates + persists a policy.
   */
  @Test
  public void testSetSharingPolicyOnUpdate() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      Catalog catalog = getOpenHouseCatalog(spark);
      TableIdentifier ident = TableIdentifier.of(DATABASE, "sharing_update");
      catalog.createTable(ident, simpleSchema(), PartitionSpec.unpartitioned(), new HashMap<>());

      catalog
          .loadTable(ident)
          .updateProperties()
          .set(POLICY_KEY, "{\"sharingEnabled\":true}")
          .commit();

      Table reloaded = catalog.loadTable(ident);
      Assertions.assertNull(reloaded.properties().get(POLICY_KEY));
      String policies = reloaded.properties().get(POLICIES_KEY);
      Assertions.assertNotNull(policies);
      Assertions.assertTrue(
          policies.contains("\"sharingEnabled\": true")
              || policies.contains("\"sharingEnabled\":true"),
          "policies should carry sharingEnabled=true after update: " + policies);
    }
  }

  /**
   * Two successive SET POLICY commits (retention, then sharing) must MERGE server-side: the second
   * policy does not clobber the first. This exercises the server-side reproduction of the legacy
   * client's {@code buildUpdatedPolicies} merge, which the stock RESTCatalog client cannot do
   * itself.
   */
  @Test
  public void testSuccessivePolicyUpdatesMerge() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      Catalog catalog = getOpenHouseCatalog(spark);
      TableIdentifier ident = TableIdentifier.of(DATABASE, "policy_merge");
      catalog.createTable(
          ident, schemaWithDateColumn(), PartitionSpec.unpartitioned(), new HashMap<>());

      catalog.loadTable(ident).updateProperties().set(POLICY_KEY, RETENTION_POLICY).commit();
      catalog
          .loadTable(ident)
          .updateProperties()
          .set(POLICY_KEY, "{\"sharingEnabled\":true}")
          .commit();

      String policies = catalog.loadTable(ident).properties().get(POLICIES_KEY);
      Assertions.assertNotNull(policies);
      // Retention set by the first commit survives the second (sharing) commit.
      Assertions.assertTrue(
          policies.contains("retention"),
          "retention must survive a subsequent sharing update: " + policies);
      Assertions.assertTrue(
          policies.contains("\"count\": 3") || policies.contains("\"count\":3"),
          "retention count must survive the merge: " + policies);
      Assertions.assertTrue(
          policies.contains("\"sharingEnabled\": true")
              || policies.contains("\"sharingEnabled\":true"),
          "sharing must be applied by the merge: " + policies);
    }
  }

  /**
   * A NON-policy reserved property (any {@code openhouse.*} key) must still be rejected on update
   * -- only the legitimate policy-carrier path is exempt from the reserved-property guard.
   */
  @Test
  public void testNonPolicyReservedPropStillRejected() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      Catalog catalog = getOpenHouseCatalog(spark);
      TableIdentifier ident = TableIdentifier.of(DATABASE, "reserved_guard");
      catalog.createTable(ident, simpleSchema(), PartitionSpec.unpartitioned(), new HashMap<>());

      Table table = catalog.loadTable(ident);
      Assertions.assertThrows(
          Exception.class,
          () -> table.updateProperties().set("openhouse.tableType", "REPLICA_TABLE").commit(),
          "mutating a reserved openhouse.* property must still be rejected");
    }
  }
}
