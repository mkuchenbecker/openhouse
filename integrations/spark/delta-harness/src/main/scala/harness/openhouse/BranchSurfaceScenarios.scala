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

// The branch and write-audit-publish surface families. Each case pins one edge of what branch
// routing exposes: what a branch keeps to itself, what it writes through to main, how a staged
// commit is published, and how maintenance behaves while a branch exists. The cases run on parquet
// and orc.
trait BranchSurfaceScenarios extends BranchScenarioKit {
  import Rows._

  /**
   * Three seed rows with keys 1, 2 and 3 in an unpartitioned table. `format` sets the file format.
   */
  private def surfaceBasePreparation(format: String): TablePreparation[CoreTable.type] =
    TablePreparation(
      format,
      TableTest(Core)
        .sql("create")(table =>
          s"CREATE TABLE $table ($columnDefinitions) USING $dataSource " +
            s"TBLPROPERTIES ('write.format.default'='$format')")()
        .insert(3)())

  /**
   * Five rows across two snapshots, a 3-row seed then a 2-row insert, in an unpartitioned table.
   * `format` sets the file format.
   */
  private def surfaceTwoSnapshotPreparation(format: String): TablePreparation[CoreTable.type] =
    TablePreparation(
      format,
      TableTest(Core)
        .sql("create")(table =>
          s"CREATE TABLE $table ($columnDefinitions) USING $dataSource " +
            s"TBLPROPERTIES ('write.format.default'='$format')")()
        .insert(3)()
        .sql("insertMore")(table =>
          s"INSERT INTO $table VALUES " +
            "(CAST(4 AS BIGINT), 4, 'row-4', 4.5, true, '2024-01-04-03'), " +
            "(CAST(5 AS BIGINT), 5, 'row-5', 5.5, false, '2024-01-05-04')")())

  /**
   * Three seed rows in an unpartitioned table with write.wap.enabled set to true. `format` sets the
   * file format.
   */
  private def surfaceWapPreparation(format: String): TablePreparation[CoreTable.type] =
    TablePreparation(
      format,
      TableTest(Core)
        .sql("create")(table =>
          s"CREATE TABLE $table ($columnDefinitions) USING $dataSource " +
            s"TBLPROPERTIES ('write.format.default'='$format')")()
        .insert(3)()
        .sql("enableWap")(table =>
          s"ALTER TABLE $table SET TBLPROPERTIES ('write.wap.enabled'='true')")())

