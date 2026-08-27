# TLA+-driven commit fixes — draft PR (rename lost-update)

**PR: https://github.com/mkuchenbecker/openhouse/pull/36 (draft, base `main`, branch `claude/tla-driven-commit-fixes`)**

Two commits on the branch (worktree `/home/user/worktrees/tla-fixes`, off `origin/main` @ 2a9dac8):
1. `docs: add TLA+ model of commit protocol rename path` (specs/tla/)
2. `fix: guard HTS rename against concurrent commits via optimistic locking` (code + tests, 15 files, +450/−33)

## What changed (files)

Spec (new directory `specs/tla/`):
- `OpenHouseCommitRename.tla` — the OpenHouseCommit spec extended with a Renamer process
  (RenameLoad / RenameWriteMetadata / RenameHtsUnguarded / RenameHtsGuarded / RenameHtsConflict /
  RenameCrash); constant `RenameGuard` toggles current-code vs fixed rename.
- `rename_unguarded.cfg`, `rename_unguarded_version.cfg`, `rename_guarded.cfg` + captured TLC
  output `tlc-rename-*.out` (renamed from .log — repo .gitignore excludes `*.log`) + `README.md`
  with repro commands (`java -cp tla2tools.jar tlc2.TLC -deadlock -config <cfg> OpenHouseCommitRename.tla`).

Code (main):
- `services/housetables/.../repository/impl/jdbc/UserTableHtsJdbcRepository.java` — `renameTableId`
  now `@Modifying(clearAutomatically=true)`, JPQL adds `table.version = table.version + 1` and
  `AND table.version = :expectedVersion`, returns `int` rows matched.
- `services/housetables/.../services/UserTablesService(Impl).java` — `renameUserTable` gains
  `expectedMetadataLocation`; reads the row (`findById`, replaces `existsById`), rejects a stale
  declared base and a 0-row conditional update with `EntityConcurrentModificationException` (→ 409
  via existing `OpenHouseExceptionHandler`); `DataIntegrityViolationException` → `AlreadyExistsException` preserved.
- `services/housetables/.../api/handler/OpenHouseUserTableHtsApiHandler.java` — passes
  `fromUserTable.metadataLocation` as the token.
- `services/housetables/.../controller/UserHouseTablesController.java` — `PATCH /hts/tables/rename`
  gains optional `expectedMetadataLocation` request param (documented; API-compatible).
- `iceberg/openhouse/internalcatalog/.../repository/HouseTableRepository(.Impl).java` — `rename`
  carries the expected base through the build-time-generated HTS client (client regenerates from the
  service OpenAPI spec, so no hand-edited client code).
- `iceberg/openhouse/internalcatalog/.../OpenHouseInternalTableOperations.java` (doCommit rename
  branch, ~line 395) — passes `houseTable.getTableVersion()` (the pre-commit tableLocation, the
  exact CAS token the save path uses; `null` when `INITIAL_VERSION`). 409 → existing
  `HouseTableConcurrentUpdateException` → `CommitFailedException` (retriable) wiring unchanged.

Test fixtures (shared by services/tables suites; both updated to the 6-arg signature + conflict guard):
- `tables-test-fixtures/tables-test-fixtures-iceberg-1.2/.../HouseTablesH2Repository.java` (also
  compiled into the iceberg-1.5 fixture via shared srcDirs)
- `services/tables/src/test/.../e2e/h2/HouseTablesH2Repository.java`

## TLC evidence

| Config | RenameGuard | Result | States |
|---|---|---|---|
| rename_unguarded.cfg | FALSE | **NoSnapshotLoss VIOLATED** | 348 generated / 183 distinct, trace depth 7 |
| rename_unguarded_version.cfg | FALSE | **MonotonicVersion VIOLATED** (no version bump) | 48 / 28 |
| rename_guarded.cfg | TRUE | **No error** (TypeOK, NoSnapshotLoss, MonotonicVersion) | 18,135 / 6,130, depth 18, complete graph |

