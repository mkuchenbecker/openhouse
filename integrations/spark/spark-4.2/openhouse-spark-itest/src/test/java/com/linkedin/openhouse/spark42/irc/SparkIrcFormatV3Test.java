package com.linkedin.openhouse.spark42.irc;

import static com.linkedin.openhouse.spark42.irc.IrcSpark.failure;
import static com.linkedin.openhouse.spark42.irc.IrcSpark.row;
import static com.linkedin.openhouse.spark42.irc.IrcSpark.rows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Format v3 tables through Spark 4.2 SQL on Iceberg 1.12 over the Iceberg REST routes, with no
 * OpenHouse class in the engine, against a tables service with the cluster switch on (catalog
 * {@code ircv3}): the two statements a user writes to get a v3 table, then what a Spark 4.2 writer
 * does to a v3 table by default. What the service stored is read from the table's current metadata
 * file. {@link #withTheSwitchOffBothStatementsFail} runs against the service every other test uses,
 * which has the switch off.
 */
public class SparkIrcFormatV3Test {
  private static final String DB = "irc42";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The service's refusal of a variant column while variant columns are off (the default). */
  private static final String VARIANT_OFF =
      "FORMAT_V3_TYPE_NOT_SUPPORTED: column v (variant) of table irc42.v3_types: variant columns"
          + " are off on this cluster (cluster.iceberg.format-v3.variant.enabled is false)";

  private static final List<String> TABLES =
      Arrays.asList(
          "v3_created",
          "v3_plain",
          "v3_upgraded",
          "v3_cow",
          "v3_mor",
          "v3_ctas",
          "v3_types",
          "v3_lineage_cow",
          "v3_lineage_mor");

  @BeforeAll
  static void addTheCatalog() throws Exception {
    IrcReadTestServiceProcess service = IrcReadTestServiceProcess.getWithFormatV3();
    SparkSession spark = IrcSpark.session();
    String prefix = "spark.sql.catalog.ircv3";
    spark.conf().set(prefix, "org.apache.iceberg.spark.SparkCatalog");
    spark.conf().set(prefix + ".type", "rest");
    spark.conf().set(prefix + ".uri", service.uri() + "/iceberg");
    spark.conf().set(prefix + ".token", service.token(IrcSpark.READER));
    ServerPlanning.notApplied(prefix, ServerPlanning.FORMAT_V3);
  }

  @AfterAll
  static void dropTheTables() throws Exception {
    for (String table : TABLES) {
      rows("DROP TABLE IF EXISTS " + t(table));
    }
  }

  private static String t(String table) {
    return "ircv3." + DB + "." + table;
  }

  /** The table's current metadata file, as the service wrote it. */
  private static JsonNode stored(String table) throws Exception {
    List<List<Object>> latest =
        rows(
            "SELECT file FROM "
                + t(table)
                + ".metadata_log_entries ORDER BY timestamp DESC LIMIT 1");
    String file = String.valueOf(latest.get(0).get(0));
    return MAPPER.readTree(
        Files.readAllBytes(
            Paths.get(file.startsWith("file:") ? java.net.URI.create(file).getPath() : file)));
  }

  private static String chain(Throwable thrown) {
    StringBuilder all = new StringBuilder();
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      all.append(t.getClass().getName()).append(": ").append(t.getMessage()).append(" <- ");
    }
    return all.toString();
  }

  // ---------------------------------------------------------------- the two statements

  @Test
  void createTableWithFormatVersion3ThenInsertAndSelect() throws Exception {
    rows(
        "CREATE TABLE "
            + t("v3_created")
            + " (id bigint, data string) USING iceberg TBLPROPERTIES ('format-version'='3')");
    Assertions.assertEquals(3, stored("v3_created").get("format-version").asInt());
    Assertions.assertEquals(0L, stored("v3_created").get("next-row-id").asLong());
    rows("INSERT INTO " + t("v3_created") + " VALUES (1,'a'),(2,'b'),(3,'c')");
    Assertions.assertEquals(
        Arrays.asList(row(1L, "a"), row(2L, "b"), row(3L, "c")),
        rows("SELECT * FROM " + t("v3_created") + " ORDER BY id"));
    JsonNode stored = stored("v3_created");
    Assertions.assertEquals(3L, stored.get("next-row-id").asLong());
    Assertions.assertEquals(0L, stored.get("snapshots").get(0).get("first-row-id").asLong());
    Assertions.assertEquals(3L, stored.get("snapshots").get(0).get("added-rows").asLong());
    Assertions.assertEquals(
        Arrays.asList(row(1L, 0L), row(2L, 1L), row(3L, 2L)),
        rows("SELECT id, _row_id FROM " + t("v3_created") + " ORDER BY id"));
    Assertions.assertTrue(
        rows("SHOW TBLPROPERTIES " + t("v3_created")).contains(row("format-version", "3")));
    // A table created without asking is still v2.
    rows("CREATE TABLE " + t("v3_plain") + " (id bigint) USING iceberg");
    Assertions.assertEquals(2, stored("v3_plain").get("format-version").asInt());
  }

  @Test
  void alterTableSetFormatVersion3UpgradesAV2TableAndKeepsItsRows() throws Exception {
    rows("CREATE TABLE " + t("v3_upgraded") + " (id bigint, data string) USING iceberg");
    rows("INSERT INTO " + t("v3_upgraded") + " VALUES (1,'a'),(2,'b')");
    Assertions.assertEquals(2, stored("v3_upgraded").get("format-version").asInt());

    rows("ALTER TABLE " + t("v3_upgraded") + " SET TBLPROPERTIES ('format-version'='3')");

    Assertions.assertEquals(3, stored("v3_upgraded").get("format-version").asInt());
    Assertions.assertEquals(
        Arrays.asList(row(1L, "a"), row(2L, "b")),
        rows("SELECT * FROM " + t("v3_upgraded") + " ORDER BY id"));
    rows("INSERT INTO " + t("v3_upgraded") + " VALUES (3,'c')");
    Assertions.assertEquals(
        Arrays.asList(row(1L, "a"), row(2L, "b"), row(3L, "c")),
        rows("SELECT * FROM " + t("v3_upgraded") + " ORDER BY id"));
    Assertions.assertEquals(3L, stored("v3_upgraded").get("next-row-id").asLong());
    Assertions.assertEquals(3, rows("SELECT DISTINCT _row_id FROM " + t("v3_upgraded")).size());
    Throwable lowered =
        failure("ALTER TABLE " + t("v3_upgraded") + " SET TBLPROPERTIES ('format-version'='2')");
    Assertions.assertTrue(
        chain(lowered).contains("Cannot downgrade v3 table to v2"), chain(lowered));
    Assertions.assertEquals(3, stored("v3_upgraded").get("format-version").asInt());
  }

  /** On a cluster with the switch off both statements are refused by the service (400). */
  @Test
  void withTheSwitchOffBothStatementsFail() throws Exception {
    String created = "irc." + DB + ".v3_off_create";
    String altered = "irc." + DB + ".v3_off_alter";
    try {
      Throwable create =
          failure(
              "CREATE TABLE "
                  + created
                  + " (id bigint) USING iceberg TBLPROPERTIES ('format-version'='3')");
      Assertions.assertTrue(
          chain(create).contains("OpenHouse creates tables with format version 2"), chain(create));
      Assertions.assertFalse(
          rows("SHOW TABLES IN irc." + DB).stream()
              .anyMatch(r -> "v3_off_create".equals(r.get(1))));
      rows("CREATE TABLE " + altered + " (id bigint) USING iceberg");
      Throwable alter =
          failure("ALTER TABLE " + altered + " SET TBLPROPERTIES ('format-version'='3')");
      Assertions.assertTrue(chain(alter).contains("does not store: format version"), chain(alter));
      Assertions.assertTrue(
          rows("SHOW TBLPROPERTIES " + altered).contains(row("format-version", "2")));
    } finally {
      rows("DROP TABLE IF EXISTS " + created);
      rows("DROP TABLE IF EXISTS " + altered);
    }
  }

  // ---------------------------------------------------------------- what a writer does by default

  /** DELETE, UPDATE and MERGE in the default copy-on-write mode. */
  @Test
  void copyOnWriteDeleteUpdateAndMerge() throws Exception {
    rows(
        "CREATE TABLE "
            + t("v3_cow")
            + " (id bigint, data string) USING iceberg TBLPROPERTIES ('format-version'='3')");
    rows("INSERT INTO " + t("v3_cow") + " VALUES (1,'a'),(2,'b'),(3,'c')");
    rows("DELETE FROM " + t("v3_cow") + " WHERE id = 3");
    rows("UPDATE " + t("v3_cow") + " SET data = 'bb' WHERE id = 2");
    rows(
        "MERGE INTO "
            + t("v3_cow")
            + " target USING (SELECT 1 AS id, 'aa' AS data UNION ALL SELECT 4, 'd') src"
            + " ON target.id = src.id"
            + " WHEN MATCHED THEN UPDATE SET target.data = src.data"
            + " WHEN NOT MATCHED THEN INSERT *");
    Assertions.assertEquals(
        Arrays.asList(row(1L, "aa"), row(2L, "bb"), row(4L, "d")),
        rows("SELECT * FROM " + t("v3_cow") + " ORDER BY id"));
    JsonNode stored = stored("v3_cow");
    long end = 0;
    for (JsonNode snapshot : stored.get("snapshots")) {
      Assertions.assertTrue(snapshot.get("first-row-id").asLong() >= end, snapshot.toString());
      end = snapshot.get("first-row-id").asLong() + snapshot.get("added-rows").asLong();
    }
    Assertions.assertEquals(end, stored.get("next-row-id").asLong());
    Assertions.assertEquals(
        Collections.emptyList(), rows("SELECT file_path FROM " + t("v3_cow") + ".delete_files"));
  }

  /**
   * DELETE, UPDATE and MERGE in merge-on-read mode, which a table opts into by property: on a v3
   * table the writer writes deletion vectors (Puffin files), and reads apply them.
   */
  @Test
  void mergeOnReadWritesDeletionVectorsAndReadsApplyThem() throws Exception {
    rows(
        "CREATE TABLE "
            + t("v3_mor")
            + " (id bigint, data string) USING iceberg TBLPROPERTIES ('format-version'='3',"
            + " 'write.delete.mode'='merge-on-read', 'write.update.mode'='merge-on-read',"
            + " 'write.merge.mode'='merge-on-read')");
    rows("INSERT INTO " + t("v3_mor") + " VALUES (1,'a'),(2,'b'),(3,'c'),(4,'d')");
    rows("DELETE FROM " + t("v3_mor") + " WHERE id = 2");
    Assertions.assertEquals(
        Arrays.asList(row(1L, "a"), row(3L, "c"), row(4L, "d")),
        rows("SELECT * FROM " + t("v3_mor") + " ORDER BY id"));
    List<List<Object>> deleteFiles =
        rows("SELECT file_format, file_path FROM " + t("v3_mor") + ".delete_files");
    Assertions.assertEquals(1, deleteFiles.size(), deleteFiles.toString());
    Assertions.assertEquals("PUFFIN", deleteFiles.get(0).get(0));
    rows("UPDATE " + t("v3_mor") + " SET data = 'cc' WHERE id = 3");
    rows(
        "MERGE INTO "
            + t("v3_mor")
            + " target USING (SELECT 1 AS id, 'aa' AS data UNION ALL SELECT 5, 'e') src"
            + " ON target.id = src.id"
            + " WHEN MATCHED THEN UPDATE SET target.data = src.data"
            + " WHEN NOT MATCHED THEN INSERT *");
    Assertions.assertEquals(
        Arrays.asList(row(1L, "aa"), row(3L, "cc"), row(4L, "d"), row(5L, "e")),
        rows("SELECT * FROM " + t("v3_mor") + " ORDER BY id"));
    Assertions.assertEquals(3, stored("v3_mor").get("format-version").asInt());
  }

  /**
   * Row lineage through UPDATE, MERGE and DELETE in a Spark 4.2 session without Iceberg's SQL
   * extensions (this JVM's session has none; Spark 4 plans row-level statements itself): the
   * contract of the fork's TestRowLevelOperationsWithLineage holds in both write modes. An
   * untouched row keeps its row id and sequence number, an updated row keeps its row id and gets
   * the statement's sequence number, an inserted row gets a fresh id.
   * SparkIrcRowLevelStatementsTest (smoke JVM, with the extensions) checks the same after every
   * statement in more shapes.
   */
  @Test
  void rowLineageWithoutIcebergSqlExtensions() throws Exception {
    Assertions.assertEquals(
        "",
        IrcSpark.session().conf().get("spark.sql.extensions", ""),
        "the session has no extensions");
    for (String mode : Arrays.asList("copy-on-write", "merge-on-read")) {
      String table = "copy-on-write".equals(mode) ? "v3_lineage_cow" : "v3_lineage_mor";
      String name = t(table);
      rows(
          "CREATE TABLE "
              + name
              + " (id bigint, data string) USING iceberg TBLPROPERTIES ('format-version'='3',"
              + " 'write.format.default'='parquet', 'write.delete.mode'='"
              + mode
              + "', 'write.update.mode'='"
              + mode
              + "', 'write.merge.mode'='"
              + mode
              + "')");
      rows("INSERT INTO " + name + " VALUES (1,'a'),(2,'b'),(3,'c')");
      Assertions.assertEquals(
          Arrays.asList(row(1L, 0L, 1L), row(2L, 1L, 1L), row(3L, 2L, 1L)), lineage(name), mode);
      rows("UPDATE " + name + " SET data = 'bb' WHERE id = 2");
      Assertions.assertEquals(
          Arrays.asList(row(1L, 0L, 1L), row(2L, 1L, 2L), row(3L, 2L, 1L)), lineage(name), mode);
      long nextRowId = stored(table).get("next-row-id").asLong();
      rows(
          "MERGE INTO "
              + name
              + " t USING (SELECT 3L AS id, 'cc' AS data UNION ALL SELECT 4L, 'd') s"
              + " ON t.id = s.id WHEN MATCHED THEN UPDATE SET t.data = s.data"
              + " WHEN NOT MATCHED THEN INSERT *");
      List<List<Object>> merged = lineage(name);
      Assertions.assertEquals(
          Arrays.asList(row(1L, 0L, 1L), row(2L, 1L, 2L), row(3L, 2L, 3L)),
          merged.subList(0, 3),
          mode);
      Assertions.assertEquals(4L, merged.get(3).get(0), mode);
      Assertions.assertTrue((Long) merged.get(3).get(1) >= nextRowId, mode + ": " + merged);
      Assertions.assertEquals(3L, merged.get(3).get(2), mode);
      rows("DELETE FROM " + name + " WHERE id = 1");
      Assertions.assertEquals(merged.subList(1, 4), lineage(name), mode);
    }
  }

  private static List<List<Object>> lineage(String name) throws Exception {
    return rows("SELECT id, _row_id, _last_updated_sequence_number FROM " + name + " ORDER BY id");
  }

  /** CTAS at v3 (a staged create and its completion), then INSERT OVERWRITE and ADD COLUMN. */
  @Test
  void ctasOverwriteAndSchemaChange() throws Exception {
    rows(
        "CREATE TABLE "
            + t("v3_ctas")
            + " USING iceberg TBLPROPERTIES ('format-version'='3')"
            + " AS SELECT 1L AS id, 'a' AS data UNION ALL SELECT 2L, 'b'");
    Assertions.assertEquals(3, stored("v3_ctas").get("format-version").asInt());
    Assertions.assertEquals(2L, stored("v3_ctas").get("next-row-id").asLong());
    Assertions.assertEquals(
        Arrays.asList(row(1L, "a"), row(2L, "b")),
        rows("SELECT * FROM " + t("v3_ctas") + " ORDER BY id"));
    rows("INSERT OVERWRITE " + t("v3_ctas") + " VALUES (3,'c')");
    rows("ALTER TABLE " + t("v3_ctas") + " ADD COLUMN extra int");
    rows("INSERT INTO " + t("v3_ctas") + " VALUES (4,'d',7)");
    Assertions.assertEquals(
        Arrays.asList(row(3L, "c", null), row(4L, "d", 7)),
        rows("SELECT * FROM " + t("v3_ctas") + " ORDER BY id"));
    Assertions.assertEquals(4L, stored("v3_ctas").get("next-row-id").asLong());
  }

  // ---------------------------------------------------------------- not yet, and said so

  /**
   * What format v3 adds to Spark SQL that this service does not take: a VARIANT column is refused
   * by the service, on create (whatever the file format) and on ADD COLUMN, naming the property
   * that keeps variant columns off by default, and leaves nothing behind (SparkIrcFormatV3TypesTest
   * has the tables that take one, work item X5); a column DEFAULT is refused by Iceberg's own Spark
   * integration before any request (defaults are set through the Iceberg API until work item W9).
   */
  @Test
  void variantColumnsAndSqlDefaultsAreRefused() throws Exception {
    Throwable create =
        failure(
            "CREATE TABLE "
                + t("v3_types")
                + " (id bigint, v variant) USING iceberg TBLPROPERTIES ('format-version'='3')");
    Assertions.assertTrue(chain(create).contains(VARIANT_OFF), chain(create));
    // Whatever the file format: variant columns are off on this cluster.
    Throwable onParquet =
        failure(
            "CREATE TABLE "
                + t("v3_types")
                + " (id bigint, v variant) USING iceberg TBLPROPERTIES ('format-version'='3',"
                + " 'write.format.default'='parquet')");
    Assertions.assertTrue(chain(onParquet).contains(VARIANT_OFF), chain(onParquet));
    Assertions.assertFalse(
        rows("SHOW TABLES IN ircv3." + DB).stream().anyMatch(r -> "v3_types".equals(r.get(1))));
    rows(
        "CREATE TABLE "
            + t("v3_types")
            + " (id bigint) USING iceberg TBLPROPERTIES ('format-version'='3')");
    Throwable add = failure("ALTER TABLE " + t("v3_types") + " ADD COLUMN v variant");
    Assertions.assertTrue(chain(add).contains(VARIANT_OFF), chain(add));
    Throwable withDefault = failure("ALTER TABLE " + t("v3_types") + " ADD COLUMN c int DEFAULT 7");
    Assertions.assertTrue(
        chain(withDefault).contains("setting default values in Spark is currently unsupported"),
        chain(withDefault));
    Assertions.assertEquals(
        Collections.singletonList(row("id", "bigint", null)), rows("DESCRIBE " + t("v3_types")));
  }
}
