# Decision log

Bottom line: decisions Claude made on two-way doors (S41, S43). Each has its reason, the evidence it rests
on, and how to reverse it. None is agreed by Mike unless the entry cites a statement.

Dates are all 2026-11-22. Claude wrote every entry. Where an entry says "Claude's reading" or "Claude's
inference", that part is judgement and not something Mike said.

## D1. Catalog path selection and the seam as built

- Date: 2026-11-22.
- Decision: the catalog path is selected by `catalog-impl`. The in-process REST-shaped adapter rethrows
  original exceptions and handles replace itself. The seam as built in pull request 84 is adopted.
- Why: zero existing production files change, so today's path cannot regress. Both the smoke suite and
  the contract suite pass on both paths. The full delta-harness gives equal counts on both paths in both
  modes.
- References: `07-seam-design.md` sections 1 and 3; `09-seam-result.md`; `11-harness-via-seam.md`;
  openhouse pull requests 84 and 86.
- Reverse by: deleting the three added classes.

## D2. Remaining differences between the two paths

- Date: 2026-11-22.
- Decision: the remaining differences between the legacy path and the REST-shaped path are accepted as
  recorded and do not block work that builds on the seam. Two become work items: C3 (a gated RTAS writes a
  data file before the server rejects it) and C4 (property and policy commits from a stale handle skip the
  base-version check on the REST-shaped path). The snapshot-history difference (a concurrent INSERT's
  snapshot is kept when an RTAS follows it) is accepted as what any REST catalog does.
- Why: none changes rows returned by Spark SQL. Fixing them is cheaper once the native REST path exists to
  compare against.
- References: `09-seam-result.md`, table "Differences that remain between the two paths"; `11-harness-via-seam.md`.
- Reverse by: reopening the row as a blocker.

## D3. Iceberg 1.2 / Spark 3.1 line gets no work

- Date: 2026-11-22.
- Decision: the Iceberg 1.2 / Spark 3.1 line gets no work on the new Iceberg line. It is removed from the
  integration branch's build when the service moves to Iceberg 1.12 and Java 17 (item V9). This is
  Claude's reading of S44.
- Why: the shared single-copy server modules cannot serve both lines once they need Java 17.
- References: S44; `12-openhouse-on-1.12-scout.md`; item I6e in `04-work.md`.
- Reverse by: giving that line pinned copies of the shared modules.

## D4. Delete-file replication is recorded and left unaddressed

- Date: 2026-11-22.
- Decision: delete-file replication on non-HDFS storage (I9) is recorded as a task and deliberately left
  unaddressed (S45). The replication patches stay as reapplied because they are LinkedIn's behavior. A
  future item (T7) replaces the replication-factor property with managed performance tiering. It is not
  designed.
- Why: Mike does not care about replication and calls it a leaking implementation detail (S45).
- References: S45; `08-findings.md`, "Second round"; iceberg pull request 4.
- Reverse by: designing I9 as a fix and reopening it.

## D5. Column defaults mean native Iceberg default values

- Date: 2026-11-22.
- Decision: column defaults on the new Iceberg line mean native Iceberg default values, proven on Parquet
  first (S47). Consequences, which are Claude's inference:
  - LinkedIn's acceptance of non-null defaults on v2 tables is not reapplied to the fork. This drops
    option 1 of `10-v2-defaults.md`, which Claude had recommended before S47.
  - Parquet and Avro readers filling defaults as Apache 1.12 does is the target behavior, not a regression.
  - The OpenHouse read bridge stays on the legacy path only.
  - A design for what the new line does with existing v2 tables that carry OpenHouse defaults is in
    progress (item X0, redefined).
- Why: S47. This is a deliberate Spark SQL behavior difference from LinkedIn's line.
- References: S47; `10-v2-defaults.md`; items X0 and X4 in `04-work.md`.
- Reverse by: reapplying the leniency patch in the fork.

## D6. Claude controls merges on the fork branches

- Date: 2026-11-22.
- Decision: Claude merges pull requests on branches of the mkuchenbecker forks (S46). Merge commits state
  the why, the evidence and the known gaps, and list each contained commit with its reason. From now on
  every commit body states why the change exists. Existing commits are not rewritten, because their ids
  are cited across these notes; the merge commit carries their reasons. Nothing is merged into either
  fork's protected main or into LinkedIn's repositories.
- Why: S46 asks for auditability of each commit's why, and S38 and S39 fix branches on the forks as the place for work.
- References: S38, S39, S46; the "State" section of `04-work.md`.
- Reverse by: stopping merges and leaving the pull requests open for review.

## D7. The openhouse build gets the fork's Iceberg from a pinned source build

- Date: 2026-11-22.
- Decision: the openhouse build gets the fork's Iceberg by building a pinned commit from source into a
  build-local Maven directory. No binaries are published anywhere.
- Why: JitPack and GitHub Packages are refused by the proxy in this environment, and a source pin is
  auditable.
- References: `12-openhouse-on-1.12-scout.md`, "Hosting options for the fork's artifacts"; items I6a and I6d in `04-work.md`.
- Reverse by: pointing the switch at a hosted repository (item I6d).

## D8. LinkedIn's line is the behavior reference

- Date: 2026-11-22.
- Decision: LinkedIn's Iceberg line is the behavior reference when reapplying patches. Apache 1.12 is only
  the base. Where an Apache test contradicts a reapplied LinkedIn behavior, the test is aligned in a
  separate commit labelled "Not a LinkedIn patch". D5 is the one stated exception.
- Why: S9 and S10 make Spark SQL behavior the expensive thing to change.
- References: S9, S10, S36; `05-iceberg-patch-ledger.md`; iceberg commits 60465ee59, 5cce4931f, 1bf3db827.
- Reverse by: dropping the alignment commits and letting Apache's behavior stand.

## D9. LinkedIn defects found by the new tests are pinned, not fixed

- Date: 2026-11-22.
- Decision: existing LinkedIn defects found by the new tests are pinned as they are and not fixed. They
  are five 500 responses for malformed requests, case-sensitive rename, the client-side policy parse
  failure after a lock, and the others listed in `08-findings.md`.
- Why: fixing them is a Spark SQL or API behavior change with no bearing on REST or v3.
- References: S9, S10; `08-findings.md`, "Existing behavior that looks like a defect" and "Third round".
- Reverse by: fixing a defect in its own item with a test that changes its pinned expectation.

## D10. Two-way doors are decided, one-way doors go to Mike

- Date: 2026-11-22.
- Decision: a Spark SQL behavior difference no longer stops an item to wait for Mike. Claude decides it if
  it is a two-way door, records it here, and goes on. Only one-way doors go to Mike: anything on a
  protected main, anything in LinkedIn's repositories, publishing artifacts, deleting branches or data, and
  upgrading the format version of tables that are not test tables. This replaces rule J18 as it was
  applied in `04-work.md`.
- Why: S41 and S43.
- References: S41, S43; J18 in `03-inferred.md`; "Rules for every item" in `04-work.md`.
- Reverse by: restoring the old rule in `04-work.md`.
