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

/**
 * The merge-on-read coexistence, maintenance, metadata and hazard families. Each case operates
 * on a table that already carries a live position-delete file, so it exercises the surface where
 * data files and delete files coexist.
 */
trait MorMaintScenarios extends MorScenarioKit {
  import Rows._

  /**
   * Two live rows with keys 2 and 3 written as one data file, with a live position-delete file
   * removing key 1, in each merge-on-read layout given. `layouts` selects which file formats the
   * cases run on.
   */
  private def morDeletedPreparations(
      layouts: List[Layout]): List[TablePreparation[CoreTable.type]] =
    layouts.map(layout =>
      TablePreparation(layout.label, createAndSeedMorDeleted(layout, 3)))

  /**
   * Merge-on-read delete-file coexistence. A read or insert on a delete-free merge-on-read table
   * is byte-identical to copy-on-write, since there are no delete files to apply and an append is
   * mode-independent. These cases instead operate on a table that already carries a live
   * position-delete file, so they exercise the genuinely merge-on-read surface: data-file and
   * delete-file coexistence. `createAndSeedMorDeleted` leaves 2 rows (keys 2 and 3) with a live
   * delete for key 1; these cases then act on that state.
   */
  lazy val morCoexistCases: List[Plan.Case] =
    morDeletedPreparations(morVerifyLayouts)
      .flatMap { preparation =>
        List(
          coexistAppend(preparation),
          coexistSecondDelete(preparation),
          coexistUpdate(preparation),
          coexistReadFilter(preparation),
          coexistCompactDeletes(preparation),
          coexistMerge(preparation))
      }

  /**
   * INSERT INTO over a table with a live position-delete file adds the new row without
   * resurrecting the deleted one.
   */
  private def coexistAppend(
      preparation: TablePreparation[CoreTable.type]): Plan.Case =
    preparation.test(
      "coexist.append") { table =>
      table.spark.sql(
        s"INSERT INTO ${table.name} VALUES " +
          "(CAST(6 AS BIGINT), 6, 'row-6', 6.5, true, '2024-01-06-05')")

      assert(
        table.spark
          .sql(s"SELECT count(*) FROM ${table.name}")
          .collect()(0)
          .getLong(0) == 3,
        "append over a live delete file returned the wrong row count")
      assert(
        table.spark
          .sql(
            s"SELECT count(*) FROM ${table.name} " +
              s"WHERE ${Core.long0.columnName} = 1")
          .collect()(0)
          .getLong(0) == 0,
        "append resurrected the deleted row")
    }

  /**
   * A second DELETE on a table that already has a live position-delete file removes the targeted
   * row and leaves delete files present.
   */
  private def coexistSecondDelete(
      preparation: TablePreparation[CoreTable.type]): Plan.Case =
    preparation.test(
      "coexist.secondDelete") { table =>
      table.spark.sql(
        s"DELETE FROM ${table.name} WHERE ${Core.long0.columnName} = 2")

      assert(
        table.spark
          .sql(s"SELECT count(*) FROM ${table.name}")
          .collect()(0)
          .getLong(0) == 1,
        "second delete returned the wrong row count")
      assert(
        table.spark
          .sql(s"SELECT count(*) FROM ${table.name}.all_delete_files")
          .collect()(0)
          .getLong(0) >= 1,
        "delete files are missing after the second delete")
    }

  /**
   * UPDATE on a table with a live position-delete file changes the targeted row's value without
   * changing the row count.
   */
  private def coexistUpdate(
      preparation: TablePreparation[CoreTable.type]): Plan.Case =
    preparation.test(
      "coexist.update") { table =>
      table.spark.sql(
        s"UPDATE ${table.name} " +
          s"SET ${Core.string0.columnName} = 'cx' " +
          s"WHERE ${Core.long0.columnName} = 3")

      assert(
        table.spark
          .sql(
            s"SELECT ${Core.string0.columnName} FROM ${table.name} " +
              s"WHERE ${Core.long0.columnName} = 3")
          .collect()(0)
          .getString(0) == "cx",
        "update over a live delete file failed")
      assert(
        table.spark
          .sql(s"SELECT count(*) FROM ${table.name}")
          .collect()(0)
          .getLong(0) == 2,
        "update over a live delete file changed the row count")
    }

