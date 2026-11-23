# Current state

Bottom line: all work is on two integration branches in Mike's forks. Nothing is merged into either fork's
main or into LinkedIn's repositories. This is the single place for current state; other files record
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
- Not yet over IRC: metrics reports, register table, multi-table commits, scan planning, credential vending, and view
  rename (OpenHouse has none).
- Format v3 tables are not yet supported by the service, which still runs on the older Iceberg line.

## How far from the goal

- Conformance: Apache's compatibility kit passes 38 of 160 tests (was 31). Of the 122 that do not, 78 are OpenHouse's own
  rules or one known gap, 11 are routes not yet served, 22 are skipped by capability flags and 11 assert the
  reference server's messages. What "conforming" should mean is a question put to Mike
  (D32). The measurements are `39-v13-compat-kit-result.md` and
  `42-v13b-spec-deviations-result.md`.
- Iceberg 1.12 as the default: the whole build passes under the opt-in switch, and the Spark SQL harness differs
  from the baseline in 6 of 2574 cases. The one real problem, time travel with a write-audit-publish branch set, is
  fixed in the Iceberg fork (I11); the rest are accepted or internal. OpenHouse still pins the older fork commit
  `28742df3e538`, and the bump is part of V9a. What remains is listed as V9a to V9e. The measurement is `40-i6f-full-regression-result.md`.
- Format v3: not started in the service. It follows V9e.

## Integration branches

`irc-integration` in mkuchenbecker/openhouse is LinkedIn main (bb8929e1) plus these pull requests. Each is a
merge commit whose message states why the change exists, the evidence, and the known gaps.

| Pull request | Work item | Merge commit |
| --- | --- | --- |
| 80 | T1, smoke suite | 37b89f0b |
| 81 | T3, plugin contract tests | d5c34e74 |
| 82 | T4, commit-path tests | 85614361 |
| 85 | T2, delta-harness | 8bda0f6b |
| 83 | T5, faithful stand-in for the house table store | 2334b050 |
| 84 | C1, the REST-shaped seam | 4f15a3a3 |
| 86 | T2b, harness through the seam | b5b8ed87 |
| 87 | I6a to I6c, opt-in Iceberg 1.12 build | fdc7f426 |
| 88 | V2, IRC read routes | 4517643b |
| 90 | Post-merge CI workflow `post-merge-build.yml` (D18) | b78743fe |
| 91 | W3, W4, I6g, I6h: the opt-in Iceberg 1.12 build passes every listed suite except two seam view cases (`25-optin-green-result.md`) | b732cc7b |
| 89 | V1, the commit core (`23-v1-commit-core-result.md`) | 94877d49 |
| 92 | N3a: Spark 4.2 on Iceberg 1.12 reads OpenHouse over IRC, opt-in build only (`26-n3a-spark42-read-result.md`) | 8b9bfe83 |
| 93 | The post-merge build no longer cancels a running build | f60e32dd |
| 94 | V3: the IRC table write routes (`27-v3-irc-write-result.md`) | 14f23870 |
| 96 | V5-0, V5-gap: IRC writes run the native update validator and refuse policy keys | 0010df15 |
| 95 | V4: the IRC view routes (`31-v4-irc-views-result.md`) | 71b3e826 |
| 97 | C5, W12, I10: the opt-in build is green and under post-merge CI (`32-optin-green-ci-result.md`) | e353a36d |
| 98 | Fix-forward: the Iceberg 1.2 line boots again (`27-v3-irc-write-result.md`, follow-ups) | cc76d485 |
| 99 | V5-1, V5-2: policies through the IRC commit route (`33-v5-policies-over-irc-result.md`) | 1fa8cb34 |
| 100 | C2-1, C2-2: the capabilities module and resolver (`34-c2-capabilities-resolver-result.md`) | 19605614 |
| 101 | N3-x1: OpenHouse SQL extensions for Spark 4.2 (`35-n3x1-spark42-extensions-result.md`) | b07613f9 |
| 102 | V5-3, V10a: grants and unlock routes; the config advertises served endpoints (`36-v5-3-grants-unlock-endpoints-result.md`) | 6015e111 |
| 103 | C2-3, N3-x2: the grants and unlock client (`38-c2-3-rest-capability-client-result.md`) | c174e388 |
| 104 | V13a: Apache's compatibility kit (`39-v13-compat-kit-result.md`) | fb8c1240 |
| 105 | I6f: full regression of the opt-in build (`40-i6f-full-regression-result.md`) | 7ddc5bb4 |
| 106 | V13b: the IRC routes follow the specification in four places (`42-v13b-spec-deviations-result.md`) | bfdcf266 |

