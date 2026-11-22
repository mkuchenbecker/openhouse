# Current state

Bottom line: all work is on two integration branches in Mike's forks. Nothing is merged into either fork's
main or into LinkedIn's repositories. This is the single place for current state; other files record
design, evidence and decisions. Dated 2026-11-22.

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

`openhouse-1.12.0` in mkuchenbecker/iceberg is Apache 1.12.0 (212aa894e) plus these pull requests.

| Pull request | What it holds | Commit |
| --- | --- | --- |
| 3 to 8 | The 13 reapplied LinkedIn patches, validated together | not listed here |
| 9 | Publishing group and version can be overridden | 984904f02 |
| 10 | I5: the patches applied to Spark 4.2 | 7cf3a1040 |
| 11 | W1 and W2: native-default tests and the ORC change | 28742df3e |

Nothing is merged into either fork's main or into LinkedIn's repositories.

## Done

P1, P2, I1 to I5, I8, I6a to I6c, T1 to T5, T2b, C1, V2, W1, W2, and the X0 design. The meaning of each
item is in `04-work.md`.

## In progress (delegated)

- V1, on branch `irc/v1-candidate-commit`: the commit path takes a base and a complete candidate metadata.
  It starts with an inventory of the rules and changes no behavior.
- W3, W4, I6g, I6h and a first run of the seam's suites on Iceberg 1.12, on branch `irc/i6-optin-green`.

## Next

- V3 (IRC write routes) needs V1.
- C2 (capability model).
- V4, V6 and V7.
- I6f, the full regression on the opt-in build.
- V9, the service on Iceberg 1.12, needs I6f.

## Waiting on Mike

Nothing blocks work. Three things are his to decide and none is started:

- more memory, or a second container, for builds;
- a CI cache for pull requests into `irc-integration`;
- shortening the retry backoff in ten slow LinkedIn tests.

One-way doors (D12) stay with him. The diagnosis behind the first three is in `20-iteration-friction.md`.

## How to audit

- The merge commits on the two integration branches carry the why for each work item and each commit.
- Decisions are in `13-decisions.md` and `16-decisions-continued.md`.
- Findings are in `08-findings.md` and the numbered result files listed in `README.md`.
- Only `01-stated.md` carries Mike's authority.
