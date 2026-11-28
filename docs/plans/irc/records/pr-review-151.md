# Review of pull request 151 (irc/x2-x3-v3-row-level, head 47d20539)

Reviewer: independent sub-agent, read only. Base for the diff: merge base 81b4cd4d (origin/irc-integration).
11 files, all test sources (3.5 itest src/shared/smoke + src/test; 4.2 itest src/service, src/smoke, src/test).

## 1. Verdict

MERGE AFTER FIXES: close F1 first.

The three things that matter most:
1. F1: on the Iceberg 1.12 line, nothing in the new list asserts that a merge-on-read statement writes any delete file at
   all. Every v3 deletion-vector check (one per data file, cardinality, replaced not re-added, added-dvs/removed-dvs)
   derives its expectation from which rows the scan says were deleted from which data file; if a MOR table were
   silently written copy-on-write (for example a catalog path dropping the `write.*.mode` table properties, whose
   default is copy-on-write), every one of those checks still passes. The headline claim is unfalsifiable against
   that regression on every path.
2. The expected row lineage is mostly sound (the model knows which ids are inserted, updated or untouched; untouched
   and updated rows are compared with the previous statement's read-back; sequence numbers come from the snapshot;
   fresh ids are bounded by next-row-id), but two facets are metadata checked against metadata or copied from the
   actual value (F2, F3).
3. The rest checks out: the `BaselineChecks` extraction is byte-identical; the Avro class clash and the 1.5.2
   position-delete reasons are true and neither hides a product problem; the 1.5.2 skip is exactly the 10 v3 tests;
   the second 4.2 smoke service is isolated and the switch-off service is started with the same arguments as before.

## 2. Findings

### F1. MUST FIX BEFORE MERGE: merge-on-read is never asserted to produce deletes on the 1.12 line

File: `integrations/spark/spark-3.5/openhouse-spark-itest/src/shared/smoke/java/com/linkedin/openhouse/spark/smoke/SparkSqlRowLevelStatements.java`,
`step()`, lines 596-681.

The expected per-file deletes are computed from the observed table:

```java
long deleted = state.recordCount - state.liveIds.size();
...
expectedDeletes.add(describe(state, deleted > 0 ? 1 : 0, deleteFormat, deleted, count));
```

and the DV counts likewise (`if (deletedNow > deletedBefore) { added++; ... }`). The only mode-specific assertion is the
copy-on-write direction (line 654-656: `if (!model.shape.mergeOnRead()) check(... "deletion vectors live", 0L, ...)`).
For a MOR shape run as copy-on-write: every data file has `recordCount == liveIds.size()`, so expected = "no deletes"
= actual; `dataFilesWithSeveralVectors` and `danglingVectors` are empty; `added` = `replaced` = 0 = the summary's absent
`added-dvs`/`removed-dvs`; lineage is kept (copy-on-write materializes it). For v2 on 1.12,
`expectedPositionDeleteFiles.add(filesWithDeletes)` is 0 and the live count is 0. Same in
`aTableUpgradedWithPositionDeletesGetsOneDeletionVectorOnItsNextStatement`: after the upgrade only "position delete
files live == 0" is checked, which a rewrite also satisfies. Only the 1.5.2 declarations (literal lists
`0,1,2,3,4,5,5`) pin MOR for v2, and on 1.5.2 there is no v3. `FormatV3SparkTest`/`SparkIrcFormatV3Test` pin a Puffin
file for one MOR table each, not per path, shape and statement.

Who is hurt: the claim "DELETE, UPDATE and MERGE keep one deletion vector per data file on every Spark path" would
stay green if a path stopped honouring MOR. Certain from reading.

Smallest fix: in `step()`, for MOR shapes check, independent of the table, that the rows removed from existing data
files equal what the statement deletes or rewrites:
`if (model.shape.mergeOnRead()) check(at + "rows the statement removed from existing data files", change.deleted.size() + change.updated.size(), deletedByStatement);`
(note `deletedByStatement` must then also exclude data files added by this statement, which it already does, as new
files have no deletes), and check the loaded table's `write.delete.mode`/`write.update.mode`/`write.merge.mode` equal
`shape.mode` after CREATE. On v3 MOR additionally `after.deletionVectors == filesWithDeletes` and `> 0` after the DELETE.

