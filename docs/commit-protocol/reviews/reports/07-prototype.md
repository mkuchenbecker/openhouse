# Report 07 — Iceberg REST-Native Commit Path: Working Prototype

Implements Phase 0 (§6 "Prototype scope") of `docs/commit-protocol/appendix-d-rest-native-migration.md` on branch `claude/openhouse-commit-protocol-cl3xg9`. All code is additive; the legacy commit path is untouched in production code.

## What was built

### Production classes (all new, package `com.linkedin.openhouse.tables.resthandler`)

| Class | Path | Role |
|---|---|---|
| `IcebergRestCommitController` | `services/tables/src/main/java/com/linkedin/openhouse/tables/resthandler/IcebergRestCommitController.java` | `POST /v1/rest/namespaces/{namespace}/tables/{tableId}/commit` (prototype-private route). Deserializes the body to `org.apache.iceberg.rest.requests.UpdateTableRequest`, returns spec `LoadTableResponse` JSON (`metadata-location` + full metadata). Rejects multi-level (`%1F`-separated) namespaces with 400. |
| `IcebergRestCommitService` | `.../resthandler/IcebergRestCommitService.java` | Adapted copy of `CatalogHandlers.commit` (the ~60-line loop, incl. a local copy of the package-private `ValidationFailureException`): `Tasks.foreach(ops).retry(4).exponentialBackoff(...).onlyRetryOn(CommitFailedException)`; per attempt: fresh base (`current()`/`refresh()`), per-attempt policy gate, requirement validation (failure wrapped so it is never retried), `TableMetadata.buildFrom(base)` + `update.applyTo`, `taskOps.commit(base, updated)` on the **unmodified** `OpenHouseInternalTableOperations`. Store-level races (`HouseTableConcurrentUpdateException` → `CommitFailedException`) are retried server-side, invisible to the client. |
| `RestUpdateValidator` | `.../resthandler/RestUpdateValidator.java` | Request-shape checks (once, pre-loop): `SetProperties`/`RemoveProperties` touching `PreservedKeyChecker`-preserved keys (`openhouse.*`, `policies`) → 400; `AssignUUID` → 400. Per-attempt check against the fresh base (inside the loop, so a concurrent lock cannot be raced past): locked table (parsed from the `policies` property's `lockState.locked`) → 400. |
| `IcebergRestExceptionHandler` | `.../resthandler/IcebergRestExceptionHandler.java` | `@RestControllerAdvice(assignableTypes = IcebergRestCommitController.class)` — spec `ErrorResponse` bodies (`{"error":{message,type,code}}`) for the prototype route only: requirement failure / lost race → **409** `CommitFailedException`; ambiguous persistence → **500** `CommitStateUnknownException` (spec semantics, not OpenHouse's legacy 503); unknown table → 404; invalid payloads (`BadRequestException`, `ValidationException`, `IllegalArgumentException`) → 400. Legacy endpoints keep the OpenHouse error envelope. |
| `IcebergRestSerde` | `.../resthandler/IcebergRestSerde.java` | Dedicated Jackson mapper configured exactly like Iceberg's `RESTObjectMapper` (kebab-case, field visibility, `RESTSerializers.registerAll`). See "deviations" for why this is a wrapper component and not an `ObjectMapper` bean. |

Verified prerequisite: the `com.linkedin.iceberg:iceberg-core:1.5.2.17` fork jar contains all needed `rest/*` classes (`CatalogHandlers`, `RESTSerializers` incl. `UpdateTableRequest` serde, `UpdateRequirements`, `LoadTableResponse`, `ErrorResponse`) — confirmed by inspecting the resolved artifact, not just upstream sources.

No changes to: `OpenHouseInternalTableOperations`, `OpenHouseInternalRepositoryImpl`, HTS, or any legacy endpoint/service. The predicted degradation of `doCommit` with no smuggled properties held empirically (merge block skipped, catalog CAS early-return, `failIfRetryUpdate` metric-only, stamp → write → HTS save), including the `base == current()` identity check across loop retries.

### Test-only glue

- `SpringH2Application` (`services/tables/src/test/.../e2e/h2/`): added `com.linkedin.openhouse.tables.resthandler` to its explicit `@ComponentScan` list (the production `TablesSpringApplication` scans `com.linkedin.openhouse.tables` wholesale and needs no change).
- `HouseTablesH2Repository` (same dir): added a fault-injection hook — `SAVE_FAILURES` queue + `SAVE_ATTEMPTS` counter + a default-method `save` override that throws one queued exception per call and otherwise delegates through `saveAll`. Behavior is unchanged when the queue is empty; this simulates HTS store failures (lost `@Version` CAS, ambiguous 5xx) that plain H2 JPA cannot produce.

## Design deviations (and why)

1. **`IcebergRestSerde` component instead of a dedicated `ObjectMapper` bean.** The design called for "a dedicated `ObjectMapper` bean". Registering any raw `ObjectMapper` bean suppresses Spring Boot's `JacksonAutoConfiguration` (`@ConditionalOnMissingBean`), so the kebab-case Iceberg mapper silently became the service-wide MVC mapper and broke every legacy endpoint (discovered as 500s on `createTable` in e2e tests: `clusterId` deserialized as null). Wrapping the mapper in a non-`ObjectMapper` component keeps the isolation the design actually wanted.
2. **e2e tests live in `services/tables/src/test/.../e2e/h2/` (not a `resthandler` subpackage) and use the module's own `SpringH2Application` harness rather than `tables-test-fixtures`.** The repo's established e2e idiom for this module (`SnapshotsControllerTest` etc.) is the H2 Spring app + `MockMvc` + package-private helpers (`RequestAndValidateHelper`); `tables-test-fixtures` is the same stack packaged for *other* modules. Same-package placement was required to reuse those helpers. The validator unit tests do live in `src/test/.../resthandler/`.
3. **Store-race injection via repository hook, not a Mockito spy.** The design suggested "spy repository". `@SpyBean` on the interface-based Spring Data H2 repository cannot `callRealMethod` ("cannot call abstract real method") and its stubbing leaks through AOP proxy layers across tests; the deterministic queue/counter hook in `HouseTablesH2Repository` is strictly more reliable and asserts attempt counts exactly.
4. **Locked-table rejection maps to 400** (BadRequest) rather than 409 — consistent with the legacy path's `UnsupportedClientOperationException` handling; the design left the code open ("400/409").

## Test matrix (all green)

Modules: `:services:tables:test` and `:iceberg:openhouse:internalcatalog:test` — full suites pass (`BUILD SUCCESSFUL`, see tail below). 35 new tests total.

### `IcebergRestCommitControllerTest` (e2e, H2 HTS + local FS, 15 tests) — `services/tables/src/test/java/com/linkedin/openhouse/tables/e2e/h2/IcebergRestCommitControllerTest.java`

| Design case | Test | Key assertions |
|---|---|---|
| (a) append happy path | `testSnapshotAppendHappyPath` | 200; response `metadata-location` == HTS row location; metadata has S1, `main`→S1; `openhouse.tableVersion` == prior location; exactly one new metadata.json |
| (b) same-ref conflict | `testConcurrentSameRefCommitConflict` | winner 200, loser 409 `CommitFailedException`; loser wrote no metadata.json; HTS pointer untouched; loser's snapshot absent |
| (c) independent commits | `testIndependentCommitReappliedOnAdvancedBase` (real interleave: property request derived at T1 lands after a racing snapshot commit → 200, final metadata has both) and `testStoreLevelRaceRetriedServerSideInvisibleToClient` (injected one-shot HTS CAS failure → single 200 to client, `SAVE_ATTEMPTS == 2`, validator invoked twice = two loop attempts) |
| (d) requirement failure | `testRequirementFailureIs409WithoutWriteOrRetry` | 409; zero save attempts; metadata.json count and HTS row unchanged; validator invoked exactly once (no server retry) |
| (e) ambiguous HTS failure | `testAmbiguousHtsFailureIsCommitStateUnknownWithOrphanOnly` | 500 with `error.type == CommitStateUnknownException`; exactly one orphan metadata.json; HTS row unchanged; follow-up load serves old metadata; single save attempt (ambiguity not blind-retried) |
| (f) #612 mirror | `testStaleWriterCannotExpireRacingSnapshot` | stale writer (assert-ref at T_X) → 409; racing snapshot survives; recomputed commit at T_Y → 200 with **all** snapshots present and `main` at the new head |
| (g) explicit expiry only | `testSnapshotRemovalRequiresExplicitRemoveSnapshotsUpdate` | `remove-snapshots [S1]` removes exactly S1; S2 and `main` retained; (inverse covered by (a)/(f): appends have no removal side-channel) |
| (h) preserved keys | `testPreservedKeyMutationRejected` | `set-properties {openhouse.tableLocation}`, `{policies}`, `remove-properties {openhouse.tableUUID}` all → 400 `BadRequestException`; nothing written |
| extra | `testLockedTableCommitRejected` (400 on `lockState.locked`), `testAssignUuidRejected` (400), `testUnknownTableIs404`, `testMultiLevelNamespaceRejected` (400 on `%1F` namespace), `testMalformedRequestBodyIs400`, `testNoopUpdateDoesNotAdvanceTable` (200, no write, pointer unchanged) |

### `RestNativeCommitOperationsTest` (ops-level, beside `OpenHouseInternalTableOperationsTest`, 6 tests) — `iceberg/openhouse/internalcatalog/src/test/java/com/linkedin/openhouse/internal/catalog/RestNativeCommitOperationsTest.java`

- `testTypedReapplyOnFreshBaseCannotDropRacingSnapshot` — the structural #612 elimination at the primitive: writer updates derived at T_X re-applied to fresh T_Y retain the racing snapshot, and `doCommit` persists all of them (no subtractive merge on the typed path).
- `testAssertRefSnapshotIdValidatesAgainstFreshBase` — stale `assert-ref-snapshot-id` passes at T_X, throws `CommitFailedException` at T_Y.
- `testRequirementDerivationPerUpdateShape` — `UpdateRequirements.forUpdateTable` edges: append+set-ref → `[assert-table-uuid, assert-ref-snapshot-id(main, S1)]`; property-only → uuid-only (logical independence); new-branch set-ref → `assert-ref-snapshot-id(branch, null)` which fails once the branch exists; uuid mismatch fails.
- `testExplicitRemoveSnapshotRemovesOnlyNamedSnapshot` — remove-snapshots removes exactly the named id.
- `testStoreFailureSemanticsOnTypedPath` — with no smuggled props: `HouseTableConcurrentUpdateException` → `CommitFailedException` (retryable); `HouseTableRepositoryStateUnknownException` → `CommitStateUnknownException`.
- `testNoLegacyTransportPropertiesAndVersionStamping` — committed properties carry no `SNAPSHOTS_JSON`/`SNAPSHOTS_REFS`/`COMMIT_KEY`; `openhouse.tableVersion` stamped to the prior metadata location; new location allocated.

### `RestUpdateValidatorTest` (unit, 14 tests) — `services/tables/src/test/java/com/linkedin/openhouse/tables/resthandler/RestUpdateValidatorTest.java`

Preserved-key set/remove rejection (incl. mixed user+preserved maps and a preserved-key sweep), user-property allowance, assign-uuid rejection, non-property updates pass, empty list passes; lock-state parsing: locked/unlocked/absent lockState/absent policies/malformed JSON (four variants, never blocks)/null base.

### Gradle evidence (final validation run tail)

```
IcebergRestCommitControllerTest — 15/15 PASSED
RestUpdateValidatorTest        — 14/14 PASSED
RestNativeCommitOperationsTest —  6/6  PASSED
:services:tables:test                       BUILD SUCCESSFUL (full suite)
:iceberg:openhouse:internalcatalog:test     BUILD SUCCESSFUL (full suite)
spotlessCheck / checkstyle (main+test)      clean for all new files; spotbugs run (repo sets ignoreFailures)
```

## Known gaps / follow-ups

1. **MySQL certification (named follow-up, per design exit criterion).** The matrix runs against the H2 JPA repository, whose `save` has *no* `@Version` CAS — store-level conflict behavior is simulated via injected `HouseTableConcurrentUpdateException`. The #612 postmortem noted H2-vs-MySQL divergence; a manual end-to-end against a dev MySQL HTS (real `@Version` CAS + the HTS client's `UserTableVersionMapper` path-equality precheck) is required before certifying concurrency semantics. Docker/testcontainers were not available in this environment.
2. **True parallel-writer interleavings** — conflicts here are sequential submissions from a shared base (deterministic); a randomized-interleaving invariant test (design §4.5: final snapshot set ⊇ every acknowledged snapshot not explicitly removed) belongs in Phase 2.
3. **Not in prototype scope (per design):** authz classification per update type, feature toggle (`enable-rest-commit`), create/stage-create/rename routes, `AddSnapshot` idempotency dedupe, metrics tags, cross-protocol (legacy-vs-REST writer) concurrency suite, shadow validation.
4. **Route is prototype-private** (`/v1/rest/.../commit`); the spec-exact `POST /v1/{prefix}/namespaces/{ns}/tables/{t}` lands with the P1 read plane. A stock `RESTCatalog` client cannot point at this route yet (tests construct the exact `UpdateTableRequest` objects a stock client serializes, via `TableMetadata.Builder.changes()` + `UpdateRequirements.forUpdateTable`).
5. **`commit()` no-op edge:** an empty-changes request returns 200 with current metadata (spec-consistent), asserted by `testNoopUpdateDoesNotAdvanceTable`.

## Pushed commits

- `e2141be` feat: add Iceberg REST-native commit endpoint prototype (controller, commit service, update validator, spec error mapper, dedicated serde) — 5 files, +520
- `62f78e5` test: cover the REST-native commit prototype with the design test matrix (8-case e2e matrix, ops-level typed-commit tests, validator unit tests, H2 fault-injection hook) — 5 files, +1252

Branch: `claude/openhouse-commit-protocol-cl3xg9` (rebased onto origin `08be320` before push; pushed `08be320..62f78e5`). Final validation: `:services:tables:test` + `:iceberg:openhouse:internalcatalog:test` + spotbugsMain — BUILD SUCCESSFUL, 597 test PASSED lines, 0 FAILED.

## Environment notes (for reproducibility)

- Gradle 7.6.2 requires JDK ≤17; installed `openjdk-17-jdk-headless` and set `org.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64` in `~/.gradle/gradle.properties`.
- The git worktree's `.git` file breaks the root `CopyGitHooksTask` (expects a `.git` directory); every gradle invocation used `-x CopyGitHooksTask`.
