package harness

// The merge-on-read preparation kit. A merge-on-read table is format version 2 with the delete,
// update and merge modes set to merge-on-read, so a mutation records position-delete files while
// preserving the untouched data files. This layer sits above RTAS, so it also owns the replace-lineage
// merge-on-read preparations. The members are lazy so they initialize on first read, after every
// trait mixed into `object Scenarios` has been constructed.
trait MorScenarioKit extends RtasScenarioKit {

  /**
   * One merge-on-read layout: a format-version 2 table whose delete, update and merge modes are
   * merge-on-read, so a mutation records its change in position-delete files and leaves the
   * untouched data files in place. `partitioning` sets the table layout and `format` sets the file
   * format.
   */
  private def morLayout(partitioning: Partitioning, format: String): Layout =
    Layout(
      s"mor-${partitioning.label}/$format",
      table =>
        s"CREATE TABLE $table ($columnDefinitions) USING $dataSource ${partitioning.clause} " +
          s"TBLPROPERTIES ('write.format.default'='$format', 'format-version'='2', " +
          s"'write.delete.mode'='merge-on-read', 'write.update.mode'='merge-on-read', 'write.merge.mode'='merge-on-read')")

  /** Every merge-on-read layout: each file format crossed with each partitioning. */
  lazy val morLayouts: List[Layout] =
    for {
      format       <- fileFormats
      partitioning <- partitionings
    } yield morLayout(partitioning, format)

  /** The unpartitioned merge-on-read layouts, one per file format. */
  lazy val unpartitionedMorLayouts: List[Layout] =
    fileFormats.map(format => morLayout(unpartitioned, format))

  /**
   * One merge-on-read layout per file format that pins how a DELETE is written physically: it sets
   * write.distribution-mode to none and stays unpartitioned, so a single seed INSERT lands every
   * row in one data file. Deleting a strict subset is then a partial-file match, which Iceberg
   * satisfies by writing a position delete rather than by dropping a whole file, so the physical
   * outcome is deterministic across formats.
   */
  lazy val morVerifyLayouts: List[Layout] =
    fileFormats.map(format => Layout(
      s"mor-verify/$format",
      table =>
        s"CREATE TABLE $table ($columnDefinitions) USING $dataSource TBLPROPERTIES (" +
          s"'write.format.default'='$format', 'format-version'='2', 'write.distribution-mode'='none', " +
          s"'write.delete.mode'='merge-on-read')"))

  /**
   * One copy-on-write layout per file format, the counterpart of `morVerifyLayouts`: it sets
   * write.distribution-mode to none and stays unpartitioned, so a single seed INSERT lands every
   * row in one data file and a strict-subset DELETE rewrites that data file instead of writing a
   * position-delete file.
   */
  lazy val cowVerifyLayouts: List[Layout] =
    fileFormats.map(format => Layout(
      s"cow-verify/$format",
      table =>
        s"CREATE TABLE $table ($columnDefinitions) USING $dataSource TBLPROPERTIES (" +
          s"'write.format.default'='$format', 'format-version'='2', 'write.distribution-mode'='none', " +
          s"'write.delete.mode'='copy-on-write')"))

  /**
   * One preparation per merge-on-read layout: three seed rows with keys 1, 2 and 3, so a later
   * mutation records its change in position-delete files.
   */
  lazy val preparedMorCoreTables: List[TablePreparation[CoreTable.type]] =
    morLayouts.map(layout =>
      TablePreparation(
        layout.label,
        createAndSeed(layout, 3)))

  /**
   * Creates the table under `layout`, then seeds `numberOfRows` rows into one data file. A plain
   * seed INSERT spreads the rows over a couple of files, where a delete aligned with a file
   * boundary is satisfied by dropping that whole file. The COALESCE(1) hint forces a single write
   * task and so a single data file, which makes a strict-subset delete a partial-file match:
   * merge-on-read writes a position delete for it, and copy-on-write rewrites the data file.
   */
  def createAndSeedSingleFile(layout: Layout, numberOfRows: Int): TableTest[CoreTable.type] =
    TableTest(Core).sql("create")(layout.create)()
      .sql(s"seed($numberOfRows, one-file)")(table =>
        s"INSERT INTO $table SELECT /*+ COALESCE(1) */ * FROM (${RowGenerator.valuesClause(Core, numberOfRows)}) AS seed")(
        view => assert(view.after.size == numberOfRows,
          s"single-file seed expected $numberOfRows rows, got ${view.after.size}"))