### F2. SHOULD FIX: lineage of upgraded rows accepts a null row id and any sequence number

Same file, `lineageChecks`, lines 699-714:

```java
expected.put(id, "assigned row id/" + seqOf(now.get(id)));   // sequence number copied from the actual value
...
boolean assigned = rowId < after.nextRowId;                   // number("null") is -1, so null passes
```

The sequence number of an upgraded row is taken from the table under test, and `number()` maps a null `_row_id` to -1,
which is `< nextRowId`. A null id is caught only indirectly when two live rows share it ("live rows sharing a _row_id");
one wrong id below next-row-id, or a wrong inherited sequence number, passes. The record's claim "old rows get row ids
from that commit" is not pinned. Fix: require `rowId >= 0` (and `>= before.nextRowId` if the claim is "from that
commit"), and expect the sequence number of the data file the rows came from (the v2 INSERT's, known from the
`INSERT on v2` step) instead of copying it. Can wait: the upgrade path is one test and the sharing check covers the
gross case. Certain from reading.

### F3. SHOULD FIX: next-row-id growth is metadata against metadata

Line 726-729: `check(at + "next-row-id grew by the snapshot's added-rows", before.nextRowId + after.addedRows, after.nextRowId)`.
Both sides come from table metadata; `addedRows` is never compared with the rows the snapshot actually added (the sum
of `recordCount` of the data files new in `after.dataFiles`, which the test already has). The "fresh row id" range
`[before.nextRowId, after.nextRowId)` inherits the same dependence. A consistent over- or under-allocation passes
unless it causes a live-id collision. Fix: also check `after.addedRows` equals the record count of the data files the
statement added. Can wait: the distinct-id check and the per-row comparison catch the harmful cases. Certain from
reading.

### F4. MINOR: smokeTestServerPlanning reruns the whole list without server planning

`SparkIrcRowLevelStatementsTest.sparkSession()` (4.2 smoke) prints "Server planning not applied" and runs the same 17
tests on client planning again in `smokeTestServerPlanning` (build.gradle line 215ff runs every smoke class). It tests
nothing the `smokeTest` run did not, and costs a second v3 service JVM and the run time. Exclude the class from that
task, or skip it there with an assumption. The smoke `ServerPlanning` has no `notApplied` like the test JVM's; the
println is the substitute.

### F5. MINOR: private ids and history in comments and messages

`SparkSqlRowLevelStatements.java` line 35-36 ("work items X2 and X3"), line 75 (`NO_V3_ON_152` skip message, shown in
test reports: "(X1)"); `BaselineChecks.java` line 35 ("Moved out of SparkSqlWriteStatements unchanged (work items X2
and X3)", also history narration); `RowLevelStatementsTest.java` Javadoc ("work items X2 and X3");
`SparkIrcRowLevelStatementsTest.java` Javadoc ("work item V11e") and the println "... refuses them (V11e)". The branch
uses this style elsewhere, but the brief lists it as cruft.

### F6. MINOR: class Javadoc misstates the v2 baseline

`SparkSqlRowLevelStatements.java` line 46: "on v2: position delete files, one more per statement". The baseline the
code checks on 1.12 is one file-scoped file per data file, replaced (lines 590-595, 299-306); "one more per statement"
is the 1.5.2 declaration and the Avro partition-scoped case. Fix the sentence.

### F7. MINOR: the v3 assumption is not cross-checked against the build's line

`requireFormatV3()` probes `TableMetadata.class.getMethod("nextRowId")`. On Spark 3.5 a wrong skip on the 1.12 line
cannot pass silently: `beginTest` records the test as run before the assumption, so the declared v3 Avro differences
would be unconsulted and `endSuite` fails; and `RestHttpPathLineTest` pins `IcebergLine` to `smoke.iceberg.build`. On
Spark 4.2 there is no such guard (only the 1.12 line exists there, so the probe cannot be false in practice). Cheaper
and explicit: `Assumptions.assumeTrue(IcebergLine.current() == V3)` and `assertEquals(formatV3Available(), line == V3)`.

