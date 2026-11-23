# Current state

Bottom line: the service is a nearly complete Iceberg REST Catalog server, Spark 4.2 works over it, Iceberg 1.12 is now
the default build, and the Spark image and dataloader checks now pass in CI (C10). Pull requests 121 (three service
follow-ups) and 124 (format v3) are merged; three pieces of work are in flight (X6, W7 with X4, and an investigation of
V11 and V12), described in "How to resume" below.
Nothing is merged into either fork's main or into LinkedIn's or Apache's repositories. Nothing is known red. This is the
single place for current state; other files record design, evidence and decisions. Dated 2026-11-23.

- Conforming IRC server: every route of the specification is served except scan planning and credential vending. View
  rename and namespace create, alter and drop are refused because OpenHouse has no such operations. Multi-table
  transactions are served for one table and refused for several. The compatibility kit passes 41 of 160; 96 fail and 23
  are skipped, almost all because the kit assumes behaviours OpenHouse deliberately does not have (see the kit result
  files, `39-v13-compat-kit-result.md` and `42-v13b-spec-deviations-result.md`). What "conforming" should mean beyond the
  wire contract is still the question put to Mike (D32).
- Spark SQL: on the target platform (Spark 4.2, Iceberg 1.12, IRC only) 166 of 177 smoke statements behave as on the
  Spark 3.5 baseline. The 11 differences are listed and are accepted or are Spark 4's own (D37,
  `50-n3b-spark42-smoke-result.md`, `52-c9-n6-result.md`).
- Iceberg 1.12: the default build since pull request 118 (V9e, `54-v9e-flip-result.md`). LinkedIn's 1.5.2 line is the
  reference, selected with `-PicebergLinkedIn`.
- Format v3: tables can be created and upgraded behind a switch that is off by default, and Spark's row-level writes
  work on them. Maintenance jobs, v3 types, replication and the defaults migration remain
  (`57-x1-format-v3-result.md`, D42, D43).
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
- Spark 4.2 over IRC passes the Spark SQL smoke suite (177 statements) and 18 write statements (inserts,
  overwrite, MERGE, UPDATE, DELETE, CTAS, RTAS, column and property changes, branch and write-audit-publish
  writes), all storing what they should (pull request 117). Renaming to a target that names the catalog works as
  on the baseline since N6 (pull request 119). 11 differences are declared and accepted (D37).
- Served over IRC now: config with the endpoint list (19 routes), namespace reads, table create, staged create,
  load, commit, rename, drop, register, metrics report, one-table transactions, views except rename, and the
  OpenHouse capability routes (policies, grants, unlock).
- Not served: scan planning (V11), credential vending and signing (V12), view rename (OpenHouse has none), and
  namespace create, alter and drop (OpenHouse refuses them natively too, D20).
- Not atomic: multi-table transactions. A transaction of one table commits; several tables are refused whole with
  400 (D39, pull request 120, `53-v8-multi-table-commit-result.md`). Real atomicity is put to Mike (D40).
- Format v3 tables are supported behind `cluster.iceberg.format-v3.enabled`, default off (pull request 124, D43).

## How far from the goal

- Conformance: Apache's compatibility kit passes 41 of 160 tests. Of the 119 that do not pass, 78 are OpenHouse's own
  rules or one known gap, 11 are routes not yet served, 22 are skipped by capability flags and 11 assert the reference
  server's messages (D32).
- Iceberg 1.12 as the default: done. The Iceberg 1.2 and Spark 3.1 projects are deleted. The Spark image and the
  dataloader tests pass on a CI runner (C10, pull request 122). Open: the default build cannot publish anywhere
  until Mike decides where the fork's Iceberg jars live.
- Format v3: X1 and W6 are done (pull request 124). v3 is off by default per cluster, chosen per table at create or
  by explicit upgrade, never implied, with no downgrade (D42, D43).

## Merged pull requests

45 pull requests are merged into `irc-integration` (the last is 121, merge commit 72947228) and 10 into the Iceberg
fork's `openhouse-1.12.0` (the last is 12, commit a88f556c). The full tables are in `45-merged-pull-requests.md`.
The latest: 121 (V15, V3b and V3c, `59-v15-v3b-v3c-result.md`); 124 (X1 and W6, `57-x1-format-v3-result.md`); 122 (C10) and 123 (C14), both in `55-c10-c14-result.md`; 118 (V9e, in `54-v9e-flip-result.md`) and 120
(V8, in `53-v8-multi-table-commit-result.md`). The first post-merge run of the flipped layout (head 440249d1, run
45202558910) was green on both jobs: 25 minutes for the default line with Docker steps, 23 for the reference line.
Nothing is merged into either fork's main or into LinkedIn's repositories.