  /**
   * Compacting main while a branch exists preserves both main's and the branch's 6 rows; a
   * follow-up compaction attempt routed at the branch via spark.wap.branch may run or throw. The
   * procedure outcome is diagnostic; the case asserts that main and the branch both remain at 6
   * rows. `format` sets the file format.
   */
  def surfaceBranchMaintenanceCases(format: String): List[Plan.Case] =
    List(
      surfaceTwoSnapshotPreparation(format).test(
        "surface.maint.compactWithBranch") { table =>
        table.spark.sql(
          s"ALTER TABLE ${table.name} SET TBLPROPERTIES " +
            "('write.wap.enabled'='true')")
        table.spark.sql(
          s"ALTER TABLE ${table.name} CREATE BRANCH cb")
        table.spark.sql(
          s"INSERT INTO ${table.name}.branch_cb VALUES " +
            "(CAST(6 AS BIGINT), 6, 'row-6', 6.5, true, '2024-01-06-05')")
        table.spark.sql(
          s"INSERT INTO ${table.name} VALUES " +
            "(CAST(7 AS BIGINT), 7, 'row-7', 7.5, true, '2024-01-07-06')")
        val compactionResult = table.spark
          .sql(
            "CALL openhouse.system.rewrite_data_files(" +
              s"table => '${catalogRelative(table.name)}', " +
              "options => map('min-input-files', '2'))")
          .collect()(0)

        println(
          "DIAG compactWithBranch: " +
            s"mainCompaction rewritten=${compactionResult.get(0)} " +
            s"added=${compactionResult.get(1)}")
        assert(
          countOf(
            table.spark,
            s"SELECT count(*) FROM ${table.name}") == "6",
          "main compaction should preserve 6 rows")
        assert(
          countOf(
            table.spark,
            s"SELECT count(*) FROM ${table.name} VERSION AS OF 'cb'") == "6",
          "main compaction should preserve the branch")

        table.spark.conf.set("spark.wap.branch", "cb")
        val branchRoutedOutcome =
          try {
            val result = table.spark
              .sql(
                "CALL openhouse.system.rewrite_data_files(" +
                  s"table => '${catalogRelative(table.name)}')")
              .collect()(0)
            s"RAN (rewritten=${result.get(0)}, added=${result.get(1)})"
          } catch {
            case exception: Throwable =>
              s"THREW ${exception.getClass.getSimpleName} :: " +
                Option(exception.getMessage).getOrElse("").take(140)
          } finally {
            table.spark.conf.unset("spark.wap.branch")
          }
        println(s"DIAG compactUnderWapConf: $branchRoutedOutcome")

        table.spark.sql(s"REFRESH TABLE ${table.name}")
        assert(
          countOf(
            table.spark,
            s"SELECT count(*) FROM ${table.name}") == "6",
          "branch-routed compaction attempt should preserve main")
        assert(
          countOf(
            table.spark,
            s"SELECT count(*) FROM ${table.name} VERSION AS OF 'cb'") == "6",
          "branch-routed compaction attempt should preserve the branch")
      })

  /**
   * SET TBLPROPERTIES issued while spark.wap.branch is set changes table-global metadata: the user
   * property is visible on the table's own properties.
   */
  private def branchLeakSetPropsCase(
      preparation: TablePreparation[CoreTable.type]): Plan.Case =
    preparation.test("branch.leak.setProps") { table =>
        table.spark.sql(
          s"ALTER TABLE ${table.name} SET TBLPROPERTIES " +
            "('write.wap.enabled'='true')")
        table.spark.sql(
          s"ALTER TABLE ${table.name} CREATE BRANCH lb2")
        table.spark.conf.set("spark.wap.branch", "lb2")
        try {
          table.spark.sql(
            s"ALTER TABLE ${table.name} SET TBLPROPERTIES " +
              "('user.leaked'='yes')")
        } finally {
          table.spark.conf.unset("spark.wap.branch")
        }

        assert(
          tableProps(table.spark, table.name)
            .get("user.leaked")
            .contains("yes"),
          "branch-routed property update should change table-global metadata")
    }

  /**
   * WRITE ORDERED BY issued while spark.wap.branch is set changes table-global metadata:
   * write.distribution-mode becomes range on the table itself.
   */
  private def branchLeakWriteOrderedByCase(
      preparation: TablePreparation[CoreTable.type]): Plan.Case =
    preparation.test("branch.leak.writeOrderedBy") { table =>
        table.spark.sql(
          s"ALTER TABLE ${table.name} SET TBLPROPERTIES " +
            "('write.wap.enabled'='true')")
        table.spark.sql(
          s"ALTER TABLE ${table.name} CREATE BRANCH lb3")
        table.spark.conf.set("spark.wap.branch", "lb3")
        try {
          table.spark.sql(
            s"ALTER TABLE ${table.name} " +
              s"WRITE ORDERED BY ${Core.long0.columnName}")
        } finally {
          table.spark.conf.unset("spark.wap.branch")
        }

        assert(
          tableProps(table.spark, table.name)
            .get("write.distribution-mode")
            .contains("range"),
          "branch-routed ordering should change table-global metadata")
    }

