package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.tablestest.rest.OpenHouseRestSparkITest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.NullOrder;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.Table;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.types.Types;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DateType;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Spark-4.0 / Iceberg-1.11 / REST-first port of the 3.1 lane's {@code CatalogOperationTest}. Runs
 * against the embedded OpenHouse server through the stock {@code RESTCatalog}. Pure-SQL cases and
 * Iceberg Java-API cases (create/load/append, WRITE ORDERED BY, catalog buildTable) port through
 * the REST catalog. The custom {@code SET/UNSET POLICY} case ({@code
 * testAlterTableUnsetReplicationPolicy} with the {@code Policies} gen-model) is dropped, and
 * OpenHouse-only property assertions ({@code openhouse.tableUri}) are dropped; see 10-RESIDUALS.md.
 */
public class CatalogOperationTestSpark4_0 extends OpenHouseRestSparkITest {

  private static final String DATABASE = "d1_catalog";

  @Test
  public void testCasingWithCTAS() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      // Casing-preserving table via backtick.
      spark.sql("CREATE TABLE openhouse." + DATABASE + ".`tT1` (name string)");
      // Write with intentionally different casing.
      spark.sql("INSERT INTO openhouse." + DATABASE + ".Tt1 VALUES ('foo')");

      Assertions.assertEquals(
          1, spark.sql("SELECT * from openhouse." + DATABASE + ".tt1").collectAsList().size());
      // CTAS referring with lower-cased name.
      spark.sql(
          "CREATE TABLE openhouse."
              + DATABASE
              + ".t2 AS SELECT * from openhouse."
              + DATABASE
              + ".tt1");
      Assertions.assertEquals(
          1, spark.sql("SELECT * FROM openhouse." + DATABASE + ".t2").collectAsList().size());
    }
  }

  @Test
  public void testCreateTablePartitionedByDate() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      String quotedFqtn = "openhouse." + DATABASE + ".tpartionedbydate";
      spark.sql(
          String.format(
              "CREATE TABLE %s (data string) PARTITIONED BY (datefield DATE)", quotedFqtn));
      spark
          .sql(String.format("INSERT INTO %s SELECT 'a', to_date('2024-06-21')", quotedFqtn))
          .show();

      StructType schema = spark.table(quotedFqtn).schema();
      StructField dateField = schema.fields()[1];
      Assertions.assertEquals("datefield", dateField.name());
      Assertions.assertTrue(
          dateField.dataType() instanceof DateType, "The 'datefield' column should be of DateType");
    }
  }

  @Test
  public void testRenameTable() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      spark.sql("CREATE TABLE openhouse." + DATABASE + ".rename_src (name string)");
      spark.sql("INSERT INTO openhouse." + DATABASE + ".rename_src VALUES ('a')");
      spark.sql(
          "ALTER TABLE openhouse."
              + DATABASE
              + ".rename_src RENAME TO openhouse."
              + DATABASE
              + ".rename_dst");
      Assertions.assertEquals(
          1,
          spark.sql("SELECT * FROM openhouse." + DATABASE + ".rename_dst").collectAsList().size());
    }
  }

  @Test
  public void testCatalogWriteAPI() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      Catalog icebergCatalog = getOpenHouseCatalog(spark);
      // Create a table
      Schema schema = new Schema(Types.NestedField.required(1, "name", Types.StringType.get()));
      TableIdentifier tableIdentifier = TableIdentifier.of("db", "aaa");
      icebergCatalog.createTable(tableIdentifier, schema);

      // Write into data with intentionally changed casing in name
      TableIdentifier tableIdentifierUpperTblName = TableIdentifier.of("db", "AAA");

      DataFile fooDataFile =
          DataFiles.builder(PartitionSpec.unpartitioned())
              .withPath("/path/to/data-a.parquet")
              .withFileSizeInBytes(10)
              .withRecordCount(1)
              .build();
      AtomicReference<Table> tableRef = new AtomicReference<>();
      Assertions.assertDoesNotThrow(
          () -> {
            Table loadedTable = icebergCatalog.loadTable(tableIdentifierUpperTblName);
            tableRef.set(loadedTable);
          });
      Table table = tableRef.get();
      Assertions.assertDoesNotThrow(
          () -> {
            table.newAppend().appendFile(fooDataFile).commit();
          });
    }
  }

  @Test
  public void testRenameTableCatalogApi() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      Catalog icebergCatalog = getOpenHouseCatalog(spark);

      TableIdentifier fromTableIdentifier = TableIdentifier.of("db", "rename_test");
      spark.sql("CREATE TABLE openhouse.db.rename_test (name string)");

      TableIdentifier toTableIdentifier = TableIdentifier.of("db", "rename_test_renamed");
      spark.sql("ALTER TABLE openhouse.db.rename_test RENAME TO openhouse.db.rename_test_renamed");

      Table loadedTable = icebergCatalog.loadTable(toTableIdentifier);
      Assertions.assertNotNull(loadedTable);

      // NOTE: the 3.1 source also asserted the OpenHouse-only property
      // openhouse.tableUri == "local-cluster.db.rename_test_renamed". That property is not surfaced
      // on the stock REST lane; dropped (see 10-RESIDUALS.md).

      Assertions.assertThrows(
          NoSuchTableException.class, () -> icebergCatalog.loadTable(fromTableIdentifier));

      spark.sql("CREATE TABLE openhouse.db.rename_test (name string)");
    }
  }

  @Test
  public void testRenameTableCaseSensitivity() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      Catalog icebergCatalog = getOpenHouseCatalog(spark);
      Schema schema =
          new Schema(
              Types.NestedField.required(
                  1,
                  "a",
                  Types.StructType.of(Types.NestedField.required(2, "b", Types.StringType.get()))),
              Types.NestedField.required(3, "c", Types.StringType.get()));

      TableIdentifier fromTableIdentifier = TableIdentifier.of("db", "rename_TEST3");
      // The OpenHouse /iceberg REST controller requires a non-null PartitionSpec on create (the
      // legacy OpenHouseCatalog client defaulted it); pass unpartitioned explicitly.
      icebergCatalog.createTable(
          fromTableIdentifier, schema, PartitionSpec.unpartitioned(), new HashMap<>());
      Table createdTable = icebergCatalog.loadTable(fromTableIdentifier);
      Assertions.assertEquals(createdTable.name(), "openhouse.db.rename_TEST3");
      TableIdentifier toTableIdentifier =
          TableIdentifier.of("db", "rename_test_renamed_CASE_SENSITIVE");
      Assertions.assertDoesNotThrow(
          () ->
              icebergCatalog.renameTable(
                  TableIdentifier.of("DB", "RENAME_test3"), toTableIdentifier));
      Table renamedTable =
          icebergCatalog.loadTable(TableIdentifier.of("dB", "rename_test_renamed_case_SENSITIVE"));

      // Ensure that the original db name is preserved
      Assertions.assertEquals(
          renamedTable.name(), "openhouse.dB.rename_test_renamed_case_SENSITIVE");
    }
  }

  @Test
  public void testAlterTableSetSortOrder() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      Catalog catalog = getOpenHouseCatalog(spark);
      spark.sql("CREATE TABLE openhouse.db.test_sort_order (id int, data string)");
      spark.sql("ALTER TABLE openhouse.db.test_sort_order WRITE ORDERED BY (id)");
      Table table = catalog.loadTable(TableIdentifier.of("db", "test_sort_order"));
      Assertions.assertEquals(
          SortOrder.builderFor(table.schema()).asc("id").build(), table.sortOrder());
      String distribution =
          spark
              .sql("show tblproperties openhouse.db.test_sort_order")
              .filter("key='write.distribution-mode'")
              .select("value")
              .first()
              .getString(0);
      Assertions.assertEquals("range", distribution);
    }
  }

  @Test
  public void testAlterTableUnsetSortOrder() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      Catalog catalog = getOpenHouseCatalog(spark);
      spark.sql("CREATE TABLE openhouse.db.test_sort_order_unset (id int, data string)");
      spark.sql("ALTER TABLE openhouse.db.test_sort_order_unset WRITE ORDERED BY (id)");
      spark.sql("ALTER TABLE openhouse.db.test_sort_order_unset WRITE UNORDERED");
      Table table = catalog.loadTable(TableIdentifier.of("db", "test_sort_order_unset"));
      Assertions.assertEquals(SortOrder.unsorted(), table.sortOrder());
    }
  }

  @Test
  public void testAlterTableSortOrderCTAS() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      Catalog catalog = getOpenHouseCatalog(spark);
      spark.sql("CREATE TABLE openhouse.db.t1 (id int, data string)");
      spark.sql("ALTER TABLE openhouse.db.t1 WRITE ORDERED BY (id)");
      Table oldTable = catalog.loadTable(TableIdentifier.of("db", "t1"));
      // CTAS with sort order is only supported through catalog API
      Transaction transaction =
          catalog
              .buildTable(TableIdentifier.of("db", "test_sort_order_ctas"), oldTable.schema())
              .withPartitionSpec(PartitionSpec.unpartitioned())
              .withSortOrder(oldTable.sortOrder())
              .createTransaction();
      transaction.commitTransaction();
      Table newTable = catalog.loadTable(TableIdentifier.of("db", "test_sort_order_ctas"));
      Assertions.assertEquals(
          SortOrder.builderFor(oldTable.schema()).asc("id").build(), newTable.sortOrder());
      // CTAS with sort order is not supported through SQL API
      spark.sql(
          "CREATE TABLE openhouse.db.test_sort_order_ctas_sql AS SELECT * FROM openhouse.db.t1");
      Table newSqlTable = catalog.loadTable(TableIdentifier.of("db", "test_sort_order_ctas_sql"));
      Assertions.assertEquals(SortOrder.unsorted(), newSqlTable.sortOrder());
    }
  }

  @Test
  public void testWriteOrderedByPersistsMultiColumnSortOrder() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      Catalog catalog = getOpenHouseCatalog(spark);
      spark.sql(
          "CREATE TABLE openhouse.db.write_ordered_multi (id INT, category STRING, data STRING)");
      spark.sql("ALTER TABLE openhouse.db.write_ordered_multi WRITE ORDERED BY category, id");

      Table table = catalog.loadTable(TableIdentifier.of("db", "write_ordered_multi"));
      Assertions.assertEquals(
          SortOrder.builderFor(table.schema()).asc("category").asc("id").build(),
          table.sortOrder());
    }
  }

  @Test
  public void testWriteOrderedByRespectsDirectionAndNullOrder() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      Catalog catalog = getOpenHouseCatalog(spark);
      spark.sql("CREATE TABLE openhouse.db.write_ordered_desc (id INT, category STRING)");
      // DESC defaults to NULLS LAST in Iceberg; override to NULLS FIRST to verify both
      // direction and null-order are propagated end-to-end.
      spark.sql(
          "ALTER TABLE openhouse.db.write_ordered_desc WRITE ORDERED BY category DESC NULLS FIRST");

      Table table = catalog.loadTable(TableIdentifier.of("db", "write_ordered_desc"));
      Assertions.assertEquals(
          SortOrder.builderFor(table.schema()).desc("category", NullOrder.NULLS_FIRST).build(),
          table.sortOrder());
    }
  }

  @Test
  public void testWriteOrderedByRoundTripsThroughInsert() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      Catalog catalog = getOpenHouseCatalog(spark);
      spark.sql("CREATE TABLE openhouse.db.write_ordered_insert (id INT, category STRING)");
      spark.sql("ALTER TABLE openhouse.db.write_ordered_insert WRITE ORDERED BY id");

      spark.sql(
          "INSERT INTO openhouse.db.write_ordered_insert VALUES (3, 'C'), (1, 'A'), (2, 'B')");

      Table table = catalog.loadTable(TableIdentifier.of("db", "write_ordered_insert"));
      // Sort order metadata is preserved across an INSERT (no implicit reset).
      Assertions.assertEquals(
          SortOrder.builderFor(table.schema()).asc("id").build(), table.sortOrder());

      List<Row> rows =
          spark.sql("SELECT id FROM openhouse.db.write_ordered_insert ORDER BY id").collectAsList();
      Assertions.assertEquals(3, rows.size());
      Assertions.assertEquals(1, rows.get(0).getInt(0));
      Assertions.assertEquals(2, rows.get(1).getInt(0));
      Assertions.assertEquals(3, rows.get(2).getInt(0));
    }
  }

  @Test
  public void testCreateReplicaSkipFieldIdReassignmentUnPartitionedTable() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      Catalog icebergCatalog = getOpenHouseCatalog(spark);
      Schema schema =
          new Schema(
              Types.NestedField.required(
                  1,
                  "a",
                  Types.StructType.of(Types.NestedField.required(2, "b", Types.StringType.get()))),
              Types.NestedField.required(3, "c", Types.StringType.get()));

      // Field ids not reassigned
      TableIdentifier tableIdentifier = TableIdentifier.of("replication_test", "t1");
      Map<String, String> props = new HashMap<>();
      props.put("client.table.schema", SchemaParser.toJson(schema));
      Table table =
          icebergCatalog.createTable(tableIdentifier, schema, PartitionSpec.unpartitioned(), props);
      Schema schemaAfterCreation = table.schema();
      Assertions.assertTrue(schemaAfterCreation.sameSchema(schema));
      Assertions.assertEquals(1, schemaAfterCreation.findField("a").fieldId());
      Assertions.assertNotEquals(3, schemaAfterCreation.findField("a.b").fieldId());
      Assertions.assertNotEquals(2, schemaAfterCreation.findField("c").fieldId());
      // Evolve schema, add top level column d (should work as before)
      table.updateSchema().addColumn("d", Types.StringType.get()).commit();
      Assertions.assertEquals(4, table.schema().findField("d").fieldId());
      // Evolve schema, add child column e to a (should work as before)
      table.updateSchema().addColumn("a", "e", Types.StringType.get()).commit();
      Assertions.assertEquals(5, table.schema().findField("a.e").fieldId());
    }
  }

  @Test
  public void testCreateReplicaSkipFieldIdReassignmentPartitionedTable() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      Catalog icebergCatalog = getOpenHouseCatalog(spark);
      Schema schema =
          new Schema(
              Types.NestedField.required(
                  1,
                  "a",
                  Types.StructType.of(Types.NestedField.required(2, "b", Types.StringType.get()))),
              Types.NestedField.required(3, "c", Types.StringType.get()));

      // Field ids not reassigned
      TableIdentifier tableIdentifier = TableIdentifier.of("replication_test", "t2");
      Map<String, String> props = new HashMap<>();
      props.put("client.table.schema", SchemaParser.toJson(schema));
      Table table =
          icebergCatalog.createTable(tableIdentifier, schema, PartitionSpec.unpartitioned(), props);
      Schema schemaAfterCreation = table.schema();
      Assertions.assertTrue(schemaAfterCreation.sameSchema(schema));
      Assertions.assertEquals(1, schemaAfterCreation.findField("a").fieldId());
      Assertions.assertNotEquals(3, schemaAfterCreation.findField("a.b").fieldId());
      Assertions.assertNotEquals(2, schemaAfterCreation.findField("c").fieldId());
      // Evolve schema, add top level column d (should work as before)
      table.updateSchema().addColumn("d", Types.StringType.get()).commit();
      Assertions.assertEquals(4, table.schema().findField("d").fieldId());
      // Evolve schema, add child column e to a (should work as before)
      table.updateSchema().addColumn("a", "e", Types.StringType.get()).commit();
      Assertions.assertEquals(5, table.schema().findField("a.e").fieldId());
    }
  }

  /**
   * Verifies renaming ONTO an existing table is rejected. The OpenHouse {@code /iceberg} rename
   * endpoint delegates to {@code OpenHouseInternalCatalog.renameTable}, which now throws {@code
   * AlreadyExistsException} (-> HTTP 409) when the destination table already exists instead of
   * silently rewriting the source table's identity onto the occupied name.
   *
   * <p>The assertion is engine-agnostic: the exact client-surfaced exception type differs between
   * the 3.1 custom-client lane ({@code WebClientResponseWithMessageException}) and this stock
   * {@code RESTCatalog} lane, so we assert the BEHAVIOR instead -- the rename is rejected (some
   * exception is thrown) AND both the source and the pre-existing destination survive the failed
   * rename intact (no silent replace).
   */
  @Test
  public void testRenameTableFailsConflict() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      Catalog icebergCatalog = getOpenHouseCatalog(spark);
      Schema schema =
          new Schema(
              Types.NestedField.required(
                  1,
                  "a",
                  Types.StructType.of(Types.NestedField.required(2, "b", Types.StringType.get()))),
              Types.NestedField.required(3, "c", Types.StringType.get()));

      TableIdentifier fromTableIdentifier = TableIdentifier.of("db", "rename_test2");
      TableIdentifier conflictingTableIdentifier = TableIdentifier.of("db", "rename_test_conflict");
      Map<String, String> props = new HashMap<>();
      props.put("client.table.schema", SchemaParser.toJson(schema));
      props.put("user.property", "test_property");
      Map<String, String> conflictingProps = new HashMap<>();
      conflictingProps.put("client.table.schema", SchemaParser.toJson(schema));
      icebergCatalog.createTable(fromTableIdentifier, schema, PartitionSpec.unpartitioned(), props);
      Table conflictingTable =
          icebergCatalog.createTable(
              conflictingTableIdentifier, schema, PartitionSpec.unpartitioned(), conflictingProps);
      Assertions.assertNull(conflictingTable.properties().get("user.property"));

      // Renaming onto an existing table must be rejected. The client-surfaced exception type is
      // engine-specific (stock RESTCatalog maps the server 409 differently than the 3.1 custom
      // client), so assert only that SOME exception is thrown -- the behavioral contract.
      Assertions.assertThrows(
          Exception.class,
          () ->
              spark.sql(
                  "ALTER TABLE openhouse.db.rename_test2 RENAME TO openhouse.db.rename_test_conflict"));

      // The failed rename must not have mutated either table: the source still exists, and the
      // pre-existing destination is untouched (its "user.property" was never set, so the source's
      // property must not have leaked onto it via a silent replace).
      Assertions.assertNotNull(icebergCatalog.loadTable(fromTableIdentifier));
      Assertions.assertNull(
          icebergCatalog.loadTable(conflictingTableIdentifier).properties().get("user.property"));
    }
  }

  /**
   * PENDING (see 10-RESIDUALS.md fix checklist). The 3.1 case drove custom {@code SET/UNSET POLICY
   * (REPLICATION|RETENTION ...)} SQL and read the result back via the {@code
   * com.linkedin.openhouse.gen.tables.client.model.Policies} gen-model. Neither the custom
   * OpenHouse SQL extension nor the {@code Policies} model is available on the REST lane (the model
   * is not even on this module's compile classpath), so the readback assertions are expressed
   * against the raw {@code policies} table property string. Disabled: the {@code SET POLICY} DDL is
   * unparseable by the stock Iceberg SQL extension on this lane.
   */
  @Disabled(
      "custom SET/UNSET POLICY SQL + Policies gen-model unavailable on REST lane — see spark4-e2e-tests/10-RESIDUALS.md")
  @Test
  public void testAlterTableUnsetReplicationPolicy() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      spark.sql("CREATE TABLE openhouse." + DATABASE + ".`ttt1` (name string)");
      spark.sql("INSERT INTO openhouse." + DATABASE + ".ttt1 VALUES ('foo')");
      spark.sql(
          "ALTER TABLE openhouse."
              + DATABASE
              + ".ttt1 SET POLICY (REPLICATION=({destination:'WAR', interval:12h}))");
      spark.sql(
          "ALTER TABLE openhouse."
              + DATABASE
              + ".ttt1 SET POLICY (RETENTION= 30d on column name where pattern='yyyy-MM-dd')");
      String policies = getPoliciesProperty("openhouse." + DATABASE + ".ttt1", spark);
      Assertions.assertNotNull(policies);
      Assertions.assertTrue(policies.contains("WAR"));
      Assertions.assertTrue(policies.contains("yyyy-MM-dd"));

      // unset replication policy
      spark.sql("ALTER TABLE openhouse." + DATABASE + ".ttt1 UNSET POLICY (REPLICATION)");
      String updatedPolicy = getPoliciesProperty("openhouse." + DATABASE + ".ttt1", spark);
      // assert that other policies, retention is not modified after unsetting replication
      Assertions.assertTrue(updatedPolicy.contains("yyyy-MM-dd"));

      // assert retention can be set after unsetting replication
      spark.sql(
          "ALTER TABLE openhouse."
              + DATABASE
              + ".ttt1 SET POLICY (RETENTION = 30D on COLUMN name WHERE pattern = 'yyyy')");
      String policyWithRetention = getPoliciesProperty("openhouse." + DATABASE + ".ttt1", spark);
      Assertions.assertTrue(policyWithRetention.contains("yyyy"));

      // assert replication can be set again after retention policy
      spark.sql(
          "ALTER TABLE openhouse."
              + DATABASE
              + ".ttt1 SET POLICY (REPLICATION=({destination:'WAR', interval:12h}))");
      String policyWithReplication = getPoliciesProperty("openhouse." + DATABASE + ".ttt1", spark);
      Assertions.assertTrue(policyWithReplication.contains("WAR"));

      // UNSET policy for table without replication
      spark.sql("CREATE TABLE openhouse." + DATABASE + ".`tttest1` (name string)");
      spark.sql("INSERT INTO openhouse." + DATABASE + ".tttest1 VALUES ('foo')");
      spark.sql("ALTER TABLE openhouse." + DATABASE + ".tttest1 UNSET POLICY (REPLICATION)");
      String policytttest1 = getPoliciesProperty("openhouse." + DATABASE + ".tttest1", spark);
      Assertions.assertNotNull(policytttest1);
    }
  }

  /**
   * Reads the raw {@code policies} table property string. The 3.1 source deserialized it into the
   * {@code Policies} gen-model, which is not on this module's compile classpath; the disabled test
   * above therefore asserts against the raw string.
   */
  private String getPoliciesProperty(String tableName, SparkSession spark) {
    List<Row> propsRows =
        spark.sql(String.format("show tblProperties %s", tableName)).collectAsList();
    Map<String, String> collect =
        propsRows.stream()
            .collect(java.util.stream.Collectors.toMap(r -> r.getString(0), r -> r.getString(1)));
    return String.valueOf(collect.get("policies"));
  }
}
