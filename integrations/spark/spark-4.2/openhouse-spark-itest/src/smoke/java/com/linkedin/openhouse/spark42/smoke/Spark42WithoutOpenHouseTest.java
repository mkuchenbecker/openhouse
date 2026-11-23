package com.linkedin.openhouse.spark42.smoke;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * What Spark 4.2 and Iceberg 1.12 do without OpenHouse, for the smoke statements on which {@link
 * Spark42IrcSparkSqlSmokeTest} differs from the baseline: the same statements on Iceberg's Hadoop
 * catalog ({@code local}), in the same session. Nothing of OpenHouse takes part: no service, no IRC
 * route, and the OpenHouse SQL extensions do not act on these statements.
 *
 * <p>So where the outcome here is the one the smoke suite sees over IRC, the difference from the
 * baseline comes with the engine, and where a statement that the OpenHouse catalog refused answers
 * here, the IRC path does what any Iceberg catalog does.
 */
public class Spark42WithoutOpenHouseTest {

  private static final String DB = SmokeSpark.LOCAL + ".smoke_plain";

  private static List<String> rows(String sql) throws Exception {
    return SmokeSpark.session().sql(sql).collectAsList().stream()
        .map(r -> r.mkString(","))
        .collect(Collectors.toList());
  }

  /** Smoke s26: Spark 4 has no Name row; it has Catalog, Namespace and Table rows. */
  @Test
  void describeExtendedNamesTheTableInThreeRowsAndHasNoNameRow() throws Exception {
    String t = DB + ".described";
    rows("DROP TABLE IF EXISTS " + t);
    rows("CREATE TABLE " + t + " (id bigint)");
    List<String> detail = rows("DESCRIBE TABLE EXTENDED " + t);
    Assertions.assertTrue(detail.stream().noneMatch(r -> r.startsWith("Name,")), detail.toString());
    Assertions.assertTrue(detail.contains("Catalog," + SmokeSpark.LOCAL + ","), detail.toString());
    Assertions.assertTrue(detail.contains("Namespace,smoke_plain,"), detail.toString());
    Assertions.assertTrue(detail.contains("Table,described,"), detail.toString());
    Assertions.assertTrue(detail.contains("Type,MANAGED,"), detail.toString());
    Assertions.assertTrue(detail.contains("Provider,iceberg,"), detail.toString());
  }

  /** Smoke s22: the snapshot a REPLACE TABLE AS SELECT adds is an overwrite on Spark 4. */
  @Test
  void replaceTableAsSelectAddsAnOverwriteSnapshot() throws Exception {
    String t = DB + ".replaced";
    rows("DROP TABLE IF EXISTS " + t);
    rows("CREATE TABLE " + t + " (id bigint, name string) TBLPROPERTIES ('team'='x')");
    rows("INSERT INTO " + t + " VALUES (1,'a')");
    rows(
        "CREATE OR REPLACE TABLE "
            + t
            + " TBLPROPERTIES ('extra'='y') AS SELECT 7L AS id, 'z' AS name, 'q' AS more");
    Assertions.assertEquals(Collections.singletonList("7,z,q"), rows("SELECT * FROM " + t));
    Assertions.assertEquals(
        Arrays.asList("append", "overwrite"),
        rows("SELECT operation FROM " + t + ".snapshots ORDER BY committed_at"));
  }

  /**
   * Smoke s24: an Iceberg catalog describes a namespace and lists the namespaces under it (none).
   * The OpenHouse catalog class refuses both.
   */
  @Test
  void aNamespaceIsDescribedAndHasNoNamespacesUnderIt() throws Exception {
    String t = DB + ".in_a_namespace";
    rows("DROP TABLE IF EXISTS " + t);
    rows("CREATE TABLE " + t + " (id bigint)");
    List<String> described = rows("DESCRIBE NAMESPACE " + DB);
    Assertions.assertTrue(
        described.contains("Catalog Name," + SmokeSpark.LOCAL), described.toString());
    Assertions.assertTrue(described.contains("Namespace Name,smoke_plain"), described.toString());
    Assertions.assertEquals(Collections.emptyList(), rows("SHOW NAMESPACES IN " + DB));
  }

  /**
   * Smoke s20 and others: reading a missing table fails on Spark 4 with the message the baseline
   * asserts the start of. Spark 4 adds a search path and a SQLSTATE to it.
   */
  @Test
  void aMissingTableIsTableOrViewNotFoundWithASqlState() throws Exception {
    SparkSession spark = SmokeSpark.session();
    Throwable failure =
        Assertions.assertThrows(
            Throwable.class, () -> spark.sql("SELECT * FROM " + DB + ".missing").collectAsList());
    Assertions.assertEquals(
        "org.apache.spark.sql.catalyst.ExtendedAnalysisException", failure.getClass().getName());
    Assertions.assertTrue(
        failure.getMessage().startsWith("[TABLE_OR_VIEW_NOT_FOUND] The table or view"),
        failure.getMessage());
    Assertions.assertTrue(failure.getMessage().contains("SQLSTATE: 42P01"), failure.getMessage());
  }
}
