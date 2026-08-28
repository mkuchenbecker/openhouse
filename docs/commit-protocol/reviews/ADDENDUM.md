# Addendum: findings that postdate the synthesis

Three findings about the rename path were established after
`reports/10-code-review-synthesis.md` was written, so they appear in none of the reports in
this directory and the register in `docs/commit-protocol/appendix-b-code-review.md` does not
yet reflect them. They are recorded here so the evidence trail is complete.

They are also, read together, one finding rather than three. Each was correct against the
interleaving it was aimed at and blind to its neighbour, and that pattern is the substantive
result.

## 1. Rename rewrites metadata only because identity is denormalized into it

Blocking finding 3 in the register says rename bypasses the compare-and-swap layers. That is
true and shallow. The reason rename can lose a commit at all is that it is not a catalog
operation in this system.

Apache Iceberg treats rename as pure identity. `HiveCatalog.renameTable` fetches the table,
calls `setDbName` and `setTableName`, and issues `alter_table`; it never touches
`metadata_location`. Under that contract a rename and a concurrent commit write disjoint
state and cannot conflict.

OpenHouse's rename instead runs through `doCommit`, which mints a new `metadata.json` from
the base the renamer loaded and then writes that file's location into the row alongside the
identity columns. The pre-fix statement was:

```
UPDATE UserTableRow table SET table.tableId = :toTableId,
       table.metadataLocation = :metadataLocation, table.databaseId = :toDatabaseId
WHERE lower(table.databaseId) = lower(:fromDatabaseId) AND lower(table.tableId) = lower(:fromTableId)
```

It rewrites metadata because table identity is denormalized into `metadata.json` as the
`openhouse.tableId` and `openhouse.databaseId` properties, and those have to be kept
consistent with the row. The service javadoc states this directly: "The new metadata file of
the table with updated table properties that match the new tableId."

The consequence is that a rename inherits the full lost-update surface of a commit. A commit
landing between the renamer's refresh and its update is overwritten by metadata derived from
the older base.

**Implication.** The compare-and-swap work is a mitigation. The root fix is to stop
embedding identity in the metadata file, or to accept that those two properties go stale and
make rename identity-only. Either removes the surface entirely and makes the concurrency
token unnecessary.

## 2. A version-only compare-and-swap is vulnerable to ABA across drop and recreate

The first guard conditioned the update on `table.version = :expectedVersion` alone, comparing
the expected metadata location earlier and non-atomically at the service's `findById`.

`@Version` is a per-row counter that resets when a row is deleted and reinserted. Dropping a
table removes the House Tables row, and recreating inserts a fresh one at version 0. So:

1. The renamer reads the row: version 0, location `L0`. The token check passes.
2. The table is dropped and recreated. A different table, a new UUID, location `L9`, and the
   new row is also at version 0.
3. The rename's `WHERE ... AND version = 0` matches the new row, overwrites its
   `metadataLocation` with the `L0`-derived file, and renames it.

The new incarnation then points at the previous incarnation's metadata, carrying a different
table UUID inside it. That is cross-incarnation corruption rather than a lost snapshot, and
it is a worse outcome than the defect the guard was added to prevent.

The fix conditions the update on the observed metadata location as well as the observed
version. Locations are `NNNNN-<uuid>.metadata.json` and are never reused, so `L9` does not
match `L0` and the update affects no rows. Binding the predicate to the location observed at
`findById`, rather than to the caller's optional token, extends the protection to callers
that declare no base.

This mirrors why Iceberg carries `assert-table-uuid` as a requirement separate from its ref
assertions: names get reused, so identity must be asserted independently of position.

A secondary benefit is that the guard becomes self-contained. The two-step version check was
sound only while every other writer bumped `@Version`, which held but was stated nowhere.

## 3. The token-absent mode remains exposed

The concurrency token is optional, so that callers predating it keep working. When it is
omitted, nothing compares the renamer's loaded state against the row, and a commit landing
between the renamer's load and the service's read is still overwritten. The version
increments correctly in that case, so the loss leaves no anomaly in the row afterward.

Closing this means rejecting renames that declare no base. Two things gate it: client
rollout, which needs a metric counting tokenless renames at the endpoint and a period of it
reading zero; and the `INITIAL_VERSION` fallback for legacy tables with no persisted
`tableLocation`, which need either a backfill or a token distinguishing "no base exists" from
"the caller omitted the base".

## Status

| Finding | State |
|---|---|
| 1. Denormalized identity forces rename to rewrite metadata | Open. Not fixed by any change; the register's framing of blocking finding 3 should be rewritten around it |
| 2. Version-only CAS is ABA-vulnerable | Fixed. The update now conditions on the observed version and the observed metadata location, with a regression test calibrated to fail without the location predicate |
| 3. Token-absent mode remains exposed | Open by design, with the two gates above named |
