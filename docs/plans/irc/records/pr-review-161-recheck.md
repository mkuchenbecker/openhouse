# Re-check of pull request 161 at head `0e5472cd5112367a9deb6d7ef7ff71fdbfd85d41`

Base `origin/irc-integration` is at `67989d16`, which is also the merge base. Read only. I ran:
- `git` and `diff`;
- `python3` over files;
- the two CI scripts, which only read files (`check-iceberg-line-branches.py`, `check-private-ids.py`);
- `gh api` GETs.

## Verdict: MERGE

M1, M2, M3, S1 and S6 are closed. None of the 16 new test-double declarations hides a change to the product: the product methods
on those paths are the same text at `bb8929e1` and at head, and the answers come from main's own exception mapping. What is
still open is listed below. All of it can follow, and none needs to block the merge.

## 1. The items the author reports closed

### M1 (closed)

- **Headers:** every stored recording now has a `# test doubles:` line.
  - Spark recordings: main's own `tablestest/HouseTablesH2Repository` at `bb8929e1`. The authorization backend is named for each
    suite.
  - Commit-path recording: this branch's earlier `e2e/h2` stand-in at `0fe4678a`, copied into main.
- **Asserted:** both `LinkedInMainRecordingFilesTest` copies assert that the line exists and names both the House Tables
  stand-in and the authorization backend. The Spark copy also asserts `# stand-in check: identical…`.
- **Pull request body:** now states, in a table, that the test doubles differ from suite to suite.
- **Remaining wording problem (MINOR):** the body's "Recording" bullet (line 19) still says `record.sh` "copies in … the House
  Tables stand-in of `services/tables`' tests". The script no longer does: it copies `CommitPath*.java` and `LinkedInMain*.java`
  only, and that stand-in is deleted.

### M2 (closed)

- `notOnMain` and the "add it to the tsv" advice are gone from both copies.
- `LinkedInMainRecording.parse` throws on any outcome that starts with `NOT ON MAIN: `.
- The message for an unrecorded statement now points to `NOT_OBSERVED_ON_MAIN`, which pins the branch's answer.
- `LinkedInMainComparisonTest` pins all three cases. It is present and passing in both copies on both lines (6 tests each in CI).

**The bypass that remains (can follow).** Someone can add a second line for an existing key that carries the branch's answer.
The reader takes the union, so that answer becomes "one of main's answers" and passes. The files test checks only the header,
the sorting and duplicate lines.
- Today only `s31 #4` and `s31 #5` legitimately have two lines.
- Fix: in both files tests, assert that the set of keys with more than one outcome equals a short reviewed list. Today that list
  is those two keys. Any other addition by hand then fails.

### M3 (closed)

- **Patterns:** the check now looks for `IcebergLine.current()`, `IcebergLine#current()`, `IcebergV3.supported()` and
  `openhouse.iceberg.line`.
- **Counts:** each entry carries the number of times its line occurs in the file.
- **Run on the tree:** "92 Iceberg-line branches in test code (50 distinct lines), all listed", exit 0. The same output appears
  in both plan jobs on head.
- **Specific entries:** `CommitPathLinkedInMainDifferences`'s `IcebergV3.supported()` selector, `FormatV3NativeApiTest` and
  `FormatV3MaintenanceTest` are listed.
- **Refiled:** `IcebergRestCatalogWriteTest` is now "difference from main" (the merge-on-read DELETE operation name).

### S1 (closed, with one gap that can follow)

- **What now fails:** `recordedTestsNotDeclared` together with `enabledTests`, found by reflection: a method must carry `@Test` or
  `@ParameterizedTest`, and the method or its class must not be `@Disabled`. A recorded test that is gone or disabled fails,
  unless `absentTests()` names it.
- **Where it runs:** `LegacyPathSparkSqlSmokeTest`, `LegacyPathWriteStatementsTest`, both `LinkedInMainAnswers` copies (the
  contract test and every `CommitPath*` class).
- **`absentTests()` is not a new escape hatch.** It is a map in reviewed code. Each entry must be recorded, must not be declared,
  and must carry a non-empty reason, or the comparison fails. So it cannot go stale. Its growth shows only in the diff, as any
  declaration's does. It holds one entry, the renamed case-rename test.
- **The gap (can follow):** the check runs in `afterAll` of a class that ran at least one test. If a whole recorded class is
  deleted, or a whole `CommitPath*Test` class is never run, its recorded rows are ignored and nothing fails.
- Fix: in the files test, `Class.forName` the class prefix of every recorded test name in `commit-path.tsv` and
  `catalog-plugin-contract.tsv`, and fail when it is missing or `@Disabled`.

### S6 (closed)

