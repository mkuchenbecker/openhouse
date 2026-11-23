package com.linkedin.openhouse.spark42.smoke;

import com.linkedin.openhouse.spark42.smoke.SmokeTestService.StoredTable;
import java.util.Arrays;
import java.util.Collections;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The write statements Spark SQL users rely on, from Spark 4.2 on Iceberg 1.12 over IRC, one small
 * test each. Where the smoke suite reads a statement's effect back through Spark, these read what
 * the service stored, through the OpenHouse Java client in the service's JVM ({@link
 * SmokeTestService#stored}): the schema, the properties, the snapshots and refs, and the rows a
 * reader of each ref gets from the data and delete files.
 */
public class SparkIrcWriteStatementsTest {

  private static final String DB = "irc42w";
  private static final String BAD_REQUEST = "org.apache.iceberg.exceptions.BadRequestException";

  private static void sql(String statement) throws Exception {
    SmokeSpark.session().sql(statement).collectAsList();
  }

  /** The Spark name of a table of this class that does not exist yet. */
  private static String fresh(String table) throws Exception {
    String name = SmokeSpark.OPENHOUSE + "." + DB + "." + table;
    sql("DROP TABLE IF EXISTS " + name);
    return name;
  }

  private static StoredTable stored(String table) throws Exception {
    return SmokeTestService.get().stored(DB + "." + table);
  }

  private static Throwable failure(String statement) throws Exception {
    SparkSession spark = SmokeSpark.session();
    return Assertions.assertThrows(Throwable.class, () -> spark.sql(statement).collectAsList());
  }

  private static void assertRefused(Throwable failure, String messageFragment) {
    Assertions.assertEquals(BAD_REQUEST, failure.getClass().getName(), String.valueOf(failure));
    Assertions.assertTrue(
        String.valueOf(failure.getMessage()).contains(messageFragment), String.valueOf(failure));
  }

  // ---------------------------------------------------------------- inserts

  @Test
  void insertIntoAppendsASnapshotPerStatement() throws Exception {
    String t = fresh("insert_into");
    sql("CREATE TABLE " + t + " (id bigint, name string)");
    sql("INSERT INTO " + t + " VALUES (1,'a'),(2,'b')");
    sql("INSERT INTO " + t + " SELECT 3, 'c'");

    StoredTable stored = stored("insert_into");
    Assertions.assertEquals(Arrays.asList("id long", "name string"), stored.columns());
    Assertions.assertEquals(Arrays.asList("1,a", "2,b", "3,c"), stored.rows());
    Assertions.assertEquals(Arrays.asList("append", "append"), stored.operations());
    Assertions.assertEquals("2", stored.summary(1).get("added-records"));
    Assertions.assertEquals("3", stored.summary(2).get("total-records"));
  }

  @Test
  void insertOverwriteOfAStaticPartitionLeavesTheOtherPartitions() throws Exception {
    String t = fresh("overwrite_static");
    sql("CREATE TABLE " + t + " (id bigint, p string) PARTITIONED BY (p)");
    sql("INSERT INTO " + t + " VALUES (1,'x'),(2,'y')");
    sql("INSERT OVERWRITE " + t + " PARTITION (p='x') SELECT 10");

    StoredTable stored = stored("overwrite_static");
    Assertions.assertEquals("[1000: p: identity(2)]", stored.partitioning());
    Assertions.assertEquals(Arrays.asList("10,x", "2,y"), stored.rows());
    Assertions.assertEquals(Arrays.asList("append", "overwrite"), stored.operations());
  }

  @Test
  void insertOverwriteWithoutAPartitionReplacesEveryRowInStaticMode() throws Exception {
    String t = fresh("overwrite_all");
    sql("CREATE TABLE " + t + " (id bigint, p string) PARTITIONED BY (p)");
    sql("INSERT INTO " + t + " VALUES (1,'x'),(2,'y'),(3,'z')");
    sql("INSERT OVERWRITE " + t + " VALUES (10,'x')");

    StoredTable stored = stored("overwrite_all");
    Assertions.assertEquals(Collections.singletonList("10,x"), stored.rows());
    Assertions.assertEquals(Arrays.asList("append", "overwrite"), stored.operations());
  }

  @Test
  void insertOverwriteWithDynamicPartitionsReplacesOnlyThePartitionsItWrites() throws Exception {
    String t = fresh("overwrite_dynamic");
    sql("CREATE TABLE " + t + " (id bigint, p string) PARTITIONED BY (p)");
    sql("INSERT INTO " + t + " VALUES (1,'x'),(2,'y'),(3,'z')");
    SparkSession spark = SmokeSpark.session();
    spark.conf().set("spark.sql.sources.partitionOverwriteMode", "dynamic");
    try {
      sql("INSERT OVERWRITE " + t + " VALUES (10,'x'),(20,'y')");
    } finally {
      spark.conf().unset("spark.sql.sources.partitionOverwriteMode");
    }

    StoredTable stored = stored("overwrite_dynamic");
    Assertions.assertEquals(Arrays.asList("10,x", "20,y", "3,z"), stored.rows());
    Assertions.assertEquals(Arrays.asList("append", "overwrite"), stored.operations());
    Assertions.assertEquals("true", stored.summary(2).get("replace-partitions"));
  }

