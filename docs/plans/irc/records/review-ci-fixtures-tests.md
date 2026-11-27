# Review: CI workflows, shared test fixtures, evidence suites (slug `ci-fixtures-tests`)

Reviewer: independent, read-only. Checkout `/home/user/oh-review` at `81b4cd4d`, base `bb8929e1`.
Pull requests: 80, 81, 82, 83, 85, 90, 93, 104, 108, 111, 115, 133, 138, 140, 144, 146, 148, 150.

## 1. Bottom line

The CI split is built soundly, and the T4 commit-path tests are real early pins. The evidence behind "behavior
preserved" is weaker than the merge messages say. Five things matter most:

1. **No suite compares against LinkedIn main.** The smoke suite (T1), the contract suite (T3) and the commit-path
   suite (T4) are hand-written expectations in source. They run against the branch's own code, on both Iceberg
   lines. The "baseline" is the legacy path at HEAD under `-PicebergLinkedIn`, not `bb8929e1`. A change to shared
   service or catalog code moves the reference line too. The author then edits the expectation, and nothing marks
   that edit as a baseline change. It has happened: s31 tag order was loosened, then re-pinned after a service
   change (N8). s17 was re-pinned after the fixture changed (T6). s19 and s24 were split per line (D14b). About 32
   more `IcebergLine.current()` branches in test code accept default-build differences with no reason recorded.
   The smoke outcomes are written to `build/reports/smoke/*.tsv` and never compared with a stored file. No
   recorded baseline exists in the repository except the delta-harness's
   `BASELINE-2026-11-22.md`, which is not in CI and has not had a full run since the default build changed.
2. **LinkedIn's release workflow breaks if this branch lands on main.** `build-tag-publish.yml` is unchanged, but
   its `tag-publish-gradle` job runs `./gradlew publish` with no fork-publish step. The build now stops at
   configuration (`gradle/iceberg-fork.gradle:23-29`), and the remote-publish guard (`:72-86`) would refuse
   anyway. The version tag is pushed *before* that step (`build-tag-publish.yml` "Bump version and push tag"), so
   every push to main would tag a release that publishes nothing: no jars, no Docker images, no Python package.
   This is documented as "would now stop at this check" (`docs/development/iceberg-fork.md:101-102`, work item
   I6d), but the tag-first side effect is not mentioned.
3. **The compatibility kit's list is a two-way ratchet on outcome, not on cause.** A listed FAILED test that starts
   failing for a new reason (a regression) stays green. Class d ("defect") is empty. Some filings are wrong:
   - `testLoadTableWithMissingMetadataFile` is a server 500, filed as class e and counted in "message text 9".
   - The SPEC reason "bucket specs are refused" is contradicted by `testCompleteCreateTable`, which creates with
     a bucket spec and passes.
   - The view-suite flag `requiresNamespaceCreate=false` is commented "a database comes into being with its first
     view", while 24 VIEWDB failures say a view cannot create its database.

   The kit also runs with format v3 switched on, which is not the default configuration.
4. **The House Tables stand-in is faithful in compare-and-set and wrong in several places that tests rely on.**
   - Restore over a live table silently overwrites it (the real store answers 409).
   - Rename, delete and restore of a missing row do nothing (the real store answers 404).
   - The H2 column for the metadata location is 255 characters; the real schema has 512. PR 108's claim "the real
     store's column is also 255" is false per `services/housetables/src/main/resources/schema.sql:6`.
   - Too long a location gives 503 in tests where the real store gives 409.
   - There are two copies of the stand-in that disagree with each other on case sensitivity, cross-database
     rename and restore of a missing row.
5. **CI otherwise holds.** The split's partition is sound by construction, a dead test JVM fails the job,
   permissions are narrower than on main, no pull-request job touches a secret, and `pull_request_target` is not
   used. Costs: check names changed, the reference build doubles the pull request's runners, and the partition
   checker (`scripts/ci/check-test-groups.py`) never runs in CI.

## 2. Defects

### D1. BLOCKER (for upstream): the release workflow tags a version, then fails, on LinkedIn main

- **What goes wrong.** After a merge to `linkedin/openhouse` main, `build-tag-publish.yml`:
  1. `build-and-run-tests` passes, because `build-run-tests.yml` now restores or publishes the fork.
  2. `tag-publish-gradle` checks out, sets up the JDK, and runs "Bump version and push tag"
     (`anothrNick/github-tag-action`, which pushes the tag).
  3. It then runs `./gradlew publish -Pversion=...`.
  4. That job has no `./.github/actions/iceberg-fork` step, so `gradle/iceberg-fork.gradle:23` throws "The build
     uses Iceberg ... is not published in ...". With the fork published, `:72-86` would still refuse every
     non-`file:` publish.
  5. The Docker push steps are skipped (`success()`), and `build-publish-python` (`needs: tag-publish-gradle`)
     never runs.
- **Result.** A tag per merge, with no artifacts, no images and no PyPI package.
- **Where.** `.github/workflows/build-tag-publish.yml` (unchanged since `bb8929e1`); `gradle/iceberg-fork.gradle:23-29`
  and `:72-86`; root cause PR 118 (default flip), outside my list, but it is LinkedIn's own CI.
- **Certainty.** Certain from reading.
- **Smallest fix.** Run the release on the reference line (`-PicebergLinkedIn` on the publish step and on
  `build-run-tests.yml` when called from the release), or move the tag step after a successful publish.

### D2. BLOCKER (evidence for a stated requirement): the preservation suites have no baseline of LinkedIn main

- **What goes wrong.** The claim "Spark SQL behavior must not change by accident" rests on three suites:
  - `SparkSqlSmokeScenarios` (shared smoke, 34 scenario methods, about 250 `sql(...)` calls, 114 of which assert
    only that the statement succeeds);
  - `CatalogPluginContractTest`;
  - the `CommitPath*Test` classes.

  In each the expected outcome is a Java literal written when the test was written.
- **The "baseline" is not LinkedIn main.**
  `SparkSqlSmokeTest.differences()` (`integrations/spark/spark-3.5/openhouse-spark-itest/src/test/java/com/linkedin/openhouse/spark/catalogtest/SparkSqlSmokeTest.java`)
  documents it as "Spark 3.5 with the OpenHouse catalog on Iceberg 1.5.2". That is the legacy path *at the
  branch head* on `-PicebergLinkedIn`, which carries every service, client and catalog change the branch made.
  Nothing anywhere runs the scenarios against `bb8929e1`.
