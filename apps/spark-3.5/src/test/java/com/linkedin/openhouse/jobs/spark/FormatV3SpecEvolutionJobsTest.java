package com.linkedin.openhouse.jobs.spark;

import static com.linkedin.openhouse.jobs.spark.FormatV3Tables.DELETED_IDS;
import static com.linkedin.openhouse.jobs.spark.FormatV3Tables.PARTITION_DAYS_AGO;
import static com.linkedin.openhouse.jobs.spark.FormatV3Tables.liveDataFiles;
import static com.linkedin.openhouse.jobs.spark.FormatV3Tables.snapshotIds;

import com.linkedin.openhouse.common.stats.model.ColumnData;
import com.linkedin.openhouse.common.stats.model.CommitEventTablePartitionStats;
import com.linkedin.openhouse.common.stats.model.CommitEventTablePartitions;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The maintenance jobs on a table whose partition spec evolved (work item X10), format v3 and v2.
 *
 * <p>What OpenHouse allows: a spec change on an existing table is refused, natively and over the
 * Iceberg REST routes, by the partition evolution rule (OpenHouseInternalRepositoryImpl
 * .checkPartitionSpecEvolution; pinned below). A table holds several specs only from birth: an
 * Iceberg REST create transaction keeps the specs its client built (pull request 134), and a
 * replace keeps the old specs (not built here). So the shape is built through the REST routes: a
 * create transaction with spec 0 = (day(ts), identity(category)) that appends one data file per
 * day, replaces {@code category} by {@code region} in the spec (spec 1 = (day(ts),
 * identity(region))), and appends one more file per day under spec 1 (each spec one the native
 * partitioning model expresses, time partitioning plus clustering: a staged create whose first spec
 * is not is refused); then Spark writes through the native catalog: the base fixture's DELETEs,
 * UPDATE and MERGE (deletion vectors on v3), on files of both specs. 12 snapshots, ids laid out as
 * in the base fixture (file k = 0 of each day under spec 0, k = 1 under spec 1).
 *
 * <p>The metadata tables' {@code partition} struct is the union of every spec's fields, by field
 * id: (ts_day, category, region). The current spec is (ts_day, region).
 */
public class FormatV3SpecEvolutionJobsTest extends FormatV3ShapeJobsTestBase {
  @Override
  String db() {
    return "x10spec";
  }

  private static final Schema SCHEMA =
      new Schema(
          Types.NestedField.optional(1, "id", Types.LongType.get()),
          Types.NestedField.optional(2, "data", Types.StringType.get()),
          Types.NestedField.optional(3, "ts", Types.TimestampType.withZone()),
          Types.NestedField.optional(4, "category", Types.StringType.get()),
          Types.NestedField.optional(5, "region", Types.StringType.get()));

  @Override
  String extraRow(long id) {
    return "(" + id + ", 'extra-" + id + "', " + ts(0, 59) + ", 'x', 'r1')";
  }

  private static String category(int p) {
    return p % 2 == 0 ? "a" : "b";
  }

  private List<Record> records(Schema schema, int p, int k) {
    List<Record> records = new ArrayList<>();
    for (int r = 0; r < 4; r++) {
      long id = p * 8L + k * 4L + r + 1;
      Record record = GenericRecord.create(schema);
      record.setField("id", id);
      record.setField("data", "row-" + id);
      record.setField(
          "ts",
          tables.today.minusDays(PARTITION_DAYS_AGO[p]).plusMinutes(id).atOffset(ZoneOffset.UTC));
      record.setField("category", k == 0 ? category(p) : "c");
      record.setField("region", k == 0 ? null : "r1");
      records.add(record);
    }
    return records;
  }

