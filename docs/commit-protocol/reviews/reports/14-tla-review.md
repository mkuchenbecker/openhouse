# TLA+ formal-methods review — PR #36 (claude/tla-driven-commit-fixes @ 0c4828d)

Charter: formal-methods reviewer. Authoritative sources: TLA+/TLC semantics and
the base spec `docs/commit-protocol/tla/OpenHouseCommit.tla` at
origin/claude/openhouse-commit-protocol-cl3xg9 (fetched and read in full).

## 1. Base-spec extension fidelity: faithful

Side-by-side comparison of `specs/tla/OpenHouseCommitRename.tla` against the
base module: every base action (`Load`, `AbortDiverged`, `AbortDedup`,
`WriteMetadata`, `HtsCommit`, `HtsConflict`, `Crash`) is preserved verbatim,
with the four renamer variables correctly added to every `UNCHANGED` tuple.
`TypeOK`/`Init` extend consistently; `history`'s `by` field widens from
`Writers` to `Actors = Writers ∪ {"renamer"}`; `Terminating` additionally
requires `rstate = "done"` (which is why `-deadlock` is needed, as the README
says). Both invariants are textually identical to the base spec's. No base
behavior was weakened or dropped.

## 2. TLC re-run: committed logs are authentic and reproduce exactly

Re-ran all three configs with the scratchpad `tla2tools.jar` (single worker,
matching the committed runs):

| Config | Re-run result | Committed log | Match |
|---|---|---|---|
| rename_unguarded | `NoSnapshotLoss` violated; 348 generated / 183 distinct, depth 7 | tlc-rename-unguarded.out | byte-identical except timestamps |
| rename_unguarded_version | `MonotonicVersion` violated; 48 / 28 | tlc-rename-unguarded-version.out | byte-identical except timestamps |
| rename_guarded | no error; 18,135 / 6,130, depth 18, complete graph | tlc-rename-guarded.out | counts identical |

The violation trace matches the claimed interleaving: renamer loads at loc 0
(snaps {}); w1 commits (ver 1, loc 1, snaps {w1}); `RenameWriteMetadata`
allocates file loc 2 from the stale view; `RenameHtsUnguarded` lands
unconditionally → catLoc 2, catSnaps {}, catVer still 1, so
`everCommitted = {w1} ⊄ {} = catSnaps`. The `MonotonicVersion` trace shows the
no-bump rewrite (`catVer = 0` after a rename commit appended to history).

## 3. Model → code mapping

