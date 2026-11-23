package com.linkedin.openhouse.spark.statementtest;

import com.linkedin.openhouse.spark.capabilities.CapabilityTestCatalogs;
import com.linkedin.openhouse.spark.capabilities.FakeRestCatalog;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.hadoop.HadoopCatalog;
import org.apache.iceberg.types.Types;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.analysis.ResolvedTable;
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan;
import org.apache.spark.sql.catalyst.plans.logical.RenameTable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import scala.jdk.javaapi.CollectionConverters;

/**
 * The target of {@code ALTER TABLE ... RENAME TO} for a table in an OpenHouse catalog.
 *
 * <p>Spark hands a rename target to the catalog as it was written: {@code RENAME TO
 * openhouse.db.t2} arrives with the namespace {@code [openhouse, db]}. The OpenHouse catalog class
 * ({@code OpenHouseCatalog.renameTable}) read a target namespace of two levels as {@code
 * <catalog>.<database>}: it took its own name off and refused any other name as a rename across
 * catalogs. Iceberg's {@code RESTCatalog} does neither, so the extensions do it during analysis,
 * and these tests say exactly what: the analyzed plan for a parsed statement, and what reaches the
 * catalog when the statement runs. The outcomes named "as the OpenHouse catalog" are the ones Spark
 * 3.5 with that catalog class gives for the same statement (run there for N6).
 *
 * <p>The catalogs: {@code openhouse} is Iceberg's {@code RESTCatalog} on a server that advertises
 * OpenHouse capabilities; {@code rest_plain} is a {@code RESTCatalog} whose server advertises none;
 * {@code oh_class} is a catalog class that implements the capability interfaces; {@code
 * hadoop_plain} is another Iceberg catalog. Each records the renames it is asked for and performs
 * none. The tables are {@code db.t} and, in a database named like the catalog, {@code openhouse.t}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RenameTableTargetTest {

  /** {@code <catalog>: <from> -> <to>} for every rename a catalog was asked for. */
  private static final List<String> RENAMES = Collections.synchronizedList(new ArrayList<>());

  private static final String TABLES = "test.tables";

  private SparkSession spark;

  @BeforeAll
  void start() throws Exception {
    String warehouse = Files.createTempDirectory("rename-target").toString();
    HadoopCatalog tables = new HadoopCatalog(new Configuration(), warehouse + "/tables");
    Schema schema = new Schema(Types.NestedField.required(1, "id", Types.LongType.get()));
    tables.createTable(TableIdentifier.of("db", "t"), schema);
    tables.createTable(TableIdentifier.of("openhouse", "t"), schema);
    SparkSession.Builder builder =
        SparkSession.builder()
            .master("local[1]")
            .config(
                "spark.sql.extensions",
                "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions,"
                    + "com.linkedin.openhouse.spark.extensions.OpenhouseSparkSessionExtensions")
            .config("spark.sql.warehouse.dir", warehouse + "/spark");
    for (String catalog : Arrays.asList("openhouse", "rest_plain", "oh_class", "hadoop_plain")) {
      String prefix = "spark.sql.catalog." + catalog;
      builder
          .config(prefix, "org.apache.iceberg.spark.SparkCatalog")
          .config(prefix + "." + TABLES, warehouse + "/tables")
          .config(prefix + ".warehouse", warehouse + "/tables");
    }
    spark =
        builder
            .config("spark.sql.catalog.openhouse.catalog-impl", RecordingRest.class.getName())
            .config("spark.sql.catalog.openhouse.uri", "http://localhost:1")
            .config(
                "spark.sql.catalog.openhouse." + FakeRestCatalog.SERVER_ADVERTISES,
                "policies,grants,unlock")
            .config("spark.sql.catalog.rest_plain.catalog-impl", RecordingRest.class.getName())
            .config("spark.sql.catalog.rest_plain.uri", "http://localhost:1")
            .config(
                "spark.sql.catalog.oh_class.catalog-impl", RecordingOpenHouseClass.class.getName())
            .config("spark.sql.catalog.hadoop_plain.catalog-impl", RecordingHadoop.class.getName())
            .getOrCreate();
  }

  @AfterAll
  void stop() throws Exception {
    spark.close();
  }

  @BeforeEach
  void forget() {
    RENAMES.clear();
  }

  // ---------------------------------------------------------------- an OpenHouse catalog

  @Test
  void theCatalogsOwnNameIsTakenOffTheTarget() throws Exception {
    RenameTable analyzed = analyzed("ALTER TABLE openhouse.db.t RENAME TO openhouse.db.t2");
    Assertions.assertEquals(Arrays.asList("db", "t2"), target(analyzed));
    Assertions.assertEquals("db.t", source(analyzed));
    Assertions.assertFalse(analyzed.isView());

    spark.sql("ALTER TABLE openhouse.db.t RENAME TO openhouse.db.t2");
    Assertions.assertEquals(Collections.singletonList("openhouse: db.t -> db.t2"), RENAMES);
  }

  @Test
  void theCatalogsOwnNameIsTakenOffWhenItIsTheCurrentCatalog() {
    spark.sql("USE openhouse.db");
    try {
      spark.sql("ALTER TABLE t RENAME TO openhouse.db.t2");
      spark.sql("ALTER TABLE db.t RENAME TO openhouse.db.t3");
    } finally {
      spark.sql("USE spark_catalog");
    }
    Assertions.assertEquals(
        Arrays.asList("openhouse: db.t -> db.t2", "openhouse: db.t -> db.t3"), RENAMES);
  }

  @Test
  void anotherCatalogsNameIsRefusedAsTheOpenHouseCatalogRefusedIt() {
    // Neither a name no catalog has, nor the name of another catalog of the session, nor the
    // catalog's own name in another case (the OpenHouse catalog compared with equals).
    for (String other : Arrays.asList("other_catalog", "rest_plain", "OpenHouse")) {
      UnsupportedOperationException refused =
          Assertions.assertThrows(
              UnsupportedOperationException.class,
              () -> spark.sql("ALTER TABLE openhouse.db.t RENAME TO " + other + ".db.t2"));
      Assertions.assertEquals(
          "Cannot rename tables across catalogs: from=openhouse.db.t, to=" + other + ".db.t2",
          refused.getMessage());
    }
    Assertions.assertEquals(Collections.emptyList(), RENAMES);
  }

  @Test
  void aTargetThatNamesNoCatalogIsLeftAsWritten() throws Exception {
    Assertions.assertEquals(
        Arrays.asList("db", "t2"), target(analyzed("ALTER TABLE openhouse.db.t RENAME TO db.t2")));
    Assertions.assertEquals(
        Collections.singletonList("t2"),
        target(analyzed("ALTER TABLE openhouse.db.t RENAME TO t2")));
    Assertions.assertEquals(
        Arrays.asList("other_db", "t2"),
        target(analyzed("ALTER TABLE openhouse.db.t RENAME TO other_db.t2")));

    spark.sql("ALTER TABLE openhouse.db.t RENAME TO db.t2");
    // Spark itself gives a target without a namespace the namespace of the table.
    spark.sql("ALTER TABLE openhouse.db.t RENAME TO t3");
    // Another database is the service's to refuse, not the rule's.
    spark.sql("ALTER TABLE openhouse.db.t RENAME TO other_db.t4");
    Assertions.assertEquals(
        Arrays.asList(
            "openhouse: db.t -> db.t2",
            "openhouse: db.t -> db.t3",
            "openhouse: db.t -> other_db.t4"),
        RENAMES);
  }

  /**
   * The ambiguity of the OpenHouse catalog, kept as it was: a target namespace of one level is
   * always a database, also when the database is named like the catalog, and a target namespace of
   * two levels is always {@code <catalog>.<database>}, of which exactly one level is taken off.
   */
  @Test
  void aDatabaseNamedLikeTheCatalogIsADatabaseInATargetOfOneLevel() throws Exception {
    // "openhouse.t2" is the table t2 of the database openhouse, from any database.
    Assertions.assertEquals(
        Arrays.asList("openhouse", "t2"),
        target(analyzed("ALTER TABLE openhouse.db.t RENAME TO openhouse.t2")));
    Assertions.assertEquals(
        Arrays.asList("openhouse", "t2"),
        target(analyzed("ALTER TABLE openhouse.openhouse.t RENAME TO openhouse.t2")));
    // "openhouse.openhouse.t2" is the same table, written with the catalog: one level goes, once.
    Assertions.assertEquals(
        Arrays.asList("openhouse", "t2"),
        target(analyzed("ALTER TABLE openhouse.openhouse.t RENAME TO openhouse.openhouse.t2")));

    spark.sql("ALTER TABLE openhouse.db.t RENAME TO openhouse.t2");
    spark.sql("ALTER TABLE openhouse.openhouse.t RENAME TO openhouse.t3");
    spark.sql("ALTER TABLE openhouse.openhouse.t RENAME TO openhouse.openhouse.t4");
    Assertions.assertEquals(
        Arrays.asList(
            "openhouse: db.t -> openhouse.t2",
            "openhouse: openhouse.t -> openhouse.t3",
            "openhouse: openhouse.t -> openhouse.t4"),
        RENAMES);
  }

  @Test
  void aTargetNamespaceOfMoreThanTwoLevelsIsRefusedAsTheOpenHouseCatalogRefusedIt() {
    // The OpenHouse catalog counted the levels before it looked at the first one.
    for (String namespace : Arrays.asList("openhouse.db.extra", "other_catalog.db.extra")) {
      ValidationException refused =
          Assertions.assertThrows(
              ValidationException.class,
              () -> spark.sql("ALTER TABLE openhouse.db.t RENAME TO " + namespace + ".t2"));
      Assertions.assertEquals("Namespace has unexpected levels " + namespace, refused.getMessage());
    }
    Assertions.assertEquals(Collections.emptyList(), RENAMES);
  }

  /**
   * The catalog class decided these outcomes when the statement ran; the rule decides them when it
   * is analyzed. Only EXPLAIN shows the difference: for a rename the rule refuses it prints the
   * refusal where it printed a plan, and for a rename the rule accepts it shows the target without
   * the catalog's name.
   */
  @Test
  void explainOfARenameShowsThatTheTargetIsReadDuringAnalysis() {
    Assertions.assertEquals(
        Arrays.asList(
            "Error occurred during query planning: ",
            "Cannot rename tables across catalogs: from=openhouse.db.t, to=other_catalog.db.t2"),
        explain("ALTER TABLE openhouse.db.t RENAME TO other_catalog.db.t2"));
    List<String> accepted = explain("ALTER TABLE openhouse.db.t RENAME TO openhouse.db.t2");
    Assertions.assertEquals(1, accepted.size(), accepted.toString());
    Assertions.assertTrue(accepted.get(0).contains("RenameTable"), accepted.toString());
    Assertions.assertTrue(accepted.get(0).contains(", db.t, db.t2,"), accepted.toString());
    Assertions.assertEquals(Collections.emptyList(), RENAMES);
  }

  // ---------------------------------------------------------------- what the rule leaves alone

  @Test
  void aRestCatalogWhoseServerAdvertisesNoOpenHouseCapabilityIsNotTouched() throws Exception {
    Assertions.assertEquals(
        Arrays.asList("rest_plain", "db", "t2"),
        target(analyzed("ALTER TABLE rest_plain.db.t RENAME TO rest_plain.db.t2")));
    spark.sql("ALTER TABLE rest_plain.db.t RENAME TO rest_plain.db.t2");
    spark.sql("ALTER TABLE rest_plain.db.t RENAME TO other_catalog.db.t3");
    spark.sql("ALTER TABLE rest_plain.db.t RENAME TO a.b.c.t4");
    spark.sql("ALTER TABLE rest_plain.db.t RENAME TO db.t5");
    Assertions.assertEquals(
        Arrays.asList(
            "rest_plain: db.t -> rest_plain.db.t2",
            "rest_plain: db.t -> other_catalog.db.t3",
            "rest_plain: db.t -> a.b.c.t4",
            "rest_plain: db.t -> db.t5"),
        RENAMES);
  }

  @Test
  void anOpenHouseCatalogClassReadsTheTargetItself() throws Exception {
    Assertions.assertEquals(
        Arrays.asList("oh_class", "db", "t2"),
        target(analyzed("ALTER TABLE oh_class.db.t RENAME TO oh_class.db.t2")));
    spark.sql("ALTER TABLE oh_class.db.t RENAME TO oh_class.db.t2");
    spark.sql("ALTER TABLE oh_class.db.t RENAME TO other_catalog.db.t3");
    spark.sql("ALTER TABLE oh_class.db.t RENAME TO a.b.c.t4");
    Assertions.assertEquals(
        Arrays.asList(
            "oh_class: db.t -> oh_class.db.t2",
            "oh_class: db.t -> other_catalog.db.t3",
            "oh_class: db.t -> a.b.c.t4"),
        RENAMES);
  }

  @Test
  void anotherIcebergCatalogIsNotTouched() throws Exception {
    Assertions.assertEquals(
        Arrays.asList("hadoop_plain", "db", "t2"),
        target(analyzed("ALTER TABLE hadoop_plain.db.t RENAME TO hadoop_plain.db.t2")));
    spark.sql("ALTER TABLE hadoop_plain.db.t RENAME TO hadoop_plain.db.t2");
    spark.sql("ALTER TABLE hadoop_plain.db.t RENAME TO other_catalog.db.t3");
    spark.sql("ALTER TABLE hadoop_plain.db.t RENAME TO a.b.c.t4");
    Assertions.assertEquals(
        Arrays.asList(
            "hadoop_plain: db.t -> hadoop_plain.db.t2",
            "hadoop_plain: db.t -> other_catalog.db.t3",
            "hadoop_plain: db.t -> a.b.c.t4"),
        RENAMES);
  }

  @Test
  void sparksOwnCatalogIsNotTouched() {
    spark.sql("CREATE TABLE spark_catalog.default.rename_src (id bigint) USING parquet");
    try {
      // Spark's own catalog refuses a target that names a catalog, its own included, itself.
      Throwable refused =
          Assertions.assertThrows(
              Throwable.class,
              () ->
                  spark.sql(
                      "ALTER TABLE spark_catalog.default.rename_src"
                          + " RENAME TO spark_catalog.default.rename_dst"));
      Assertions.assertTrue(
          String.valueOf(refused.getMessage()).contains("IDENTIFIER_TOO_MANY_NAME_PARTS"),
          refused.toString());
      spark.sql("ALTER TABLE spark_catalog.default.rename_src RENAME TO default.rename_dst");
      Assertions.assertEquals(
          Collections.emptyList(),
          spark.sql("SELECT * FROM spark_catalog.default.rename_dst").collectAsList());
    } finally {
      spark.sql("DROP TABLE IF EXISTS spark_catalog.default.rename_src");
      spark.sql("DROP TABLE IF EXISTS spark_catalog.default.rename_dst");
    }
    Assertions.assertEquals(Collections.emptyList(), RENAMES);
  }

  @Test
  void alterViewOnATableKeepsSparksOwnRefusal() {
    // Spark refuses ALTER VIEW ... RENAME on a table when it plans the statement, before a catalog
    // is asked; the rule does not get ahead of it with its own refusal of the other catalog.
    Throwable refused =
        Assertions.assertThrows(
            Throwable.class,
            () -> spark.sql("ALTER VIEW openhouse.db.t RENAME TO other_catalog.db.t2"));
    Assertions.assertEquals(
        "org.apache.spark.sql.AnalysisException", refused.getClass().getName(), refused.toString());
    Assertions.assertTrue(
        refused.getMessage().contains("Cannot rename a table with ALTER VIEW"),
        refused.getMessage());
    Assertions.assertEquals(Collections.emptyList(), RENAMES);
  }

  @Test
  void aTableThatDoesNotExistIsSparksToRefuse() {
    Throwable refused =
        Assertions.assertThrows(
            Throwable.class,
            () -> spark.sql("ALTER TABLE openhouse.db.missing RENAME TO other_catalog.db.t2"));
    Assertions.assertTrue(
        String.valueOf(refused.getMessage()).contains("TABLE_OR_VIEW_NOT_FOUND"),
        refused.toString());
  }

  // ---------------------------------------------------------------- helpers

  /** The analyzed plan of a parsed {@code ALTER TABLE ... RENAME TO}; nothing is executed. */
  private RenameTable analyzed(String sql) throws Exception {
    LogicalPlan parsed = spark.sessionState().sqlParser().parsePlan(sql);
    Assertions.assertTrue(parsed instanceof RenameTable, parsed.toString());
    LogicalPlan analyzed = spark.sessionState().analyzer().execute(parsed);
    spark.sessionState().analyzer().checkAnalysis(analyzed);
    Assertions.assertTrue(analyzed instanceof RenameTable, analyzed.toString());
    Assertions.assertEquals(Collections.emptyList(), RENAMES, "analysis asks no catalog to rename");
    return (RenameTable) analyzed;
  }

  private List<String> explain(String sql) {
    return spark.sql("EXPLAIN " + sql).collectAsList().stream()
        .map(row -> row.getString(0))
        .collect(Collectors.toList());
  }

  private static List<String> target(RenameTable rename) {
    return CollectionConverters.asJava(rename.newName());
  }

  private static String source(RenameTable rename) {
    return ((ResolvedTable) rename.child()).identifier().toString();
  }

  /** Reads tables from a directory and records renames; the mix-in of the catalogs below. */
  private static final class Tables {
    private HadoopCatalog tables;

    void open(Map<String, String> properties) {
      tables = new HadoopCatalog(new Configuration(), properties.get(TABLES));
    }

    Table load(TableIdentifier identifier) {
      return tables.loadTable(identifier);
    }

    static void record(String catalog, TableIdentifier from, TableIdentifier to) {
      RENAMES.add(catalog + ": " + from + " -> " + to);
    }
  }

  /** Iceberg's {@code RESTCatalog}, with tables to load and renames recorded. */
  public static class RecordingRest extends FakeRestCatalog {
    private final Tables tables = new Tables();

    @Override
    public void initialize(String name, Map<String, String> properties) {
      super.initialize(name, properties);
      tables.open(properties);
    }

    @Override
    public Table loadTable(TableIdentifier identifier) {
      return tables.load(identifier);
    }

    /** For {@code USE <catalog>.<database>}. */
    @Override
    public boolean namespaceExists(Namespace namespace) {
      return true;
    }

    @Override
    public void renameTable(TableIdentifier from, TableIdentifier to) {
      Tables.record(name(), from, to);
    }
  }

  /** A catalog class that implements the capability interfaces, with renames recorded. */
  public static class RecordingOpenHouseClass extends CapabilityTestCatalogs.Native {
    @Override
    public void renameTable(TableIdentifier from, TableIdentifier to) {
      Tables.record(name(), from, to);
    }
  }

  /** Iceberg's Hadoop catalog, with renames recorded. */
  public static class RecordingHadoop extends HadoopCatalog {
    @Override
    public void renameTable(TableIdentifier from, TableIdentifier to) {
      Tables.record(name(), from, to);
    }
  }
}
