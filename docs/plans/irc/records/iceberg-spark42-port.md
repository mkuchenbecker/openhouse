# Spark 4.2 port of the reapplied LinkedIn patches (irc/spark42-patches)

**Bottom line (ran):** Every change the reapplied LinkedIn patches made under `spark/v3.5` now has a Spark 4.2 counterpart, with tests. The files touched under `spark/v4.2` match those under `spark/v3.5` one to one, plus `SparkRewriteWriteBuilder`, which exists only in 4.2.
- Full Spark 4.2 suite: 6199 run, 0 failed at the tip.
  - The first pass had 2 failures, both in `TestSparkMetadataColumns`. They were caused by the ports, are fixed with two test alignments, and the class passes on the clean base.
- Full Spark 4.2 extensions suite: 2958 run, 0 failed.
- Compile, checkstyle and spotless pass on both Spark 4.2 modules.

Branch `irc/spark42-patches`, tip **d701a1b9b** (pushed). Base: `irc/reapply-all` f180ae589. Draft PR: https://github.com/mkuchenbecker/iceberg/pull/10 (base `irc/reapply-all`).

Worktrees: `/home/user/iceberg-s42` (branch) and `/home/user/iceberg-s42-base` (detached `origin/openhouse-1.12.0`, 212aa894e, used for classification).

Logs, JUnit XML and per-run summaries: `/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/s42/runs/<label>/`. The helper scripts `rt.sh`, `port.sh`, `full-core.sh` and `full-ext.sh` are in the same `s42` directory.

Flags for every Gradle run: `--no-daemon --continue --max-workers=2 -Dorg.gradle.jvmargs=-Xmx4g -DsparkVersions=4.2 -DscalaVersion=2.13 -DflinkVersions= -DkafkaVersions=`, JDK 21. Counts come from JUnit XML and are given as run/failed/skipped.

## Step 1: spark/v3.5 files changed by the reapply, and their 4.2 counterparts
| 3.5 reapply (LinkedIn) | spark/v3.5 files | v4.2 counterpart |
|---|---|---|
| 9bcea01fd (d69c1fd91) | SparkWriteConf; tests TestSparkDistributionAndOrderingUtil, TestSparkWriteConf, TestRewritePositionDeleteFilesAction, TestIcebergSourceTablesBase | all exist (TestRewritePositionDeleteFilesAction identical) |
| 1e8e41334 (0b10d8734) | SparkPositionDeltaWrite, SparkTable, SparkWrite, SparkWriteBuilder; tests TestDeleteFrom, ext TestMergeOnReadDelete | all exist, diverged; plus 4.2-only SparkRewriteWriteBuilder |
| 287085cd7 (b7851bb63) | ext IcebergSparkSqlExtensionsParser.scala; ext TestViews | exist, diverged |
| 858da6420 (25f1e5c9b) | SparkWriteConf, SparkWriteOptions, SparkPositionDeletesRewrite, SparkPositionDeltaWrite; TestSparkWriteConf | exist |
| 2edfaf344 (809534da0) | SparkSQLProperties, SparkWriteConf; TestDeleteFileReplication (new), TestSparkWriteConf | exist; TestDeleteFileReplication new in 4.2 |
| e30adc591 (dbcca9b96) | TestDeleteFileReplication | (new) |
| 60465ee59 (LinkedIn defaults) | jmh IcebergSourceDeleteBenchmark, SparkWriteConf, SparkWriteOptions; TestDeleteFileReplication, TestSparkExecutorCache, TestSparkWriteConf | exist (benchmark identical) |
| 617776733 (04d2cd2af) | actions/RewriteDataFilesSparkAction; TestRewriteDataFilesAction | exist |
| 294029a14 (1b8361f00) | OrcSchemaWithTypeVisitorSpark (new), data/SparkOrcReader, SparkBatch, SparkFormatModels; TestSparkOrcReaderForFieldsWithDefaultValue (new), TestSparkBatchScanInitialDefaults (new) | SparkOrcReader identical; others exist; three new files |
| c88162714 (4df974156) | TestSparkOrcReaderForFieldsWithDefaultValue | (new) |
| 8be4d0a18 (a6aef6788) | SparkStagedScan; TestSparkStagedScan | exist, diverged |
| 61dbdbeff (39df55d00) + 434f0591a | TestSparkOrcReaderForFieldsWithDefaultValue, TestSparkOrcInitialDefaultScan (new) | (new) |
| b80d4ef47 (8a501e296) | SparkScanBuilder; TestReportColumnStats (new) | SparkScanBuilder heavily diverged (BaseSparkScanBuilder) |
| 5f33cc100 (efb092202) | SparkReadConf, SparkSQLProperties; TestSparkReadConf | exist (TestSparkReadConf identical) |
| 1bf3db827 (not LinkedIn) | TestComputePartitionStatsAction | identical |

