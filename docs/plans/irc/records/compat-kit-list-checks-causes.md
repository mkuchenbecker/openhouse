# Record: compat-kit list checks causes (FF-25, RF-061, BC-043)

Bottom line: done. Draft pull request 167, head 429156356f288d928f208a0c14257dfa7e3a496f, every CI job green (kit: default 52/92/16, v3 54/90/16, all causes as listed).

## Steps
1. Read brief, FF-25, RF-061, BC-043, review record D3 / 2f. Confirmed against today's code:
   - build.gradle ratchet compares only name -> FAILED|SKIPPED; class letter parsed, not compared; total only logged; passing set not listed.
   - RestCompatKitService hard-codes --cluster.iceberg.format-v3.enabled=true (BC-043: not the default).
   - testLoadTableWithMissingMetadataFile filed e (NOTFOUND) though reason says server answers 500.
   - SPEC reason "partition evolution and bucket specs are refused" (bucket create passes in testCompleteCreateTable).
   - CI: kit runs in `rest` job via `check` (split-build.yml, build-run-tests.yml), results excluded from failed-test printer by task name.
2. Design: JUnit TestExecutionListener in the test JVM writes every test's outcome, throwable type and message, skip reason; ratchet reads it.
3. Wrote RestCompatKitOutcomeListener (JUnit TestExecutionListener, JSON lines), service properties passed per configuration
   (RestCompatKitService no longer hard-codes v3), two tasks restCompatKitTest (default) / restCompatKitFormatV3Test (v3).
4. Ran both (ran, not inferred): default 160 = 52 passed / 92 failed / 16 skipped; v3 160 = 54 / 90 / 16 (matches today's
   published numbers, which were measured with v3 on). Only differences: createTableTransaction(int)[3] and
   testCreateTableWithDefaultColumnValue fail at default (format-version 3 refused 400), pass with v3.
   Raw outcomes: scratchpad/runs/{default,v3}.jsonl.
5. Review of every failure vs stated reason (generator scratchpad/gen/gen.py, write.py): see final report table.
   Wrong/refined: testLoadTableWithMissingMetadataFile (e -> d MISSINGMETA, 500 ServiceFailureException);
   createViewErrorCases (DIALECT -> e VIEWERRTYPE); SPEC wording; SCHEMA (all 4 are drops, none rename); VRENAME (client refuses,
   endpoint not advertised: no 406 sent); testLoadNamespaceMetadata (reviewer doubt) passes today.
6. Docs section rewritten (docs/development/iceberg-fork.md), CI print-failed excludes the new task.
7. Mutation run pending (6 deliberate list errors must each be reported).
8. Mutation run: 6 deliberate errors, 6 reported. Clean check (both tasks) green. Commit 67daf3e8 pushed; draft pull request 167.
   Head 67daf3e80e916c54aab17ba8a862a469a0571a50. Waiting for CI.
9. Review round (coordinator): merged base 315a6e92 (88c79b83, clean); e1a04fef VIEWDB -> d, real mechanism, LISTNS/KITNS root,
   DIALECT/DIALECTRES/VIEWERRTYPE behind it; ad561bc4 minors (stale comment, AssertionFailedError, counts removed; SpotBugs 2 are
   in ServiceWarehouse.delete, unchanged here, not new); 42915635 docs grouping. Kit both configs green locally after merge;
   partition holds on both lines; guards pass. Pushed head 429156356f288d928f208a0c14257dfa7e3a496f. Waiting for CI.
