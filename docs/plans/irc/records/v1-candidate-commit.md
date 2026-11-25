# V1: a candidate-commit core on the tables service

Branch `irc/v1-candidate-commit` (mkuchenbecker/openhouse), based on `origin/irc/v2-irc-read-endpoints` at a2edc223 (pull request 88). Draft pull request into `irc-integration`: https://github.com/mkuchenbecker/openhouse/pull/89. Until pull request 88 merges, the diff of 89 also contains 88's commits.

The rule inventory (step 1) is in `/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/reports/v1-rule-inventory.md`.

## Bottom line

- **Through the core:** every native write to an existing table that goes through `PUT /tables/{t}` (property, schema, policy, sort-order and no-op updates) or `PUT .../iceberg/v2/snapshots` (append, delete, overwrite, refs and branches, expiry, and replace/RTAS commits). Both routes decode the request into a `TableCommit` and call `TableCommitCore`. That core runs every service-layer rule in its native order, then persists through the unchanged repository and catalog, so the three guards are the same: the base-version check, the retry cache and the house-table compare-and-set.
- **New entry point:** `TableCommitService.commit(TableIdentifier, String baseVersion, TableDtoWithMetadata base, TableMetadata candidate, CommitContext)`. It converts a complete Iceberg candidate into the core's input and runs the same core. It works for property, schema, sort-order, policy and snapshot commits. 18 tests cover it; each rejection is compared with the native answer on the same table.
- **Not through the core, by design in this step:** create, staged create, create-with-data (CTAS's first snapshot PUT), staged replace (`POST` with `stageReplace`), the lock and unlock API, rename and drop. The reasons are below.
- **Deviation from the target shape (stated reason).** The core carries the candidate in the shape of a native write, a `TableDto`. Its rules and the repository read that shape; they are not restated over two Iceberg `TableMetadata` objects. A `TableMetadata` candidate is converted into that shape at the entry point.
  - The rules' extension points take a `TableDto`: `PreservedKeyChecker.isKeyPreservedForTable`, plus the `@PreservedPropsToggleEnabler` aspect, `TablePolicyManager`, `PoliciesSpecMapper`, `PartitionSpecMapper.toPartitionSpec`, `ReadBridgeStripProtection.prepare` and `AuthorizationUtils`. Deployed overrides receive that DTO.
  - Several messages embed request text (schema JSON, base version).
  - The native order of checks and decoding must hold. Iceberg's `TableMetadata.Builder` rejects some invalid candidates itself, for example a schema that drops a partition source column. So the native path cannot build a `TableMetadata` up front without changing which error a bad request gets.
  - The inventory states, rule by rule, which rules can be restated over the pair and what each of the rest needs.
- **Nothing changed for native requests** (ran):
  - Same test counts, plus only the new tests.
  - The stored metadata of 11 representative writes is byte-identical after normalization.
  - The harness slice and the full stub-mode run match their baselines: 2574 / 2560 / 3 lock failures / 11 skipped.

## Commits (oldest first; each body says why)

| Commit | What |
| --- | --- |
| e2db612a `test:` | `StoredMetadataGoldenTest` and `src/test/resources/stored-metadata/*.json`, recorded on a2edc223 before any production change. |
| 56c65c3b `test:` | `CommitPathUpdateRulesTest`: four HTTP pins for update rules that had none. All four passed on the unchanged code. |
| 8b04578f `refactor:` | `commit/{CommitKind,CommitContext,TableCommit}`, `services/TableCommitCore`. `TablesServiceImpl` and `IcebergSnapshotsServiceImpl` decode into it. |
| 0712468a `test:` | `TableCommitServiceTest` (18 tests). Red when committed: it does not compile without the entry point. |
| 7f7c5638 `feat:` | `TableCommitService` (the `TableMetadata` entry point) and `CandidateViews` (candidate to core input, with the refusal of changes that cannot be persisted). |

No existing test was changed. The mocked service tests (`LockEvaluationServiceTest`, `SystemOnlyLockPolicyServiceTest`, `TablesLockServiceTest`) still verify `repository.save(TableDto)`, unchanged. The core persists through exactly that call, and it is built from the calling service's own fields, so those tests did not need new wiring.

## What the core does, in order (native order preserved)

`TableCommitCore.commit(TableCommit)`:

1. Lock-flip check: the request's `policies.lockState` may not flip the stored lock. Table PUT only. Its NPE on a table without a lock-state object is kept.
2. Legacy lock refusal. The wording depends on the kind: "cannot be updated." for a table PUT, "cannot be written to" for a snapshots PUT.
3. `checkTableWritePathPrivileges` (UPDATE_TABLE_METADATA, plus SYSTEM_ADMIN for a replica), then `checkSystemOnlyLockAccess`.
4. The table PUT's no-op short-circuit (`CommitContext.unchanged`). It returns the stored table without writing.
5. Candidate computed on first use. The native table PUT therefore still maps its request only after steps 1 to 3, as before.
6. `ClientCapabilityGate.checkWrite`, `LockPolicyValidator.prepare` (SYSTEM_ONLY preservation) and `ReadBridgeStripProtection.prepare` (`ColumnDefaultException` becomes 422).
7. `repository.save(candidate)`. The route's wording of `BadRequestException` (400), `CommitFailedException` (409) and, for table PUT only, `CommitStateUnknownException` (503).

Everything after step 7 is unchanged code, in the repository, `doCommit` and HTS. In order: occupancy check, reload, replication skip, base-version check, reserved properties, table type, partition evolution, schema evolution, property/policy/sort-order/snapshot encoding, then `doCommit`'s CAS, max-ref-age, pre-commit checks (column defaults), retry cache, stamping, decoding, file write and HTS compare-and-set.

## What still goes around the core, and why

| Write | Why not in V1 |
| --- | --- |
| Create, staged create, CTAS first commit | A different rule set: database CREATE_TABLE privilege, the INITIAL-version check, the table count limit, UUID derivation, location allocation, and cluster-forced properties including `format-version`. The location and UUID are outputs of the create, not candidate inputs. V3's create needs its own entry (identifier, schema, spec, sort order, properties, optional location), or an extension of this one with a null base. |
| Staged replace (`POST stageReplace=true`) | Natively it runs only the write and SYSTEM_ONLY checks: no lock refusal, no capability gate before the special case. Putting it through the core would add checks, which is a behavior change. A snapshots-PUT replace commit (`replaceCommit=true`) does go through the core, because its native rules are the core's. |
| Lock / unlock API | Its own rules: replica refusal, LOCK_ADMIN, reason matching. It persists through the same `TableCommitCore.saveTableUpdate`, and so through the same repository path. |
| Rename, drop, restore, purge | They do not write a candidate. Rename writes three reserved properties in the catalog; drop writes no metadata. |

## Rules that cannot be stated over a metadata pair (full detail in the inventory, section 3)

1. **Native no-op short-circuit.** It compares request text with the stored DTO view, including `baseTableVersion` against the stored `openhouse.tableVersion`. It needs the native request, and is carried as `CommitContext.unchanged` (native only).
2. **Reserved properties and the replication skip.** They read the request's reserved keys as sent. This works over a pair only if the candidate's properties are the requested ones, reserved keys included. An IRC candidate (base plus `SetProperties`) has that shape.
3. **Policies are a second channel.** The raw `policies` key is reserved; the structured value replaces it. This needs `CommitContext.requestedPolicies`. The entry point keeps the stored policies when the context gives none.
4. **Table type.** A request field stored as `openhouse.tableType`. The entry point keeps the stored type; a candidate that edits the property is refused as a reserved-key change.
5. **Snapshot payload text.** It is parsed inside `doCommit`, after the CAS, the max-ref-age and pre-commit checks and the retry cache. Parsing earlier would reorder errors, and would stop the retry cache from burning the base version of an unparseable request. The entry point serializes the candidate's snapshots and refs into the same payload.
6. **Schema text.** The strip and the schema-evolution message read schema JSON text.
7. **Construction order.** Iceberg's builder rejects some invalid candidates before OpenHouse's rules could.
8. **Operation kind.** It decides the lock wording, the 409/503 wording and which table-PUT-only rules apply (`CommitKind`).
9. **Replace and staged intents.** These are flags, not metadata.
10. **Extension points typed on `TableDto`.**

## Test counts (ran, JDK 17, `--max-workers=2 -Xmx4g`)

| Suite | Base a2edc223 | Branch 7f7c5638 | Difference |
| --- | --- | --- | --- |
| `:services:tables:test` | 1296, 0 failed (ran here) | 1329, 0 failed | +33: `StoredMetadataGoldenTest` 11, `CommitPathUpdateRulesTest` 4, `TableCommitServiceTest` 18 |
| `:iceberg:openhouse:internalcatalog:test` | 319, 0 failed (ran here) | 319, 0 failed (task up to date: no input changed; the module is untouched) | none |
| Spark 3.5 itest `catalogTest` | 219, 1 skipped (V2 record) | 219, 1 skipped, 0 failed (ran) | none |
| Spark 3.5 itest `statementTest` | 137 (V2 record) | 137, 0 failed (ran) | none |
| Spark 3.5 itest `test` | 124 (V2 record) | 124, 0 failed (ran) | none |

How the branch run was split: the first full branch run of `:services:tables:test` lost its Gradle daemon to the shared memory limit, after which the run was split into two invocations by package. `e2e.*` gave 432; every other package gave 897. That is 1329 in total, 0 failed.

Spark baseline: not re-run here. The base counts are those the V2 record reports for bddef9fa plus the uncommitted a2edc223. a2edc223 changes only Spark itest assertions.

## Stored-metadata comparison (ran)

`StoredMetadataGoldenTest` reads every metadata file each scenario leaves, oldest first, and compares them as stored JSON with files recorded on a2edc223. The scenarios are create, property PUT twice, schema PUT, policy PUT, sort-order PUT, no-op PUT, two appends, an append then a delete, a refs-only branch, a replace commit, and lock then unlock. The test runs in the branch's 1329.

- **Normalized:**
  - `last-updated-ms` and every `timestamp-ms`, to 0.
  - `openhouse.lastModifiedTime` and `openhouse.creationTime`, to `ts`.
  - Every UUID in any string, to `uuid`: table UUID, metadata and manifest-list file names, table directory.
  - Snapshot ids drawn by the test client, renamed by order of first appearance.
  - Inside the `policies` property, the replication `cronSchedule`, which the server draws at random, and the lock's `creationTime`.
- **Nothing else.**
- **Determinism check:** two runs on the unchanged code produced identical files.
- **Versions per scenario:** 1, 3, 2, 2, 2, 1, 3, 3, 3, 4 and 3 files, in the scenario order above. Same before and after.

## Harness (legacy path, in-memory HTS stub)

- `delete parquet` slice (ran): 238 cases, 237 passed, 1 skipped, 0 failed. Same as the recorded slice (238 / 237 / 0 / 1).
  - The first attempt, with `HARNESS_PARALLELISM=2 -Xmx3g`, was killed by the memory cgroup (exit 137) while another agent's build was running.
  - The rerun used `HARNESS_PARALLELISM=1 -Xmx2g`.
- Full stub-mode run (ran, `HARNESS_PARALLELISM=2`, `-Xmx3g -XX:MaxDirectMemorySize=512m -XX:ReservedCodeCacheSize=160m`, private TMPDIR, started only once no other Gradle test workers were running): **2574 cases, 2560 passed, 3 failed, 11 skipped, 1214 s**. Identical to the baseline. The 3 failures are the baseline's three lock cases with the baseline's message (`control.lock.enforcement`, `interact.rtas.onLockedTable`, `hazard.lock.starvesMaintenance`: the client-side "Cannot convert policies string to policies object").

## Could not be kept identical

None for native requests. Two comparisons in the new core tests are not byte-for-byte, by nature of the input, not of the code:

- A conflict is compared through the error handler's wording ("Entity with key[...] ... nested exception message: ..."), because the core throws the exception and the handler words it.
- The schema-evolution message embeds the proposed schema JSON. A built candidate's schema id is the one Iceberg's builder assigns; a native client sends its own.

One finding pinned as it is: a snapshots PUT on a locked table answers **400**, not 423. The inventory's first draft said 423 and was corrected.

## What the IRC write routes (V3) can rely on, and what they cannot

**Can rely on:**

- `TableCommitService.commit(...)` with base = `TablesService.loadTable(...)` and `baseVersion` = `base.getTableDto().getTableLocation()`. It gives the native update rules and the native guards.
- The kinds: `CommitKind.SNAPSHOTS_UPDATE` gives the snapshots-route wording and rules; `TABLE_UPDATE` adds the lock-flip check (only when policies are passed) and the 503 mapping of unknown commit state.
- Snapshot and ref changes in the candidate are sent as the full snapshot list plus refs, exactly the native protocol, so the stale-snapshot CAS and the column-default Type 2 check apply.
- Reserved `openhouse.*` and `policies` edits are refused with the native 400.
- Max-ref-age, the capability gate, column-default rules, partition-evolution (by field names) and schema-evolution rules apply as natively.

**Cannot rely on:**

- **Create and staged create.** Not built.
- **Refused changes.** Changing location, UUID or format version (as metadata), statistics files, removing every snapshot, or a spec the native API cannot express (time partitioning plus clustering) is refused with 400 `RequestValidationFailureException` naming the field. This refusal is a V1 choice on the new path only; V3 may want a different status.
- **Same-name transform changes.** A partition change that keeps field names is accepted and not stored, as on the native path.
- **IRC requirements.** `UpdateRequirement`s must be checked by V3 against the base before building the candidate. The core checks only the base version.
- **IRC error mapping.** Exceptions are the native ones: `EntityConcurrentModificationException` 409, `OpenHouseCommitStateUnknownException` 503 on `TABLE_UPDATE`, raw `CommitStateUnknownException` on `SNAPSHOTS_UPDATE`. V3 must map them to IRC semantics. 1.5.2 RESTCatalog treats 503 as unavailable, not unknown.
- **Request beans.** The capability gate, the column-default bypass and the SYSTEM action declaration read request-scoped beans, so V3 calls must run inside the HTTP request.
- **Policies.** Changed only through `CommitContext.requestedPolicies`. No IRC update maps to it yet.

## Ran versus read

- **Ran:**
  - the baseline `:services:tables:test` and `internalcatalog:test` on a2edc223;
  - the golden recording, twice, on a2edc223;
  - the new HTTP pins on unchanged code;
  - the branch's full `:services:tables:test` (split by package after a memory kill) and Spark 3.5 itest catalogTest, statementTest and test;
  - the harness slice and the full stub-mode harness run;
  - `spotlessApply` and `spotlessCheck` on `:services:tables`.
- **Read, not run:** the Spark baseline counts (V2 record) and the full-harness baseline (`BASELINE-2026-11-22.md`).
- **Not run:**
  - the real-HTS harness mode;
  - `internalcatalog:test` as a fresh execution on the branch (up to date; nothing in that module changed);
  - any Iceberg 1.12 opt-in build.
