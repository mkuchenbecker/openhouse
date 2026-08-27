# testing-review — PR #36 (claude/tla-driven-commit-fixes @ 0c4828d), EVALUATE mode

Contract inventory: the changed claims are (1) `renameTableId` matches only a
row at `expectedVersion`, bumps `@Version` atomically, returns rows matched;
(2) `renameUserTable` throws `NoSuchUserTableException` on a missing source
row, `EntityConcurrentModificationException` on a stale declared token and on
a 0-row update, still succeeds with a null token, and preserves
`AlreadyExistsException` on target collision; (3) the controller accepts an
optional `expectedMetadataLocation` (omitted = old behavior, stale = 409,
match = 204); (4) the internal-catalog client transmits the token on the wire
and maps 409 to `HouseTableConcurrentUpdateException`; (5) doCommit's rename
branch declares `houseTable.getTableVersion()` as the token, null when
`INITIAL_VERSION`, and a conflict surfaces as retriable
`CommitFailedException`; (6) the H2 fixtures mirror the HTS token guard.

Control-flow partitions: token {null, match, stale}; source row {present,
absent}; version {current, advanced between read and update — a forced
interleaving}; target {free, taken}; doCommit base {persisted, INITIAL_VERSION}.

## SHOULD × DOES map

| # | Claim / partition | SHOULD (type; level; oracle) | DOES | Verdict |
|---|---|---|---|---|
| 1 | UPDATE at current version → 1 row, version bumped | Contract; contract (H2 repo); delta assert | `HtsRepositoryTest#testRenameUserTable` (asserts rows==1, version==old+1) | met |
| 2 | UPDATE at stale version → 0 rows, winner intact | Interleaving (forced); contract; delta assert | `HtsRepositoryTest#testRenameUserTableAtStaleVersionUpdatesNoRows` | met |
| 3 | Service: stale declared token → conflict, row untouched | Contract; contract; delta assert | `UserTablesServiceTest#testUserTableRenameFailsOnStaleExpectedMetadataLocation` | met |
| 4 | Service: commit lands between read and UPDATE → conflict | Interleaving (forced via spy `findById` freeze); contract | `UserTablesServiceTest#testUserTableRenameConflictsWithConcurrentCommit` — legitimate mock (forced interleaving), reset in `@BeforeEach` so no order leak | met |
| 5 | Service: null token still renames | Contract; contract | `#testUserTableRenameWithoutExpectedMetadataLocation` | met |
| 6 | Service: missing source row → NoSuchUserTable | Contract; contract | existing `HtsControllerTest#testRenameUserTableFails` (404); with-token variant converges (`findById.orElseThrow` precedes the token check) — pruned | met (convergence) |
| 7 | Service: target taken → AlreadyExists | Contract; contract | existing `#testUserTableRenameFails` (409-path via `DataIntegrityViolation`); token variant converges after the check — pruned | met (convergence) |
| 8 | HTTP: token match → 204; stale → 409; omitted → 204 | Contract; contract (MockMvc) | `HtsControllerTest#testRenameUserTableWithExpectedMetadataLocation`, `#...ConflictsOnStaleExpectedMetadataLocation` (with intact-row delta), existing `#testRenameUserTable` | met |
| 9 | Client transmits the token on the wire | Contract; contract (MockWebServer) | `HouseTableRepositoryImplTest#testRepoRenamePassesExpectedMetadataLocation` — weak oracle, see finding 1 | met, strengthen |
| 10 | Client maps rename 409 → HouseTableConcurrentUpdateException | Fault-injection; contract | existing `#testRepoRenameFailsWithExceptions` (map includes 409) | met |
| 11 | doCommit rename declares token; INITIAL_VERSION → null | Contract (outbound port, mock verify legitimate); contract | `OpenHouseInternalTableOperationsTest#testDoCommitRenamePassesExpectedMetadataLocation`, `#...WithoutPersistedBaseOmits...` | met |
| 12 | Rename conflict → retriable CommitFailedException | Contract; contract | `#testDoCommitExceptionHandling` proves the shared catch for `HouseTableConcurrentUpdateException` via the save path; the rename call sits in the same try — pruned by convergence | met (convergence) |
| 13 | Stale writer's save conflicts after a rename (version-bump consequence) | — | bump proven (row 1) + save-at-wrong-version conflict proven (existing `HtsRepositoryTest`, version 100L save throws); composition converges | met (convergence) |
| 14 | H2 fixture conflict branch behaves like HTS | Composition; composition | nothing drives the fixtures' mismatch branch — see finding 2 | gap |

## Findings

### 1. The wire-transmission test asserts the parameter's presence, not its value
- **location**: iceberg/openhouse/internalcatalog/src/test/java/com/linkedin/openhouse/internal/catalog/repository/HouseTableRepositoryImplTest.java:363-365
- **principle**: 3 (Oracle strength)
- **claim**: the only assertion on the recorded rename request is
  `getPath().contains("expectedMetadataLocation=")`, which passes even if the
  client sends an empty or wrong value.
