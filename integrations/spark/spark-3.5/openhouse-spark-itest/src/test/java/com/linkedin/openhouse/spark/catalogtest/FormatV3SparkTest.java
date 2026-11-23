package com.linkedin.openhouse.spark.catalogtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkedin.openhouse.javaclient.IcebergLine;
import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;

/**
 * Format v3 tables through Spark 3.5 SQL with the OpenHouse catalog (the native API), against a
 * tables service with the cluster switch on: the two statements a user writes to get a v3 table
 * ({@code CREATE TABLE ... TBLPROPERTIES ('format-version'='3')} and {@code ALTER TABLE ... SET
 * TBLPROPERTIES ('format-version'='3')}), then what a Spark 3.5 writer does to a v3 table by
 * default, each statement pinned with the rows it leaves and what the service stores.
 *
 * <p>Format v3 needs the Iceberg 1.12 line, so those tests are skipped on LinkedIn's Iceberg 1.5.2
 * reference line; {@link #withTheSwitchOffBothStatementsFail} pins what the two statements do on a
 * cluster with the switch off, on both lines.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class FormatV3SparkTest extends OpenHouseSparkITest {
  private static final String DB = "fv3_sql";

  /** The OpenHouse catalog on the service with the switch on. */
  private static final String V3 = "v3house";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private SparkSession spark;
  private FormatV3Server server;
  private Catalog onServer;
  private Catalog onDefaultServer;

  @BeforeAll
  void start() throws Exception {
    spark = getSparkSession();
    onDefaultServer = getOpenHouseCatalog(spark);
    server = new FormatV3Server();
    Map<String, String> properties = new HashMap<>();
    String defaults = "spark.sql.catalog.openhouse.";
    properties.put("uri", server.uri());
    properties.put("cluster", spark.conf().get(defaults + "cluster"));
    properties.put("auth-token", spark.conf().get(defaults + "auth-token"));
    String prefix = "spark.sql.catalog." + V3;
    spark.conf().set(prefix, spark.conf().get("spark.sql.catalog.openhouse"));
    spark.conf().set(prefix + ".catalog-impl", spark.conf().get(defaults + "catalog-impl"));
    properties.forEach((key, value) -> spark.conf().set(prefix + "." + key, value));
    onServer =
        CatalogUtil.loadCatalog(
            spark.conf().get(defaults + "catalog-impl"),
            V3,
            properties,
            spark.sparkContext().hadoopConfiguration());
  }

  @AfterAll
  void stop() {
    if (server != null) {
      for (TableIdentifier table :
          onServer.listTables(org.apache.iceberg.catalog.Namespace.of(DB))) {
        onServer.dropTable(table);
      }
      server.close();
    }
    for (String table : Arrays.asList("off_create", "off_alter")) {
      spark.sql("DROP TABLE IF EXISTS openhouse." + DB + "." + table);
    }
  }

  // ---------------------------------------------------------------- helpers

  private static String t(String table) {
    return V3 + "." + DB + "." + table;
  }

  private List<String> rows(String sql) {
    return spark.sql(sql).collectAsList().stream().map(Row::toString).collect(Collectors.toList());
  }

  private static Throwable thrown(Executable executable) {
    try {
      executable.execute();
    } catch (Throwable t) {
      return t;
    }
    throw new AssertionError("expected a failure");
  }

  private static String chain(Throwable thrown) {
    StringBuilder all = new StringBuilder();
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      all.append(t.getClass().getName()).append(": ").append(t.getMessage()).append(" <- ");
    }
    return all.toString();
  }

  private static TableMetadata metadata(Catalog catalog, String table) {
    return ((HasTableOperations) catalog.loadTable(TableIdentifier.of(DB, table)))
        .operations()
        .current();
  }

  /** The stored metadata file of the table, as JSON: what the service wrote. */
  private JsonNode stored(String table) throws Exception {
    String location = metadata(onServer, table).metadataFileLocation();
    return MAPPER.readTree(Files.readAllBytes(Paths.get(java.net.URI.create(location).getPath())));
  }

  private int formatVersion(String table) throws Exception {
    return stored(table).get("format-version").asInt();
  }

  private long nextRowId(String table) throws Exception {
    return stored(table).get("next-row-id").asLong();
  }

  private void requireFormatV3() {
    Assumptions.assumeTrue(IcebergLine.current() == IcebergLine.V3);
  }

  // ---------------------------------------------------------------- the two statements

  @Test
  void createTableWithFormatVersion3ThenInsertAndSelect() throws Exception {
    requireFormatV3();
    spark.sql(
        "CREATE TABLE "
            + t("created")
            + " (id bigint, data string) TBLPROPERTIES ('format-version'='3')");
    Assertions.assertEquals(3, formatVersion("created"));
    Assertions.assertEquals(0L, nextRowId("created"));
    spark.sql("INSERT INTO " + t("created") + " VALUES (1,'a'),(2,'b'),(3,'c')");
    Assertions.assertEquals(
        Arrays.asList("[1,a]", "[2,b]", "[3,c]"),
        rows("SELECT * FROM " + t("created") + " ORDER BY id"));
    // Row lineage: the snapshot carries first-row-id and added-rows, and the table's next-row-id
    // moved past the three rows.
    JsonNode snapshot = stored("created").get("snapshots").get(0);
    Assertions.assertEquals(0L, snapshot.get("first-row-id").asLong());
    Assertions.assertEquals(3L, snapshot.get("added-rows").asLong());
    Assertions.assertEquals(3L, nextRowId("created"));
    Assertions.assertEquals(
        Arrays.asList("[1,0]", "[2,1]", "[3,2]"),
        rows("SELECT id, _row_id FROM " + t("created") + " ORDER BY id"));
    // Spark shows the table's format version as it does for any Iceberg table.
    Assertions.assertTrue(
        rows("SHOW TBLPROPERTIES " + t("created")).contains("[format-version,3]"),
        rows("SHOW TBLPROPERTIES " + t("created")).toString());
    // A table created without asking is still v2.
    spark.sql("CREATE TABLE " + t("plain") + " (id bigint, data string)");
    Assertions.assertEquals(2, formatVersion("plain"));
  }

  @Test
  void alterTableSetFormatVersion3UpgradesAV2TableAndKeepsItsRows() throws Exception {
    requireFormatV3();
    spark.sql("CREATE TABLE " + t("upgraded") + " (id bigint, data string)");
    spark.sql("INSERT INTO " + t("upgraded") + " VALUES (1,'a'),(2,'b')");
    Assertions.assertEquals(2, formatVersion("upgraded"));

    spark.sql("ALTER TABLE " + t("upgraded") + " SET TBLPROPERTIES ('format-version'='3')");

    Assertions.assertEquals(3, formatVersion("upgraded"));
    Assertions.assertEquals(
        Arrays.asList("[1,a]", "[2,b]"), rows("SELECT * FROM " + t("upgraded") + " ORDER BY id"));
    spark.sql("INSERT INTO " + t("upgraded") + " VALUES (3,'c')");
    Assertions.assertEquals(
        Arrays.asList("[1,a]", "[2,b]", "[3,c]"),
        rows("SELECT * FROM " + t("upgraded") + " ORDER BY id"));
    // The first commit after the upgrade gives row ids to the rows written before it as well.
    Assertions.assertEquals(3L, nextRowId("upgraded"));
    Assertions.assertEquals(3, rows("SELECT DISTINCT _row_id FROM " + t("upgraded")).size());
    // Lowering it again is refused, by Iceberg in the client before any request.
    Throwable lowered =
        thrown(
            () ->
                spark.sql(
                    "ALTER TABLE " + t("upgraded") + " SET TBLPROPERTIES ('format-version'='2')"));
    Assertions.assertTrue(
        chain(lowered).contains("Cannot downgrade v3 table to v2"), chain(lowered));
    Assertions.assertEquals(3, formatVersion("upgraded"));
  }

  /**
   * On a cluster with the switch off both statements fail and leave no v3 table. On the Iceberg
   * 1.12 line the service refuses them (400). On LinkedIn's Iceberg 1.5.2 line the client's own
   * Iceberg refuses them before any request, because it cannot build format v3 metadata.
   */
  @Test
  void withTheSwitchOffBothStatementsFail() {
    String created = "openhouse." + DB + ".off_create";
    String altered = "openhouse." + DB + ".off_alter";
    spark.sql("DROP TABLE IF EXISTS " + created);
    spark.sql("DROP TABLE IF EXISTS " + altered);
    Throwable create =
        thrown(
            () ->
                spark.sql(
                    "CREATE TABLE "
                        + created
                        + " (id bigint) TBLPROPERTIES ('format-version'='3')"));
    spark.sql("CREATE TABLE " + altered + " (id bigint)");
    Throwable alter =
        thrown(
            () ->
                spark.sql("ALTER TABLE " + altered + " SET TBLPROPERTIES ('format-version'='3')"));
    if (IcebergLine.current() == IcebergLine.V3) {
      for (Throwable refused : Arrays.asList(create, alter)) {
        Assertions.assertTrue(chain(refused).contains("FORMAT_VERSION_REFUSED"), chain(refused));
        Assertions.assertTrue(
            chain(refused).contains("format version 3 is not enabled on this cluster"),
            chain(refused));
      }
    } else {
      Assertions.assertTrue(
          chain(create).contains("Unsupported format version: v3"), chain(create));
      Assertions.assertTrue(
          chain(alter).contains("Cannot upgrade table to unsupported format version: v3"),
          chain(alter));
    }
    Assertions.assertFalse(onDefaultServer.tableExists(TableIdentifier.of(DB, "off_create")));
    Assertions.assertEquals(2, metadata(onDefaultServer, "off_alter").formatVersion());
  }

  // ---------------------------------------------------------------- what a writer does by default

  /** DELETE, UPDATE and MERGE in the default copy-on-write mode. */
  @Test
  void copyOnWriteDeleteUpdateAndMerge() throws Exception {
    requireFormatV3();
    spark.sql(
        "CREATE TABLE "
            + t("cow")
            + " (id bigint, data string) TBLPROPERTIES ('format-version'='3')");
    spark.sql("INSERT INTO " + t("cow") + " VALUES (1,'a'),(2,'b'),(3,'c')");
    spark.sql("DELETE FROM " + t("cow") + " WHERE id = 3");
    spark.sql("UPDATE " + t("cow") + " SET data = 'bb' WHERE id = 2");
    spark.sql(
        "MERGE INTO "
            + t("cow")
            + " target USING (SELECT 1 AS id, 'aa' AS data UNION ALL SELECT 4, 'd') src"
            + " ON target.id = src.id"
            + " WHEN MATCHED THEN UPDATE SET target.data = src.data"
            + " WHEN NOT MATCHED THEN INSERT *");
    Assertions.assertEquals(
        Arrays.asList("[1,aa]", "[2,bb]", "[4,d]"),
        rows("SELECT * FROM " + t("cow") + " ORDER BY id"));
    Assertions.assertEquals(3, formatVersion("cow"));
    // Every snapshot carries its row lineage, and next-row-id is the last one's end.
    long end = 0;
    for (JsonNode snapshot : stored("cow").get("snapshots")) {
      Assertions.assertTrue(snapshot.has("first-row-id"), snapshot.toString());
      Assertions.assertTrue(snapshot.get("first-row-id").asLong() >= end, snapshot.toString());
      end = snapshot.get("first-row-id").asLong() + snapshot.get("added-rows").asLong();
    }
    Assertions.assertEquals(end, nextRowId("cow"));
    Assertions.assertEquals(
        Collections.emptyList(), rows("SELECT file_path FROM " + t("cow") + ".delete_files"));
  }

  /**
   * DELETE, UPDATE and MERGE in merge-on-read mode, which a table opts into by property: on a v3
   * table the writer writes deletion vectors (Puffin files), and reads apply them.
   */
  @Test
  void mergeOnReadWritesDeletionVectorsAndReadsApplyThem() throws Exception {
    requireFormatV3();
    spark.sql(
        "CREATE TABLE "
            + t("mor")
            + " (id bigint, data string) TBLPROPERTIES ('format-version'='3',"
            + " 'write.delete.mode'='merge-on-read', 'write.update.mode'='merge-on-read',"
            + " 'write.merge.mode'='merge-on-read')");
    spark.sql("INSERT INTO " + t("mor") + " VALUES (1,'a'),(2,'b'),(3,'c'),(4,'d')");
    spark.sql("DELETE FROM " + t("mor") + " WHERE id = 2");
    Assertions.assertEquals(
        Arrays.asList("[1,a]", "[3,c]", "[4,d]"),
        rows("SELECT * FROM " + t("mor") + " ORDER BY id"));
    List<String> deleteFiles =
        rows("SELECT file_format, file_path FROM " + t("mor") + ".delete_files");
    Assertions.assertEquals(1, deleteFiles.size(), deleteFiles.toString());
    Assertions.assertTrue(deleteFiles.get(0).startsWith("[PUFFIN,"), deleteFiles.toString());
    Assertions.assertTrue(deleteFiles.get(0).endsWith(".puffin]"), deleteFiles.toString());

    spark.sql("UPDATE " + t("mor") + " SET data = 'cc' WHERE id = 3");
    spark.sql(
        "MERGE INTO "
            + t("mor")
            + " target USING (SELECT 1 AS id, 'aa' AS data UNION ALL SELECT 5, 'e') src"
            + " ON target.id = src.id"
            + " WHEN MATCHED THEN UPDATE SET target.data = src.data"
            + " WHEN NOT MATCHED THEN INSERT *");
    Assertions.assertEquals(
        Arrays.asList("[1,aa]", "[3,cc]", "[4,d]", "[5,e]"),
        rows("SELECT * FROM " + t("mor") + " ORDER BY id"));
    // A second session-independent reader, the OpenHouse Java client, sees a v3 table whose
    // current snapshot counts the deletion vectors.
    Assertions.assertEquals(3, formatVersion("mor"));
    JsonNode snapshots = stored("mor").get("snapshots");
    Assertions.assertTrue(
        snapshots.get(snapshots.size() - 1).get("summary").has("total-delete-files"),
        snapshots.toString());
  }

  /** INSERT OVERWRITE, ADD COLUMN, a branch write, CTAS and RTAS on v3 tables. */
  @Test
  void overwriteSchemaChangeBranchCtasAndRtas() throws Exception {
    requireFormatV3();
    spark.sql(
        "CREATE TABLE "
            + t("misc")
            + " (id bigint, data string) TBLPROPERTIES ('format-version'='3')");
    spark.sql("INSERT INTO " + t("misc") + " VALUES (1,'a'),(2,'b')");
    spark.sql("INSERT OVERWRITE " + t("misc") + " VALUES (3,'c')");
    Assertions.assertEquals(
        Collections.singletonList("[3,c]"), rows("SELECT * FROM " + t("misc") + " ORDER BY id"));
    spark.sql("ALTER TABLE " + t("misc") + " ADD COLUMN extra int");
    spark.sql("INSERT INTO " + t("misc") + " VALUES (4,'d',7)");
    Assertions.assertEquals(
        Arrays.asList("[3,c,null]", "[4,d,7]"),
        rows("SELECT * FROM " + t("misc") + " ORDER BY id"));
    spark.sql("ALTER TABLE " + t("misc") + " CREATE BRANCH audit");
    spark.sql("INSERT INTO " + t("misc") + ".branch_audit VALUES (5,'e',8)");
    Assertions.assertEquals(2, rows("SELECT * FROM " + t("misc")).size());
    Assertions.assertEquals(3, rows("SELECT * FROM " + t("misc") + ".branch_audit").size());
    spark.sql("ALTER TABLE " + t("misc") + " SET TBLPROPERTIES ('user.note'='x')");
    Assertions.assertEquals(3, formatVersion("misc"));
    Assertions.assertEquals(5L, nextRowId("misc"));

    spark.sql(
        "CREATE TABLE "
            + t("ctas")
            + " TBLPROPERTIES ('format-version'='3') AS SELECT id, data FROM "
            + t("misc"));
    Assertions.assertEquals(3, formatVersion("ctas"));
    Assertions.assertEquals(
        Arrays.asList("[3,c]", "[4,d]"), rows("SELECT * FROM " + t("ctas") + " ORDER BY id"));
    Assertions.assertEquals(2L, nextRowId("ctas"));

    spark.sql("ALTER TABLE " + t("ctas") + " SET TBLPROPERTIES ('replace.enabled'='true')");
    spark.sql("REPLACE TABLE " + t("ctas") + " AS SELECT id FROM " + t("misc") + " WHERE id = 4");
    Assertions.assertEquals(Collections.singletonList("[4]"), rows("SELECT * FROM " + t("ctas")));
    Assertions.assertEquals(3, formatVersion("ctas"));
    Assertions.assertEquals(3L, nextRowId("ctas"));
  }

  /** OpenHouse's own statements on a v3 table: policies and column tags are stored as on v2. */
  @Test
  void openHousePolicyStatementsOnAV3Table() throws Exception {
    requireFormatV3();
    spark.sql(
        "CREATE TABLE "
            + t("policies")
            + " (id bigint, data string, ts timestamp) PARTITIONED BY (days(ts))"
            + " TBLPROPERTIES ('format-version'='3')");
    spark.sql("ALTER TABLE " + t("policies") + " SET POLICY (RETENTION=30d)");
    spark.sql("ALTER TABLE " + t("policies") + " SET POLICY (SHARING=true)");
    spark.sql("ALTER TABLE " + t("policies") + " MODIFY COLUMN data SET TAG = (PII)");
    String policies = metadata(onServer, "policies").properties().get("policies");
    Assertions.assertTrue(policies.contains("\"count\": 30"), policies);
    Assertions.assertTrue(policies.contains("\"sharingEnabled\": true"), policies);
    Assertions.assertTrue(policies.contains("PII"), policies);
    Assertions.assertEquals(3, formatVersion("policies"));
  }
}