- **Cases where the baseline itself was edited.**

  | Commit | What changed | Stated reason |
  |---|---|---|
  | `adb70040`, `c11671f9` | s19, s24: the message is chosen per line in the test | "D14b, apache/iceberg#14507"; later moved into `namespaceExistsCheckOnIceberg112()` as Differences by N7 `c5a083ef` |
  | `87547373` | s31: exact tag order loosened to either order | "HashSet ordered by identity hash codes" (true of main) |
  | `7f8de45f` (N8, PR 139) | s31 re-pinned to `PII, HC` | "The service writes a column's tags in the declaration order ... (work item N8; before, the order changed)": a service behavior change written into the baseline itself, on every path, with no Difference |
  | `fe0ae995` (T6, PR 140) | s17: `rows()` (no grants) replaced by listings | The fixture changed |
  | `1598eb5b`, `e7eb4aac`, `ca87ee83` | s21-s30, s32, s33 added | Written against branch code after earlier merges, "asserted on the legacy path first" |

  The first-written scenarios (s01-s20, PR 80) and T3/T4 (PRs 81, 82) were written on unchanged code, so for those
  the literals equal main's behavior on the day they were written.
- **A second, unaudited difference channel.** About 32 `IcebergLine.current()` branches in test sources
  (`git grep "IcebergLine.current()"` outside `/main/`) hold different expectations for the default build:
  - `RTASTest` (D14a: an RTAS that dropped the partition source column now succeeds);
  - `CatalogPluginContractTest.capabilityMismatch` (line 527: a create with the column-defaults capability is now
    refused with 422 on the default build);
  - `OpenHouseTableOperationsTest`, `TablesClientTest`, `FormatV3SparkTest` and others.

  None goes through the `Difference` check (quote the baseline, prove it really differs, give a reason).
- **Certainty.** Certain from reading.
- **Smallest fix (a test that settles it).**
  1. Record the smoke outcomes TSV once from `bb8929e1` plus only the suite (scenarios s01-s33 back-ported as
     tests), and commit it as `smoke-baseline-bb8929e1.tsv`.
  2. Make `catalogTest` on the reference line compare its TSV with that file.
  3. Require that any edit to the file names a decision.
  4. Route the `IcebergLine` branches through `Difference` (or a reviewed list) so each carries a reason and fails
     when it no longer differs.

### D3. SHOULD FIX: the compatibility kit's ratchet ignores cause, and several filings are wrong

- **Where.** `integrations/java/openhouse-rest-compat-kit/build.gradle:128-206`; `expected-outcomes.txt`.
- **The ratchet (verified).** `afterTest` records `<Class>.<test>` → `FAILED|SKIPPED`, and `doLast` fails on any
  of: a name in one map but not the other, or an outcome mismatch. So a newly failing test fails the build, a
  newly passing listed test fails the build ("remove it from the list"), and an outcome flip fails the build.
  Good.
- **What it misses.**
  - **(a) Cause.** The class letter and reason are parsed (`parts[2] ==~ /[a-e]/`) but never compared with
    anything. 95 FAILED entries are 95 places where a new failure cause is invisible. Example: RTAS's 16 tests
    fail at "replace needs replace.enabled"; if the replace path then broke later in the test, nothing would show.
  - **(b) Disappearance.** A test that passed and silently stops running (a kit bump that renames it, a class not
    discovered) is invisible. The total (160) is only logged (`afterSuite`), never asserted.
  - **(c) Configuration.** The kit's service runs with `cluster.iceberg.format-v3.enabled` on (commit
    `19a63d10`). The default deployment's IRC conformance is not what is ratcheted.
- **Sample of 24 filings checked against `CatalogTests.java` / `ViewCatalogTests.java` in the fork.**
  - **Hold:**
    - `testUpdateTableSchemaServerSideRetry` and `testUpdateTransaction` (both reach `updateSpec().addField(bucket)`);
    - `testUpdateTableSpec`;
    - `testUpdateTableSchemaConflict` (`deleteColumn("data")`);
    - `testUpdateTableSchemaThenRevert`;
    - `testRemoveUnusedSchemas` (the create with SPEC succeeds, as `testCompleteCreateTable` shows, then
      `deleteColumn`);
    - `testListNamespaces` (`createNamespace` is the first non-read);
    - `testRegisterTable` (`dropTable(TABLE, false)` then register of the old metadata);
    - `dropNonEmptyNamespace` (genuinely asserts absent and then present with the flag false: a real kit
      self-contradiction);
    - `listViews` (lists `ns1` never created);
    - `renameViewSourceMissing` (no view rename route);
    - `basicCreateView` (implicit namespace via a view);
    - `createTableTransaction(int)[1]`;
    - `createViewThatAlreadyExistsAsTable` (asserts the message `"Table with same name already exists: ns.table"`,
      so it is a message filing);
    - `concurrentReplaceViewVersion`, `completeCreateView` (`withQuery("spark", ...)` plus another dialect);
    - RTAS group (`replaceTransaction` paths);
    - `testCompleteCreateOrReplaceTransactionReplace`;
    - `testSetNamespaceProperties` (assumption);
    - `testNamespaceWithDot`, `testTableNameWithDot` (flags).
  - **Wrong or doubtful:**
    1. **Misfiled as "kit assumption, message text".** `testLoadTableWithMissingMetadataFile(Path) | FAILED | e`:
       the reason itself says "the server answers 500". A 500 on a load is not a message-text difference and not a
       reference-implementation assumption. It is the server failing to map a missing metadata file
       (`NotFoundException`) to 404. It belongs in class d (defect), the class that is empty. The prompt's grouping
       "message text 9" = MSG 8 + this one, so that count is off by one.
    2. **Inaccurate reason.** The SPEC group says "partition evolution and bucket specs are refused 400", but
       `testCompleteCreateTable` creates `withPartitionSpec(SPEC)` (`bucket("id", 16)`, `CatalogTests.java:128-129`,
       `716`) and passes. Only evolution is refused.
    3. **Self-contradiction in the flag subclass.**
       `OpenHouseRestCompatibilityKitViewCatalogTests.requiresNamespaceCreate()` is documented "a database comes
       into being with its first view", while all 24 VIEWDB entries say "a view cannot create its database (404
       NoSuchNamespace)". One of the two is false. If views should create their database as tables do, the 24 are
       an implementation gap of the view route (class c or d), not a "model difference" (class b).
    4. **Doubtful.** `testLoadNamespaceMetadata` is filed as "namespace create or drop is not served (406)", but its
       first assertion is `loadNamespaceMetadata(NS)` throwing `NoSuchNamespaceException` with
       `"Namespace does not exist: %s"`. Whether it fails there (a message filing) or at `createNamespace` cannot
       be told without running.
