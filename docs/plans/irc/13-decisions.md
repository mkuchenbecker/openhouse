# Decision log

Bottom line: decisions Claude made on two-way doors (S41, S43). Each has its reason, the evidence it rests
on, and how to reverse it. None is agreed by Mike unless the entry cites a statement.

Dates are all 2026-11-22. Claude wrote every entry; "Claude's reading" marks judgement Mike did not state.

## D1. Catalog path selection and the seam as built

- Date: 2026-11-22.
- Decision: the catalog path is selected by `catalog-impl`. The in-process REST-shaped adapter rethrows
  original exceptions and handles replace itself. The seam as built in pull request 84 is adopted.
- Why: no existing production file changes, so today's path cannot regress. The smoke and contract suites
  pass on both paths, and the full delta-harness gives equal counts on both paths in both modes.
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
- Why: none changes rows returned by Spark SQL. Fixing them is cheaper once native REST exists to compare.
- References: `09-seam-result.md`, table "Differences that remain between the two paths"; `11-harness-via-seam.md`.
- Reverse by: reopening the row as a blocker.

## D3. Iceberg 1.2 / Spark 3.1 line gets no work

- Date: 2026-11-22.
- Decision: the Iceberg 1.2 / Spark 3.1 line gets no work on the new Iceberg line. It is removed from the
  integration branch's build when the service moves to Iceberg 1.12 and Java 17 (item V9). This is
  Claude's reading of S44.
- Why: the shared server modules cannot serve both lines once they need Java 17.
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
- Why: S46 asks for an auditable why per commit, and S38 and S39 make fork branches the place for work.
- References: S38, S39, S46; the "State" section of `04-work.md`.
- Reverse by: stopping merges and leaving the pull requests open for review.

## D7. The openhouse build gets the fork's Iceberg from a pinned source build

- Date: 2026-11-22.
- Decision: the openhouse build gets the fork's Iceberg by building a pinned commit from source into a
  build-local Maven directory. No binaries are published anywhere.
- Why: the proxy refuses JitPack and GitHub Packages here, and a source pin is auditable.
- References: `12-openhouse-on-1.12-scout.md`, "Hosting options for the fork's artifacts"; items I6a and I6d in `04-work.md`.
- Reverse by: pointing the switch at a hosted repository (item I6d).

## D8. LinkedIn's line is the behavior reference

- Date: 2026-11-22.
- Decision: LinkedIn's Iceberg line is the behavior reference when reapplying patches. Apache 1.12 is only
  the base. Where an Apache test contradicts a reapplied LinkedIn behavior, the test is aligned in a
  separate commit whose message body is labelled "Not a LinkedIn patch". D5 is the one stated exception.
- Why: S9 and S10 make Spark SQL behavior the expensive thing to change.
- References: S9, S10, S36; `05-iceberg-patch-ledger.md`; iceberg commits 5cce4931f, 7e23bad11 and 1bf3db827, each labelled "Not a LinkedIn patch" in its message body.
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

## D11. The native-defaults design is adopted

- Date: 2026-11-22.
- Decision: the design in `14-native-defaults-design.md` is adopted, with these choices by Claude:
  - (a) On the new Iceberg line the client neither overlays OpenHouse defaults nor declares the
    `column-defaults` capability. A v2 table that requires it is refused with the existing 422, not read
    differently by old and new clients.
  - (b) Defaults can be set on v3 tables only. Nothing upgrades a table's format version implicitly.
  - (c) ORC readers that do not opt in to default filling fail as Apache does, not return NULL. This
    departs from LinkedIn's line and falls under D5.
  - (d) Spark SQL statements for setting defaults (item W9) are not built now. No Spark SQL statement sets
    a default on LinkedIn's line either, so nothing is lost. Tests set native defaults through the Iceberg API.
  - (e) The two server rules that matter only once the service holds v3 tables (refuse a non-null
    `write-default` below v3, W5; refuse format-version changes in ordinary commits, W10) are folded into
    items W6 and X1, not built against the 1.5.2 service.
- Why: S47. A table that reads differently depending on the client is not a valid state. Silent NULLs hide data loss.
- References: `14-native-defaults-design.md`, decisions 3a to 3f, and the experiment output it names.
- Reverse by: restoring the overlay and the capability on the new-line client.

## D12. One-way doors from the defaults design stay with Mike

- Date: 2026-11-22.
- Decision: three things are one-way doors and stay with Mike (D10). They are upgrading any non-test table
  to format v3, translating a table's stored OpenHouse defaults into native `initial-default` values, and
  writing native defaults into a non-test table at all. No work item does any of them on a non-test table
  without his say.
- Why: none can be undone. Iceberg cannot downgrade v3, and legacy clients cannot load v3.
- References: `14-native-defaults-design.md`, "One-way doors"; item W8 in `04-work.md`.
- Reverse by: not applicable; each door is approved per table.

Continued in `16-decisions-continued.md` (D13 onward).
