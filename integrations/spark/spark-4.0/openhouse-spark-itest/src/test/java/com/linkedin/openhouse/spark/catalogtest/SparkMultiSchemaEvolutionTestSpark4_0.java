package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.tablestest.rest.OpenHouseRestSparkITest;
import java.util.HashMap;
import java.util.Map;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.types.Types;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Spark-4.0 / Iceberg-1.11 / REST-first port of the 3.5 lane's {@code
 * e2e/SparkMultiSchemaEvolutionTest}. Exercises multi-schema evolution on REPLICA tables by
 * hand-building {@link TableMetadata} and committing it through {@link TableOperations}.
 *
 * <p>The 3.5 source cast the catalog to {@code OpenHouseCatalog} and used {@code newTableOps}; on the
 * REST lane the catalog is a stock {@code RESTCatalog}, so the {@link TableOperations} is obtained
 * via {@code ((BaseTable) table).operations()}. Uses the 1.11 one-arg {@code addSchema(schema)}.
 */
public class SparkMultiSchemaEvolutionTestSpark4_0 extends OpenHouseRestSparkITest {

  private static TableOperations ops(Catalog catalog, TableIdentifier id) {
    return ((BaseTable) catalog.loadTable(id)).operations();
  }

  @Test
  void testMultiSchemaEvolution() throws Exception {
    SparkSession spark = null;
    try {
      spark = getSparkSession();
      spark.sql(
          "CREATE TABLE openhouse.multiSchemaTest.t1 (name string, id int) TBLPROPERTIES ('openhouse.tableType' = 'REPLICA_TABLE');");
      spark.sql("INSERT INTO openhouse.multiSchemaTest.t1 VALUES ('Alice', 1)");
      spark.sql("INSERT INTO openhouse.multiSchemaTest.t1 VALUES ('Bob', 2), ('Charlie', 3)");
      TableIdentifier tableIdentifier = TableIdentifier.of("multiSchemaTest", "t1");
      Catalog ohCatalog = getOpenHouseCatalog(spark);
      TableOperations ops = ops(ohCatalog, tableIdentifier);
      Schema evolvedSchema =
          new Schema(
              Types.NestedField.optional(1, "name", Types.StringType.get()),
              Types.NestedField.optional(2, "id", Types.IntegerType.get()),
              Types.NestedField.optional(3, "newCol", Types.IntegerType.get()));
      Schema finalEvolvedSchema =
          new Schema(
              Types.NestedField.optional(1, "name", Types.StringType.get()),
              Types.NestedField.optional(2, "id", Types.IntegerType.get()),
              Types.NestedField.optional(3, "newCol1", Types.IntegerType.get()),
              Types.NestedField.optional(4, "newCol2", Types.IntegerType.get()));

      TableMetadata metadata = ops.current();
      TableMetadata evolvedMetadata =
          TableMetadata.buildFrom(metadata).addSchema(evolvedSchema).build();
      TableMetadata finalEvolvedMetadata =
          TableMetadata.buildFrom(evolvedMetadata)
              .addSchema(finalEvolvedSchema)
              .setCurrentSchema(2)
              .build();

      Assertions.assertEquals(finalEvolvedMetadata.schemas().size(), 3);
      ops.commit(metadata, finalEvolvedMetadata);
      TableMetadata result = ops.current();
      Assertions.assertEquals(3, result.schemas().size());
      Assertions.assertTrue(result.schema().sameSchema(finalEvolvedSchema));
    } finally {
      if (spark != null) {
        spark.sql("DROP TABLE openhouse.multiSchemaTest.t1");
      }
    }
  }

  @Test
  void testSingleSchemaEvolution() throws Exception {
    SparkSession spark = null;
    try {
      spark = getSparkSession();
      spark.sql(
          "CREATE TABLE openhouse.multiSchemaTest.t1 (name string, id int) TBLPROPERTIES ('openhouse.tableType' = 'REPLICA_TABLE');");
      spark.sql("INSERT INTO openhouse.multiSchemaTest.t1 VALUES ('Alice', 1)");
      spark.sql("INSERT INTO openhouse.multiSchemaTest.t1 VALUES ('Bob', 2), ('Charlie', 3)");
      TableIdentifier tableIdentifier = TableIdentifier.of("multiSchemaTest", "t1");
      Catalog ohCatalog = getOpenHouseCatalog(spark);
      TableOperations ops = ops(ohCatalog, tableIdentifier);
      Schema finalEvolvedSchema =
          new Schema(
              Types.NestedField.optional(1, "name", Types.StringType.get()),
              Types.NestedField.optional(2, "id", Types.IntegerType.get()),
              Types.NestedField.optional(3, "newCol", Types.IntegerType.get()));

      TableMetadata metadata = ops.current();
      TableMetadata finalEvolvedMetadata =
          TableMetadata.buildFrom(metadata).addSchema(finalEvolvedSchema).setCurrentSchema(1).build();

      Assertions.assertEquals(finalEvolvedMetadata.schemas().size(), 2);
      ops.commit(metadata, finalEvolvedMetadata);
      TableMetadata result = ops.current();
      Assertions.assertEquals(2, result.schemas().size());
      Assertions.assertTrue(result.schema().sameSchema(finalEvolvedSchema));
    } finally {
      if (spark != null) {
        spark.sql("DROP TABLE openhouse.multiSchemaTest.t1");
      }
    }
  }

