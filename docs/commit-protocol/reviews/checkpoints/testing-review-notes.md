# Testing review checkpoint — OpenHouse commit path (EVALUATE mode)

## Status
- [x] Skill read; context read
- [x] DOES-set inventory complete (all files below read/grepped)
- [x] Source lines verified for citations
- [x] Report written -> reports/04-testing-review.md (12 findings: 5 blockers F1-F5, 5 suggestions F6-F10, 2 nits F11-F12; verdict: ship after named tests)

## DOES inventory (verified)
### internalcatalog unit (OpenHouseInternalTableOperationsTest.java, 2131 lines)
- doCommit append initial/existing (154, 188): oracle = captured HouseTable properties + save() called; TableMetadataParser.write mocked out
- **testDoCommitMustAbortStaleBaseRebaseToPreventSnapshotLoss (258)** — the #612 repro; only test that sets COMMIT_KEY (grep confirms lines 301 only)
- append+delete (331), delete (510), staged create/WAP (556, 581, 608, 742, 766), cherry-pick x3 (804, 843, 879), deleteLastStaged (911)
- exception mapping (655): HouseTableCaller/ConcurrentUpdate/NotFound→CFE; StateUnknown→CSU (via Throwable→checkCommitStatus)
- validation → BadRequest (688); stale seq number → 409 CFE (1970)
- multi-diff commits (1511-1863); replicated-create updateMetadata (379-509); metrics/spans/cache tests; corrupt metadata refresh (2107, 2124)
- NOT present: failIfRetryUpdate dedupe (no test anywhere, repo-wide grep), CAS pass-path unit, CAS INITIAL_VERSION-vs-existing partition, IOException handler path, rename-commit doCommit branch, checkCommitStatus SUCCESS-reconciliation partition
### HTS client (HouseTableRepositoryImplTest, MockWebServer): status→exception map save+findById (169, 235), write no-retry incl 5xx (605) + DNS (706), write timeout (651), read retries (561, 665). Strong fault-injection.
### services/tables e2e h2: RepositoryTest.testMetadataConcurrentUpdate (833, sequential stale base → CFE via versionCheck); testMetadataUpdateForDeleted (807); RepositoryTestWithSettableComponents.testNoRetryInternalRepo (112 — forced fault at HTS save, asserts save x1, refresh x2 = no server-side Iceberg retry), testSaveClearsTransientCommitProperties (209), testFailedHtsRepoWhenGet (268)
- SnapshotsControllerTest: append/delete/multiple/replica/replace/locked; helper asserts tableVersion==baseTableVersion + snapshots vs catalog (RequestAndValidateHelper:298-341). No stale-base 409 e2e on snapshots endpoint.
- TablesControllerTest: create-conflict 409 (401-429), stage-replace flows incl RTAS-disabled 400 (testStagedReplaceFailsWhenRtasDisabled), WAP/replication rejected
- mock: controller exception→status incl CSU→503 (TablesControllerTest.mock:369-421); IcebergSnapshotsServiceTest CFE→ECME (94-121); TablesServiceTest.mock HouseTableConcurrentUpdate/Caller (56, 68) — no CSU translation test at service layer
### housetables: UserTableVersionMapperTest (4 cases incl mismatch); HtsRepositoryTest.testSaveUserTableWithConflict (129 — @Version wrong/correct/stale); HtsControllerTest.testConflictAtTargetVersion (362); UserTablesServiceTest happy update (373), rename (391); rename bypass of @Version untested as race
### client: OpenHouseTableOperationsTest (java-itest) HTTP→exception incl interrupted (67-180); DoCommitTest (spark mock) 409/500/503/504/400/404 + routing smoke (194-298); ServerClientExceptionMappingTest doRefresh+doCommit matrix (129-215). NO test asserts baseTableVersion payload value (grep: zero hits in itest dirs).
### fixtures/apps: OpenHouseSparkITest embedded server; no concurrent-writer functional test anywhere (PR #614 test dropped per bug report §4)

## Verified source pointers for findings
- ITO.java: CAS 604-635 (call 269), failIfRetryUpdate 642-664, CACHE 93-94, merge 314-354, IOException handler 424-437 (delete→UnsupportedOperationException at HouseTableRepositoryImpl.java:319-322 uncaught), Throwable→checkCommitStatus 452-476, processSchemas swallow 704-716, staged skip HTS 401-419, rename branch 386-400, replicated rewrite 420-422 → MetadataUpdateUtils.java:36-58 fs.create(path,true)
- OpenHouseInternalRepositoryImpl.java: COMMIT_KEY set 196, num-retries "0" override 201-207, versionCheck 451-475
- IcebergSnapshotsServiceImpl.java: catch list 91-109 lacks CommitStateUnknownException
- client OpenHouseTableOperations.java: baseTableVersion stamp 205-209 (metadata), 369-370 (snapshots full list 371-376)
- HtsControllerTest.testConflictAtTargetVersion:362; UserTableRow @Version; UserTableVersionMapper.java:20-47

## Key SHOULD-minus-DOES gaps (to write up)
G1 failIfRetryUpdate: zero tests (both partitions: dup key → CFE; key burned pre-commit → spurious 409 after transient failure)
G2 CAS partitions untested: INITIAL_VERSION vs existing base; pass-path unit (implicit e2e only); no-COMMIT_KEY + SNAPSHOTS_JSON present (replace path) convergence undocumented
G3 CommitStateUnknown ambiguity resolution: checkCommitStatus SUCCESS partition (HTS 5xx but persisted → commit treated success) untested; CSU on snapshots service path → 500 not 503 unpinned (smell 8)
G4 IOException handler: delete() UnsupportedOperationException masks CFE → untested landmine (smell 1)
G5 client baseTableVersion stamping unasserted anywhere (CAS token integrity)
G6 rename-vs-commit race: renameTableId bypasses @Version, no interleaving test (smell 4)
G7 replicated-create in-place rewrite non-atomic post-commit (smell 3): only happy-path mocked-fs tests
G8 no concurrent two-writer test at any level (forced interleaving at HTS save exists only for clean 409; no full interleaving w/ subtractive merge protecting racing snapshot after retry)
G9 processSchemas swallows schema parse failure — silently-skipped schema untested/unpinned
G10 orphan metadata.json on failed commit (S3 window) — no test asserts HTS row unchanged + file orphaned after injected save failure (partial: unit tests assert exception but not "old pointer intact")
DOES-minus-SHOULD: property-count assertions (assertEquals(4, updatedProperties.size())) = brittle pins unlabeled; testWriteTimeout 58s wall-clock sleep test
