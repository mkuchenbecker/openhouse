# Current state

Bottom line: all work is on two integration branches in Mike's forks. Nothing is merged into either fork's
main or into LinkedIn's repositories. This is the single place for current state; other files record
design, evidence and decisions. Dated 2026-11-22.

## What works today

- The native API and Spark 3.5 with the OpenHouse catalog are unchanged.
- An unmodified Iceberg `RESTCatalog`, and Spark 3.5 through it, can read and write OpenHouse tables under
  `/iceberg` with the native rules (`27-v3-irc-write-result.md`).
- Views work over IRC for an unmodified `RESTCatalog` and Spark 3.5, without rename. They do not yet work for
  Iceberg 1.12 clients until the config route advertises view support (`31-v4-irc-views-result.md`).
- Spark 4.2 on the fork's Iceberg 1.12 can read OpenHouse tables over IRC, and can now also create tables and
  insert rows over IRC, under the opt-in build (`26-n3a-spark42-read-result.md`, `32-optin-green-ci-result.md`).
- Policies over IRC are refused with a clear 400 until the translation lands (D25).
- The opt-in Iceberg 1.12 build passes all its listed suites.
- Not yet over IRC: policies (refused), grants, unlock, metrics, register, multi-table commits, scan planning and
  credentials.
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
| 96 | V5-0, V5-gap: IRC writes run the native update validator and refuse policy keys | 0010df15 |
| 95 | V4: the IRC view routes (`31-v4-irc-views-result.md`) | 71b3e826 |
| 97 | C5, W12, I10: the opt-in build is green and under post-merge CI (`32-optin-green-ci-result.md`) | e353a36d |
| 98 | Fix-forward: the Iceberg 1.2 line boots again (`27-v3-irc-write-result.md`, follow-ups) | cc76d485 |

`openhouse-1.12.0` in mkuchenbecker/iceberg is Apache 1.12.0 (212aa894e) plus these pull requests.

| Pull request | What it holds | Commit |
| --- | --- | --- |
| 3 to 8 | The 13 reapplied LinkedIn patches, validated together | not listed here |
| 9 | Publishing group and version can be overridden | 984904f02 |
| 10 | I5: the patches applied to Spark 4.2 | 7cf3a1040 |
| 11 | W1 and W2: native-default tests and the ORC change | 28742df3e |

Nothing is merged into either fork's main or into LinkedIn's repositories.

## Done

P1, P2, I1 to I5, I8, I6a to I6c, I6g, I6h, T1 to T5, T2b, C1, V1, V2, V3, V4, V5-0, V5-gap, N3a, C5, W1 to W4, W12, I10,
and the X0 design. The meaning of each
item is in `04-work.md`.

## In progress (delegated)

- V5-1 with V5-2 (policies over IRC), on branch `irc/v5-policies-over-irc`.
- C2-1 with C2-2 (capabilities module and resolver), on branch `irc/c2-capabilities-resolver`.

## Next

V5-3, C2-3, V10a, N3b, N3-x1, N3-x2, V6, V7, V3b, V3c, I6f, then V9 and the v3 items. The capabilities
items come from `28-capabilities-over-irc-design.md` (D23) and are listed in `04-work.md`.

## Known red on `irc-integration`

The default build was red on `irc-integration` from 14f23870 (pull request 94) until cc76d485 (pull request 98),
on the Iceberg 1.2 fixture. The fix was verified locally on the merged head (the three failing tasks and the Spark
3.1 itest tasks pass). No post-merge run has yet been read for cc76d485. The first run of the opt-in post-merge job
is also pending. The last completed post-merge build read was run 45062814318 on 94877d49, which passed (27
minutes).

## How work is tested

Under D18, before a merge an agent runs the test classes it touched plus the black-box suite of the module it
changed, and Claude merges on that without waiting for the pull request's CI. The full build runs on
`irc-integration` after each merge and a failure is fixed forward, so work that starts from the branch checks the
known-red list above first. Heavy runs, such as the full delta-harness and the full Iceberg Spark suites, run after
merges when memory is free. An item whose claim is "no behavior change" runs the full suite before merge. D26 adds the cheap checks every brief
now requires, after the red build (`21-findings-continued.md`).

## Waiting on Mike

Nothing blocks work. His to decide: worker sessions on separate machines, because a bigger container is not
available (D21), and the one-way doors in D12 and D24. The diagnosis behind the first is in
`20-iteration-friction.md`.

## How to audit

- The merge commits on the two integration branches carry the why for each work item and each commit.
- Decisions are in `13-decisions.md`, `16-decisions-continued.md` and `22-decisions-continued-2.md`.
- Findings are in `08-findings.md` and the numbered result files listed in `README.md`.
- Only `01-stated.md` carries Mike's authority.
