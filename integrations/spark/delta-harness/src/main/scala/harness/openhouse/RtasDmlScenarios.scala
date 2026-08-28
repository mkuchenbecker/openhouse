package harness

// The RTAS DML buckets. Each bucket is a replace-lineage preparation list crossed with one of the
// shared DML test-case lists that DmlScenarios names, so a replaced table runs the same operations
// and the same assertions as a freshly created one.
trait RtasDmlScenarios extends RtasScenarioKit { this: DmlScenarios =>

  /**
   * Every DML case on the replace-lineage core preparations, plus the null-string DELETE on the
   * same preparations extended with a null-string row.
   */
  lazy val rtasDmlCases: List[Plan.Case] =
    preparedRtasCoreTables.flatMap(preparation => allDmlTestCases.map(_.runOn(preparation))) ++
      preparedNullStringRtasCoreTables.flatMap(preparation =>
        nullStringRowTestCases.map(_.runOn(preparation)))

  /** The partition-scoped writes on the replace-lineage partitioned preparations. */
  lazy val rtasPartitionedDmlCases: List[Plan.Case] =
    preparedRtasPartitionedCoreTables.flatMap(preparation =>
      partitionedTableTestCases.map(_.runOn(preparation)))
}