  /**
   * A filtered read over a table with a live position-delete file does not return the deleted row.
   */
  private def coexistReadFilter(
      preparation: TablePreparation[CoreTable.type]): Plan.Case =
    preparation.test(
      "coexist.readFilter") { table =>
      val keys = table.spark
        .sql(
          s"SELECT ${Core.long0.columnName} FROM ${table.name} " +
            s"WHERE ${Core.long0.columnName} <= 2 " +
            s"ORDER BY ${Core.long0.columnName}")
        .collect()
        .toSeq
        .map(_.getLong(0))

      assert(
        keys == Seq(2L),
        s"filter did not apply the position delete: $keys")
    }

  /**
   * rewrite_position_delete_files compacts the live position-delete file while preserving the 2
   * live rows.
   */
  private def coexistCompactDeletes(
      preparation: TablePreparation[CoreTable.type]): Plan.Case =
    preparation.test(
      "coexist.compactDeletes") { table =>
      table.spark.sql(
        "CALL openhouse.system.rewrite_position_delete_files(" +
          s"table => '${catalogRelative(table.name)}', " +
          "options => map('rewrite-all', 'true'))")

      assert(
        table.spark
          .sql(s"SELECT count(*) FROM ${table.name}")
          .collect()(0)
          .getLong(0) == 2,
        "position-delete compaction changed the row set")
    }

  /**
   * MERGE INTO on a table with a live position-delete file updates the matched row without
   * changing the row count.
   */
  private def coexistMerge(
      preparation: TablePreparation[CoreTable.type]): Plan.Case =
    preparation.test(
      "coexist.merge") { table =>
      table.spark.sql(
        s"MERGE INTO ${table.name} target " +
          "USING (SELECT CAST(3 AS BIGINT) key) source " +
          s"ON target.${Core.long0.columnName} = source.key " +
          "WHEN MATCHED THEN UPDATE " +
          s"SET ${Core.string0.columnName} = 'mg'")

      assert(
        table.spark
          .sql(s"SELECT count(*) FROM ${table.name}")
          .collect()(0)
          .getLong(0) == 2,
        "merge over a live delete file changed the row count")
      assert(
        table.spark
          .sql(
            s"SELECT ${Core.string0.columnName} FROM ${table.name} " +
              s"WHERE ${Core.long0.columnName} = 3")
          .collect()(0)
          .getString(0) == "mg",
        "merge over a live delete file failed")
    }

  /**
   * Maintenance on a merge-on-read table that carries a live position-delete file.
   * `createAndSeedMorDeleted` leaves keys 2 and 3 live with a live delete for key 1. These cases
   * check whether each maintenance procedure handles the delete file correctly: folding it away,
   * preserving it, or leaving the deleted row gone. The cases run across all three merge-on-read
   * formats because each format uses its own delete-file decoder.
   */
  lazy val maintenanceMorFoldCases: List[Plan.Case] =
    morDeletedPreparations(morVerifyLayouts)
      .flatMap { preparation =>
        List(
          maintMorRewriteDataFilesDanglingDelete(preparation),
          maintMorRewritePositionDeleteFolds(preparation))
      }