  // ---------------------------------------------------------------- row-level changes

  @Test
  void mergeIntoRewritesTheDataFilesByDefault() throws Exception {
    String t = fresh("merge_cow");
    String source = fresh("merge_cow_source");
    sql("CREATE TABLE " + t + " (id bigint, name string)");
    sql("CREATE TABLE " + source + " (id bigint, name string)");
    sql("INSERT INTO " + t + " VALUES (1,'a'),(2,'b')");
    sql("INSERT INTO " + source + " VALUES (2,'B'),(3,'c')");
    sql(
        "MERGE INTO "
            + t
            + " t USING "
            + source
            + " s ON t.id = s.id"
            + " WHEN MATCHED THEN UPDATE SET t.name = s.name"
            + " WHEN NOT MATCHED THEN INSERT *");

    StoredTable stored = stored("merge_cow");
    Assertions.assertEquals(Arrays.asList("1,a", "2,B", "3,c"), stored.rows());
    Assertions.assertEquals(Arrays.asList("append", "overwrite"), stored.operations());
    Assertions.assertEquals("0", stored.summary(2).get("total-delete-files"));
  }

  @Test
  void mergeIntoWritesAPositionDeleteFileWhenTheTableAsksForMergeOnRead() throws Exception {
    String t = fresh("merge_mor");
    String source = fresh("merge_mor_source");
    sql(
        "CREATE TABLE "
            + t
            + " (id bigint, name string) TBLPROPERTIES ("
            + "'write.merge.mode'='merge-on-read','write.delete.mode'='merge-on-read',"
            + "'write.update.mode'='merge-on-read')");
    sql("CREATE TABLE " + source + " (id bigint, name string)");
    sql("INSERT INTO " + t + " VALUES (1,'a'),(2,'b')");
    sql("INSERT INTO " + source + " VALUES (2,'B'),(3,'c')");
    sql(
        "MERGE INTO "
            + t
            + " t USING "
            + source
            + " s ON t.id = s.id"
            + " WHEN MATCHED THEN UPDATE SET t.name = s.name"
            + " WHEN NOT MATCHED THEN INSERT *");

    StoredTable stored = stored("merge_mor");
    // The Java client's reader applies the delete file: it does not return the row (2,b).
    Assertions.assertEquals(Arrays.asList("1,a", "2,B", "3,c"), stored.rows());
    Assertions.assertEquals(Arrays.asList("append", "overwrite"), stored.operations());
    Assertions.assertEquals("1", stored.summary(2).get("total-delete-files"));
    Assertions.assertEquals("1", stored.summary(2).get("total-position-deletes"));
  }

  @Test
  void updateChangesTheMatchingRows() throws Exception {
    String t = fresh("update_rows");
    sql("CREATE TABLE " + t + " (id bigint, name string)");
    sql("INSERT INTO " + t + " VALUES (1,'a'),(2,'b')");
    sql("UPDATE " + t + " SET name = 'B' WHERE id = 2");

    StoredTable stored = stored("update_rows");
    Assertions.assertEquals(Arrays.asList("1,a", "2,B"), stored.rows());
    Assertions.assertEquals(Arrays.asList("append", "overwrite"), stored.operations());
  }

  @Test
  void deleteRemovesTheMatchingRows() throws Exception {
    String t = fresh("delete_rows");
    sql("CREATE TABLE " + t + " (id bigint, name string)");
    sql("INSERT INTO " + t + " VALUES (1,'a'),(2,'b'),(3,'c')");
    sql("DELETE FROM " + t + " WHERE id = 2");

    StoredTable stored = stored("delete_rows");
    Assertions.assertEquals(Arrays.asList("1,a", "3,c"), stored.rows());
    Assertions.assertEquals(Arrays.asList("append", "overwrite"), stored.operations());
  }

  // ---------------------------------------------------------------- create and replace as select

  @Test
  void createTableAsSelectIsOneCreateWithItsRows() throws Exception {
    String source = fresh("ctas_source");
    String t = fresh("ctas");
    sql("CREATE TABLE " + source + " (id bigint, name string)");
    sql("INSERT INTO " + source + " VALUES (1,'a'),(2,'b')");
    sql("CREATE TABLE " + t + " AS SELECT * FROM " + source);

    StoredTable stored = stored("ctas");
    Assertions.assertEquals(Arrays.asList("id long", "name string"), stored.columns());
    Assertions.assertEquals(Arrays.asList("1,a", "2,b"), stored.rows());
    Assertions.assertEquals(Collections.singletonList("append"), stored.operations());
    // One native create carried the snapshot: no second version was written.
    Assertions.assertEquals("INITIAL_VERSION", stored.properties().get("openhouse.tableVersion"));
    Assertions.assertEquals("ctas", stored.properties().get("openhouse.tableId"));
  }