  @Override
  Shape createShape(int formatVersion, String name) {
    SparkSession spark = session(formatVersion);
    String fqtn = db() + "." + name + "_v" + formatVersion;
    String t = "openhouse." + fqtn;
    spark.sql("DROP TABLE IF EXISTS " + t);
    RESTCatalog rest = rest(formatVersion);
    Map<String, String> properties = new HashMap<>();
    properties.put("write.delete.mode", "merge-on-read");
    properties.put("write.update.mode", "merge-on-read");
    properties.put("write.merge.mode", "merge-on-read");
    properties.put("write.format.default", "orc");
    if (formatVersion == 3) {
      properties.put("format-version", "3");
    }
    Transaction create =
        rest.buildTable(TableIdentifier.parse(fqtn), SCHEMA)
            .withPartitionSpec(
                PartitionSpec.builderFor(SCHEMA).day("ts").identity("category").build())
            .withProperties(properties)
            .createTransaction();
    Table staged = create.table();
    for (int p = 0; p < PARTITION_DAYS_AGO.length; p++) {
      DataFile file = IcebergFiles.writeData(staged, staged.spec(), records(staged.schema(), p, 0));
      create.newAppend().appendFile(file).commit();
    }
    create.updateSpec().removeField("category").addField("region").commit();
    for (int p = 0; p < PARTITION_DAYS_AGO.length; p++) {
      DataFile file = IcebergFiles.writeData(staged, staged.spec(), records(staged.schema(), p, 1));
      create.newAppend().appendFile(file).commit();
    }
    create.commitTransaction();
    tables.rowLevelWrites(spark, t);

    Table table = ops(formatVersion).getTable(fqtn);
    Assertions.assertEquals(formatVersion, FormatV3Tables.formatVersion(table));
    Assertions.assertEquals(2, table.specs().size(), "two specs");
    Assertions.assertEquals(1, table.spec().specId());
    Assertions.assertEquals(
        Arrays.asList("ts_day", "region"),
        table.spec().fields().stream().map(f -> f.name()).collect(Collectors.toList()));
    Assertions.assertEquals(12, snapshotIds(table).size());
    Set<Integer> specIds =
        liveDataFiles(table, table.currentSnapshot()).stream()
            .map(DataFile::specId)
            .collect(Collectors.toCollection(TreeSet::new));
    Assertions.assertEquals(
        new TreeSet<>(Arrays.asList(0, 1)), specIds, "live files of both specs");
    Assertions.assertEquals(
        Arrays.asList("ts_day", "category", "region"),
        partitionStructFields(spark, t),
        "the metadata tables' partition struct is the union of both specs");
    return new Shape(fqtn, deleted(MAIN, new TreeSet<>(DELETED_IDS)));
  }

  /** The field names of the {@code partition} struct of the table's metadata tables. */
  private static List<String> partitionStructFields(SparkSession spark, String t) {
    return Arrays.asList(
        ((StructType)
                spark
                    .sql("SELECT data_file.partition FROM " + t + ".all_entries")
                    .schema()
                    .fields()[0]
                    .dataType())
            .fieldNames());
  }

  /**
   * A spec change on an existing table is refused natively (Spark through the OpenHouse catalog).
   */
  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void aSpecChangeOnAnExistingTableIsRefusedNatively(int formatVersion) {
    String fqtn = tables.createAndInsert(formatVersion, "refused_native", "orc");
    long head = ops(formatVersion).getTable(fqtn).currentSnapshot().snapshotId();
    Exception refused =
        Assertions.assertThrows(
            Exception.class,
            () ->
                session(formatVersion)
                    .sql("ALTER TABLE openhouse." + fqtn + " ADD PARTITION FIELD data"));
    Assertions.assertTrue(
        String.valueOf(refused.getMessage()).contains("Evolution of table partitioning"),
        String.valueOf(refused.getMessage()));
    Table table = ops(formatVersion).getTable(fqtn);
    Assertions.assertEquals(1, table.specs().size());
    Assertions.assertEquals(head, table.currentSnapshot().snapshotId());
  }

