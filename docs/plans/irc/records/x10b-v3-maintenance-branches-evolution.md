# X10 (rest): maintenance on format v3 tables with branches and tags, evolved specs, equality deletes: record

Marks: [ran] run in worktree /home/user/oh-x10b (branch irc/x10b-v3-maintenance-branches-evolution, base d30cb9f6);
[read] read in code, not run; [inferred] neither.

## Bottom line

Every maintenance job ran untouched on all three shapes, on format v2 and v3, on both jobs lines (Spark 3.5 with
the OpenHouse catalog, Spark 4.2 over REST), with one exception. Partition stats mislabelled the values of
evolved-spec tables; fixed, with single-spec output unchanged. The first CI run was red because the test JVM ran out
of memory, driven by a pre-existing connection-pool leak per Operations.getTable. Fixed in the tests only, by one
catalog per format for the tests' own loads. Draft pull request 137, head b2dcf8c09a7cb3e453908b13755e141ea2243efe:
CI green on every job. Base e6c52c21 (pull request 139, nothing under apps/) not merged; it merges cleanly.

## What OpenHouse allows [read; pinning tests pending]

- Branches and tags: allowed natively (Spark SQL CREATE BRANCH/TAG, Java manageSnapshots) and over REST
  (IcebergRestCatalogWriteTest.branchesAndTags). The snapshots PUT carries the client's refs map; the server
  removes refs missing from it and sets the rest (OpenHouseInternalTableOperations.doCommit, step 4).
  history.expire.max-ref-age-ms is kept in (0, 7 days] (boundMaxRefAge), so expiry drops refs older than 7 days.
- Partition spec evolution: a spec change on an existing table is refused natively and over REST
  (OpenHouseInternalRepositoryImpl.checkPartitionSpecEvolution compares partition field names; IrcWriteRoutesTest
  partitionEvolutionGetsTheNative400). Several specs exist only from an IRC create transaction (pull request 134)
  or a replace (keeps old specs).
- Equality deletes: no OpenHouse check refers to them (grep "equality" in services/internalcatalog main).

## Log

- Worktree created at d30cb9f6.
- d81ddc32 tests committed (compile on 1.12): FormatV3ShapeJobsTestBase (every job, strict + scheduler args, all refs
  checked) + FormatV3BranchesJobsTest, FormatV3SpecEvolutionJobsTest, FormatV3EqualityDeletesJobsTest; per-line
  IcebergFiles (generic Parquet data + equality-delete writers); FormatV3Tables: createAndInsert/rowLevelWrites extracted
  (same statements). Merged origin/irc-integration e2823b69 (pull request 135) as b067e2f6, clean: the new tests
  are also compiled and run by :apps:openhouse-spark-apps-4.2_2.13 (RESTCatalog sessions). REST client in tests now
  takes the session catalog's uri (4.2: already /iceberg; 3.5: + /iceberg).
- First run (1.12, Spark 3.5, three classes) queued behind memory.
- Run r1 [ran, 1.12, Spark 3.5], 70 cases: branches 26/28 and equality deletes 26/28 pass untouched (every job, v2+v3);
  the 4 failures were harness: Spark's cached table did not see a branch/an equality delete committed through another
  client (REFRESH TABLE added). Spec evolution 0/22 ran: the staged create (first REST request) is refused when its
  initial spec is not what the native model spells (day(ts) first, then clustering: IcebergCreates.toCreateBody,
  sameFields compares field order). Fixture changed to spec 0 = (day(ts), identity(category)), spec 1 = (day(ts),
  identity(region)): category dropped, region added, so the union struct (ts_day, category, region) differs from the
  current spec (ts_day, region) at position 1, which the suspect maps positionally.
- Observed: production compaction (--tableName only) committed on both shapes, v2 and v3. Equality deletes: strict
  compaction leaves all 6 equality delete files live in metadata but none applies to a live data file (rewritten
  files carry the starting sequence number); after retention 2 of 6 remain live (those of kept partitions).
