# Combined branch irc/reapply-all validation (JDK 21, Gradle 8.14.5, Scala 2.13)

**Bottom line:** The 13 patches compile together (incl. Spark 4.2) and merge with no conflicts. Of 20,654 tests run, 8 failed, all in one class, `spark.actions.TestComputePartitionStatsAction`. The failure comes from one patch alone, not an interaction: LinkedIn d69c1fd91, "Default partitioned write distribution mode to NONE" (reapplied as 9bcea01fd). That patch changes how many data files an INSERT writes, and the test hard-codes the count. Checkstyle fails on 4 files, each introduced by a single source branch. revapi and spotless pass. Everything in the Spark 3.5 and extensions suites ran; one run was OOM-killed and re-run.

Branch: irc/reapply-all = irc/reapply-orc-defaults (5cce4931f) + merge of irc/reapply-spark-read (7bf1378f5) + merge of irc/reapply-rewrite (e4a27905f). Sources: reapply-independent (4), reapply-replication (3+2), reapply-orc-defaults (3+1), reapply-spark-read (2), reapply-rewrite (2).

## Merge conflicts
None. Both merges completed with the ort strategy and no conflict markers. No resolution was needed.

## Validation
Flags `$F` = `--no-daemon --continue --max-workers=2 -Dorg.gradle.jvmargs=-Xmx4g -DsparkVersions=<v> -DscalaVersion=2.13 -DflinkVersions= -DkafkaVersions=`. Counts are from JUnit XML (tests/failed/skipped). Logs are in scratchpad/all/.

| Step | Command (abridged) | Run | Fail | Skip | Wall |
|---|---|---|---|---|---|
| 1 compile | `$F` (v=3.5,4.2) compileJava+compileTestJava on api, core, common, data, orc, parquet, open-api (+compileTestFixturesJava), spark 3.5/4.2 and extensions 3.5/4.2; bundled-guava compileJava | BUILD SUCCESSFUL | 0 | - | 348 s |
| 2 api | `:iceberg-api:test` | 1295 | 0 | 1 | 36 s |
| 2 common | `:iceberg-common:test` | 5 | 0 | 0 | 18 s |
| 2 orc | `:iceberg-orc:test` | 87 | 0 | 0 | 25 s |
| 2 parquet | `:iceberg-parquet:test` | 756 | 0 | 9 | 53 s |
| 2 data | `:iceberg-data:test` | 805 | 0 | 85 | 109 s |
| 2 core | `:iceberg-core:test` | 9131 | 0 | 550 | 937 s |
| 3 revapi | `:iceberg-{api,core,orc,parquet}:revapi` | PASS | 0 | - | 45 s |
| 3 style | spotlessCheck (api, common, core, data, orc, parquet, bundled-guava, open-api, spark 3.5, ext 3.5) + checkstyleMain/Test (all those except bundled-guava) | spotless PASS; checkstyle FAIL on 4 files | - | - | 110 s |
| 4 spark 3.5 | `:iceberg-spark:iceberg-spark-3.5_2.13:test`, in pieces (below) | 5513 | 8 | 990 | 4657 s (78 min) total, plus 811 s for the OOM-killed run |
| 4 spark-ext 3.5 | `:iceberg-spark:iceberg-spark-extensions-3.5_2.13:test`, in 6 pieces | 2719 | 0 | 180 | 2729 s (45 min) total |
| 5 spark 4.2 | 4 classes (TestCreateTable, TestAlterTable, TestPartitionedWrites, TestRestDropPurgeTable) | 156 | 0 | 8 | 116 s |
| 5 spark-ext 4.2 | TestMergeOnReadDelete, TestMergeOnReadWithLineage | 187 | 0 | 24 | 405 s |

Total run: 20,654. Failed: 8. Unit-suite counts match the baseline except for tests the patches add: api +8 (1287 to 1295), orc +21 (66 to 87), core +56 (9075 to 9131). common, parquet and data are identical. Spark 4.2 counts equal the baseline (156 and 187).

Spark 3.5 pieces (each a separate Gradle run, XML saved after each):
- Root classes of `org.apache.iceberg.spark`: 269 tests.
- `spark.functions`: 6.
- `spark.data`: 1289.
- `spark.sql`: 688.
- `spark.source`: 1596.
- `org.apache.iceberg.Test*` (serialization and distributed-scan tests in the spark module): 629.
- `spark.actions`, run class by class: 1036 tests, 8 failures.
- `TestRemoveOrphanFilesAction` matches no tests because it is abstract; its subclass `TestRemoveOrphanFilesAction3` ran (186).

Extensions pieces:
- `TestA*`, `TestB*`, `TestC*`: 988.
- `TestDe*`, `TestEx*`, `TestFa*`: 96.
- `TestMe*`, `TestMi*`: 699.
- `TestPu*`, `TestRe*`, `TestRo*`: 448.
- `TestS*`, `TestT*`, `TestU*`, `TestV*`, `TestW*`: 457.
- `TestExtendedParser` plus `spark.source.*`: 31.
- `TestDelete`, `TestMerge`, `TestUpdate` and `TestRowLevelOperationsWithLineage` are abstract bases and have no XML of their own; their subclasses ran.