All configs run with `DivergenceCheck = TRUE` (post-#612-fix commit path) — i.e. the unguarded
rename is a *residual* hole on top of the fixed protocol, as the report 05 recommendation predicted.
Violation trace (tlc-rename-unguarded.out): RenameLoad@loc0(snaps {}) → w1 Load/WriteMetadata/HtsCommit
(ver 1, loc 1, snaps {w1}) → RenameWriteMetadata (file loc 2 from stale view) → RenameHtsUnguarded lands
unconditionally: catLoc=2, catSnaps={}, catVer still 1 — everCommitted {w1} ⊄ {} = catSnaps.
Each run < 1 s under OpenJDK 21 with the scratchpad tla2tools.jar.

## Fix shape (mirrors the TLC-verified guarded model)

Two layers, matching `RenameHtsGuarded`'s guard `catLoc = rBase` + `catVer' = catVer + 1`:
1. DB-level CAS always on: the rename UPDATE is conditional on the `@Version` the service just read
   and bumps it atomically; 0 rows ⇒ 409 (closes the read→update race inside HTS regardless of caller).
2. Caller-declared base end-to-end: the internal catalog's rename commit declares the writer's
   pre-commit metadata location; HTS 409s if the row moved past it (closes the load→commit race,
   same token semantics as the PUT path's `UserTableVersionMapper`).

## Tests (all green)

Gradle (Java 17; `-x CopyGitHooksTask` needed in a worktree since `.git` is a file):
- `:services:housetables:test` — BUILD SUCCESSFUL (full suite)
- `:iceberg:openhouse:internalcatalog:test` — BUILD SUCCESSFUL (85 tests)
- `:services:tables:test` — BUILD SUCCESSFUL (full e2e suite through the H2 fixture rename path)
- fixtures compile for both iceberg 1.2/1.5 variants; `spotlessApply` clean on all touched modules

New/updated tests:
- (a) version bump: `HtsRepositoryTest#testRenameUserTable` asserts version = old + 1.
- (b) rename-vs-commit race: `HtsRepositoryTest#testRenameUserTableAtStaleVersionUpdatesNoRows`
  (0 rows matched, winner intact) and `UserTablesServiceTest#testUserTableRenameConflictsWithConcurrentCommit`
  (freezes the rename's read at the pre-commit row via a `@SpyBean` `findById` stub, real conditional
  UPDATE runs against the advanced row → `EntityConcurrentModificationException`; committed
  metadataLocation intact; rename target absent) plus
  `#testUserTableRenameFailsOnStaleExpectedMetadataLocation` and
  `HtsControllerTest#testRenameUserTableConflictsOnStaleExpectedMetadataLocation` (HTTP 409).
- (c) happy path: `UserTablesServiceTest#testUserTableRename` (+ without-token variant),
  `HtsControllerTest#testRenameUserTable(WithExpectedMetadataLocation)`, existing case-sensitivity
  and AlreadyExists tests updated and passing.
- (d) internal-catalog path: `OpenHouseInternalTableOperationsTest#testDoCommitRenamePassesExpectedMetadataLocation`
  and `#...WithoutPersistedBaseOmitsExpectedMetadataLocation`;
  `HouseTableRepositoryImplTest#testRepoRenamePassesExpectedMetadataLocation` (recorded request
  carries the param) + existing rename tests updated; whole existing suites pass.

## PR

- Number/URL: **#36 — https://github.com/mkuchenbecker/openhouse/pull/36** (DRAFT)
- Mirrors the repo PR template sections (Summary / Changes / Testing Done / Additional Information);
  Summary states the TLA+ grounding (unguarded rename violates NoSnapshotLoss; fix implements the
  guarded model TLC proved safe).

## Known limitations

- The model keeps the base spec's abstractions: single table, one snapshot per writer, no snapshot
  expiration, no CommitStateUnknown window; a single renamer process; the identifier change itself
  (db/table id) is not modeled — only the metadataLocation overwrite, which is what loses data.
  AlreadyExists conflicts on the target name fold into RenameCrash.
- TLC verifies the bounded configs only (2 writers / MaxCommits 4); no TLAPS proof. The bug class
  needs only 1 writer + 1 renamer, so small scope is sufficient here.
- HTS compares `expectedMetadataLocation` by exact string equality (same convention as
  `UserTableVersionMapper`); the caller passes the same-channel token (previous `tableLocation`
  property), so scheme normalization is not needed on this path — but callers passing hand-built
  locations with a different scheme would get spurious 409s (fail-safe direction).
- When the caller supplies no token (old clients), the rename is still CAS-guarded between its own
  read and update, but a commit landing between the *caller's* load and the rename request cannot be
  detected — that requires the new parameter, which the internal catalog now always passes (except
  the defensive `INITIAL_VERSION` fallback).
- The renamed-row version bump means a client holding the pre-rename version now conflicts on its
  next save (correct, but a behavior change for anyone who relied on rename being version-invisible).