  /**
   * A spark.wap.id-tagged insert produces exactly one staged snapshot carrying that wap.id, and
   * that snapshot is unaffected by later disabling write.wap.enabled on the table.
   */
  private def branchWapToggleNoGuardCase(
      preparation: TablePreparation[CoreTable.type]): Plan.Case =
    preparation.test("branch.wapToggle.noGuard") { table =>
        table.spark.conf.set("spark.wap.id", "w9")
        try {
          table.spark.sql(
            s"INSERT INTO ${table.name} VALUES " +
              "(CAST(9 AS BIGINT), 9, 'row-9', 9.5, true, '2024-01-09-01')")
        } finally {
          table.spark.conf.unset("spark.wap.id")
        }
        val stagedSnapshotCount = countOf(
          table.spark,
          s"SELECT count(*) FROM ${table.name}.snapshots " +
            "WHERE summary['wap.id'] = 'w9'")
        assert(
          stagedSnapshotCount == "1",
          s"expected one staged snapshot, got $stagedSnapshotCount")

        table.spark.sql(
          s"ALTER TABLE ${table.name} SET TBLPROPERTIES " +
            "('write.wap.enabled'='false')")
        val stagedAfterToggle = countOf(
          table.spark,
          s"SELECT count(*) FROM ${table.name}.snapshots " +
            "WHERE summary['wap.id'] = 'w9'")

        assert(
          stagedAfterToggle == "1",
          "disabling write.wap.enabled should not remove an already-staged snapshot, " +
            s"got $stagedAfterToggle")
    }

  /**
   * Cherry-picking a WAP-staged snapshot publishes its row, taking the row count from 3 to 4;
   * cherry-picking that same snapshot a second time is rejected as a duplicate.
   */
  private def wapNegDoubleCherrypickCase(
      preparation: TablePreparation[CoreTable.type]): Plan.Case =
    preparation.test("wap.neg.doubleCherrypick") { table =>
        table.spark.conf.set("spark.wap.id", "w1")
        try {
          table.spark.sql(
            s"INSERT INTO ${table.name} VALUES " +
              "(CAST(9 AS BIGINT), 9, 'row-9', 9.5, true, '2024-01-09-01')")
        } finally {
          table.spark.conf.unset("spark.wap.id")
        }
        val stagedSnapshotId = table.spark
          .sql(
            s"SELECT snapshot_id FROM ${table.name}.snapshots " +
              "WHERE summary['wap.id'] = 'w1'")
          .collect()(0)
          .getLong(0)
        table.spark.sql(
          "CALL openhouse.system.cherrypick_snapshot(" +
            s"'${catalogRelative(table.name)}', ${stagedSnapshotId}L)")
        assert(
          countOf(
            table.spark,
            s"SELECT count(*) FROM ${table.name}") == "4",
          "first cherry-pick should publish the staged row")

        val exception = Check.intercept[Exception](
          table.spark.sql(
            "CALL openhouse.system.cherrypick_snapshot(" +
              s"'${catalogRelative(table.name)}', ${stagedSnapshotId}L)"))
        println(
          "DIAG doubleCherrypick: " +
            s"${exception.getClass.getName} :: " +
            Option(exception.getMessage).getOrElse("").take(180))
        assert(
          Option(exception.getMessage).exists(message =>
            message.toLowerCase.contains("duplicate") ||
              message.toLowerCase.contains("already")),
          "second cherry-pick should reject the duplicate WAP commit")
    }

