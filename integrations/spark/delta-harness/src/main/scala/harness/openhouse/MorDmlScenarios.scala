package harness

// The merge-on-read DML buckets. The mutation buckets are merge-on-read preparation lists crossed
// with the shared DML test-case lists that DmlScenarios names, so a merge-on-read table runs the
// same row-delta assertions as a copy-on-write one. The delete-file-mode bucket is the exception:
// it asserts the physical difference between the two write modes directly.
trait MorDmlScenarios extends MorScenarioKit { this: DmlScenarios =>
  import Rows._

  /**
   * The merge-on-read row-mutation bucket: every merge-on-read core preparation crossed with the
   * shared row-mutation cases, plus the null-string preparations crossed with the null-string case.
   */
  lazy val morDmlCases: List[Plan.Case] =
    preparedMorCoreTables.flatMap(preparation => rowMutationTestCases.map(_.runOn(preparation))) ++
      preparedNullStringMorCoreTables.flatMap(preparation =>
        nullStringRowTestCases.map(_.runOn(preparation)))

  /**
   * The replace-lineage merge-on-read row-mutation bucket: every replace-lineage merge-on-read
   * preparation crossed with the shared row-mutation cases, plus the null-string counterparts.
   */
  lazy val rtasMorDmlCases: List[Plan.Case] =
    preparedRtasMorCoreTables.flatMap(preparation =>
      rowMutationTestCases.map(_.runOn(preparation))) ++
      preparedNullStringRtasMorCoreTables.flatMap(preparation =>
        nullStringRowTestCases.map(_.runOn(preparation)))

  /**
   * The merge-on-read read bucket: every merge-on-read read preparation, which starts behind a live
   * position-delete file, crossed with the shared read cases.
   */
  lazy val morReadDmlCases: List[Plan.Case] =
    preparedMorReadCoreTables.flatMap(preparation => readTestCases.map(_.runOn(preparation)))

  /**
   * One preparation per merge-on-read verify layout: three seed rows with keys 1, 2 and 3 written
   * as one data file, so a strict-subset delete is a partial-file match.
   */
  private lazy val preparedSingleFileMorTables: List[TablePreparation[CoreTable.type]] =
    morVerifyLayouts.map(layout =>
      TablePreparation(
        layout.label,
        createAndSeedSingleFile(layout, 3)))

  /**
   * One preparation per copy-on-write verify layout: three seed rows with keys 1, 2 and 3 written
   * as one data file, so a strict-subset delete is a partial-file match.
   */
  private lazy val preparedSingleFileCowTables: List[TablePreparation[CoreTable.type]] =
    cowVerifyLayouts.map(layout =>
      TablePreparation(
        layout.label,
        createAndSeedSingleFile(layout, 3)))

  /**
   * A merge-on-read DELETE WHERE foo_col_long < 2 against a single data file removes the matching
   * row, records the removal in at least one position-delete file, and commits one snapshot.
   */
  private lazy val morWritesDeleteFiles: DmlTestCase[CoreTable.type] =
    DmlTestCase(
      "mor.writesDeleteFiles",
      table => {
        val before = table.state

        table.spark.sql(
          s"DELETE FROM ${table.name} WHERE ${Core.long0.columnName} < 2")
        val after = table.state
        val deleteFileCount = table.spark
          .sql(s"SELECT count(*) FROM ${table.name}.delete_files")
          .collect()(0)
          .getLong(0)

        assert(
          after.rows == before.rows.filterNot(_.get(Core.long0) < 2),
          s"strict-subset DELETE returned an unexpected row set: ${after.rows}")
        assert(
          deleteFileCount >= 1,
          "merge-on-read DELETE should write a position-delete file")
        assert(
          after.snapshotCount == before.snapshotCount + 1,
          "a merge-on-read DELETE commits one snapshot")
      })

  /**
   * A copy-on-write DELETE WHERE foo_col_long < 2 against a single data file removes the matching
   * row by rewriting that file, leaves the table with no delete files, and commits one snapshot.
   */
  private lazy val cowWritesNoDeleteFiles: DmlTestCase[CoreTable.type] =
    DmlTestCase(
      "cow.writesNoDeleteFiles",
      table => {
        val before = table.state

        table.spark.sql(
          s"DELETE FROM ${table.name} WHERE ${Core.long0.columnName} < 2")
        val after = table.state
        val deleteFileCount = table.spark
          .sql(s"SELECT count(*) FROM ${table.name}.delete_files")
          .collect()(0)
          .getLong(0)

        assert(
          after.rows == before.rows.filterNot(_.get(Core.long0) < 2),
          s"strict-subset DELETE returned an unexpected row set: ${after.rows}")
        assert(
          deleteFileCount == 0,
          "copy-on-write DELETE should not write delete files")
        assert(
          after.snapshotCount == before.snapshotCount + 1,
          "a copy-on-write DELETE commits one snapshot")
      })

  /**
   * The delete-file-mode bucket: the merge-on-read case on every single-file merge-on-read
   * preparation, then the copy-on-write case on every single-file copy-on-write preparation. Both
   * cases delete one of three rows from a single data file, which makes the physical difference
   * between the two write modes deterministic across formats.
   */
  lazy val deleteFileModeCases: List[Plan.Case] =
    preparedSingleFileMorTables.map(morWritesDeleteFiles.runOn) ++
      preparedSingleFileCowTables.map(cowWritesNoDeleteFiles.runOn)
}
