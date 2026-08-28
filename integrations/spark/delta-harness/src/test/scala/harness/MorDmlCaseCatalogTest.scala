package harness

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins the shape of the merge-on-read DML buckets: each one is a merge-on-read preparation list
 * crossed with a DML test-case list named by the standard layer, plus the pair of cases that
 * assert the physical difference between the two write modes. Reading these lists does not
 * execute a case or start Spark.
 */
final class MorDmlCaseCatalogTest {

  @Test
  def eachBucketIsThePreparationListCrossedWithItsTestCaseList(): Unit = {
    assertEquals(
      caseIds(Scenarios.preparedMorCoreTables, Scenarios.rowMutationTestCases) ++
        caseIds(Scenarios.preparedNullStringMorCoreTables, Scenarios.nullStringRowTestCases),
      Scenarios.morDmlCases.map(_.id),
      "morDmlCases is not its named preparations crossed with its named test cases")
    assertEquals(
      caseIds(Scenarios.preparedRtasMorCoreTables, Scenarios.rowMutationTestCases) ++
        caseIds(Scenarios.preparedNullStringRtasMorCoreTables, Scenarios.nullStringRowTestCases),
      Scenarios.rtasMorDmlCases.map(_.id),
      "rtasMorDmlCases is not its named preparations crossed with its named test cases")
    assertEquals(
      caseIds(Scenarios.preparedMorReadCoreTables, Scenarios.readTestCases),
      Scenarios.morReadDmlCases.map(_.id),
      "morReadDmlCases is not its named preparations crossed with its named test cases")
  }

  @Test
  def theDeleteFileModeBucketPairsOneMergeOnReadCaseWithOneCopyOnWriteCase(): Unit =
    assertEquals(
      Scenarios.morVerifyLayouts.map(layout => s"mor.writesDeleteFiles @ ${layout.label}") ++
        Scenarios.cowVerifyLayouts.map(layout => s"cow.writesNoDeleteFiles @ ${layout.label}"),
      Scenarios.deleteFileModeCases.map(_.id))

  @Test
  def theLayoutFormatCasesRunOnTheMergeOnReadReadPreparations(): Unit =
    assertEquals(
      caseIds(Scenarios.morReadLayoutFormatPreparations, "format.materialization"),
      Scenarios.morReadLayoutFormatCases.map(_.id))

  @Test
  def eachMergeOnReadLayoutListCrossesItsFormatsWithItsPartitionings(): Unit = {
    assertEquals(
      List(
        "mor-unpartitioned/parquet",
        "mor-partitioned/parquet",
        "mor-unpartitioned/orc",
        "mor-partitioned/orc",
        "mor-unpartitioned/avro",
        "mor-partitioned/avro"),
      Scenarios.morLayouts.map(_.label))
    assertEquals(
      List("mor-unpartitioned/parquet", "mor-unpartitioned/orc", "mor-unpartitioned/avro"),
      Scenarios.unpartitionedMorLayouts.map(_.label))
    assertEquals(
      List("mor-verify/parquet", "mor-verify/orc", "mor-verify/avro"),
      Scenarios.morVerifyLayouts.map(_.label))
    assertEquals(
      List("cow-verify/parquet", "cow-verify/orc", "cow-verify/avro"),
      Scenarios.cowVerifyLayouts.map(_.label))
  }

  @Test
  def theMergeOnReadPreparationsCarryTheirWritePath(): Unit = {
    assertEquals(
      Scenarios.morLayouts.map(_.label),
      Scenarios.preparedMorCoreTables.map(_.label))
    Scenarios.preparedMorCoreTables.foreach { preparation =>
      assertEquals(
        "",
        preparation.casePrefix,
        s"${preparation.label} should be an unprefixed merge-on-read preparation")
      assertEquals(
        List("create", "insert(3)"),
        preparation.preparation.steps.map(_.label).toList,
        s"${preparation.label} is not a created-and-seeded table")
    }
    Scenarios.preparedMorReadCoreTables.foreach { preparation =>
      assertEquals(
        "prep.morRead:",
        preparation.casePrefix,
        s"${preparation.label} is not marked as a merge-on-read read preparation")
      assertEquals(
        List("prep.morDelete"),
        preparation.preparation.steps.map(_.label).toList.takeRight(1),
        s"${preparation.label} does not end with the merge-on-read delete step")
    }
    Scenarios.preparedRtasMorCoreTables.foreach { preparation =>
      assertEquals(
        "prep.rtasMor:",
        preparation.casePrefix,
        s"${preparation.label} is not marked as a replace-lineage merge-on-read preparation")
      assertEquals(
        List("prep.rtasMor", "prep.rtasMor.refresh"),
        preparation.preparation.steps.map(_.label).toList.takeRight(2),
        s"${preparation.label} does not end with the replace-and-refresh steps")
    }
  }

  @Test
  def theNullStringPreparationsExtendTheMergeOnReadPreparations(): Unit = {
    assertEquals(
      Scenarios.preparedMorCoreTables.map(preparation =>
        (preparation.casePrefix, preparation.label)),
      Scenarios.preparedNullStringMorCoreTables.map(preparation =>
        (preparation.casePrefix, preparation.label)))
    assertEquals(
      Scenarios.preparedMorCoreTables.map(_.preparation.steps.size + 1),
      Scenarios.preparedNullStringMorCoreTables.map(_.preparation.steps.size))
    assertEquals(
      List("prep.nullStringRow"),
      Scenarios.preparedNullStringMorCoreTables.head.preparation.steps
        .map(_.label).toList.takeRight(1))
    assertEquals(
      Scenarios.preparedRtasMorCoreTables.map(preparation =>
        (preparation.casePrefix, preparation.label)),
      Scenarios.preparedNullStringRtasMorCoreTables.map(preparation =>
        (preparation.casePrefix, preparation.label)))
    assertEquals(
      Scenarios.preparedRtasMorCoreTables.map(_.preparation.steps.size + 1),
      Scenarios.preparedNullStringRtasMorCoreTables.map(_.preparation.steps.size))
    assertEquals(
      List("prep.nullStringRow"),
      Scenarios.preparedNullStringRtasMorCoreTables.head.preparation.steps
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
