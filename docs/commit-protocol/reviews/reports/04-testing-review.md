# Testing review — OpenHouse table commit protocol (EVALUATE mode)

Reviewer: `testing-review` skill, evaluate mode. Target: the commit-path code named in
`01-code-manifest.md`, rooted at `/home/user/openhouse` (HEAD `2a9dac8`, includes fix #612).
Deployment posture: production correctness-critical commit path with a recent silent-data-loss
incident (incident-12185); concurrency and crash-window behavior are the highest-value targets.
All file:line pointers below were verified against the working tree during this review.

---

## Phase 0 — the two maps

### Contract inventory (what is claimed)

| # | Claim (gating) | Defined where |
|---|---|---|
| C1 | Client `doCommit` routes: metadata+snapshots+existing→`putSnapshotsForReplace` (replaceCommit=true); snapshots→`putSnapshots`; metadata-only→`createUpdateTable` | `integrations/java/iceberg-1.2/openhouse-java-runtime/.../OpenHouseTableOperations.java:142-169, 411-416` |
| C2 | Client stamps the CAS token: `baseTableVersion = base==null ? INITIAL_VERSION : base.metadataFileLocation()`; snapshots body carries the FULL snapshot list + refs | same file `:205-209` (metadata), `:369-376` (snapshots) |
| C3 | Client HTTP→Iceberg exception map: 409→CommitFailed (retriable), 404→NoSuchTable, 400→BadRequest, 5xx/no-response/interrupt→CommitStateUnknown (no file cleanup) | same file `:418-464` |
| C4 | Tables-service advisory CAS (`versionCheck`): request base vs current `openhouse.tableLocation`, scheme-less; mismatch→CommitFailed; deleted-table + non-INITIAL→RequestValidationFailure | `services/tables/.../OpenHouseInternalRepositoryImpl.java:451-475` |
| C5 | Repo stamps `COMMIT_KEY = tableVersion` on every update transaction and forces `commit.num-retries=0` (server-side Iceberg transaction must never auto-retry/rebase) | same file `:196, 201-207` |
| C6 | Catalog CAS (`abortIfWriterBaseDivergedFromCatalog`, fix #612): snapshot-bearing commit whose `COMMIT_KEY` diverges from `base.metadataFileLocation()` (path-normalized), or is INITIAL_VERSION against an existing base, must abort with CommitFailed BEFORE any write. Exclusions: base==null, no SNAPSHOTS_JSON, no COMMIT_KEY (replace/stage paths, authoritative by design) | `iceberg/openhouse/internalcatalog/.../OpenHouseInternalTableOperations.java:269, 604-635` |
| C7 | `failIfRetryUpdate`: a `COMMIT_KEY` seen before (per-JVM 5-min Guava cache) means an internal Iceberg retry re-submitted the same commit → hard CommitFailed ("retry from application"); first sighting caches the key and strips it from properties | same file `:93-94, 642-664` |
| C8 | Subtractive snapshot merge: payload snapshots not in current metadata are added; current snapshots absent from payload are REMOVED; refs synced to payload | same file `:314-354` |
| C9 | New metadata.json written to `{tableLocation}/%05d-{uuid}.metadata.json` before the pointer flips; failure after the write leaves an orphan file and an UNCHANGED HTS pointer (no state change visible to readers) | same file `:258-262, 356-383`; commit point at `:404` |
| C10 | doCommit error classification: HouseTableCaller/NotFound/ConcurrentUpdate→CommitFailed(409); stale-sequence ValidationException→CommitFailed else BadRequest; IOException→checkCommitStatus + HTS cleanup + CommitFailed; other Throwable→`checkCommitStatus` → SUCCESS (treat as committed) / FAILURE (CommitFailed) / UNKNOWN (CommitStateUnknown) | same file `:424-476` |
| C11 | Staged (WAP) commits write metadata.json but never touch HTS; suppressed forced refresh | same file `:401-419, 212-222` |
| C12 | HTS client: writes never retry; 409→HouseTableConcurrentUpdate, 404→NotFound, 4xx→Caller, 5xx→RepositoryStateUnknown; reads retry 3x | `iceberg/openhouse/internalcatalog/.../HouseTableRepositoryImpl.java:58-61, 188-217` |
| C13 | HTS is the atomic commit point: `UserTableVersionMapper` string-compares request `tableVersion` vs row `metadataLocation` and inherits `@Version` only on match; the JPA optimistic lock is the arbiter; any concurrency loss → 409 | `services/housetables/.../UserTableVersionMapper.java:20-47`, `UserTableRow.java:28`, `UserTablesServiceImpl.java:98-127` |
| C14 | Service exception→HTTP: CommitFailed→EntityConcurrentModification→409; CommitStateUnknown→OpenHouseCommitStateUnknown→503 (tables path only — snapshots path has NO CSU catch and falls to 500) | `TablesServiceImpl.java:171-193`, `IcebergSnapshotsServiceImpl.java:89-110`, `OpenHouseExceptionHandler.java:130-152` |
| C15 | Replace/stage-replace is wholesale-authoritative (no COMMIT_KEY, no versionCheck) and is gated: RTAS requires `replace.enabled`, rejected under WAP/replication/lock | `OpenHouseInternalRepositoryImpl.java:154-177`; validation in the same module |
| C16 | Rename commit routes to `houseTableRepository.rename` → HTS `renameTableId` JPQL UPDATE (bypasses `@Version`) | `OpenHouseInternalTableOperations.java:386-400`, `UserTableHtsJdbcRepository.java:115-125` |
| C17 | Replicated-table create rewrites the just-committed metadata.json in place (`fs.create(path, true)`) after the commit point | `OpenHouseInternalTableOperations.java:420-422`, `utils/MetadataUpdateUtils.java:36-58` |

### Control-flow map (partitions and failure windows)

Crash/failure windows S1–S7 per the protocol brief: nothing-written (S1–S2), orphan
metadata.json (S3), ambiguous HTS write (S4: clean 409 vs 5xx/timeout vs
actually-persisted), post-commit-point failures (S5–S6: response lost → client CSU;
replicated-create in-place rewrite). Concurrency points: (a) two writers from the same base
racing to HTS (`@Version` arbitration); (b) the `BaseTransaction.applyUpdates` silent-rebase
window between `versionCheck` and `doCommit` (the #612 class); (c) rename racing a commit
(no `@Version` on rename); (d) same-JVM internal retry re-presenting a burned `COMMIT_KEY`.
Convergence notes: the staged path provably skips HTS (`:401-419`), so
staged-commit-vs-HTS-conflict cells are vacuous; metadata-only commits carry no
SNAPSHOTS_JSON, so subtractive-merge cells for them are vacuous (CAS exclusion at `:610-616`
states the argument in code).

---

## The map: SHOULD × DOES × verdict

Levels: unit = internalcatalog/class tests; contract = module with faked ports; comp = e2e-h2
(Spring, real repo+H2 HTS); client = itest with MockWebServer. Test files abbreviated:
**ITOTest** = `iceberg/openhouse/internalcatalog/src/test/.../OpenHouseInternalTableOperationsTest.java`;
**HTRTest** = `.../repository/HouseTableRepositoryImplTest.java`;
**RepoTest** = `services/tables/src/test/.../e2e/h2/RepositoryTest.java`;
**Settable** = `.../e2e/h2/RepositoryTestWithSettableComponents.java`;
**SnapCtl** = `.../e2e/h2/SnapshotsControllerTest.java`; **TblCtl** = `.../e2e/h2/TablesControllerTest.java`;
**OHTOTest** = `integrations/java/.../javaclient/OpenHouseTableOperationsTest.java`;
**DoCommitTest/SCEMTest** = `integrations/spark/spark-3.1/.../mock/DoCommitTest.java` / `ServerClientExceptionMappingTest.java`;
**HtsRepoTest/HtsCtl/UTVMTest** = housetables `e2e/usertable/HtsRepositoryTest.java`, `HtsControllerTest.java`, `mock/mapper/UserTableVersionMapperTest.java`.

| Claim / partition | SHOULD (type; level; oracle) | DOES | Verdict |
|---|---|---|---|
| C1 routing: snapshots-only; metadata-only | contract; client; request made to right endpoint | DoCommitTest:238-273, 275-298 (request-taken oracle) | covered, weak oracle (endpoint only) |
| C1 routing: metadata+snapshots+base≠null → replaceCommit=true | contract; client; captured body has `replaceCommit=true` | DoCommitTest:276-298 traverses branch, asserts only no-throw; nothing inspects the flag | **GAP** (F5b) |
| C2 baseTableVersion stamping (base / null-base); full snapshot list in body | contract; client; captured request body equals `base.metadataFileLocation()` / INITIAL | none — repo-wide grep: zero client tests reference `baseTableVersion` | **GAP** (F5) |
| C3 client HTTP→exception map (409/404/400/500/501/502/503/504/no-response/interrupt) | contract; client; exact exception type | OHTOTest:67-180; DoCommitTest:194-216; SCEMTest:129-215 (refresh + commit matrices) | covered, strong |
| C4 versionCheck: stale base → CommitFailed; deleted table → RequestValidationFailure | contract; comp; exact exception | RepoTest:833-873 (`testMetadataConcurrentUpdate`), RepoTest:807-830 | covered |
| C5 num-retries=0: server never auto-retries a failed commit | forced interleaving; comp; `save()` called exactly once, `refresh()` exactly twice | Settable:112-206 (`testNoRetryInternalRepo`) — exemplary instrument | covered, strong |
| C5 user-supplied commit.num-retries preserved post-commit | contract; comp; property round-trip | Settable:209-253 | covered |
| C6 CAS: silent-rebase divergence → abort, nothing persisted | fault-injection/pin of incident; unit; throws CommitFailed + `save` never called | ITOTest:258-324 (#612 repro; documented to fail on unfixed code — calibrated) | covered, strong |
| C6 CAS: `COMMIT_KEY=INITIAL_VERSION` vs existing base → abort | contract; unit; exact exception | none (only line 301 in the repro sets COMMIT_KEY, with a path value) | **GAP** (F6) |
| C6 CAS pass path: matching COMMIT_KEY commits cleanly (incl. scheme-full vs scheme-less path) | contract; unit; commit proceeds, key stripped from persisted props | implicit only: every e2e-h2 update passes through it; no unit case, no scheme-normalization case | **GAP** (F6) |
| C7 failIfRetryUpdate: repeated COMMIT_KEY → hard CommitFailed | fault-injection; unit; exact exception + message | **none anywhere** (repo-wide grep for COMMIT_KEY/failIfRetry in tests: only the #612 repro) | **GAP** (F1) |
| C7 failIfRetryUpdate: key burned pre-commit → engine retry after transient failure gets spurious 409 | contract/pin; unit; documents the intended-or-not behavior | none | **GAP** (F1) |
| C8 merge: append initial/existing; append+delete; delete; WAP stage; cherry-pick x3; multi-diff jumps; branch refs | contract; unit; final snapshot-id set + refs | ITOTest:154-223, 331-372, 510-549, 742-930, 1511-1968 — broad matrix | covered |
| C8 merge: deleting a still-referenced snapshot → BadRequest | contract; unit | ITOTest:688-735 | covered |
| C8 stale sequence number → 409 not 400 | contract; unit; exception type + message | ITOTest:1970-2043 (verifies Iceberg's own throw first — good discipline) | covered |
| C9 failed commit leaves old pointer intact (orphan file harmless) | fault-injection; unit/comp; after injected HTS failure, `findById` still returns old location, reader unaffected | partial: #612 test asserts `save` never called; exception-path tests assert only the thrown type | **GAP** (F10, folded into F3/F4 actions) |
| C10 HouseTable\* exceptions → CommitFailed; StateUnknown → CSU | fault-injection; unit; exception type | ITOTest:655-680 | covered (UNKNOWN outcome only) |
| C10 `checkCommitStatus` SUCCESS partition: HTS 5xx but row actually persisted → doCommit treats as SUCCESS, no exception | fault-injection; unit; save throws StateUnknown, findById returns NEW location → no throw | none — the three-way classification is never exercised; only UNKNOWN falls out of the mock defaults | **GAP** (F3) |
| C10 IOException path: checkCommitStatus + HTS cleanup + CommitFailed | fault-injection; unit; exact exception | none — and the path is defective: `delete()` throws `UnsupportedOperationException` (`HouseTableRepositoryImpl.java:319-322`), not in the catch list (`ITO:429-431`) | **GAP + latent bug** (F4) |
| C11 staged commits never touch HTS; forced refresh suppressed | stateful lifecycle; unit; `save`/`findById` never called; `shouldRefresh` false | ITOTest:556-648 | covered, strong |
| C12 HTS status→exception map (save + findById) | contract; MockWebServer; exact exception per code | HTRTest:169-217, 235-259 | covered, strong |
| C12 writes never retried (5xx, DNS timeout); reads retried 3x | fault-injection; contract; retry-listener count == 0 / == N | HTRTest:605-622, 706-726, 561-602, 665-703 | covered, strong (but wall-clock: F12) |
| C13 HTS optimistic lock: wrong/correct/stale `@Version` | contract; comp(H2); exception vs success | HtsRepoTest:129-170; HtsCtl:362 (`testConflictAtTargetVersion` 409 e2e); UTVMTest (all 4 mapper partitions) | covered |
| C13 two concurrent putUserTable between findById and save (read-then-write window) | forced interleaving; comp; loser gets 409, winner's row survives | none — only sequential stale-version cases | gap, low priority: the `@Version` WHERE-clause arbitration is Hibernate's contract; sequential stale-save exercises the same SQL. Accepted with this convergence argument, but see F2 for the cross-service version |
| C14 CommitFailed→409 (snapshots path); →409 (tables path); handler ECME→409, CSU→503 | contract; mock service/controller | `mock/service/IcebergSnapshotsServiceTest.java:94-121`; `mock/controller/TablesControllerTest.java:369-421`; e2e create-conflict TblCtl:401-429 | covered |
| C14 CommitStateUnknown on snapshots path → today 500, not 503 | pin (undocumented asymmetry); mock service; response status | none — `IcebergSnapshotsServiceImpl.java:89-110` has no CSU catch and no test records the resulting 500 | **GAP** (F3b) |
| C15 replace gating: RTAS disabled→400; locked→400; WAP/replication→400; replace merges policies | contract; comp | TblCtl:914-1010+, SnapCtl:317-455, RepoTest:166-517 | covered |
| C15 replace-vs-concurrent-append clobber semantics (undefended by design) | pin; unit; a replace commit with no COMMIT_KEY removes a concurrently-added snapshot without error | none — the design decision lives only in a code comment (`ITO:594-596, 620-622`) | **GAP** (F6b) |
| C16 rename racing a normal commit (`renameTableId` bypasses `@Version`) | forced interleaving; comp; one side must fail or both effects survive | rename happy paths only (HtsRepoTest:172-243, RepoTest:1178-1240, UserTablesServiceTest:391-438) | **GAP** (F7) |
| C17 in-place metadata rewrite: happy path | contract; unit; mocked fs write called | ITOTest:379-509 | covered |
| C17 rewrite failure/crash → committed pointer targets corrupt file; cache now disagrees with file | fault-injection; unit; injected IOException mid-rewrite → surfaced error + cache re-seeded or invalidated | none | **GAP** (F8) |
| Two-writer end-to-end: loser 409, engine retry, FINAL state contains both writers' snapshots | forced interleaving; comp (e2e h2); union oracle | **none at any level** — the concurrent functional test from PR #614 was dropped (only reproduced on H2); nothing replaced it | **GAP** (F2) |
| processSchemas: unparsable intermediate schema | contract-or-pin; unit; today it is silently skipped (`ITO:704-716` catches Exception and logs) | none | **GAP** (F9) |

DOES-minus-SHOULD (tests attacking no claim): the `assertEquals(4, updatedProperties.size())`
property-count assertions (F11); otherwise the suite is lean — the mocked
`TableMetadataParser.write` in ITOTest and the mocked HTS repos are legitimate boundary
devices for fault injection and are not flagged (anti-noise rule).

---

## Findings

### F1 — failIfRetryUpdate has zero tests in the repository
- **location**: `iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/OpenHouseInternalTableOperations.java:642-664` (cache decl `:93-94`); no test found (repo-wide grep for `failIfRetry`/`COMMIT_KEY` over test sources hits only `OpenHouseInternalTableOperationsTest.java:301`)
- **principle**: 1 (claims before tests — defined behavior with no test is an unverified promise); 4 (the instrument cannot lie)
- **claim**: The dedup-cache guard — the only backstop against a server-side internal Iceberg retry silently re-submitting a user commit — is never exercised: neither the duplicate-key abort nor the pre-commit key burn has a test.
- **evidence**: `if (CACHE.getIfPresent(userProvidedTblVer) != null) { throw new CommitFailedException(... "is stale, please consider retry from application" ...); } else { CACHE.put(userProvidedTblVer, 1); }` — the key is cached before the commit succeeds (`:648-654`), so a failed commit burns its key; no test constructs either scenario.
- **failure scenario**: A regression (wrong cache key, cache removed, ordering change vs the CAS, TTL misconfiguration) ships silently. With `commit.num-retries=0` depending on forked-iceberg behavior (`OpenHouseInternalRepositoryImpl.java:201-207`), this guard is the second line of defense against exactly the incident-12185 rebase class. Separately, the pre-commit burn means a legitimate engine retry after a transient failure on the same instance gets a spurious "stale" 409 — behavior nobody has pinned, so nobody will notice if it changes in either direction.
- **decision**: Write two unit tests in ITOTest (fault-injection at contract level, example oracle): (1) two `doCommit` calls with the same `COMMIT_KEY` where the first fails at HTS (mock save throws) — second must throw CommitFailed containing "is stale", `save` not called again; (2) first sighting commits cleanly and the persisted properties do not contain `commitKey`. Add an explicit pin test for the burn-before-success semantics with a comment marking it a pin, so a future fix to smell #2 flips a labeled test, not production.
- **severity**: blocker · **confidence**: confirmed · **reviewer**: testing-review

### F2 — no test at any level proves a lost-update-free two-writer commit (win, 409, retry, union survives)
- **location**: absent; nearest neighbors: `OpenHouseInternalTableOperationsTest.java:258` (#612 doCommit-boundary repro), `RepositoryTest.java:833` (sequential stale base), `RepositoryTestWithSettableComponents.java:112` (no-server-retry)
- **principle**: 0 (a case set clustered on one path while a reachable branch has no case through it); 4 (races: force the interleaving at a seam)
- **claim**: The end-to-end concurrency contract — writer B losing to writer A gets 409, refreshes, retries, and the final table contains BOTH snapshots — is asserted nowhere; every existing test checks one link of the chain (versionCheck 409, HTS 409 mapping, CAS abort) but none checks the recovery composition in which the subtractive merge runs a second time against the advanced base.
- **evidence**: The fix's own history: `SparkConcurrentInsertFunctionalTest` (PR #614) was dropped ("only reproduced against the H2 test fixture") and nothing replaced it. SnapCtl has no test that PUTs snapshots with a stale `baseTableVersion` at all — grep for `isConflict` in `SnapshotsControllerTest.java` returns nothing.
- **failure scenario**: A regression in the retry path — e.g. the merge mis-computing `toRemove` when the retried payload is recomputed against T_Y, or the 409 message/type change breaking the engine's retry classification — silently reintroduces snapshot loss one hop away from the incident the #612 test pins.
- **decision**: Write a forced-interleaving test at the composition level (e2e h2, `SnapshotsControllerTest`): create table; commit snapshot S_a as writer A; PUT snapshots as writer B with `baseTableVersion` = the pre-A location and a payload omitting S_a → assert HTTP 409; re-PUT as B with refreshed base and payload {S_a, S_b} → assert 200 and final snapshot set == {S_a, S_b} via the catalog (delta/union oracle, the helper at `RequestAndValidateHelper.java:298-341` already validates against the loaded table). This also closes the missing stale-base-409 e2e cell on the snapshots endpoint.
- **severity**: blocker · **confidence**: confirmed · **reviewer**: testing-review

### F3 — the commit-state-ambiguity window is tested for only one of its three outcomes
- **location**: `OpenHouseInternalTableOperations.java:452-476` (`checkCommitStatus` → SUCCESS/FAILURE/UNKNOWN); only-UNKNOWN test at `OpenHouseInternalTableOperationsTest.java:675-679`; untyped 500 on snapshots path: `services/tables/src/main/java/com/linkedin/openhouse/tables/services/IcebergSnapshotsServiceImpl.java:89-110` (no `CommitStateUnknownException` catch, unlike `TablesServiceImpl.java:171-193`)
- **principle**: 0 (missed partition); 5 (fault-injection is the legitimate mock use — but only if the fault matrix is complete)
- **claim**: When HTS returns 5xx/timeout on save, `checkCommitStatus` re-reads the pointer to classify the commit; the SUCCESS partition (HTS actually persisted → doCommit must return success) and the FAILURE partition (provably not persisted → CommitFailed) have no tests, and the snapshots endpoint surfacing CSU as HTTP 500 instead of 503 is unpinned.
- **evidence**: `testDoCommitExceptionHandling` stubs `save` to throw `HouseTableRepositoryStateUnknownException` and asserts `CommitStateUnknownException` — with the default mock, `findById` returns empty, so only UNKNOWN is ever reached. No test stubs `findById` to return the NEW metadata location after a failed-looking save.
- **failure scenario**: Misclassification in the ambiguity window ships silently: a commit that actually landed reported as unknown (client app re-drives writes, duplicate data risk at the application layer) or — worse — a lost commit reported as success. On the snapshots path, a load balancer or client that treats 500 differently from 503 (retry budgets, alerting) behaves differently from the tables path with nobody having decided that.
- **decision**: (a) Add two fault-injection unit tests in ITOTest: save throws StateUnknown + `findById` returns the new location → doCommit completes without exception (SUCCESS); save throws StateUnknown + `findById` returns the OLD location → CommitFailed (FAILURE). Assert in both that the old pointer is what a reader would see on failure (closes the C9 old-pointer-intact gap). (b) Add a mock-service test that `IcebergSnapshotsServiceImpl.putIcebergSnapshots` on `CommitStateUnknownException` yields 500 today, labeled as a pin — or fix the asymmetry and assert 503 as a contract; either way the choice stops being implicit.
- **severity**: blocker · **confidence**: confirmed · **reviewer**: testing-review

### F4 — the IOException cleanup path is untested and defective: `delete()` throws UnsupportedOperationException that masks the CommitFailedException
- **location**: `OpenHouseInternalTableOperations.java:424-437`; `HouseTableRepositoryImpl.java:319-322` (`public void delete(HouseTable entity) { throw new UnsupportedOperationException("Entity deletion is not supported."); }`)
- **principle**: 4 (code no test exercises is a question never asked — and this one has a wrong answer waiting); none (internal consistency) for the bug itself
- **claim**: On `IOException` during doCommit the handler calls `houseTableRepository.delete(houseTable)` whose production implementation unconditionally throws `UnsupportedOperationException`, which is not among the caught types (`HouseTableCallerException | HouseTableNotFoundException | HouseTableConcurrentUpdateException`, `:429-431`), so the intended `CommitFailedException(ioe)` is replaced by an unclassified 500 — and no test reaches this path.
- **evidence**: quoted above; also note `houseTable` may still be the empty `HouseTable.builder().build()` from `:264` when the IOException precedes mapping, so even a working delete would target a nonsense row.
- **failure scenario**: Any storage IOException during the metadata write turns a retriable 409 into a generic 500; the client maps 500→CommitStateUnknown, so the engine will NOT clean up and will NOT retry — availability and operator-confusion damage in exactly the failure mode (storage blips) this branch was written for.
- **decision**: Write the fault-injection unit test first (mock `io()`/output file to throw IOException mid-write; assert `CommitFailedException` with the IOException as cause) — it fails today and forces the fix (catch UnsupportedOperationException, or delete the cleanup call, whose semantics for update commits are wrong anyway: deleting the row would drop the whole table pointer).
- **severity**: blocker · **confidence**: confirmed (code path verified; test would fail as written today) · **reviewer**: testing-review

### F5 — the client-side CAS token is asserted by no test; replace-routing has an oracle that cannot distinguish the branches
- **location**: `integrations/java/iceberg-1.2/openhouse-java-runtime/.../OpenHouseTableOperations.java:205-209, 369-376` (stamping), `:148-155` (routing); tests: `DoCommitTest.java:238-298` (request-made/no-throw oracles only); grep for `baseTableVersion` over both itest trees: zero hits
- **principle**: 1 (defined behavior with no test is an unverified promise); 3 (oracle strength — the existing oracle is weaker than the free one)
- **claim**: No test anywhere asserts that the request body carries `baseTableVersion == base.metadataFileLocation()` (or INITIAL on create), the full snapshot list, or `replaceCommit=true` on the metadata+snapshots+existing route — the token every downstream CAS (versionCheck, #612 abort, HTS compare) consumes.
- **evidence**: `testMetadataWithDataChange` ends in `Assertions.assertDoesNotThrow(() -> ops.doCommit(base, dataPlusMetaDataChangeOffBase))` — both `putSnapshots` and `putSnapshotsForReplace` hit the same `putSnapshotsV1` endpoint, differing only in the `replaceCommit` body flag the test never reads.
- **failure scenario**: A stamping regression (e.g. using `metadata` instead of `base`, or a refactor moving the stamp after a refresh) disables the entire server-side CAS chain while every existing test — client and server — stays green, because server tests inject properties directly and e2e-h2 bypasses this client. A mis-routing regression that marks an ordinary append `replaceCommit=true` sails past the #612 CAS entirely (replace carries no COMMIT_KEY and is wholesale-authoritative, `OpenHouseInternalRepositoryImpl.java:154-177`). This is the same escape topology as incident-12185: every layer assumed another layer checked.
- **decision**: Write contract tests at the client level using the existing MockWebServer (`DoCommitTest`): capture `takeRequest()` and assert on the parsed body — (1) update: `baseTableVersion == base.metadataFileLocation()`, `replaceCommit` absent/false; (2) create: INITIAL_VERSION; (3) metadata+snapshots+existing: `replaceCommit == true`; (4) snapshots body contains exactly `newMetadata.snapshots()` serialized. Example oracle; the parsing is already free.
- **severity**: blocker · **confidence**: confirmed · **reviewer**: testing-review

### F6 — CAS partitions beyond the incident repro are untested, and the deliberate replace-path exclusion is undocumented by any test
- **location**: `OpenHouseInternalTableOperations.java:604-635` (partitions at `:605, 610, 620, 624`); sole test `OpenHouseInternalTableOperationsTest.java:258-324`
- **principle**: 0 (case set clustered on one path); 6 (deliberate exclusions deserve a stated, visible record)
- **claim**: Of the CAS's five partitions, only "path-valued COMMIT_KEY diverges" is tested. Untested: (a) `COMMIT_KEY=INITIAL_VERSION` against an existing base → must abort (a create raced an existing table); (b) matching COMMIT_KEY → passes, including the scheme-full vs scheme-less normalization (`hdfs://nn/path` vs `/path`) that the Hadoop-Path comparison exists for; (c) no-COMMIT_KEY + SNAPSHOTS_JSON present (the replace path) → intentionally passes and can remove concurrent snapshots — a design decision recorded only in comments.
- **evidence**: grep confirms line 301 is the only test assignment of `CatalogConstants.COMMIT_KEY`; the exclusion rationale lives at `:594-596` and `:620-622` in main code only.
- **failure scenario**: (a) ships a silent create-clobber; (b) a normalization regression turns every commit from a scheme-full client into a spurious 409 (availability incident) — nothing would catch it before production since e2e-h2 uses consistent schemes; (c) if someone later "fixes" the null-COMMIT_KEY pass-through or a client change starts sending COMMIT_KEY on replace, behavior flips with no test flipping.
- **decision**: Three unit tests in ITOTest: INITIAL_VERSION-vs-existing-base → CommitFailed; matching key with `hdfs://` scheme on one side → commit proceeds and `save` called; replace-shaped metadata (SNAPSHOTS_JSON, no COMMIT_KEY) omitting an existing snapshot → commits and removes it, labeled `// PIN: replace is wholesale-authoritative by design (see #612 scope exclusions)`.
- **severity**: suggestion · **confidence**: confirmed · **reviewer**: testing-review

### F7 — rename bypasses the optimistic lock and no test asks what a rename racing a commit does
- **location**: `services/housetables/.../repository/impl/jdbc/UserTableHtsJdbcRepository.java:115-125` (JPQL UPDATE, no `@Version` check/bump); rename commit routing `OpenHouseInternalTableOperations.java:386-400`; existing tests are happy-path only (`HtsRepositoryTest.java:172-243`, `RepositoryTest.java:1178-1240`)
- **principle**: 0 (concurrency point with no case through it); 4 (three-tier race response — none present)
- **claim**: `renameTableId` writes `metadataLocation` without CAS; a rename racing a normal commit can clobber the winner's pointer with the rename's own metadata location, and no test at any level exercises rename-vs-commit ordering.
- **failure scenario**: A committed snapshot's pointer is overwritten by a concurrent rename — the same silent-lost-update class as the incident, through a side door the #612 CAS does not cover (rename commits carry no SNAPSHOTS_JSON, so the CAS returns at `:610`).
- **decision**: Write a forced-interleaving test at the comp level (H2 HTS): save row at version v; issue a normal `save` bumping the pointer; then `renameTableId` carrying the pre-bump metadata location; assert the final row — today the rename wins and the commit's pointer is lost; land it as a labeled pin so the hole is visible, and attach the finding that the fix is a `@Version`/metadataLocation guard in the rename JPQL.
- **severity**: suggestion · **confidence**: probable (interleaving reasoning verified in code; not executed) · **reviewer**: testing-review

### F8 — post-commit in-place metadata rewrite (replicated create) has happy-path tests only
- **location**: `utils/MetadataUpdateUtils.java:36-58` (`fs.create(path, true)` overwrite after the commit point, call at `OpenHouseInternalTableOperations.java:420-422`); tests `OpenHouseInternalTableOperationsTest.java:379-509` (mocked fs, success only)
- **principle**: 0 (failure window S6 has no case); 5 (the mock exists — the fault was just never injected)
- **claim**: A failure mid-rewrite corrupts the file the just-persisted HTS pointer references, and the seeded metadata cache (`:367`) silently disagrees with the rewritten file; neither behavior has a test.
- **failure scenario**: Replicated-table creates that crash mid-rewrite leave a committed pointer to a truncated metadata.json — table unreadable until manual repair; the cache masking it on the same instance makes diagnosis worse.
- **decision**: Fault-injection unit test: `mockFileSystem.create` throws / stream fails mid-write → assert the error surfaces (not swallowed) and the cache entry for `newMetadataLocation` is invalidated or re-seeded. If the team instead decides the window is accepted, record it as a skip-with-reason, not silence.
- **severity**: suggestion · **confidence**: probable · **reviewer**: testing-review

### F9 — processSchemas swallows per-schema parse failures; the silent skip is neither contract nor pin
- **location**: `OpenHouseInternalTableOperations.java:704-716` (`catch (Exception e) { log.error("Failed to process schema: ..."); }` inside the per-schema loop)
- **principle**: 6 (no question dropped silently); 1 (behavior nothing defines)
- **claim**: An unparsable intermediate schema is logged and skipped, so a replicated commit can land with fewer schema versions than requested; no test exercises the branch, and no document defines skip-vs-fail.
- **failure scenario**: Replication silently diverges schema history between clusters; discovered only when a reader needs the missing schema id (compare the tested corrupt-schema refresh failure at ITOTest:2107 — the write side has no equivalent).
- **decision**: Decide the contract, then one unit test: bad intermediate schema JSON → either the commit fails (assert exception) or the skip is pinned with a label. Surfacing the decision is the point.
- **severity**: suggestion · **confidence**: confirmed (branch verified, no test) · **reviewer**: testing-review

### F10 — no test asserts the S3/S4 invariant "failed commit ⇒ old pointer intact, orphan file only"
- **location**: failure-path tests `OpenHouseInternalTableOperationsTest.java:655-680` (assert exception type only); the invariant is stated in the protocol (commit point = HTS row, `:404`)
- **principle**: 3 (oracle strength — the free delta assertion on observable state is unused)
- **claim**: Exception-path tests never assert that the HTS row was not saved and that a subsequent refresh still serves the old base — the actual safety property of the orphan-file design.
- **decision**: Fold into F3/F4's new tests: each failure-injection case additionally asserts `verify(repo, never()).save(any())` (where applicable) and that `doRefresh` state is unchanged. No new test files needed.
- **severity**: suggestion · **confidence**: confirmed · **reviewer**: testing-review

### F11 — property-count assertions are unlabeled pins that fail for the wrong reason
- **location**: `OpenHouseInternalTableOperationsTest.java:172-175, 213-216, 539-542` — `Assertions.assertEquals(4, updatedProperties.size()); /*write.parquet.compression-codec, location, lastModifiedTime, version*/`
- **principle**: 1 (a test asserting behavior nothing defines is a pin mislabeled as a contract)
- **claim**: The exact property count is not a contract; any legitimately added property fails three tests with a message that names no broken promise.
- **decision**: Replace with targeted assertions (the specific keys that must/must-not be present — the transport keys `snapshotsJsonToBePut`, `snapshotsRefs`, `commitKey` stripped is the real claim) or keep and label as a pin. The stripped-commitKey assertion this would add also serves F1.
- **severity**: nit · **confidence**: confirmed · **reviewer**: testing-review

### F12 — timeout tests burn ~2 minutes of wall clock through real sleeps
- **location**: `HouseTableRepositoryImplTest.java:651-662` (`setHeadersDelay(58, SECONDS)`), `:665-703` (2 × 31 s delays)
- **principle**: 4 (time enters through seams)
- **claim**: The write/read timeout claims are tested against real elapsed time, making the module's suite slow and CI-fragile; the timeout values are constructor-injectable in principle.
- **decision**: Move the timeout configuration to a seam (short test-only timeouts) and keep the same assertions. Keep the tests — the claims are right, only the clock is wrong.
- **severity**: nit · **confidence**: confirmed · **reviewer**: testing-review

---

## Verdict

**Ship after the named tests.** The instrument does not lie — no retried flakes, no order
dependence, no vacuous-pass patterns were found; the suites that exist are largely
well-aimed, and three are exemplary (the #612 repro with its documented red state, the
no-server-retry interleaving test, the HTS-client fault matrix). But the diff shows the
suite is strongest exactly where the incident already forced it and thinnest in the
neighboring cells the same incident predicts: the retry-recovery composition, the dedup
cache, the ambiguity classifier, and the client-side token that feeds every CAS.

Named actions, in the order the next escaped defect would exploit them:

1. **F1** — two unit fault-injection tests for `failIfRetryUpdate` (dup-key abort; clean first-sighting + key stripped), plus the labeled burn-before-success pin.
2. **F2** — one composition test in `SnapshotsControllerTest`: stale-base PUT → 409 → refreshed retry → final snapshot set is the union.
3. **F3** — checkCommitStatus SUCCESS and FAILURE partitions as unit fault-injection tests (with F10's no-state-change assertions); pin-or-fix the snapshots-path CSU→500.
4. **F4** — IOException-during-write unit test (fails today; forces the UnsupportedOperationException fix).
5. **F5** — client request-body contract tests: baseTableVersion stamping (update/create), replaceCommit routing flag, full-snapshot-list payload.
6. **F6–F9** — CAS partitions + labeled replace-clobber pin; rename-race pin; rewrite fault injection; processSchemas decision.
7. **F11–F12** — assertion cleanup and clock seams, opportunistically.

Counts: 12 findings — 5 blockers (F1–F5), 5 suggestions (F6–F10), 2 nits (F11–F12).