  /**
   * Expiring the snapshot a branch currently points to is rejected with an exception, and the
   * branch ref still points at its original snapshot afterward.
   */
  private def wapNegExpireRefTargetCase(
      preparation: TablePreparation[CoreTable.type]): Plan.Case =
    preparation.test("wap.neg.expireRefTarget") { table =>
        table.spark.sql(
          s"ALTER TABLE ${table.name} CREATE BRANCH eb2")
        val branchHeadSnapshotId = table.spark
          .sql(
            s"SELECT snapshot_id FROM ${table.name}.refs " +
              "WHERE name = 'eb2'")
          .collect()(0)
          .getLong(0)
        Check.intercept[Exception](
          table.spark.sql(
            "CALL openhouse.system.expire_snapshots(" +
              s"table => '${catalogRelative(table.name)}', " +
              s"snapshot_ids => ARRAY(${branchHeadSnapshotId}L))"))

        val branchHeadSnapshotIdAfter = table.spark
          .sql(
            s"SELECT snapshot_id FROM ${table.name}.refs " +
              "WHERE name = 'eb2'")
          .collect()(0)
          .getLong(0)
        assert(
          branchHeadSnapshotIdAfter == branchHeadSnapshotId,
          "rejected expiration should leave the branch ref pointing at its original snapshot")
    }

  /**
   * fast_forward moves main to a branch's head after two branch-only inserts, growing main from 3
   * rows to 5.
   */
  private def branchFastForwardMergeCase(
      preparation: TablePreparation[CoreTable.type]): Plan.Case =
    preparation.test("branch.fastForward.merge") { table =>
        table.spark.sql(
          s"ALTER TABLE ${table.name} CREATE BRANCH fb")
        table.spark.sql(
          s"INSERT INTO ${table.name}.branch_fb VALUES " +
            "(CAST(6 AS BIGINT), 6, 'row-6', 6.5, true, '2024-01-06-05')")
        table.spark.sql(
          s"INSERT INTO ${table.name}.branch_fb VALUES " +
            "(CAST(7 AS BIGINT), 7, 'row-7', 7.5, true, '2024-01-07-06')")
        assert(
          countOf(
            table.spark,
            s"SELECT count(*) FROM ${table.name}") == "3",
          "branch writes should not advance main")

        table.spark.sql(
          "CALL openhouse.system.fast_forward(" +
            s"'${catalogRelative(table.name)}', 'main', 'fb')")
        assert(
          countOf(
            table.spark,
            s"SELECT count(*) FROM ${table.name}") == "5",
          "fast_forward should move main to the branch head")
    }

  /**
   * fast_forward is rejected with an ancestry error when main and the branch have both advanced
   * independently since they diverged.
   */
  private def branchFastForwardDivergentCase(
      preparation: TablePreparation[CoreTable.type]): Plan.Case =
    preparation.test("branch.fastForward.divergent") { table =>
        table.spark.sql(
          s"ALTER TABLE ${table.name} CREATE BRANCH db")
        table.spark.sql(
          s"INSERT INTO ${table.name}.branch_db VALUES " +
            "(CAST(6 AS BIGINT), 6, 'row-6', 6.5, true, '2024-01-06-05')")
        table.spark.sql(
          s"INSERT INTO ${table.name} VALUES " +
            "(CAST(7 AS BIGINT), 7, 'row-7', 7.5, true, '2024-01-07-06')")
        val exception = Check.intercept[Exception](
          table.spark.sql(
            "CALL openhouse.system.fast_forward(" +
              s"'${catalogRelative(table.name)}', 'main', 'db')"))

        println(
          "DIAG ffDivergent: " +
            s"${exception.getClass.getName} :: " +
            Option(exception.getMessage).getOrElse("").take(180))
        assert(
          Option(exception.getMessage).exists(message =>
            message.toLowerCase.contains("ancestor") ||
              message.toLowerCase.contains("fast-forward")),
          "divergent fast_forward should report an ancestry error")
    }

  /**
   * A new branch starts pointing at the current 5-row head; REPLACE BRANCH AS OF the earlier
   * snapshot retargets it back to the 3-row seed state.
   */
  private def branchReplaceBranchCase(
      preparation: TablePreparation[CoreTable.type]): Plan.Case =
    preparation.test("branch.replaceBranch") { table =>
        val snapshots = snapshotIds(table.spark, table.name)
        table.spark.sql(
          s"ALTER TABLE ${table.name} CREATE BRANCH rb2")
        assert(
          countOf(
            table.spark,
            s"SELECT count(*) FROM ${table.name} VERSION AS OF 'rb2'") == "5",
          "new branch should point at the current head")

        table.spark.sql(
          s"ALTER TABLE ${table.name} REPLACE BRANCH rb2 " +
            s"AS OF VERSION ${snapshots.head}")
        assert(
          countOf(
            table.spark,
            s"SELECT count(*) FROM ${table.name} VERSION AS OF 'rb2'") == "3",
          "REPLACE BRANCH should retarget the branch to the older snapshot")
    }