## 3. Behavior changes

| Change | Production path affected | Direction |
| --- | --- | --- |
| `BaselineChecks` extracted from `SparkSqlWriteStatements` | none (test source set) | n/a |
| `IrcSmokeTestService` accepts extra Spring arguments after the work directory | none (4.2 itest `service` source set, a test service JVM) | n/a |
| `SmokeTestService.getWithFormatV3()` second service | none (smoke source set) | n/a |
| New test classes and one new test method | none | n/a |

No file under any `src/main` is touched. Agrees with the author's "none".

## 4. Tests

- `SparkSqlRowLevelStatements` (shared list), per check kind:
  - Rows: expected from an independent model (`Change` applied to `model.rows`), checked through the path under test,
    Iceberg's generic reader and (3.5) the two other paths with no cache. Can fail. Sound.
  - One live vector per data file: live delete-manifest entries (`ManifestReader.iterator()` is `liveEntries()`, checked
    in the fork), grouped by `referencedDataFile`, must not have two; also no dangling vector. Spec-derived, can fail.
  - Vector cardinality = rows deleted from that file: actual is the DV's `recordCount`; expected is `recordCount` of
    the data file minus the live ids Spark attributes to it via `_file`. Two independent sources (metadata vs the
    reader's application of the bitmap), plus the rows check. Can fail. But see F1: nothing requires the count to be
    non-zero on MOR.
  - No position delete file on v3: live non-Puffin position deletes must be 0. Can fail.
  - added-dvs/removed-dvs: expectation derived from the before/after deleted counts per file; can fail for a spurious
    re-add or a missing replacement. Vacuous under F1.
  - Row lineage: untouched rows compared with the previous statement's read-back (a real invariant, chained from the
    INSERT, whose ids are pinned to `[0, 6)` and distinct); updated rows keep the earlier id and get
    `snapshot.sequenceNumber()`; inserted rows must lie in `[before.nextRowId, after.nextRowId)` with the snapshot's
    sequence number; no shared ids among live rows. Expected lineage is a model over the read-back, not copied from
    the current state, except the upgraded rows (F2). Next-row-id growth is metadata-only (F3).
  - Time travel and rollback: before/after values read back earlier and compared with `VERSION AS OF`; the id of the
    row inserted after the rollback must be `>=` next-row-id after the rolled-back UPDATE. Can fail. Sound.
  - Upgrade: the post-upgrade applied-deletes literal (`1 PARQUET holding 1 positions`) is independent; the first v3
    DELETE's merge of the old position into one DV is checked by the derived expectation `1 PUFFIN holding 2` (sound
    only if the delete is MOR; F1).
- Baseline: this list has no legacy-path baseline; the baseline is the test's own model plus spec invariants, the
  same on every platform. A wrong-but-consistent answer on all paths fails for rows, vector multiplicity, dangling
  vectors, position deletes on v3, lineage of untouched/updated rows and id uniqueness; it passes for MOR written as
  COW (F1), upgraded rows' lineage (F2) and next-row-id/added-rows arithmetic (F3).
- `RowLevelStatementsTest` + 3 path classes (3.5 catalogTest): declarations checked by `BaselineChecks` (must differ,
  must quote the baseline, must be consulted). Avro declared with the exact `ClassCastException` text, so a fixed
  classpath makes the run fail ("declared but the value is the baseline's") rather than hide it.
- `SparkIrcRowLevelStatementsTest` (4.2 smokeTest): no declarations; Avro v3/v2 run here, so the partition-scoped v2
  Avro model is exercised only on 4.2.
- `SparkIrcFormatV3Test.rowLineageWithoutIcebergSqlExtensions`: literal expected row ids and sequence numbers
  (0/1/2, 1/2/3), asserts the session has no extensions; fully independent. Can fail. Parquet unpartitioned only, no
  delete-file checks.
