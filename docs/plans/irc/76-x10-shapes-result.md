# X10 (rest) and X15: maintenance on branches, evolved specs and equality deletes: result

Bottom line: every maintenance job keeps what readers need on all three remaining table shapes (branches and tags,
evolved partition specs, equality deletes), on format v2 and v3, on both jobs lines. One job was wrong: partition stats
mislabelled the values of tables with more than one spec; fixed (X15). Pull request 137 merged into `irc-integration`
on 2026-11-24 as merge commit `461ae4378a059621db6bf84a88469fa1d2c0eb95` (pull request head `b2dcf8c0`). The first CI
run was red because the test process ran out of memory, from a leak in the job code that predates this work and
remains (N16). Marks: [ran] run, [read] read in code, [inferred] neither. Dated 2026-11-24.

## What OpenHouse allows for each shape [read, then pinned by tests that ran]

| Shape | Native API | Iceberg REST routes |
| --- | --- | --- |
| Branches and tags | Accepted (Spark SQL CREATE BRANCH and TAG, Java `manageSnapshots`). `history.expire.max-ref-age-ms` is held at 7 days or less. | Accepted (`IcebergRestCatalogWriteTest.branchesAndTags`). The snapshots update carries the client's refs; the server removes refs missing from it and sets the rest. |
| Spec change on an existing table | Refused (`checkPartitionSpecEvolution` compares partition field names). | Refused with the same message. |
| Several specs | Only from an Iceberg REST create transaction (pull request 134) or a replace, which keeps old specs. | Same. |
| Equality deletes | Accepted: no OpenHouse check refers to them. | Accepted. |

## Result of the jobs by shape [ran]

Jobs run: compaction with strict settings and with the scheduler's arguments, snapshot expiry with file deletion,
orphan removal single and batched, retention with backup on and off, table stats. Each ran on format v2 and v3, on
Spark 3.5 with the OpenHouse catalog and on Spark 4.2 with Iceberg's REST catalog over `/iceberg`. A pass means: rows
of main and of every branch and tag, deleted rows deleted on every ref, `_row_id` of surviving rows, time travel to
every retained snapshot and tag, and every file any reachable snapshot references.

| Shape | Result | Notes |
| --- | --- | --- |
| Branches and tags | Pass, every job, untouched | `retainLast` applies per branch; tagged snapshots are kept. |
| Equality deletes | Pass, every job, untouched | Strict compaction leaves all 6 equality delete files live in metadata, but none applies to a live data file: rewritten files carry the starting sequence number. After retention 2 of 6 remain live, those of kept partitions. |
| Evolved partition specs | 20 of 22 cases passed untouched on the first run; the failure was table stats, then fixed | Fixture: spec 0 is (day(ts), identity(category)), spec 1 is (day(ts), identity(region)). |

The one failure: `partitionStatsLabelEachValueWithItsOwnField`, on v2 and v3: a spec 0 category value was reported as
region and spec 1 region values were not reported.

## The stats mislabel (X15)

- Cause [ran, then read]: `TableStatsCollectorUtil.transformPartitionRowToColumnData` mapped a partition row's values by
  position to the names of the current spec. On a table whose current spec dropped a field of an earlier spec, a value of
  the dropped field was reported under another field's name and later values were lost.
- Fix (commit `68fbb3aa`): label each value with the partition row's own struct field names when the row has a schema;
  otherwise positional, as before. After it the label tests pass on v2 and v3 [ran].
- Single-spec output shown unchanged by two things [ran]: a unit test comparing the two labelling paths on a single-spec
  table, and a test on real v2 and v3 tables that the row's struct equals the spec. The bytes of a whole stats run
  before and after were not diffed. Tables made by a replace or an Iceberg REST create with history are the ones that
  change.

## The red first CI run and its root cause

- On head `97eb1f51` the default Spark 3.5 job was red: 381 tests, 62 failed, 6 skipped; the other 13 jobs were green
  [ran].
- Root cause [ran, measured]: the test process (Gradle's default 512 MB heap) hit `OutOfMemoryError` in
  `OperationsTest`, after the new classes; the Spark context died, every later Spark test failed, executor exit 52. The
  live heap after collection rose from 298 to 353 MB in four minutes. Class histograms showed the connection pools of the
  relocated reactor-netty client growing from 572 to 775 while about 5 API clients were live.
- Why: every `Operations.getTable` builds a new OpenHouse catalog; its client creates a connection pool that is never
  disposed, about a quarter of a megabyte each, so every one stays reachable. The new tests loaded tables through
  `Operations` hundreds of times per class.
- Fix (commit `9d6fdbb3`, tests only): the test helper `FormatV3Tables.table()` keeps one catalog per format version for
  the tests' own loads. The leak in the job code predates this work, also grows in long batched jobs, and is not fixed
  here: it is N16, now a defect in flight (`41-work-platforms-v3.md`).

## Heap margin after the fix [ran]

On the subset of the new classes, `OperationsTest` and the stats tests, with a collection log: pools 35 at the start of
the branches class, 670 entering `OperationsTest`, 785 at its end. On `b2dcf8c0` the full suite's heap after collection
was 232 MB, then 307 MB, then 373 MB of 512 MB at the end, and still rising: about 140 MB of margin. New jobs tests can
run the suite out of memory until N16 lands.

## Evidence (head `b2dcf8c0`, JDK 17; tests / failed / skipped) [ran]

| Line or jobs line | Result |
| --- | --- |
| 1.12 build line, Spark 3.5 jobs project | 466 / 0 / 5. New classes: branches 24, equality deletes 20, spec evolution 26; `TableStatsCollectorUtilTest` 35 (2 new). |
| Reference line (`-PicebergLinkedIn`) | 466 / 0 / 88; the v3 cases skip there. |
| Spark 4.2 jobs over REST, local, earlier head | Branches 24, equality deletes 20, spec evolution 26, stats test 35: all passed. |
| Spark 4.2 jobs project, full check | Run by CI on the pull request head, green; the local full run had not finished at the time of the record (51 passed, 0 failed so far). |
| CI on `b2dcf8c0` | 13 jobs passed, 1 skipped by design. The test-group partition check (`scripts/ci/check-test-groups.py`) holds. |

Post-merge CI for the merge of pull request 137 (and for 139) has not been read yet.

## Known gaps

- Not covered: a job run against a branch, a table built by a replace, HDFS and object stores.
- Partial-progress compaction raced by construction, the service-internal window between the base check and the
  compare-and-set, and expiry, retention and orphan removal races over the REST routes remain from
  `65-x10-concurrency-result.md`.
- The pull request was not merged with `e6c52c21` (pull request 139, nothing under `apps/`) before the merge; the merge
  was clean and the post-merge run covers the combination.
- The jobs test suite ends at about 373 of 512 MB until N16 lands.