  /**
   * What a branch keeps to itself and what it leaks to main, plus the branch merge and retarget
   * procedures. `format` sets the file format.
   */
  def surfaceBranchCases(format: String): List[Plan.Case] =
    List(
      branchLeakSetPropsCase(surfaceBasePreparation(format)),
      branchLeakWriteOrderedByCase(surfaceBasePreparation(format)),
      branchWapToggleNoGuardCase(surfaceWapPreparation(format)),
      wapNegDoubleCherrypickCase(surfaceWapPreparation(format)),
      wapNegExpireRefTargetCase(surfaceBasePreparation(format)),
      branchFastForwardMergeCase(surfaceBasePreparation(format)),
      branchFastForwardDivergentCase(surfaceBasePreparation(format)),
      branchReplaceBranchCase(surfaceTwoSnapshotPreparation(format)))

  /**
   * A WAP-staged insert stays invisible on main (still 3 rows) until publish_changes publishes it,
   * growing main to 4 rows. `format` sets the file format.
   */
  def surfaceBranchPublishCases(format: String): List[Plan.Case] =
    List(
      surfaceWapPreparation(format).test(
        "surface.proc.publishChanges") { table =>
        table.spark.conf.set("spark.wap.id", "pw1")
        try {
          table.spark.sql(
            s"INSERT INTO ${table.name} VALUES " +
              "(CAST(9 AS BIGINT), 9, 'row-9', 9.5, true, '2024-01-09-01')")
        } finally {
          table.spark.conf.unset("spark.wap.id")
        }
        assert(
          countOf(
            table.spark,
            s"SELECT count(*) FROM ${table.name}") == "3",
          "staged write should not be visible before publish")

        table.spark.sql(
          "CALL openhouse.system.publish_changes(" +
            s"table => '${catalogRelative(table.name)}', wap_id => 'pw1')")
        assert(
          countOf(
            table.spark,
            s"SELECT count(*) FROM ${table.name}") == "4",
          "publish_changes should publish the staged row")
      })

  /**
   * A DataFrame writeTo(...).append() targeting a branch adds the row to that branch, taking it to
   * 4 rows, while leaving main unchanged at 3 rows. `format` sets the file format.
   */
  def surfaceBranchWriteCases(format: String): List[Plan.Case] =
    List(
      surfaceBasePreparation(format).test(
        "surface.write.dfToBranch") { table =>
        table.spark.sql(
          s"ALTER TABLE ${table.name} CREATE BRANCH wb")
        val row = table.spark.sql(
          s"SELECT CAST(50 AS BIGINT) AS ${Core.long0.columnName}, " +
            s"50 AS ${Core.int0.columnName}, " +
            s"'row-50' AS ${Core.string0.columnName}, " +
            s"50.5 AS ${Core.double0.columnName}, " +
            s"true AS ${Core.boolean0.columnName}, " +
            s"'2024-01-09-01' AS ${Core.datePartition.columnName}")
        row.writeTo(s"${table.name}.branch_wb").append()

        assert(
          countOf(
            table.spark,
            s"SELECT count(*) FROM ${table.name} VERSION AS OF 'wb'") == "4",
          "DataFrame writer should append to the branch")
        assert(
          countOf(
            table.spark,
            s"SELECT count(*) FROM ${table.name}") == "3",
          "DataFrame branch write should leave main unchanged")
      })
}
