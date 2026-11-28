# V11b, V11c: server-side planning under Spark writes, and a bound on one plan: record

Bottom line: done. Draft pull request 131, head ecf7c5f9c7225794ade7820cc300c870f2130910, CI green (both checks).
V11b: with server planning, Spark 4.2 writes store the same tables and reads return the same rows, with two
differences that are not in OpenHouse's route: on format v3, scan tasks carry no sequence numbers, and a filter on a
column added after the current snapshot gets a 400. Both are pinned by tests. V11c: one plan holds at most 10,000 file
scan tasks (cluster property). A scan that plans more is answered `failed`, and the client fails visibly.

Worktree /home/user/oh-v11b, branch irc/v11b-v11c-planning-followups, base origin/irc-integration adc28470 (still
the tip at the last fetch, so no merge was needed).

## Commits
- 1c756ef8 V11c test: cost measurement (IrcScanPlanCostTest), run before the bound.
- 961d1d13 V11b test: parity tests (SparkIrcServerPlanningWritesTest, ...ReadsTest), ServerPlanning switch, Gradle
  tasks testServerPlanning and smokeTestServerPlanning (both in check); no-plan-request checks assert the converse there.
- 8ae6b42e V11b test: pins the 400 for a filter on a column added after the current snapshot.
- ecf7c5f9 V11c: the bound (IcebergRestScanPlanner, IcebergRestScanController), IrcScanPlanningBoundTest,
  SparkIrcPlanBoundTest (its own service with bound 4), IrcReadTestServiceProcess.startOwn.

## Client behavior by answer [read, fork origin/openhouse-1.12.0, core/.../rest/]
- RESTTableScan.planTableScan: `completed` returns the tasks. `submitted` polls GET plan/{id}. `failed` throws
  IllegalStateException("Remote scan planning failed for planId: null: <type> (code=..): <message>").
- 4xx and 5xx go through tableErrorHandler: 400 BadRequestException("Malformed request: .."), 500
  ServiceFailureException, 503 ServiceUnavailableException.
- No answer makes the client fall back to client planning. Retries: 429 always; 503 only for idempotent requests, with
  an Idempotency-Key, or with Retry-After. A plan POST has none of these, so it is not retried.
- A `failed` answer may not carry a plan id (PlanTableScanResponse.validate).

## V11c measurement [ran] (local fixture: H2, local FS; test JVM heap 512 MB, the Gradle default; 4 cpus; 3-column
synthetic files with stats; one manifest per 1,000 files; median of 3 runs after a warm-up)
| files | request | before: ms / answer / heap high-water writing | after |
|---|---|---|---|
| 1,000 | {} | 52 ms / 277 KB / 2.4 MB | same (completed) |
| 1,000 | stats | 54 ms / 606 KB / 7.8 MB | same |
| 10,000 | {} | 126 ms / 2.7 MB / 27 MB (serving thread 24 MB alloc) | same |
| 10,000 | stats | 360 ms / 6.0 MB / 98 MB (82 MB alloc) | same |
| 20,000 | {} | 210 ms / 5.5 MB / 82 MB | failed, 55 ms, serving thread 0.7 MB, all threads 18 MB |
| 20,000 | stats | 1,135 ms / 12 MB / 192 MB | failed, 80 ms, all threads 46 MB |
| 50,000 | {} | 669 ms / 13.7 MB / 171 MB | failed, 52 ms, all threads 19 MB |
| 50,000 | stats | OutOfMemoryError in the 512 MB JVM | failed, 89 ms, all threads 48 MB |
Default 10,000: at the bound one plan peaks around 28 MB (about 100 MB with stats on every column). The sandbox
recipes run the tables service with -Xmx1024M.
Not shown: real storage (S3, ADLS), concurrent plans, wide schemas, and the heap peak during planning itself (only
during writing is measured). The bound limits what a plan holds, not the manifest-read time of a heavily pruned scan.

## V11b results [ran on ecf7c5f9]
- Writes, v2 and v3, each scenario run twice from scratch (client catalog vs server catalog) and compared by what was
  stored: DELETE, UPDATE and MERGE in CoW and MoR, two MoR deletes on the same files, dynamic INSERT OVERWRITE reading
  its own table, CTAS and RTAS. Rows, files, snapshot summaries and row ids are the same.
- Difference 1, not OpenHouse's: the spec's DataFile in a scan task has no sequence number, and ContentFileParser reads
  none. So on v3 a server-planned read gets a null inherited _last_updated_sequence_number, and a CoW rewrite stores the
  rewrite's sequence number for the rows it keeps. Pinned by two tests in SparkIrcServerPlanningWritesTest.
  MoR, IOW and CTAS/RTAS are not affected.
