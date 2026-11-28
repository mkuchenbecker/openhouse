# Review of pull request 161: "Evidence suites compare with a recording of LinkedIn main"

Head `210d54f85ee43f17bdd2160bfae525d7c0908b59`, base `origin/irc-integration` `4b9d152d` (the merge base). Read only. No builds run. I ran
plain `diff`, `git`, `python3` over files, `javap` over cached jackson-core jars, and `gh api` GETs for check runs and job logs.

## 1. Verdict

**MERGE AFTER FIXES.** The recording is genuinely main's and the comparison is mostly sound. Close these first:
M1 (the claim that "only production code differs" is false for the Spark suites), M2 (the `NOT ON MAIN` escape hatch, which the
failure message tells people to use by editing the recording by hand), M3 (the Iceberg-line list misses line branches, including
the selector of this pull request's own commit-path declarations).

The three things that matter most:

1. **The stored recordings come from main.** I checked all of them, not a sample. The union of the six raw runs in `raw-final5`
   is byte-identical to the body of each stored file: smoke 304 lines, write 80, contract 42, commit-path 310. The header names
   `bb8929e1004a…` and the jars that were loaded (`iceberg-spark-runtime-3.5_2.12-1.5.2.24.jar`, `iceberg-api-1.5.2.24.jar`). The
   main-side sources the author copied in (`main-worktree-final`) are identical to the branch at `0fe4678a`, apart from the three
   `sed` substitutions in `CatalogPluginContractTest`. All 23 declarations in code (9 active on the reference line, 17 on the
   default line) quote main's recorded answer exactly; I checked this mechanically.
2. **The test setups were not the same on both sides, contrary to the claim.** The Spark suites on main ran against main's own
   House Tables stand-in, the one in `tables-test-fixtures` `src/main`, which the script does not and cannot replace. On the
   branch they run against the branch's rewritten stand-in: compare-and-swap on save, the delete flag read the other way round,
   case-insensitive listing, a guard on renames. The commit-path suites use the branch's stand-in on both sides. The pull request
   body, `record.sh` and the author's record all say "same fixtures as the branch, so only production code differs". That is
   true only for `services/tables`.
3. **What a green run proves is narrow.** It covers 734 recorded keys on one existing path: Spark 3.5 through the OpenHouse
   catalog over the native API, plus MockMvc requests to 9 native route shapes. Comparisons run under test doubles that allow
   every principal everything except two, and under normalizations that drop the identity of ids. Several existing surfaces
   that the default line demonstrably changes are not under it. One example: `BaseRowDelta` names a merge-on-read DELETE
   "delete" on Iceberg 1.12 where main says "overwrite". It is in no ledger row and is filed in the new list as "not a
   difference from main".

## 2. Findings

### M1. MUST FIX BEFORE MERGE: the recording's premise ("only production code differs") is false for the Spark suites

- **What goes wrong:**
  - `record.sh` replaces the stand-in only under `services/tables/src/test`:
    `cp "$REPO"/$H2/CommitPath*.java "$REPO"/$H2/LinkedInMain*.java "$REPO/$H2/HouseTablesH2Repository.java" "$MAIN/$H2/"`.
  - The Spark 3.5 itest gets its service from `SpringH2TestApplication` in
    `tables-test-fixtures/tables-test-fixtures-iceberg-1.5`. On main that module compiles
    `tables-test-fixtures-iceberg-1.2/src/main/java/.../tablestest/HouseTablesH2Repository.java` (main's `build.gradle:18`
    `srcDirs += project(':tables-test-fixtures:tables-test-fixtures_2.12')...`). That is `src/main`, so the guard
    (`git status` must show test directories only) leaves it as main's.
  - `diff` of main's fixture stand-in against the branch's
    (`tables-test-fixtures/tables-test-fixtures-iceberg-1.5/src/shared/main/java/.../HouseTablesH2Repository.java`, +357 lines)
    shows four differences:
    - save is a compare-and-swap on the metadata location, under a lock;
    - `deleteById(key, purge)` replaces main's `deleteById(key, isSoftDelete)`, so the meaning of the flag is inverted;
    - listing is case-insensitive (`findByDatabaseIdIgnoreCase`) and `findStoredLocation` uses `lower()`;
    - a rename onto an occupied key is refused.
  - So every Spark row compares (main's code plus main's stand-in) with (the branch's code plus the branch's stand-in).
  - Stated in: the pull request body ("…the grant-storing authorization backend and House Tables stand-in the branch's suites use,
    so only production code differs"), `record.sh` lines 14 to 18, and the author's record ("same fixtures as the branch, so only
    production code differs").
- **Why it matters:**
  - A false match needs a product change and a stand-in change that cancel out. For example: the branch drops a check in the
    product that main had, and the branch stand-in's compare-and-swap supplies the same 409. I found no concrete instance; that
    would need a run.
  - A reader is told the opposite of what was done. This is an audit tool: what it was run with has to be stated exactly.
  - The two suites made opposite choices. `services/tables` copied the branch stand-in into main; the Spark suites did not.
- **How sure:** certain from reading. Whether any row is affected, I cannot tell without running.
- **Smallest fix:**
  - Correct `record.sh` (comment lines 14 to 18), the pull request body and the record.
  - Have `LinkedInMainRecording.header` write a `# test doubles:` line naming which House Tables stand-in and which authorization
    backend each suite ran with on main.
  - SHOULD, to settle it: re-record the commit-path suite once with main's own `e2e/h2/HouseTablesH2Repository` (do not copy it).
    Report which rows change. Those are the rows decided by the stand-in, not by main's production code.

### M2. MUST FIX BEFORE MERGE: the `NOT ON MAIN` marker is an escape hatch that the failure message advertises

- **Where:** `LinkedInMainComparison.java` (both copies), lines 148 to 166 in the Spark copy.
- **The quoted lines:**
  - The message for a statement with no recorded outcome says
    `"record it against main (scripts/linkedin-main-baseline/record.sh), or add it to linkedin-main/" + suite + ".tsv as \"" + LinkedInMainRecording.NOT_ON_MAIN + "<the reason>\""`.
  - A row so marked gives `verdict = "not on main"` with no problem raised.
  - A declaration for it is itself an error: `"a difference is declared for a statement not on main"`.
- **What goes wrong:**
  - A row edited in by hand turns any red statement green. The statement is then compared with nothing.
  - Its answer on the branch is pinned nowhere: a declaration is forbidden, so the `here` value can never be stated.
  - `LinkedInMainRecordingFilesTest` cannot tell a hand-edited row from a recorded one: it checks the header, the sorting and
    duplicates only.
  - This contradicts the file's own javadoc: "The file is data, never written by a test run … this script is the only way to
    write them".
  - No row uses the marker today (0 in all four files), so removing it costs nothing.
- **How sure:** certain from reading.
- **Smallest fix:** delete the `NOT_ON_MAIN` branch and the advice in the message. Alternatively, handle it like
  `NOT_OBSERVED_ON_MAIN`: require a `MainDifference` that pins `here` and gives the cause in code.

### M3. MUST FIX BEFORE MERGE: the Iceberg-line check finds `IcebergLine.current()` only, and collapses duplicates

- **Where:** `scripts/ci/check-iceberg-line-branches.py` lines 43 to 44 (`if "IcebergLine.current()" in line …`) and lines 37 and
  60 (sets of `(path, text)`).
- **Line branches in test code that it misses:**
  - `services/tables/src/test/.../CommitPathLinkedInMainDifferences.java:29` `if (IcebergV3.supported()) {`. This selects this
    pull request's own three Jackson declarations.
  - `FormatV3NativeApiTest.java`: 17 `assumeTrue/assumeFalse(IcebergV3.supported())`, including line 234, a test that runs on the
    reference line only.
  - `apps/spark-3.5/.../FormatV3MaintenanceTest.java:112` `System.getProperty("openhouse.iceberg.line")`.
- **Duplicates:** identical lines in one file count once. `OpenHouseTableOperationsTest` has 4 occurrences and 3 entries; CI
  prints "31 Iceberg-line branches" while there are more occurrences. A new branch textually identical to a listed one passes
  unseen.
- **Further gaps:**
  - The list's header claims "Each such branch asserts a different value on each line, so it fails … when that line answers as
    main again". Nothing enforces that, and the `assumeTrue` entries cannot fail.
  - FF-22 as written asks for "each failing when it no longer differs". The author marks FF-22 "partly", but the script and the
    TSV say "every".
- **How sure:** certain from reading.
- **Smallest fix:**
  - Add `IcebergV3.supported()` and `openhouse.iceberg.line` to the patterns.
  - Key the list by `(path, text, occurrence count)`, or store the count per entry.
  - List the new entries.
  - Reword the header claim to what is enforced.

### S1. SHOULD FIX: a removed or disabled test drops its recording and its declarations silently

- **Where:** `LinkedInMainComparison.endSuite`, lines 233 to 249. Both stale checks are guarded by
  `testsRun.contains(testOf(…))`.
- **What goes wrong:**
  - A scenario method, a write test or a commit-path test that is deleted, or marked `@Disabled`, takes its recorded rows and
    its declarations out of the comparison with no failure.
  - Deleting `s26` on the default line would delete the row-lineage difference from view.
  - A rename is loud, through `NOT RECORDED` on the new name. A deletion is not.
- **Why it can wait:** it needs someone to remove a test. Review of the diff would see that.
- **Fix:** in `endSuite`, compare the recorded test names with the test methods the class declares (by reflection) and fail on
  a recorded name with no method. Or do the same in the recording-files tests.

### S2. SHOULD FIX: the commit-path recording predates the stand-in now on the branch

- **What:** `raw-final5` ran with `HouseTablesH2Repository` from `0fe4678a`. The merge `210d54f8` (pull request 158) changed it:
  `findAllByDatabaseId` became `findByDatabaseIdIgnoreCase`.
- **Impact today:** I listed the route shapes recorded (section 4). None lists a database's tables, so no row is affected.
- **Fix:** re-record (about 15 minutes) so the recording corresponds to the stand-in in the tree.

### S3. SHOULD FIX: default-line differences that are real but are in no ledger row and outside the recording

1. **The snapshot operation of a merge-on-read DELETE.**
   - Fork `core/.../BaseRowDelta.java:55-56` at `openhouse-1.12.0`: `if (addsDeleteFiles() && !addsDataFiles()) return DataOperations.DELETE;`.
     Apache `1.5.2`: `return DataOperations.OVERWRITE;`.
   - This runs on the existing OpenHouse catalog path. Spark SQL `snapshots.operation` reads it, and so does anything in the
     maintenance jobs that inspects it.
   - `iceberg-line-branches.tsv` files the only test that shows it (`IcebergRestCatalogWriteTest`, `? "overwrite" : "delete"`)
     as "Iceberg client version … on a route LinkedIn main does not have; not a difference from main".
   - The write-statements suite has no merge-on-read DELETE. Its `deleteRemovesTheMatchingRows` is copy-on-write and records
     `[append, overwrite]` on main.
   - Not in `85-behavior-change-ledger.md`; I searched for "BaseRowDelta", "row delta" and the operation name.
   - Fix: reclassify the entry as "difference from main", add a merge-on-read DELETE write statement so the recording shows it,
     and add a ledger row.
2. **Jackson 2.22 in the default line's tables service.** This is the general dependency move behind the two NEW items. BC-027
   names Iceberg, ORC, Avro and Caffeine, not Jackson. The pull request body says "BC-027 names the dependency move": partly
   true.

### S4. SHOULD FIX: normalization erases identity

- **Where:** `LinkedInMainRecording.PATTERNS`, lines 142 to 154.
- **What it hides:**
  - `<uuid>` and `<number>` (any 10 or more digits) replace values without keeping equality. A change in whether a replace keeps
    the table's UUID, or whether two outcomes name the same snapshot, is invisible: `s32` records `snapshot_id` rows as
    `[<number>]`, and the locations as `…-<uuid>/00000-<uuid>…`.
  - Any future data value of 10 or more digits, or a timestamp literal in result rows, would be masked too. None is recorded
    today: 0 `<time>`, and `<number>` only in ids, times and properties.
- **Fix:** number the placeholders by first appearance within one outcome or test (`<uuid#1>`, `<number#2>`). Restrict the
  timestamp and 10-digit rules to metadata keys where possible.

### S5. SHOULD FIX: what the commit-path comparison does not compare

- **Where:** `CommitPathHttp.perform`, `LinkedInMainAnswers` (`services/tables`).
- Bodies of successful non-GET requests (POST 201, PUT 200) are not compared, only the status.
- Errors are compared by `$.message` only.
- All answers to one method and URI within a test become one sorted multiset (`LinkedInMainRecording.answers`), so the order and
  which request got which answer are lost.
- Answers from other threads keep the status only ("sent from another thread: status only"). The recorded race row is
  `200 … ; 7 x 409 …`.
- **Fix:** compare create and update response bodies (sorted keys, normalized). Key main-thread requests by occurrence, as
  `answerInOrder` does in the Spark copy.

### S6. SHOULD FIX: one environment variable turns every comparison into a green no-op

- **What:** `OPENHOUSE_LINKEDIN_MAIN_RECORD_DIR` set means record mode: `observe` returns before comparing, and `endSuite`
  writes a file and returns nothing wrong. Nothing stops that mode on the branch.
- **Fix:** in record mode, fail if a branch-only class is on the classpath (for example `com.linkedin.openhouse.javaclient.IcebergLine`,
  which main lacks), or if `OPENHOUSE_LINKEDIN_MAIN_COMMIT` does not start with `bb8929e1`.

### MINOR

- **Spark copy of `LinkedInMainAnswers`:** `CURRENT` is a plain `ThreadLocal`, so answers given on other threads are dropped
  without a trace. The `services/tables` copy uses `InheritableThreadLocal` and status only. Today no contract test answers off
  the test thread.
- **Correlation of the two `s31` statements:** they are compared one by one against "any recorded answer". On main, `#4` and
  `#5` always share one order within a run (runs 1 and 2 gave HC,PII; runs 3 to 6 gave PII,HC). A branch giving `#4` PII,HC
  and `#5` HC,PII would pass, although main never gave that pair.
- **`record.sh` and incomplete runs:** it does not check that all runs recorded the same set of keys. `sort -u` takes the union,
  so a run that stopped early is invisible. All six runs had identical key counts here.
- **Duplicated code:** the three classes are copied into `services/tables`. `Recording` and `Comparison` agree apart from the
  package and javadoc. `Answers` differs by design: thread handling, fixed suite. Consider one shared test-fixtures source.
- **Line length:** checkstyle warns on `LinkedInMainDifferences.java` lines of up to 1,135 characters (CI log). Readable diffs of
  a declaration need wrapped literals.
- **Not implemented from FF-20:** "an edit to the file must name a decision".
- **Wording of the Jackson declaration:** it says 2.22.2 against 2.14.3. I confirmed the default of
  `JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION` from the jars' bytecode: `true` in 2.14.3 and 2.15.2, `false` in 2.16.0 and
  2.22.2. The fork's `gradle/libs.versions.toml` pins `jackson-bom = "2.22.2"`. Which jackson-core each line's
  `services:tables` actually resolves I could not confirm without Gradle; the recorded text proves at least 2.16 on the default
  line and below 2.16 on main.

## 3. Behavior changes

The pull request changes no product code. Non-test files in the diff: `.github/workflows/split-build.yml` (one plan-job step),
`scripts/ci/check-iceberg-line-branches.py`, `scripts/ci/iceberg-line-branches.tsv`, and `scripts/linkedin-main-baseline/**`.
Every Java file is under `src/test`, `src/shared/test` or `src/shared/smoke` of the itest, or under `services/tables/src/test`.
The author's section agrees.

Differences against main that the pull request surfaces or that I found:

| Line | Where | Main | Branch | Cause, checked | Ledger | Restorable in OpenHouse |
|---|---|---|---|---|---|---|
| both | s19, s24 namespace statements, contract namespace tests (6 smoke, 3 contract on the reference line) | `UnsupportedOperationException` "Describing database…" | stored namespace records, `SCHEMA_NOT_FOUND` | quotes match the recording | BC-046 to BC-049 | owner's (directed) |
| 1.12 | s19, s24 CREATE, DROP, USE | as above | "Checking if database exists is not supported" | fork `SparkCatalog.namespaceExists`, commit `ccc3e14df` (#14507), confirmed | BC-030 | no (Iceberg 1.12) |
| 1.12 | contract `capabilityMismatch` | 2 refusals | 3; the existing client is refused | `OpenHouseCatalog.capabilities` by line | BC-029 | foundation |
| 1.12 | s26 `DESCRIBE TABLE EXTENDED` on a format 2 table | metadata columns up to `_deleted` | also `_row_id`, `_last_updated_sequence_number` | fork `spark/v3.5/.../SparkTable.java:276-287`, unconditional, confirmed | NEW, not in the ledger | not in OpenHouse (Iceberg's `SparkCatalog` builds `SparkTable`); a small fork patch gating on format version 3 or above. Not on the reference line: 1.5.2 has no such columns, and the declaration is gated by `ICEBERG_112_LINE` |
| 1.12 | 3 commit-path 500s on a body that is not JSON | `[Source: (String)"not json"; …column: 4]` | `[Source: REDACTED (…disabled); …column: 1]` | jackson-core default changed in 2.16.0 (bytecode, confirmed); fork BOM 2.22.2 | NEW, not in the ledger (BC-027 omits Jackson) | partly: one line at tables-service start, `JsonUtil.factory().enable(JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION)` (Iceberg's shared factory parses these), restores the source text, not the column. Redaction is a security default; I recommend declaring and keeping it. Not on the reference line: the declaration is gated by `IcebergV3.supported()` and the reference-line run is green with none |
| 1.12 | merge-on-read DELETE through the OpenHouse catalog: `snapshots.operation` | `overwrite` | `delete` | `BaseRowDelta.operation()`, confirmed in both versions | NEW, not in the ledger, misfiled in the line list | no (Iceberg); fork patch |

Differences from the author's view:
- The last row is missing from the author's accounting.
- The stand-in asymmetry (M1) is unacknowledged.
- "BC-027 names the dependency move" is partly wrong: BC-027 does not name Jackson.

## 4. Tests

**What the recording holds:**

| Suite | Keys | Lines | Tests | Main's verdicts |
|---|---|---|---|---|
| smoke | 302 | 304 | 33 scenarios | not compared |
| write | 80 | 80 | 19 | not compared |
| contract | 42 | 42 | 21 | 19 pass, 2 fail |
| commit-path | 310 | 310 | 65 | all pass |

The commit-path rows cover these route shapes:
- `DELETE` table, `/lock`, `/purge`;
- `GET` table and `softDeletedTables`;
- `PATCH /rename`;
- `POST` create and `/lock`;
- `PUT` table, `/iceberg/v2/snapshots`, `/restore`.

**Per class:**

- **`LegacyPathSparkSqlSmokeTest`:** can fail for the right reason. Every `sql()` statement is compared, and missing or extra
  statements fail when the scenario ran. Not compared:
  - the statement inside `CommitRace.arm` (`SparkSqlSmokeScenarios.java:1619`);
  - the `DESCRIBE TABLE EXTENDED` helper at line 1379;
  - `DROP TABLE IF EXISTS` cleanup.
- **`LegacyPathWriteStatementsTest`:** sound. Values come through the `check()` hook. The recorder's `stored()` and `rows()` are
  identical to `RestHttpPathWriteStatementsTest`'s apart from two comments. It runs on the shared fixture service, the same as
  main's recorder.
- **`CatalogPluginContractTest` (extension):**
  - Compares `thrown` answers and the verdict. Main ran the `sed`-ported legacy branches, so a green verdict on the reference
    line means main passes the branch's reference-line literals.
  - On the default line, value assertions inside `IcebergLine.current() == V3` branches are never checked against main.
    Both branches (lines 359 and 607) do route their exceptions through `thrown()`, so today nothing is lost.
- **The eight `CommitPath*Test` classes and `CommitPathUpdateRulesTest`:** I read the diff line by line. The changes are
  `@ExtendWith(LinkedInMainAnswers.class)` and `mvc.perform` → `perform(mvc, …)`. No assertion was changed, weakened or removed.
  Every request in the classes goes through `CommitPathHttp`; no direct `mvc.perform` is left. Requests made in `@AfterEach`
  run outside the interceptor and are not compared, which is fine.
- **`LinkedInMainRecordingFilesTest` (both copies):** checks the header, the sorting and duplicates only. It cannot detect hand
  edits (M2). A deleted or renamed `.tsv` fails loudly: here, and in `LinkedInMainRecording.load` (`IllegalStateException`) in
  the constructor or interceptor.

**Stale declarations:** the author's claims hold when the test ran. A declaration whose statement gives main's answer again
fails ("a difference is declared but the outcome is main's"), and so does a declaration nothing consulted. When the test did not
run, neither fails (S1).

**Unpinned claims:**
- "only production code differs" (M1);
- "every Iceberg-line branch" (M3);
- "a declaration … quotes main's answer", which holds only per active line. Both lines are green, and my mechanical check
  confirms all 23.

## 5. Checked and found sound

- **Provenance:** commit, JDK 17, main's wrapper, the loaded jars named in the header; the guard allows only test directories
  (status: one `M` in `services/tables/src/test`, the rest untracked test files).
- **Stored against raw:** for all four files, the union of runs 1 to 6 equals the stored body exactly.
- **Multiple answers:** only `s31 #4` and `#5` have two answers (tag order). BC-005 is indeed invisible: the branch's fixed
  PII,HC is one of main's per-JVM answers. Its other half, that a PUT differing only in order rewrote the policies, is not
  observed either.
- **The `iceberg-data` exclusion:** matches main's own `apps/spark-3.5/build.gradle:19-21` and the branch itest's
  `build.gradle:29-34`. It is on the test classpath and the same on both sides.
- **The authorization stand-in:** `GrantStoringAuthorization` is the same on both sides.
- **The two copies of `Recording` and `Comparison`:** they agree apart from the package and javadoc.
- **OS-user normalization:** replaces only whole words equal to `user.name` (3 characters or more). The recording has exactly
  one `<os user>`, the s26 Owner.
- **CI on `210d54f8`:** all success; the Docker job is skipped on the reference line by design. In the job logs:
  - Default 140900851841 and Reference 140900843337: `CatalogPluginContractTest` 21, `LegacyPathSparkSqlSmokeTest` 34,
    `LegacyPathWriteStatementsTest` 19, `LinkedInMainRecordingFilesTest` 3, all PASSED, 0 FAILED.
  - Default rest 140900851814 and Reference rest 140900843228: 65 `CommitPath*` PASSED and the files test PASSED. The 90 FAILED
    lines in the default rest log are `OpenHouseRestCompatibilityKit*`, which were already failing and are unrelated.
  - Both plan jobs: "31 Iceberg-line branches in test code, all listed".
- **Private ids:** none in added code. The only upstream references are Apache Iceberg pull request 14507 and the upstream
  `BaseRowDelta` behavior.
- **Kinds in the line list:** I sampled 12. 11 are honest; the exception is `IcebergRestCatalogWriteTest` (S3.1).

## 6. Not checked

- Whether any Spark row would change with matching stand-ins (needs a run: M1).
- Which jackson-core version each line's `services:tables` resolves (needs Gradle).
- When the column counting in Jackson's location changed (no jackson sources on disk).
- The earlier `raw-final` to `raw-final4` directories, beyond noting that they exist.
- Spark 4.2, Spark 3.1, the jobs, House Tables routes, the Java client outside the contract test, and published artifacts
  (FF-23, FF-24): none of these is under the recording at all. A green run says nothing about them.

**What a reader may conclude:** a green run shows that, on Spark 3.5 through the OpenHouse catalog over the native API, and for
the 9 native route shapes the commit-path tests send, each of these 734 recorded statements gives LinkedIn main's answer or a
declared one. The comparison is made after normalizing ids, times and the OS user, and both lines run under test doubles that
allow nearly everything. **What a reader may not conclude:** that existing behavior did not change. Differences in authorization
decisions, in the identity of ids, in response bodies of writes, and on every surface outside those statements are not
measured. The default line already shows at least one Spark SQL difference outside the recording (the row-delta operation
name). On the Spark side, part of what is compared is the branch's test stand-in against main's.
