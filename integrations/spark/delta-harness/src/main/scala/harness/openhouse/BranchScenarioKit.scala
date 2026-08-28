package harness

// The branch and write-audit-publish preparation kit. A branch preparation seeds main, creates
// branch b, and routes the session at that branch through spark.wap.branch, so every read and write
// the case performs lands on the branch while main keeps its seed rows. This layer sits above
// merge-on-read, so it also owns the branch-on-merge-on-read preparations. The members are lazy so
// they initialize on first read, after every trait mixed into `object Scenarios` has been
// constructed.
trait BranchScenarioKit extends MorScenarioKit {

  /**
   * Creates and seeds the table under `layout`, enables write.wap.enabled, creates branch b, then
   * sets spark.wap.branch to b, so every later read and write in the case lands on the branch. A
   * case captures its own before state from the branch and asserts against it, so the same case
   * body holds on a branch and on main. Each case runs in its own spark.newSession(), which keeps
   * the setting scoped to that case. `layout` sets the starting table shape and `numberOfRows` sets
   * the seed row count.
   */
  def createAndSeedOnBranch(layout: Layout, numberOfRows: Int): TableTest[CoreTable.type] =
    createAndSeed(layout, numberOfRows)
      .sql("prep.enableWap")(t => s"ALTER TABLE $t SET TBLPROPERTIES ('write.wap.enabled'='true')")()
      .step("prep.routeToBranch") { (spark, table) =>
        spark.sql(s"ALTER TABLE $table CREATE BRANCH b")
        spark.conf.set("spark.wap.branch", "b")
      }()

  /**
   * The afterTest check every branch preparation carries: it clears spark.wap.branch and confirms
   * main still holds exactly the three seed rows, so the case's branch-routed writes did not leak
   * to main.
   */
  private def assertBranchMainIsolation(table: PreparedTable[CoreTable.type]): Unit = {
    table.spark.conf.unset("spark.wap.branch")
    val mainCount = table.spark
      .sql(s"SELECT count(*) FROM ${table.name}")
      .collect()(0)
      .getLong(0)
    assert(
      mainCount == 3,
      s"branch operation leaked to main: expected 3 rows, got $mainCount")
  }

  /**
   * One branch preparation per core layout: three seed rows with keys 1, 2 and 3 on main,
   * write.wap.enabled set, branch b created, and spark.wap.branch set to b, so every read and write
   * in the case lands on branch b while main keeps its three seed rows.
   */
  lazy val preparedBranchCoreTables: List[TablePreparation[CoreTable.type]] =
    layouts.map { layout =>
      TablePreparation(
        layout.label,
        createAndSeedOnBranch(layout, 3),
        "branchWap:",
        assertBranchMainIsolation)
    }

  /**
   * One branch preparation per datepartition-partitioned core layout, otherwise the same starting
   * state as `preparedBranchCoreTables`: three seed rows routed onto branch b while main keeps them.
   */
  lazy val preparedPartitionedBranchCoreTables: List[TablePreparation[CoreTable.type]] =
    partitionedLayouts.map { layout =>
      TablePreparation(
        layout.label,
        createAndSeedOnBranch(layout, 3),
        "branchWap:",
        assertBranchMainIsolation)
    }

  /**
   * One branch preparation per unpartitioned merge-on-read layout: three seed rows routed onto
   * branch b of a merge-on-read table while main keeps them, so a branch mutation records
   * position-delete files on the branch.
   */
  lazy val preparedBranchMorCoreTables: List[TablePreparation[CoreTable.type]] =
    unpartitionedMorLayouts.map { layout =>
      TablePreparation(
        layout.label,
        createAndSeedOnBranch(layout, 3),
        "branchWap:",
        assertBranchMainIsolation)
    }

  /** The branch core preparations, each carrying one row whose string column is null. */
  lazy val preparedNullStringBranchCoreTables: List[TablePreparation[CoreTable.type]] =
    preparedBranchCoreTables.map(withNullStringRow)

  /** The branch merge-on-read preparations, each carrying one row whose string column is null. */
  lazy val preparedNullStringBranchMorCoreTables: List[TablePreparation[CoreTable.type]] =
    preparedBranchMorCoreTables.map(withNullStringRow)

  /** Uses the branch core preparations because each writes data files for format inspection. */
  lazy val branchLayoutFormatPreparations: List[TablePreparation[CoreTable.type]] =
    preparedBranchCoreTables

  /** Runs format materialization on every branch preparation that writes data files. */
  def branchLayoutFormatCases: List[Plan.Case] =
    layoutFormatCasesFor(branchLayoutFormatPreparations)
}
