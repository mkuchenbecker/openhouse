# Result of V17 and X16 (pull requests 147 and 148)

Bottom line: both merged into `irc-integration` on 2026-11-25. V17 (pull request 147, merge commit `2ba15929`) makes the
namespace check of six Iceberg REST routes read one House Tables row instead of every table of the database; every
answer is unchanged, and two listings remain because each needs a House Tables query that does not exist (the owner's
decision). X16 (pull request 148, merge commit `886b660b`) found that the 409 which cost a compaction one file group was
caused by the test fixture, not by production code: the embedded H2 stand-in could keep serving a row as it was before a
commit that had returned. H2's reuse of query results is now off in the shared stand-in. Production is judged unaffected
because the real store is not H2 (inferred). Marks: [ran], [read], [inferred]; facts the orchestrator read are said so.
Records: `v17-irc-listing.md` and `compaction-partial-progress-409.md` in the reports directory.

## V17: the namespace check reads one row

Bottom line: HEAD and GET namespace, namespaces under a parent, the view list of a database with views disabled, and a
rename to another database now read one page of one row through the native paged table search, the probe the native
views service already uses. An empty table list reads one listing fewer. Every answer is unchanged.

### The route table [ran]

`IrcHouseTableReadsTest` pins the ordered House Tables reads per route on a database of three tables (T tables, D databases).

| Route | Before | After | Native comparison |
| --- | --- | --- | --- |
| HEAD namespace, exists | one listing of T rows | one page of 1 | views service probe: page of 1 [read] |
| HEAD namespace, missing | listing (0 rows), then all databases (D) | page (0 rows), then all databases (D) | none |
| GET namespace | one listing of T rows | one page of 1 | none |
| GET namespaces under a parent | one listing of T rows | one page of 1 | none |
| GET table list | one listing of T rows | unchanged | unpaged search: one listing of T |
| GET table list, page size 1 | full list, size ignored | unchanged | native paged search, size 1: one page |
| GET table list of an empty database | listing, listing, all databases | listing, all databases | none |
| GET views, views disabled | one listing of T rows | one page of 1 | none |
| rename to another database | one listing of T rows, then 406 | one page of 1, then 406 | none |
| staged create completion | listing of T plus soft-deleted pages | unchanged | native create: none |
| register (read, not pinned) | same method as the completion | unchanged | none |

Config, load, create, commit, same-database rename, drop, table-exists and the database list read only by key or once [ran, survey not kept].

### What changed and why the answers are unchanged

- The production diff is 19 lines in `IcebergRestApiHandler`, read by the orchestrator before merging, plus one new test
  class: `searchTables(db, 0, 1, null, null, null)`, no fields so no authorization step, the same House Tables filter as
  the unpaged search [read]. For an empty table list the second probe is skipped; a table created between the two reads
  now gets the database-list answer, a race that existed before.
- The 367 Iceberg REST service tests pass on the default line, the compatibility kit's 118 non-passing cases are exactly
  the reviewed list, and the five touched route classes (118 tests) pass on the reference line [ran]. The "before"
  numbers were taken from the base build's CI, not rerun. Reads are counted against the H2 stand-in; that the paged and
  unpaged queries of the real store return the same rows is read from their shared filter, not run. The pull request
  build was green on head `f6f9aaed` on both lines (read by the orchestrator).

### Two costs remain, and the page-size point

1. A missing namespace still reads the list of every database. It needs "does any row, table or view, have exactly this
   database id". The existing database-id search has no endpoint or client and is case-insensitive where today's check
   is exact; the views probe throws on a non-canonical row, which would change an answer [read]. Work item V23.
2. Completing a staged create or registering a table still lists the database and its soft-deleted rows for the
   directory-in-use check. It needs "rows of a database, live and soft-deleted, whose table UUID is U or whose location
   starts with P". Work item V24.
3. The table list route answers the full list whatever page size is asked. Honoring it changes the answer (a page token
   appears), so it needs a decision on paging over these routes. Work item V25, low priority, not started.

Items 1 and 2 are the owner's decision; nothing waits on them, and today's cost is pinned by the test.

## X16: the 409 that cost a compaction one file group

