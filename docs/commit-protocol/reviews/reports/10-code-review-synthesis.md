# Code review synthesis — OpenHouse table commit path at `2a9dac8`

## 1. Summary

The commit path's atomic core is sound, and everything dangerous at this commit sits beside it: the single HTS row update guarded by the JPA `@Version` lock is a correct commit point, but three code paths still mutate or misreport committed state around it, and the test suite is thinnest exactly in the cells adjacent to the incident it was hardened for. This synthesis re-verified all 44 findings from three expert reports (architecture and testing, both briefed on the protocol analysis; protocol-correctness, blind against Apache Iceberg's own contract) against the code at `2a9dac8`. **All 44 findings verified; none were killed.** After merging duplicates they resolve to **29 findings: 9 blocking and 20 follow-up.** The three most severe:

1. The `doCommit` IOException handler is reachable only after the commit point, yet it ignores its own commit-status probe, attempts to delete the committed HTS row (surviving today only because `delete()` throws an uncaught `UnsupportedOperationException`), and signals `CommitFailedException` — the one exception Iceberg defines as "safe to clean up" — for a commit that already happened (finding 1).
2. The replicated-create path re-opens the already-committed, already-pointed-to metadata.json and rewrites it in place with `fs.create(path, true)`; a crash mid-rewrite leaves the table's live pointer referencing a truncated file, and the per-instance metadata cache is left holding pre-rewrite content (finding 2).
3. Rename is an unconditional JPQL `UPDATE` of `metadataLocation` with no version predicate and no CAS anywhere above it, so a rename racing a normal commit silently discards the winner's acknowledged snapshot — the same lost-update class as incident-12185, through a channel fix #612 does not cover (finding 3).

All three of these were found independently by both a briefed expert and the blind expert — the strongest corroboration this review produced.