  /**
   * rewrite_data_files applies the live delete into the compacted data (key 1 stays gone,
   * two rows read back correctly) but leaves the now-dangling position-delete file in place.
   */
  private def maintMorRewriteDataFilesDanglingDelete(
      preparation: TablePreparation[CoreTable.type]): Plan.Case =
    preparation.test(
      "maint.mor.rewriteDataFilesDanglingDelete") { table =>
      table.spark.sql(
        "CALL openhouse.system.rewrite_data_files(" +
          s"table => '${catalogRelative(table.name)}', " +
          "options => map('rewrite-all', 'true'))")

      assert(
        table.spark
          .sql(s"SELECT count(*) FROM ${table.name}")
          .collect()(0)
          .getLong(0) == 2,
        "rewrite_data_files changed the live row set")
      assert(
        table.spark
          .sql(
            s"SELECT count(*) FROM ${table.name} " +
              s"WHERE ${Core.long0.columnName} = 1")
          .collect()(0)
          .getLong(0) == 0,
        "rewrite_data_files resurrected the deleted row")

      val deleteFileCount = table.spark
        .sql(s"SELECT count(*) FROM ${table.name}.delete_files")
        .collect()(0)
        .getLong(0)
      val keys = table.spark
        .sql(
          s"SELECT ${Core.long0.columnName} FROM ${table.name} " +
            s"WHERE ${Core.long0.columnName} <= 2 " +
            s"ORDER BY ${Core.long0.columnName}")
        .collect()
        .toSeq
        .map(_.getLong(0))

      assert(
        deleteFileCount == 1,
        "rewrite_data_files should leave one dangling position delete, " +
          s"got $deleteFileCount")
      assert(
        keys == Seq(2L),
        s"read after rewrite_data_files returned incorrect keys: $keys")
    }

  /**
   * After rewrite_data_files leaves a dangling position delete,
   * rewrite_position_delete_files folds it away (the delete-file count drops to zero) while
   * the live row set stays correct.
   */
  private def maintMorRewritePositionDeleteFolds(
      preparation: TablePreparation[CoreTable.type]): Plan.Case =
    preparation.test(
      "maint.mor.rewritePositionDeleteFolds") { table =>
      table.spark.sql(
        "CALL openhouse.system.rewrite_data_files(" +
          s"table => '${catalogRelative(table.name)}', " +
          "options => map('rewrite-all', 'true'))")
      val deleteFilesBefore = table.spark
        .sql(s"SELECT count(*) FROM ${table.name}.delete_files")
        .collect()(0)
        .getLong(0)

      table.spark.sql(
        "CALL openhouse.system.rewrite_position_delete_files(" +
          s"table => '${catalogRelative(table.name)}', " +
          "options => map('rewrite-all', 'true'))")
      val deleteFilesAfter = table.spark
        .sql(s"SELECT count(*) FROM ${table.name}.delete_files")
        .collect()(0)
        .getLong(0)

      assert(
        table.spark
          .sql(s"SELECT count(*) FROM ${table.name}")
          .collect()(0)
          .getLong(0) == 2,
        "rewrite_position_delete_files changed the live row set")
      assert(
        table.spark
          .sql(
            s"SELECT count(*) FROM ${table.name} " +
              s"WHERE ${Core.long0.columnName} = 1")
          .collect()(0)
          .getLong(0) == 0,
        "rewrite_position_delete_files resurrected the deleted row")
      assert(
        deleteFilesBefore == 1 && deleteFilesAfter == 0,
        "rewrite_position_delete_files should fold the dangling delete: " +
          s"before=$deleteFilesBefore after=$deleteFilesAfter")
    }

  /**
   * Metadata-only maintenance over a live delete does not decode the delete file, so its behavior
   * does not vary by format; this runs against a single merge-on-read layout. Each case must
   * preserve the delete (2 live rows, key 1 still gone).
   */
  lazy val maintenanceMorMetaCases: List[Plan.Case] =
    morDeletedPreparations(
      morVerifyLayouts.filter(layout =>
        layout.label == "mor-verify/parquet" ||
          layout.label == "mor-verify/orc"))
      .flatMap { preparation =>
        List(
          maintMorExpireSnapshots(preparation),
          maintMorRewriteManifests(preparation),
          maintMorRemoveOrphanFiles(preparation),
          maintMorCompactThenExpire(preparation))
      }

