# Current state

Bottom line: the service is a nearly complete Iceberg REST Catalog server, Spark 4.2 works over it, Iceberg 1.12 is now
the default build, and format v3 has started. Nothing is merged into either fork's main or into LinkedIn's or Apache's
repositories. Nothing is known red. This is the single place for current state; other files record design, evidence and
decisions. Dated 2026-11-23.

- Conforming IRC server: every route of the specification is served except scan planning and credential vending. View
  rename and namespace create, alter and drop are refused because OpenHouse has no such operations. Multi-table
  transactions are served for one table and refused for several. The compatibility kit passes 39 of 160; 98 fail and 23
  are skipped, almost all because the kit assumes behaviours OpenHouse deliberately does not have (see the kit result
  files, `39-v13-compat-kit-result.md` and `42-v13b-spec-deviations-result.md`). What "conforming" should mean beyond the
  wire contract is still the question put to Mike (D32).
- Spark SQL: on the target platform (Spark 4.2, Iceberg 1.12, IRC only) 166 of 177 smoke statements behave as on the
  Spark 3.5 baseline. The 11 differences are listed and are accepted or are Spark 4's own (D37,
  `50-n3b-spark42-smoke-result.md`, `52-c9-n6-result.md`).
- Iceberg 1.12: the default build since pull request 118 (V9e, `54-v9e-flip-result.md`). LinkedIn's 1.5.2 line is the
  reference, selected with `-PicebergLinkedIn`.
- Format v3: started (X1 with W6, D42); nothing merged yet.
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
- Format v3 tables are not yet supported by the service; the work is running (D42).

## How far from the goal

- Conformance: Apache's compatibility kit passes 39 of 160 tests. Of the 121 that do not pass, 78 are OpenHouse's own
  rules or one known gap, 11 are routes not yet served, 22 are skipped by capability flags and 11 assert the reference
  server's messages (D32).
- Iceberg 1.12 as the default: done. The Iceberg 1.2 and Spark 3.1 projects are deleted. Open: the Spark image and the
  dataloader tests have never finished on a CI runner on this line (C10), and the default build cannot publish
  anywhere until Mike decides where the fork's Iceberg jars live.
- Format v3: X1 and W6 are running on branch `irc/x1-format-v3`. v3 is off by default per cluster, chosen per table at
  create or by explicit upgrade, never implied, with no downgrade (D42).

## Merged pull requests

41 pull requests are merged into `irc-integration` (the last is 118, merge commit 440249d1) and 10 into the Iceberg
fork's `openhouse-1.12.0` (the last is 12, commit a88f556c). The full tables are in `45-merged-pull-requests.md`.
The latest four: 114 (C9 in part) and 119 (N6), both in `52-c9-n6-result.md`, 120 (V8, in
`53-v8-multi-table-commit-result.md`) and 118 (V9e, in `54-v9e-flip-result.md`).
Nothing is merged into either fork's main or into LinkedIn's repositories.

## Done

P1, P2, I1 to I5, I8, I6a to I6c, I6g, I6h, T1 to T5, T2b, C1, V1, V2, V3, V4, V5-0, V5-gap, V5-1, V5-2, V5-3, V10a,
C2-1, C2-2, C2-3, N3-x1, N3-x2, N3a, N3b, N3, N6, T10, C9 (green parts), C5, I6f, V13a, V13b, I11, V9a, V9b, V9c, V9e, C6, T9, V6, V7, V8, V9d, V16, T13, W1 to W4, W12, I10, the two CI fix-forwards of pull requests 107 and 108, and the X0 design. The meaning of each item is in `04-work.md`.

## In progress (delegated)

- X1 with W6 (format v3 tables in the service, D42), branch `irc/x1-format-v3`.
- A bundle of three small service items on branch `irc/v15-v3b-v3c-service-followups`: V15 (a create whose
  metadata location will not fit House Tables' 255 characters is refused with 400 before anything is written, on
  both APIs, D41), V3b (the IRC config route's FileIO default follows the cluster's storage) and V3c (failed IRC
  requests appear in the service audit like native ones).

## Next

C10 is about to start (the Spark image and dataloader tests in CI). Then the rest of format v3 (X2 to X7). New from V9e: V18, C11, C12 and C13. N5 is pinned and waits for Mike (D35). Other small items: C7, C8, T12, V17, and T11 (real S3, needs an environment with S3). N7 (one list of smoke scenarios for Spark 3.5 and 4.2) can start now. V11 and V12 can go alongside. The order of the large work is D29.

## Known red on `irc-integration`

Nothing known red at this time. Post-merge runs for pull requests 109 and 110 passed on both builds, and both builds
passed in CI on pull request 113. Pull request 116's own CI was not waited for, nor was pull request 119's. Pull requests
now run both builds (C6). The post-merge 1.12 job for pull request 111 took under 4 minutes because Gradle reused
test results from the previous run on identical inputs; no step was skipped. Unexplained and not red: the Spark 4.2
`test` task failed once in six runs (T8). `SparkIrcEndpointsTest.existenceChecksAreHeadRequests` failed once on reading
the access log early; pull request 112 made it wait for the last line and that may be T8, which stays open until a
CI failure names its test. And the build is not reproducible bit
for bit (`21-findings-continued.md`). Open product defect: V15, being fixed (D41). Pull request 118's own CI was green on both jobs.

## How work is tested

Post-merge results are read after every merge, not assumed (D33). Under D18, before a merge an agent runs the test classes it touched plus the black-box suite of the module it
changed, and Claude merges on that without waiting for the pull request's CI. The full build runs on
`irc-integration` after each merge and a failure is fixed forward, so work that starts from the branch checks the
known-red list above first. Heavy runs, such as the full delta-harness and the full Iceberg Spark suites, run after
merges when memory is free. An item whose claim is "no behavior change" runs the full suite before merge. D26 adds the cheap checks every brief
now requires, after the red build (`21-findings-continued.md`). D33 tightens it: a suite counts as passing under
the 1.12 line only if it ran there.

## Waiting on Mike

- D32: what "conforming Iceberg REST Catalog server" means.
- The one-way doors in D12 and D24, before any release outside the forks. D34 adds one: the register and metrics
  route semantics become a public contract at release. It does not block work.
- D35: whether to fix the native directory-reuse defect (N5), because the fix changes native behavior. Four native
  requests can still delete a renamed table's files. It does not block work.
- A veto, if wanted, of the accepted Spark 4.2 differences in D37 (list in `50-n3b-spark42-smoke-result.md`). They
  become user-visible behaviour at release. It does not block work.
- Publishing the fork's Iceberg jars anywhere is a one-way door, and the 1.12 default makes it needed before any
  release. C13, the release workflow's order of tag and publish, belongs with it. It does not block work (D38 addendum).
- D40: whether to build real atomic multi-table commit. It needs a new House Tables endpoint, a regenerated
  client and a split of the commit core, and is a one-way door. No engine sends the route, so leaving it as built
  is a reasonable choice. It does not block work.
- Worker sessions on separate machines, because a bigger container is not available (D21). The diagnosis is in
  `20-iteration-friction.md`.

Nothing blocks work.

## How to audit

- The merge commits on the two integration branches carry the why for each work item and each commit.
- Decisions are in `13-decisions.md`, `16-decisions-continued.md`, `22-decisions-continued-2.md` and
  `37-decisions-continued-3.md` (D30 to D36) and `51-decisions-continued-4.md` (D37 to D42, with dated addenda).
- Findings are in `08-findings.md` and the numbered result files listed in `README.md`.
- Only `01-stated.md` carries Mike's authority.
