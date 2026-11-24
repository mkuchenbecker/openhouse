# Current state

Bottom line: the service is a nearly complete Iceberg REST Catalog server, Spark 4.2 works over it, and the maintenance
jobs pass their whole suite on Spark 4.2 over it. Iceberg 1.12 is the default build, and the Spark image and dataloader
checks pass in CI. Pull requests 121 and 124 to 136 are merged (136 is C12, UTF-8 sources; 135 is N11; 134 is V14). One
piece of work is in flight: the rest of X10. View rename stopped on D47. Nothing is merged into either fork's main or
into LinkedIn's or Apache's repositories. Nothing is known red; post-merge CI for 132 and 133 was read and passed, and
for 134, 135 and 136 it is not read yet. An audit of the open work items found 8 of 62 already true and 8 partly done
(`74-work-table-audit.md`). This is the single place for current state. Dated 2026-11-24.

- Conforming IRC server: every route of the specification is served except the stored-plan routes and credential vending and
  signing. View rename and namespace create, alter and drop are refused because OpenHouse has no such operations. Multi-table
  transactions are served for one table and refused for several. The compatibility kit passes 42 of 160; 95 fail and 23
  are skipped, almost all because the kit assumes behaviours OpenHouse deliberately does not have (see the kit result
  files, `39-v13-compat-kit-result.md` and `42-v13b-spec-deviations-result.md`). What "conforming" should mean beyond the
  wire contract is still the question put to Mike (D32).
- Spark SQL: on the target platform (Spark 4.2, Iceberg 1.12, IRC only) 166 of 177 smoke statements behave as on the
  Spark 3.5 baseline. The 11 differences are listed and are accepted or are Spark 4's own (D37,
  `50-n3b-spark42-smoke-result.md`, `52-c9-n6-result.md`). On Spark 3.5 with Iceberg's plain REST catalog (1.12 line)
  168 of 177 behave as the baseline; the 9 differences are declared, none in rows read or in what a write stores
  (`64-n1c-n7-result.md`) [ran].
- Maintenance jobs: they compile unchanged on Spark 4.2 and pass their whole suite there with the REST catalog as the
  only catalog (395 tests, 0 failed, 5 skipped); the Spark 3.5 jobs are unchanged. Compaction of a `variant` table works
  there. Deploying them is Mike's (N12 to N15) (`73-n11-jobs-spark42-result.md`, pull request 135) [ran].
- Iceberg 1.12: the default build since pull request 118 (V9e, `54-v9e-flip-result.md`). LinkedIn's 1.5.2 line is the
  reference, selected with `-PicebergLinkedIn`.
- Format v3: tables can be created and upgraded behind a switch that is off by default, and Spark's row-level writes
  work on them. Spark 3.5 and 4.2 read old files through native defaults (`61-w7-x4-result.md`). Every maintenance job
  runs on a v3 table without losing live data (`63-x6-maintenance-v3-result.md`), and row-level writes racing the jobs lose
  none either (`65-x10-concurrency-result.md`); the retention backup's handling of deletes is put to Mike (D46). Column types are decided type by type (`68-x5-v3-types-result.md`). Replication and the defaults migration remain (`57-x1-format-v3-result.md`).
- One data-loss risk remains, natively only: a create naming a renamed-away table's directory is closed over IRC and
  pinned natively, and the fix is put to Mike (D35).

## What works today

- The native API and Spark 3.5 with the OpenHouse catalog are unchanged.
- Over the Iceberg REST Catalog, an unmodified Iceberg client and Spark 3.5 can read and write tables and views
  (`27-v3-irc-write-result.md`, `31-v4-irc-views-result.md`). Views cannot be renamed.
- Every OpenHouse statement (policies, column tags, grants, unlock) now works through an unmodified Iceberg
  `RESTCatalog` on Spark 3.5, and on Spark 4.2 with the fork's Iceberg 1.12, where the
  engine holds no OpenHouse catalog or client class (`33-v5-policies-over-irc-result.md`,
  `35-n3x1-spark42-extensions-result.md`, `36-v5-3-grants-unlock-endpoints-result.md`,
  `38-c2-3-rest-capability-client-result.md`). Failures keep the native message but arrive as Iceberg exception types.