| Model element | Code | Verdict |
|---|---|---|
| `RenameLoad`: `rBase' = catLoc` (token = row's location at load) | doCommit stamps `tableVersion` from the loaded metadata's `tableLocation` property (OpenHouseInternalTableOperations.java:274-277); rename branch sends `houseTable.getTableVersion()` (:400-403) | faithful; same string channel and exact-equality convention the PUT path's `UserTableVersionMapper` already uses; HTS persists locations scheme-less (ITO comment :261) so equality is exact |
| `RenameHtsGuarded` guard `catLoc = rBase`, `catVer' = catVer + 1`, atomic | service compares token to `row.metadataLocation` at `findById` time (UserTablesServiceImpl.java:151-175), then JPQL UPDATE conditions on `version = :expectedVersion` and bumps it (UserTableHtsJdbcRepository.java:128-129) | equivalent, via a load-bearing invariant — see finding 2 |
| 0-row → 409 → retriable conflict (`RenameHtsConflict`) | `updatedRows == 0` → `EntityConcurrentModificationException` → 409 → client `HouseTableConcurrentUpdateException` → `CommitFailedException` (verified through OpenHouseExceptionHandler.java:130-138, HouseTableRepositoryImpl.java:192, OpenHouseInternalTableOperations.java:459-462) | faithful; the model's reload-and-retry is an over-approximation (code propagates the failure to the caller instead of retrying), which is sound for safety invariants |
| `RenameHtsUnguarded` = pre-fix code | old JPQL: no version predicate, no bump | faithful |
| Rename bypasses commit-path guards (no SNAPSHOTS_JSON/COMMIT_KEY) | `abortIfWriterBaseDivergedFromCatalog` early-returns for non-snapshot commits (ITO:620-623) | faithful |

## Findings

### 1. The token-absent rename mode is not modeled, but the code ships it
- **location**: specs/tla/OpenHouseCommitRename.tla:278-296 (RenameHtsGuarded); specs/tla/README.md:16 ("All invariants hold"); code path UserTablesServiceImpl.java:160 (`expectedMetadataLocation != null` skip) and OpenHouseInternalTableOperations.java:400-410 (`INITIAL_VERSION` → null)
- **principle**: model completeness (the verified model must cover the shipped modes)
- **claim**: `RenameHtsGuarded` always carries `rBase`; the code additionally
  ships a null-token mode (old clients during rollout, and the
  `INITIAL_VERSION` fallback for legacy tables with no `tableLocation`
  property) whose guard is strictly weaker — version-CAS between HTS's own
  read and UPDATE only — and that mode still admits the original race in the
  window between the renamer's load and the HTS read. TLC's clean pass
  therefore proves the token-present mode only, while the README states "All
  invariants hold" unscoped.
- **evidence**: with a null token the service read at request time supplies
  `fromUserTableRow.getVersion()` for the CAS, so a commit landing before
  that read is accepted and its metadataLocation overwritten — exactly the
  modeled `RenameHtsUnguarded` outcome, shifted to a narrower but non-empty
  window. The PR body's Additional Information admits this ("even without it
  the rename now conflicts... between the rename's own read and update").
- **failure scenario**: a legacy table (no persisted `tableLocation`) is
  renamed while a commit is in flight; the rename carries no token, lands on
  the post-commit row, and the commit's metadata is silently replaced —
  the class of loss the PR claims TLC-verified closure for.
- **options**: (a) model the null-token mode (a third rename action guarded
  only on version continuity from a `RenameServiceRead` step) — TLC will
  produce the residual counterexample, which is the honest artifact and
  motivates why the catalog always passes the token; (b) scope the README
  and spec-header claim to the token-present mode. Either is small; (a) is
  the one a formal-methods reader will want.
- **severity**: suggestion (the residual window is real but narrow, pre-dates
  the PR, and the PR strictly shrinks it; the defect here is the unscoped
  verification claim, not a regression)
- **confidence**: confirmed
- **reviewer**: tla-review

### 2. The modeled atomic guard maps to a two-step code guard whose equivalence rests on an unstated invariant
- **location**: specs/tla/OpenHouseCommitRename.tla:27-31 and :278-284 (header and RenameHtsGuarded describe the UPDATE as "conditional on ... expected metadataLocation + @Version"); UserTableHtsJdbcRepository.java:128-129
- **principle**: refinement soundness (the abstraction must name what it collapses)
- **claim**: the real JPQL conditions on `@Version` only; the expected
  metadataLocation is checked earlier, non-atomically, at the service's
  `findById`. The collapse into one atomic `catLoc = rBase` predicate is
  sound if and only if every `UserTableRow` mutation bumps `@Version` —
  verified true today (`renameTableId` is now the sole JPQL UPDATE; all other
  writes are versioned `save`s or deletes) — but neither the spec header nor
  the README states this invariant, and both describe the SQL as conditioning
  on the metadataLocation, which it does not.
- **evidence**: `"... AND table.version = :expectedVersion"` is the entire
  concurrency predicate; the token comparison sits at
  UserTablesServiceImpl.java:160-161, a separate read.
- **failure scenario**: a future maintainer adds another JPQL UPDATE on
  `UserTableRow` (or drops the bump) without realizing the TLC result depends
  on version-bumps-on-every-write; the model stays green while its refinement
  to the code silently breaks.
- **options**: (a) one sentence in the spec header/README stating the
  two-step decomposition and the bump-on-every-write invariant it relies on;
  (b) additionally condition the JPQL on `metadataLocation = :expected...`
  when a token is present, making the SQL match the model literally. (a) is
  sufficient; (b) is defense-in-depth.
- **severity**: suggestion
- **confidence**: confirmed
- **reviewer**: tla-review

### 3. The README's "abridged" counterexample reorders the committed trace's steps
- **location**: specs/tla/README.md:52-59
- **principle**: evidence fidelity (a quoted trace should match its source)
- **claim**: the README presents the trace as `RenameLoad` → `Load(w1)` →
  `WriteMetadata/HtsCommit(w1)` → ..., while tlc-rename-unguarded.out's
  actual order is `Load(w1)`, `WriteMetadata(w1)`, `RenameLoad`,
  `HtsCommit(w1)`, ... The two orders are semantically interchangeable
  (the commuted actions are independent), but the text says the trace is
  "from `tlc-rename-unguarded.out`".
- **evidence**: State 2-4 of the committed log (Load line 145, WriteMetadata
  line 178, RenameLoad line 232) versus README items 1-3.
- **failure scenario**: a reader diffing the README against the log concludes
  the log was regenerated after the README and mistrusts both.
- **severity**: nit
- **confidence**: confirmed
- **reviewer**: tla-review

## Notes (no finding)

- Rollout ordering: a new internal-catalog client against an old HTS silently
  drops the unknown query parameter (Spring ignores it) and the old
  unconditional UPDATE remains — the guarantee lands only once HTS is
  deployed. Benign and standard, but worth knowing for the rollout plan.
- The commit-budget coupling (`RenameWriteMetadata` consumes the same
  `nextLoc` budget as writers) is a sound abstraction: it only bounds the
  state space.
- Small-scope argument holds: the violated class needs one writer plus the
  renamer; {w1, w2} × MaxCommits 4 is ample, and the guarded run's state
  graph is complete (0 states on queue).

## Verdict on model-vs-code fidelity

The guarded model is faithfully implemented for renames that carry the token
— every modeled guard, bump, and failure channel maps to verified code, and
the committed TLC evidence is genuine and reproducible bit-for-bit. The two
gaps are documentation-shaped: the null-token mode ships outside the verified
envelope (finding 1), and the model's atomicity collapse relies on an
unstated, currently-true invariant (finding 2). Neither blocks merge; both
should be written down before the spec is cited as proof.