0a002d483, 7e23bad11, 5cce4931f, 5109b9b68 and 2ae8c3399 do not touch spark/v3.5.

## Step 2: per-patch port, red then green (ran)
"before" means the tests ran with the main change absent. "after" means the tests ran with the main change applied. Counts are for the filtered test run named.

| 4.2 commit | mirrors | ported / adapted | before | after |
|---|---|---|---|---|
| 16a2020b0 | d69c1fd91 / 9bcea01fd | SparkWriteConf default NONE (mechanical). TestSparkWriteConf adapted to the 4.2 `new SparkWriteConf(spark, table)` constructor | 220/2/0 (Distribution must match: Clustered vs Unspecified; expected NONE, was HASH) | 220/0/0 |
| eddf4cbb9 | 1bf3db827 (not LinkedIn) | TestComputePartitionStatsAction tables use hash | 20/8/0 (data_file_count 2 vs 1) | 20/0/0 |
| 7b71f6e14 | 0b10d8734 / 1e8e41334 | **Adapted**: 4.2 SparkWrite constructor has a branch argument; SparkTable uses `spark()`; 4.2-only SparkRewriteWriteBuilder also passes appName (rewrites stage files only, so nothing observable changes there; needed for the constructor); TestDeleteFrom uses `tableProperties()` (4.2 class is format-version parameterized) | TestDeleteFrom 96/12/4, ext method 3/3/0 (map lacks spark.app.name) | 96/0/4, 3/0/0 |
| edc9c5448 | b7851bb63 / 287085cd7 | **Adapted**: the conf check is in `parsePlanWithDelegate`, which covers both `parsePlan` and 4.2's `parsePlanWithParameters` | 3/3/0 (still CreateIcebergView when disabled) | 3/0/0 |
| ac1f842ab | 25f1e5c9b / 858da6420 | main applied with context offsets; tests wrap options in CaseInsensitiveStringMap | compile error (no deleteFileReplication / DELETE_FILE_REPLICATION) | TestSparkWriteConf 36/0/0 |
| b34ed1467 | 809534da0 / 2edfaf344 | session conf and table property precedence; TestDeleteFileReplication copied | with only the constant added: 42/5/0 (expected 7/5/6/4/6, was 1/1/0/0/0) | 42/0/0 |
| 186f1b60e | dbcca9b96 / e30adc591 | Spark side is test-only (ORC cases); the ORC main change is already on the branch | ORC change temporarily reverted: 6/2/0 (ORC delete files replication 1, expected 7/5) | 6/0/0 |
| 699e7f932 | 60465ee59 | default (short) 3, range check removed, jmh benchmark, TestSparkExecutorCache FileIO override | 67/3/0 (expected 3, was 0/1/1) | 67/0/0 |
| e06d09ef8 | 04d2cd2af / 617776733 | MAX_TOTAL_FILES_SIZE_BYTES in the valid options (mechanical) | 21/12/0 ("Cannot use options [max-total-files-size-bytes]") | 21/0/0 |
| c20993a7b | 1b8361f00 / 294029a14 | **Adapted**: SparkBatch uses `projection` (3.5: expectedSchema). The reader test writes embedded-id ORC files, because the branch's ORC module already has 4df974156; that setup is folded in from c88162714, and the bare-schema version stayed red even with the Spark change (run p09-after 5/2/0) | batch test: compile error; reader test alone: 2/2/0 ("Missing ORC reader for field col2 (2)") | 5/0/0 |
| 8c4bb9094 | 4df974156 / c88162714 | test-only (partial-id and name-mapped cases); the file now equals the 3.5 copy at c88162714 | not shown red: reverting the ORC change does not compile (later 61dbdbeff ORC tests use FieldIdSource) | 4/0/0 |
| de0dfb6ad | a6aef6788 / 8be4d0a18 | **Adapted**: 4.2 hashCode already used splitLookback, so only planTaskGroupsWithDataSize applies; tests use 4.2 staged loading (SparkTableCache + `load(setId)`, builder takes the set id) | 16/4/0 (expected 1 partition, was 2) | 16/0/0 |
| b0c58fb49 | 39df55d00 / 61dbdbeff (+434f0591a style) | test-only; TestSparkOrcInitialDefaultScan copied unchanged | api/orc change temporarily reverted: 12/3/0 ("ORC schema does not contain Iceberg IDs") | 12/0/0 |
| ecef7cc9e | 8a501e296 / b80d4ef47 | **Adapted**: 4.2 already narrows stats to variant columns through `Scan.includeColumnStats(Collection)`, which replaces any earlier selection, so the requested columns are merged with the variant columns in `includeStats`. A flag on buildIcebergBatchScan limits this to the plain batch scan (same scope as 3.5) | with only the API stub: 6/3/0 (no bounds for the requested column) | 6/0/0 |
| 494f770e8 | efb092202 / 5f33cc100 | mechanical | with only the constant: 12/3/0 | 12/0/0 |
| 66eb1b9ed | style (not LinkedIn) | SparkWrite.commitOperation: complexity 13>12 (4.2 has more branches), app-id/name moved to a helper; spotless on SparkScanBuilder | checkstyle 1 error, spotless 1 file | pass |
| 0c281491e | test alignment (not LinkedIn) | TestSparkMetadataColumns map/list tests request hash distribution | see Failures | |
| d701a1b9b | test alignment (not LinkedIn) | TestTables.LocalFileIO overrides newOutputFile(path, properties) | see Failures | |