  @Test
  void testMultiSchemaEvolutionColumnOrderingOnCreate() throws Exception {
    SparkSession spark = null;
    try {
      spark = getSparkSession();
      TableIdentifier tableIdentifier = TableIdentifier.of("multiSchemaTest", "t2");
      Catalog ohCatalog = getOpenHouseCatalog(spark);
      Schema schemaColumnOrdering =
          new Schema(
              Types.NestedField.optional(2, "name", Types.StringType.get()),
              Types.NestedField.optional(1, "id", Types.IntegerType.get()),
              Types.NestedField.optional(4, "newCol1", Types.IntegerType.get()),
              Types.NestedField.optional(3, "newCol2", Types.IntegerType.get()));
      Map<String, String> tableProperties = new HashMap<>();
      tableProperties.put("openhouse.tableType", "REPLICA_TABLE");
      tableProperties.put("openhouse.isTableReplicated", "true");
      tableProperties.put("client.table.schema", SchemaParser.toJson(schemaColumnOrdering));
      ohCatalog.createTable(
          tableIdentifier, schemaColumnOrdering, PartitionSpec.unpartitioned(), tableProperties);
      TableOperations ops = ops(ohCatalog, tableIdentifier);
      TableMetadata metadata = ops.current();
      Assertions.assertEquals(metadata.schema().findColumnName(2), "name");
      Assertions.assertTrue(metadata.schema().sameSchema(schemaColumnOrdering));
      Schema schemaColumnOrdering2 =
          new Schema(
              Types.NestedField.optional(2, "name", Types.StringType.get()),
              Types.NestedField.optional(1, "id", Types.IntegerType.get()),
              Types.NestedField.optional(4, "newCol1", Types.IntegerType.get()),
              Types.NestedField.optional(3, "newCol2", Types.IntegerType.get()),
              Types.NestedField.optional(5, "newCol3", Types.IntegerType.get()));

      Schema schemaColumnOrdering3 =
          new Schema(
              Types.NestedField.optional(2, "name", Types.StringType.get()),
              Types.NestedField.optional(1, "id", Types.IntegerType.get()),
              Types.NestedField.optional(4, "newCol1", Types.IntegerType.get()),
              Types.NestedField.optional(3, "newCol2", Types.IntegerType.get()),
              Types.NestedField.optional(5, "newCol3", Types.IntegerType.get()),
              Types.NestedField.optional(6, "newCol4", Types.IntegerType.get()),
              Types.NestedField.optional(7, "newCol5", Types.IntegerType.get()));

      Schema schemaColumnOrdering4 =
          new Schema(
              Types.NestedField.optional(2, "name", Types.StringType.get()),
              Types.NestedField.optional(1, "id", Types.IntegerType.get()),
              Types.NestedField.optional(4, "newCol1", Types.IntegerType.get()),
              Types.NestedField.optional(3, "newCol2", Types.IntegerType.get()),
              Types.NestedField.optional(5, "newCol3", Types.IntegerType.get()),
              Types.NestedField.optional(6, "newCol4", Types.IntegerType.get()),
              Types.NestedField.optional(7, "newCol5", Types.IntegerType.get()),
              Types.NestedField.optional(8, "newCol6", Types.IntegerType.get()));

      TableMetadata evolvedMetadata =
          TableMetadata.buildFrom(metadata).addSchema(schemaColumnOrdering2).build();
      TableMetadata secondaryEvolvedMetadata =
          TableMetadata.buildFrom(evolvedMetadata)
              .addSchema(schemaColumnOrdering3)
              .setCurrentSchema(2)
              .build();
      TableMetadata finalEvolvedMetadata =
          TableMetadata.buildFrom(secondaryEvolvedMetadata)
              .addSchema(schemaColumnOrdering4)
              .setCurrentSchema(3)
              .build();

      Assertions.assertEquals(finalEvolvedMetadata.schemas().size(), 4);
      ops.commit(metadata, finalEvolvedMetadata);
      TableMetadata result = ops.current();
      Assertions.assertEquals(4, result.schemas().size());
      // Validate ordering of columns persists on creation
      Assertions.assertEquals(result.schema().findColumnName(2), "name");
      Assertions.assertTrue(result.schema().sameSchema(schemaColumnOrdering4));
    } finally {
      if (spark != null) {
        spark.sql("DROP TABLE openhouse.multiSchemaTest.t2");
      }
    }
  }
}