- The server tells newer clients exactly which routes it serves, so they use HEAD checks and views and do not send
  routes that are not served.
- An IRC create ignores a location the client names and uses the server's (D30). A rename across databases answers
  404 or 406, a create asking for a format version other than the cluster's answers 400, and a rename onto a view's
  name answers 409 (`42-v13b-spec-deviations-result.md`).
- Metrics reports and register table are served (pull request 112, D34).
- An IRC create can no longer be given another table's directory: a completion naming a directory in use is refused
  with 400, and `openhouse.tableUUID` in a create is ignored (D36, `48-v16-directory-reuse-result.md`).
- Spark 4.2 over IRC passes the Spark SQL smoke suite (177 statements) and 18 write statements, all storing what they
  should (pull request 117); renaming to a target that names the catalog works since N6 (pull request 119).
- Served over IRC now: config with the endpoint list, namespace reads, table create, staged create, load, commit,
  rename, drop, register, metrics report, one-table transactions, views except rename, and the OpenHouse capability
  routes (policies, grants, unlock). On the Iceberg 1.12 line also the synchronous plan route and the credentials
  route, which answers an empty list (pull request 127, `62-v11a-v12a-result.md`); the 1.5.2 line answers 406 for both.
- Not served: the stored-plan routes (GET and DELETE plan/{id}, POST tasks), real credential vending and signing
  (Mike's), view rename (OpenHouse has none), and namespace create, alter and drop (OpenHouse refuses them natively
  too, D20).
- Not atomic: multi-table transactions. A transaction of one table commits; several tables are refused whole with
  400 (D39, pull request 120, `53-v8-multi-table-commit-result.md`). Real atomicity is put to Mike (D40).

## How far from the goal

- Conformance: Apache's compatibility kit passes 42 of 160 tests; 95 fail and 23 are skipped (22 capability flags, one
  kit `@Disabled`). By class, 76 of the 95 are OpenHouse's own rules, 9 are routes not served and 10 are kit
  assumptions (D32). By cause: namespace has no independent existence 26, namespace create, alter and drop not served 7,
  view rename not served 2, view policy 15, table replace needs `replace.enabled` 16, schema and partition evolution
  rules 13, other model differences 6, the kit asserting the reference server's messages 9, the kit's own
  contradiction 1. None is about scan planning or credentials (`60-v11-v12-investigation-result.md`).
- Iceberg 1.12 as the default: done. The Iceberg 1.2 and Spark 3.1 projects are deleted. The Spark image and the
  dataloader tests pass on a CI runner (C10, pull request 122). Open: the default build cannot publish anywhere
  until Mike decides where the fork's Iceberg jars live.

## Merged pull requests

57 pull requests are merged into `irc-integration` (the last is 136, merge commit 4b696a55) and 10 into the Iceberg
fork's `openhouse-1.12.0` (the last is 12, commit a88f556c). The full tables are in `45-merged-pull-requests.md`.
The latest: 136 (C12); 135 (N11, `73-n11-jobs-spark42-result.md`); 134 (V14, `72-v14-create-history-result.md`).

## Done

P1, P2, I1 to I5, I8, I6e, N1, N2, C8, C12, T12, I6a to I6c, I6g, I6h, T1 to T5, T2b, C1, V1, V2, V3, V4, V5-0, V5-gap, V5-1, V5-2, V5-3, V10a,
C2-1, C2-2, C2-3, N3-x1, N3-x2, N3a, N3b, N3, N6, T10, C9 (green parts), C5, I6f, V13a, V13b, I11, V9a, V9b, V9c, V9e, C6, T9, V6, V7, V8, V9d, V16, T13, C10, C14, X1, W6, W7, X4, X5, X6, V15, V3b, V3c, V11a, V11b, V11c, V11e, V12a, V14, C16, N11 (landable part), N1-c, N7, W1 to W4, W12, I10, the two CI fix-forwards of pull requests 107 and 108, and the X0 design. The meaning of each item is in `04-work.md`.