- **History.** Every change to the list is in five commits (`36434a95`, `904b4c0d`, `c104ddb3`, `19a63d10`,
  `5cf98a45`), each with a stated reason. The only class-d entry ever listed, HTSKEY
  (`renameTableTargetAlreadyExistsAsView`: "rename of a table onto a view's name succeeds: the in-memory house-table
  stand-in lacks the shared key"), left in `904b4c0d` because the test passed after the T5 stand-in rename change.
  That is a fixture fix, not proof the real route refuses.
- **Certainty.** Certain for items 1-3. Item 4 cannot be told without running.
- **Fix.**
  - Assert the total and the passing set too (a list of the 42).
  - Record the first failing assertion's class and message prefix per FAILED entry and compare it.
  - Refile the 500 as d.
  - Fix the SPEC wording.
  - Decide the view-creates-database question.

### D4. SHOULD FIX: the House Tables stand-in differs from the real store where tests depend on it

These are every code-level difference between the fixture stand-in and the real path. The real path is
`HouseTableRepositoryImpl` → House Tables service `UserTablesServiceImpl` / `UserTableHtsJdbcRepository`. The
stand-in is
`tables-test-fixtures/tables-test-fixtures-iceberg-1.5/src/shared/main/java/com/linkedin/openhouse/tablestest/HouseTablesH2Repository.java`,
abbreviated FX below. The services/tables test copy,
`services/tables/src/test/java/com/linkedin/openhouse/tables/e2e/h2/HouseTablesH2Repository.java`, is abbreviated
TS.

1. **Compare-and-set: faithful.**
   - FX `save` (lines ~129-158) compares `tableVersion` with the stored location, or with INITIAL_VERSION when
     the key is empty. That is exactly `UserTableVersionMapper.toVersion` (`services/housetables/.../UserTableVersionMapper.java:21-46`).
   - Atomicity comes from one JVM-wide `ReentrantLock SAVE_LOCK` (an interface constant). It is shared by *every*
     application context in the JVM (the Spark 3.5 itest runs two services in one JVM) and serializes all keys.
     The real store uses a per-row JPA `@Version`.
   - Rename does not take the lock (`saveAndFlush` direct). The real rename is one conditional `UPDATE`
     (`UserTableHtsJdbcRepository.java:314-328`).
2. **Wrong-type occupant.** The real store throws `AlreadyExistsException` before the version check
   (`UserTablesServiceImpl.persistTypedEntity`); both reach the client as 409. FX: "Any occupant counts" and it
   gives a version mismatch, also 409. Same status. But FX does not stamp the entity type on `save`: a table
   saved with the version of a view at the key overwrites the view row. The real store refuses it.
3. **Rename.**
   - **Missing or view source.** Real: `renameTableId(...) == 0` → `NoSuchUserTableException` (404) →
     `HouseTableNotFoundException`. FX: `findById(fromKey).ifPresent(...)`, a silent no-op.
   - **Destination database.** Real keeps `fromDatabaseId` ("Use fromDatabaseId for destination db"). FX keeps the
     source database too, but checks occupancy at `toDatabaseId.toTableId`. In a cross-database rename it checks the
     wrong key, and `saveAndFlush` of the renamed entity onto an occupied `fromDb.toTable` is a JPA merge that
     silently overwrites the occupant. TS instead moves the row to `toDatabaseId`.
4. **Delete.**
   - The purge flag is now right in both: `!purge` = soft. The real client sends `!purge`
     (`HouseTableRepositoryImpl.java:234-243`); main's fixture had it inverted. T5 fixed it.
   - **Missing row.** Real `deleteTableById == 0` → 404. FX: silent no-op.
5. **Restore.**
   - **Occupant present.** Real: `AlreadyExistsException` (409). FX: `saveAndFlush(stored(restored))`, a JPA merge
     that silently overwrites the live table with the soft-deleted copy.
   - **Missing soft-deleted row.** Real: `NoSuchSoftDeletedUserTableException` (404). FX: silent no-op. TS throws
     `NoSuchUserTableException`.
   - The T5 merge message says "Rename and restore stay unchecked in the stand-in, as in the real store". That is
     false for restore.
6. **Soft-deleted listing (`searchSoftDeletedTables`).**
   - Real: a SQL filter with `upper()` on both ids, sorted, paged by Spring.
   - FX: in-memory static `HashMap` shared across all contexts in the JVM and never cleared; `databaseId` compared
     case-*sensitively*; iteration order unspecified (no sort); and `new PageImpl<>(content, PageRequest.of(page,
     numPages), total)` reports the page *size* as the number of pages (a pre-existing bug, not fixed by T5).
7. **Purge.** FX compares ids case-sensitively; the real query `deleteByDatabaseIdTableIdPurgeAfterMs` is not.
8. **Case sensitivity.**
   - Real: case-insensitive everywhere (`IgnoreCase` finders, `upper()` queries, MySQL `utf8mb4_0900_ai_ci` per
     the repository comment).
   - FX: case-insensitive reads and compare-and-set, but H2's primary key is case-sensitive. A save of `DB.T`
     carrying the version of `db.t` inserts a second row, and the next `IgnoreCase` finder then sees two rows.
   - TS: `findStoredLocation` uses `h.databaseId = :databaseId` (case-sensitive), so a create of `DB.T` over
     `db.t` is accepted as a new row. The real store answers 409.
9. **Length limits.**
   - Real schema: `database_id`/`table_id` VARCHAR(128), `metadata_location` VARCHAR(512)
     (`services/housetables/src/main/resources/schema.sql:3-6`, the same at `bb8929e1`).
   - FX/TS: H2 generated from the `HouseTable` entity (`iceberg/openhouse/internalcatalog/.../model/HouseTable.java`,
     no `@Column` lengths), so 255 for every string.
   - The stand-in accepts 129-255-character ids that the real store refuses, and refuses 256-512-character
     locations that the real store accepts. PR 108 built a 90-character temp-directory guard on the false premise
     "the real store's column is also 255".
10. **Error statuses.**
    - A too-long value in FX raises H2's `DataIntegrityViolationException` straight out of `saveAndFlush`. It
      reaches the tables service as an unmapped runtime error (503, `CommitStateUnknownException`, per PR 108's
      own text).
    - The real store maps `DataIntegrityViolationException` to `EntityConcurrentModificationException` (409,
      `UserTablesServiceImpl.persistTypedEntity` catch).
    - The client paths for 400/401/403/429 (`HouseTableCallerException`), 5xx and DNS
      (`HouseTableRepositoryStateUnknownException`, retried) are never produced by the stand-in, so the retry and
      state-unknown branches of the commit path are untested end to end.
11. **Storage type.** Real defaults `storageType` to `hdfs` (mapper and DDL). FX leaves it null.
12. **H2 result reuse (PRs 148 and 150).**
    - The fix is a `@PropertySource("classpath:tables-test-fixtures-h2.properties")` that sets
      `spring.datasource.url`. `@PropertySource` has the lowest precedence, so any consumer that sets
      `spring.datasource.url` (an `application.properties` on its test classpath, a `-D` flag) silently loses
      `OPTIMIZE_REUSE_RESULTS=FALSE`, and the stale-row behavior returns.
    - Nothing asserts the effective URL. The reproduction test builds its own context, so it would not see the
      override.

- **Certainty.** Items 1-11 are certain from reading. That FX's merge overwrites in items 3 and 5 assumes standard
  Spring Data `save` merge semantics; certain for a `saveAndFlush` on an entity whose primary key exists.
- **Fix.**
  - One stand-in, not two.
  - Throw `HouseTableNotFoundException` and `HouseTableConcurrentUpdateException` where the real store answers 404
    and 409.
  - Give the H2 schema the real DDL (load `schema.sql`, or add `@Column(length=...)` in the fixture's mapping).
  - Map `DataIntegrityViolationException` to the conflict.
  - Make the soft-deleted store per context.
  - A parity test: run one table of operations through FX and through the real House Tables service in
    `services/housetables` tests (embedded MySQL or H2 with the real DDL), and diff the results.

### D5. SHOULD FIX: the delta-harness, the only recording of main's behavior, has not run since the flip

`integrations/spark/delta-harness/BASELINE-2026-11-22.md` records counts and non-passing cases at `bb8929e1`
(2574/2792 cases). It is "on demand", not in CI or settings. The records show one 1.12 run before the flip
(`docs/plans/irc/records/i6f-optin-full-regression.md`, six differences) and "The full harness was not run" at the
flip (`records/v9a-flip-prep.md:48`). About 30 merges have landed since. The script was edited for the flip
(`98328ea2`) but its new form is not evidenced as run.

- **Certainty.** Certain from the history and notes.
- **Fix.** A scheduled (nightly) job that runs it on both lines and diffs against the baseline file.

### D6. SHOULD FIX: LinkedIn's pull request check names and runner cost change

`pr-validations.yml` now calls `split-build.yml` twice (default and reference lines): about 14 jobs where main had
one. The job "build-run-tests / Build and Run Tests" no longer reports on pull requests. Any branch protection on
`linkedin/openhouse` that requires that check would block every pull request. PR 133 says no rule depends on it,
but it checked only the fork ("the one ruleset covers main and requires no status checks").

- **Certainty.** The name change is certain. LinkedIn's protection cannot be told from here.
- **Fix.** Keep a final aggregating job named `Build and Run Tests` that `needs` all groups. Make the reference line
  optional upstream.

### D7. MINOR: the partition checker never runs in CI

`scripts/ci/check-test-groups.py` (dry runs of `build` versus the union of groups) is run only by hand; PR 140's
evidence lists one manual run. The partition is sound by construction: `only` gives `:p:check`; `except` gives
`clean build -x :p:check`; `-x` keeps a dependency another task still needs; tasks hung on `build` or `assemble` of
a matched project still run in `rest`. So the risk is low.

There is one silent case: a matched directory with no project gives `help` and a green job
(`scripts/ci/test-groups.gradle:46-48`). On the default line that happens only if `settings.gradle` stops including
the project, which also drops it from `build`, so the old single job would not have run it either. Not a hole.

- **Fix.** Run the checker in the `plan` job (a few minutes of dry runs), or drop the claim that it guards anything.

### D8. MINOR: the grant tests assert a privilege model the tests invented

`integrations/spark/spark-3.5/openhouse-spark-itest/src/shared/authorization/java/com/linkedin/openhouse/spark/grants/GrantStoringAuthorization.java:48-63`
maps roles to privileges ("this is the reading of the role names ..."). The N10 and T6 effect tests
(`IcebergRestGrantsUnlockSqlTest`) and s17 pin that model, not the deployed OPA policy. Grants are keyed by
`db.table` and survive DROP TABLE and recreate in the stand-in.

The backend is not reachable from production wiring: it is only in test source sets and the Spark 4.2 itest's
`service` source set, which is not published. PR 140 documents the model gap.

### D9. MINOR: a line-gated skip that catches any exception

`apps/spark-3.5/src/test/java/com/linkedin/openhouse/jobs/util/TableStatsFormatV3TypesTest.java:48-53` skips on
*any* `RuntimeException` from `SchemaParser.fromJson`. On the default line a regression in parsing would skip,
not fail. Other line gates key on class presence or `IcebergLine`, which is better.

- **Fix.** `assumeTrue(IcebergLine.current() == V3)`, then let the parse throw.

### D10. MINOR: `print-failed-tests.py --exclude restCompatKitTest` hides kit failures

When the kit's own `doLast` ratchet fails, its message is in the Gradle log, but the per-test listing at the end
is suppressed (`split-build.yml`, `build-run-tests.yml`). That is acceptable by design; noted only because a kit
regression is then visible only through the Gradle exception text.

## 3. Behavior changes against LinkedIn main

Every file in my area that existed at `bb8929e1` and was modified, deleted or moved
(`git diff --diff-filter=MDR -M bb8929e1..HEAD` over `.github`, `scripts/ci`, `tables-test-fixtures`, the Spark 3.5
itest build file, and the services/tables stand-in) is accounted for below. New files (`split-build.yml`,
`post-merge-build.yml`, composite actions, `scripts/ci/*`, the kit module, the delta-harness, smoke sources) are new
and only run on this branch's triggers, except where a row says otherwise.

| # | What existed (main) | After (HEAD) | PR | Default on | Justification in code or notes | Documented for follow-up | Upstream accepts as is | Restore or gate |
|---|---|---|---|---|---|---|---|---|
| 1 | `build-tag-publish.yml` + `build-run-tests.yml`: release builds, tags, publishes jars, images and PyPI on main | Same file, but `./gradlew publish` stops at configuration (fork not published in that job; then the remote-publish guard, `gradle/iceberg-fork.gradle:23-29, 72-86`), **after** the tag is pushed; images and PyPI never run | 118 (flip) via iceberg-fork.gradle; release path through `build-run-tests.yml` edited by 90/133/138 | Yes, on main | `docs/development/iceberg-fork.md:101-102` "there it would now stop at this check"; D7 | Work item I6d (hosting the fork) | No | SMALL: publish from `-PicebergLinkedIn`, or tag after a successful publish |
| 2 | `build-run-tests.yml`: one job, `clean build`, compose up, `sleep 30`, Python integration test with the Iceberg 1.2 fixture token, compose down (`bb8929e1:.github/workflows/build-run-tests.yml`) | Compile step, `build --continue` tee'd under `pipefail`, composite `default-build-deployables` (shadowJar, dockerPrereqs, jobs-scheduler image check, health-poll wait, integration test with the 1.5 fixture token, iceberg-core-ohfork check), failed-test printer, report upload, `gradle-cache-write` input | 90, 133, 138 (and 111/118 for the fork) | Yes (release) | Comments in file; PR 133/138 messages | — | Mostly yes; the `-ohfork` jar check is fork-specific | SMALL |
| 3 | `pr-validations.yml`: pull requests run `build-run-tests.yml` (one job, check "build-run-tests / Build and Run Tests") | `split-build.yml` twice: default line (4 groups + Docker + plan) and reference line (`-PicebergLinkedIn`); check names change; about 14 runners | 111, 133 | Yes, every pull request into main | File comments; PR 133 "Nothing depends on the old check names" (fork only) | — | No (cost, names) | SMALL: an aggregating job with the old name; reference line optional |
| 4 | `dataloader-tests.yml`: PRs into main touching `integrations/python/dataloader/**`; one job: `make sync verify`, `./gradlew clean build shadowJar -x test`, compose, Livy poll, `make integration-tests` with the 1.2 token | Two jobs (unit; integration with fork action, `shadowJar dockerPrereqs`, stricter readiness of HDFS, Livy and the worker, timeouts, diagnostics); extra PR paths (`*.Dockerfile`, compose `common/**` and `oh-hadoop-spark/**`, `scripts/iceberg-fork/**`, the workflow, the action); also PRs into irc-integration, push to irc-integration, dispatch; concurrency group per event; `clean build -x test` no longer run, so no checkstyle or compile of all modules in that job | not in my list (Dataloader work); fork action from 111 | Yes for PRs into main | File comments | — | Mostly; the new paths fire it on more LinkedIn pull requests | SMALL |
| 5 | `renovate.json`/`renovate.yml`: keep `iceberg_1_2_version` within 1.2.x and `iceberg_1_5_version` within 1.5.x | Only 1.5.x (the 1.2 rule and matcher removed); the 1.5 bump now affects only the reference line, since the default build overrides `iceberg_1_5_version` with the fork version | 118 | Yes (scheduled) | Comment "the reference line of the build" | — | Only if the 1.2 line's deletion is accepted | SMALL (tied to row 6) |
| 6 | Published module `tables-test-fixtures_2.12` (`:tables-test-fixtures:tables-test-fixtures-iceberg-1.2`, Spark 3.1 / Iceberg 1.2) | Deleted (`dec16c80`); its sources moved to `tables-test-fixtures-iceberg-1.5/src/shared` | 110 (move), 118 (delete) | Yes (artifact gone) | Part of deleting the Iceberg 1.2 / Spark 3.1 line | — | No, without a deprecation | FOUNDATION: restoring it means restoring the 1.2/3.1 line (another area) |
| 7 | Published `tables-test-fixtures-iceberg-1.5_2.12` stand-in: `save` without a version check; `deleteById(key, isSoftDelete)` treating `true` as soft (inverted against the real client, which passes `!purge`); rename overwrote an occupied key; Spring Boot's generated embedded H2 URL | Compare-and-set on the metadata location (`HouseTableConcurrentUpdateException`); `purge=true` is a hard delete, soft delete stamps `deletedAtMs` and `purgeAfterMs`=+7 d; rename onto an occupied key throws; restore and rename write through `saveAndFlush(stored(...))`; `@PropertySource` sets `spring.datasource.url=...;OPTIMIZE_REUSE_RESULTS=FALSE` with a random name; JVM-wide `SAVE_LOCK` | 83, 148 | Yes, for every downstream consumer of the published fixture | Class comment (lines 30-54); properties file comment | — | Probably yes (fidelity fixes), but downstream tests that saved without a version now get 409, and consumer tests that set `spring.datasource.url` silently drop the fix | SMALL |
| 8 | `IcebergViewBeanBoundaryTest` (811 lines, 12 tests, Iceberg 1.2 fixture tests, also compiled by the 1.5 fixture) | Deleted; a 142-line, 3-test version in `src/shared/test` plus `IcebergRestRoutesBoundaryTest` (5 tests) | 106, 110, 112, 120, 127, 139, 142, 149 (others' areas) | Test-only | Removed tests pinned the Iceberg 1.2 guards (`ViewsDisabledService`), removed with V18/V22 | — | — | Not a product change (checked by reading the commit subjects and the remaining tests) |
| 9 | Fixture `build.gradle` (1.5): `srcDirs += project(':...iceberg-1.2').sourceSets...` | `srcDirs += 'src/shared/...'` | 110 | — | Comment | "can be folded into src/main" | — | Refactor; same classes in the jar (the moved files are 0-line renames, checked with `--stat -M`) |
| 10 | Moved fixture classes: `OpenHouseLocalServer`, `OpenHouseSparkITest`, `SparkItestColumnDefaults`, `TestSparkSessionUtil`, `ToggleH2StatusesRepository`, `annotation/*`, `dummy.token`, `OpenHouseLocalServerTest`, `TomcatServerBootTest` | Same content in `src/shared` | 110 | — | — | — | — | No change (100% renames) |
| 11 | `SpringH2TestApplication` (fixture) | `+@PropertySource` (row 7) | 148 | Yes (consumers) | Row 7 | — | — | SMALL |
| 12 | `HouseTablesH2ViewAccessorTest` (fixture test) | Moved, +21 lines (INITIAL_VERSION on setup rows) | 83, 110 | Test-only | T5 `60806553` | — | — | None |
| 13 | services/tables `HouseTablesH2Repository` (test) | Compare-and-set, delete semantics, rename occupancy, `findStoredLocation` (case-sensitive) | 83, 106 | Test-only | Class comment | — | — | None (but see D4) |
| 14 | services/tables `SpringH2Application` (test) | `+@PropertySource` (H2 reuse off) | 150 | Test-only | Properties comment | — | — | None |
| 15 | Spark 3.5 itest `build.gradle`: test sources = own + the Spark 3.1 itest's with 7 exclude patterns | `src/shared/test`, `src/shared/smoke`, `src/shared/authorization`; `iceberg-data` (and on the default line `iceberg-orc`/`iceberg-parquet`) excluded from the test runtime classpath; outcomes directory and line system properties | 80, 110, 115, 140 | Test-only | Comments | — | — | None; the excluded jars mean the itest's classpath is not the deployed one, already the case for apps |

Off-path check: none of the new workflows runs on `linkedin/openhouse`'s main except rows 1-4. `post-merge-build.yml`
and the dataloader push trigger are scoped to `irc-integration`. Unchanged LinkedIn workflows: `build-tag-publish.yml`
(content), `renovate.yml` (comment only, plus row 5).

Permissions:
- `pr-validations.yml`: no top-level `permissions` on main or now.
- `split-build.yml`: every job sets `contents: read`, narrower than main.
- `post-merge-build.yml`: `contents: read`.
- Dataloader integration job: `contents: read`.

No workflow added a secret reference or `pull_request_target`.

## 4. Cruft

**Two implementations of one thing**
- Two House Tables stand-ins that diverge:
  `services/tables/src/test/java/com/linkedin/openhouse/tables/e2e/h2/HouseTablesH2Repository.java` (357 lines) and
  `tables-test-fixtures/.../tablestest/HouseTablesH2Repository.java` (349 lines). They differ on case sensitivity,
  cross-database rename and restore of a missing row (D4). Keep one, in the fixture.
- Two copies of `HouseTablesH2ReadAfterCommitTest`, 183 and 182 lines, 62 diff lines apart
  (`tables-test-fixtures/.../HouseTablesH2ReadAfterCommitTest.java`,
  `services/tables/.../e2e/h2/HouseTablesH2ReadAfterCommitTest.java`). They also add two 3000-commit loops to every
  build. Keep one.
- Two copies of the properties file (`tables-test-fixtures-h2.properties`, `tables-service-test-h2.properties`).
  Merge with the stand-in.
- Two `ServiceWarehouse` classes
  (`integrations/java/openhouse-rest-compat-kit/src/service/java/.../ServiceWarehouse.java`, 66 lines;
  `integrations/spark/spark-4.2/openhouse-spark-itest/src/service/java/.../ServiceWarehouse.java`, 63 lines;
  44 diff lines). Share one test helper.
- Two mechanisms for "this path or line differs": `Difference` in the smoke suite and about 32 ad hoc
  `IcebergLine.current()` branches in test classes. Fold them into one reviewed mechanism (D2).
- `build-run-tests.yml` (single job, release only) next to `split-build.yml`. They share the composite action, but
  the compile, test and print steps are written twice. Either make the release call `split-build.yml`, or keep one
  and drop the claim that they are "the same tasks".

**Comments that narrate history or cite private ids** (counted by `git grep` for work-item, decision or PR ids):
- Spark 3.5 itest sources: 63 lines.
- Spark 4.2 itest sources: 45 lines.
- Kit: 6 lines.
- Fixtures: 2 lines.
- `gradle/iceberg-fork.gradle`: 3 lines ("decision D7; hosting it is work item I6d").

Examples:
- `SparkSqlSmokeScenarios.java` javadoc "(work item T1)", "(work item N7)", "(work items C3 and C4)".
- `SparkSqlSmokeTest.java` "Decision D14b", "Work item C3: ... put to a decision".
- `expected-outcomes.txt` reasons "until work item X5", "(D30)".
- `split-build.yml:15-17` "The single job took 52 minutes on a pull request and up to 67 after a merge on 2026-11-24".
- `post-merge-build.yml:12-15` "Until 2026-11-24 each line was one job ...".
- `test-groups.py:17-19` "on the pull request run of 2026-11-24".
- `tables-test-fixtures-iceberg-1.5/build.gradle:14-16` "until that project was deleted. ... can be folded".
- The Spark 3.5 itest `build.gradle` "until that project was deleted".

Replace them with what the code does; move the timings to the PR.

**Leftovers and naming by work order**
- `src/shared/{main,test}` in the fixture and `src/shared/test` in the Spark 3.5 itest exist only because the
  Iceberg 1.2 / Spark 3.1 twins once compiled them. Fold them into `src/main` and `src/test`.
- The smoke DB name `smoke_t1` and constants named after work items ("T1") are names by work order.
- The `-PicebergFork` deprecated no-op (`settings.gradle:33-43`) is a straddle leftover. It is outside my area, but CI
  no longer passes it.

**Test-only code in main source sets**
- The `tables-test-fixtures` "main" source set is a test-fixture artifact by design. Its new concurrency lock
  `SAVE_LOCK` and the H2 properties are published to consumers (row 7). Acceptable for a fixture jar, but the static
  lock and the static soft-deleted map are JVM-global state in a published library. Make them per context.
- No test seam was added to production classes in my area. `HouseTableRepositoryImpl.writeRequestTimeout()`
  `@VisibleForTesting` is pre-existing at `bb8929e1`.

**Switches that guard nothing**
- `tasks.matching { it.name in ['spotbugsService','checkstyleService'] }.configureEach { enabled = false }` (kit and
  Spark 4.2 itest) and `spotbugsSmoke` disabled: the static analysis of those source sets is off with no stated
  reason. MINOR.

## 5. Tests

**Claims no test would catch if false**
- PR 80, 146 and the docs: "Spark SQL behavior is preserved": there is no stored baseline of main (D2).
- PR 133 and 144: "the jobs together run every task of `clean build`": only `check-test-groups.py`, run by hand
  (D7).
- PR 108: "the real store's column is also 255 characters": false per `schema.sql` (512). No test compares the
  stand-in schema with the real DDL.
- PR 83: "Rename and restore stay unchecked in the stand-in, as in the real store": false for restore, which the
  real store checks for occupancy with 409 and for absence with 404 (D4.5).
- PR 148: "That production is unaffected is inferred from the real store not being H2" is stated honestly as an
  inference. No production-side read-after-commit test exists.
- PR 146: "no lost update in 13 races per path". The readbacks were "not checked against an injected lost update"
  (its own known gap), so the s33 readback has never been seen to fail.
- PR 104 and the kit list: the reason column. Nothing checks that a FAILED test fails for the listed reason (D3).
- PR 140: "a grant has an effect" holds only for the test backend's role model (D8).

**Assertions that cannot fail or pin fixtures, not product**
- `CatalogPluginContractTest` grants section (`...src/shared/test/java/.../CatalogPluginContractTest.java`, about
  lines 505-515) asserts `getTableAclPolicies` and `getDatabaseAclPolicies` are empty after granting. That is the
  fixture's `OpaAuthorizationHandler` without OPA dropping grants, not OpenHouse behavior. It still holds on the
  contract suite's service because T6 swapped the backend only for the smoke and grants services.
- `succeeds()` is the expectation of 114 of about 250 smoke `sql()` calls. Most are writes followed by a read that
  is asserted, so this is acceptable.
- The kit `test` task uses `failOnNoMatchingTests = false` and runs nothing by design.

**Pinned defects (expected-wrong behavior)**
- `CommitPathStateRulesTest:210-227` (NPE on a lockState PUT) and `CommitPathSnapshotsTest:193` (NPE).
- `CommitPathAuthorizationTest.pinnedDefectCreateInARenamedTablesDirectoryNeedsOnlyTheCreatePrivilegeOnTheDatabase`
  (N5: a create can take a renamed table's directory with only CREATE_TABLE on the database).

All are main's behavior, labelled PINNED DEFECT, and still right as pins of main. The N5 one is a security and
data-loss defect of main that the native API still has.

**Declared differences per path (smoke)**

| Path | Default (1.12) line | Reference (1.5.2) line |
|---|---|---|
| legacy | 2 | 0 |
| rest-shim | 4 | 2 |
| rest-http | 24 (9 + 2 s32 + 13 s33), the same 24 on both lines | 24 |
| Spark 4.2 | 26 (11 + 2 + 13) | not built |

I checked 16 distinct reasons against code:
- s19 and s24 on 1.12: `OpenHouseCatalog.java:487` "Describing database ..." versus `:510` "Checking if database
  exists ...".
- rest-http and Spark 4.2 s19, s24 ALTER and s24 DROP: no `POST /namespaces`, no `.../properties`, no
  `DELETE /namespaces/{ns}` mapping in `services/tables/.../api/irc/*Controller.java`.
- s24 DESCRIBE NAMESPACE and SHOW NAMESPACES: the GET namespace and namespaces mappings exist
  (`IcebergRestCatalogController.java:58,67`).
- s25 ×2: `RESTSessionCatalog.java:1519` "Invalid namespace: %s".
- s27 and s29: capability client; `contains` matching makes the "Malformed request: " prefix harmless.
- s22 and s26: reproduced on Iceberg's Hadoop catalog by `Spark42WithoutOpenHouseTest.java:31-60`.
- s33 ×13: `UpdateRequirements.java:45,55` only `AssertTableUUID` for property updates.
- s32 ×2: Iceberg's client-side staged replace.

All 16 hold.

The mechanism is strict per statement:
- it quotes the baseline exactly;
- it fails if the outcome equals the baseline;
- it fails if the declaration is unused for a run scenario (`endSuite`);
- an expectation never holds on a failed statement except `fails(...)`.

A new difference still needs no review: any pull request can add a `Difference.on(...)` with a free-text `kind`
such as "(d) IRC path, not decided" (8 of Spark 4.2's 26 and the s32/s33 entries use it). And editing the scenario's
baseline literal (D2) bypasses the mechanism entirely.

**Skipped, assumed away, or never run**
- About 40 `assumeTrue(IcebergV3.supported())`, 5 `assumeTrue(IcebergLine.current() == V3)` and several
  `assumeTrue(served())` (class presence): all skip on the reference line by design and run on the default line.
- `TableStatsFormatV3TypesTest` skips on any exception (D9).
- The kit's 23 SKIPPED (22 by flags, 1 `@Disabled` by the kit).
- delta-harness: not in CI, 11 known-bug cases skipped by its own `Plan.knownBugs`, no full run since the flip (D5).
- PR 148's evidence: the jobs suite has "91 skipped" on the 1.5.2 line (the v3 assumptions).
- Spark 4.2 `test` excludes `spark42.extensions.*` (they run in `extensionsTest`; fine).

## 6. Per pull request

| PR | Leaves-true claims hold? | Why |
|---|---|---|
| 80 (T1 smoke) | Partly | The suite runs and its original 21 scenarios were written on unchanged code; "records what Spark SQL does today" was true then. It is not a stored baseline, and later scenarios and edits are not of main (D2) |
| 81 (T3 contract) | Yes | 18 tests written on unchanged code; later edits only add a path (`d742493a`), scenarios (`1598eb5b`) and a per-line branch (`786a9a88`, which accepts a default-build change: a 422 on create with the capability) |
| 82 (T4 commit path) | Yes | The seven files are nearly untouched since (`60806553` setup fix, `5246b357` adds a pin); pinned 500s and NPEs are documented |
| 83 (T5 stand-in) | Partly | Compare-and-set and delete semantics are faithful; "rename and restore unchecked as in the real store" is wrong for restore; several infidelities remain (D4); "both copies" were made to diverge |
| 85 (T2 delta-harness) | Yes, then stale | The baseline was recorded at `bb8929e1`; not in CI by its own statement; no full run since the flip (D5) |
| 90 (post-merge) | Yes then; superseded | Now `split-build.yml` with `contents: read`; "newer push cancels older" was replaced by 93 and 144 |
| 93 (finish running build) | Yes then; superseded | 144 changed the group to per-commit with no cancel |
| 104 (V13a kit) | Partly | The two-way ratchet on outcome holds; "the gap is a reviewed list" holds for names, not causes; filings wrong in places (D3); measured under v3 on since `19a63d10` |
| 108 (short warehouse) | Partly | The short temp directory works, but its premise "the real store's column is also 255" is false (512); the 503-versus-409 status is a stand-in artifact |
| 111 (both builds on PRs) | Yes | `pr-validations.yml` runs both lines; fork cache logic as described (`.github/actions/iceberg-fork/action.yml`) |
| 115 (T10) | Yes | Excludes removed; the three Spark 3.1-only tests were since ported (`PartitionTestSpark3_5.java:53,110`, `AlterTableTestSpark3_5.java:54`) |
| 133 (split) | Partly | The partition is sound by construction; the checker is not in CI; "nothing depends on check names" was checked on the fork only (D6) |
| 138 (print failed) | Yes | `tee` under `pipefail` keeps Gradle's status; the printer exits 0 and only prints; self-test in `plan` |
| 140 (T6, N10) | Partly | Grants are stored and observed; the effect is of a test-invented role model (D8); the backend is not in production wiring (checked) |
| 144 (post-merge split) | Yes | One run per commit, `rest` the only cache writer, `iceberg-linkedin-build.yml` deleted; "release untouched" is true, but the release is broken by the flip (D1) |
| 146 (C3, C4) | Yes, with its gaps | Counts match the code (legacy 2/0, rest-shim 4/2, rest-http 24, Spark 4.2 26; 13 races); the lost-update readback was never shown to fail |
| 148 (X16 H2 reuse) | Yes | Fix and reproduction as described; `@PropertySource` precedence weakness (D4.12) |
| 150 (X18) | Yes | Same fix in the services/tables copy; duplicates the reproduction test (cruft) |

## 7. For the clean re-implementation

**Keep (essential design)**
1. A Spark SQL scenario list that depends only on `spark.sql` and JUnit, one source shared by every engine line.
2. A per-path `Difference` declaration that must quote the baseline, must really differ, and must be consumed. Add
   to it a **stored baseline file recorded from LinkedIn main**, compared on every build of the reference line.
3. A per-statement outcomes TSV, but committed as the baseline, not only written to `build/`.
4. Black-box commit-path tests at the HTTP boundary (T4), with explicitly labelled pinned defects.
5. A catalog-plugin contract suite through public Iceberg interfaces (T3), parameterized by catalog path.
6. Apache's REST compatibility kit with an expected-outcomes ratchet that is two-way on name and outcome. Add the
   first failing assertion's type and message prefix, a passing list, and runs at the default configuration and at
   the v3-on one.
7. One House Tables stand-in with the real DDL lengths and case-insensitive keys, compare-and-set, 404/409 parity,
   and a parity test against the real service.
8. A test authorization backend that stores grants, behind a Spring `@Primary` test bean, kept out of production
   source sets.
9. CI split by project inside Gradle (an init script with `only`/`except`), the partition checked by a dry run *in
   CI*, and one composite action for the Docker steps.
10. Console tee under `pipefail`, plus a failed-test printer that reads the console when the JVM died.
11. A post-merge run per commit with no cancellation, and one Gradle cache writer per line.
12. A release workflow that publishes only from a line whose dependencies are resolvable, with the tag pushed after
    a successful publish.

**Do not carry over**
- Baselines edited in place; `IcebergLine` branches in test bodies; free-text difference "kinds" with no review.
- Two stand-in copies, two reproduction tests, two `ServiceWarehouse` classes.
- `src/shared` directories that exist only for deleted twins.
- Comments with work-item or decision ids and dated timing narratives.
- A JVM-global `SAVE_LOCK` and a static soft-deleted map in a published fixture.
- `@PropertySource` for a setting that must hold.
- Catch-any-exception assumptions.

**Proposed commit sequence for upstream** (each commit reviewable alone; behavior-preserving first)
1. CI: tee Gradle output under `pipefail` and add the failed-test printer with its self-test (no task changes).
2. CI: extract the Docker steps of `build-run-tests.yml` into a composite action, unchanged steps.
3. Tests: the smoke scenario list plus legacy-path runner on main, with the TSV of main's outcomes committed as the
   baseline and compared.
4. Tests: the catalog-plugin contract suite (T3) on main.
5. Tests: the commit-path suite (T4) on main, with pinned defects labelled.
6. Fixture: the stand-in gets the real DDL, compare-and-set, 404/409 parity, per-context state and one copy, plus a
   parity test against `services/housetables`. Flag it in release notes for fixture consumers.
7. Fixture: H2 result reuse off, set where it cannot be overridden silently (a `DataSource` bean or
   `SpringApplication.setDefaultProperties` asserted by a test).
8. Tests: the grant-storing test backend and s17's grant listing.
9. CI: the split build (init script, matrix, partition check in the `plan` job), with an aggregating job keeping the
   old check name.
10. (Behavior-adding, gated) The REST compatibility kit module and ratchet, only once the IRC routes exist.
11. (Behavior-adding) The reference-line job and the release change, decided together with hosting the fork (I6d).

## 8. Not checked

- I ran nothing (read-only brief): no Gradle, kit or harness runs. Every runtime claim is from reading code and
  notes.
- I read about 24 of the 118 kit filings against the kit source. The rest are taken by group.
- I did not read the full body of each smoke scenario s01-s33 against main's code paths, only the mechanism, the
  history of baseline edits, and 16 difference reasons.
- I did not check LinkedIn's actual branch protection on `linkedin/openhouse` (not reachable from here).
- I did not trace the real House Tables `SoftDeletedUserTableHtsJdbcRepository` queries line by line; the
  case-insensitivity of purge is inferred from the service's other queries.
- I did not check `print-failed-tests.py` parsing in depth beyond its exit behavior and its role.
- I did not check the Spark 4.2 itest's own suites (`SparkIrc*Test`) beyond the smoke wiring and the differences
  list.
- I did not check the delta-harness scenario code itself (5,800 lines, cherry-picked), only its wiring, baseline and
  run history.
