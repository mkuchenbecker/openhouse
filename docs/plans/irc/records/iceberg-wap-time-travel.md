# Iceberg fork: time travel while `spark.wap.branch` is set (Spark 3.5, 4.2)

Date 2026-11-23. Fork mkuchenbecker/iceberg, branch `irc/wap-time-travel` from `origin/openhouse-1.12.0` (28742df3e).
Worktree `/home/user/iceberg-wap`. Draft pull request https://github.com/mkuchenbecker/iceberg/pull/12 into
`openhouse-1.12.0`. Head `fd5ed9b8bd23a06b49dfe8404816aaff0a816b53`. "Ran" means it was run here; "read" means it comes from reading the code.

## Bottom line
- **The cause.** Apache a47937c0c (#10538, "Spark 3.5: Support Aggregate push down for incremental scan", 2024-06-21)
  came after 1.5.2. It replaced `SparkScanBuilder.readSnapshot()` in `pushAggregation` with `buildIcebergBatchScan(true, ...)`,
  so aggregate push-down now builds the same scan as a row read: `useSnapshot`/`asOfTime`/`useRef(tag)` plus
  `useRef(<WAP branch>)`. `SnapshotScan.useRef` refuses that combination ("Cannot override ref, already set snapshot id").
- **Why LinkedIn's line passed.** On 1.5.2, `pushAggregation` took the snapshot from `readConf.snapshotId()` and never
  touched the WAP branch, so `count(*) ... VERSION AS OF / TIMESTAMP AS OF` read main's history. OpenHouse's harness case
  asserts with `count(*)`, so it passed. **Row reads failed on LinkedIn's line in the same way** (ran). The real defect is
  older and is shared by both lines: `SparkReadConf.branch()` falls back to the WAP branch even when a snapshot, timestamp
  or tag is named. No other file differs. `SparkReadConf.branch()`, `SparkTable.newScanBuilder`,
  `SparkCatalog.loadTable(ident, version|timestamp)`, `SparkScanBuilder.buildBatchScan` and `SnapshotScan.useRef` are the
  same on both lines (read, by diff).
- **Not a LinkedIn patch.** a47937c0c is Apache's commit (Huaxin Gao), not one of LinkedIn's. No LinkedIn commit in the
  ledger touches this code: the ledger has 29 commits, none for aggregate push-down or WAP reads.
- **Fix (Spark 3.5).** `SparkReadConf.branch()` returns null, so it skips the WAP fallback, when `snapshotId()`, `asOfTimestamp()` or
  `tag()` is set and no branch is named in the identifier or the options. This change goes further than LinkedIn's line, which only
  handled aggregates.
- **Upstream status.** Apache main is not fixed for 3.5 or 4.0 (read, against `apache/main` d74ce711d, fetched here).
  In 4.1 and 4.2, Apache moved WAP read resolution into the `ResolveBranch` analyzer rule (#15288 c7ed71e97, #15240
  30232d3e7). That rule only rewrites relations without a time-travel spec, so the problem does not occur there. There was
  no upstream change to cherry-pick for 3.5.
- **Spark 4.2.** No fix was needed. The same test passes on the base (ran) and is committed as a pin.
- **Spark 4.0.** Same code as 3.5, so the same problem (read; not ported).
- **Spark 4.1.** Same `ResolveBranch` and `SparkTableUtil` as 4.2 (read: the files are identical), so it should be fine.
  Not run.

## The matrix
The table has WAP enabled and these refs:
- main: snapshot 1 = {1}, snapshot 2 = {1, 2}
- tag `t1` on snapshot 1
- branch `other` = snapshot 1 + {9}

There are three session states:
- **A**: `spark.wap.branch=wap`, branch not created
- **B**: branch `wap` created at main's head
- **C**: a staged INSERT of {3} on `wap`

Each read is checked as rows and as `count(*)` (push-down). "time travel" = `VERSION AS OF <snap1>`,
`TIMESTAMP AS OF '<snap1 ts>'`, `VERSION AS OF 't1'`, and the options `snapshot-id`, `as-of-timestamp` and `tag` (Spark 4.2:
`versionAsOf`/`timestampAsOf`). "Refuse" = `IllegalArgumentException: Cannot override ref, already set snapshot id=...`.

| Read | State | LinkedIn 1.5.2 (ran) | Fork 1.12 before, Spark 3.5 (ran) | Fork after, Spark 3.5 (ran) | Fork, Spark 4.2 (ran, no change) |
|---|---|---|---|---|---|
| no version, rows / count | A | {1,2} / 2 | {1,2} / 2 | {1,2} / 2 | {1,2} / 2 |
| no version | B | {1,2} / 2 | {1,2} / 2 | {1,2} / 2 | {1,2} / 2 |
| no version | C | {1,2,3} / 3 | {1,2,3} / 3 | {1,2,3} / 3 | {1,2,3} / 3 |
| time travel (all 6 forms), rows | A | {1} | {1} | {1} | {1} |
| time travel (all 6 forms), count | A | 1 | 1 | 1 | 1 |
| time travel (all 6 forms), rows | B, C | **Refuse** | **Refuse** | {1} | {1} |
| time travel (all 6 forms), count | B, C | 1 | **Refuse** | 1 | 1 |
| `VERSION AS OF 'other'`, `.branch_other`, option `branch` | A, B, C | {1,9} / 2 | {1,9} / 2 | {1,9} / 2 | {1,9} / 2 |
| legacy options `snapshot-id`, `as-of-timestamp`, `tag` | A, B, C | as time travel | as time travel | as time travel | rejected: "Time travel option `...` is no longer supported" (Apache 4.1+, not WAP-related) |

On LinkedIn's line, the option reads `as-of-timestamp` and `tag` gave a correct `count(*)` = 1 (ran), because its `IcebergSource` turns
those options into a snapshot-id table selector before push-down sees them.

Raw results: the JUnit XML files in the scratchpad (`wap35-before.xml`, `wap-li152.xml`) and the logs `wap35-before.log`,
`wap35-after.log`, `wap42-before.log`, `wap-li152.log`.

## How LinkedIn's line was run
- Worktree: detached `li/openhouse-1.5.2` (af77f0550).
- Same test file, copied over.
- JDK 17 and that line's Gradle 8.1.1, `-DsparkVersions=3.5 -DscalaVersion=2.13`.
- Needed `LC_ALL=C.UTF-8` plus `-Dfile.encoding=UTF-8` appended to `JAVA_TOOL_OPTIONS` (the existing value was kept). Without
  it, LinkedIn's ORC sources fail to compile.
- Needed `-x generateGitProperties`: the old jgit does not understand git worktrees.
- Two Maven Central 429s, then retried.
- The worktree was removed afterwards.

## Fix and files
- `spark/v3.5/spark/src/main/java/org/apache/iceberg/spark/SparkReadConf.java`: 6 lines in `branch()`. SQL
  `VERSION AS OF`/`TIMESTAMP AS OF` reach `SparkReadConf` as the `snapshot-id` option (`SparkTable.addSnapshotId`), so
  they are covered.
- `spark/v3.5/spark-extensions/src/test/java/org/apache/iceberg/spark/extensions/TestWapBranchTimeTravel.java` (new).
- `spark/v4.2/spark-extensions/src/test/java/org/apache/iceberg/spark/extensions/TestWapBranchTimeTravel.java` (new).
  It lives in extensions because 4.2 WAP reads need the extensions. It uses `versionAsOf`/`timestampAsOf` and pins the legacy-option
  rejection.

## Test counts (ran, JDK 21)
- **New tests.**
  - Spark 3.5: 2 of 3 fail before the fix (B and C), 3 of 3 pass after.
  - Spark 4.2: 3 of 3 pass on the base. It never failed, because there was nothing to fix.
- **Existing tests.** Filters: `*Wap*`, `*Branch*`, `*TimeTravel*`, `*SnapshotSelection*`, `*Select*`, plus `TestSparkReadConf` and
  `TestAggregatePushDown`, with the fix in place. The skips are existing assumptions.
  - Spark 3.5 spark: 433 tests, 0 failed, 16 skipped. Includes TestSelect 78, TestSnapshotSelection 24,
    TestPartitionedWritesToWapBranch 36, TestPartitionedWritesToBranch 28, TestUnpartitionedWritesToBranch 32,
    TestAggregatePushDown 88, TestSparkReadConf 12.
  - Spark 3.5 extensions: 200 tests, 0 failed, 42 skipped. Includes TestBranchDDL 19, TestReplaceBranch 7,
    TestFastForwardBranchProcedure 28, TestPublishChangesProcedure 24, the WAP and branch methods of the CoW/MoR
    Delete/Merge/Update suites, and TestWapBranchTimeTravel 3.
  - Spark 4.2 spark: 397 tests, 0 failed, 18 skipped.
  - Spark 4.2 extensions: 236 tests, 0 failed, 42 skipped. Includes TestPartitionedWritesToWapBranch 36.
- **Checks.** Compile (`compileTestJava`) plus `checkstyleMain`, `checkstyleTest` and `spotlessCheck` pass for spark-3.5,
  spark-extensions-3.5 and spark-extensions-4.2.

## Commits
- da24b117c Spark 3.5: Test that time travel reads ignore the session WAP branch
- 435fc494b Spark 3.5: Time travel reads ignore the session WAP branch (Not a LinkedIn patch)
- fd5ed9b8b Spark 4.2: Pin that time travel reads ignore the session WAP branch

## Ready-to-use merge commit
Title: `Merge: Time travel reads ignore the session WAP branch on Spark 3.5 (#12)`

```
Why: OpenHouse's Spark SQL harness found that on this line an explicit TIMESTAMP AS OF or VERSION AS OF fails with "Cannot override ref, already set snapshot id" while spark.wap.branch names an existing branch on a WAP-enabled table (interact.branch.ttBeforeBranchPoint). The cause is that SparkReadConf.branch() falls back to the WAP branch even when the read names a snapshot, timestamp or tag, so SparkScanBuilder applies both and SnapshotScan refuses. LinkedIn's 1.5.2 line has the same fallback and fails the same way for row reads. Its count(*) reads worked only because aggregate push-down picked the snapshot by id and ignored the branch. Apache a47937c0c (#10538), after 1.5.2, made push-down build the normal scan, so on 1.12 count(*) fails too. The fix is not a LinkedIn patch: an explicit snapshot, timestamp or tag now disables the WAP fallback on Spark 3.5.
Leaves true: On Spark 3.5 and 4.2, with spark.wap.branch set on a WAP table (branch absent, at main's head, or with a staged write), every explicit read reads main's history or the named ref, as rows and as count(*). These are VERSION AS OF <id>, TIMESTAMP AS OF, VERSION AS OF '<tag>' or '<branch>', a branch_ identifier, and the snapshot-id, as-of-timestamp, tag and branch options on 3.5 (versionAsOf, timestampAsOf and branch on 4.2). A read with no version follows the WAP branch once it exists. A branch named explicitly together with a snapshot is still refused. Spark 4.2 needed no code change; Apache's ResolveBranch rule (#15288, #15240) already skips time-travel relations. On 4.2 the legacy snapshot-id, as-of-timestamp and tag options are rejected, as in Apache 4.1+.
Evidence: Run locally on JDK 21. The new TestWapBranchTimeTravel on Spark 3.5 fails 2 of 3 before the fix and passes 3 of 3 after. On Spark 4.2 it passes 3 of 3 on the base. The same test run on li/openhouse-1.5.2 (JDK 17) shows row reads refused and count(*) correct. Existing classes matching Wap, Branch, TimeTravel, SnapshotSelection and Select, plus TestSparkReadConf and TestAggregatePushDown, all pass: Spark 3.5 spark 433 run, 0 failed; Spark 3.5 extensions 200 run, 0 failed; Spark 4.2 spark 397 run, 0 failed; Spark 4.2 extensions 236 run, 0 failed. Compile, checkstyle and spotless pass for the touched modules. Fork CI is not a gate because it fails on Maven Central rate limits.
Known gaps: Spark 4.0 has the same code as 3.5 and the same problem; it was read, not fixed or run. Spark 4.1 should behave like 4.2; it was read, not run. Apache main has no fix for 3.5 or 4.0. The full Spark suites were not run, only the filtered classes. OpenHouse's harness was not re-run against the fixed fork.
Commits:
- da24b117c Spark 3.5: Test that time travel reads ignore the session WAP branch: the failing test, committed first; it records the matrix for rows and count(*)
- 435fc494b Spark 3.5: Time travel reads ignore the session WAP branch: not a LinkedIn patch; fixes the Apache regression from #10538 relative to LinkedIn's line, and also fixes row reads, which LinkedIn's line never handled
- fd5ed9b8b Spark 4.2: Pin that time travel reads ignore the session WAP branch: evidence that 4.2's own read path already behaves this way, so no port is needed
Decision record: docs/plans/irc/13-decisions.md (D8) and docs/plans/irc/22-decisions-continued-2.md (D31) on branch claude/openhouse-rest-v3-planning-g42c0h of mkuchenbecker/openhouse.

Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01T3dbirnudathz3xU8K18AE
```

## Not run
- Spark 4.0 and 4.1: read only.
- The full Spark 3.5 and 4.2 suites: only the filtered classes were run.
- OpenHouse's delta harness against the fixed fork.
- Fork CI: not acted on, by instruction.
- The LinkedIn run used the `li/openhouse-1.5.2` tip (af77f0550), not the released 1.5.2.24 jar.

## Side effects
- `/home/user/iceberg` now has `refs/remotes/apache/main`. It was fetched with `--shallow-since=2024-03-01`, which adds shallow roots to that
  clone, to find the Apache commit and check upstream.
- The repository stays shallow.
