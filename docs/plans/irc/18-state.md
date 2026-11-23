# Current state

Bottom line: all work is on two integration branches in Mike's forks, nothing is merged into either fork's main or
LinkedIn's repositories, and nothing is known red: post-merge runs for pull requests 109 and 110 passed on both builds.
Of the five items needed to make 1.12 the default (V9a to V9e), four are done (V9a to V9d); V9e waits on T10 and C9. V6, V7, V16, N5
and T13 are done. One data-loss risk remains, natively only: a create naming a renamed-away table's directory is
closed over IRC and pinned natively, and the fix is put to Mike (D35). This is the single place for current state; other files record
design, evidence and decisions. Dated 2026-11-23.

## What works today

- The native API and Spark 3.5 with the OpenHouse catalog are unchanged.
- Over the Iceberg REST Catalog, an unmodified Iceberg client and Spark 3.5 can read and write tables and views
  (`27-v3-irc-write-result.md`, `31-v4-irc-views-result.md`). Views cannot be renamed.
- Every OpenHouse statement (policies, column tags, grants, unlock) now works through an unmodified Iceberg
  `RESTCatalog` on Spark 3.5, and on Spark 4.2 with the fork's Iceberg 1.12 under the opt-in build, where the
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
- Not yet over IRC: multi-table commits, scan planning, credential vending, and view
  rename (OpenHouse has none).
- Format v3 tables are not yet supported by the service, which still runs on the older Iceberg line by default.

## How far from the goal

- Conformance: Apache's compatibility kit passes 39 of 160 tests (was 31, then 38). Of the 121 that do not, 78 are OpenHouse's own
  rules or one known gap, 11 are routes not yet served, 22 are skipped by capability flags and 11 assert the
  reference server's messages. What "conforming" should mean is a question put to Mike
  (D32). The measurements are `39-v13-compat-kit-result.md` and
  `42-v13b-spec-deviations-result.md`.
- Iceberg 1.12 as the default: of the five items needed (V9a to V9e), four are done (V9a to V9d). V9e is not started;
  it waits on T10 (the five Spark 3.5 test classes that are never compiled) and uses the result of C9 (the Docker
  checks for the 1.12 line in CI). The list of what V9e flips and deletes is in `47-v9d-deployables-result.md`. Under the
  switch the uber jars, scheduler image and Spark image build and start; both builds were green in CI on pull request 113.
  I11 and V13b, which V9e also needed, are done. The opt-in build is green in CI and now runs on every pull request (C6). The fork
  is pinned at `a88f556c` and published under the Gradle user home, outside the checkout. The Spark SQL harness differs
  from the baseline in 6 of 2574 cases; the one real problem (I11) is fixed. Eleven blockers to deleting the old
  projects are listed in `44-v9abc-flip-prep-result.md`. Measurements: `40-i6f-full-regression-result.md`,
  `43-optin-ci-red-result.md`.
- Format v3: not started in the service. It follows V9e.

## Merged pull requests

35 pull requests are merged into `irc-integration` (the last is 116, merge commit 0a3bf529) and 10 into the Iceberg
fork's `openhouse-1.12.0` (the last is 12, commit a88f556c). The full tables are in `45-merged-pull-requests.md`.
The latest three: 112 (V6, V7), 113 (V9d, in `47-v9d-deployables-result.md`) and 116 (V16, N5, T13, in
`48-v16-directory-reuse-result.md`).
Nothing is merged into either fork's main or into LinkedIn's repositories.

## Done

P1, P2, I1 to I5, I8, I6a to I6c, I6g, I6h, T1 to T5, T2b, C1, V1, V2, V3, V4, V5-0, V5-gap, V5-1, V5-2, V5-3, V10a,
C2-1, C2-2, C2-3, N3-x1, N3-x2, N3a, C5, I6f, V13a, V13b, I11, V9a, V9b, V9c, C6, T9, V6, V7, V9d, V16, T13, W1 to W4, W12, I10, the two CI fix-forwards of pull requests 107 and 108, and the X0 design. The meaning of each item is in `04-work.md`.

## In progress (delegated)

- C9 (the Docker checks for the 1.12 line in CI, green), branch `irc/c9-docker-steps-1.12-ci`.
- T10 (the five Spark 3.5 test classes that are never compiled). Interrupted twice by API overload and resumed.

## Next

After those: V9e (make 1.12 the default and delete the old projects, with C8), then format v3 starting at X1. New
from V13b: N4 and V14. N5 is pinned and waits for Mike (D35). New small items: C7, C8, T12, V17, and T11
(real S3, needs an environment with S3). N3b, V8, V11, V12, V3b and V3c can go alongside. The order of the large work
is D29.

## Known red on `irc-integration`

Nothing known red at this time. Post-merge runs for pull requests 109 and 110 passed on both builds, and both builds
passed in CI on pull request 113. Pull request 116's own CI was not waited for. Pull requests
now run both builds (C6). The post-merge 1.12 job for pull request 111 took under 4 minutes because Gradle reused
test results from the previous run on identical inputs; no step was skipped. Unexplained and not red: the Spark 4.2
`test` task failed once in six runs (T8). `SparkIrcEndpointsTest.existenceChecksAreHeadRequests` failed once on reading
the access log early; pull request 112 made it wait for the last line and that may be T8, which stays open until a
CI failure names its test. And the build is not reproducible bit
for bit (`21-findings-continued.md`). Open product defect: V15.

## How work is tested

Post-merge results are read after every merge, not assumed (D33). Under D18, before a merge an agent runs the test classes it touched plus the black-box suite of the module it
changed, and Claude merges on that without waiting for the pull request's CI. The full build runs on
`irc-integration` after each merge and a failure is fixed forward, so work that starts from the branch checks the
known-red list above first. Heavy runs, such as the full delta-harness and the full Iceberg Spark suites, run after
merges when memory is free. An item whose claim is "no behavior change" runs the full suite before merge. D26 adds the cheap checks every brief
now requires, after the red build (`21-findings-continued.md`). D33 tightens it: a suite counts as passing under
`-PicebergFork` only if it ran there.

## Waiting on Mike

- D32: what "conforming Iceberg REST Catalog server" means.
- The one-way doors in D12 and D24, before any release outside the forks. D34 adds one: the register and metrics
  route semantics become a public contract at release. It does not block work.
- D35: whether to fix the native directory-reuse defect (N5), because the fix changes native behavior. Four native
  requests can still delete a renamed table's files. It does not block work.
- Worker sessions on separate machines, because a bigger container is not available (D21). The diagnosis is in
  `20-iteration-friction.md`.

Nothing blocks work.

## How to audit

- The merge commits on the two integration branches carry the why for each work item and each commit.
- Decisions are in `13-decisions.md`, `16-decisions-continued.md`, `22-decisions-continued-2.md` and
  `37-decisions-continued-3.md` (D30 to D36).
- Findings are in `08-findings.md` and the numbered result files listed in `README.md`.
- Only `01-stated.md` carries Mike's authority.
