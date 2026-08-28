# Arch review working notes — OpenHouse commit path
HEAD: 2a9dac8ae96d408164ab45898b62c12138e0ea73

Writing rules fetched (STRUCTURE.md): conclusion first; tables over lists; numbered items; self-contained; caveats must matter; complete sentences; layered (TLDR→sketch→detail→appendix); present tense.

## Status
- [x] Skill + PRINCIPLES read
- [x] Manifest, protocol brief, bug case study read
- [ ] Orient: module graph
- [ ] Read code (25 manifest files)
- [ ] Verify 10 pre-flagged smells independently
- [ ] Draft findings with options
- [ ] Write 04-arch-review.md

## Findings candidates (running)
(none yet)

## After reading OpenHouseInternalTableOperations.java (ITO)
Module graph: services:tables -> {services:common, internalcatalog, client:hts}; internalcatalog -> {services:common, client:hts, cluster:*}; services:housetables -> {services:common, htscatalog}. Client runtime separate (integrations/java/iceberg-1.2).

Candidate findings (to verify with callers):
A. doCommit catch(IOException) 424-437: only checked-IOException source in try block appears to be updateMetadataFieldForTable (line 421, POST-commit-point) => handler throws CommitFailedException AFTER durable commit; ignores checkCommitStatus result it computed; delete(houseTable) unimplemented in prod (verify HouseTableRepositoryImpl.delete) and semantically wrong for updates. VERIFY: does anything else in try throw checked IOException?
B. Property-bag protocol: COMMIT_KEY/SNAPSHOTS_JSON/staging flags/sort order/schemas smuggled through Iceberg transaction properties; caused #612 (applyUpdates rebased props). Structural root finding. Ordering of strips load-bearing (589-599).
C. failIfRetryUpdate cache: per-JVM, key burned pre-commit (654); transient HouseTableCallerException(429) -> 409 -> engine retry same base same instance -> spurious hard 409 "retry from application". Overlaps with commit.num-retries=0 + CAS: three mechanisms own one decision.
D. processSchemas 704-715: catch(Exception) log-and-continue inside stream lambda -> silently skipped schema on replication path -> wrong committed metadata.
E. isStaleSnapshotError 670-675: string-matching Iceberg's ValidationException message; version bump => retriable conflict becomes 400.
F. Rename detection 386-394 inferred from property diff vs tableIdentifier; rename path 395-400 -> HTS renameTableId (verify @Version bypass).
G. refs: snapshots present + refs null => all refs removed (317-320, 347-351). Verify client always sends refs.
H. cache.seed at 367 pre-HTS-save; in-place rewrite 420-421 after seed => cache/file divergence (verify TableMetadataCache).
I. doRefresh 126-130 IllegalStateException when row vanished -> 500 to client (deleted-concurrently is a normal outcome; absent as a type?).

## HTS side verified
- HouseTableRepositoryImpl.delete(entity) throws UnsupportedOperationException at 319-322 => finding A confirmed (uncaught in ITO 429-431).
- HouseTableRepository extends PagingAndSortingRepository: wide framework contract, half unimplemented -> the delete() landmine is a direct consequence (Enumerable outcomes / minimal knowledge).
- handleHtsHttpError: 401/403/400/429 -> HouseTableCallerException -> ITO maps to CommitFailedException(409 conflict) => infra/auth failure rendered as caller conflict (Dependency failure own category).
- UserTableHtsJdbcRepository.renameTableId 115-125: @Modifying JPQL UPDATE, no @Version check/bump, unconditional metadataLocation overwrite. Rename racing a commit clobbers winner metadataLocation => snapshot loss channel OPEN at HEAD (rename commits skip abortIfWriterBaseDiverged since no SNAPSHOTS_JSON_KEY). Finding F -> blocker.
- UserTableVersionMapper: raw string .equals on metadataLocation (no scheme normalization; upstream checks normalize) — smell #5 confirmed. CAS decision lives in a MapStruct mapper plugin (ownership smeared). Throws EntityConcurrentModificationException(msg, new RuntimeException()) — fabricated cause destroys descent record.
- putUserTable not @Transactional (find-then-save); safe only via @Version — smell #10 confirmed.
- UserTablesServiceImpl imports org.apache.iceberg.exceptions.CommitFailedException (line 27, catch at 112) — HTS knows Iceberg vocabulary (upward reference; smell #9 confirmed).

## Final findings slate (before write-up)
Blockers: F1 doCommit IOException handler (post-commit-only reachable, delete() landmine, ignores checkCommitStatus); F2 in-place rewrite MetadataUpdateUtils (fs.create overwrite of committed file + cache divergence + cause-drop); F3 rename bypasses all CAS (renameTableId JPQL no @Version, ITO CAS skips renames); F4 property-bag commit protocol through Iceberg transaction (root cause of #612, fork-coupled); F6 client diff-routing silently no-ops unclassified commits (probable); F10 processSchemas swallow.
Suggestions: F5 CAS ownership smeared 4 sites + comparison-semantics drift (raw equals in HTS); F7 replace inferred from diff + CAS-exempt; F8 snapshots path missing CommitStateUnknown catch (500 vs 503); F9 failIfRetryUpdate pre-commit key burn per-JVM; F11 isStaleSnapshotError message matching; F12 PagingAndSortingRepository wide contract + HTS catches Iceberg CommitFailedException; F13 client doRefresh 400->absent; F14 snapshot payload trusted wholesale (parse ignores FileIO).
Nits: F15 stacktraces in HTTP responses; F16 fabricated causes (UserTableVersionMapper new RuntimeException; MetadataUpdateUtils new IOException(errMsg)); F17 interrupt flag not restored client doCommit; F18 doRefresh concurrent-drop -> IllegalStateException 500.
Checked-IOException proof: only `updateMetadataFieldForTable` (ITO:804, called :421) declares IOException inside doCommit try => catch(IOException):424 reachable only post-commit-point.

## DONE
Report written to reports/04-arch-review.md. 18 findings: 6 blockers (F1 IOException handler post-commit misreport; F2 in-place rewrite; F3 rename CAS bypass; F4 property-bag protocol; F5 client non-total routing [probable]; F6 processSchemas swallow), 8 suggestions, 4 nits. All line refs verified at HEAD 2a9dac8.