  /** ... and over the Iceberg REST routes. */
  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void aSpecChangeOnAnExistingTableIsRefusedOverRest(int formatVersion) {
    String fqtn = tables.createAndInsert(formatVersion, "refused_rest", "orc");
    Table table = rest(formatVersion).loadTable(TableIdentifier.parse(fqtn));
    Exception refused =
        Assertions.assertThrows(
            Exception.class, () -> table.updateSpec().addField("data").commit());
    Assertions.assertTrue(
        String.valueOf(refused.getMessage()).contains("Evolution of table partitioning"),
        String.valueOf(refused.getMessage()));
    Assertions.assertEquals(1, ops(formatVersion).getTable(fqtn).specs().size());
  }

  /**
   * The suspect from pull request 134: partition-level stats label each value of the metadata
   * tables' partition struct. That struct is the union of every spec's fields; every label must
   * name the field the value belongs to: a ts_day value is a date, a category value is a, b.
   */
  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void partitionStatsLabelEachValueWithItsOwnField(int formatVersion) {
    Shape shape = createShape(formatVersion, "stats_labels");
    Operations ops = ops(formatVersion);
    List<CommitEventTablePartitions> events = ops.collectCommitEventTablePartitions(shape.fqtn);
    List<CommitEventTablePartitionStats> stats =
        ops.collectCommitEventTablePartitionStats(shape.fqtn);
    Assertions.assertFalse(events.isEmpty());
    Assertions.assertFalse(stats.isEmpty());
    List<List<ColumnData>> labels = new ArrayList<>();
    events.forEach(e -> labels.add(e.getPartitionData()));
    stats.forEach(s -> labels.add(s.getPartitionData()));
    boolean sawCategory = false;
    boolean sawRegion = false;
    for (List<ColumnData> partition : labels) {
      Map<String, String> byName = new HashMap<>();
      partition.forEach(c -> byName.put(c.getColumnName(), String.valueOf(c.getValue())));
      Assertions.assertTrue(
          byName.containsKey("ts_day") && byName.get("ts_day").matches("\\d{4}-\\d{2}-\\d{2}"),
          "ts_day labels a date: " + byName);
      if (byName.containsKey("category")) {
        sawCategory = true;
        Assertions.assertTrue(
            Arrays.asList("a", "b").contains(byName.get("category")), "category: " + byName);
      }
      if (byName.containsKey("region")) {
        sawRegion = true;
        Assertions.assertEquals("r1", byName.get("region"), "region: " + byName);
      }
      Assertions.assertTrue(
          Arrays.asList("ts_day", "category", "region").containsAll(byName.keySet()),
          byName.toString());
    }
    Assertions.assertTrue(sawCategory, "spec 0 partitions keep their category label");
    Assertions.assertTrue(sawRegion, "spec 1 partitions keep their region label");
  }

  /**
   * On a table with one spec the metadata tables' partition struct is exactly that spec's fields in
   * order, so labels taken from the struct equal those taken from the spec by position: the output
   * for single-spec tables does not change.
   */
  @ParameterizedTest(name = "format v{0}")
  @ValueSource(ints = {2, 3})
  void onASingleSpecTablePartitionLabelsAreTheSpecsFields(int formatVersion) {
    String fqtn = tables.createTable(formatVersion, "single_spec_labels", "orc");
    String t = "openhouse." + fqtn;
    SparkSession spark = session(formatVersion);
    Table table = ops(formatVersion).getTable(fqtn);
    Assertions.assertEquals(
        table.spec().fields().stream().map(f -> f.name()).collect(Collectors.toList()),
        partitionStructFields(spark, t));
    for (CommitEventTablePartitions event :
        ops(formatVersion).collectCommitEventTablePartitions(fqtn)) {
      Assertions.assertEquals(1, event.getPartitionData().size());
      Assertions.assertEquals("ts_day", event.getPartitionData().get(0).getColumnName());
    }
  }
}
