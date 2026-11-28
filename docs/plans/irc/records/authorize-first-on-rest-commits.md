# Authorize first on the Iceberg REST commit routes: record

## Bottom line (kept current)
Done. Draft pull request #152 (base `irc-integration`), head `478edea6dc7ebd62c9b1c21d55a1d9f5ecac1a04`, every CI job green.
The table commit, one-table transaction and view commit routes now make the native privilege decision before anything
stored is checked or answered, an empty commit needs the load's privileges, and the IRC view load needs GET_TABLE_METADATA
on the view. Full route table, behavior changes and what is left: in the pull request body (copy in scratchpad/authz-first/pr-body.md).

## Steps done
1. Read both review reports (commit-write D-1, D-2; views-capabilities D1, D3) and the code of every `/iceberg` route.
2. Orchestrator addition received mid-task: the IRC view load now requires `GET_TABLE_METADATA` on the view's
   authorization reference, before the metadata file is read; view HEAD follows the table HEAD; view list unchanged.

## Design (decided before tests)
- Table commit (non-create) and one-table transaction: after `loadBase` (the same stored-table load the native PUT makes
  before its own check, needed to build the same authorization resource), and before requirements, updates, validation:
  - no updates: the privilege decisions of a table load (LOCK_ADMIN when legacy-locked, then GET_TABLE_METADATA);
  - updates: the write-path privilege (UPDATE_TABLE_METADATA, SYSTEM_ADMIN on a replica), as the core asks it.
  A refusal is audited as a FAILED table event (READ for a read; UPDATE / COMMIT / REPLACE_COMMIT for a write).
  The core's own checks still run afterwards, unchanged, so an authorized caller sees the same answers.
- Staged-create completion on an existing table: the assert-create requirement is checked first, so the answer is the
  fixed "already exists" text instead of a requirement message carrying stored ids.
