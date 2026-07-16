package com.linkedin.openhouse.spark.catalogtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linkedin.openhouse.tablestest.OpenHouseSparkITest;
import java.util.List;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Regression guard for {@code OpenhouseSparkSqlExtensionsParser} implementing Iceberg's {@code
 * ExtendedParser}.
 *
 * <p>Iceberg's {@code rewrite_data_files} procedure parses its {@code sort_order} / {@code
 * zorder(...)} argument via {@code ExtendedParser.parseSortOrder(spark, ...)}, which requires the
 * session's top-level SQL parser to be an Iceberg {@code ExtendedParser}. OpenHouse's parser
 * extension is applied AFTER Iceberg's (see {@code spark.sql.extensions} in {@code
 * TestSparkSessionUtil}), so {@code OpenhouseSparkSqlExtensionsParser} is the top-level parser; if it
 * does not itself implement {@code ExtendedParser} and delegate {@code parseSortOrder}, every
 * sort/zorder compaction fails with {@code IllegalStateException: Cannot parse order: parser is not
 * an Iceberg ExtendedParser}.
 *
 * <p>Unlike the {@code OptimizeTestSpark3_5} suite, this test issues the {@code CALL} directly (no
 * patched-grammar {@code OPTIMIZE} keyword), so it runs against stock spark-sql in ordinary OpenHouse
 * CI and locks the fix against regression. Plain bin-pack compaction (no {@code sort_order}) does not
 * exercise this path, which is why the bug went unnoticed until sort/zorder clustering was added.
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
@Execution(ExecutionMode.SAME_THREAD)
public class RewriteDataFilesSortOrderTestSpark3_5 extends OpenHouseSparkITest {

  private static final String DATABASE = "d1_rewrite_sortorder";
  private static final String TEST_PREFIX = "rewrite_sortorder_";

  private static long snapshotCount(SparkSession spark, String tableName) {
    return spark.sql("SELECT snapshot_id FROM " + tableName + ".snapshots").collectAsList().size();
  }

  @Test
  public void testRewriteDataFilesWithZorderSortOrderThroughOpenHouseCatalog() throws Exception {
    try (SparkSession spark = getSparkSession()) {
      String tableId = TEST_PREFIX + System.currentTimeMillis();
      String tableName = "openhouse." + DATABASE + "." + tableId;
      // The procedure's `table` argument is the catalog-relative identifier (db.table).
      String tableArg = DATABASE + "." + tableId;

      spark.sql("CREATE TABLE " + tableName + " (id int, val int)");
      // Several small files so there is a real rewrite to perform.
      spark.sql("INSERT INTO " + tableName + " VALUES (3, 1), (1, 2), (2, 3)");
      spark.sql("INSERT INTO " + tableName + " VALUES (6, 4), (4, 5), (5, 6)");
      spark.sql("INSERT INTO " + tableName + " VALUES (9, 7), (7, 8), (8, 9)");

      long before = snapshotCount(spark, tableName);

      // The sort_order => 'zorder(id)' argument is parsed by Iceberg's ExtendedParser. Before the
      // OpenhouseSparkSqlExtensionsParser fix this throws "parser is not an Iceberg ExtendedParser".
      spark.sql(
          "CALL openhouse.system.rewrite_data_files("
              + "table => '"
              + tableArg
              + "', "
              + "strategy => 'sort', "
              + "sort_order => 'zorder(id)', "
              + "options => map('min-input-files','2','rewrite-all','true'))");

      long after = snapshotCount(spark, tableName);
      assertTrue(
          after > before,
          "rewrite_data_files with a zorder sort_order should commit a rewrite snapshot (before="
              + before + ", after=" + after + ")");

      List<Row> rows = spark.sql("SELECT id FROM " + tableName + " ORDER BY id").collectAsList();
      assertEquals(9, rows.size(), "all rows must remain readable after the sorted rewrite");
      assertEquals("1", rows.get(0).mkString());
      assertEquals("9", rows.get(8).mkString());

      spark.sql("DROP TABLE " + tableName);
    }
  }
}
