# V11b and V11c: server-side planning under Spark writes, and a bound on one plan

Bottom line: done and merged. Pull request 131 (merge commit `1dd485d37140f91a0bcbe469b933762085f968a4`, pull request
head `ecf7c5f9`) was merged into `irc-integration` on 2026-11-24. With server planning, Spark 4.2 writes store the same
tables and reads return the same rows as with client planning, with two differences that are not in OpenHouse's route and
are pinned by tests. One plan holds at most 10,000 file scan tasks (a cluster property); a larger scan is answered
`failed` and the client fails visibly. Post-merge CI for 131 has not been read yet. Dated 2026-11-24.

Marks: [ran] was run, [read] was read in code, [inferred] is reasoning. Sources: the agent's record
`v11b-v11c-planning-followups.md` in the reports directory and the merge message. Decision records: D44
(`56-decisions-continued-5.md`), D49 (`67-decisions-continued-6.md`).

## Writes and reads: parity [ran on ecf7c5f9]

- Writes on v2 and v3, each scenario run twice from scratch (client catalog and server catalog) and compared by what was
  stored: DELETE, UPDATE and MERGE in copy-on-write and merge-on-read, two merge-on-read deletes on the same files,
  dynamic INSERT OVERWRITE reading its own table, CTAS and RTAS. Rows, files, snapshot summaries and row ids are the same.
- Reads that are the same both ways: joins, aggregates with pushed filters, time travel by snapshot id and timestamp,
  branch and tag reads, a 2,500-file table, LIMIT over partly deleted files. Metadata tables and incremental reads plan on
  the client either way (pinned).
- Benign differences left out of the comparison: deletion vector offsets inside a Puffin file, and the row ids given to
  new rows (CTAS, INSERT OVERWRITE), which follow task order.
- The whole Spark 4.2 test and smoke suites run a second time with server planning (tasks `testServerPlanning` and
  `smokeTestServerPlanning`, part of `check`). Smoke: 193 of 193 statements have the same outcome; 188 are byte-identical
  once ids, timestamps and paths are normalized, and 5 differ only in UUIDs, a random cron minute and a tag set's order.
- Plan requests: the default test JVM sent 200 of 3,708 requests, all from `irc42_plan`; the server-planning JVM 575 of
  4,094; smoke 0 of 1,271 by default and 40 of 1,311 with server planning. Never GET or DELETE plan, never POST tasks.

## Two pinned differences, neither in OpenHouse's route

1. Format v3 sequence numbers [read in code, ran]. A scan task's data file has no sequence number in the specification,
   and Iceberg's `ContentFileParser` reads none. A server-planned read on v3 gets a null inherited
   `_last_updated_sequence_number`, and a copy-on-write rewrite planned on the server stores the rewrite's sequence number
   for the rows it keeps, so stored row lineage differs from client planning. Merge-on-read, INSERT OVERWRITE and
   CTAS/RTAS are not affected. Pinned by two tests in `SparkIrcServerPlanningWritesTest`. D49 records the decision to
   refuse v3 tables on the route (work item V11e).
2. A filter on a column added after the current snapshot [ran]. It answers 400 "Cannot find field". Spark 4.2 pins reads
   with `useSnapshot`, so the client sends `use-snapshot-schema=true` with names from the current schema, and the route,
   like Iceberg's `CatalogHandlers`, plans with the snapshot schema. It goes away only after a new snapshot is written and
   the cached table is refreshed. It is a visible failure, never fewer rows. Pinned by `SparkIrcServerPlanningReadsTest`
   and `SparkIrcNativeDefaultsTest`. Before the pin, 4 of 105 tests gave a different outcome, all one statement. Whether
   the route can be made to equal client planning is delegated to V11e.

## The bound

- One plan holds at most `cluster.tables.irc.scan-planning.max-file-scan-tasks` file scan tasks, default 10,000, counted
  after pruning. A value that is not positive means the default. Planning stops as soon as the bound is passed.
- What a client sees above it: HTTP 200 with status `failed`, error code 422, type `ScanPlanTooLargeException`, naming the
  bound and the property, with no plan id and no tasks. The 1.12 client has no fallback to client planning: it throws
  `IllegalStateException("Remote scan planning failed for planId: null: ...")` and does not retry, because a plan POST
  carries no idempotency key [read]. A write that hits the bound leaves the table unchanged. A refusal is logged at WARN.
- A cap, not pagination: the client falls back on no answer, so a cap fails visibly, and pages would need package-private
  Iceberg planning internals and a token that cannot be signed without a secret [inferred; not prototyped].
- The property is read in the 1.12-only controller, not in `ClusterProperties`: it means nothing on the 1.5.2 line.
- The default rests on this measurement [ran]. Fixture: H2, local file system, 512 MB test heap, 4 cpus, 3-column
  synthetic files with stats, one manifest per 1,000 files, median of 3 runs after a warm-up.

| Files | Request | Before the bound: time / answer / heap high-water writing | After the bound |
| --- | --- | --- | --- |
| 1,000 | {} | 52 ms / 277 KB / 2.4 MB | same (completed) |
| 1,000 | stats | 54 ms / 606 KB / 7.8 MB | same |
| 10,000 | {} | 126 ms / 2.7 MB / 27 MB | same |
| 10,000 | stats | 360 ms / 6.0 MB / 98 MB | same |
| 20,000 | {} | 210 ms / 5.5 MB / 82 MB | failed, 55 ms, serving thread 0.7 MB |
| 20,000 | stats | 1,135 ms / 12 MB / 192 MB | failed, 80 ms, all threads 46 MB |
| 50,000 | {} | 669 ms / 13.7 MB / 171 MB | failed, 52 ms, all threads 19 MB |
| 50,000 | stats | OutOfMemoryError in the 512 MB JVM | failed, 89 ms, all threads 48 MB |

At the default one plan peaks near 28 MB, about 100 MB with stats on every column. The sandbox recipes run the tables
service with `-Xmx1024M`.

## Evidence on the pull request head [ran, ecf7c5f9, JDK 17]

- 1.12 line: `:services:tables:test` 1701 tests, 0 failed, 1 skipped. 1.5.2 line (`-PicebergLinkedIn`): 1679, 0 failed,
  39 skipped (the same as pull request 127: the new tests exist only on 1.12).
- Compatibility kit: 160 = 41 passed, 96 failed, 23 skipped; `expected-outcomes.txt` untouched; the task passed.
- Spark 4.2: `test` 109, `testServerPlanning` 109, `extensionsTest` 24, `smokeTest` 65, `smokeTestServerPlanning` 65, all
  passed. `spotlessCheck` on the whole build passed.
- CI on the pull request head: Build and Run Tests passed (it ran both new tasks); Reference build on 1.5.2 passed.
- No file under the service's shared `src/main` changed: only the 1.12-only plan controller and planner, tests, and one
  Spark itest build file. The service keeps no state, never sends `scan-planning-mode`, and the endpoint list is unchanged.

## Known gaps

- Local storage only; no S3 or ADLS. One plan at a time: no concurrent plans, no multi-instance deployment.
- The heap peak while planning (only the peak while writing the answer is measured) and the manifest-read time of a heavily
  pruned scan are not bounded or measured. Wide schemas were not measured.
- Spark 3.5 with server planning, and `extensionsTest` with server planning, were not run.
- Post-merge CI on `irc-integration` at `1dd485d3` has not been read yet.
