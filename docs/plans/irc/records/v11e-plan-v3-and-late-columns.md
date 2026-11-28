# V11e: scan planning refuses format v3 tables; late-added columns: record

Bottom line: done. Draft pull request 132, head ddac7d96668b0799951725f3dd843d794b5fcfcd, CI green (both checks).
- The plan route refuses tables of format version 3 or higher, variant tables included. The answer is `failed`
  (200, code 422, ScanPlanFormatVersionException). The client fails visibly, and writes leave the table unchanged.
- Late-added columns: fixed. The route now projects the current schema's columns, and every case asked for equals
  client planning.

Worktree /home/user/oh-v11e, branch irc/v11e-plan-v3-and-late-columns, base 1dd485d3, merged with irc-integration
70b35d7a (pull request 129).

## Commits (all pushed)
- 8965424a test: pins today's answers for scans naming a column added after their snapshot.
- 87447294 the late-column fix (lateColumnProjection); the pins become comparisons with client planning; the
  server-planning branch of SparkIrcNativeDefaultsTest removed.
- a5c7ca5c the v3 refusal; v3 parity tests and lineage pins become refusal tests; v3 catalogs declared in the reruns.
- 4111933a merge of origin/irc-integration 70b35d7a (no conflicts; recovered by commit-tree, see below).
- 2dfcdb29 a variant table is refused too; X5's ircv3t catalog declared. ddac7d96 that test's table writes Parquet.

## Late-added columns [read: fork SnapshotScan, RESTTableScan; ran: IrcScanPlanningLateColumnTest, Spark]
- Spark pins main reads: filter, project(current schema), then useSnapshot(current). RESTTableScan then sends
  use-snapshot-schema=true with current-schema names.
- Client planning: the scan's schema is its projection. specs() keeps the table specs while the snapshot is the
  current one, so the filter binds. For time travel to a non-current snapshot, specs() rebinds the specs to the
  snapshot's schema, so a filter on a newer column fails on the client as well.
- Route before the fix: it selected the names from the snapshot's schema, dropped the new column, and the filter did
  not bind, giving a 400.
- Fix: for a point-in-time scan with use-snapshot-schema=true, if a selected name is missing from the snapshot's schema
  and every name is in the current schema, project those columns of the current schema. Otherwise behave as before.
- Equal to client planning in every case:
  - no snapshot since the column was added: a filter true for nulls, a filter false for nulls, a projection only,
    and a case-insensitive name;
  - one snapshot since;
  - time travel naming the newer column. Spark fails analysis both ways, the client fails the filter, and a
    projection of the older columns reads the same.

## Format v3 refusal [read: RESTTableScan; ran: tests]
- `failed` makes the client throw IllegalStateException with no retry and no fallback (no client path re-plans).
- Writes fail while planning, before anything is written. Tested: SELECT, UPDATE copy-on-write and MERGE merge-on-read
  on v3 all fail and change nothing (rows, files, snapshots, lineage). The same statements on v2 succeed.
- A variant table is refused as well. The load's 404 comes first. Client planning still reads v3 tables.
- Metadata-only paths do not use the route at all: metadata tables, incremental reads, and Spark's whole-file
  metadata delete.

## Server-planning reruns
- Catalogs that keep client planning there, each declared with its reason (ServerPlanning.notApplied, asserted by
  SparkIrcNoPlanRequestTest): x10_v3, x10_v3_loser, ircv3 (SparkIrcFormatV3Test and SparkIrcNativeDefaultsTest),
  ircv3t (SparkIrcFormatV3TypesTest).
- No statement is skipped silently.

## Counts [ran on the merged tree, JDK 17]
- :services:tables:test 1.12: 1720 tests, 1 failed, 1 skipped. The failure was my variant fixture (ORC is refused for
  variant by pull request 129). Fixed in ddac7d96 and rerun: IrcScanPlanningFormatV3Test 3/3. The full 1.12 suite was
  not rerun after that one-line fixture change.
- :services:tables:test 1.5.2: 1692 tests, 0 failed, 50 skipped.
- restCompatKitTest: 160 = 41 passed, 96 failed, 23 skipped; expected-outcomes.txt untouched.
- spotlessCheck: passed.
- Spark 4.2: test 114/114, testServerPlanning 114/114, smokeTest 65/65, smokeTestServerPlanning 65/65. All 193 smoke
  statements give the same outcome both ways.
- Plan requests: default test JVM 169 of 3,719, all in irc42_plan; smoke 0 of 1,271 by default. With server planning:
  283 of 3,833, and smoke 40 of 1,311.

## Merge incident
- The pre-commit hook's stash dropped MERGE_HEAD. Stash entry GitHookUnstagedStash1795509258 held the whole merged
  worktree (pull request 129's files, staged) plus my two unstaged edits (the variant case in
  IrcScanPlanningFormatV3Test, and the ircv3t declaration).
- Recovery:
  1. Built the merge commit 4111933a with commit-tree from the entry's index tree, parents a5c7ca5c and 70b35d7a.
  2. Ran merge --quit and reset --soft to it.
  3. Restored my two files from the entry. The worktree diff against the entry was empty.
  4. Dropped the entry.
- Check: the merge differs from 70b35d7a only by this branch's 12 files.

## Not verified
- Spark 3.5 and extensionsTest with server planning.
- Storage other than local.
