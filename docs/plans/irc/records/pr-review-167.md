# Review of pull request 167: the compatibility kit's list checks every test's cause, at two configurations

Reviewed head `67daf3e80e916c54aab17ba8a862a469a0571a50` (one commit; it was still the head on GitHub when this was written). Base `origin/irc-integration` `81cb4b74`, which is also the merge base. Worktree `/home/user/oh-kit`, read only.
CI run `46145450385`, "Default build, rest" job `140980434068`: **success**. The kit's summary in that log: default 160 tests = 52 passed / 92 failed / 16 skipped, "all 160 tests have the outcome and cause listed". v3 160 = 54 / 90 / 16, same message. The other jobs were still running when this was written, apart from the jobs that had already passed: Docker images and integration tests, test groups, and Reference spark-4.2-itest and apps-spark-4.2.

## 1. Verdict

**MERGE AS IS.** No finding blocks the merge. The ratchet does what the item asks, and I found no case where it stays green while conformance gets worse (attack 1). The two-configuration split is self-checking, the partition holds, there is no product change, and the private-id check passes. The findings below are about the *review of the causes*, which is the guide for what to build next, plus some stale comments. They can follow in a later pull request because none of them changes what the ratchet accepts.

The three things that matter most:
1. **The 24 VIEWDB tests are filed as a model difference (b), but on the `/iceberg` surface they are a server contradiction.** `HEAD /namespaces/ns` answers "exists" for a namespace that has only a record (`IcebergRestApiHandler.requireNamespace` / `hasRecordOrIsListed`). The view create in that same namespace answers 404 `NoSuchNamespaceException` "Database not found" (`ViewsServiceImpl.requireDatabaseExists`, `IcebergRestViewRegistration.register` at about line 145: both check only for tables and views). The kit's `requiresNamespaceCreate=false` on the view suite keeps the kit off this path, so the list never shows it. LISTNS (2) and KITNS (1) exist only because of that flag. That makes 27 tests with one root cause, which is FF-51.
2. **DIALECT (12), DIALECTRES (2) and VIEWERRTYPE (1) are gated behind VIEWDB.** Each of these tests creates its view in a namespace that holds no table, and the dialect check happens before the database check. Allowing trino alone would turn none of them green; they would move to VIEWDB. The author's table shows them as independent counts, which overstates any dialect-only work.
3. **The ratchet is sound.** A pass that turns into a failure, a new cause, a missing test, a new test, a changed skip, a container failure, a JVM that dies, a missing or empty list, a duplicate entry, a half-tagged entry, and the v3 switch silently not applying: each one fails the build. The weakest prefix is `Database not found: ns` (22 characters, 24 entries). It is specific to one server check, so it is not a real hole.

## 2. Findings