## Failures
1. `org.apache.iceberg.spark.actions.TestComputePartitionStatsAction`: 8 failed of 20 (`partitionStatsComputeOnSnapshot` and `partitionStatsComputeOnLatestSnapshot` across 4 catalogs: testhive, testhadoop, testrest, spark_catalog).
   - Error: `AssertionError: Expecting actual: [(Record(foo, A), 0, 2L, 2, 1662L, ...)] to contain exactly in any order: [(Record(foo, A), 0, 2L, 1, 1662L, ...)]` at TestComputePartitionStatsAction.java:292. The partition has 2 records and the test expects 1 data file, but there are 2.
   - Class (b), caused by one ported patch: d69c1fd91, "Spark 3.5: Default partitioned write distribution mode to NONE" (reapplied as 9bcea01fd).
   - Evidence, class run alone: untouched base 212aa894e = 20/0 failed; reapply-independent tip = 20/8 failed; its commit 0a002d483 (just before the patch) = 20/0 failed; 9bcea01fd = 20/8 failed. Mechanism: with HASH distribution an INSERT of 2 rows in one partition wrote 1 file. With NONE it writes 1 file per task.
   - The patch updated TestSparkWriteConf and TestSparkDistributionAndOrderingUtil but not this test. It needs a test update by the patch owner. Not fixed here, as instructed.
2. OOM kill, class (d) environmental. The first run of the whole `spark.actions` package ended with "Gradle Test Executor 1 finished with non-zero exit value 137" after 13m30s. dmesg shows a memory-cgroup OOM kill of a java process (anon-rss ~4 GB) while other builds shared the 15 GB machine. The package was re-run class by class and finished. The XML of that aborted run was discarded.
3. Checkstyle (style, not test). Each failure is in a file one source branch introduced, so none is a merge interaction. Fixing needs the patch owner.
   - `api/.../RewriteJobOrder.java:64` LiteralStringReplaceAll. Patch: 04d2cd2af (rewrite).
   - `core/src/test/.../TestBinPackRewriteFilePlanner.java`: 14 errors (LocalVariableName on single-letter variables at lines 664-718, and AssertThatThrownByWithMessageCheck at line 803). Patches: 04d2cd2af and a6aef6788 (rewrite).
   - `core/.../io/OutputFileFactory.java:181` 'replicationFactor' hides a field (HiddenField). Patch: 25f1e5c9b (replication).
   - `spark/v3.5/spark/src/test/.../TestSparkOrcInitialDefaultScan.java:57` RegexpMultiline, non-static import of org.assertj.core.api.Assertions. Patch: orc-defaults branch (39df55d00 area).
   - All four are in the source branches themselves. I did not check whether they also fail checkstyle on those branches individually.

## Not run
- Checkstyle on bundled-guava was not run (spotless was). Spark 3.5 extensions checkstyle ran and had no violations.
- Flink, Kafka Connect, Spark 4.0/4.1, Scala 2.12, spark-runtime modules and `:iceberg-open-api:test` (only compiled): out of scope.
- Spark 4.2 beyond the six baseline classes: bounded by instruction.
- The `Test*` pattern splits for extensions rely on name prefixes. I confirmed all non-abstract `Test*.java` classes in both modules have XML, except the abstract ones listed above.

Throwaway worktrees: /home/user/iceberg-base, /home/user/iceberg-indep (detached).

## Follow-up fixes
All fixed at the source branch, merged forward with ordinary merge commits, pushed (no force). Logs: scratchpad/fix/.
1. independent 1bf3db827 "Not a LinkedIn patch": TestComputePartitionStatsAction tables set write.distribution-mode=hash (same approach as d69c1fd91 in TestIcebergSourceTablesBase / TestRewritePositionDeleteFilesAction). Before 20 tests / 8 failed; after 20/0. spark/v4.2 has an identical copy of the class (same expectation); not fixed, the NONE patch is not applied there yet.
2. rewrite 5109b9b68: RewriteJobOrder replaceAll->replace; TestBinPackRewriteFilePlanner single-letter locals renamed, message check added. checkstyle 15 errors -> 0; spotless pass; TestBinPackRewriteFilePlanner 43/0, TestRewriteJobOrder 6/0.
3. replication 2ae8c3399: OutputFileFactory.Builder.replicationFactor param renamed newReplicationFactor. HiddenField -> 0; TestOutputFileFactory 12/0, TestOutputFileFactoryReplication 6/0.
4. orc-defaults 434f0591a: static assertThat import in TestSparkOrcInitialDefaultScan. checkstyle error -> 0; test 6/0.
Tips: independent 1bf3db827, replication 59c743cee, rewrite 69b739e09, orc-defaults cb39aa2d6, spark-read f18763463, all f180ae589.
On reapply-all: compile + checkstyleMain/Test + spotlessCheck for spark 3.5, extensions 3.5, api, core, orc, parquet, data: BUILD SUCCESSFUL. Four test classes (plus TestOutputFileFactory*) all 0 failures.
