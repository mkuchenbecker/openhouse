package com.linkedin.openhouse.jobs.util;

import com.linkedin.openhouse.common.stats.model.ColumnData;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.iceberg.MetadataTableType;
import org.apache.iceberg.MetadataTableUtils;
import org.apache.iceberg.MetricsUtil;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.hadoop.HadoopTables;
import org.apache.iceberg.types.Types;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The stats collection job's type-specific code on the two format v3 types the tables service takes
 * (work item X5): the job collects column metrics for the columns of a table's {@code
 * readable_metrics} and converts each bound by the column's Iceberg type. An {@code unknown} column
 * is in {@code readable_metrics} with bounds that are always null, and yields a null count and no
 * bounds; a {@code variant} column is not in {@code readable_metrics} (Iceberg keeps metrics of
 * primitive columns only), so the job collects nothing for it. Format v3 needs the Iceberg 1.12
 * line, so these tests are skipped on the reference line.
 */
public class TableStatsFormatV3TypesTest {
  private static final String SCHEMA =
      "{\"type\":\"struct\",\"fields\":["
          + "{\"id\":1,\"name\":\"id\",\"required\":false,\"type\":\"long\"},"
          + "{\"id\":2,\"name\":\"c\",\"required\":false,\"type\":\"unknown\"},"
          + "{\"id\":3,\"name\":\"v\",\"required\":false,\"type\":\"variant\"}]}";

  @TempDir java.nio.file.Path warehouse;

  private static Schema schema() {
    Schema schema;
    try {
      schema = SchemaParser.fromJson(SCHEMA);
    } catch (RuntimeException e) {
      // LinkedIn's Iceberg 1.5.2 has neither type.
      Assumptions.assumeTrue(false, "this Iceberg has no format v3 types: " + e.getMessage());
      throw e;
    }
    return schema;
  }

  @Test
  void readableMetricsHoldTheUnknownColumnAndNotTheVariantColumn() {
    Schema schema = schema();
    Table table =
        new HadoopTables()
            .create(
                schema,
                PartitionSpec.unpartitioned(),
                java.util.Collections.singletonMap(TableProperties.FORMAT_VERSION, "3"),
                warehouse.resolve("t").toString());
    Table dataFiles =
        MetadataTableUtils.createMetadataTableInstance(table, MetadataTableType.DATA_FILES);
    Schema readable =
        MetricsUtil.readableMetricsSchema(table.schema(), dataFiles.schema())
            .findField(MetricsUtil.READABLE_METRICS)
            .type()
            .asStructType()
            .asSchema();
    Assertions.assertEquals(
        Arrays.asList("c", "id"),
        readable.columns().stream().map(Types.NestedField::name).collect(Collectors.toList()));
    Assertions.assertEquals(
        "unknown",
        readable.findField("c").type().asStructType().field("lower_bound").type().toString());
  }

  @Test
  void anUnknownColumnYieldsANullCountAndNoBounds() {
    Schema schema = schema();
    Assertions.assertNull(
        TableStatsCollectorUtil.convertValueToColumnData("c", null, schema.findType("c")));
    StructType rowType =
        new StructType(
            new StructField[] {
              DataTypes.createStructField("c_null_count", DataTypes.LongType, true),
              DataTypes.createStructField("c_nan_count", DataTypes.LongType, true),
              DataTypes.createStructField("c_min_value", DataTypes.NullType, true),
              DataTypes.createStructField("c_max_value", DataTypes.NullType, true),
              DataTypes.createStructField("c_column_size", DataTypes.LongType, true)
            });
    Row row = new GenericRowWithSchema(new Object[] {3L, 0L, null, null, 0L}, rowType);
    Map<String, List<ColumnData>> metrics =
        TableStatsCollectorUtil.extractColumnMetricsFromAggregatedRow(
            row, schema, java.util.Collections.singletonList("c"));
    Assertions.assertEquals(1, metrics.get("nullCount").size());
    Assertions.assertEquals("c", metrics.get("nullCount").get(0).getColumnName());
    Assertions.assertTrue(metrics.get("minValue").isEmpty());
    Assertions.assertTrue(metrics.get("maxValue").isEmpty());
  }
}