  @Test
  void replaceTableAsSelectReplacesSchemaAndRowsAndKeepsPropertiesWhereReplaceIsEnabled()
      throws Exception {
    String t = fresh("rtas_enabled");
    sql("CREATE TABLE " + t + " (id bigint, name string) TBLPROPERTIES ('team'='x')");
    sql("INSERT INTO " + t + " VALUES (1,'a')");
    sql("ALTER TABLE " + t + " SET TBLPROPERTIES ('replace.enabled'='true')");
    sql(
        "CREATE OR REPLACE TABLE "
            + t
            + " TBLPROPERTIES ('extra'='y') AS SELECT 7L AS id, 'z' AS name, 'q' AS more");

    StoredTable stored = stored("rtas_enabled");
    Assertions.assertEquals(
        Arrays.asList("id long", "name string", "more string"), stored.columns());
    Assertions.assertEquals(Collections.singletonList("7,z,q"), stored.rows());
    Assertions.assertEquals("x", stored.properties().get("team"));
    Assertions.assertEquals("y", stored.properties().get("extra"));
    Assertions.assertEquals("true", stored.properties().get("replace.enabled"));
    // The replaced table's snapshot is kept, outside the history of main. On Spark 4 the replace
    // adds an overwrite snapshot (Spark 3.5: an append; see the smoke suite, s22).
    Assertions.assertEquals(Arrays.asList("append", "overwrite"), stored.operations());
    Assertions.assertFalse(stored.reachable(1));
    Assertions.assertEquals(Collections.singletonList("1,a"), stored.rows("snapshot 1"));
  }

  @Test
  void replaceTableAsSelectIsRefusedAndChangesNothingWhereReplaceIsNotEnabled() throws Exception {
    String t = fresh("rtas_gated");
    sql("CREATE TABLE " + t + " (id bigint, name string)");
    sql("INSERT INTO " + t + " VALUES (1,'a')");

    assertRefused(
        failure("CREATE OR REPLACE TABLE " + t + " AS SELECT 7L AS id, 'z' AS name, 'q' AS more"),
        "REPLACE TABLE AS SELECT is not enabled for table");

    StoredTable stored = stored("rtas_gated");
    Assertions.assertEquals(Arrays.asList("id long", "name string"), stored.columns());
    Assertions.assertEquals(Collections.singletonList("1,a"), stored.rows());
    Assertions.assertEquals(Collections.singletonList("append"), stored.operations());
  }

  // ---------------------------------------------------------------- columns and properties

  @Test
  void addColumnIsStoredAndOldRowsReadNull() throws Exception {
    String t = fresh("add_column");
    sql("CREATE TABLE " + t + " (id bigint)");
    sql("INSERT INTO " + t + " VALUES (1)");
    sql("ALTER TABLE " + t + " ADD COLUMN extra string");
    sql("INSERT INTO " + t + " VALUES (2,'v')");

    StoredTable stored = stored("add_column");
    Assertions.assertEquals(Arrays.asList("id long", "extra string"), stored.columns());
    Assertions.assertEquals(Arrays.asList("1,null", "2,v"), stored.rows());
  }

  /** The native schema rule matches columns by name, so a rename is refused, as natively. */
  @Test
  void renameColumnIsRefusedByTheNativeSchemaRuleAndChangesNothing() throws Exception {
    String t = fresh("rename_column");
    sql("CREATE TABLE " + t + " (id bigint, name string)");
    sql("INSERT INTO " + t + " VALUES (1,'a')");

    assertRefused(
        failure("ALTER TABLE " + t + " RENAME COLUMN name TO label"), "invalid schema evolution");

    StoredTable stored = stored("rename_column");
    Assertions.assertEquals(Arrays.asList("id long", "name string"), stored.columns());
    Assertions.assertEquals(Collections.singletonList("1,a"), stored.rows());
  }

  /** The native schema rule forbids dropping a column, so a drop is refused, as natively. */
  @Test
  void dropColumnIsRefusedByTheNativeSchemaRuleAndChangesNothing() throws Exception {
    String t = fresh("drop_column");
    sql("CREATE TABLE " + t + " (id bigint, name string)");
    sql("INSERT INTO " + t + " VALUES (1,'a')");

    assertRefused(failure("ALTER TABLE " + t + " DROP COLUMN name"), "invalid schema evolution");

    StoredTable stored = stored("drop_column");
    Assertions.assertEquals(Arrays.asList("id long", "name string"), stored.columns());
    Assertions.assertEquals(Collections.singletonList("1,a"), stored.rows());
  }