`openhouse-1.12.0` in mkuchenbecker/iceberg is Apache 1.12.0 (212aa894e) plus these pull requests.

| Pull request | What it holds | Commit |
| --- | --- | --- |
| 3 to 8 | The 13 reapplied LinkedIn patches, validated together | not listed here |
| 9 | Publishing group and version can be overridden | 984904f02 |
| 10 | I5: the patches applied to Spark 4.2 | 7cf3a1040 |
| 11 | W1 and W2: native-default tests and the ORC change | 28742df3e |
| 12 | I11: time-travel reads ignore the session write-audit-publish branch on Spark 3.5 | a88f556c |

Nothing is merged into either fork's main or into LinkedIn's repositories.

## Done

P1, P2, I1 to I5, I8, I6a to I6c, I6g, I6h, T1 to T5, T2b, C1, V1, V2, V3, V4, V5-0, V5-gap, V5-1, V5-2, V5-3, V10a,
C2-1, C2-2, C2-3, N3-x1, N3-x2, N3a, C5, I6f, V13a, V13b, I11, W1 to W4, W12, I10, and the X0 design. The meaning of each item is in `04-work.md`.

## In progress (delegated)

- V9a and V9b (the fork's artifacts survive `clean`; `iceberg-aws` from the fork; the pin bump), on branch
  `irc/v9a-flip-prep`.
- V9c (sources reused from the Iceberg 1.2 / Spark 3.1 projects move into the newer projects), on branch
  `irc/v9c-move-shared-sources`.

## Next

V9d (Docker images), V9e (make 1.12 the default), then format v3 starting at X1. New from V13b: N4 (native
rename onto a view answers 409) and V14 (create transaction history). N3b, V6, V7, V8, V11, V12, V3b and V3c can go alongside. The order of the large work
is D29.

## Known red on `irc-integration`

- Default build: green at 6015e111 (the last full run read). Pull requests 103 to 106 since then passed targeted
  suites only.
- Opt-in build: the whole build passes locally on the head of pull request 105 (pull request 106 ran its compile, kit and Spark 4.2 suites); no run of the opt-in post-merge CI
  job has been read.

## How work is tested

Under D18, before a merge an agent runs the test classes it touched plus the black-box suite of the module it
changed, and Claude merges on that without waiting for the pull request's CI. The full build runs on
`irc-integration` after each merge and a failure is fixed forward, so work that starts from the branch checks the
known-red list above first. Heavy runs, such as the full delta-harness and the full Iceberg Spark suites, run after
merges when memory is free. An item whose claim is "no behavior change" runs the full suite before merge. D26 adds the cheap checks every brief
now requires, after the red build (`21-findings-continued.md`).

## Waiting on Mike

- D32: what "conforming Iceberg REST Catalog server" means.
- The one-way doors in D12 and D24, before any release outside the forks.
- Worker sessions on separate machines, because a bigger container is not available (D21). The diagnosis is in
  `20-iteration-friction.md`.

Nothing blocks work.

## How to audit

- The merge commits on the two integration branches carry the why for each work item and each commit.
- Decisions are in `13-decisions.md`, `16-decisions-continued.md`, `22-decisions-continued-2.md` and
  `37-decisions-continued-3.md`.
- Findings are in `08-findings.md` and the numbered result files listed in `README.md`.
- Only `01-stated.md` carries Mike's authority.
