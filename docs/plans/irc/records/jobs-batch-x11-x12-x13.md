# Jobs batch X11 / X12 / X13: record

Marks: [ran] run in worktree /home/user/oh-jobs3 (branch irc/jobs-batch-x11-x12-x13, base f07a92e1), JDK 17;
[read] read in code or bytecode, not run; [inferred] reasoning, not run.

## Bottom line

- X11 was a real defect on the 1.5.2 line, and the fixture is not the cause: the test session has the same
  extensions, in the same order, and the same openhouse catalog as jobs.yaml [ran]. Fixed in the app, 1.5.2 only
  in effect; 1.12 and Spark 4.2 make the same SQL call as before [ran].
- X12 did not hold: the collectors already count each Puffin file once with its size once. Pinned, no code change [ran].
- X13 is harmless and cleaned up by the next retention run. Compaction and expiry keep it. Pinned, no job change [ran].

## State for a restart

- Commits: 8da35a1b X11 pin; d11017bb X11 fix; b8c26e4b X12 pin; a76d7827 X13 test. origin/irc-integration still
  f07a92e1 at the final fetch (nothing to merge). Pushed head a76d7827523745a6857c27482a5725d063f3d4ce; draft
  pull request 145. CI: see below.
- Full suites on a76d7827 [ran]: 1.12 481 = 476 passed / 0 failed / 5 skipped; 4.2 482 = 477 / 0 / 5;
  1.5.2 481 = 389 / 1 / 91. The failure: FormatV3MaintenanceTest.compactionKeepsLiveRowsDeletesAndRowIds[v2, orc],
  "every data file that carried deletes was rewritten": one of four partial-progress group commits got 409 from
  the service ("modified by another process") after Iceberg's retries; 3 of 4 groups committed. Compaction code
  and that test are untouched; class rerun alone on 1.5.2: 22 passed / 21 skipped. Timing race, pre-existing.
- check-test-groups.py (default line): partition holds.

## X11: sort stats on the 1.5.2 line

- Where [ran, stack from the 1.5.2 run]: SortStatsCollectionSparkApp.runInner -> `call openhouse.system.rewrite_data_files(...,
  strategy => 'sort', sort_order => ...)` -> RewriteDataFilesProcedure.checkAndApplyStrategy (1.5.2 line 142) ->
  ExtendedParser.parseSortOrder(spark, order) (line 64) -> IllegalStateException. The temporary copy was already made.
- Why [read]: 1.5.2's parseSortOrder checks `sessionState().sqlParser() instanceof ExtendedParser` (javap of
  iceberg-spark-runtime-3.5_2.12-1.5.2.24). The 1.12 fork's parseSortOrder walks the parsers' delegate fields
  (findParser / getNextDelegateParser). Spark chains injected parsers in list order, so with Iceberg's extensions first
  and OpenHouse's second, the outermost parser is OpenhouseSparkSqlExtensionsParser (not an ExtendedParser),
  delegating to Iceberg's.
- Fixture vs production [ran, SortStatsParserTest]: the test reads jobs.yaml (infra/recipes/docker-compose/
  oh-hadoop-spark/jobs.yaml, the only job configuration in the repository) and asserts the session's
  spark.sql.extensions, spark.sql.catalog.openhouse and catalog-impl equal it (catalog-impl on the 3.5 line; 4.2
  uses RESTCatalog). The scheduler adds only `--tableName` (SortStatsCollectionTask.getArgs) and the auth token
  (JobsRegistry) [read]. So a 1.5.2 deployment with jobs.yaml's order fails the same way [inferred from the match].
- Separate fact, pinned [ran]: jobs.yaml sets no spark_catalog, and the app copies its sample from
  `spark_catalog.<fqtn>`. On a session exactly as jobs.yaml gives it, the job stops at the copy with
  TABLE_OR_VIEW_NOT_FOUND, on every line, before the rewrite. The X6/X11 job tests point spark_catalog at OpenHouse
  (as a deployment that runs this job must). Deployment configuration: reported, not changed.