- `recordDirectory()` calls `checkRecordable`.
- It refuses unless `OPENHOUSE_LINKEDIN_MAIN_COMMIT` starts with `bb8929e1` and the branch-only class is absent:
  - Spark copy: `javaclient.IcebergLine`;
  - `services/tables` copy: `tables.formatversion.IcebergV3`, which does not exist at `bb8929e1` (checked).
- Pinned by `onlyLinkedInMainMayRecord`.
- I found no bypass that does not involve editing the guard itself.

### Comparison code

The two copies of `Recording` and `Comparison` are still identical apart from the package, the javadoc and `BRANCH_ONLY_CLASS`.

## 2. The 16 test-double declarations

### Main's quoted answers

All 16 match `commit-path.tsv` exactly; I checked mechanically. So do the 3 Jackson declarations: 19 of 19.

### Count and grouping

- 9 soft-deleted listings, where only `pageSize` and `size` change from 1 to 10:
  - `auth_rst`, `auth_prg`, `cc_purge`, `cc_listing`, `cc_listing_elsewhere`, `cc_restore`, `cc_delete`, `cl_restore`;
  - `cc_restore_unknown`, which also has the extra empty listing that the test added after a restore.
- 1 read by another spelling: `GET st_ren_case/tables/T`, 404 on main, 200 here.
- 1 restore request: `cc_restore_unknown` `PUT /restore`.
- 5 answers of the renamed case-rename test.

### The cited contract cases exist and say what is claimed

All five are in `tables-test-fixtures/.../HouseTableRepositoryContract.java`:

| Contract case | What it asserts |
|---|---|
| `theSoftDeletedListingIgnoresCaseSortsAsAskedAndPagesInTheStore` | the page has the size asked for (`getSize() == 2` for `PageRequest.of(0, 2)`) |
| `aRestoreOfAMissingSoftDeletedRowIsNotFound` | `HouseTableNotFoundException` |
| `aRestoreOverALiveTableIsAConflictAndChangesNothing` | `HouseTableConcurrentUpdateException` |
| `aCreateIsReadBackByAnySpellingWithTheSpellingItWasCreatedWith` | a row is read back by any spelling of its key |
| `aRenameOntoAnOccupiedKeyOfAnySpellingIsAConflictAndChangesNothing` | `HouseTableConcurrentUpdateException` for `u`, `U`, `v`, `V` |

Whether the contract really runs against the real House Tables service is the subject of pull request 163; I did not re-verify it.

### The product path is unchanged

**Methods compared.** I extracted each named method at `bb8929e1` and at `HEAD` and compared the text; all are identical:
- `TablesController`: `searchSoftDeletedTables`, `restoreTable`, `renameTable`;
- `TablesServiceImpl`: `renameTable`, `restoreTable`, `searchSoftDeletedTables`;
- `OpenHouseInternalRepositoryImpl`: `rename`, `restoreTable`, `searchSoftDeletedTables`;
- `OpenHouseInternalCatalog`: `renameTable`, `restoreTable`, `searchSoftDeletedTables`;
- `HouseTableRepositoryImpl`: `rename`, `restoreTable`, `searchSoftDeletedTables`, `findById`, `save`. I added this file.

**Where the declared answers come from.** They are produced by main's own code:
- Main's `OpenHouseInternalCatalog.restoreTable` maps `HouseTableNotFoundException` to `NoSuchSoftDeletedUserTableException`,
  whose text is "Soft deleted user table $db.$tbl with deletedAtMs $deletedAtMs cannot be found".
- It maps `HouseTableConcurrentUpdateException` to `AlreadyExistsException("Table", …)`, whose text is "$ent $id already exists".
- These are exactly the branch's declared texts.
- `OpenHouseExceptionHandler` has no change to these mappings. The new namespace handler has its own exceptions.

**The read by another spelling is not one of the author's listed methods, so I checked it separately.**
- `TablesServiceImpl.getTable` is only refactored (it now calls `checkLoad`, with the same checks in the same order).
- `OpenHouseInternalRepositoryImpl.findById` has no change.
- `OpenHouseInternalTableOperations.doRefresh` has no change.
- No case folding (`IgnoreCase`, `toLowerCase`) was added on the read path.
- So the 404 → 200 comes from the stand-in.

**No product change hides under the "stand-in difference" label on these 16 rows.**

### The renamed test

- **What was recorded.** The old test `renameOntoANameDifferingOnlyInCaseFromAnExistingTableSucceeds` has 6 recorded keys:
  - `GET OTHER`;
  - `GET other`;
  - `GET u`;
  - `PATCH …toTableId=OTHER`;
  - `POST`;
  - `verdict`.
- **The mapping is honest.**
  - The reason text names all six recorded keys.
  - The new test sends no `GET OTHER`, so it declares 5 keys.
  - Each declaration pins the branch's answer through `NOT_OBSERVED_ON_MAIN`: `2 x 201`, two `2 x 200` bodies,
    `409 Table local-cluster.st_ren_exist_case.other already exists`, and `passes`.
  - The old name is in `absentTests()` with its reason.
