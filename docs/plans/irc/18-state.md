# Current state

Bottom line: the service is a nearly complete Iceberg REST Catalog server, Spark 4.2 works over it, and the maintenance
jobs pass their whole suite on Spark 4.2 over it. Iceberg 1.12 is the default build, and the Spark image and dataloader
checks pass in CI. Pull requests 121 and 124 to 145 are merged (145 is X11, X12 and X13, sort stats collection on the Iceberg 1.5.2 line
fixed and two suspected defects found harmless; 144 is C20, the post-merge build run as the pull request's split; 143 is
C19). Two pieces of work are in flight: a compaction conflict found by 145 (X16), and C3 with C4. View rename stopped on
D47. Nothing is merged into either fork's main or into LinkedIn's or Apache's repositories. Nothing is known red;
post-merge CI for every merge up to and including 144 was read and passed, and for 145 it is not read yet. An audit of
the open work items found 8 of 62 already true and 8 partly done (`74-work-table-audit.md`; 14 of the open items had
closed by 2026-11-24 and four more since). This is the single place for current state. Dated 2026-11-25.

- Conforming IRC server: every route is served except the stored-plan routes, credential vending and signing, view
  rename, and namespace create, alter and drop (OpenHouse has no such operations). The compatibility kit passes 42 of
  160, almost all failures being kit assumptions OpenHouse deliberately does not meet (`39-v13-compat-kit-result.md`,
  `42-v13b-spec-deviations-result.md`). What "conforming" should mean is still put to Mike (D32).
- Spark SQL: on the target platform (Spark 4.2, Iceberg 1.12, IRC only) 199 of 210 smoke statements behave as on the
  Spark 3.5 baseline [CI]. The 11 differences are listed and are accepted or are Spark 4's own (D37,
  `50-n3b-spark42-smoke-result.md`, `52-c9-n6-result.md`). On Spark 3.5 with Iceberg's plain REST catalog 201 of 210
  behave as the baseline on both Iceberg lines; the 9 differences are declared, none in rows read or in what a write
  stores. The OpenHouse catalog and the REST-shaped shim give 208 of 210 on 1.12 and 210 of 210 on 1.5.2 [ran]
  (`64-n1c-n7-result.md`, `77-t6-n10-result.md`). Grants are listed after GRANT and gone after REVOKE on every path.
- Maintenance jobs: they pass their whole suite on Spark 4.2 over the REST catalog (395 tests, 0 failed, 5 skipped); the
  Spark 3.5 jobs are unchanged. Deploying them is Mike's (N12 to N15) (`73-n11-jobs-spark42-result.md`) [ran].
- Iceberg 1.12: the default build since pull request 118 (V9e, `54-v9e-flip-result.md`). LinkedIn's 1.5.2 line is the
  reference, selected with `-PicebergLinkedIn`.
- Format v3: tables can be created and upgraded behind a switch that is off by default, and Spark's row-level writes
  work on them. Spark 3.5 and 4.2 read old files through native defaults (`61-w7-x4-result.md`). Every maintenance job
  runs on a v3 table without losing live data (`63-x6-maintenance-v3-result.md`), and row-level writes racing the jobs
  lose none either (`65-x10-concurrency-result.md`), nor do tables with branches, evolved specs or equality deletes
  (`76-x10-shapes-result.md`); the retention backup's handling of deletes is put to Mike (D46).
  Column types are decided type by type (`68-x5-v3-types-result.md`). Replication and the defaults migration remain
  (`57-x1-format-v3-result.md`).
- One data-loss risk remains, natively only: a create naming a renamed-away table's directory is closed over IRC and
  pinned natively, and the fix is put to Mike (D35).

## What works today

- The native API and Spark 3.5 with the OpenHouse catalog are unchanged.
- Over the Iceberg REST Catalog, an unmodified Iceberg client and Spark 3.5 can read and write tables and views
  (`27-v3-irc-write-result.md`, `31-v4-irc-views-result.md`). Views cannot be renamed.
- Every OpenHouse statement (policies, column tags, grants, unlock) works through an unmodified Iceberg `RESTCatalog` on
  Spark 3.5 and on Spark 4.2, where the engine holds no OpenHouse catalog or client class
  (`33-v5-policies-over-irc-result.md`, `35-n3x1-spark42-extensions-result.md`, `36-v5-3-grants-unlock-endpoints-result.md`,
  `38-c2-3-rest-capability-client-result.md`). Failures keep the native message but arrive as Iceberg exception types.