- Fix (d11017bb): `rewriteSorted`. If Iceberg's own parseSortOrder works on the session (1.12, both Spark
  lines), the old SQL call, same text. Otherwise, if an Iceberg ExtendedParser sits below the session's parser
  (delegate walk, as 1.12 does), parse SORT_ORDER with it, build the SortOrder as the procedure does
  (builder.sortBy(term, direction, nullOrder)), and run SparkActions.rewriteDataFiles(table).options(same).sort(order)
  .execute() on the table loaded through the openhouse catalog: what 1.5.2's procedure body does (no branch, no
  filter). With no Iceberg parser at all: the old call, failing as before.
- Behaviour changes: on 1.5.2 the job now completes (property sort-compression-rate set; 0.73% in the test) and logs one
  INFO line naming the parsers. Not identical to the procedure: no Spark cache refresh of the temp table (it is
  dropped right after) and no procedure output rows (unused). 1.12/4.2: one extra in-memory parse per run.
- Options not taken: reorder the extensions in jobs.yaml (deployment change; OpenHouse's parser would then be
  wrapped by Iceberg's, and its own commands parse only if Iceberg's parser delegates them: not tested).
- Proof [ran]: FormatV3MaintenanceTest sort stats v2 now asserts success on 1.5.2 (v3 skipped there) and v2/v3 on
  1.12 and 4.2; SortStatsParserTest 6 cases on all three: which path each line takes (fallback log line present on
  1.5.2 only), the parsed order equals asc(memberId, nulls last), asc(time, nulls last), one sorted file, same rows.

## X12: delete-file stats on v3

- TableStatsCollectorUtil.getFileMetadataTable [read]: select content, file_path, file_size_in_bytes; dropDuplicates;
  groupBy content; count, sum. A DV's DeleteFile has the Puffin path and the whole Puffin size (BaseDVFileWriter
  .createDV withFileSizeInBytes(size)) [read], so all DVs in one Puffin file are one row after dropDuplicates.
- Pinned [ran, 1.12]: v3 current: 8 DVs in 4 Puffin files, stats 4 files / 5381 bytes (per-vector would be 8 /
  13327); all snapshots 4 / 5381. v2: 8 / 7386 current, 13 / 11957 all; equality 0. Expected from delete manifests + fs.
- No code change, no schema field. No DV-count field exists in IcebergTableStats [read].

## X13: dangling deletion vector after retention

- Iceberg 1.12 fork [read]: MergingSnapshotProducer removes DVs only for data files removed in the same commit
  (removeDanglingDeletesFor, ManifestFilterManager.isDanglingDV); RowDelta (the raced DELETE) adds a DV for a file
  already gone. RewriteDataFilesSparkAction removes dangling deletes only with remove-dangling-deletes=true (default
  false; Operations.rewriteDataFiles does not set it). deleteByRowFilter also filters delete manifests.
- Test [ran, 1.12]: race (as X10) -> compaction (deleteFileThreshold 1) keeps it -> expiry (keep 1) keeps it ->
  rows, row ids, every referenced file, stats fine -> second retention run commits a delete with removed-dvs=1 ->
  no dangling DV, rows and row ids unchanged. v2: same rows; the second run removes the raced position delete file.
- No job change. Cost of the leftover until then: one DV entry and its Puffin bytes (shared Puffin may be live anyway).
- Gap: if the retention policy is lengthened or removed before the next run, the vector stays (harmless) [inferred].

## Decisions

- X11 fallback gated on Iceberg's own parse failing and an Iceberg parser below: leaves 1.12/4.2 on the old call.
- X11 action rather than an ALTER TABLE WRITE ORDERED BY on the temp table: no extra commit to the service.
- X12/X13 closed with tests only.

## Not verified

- A real 1.5.2 deployment, Livy, the scheduler; HDFS/object stores. X13 on the 1.5.2 line (no v3 there).