## How to resume

Bottom line: pull requests 121 and 124 to 136 are merged, and `irc-integration` is at `4b696a55`. One piece of work is
in flight (below); view rename stopped on D47 with no branch pushed. If the session stops, a future session reads each
record below and the branch's log, and resumes from what they say.

- Usage limit: subagents on the default model are unavailable until 2026-11-30 09:00 UTC; code agents use the next
  model down.
- The rest of X10 (maintenance on format v3 tables with branches and tags, evolved partition specs and equality
  deletes): worktree `/home/user/oh-x10b`, branch `irc/x10b-v3-maintenance-branches-evolution`, record
  `x10b-v3-maintenance-branches-evolution.md` in the reports directory.
- View rename (V21) stopped on D47: House Tables has no atomic view rename, and a new House Tables API is Mike's.
- Post-merge CI for 134, 135 and 136 has not been read yet; read all three first (D33). 135's branch was merged with the
  base before 134 landed, so its run is the first on the combined tree.
- Audit of the open work items: `74-work-table-audit.md` (62 open: 8 already true, 8 partly done, 16 waiting on Mike, 2
  cannot be settled by reading, 28 still open). Read it before launching any small item.
- Hazard: orphaned test processes from a finished agent (a Gradle test worker and three test service processes of the
  format v3 types work, started about 07:10 UTC on 2026-11-24) hold about 3 GB of the machine's 15 GB. The orchestrator's
  attempt to stop them was denied by the permission system and Mike was told; builds have less headroom until they are
  gone or the container restarts.
- The reports directory is `/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/reports`.

What comes next, after the work in flight:
1. The rest of format v3: the wider X2 and X3 scenarios, W8, X7 (refused today; investigated first), X9 to X15 from X6, X10 and V14 (X14 needs Mike's say), X8 once D46 is decided, and N16 (one catalog per job run); N12 to N15 are Mike's.
2. V11d and V12b to V12e are Mike's; see `41-work-platforms-v3.md`.
3. The small follow-ups: V17 to V20, C11, C13, C15, C17, C18, T8, T11, C7. The order of the large work is D29.

## Known red on `irc-integration`

Nothing known red at this time. Post-merge CI for pull requests 121 and 124 to 133 was read on 2026-11-24 and every job passed; 134's, 135's and 136's are not read yet. For 133 the reshaped Docker step ran and passed in the post-merge job. Unexplained and not red: the Spark 4.2 `test` task failed once in six runs, and
`SparkIrcEndpointsTest.existenceChecksAreHeadRequests` failed once on reading the access log early (T8, open until a CI
failure names its test). The build is not reproducible bit for bit (C15, `21-findings-continued.md`). Open product
defects found by pull request 121: V19 (rename to 129 to 255 characters) and V20 (FileIO of a table on a
non-default storage type).

## How CI runs

A pull request's CI is the split build from C16 (`71-c16-ci-split-result.md`): parallel jobs, about 22 minutes, with
check names like `build-run-tests / Default build, apps-spark-3.5`, running the same tests as before. Since pull request
135 it has a job for the Spark 4.2 jobs project, `Default build, apps-spark-4.2`, about 17 minutes. Post-merge runs
are still the two single jobs (default and reference line); they run everything and write the Gradle cache.

## How work is tested

Post-merge results are read after every merge, not assumed (D33). Under D18, before a merge an agent runs the test classes it touched plus the black-box suite of the module it
changed, and Claude merges on that without waiting for the pull request's CI. The full build runs on
`irc-integration` after each merge and a failure is fixed forward, so work that starts from the branch checks the
known-red list above first. Heavy runs, such as the full delta-harness and the full Iceberg Spark suites, run after
merges when memory is free. An item whose claim is "no behavior change" runs the full suite before merge. D26 adds the cheap checks every brief
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
