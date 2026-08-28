package harness

import org.apache.spark.sql.{AnalysisException, Row, SparkSession}
import org.apache.iceberg.exceptions.BadRequestException
import org.apache.iceberg.exceptions.ValidationException
import com.linkedin.openhouse.javaclient.exception.WebClientResponseWithMessageException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.annotation.tailrec
import scala.reflect.{ClassTag, classTag}
import scala.util.control.NonFatal

// The fork behavior that only shows up on a merge-on-read delete. The delete-file replication factor
// is stamped onto the position-delete file this DELETE writes, so the case needs the merge-on-read
// write path.
trait MorForkScenarios extends MorScenarioKit {
  import Rows._

  private def showProps(spark: SparkSession, table: String): Map[String, String] =
    spark.sql(s"SHOW TBLPROPERTIES $table").collect().toSeq.map(r => r.getString(0) -> r.getString(1)).toMap

  /**
   * The write.delete-file-replication property round-trips through the catalog, a merge-on-read
   * DELETE physically writes a position-delete file, the surviving rows are correct, and the
   * property is still set after the delete. The property is resolved into a replication factor that
   * the delete-file write path stamps onto the position-delete file's output properties, which is
   * what tells HDFS to set that file's block replication. The local harness asserts the catalog
   * property, the physical delete file, and the surviving rows; HDFS verifies block replication in
   * its own environment.
   */
  private def forkDeleteFileReplication(ctx: Ctx): Unit = {
    val spark = ctx.spark
    val table = s"${ctx.namespace}.t_delrepl"
    spark.sql(s"DROP TABLE IF EXISTS $table")
    // Merge-on-read, unpartitioned, distribution none, so one seed INSERT lands one data file; a partial
    // DELETE against that file must then be satisfied with a position-delete file.
    spark.sql(s"CREATE TABLE $table (id bigint, s string) USING $dataSource TBLPROPERTIES (" +
      s"'format-version'='2', 'write.distribution-mode'='none', 'write.delete.mode'='merge-on-read', " +
      s"'write.update.mode'='merge-on-read', 'write.delete-file-replication'='2')")
    // COALESCE(1) produces a single data file. Deleting a strict subset records a position-delete
    // file while preserving the untouched rows in that data file.
    spark.sql(s"INSERT INTO $table SELECT /*+ COALESCE(1) */ * FROM (VALUES (1L,'a'),(2L,'b'),(3L,'c')) AS s(id, s)")

    // (1) The property round-trips through the catalog metadata.
    val p1 = showProps(spark, table)
    assert(p1.get("write.delete-file-replication").contains("2"),
      s"expected write.delete-file-replication=2 to round-trip, got ${p1.get("write.delete-file-replication")}")

    // (2) A merge-on-read DELETE writes a position-delete file.
    spark.sql(s"DELETE FROM $table WHERE id = 1")
    val delFiles = spark.sql(s"SELECT count(*) FROM $table.delete_files").collect()(0).getLong(0)
    assert(delFiles >= 1, s"merge-on-read DELETE should write a position-delete file, got $delFiles")

    // (3) The DML result is correct; the replication factor never alters the logical row set.
    val rows = spark.sql(s"SELECT id FROM $table ORDER BY id").collect().toSeq.map(_.getLong(0))
    assert(rows == Seq(2L, 3L), s"expected [2,3] after the merge-on-read delete, got $rows")

    // (4) The property survives the mutation.
    val p2 = showProps(spark, table)
    assert(p2.get("write.delete-file-replication").contains("2"), "write.delete-file-replication lost after DELETE")

    println(s"fork.deleteFileReplication: prop=2 roundtrips=yes deleteFiles=$delFiles rows=${rows.mkString(",")}")
    spark.sql(s"DROP TABLE IF EXISTS $table")
  }

  /** The delete-file-replication case, driven directly against a merge-on-read table. */
  val forkDeleteFileReplicationCases: List[Plan.Case] =
    List(
      Plan.Case(
        "fork.deleteFileReplication @ mor",
        forkDeleteFileReplication))
}
