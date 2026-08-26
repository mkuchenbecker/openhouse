# TLA+ model of the OpenHouse commit protocol — rename path

`OpenHouseCommitRename.tla` is a TLA+ model of the OpenHouse table commit
protocol (the HTS single-row optimistic-locked UPDATE as the atomic commit
point, the tables-service `doCommit` pipeline, and its CAS layers), extended
with the **rename** path: `OpenHouseInternalCatalog.renameTable` →
`OpenHouseInternalTableOperations.doCommit` (rename branch) →
`PATCH /hts/tables/rename` → `UserTableHtsJdbcRepository.renameTableId`.

The `RenameGuard` constant toggles the rename fix:

| Config | `RenameGuard` | Meaning | TLC result |
|---|---|---|---|
| `rename_unguarded.cfg` | `FALSE` | `renameTableId` as an **unconditional** JPQL UPDATE (no `@Version` predicate, no version bump) | **`NoSnapshotLoss` violated** — a snapshot commit landing between the renamer's load and its UPDATE is silently clobbered (`tlc-rename-unguarded.out`) |
| `rename_unguarded_version.cfg` | `FALSE` | same, checking the version invariant | **`MonotonicVersion` violated** — the row is rewritten without a version bump (`tlc-rename-unguarded-version.out`) |
| `rename_guarded.cfg` | `TRUE` | rename carries the caller's expected base; the UPDATE is **conditional** on it (expected metadataLocation + `@Version`) and bumps `@Version` atomically; a 0-row match surfaces as a 409 conflict and the renamer retries from a fresh load | **All invariants hold** (`tlc-rename-guarded.out`) |

All configs run with the commit-path divergence check enabled
(`DivergenceCheck = TRUE`, i.e. the post-`abortIfWriterBaseDivergedFromCatalog`
protocol), which shows the unguarded rename is a *residual* lost-update
channel that the commit-path guards cannot see: a rename commit carries no
`SNAPSHOTS_JSON`/`COMMIT_KEY`, so both `abortIfWriterBaseDivergedFromCatalog`
and `failIfRetryUpdate` skip it, and the unconditional UPDATE bypasses the
HTS `@Version` CAS entirely.

The corresponding code fix in this repository makes the guarded model real:

- `UserTableHtsJdbcRepository.renameTableId` — conditional on the row's
  current `@Version`, bumps it atomically, reports rows matched;
- `UserTablesServiceImpl.renameUserTable` — verifies the caller's expected
  `metadataLocation` (when provided) and turns a 0-row update into an
  `EntityConcurrentModificationException` (HTTP 409);
- `OpenHouseInternalTableOperations` passes the renamer's base metadata
  location through `HouseTableRepository.rename`.

## Running TLC

Requires Java 11+ and `tla2tools.jar`
(<https://github.com/tlaplus/tlaplus/releases>):

```sh
cd specs/tla
java -cp tla2tools.jar tlc2.TLC -deadlock -config rename_unguarded.cfg OpenHouseCommitRename.tla   # violation
java -cp tla2tools.jar tlc2.TLC -deadlock -config rename_unguarded_version.cfg OpenHouseCommitRename.tla   # violation
java -cp tla2tools.jar tlc2.TLC -deadlock -config rename_guarded.cfg OpenHouseCommitRename.tla    # passes
```

(`-deadlock` disables deadlock reporting: the model intentionally terminates
once every writer and the renamer are done. Each run finishes in under a
second — the state spaces are 183 / 28 / 6130 distinct states.)

The abridged `NoSnapshotLoss` counterexample from `tlc-rename-unguarded.out`:

1. `RenameLoad` — renamer loads the table at location 0, snapshot set `{}`
2. `Load(w1)` — writer w1 loads at location 0, stages payload `{w1}`
3. `WriteMetadata(w1)` / `HtsCommit(w1)` — w1 commits: row moves to
   location 1, version 1, snapshots `{w1}`
4. `RenameWriteMetadata` — renamer writes metadata file 2 from its stale
   loaded state (snapshot set `{}`)
5. `RenameHtsUnguarded` — the unconditional UPDATE lands: row now points at
   location 2, snapshots `{}`, version still 1 — **w1's committed snapshot
   is lost** (`everCommitted = {w1} ⊄ catSnaps = {}`)

Model abstractions and their justifications are documented in the header
comment of `OpenHouseCommitRename.tla`.