### F1. SHOULD FIX (record only): VIEWDB is classed b, but `/iceberg` contradicts itself on a record-only namespace
- **What goes wrong.** A client following the spec runs `createNamespace(ns)` and gets 200. Then `namespaceExists(ns)` answers true. Then `createView(ns.v)` answers 404 `NoSuchNamespaceException: Database not found: ns`. In Spark terms, `CREATE NAMESPACE x; CREATE VIEW x.v ...` fails on a namespace the server just said exists. That is a spec breach on a route the server serves (class d, by the list's own legend). It is not a model difference.
- **The list's reason:** `expected-outcomes.txt:33`: "the native view create needs a database that already holds a table or a view ... Whether a view should create its database ... is not decided; if it should, this is a gap of the view route (class c), not a model difference". This frames the question only as "should a view *create* its database" (the case the kit exercises under `requiresNamespaceCreate=false`). It leaves out the record case. The only place that case is written down is the view subclass's javadoc ("a namespace record does not make one, so creating the namespace first would not help", `OpenHouseRestCompatibilityKitViewCatalogTests.java`).
- **The code:**
  - `services/tables/src/main/java/com/linkedin/openhouse/tables/services/ViewsServiceImpl.java:466-474`: `findAllByDatabaseId(...) || findAllViewsByDatabaseId(...)`, otherwise `DATABASE_NOT_FOUND`.
  - `IcebergRestApiHandler.java` (around lines 452-475): "A namespace exists exactly when it holds something or has a record".
- **Certainty:** certain from reading. The fix (FF-51) is a native behavior change and is the owner's call. That is why this can wait.
- **Smallest fix:** in the VIEWDB reason, name the record case. Mark the category as an IRC-surface defect whose fix needs the owner's native decision, with LISTNS and KITNS as consequences of the flag the same rule forces. Optionally run the view suite with `requiresNamespaceCreate=true`. The kit would then exercise the spec path itself: LISTNS and KITNS would turn into VIEWDB failures or passes, and the contradiction would show in the list instead of a javadoc.

### F2. SHOULD FIX (record only): the review's counts hide that the view failures are one cluster
- **What goes wrong.** DIALECT (12) and DIALECTRES (2) all fail at the view create, on dialect validation. A structural check of all 14 kit test bodies in `ViewCatalogTests` (`buildView` before any `buildTable`) shows the namespace holds no table at that point. Allow trino, and every one of them then fails on "Database not found". VIEWERRTYPE (1) is the same: its duplicate-dialect step builds a view in `ns`, which holds nothing.
- **The other direction.** Among the 24 VIEWDB tests, four call `renameView` and would next hit VRENAME. `createOrReplaceView[1,2]` uses trino and would next hit DIALECT. `replaceTableViaTransactionThatAlreadyExistsAsView` may hit RTAS. So fixing VIEWDB alone turns perhaps 15 to 18 green, not 24.
- **Why it matters.** The pull request body offers the table as the guide for what to build next. The list's header says "a later step may fail for another reason once that one is gone", but the table does not apply that to the ordering.
- **Certainty:** certain about which tests come first (from the kit source). The exact number of next steps cannot be told without running.
- **Fix:** add one line to the category reasons or the documentation: "DIALECT, DIALECTRES and VIEWERRTYPE are reached only in a namespace with no table: behind VIEWDB".

### F3. MINOR: IDS (3) is filed as a model difference; it is better read as a cheap gap in the `/iceberg` layer
- `testLoadTableWithNonExistingNamespace` and `loadViewWithNonExistingNamespace` fail at their *first* call, `tableExists` or `viewExists`, which throws `BadRequestException` instead of returning false. So a client existence check (`spark.catalog.tableExists`, `IF NOT EXISTS` probes) throws for any name that is not an OpenHouse id. The 400 comes from reusing the native validator.
- The spec does allow 400 for "request validation failure", so b can be defended. But a name that cannot exist does not exist, and answering 404 on the `/iceberg` existence and load routes changes nothing native. Certainty: certain about the behavior; the classification is a judgment call.

### F4. MINOR: MISSINGMETA classed d is the item's instruction, but the spec would allow 5XX
- The kit expects the reference server's 404 `NotFoundException`. The spec lists 5XX as a valid answer, and the item (FF-25, from section 2f of the planning notes) told the author to refile it as d. The author did, and the reason text is honest ("loads as 500 ... the server does not answer 404 NotFoundException, which the kit expects"). Severity is low.
- A side note on a product behavior this pull request does not change: the 500 message carries the server's storage path (`Failed to open input stream for file:` followed by a path). The prefix stops at `file:`, as it should.

### F5. MINOR: stale or inaccurate comments and documentation
- `integrations/java/openhouse-rest-compat-kit/build.gradle:114`: "the kit runs only in restCompatKitTest". It now runs in two tasks.
- `expected-outcomes.txt:15-16` and `docs/development/iceberg-fork.md` ("for a failed assertion `java.lang.AssertionError`"): KITNS's throwable is `org.opentest4j.AssertionFailedError` (`expected-outcomes.txt:167`), which AssertJ's boolean assertions throw. The rule as written is wrong for that one entry.
- The per-category count comments (`# VIEWDB: 24 test(s)`, `# Passing at both configurations: 52 test(s)`, `# FMTV3: 2 test(s) at default, 0 with v3`) are hand-written and nothing checks them. The "Changing the list" section does not say to update them. They will drift. Either drop them (the green run prints the counts) or mention them.
- "Every run writes `tmp/<task>/actual-outcomes.txt`" (pull request body, docs): not when the outcomes file is missing. The task throws before writing it (`build.gradle:288-290`).

### F6. MINOR, not settled: SpotBugs reports 2 violations in `spotbugsService`
- Log of job `140980434068`, around line 1608: "2 SpotBugs violations were found" in `:integrations:java:openhouse-rest-compat-kit:spotbugsService`. It is advisory (`build.gradle:167`, `ignoreFailures = true`) and the build is green.
- On the base the task came FROM-CACHE, so I could not tell whether the violations are new (`RestCompatKitService.java` changed here). Reading `build/openhouse-rest-compat-kit/reports/spotbugs/service.xml` from a local run would settle it.

### Attack 1: can the ratchet be green while conformance gets worse? It cannot, for each case asked

| Case | What happens | Where |
|---|---|---|
| A passing test fails | "a regression" | `build.gradle:314-317` |
| A failing test fails for another reason (same type, other message; other type; failure in set-up) | Type compared exactly, message by anchored prefix (`lookingAt`): "for another cause than listed" | `:318-321`, `:149-152` |
| A failing test passes | "it passes now: list it as PASSED" | `:316` |
| A test disappears | "did not run" | `:312-313` |
| A new test | "not listed" | `:310-311` |
| A skip changes | SKIPPED entries carry a type and prefix too; ABORTED and disabled are recorded | listener `:56-63`, `:75-77` |
| A class is disabled, or a container (`@BeforeAll`) fails | Written as a container entry, which is a problem; its tests also show as "did not run" | listener `:57-60`, `:84-86`; build `:296-297` |
| Test JVM dies, or the service fails to start | The session listener throws, `testPlanExecutionFinished` never writes, "The kit wrote no outcomes" | `:288-290`; outcomes deleted in `doFirst` `:262-264`, so no stale file |
| Task restored from cache | `upToDateWhen { false }`, which disables reuse; the CI log shows both tasks executed | `:271` |
| Task not run | `check` depends on both, and CI's `rest` job runs `build`; only `-x` or a `--tests` filter would skip it (a filter would itself fail as "did not run") | `:363-365` |
| List missing, empty, duplicated, half-tagged, unknown tag, unused or undefined category | `readLines` throws on a missing file; empty means all "not listed"; the others are refused with a line number | `:170-239` |
| Gradle and the listener disagree on the count | Refused (only when `afterSuite` ran) | `:302-304` |

- **Prefixes.** 108 non-passing lines; none under 20 characters; 26 under 30.
  - `Database not found: ns`, 24 entries, VIEWDB: one product check, and it would also match `ns2`.
  - `Malformed request: Bad Request`, 2 entries, IDS: a HEAD has no body, so *any* 400 on a HEAD matches.
  - `assumption was not met due to: Expecting value to be true but was false`, 5 skips (DOT, SLASH, NOCREATE): any failing boolean assumption in those tests matches.
  - None of these can hide a regression elsewhere, because each test's earlier and later steps are other assertions.
- **Normalization.** All whitespace becomes single spaces, and the comparison is a prefix. It hides changes in whitespace and in anything after the prefix. The ` ... ` wildcard (SCHEMA, four entries, two wildcards each) hides the printed schemas but is anchored on the cause at the end.
- **What remains a hole, by design.** Only the first failing step of a listed test is checked, so a regression in a later step of a listed failure is invisible. This matters most for the views: only two view tests pass (`renameTableTargetAlreadyExistsAsView`, `replaceViewThatAlreadyExistsAsTable`), so the kit measures almost nothing of the view routes beyond their first refusal.

### Attack 3: the format v3 run
- **Is the switch really on?** The property goes build → `systemProperty 'rck.service.properties'` (`build.gradle:259`) → `RestCompatKitServiceListener` (one per line) → `RestCompatKitService` args → `--cluster.iceberg.format-v3.enabled=true`. The default run passes none.
- **Self-checking.** The two tagged tests (`createTableTransaction(int)[3]`, `testCreateTableWithDefaultColumnValue()`) are listed FAILED at default and PASSED at v3. Both runs using the same configuration, either way, would fail one task. CI shows 52 and 54 passed.
- **The stated reason is right.** At default, the server refuses format-version 3 at create with 400 "OpenHouse creates tables with format version 2". Nothing else differs, cause included (both runs green against one list).
- **Duration.** Kit tasks in this run: about 52 s (default, 12:38:30 to 12:39:22) and about 47 s (v3, 12:39:30 to 12:40:17). The base post-merge run's single kit task took about 58 s. Adding the second run costs about 50 s of a job that takes 4 min 15 s here (cached) and 12 min 58 s on the base post-merge run (less cached). It does not double the job.
- **Partition.** `restCompatKitFormatV3Test` matches `TEST_TASK` (`[A-Za-z]*Test`) in `check-test-groups.py`. Groups are by project directory and the kit's project is in `rest`, so the new task is in exactly one job, and CI shows both tasks in "Default build, rest". The kit is not in the reference build. I did not run the script, which needs Gradle.
- `mustRunAfter` keeps the two services from running at the same time under `org.gradle.parallel=true`.

### Attack 4: no product change
- Files in the diff that are neither tests nor build files: `.github/workflows/build-run-tests.yml` and `split-build.yml` (a comment, and `--exclude restCompatKitFormatV3Test`), and `docs/development/iceberg-fork.md`.
- `RestCompatKitService.java` is the kit's harness (`src/service` of the kit module), not product. Nothing under `services/`, `cluster/`, `iceberg/`, `apps/` or the fixtures is touched.

### Attack 5: interaction with pull request 168
- 168 edits the same argument list in `RestCompatKitService.java`: it adds `--cluster.tables.irc.enabled=true` next to the v3 line this pull request removes, so it is a textual conflict. 168 also sets `cluster.tables.irc.enabled=true` in `openhouse-tables-test-fixtures.properties`, which `SpringH2TestApplication` uses.
- **If the merge keeps the hard-coded v3 line:** the default run gets v3, the two FMTV3 tests pass, and the task fails.
- **If the routes end up off:** the catalog client's configuration fetch fails, so every test (or the container) fails, and the 52 listed passes go red. The task fails without even needing the cause check. Every listed failure's type would also change.
- So the kit cannot "pass" with the routes off. Whoever resolves the conflict should put `cluster.tables.irc.enabled=true` in the argument list, not in `kitConfigurations`, so that both runs get it. With the fixture property it is on either way.

## 3. Behavior changes

| Change | Toward or away from LinkedIn main | Default |
|---|---|---|
| The kit's main run measures the service's default configuration (v3 was hard-coded on) | n/a (main has no kit); back toward the default, as BC-043 asked | test only |
| A second kit run with v3 on | n/a | test only |
| Product paths | none | none |

This agrees with the author's section.

## 4. Tests
- **`RestCompatKitOutcomeListener` and the doLast ratchet.** They can fail for the right reason: every case in the table under attack 1 maps to a branch that adds a problem.
  - The author's broken-list run (six differences) is a claim I could not re-run.
  - Not pinned by any automatic test: the listener's naming of inherited, parameterized tests against Gradle's naming. Only the count is cross-checked, and a naming mismatch would show as "not listed" plus "did not run", so it fails closed.
- **`expected-outcomes.txt`.** Counts per category match the CI summary exactly at both configurations.

## 5. Review of the failures (attack 2): what I checked

I read the kit source (fork `origin/openhouse-1.12.0`: `core/src/test/.../catalog/CatalogTests.java`, `core/src/test/.../view/ViewCatalogTests.java`, `open-api/src/test/.../RESTCompatibilityKit*Tests.java`) and compared it with the recorded failure.

**Tests whose bodies I read (about 25):**

| Category | Tests read |
|---|---|
| SPEC | `testUpdateTableSchemaServerSideRetry`, `testUpdateTransaction`, `testUpdateTableSpecConflict` |
| SCHEMA | `testUpdateTableSchemaThenRevert`, `testUpdateTableSchemaConflict`, `testRemoveUnusedSchemas`: all drops |
| IDS | `testListNonExistingNamespace`, `testLoadTableWithNonExistingNamespace`, `loadViewWithNonExistingNamespace` |
| LISTREC | `testCreateNamespace` |
| LISTNS | `listViews` |
| KITNS | `dropNonEmptyNamespace`: asserts "should not exist" then "should exist" with nothing in between; contradictory under this flag, so e is defensible |
| VRENAME | `renameViewSourceMissing` |
| VIEWERRTYPE | `createViewErrorCases`: the duplicate-dialect step expects `IllegalArgumentException` plus a message; the refile from DIALECT is right |
| MISSINGMETA | `testLoadTableWithMissingMetadataFile` |
| DROPPURGE | `testRegisterTable`, consistent with `testDropTableWithoutPurge` passing |
| MSG | `loadViewThatAlreadyExistsAsTable`, `replaceViewThatAlreadyExistsAsTable`, `createViewThatAlreadyExists` |
| DIALECTRES | `concurrentReplaceViewVersion` |

**Structural scan:** all 24 VIEWDB and all 14 DIALECT/DIALECTRES bodies.

**Checked by the recorded message alone:** RTAS (16; the message names the replace policy), FMT, FMTV3, and the MSG entries whose message shows the type assertion passed and the message assertion failed.

**What I agree with:**
- Every filing's first step matches its recorded failure. No entry is filed under a declared gap while its recorded failure shows something else.
- The five corrections the author lists are right: MISSINGMETA (as the item directed), VIEWERRTYPE, SPEC wording, SCHEMA "all drops", VRENAME "client refuses, not 406".
- `testLoadNamespaceMetadata` passes.

**The e class ("not required of a conforming server"), entry by entry:**
- MSG 8: defensible; the spec fixes no message text.
- KITNS 1: defensible; the test contradicts itself under this flag.
- VIEWERRTYPE 1: defensible; the spec fixes no error-type strings, although the reference client maps the type `IllegalArgumentException`.
- DISABLED: the kit's own.
- None of them is a server gap described charitably. The charitable filings are in class b: VIEWDB (F1) and, debatably, IDS (F3).

**My table of causes (default configuration, 92 failures; same counts as the author's, regrouped by what it takes to fix):**

| Group | Categories | Count |
|---|---|---|
| A. Views in a namespace with no table or only a record (owner's native change, FF-51) | VIEWDB 24, LISTNS 2, KITNS 1 (flag artifacts of the same rule) | 27 |
| A'. Behind A: view dialect policy (owner's) | DIALECT 12, DIALECTRES 2 | 14 |
| B. Owner's table policies, native | RTAS 16, SPEC 9, SCHEMA 4, FMT 2, DROPPURGE 1 | 32 |
| C. Owner's native API missing | LISTREC 2 (a House Tables list of records, FF-52), VRENAME 2 (no native view rename) | 4 |
| D. `/iceberg` layer only, no native change | MSG 8 (not required, cheap), IDS 3, MISSINGMETA 1, VIEWERRTYPE 1 (type string; also behind A and A') | 13 |
| E. Configuration | FMTV3 2 (green with the switch) | 2 |
| Total | | 92 |

**What would turn the most tests green, in order:**
1. **Views creatable in a fresh or record-only namespace (A).** It unlocks 27 directly and is the prerequisite for 14 more (A'). Alone it turns perhaps 15 to 18 green, because the others move to VRENAME, DIALECT and RTAS. Confidence: high that this is the largest lever; low on the exact count.
2. **Replace allowed without `replace.enabled` (RTAS 16).** Confidence high that this is the first step; the later steps are unknown.
3. **The `/iceberg` layer (D, 12 excluding VIEWERRTYPE):** reference message text, 404 for ids that cannot exist, a missing metadata file mapped to `NotFoundException`. Cheap, no native change, no behavior drift on native paths. MSG: the type already passes in each case, so confidence is medium-high that they turn green. IDS and MISSINGMETA: medium.
4. **The view dialect policy (A')**, only together with 1.
5. **SPEC 9 and SCHEMA 4:** OpenHouse's evolution rules, a foundation decision, unlikely to change. Then LISTREC 2, VRENAME 2, FMT 2 and DROPPURGE 1, each needing an owner's API or policy.

Not required of a conforming server: MSG 8, VIEWERRTYPE 1, KITNS 1, and LISTNS 2 as long as the flag stays false.

## 6. Checked and found sound
- Name construction in the listener: the nearest class-source ancestor, simple parameter types, and `[n]` from the `test-template-invocation` segment. CI shows all 160 matched.
- The listener does nothing outside the kit task (`rck.outcomes.file` unset).
- Each kit task's service is separate (the session listener runs once per test JVM).
- `print-failed-tests.py --exclude` matches task names exactly, so adding the second exclude is needed and correct.
- The documentation's "a fix makes three tests pass" walk-through works: three "it passes now" problems; change the three lines to `| PASSED`; an emptied category is refused until it is removed (only the count comments drift, F5).
- Moves from the base list: only MISSINGMETA (e to d), createViewErrorCases (DIALECT to VIEWERRTYPE), and the new FMTV3 at default. Every other test keeps its base class.
- `python3 scripts/ci/check-private-ids.py`: passes (rc 0). No new private ids in the diff.

## 7. Not checked
- Ran nothing with Gradle. The author's broken-list experiment, `check-test-groups.py` and `check-iceberg-line-branches.py` were not re-run.
- What lies behind each first failure, except where the kit source shows it (F2 counts are estimates).
- Whether the 2 SpotBugs violations are new (F6).
- CI jobs other than "Default build, rest" (still running when written). Pull request 168's CI.

## Re-check at head `42915635` (from `67daf3e8`; merge `88c79b83`, then `e1a04fef`, `ad561bc4`, `42915635`)

- **Verdict: MERGE.** Nothing is open.
- **The list's test lines are unchanged.** All 162 non-comment, non-category lines of `expected-outcomes.txt` are byte-identical at both heads (compared by script), so no outcome, throwable type or message prefix changed. Only six `category` lines changed (VIEWDB, DIALECT, DIALECTRES, LISTNS, KITNS, VIEWERRTYPE), and only in their reasons. VIEWDB's class letter changed from b to d.
- **Nothing in the ratchet loosened.** The only change to the kit's `build.gradle` is the comment at line 108. The other removal, the configuration-time "fork not published" guard, comes from 166: the base `315a6e92` has no such guard either.
- **The merge is clean and keeps 166's workflow structure.**
  - Against `315a6e92`, the head changes only this pull request's 10 files.
  - `split-build.yml` keeps 166's layout; the only kit line is the second `--exclude` (line 245).
  - The kit is still in the `rest` matrix group, so `restCompatKitFormatV3Test` runs in exactly one job.
- **The change from b to d is right by the list's own legend** ("d a defect: the server breaks the spec on a route it serves"). The new reason states the contradiction: HEAD says the namespace exists, yet the view create answers 404 "Database not found".
- **Removing the per-category count comments loses nothing.** The ratchet never read them; the green run still prints the count per cause from the list.
- **The new "What would move the number" section matches my grouping.** Same rows, same counts (27 / 14 / 32 / 4 / 13 / 2 = 92), the same "not required" set, and the same caveat about tests moving to their next cause.
- **Minors closed:** the comment at `build.gradle:108`, and `AssertionFailedError` in the list's header and the docs. The `actual-outcomes.txt` wording is corrected.
- **SpotBugs:** the author reports the two findings are pre-existing ones in `ServiceWarehouse.delete`. I did not verify that.
- **Private-id guard:** `python3 scripts/ci/check-private-ids.py` passes at the head.
