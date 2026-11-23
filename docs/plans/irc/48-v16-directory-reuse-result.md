# V16, N5 and T13 result: a table's directory can no longer be reused over IRC

Bottom line: V16, N5 and T13 are done (pull request 116, merge commit `0a3bf5292fd861405d416b26227d1f504d2a1288`,
merged into `irc-integration`). The data-loss path is confirmed by running it: after a rename, a create of the old
name that carries the old table's UUID becomes a second table in the first table's directory. Dropping the second
table answers 204 and deletes the first table's files, and every read of the first then answers 500. Over IRC it
had three ways in; all are closed. Natively it has four ways in, needs only the create privilege on the database,
and is unchanged; five tests pin it (D9). The fix for the native half is put to Mike (D35). The four register
refusals that had no tests now have them. Dated 2026-11-23. Source: the delegated agent's report.

## The sequence, run on both APIs

Table `db.a` is created (directory `<root>/db/a-<uuid>`), given a snapshot, and renamed to `db.b`. The rename
changes the row's key; the directory keeps its name and the row keeps its UUID. A create of `db.a` that ends up
with that UUID is a second table in that directory. Drop deletes the row, then the directory.

## What changed on IRC (one class, `IcebergRestWriteHandler`)

- The commit that completes a staged create, when it names a location, is refused with 400 and changes nothing
  when a live or soft-deleted row points into that directory or carries its UUID, or when a snapshot's manifest
  list does not lie in that directory. These are register's checks, now shared; register's messages are
  byte-equal to before. The create privilege is asked before the rows are read, so a denied principal is not told
  whose directory it is.
- A create and a staged create no longer pass `openhouse.tableUUID` to the native create: the table is created in
  a directory of its own under a new UUID (D36, extending D30).
- Native classes changed: none.

The three ways in before the fix, each with a test that failed first (8 of the 10 new IRC tests were red):
1. a completion naming a live table's directory as its location (with or without data);
2. a completion naming its own directory whose snapshot's manifest list lies in a live table's directory (the
   native create takes the UUID from that path);
3. a create or staged create whose properties carry `openhouse.tableUUID`.
A completion naming a soft-deleted table's directory was also open and is closed. A create naming the directory as
`location` was already safe (D30), and a test now says so, also through an unmodified `RESTCatalog` CTAS.

## N5: the native API, pinned and not changed

Four requests each answered 201 and each led to the same loss: (1) a create with the four `openhouse.*` id and
location properties naming any existing file of the directory; (2) the same UUID with
`openhouse.isTableReplicated=true`, which has no path check; (3) a `REPLICA_TABLE` create with the UUID; (4) a
create with data whose first snapshot's manifest list lies there. With every decision on a table denied the create
still answers 201: the only decision asked is `CREATE_TABLE` on the database, so no privilege on the damaged table
is needed. Nothing reads house-table rows when a UUID is supplied. Five tests pin this
(`NativeCreateDirectoryReusePinnedDefectTest` 4, `CommitPathAuthorizationTest` 1). The proposed fix is D35.

## T13: the four register refusals

Tests only; all passed first time and nothing was fixed: partition-spec history, field ids a create would
renumber, statistics files (table and partition), and the replica table type. Each expects 400 with the
refusal's message, no change, and then registers the untouched file (200).

## Counts

All on head `2a2bb427`, which merges `irc-integration` at pull request 113; the same counts held before that merge.
- `:services:tables:test` 1579 / 0 failed, default and under the switch (1560 before, plus 19 new: 7 + 2 + 1 + 4 +
  1 + 4).
- Fixtures 1.2 41 / 0; fixtures 1.5 41 / 0 on both builds; Spark 3.5 `catalogTest` 269 / 0 failed / 1 skipped;
  Spark 4.2 `test` 40 / 0.
- Compatibility kit 160: 39 passed, 98 failed, 23 skipped, equal to the list; no outcome moved.
- `spotlessCheck` passes. No golden file changed.

## What was not run

Spark 3.5 `statementTest` and `test`; Spark 3.1 suites; Spark 4.2 `extensionsTest`; datalayout and apps; the real
House Tables service with MySQL and its soft-deleted paging; storages other than local; pull request CI. The
Iceberg 1.2 runtime serves no IRC write route, so it needs no guard. The IRC proof of what the drop destroys ran in
a scratch probe that is not kept, because once fixed it cannot be a passing IRC test.

## Known gaps

- Two IRC-only differences, accepted (D36): a create with incomplete companion properties used to get the native
  400 and is now created under a new UUID; a denied principal whose completion also breaks a native validation rule
  now gets 403 where it got that rule's 400.
- An IRC create can still set `openhouse.isTableReplicated`; the native create stores it. It no longer places
  the table. Noted on I9's row in `04-work.md`.
- Each completion that names a location lists all of the database's house-table rows (every CTAS over IRC). Small
  item V17: a House Tables lookup by UUID would make it one row.
- The native defect stays open until Mike answers D35.
- Note 46 says `openhouse.replicaTableLocationId` names a directory in this build. The agent read that the base
  checker filters it out before the location is allocated, so it does not here.