- **What this costs.** For these 5 requests the comparison now holds the branch to its own answer, not to main's. That is
  correct here because the product path is unchanged, but it is not evidence of agreement with main. Main's answer under the
  real store is inferred from the contract, not recorded.

### Test edits since the recording

Pull request 163 changed the `CommitPath*` tests after the recording: 6 files, +47/−24 lines. The changes are stand-in
injection and new expectations (restore over a live table now 409; the other spelling is the same table). All of them are
covered by the 16 declarations. This is the re-record debt the README describes.

## 3. The stand-in measurement

- **Recomputed from raw output.** For `standin-check/run1` and `standin-check-163/run1` I compared every stored key
  (302 smoke + 80 write + 42 contract = 424) with the measured outcome. In both: 424 keys, 0 not among the stored outcomes,
  0 missing, 0 extra.
- **Gradle log:** `tables-test-fixtures-iceberg-1.5:compileJava` ran (not `UP-TO-DATE`) in both runs, which fits a substituted
  stand-in.
- **The stored bodies are byte-identical to the ones I verified before** (union of `raw-final5`): smoke, write, contract and
  commit-path all identical. Only header lines changed.
- **MINOR: two header texts are constants in `record.sh`.**
  - `STAND_IN_CHECK_SPARK` ("checked on 2026-11-28") and `DOUBLES_TABLES` ("this branch's earlier stand-in … at 0fe4678a,
    copied into main") are written on every future re-record whether or not they are still true.
  - The README asks the next person to update them.
  - A commit-path re-record as the script stands would most likely fail to compile on main (the tests now inject the fixtures'
    stand-in), which fails loudly. Still, the script should refuse `TASKS` containing `tables` until the port the README
    describes exists, and should emit `# stand-in check:` only from a measurement made in that run.

## 4. The merges

**Parents:**
- `73da643b` = `bd5459f0` + `a0239ad7`;
- `96a25fb0` = `6f2fc38d` + `35bd941c`;
- `0e5472cd` = `b03b8757` + `67989d16`.

Each has both parents.

**Trees.** For every file touched only by the base side, each merge has the base version exactly. For every file touched only by
the branch, the merge has the branch version, with one exception:
- In `96a25fb0`, two branch-only files changed inside the merge: `LinkedInMainDifferences.java` and `iceberg-line-branches.tsv`.
- The only edits are rewording "Apache Iceberg pull request 14507/11868" as "the upstream change that …", which the private-id
  guard of pull request 164 requires (it counts any "pull request N" with N ≥ 80).
- This is benign, but it removes a public citation (MINOR). The guard's own comment allows `apache/iceberg#14507`, which would
  keep the reference.

**Files both sides touched:**
- `split-build.yml` keeps both plan-job steps: the Iceberg-line check, and the private-id check (self-test, then the check,
  default line only).
- The `CommitPath*` and contract merges keep the base's stand-in injection and the branch's wiring.
- `0e5472cd` had no file that both sides touched.

**Private-id guard.** Run on the tree: "No private planning ids outside scripts/ci/private-ids-allowed.txt", exit 0. Its scan
includes the `.tsv` data and the recorder sources. It also printed one stale allow-list note, for a Spark 3.1 file, which is
not this pull request's. The pull request adds no allow-list entry.

## 5. Pull request body: what a green run proves

The section "What a green run proves" now matches the bar I set:
- the scope: 734 statements on the legacy path and the 9 route shapes;
- the normalizations;
- the test doubles, which differ by suite;
- what is not measured: authorization decisions, the identity of ids, write-response bodies and which request got which answer,
  Spark 3.1 and 4.x, the other paths, the jobs, House Tables routes, the Java client outside the contract test, and published
  artifacts;
- the merge-on-read DELETE operation name, a known difference outside the recording.

Stale text (MINOR):
- line 19 (see M1 above);
- line 94 and the README's "Waiting for a re-record" still say the work waits for `irc/house-tables-stand-in-fidelity` to merge,
  but it merged as pull request 163;
- the README's "When to record again" still lists "the House Tables stand-in of `services/tables`' tests" as a copied test double.

## 6. CI on head

All 14 check runs completed with success. The only exception is the Docker job on the reference line, which is skipped by
design.

| Job | Contract | Smoke | Write | Files tests | Comparison self-tests | Failures |
|---|---|---|---|---|---|---|
| Default, Spark 3.5 itest (140948989017) | 21 | 34 | 19 | 3 | 6 | 0 |
| Reference, Spark 3.5 itest (140948991777) | 21 | 34 | 19 | 3 | 6 | 0 |

| Job | `CommitPath*` passed | Files test | Comparison self-tests | Failures outside the compatibility kit |
|---|---|---|---|---|
| Default rest (140948989008) | 65 | 1 | 6 | 0 |
| Reference rest (140948991608) | 65 | 1 | 6 | 0 |

The 10 skipped tests on the reference line are `LegacyPathRowLevelStatementsTest` v3 cases, which are not compared.

## 7. What merging 161 first buys for pull request 166 (Jackson 2.22 on the default line)

**What it would detect.** On the default line, the commit-path comparison would see a changed native answer from 166:
- in a status;
- in an error's `$.message`, including Jackson exception text in 500s;
- in a GET body's set of keys and values: an added or removed field, a null now written or omitted, a number written as a
  string, an epoch value turned into an ISO string.

The Spark comparisons would see it only where the Java client reads the changed field into what Spark shows.

**What it would miss.**
- The bodies of successful POST and PUT responses.
- Every error field other than `message`.
- Key order: it is sorted away.
- Number formatting inside GET bodies: the test re-parses and re-writes the body with its own `ObjectMapper`, so `1.0` against
  `1` and similar disappear.
- Changes in a timestamp's format: the `<time>` placeholder masks those, and numbers of ten digits or more become `<number>`.
- Request-parsing changes (strictness on unknown properties, coercion), except in the few malformed-body tests.

## Open, all can follow

1. Hand-added second outcome lines: assert that the set of keys with more than one outcome is a reviewed list (today `s31 #4`,
   `s31 #5`). Both `LinkedInMainRecordingFilesTest` copies.
2. A whole recorded test class deleted or disabled is silent: check that the class of every recorded test name resolves and is
   not `@Disabled`, in the files tests.
3. `record.sh`: stop writing `STAND_IN_CHECK_SPARK` and `DOUBLES_TABLES` as fixed text; refuse `TASKS` containing `tables` until
   the stand-in is ported.
4. Stale text: pull request body lines 19 and 94; README "When to record again" and "Waiting for a re-record".
5. Upstream citations: use `apache/iceberg#14507` in `LinkedInMainDifferences.java` `NAMESPACE_EXISTS_WHY` and in the
   `iceberg-line-branches.tsv` entries, instead of the vaguer wording.
6. Already declared as left by the author: S2, S4 and S5 (a full re-record), and the merge-on-read DELETE write statement.

## Confirmation at head `028f295f` (since `0e5472cd`)

### The five follow-up commits do what was asked, and nothing else

- **No recorded answer changed.**
  - All four `.tsv` bodies are byte-identical to the bodies I verified in my first review, and `commit-path.tsv` is untouched.
  - The only lines that changed in the three Spark files are the `# stand-in check:` header lines, which now read "…measured on
    2026-11-28 with 1 run(s)".
- **No declaration was added or removed.** The one reworded declaration is item 5: `NAMESPACE_EXISTS_WHY` now cites
  `apache/iceberg#14507`. Two reason texts in `iceberg-line-branches.tsv` now cite `apache/iceberg#14507` and `#11868`.
- **No comparison logic was loosened.** `LinkedInMainComparison`, `LinkedInMainRecording` and `LinkedInMainAnswers` are not in
  the diff at all.
- **Item 1:** both `LinkedInMainRecordingFilesTest` copies now require the keys that carry more than one recorded outcome to
  equal a reviewed list. That list is the two `s31` keys for the smoke recording, and empty everywhere else.
- **Item 2:** each class named in the contract and commit-path recordings must exist (`Class.forName`) and must not be
  `@Disabled`.
- **Item 3:** `record.sh` now:
  - defaults `TASKS` to `spark`;
  - refuses `tables` except with `MERGE_ONLY=1`;
  - writes the stand-in check line only from a measurement made for that recording.

  A measurement that finds a difference writes "DIFFERENT…", which the files test's `startsWith("identical")` check rejects.
- **Item 4:** the README and the pull request body are updated.

### The merge `903b8c84` is clean

- Parents: `0e5472cd` (the branch) and `35cc7b1e` (the base, pull request 165).
- No file was touched by both sides.
- Every file the base changed equals the base's version, and every file the branch changed equals the branch's version.

### Guards and CI

- The private-id guard passes on the tree (exit 0). It prints one stale allow-list note, for a Spark 3.1 file that is not part of
  this pull request.
- The Iceberg-line check passes: 92 branches on 50 lines, all listed.
- CI on the head: 13 jobs succeeded and 1 was skipped by design.

### Verdict: MERGE

One narrow gap remains, and it can follow. The smoke and write recordings are keyed by scenario or method name, not by class.
If `LegacyPathSparkSqlSmokeTest` or `LegacyPathWriteStatementsTest` were deleted as a whole, nothing would fail. The fix is to
name the class that owns each of these recordings in the files test and assert that it exists and is not `@Disabled`.
