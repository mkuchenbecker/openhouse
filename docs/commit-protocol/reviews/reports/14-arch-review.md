# arch-review — PR #36 (claude/tla-driven-commit-fixes @ 0c4828d)

Scope: the rename-guard change across services/housetables (controller → handler →
service → JPQL repository), iceberg/openhouse/internalcatalog (doCommit rename
branch, HouseTableRepository contract, HTS client impl), and the two H2 test
fixtures. Orientation: housetables is the authoritative commit-point service;
internalcatalog is its client, talking through a build-time-generated HTS client
regenerated from the controller's OpenAPI spec (compile-checked, no hand-edited
client). The commit (save) hot path is untouched; the rename branch swaps one
existsById for one findById — no extra round trip.

Wiring verified end-to-end: token check / 0-row CAS →
`EntityConcurrentModificationException` → `OpenHouseExceptionHandler` 409
(services/common/.../OpenHouseExceptionHandler.java:130-138) → client
`WebClientResponseException.Conflict` → `HouseTableConcurrentUpdateException`
(HouseTableRepositoryImpl.java:192) → doCommit catch → `CommitFailedException`
(retriable) (OpenHouseInternalTableOperations.java:459-462). The token the rename
branch declares (`houseTable.getTableVersion()`) is stamped at
OpenHouseInternalTableOperations.java:274-277 from the pre-commit `tableLocation`
property — the same token the PUT path's `UserTableVersionMapper` compares by
exact string equality, so the two CAS channels share one convention.

## Findings

### 1. The concurrency token rides in a repurposed DTO field, so the contract lives in a comment
- **location**: services/housetables/src/main/java/com/linkedin/openhouse/housetables/api/handler/OpenHouseUserTableHtsApiHandler.java:116-117 (and UserHouseTablesController.java:254-260)
- **principle**: Minimal knowledge; Narrow contracts, one seam
- **claim**: `expectedMetadataLocation` is smuggled to the service through
  `fromUserTable.metadataLocation`, a field whose normal meaning ("the table's
  metadata location") is here reassigned to "the location the caller observed";
  nothing in the `renameEntity(UserTable, UserTable)` signature states this, only
  the comment above the call.
- **failure scenario**: a future maintainer of the handler (or the generic
  `renameEntity` interface, which JobTables also implements) populates
  `fromUserTable.metadataLocation` from the stored row "for completeness" — the
  token check then always passes trivially and the guard this PR adds is
  silently disabled, with no compile-time signal.
- **options**: (a) as-is — the comment plus controller `@Parameter` doc carry
  it, and the field reading "the from-table state the caller observed" is a
  semantically defensible fit; (b) enabling refactor — a dedicated
  `expectedMetadataLocation` parameter on the HTS handler's rename method;
  touches the generic handler interface and its Jobs implementation, still
  module-internal to services/housetables. (b) is small and makes the misuse
  unrepresentable.
- **severity**: suggestion
- **confidence**: confirmed
- **reviewer**: arch-review

### 2. Fabricated `new RuntimeException()` causes render "null" into the 409 body
- **location**: services/housetables/src/main/java/com/linkedin/openhouse/housetables/services/UserTablesServiceImpl.java:162-174 and :196-211 (the two new throw sites)
- **principle**: Failures flow low to high (the cause chain is the record of the descent)
- **claim**: both new conflict throws attach a message-less `new
  RuntimeException()` as cause; `OpenHouseExceptionHandler` renders the 409 body
  as `... nested exception message: %s` with `cme.getCause().getMessage()`
  (OpenHouseExceptionHandler.java:47-48, :138), so clients see literally
  `nested exception message: null`.
- **failure scenario**: an operator debugging a rename 409 reads the response
  body; the actual/expected location detail available at the throw site was
  discarded, and the trailing "null" suggests a broken handler rather than a
  deliberate conflict.
- **options**: (a) as-is — this copies the pre-existing convention
  (`UserTableVersionMapper.java:24-44` does the same, so PUT-path 409s already
  read this way); (b) enabling micro-fix — give the cause a message (or add a
  cause-less constructor and make the handler null-tolerant), which fixes the
  new sites without touching the convention elsewhere.
- **severity**: nit (pre-existing convention shapes the grade)
- **confidence**: confirmed
- **reviewer**: arch-review

### 3. `renameTableId`'s conflict signal is a discardable int, and one in-tree caller already discards it
- **location**: services/housetables/src/main/java/com/linkedin/openhouse/housetables/repository/impl/jdbc/UserTableHtsJdbcRepository.java:126-130
- **principle**: Outcomes as values (a result that can be silently discarded is a swallowed failure)
- **claim**: the 0-rows-matched conflict outcome exists only as an `int` the
  caller must remember to check; the javadoc states the obligation but nothing
  enforces it, and `HtsRepositoryTest#testRenameCaseSensitivity`
  (services/housetables/src/test/.../HtsRepositoryTest.java:273-281) already
  calls it with a guessed version `0L` and ignores the return.
- **failure scenario**: a second production caller appears (e.g. a cross-database
  rename later), calls `renameTableId`, skips the check, and a 0-row conflict is
  reported as success — exactly the lost-update class this PR closes, reopened
  one layer down.
- **options**: (a) as-is — single production caller, checked; (b) enabling
  refactor — annotate `@CheckReturnValue` (error-prone is already on the
  build) or have the service-facing seam be the only caller by package-privacy
  convention. (b) is one annotation.
- **severity**: suggestion
- **confidence**: confirmed
- **reviewer**: arch-review

### 4. `HouseTableRepository.rename` documents "a concurrent update conflict" without naming the outcome type
- **location**: iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/repository/HouseTableRepository.java:37-49
- **principle**: Enumerable outcomes
- **claim**: the new javadoc promises the rename "fails with a concurrent update
  conflict" but does not name `HouseTableConcurrentUpdateException`, the type
  callers (doCommit's catch at OpenHouseInternalTableOperations.java:459) build
  retry policy on; the sibling `UserTablesService.renameUserTable` javadoc does
  name its exception.
- **failure scenario**: an implementer of this interface (there are three:
  Impl + two H2 fixtures) signals the conflict with a different unchecked type;
  doCommit's catch misses it and a conflict escapes as a non-retriable failure.
- **options**: (a) as-is; (b) add `@throws HouseTableConcurrentUpdateException`
  to the javadoc — zero blast radius.
- **severity**: nit
- **confidence**: confirmed
- **reviewer**: arch-review

## Notes (no finding)

- The split guard — token compared non-atomically at read
  (UserTablesServiceImpl.java:160-165), UPDATE conditioned on `@Version` only
  (UserTableHtsJdbcRepository.java:135-137) — is sound because every
  metadataLocation mutation now bumps `@Version` (JPA save always did; rename
  now does). The composition, not either check alone, is what implements the
  guarded model; flagged to the TLA+ reviewer for model-fidelity confirmation.
- Backward compatibility of the API is real: `expectedMetadataLocation` is an
  optional request param, old clients omit it and get the server-side CAS only;
  the generated HTS client regenerates from the OpenAPI spec so the 6-arg
  signature is compile-checked, not hand-maintained.
- The two H2 fixture copies of the guard (services/tables test fixture and
  tables-test-fixtures) sit on opposite sides of a module boundary that exists
  for iceberg-version variants; per the anti-noise commitment their duplication
  is not a finding. Their fidelity to the real JPQL CAS is a testing-review
  question.
- 0-row conflation of "concurrently modified" with "concurrently deleted"
  (both → 409) is defined, documented behavior and both are genuine concurrent
  interference; no finding.
