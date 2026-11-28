package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.common.security.DummyTokenInterceptor;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * A table an Iceberg {@link RESTCatalog} creates through a create transaction that changed the
 * schema, the partition spec and the sort order before it committed keeps that history, and the
 * native OpenHouse path (the OpenHouse Java client, and Spark SQL through the {@code openhouse}
 * catalog) reads and writes it: inserts land under the final spec, a later schema change through
 * Spark and a later commit through the REST catalog keep the history, and both clients see the same
 * metadata after each step.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class IcebergRestCreateHistoryTest extends OpenHouseSparkITest {
  private static final String DB = "irc_hist";

  private static final Schema SCHEMA =
      new Schema(
          Types.NestedField.required(1, "id", Types.LongType.get()),
          Types.NestedField.optional(2, "data", Types.StringType.get()));

  private SparkSession spark;
  private Catalog openhouse;
  private RESTCatalog rest;

  @BeforeAll
  void start() throws Exception {
    spark = getSparkSession();
    openhouse = getOpenHouseCatalog(spark);
    Map<String, String> properties = new HashMap<>();
    properties.put("uri", getOpenHouseLocalServerURI() + "/iceberg");
    properties.put("token", new DummyTokenInterceptor.DummySecurityJWT("irc_hist").buildNoopJWT());
    rest =
        (RESTCatalog)
            CatalogUtil.loadCatalog(
                RESTCatalog.class.getName(),
                "openhouse",
                properties,
                spark.sparkContext().hadoopConfiguration());
    try {
      openhouse.dropTable(id());
    } catch (RuntimeException ignored) {
      // not there
    }
  }

  @AfterAll
  void stop() throws Exception {
    rest.close();
  }

  private static TableIdentifier id() {
    return TableIdentifier.of(DB, "t");
  }

  private static TableMetadata current(Table table) {
    return ((HasTableOperations) table).operations().current();
  }

  private static String withoutLastUpdated(TableMetadata metadata) {
    return TableMetadataParser.toJson(metadata)
        .replaceFirst("\"last-updated-ms\"\\s*:\\s*\\d+", "\"last-updated-ms\":0");
  }

  /** The OpenHouse client and a fresh RESTCatalog load show the same table; returns it. */
  private TableMetadata sameView() {
    TableMetadata viaRest = current(rest.loadTable(id()));
    TableMetadata viaOpenHouse = current(openhouse.loadTable(id()));
    Assertions.assertEquals(withoutLastUpdated(viaOpenHouse), withoutLastUpdated(viaRest));
    return viaOpenHouse;
  }

  private List<String> rows(String sql) {
    return spark.sql(sql).collectAsList().stream().map(Row::toString).collect(Collectors.toList());
  }

  /** The spec id of each data file a scan of the table plans, sorted. */
  private List<Integer> specIds() throws Exception {
    Table table = openhouse.loadTable(id());
    try (org.apache.iceberg.io.CloseableIterable<FileScanTask> tasks =
        table.newScan().planFiles()) {
      return StreamSupport.stream(tasks.spliterator(), false)
          .map(task -> task.file().specId())
          .sorted()
          .collect(Collectors.toList());
    }
  }

  @Test
  void aTableCreatedWithHistoryIsReadAndWrittenThroughTheNativePath() throws Exception {
    Transaction create =
        rest.buildTable(id(), SCHEMA)
            .withPartitionSpec(PartitionSpec.builderFor(SCHEMA).identity("data").build())
            .createTransaction();
    create.updateSchema().addColumn("p", Types.StringType.get()).commit();
    create.updateSpec().addField("p").commit();
    create.replaceSortOrder().asc("id").commit();
    create.commitTransaction();

    TableMetadata created = sameView();
    Assertions.assertEquals(2, created.schemas().size());
    Assertions.assertEquals(1, created.currentSchemaId());
    Assertions.assertEquals(2, created.specs().size());
    Assertions.assertEquals(1, created.defaultSpecId());
    Assertions.assertEquals(1, created.defaultSortOrderId());

    String t = "openhouse." + DB + ".t";
    spark.sql("INSERT INTO " + t + " VALUES (1, 'a', 'x'), (2, 'b', 'y')");
    Assertions.assertEquals(
        Arrays.asList("[1,a,x]", "[2,b,y]"), rows("SELECT * FROM " + t + " ORDER BY id"));
    Assertions.assertEquals(Arrays.asList(1, 1), specIds());

    spark.sql("ALTER TABLE " + t + " ADD COLUMN extra int");
    spark.sql("INSERT INTO " + t + " VALUES (3, 'c', 'x', 7)");
    TableMetadata afterSpark = sameView();
    Assertions.assertEquals(3, afterSpark.schemas().size());
    Assertions.assertEquals(2, afterSpark.specs().size());
    Assertions.assertEquals(1, afterSpark.defaultSpecId());

    Table viaRest = rest.loadTable(id());
    viaRest.updateSchema().addColumn("later", Types.StringType.get()).commit();
    TableMetadata afterRest = sameView();
    Assertions.assertEquals(4, afterRest.schemas().size());
    spark.sql("REFRESH TABLE " + t);
    spark.sql("INSERT INTO " + t + " VALUES (4, 'd', 'y', 8, 'z')");
    Assertions.assertEquals(
        Arrays.asList("[1,a,x,null,null]", "[2,b,y,null,null]", "[3,c,x,7,null]", "[4,d,y,8,z]"),
        rows("SELECT * FROM " + t + " ORDER BY id"));
    Assertions.assertEquals(Arrays.asList(1, 1, 1, 1), specIds());
    sameView();
  }
}
