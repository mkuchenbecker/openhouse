# Compaction with partial progress: a group commit refused with 409

## Bottom line

A test-fixture defect, not a job, client or service defect: the House Tables stand-in's embedded H2
(tables-test-fixtures, SpringH2TestApplication) could serve a session a row older than a commit that
had returned, and keep serving it on that pooled connection. H2 result reuse is now off there; the
compaction test is unchanged. Production House Tables is not H2, so production compactions are not exposed.

Branch irc/compaction-partial-progress-409 (worktree /home/user/oh-flake), base 9dfbbca7.
Commits: 12064fa0 reproduction test (fails on the old fixture), 13ffb02e fix. Pushed.

## Root cause [ran + read]

- H2 2.1.210 `org.h2.command.query.Query.query`: reuses `lastResult` when parameters match and no
  table changed since `lastEvaluated` (OPTIMIZE_REUSE_RESULTS, default on). `lastEvaluated` is the
  database modification id read before evaluation.
- `org.h2.engine.SessionLocal.commit(boolean)` calls `markUsedTablesAsUpdated` (bumps the table's
  modification id) before `Transaction.commit` (makes the row visible) [javap]. A read on another
  session in between computes the old row and records it as current; that session's same query
  returns the old row until the table changes again.
- HikariCP gives a thread back its last connection, so one Tomcat thread kept answering the old row.
- In the failing run [ran, log survived: scratchpad/flake409/failing-run-152.xml]: group 4 attempts:
  1) base 00014, 409 retry cache (group 3 had just committed 00015 from 00014: a real conflict);
  2) and 3) base 00015, service read head 00014 (stale); 4) client got 00014 from a stale GET, sent
  base 00014, service head 00015. Retries exhausted, group dropped ("partial progress enabled.
  Ignoring"). Server reads of the row after 00015 alternated 15/14 per request to the end of the test.
- Reproduction [ran]: HouseTablesH2ReadAfterCommitTest, 3000 CAS saves vs 3 readers: old fixture
  602 old reads via findById, 761334 via JDBC (fails every run); fixed fixture 0 and 0.

## Candidates

1. Partial-progress commits overlapping: CONFIRMED as the trigger, not the cause. BaseCommitService.offer
   (v1.5.2.24 and openhouse-1.12.0, line 150) commits on each Rewrite-Service thread, so groups commit
   concurrently on one shared Table; two from the same base give one 409, which Iceberg retries.
   With a consistent store each failed attempt means another group landed: at most groups-1 failures
   (3 here) < 5 attempts, so all groups commit.
2. Retry cache: REFUTED as the cause. Its 409 was attempt 1, a genuine same-base conflict, retried.
3. Client mapping/retry: REFUTED. 409 -> CommitFailedException -> refresh and retry, as intended; the
   stale base on attempt 4 came from a stale GET answer, not from the client. Shared unsynchronized
   BaseMetastoreTableOperations can only move to a newer location absent stale answers [read].
4. H2 stand-in: CONFIRMED (above).
5. Other lines: the fixture is shared by both Spark 3.5 lines, the 4.2 line, the Spark itests and the
   REST compatibility kit, so any of them could hit it; the fix covers them all. Not production.

## What the job reports when a group is dropped [read + ran]

1.5.2: Result lists only committed groups; rewriteFailures has rewrite failures only; the app logs
and emits counts of committed groups and succeeds. 1.12: same plus a WARN, and a throw past
partial-progress.max-failed-commits (default = max-commits). Consistent with what was committed.

## Changed / not changed

- Changed: tables-test-fixtures-h2.properties (spring.datasource.url with a random name per context,
  OPTIMIZE_REUSE_RESULTS=FALSE), @PropertySource on SpringH2TestApplication; new fixture test.
- Not changed: FormatV3MaintenanceTest (its assertion holds given a consistent store, see 1), the
  job, client, service commit path, retry cache, deployment args, Iceberg fork.
- Same race exists in services/tables test copy (SpringH2Application + its HouseTablesH2Repository);
  not changed (service project tests), reported.

## Verification

- Fixture tests 1.5.2 line: 33 passed, 0 failed (includes the 2 new cases).
- Full jobs suite 1.5.2: running.
- Full jobs suite 1.5.2 [ran, on 13ffb02e]: 481 = 390 passed / 0 failed / 91 skipped; no stale GET.
- Merged origin/irc-integration 22017134 (spark-itest smoke files only) -> 2519945f; check-test-groups: holds.
- Draft pull request 148, head 2519945f4ebb37c859c6270eb317063b8a98c0df. CI: 13 jobs success, 1 skipped (Reference Docker/integration, skipped by design). Done.

## Follow-up: other test H2 databases (branch irc/h2-standin-reuse-results, worktree /home/user/oh-h2)

- Pull request 148 merged as 886b660b. New branch from it; draft pull request 150.
- services/tables SpringH2Application (Boot default embedded H2 2.1.210, Hikari, CAS + concurrent
  reads): CAN serve stale. Ported reproduction fails before (819 findById / 380178 JDBC), passes after.
  Fixed: test resource tables-service-test-h2.properties + @PropertySource (f8197d67 test, ad50d763 fix).
- services/housetables tests: URL from main source (JdbcProviderConfiguration.H2_DEFAULT_URL); out of
  scope, reported, not changed. Optimizer tests: MySQL Testcontainers. MockTablesApplication, jobs
  service: no datasource. apps/optimizer apps: main application.properties, no tests. Trino client
  test: one unpooled connection, sequential: cannot.
- Ran: 5 classes (19 tests) on default and reference line, all pass; check-test-groups holds.
- CI on ad50d763a14c0fdf309135a103f45a3ab84d9fb9: 13 success, 1 skipped (Reference Docker/integration, by design). Done.