- **evidence**: `Assertions.assertTrue(renameRequest.getPath().contains("expectedMetadataLocation="), ...)`
- **failure scenario**: a client-regeneration or encoding regression sends
  `expectedMetadataLocation=` (empty) or a different field's value; HTS then
  never token-checks (null/mismatch semantics change) while this test stays
  green.
- **decision**: strengthen in place — assert the query parameter's decoded
  value equals `HOUSE_TABLE.getTableLocation()` (contract test, contract
  level, example oracle). One-line change.
- **severity**: suggestion
- **confidence**: confirmed
- **reviewer**: testing-review

### 2. The H2 fixtures' conflict branch is fake behavior no test exercises
- **location**: services/tables/src/test/java/com/linkedin/openhouse/tables/e2e/h2/HouseTablesH2Repository.java:45-49 and tables-test-fixtures/tables-test-fixtures-iceberg-1.2/src/main/java/com/linkedin/openhouse/tablestest/HouseTablesH2Repository.java:68-72
- **principle**: 5 (Fakes answer for the wrong system); 4 (coverage — a question never asked)
- **claim**: both fixtures grow a token-mismatch branch that throws
  `HouseTableConcurrentUpdateException`, but every services/tables e2e rename
  goes down the happy path, so the branch — and the fixtures' fidelity to the
  real HTS 409 semantics — is never checked by any test.
- **evidence**: services/tables e2e rename tests (TablesControllerTest.java:1694-1733)
  perform only successful renames; no tables-side test constructs a stale
  token.
- **failure scenario**: the fixture's guard drifts from HTS (e.g. compares the
  wrong field, or HTS later normalizes locations); services/tables e2e stays
  green while real deployments 409 or, worse, skip the check — false
  confidence from an unverified model.
- **decision**: write the named test — composition level, in services/tables
  e2e: seed a table, advance its HTS row (fixture save), then drive a rename
  whose token is stale through the catalog and assert the surfaced conflict
  (`CommitFailedException`/409 rendering). Alternatively (cheaper, weaker):
  a fixture-level test asserting the fixture throws on mismatch, labeled as a
  fake-fidelity pin.
- **severity**: suggestion
- **confidence**: confirmed
- **reviewer**: testing-review

### 3. `testRenameCaseSensitivity` guesses version 0 and discards the row count
- **location**: services/housetables/src/test/java/com/linkedin/openhouse/housetables/e2e/usertable/HtsRepositoryTest.java:273-281
- **principle**: 4 (a test proves it ran)
- **claim**: the updated call hardcodes `0L` as the expected version and
  ignores the returned row count; the test only works because a freshly saved
  row happens to start at version 0.
- **evidence**: `htsRepository.renameTableId(..., TEST_TUPLE_1_1.getTableLoc(), 0L);` with no assertion on the return.
- **failure scenario**: none ships uncaught today (a silent 0-row no-op would
  fail the later fetch assertions), but the test's failure mode would read as
  "case sensitivity broke" instead of "seed version assumption broke" —
  misdirecting the responder.
- **decision**: fix in place — use the saved row's `getVersion()` and assert
  the return is 1, mirroring `testRenameUserTable`.
- **severity**: nit
- **confidence**: confirmed
- **reviewer**: testing-review

### 4. The mock-server drain loop can absorb up to 5 s per missing request
- **location**: iceberg/openhouse/internalcatalog/src/test/java/com/linkedin/openhouse/internal/catalog/repository/HouseTableRepositoryImplTest.java:350-359
- **principle**: 4 (the harness is stateless even when the subject is stateful)
- **claim**: the new test drains a static, suite-shared `MockWebServer`
  request queue with a 5-second `takeRequest` timeout to find its own request
  — a workaround for shared fixture state rather than a fresh world per case.
- **evidence**: the in-test comment says so: "The static mock server records
  requests across tests; drain to the rename request."
- **failure scenario**: on a regression (request never sent) the test spends
  5 s before failing; more tests copying the pattern compound suite time and
  keep the shared-queue coupling alive.
- **decision**: keep (the pattern matches the file's existing static-server
  design; a per-test server is a larger refactor out of this PR's scope) —
  recorded so the next author does not treat it as the preferred idiom.
- **severity**: nit
- **confidence**: confirmed
- **reviewer**: testing-review

## Verdict

Ship. The gating claims — the conditional UPDATE, the version bump, both
conflict channels, the null-token fallback, HTTP surfacing, the wire pass-through,
and the doCommit token derivation — each have a test at their cheapest
falsifying level, the two race partitions use forced interleavings rather than
stress, and the conflict tests assert winner-intact deltas, not just throws.
The two suggestions (a value assertion on the wire test, exercising the
fixtures' conflict branch) harden the instrument but do not gate: the claims
they touch are already falsified at the housetables level.