- Difference 2, not OpenHouse's: a filter on a column added after the current snapshot answers 400 "Cannot find field".
  Spark 4.2 pins main reads with useSnapshot, so RESTTableScan sends use-snapshot-schema=true with names from the
  current schema; the route, like CatalogHandlers, plans with the snapshot schema. It goes away only after a new
  snapshot is written AND the cached table is refreshed. Pinned by SparkIrcServerPlanningReadsTest and
  SparkIrcNativeDefaultsTest (server-planning run).
- Benign differences: DV offsets inside a Puffin file and the row ids given to new rows (CTAS, IOW) follow task order.
  The comparison leaves them out.
- Reads that are the same both ways: joins, aggregates with pushed filters, time travel by id and by timestamp,
  branch and tag, metadata tables (client-planned), incremental reads (client-planned), 2,500 files, LIMIT over
  partly deleted files.
- Suite reruns: `test` 109/109 and `testServerPlanning` 109/109. Before the pin, 105 tests in both runs, of which
  101 gave the same outcome and 4 failed; all 4 were one statement (WHERE c_late = 9), difference 2.
  `smokeTest` 65/65 and `smokeTestServerPlanning` 65/65. Smoke outcome file: 193 of 193 statements have the same
  outcome; 188 are byte-identical once ids, timestamps and paths are normalized, and the other 5 differ only in UUIDs,
  a random cron minute and the order of a tag set.
- Plan requests: default test JVM 200 of 3,708, all in irc42_plan; server-planning JVM 575 of 4,094 (358 from other
  tests); smoke 0 of 1,271 by default, 40 of 1,311 with server planning; never GET/DELETE plan or POST tasks.

## Counts [ran, ecf7c5f9, JDK 17]
- :services:tables:test 1.12: 1701 tests, 0 failed, 1 skipped. 1.5.2 (-PicebergLinkedIn): 1679, 0 failed, 39
  skipped (the same as pull request 127: the new tests exist only on 1.12).
- restCompatKitTest: 160 = 41 passed, 96 failed, 23 skipped; expected-outcomes.txt untouched; the task passed.
- Spark 4.2: test 109, testServerPlanning 109, extensionsTest 24, smokeTest 65, smokeTestServerPlanning 65, all passed.
- spotlessCheck (whole build) passed.
- CI on ecf7c5f9: Build and Run Tests success (it ran testServerPlanning and smokeTestServerPlanning); Reference
  build on 1.5.2 success.

## Decisions (two-way doors)
1. Cap, not pagination. The client falls back on no answer, so a cap fails visibly. Pages would need package-private
   ManifestGroup and DeleteFileIndex (or our own delete matching) plus an unsigned token. Tests:
   IrcScanPlanningBoundTest, SparkIrcPlanBoundTest.
2. Over the cap, answer `failed` (200; error 422, type ScanPlanTooLargeException; no plan id, no tasks) rather than a
   400 ("Malformed request" misleads) or a 5xx (looks like an outage). Test: aboveTheBoundThePlanIsAnsweredFailedWithNoTask.
3. Stop planning as soon as the bound is passed; the bound counts tasks after pruning. Tests:
   aFilterThatPrunesToTheBoundPlans, theDefaultBoundIsTenThousandFileScanTasks.
4. Default 10,000; a value that is not positive means the default. Test: theDefaultBoundIsTenThousandFileScanTasks.
5. The property is read in the 1.12-only controller, not ClusterProperties: it means nothing on 1.5.2, and that file
   is X5's area. A refusal is logged at WARN (the answer is a 200, so the failure audit does not see it).
6. Server planning is selected for whole suites through their own Gradle tasks, not a parameter: catalog properties
   are fixed per Spark session, each run needs its own service log, and the default tasks must stay exactly what a
   default client does.
7. Writes are compared by what they stored, read through the client catalog. Lineage of new rows is compared as a set.
8. Difference 2 is pinned, not fixed: fixing it changes what a served route answers (put to the orchestrator).

## Put to the orchestrator (not acted on)
- Whether the plan route should resolve select and filter names against the table schema when use-snapshot-schema=true
  and the snapshot schema cannot bind them (it would turn difference 2's 400 into the client's rows). This is a
  behavior change of the plan route.
- For V11d: server planning should not be offered to writers of format v3 tables that rely on row lineage
  (_last_updated_sequence_number) while copy-on-write is used, until the spec carries sequence numbers.

## Not verified
- Spark 3.5 with server planning; extensionsTest rerun with server planning; S3 or ADLS storage; concurrent plans;
  the heap peak while planning (as opposed to writing); a real multi-instance deployment.