  /**
   * expire_snapshots over a table with a live position-delete file leaves the 2 live rows
   * unchanged and does not resurrect the deleted row.
   */
  private def maintMorExpireSnapshots(
      preparation: TablePreparation[CoreTable.type]): Plan.Case =
    preparation.test(
      "maint.mor.expireSnapshots") { table =>
      table.spark.sql(
        "CALL openhouse.system.expire_snapshots(" +
          s"table => '${catalogRelative(table.name)}', " +
          "older_than => TIMESTAMP '2999-01-01 00:00:00', " +
          "retain_last => 1)")

      assert(
        table.spark
          .sql(s"SELECT count(*) FROM ${table.name}")
          .collect()(0)
          .getLong(0) == 2,
        "expire_snapshots changed the live row set")
      assert(
        table.spark
          .sql(
            s"SELECT count(*) FROM ${table.name} " +
              s"WHERE ${Core.long0.columnName} = 1")
          .collect()(0)
          .getLong(0) == 0,
        "expire_snapshots resurrected the deleted row")
    }

  /**
   * rewrite_manifests over a table with a live position-delete file leaves the 2 live rows
   * unchanged.
   */
  private def maintMorRewriteManifests(
      preparation: TablePreparation[CoreTable.type]): Plan.Case =
    preparation.test(
      "maint.mor.rewriteManifests") { table =>
      table.spark.sql(
        "CALL openhouse.system.rewrite_manifests(" +
          s"table => '${catalogRelative(table.name)}', " +
          "use_caching => false)")

      assert(
        table.spark
          .sql(s"SELECT count(*) FROM ${table.name}")
          .collect()(0)
          .getLong(0) == 2,
        "rewrite_manifests changed the live row set")
    }

  /**
   * remove_orphan_files over a table with a live position-delete file leaves the 2 live rows
   * unchanged.
   */
  private def maintMorRemoveOrphanFiles(
      preparation: TablePreparation[CoreTable.type]): Plan.Case =
    preparation.test(
      "maint.mor.removeOrphanFiles") { table =>
      table.spark.sql(
        "CALL openhouse.system.remove_orphan_files(" +
          s"table => '${catalogRelative(table.name)}', " +
          "older_than => TIMESTAMP '2020-01-01 00:00:00')")

      assert(
        table.spark
          .sql(s"SELECT count(*) FROM ${table.name}")
          .collect()(0)
          .getLong(0) == 2,
        "remove_orphan_files changed the live row set")
    }

  /**
   * Running rewrite_position_delete_files followed by expire_snapshots leaves the 2 live rows
   * unchanged and does not resurrect the deleted row.
   */
  private def maintMorCompactThenExpire(
      preparation: TablePreparation[CoreTable.type]): Plan.Case =
    preparation.test(
      "maint.mor.compactThenExpire") { table =>
      table.spark.sql(
        "CALL openhouse.system.rewrite_position_delete_files(" +
          s"table => '${catalogRelative(table.name)}', " +
          "options => map('rewrite-all', 'true'))")
      table.spark.sql(
        "CALL openhouse.system.expire_snapshots(" +
          s"table => '${catalogRelative(table.name)}', " +
          "older_than => TIMESTAMP '2999-01-01 00:00:00', " +
          "retain_last => 1)")

      assert(
        table.spark
          .sql(s"SELECT count(*) FROM ${table.name}")
          .collect()(0)
          .getLong(0) == 2,
        "compact-then-expire changed the live row set")
      assert(
        table.spark
          .sql(
            s"SELECT count(*) FROM ${table.name} " +
              s"WHERE ${Core.long0.columnName} = 1")
          .collect()(0)
          .getLong(0) == 0,
        "compact-then-expire resurrected the deleted row")
    }

