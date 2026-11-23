package com.linkedin.openhouse.spark.e2e.ddl;

import static com.linkedin.openhouse.spark.MockHelpersSpark3_5.*;
import static com.linkedin.openhouse.spark.SparkTestBase.*;

import com.linkedin.openhouse.gen.tables.client.model.GetTableResponseBody;
import com.linkedin.openhouse.spark.SparkTestBase;
import java.util.HashMap;
import java.util.Map;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.BadRequestException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SparkTestBase.class)
public class AlterTableTestSpark3_5 {

  @Test
  public void testSetTableProps() {
    final String key = "key";
    final String value = "value";

    GetTableResponseBody existingTable =
        mockGetTableResponseBody(
            "dbAlter",
            "tb1",
            "c1",
            "dbAlter.tb1.c1",
            "UUID",
            mockTableLocationDefaultSchema(TableIdentifier.of("dbAlter", "tb1")),
            "v1",
            baseSchema,
            null,
            null);
    mockTableService.enqueue(mockResponse(200, existingTable)); // doRefresh()
    mockTableService.enqueue(mockResponse(200, existingTable)); // doRefresh()
    mockTableService.enqueue(mockResponse(200, existingTable)); // doRefresh()

    Map<String, String> tblProps = new HashMap<>();
    tblProps.put(key, value);
    mockTableService.enqueue(
        mockResponse(200, decorateResponse(existingTable, tblProps))); // doCommit()
    mockTableService.enqueue(mockResponse(200, existingTable)); // doRefresh()

    Assertions.assertDoesNotThrow(
        () ->
            spark.sql(
                String.format(
                    "ALTER TABLE openhouse.dbAlter.tb1 SET TBLPROPERTIES('%s'='%s')", key, value)));
  }

  @Test
  public void testSetPoliciesTablePropsBlocked() {
    GetTableResponseBody existingTable =
        mockGetTableResponseBody(
            "dbAlter",
            "tb2",
            "c1",
            "dbAlter.tb2.c1",
            "UUID",
            mockTableLocationDefaultSchema(TableIdentifier.of("dbAlter", "tb2")),
            "v1",
            baseSchema,
            null,
            null);
    mockTableService.enqueue(mockResponse(200, existingTable)); // doRefresh()
    mockTableService.enqueue(mockResponse(200, existingTable)); // doRefresh()
    mockTableService.enqueue(mockResponse(200, existingTable)); // doRefresh()

    // Server rejects the commit because 'policies' is a preserved key
    mockTableService.enqueue(mockResponse(400, existingTable)); // doCommit()

    // Ported from the Spark 3.1 itest's AlterTableTest, which asserted only Exception.class. On
    // Spark
    // 3.1 (Iceberg 1.2) and on Spark 3.5 (Iceberg 1.5.2 and 1.12) the statement fails the same way:
    // three reads, then the PUT, whose 400 the client turns into Iceberg's BadRequestException.
    BadRequestException exception =
        Assertions.assertThrows(
            BadRequestException.class,
            () ->
                spark.sql(
                    "ALTER TABLE openhouse.dbAlter.tb2 SET TBLPROPERTIES('policies'='{\"retention\":{\"count\":1,\"granularity\":\"DAY\"}}')"));
    Assertions.assertTrue(exception.getMessage().startsWith("400 "), exception.getMessage());
  }

  @Test
  public void testUnsetTableProps() {
    final String key = "key";
    final String value = "value";
    Map<String, String> tblProps = new HashMap<>();
    tblProps.put(key, value);

    GetTableResponseBody existingTable =
        mockGetTableResponseBody(
            "dbUnset",
            "tb1",
            "c1",
            "dbUnset.tb1.c1",
            "UUID",
            mockTableLocationDefaultSchema(TableIdentifier.of("dbUnset", "tb1")),
            "v1",
            baseSchema,
            null,
            null);
    GetTableResponseBody decoratedTable = decorateResponse(existingTable, tblProps);
    mockTableService.enqueue(mockResponse(200, existingTable)); // doRefresh()
    mockTableService.enqueue(mockResponse(200, existingTable)); // doRefresh()
    mockTableService.enqueue(mockResponse(200, decoratedTable)); // doRefresh()

    Assertions.assertDoesNotThrow(
        () ->
            spark.sql(
                String.format("ALTER TABLE openhouse.dbUnset.tb1 UNSET TBLPROPERTIES('%s')", key)));
  }
}