- View commit: gate, row lookup (404s), then the replace privilege (UPDATE_TABLE_METADATA on the view's reference),
  audited as a FAILED REPLACE on refusal, before the metadata read and the requirement check. Empty view commit also
  needs GET_TABLE_METADATA.
- View load: gate, row lookup (404), GET_TABLE_METADATA on the reference, then the metadata read.

3. Commit `59b3c3c5`: tests first. Ran on the default line against the old code: 27 tests, 11 failed (all for the
   reason intended: 200 with metadata, 409 with the stored UUID, 400 from an update, 200 view load).
4. Commit `a51eda90`: the fix. Ran the four auth classes on the default line: 34 tests, 0 failed.
5. Ran on the default line (fork Iceberg 1.12), after the fix: `:services:tables:test` filtered to `*.Irc*`, `*Views*`,
   `*ViewAudit*`, `*TableCommitService*`, `*CommitPath*`, `TablesApiHandlerAuditTest`, `TablesServiceTest`,
   `ViewsApiHandlerAuditTest`: 81 classes, 953 tests, 0 failed, 0 skipped.

## Native ordering found (read, not run, except where a test runs it)
- Native table GET (`TablesServiceImpl.loadTable`): House Tables + metadata read, 404 if missing, then LOCK_ADMIN (legacy
  lock only), GET_TABLE_METADATA, SYSTEM_ONLY lock access, capability gate. So natively a missing table is 404 for
  anyone and a present one is 403 for a denied caller: existence is told before authorization. Matched on every route here.
- Native table PUT update (`putTable` -> `TableCommitCore`): request validator (judges the request only), findById
  (metadata read), lock-flip check (400), legacy lock (400), UPDATE_TABLE_METADATA (SYSTEM_ADMIN on replicas), SYSTEM_ONLY.
  The stored table is read from storage before the decision natively too: the decision is made on the full TableDto.
- Native view GET: gate, row, metadata read, pointer answered, no decision. Native view PUT: validator, gate, database
  exists, occupant, decision (UPDATE_TABLE_METADATA on the view's reference for a replace), then name, base version.
- Table HEAD on `/iceberg`: house-table lookup, no decision. So view HEAD stays as it is: no decision (it goes through the
  native GET, which reads the metadata file and answers 204 only).
6. Ran on `-PicebergLinkedIn` (1.5.2) the same filter: 72 classes, 893 tests, 0 failed, 15 skipped (IrcFormatV3RoutesTest 8,
   IrcPlanCredentialsAuthTest 3, IrcPlanCredentialsRoutesTest 3, IrcRestCatalogCreateHistoryV3Test 1: line skips).
7. `origin/irc-integration` has no commits beyond `81b4cd4d`: no merge needed. `check-test-groups.py`: partition holds on both lines.
8. Draft pull request #152 opened, head `a51eda902c0dbc21b5946310b41ec8adaa015898`. Body = scratchpad/pr-body.md (route table,
   behavior changes, pins, left). Waiting for CI.
9. CI on `a51eda90`: `Reference build, spark-3.5-openhouse-spark-itest` failed: `IcebergRestCatalogViewTest.aDeniedPrincipalIsForbiddenToWriteAndMayRead`
   pinned the old unauthorized view read (stranger loads the view). A pin I had not found (I had searched only services/tables).
   Changed it (commit `478edea6`, renamed `aDeniedPrincipalIsForbiddenToReadAndWrite`); ran the class on both lines: 5/5 pass.
   Old assertion: `assertEquals(metadata(rest.loadView(g)).metadataFileLocation(), metadata(stranger.loadView(g)).metadataFileLocation())`
   and a stranger replace through `stranger.loadView(g).updateProperties()...` expected 403. New: `stranger.loadView(g)` throws,
   with a `ForbiddenException` naming irc_stranger in its cause chain (1.5 wraps it in NoSuchViewException), no SQL in the
   message; the name is still listed. A `viewExists` assertion was dropped: on the 1.5 client it is a load, so it says false.
10. Pushed `478edea6dc7ebd62c9b1c21d55a1d9f5ecac1a04`; pull request #152 body updated (pin added). Note: the shared scratchpad
    file `pr-body.md` was overwritten by another agent; my body was recovered from GitHub into scratchpad/authz-first/.
    CI on `a51eda90` (superseded): all finished jobs green except both spark-3.5-openhouse-spark-itest jobs (the pin above);
    apps-spark-3.5 / apps-spark-4.2 (default) were still running when the head moved. Polling CI on `478edea6`.
11. CI on `478edea6`: all 14 jobs success (Default and Reference: test groups, rest, apps-spark-3.5, apps-spark-4.2,
    spark-3.5-openhouse-spark-itest, spark-4.2-openhouse-spark-itest; Default Docker images and integration tests). The two
    top-level workflow entries and the iceberg-linkedin Docker job are "skipped" by design.

## Review round (pull request 152 review: MERGE AFTER FIXES)
12. Read scratchpad/reports/pr-review-152.md. Base unchanged (no merge needed).
13. Commit `1dd4e003` (tests, red): on the old code, default line, 53 tests in IrcWriteRoutesAuthTest, IrcViewRoutesAuthTest,
    IrcReadRoutesTest: 5 failed, each for the reviewer's defect (no-change commits answer metadata past the load checks;
    no READ audit on an allowed one; 423 and 422 skipped; view no-change commit answers a writer the definition).
    The new pins that passed on the old code: Iceberg-recorded "changes" are writes, replica SYSTEM_ADMIN first, F3 known defect.
14. Commit `51194711` (fix): no-change commit (no recorded change and no patch) = load: TablesService.checkLoad (exposed,
    default runs getTable) + READ audit; empty commit uses checkLoad too; TableCommitService.authorizeRead removed; view
    no-change commit needs read; view read decision is one method. Auth classes after fix: 65 tests, 0 failed (default line).
15. Broad runs after the fix: default 81 classes / 961 tests / 0 failed; 1.5.2 72 / 901 / 0 failed / 15 skipped.
    Spark 3.5 view+read catalogTest: 1.5.2 run showed createOrReplace by the writer is 403 (not 409 as the reviewer read);
    commit `cd3c0dfd` pins 403 on both lines and viewExists (true 1.12, false 1.5). 19/19 on both lines.
16. Base moved (#154, 5 commits, no file of this branch); merged under the lock: `33797be0`. Reran the two auth classes
    (29 tests) and Spark view/read (19) on the default line: 0 failed; check-test-groups holds on both lines. Pushed.
    Pull request body replaced (behavior changes 1-6, route table rows for no-change commits, pins, left with review ids).
    Polling CI on `33797be07dbbd3f636c4c2c88cc9681eb7a9c7d9`.