- An IRC create can no longer be given another table's directory: a completion naming a directory in use is refused
  with 400, and `openhouse.tableUUID` in a create is ignored (D36, `48-v16-directory-reuse-result.md`).
- Spark 4.2 over IRC passes the Spark SQL smoke suite (210 statements) and 19 write statements, all storing what they
  should (pull request 117, 140); renaming to a target that names the catalog works since N6 (pull request 119).
- Served over IRC now: config with the endpoint list, namespace reads, table create, staged create, load, commit,
  rename, drop, register, metrics report, one-table transactions, views except rename, and the OpenHouse capability
  routes (policies, grants, unlock). On the Iceberg 1.12 line also the synchronous plan route and the credentials
  route, which answers an empty list (pull request 127, `62-v11a-v12a-result.md`); the 1.5.2 line answers 406 for both.
- Not atomic: multi-table transactions. A transaction of one table commits; several tables are refused whole with
  400 (D39, pull request 120, `53-v8-multi-table-commit-result.md`). Real atomicity is put to Mike (D40).

## How far from the goal

- Maintenance jobs: proven on format v3 tables in every shape examined: plain, under concurrent row-level writes, with
  the new column types, with branches and tags, with evolved partition specs and with equality deletes, on Spark 3.5
  and on Spark 4.2 over the REST routes (`63-`, `65-`, `68-` and `76-` result files) [ran]. Not covered: a job run
  against a branch, a table built by a replace, HDFS and object stores.
- Conformance: of the kit's 95 failures, 76 are OpenHouse's own rules, 9 are routes not served and 10 are kit
  assumptions (D32; `60-v11-v12-investigation-result.md`).
- Iceberg 1.12 as the default: done, and the 1.2 and Spark 3.1 projects are deleted. Open: the default build cannot
  publish anywhere until Mike decides where the fork's Iceberg jars live.

## Merged pull requests

66 pull requests are merged into `irc-integration` (the last is 145, merge commit 9dfbbca7) and 10 into the Iceberg
fork's `openhouse-1.12.0` (the last is 12, commit a88f556c). The full tables are in `45-merged-pull-requests.md`.
The latest: 145 and 144 in `79-c20-x11-x12-x13-result.md`; 143, 142 and 141 in `78-v19-v20-v22-n16-x9-result.md`; 140 in `77-t6-n10-result.md`.

## Done

P1, P2, I1 to I5, I8, I6e, N1, N2, C8, C12, T12, I6a to I6c, I6g, I6h, T1 to T5, T2b, C1, V1, V2, V3, V4, V5-0, V5-gap, V5-1, V5-2, V5-3, V10a,
C2-1, C2-2, C2-3, N8, V18, C15, T6, N10, V19, V20, V22, N16, X9, C19, C20, X11, X12, X13, N3-x1, N3-x2, N3a, N3b, N3, N6, T10, C9 (green parts), C5, I6f, V13a, V13b, I11, V9a, V9b, V9c, V9e, C6, T9, V6, V7, V8, V9d, V16, T13, C10, C14, X1, W6, W7, X4, X5, X6, V15, V3b, V3c, V11a, V11b, V11c, V11e, V12a, V14, C16, N11 (landable part), N1-c, N7, W1 to W4, W12, I10, the two CI fix-forwards of pull requests 107 and 108, and the X0 design. The meaning of each item is in `04-work.md`.

## How to resume

Bottom line: pull requests 121 and 124 to 145 are merged, and `irc-integration` is at `9dfbbca7`. Two pieces of
work are in flight (below); view rename stopped on D47 with no branch pushed. If the session stops, a future session
reads each record below and the branch's log, and resumes from what they say.

- Usage limit: subagents on the default model are unavailable until 2026-11-30 09:00 UTC; code agents use the next
  model down.
- In flight, X16: on the 1.5.2 line one of four compaction group commits got a 409 from the tables service after
  retries. Do the group commits conflict with each other, and does a production compaction see it? Worktree
  `/home/user/oh-flake`, branch `irc/compaction-partial-progress-409`, record `compaction-partial-progress-409.md`.
- In flight, C3 and C4 together (the REST-shaped path: a gated replace-as-select writes no data file before the server
  rejects it; property and policy commits from a stale handle fail as on the legacy path). Worktree `/home/user/oh-c34`,
  branch `irc/c3-c4-rest-shaped-path`, record `c3-c4-rest-shaped-path.md` in the reports directory.
