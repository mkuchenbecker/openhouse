# Commit-protocol review: raw evidence

This directory holds the unedited working artifacts behind the OpenHouse commit-protocol
analysis. Everything here is preserved so that a reader who was not present can check any
conclusion against the material that produced it, rather than taking a synthesized summary
on trust.

The synthesized outputs live elsewhere. `docs/commit-protocol/` (pull request #35) carries
the protocol explainer, the incident case study, the adjudicated findings register, the
Iceberg reference, the migration design, and the TLA+ appendix. This directory is the layer
underneath: the individual expert reports those documents were built from, the working notes
each reviewer kept, and the model-checker output.

## Provenance and method

Each report was produced by an independent agent working from a written charter, using the
review protocol published at
[mkuchenbecker/code-review-skills](https://github.com/mkuchenbecker/code-review-skills):
grok, fan out to independent experts, synthesize, review the review. Reviewers marked
"blind" were given no prior analysis on purpose, so that their agreement with a briefed
reviewer counts as corroboration rather than echo.

Files are committed **verbatim**, including their internal cross-references, local checkout
paths, and any process narration. Nothing was cleaned up, because an edited audit trail is
not one. Use the decoding key below to resolve references.

## Decoding key

| Reference in the reports | Resolves to |
|---|---|
| `/home/user/openhouse/...` | The root of this repository |
| `/home/user/iceberg/...` | An `apache/iceberg` checkout on the 1.5.2.x line |
| `/home/user/code-review-skills/...` | [mkuchenbecker/code-review-skills](https://github.com/mkuchenbecker/code-review-skills) |
| `/home/user/mkuchenbecker/humanizer/...` | [mkuchenbecker/humanizer](https://github.com/mkuchenbecker/humanizer), the writing reference |
| "report 01", "report 02", "report 03" | `reports/01-*`, `reports/02-*`, `reports/03-*` in this directory |
| `2a9dac8` | The commit all code claims are pinned to |
| Numbered "smells" | The ten items in `reports/01-openhouse-commit-protocol.md` §7 |

## What is here

### `reports/` — the expert reports

| File | What it is |
|---|---|
| `01-openhouse-commit-protocol.md` | End-to-end control flow of the commit path, with the CAS layers, failure windows, and ten flagged smells |
| `01-code-manifest.md` | The 25 files a reviewer of the commit path should read |
| `02-snapshot-drop-bug.md` | Root cause and fix analysis of the stale-base snapshot loss |
| `03-iceberg-commit-deepdive.md` | Apache Iceberg's native and REST commit protocols, as the reference standard |
| `04-arch-review.md` | Architecture review of the commit path (briefed) |
| `04-testing-review.md` | Testing review of the commit path (briefed) |
| `04-blind-protocol-review.md` | Protocol-correctness review (blind, judged against Iceberg's own contract) |
| `05-tla.md` | TLA+ feasibility assessment, spec walkthrough, and TLC results |
| `06-rest-native-design.md` | The REST-catalog-native migration design and estimate |
| `07-prototype.md` | What the REST-native prototype implements, its deviations, and its test matrix |
| `09-writing-review.md` | Blind writing review of the first three documents |
| `10-code-review-synthesis.md` | Adjudication of all 44 raw findings into 29, with the adjudication log |
| `11-tla-fixes-pr.md` | The rename-guard change and its model-checking evidence |
| `12-final-gate.md` | Writing review of the synthesis plus an 84-citation verification sweep |
| `14-*.md` | The four-expert review of the rename-guard pull request, and its synthesis |
| `15-followups.md` | Integration of the follow-up findings across two pull requests |
| `16-appendix-writing-review.md` | Blind writing review of the appendices, including the design-document genre assessment |
| `17-appendix-fixes.md` | Disposition of those findings, including one partial decline |
| `18-pr36-description-review.md` | Blind review of a pull request description against hard prose rules |
| `19-rename-cas-hardening.md` | The ABA defect in the version-only rename CAS and its fix |

### `checkpoints/` — working notes

Each reviewer checkpointed state to disk as it went, so an interrupted run could resume from
notes instead of from zero. These show the order in which conclusions were reached, which is
where you can see a reviewer changing its mind.

### `tla-specs/` and `tlc-output/` — the model and its results

The TLA+ model of the commit protocol, its rename extension, the configurations, and the raw
TLC output. `OpenHouseCommitRename-with-tokenless.tla` is the variant that models the
token-absent rename mode.

These files are **not** part of any shipped change. They were deliberately kept out of the
code pull requests, so that no future change to the commit path is obliged to update a spec
or refresh checked-in checker output. They are preserved here as evidence only. The
tokenless variant in particular was recovered from a commit that a rebase left unreachable,
and would otherwise have been garbage-collected.

### What is deliberately absent

Build and test logs from the working container (Gradle output, lint runs, container setup)
are not included. They record tooling behavior rather than findings, and none of the
conclusions rests on them. Test results that *are* load-bearing are quoted inside the
reports that rely on them.

## Known limits of this record

The reports are working artifacts and disagree with each other in places. That is the point:
the disagreements are visible, and `reports/10-code-review-synthesis.md` records how each was
adjudicated and which findings were downgraded or declined.

Three findings postdate the synthesis and therefore appear in none of the reports here. They
are written up in [`ADDENDUM.md`](ADDENDUM.md), which also explains why the register in
`docs/commit-protocol/appendix-b-code-review.md` is stale with respect to them.
