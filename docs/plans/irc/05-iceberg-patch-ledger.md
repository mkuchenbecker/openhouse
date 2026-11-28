# Iceberg patch ledger: LinkedIn `openhouse-1.5.2` against Apache 1.12.0

Bottom line: of LinkedIn's 29 commits on top of Apache 1.5.2, 13 must be reapplied on the 1.12 line, 11
are already in Apache 1.12.0, and 5 are LinkedIn release plumbing. One of the 11 is covered under a
different Spark config key and needs a decision.

Whose voice: a delegated agent produced this by reading the 1.12.0 tree. Claude re-checked 7 of the 29
rows directly (write distribution default, transaction retries, app name in snapshot summary, split-size
key, ORC default reads, app-name context, commit count) and all 7 matched. The statuses are judgement
until each patch is actually reapplied with its test.

Decision needed from Mike: LinkedIn's Spark config key `spark.sql.iceberg.split-size` does not exist in
1.12. Apache's equivalent is `spark.sql.iceberg.read.split-size`. Jobs that set the old key would
silently lose the setting. Carrying the old key as an alias avoids a Spark-level behavior change.

| sha | subject (trimmed) | area | status | evidence in 1.12.0 | tests |
|---|---|---|---|---|---|
| 04d2cd2af | Budgeted data files rewrite, order by file sequence number | core+api, spark-3.5 | REAPPLY | `RewriteJobOrder` has only BYTES/FILES/NONE, no `FILES_MIN_SEQUENCE_NUMBER_*`; `fromName` still uses `replaceFirst`. No `MAX_TOTAL_FILES_SIZE_BYTES` or `minFileSequenceNumber` anywhere (`git grep`). Selection loop would sit in core `actions/BinPackRewriteFilePlanner.java` (has `rewriteJobOrder`, `.sorted(RewriteFileGroup.comparator(...))`); Spark `RewriteDataFilesSparkAction` has no `pickedTasks` code | yes |
| c14c71577 | Enable publishing builds from 1.5.2 branch | build | BUILD ONLY | shipkit/version plumbing, group `com.linkedin.iceberg`, CI. 1.12 has no `shipkit.gradle`, `version.txt`, `version.properties`. Side effects: removes revapi plugin wiring, comments out some `@Test`s in `HadoopFileIOTest`/`TestHadoopCommits`, deletes `TestS3OutputStream` | no |
| d4e456297 | Unsupported Spark versions exception in java-ci | build | BUILD ONLY | one line in `.github/workflows/java-ci.yml` | no |
| d114382b0 | Fix release publishing process | build | BUILD ONLY | 2 lines in `java-ci.yml` | no |
| 231beef6f | Remove rc version suffix from build version | build | BUILD ONLY | `build.gradle` version logic, `version.txt` | no |
| 0b10d8734 | Add application name to snapshot metadata in Spark writes | spark-3.5 | REAPPLY | `git grep spark.app.name` in v3.5 `source/`: absent. Only `"spark.app.id"` is set (`SparkWrite.java:215`, `SparkPositionDeltaWrite.java:323`, `SparkTable.java:419`); no `applicationName` field | no |
| 1753a6d42 | fix version as part of CI | build | BUILD ONLY | `java-ci.yml`, `build.gradle`, new `ci-create-tag.sh` (absent in 1.12), deletes `version.txt` | no |
| 5b7fb483f | Openhouse 1.5.2: read retry props from `current` in BaseTransaction | core+api | REAPPLY | `core/.../BaseTransaction.java` 1.12: `commitReplaceTransaction` still uses `PropertyUtil.propertyAsInt(props, COMMIT_NUM_RETRIES,...)` (l.302), `commitSimpleTransaction` still uses `base.propertyAsInt(COMMIT_NUM_RETRIES,...)` (l.360) | no |
| b7851bb63 | Do not rewrite Spark views (3.5) | spark-3.5 | REAPPLY | `IcebergSparkSqlExtensionsParser.scala` 1.12 l.122 still calls `RewriteViewCommands(...)` unconditionally; `git grep rewriteViewCommands` finds nothing | no |
| 25f1e5c9b | Configure replication factor of delete files | core+api, spark-3.5 | REAPPLY | `git grep -i replication` in api/core/spark v3.5: nothing (only unrelated `short replication` params in `orc/.../FileIOFSUtil.java`). No `FileIO.newOutputFile(path, props)`, no `OutputFileFactory.FILE_REPLICATION_FACTOR`, `HadoopOutputFile` has ctor `(fs, path, conf)` only | yes |
| efb092202 | SparkSQLProperty for split-size | spark-3.5 | ALREADY IN 1.12 (key differs) | `SparkSQLProperties.READ_SPLIT_SIZE = "spark.sql.iceberg.read.split-size"` wired by `.sessionConf(...)` in `SparkReadConf.java:198,206` (v3.5) and v4.2. LinkedIn's key was `spark.sql.iceberg.split-size`; that exact string is absent | no |
| 809534da0 | Delete file replication as table and spark-sql property | core+api, spark-3.5 | REAPPLY | No `DELETE_FILE_REPLICATION` in `TableProperties`, `SparkSQLProperties` or `SparkWriteConf` (v3.5, v4.2) | no |
| a6aef6788 | Fix bin-pack compaction undersized output files | core+api, spark-3.5 | REAPPLY | No `planTaskGroupsWithDataSize` or weight-function overload in `TableScanUtil.java`. `SparkStagedScan.java:62` uses plain `planTaskGroups(...)`; `hashCode` l.89 still passes `splitSize, splitSize` (the bug the patch fixes). Its `RewriteDataFilesSparkAction` hunk targets code absent in 1.12 (see 04d2cd2af) | yes |
| e1103d86c | app-name to EnvironmentContext | core+api, spark-3.5 | ALREADY IN 1.12 | `CatalogProperties.APP_NAME = "app-name"` (l.158); `SparkCatalog.java:830` (v3.5) and `:869` (v4.2) `EnvironmentContext.put(CatalogProperties.APP_NAME, ...appName())` | yes |
| c9c41c46f | Backport stream-results for remove orphan files | spark-3.5 | ALREADY IN 1.12 | `DeleteOrphanFilesSparkAction.java:105` `STREAM_RESULTS`, `:110` `MAX_ORPHAN_FILE_SAMPLE_SIZE`; `RemoveOrphanFilesProcedure.java:76` `stream_results` param | yes |
| 3d6d0d7c1 | CachingCatalog load with metadata table name (#11738) | core+api | ALREADY IN 1.12 | `CachingCatalog.loadTable` has `table instanceof BaseMetadataTable` branch and "Cache underlying table" | yes |
| c3a07e2ef | Backport #10680 (PartitionSet toString) | core+api | ALREADY IN 1.12 | `PartitionSet.java:203` `append(s.get(i, Object.class))` without `.toString()` | yes |
| 198cc0189 | Backport ParallelIterable memory fixes | core+api | ALREADY IN 1.12 | `ParallelIterable.java`: `DEFAULT_MAX_QUEUE_SIZE = 30_000`, `yieldedTasks` deque, `maxQueueSize` check in `checkTasks` | yes |
| 0ffc69d3f | Bump Avro to 1.11.4 (CVE) | build | ALREADY IN 1.12 | `gradle/libs.versions.toml` 1.12: `avro = "1.12.2"` (newer than 1.11.4) | no |
| d69c1fd91 | Spark 3.5: default partitioned write distribution to NONE | spark-3.5 | REAPPLY | `SparkWriteConf.defaultWriteDistributionMode()` returns `HASH` for partitioned tables (v3.5 l.343, v4.2 l.339) | yes |
| d1603c807 | NestedField column-default APIs, `Expressions.lit()` | core+api | ALREADY IN 1.12 | `Types.NestedField.Builder` with `withInitialDefault`/`withWriteDefault`, `from`, `ofType`; `initialDefaultLiteral()`; `Expressions.lit` (l.328); `SchemaParser` `INITIAL_DEFAULT`/`defaultFromJson` | yes |
| dbcca9b96 | ORC: propagate delete file replication to Hadoop streams | orc, core+api | REAPPLY | `ORC.java:814` and `OrcFileAppender.java:84` use `HadoopOutputFile` shortcut with no replication check; `HadoopOutputFile` has no `replication()` | yes |
| 8cbd4f5a8 | ORC: bind struct fields by field id (generic + Spark 3.5) | orc, spark-3.5 | ALREADY IN 1.12 | `OrcValueReaders.StructReader`: `orcFieldIndex`, `readersByFieldId`, `buildFieldIdToOrcIndex`, old ctor `@Deprecated`; `GenericOrcReader.java:79` and `SparkOrcReader.java:80` pass `record` to `struct(...)` | yes |
| 1b8361f00 | ORC/Spark: default-value reads via idToConstant | orc, spark-3.5 | REAPPLY | `ORCSchemaUtil.java:407` throws `UnsupportedOperationException("ORC cannot read default value...")`. No `supportsInitialDefaults`, `OrcSchemaWithTypeVisitorSpark`, `hasNoInitialDefaults` anywhere | yes |
| 4df974156 | ORC: omit initial-defaults only for complete embedded ids | orc | REAPPLY | No `FieldIdSource` / `hasAllIds` / `isOmittableDefault` in `ORCSchemaUtil` | yes |
| 39df55d00 | ORC: no file filters for omitted initial-default fields | core+api, orc | REAPPLY | `PruneColumns.java:70,74` still builds `NestedField.optional/required(...)` without defaults (`AssignFreshIds:91`, `ReassignIds:82` already use `NestedField.from`); `ExpressionToSearchArgument` has no `columnNamesForPushdown` or missing-column guard | yes |
| 8a501e296 | Spark 3.5: retain tracked-column stats for CVL | spark-3.5 | REAPPLY | `SparkScanBuilder` v3.5 has only `scan.includeColumnStats()` (l.483, 517); no `includeColumnStats(Collection<String>)` method or `columnStatsColumns` (v3.5, v4.2). Core API `BatchScan.includeColumnStats(Collection)` exists (`BatchScanAdapter.java:94`) | yes |
| 6c9a0ec78 | Backport rewrite delete file fix (#10020) | core+api, spark-3.5 | ALREADY IN 1.12 | `Schema` ctor with `TypeUtil.GetID`, `idsToReassigned()/idsToOriginal()`, `PartitionSpec.rawPartitionType()`, `AssignIds.java`, `Types.withFieldId`, `ManifestReader.java:166` uses `rawPartitionType()`, `PositionDeletesTable.calculateSchema` reassigns ids, `BaseMetadataTable.java:89` `idsToReassigned`; Spark side moved to core `BinPackRewritePositionDeletePlanner.java:165` `partitionType(deletesTable)` | yes |
| af77f0550 | Backport MoR read fix (#10020) | spark-3.5 | ALREADY IN 1.12 | `SparkScanBuilder.calculateMetadataSchema` with `idsToReassign` (v3.5 l.361-389); v4.2 has `calculateMetadataSchema` in `BaseSparkScanBuilder.java:219` | yes |

## Spark detail for REAPPLY commits
"Orig" = modules the LinkedIn commit touched (main code). Existence is in 1.12.0 at the same relative path.

| sha | Orig modules | spark/v3.5 file | spark/v4.2 file |
|---|---|---|---|
| 04d2cd2af | v3.5 | `RewriteDataFilesSparkAction` exists but holds no job-order or size-budget logic; that is in core `BinPackRewriteFilePlanner` | same |
| 0b10d8734 | v3.3, v3.4, v3.5 | `SparkWrite`, `SparkWriteBuilder`, `SparkPositionDeltaWrite`, `SparkTable` all exist | all exist |
| b7851bb63 | v3.5 (spark-extensions) | `IcebergSparkSqlExtensionsParser.scala` exists | exists |
| 25f1e5c9b | v3.4, v3.5 | `SparkWriteConf`, `SparkWriteOptions`, `SparkPositionDeletesRewrite`, `SparkPositionDeltaWrite`, `SparkWrite`, jmh `IcebergSourceDeleteBenchmark` exist | all exist |
| 809534da0 | v3.3, v3.4, v3.5 | `SparkSQLProperties`, `SparkWriteConf`, `SparkWriteOptions` exist | exist |
| a6aef6788 | v3.5 | `SparkStagedScan` exists; `RewriteDataFilesSparkAction` hunk has no target | `SparkStagedScan` exists (planTaskGroups at l.68) |
| d69c1fd91 | v3.5 | `SparkWriteConf` exists | exists |
| 1b8361f00 | v3.5 | `SparkOrcReader`, `BaseReader`, `BaseRowReader`, `SparkBatch` exist; `OrcSchemaWithTypeVisitorSpark` is a new file (absent). 1.12 `BaseReader` has no `convertConstant` | same files exist |
| 8a501e296 | v3.5 | `SparkScanBuilder` exists | exists (shares `BaseSparkScanBuilder`) |
4df974156, 39df55d00 and dbcca9b96 have no non-test Spark main files; their Spark parts are tests only.

## Dependencies between the 29 commits
- 809534da0 -> 25f1e5c9b: uses the replication plumbing and edits the same `SparkWriteConf`/`SparkWriteOptions`/`SparkSQLProperties`.
- dbcca9b96 -> 25f1e5c9b: calls `HadoopOutputFile.replication()`, which 25f1e5c9b adds.
- a6aef6788 -> 04d2cd2af: changes the `pickedTasksSizeBytes` loop that 04d2cd2af introduced, in the same Spark action.
- 1b8361f00 -> d1603c807 (`initialDefault()`) and 8cbd4f5a8 (`SparkOrcReader` ReadBuilder and `StructReader` id binding it extends).
- 4df974156 -> 1b8361f00: rewrites `ORCSchemaUtil`/`OrcIterable` code and the same test class.
- 39df55d00 -> 1b8361f00 and d1603c807: relies on omitted-default projection; uses `NestedField.from(...)`. Also edits the test class from 1b8361f00 and 4df974156.
- af77f0550 -> 8a501e296: textual only; its diff base is the `SparkScanBuilder` produced by 8a501e296. Semantically independent.
- 6c9a0ec78 -> d1603c807: `Types.withFieldId` and `AssignIds` carry `initialDefault`/`writeDefault`.
- 25f1e5c9b, d1603c807, 6c9a0ec78 each add `.palantir/revapi.yml` entries (`1.5.2.2`, `1.5.2.14`), while c14c71577 removed the revapi plugin wiring.
- Build chain: c14c71577 -> d4e456297, d114382b0, 231beef6f, 1753a6d42 (all edit its workflows and `build.gradle`; 1753a6d42 also undoes part of 231beef6f/version.txt).
- Independent: 0b10d8734, 5b7fb483f, b7851bb63, d69c1fd91, e1103d86c, c9c41c46f, 3d6d0d7c1, c3a07e2ef, 198cc0189, 0ffc69d3f.

## Could not determine
- None of the 29 is UNCLEAR on the code.
- efb092202: upstream wires a session property under a different key (`spark.sql.iceberg.read.split-size`). Whether LinkedIn jobs set the old key `spark.sql.iceberg.split-size` is not in the repo; that decides whether it counts as covered.
- 04d2cd2af / a6aef6788: I located where the logic now lives (core `BinPackRewriteFilePlanner`) but did not trace its grouping step to confirm the old `RewriteDataFilesSparkAction` hunk has no behavioral twin there.
- Spark v4.0 and v4.1 were not checked; only v3.5 and v4.2 were read.
- Test files in 1.12 were not inspected, so "has tests" refers to the LinkedIn commit only.
