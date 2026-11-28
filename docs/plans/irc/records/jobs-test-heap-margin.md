# Jobs tests: close what fixtures build, and an explicit test heap

## Bottom line
- Draft pull request 143 (base irc-integration), head c34c978398acae3a896a4b966f63558f28c269af, two commits. All CI jobs green.
- Heap after a full collection at the end of the Spark 3.5 jobs suite (default line, 512 MB default heap):
  369 MB before, 296 MB after the fixtures alone (about 73 MB saved), margin 143 MB -> 216 MB.
  With maxHeapSize = 1g: margin about 728 MB. Tests 471 / 5 skipped / 0 failed in both runs.
- Finding: Operations.close() also closes its Spark session (stops the context). Part of the saving is therefore
  sessions being stopped and recreated, not only pools. Not known how much of the 73 MB is which.
- Finding: the Spark 4.2 jobs project already sets maxHeapSize = 1536m (apps/spark-4.2/build.gradle); the task said
  neither did. Left unchanged. Only apps/spark-3.5/build.gradle got a value.

## Method [ran]
- Init script (-I) adds -Xlog:gc* to the test task of the Spark 3.5 project; a sampler runs jcmd GC.run every 2 min
  against the test executor of this worktree. Numbers are the "Pause Full ... M->NM" lines. First sampler picked another
  session's executor; fixed, so forced collections start ~10 min into the before run.
- Before run on 3079859d detached; after run on ee71f46d (differs from c34c9783 only in the Spark 4.2 test file).
- Series after full GC, MB: before 143 166 211 222 220 250 274 276 278 290 300 305 305 313 335 369;
  after 151 152 215 226 226 225 202 240 239 262 257 254 264 290 296.

## Fixtures changed (tests only, no assertion changed)
- FormatV3Tables: ops(format) = one Operations per format version, closed in close(); newOps(session, emitter) for
  own session/spy emitter, closed in close(). Used by FormatV3Maintenance (retention x2, sort stats), Variant, Types.
- FormatV3ConcurrencyTest: loserOps cached per format, separate from the winner's, closed in @AfterAll.
- BucketBehaviorTest: one per session, closed @AfterEach. DataLayoutStrategyGeneratorSparkAppTest: try-with-resources.
- Spark42TypeRestCatalogTest: try-with-resources, closed last (first attempt failed: close stopped the session before
  the final query; found by the local Spark 4.2 run).
- SparkMoRFunctionalTest: closed in @AfterEach.
- Not changed: SparkSchemaEvolutionTest, WapIdJavaTest, SortStatsTempTableTest (one Operations per test).

## Heap value
- 1g in apps/spark-3.5/build.gradle, tasks.withType(Test). Single post-merge job: up to 4 workers, so up to 4 test
  JVMs at once; largest heap anywhere is 1536m (4.2 jobs, itests), so <= 6 GB, plus the 2 GB Gradle daemon, in 16 GB.
- No heap assertion. Future authors: watch heap after full GC at the end of the suite; count Operations built per
  fixture (OperationsCatalogTest counts catalogs per Operations).

## Local runs
- Spark 3.5 full, default line: 471 tests, 5 skipped, 0 failed (before and after).
- Touched classes on -PicebergLinkedIn: 98 tests, 47 skipped (v3 on 1.5.2), 0 failed.
- Touched classes on Spark 4.2: 99 tests, 1 failed (above), then Spark42TypeRestCatalogTest passes after the fix.
- Full suites left to CI, all success: Default build, apps-spark-3.5; Default build, apps-spark-4.2;
  Reference build, apps-spark-3.5 (and the other 13 jobs).

## Process
- Branch history rewritten once (force-with-lease on my own branch) to fold the 4.2 fix into the fixtures commit.
- Not verified: the heap with 1g was not measured; the 4.2 full suite locally; the split between session closing and
  pool closing in the saving.
