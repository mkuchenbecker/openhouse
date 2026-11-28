# X2 and X3: row-level statements and row lineage on format v3 tables: record

Marks: [ran] run in worktree /home/user/oh-x23 (branch irc/x2-x3-v3-row-level, base cafcc9da); [read] read in
code; [inferred] neither.

## Bottom line

No defect found: on every Spark line and catalog path, DELETE, UPDATE and MERGE on v3 tables keep exactly one live
deletion vector per data file (replaced, never added a second time, its cardinality the rows deleted), and row
lineage follows the fork's contract after every statement, in both write modes [ran, 1.12 and 4.2]. Tests only; no
production code changed. Two declared differences: Avro cannot be written in the Spark 3.5 itest JVM (harness
classpath), and on the 1.5.2 line v2 position delete files accumulate.

## State 2026-11-27 (resumed)

- Merged origin/irc-integration 81b4cd4d: merge commit 47d20539 (pushed). Reran [ran]: 1.5.2 RowLevel x3
  (17: 7 run, 10 skipped, 0 failed) + RestHttpPathWriteStatementsTest 19/0; 1.12 RowLevel x3 17/0,
  RestHttpPathWriteStatementsTest 19/0, FormatV3SparkTest 7/0; 4.2 SparkIrcRowLevelStatementsTest 17/0,
  SparkIrcWriteStatementsTest 19/0, SparkIrcFormatV3Test 8/0. check-test-groups: partition holds.
- Draft pull request 151 opened. CI on 47d20539: 13 jobs success, 1 skipped by the workflow (reference build's
  Docker job). Done; the next step is the independent review, then the orchestrator's merge.
- Behavior changes against the base: none, test sources only (spark-3.5 itest src/shared/smoke, src/test;
  spark-4.2 itest src/service, src/smoke, src/test).

## State for a restart (paused 2026-11-25 by the owner's instruction)

- Commits pushed: d3c35471 (3.5 tests + BaselineChecks extraction), e364a3c3 (4.2 runner, v3 smoke service),
  f62caf80 (partition-scoped v2 Avro deletes, 1.5.2 declarations), 1892643c (4.2 lineage without extensions).
  Pushed head 1892643c65551e97ed404350e05b4231ea87039d; worktree clean, nothing uncommitted.
- Done at 1892643c [ran]: 1.12 line: 3.5 RowLevel x3 (17/0/0 each) + RestHttpPathWriteStatementsTest 19/0;
  4.2 smokeTest SparkIrcRowLevelStatementsTest 17/0 + SparkIrcWriteStatementsTest 19/0; 4.2 test
  SparkIrcFormatV3Test 8/0. 1.5.2 line at f62caf80: RowLevel x3 17 each, 7 run, 10 skipped, 0 failed.
- Half done: the final 1.5.2 run (RowLevel x3 + RestHttpPathWriteStatementsTest at 1892643c) was stopped by the
  pause; no result. origin/irc-integration was still cafcc9da (nothing to merge) when checked.
- No pull request opened.
- Exact next step: fetch and merge origin/irc-integration under the lock; rerun the 1.5.2 command
  (`./gradlew -PicebergLinkedIn ... :integrations:spark:spark-3.5:openhouse-spark-3.5-itest:catalogTest --tests
  '*RowLevelStatementsTest' --tests '*RestHttpPathWriteStatementsTest'`); run `python3 scripts/ci/check-test-groups.py`;
  open the draft pull request against irc-integration; wait for CI; write the final report.

## What the tests are [ran]

`SparkSqlRowLevelStatements` (shared smoke sources, compiled by both itests) on a service with the v3 switch on:
14 shapes (Parquet v2/v3 x MOR/COW x unpartitioned/partitioned; ORC v3 MOR, v3 COW, v2 MOR; Avro the same), each
INSERT then DELETE, UPDATE, MERGE (matched delete + matched update + not-matched insert), MERGE (matched update
only), MERGE (matched delete only), MERGE (not-matched insert only); three of them hit the same data file. After
every statement: rows via the path under test, via Iceberg's generic reader (no Spark), via the other paths of the
JVM (3.5); per data file the delete files a scan applies (count, format, positions); live delete-manifest entries
(no data file with two vectors, no dangling vector, no position delete file on v3); `added-dvs`/`removed-dvs`;
every row's `_row_id/_last_updated_sequence_number`; no shared row id; next-row-id = previous + added-rows. Plus
time travel + rollback (v2 and v3) and an upgraded v2 table with a position delete file. Runners:
Legacy/RestShim/RestHttpPathRowLevelStatementsTest (3.5 catalogTest), SparkIrcRowLevelStatementsTest (4.2
smokeTest), and SparkIrcFormatV3Test.rowLineageWithoutIcebergSqlExtensions (4.2 test JVM, no extensions).

