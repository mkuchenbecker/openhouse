# Current state

Bottom line: all work is on two integration branches in Mike's forks. Nothing is merged into either fork's
main or into LinkedIn's repositories. This is the single place for current state; other files record
design, evidence and decisions. Dated 2026-11-22.

## What works today

- The native API and Spark 3.5 with the OpenHouse catalog are unchanged.
- An unmodified Iceberg `RESTCatalog`, and Spark 3.5 through it, can read and write OpenHouse tables under
  `/iceberg` with the native rules (`27-v3-irc-write-result.md`).
- Spark 4.2 on the fork's Iceberg 1.12 can read OpenHouse tables over IRC under the opt-in build
  (`26-n3a-spark42-read-result.md`).
- Not yet over IRC: views (in progress), policies, grants, unlock, metrics, register, multi-table commits, scan
  planning and credentials.
- Format v3 tables are not yet supported by the service.

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

`openhouse-1.12.0` in mkuchenbecker/iceberg is Apache 1.12.0 (212aa894e) plus these pull requests.

| Pull request | What it holds | Commit |
| --- | --- | --- |
| 3 to 8 | The 13 reapplied LinkedIn patches, validated together | not listed here |
| 9 | Publishing group and version can be overridden | 984904f02 |
| 10 | I5: the patches applied to Spark 4.2 | 7cf3a1040 |
| 11 | W1 and W2: native-default tests and the ORC change | 28742df3e |

Nothing is merged into either fork's main or into LinkedIn's repositories.

## Done

P1, P2, I1 to I5, I8, I6a to I6c, I6g, I6h, T1 to T5, T2b, C1, V1, V2, V3, N3a, W1 to W4, and the X0 design. The meaning of each
item is in `04-work.md`.

## In progress (delegated)

- V4 (IRC view routes), on branch `irc/v4-irc-view-routes`.
- C5 with W12 and I10, on branch `irc/c5-optin-ci`.
- V5-0 and V5-gap, with the interim refusal of policy keys (D25), on branch `irc/v3-irc-write-gaps`.

## Next

V5-1, V5-2, V5-3, C2-1, C2-2, C2-3, N3b, N3-x1, N3-x2, V6, V7, I6f, then V9 and the v3 items. The capabilities
items come from `28-capabilities-over-irc-design.md` (D23) and are listed in `04-work.md`.

## Known red on `irc-integration`

None known on the default build. Last completed post-merge build: run 45062814318 on 94877d49, passed (27
minutes; the Gradle cache was restored and saved about 8 minutes). Later merges are building. Under the opt-in
build two view cases on the in-process REST-shaped path fail until C5 lands, and the Spark 4.2 write test pins a
406 that pull request 94 made obsolete. Both are being fixed on `irc/c5-optin-ci`.

## How work is tested

Under D18, before a merge an agent runs the test classes it touched plus the black-box suite of the module it
changed, and Claude merges on that without waiting for the pull request's CI. The full build runs on
`irc-integration` after each merge and a failure is fixed forward, so work that starts from the branch checks the
known-red list above first. Heavy runs, such as the full delta-harness and the full Iceberg Spark suites, run after
merges when memory is free. An item whose claim is "no behavior change" runs the full suite before merge.

## Waiting on Mike

Nothing blocks work. His to decide: worker sessions on separate machines, because a bigger container is not
available (D21), and the one-way doors in D12 and D24. The diagnosis behind the first is in
`20-iteration-friction.md`.

## How to audit

- The merge commits on the two integration branches carry the why for each work item and each commit.
- Decisions are in `13-decisions.md`, `16-decisions-continued.md` and `22-decisions-continued-2.md`.
- Findings are in `08-findings.md` and the numbered result files listed in `README.md`.
- Only `01-stated.md` carries Mike's authority.