Bottom line: the cause is in the test fixture only. The House Tables stand-in is an embedded H2 2.1.210 database that
reuses a session's last query result while it believes no table changed. Its commit marks the table changed before the new
row is visible, so a read on another pooled connection in that window caches the old row and keeps answering it.

### What was observed and the cause [ran, read]

On the Iceberg 1.5.2 line a partial-progress compaction of four file groups lost one group to a 409 through every retry,
once, locally, with nothing else writing (`FormatV3MaintenanceTest.compactionKeepsLiveRowsDeletesAndRowIds[v2, orc]`, found
by pull request 145; the post-merge CI of 145 passed the same case, read by the orchestrator). The failing run's result
file survived. For the fourth group the attempts were:

1. Base 00014, 409 from the retry cache: group 3 had just committed 00015 from 00014, a real conflict.
2. And 3. Base 00015, but the service read head 00014, a stale answer.
4. The client received 00014 from a stale GET, sent base 00014, and the service head was 00015.

The retries ran out and the group was dropped. Reads of the row after 00015 alternated 15 and 14 to the end of the test.
The mechanism in H2 [read from bytecode with `javap`]: `Query.query` reuses its `lastResult` when parameters match and no
table changed since `lastEvaluated` (`OPTIMIZE_REUSE_RESULTS`, on by default). `SessionLocal.commit` marks the tables
updated before `Transaction.commit` makes the row visible. HikariCP hands a thread back its last connection, so one
Tomcat thread kept answering the old row.

### The five candidates

1. Partial-progress commits overlapping: confirmed as the trigger, not the cause. `BaseCommitService.offer` commits on
   each rewrite thread (line 150 in both `v1.5.2.24` and `openhouse-1.12.0`), so groups commit concurrently on one shared
   table, and two from the same base give one 409 that Iceberg retries. With a consistent store each failed attempt means
   another group landed, so four groups fail each other at most three times, fewer than Iceberg's five attempts.
2. The retry cache: refuted. Its 409 was attempt 1, a genuine same-base conflict, and was retried.
3. The client's mapping and retry: refuted. A 409 becomes a commit failure, then a refresh and retry, as intended; the
   stale base of attempt 4 came from a stale GET answer [read].
4. The H2 stand-in: confirmed.
5. Other lines: the fixture is shared by both Spark 3.5 lines, the Spark 4.2 line, the Spark integration tests and the
   compatibility kit, so any could hit it; the fix covers them all. Not production.

### Why production is judged unaffected, and what a dropped group reports

The real House Tables store is not H2 (inferred, not run), so the stale-read window does not exist there. If a group were
dropped anyway: on the 1.5.2 line the result lists only committed groups, `rewriteFailures` lists rewrite failures only,
and the job logs and emits the count of committed groups and succeeds. On the 1.12 line it also logs a WARN and
throws past `partial-progress.max-failed-commits` (default: `max-commits`) [read and ran]. The reported result is
consistent with what was committed.

### What changed, the numbers, and what was not verified

- Changed: a new `tables-test-fixtures-h2.properties` (random database name per context, `OPTIMIZE_REUSE_RESULTS=FALSE`),
  a `@PropertySource` on `SpringH2TestApplication`, a new fixture test, and two lines of existing code (read by the
  orchestrator). Not changed: `FormatV3MaintenanceTest` keeps its strict assertion; the job, client, commit path, retry
  cache, deployment arguments and Iceberg fork.
- `HouseTablesH2ReadAfterCommitTest` (3000 compare-and-set saves against three readers) fails on the old fixture every
  run: 602 old reads through the repository, 761334 through JDBC; on the fixed fixture both are 0 [ran]. Fixture tests on
  the 1.5.2 line: 33 passed. Full jobs suite there: 481 tests, 390 passed, 0 failed, 91 skipped, no read answers an older
  version than an earlier one [ran]. The pull request build was green on head `2519945f` on both lines (read by the
  orchestrator).
- Not verified: the reproduction opens H2's window by repetition, not by construction; production being unaffected is
  inferred; the tables service's own test copy of the stand-in still has H2's default and no other embedded H2 in the
  tests was checked (work item X18); post-merge CI for 147 and 148 had not been read when this was written.
