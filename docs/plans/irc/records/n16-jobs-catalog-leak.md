# N16 (+X9): jobs build one catalog per job run, not one per table load — record

Marks: [ran] run in /home/user/oh-n16 (JDK 17); [read] read in code; [inferred] reasoning, not run.
Branch irc/n16-jobs-catalog-leak; draft pull request 141; head f4e7d716a1bde7997fec550785c4aa6b742ed766.

## Bottom line
- Operations builds its catalog on first use, reuses it, closes it in close(). 300 loads + 1 expireSnapshots:
  301 catalogs before, 1 after [ran, both jobs lines]. Full 1.12 suite: heap after full GC at the end 486-494 MB
  of 512 before (base + the pinning test), 369 MB after; live client pools 1167 -> 682 [ran].
- Left: one undisposed OpenHouse client pool per Operations (per job run) and per Spark session (Spark's own
  catalogs); disposing needs a Java client change (owner).
- X9 done: WARN + metric retention_backup_delete_file_count when expired partitions of a backup table carry deletes.
- CI on d0ce3a36 (N16 + merge): all green. CI on f4e7d716 (with X9): see final report.

## Commits
1. 6c2e4088 pinning test (asserts today's 301) + seam catalogsBuilt(); no behavior change.
2. b89131f2 fix; test flipped to 1; + closeClosesTheCatalog; + seam currentCatalog().
3. d0ce3a36 merge origin/irc-integration dfcd15a0 (no apps/client/integrations-java change).
4. f4e7d716 X9.

## Who constructs and closes Operations [read]
- Production: BaseSparkApp.run only, try-with-resources (closed before onFinished). Scheduler: none (TablesClientFactory).
- Tests: ~70 sites, most try-with-resources; FormatV3Tables.ops / loserOps / BucketBehaviorTest / DataLayout... /
  Spark42TypeRestCatalogTest build without closing (one catalog each now, previously one per load).

## Level of the fix (decision)
- (a) jobs: chosen. Smallest change, touches only the jobs; fixes the per-load growth everywhere it occurs.
- (b) WebClientFactory share/dispose: not done. Changes the pool limits / lifetime for every user of the client;
  brought to the orchestrator. OpenHouseCatalog's close (1.12: BaseMetastoreCatalog, metrics reporter only) does
  not reach the pool; on 1.5.2 [inferred] same.

## No behavior change
- Freshness [read+ran]: OpenHouse catalog refreshes per load (BaseMetastoreCatalog, no cache); RESTCatalog
  (fork 1.12 a88f556c) RESTTableCache is filled only from an ETag response header; the tables service sends none
  (grep services: no ETag, no ShallowEtagHeaderFilter). getTableSeesWhatOtherClientsCommitted passes on 1.12,
  reference and 4.2: snapshot (SQL INSERT via the session's catalog), schema change and property (another catalog).
- Requests [read]: OpenHouse catalog initialize sends no request; loads unchanged; session-id header from the
  singleton TablesApiClientFactory, already one per JVM. REST: GET /config once per RESTCatalog.initialize ->
  one per Operations (4.2 service.log has no access log, so not counted there; catalogsBuilt is the count).
- Auth [read]: OpenHouse catalog: auth-token read at initialize from the Spark property; updateAuthToken never
  called in main code; property never changed in a run. REST: static `token` property; for a JWT with exp a
  token-exchange refresh is scheduled before expiry (POST /v1/oauth/tokens, unserved by the service). Per-call
  catalogs did the same per catalog plus an attempt at init after expiry. No fresh-token source either way.
  Edge difference: number/timing of failed exchange attempts for a JWT expiring mid-run. Test token has no exp.
- Concurrency: getCatalog synchronized; batched apps share one catalog across their thread pool (as Spark's SQL
  catalog already is); one pool of max 500 connections instead of one per load.
- Conf read at first use, not per call: production sets everything before Operations is built.
- Close: Operations.close closes a Closeable catalog (RESTCatalog: HTTP client, sessions; 1.12 OpenHouse catalog:
  metrics reporter); failures logged at WARN, not thrown. Fewer INFO lines (catalog init logs once).

## Suites [ran] (base: 466 / 466 / 470 — 4.2 base from CI log of b2dcf8c0 [read], same apps tree)
- 1.12 after fix: 469 = 464 passed, 0 failed, 5 skipped. Reference: 469 = 381 passed, 0 failed, 88 skipped.
- 4.2 check after fix: 473 = 465 passed, 0 failed, 8 skipped.
- With X9 (classes only): FormatV3MaintenanceTest.retention* 10/10 on 1.12 and 4.2; reference 5 passed + 5 v3
  skipped (existing assumption), OperationsTest all passed on reference. Full suites with X9 left to CI.
- check-test-groups.py: partition holds (after merge, and before the final push).

## Heap (GC log via init script, jcmd GC.class_histogram every 3 min) [ran]
- After (b89131f2): pools 22, 278 (18 min), 528 (30 min), 682 at end; after full GC 302, 321, 315, 369 MB of 512;
  20 full GCs (~11 induced by jcmd). Pool creations ("Creating custom connection provider"): 745.
- Base (6c2e4088 = base + seam + pinning test, which adds ~301 pools): 1167 pools at end; full GCs back to back at
  486-494 MB of 512; 168 full GCs. Creations 1385 (1078 without OperationsCatalogTest).
- Margin ~140 MB. Remaining sources: Spark session catalogs per session/newSession, one per Operations instance.
  Test heap not raised.

## X9
- Operations.prepareBackupDataManifests -> reportDeleteFilesInBackup: counts on the backup's own plan: distinct
  position-delete files, distinct equality-delete files, deletion vectors (Puffin; one per data file).
- WARN "Retention backup of table T: N of M expired data files carry deletes (...)"; metric
  retention_backup_delete_file_count {table_name, delete_kind}; nothing when none, or when the emitter is null.
- Observed: v2 4 position-delete files, v3 4 deletion vectors, of 6 expired data files.
- Gap: no equality-delete fixture through retention with backup.

## Owner items
- Java client: give OpenHouseCatalog a way to dispose its pool (or share one); changes other users.
- Fixtures could reuse one Operations per format (FormatV3Tables.ops) to cut test pools further.
