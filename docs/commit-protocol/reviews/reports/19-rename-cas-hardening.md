# Rename compare-and-swap hardening

## The final predicate

`UserTableHtsJdbcRepository.renameTableId` now issues:

```
UPDATE UserTableRow table
   SET table.tableId = :toTableId,
       table.metadataLocation = :metadataLocation,
       table.databaseId = :toDatabaseId,
       table.version = table.version + 1
 WHERE lower(table.databaseId) = lower(:fromDatabaseId)
   AND lower(table.tableId) = lower(:fromTableId)
   AND table.version = :expectedVersion
   AND table.metadataLocation = :expectedMetadataLocation
```

`UserTablesServiceImpl.renameUserTable` binds both conditions from the row state observed at
`findById`: `fromUserTableRow.getVersion()` and `fromUserTableRow.getMetadataLocation()`. The
caller's optional `expectedMetadataLocation` token is deliberately not what the update conditions
on, so the guard protects the tokenless mode as well. The caller's token check stays exactly where
it was, before the update, because it answers a different question: whether the caller's own view of
the table is current.

The version predicate and the atomic version bump are unchanged. The location predicate is what
closes ABA. `@Version` is a per-row counter that restarts at 0 when a row is deleted and reinserted,
so a table dropped and recreated at the same identity presents a different table at the same version,
and a version-only condition would match it, overwrite the new incarnation's metadata location with a
file derived from the previous incarnation's, and rename it. Metadata locations are per-commit
`NNNNN-<uuid>.metadata.json` files that are never reused, so conditioning on the observed location
identifies a row state across incarnations in a way the version cannot. It also makes the guard
self-contained rather than dependent on the unstated invariant that every other writer bumps the
version.

## Null metadataLocation: unreachable, so strict equality is used

A null observed location would make `column = :param` never match and would break rename for that
row. That state is not reachable, so no null-safe formulation was added. The evidence:

| Path that writes a `user_table_row` | Why the location cannot be null |
|---|---|
| `UserTablesServiceImpl.putUserTable`, the only create-or-update path | `UserTable.metadataLocation` carries `@NotEmpty`, and `OpenHouseUserTableHtsApiValidator.validatePutEntity` runs bean validation on the entity before the service is called. |
| `UserTablesServiceImpl.restoreUserTable` | Copies the location from a soft-deleted row, which was itself copied from a user table row. |

A second, independent signal is that `UserTableVersionMapper.toVersion` dereferences
`existingUserTableRow.get().getMetadataLocation().equals(...)` with no null check on every update to
an existing row. A row carrying a null location would already fail with a `NullPointerException` on
its next PUT, long before any rename could reach it, so the codebase already treats non-null as an
invariant rather than a possibility.

The DDL column `metadata_location VARCHAR(512)` is nullable, but nullability in a schema marked
alpha is not a code path. Had the assessment been wrong, the strict predicate fails closed with an
HTTP 409 rather than corrupting a row, which is the safe direction to be wrong in. The invariant and
the reasoning are recorded on the `expectedMetadataLocation` parameter's javadoc so a future writer
who wants to relax `@NotEmpty` finds the dependency.

## The ABA test and its evidence

Two tests drive the scenario explicitly.

`HtsRepositoryTest#testRenameUserTableAfterDropAndRecreateUpdatesNoRows` seeds a row, captures its
version and location, deletes it, reinserts a different row at the same identity with a different
metadata location, asserts the recreated row landed back on exactly the observed version (the
premise of the race), then calls `renameTableId` with the originally observed version and location
and asserts zero rows matched and the new incarnation is untouched.

`UserTablesServiceTest#testUserTableRenameConflictsWithDropAndRecreate` is the service-level
equivalent in the idiom the neighbouring concurrency test already uses. It drops and recreates the
table through the service, freezes the rename's read at the previous incarnation with a Mockito
`doReturn` on `findById`, and asserts the rename raises `EntityConcurrentModificationException` while
the recreated table keeps its own metadata and no rename target appears.

Both fail without the fix. With the location predicate temporarily neutralised to a tautology over
the same bound parameter, leaving the version predicate intact, the repository test reported
`expected: <0> but was: <1>`, meaning the update matched and rewrote the new incarnation, and the
service test reported `Expected EntityConcurrentModificationException to be thrown, but nothing was
thrown`, meaning the rename silently landed on the wrong table. Restoring the predicate returns both
to green.

The two in-memory House Tables fixtures gained the same semantics: after the caller-token check,
each re-reads the row and rejects the rename unless it still carries the location observed by the
first read. `HouseTablesH2RepositoryRenameParityTest` on PR #37 gained a fourth mode covering this,
and removing the fixture guard fails those two parity cases, so the coverage is real rather than
vacuous.

## Test results

| Suite | Branch #36 | Branch #37 |
|---|---|---|
| `:services:housetables:test` | 136 passed, 0 failed | 138 passed, 0 failed |
| `:iceberg:openhouse:internalcatalog:test` | 85 passed, 0 failed | 85 passed, 0 failed |
| `:services:tables:test` | 480 passed, 0 failed | 485 passed, 0 failed |

`spotlessApply` was run and `spotlessCheck` is clean on both branches. Checkstyle reports only
warnings that predate this work, including the 178-character JPQL SET clause that the change moved
but did not lengthen. No new violation was introduced.

## Pushed shas

| Branch | PR | Sha |
|---|---|---|
| `claude/tla-driven-commit-fixes` | #36 | 9fedbce433b264d45496dcbf930eaa880f6e368a |
| `claude/tla-rename-followups` | #37 | 29ea7079fc3f02f300df8be2a3d76972bfb6e448 |

PR #37 was rebased onto the new #36 head with no conflicts, and its diff against #36 remains its own
two commits, touching neither `UserTablesServiceImpl` nor `UserTableHtsJdbcRepository`. The parity
commit was amended rather than supplemented so the branch keeps its two-commit shape.

PR #36's body was amended in two places and nowhere else: the `UserTableHtsJdbcRepository` row of the
Sketch table now states that the update is conditional on both the observed version and the observed
metadata location, and the Summary gained one sentence explaining that the location predicate is what
makes the guard immune to a version reset across drop and recreate.