- Unpinned claims: Avro row-level statements on any Spark 3.5 path and on the 1.5.2 line (declared, not run); MOR
  producing deletes (F1); "old rows get row ids from that commit" (F2).

## 5. Checked and found sound

- `BaselineChecks` extraction: the removed block of `SparkSqlWriteStatements` and the moved code are identical line
  for line (mechanical diff, only the class brace differs). Lifecycle methods (`beginTest`, `endTest`, `endSuite`) are
  the only ones in the hierarchy; neither subclass (`RestHttpPathWriteStatementsTest`, `SparkIrcWriteStatementsTest`)
  overrides them; `Difference` is not imported by name anywhere, so the move of the nested class compiles unchanged.
- Avro class clash reason: true. The itest's test runtime has the thin `iceberg-core` (embedded service) next to
  `iceberg-spark-runtime`; the build already excludes thin `iceberg-data` (and on 1.12 `iceberg-orc`/`iceberg-parquet`)
  for the same reason. The shipped `openhouse-spark-3.5-runtime_2.12-uber.jar` (built in the author's worktree) holds
  no `org/apache/iceberg/**` and no `org/apache/avro/**` classes, and `openhouse-java-runtime`'s shadow jar packs only
  `fatJarPackagedDependencies` (secureclient), with `iceberg-core` as `implementation` only. So a real Spark 3.5
  deployment has only the runtime's shaded copy: harness matter, not a product problem.
- 1.5.2 position deletes: LinkedIn 1.5.2.24's `RowDelta` has no `removeDeletes` and `SparkPositionDeltaWrite` has no
  rewritable/rewritten deletes (checked with javap/strings on the cached jar); the fork 1.12 has
  `broadcastRewritableDeletes` and `rowDelta.removeDeletes(...)`. Declared numbers are plausible per statement.
- Skips on 1.5.2: exactly the 10 v3 tests (8 v3 shapes, v3 time travel, upgrade); the 7 v2 tests run. The assumption
  is the v3 probe only and wraps nothing else.
- Spark 4.2 service change: with no extra arguments the Spring argument list is the same as before; `get()` passes an
  empty list. The v3 service has its own temp work directory, warehouse (`ServiceWarehouse.create()`), port 0, tomcat
  base and access log, so it does not touch the switch-off service or its access-log checks. The new class sets a
  `rowlevel` catalog on the shared `SmokeSpark` session (a new catalog name; nothing else changes).
- Spark 3.5: `FormatV3Server` (existing) per class, H2 URL is `jdbc:h2:mem:${random.uuid}`, so no shared house-tables
  state; the suite session is `base.newSession()`, so its catalogs do not leak into the base session.
- Runtime (one CI sample each, head vs base): default spark-3.5 itest 12m20s to 15m29s (+3m), default spark-4.2 itest
  15m36s to 19m36s (+4m), reference spark-3.5 itest 10m14s to 11m00s. Adds 3 embedded Spring services and 3 Spark
  sessions (sharing the fixture's Spark context) to the 3.5 catalogTest JVM, about 48 tables (17 per class minus
  skips); in each 4.2 smoke JVM one more service JVM (-Xmx768m) and 17 tables, run twice (F4).
- Spark 4.2 lineage test runs under `testServerPlanning` with `ServerPlanning.notApplied` for the v3 catalog (existing).

## 6. Not checked

- Did not run any build or test; CI at 47d20539 green (13 success, 1 skipped by the workflow) per the check-runs API.
- Did not read the outcomes TSV files of a run to see the actual per-file strings.
- Did not verify the 1.5.2 declared lists value by value (they are exact strings, so a wrong one would fail the run).
- Spark 4.2 behaviour of `CALL ... rollback_to_snapshot` and row-level planning without extensions: taken from the
  green run.

## Re-check at b27e5133

Read `git diff 47d20539..b27e5133` (commits 8f096093, b27e5133; 5 files, all test sources plus the 4.2 itest
build.gradle). Not run. Verdict: MERGE.

- F1 closed. The new check in `step()`, "rows the statement removed from existing data files through delete files",
  expects `change.deleted.size() + change.updated.size()` on MOR and 0 on COW. That expected value comes from the
  statement's own `Change` model, not from the table. The actual value is the sum, over data files that existed
  before, of rows the reader now drops. A MOR table written copy-on-write gives 0 against 1/1/2/1/1 on DELETE,
  UPDATE, the three-clause MERGE, the update-only MERGE and the delete-only MERGE, so it fails on every shape and in
  every row-level test. `step()` is the only path for statements, which includes the rollback test and the v3 steps
  of the upgrade test; there the expected value is 1 (row 3) against deletedNow 2 minus deletedBefore 1. The reverse
  mutation (COW written MOR) also fails, because 0 is expected. Further checks were added: `checkWriteModes()` on all
  three CREATE sites (literal expected from `shape.mode`); "a deletion vector is live" on v3 MOR statements that
  delete or update; and "deletion vectors live == filesWithDeletes". The author's mutation count of 11 matches my
  reading: 1 write-mode check, plus 5 rows-removed checks, plus 5 "a deletion vector is live" checks, on
  v3MergeOnReadParquet.
- F2 closed. For upgraded rows, the expected sequence number is now `insertSequenceNumber`, which is the v2 INSERT
  snapshot's sequence number, read before the upgrade. It is no longer copied from the actual row. "Assigned" now
  requires `before.nextRowId <= rowId < after.nextRowId`, so a null id (-1) fails. A new check fails any live row
  whose lineage contains null.
- F3 closed. The author's reading of the specification is right:
  - spec.md defines the snapshot's `added-rows` as "the upper bound of the number of rows with assigned row IDs"
    (field table and the Snapshot Row IDs section), and recommends incrementing next-row-id by the added plus
    existing row counts of all manifests assigned a first_row_id.
  - The fork computes the snapshot field as `assignedRows = writer.nextRowId() - base.nextRowId()`
    (SnapshotProducer), where ManifestListWriter adds `existingRowsCount + addedRowsCount` per assigned manifest.
    TableMetadata then does `nextRowId += snapshot.addedRows()`.
  - On a COW partitioned table, the rewritten manifest carries the other partition's EXISTING file, so added-rows
    is greater than the rows of the new files. That is allowed: existing files keep their written first_row_id, and
    only id space is skipped. Not a product defect.
  - One caveat: the spec's worked example says "added-rows ... 100 (50 + 50)" next to a next-row-id step of 375.
    That is an inconsistency inside the spec. The field definition and the fork agree with the test.
  - Independence: the equality with the manifest-based sum is metadata against metadata written by the same
    ManifestListWriter in the same commit, so it can fail only if the two diverge (weak, but harmless). The check
    that matters is "added-rows >= rows of the data files the statement added (+4 old rows on the first v3 commit)".
    It takes record counts from the scan's new data files, independent of the snapshot field, and fails on
    under-allocation, the dangerous case.
  - Residual nit, not blocking: manifests with a null first_row_id (-1) are silently excluded rather than flagged.
- F4 closed. `smokeTestServerPlanning` excludes `SparkIrcRowLevelStatementsTest`, and the println is removed.
- F5 closed. No X/V/RF/FF/BC ids or "work item" are left in added lines of the whole PR diff. The "Moved out ..."
  sentence is removed.
- F6 closed. The class Javadoc now states the 1.12 v2 baseline: file-scoped and replaced, with Avro
  partition-scoped.
- F7 closed. `requireFormatV3()` asserts that `buildLineHasFormatV3()` equals `formatV3Available()`, then assumes on
  the build line. On 3.5 the build line comes from `smoke.iceberg.build`, so an unset property on a 1.12 classpath
  fails loudly. On 4.2 the build line is constant true.
- Nothing else changed: the only other edits are Javadoc rewording in RowLevelStatementsTest and
  SparkIrcRowLevelStatementsTest.