## Done

P1, P2, I1 to I5, I8, I6a to I6c, I6g, I6h, T1 to T5, T2b, C1, V1, V2, V3, V4, V5-0, V5-gap, V5-1, V5-2, V5-3, V10a,
C2-1, C2-2, C2-3, N3-x1, N3-x2, N3a, N3b, N3, N6, T10, C9 (green parts), C5, I6f, V13a, V13b, I11, V9a, V9b, V9c, V9e, C6, T9, V6, V7, V8, V9d, V16, T13, C10, C14, X1, W6, V15, V3b, V3c, W1 to W4, W12, I10, the two CI fix-forwards of pull requests 107 and 108, and the X0 design. The meaning of each item is in `04-work.md`.

## How to resume

Bottom line: pull requests 121 and 124 are merged, and `irc-integration` is at `72947228`. Three pieces of work were in
flight as of 2026-11-23 about 00:10 UTC on the 24th; none has reported. If the session stops, a future session reads
each record below and the branch's log, and resumes from what they say.

- Usage limit: subagents on the default model are unavailable until 2026-11-30 09:00 UTC. Code agents run on the next
  model down meanwhile.
- X6, maintenance jobs on format v3 tables: worktree `/home/user/oh-x6`, branch `irc/x6-maintenance-v3`, record
  `x6-maintenance-v3.md` in the reports directory.
- W7 with X4, Spark reads old files through native default values on v3: worktree `/home/user/oh-w7`, branch
  `irc/w7-x4-v3-default-reads`, record `w7-x4-v3-default-reads.md`.
- An investigation of V11 (IRC scan planning) and V12 (IRC credential vending), read-only, no branch: record
  `v11-v12-investigation.md`.
- The reports directory is `/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/reports`.

What comes next, in order, after these:

1. The rest of format v3: the wider X2 and X3 scenarios, W8, and X5 and X7 (refused today; X7 investigated first).
2. IRC scan planning (V11) and credential vending (V12), built from the investigation.
3. N1-c and N7: the Spark 3.5 smoke suite on the plain REST path, and one shared list of scenarios.
4. The small follow-ups: V17 to V20, C11 to C13, C15, T8, T11, C7, C8. The order of the large work is D29.

## Known red on `irc-integration`

Nothing known red at this time. Both builds passed in CI on pull requests 113, 118, 122 and 123, and the post-merge run
of 118 was green on both jobs. Unexplained and not red: the Spark 4.2 `test` task failed once in six runs, and
`SparkIrcEndpointsTest.existenceChecksAreHeadRequests` failed once on reading the access log early (T8, open until a CI
failure names its test). The build is not reproducible bit for bit (C15, `21-findings-continued.md`). Open product
defects found by pull request 121: V19 (rename to 129 to 255 characters) and V20 (FileIO of a table on a
non-default storage type).

## How work is tested

Post-merge results are read after every merge, not assumed (D33). Under D18, before a merge an agent runs the test classes it touched plus the black-box suite of the module it
changed, and Claude merges on that without waiting for the pull request's CI. The full build runs on
`irc-integration` after each merge and a failure is fixed forward, so work that starts from the branch checks the
known-red list above first. Heavy runs, such as the full delta-harness and the full Iceberg Spark suites, run after
merges when memory is free. An item whose claim is "no behavior change" runs the full suite before merge. D26 adds the cheap checks every brief
now requires, after the red build (`21-findings-continued.md`). D33 tightens it: a suite counts as passing under
the 1.12 line only if it ran there.

## Waiting on Mike

Nothing blocks work. The list is in `58-waiting-on-mike.md`: the meaning of "conforming" (D32), the one-way doors
(D12, D24, D34, D38, D40), the native directory-reuse fix (D35), the Spark 4.2 differences (D37), and, new, turning
the v3 switch on for a real cluster and upgrading a real table. An upgrade is irreversible and LinkedIn 1.5.2 clients
cannot load the table afterwards.

## How to audit

- The merge commits on the two integration branches carry the why for each work item and each commit.
- Decisions are in `13-decisions.md`, `16-decisions-continued.md`, `22-decisions-continued-2.md` and
  `37-decisions-continued-3.md` (D30 to D36) and `51-decisions-continued-4.md` (D37 to D42, with dated addenda) and `56-decisions-continued-5.md` (D43).
- Findings are in `08-findings.md` and the numbered result files listed in `README.md`.
- Only `01-stated.md` carries Mike's authority.