- Run r2 [ran, 1.12, Spark 3.5]: spec evolution 20/22 pass untouched (every job); partitionStatsLabelEachValueWithItsOwnField
  FAILED v2 and v3: "region: {region=b, ts_day=...} expected r1" = the stats mislabel is REAL (a spec-0 category value
  reported as region; spec-1 region values not reported). Pinning tests: branches native+REST, equality deletes
  native, spec change refused native+REST: all pass. Commit 2d370b87 (tests), pushed.
- Fix 68fbb3aa: TableStatsCollectorUtil.transformPartitionRowToColumnData labels by the row's own struct field names
  when the row has a schema (else positional, as before). r3 [ran]: label tests pass v2+v3; TableStatsCollectorUtilTest
  (2 new: union labelling; single-spec with-schema == positional) and TableStatsCollection* all pass (90 cases).
- Next: same classes on Spark 4.2 (r4), then full suites on three projects/lines.
- r5 [ran, 1.12, Spark 4.2 jobs over REST]: Branches 24/0/0, EqualityDeletes 20/0/0, SpecEvolution 26/0/0,
  TableStatsCollectorUtilTest 35/0/0. Same outcomes as Spark 3.5 (production compaction commits; same EQ counts).
  First 4.2 compile failed: DummyTokenInterceptor needs jettison, absent there: token now from session conf.
- 48b64f9b (pin production compaction on 1.12; session token); merged origin/irc-integration 4b696a55 (#136,
  build.gradle only) as 97eb1f51, pushed. Draft pull request 137 opened on 97eb1f51. Full suites running (full.sh).
- Full suites on 97eb1f51 [ran]: reference line apps 466/0/88 (pass). 1.12 line apps 464/67 failed/6 locally, and CI
  apps-spark-3.5 (default) red 381/62/6, all other 13 jobs green (incl. apps-spark-4.2 both lines).
- Root cause of the red job [ran, measured]: test JVM (Gradle default heap 512 MB, no maxHeapSize set) hit
  OutOfMemoryError in OperationsTest (after the FormatV3 classes); SparkContext died, every later Spark test failed,
  executor exit 52. Live-heap floor rose steadily (gc log 298 -> 353 MB in 4 min); histogram diffs: relocated
  reactor-netty DefaultPooledConnectionProvider 572 -> 775 (and one ObjectMapper/DeserializerCache each) while only
  ~5 ApiClients live. Every Operations.getTable builds a new OpenHouse catalog; its WebClientFactory
  .getCustomConnectionProvider() pool (evictInBackground) is never disposed, so each stays reachable. My tests load
  tables via Operations hundreds of times per class. Fix (tests only): FormatV3Tables.table() = one catalog per
  format for the tests' own loads. Production jobs unchanged (leak reported, not fixed: client code).
- After the test-side fix [ran, 1.12, subset FormatV3* + OperationsTest + *TableStatsCollectionSparkAppTest, gc log]:
  all pass; pools 35 at Branches start -> 670 entering OperationsTest -> 785 at its end (~0.25 MB live each), live
  after full GC 373 MB of 512. The remaining pools are the jobs' own (production code, one per Operations.getTable)
  and other classes' test loads. Committed 9d6fdbb3; merged origin/irc-integration c2c52144 (#138) as b2dcf8c0, pushed.
  Full suites rerunning; CI on b2dcf8c0 running.
- CI on b2dcf8c0 [ran]: all 13 jobs success (1 skipped by design), incl. Default apps-spark-3.5 and apps-spark-4.2.
- Local on b2dcf8c0 [ran]: apps 1.12 466/0 failed/5 skipped (Branches 24, EqualityDeletes 20, SpecEvolution 26,
  TableStatsCollectorUtilTest 35: all 0 failed). gc log: post-GC floor 232 MB (16:0x), 307, 373 MB at the end of 512:
  passes with ~140 MB margin, still rising (pre-existing per-catalog pool leak). Reference line and 4.2 check running.
- Reference line full on b2dcf8c0 [ran]: apps 466/0/88. Spark 4.2 check: CI green; local run still in progress
  at report time (51 passed, 0 failed so far). Pull request 137 description updated. Final report sent.
- Spark 4.2 check local on b2dcf8c0 [ran, after the report]: did not complete. The Gradle daemon disappeared (killed; memory was shared and tight) after 123 tests passed and 0 failed. Not retried, as the coordinator asked; CI ran it green.