- View rename (V21) stopped on D47: House Tables has no atomic view rename, and a new House Tables API is Mike's.
- Post-merge CI [read 2026-11-25]: passed for the merge of 143 (the last run through the old single jobs). The first run
  through the new split path, for the merge of 144, passed: 13 jobs green and one skipped by design, 31 minutes from
  merge to last job; the two `rest` jobs each saved a Gradle home cache entry keyed by the merge commit and another job
  checked reported a read-only cache. A pull request restoring that entry has not been looked at yet. Not read yet for
  145: read it first (D33).
- Audit of the open work items: `74-work-table-audit.md` (62 open at the time; 14 closed by 2026-11-24 and four since).
  Read it before launching any small item.
- The container has restarted twice (2026-11-24 and 2026-11-25) with nothing lost, because agents push after every commit
  and keep a record file. The second restart cleared the orphaned test processes that held about 3 GB of memory.
- The jobs test heap hazard is resolved by pull request 143 (about 728 MB of margin under 1 GB, computed); a future author
  should watch the heap after a full collection at the end of the suite and how many `Operations` a fixture builds.
- The reports directory is `/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/reports`.

What comes next, after the work in flight:
1. The rest of format v3: the wider X2 and X3 scenarios, W8, X7 (refused today; investigated first), X14 (needs Mike's say) and X17 (the job configuration's missing catalog, Mike's), X8 once D46 is decided, and the work after N16 (N17, Mike's); N12 to N15 are Mike's.
2. V11d and V12b to V12e are Mike's; see `41-work-platforms-v3.md`.
3. The small follow-ups: V17, C11, C13, C17 (first lead from C15), C18, T8, T11, C7. The order of the large work is D29.

## Known red on `irc-integration`

Nothing known red at this time. Post-merge CI for every merge up to and including 144 was read and every job passed;
that of 145 is not read yet. Unexplained and not red: the Spark 4.2 `test` task failed once in six runs, and
`SparkIrcEndpointsTest.existenceChecksAreHeadRequests` failed once on reading the access log early (T8, open until a CI
failure names its test). V19 and V20, found by pull request 121, are fixed (pull request 142).

## How CI runs

A pull request's CI is the split build from C16 (`71-c16-ci-split-result.md`): parallel jobs, about 22 minutes, with
check names like `build-run-tests / Default build, apps-spark-3.5`, running the same tests as before. Since pull request
135 it has a job for the Spark 4.2 jobs project, `Default build, apps-spark-4.2`, about 17 minutes. Since pull request
144 (C20) the build after a merge is the same split on both lines plus the Docker steps, about 31 to 34 minutes from merge
to answer, and runs for different merges overlap; only the `rest` job of each line writes the Gradle cache. The old
single jobs remain only for the release workflow (`79-c20-x11-x12-x13-result.md`). Since pull request 138 the
print-failed-tests step also reads the build's console log, so a dead test process still lists its failures (not yet
seen on a red job).

## How work is tested

Post-merge results are read after every merge, not assumed (D33). Under D18, before a merge an agent runs the test classes it touched plus the black-box suite of the module it
changed, and Claude merges on that without waiting for the pull request's CI. The full build runs on
`irc-integration` after each merge and a failure is fixed forward, so work that starts from the branch checks the
known-red list above first. An item whose claim is "no behavior change" runs the full suite before merge. D26 adds the cheap checks every brief
now requires (`21-findings-continued.md`); D33: a suite counts only if it ran on the 1.12 line.

## Waiting on Mike

Nothing blocks work. The list is in `58-waiting-on-mike.md`: the meaning of "conforming" (D32), whether the server turns on server-side scan planning (V11d), credential vending (V12b), the one-way doors
(D12, D24, D34, D38, D40), view rename and the House Tables API (D47), the native directory-reuse fix (D35), the Spark 4.2 differences (D37), and, new, turning
the v3 switch on for a real cluster and upgrading a real table, and the retention backup's handling of deletes (D46).
Three questions from X5's column types are there too, and X14 (an age floor in orphan removal) needs his say; nothing waits on them.

## How to audit

- Decisions are in `13-decisions.md`, `16-decisions-continued.md`, `22-decisions-continued-2.md` and
  `37-decisions-continued-3.md` (D30 to D36) and `51-decisions-continued-4.md` (D37 to D42, with dated addenda) and `56-decisions-continued-5.md` (D43 to D47) and `67-decisions-continued-6.md` (D48 to D50).
- Findings are in `08-findings.md` and the numbered result files listed in `README.md`.
- Only `01-stated.md` carries Mike's authority.