  /**
   * Seeds `numberOfRows` rows into one data file on a merge-on-read layout, then deletes the row
   * with key 1. The table holds the remaining rows behind a live position-delete file, which
   * exercises the scan path where the reader applies a position delete at read time.
   */
  def createAndSeedMorDeleted(layout: Layout, numberOfRows: Int): TableTest[CoreTable.type] =
    createAndSeedSingleFile(layout, numberOfRows)
      .step("prep.morDelete") { (spark, table) =>
        spark.sql(s"DELETE FROM $table WHERE ${Core.long0.columnName} = 1")   // a strict subset, so Iceberg writes a position delete
      } { view =>
        assert(view.after.size == numberOfRows - 1, s"MoR prep delete failed: ${view.after.size}")
        val deleteFiles = view.spark.sql(s"SELECT count(*) FROM ${view.table}.all_delete_files").collect()(0).getLong(0)
        assert(deleteFiles == 1, s"MoR prep must leave a live position-delete file, got $deleteFiles")
      }

  /**
   * One preparation per merge-on-read verify layout: three seed rows written as one data file, then
   * the row with key 1 deleted merge-on-read, so keys 2 and 3 remain behind a live position-delete
   * file that the reader applies at scan time.
   */
  lazy val preparedMorReadCoreTables: List[TablePreparation[CoreTable.type]] =
    morVerifyLayouts.map { layout =>
      TablePreparation(
        layout.label,
        createAndSeedMorDeleted(layout, 3),
        "prep.morRead:")
    }

  /**
   * The merge-on-read table property fragment for `format`: format-version 2 with the delete,
   * update and merge modes all set to merge-on-read.
   */
  protected def morPropsFmt(format: String) = s"'write.format.default'='$format', 'format-version'='2', " +
    "'write.delete.mode'='merge-on-read', 'write.update.mode'='merge-on-read', 'write.merge.mode'='merge-on-read'"

  /**
   * Creates a replace-lineage merge-on-read table: it seeds `numberOfRows` rows, then re-specifies
   * the same shape and merge-on-read modes through CREATE OR REPLACE TABLE AS SELECT and refreshes
   * the table, so the result holds the seeded rows reached through the replace path and later
   * mutations run on the merge-on-read write path. `partitioning` sets the table layout and
   * `format` sets the file format.
   */
  def createAndSeedRtasMor(partitioning: Partitioning, numberOfRows: Int, format: String): TableTest[CoreTable.type] =
    TableTest(Core)
      .sql("create")(t => s"CREATE TABLE $t ($columnDefinitions) USING $dataSource ${partitioning.clause} " +
        s"TBLPROPERTIES (${morPropsFmt(format)}, 'replace.enabled'='true')")()
      .insert(numberOfRows)()
      .sql("prep.rtasMor")(t => s"CREATE OR REPLACE TABLE $t USING $dataSource ${partitioning.clause} " +
        s"TBLPROPERTIES (${morPropsFmt(format)}) AS SELECT * FROM $t")()
      // The OpenHouse user guide requires REFRESH TABLE after a replace, so the Spark session
      // reads the committed metadata pointer before the preparation returns.
      .sql("prep.rtasMor.refresh")(t => s"REFRESH TABLE $t")()

  /**
   * One replace-lineage merge-on-read preparation per file format: three seed rows with keys 1, 2
   * and 3 in an unpartitioned table, then replaced in place by CREATE OR REPLACE TABLE AS SELECT
   * re-specifying the merge-on-read modes, so mutations run on replace lineage.
   */
  lazy val preparedRtasMorCoreTables: List[TablePreparation[CoreTable.type]] =
    fileFormats.map { format =>
      TablePreparation(
        s"mor-${unpartitioned.label}/$format",
        createAndSeedRtasMor(unpartitioned, 3, format),
        "prep.rtasMor:")
    }

  /** The merge-on-read core preparations, each carrying one row whose string column is null. */
  lazy val preparedNullStringMorCoreTables: List[TablePreparation[CoreTable.type]] =
    preparedMorCoreTables.map(withNullStringRow)

  /** The replace-lineage merge-on-read preparations, each carrying one row whose string column is null. */
  lazy val preparedNullStringRtasMorCoreTables: List[TablePreparation[CoreTable.type]] =
    preparedRtasMorCoreTables.map(withNullStringRow)

  /** The merge-on-read read preparations that carry a live position-delete file. */
  lazy val morReadLayoutFormatPreparations: List[TablePreparation[CoreTable.type]] =
    preparedMorReadCoreTables

  /** The format-materialization case on every merge-on-read read preparation. */
  def morReadLayoutFormatCases: List[Plan.Case] =
    layoutFormatCasesFor(morReadLayoutFormatPreparations)
}