  /**
   * A live position delete is snapshot-scoped state. These cases check that it is resolved
   * correctly across history and restore: a delete must not be retroactive (pre-delete snapshots
   * still show the row), rollback must undo it, and it must survive expiration of older snapshots.
   * Time travel and rollback select snapshots. One merge-on-read layout covers this
   * format-independent behavior.
   */
  lazy val morHazardCases: List[Plan.Case] =
    morDeletedPreparations(
      morVerifyLayouts.filter(layout =>
        layout.label == "mor-verify/parquet" ||
          layout.label == "mor-verify/orc"))
      .flatMap { preparation =>
        List(
          hazardMorTimeTravelBeforeDelete(preparation),
          hazardMorRollbackUndoesDelete(preparation),
          hazardMorExpireThenDeleteHolds(preparation))
      }

  /**
   * The current read applies the live position delete (2 rows), while VERSION AS OF the snapshot
   * before the delete still shows the deleted row.
   */
  private def hazardMorTimeTravelBeforeDelete(
      preparation: TablePreparation[CoreTable.type]): Plan.Case =
    preparation.test(
      "hazard.mor.timeTravelBeforeDelete") { table =>
      val seedSnapshotId = table.spark
        .sql(
          s"SELECT snapshot_id FROM ${table.name}.snapshots " +
            "ORDER BY committed_at LIMIT 1")
        .collect()(0)
        .getLong(0)

      assert(
        table.spark
          .sql(s"SELECT count(*) FROM ${table.name}")
          .collect()(0)
          .getLong(0) == 2,
        "current merge-on-read state should apply the delete")
      assert(
        table.spark
          .sql(
            s"SELECT count(*) FROM ${table.name} " +
              s"VERSION AS OF $seedSnapshotId")
          .collect()(0)
          .getLong(0) == 3,
        "the snapshot before the delete should still contain the row")
    }

  /**
   * rollback_to_snapshot to before the position delete restores the deleted row and the full 3-row
   * set.
   */
  private def hazardMorRollbackUndoesDelete(
      preparation: TablePreparation[CoreTable.type]): Plan.Case =
    preparation.test(
      "hazard.mor.rollbackUndoesDelete") { table =>
      val seedSnapshotId = table.spark
        .sql(
          s"SELECT snapshot_id FROM ${table.name}.snapshots " +
            "ORDER BY committed_at LIMIT 1")
        .collect()(0)
        .getLong(0)

      table.spark.sql(
        "CALL openhouse.system.rollback_to_snapshot(" +
          s"table => '${catalogRelative(table.name)}', " +
          s"snapshot_id => ${seedSnapshotId}L)")

      assert(
        table.spark
          .sql(s"SELECT count(*) FROM ${table.name}")
          .collect()(0)
          .getLong(0) == 3,
        "rollback did not undo the merge-on-read delete")
      assert(
        table.spark
          .sql(
            s"SELECT count(*) FROM ${table.name} " +
              s"WHERE ${Core.long0.columnName} = 1")
          .collect()(0)
          .getLong(0) == 1,
        "rollback did not restore the deleted row")
    }

  /** After snapshot expiration, a read still excludes the position-deleted row. */
  private def hazardMorExpireThenDeleteHolds(
      preparation: TablePreparation[CoreTable.type]): Plan.Case =
    preparation.test(
      "hazard.mor.expireThenDeleteHolds") { table =>
      table.spark.sql(
        "CALL openhouse.system.expire_snapshots(" +
          s"table => '${catalogRelative(table.name)}', " +
          "older_than => TIMESTAMP '2999-01-01 00:00:00', " +
          "retain_last => 1)")

      val keys = table.spark
        .sql(
          s"SELECT ${Core.long0.columnName} FROM ${table.name} " +
            s"WHERE ${Core.long0.columnName} <= 2 " +
            s"ORDER BY ${Core.long0.columnName}")
        .collect()
        .toSeq
        .map(_.getLong(0))

      assert(
        keys == Seq(2L),
        s"delete did not survive snapshot expiration: $keys")
    }
}