Tier semantics for this appendix, per the stated deployment posture (production Iceberg control plane, correctness-critical path, recent silent-data-loss incident #612 fixed at this commit, multiple replicas behind a load balancer, Spark clients with Iceberg retry machinery, a later fix costs a release cycle): **blocking** means the fix should land before the next change to this path ships, or the defect is an active data-loss/corruption/misreport hazard today; everything else is **follow-up**, with its trigger named where one exists. Category tags name the expert worldview(s) that produced the finding: `arch` (architecture, briefed), `testing` (test-suite, briefed), `protocol` (blind protocol-correctness).

What this review did not cover, so its absence below is a scope statement and not a clean bill: authorization and security depth beyond the two TOCTOU/eligibility findings (17, 18), performance, storage-layer internals, HTS database schema and migrations, the soft-delete/restore path, and client-side read behavior. The blind reviewer's positive note also stands as context: for the mainline snapshot commit, the layered defense (`versionCheck` fast-fail, the #612 catalog CAS, the HTS location-compare plus `@Version` lock as the single arbiter, and a contract-conformant `checkCommitStatus` probe in the `Throwable` path) is correctly constructed.

### File abbreviations used below

| Abbrev | Repo-relative path |
|---|---|
| `ITO` | `iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/OpenHouseInternalTableOperations.java` |
| `HTRImpl` | `iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/repository/HouseTableRepositoryImpl.java` |
| `RepoImpl` | `services/tables/src/main/java/com/linkedin/openhouse/tables/repository/impl/OpenHouseInternalRepositoryImpl.java` |
| `SnapSvc` | `services/tables/src/main/java/com/linkedin/openhouse/tables/services/IcebergSnapshotsServiceImpl.java` |
| `HtsJdbc` | `services/housetables/src/main/java/com/linkedin/openhouse/housetables/repository/impl/jdbc/UserTableHtsJdbcRepository.java` |
| `HtsSvc` | `services/housetables/src/main/java/com/linkedin/openhouse/housetables/services/UserTablesServiceImpl.java` |
| `VerMapper` | `services/housetables/src/main/java/com/linkedin/openhouse/housetables/dto/mapper/UserTableVersionMapper.java` |
| `ClientOps` | `integrations/java/iceberg-1.2/openhouse-java-runtime/src/main/java/com/linkedin/openhouse/javaclient/OpenHouseTableOperations.java` |
| `ExcHandler` | `services/common/src/main/java/com/linkedin/openhouse/common/exception/handler/OpenHouseExceptionHandler.java` |
| `ITOTest` | `iceberg/openhouse/internalcatalog/src/test/java/com/linkedin/openhouse/internal/catalog/OpenHouseInternalTableOperationsTest.java` |
| `HTRTest` | `iceberg/openhouse/internalcatalog/src/test/java/com/linkedin/openhouse/internal/catalog/repository/HouseTableRepositoryImplTest.java` |

Finding numbers are stable addresses; any future revision appends rather than renumbers.

---

## 2. Adjudicated findings

### Blocking

**Finding 1 — [arch+testing+protocol][blocking] The `doCommit` IOException handler runs only after the commit point, yet reports clean failure, ignores its own probe, and points a row-deletion at the committed table.**
Evidence: `ITO:424-437` (the handler), reachability from `ITO:421` calling `updateMetadataFieldForTable` (`ITO:804-821`, the only checked-`IOException` source inside the try) which executes after `houseTableRepository.save` at `ITO:404`; the `commitStatus = checkCommitStatus(...)` result at `ITO:425` is assigned and then ignored; `houseTableRepository.delete(houseTable)` at `ITO:428` invokes `HTRImpl:320-322`, which unconditionally throws `UnsupportedOperationException` — not among the caught types at `ITO:429-431`. Iceberg's contract (`TableOperations` in Apache Iceberg: no failable operations after the atomic commit succeeds; unknown outcomes must be `CommitStateUnknownException`) is violated because `CommitFailedException` implements `CleanableFailure`, which licenses the engine's `SnapshotProducer` to delete the just-written manifests.
Failure scenario: a replicated-table create commits (HTS pointer durably advanced), then the in-place rewrite hits a transient storage `IOException`. Today the `UnsupportedOperationException` escapes as a generic 500 → the client maps it to `CommitStateUnknownException` — survivable only because the landmine detonates before the misreport. If anyone implements `delete()` (the interface advertises it — finding 16), the same event deletes the live table pointer and then reports a cleanable failure for a commit that succeeded.
Found by: arch #1, protocol F1 and F8, testing F4 (independently: briefed and blind converged).
Action: write the fault-injection unit test first (inject `IOException` from the rewrite; it fails today), then delete the `catch (IOException)` block's cleanup-and-misreport behavior — with finding 2's fix the only checked-IOException source disappears and the compiler enforces the block's removal. Do not implement `delete()` to "fix" the `UnsupportedOperationException`.

**Finding 2 — [arch+testing+protocol][blocking] The replicated-create path rewrites the already-committed metadata.json in place, non-atomically, and strands the metadata cache.**
Evidence: `ITO:420-422` calls `updateMetadataFieldForTable` after the commit point (`ITO:404`); `iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/utils/MetadataUpdateUtils.java:36-57` does read → `fs.create(new Path(hdfsPath), true)` (truncating overwrite of the same path, line 45) → write; the cache was seeded with the pre-rewrite object at `ITO:367`. This breaks the immutability assumption that makes unique-named metadata files, location-keyed caching, and `checkCommitStatus` sound.
Failure scenario: a crash or storage error between truncation and write completion leaves the committed pointer referencing a corrupt file — every subsequent refresh on every instance throws and the table is unavailable until manual repair. Even without a crash, the committing instance serves cached pre-rewrite metadata for that location while other replicas parse the rewritten file: same location, two contents, replicas disagreeing behind the load balancer.
Found by: arch #2, protocol F2, testing F8 (test gap: happy-path-only tests at `ITOTest:376-509`).
Action: patch `last-updated-ms` into the serialized JSON before the single metadata write at `ITO:361-366` (the value is already in hand at `ITO:281-284`) and remove `MetadataUpdateUtils` from this path; one write, one immutable file, and finding 1's checked-IOException source disappears with it. Add the fault-injection test from testing F8 either way.

**Finding 3 — [arch+testing+protocol][blocking] Rename bypasses every CAS: an unconditional pointer overwrite that silently discards a concurrent committed commit.**
Evidence: `HtsJdbc:115-125` — a JPQL bulk `UPDATE` setting `metadataLocation` with no `metadataLocation`/`version` predicate and no `@Version` bump (JPQL bulk updates bypass the optimistic-lock machinery entirely); caller chain `ITO:386-400` → `HTRImpl:239-254` → `HtsSvc:140-167` (only a non-atomic `existsById` pre-check). The rename commit is also exempt from the #612 catalog CAS (`ITO:610-616`, no `SNAPSHOTS_JSON_KEY`) and no `COMMIT_KEY` is stamped by `renameTable` (`iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/OpenHouseInternalCatalog.java:212-244`).
Failure scenario (concrete interleaving, from the blind report): rename loads the table at base L0 and writes L1'; writer W commits snapshots (row → L1, W acknowledged); rename's `renameTableId` then unconditionally sets the row to L1'. W's committed, acknowledged snapshot is gone with no error on either side — byte-for-byte the incident-12185 outcome through a side door. The window is the full duration of a rename request. No test at any level exercises rename racing a commit (testing F7; existing tests are happy-path only).
Found by: arch #3, protocol F3, testing F7.
Action: make the rename write conditional — add `AND table.metadataLocation = :expectedBase` (plus a version bump) to the JPQL and thread the expected base from `doCommit` (which holds `base.metadataFileLocation()`) through `HouseTableRepository.rename` and the HTS rename API; zero rows updated → 409. Land the forced-interleaving pin test from testing F7 with it.

**Finding 4 — [arch+testing+protocol][blocking] `processSchemas` swallows per-schema parse failures inside the commit and commits anyway with wrong schema lineage.**
Evidence: `ITO:704-715` — each schema after the first (including the final evolved schema when intermediates are present) is applied inside a stream lambda whose `catch (Exception e)` only logs. No test exercises the branch (testing F9).
Failure scenario: replication submits N intermediate schemas and entry k is malformed; the commit succeeds with schemas k..N silently missing, replica schema history diverges from the primary, and the damage surfaces far from the cause when a reader needs the missing schema id.
Found by: arch #6, testing F9, protocol F13.
Adjudication of the severity spread (arch: blocker; blind: minor; testing: "decide the contract"): tiered blocking because silently wrong committed metadata on a live replication surface is exactly the silent-wrongness class the posture makes blocking, and the fix is one line.
Action: let the exception propagate (it lands in the existing `IllegalArgumentException`/`Throwable` classification in `doCommit` and renders as a failed commit), and add the unit test that pins the new contract.

**Finding 5 — [arch+testing+protocol][blocking] `failIfRetryUpdate` burns the commit key before the commit succeeds, in a per-JVM cache, and answers duplicates with the one signal that is never correct — and has zero tests.**
Evidence: `ITO:93-94` (static 5-minute Guava cache), `ITO:642-664` (`CACHE.put` on first sight at line 654, before the commit outcome is known; runs at `ITO:271`, before the metadata write and HTS save). Repo-wide grep over test sources finds `COMMIT_KEY` only at `ITOTest:301` (inside the #612 repro) — neither the duplicate-key abort nor the pre-commit burn has a test.
Failure scenario: (a) a commit fails cleanly after the burn (e.g. HTS 429 → 409 to the engine); the engine's sanctioned retry refreshes, finds the base unchanged, re-presents the same key to the same instance, and gets a hard "please consider retry from application" 409 for up to five minutes — a transient throttle escalated into a failed job. (b) Behind the load balancer the cache cannot catch cross-instance duplicates at all, so its valid scope is only same-JVM internal retries. (c) When it does fire against a retry of a possibly-committed request, `CommitFailedException` is a `CleanableFailure` — the wrong answer class for that case (the correct ones are idempotent success or unknown-state); this participates in finding 15's duplicate/cleanup path.
Found by: arch #10, protocol F4, testing F1.
Adjudication of the severity spread (arch: suggestion; blind: major; testing: blocker): tiered blocking, bounded to the smallest step — the deciding posture facts are that scenario (a) is an active wrong behavior on legitimate engine retries today, and that this mechanism is the second line of defense against the incident-12185 class while having no tests at all.
Action: move `CACHE.put` after the successful `houseTableRepository.save`, and add testing F1's two unit tests (duplicate-key abort; clean first sighting with the key stripped from persisted properties). The larger option — deleting the mechanism once finding 10's restructure removes server-side transactions — is the follow-up.

**Finding 6 — [arch][blocking] Client `doCommit` routing is not total: a commit whose changes fall outside the six compared dimensions is acknowledged as success without any REST call.**
Evidence: `ClientOps:146-155` dispatches on `isMetadataUpdated` (`ClientOps:171-180`: schema, properties, spec, sortOrder) and `areSnapshotsUpdated` (`ClientOps:343-349`: snapshots, refs) with no final `else`; a commit differing only in, e.g., table location (`updateLocation`) or statistics files matches no branch, makes no HTTP request, and returns — Iceberg's `commit()` wrapper then reports success to the engine.
Failure scenario: `ALTER TABLE ... SET LOCATION` (or another unclassified commit shape) on the Spark runtime is acknowledged to the user and evaporates on the next refresh — an acknowledged-but-unpersisted commit, the same contract violation as a lost update.
Found by: arch #5 (briefed-only; the mechanism was re-verified directly against the code — the routing hole is real; the reachable Spark trigger remains argued rather than executed).
Adjudication: kept blocking despite "probable" reachability because the failure mode is silent success and the guard is a one-line `else` that throws; a defense that costs nothing against a silent-loss class clears the bar.
Action: add a final `else` throwing an unsupported-commit error so unclassified commit shapes fail loudly at the source.

**Finding 7 — [testing][blocking] No test at any level proves the two-writer contract end to end: loser gets 409, retries from a refreshed base, and the final table contains both writers' snapshots.**
Evidence: the #612 repro (`ITOTest:257-325`) pins the abort; `services/tables/src/test/java/com/linkedin/openhouse/tables/e2e/h2/RepositoryTest.java` covers sequential stale-base 409s; but no test composes 409 → refresh → re-merge against the advanced base. `SnapshotsControllerTest` contains no stale-base conflict case at all (grep for `isConflict` in `services/tables/src/test/java/com/linkedin/openhouse/tables/e2e/h2/SnapshotsControllerTest.java` returns nothing), and the concurrent functional test dropped during the #612 work was never replaced (no `*ConcurrentInsert*` test exists in the repo).
Failure scenario: a regression in the retry-recovery composition — the subtractive merge mis-computing removals on the second pass, or a 409 rendering change breaking the engine's retry classification — silently reintroduces snapshot loss one hop from the pinned incident.
Found by: testing F2.
Action: one e2e-h2 composition test in `SnapshotsControllerTest`: commit S_a as writer A; PUT snapshots as writer B with the pre-A base and a payload omitting S_a → assert 409; re-PUT with refreshed base and payload {S_a, S_b} → assert 200 and final snapshot set equals the union.

**Finding 8 — [testing][blocking] The commit-ambiguity classifier is tested for only one of its three outcomes, and no failure-path test asserts the old-pointer-intact safety property.**
Evidence: `ITO:452-476` classifies `checkCommitStatus` into SUCCESS/FAILURE/UNKNOWN; the only test (`ITOTest:659-685`, `testDoCommitExceptionHandling`) stubs `save` to throw and asserts exception types only — with the default mock, `findById` returns empty, so only UNKNOWN is ever reached. No test stubs `findById` to return the new location after a failed-looking save (SUCCESS partition: doCommit must return success) or the old location (FAILURE partition: `CommitFailedException`), and no exception-path test asserts that the HTS row was not saved and a reader still sees the old base (testing F10).
Failure scenario: a misclassification ships silently — a landed commit reported unknown (application re-drives writes, duplicate data), or worse, reported as clean failure.
Found by: testing F3 (part a) and F10 (folded here per its own recommendation).
Action: two fault-injection unit tests in `ITOTest` (save throws state-unknown + `findById` returns new location → no exception; + returns old location → `CommitFailedException`), each additionally asserting `verify(repo, never()).save(...)`-style no-state-change where applicable.

**Finding 9 — [testing][blocking] The client-side CAS token — the input every server-side check consumes — is asserted by no test, and the replace-routing oracle cannot distinguish the branches.**
Evidence: stamping at `ClientOps:208-209` (metadata path) and `ClientOps:369-376` (snapshots path); routing flag at `ClientOps:411-416`. Grep for `baseTableVersion` over both client/Spark itest trees returns zero hits; `integrations/spark/spark-3.1/openhouse-spark-itest/src/test/java/com/linkedin/openhouse/spark/mock/DoCommitTest.java:238-298` uses request-taken and no-throw oracles only (`testMetadataWithDataChange` ends in `assertDoesNotThrow` and never reads the `replaceCommit` flag both branches share an endpoint on).
Failure scenario: a stamping regression (wrong metadata object, stamp moved after a refresh) disables the entire server-side CAS chain while every client and server test stays green — server tests inject properties directly and e2e-h2 bypasses this client. A mis-routing regression that marks an append `replaceCommit=true` sails past the #612 CAS entirely, since replace carries no `COMMIT_KEY` and is wholesale-authoritative. This is the incident's escape topology: every layer assuming another layer checked.
Found by: testing F5.
Action: MockWebServer contract tests in `DoCommitTest` asserting on the parsed request body: `baseTableVersion == base.metadataFileLocation()` on update and `INITIAL_VERSION` on create; `replaceCommit == true` only on the metadata+snapshots+existing route; the snapshots body carrying exactly `newMetadata.snapshots()`.

### Follow-up

**Finding 10 — [arch][follow-up] The commit payload rides as string table-properties through Iceberg's transaction machinery that does not own it — the structural cause of #612, still fork-coupled at HEAD.**
Evidence: staging at `RepoImpl:183-216` (`COMMIT_KEY` at line 196; the correctness comment "relies on forked iceberg-core to use this property for building the base transaction retryer" at lines 197-200; transient-prefix stash at `RepoImpl:750-781`); consumption as an ordered strip sequence at `ITO:267-312`, with the strip-order constraint documented as load-bearing at `ITO:598-599`. Three compensating mechanisms exist only because of the carrier choice: the `commit.num-retries=0` override, the finding-5 dedup cache, and the #612 CAS itself.
Failure scenario: already materialized once (incident-12185); the residual risk is that any iceberg-core upgrade or fork drift that changes how transactions copy or re-apply properties silently re-opens the class, since the compiler cannot see this contract.
Found by: arch #4 (briefed-only; verified hardest per the anchoring rule — every cited mechanism reproduces at `2a9dac8`).
Adjudication: the arch report graded this blocker; tiered follow-up here on proportion grounds — at this commit every known active exploit of the carrier is either separately defended (the CAS trio for snapshot commits) or separately filed as its own blocking finding (3, 14), and the demanded fix is a refactor rather than a guard. Triggers: **before or alongside any iceberg-core upgrade or fork sync, and as the enabling step of the next commit-path feature.** The enabling shape: a typed per-operation commit context passed from `RepoImpl` through `newTableOps` (`OpenHouseInternalCatalog.java:72-84` already constructs ops per request) instead of property-map smuggling.

**Finding 11 — [arch+protocol][follow-up] The snapshot payload is trusted wholesale, the merge is subtractive, and an omitted refs field wipes every branch and tag.**
Evidence: `iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/SnapshotsUtil.java:45-47` (the `FileIO` parameter is never used — no manifest-list existence or ownership validation); `ITO:337-344` (snapshots absent from the payload are removed), `ITO:346-351` (refs synced to payload), `ITO:317-320` (`serializedSnapshotRefs == null` → empty map → all refs removed). On the service side `RepoImpl:696-708` sets `SNAPSHOTS_REFS_KEY` only when the request's refs map is non-empty, and `services/tables/src/main/java/com/linkedin/openhouse/tables/api/validator/impl/IcebergSnapshotsApiValidatorImpl.java` never requires `snapshotRefs`.
Failure scenario: a hand-rolled REST client (the API is public) PUTs snapshots without refs → the server silently drops `main` and the table reads as empty at HEAD, acknowledged as success; a wrong-but-parsable manifest-list path or an empty snapshot list against a populated base commits equally silently. The #612 CAS defends against stale payloads, not wrong ones.
Found by: arch #14, protocol F7 (the refs-wipe default is the blind expert's addition).
Adjudication (arch: suggestion; blind: major): tiered follow-up because the deployed clients are Spark engines using the OpenHouse library, which always sends the full desired refs state; trigger: **before any non-OpenHouse client is supported or the REST API is opened to third parties** — at that point the refs-default becomes a blocking destructive default. Action then: treat an absent refs field on a snapshot-bearing commit as an error (or no-op), and validate manifest-list paths fall under the table location at the parse seam.

**Finding 12 — [arch+protocol+testing][follow-up] Unknown-outcome commits render inconsistently (503 on the tables path, untyped 500 on the snapshots path), and the 503 choice itself contradicts the Iceberg REST convention.**
Evidence: `SnapSvc:89-109` catches only `BadRequestException` and `CommitFailedException`, so `CommitStateUnknownException` on the data path falls to the generic handler (`ExcHandler:402-415` — 500 with `exception.toString()` and a stack trace); `services/tables/src/main/java/com/linkedin/openhouse/tables/services/TablesServiceImpl.java:183-193` wraps it → 503 (`ExcHandler:146-159`); 503 is absent from the endpoint's declared responses. Apache Iceberg's REST spec attaches `CommitStateUnknownException` to 500/502/504, and treats 503 as the generic "not processed, retry safely" code.
Failure scenario: any intermediary or non-first-party client applying standard HTTP semantics blind-retries a possibly-committed mutation on 503 (landing in finding 15's duplicate path), or treats the snapshots path's 500 differently from the tables path's 503 with nobody having decided that.
Found by: arch #9, protocol F6, testing F3 (part b).
Adjudication of the direct disagreement — arch #9 recommends unifying both endpoints on the typed 503; protocol F6 shows 503 is the wrong code by the ecosystem contract. Ruled with the blind expert: the contract evidence is authoritative, and the first-party client maps 500, 503, and 504 to `CommitStateUnknownException` alike (`ClientOps:430-438`), so moving to a typed 500/504-family rendering breaks no deployed client while 503 actively invites intermediary retries. Tiered follow-up because first-party deployments are belt-protected today; trigger: **the next change to either service's exception surface, and before any non-OpenHouse client**. Action: one shared translation for both services, a typed non-503 unknown-state rendering, declared in the OpenAPI spec; land testing F3's pin test with it.

**Finding 13 — [arch][follow-up] The base-version CAS has four owners with three comparison semantics, and the authoritative one lives inside a MapStruct mapper.**
Evidence: (a) `RepoImpl:451-475` (`versionCheck`, scheme-less via `URI.getPath`); (b) `ITO:624-628` (Hadoop `Path.toUri().getPath()`); (c) `ITO:642-664` (raw string keys in the dedup cache); (d) `VerMapper:34` (raw `String.equals`, the only comparison the database backs atomically, throwing the concurrency exception from inside a mapper plugin). `HtsSvc:98-127` (`putUserTable`) is find-then-map-then-save without `@Transactional`, safe today only because of `@Version`.
Failure scenario: any scheme drift in stored locations (storage migration, a client sending scheme-full paths) makes (a)/(b) pass while (d) fails raw equality — every commit to affected tables permanently 409s, or conflict reporting diverges across layers. Every new commit flavor must rediscover which checks apply (rename discovered none — finding 3).
Found by: arch #7 (briefed-only; all four sites re-verified).
Action: canonicalize the location at each service boundary with one shared normalization, move the HTS version decision out of the mapper into the service where the transaction lives, and document (a)/(b) as advisories over (d) as sole arbiter.

**Finding 14 — [arch][follow-up] "Replace" — the CAS-exempt, wholesale-authoritative commit flavor — is inferred from a client-side state diff instead of declared intent.**
Evidence: `ClientOps:147-150` (`metadataUpdated && snapshotsUpdated && base != null` ⇒ `replaceCommit(true)`, `ClientOps:411-416`); server-side, the replace branch (`RepoImpl:154-177`) stamps no `COMMIT_KEY`, so `ITO:620-622` skips the CAS.
Failure scenario: a schema-evolving append (schema + snapshots in one transaction) is classified as a replace: with RTAS disabled it hard-fails with a misleading "enable REPLACE" error; with RTAS enabled it proceeds CAS-exempt with full authority over the snapshot set, so a stale such commit can expire a concurrent writer's snapshots "by design".
Found by: arch #8 (briefed-only; mechanism re-verified); cross-references testing F6(c) — the exclusion's only record is a code comment, with no pin test.
Action: carry the engine's declared operation kind in the request body and key both the privilege check and the CAS exemption on it; server-side, require even a replace to declare its base and abort on divergence (replace keeps authority over content, loses the right to be stale). Trigger: **before broadening RTAS enablement.**

**Finding 15 — [protocol][follow-up] No idempotency at the commit point: a retry of an already-committed request is indistinguishable from a genuine conflict and is reported as one.**
Evidence: `VerMapper:33-45` compares only the claimed base against the row — there is no memory of which request produced the current pointer, so a duplicate of a commit whose first attempt landed (response lost) gets `EntityConcurrentModificationException` → 409 → client `CommitFailedException`, a `CleanableFailure`, for durably committed work. The server's own 409 message ("please consider retry from application", `ITO:651`) invites the harmful pattern.
Failure scenario: application-level re-drive after an ambiguous outcome appends the same files again (duplicate rows), or an exhausted retry loop cleans manifests the current metadata references.
Found by: protocol F5 (blind-only).
Action: a commit-UUID checked at the row (the `COMMIT_KEY` machinery is halfway there) to answer duplicates with idempotent success or unknown-state. This is a protocol-hardening design change shared with most metastore catalogs — follow-up, natural to fold into finding 10's typed-context work.

**Finding 16 — [arch][follow-up] The HTS boundary's vocabulary is wrong in both directions: a framework CRUD facade advertising unimplemented operations, and the bottom store catching Iceberg's exceptions.**
Evidence: `iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/repository/HouseTableRepository.java:16-17` extends Spring Data `PagingAndSortingRepository`; `HTRImpl:257-337` throws `UnsupportedOperationException` from 10+ inherited methods (finding 1 stepped on `delete`); `HtsSvc:27,112` imports and catches `org.apache.iceberg.exceptions.CommitFailedException` in a module with no business knowing Iceberg exists.
Found by: arch #12 (the `delete` landmine half is folded into finding 1, where the blind expert's F8 corroborates it).
Action: replace the inherited facade with a hand-written interface listing exactly the supported operations, and translate the Iceberg-backed HTS repository's failures into an HTS-owned exception at that repository.

**Finding 17 — [protocol][follow-up] Replication-flow requests skip the eligibility checks that protect the CAS-token property.**
Evidence: `RepoImpl:288-312` (`skipEligibilityCheck`) bypasses `versionCheck` and `checkIfPreservedTblPropsModified` entirely (`RepoImpl:434-445`) when the existing table is marked replicated or on the legacy replica→primary path; `services/tables/src/main/java/com/linkedin/openhouse/tables/utils/TableUUIDGenerator.java:150-164` similarly skips path validation for replica/replicated requests. The invariant "the CAS token is always server-derived" does not hold on this path; exploitability is bounded by deployment authorization (the flows are for a trusted replication service).
Found by: protocol F12 (blind-only).
Action: re-derive `openhouse.tableLocation` server-side even on skip-eligibility paths, or restrict the skip to the specific fields replication legitimately differs on.

**Finding 18 — [protocol][follow-up] TOCTOU between the service-layer lock/authorization checks and the commit point.**
Evidence: `SnapSvc:41-88` reads the table once for lock and privilege decisions; `RepoImpl` loads it again for the commit; nothing re-validates the lock at the commit point. A lock applied in the window is not enforced for the in-flight write; the pointer CAS defends only policy changes that themselves advance the pointer.
Found by: protocol F11 (blind-only).
Action: re-check the lock against the freshly loaded table inside the repository save, or make lock changes pointer-advancing by construction.

**Finding 19 — [protocol][follow-up] The HTS client retries rename/delete with non-idempotent semantics and blocks without a timeout.**
Evidence: `HTRImpl:226-237` and `HTRImpl:239-254` — `deleteById` and `rename` run under a retry template for `IllegalStateException` around `.block()` with no duration, so the intended timeout-driven retry mostly cannot fire, and a stalled connection hangs the request thread; when a retry does happen after a lost success response, the second attempt's 404 surfaces as failure (on the rename path inside doCommit, as a cleanable `CommitFailedException` at `ITO:448-451`) for an operation that landed.
Found by: protocol F9 (blind-only).
Action: bound the block with the write timeout and stop retrying non-idempotent verbs, or make the retry 404-tolerant with an explicit landed-check.

**Finding 20 — [arch][follow-up] Retriable-conflict vs bad-request classification depends on matching Iceberg's exception message strings.**
Evidence: `ITO:670-675` (`msg.contains("Cannot add snapshot with sequence number")...`), used at `ITO:440-445`. An iceberg-core upgrade or fork drift that rewords the message turns genuine concurrency conflicts into 400s that engines will not retry, with no build-time signal.
Found by: arch #11 (briefed-only; verified).
Action: detect the condition structurally by comparing the payload's sequence numbers against the base in the merge block. Trigger: **at the next iceberg-core upgrade or fork sync at the latest.**

**Finding 21 — [arch][follow-up] Client refresh maps HTTP 400 to "table absent".**
Evidence: `ClientOps:108` (`onErrorResume(WebClientResponseException.BadRequest.class, e -> Mono.empty())`), consumed at `ClientOps:119-126` — a 400 during refresh is indistinguishable from 404, so an outage or validation change converts into the wrong answer "your table does not exist", propagated as truth (a CTAS flow would proceed down its create path).
Found by: arch #13 (briefed-only; the mapping is confirmed, its motivating server behavior is not recoverable from the code).
Action: drop the mapping (one line) or gate it on the specific legacy response that motivated it.

**Finding 22 — [arch+protocol][follow-up] A concurrently dropped table surfaces as `IllegalStateException` → HTTP 500.**
Evidence: `ITO:126-130` throws `IllegalStateException` when the row vanished between requests; `ExcHandler:316-329` maps it to 500; the client-side ops models the same event as `NoSuchTableException` (`ClientOps:120-123`). A writer racing a drop gets an unknown-state 500 instead of a definitive not-found its retry policy could act on — and then refuses to clean up files for a table that provably no longer exists.
Found by: arch #18, protocol F10.
Action: throw the catalog's not-found type; concurrent deletion is an expected outcome in a multi-writer control plane.

**Finding 23 — [protocol][follow-up] Drop is a non-atomic two-step, and post-commit metadata GC bookkeeping derives from the wrong metadata object.**
Evidence: `OpenHouseInternalCatalog.java:157-192` deletes the HTS row and then purges files (safe ordering, but orphaned files on a crash between the two with no reconciliation in this path). Separately, Iceberg's `BaseMetastoreTableOperations.commit` calls `deleteRemovedMetadataFiles(base, metadata)` after `doCommit` using the pre-transform `metadata`, while `doCommit` rebuilds and persists a different `metadataToCommit` (`ITO:307-353`) — with `write.metadata.delete-after-commit.enabled` set from cluster properties (`RepoImpl:601-604`), the deletion bookkeeping and the persisted previous-files log come from two different objects. Divergence is unlikely but unproven.
Found by: protocol F14 (blind-only).
Action: add an orphan-reconciliation note/job for drop, and prove or pin the `deleteRemovedMetadataFiles` equivalence before enabling delete-after-commit broadly.

**Finding 24 — [testing][follow-up] The catalog CAS's remaining partitions are untested, and its deliberate replace exemption is recorded only in comments.**
Evidence: of the CAS partitions at `ITO:605-628`, only "path-valued `COMMIT_KEY` diverges" is tested (`ITOTest:257-325`). Untested: `COMMIT_KEY=INITIAL_VERSION` against an existing base (must abort — a create raced an existing table); a matching key including scheme-full vs scheme-less normalization (a regression turns every scheme-full client's commits into spurious 409s); and the no-`COMMIT_KEY` replace pass-through, whose rationale lives only at `ITO:594-596` and `ITO:620-622`.
Found by: testing F6 (cross-references finding 14).
Action: three unit tests — INITIAL_VERSION-vs-existing-base aborts; matching key with a scheme on one side commits; replace-shaped metadata omitting an existing snapshot commits and removes it, labeled as a pin of the by-design exemption.

**Finding 25 — [arch][follow-up] Every error response embeds abbreviated stack traces, and the 500 fallback embeds `exception.toString()`.**
Evidence: `ExcHandler` builders throughout (e.g. `ExcHandler:139`, `ExcHandler:155`), fallback at `ExcHandler:402-415`. Internal class names, HTS endpoints, and storage paths leak to any API consumer on every failure; combined with finding 12, the data path's unknown-state response is a 500 whose body is a stack trace.
Found by: arch #15. Action: gate stack-trace fields behind a debug flag; keep messages service-owned.

**Finding 26 — [arch][follow-up] Fabricated and dropped exception causes destroy the failure record on exactly the forensic paths.**
Evidence: `VerMapper:30,44` (`new EntityConcurrentModificationException(..., new RuntimeException())`); `MetadataUpdateUtils.java:50-55` (`throw new IOException(errMsg)` discarding the caught `e` after logging it).
Found by: arch #16. Action: pass the real cause.

**Finding 27 — [arch][follow-up] Interrupt detected but the interrupt flag is never restored.**
Evidence: `ClientOps:156-167` inspects `e.getCause() instanceof InterruptedException` and throws `CommitStateUnknownException` without calling `Thread.currentThread().interrupt()`; a cancelled Spark task loses the cancellation signal.
Found by: arch #17. Action: restore the flag before rethrowing.

**Finding 28 — [testing][follow-up] Property-count assertions are unlabeled pins that fail for the wrong reason.**
Evidence: `ITOTest:172-175`, `ITOTest:213-216`, `ITOTest:539-542` assert `updatedProperties.size() == 4`; any legitimately added property fails three tests with a message naming no broken promise, while the real claim (the transport keys `snapshotsJsonToBePut`, `snapshotsRefs`, `commitKey` are stripped) is unasserted.
Found by: testing F11. Action: replace with must/must-not-contain key assertions (the stripped-`commitKey` assertion also serves finding 5's tests).

**Finding 29 — [testing][follow-up] Timeout tests burn about two minutes of real wall clock.**
Evidence: `HTRTest:659` (58-second header delay via `writeTimeout - 2` with `writeTimeout = 60`), `HTRTest:673,679` (two 31-second delays via `readTimeout + 1` with `readTimeout = 30`). The claims are right; the clock is wrong.
Found by: testing F12. Action: inject short test-only timeouts through a seam and keep the assertions.

---

## 3. Adjudication log

### Verification

Every location, quote, and mechanism in all 44 input findings was re-checked against the code at `2a9dac8` (the working tree differs from that commit only in `docs/`), including the blind report's citations into the Apache Iceberg source it used as the contract reference (the post-commit rule in `TableOperations`, `CommitFailedException implements CleanableFailure`, `SnapshotProducer`'s cleanup on cleanable failures, `BaseMetastoreTableOperations.commit`'s `deleteRemovedMetadataFiles(base, metadata)` call, and the REST spec's 500/502/504-vs-503 semantics). **No finding failed verification; none were returned.** Three evidence-level corrections were made without affecting any finding's survival:

| Report / finding | Correction |
|---|---|
| testing F4 | The side-claim that `houseTable` "may still be the empty builder" when the IOException precedes mapping is unreachable at this commit: the only checked-IOException source inside the try (`ITO:421`) runs after the mapping at `ITO:385`. The finding's core (uncaught `UnsupportedOperationException` masking the intended exception, zero tests) verifies fully. |
| arch #3 | The caller pointer "`OpenHouseInternalCatalog.java:292-244`" is a typo; `renameTable` is at `OpenHouseInternalCatalog.java:212-244`. All other pointers verify. |
| testing F12 | The quoted `setHeadersDelay(58, SECONDS)` is a paraphrase; the code reads `writeTimeout - 2` (= 58 s) and `readTimeout + 1` (= 31 s) at `HTRTest:659,673,679`. Substance verifies. |

### Merges

44 input findings merged into 29. Merge test applied: same location and same failure → one finding citing every reviewer; shared cause with different locations → separate findings naming the connection (e.g. findings 14 and 24 both concern the replace exemption; findings 1 and 16 both touch the `delete()` landmine, recorded once in finding 1 with the interface-width remainder in finding 16).

| Synthesis finding | Merged sources |
|---|---|
| 1 | arch #1 + protocol F1 + protocol F8 + testing F4 |
| 2 | arch #2 + protocol F2 + testing F8 |
| 3 | arch #3 + protocol F3 + testing F7 |
| 4 | arch #6 + testing F9 + protocol F13 |
| 5 | arch #10 + testing F1 + protocol F4 |
| 8 | testing F3(a) + testing F10 |
| 11 | arch #14 + protocol F7 |
| 12 | arch #9 + protocol F6 + testing F3(b) |
| 22 | arch #18 + protocol F10 |
| 6, 7, 9, 10, 13–21, 23–29 | single-source (see per-finding attribution) |

Protocol F15 was a no-defect contract note, not a finding; its content is reflected in the summary's statement of what holds the protocol together.

### Disagreements and rulings

1. **503 vs 500 for unknown-state (finding 12).** Arch #9 recommended unifying both endpoints on the typed 503 the tables path already uses; protocol F6 showed 503 contradicts the Iceberg REST convention, under which ambiguous commits are 500/502/504 with a typed `CommitStateUnknownException` body and 503 means "not processed, retry safely". Ruled with the blind expert: the contract evidence is authoritative, and the first-party client maps 500/503/504 identically to `CommitStateUnknownException`, so the convention-conformant rendering breaks nothing deployed while 503 actively invites intermediary blind retries of possibly-committed mutations. The surviving finding demands unification on a typed non-503 rendering.
2. **`processSchemas` severity (finding 4).** Arch: blocker; blind: minor; testing: "decide the contract first". Ruled blocking: silently wrong committed metadata on a live replication surface is the silent-wrongness class the posture defines as blocking, and the fix is one line. The testing report's real point — that the decision must become visible — is honored by the demanded pin/contract test.
3. **`failIfRetryUpdate` tier (finding 5).** Arch: suggestion; blind: major; testing: blocker. Ruled blocking, bounded to the smallest step (move the cache write after commit success; add the two unit tests). Deciding posture facts: the spurious-409 poisoning of legitimate engine retries is active wrong behavior today behind a load balancer, and the mechanism is the second line of defense against the incident class with zero test coverage. The mechanism's deletion (possible under finding 10's restructure) stays follow-up.
4. **Property-bag carrier tier (finding 10).** The arch report graded it blocker. Tiered follow-up on proportion (principle 4): every known active exploit of the carrier is separately defended at HEAD or separately filed as blocking here (findings 3, 14), so the residual hazard is latent until the carrier's behavior changes — which is exactly the named trigger (fork sync / iceberg upgrade / next commit-path feature). The claim itself stands unreduced.
5. **Client routing hole tier (finding 6).** Arch graded blocker at "probable" confidence (reachable trigger argued, not executed). Kept blocking: the failure mode is silent acknowledgment of an unpersisted commit, and the demanded fix is a one-line guard, so the cost-risk asymmetry decides toward blocking despite the reachability uncertainty.
6. **Snapshot-payload trust tier (finding 11).** Arch: suggestion; blind: major (destructive refs-wipe default). Tiered follow-up on the posture fact that deployed writers are Spark engines using the OpenHouse client, which always transmits the full refs state; the named trigger (non-OpenHouse clients / third-party API exposure) converts it to blocking at that point.

### Tiering posture facts

The posture facts used throughout: production control plane with a recent silent-data-loss incident on this exact path (tips close calls on silent-wrongness classes toward blocking — findings 3, 4, 6); multiple replicas behind a load balancer (makes per-JVM defenses partially fictional and cache/file divergence a client-visible inconsistency — findings 2, 5); clients are first-party Spark engines with Iceberg retry machinery (keeps wire-contract and raw-REST hazards at follow-up with named triggers — findings 11, 12, 15); a later fix costs a release cycle with interim data-loss risk (justifies blocking the small guards now rather than awaiting the structural refactor — findings 1, 3, 5, 6). Test-gap findings 7, 8, and 9 are tiered blocking because each pins incident-adjacent behavior whose regression would be silent, and each costs only tests.

### Blind-vs-briefed convergence

The blind protocol expert, working only from source plus the Apache Iceberg contract, independently reproduced 8 of the architecture expert's 18 findings — including all three of the most severe code defects (findings 1, 2, 3, each with matching mechanism and failure interleaving) and 4 of the arch report's 6 blockers — and independently hit 7 of the 10 pre-flagged smells the briefed experts were given. Roughly 55% of the briefed experts' code-defect findings were independently reproduced, and 100% at the most-severe tier, which materially raises confidence that the top of this list reflects the code rather than the briefing. The blind expert also contributed five findings no briefed expert made (15, 17, 18, 19, 23) plus the refs-wipe default in finding 11 and the 503-convention evidence that decided finding 12's ruling. Findings made only by briefed experts (6, 10, 13, 14, 16, 20, 21, 25–27 and the testing-suite findings) were verified with corresponding extra skepticism; all reproduce against the code, and none rests on the briefing documents as evidence.

---
*Generated by the synthesis-review procedure: per-finding verification against `2a9dac8`, duplicate merging with source attribution, disagreement adjudication, and posture-based tiering. Expert inputs: architecture review (18 findings, briefed), testing review (12 findings, briefed), blind protocol-correctness review (14 findings + 1 note, contract-referenced against Apache Iceberg).*
