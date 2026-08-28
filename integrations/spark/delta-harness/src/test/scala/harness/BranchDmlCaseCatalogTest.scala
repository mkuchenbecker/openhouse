package harness

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins the shape of the branch DML buckets: each one is a branch-routed preparation list crossed
 * with a DML test-case list named by the standard layer. Reading these lists does not execute a
 * case or start Spark.
 */
final class BranchDmlCaseCatalogTest {

  @Test
  def eachBucketIsThePreparationListCrossedWithItsTestCaseList(): Unit = {
    assertEquals(
      caseIds(Scenarios.preparedBranchCoreTables, Scenarios.allDmlTestCases) ++
        caseIds(Scenarios.preparedNullStringBranchCoreTables, Scenarios.nullStringRowTestCases),
      Scenarios.branchDmlCases.map(_.id),
      "branchDmlCases is not its named preparations crossed with its named test cases")
    assertEquals(
      caseIds(Scenarios.preparedPartitionedBranchCoreTables, Scenarios.partitionedTableTestCases),
      Scenarios.branchPartitionedDmlCases.map(_.id),
      "branchPartitionedDmlCases is not its named preparations crossed with its named test cases")
    assertEquals(
      caseIds(Scenarios.preparedBranchMorCoreTables, Scenarios.rowMutationTestCases) ++
        caseIds(Scenarios.preparedNullStringBranchMorCoreTables, Scenarios.nullStringRowTestCases),
      Scenarios.branchMorDmlCases.map(_.id),
      "branchMorDmlCases is not its named preparations crossed with its named test cases")
  }

  @Test
  def theBranchPreparationsCarryTheRoutingTheySetUp(): Unit = {
    val branchPreparations =
      Scenarios.preparedBranchCoreTables ++
        Scenarios.preparedPartitionedBranchCoreTables ++
        Scenarios.preparedBranchMorCoreTables

    assertEquals(
      Scenarios.layouts.map(_.label),
      Scenarios.preparedBranchCoreTables.map(_.label))
    assertEquals(
      Scenarios.partitionedLayouts.map(_.label),
      Scenarios.preparedPartitionedBranchCoreTables.map(_.label))
    assertEquals(
      Scenarios.unpartitionedMorLayouts.map(_.label),
      Scenarios.preparedBranchMorCoreTables.map(_.label))

    branchPreparations.foreach { preparation =>
      assertEquals(
        "branchWap:",
        preparation.casePrefix,
        s"${preparation.label} is not marked as a branch-routed preparation")
      assertEquals(
        List("prep.enableWap", "prep.routeToBranch"),
        preparation.preparation.steps.map(_.label).toList.takeRight(2),
        s"${preparation.label} does not end by enabling WAP and routing to the branch")
    }
  }

  @Test
  def theNullStringPreparationsExtendTheBranchPreparations(): Unit = {
    assertEquals(
      Scenarios.preparedBranchCoreTables.map(preparation =>
        (preparation.casePrefix, preparation.label)),
      Scenarios.preparedNullStringBranchCoreTables.map(preparation =>
        (preparation.casePrefix, preparation.label)))
    assertEquals(
      Scenarios.preparedBranchCoreTables.map(_.preparation.steps.size + 1),
      Scenarios.preparedNullStringBranchCoreTables.map(_.preparation.steps.size))
    assertEquals(
      List("prep.nullStringRow"),
      Scenarios.preparedNullStringBranchCoreTables.head.preparation.steps
        .map(_.label).toList.takeRight(1))
    assertEquals(
      Scenarios.preparedBranchMorCoreTables.map(preparation =>
        (preparation.casePrefix, preparation.label)),
      Scenarios.preparedNullStringBranchMorCoreTables.map(preparation =>
        (preparation.casePrefix, preparation.label)))
    assertEquals(
      Scenarios.preparedBranchMorCoreTables.map(_.preparation.steps.size + 1),
      Scenarios.preparedNullStringBranchMorCoreTables.map(_.preparation.steps.size))
    assertEquals(
      List("prep.nullStringRow"),
      Scenarios.preparedNullStringBranchMorCoreTables.head.preparation.steps
        .map(_.label).toList.takeRight(1))
  }

  @Test
  def theLayoutFormatCasesRunOnTheBranchPreparations(): Unit =
    assertEquals(
      caseIds(Scenarios.branchLayoutFormatPreparations, "format.materialization"),
      Scenarios.branchLayoutFormatCases.map(_.id))

  private def caseIds(
      preparations: List[TablePreparation[CoreTable.type]],
      testCases: List[DmlTestCase[CoreTable.type]]
  ): List[String] =
    preparations.flatMap(preparation =>
      testCases.map(testCase =>
        s"${preparation.casePrefix}${testCase.id} @ ${preparation.label}"))

  private def caseIds(
      preparations: List[TablePreparation[CoreTable.type]],
      testCaseId: String
  ): List[String] =
    preparations.map(preparation =>
      s"${preparation.casePrefix}$testCaseId @ ${preparation.label}")
}
