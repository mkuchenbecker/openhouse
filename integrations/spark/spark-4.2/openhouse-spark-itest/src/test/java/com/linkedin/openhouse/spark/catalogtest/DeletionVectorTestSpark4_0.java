package com.linkedin.openhouse.spark.catalogtest;

import com.linkedin.openhouse.tablestest.rest.OpenHouseRestSparkITest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Rung 3 (the goal of the modernization spike): proves Iceberg v3 DataSource-V2 <b>deletion
 * vectors</b> end-to-end on the Spark-4.0 REST lane, against the embedded OpenHouse server writing
 * <b>format-version 3</b> metadata.
 *
 * <h2>How v3 is enabled (in isolation)</h2>
 *
 * OpenHouse <i>forces</i> every table's {@code format-version} to the cluster default on create
 * ({@code OpenHouseInternalRepositoryImpl.computePropsForTableCreation} unconditionally puts {@code
 * TableProperties.FORMAT_VERSION = clusterProperties.getClusterIcebergFormatVersion()}), so a
 * client {@code TBLPROPERTIES('format-version'='3')} hint is ignored — a v3 table can ONLY be
 * authored by raising the cluster default. That default is the Spring property {@code
 * cluster.iceberg.format-version} (@Value default {@code 2}), which the embedded {@code
 * SpringH2TestApplication} resolves from JVM system properties.
 *
 * <p>Raising it JVM-globally would flip the whole (currently green, default-v2) itest suite to v3.
 * To avoid that, this class does NOT run in the shared {@code test} task JVM: the module's {@code
 * build.gradle} <b>excludes</b> {@code DeletionVectorTestSpark4_0} from {@code test} and runs it in
 * a dedicated {@code deletionVectorTest} Test task that forks its OWN JVM with {@code
 * -Dcluster.iceberg.format-version=3}. The embedded server started inside that fork therefore
 * authors v3 metadata, while every other {@code catalogtest} class keeps seeing the default v2 in
 * the separate {@code test} fork. The isolation is by JVM fork, not by JUnit ordering, so it is
 * robust.
 *
 * <h2>What is proven</h2>
 *
 * <ol>
 *   <li>{@link #testServerAuthorsFormatVersion3()} — the fork server's metadata-writer emits
 *       format-version 3 (table property {@code format-version == 3} AND the on-disk metadata JSON
 *       carries {@code "format-version" : 3}).
 *   <li>{@link #testMergeOnReadDeleteWritesDeletionVector()} — a row-level {@code DELETE} on a v3
 *       merge-on-read table writes a Puffin <b>deletion vector</b> (delete-file {@code file_format
 *       = PUFFIN}, physical {@code *.puffin} carrying a {@code deletion-vector-v1} blob), NOT a
 *       classic {@code *-deletes.parquet} positional delete file, and the deleted row is gone on
 *       read-back while the survivors remain.
 * </ol>
 */
public class DeletionVectorTestSpark4_0 extends OpenHouseRestSparkITest {

  private static final String DATABASE = "dbdv";

  /**
   * Metadata-writer v3 proof: with the fork server on {@code cluster.iceberg.format-version=3}, a
   * freshly created table reports {@code format-version = 3} both as a table property AND in the
   * physical metadata JSON the OpenHouse server authored.
   */
  @Test
  public void testServerAuthorsFormatVersion3() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      String fqtn = "openhouse." + DATABASE + ".v3check";
      spark.sql("CREATE TABLE " + fqtn + " (id bigint, data string) USING iceberg");

      // (a) format-version surfaced as a table property must be 3 (server-forced, not client hint).
      String formatVersion =
          spark
              .sql("SHOW TBLPROPERTIES " + fqtn)
              .filter("key = 'format-version'")
              .select("value")
              .first()
              .getString(0);
      Assertions.assertEquals(
          "3",
          formatVersion,
          "server must author format-version 3 when cluster.iceberg.format-version=3");

      // (b) Prove it end-to-end through the Iceberg metadata-writer: the current TableMetadata the
      // server wrote has formatVersion()==3, and the on-disk *.metadata.json literally carries it.
      Catalog catalog = getOpenHouseCatalog(spark);
      Table table = catalog.loadTable(TableIdentifier.of(DATABASE, "v3check"));
      TableMetadata metadata = ((HasTableOperations) table).operations().current();
      Assertions.assertEquals(
          3, metadata.formatVersion(), "loaded TableMetadata.formatVersion() must be 3");

      String metadataJsonPath = metadata.metadataFileLocation().replaceFirst("^file:", "");
      String metadataJson =
          new String(Files.readAllBytes(Paths.get(metadataJsonPath)), StandardCharsets.UTF_8);
      // OpenHouse writes compact metadata JSON ("format-version":3); tolerate any whitespace so the
      // proof does not hinge on the serializer's pretty-printing choice.
      Assertions.assertTrue(
          metadataJson.matches("(?s).*\"format-version\"\\s*:\\s*3.*"),
          "server-authored metadata JSON must declare format-version 3, at " + metadataJsonPath);
    }
  }

  /**
   * Deletion-vector proof: on a v3 merge-on-read table a row-level {@code DELETE} writes a Puffin
   * deletion vector, not a classic parquet positional delete file. Asserts the survivor set on
   * read-back AND the physical DV evidence via the {@code .delete_files} metadata table plus the
   * on-disk puffin blob.
   */
  @Test
  public void testMergeOnReadDeleteWritesDeletionVector() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      String fqtn = "openhouse." + DATABASE + ".dv_delete";
      spark.sql(
          "CREATE TABLE "
              + fqtn
              + " (id bigint, data string) USING iceberg "
              + "TBLPROPERTIES ("
              + "  'write.format.default'='parquet',"
              + "  'write.delete.mode'='merge-on-read',"
              + "  'write.update.mode'='merge-on-read',"
              + "  'write.merge.mode'='merge-on-read')");

      // Sanity: the table really is v3 (deletion vectors are a v3-only construct).
      String formatVersion =
          spark
              .sql("SHOW TBLPROPERTIES " + fqtn)
              .filter("key = 'format-version'")
              .select("value")
              .first()
              .getString(0);
      Assertions.assertEquals("3", formatVersion, "DV proof requires a v3 table");

      spark.sql("INSERT INTO " + fqtn + " VALUES (1,'a'),(2,'b'),(3,'c'),(4,'d'),(5,'e'),(6,'f')");
      Assertions.assertEquals(
          6L, spark.sql("SELECT count(*) FROM " + fqtn).first().getLong(0), "6 rows after insert");

      // Row-level merge-on-read DELETE -> must emit a deletion vector (puffin), not a data rewrite.
      spark.sql("DELETE FROM " + fqtn + " WHERE id = 2");

      // Read-back: the deleted row is gone; the survivors remain.
      List<Row> survivors = spark.sql("SELECT id FROM " + fqtn + " ORDER BY id").collectAsList();
      List<Long> ids = survivors.stream().map(r -> r.getLong(0)).collect(Collectors.toList());
      Assertions.assertEquals(java.util.Arrays.asList(1L, 3L, 4L, 5L, 6L), ids, "row id=2 removed");
      Assertions.assertEquals(
          0L,
          spark.sql("SELECT count(*) FROM " + fqtn + " WHERE id = 2").first().getLong(0),
          "deleted row must not be readable");

      // DV evidence #1: the delete manifest carries a PUFFIN delete file (a deletion vector), NOT a
      // parquet positional delete file. content=1 is POSITION_DELETES for both; file_format is the
      // v2-vs-v3 discriminator (PARQUET = classic pos-delete, PUFFIN = deletion vector).
      List<Row> deleteFiles =
          spark
              .sql(
                  "SELECT content, file_format, record_count, file_path FROM "
                      + fqtn
                      + ".delete_files")
              .collectAsList();
      Assertions.assertFalse(deleteFiles.isEmpty(), "a delete file must exist after the DELETE");
      System.out.println("[DV-PROOF] format-version=" + formatVersion);
      deleteFiles.forEach(
          r ->
              System.out.println(
                  "[DV-PROOF] delete_files: content="
                      + r.get(0)
                      + " file_format="
                      + r.getString(1)
                      + " record_count="
                      + r.get(2)
                      + " path="
                      + r.getString(3)));
      long puffinDeletes =
          deleteFiles.stream().filter(r -> "PUFFIN".equals(r.getString(1))).count();
      long parquetDeletes =
          deleteFiles.stream().filter(r -> "PARQUET".equals(r.getString(1))).count();
      Assertions.assertTrue(
          puffinDeletes >= 1,
          "the DELETE must write a PUFFIN deletion vector; delete_files=" + deleteFiles);
      Assertions.assertEquals(
          0,
          parquetDeletes,
          "no classic parquet positional delete file may be written on v3; delete_files="
              + deleteFiles);

      // DV evidence #2: physically confirm the delete file is a *.puffin carrying a
      // deletion-vector-v1 blob (the on-disk fingerprint of an Iceberg v3 deletion vector).
      String deletePath =
          deleteFiles.stream()
              .filter(r -> "PUFFIN".equals(r.getString(1)))
              .map(r -> r.getString(3))
              .findFirst()
              .orElseThrow(() -> new AssertionError("no puffin delete file path"));
      Assertions.assertTrue(
          deletePath.endsWith(".puffin"),
          "deletion vector must be a .puffin file, got " + deletePath);
      byte[] puffinBytes = Files.readAllBytes(Paths.get(deletePath.replaceFirst("^file:", "")));
      String puffinAscii = new String(puffinBytes, StandardCharsets.ISO_8859_1);
      Assertions.assertTrue(
          puffinAscii.contains("deletion-vector-v1"),
          "puffin footer must carry a deletion-vector-v1 blob, at " + deletePath);
      System.out.println(
          "[DV-PROOF] puffin "
              + deletePath
              + " carries deletion-vector-v1 blob (size="
              + puffinBytes.length
              + "B); survivors="
              + ids);
    }
  }
}