## Step 3: validation
### 3a. Static checks (ran)
| piece | result | wall |
|---|---|---|
| compileJava, compileTestJava, compileJmhJava, checkstyleMain/Test/Jmh, spotlessCheck on spark-4.2 and spark-extensions-4.2; runtime-4.2 compileJava (at 66eb1b9ed) | BUILD SUCCESSFUL | 74 s |
| checkstyleTest + spotlessCheck on spark-4.2 at the tip d701a1b9b | BUILD SUCCESSFUL | 24 s |
| revapi | not configured for Spark modules (REVAPI_PROJECTS = api, core, parquet, orc, common, data); not applicable | - |

### 3b. Touched classes + baseline classes (ran, at 66eb1b9ed)
| piece | run | fail | skip | wall |
|---|---|---|---|---|
| core: 16 touched classes + TestCreateTable, TestAlterTable, TestPartitionedWrites(+AsSelect, +ToBranch via wildcard), TestRestDropPurgeTable | 819 | 0 | 20 | 787 s |
| of which the 4 baseline classes (4+60+64+28) | **156** | 0 | 8 | |
| ext: TestMergeOnReadDelete, TestMergeOnReadWithLineage, TestViews | 484 | 0 | 32 | 437 s |
| of which the 2 baseline classes (150+40) | **190** = 187 + 3 new | 0 | 24 | |

### 3c. Full suites (ran)
The core pieces ran at 66eb1b9ed. The two later commits change only TestSparkMetadataColumns and TestTables, both in `spark.source`, so that package was re-run at the tip d701a1b9b.

| piece | run | fail | skip | wall |
|---|---|---|---|---|
| `org.apache.iceberg.Test*` | 629 | 0 | 116 | 88 s |
| `org.apache.iceberg.spark.Test*` | 340 | 0 | 8 | 152 s |
| spark.functions | 6 | 0 | 0 | 23 s |
| spark.variant | 30 | 0 | 0 | 59 s |
| spark.data (incl. vectorized) | 1483 | 0 | 566 | 364 s |
| spark.sql | 829 | 0 | 28 | 777 s |
| spark.source, first pass (66eb1b9ed) | 1831 | **2** | 204 | 1271 s |
| spark.source at the tip (d701a1b9b) | 1831 | 0 | 204 | 1310 s |
| spark.actions, 16 runs class by class | 1051 | 0 | 151 | 1859 s |
| **core total at the tip** | **6199** | **0** | **1073** | |
| ext TestA/B/C* | 1020 | 0 | 46 | 1033 s |
| ext TestDe/Ex/Fa/In/Pa* | 155 | 0 | 0 | 183 s |
| ext TestMe/Mi* | 747 | 0 | 119 | 809 s |
| ext TestPu/Re/Ro* | 464 | 0 | 17 | 432 s |
| ext TestS/T/U/V/W* | 537 | 0 | 16 | 330 s |
| ext `spark.Test*` + `spark.source.*` | 35 | 0 | 0 | 77 s |
| **extensions total** | **2958** | **0** | **198** | 2864 s |

The actions class-by-class counts (run/fail/skip) are:

