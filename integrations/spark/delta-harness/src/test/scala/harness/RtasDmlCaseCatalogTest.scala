package harness

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins the shape of the RTAS DML buckets: each one is a replace-lineage preparation list crossed
 * with a DML test-case list the standard layer names. Reading these lists does not execute a case
 * or start Spark.
 */
final class RtasDmlCaseCatalogTest {

  @Test
  def eachBucketIsThePreparationListCrossedWithItsTestCaseList(): Unit = {
    assertEquals(
      caseIds(Scenarios.preparedRtasCoreTables, Scenarios.allDmlTestCases) ++
        caseIds(Scenarios.preparedNullStringRtasCoreTables, Scenarios.nullStringRowTestCases),
      Scenarios.rtasDmlCases.map(_.id),
      "rtasDmlCases is not its named preparations crossed with its named test cases")
    assertEquals(
      caseIds(Scenarios.preparedRtasPartitionedCoreTables, Scenarios.partitionedTableTestCases),
      Scenarios.rtasPartitionedDmlCases.map(_.id),
      "rtasPartitionedDmlCases is not its named preparations crossed with its named test cases")
  }

  @Test
  def theReplaceLineagePreparationsCarryTheReplacePath(): Unit = {
    Scenarios.preparedRtasCoreTables.foreach { preparation =>
      assertEquals(
        "prep.rtas:",
        preparation.casePrefix,
        s"${preparation.label} is not marked as a replace-lineage preparation")
      assertEquals(
        List("prep.rtas", "prep.rtas.refresh"),
        preparation.preparation.steps.map(_.label).toList.takeRight(2),
        s"${preparation.label} does not end with the replace-and-refresh steps")
    }
  }

  @Test
  def theLayoutFormatCasesRunOnTheReplaceLineagePreparations(): Unit =
    assertEquals(
      caseIds(Scenarios.rtasLayoutFormatPreparations, "format.materialization"),
      Scenarios.rtasLayoutFormatCases.map(_.id))

  @Test
  def theNullStringPreparationsExtendTheReplaceLineagePreparations(): Unit = {
    assertEquals(
      Scenarios.preparedRtasCoreTables.map(preparation =>
        (preparation.casePrefix, preparation.label)),
      Scenarios.preparedNullStringRtasCoreTables.map(preparation =>
        (preparation.casePrefix, preparation.label)))
    assertEquals(
      Scenarios.preparedRtasCoreTables.map(_.preparation.steps.size + 1),
      Scenarios.preparedNullStringRtasCoreTables.map(_.preparation.steps.size))
    assertEquals(
      List("prep.nullStringRow"),
      Scenarios.preparedNullStringRtasCoreTables.head.preparation.steps
        .map(_.label).toList.takeRight(1))
  }

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