## Results [ran]

| Scenario | 3.5 legacy | 3.5 rest-shim | 3.5 rest-http | 4.2 IRC |
| --- | --- | --- | --- | --- |
| Parquet v3 MOR/COW, unpart/part, all 7 statements | passes | passes | passes | passes |
| ORC v3 MOR, v3 COW | passes | passes | passes | passes |
| Avro v3 MOR, v3 COW, v2 MOR | declared (itest JVM) | declared | declared | passes |
| 2nd/3rd statement on one data file: one live vector, cardinality, removed-dvs | passes | passes | passes | passes |
| row lineage per row after each statement | passes | passes | passes | passes (with and without Iceberg SQL extensions) |
| reads agree: path, other paths, generic reader | passes | passes | passes | passes (path, generic reader) |
| time travel before DELETE/UPDATE; rollback; DELETE+INSERT after | passes | passes | passes | passes |
| v2 next to each v3 case | passes; 1.5.2: declared | same | same | passes |
| upgraded v2 table with position delete file, next statements | passes | passes | passes | passes |

On 1.5.2 [ran]: per path 17 tests, 7 run (v2), 10 skipped by assumption (v3), 0 failed.

## Findings [ran]

- v2 on Iceberg 1.12: Spark replaces a file-scoped position delete file (one per data file, like a vector).
  v2 Avro: delete files have no bounds, so they are partition-scoped and accumulate (in the baseline).
- 1.5.2: v2 position delete files accumulate, one per statement (declared, 4 tests).
- Avro in the 3.5 itest JVM: ClassCastException, thin iceberg-core next to the shaded runtime (declared).
- Upgrade: the first DELETE on v3 folds the position delete into one vector and the position delete file stops
  being live; old rows get row ids from that commit.

## Review round (pull request 151 review, 2026-11-27)

- F1-F7 edited in the worktree, not yet compiled/committed (waiting for 6 GB free memory). Next: compile,
  spotless, mutation check (v3MergeOnReadParquet created copy-on-write, legacy path, must go red), revert,
  run 3.5 RowLevel x3 on 1.12 and 1.5.2 and 4.2 RowLevel + SparkIrcFormatV3Test, commit, push, CI.
- 8f096093 committed and pushed (F1, F2, F3 first form, F4-F7). Mutation check [ran]: v3MergeOnReadParquet
  created copy-on-write, legacy path: 11 unexpected values (write modes; rows removed through delete files 1/1/2/1/1
  vs 0; "a deletion vector is live" false), reverted, not committed.
- Full run at 8f096093 [ran]: F3's first form (added-rows == rows of new data files) failed on
  v3CopyOnWriteParquetPartitioned on all four paths (DELETE 2 vs 5, MERGE update 3 vs 5). Not a defect: the
  spec makes added-rows the upper bound, the added + existing row counts of manifests given a first-row-id
  (a rewritten manifest carries the other partition's file). Check corrected to the spec plus a lower bound
  (uncommitted, rerunning everything).
- b27e5133 committed and pushed (added-rows per the spec). Full rerun at b27e5133 [ran]: 1.12 RowLevel x3 17/0,
  RestHttpPathWriteStatementsTest 19/0; 4.2 SparkIrcRowLevelStatementsTest 17/0, SparkIrcWriteStatementsTest 19/0,
  SparkIrcFormatV3Test 8/0; 1.5.2 RowLevel x3 17 (10 skipped)/0, RestHttpPathWriteStatementsTest 19/0.
  check-test-groups holds. Pull request body updated. Waiting for CI on b27e5133.
- CI on b27e5133: 13 jobs success; skipped by the workflow: reference Docker job and two summary entries. Done.