  @Test
  void tablePropertiesAreSetAndUnsetAndAReservedKeyIsRefused() throws Exception {
    String t = fresh("properties");
    sql("CREATE TABLE " + t + " (id bigint)");

    sql("ALTER TABLE " + t + " SET TBLPROPERTIES ('team'='x', 'tier'='gold')");
    Assertions.assertEquals("x", stored("properties").properties().get("team"));
    Assertions.assertEquals("gold", stored("properties").properties().get("tier"));

    sql("ALTER TABLE " + t + " UNSET TBLPROPERTIES ('team')");
    Assertions.assertFalse(stored("properties").properties().containsKey("team"));
    Assertions.assertEquals("gold", stored("properties").properties().get("tier"));

    assertRefused(
        failure("ALTER TABLE " + t + " SET TBLPROPERTIES ('openhouse.foo'='hack')"),
        "table properties starting with `openhouse.` and the `policies` key cannot be modified");
    Assertions.assertFalse(stored("properties").properties().containsKey("openhouse.foo"));
  }

  // ---------------------------------------------------------------- branches and staged writes

  @Test
  void anInsertIntoABranchLeavesMainAsItWas() throws Exception {
    String t = fresh("branch_write");
    sql("CREATE TABLE " + t + " (id bigint)");
    sql("INSERT INTO " + t + " VALUES (1)");
    sql("ALTER TABLE " + t + " CREATE BRANCH b1");
    sql("INSERT INTO " + t + ".branch_b1 VALUES (2)");

    StoredTable stored = stored("branch_write");
    Assertions.assertEquals(Arrays.asList("b1=branch", "main=branch"), stored.refs());
    Assertions.assertEquals(Collections.singletonList("1"), stored.rows());
    Assertions.assertEquals(Arrays.asList("1", "2"), stored.rows("b1"));
  }

  /** Write-audit-publish by id, as the Spark 3.5 itest's WapIdTest: staged, then published. */
  @Test
  void aWriteWithAWapIdIsStagedAndCherryPickingPublishesIt() throws Exception {
    String t = fresh("wap_id");
    sql("CREATE TABLE " + t + " (name string)");
    sql("ALTER TABLE " + t + " SET TBLPROPERTIES ('write.wap.enabled'='true')");
    sql("INSERT INTO " + t + " VALUES ('main.a')");
    SparkSession spark = SmokeSpark.session();
    spark.conf().set("spark.wap.id", "wap1");
    try {
      sql("INSERT INTO " + t + " VALUES ('wap1.a')");
    } finally {
      spark.conf().unset("spark.wap.id");
    }

    StoredTable staged = stored("wap_id");
    Assertions.assertEquals(Collections.singletonList("main.a"), staged.rows());
    Assertions.assertEquals(Arrays.asList("append", "append"), staged.operations());
    Assertions.assertEquals("wap1", staged.summary(2).get("wap.id"));
    Assertions.assertFalse(staged.reachable(2));
    Assertions.assertEquals(Arrays.asList("main.a", "wap1.a"), staged.rows("snapshot 2"));

    sql(
        "CALL "
            + SmokeSpark.OPENHOUSE
            + ".system.cherrypick_snapshot('"
            + DB
            + ".wap_id', "
            + staged.snapshotId(2)
            + ")");

    StoredTable published = stored("wap_id");
    Assertions.assertEquals(Arrays.asList("main.a", "wap1.a"), published.rows());
    Assertions.assertTrue(published.reachable(2));
    Assertions.assertEquals(Arrays.asList("append", "append"), published.operations());
  }

  /** Write-audit-publish by branch: the session's writes go to the branch it names. */
  @Test
  void aWriteWithAWapBranchGoesToThatBranch() throws Exception {
    String t = fresh("wap_branch");
    sql("CREATE TABLE " + t + " (name string)");
    sql("ALTER TABLE " + t + " SET TBLPROPERTIES ('write.wap.enabled'='true')");
    sql("INSERT INTO " + t + " VALUES ('main.a')");
    SparkSession spark = SmokeSpark.session();
    spark.conf().set("spark.wap.branch", "staging");
    try {
      sql("INSERT INTO " + t + " VALUES ('staging.a')");
    } finally {
      spark.conf().unset("spark.wap.branch");
    }

    StoredTable stored = stored("wap_branch");
    Assertions.assertEquals(Arrays.asList("main=branch", "staging=branch"), stored.refs());
    Assertions.assertEquals(Collections.singletonList("main.a"), stored.rows());
    Assertions.assertEquals(Arrays.asList("main.a", "staging.a"), stored.rows("staging"));
  }
}
