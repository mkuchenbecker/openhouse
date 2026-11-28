# Result of V11e: scan planning refuses format v3 tables, and late-added columns plan as on the client

Bottom line: pull request 132 merged on 2026-11-24 into `irc-integration` (merge commit `9f9561e6`, pull request head
`ddac7d96`). The plan route now refuses a table of format version 3 or higher with a visible failure, so a client fails
instead of reading a null `_last_updated_sequence_number` or storing wrong row lineage. A scan naming a column added
after its snapshot, which answered 400, is now planned as the client plans it, in every case that was listed. Format v2
tables are planned as before. Marks: [ran] was run, [read] was read in code, [inferred] is judgement. Sources: the
author's record and the merge message. Post-merge CI for this merge has not been read yet.

## What the route answers for a format v3 table

- The plan route answers HTTP 200 with status `failed`, code 422, type `ScanPlanFormatVersionException`, a message that
  names the reason and says to plan on the client. There is no task and no plan id. Variant tables are refused too [ran].
- The load's checks run first, so 403 and 404 are the load's [ran].
- Why: a scan task in the specification carries no sequence number, and Iceberg 1.12's parser reads none. A server-planned
  read of a v3 table returned a null `_last_updated_sequence_number`, and a server-planned copy-on-write rewrite stored
  wrong row lineage for the rows it kept. That is a wrong answer and a permanent change, so D49 chose a refusal.
- Why a client fails visibly: on `failed` the 1.12 client throws `IllegalStateException`, does not retry and has no
  fallback to client planning [read: RESTTableScan]. A write fails during planning, before anything is written [ran]:
  SELECT, copy-on-write UPDATE and merge-on-read MERGE on a v3 table each fail, and rows, files, snapshots and lineage
  are unchanged. The same statements on a v2 table plan on the server and succeed.
- Client planning still reads v3 tables, including a table with deletion vectors and a variant table [ran].
- Paths that do not use the route are unaffected: metadata tables, incremental reads and Spark's whole-file metadata
  delete [read].

## Late-added columns: cause and fix

- Cause [read: the fork's SnapshotScan and RESTTableScan; ran: IrcScanPlanningLateColumnTest and Spark]: Spark pins a
  read of `main` with filter, then project(current schema), then useSnapshot(current). The 1.12 client then sends
  use-snapshot-schema=true with names from the current schema. The route took the selected names from the snapshot's
  schema, dropped the new column, and the filter did not bind, which gave 400. Client planning binds because the scan's
  schema is its projection.
- Fix: for a point-in-time scan with use-snapshot-schema=true, when a selected name is missing from the snapshot's schema
  and every name is in the current schema, the route projects those columns of the current schema. Every other scan is
  planned as before.
- Cases shown equal to client planning, by planned tasks and residuals and by Spark rows [ran]: a column added with no
  snapshot since, and with one snapshot since, each with a filter true for nulls, a filter false for nulls, a projection
  alone, and a case-insensitive name.
- Time travel to an older snapshot naming the newer column: Spark fails analysis the same way both ways, and at the Java
  level the route fails the filter as the client's own planning does. A projection of the older columns reads the same.
  The old 400 was pinned first, in the first commit, then turned into a comparison.
- This closes the question D49 delegated: the late-added-column case was fixed, because every listed case equals client
  planning.

## Tests whose subject changed

- IrcScanPlanningFormatV3Test, the v3 test of SparkIrcScanPlanningTest and the v3 scenarios of
  SparkIrcServerPlanningWritesTest now assert the refusal and an unchanged table, because a server-planned v3 parity no
  longer exists.
- The two tests that pinned the lineage difference are replaced by one refusal test.
- The 400 pins for late columns became comparisons with client planning, and the server-planning branch of
  SparkIrcNativeDefaultsTest was removed.
- In the server-planning reruns the catalogs whose tables are v3 keep client planning: x10_v3, x10_v3_loser, ircv3 and
  ircv3t. Each is declared with its reason through `ServerPlanning.notApplied` and asserted by SparkIrcNoPlanRequestTest,
  so no statement is skipped silently.

## Evidence counts

Merged tree, JDK 17 [ran], except where stated.

| Build line | Result |
| --- | --- |
| `:services:tables:test`, Iceberg 1.12 | 1720 tests, 1 failed, 1 skipped; the failure was this branch's own new variant fixture (ORC, refused for variant by pull request 129) |
| `:services:tables:test`, 1.5.2 (`-PicebergLinkedIn`) | 1692 tests, 0 failed, 50 skipped |
| `restCompatKitTest` | 160: 41 passed, 96 failed, 23 skipped; `expected-outcomes.txt` untouched |
| Spark 4.2 `test` and `testServerPlanning` | 114 of 114 each |
| Spark 4.2 `smokeTest` and `smokeTestServerPlanning` | 65 of 65 each; all 193 smoke statements give the same outcome both ways |
| `spotlessCheck` | passed |

- Plan requests: default test JVM 169 of 3,719, all in the planning catalog; smoke JVM 0 of 1,271 by default. With server
  planning: 283 of 3,833, and smoke 40 of 1,311.
- The failed variant fixture was fixed in `ddac7d96` and that class rerun 3 of 3. The full 1.12 suite was not rerun
  locally after that one-line change; CI on the pull request passed both jobs on `ddac7d96`.

## Known gaps

- The refusal stays until the specification's scan task and the client carry sequence numbers.
- Spark 3.5 and the extensions suite were not rerun with server planning. Local file system only.
- Post-merge CI for 132 is not read yet (D33).
- Incident, not a defect in the merge: the pre-commit hook's stash dropped the merge state during the branch's merge of
  pull request 129. The merge commit `4111933a` was rebuilt with `commit-tree` from the stash entry's index tree and
  differs from `70b35d7a` only by this branch's 12 files [ran].