| class | run | fail | skip |
|---|---|---|---|
| ComputePartitionStats | 20 | 0 | 0 |
| ComputeTableStats | 76 | 0 | 0 |
| CreateActions | 112 | 0 | 60 |
| DeleteReachableFiles | 36 | 0 | 0 |
| ExpireSnapshots | 99 | 0 | 0 |
| MigrateTable | 4 | 0 | 3 |
| RemoveDanglingDelete | 18 | 0 | 5 |
| RemoveOrphanFilesAction3 | 186 | 0 | 23 |
| RewriteDataFiles | 213 | 0 | 8 |
| RewriteManifests | 120 | 0 | 36 |
| RewritePositionDeleteFiles | 19 | 0 | 0 |
| RewriteTablePaths | 126 | 0 | 14 |
| SnapshotTable | 12 | 0 | 2 |
| SparkFileRewriteRunners | 8 | 0 | 0 |
| SparkShufflingDataRewritePlanner | 2 | 0 | 0 |

`TestRemoveOrphanFilesAction` is abstract: its run found no tests (rc=1, expected), and its subclass `TestRemoveOrphanFilesAction3` ran.

Coverage was checked: every non-abstract `Test*.java` in both 4.2 test trees has an XML, except the helper classes TestTables, TestSparkCatalog, TestIcebergSource and TestHelpers, which hold no tests. The abstract extension bases (TestDelete, TestMerge, TestUpdate, TestRowLevelOperationsWithLineage, TestDelete/Merge/UpdateMetrics) ran through their subclasses.

There were no OOM kills and no HTTP 429 responses.

## Failures and classification
1. `spark.source.TestSparkMetadataColumns`, first pass: 2 of 200 failed (PARQUET, vectorized=true, v2 and v3), in `testPartitionMetadataColumnWithMapColumn`. The error was "Failed to create the file's directory at .../data/id_bucket=0" (`Files.LocalOutputFile.create`).
   - Clean base (`/home/user/iceberg-s42-base`, 212aa894e): 200/0/64. The failure does not occur there.
   - Class re-run on the branch: 200/1/64, this time a different parameter combination. The failure is non-deterministic.
   - **Classification (b), caused by a port.** The cause is d69c1fd91 (NONE distribution). The test is 4.x-only and inserts two rows in one INSERT into a bucket(id,1) table. The comment in the test says they should share one file so the DELETE is merge-on-read. Under NONE the two rows go to two tasks, which race on mkdirs of the same partition directory, or land in separate files, which makes the DELETE metadata-only.
   - Fix 0c281491e: the map and list tables set write.distribution-mode=hash, following the d69c1fd91 pattern.
2. After fix 1, the same class failed 12 of 200 (`testPartitionMetadataColumnWithListColumn` / `...MapColumn`). The error was "Creating output file at ...-deletes.parquet with properties: {file-replication-factor=3} is not supported".
   - **Classification (b), caused by a port**: the replication defaults (699e7f932, mirroring 60465ee59). Delete files now always go through `FileIO.newOutputFile(path, properties)`, whose default throws. Before fix 1 this path was masked because the DELETE was metadata-only.
   - Fix d701a1b9b: TestTables.LocalFileIO overrides the method. This is the same treatment 60465ee59 and LinkedIn give TestSparkExecutorCache's FileIO.
   - After both fixes: 200/0/64 in three forced re-runs (`--rerun`), and the whole source package at the tip gave 1831/0/204.
   - The same latent gap exists in `spark/v3.5` TestTables.LocalFileIO. No 3.5 test reaches it, and 3.5 was not touched here.

There are no (a) or (c) failures.

## What was not run, or was inferred
- The red step was not shown for 8c4bb9094 (4df974156 Spark tests). Its ORC dependency is shared and already present, and reverting it does not compile. That these tests depend on the ORC change is inferred, not shown.
- For 186f1b60e and b0c58fb49 the red step came from temporarily reverting the already-present ORC/API change. That is not the Spark main change, which those patches do not have.
- The appName pass-through in SparkRewriteWriteBuilder has no test (as in 3.5). Rewrite writes only stage files, so no snapshot summary is observable from it; this is inferred from the code.
- Not run: revapi (not configured for Spark), Spark 3.5/4.0/4.1, Flink, Kafka, Scala 2.12, and spark-runtime-4.2 tests (runtime only compiled). The fork's CI on the PR was not watched or acted on.
- Several `rt.sh` re-runs with identical inputs were served from the Gradle cache (13-14 s). Those were discarded, and the three counted re-runs used `--rerun`.
