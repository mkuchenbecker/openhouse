# Review of pull request 164: private planning ids out of messages, comments and build files; CI check

Reviewed head `d57bb53631a57cb40895a540eda99929ca1e1615` (same as assigned; `gh api .../pulls/164` reports the same head),
worktree `/home/user/oh-ids`, base `origin/irc-integration` = merge base = `4b9d152d`. 9 commits, 130 files,
+791/-632. Read-only; I ran the pull request's own `scripts/ci/check-private-ids.py` (reads files only) and my own
scanners over `git diff bb8929e1..HEAD` and over `git ls-tree bb8929e1`.

Real leftovers: **1 in code and comments outside the declared exclusions** (`CommitPathUpdateRulesTest.java:38-39`).
Inside the exclusions: **14 lines in `docs/development/iceberg-fork.md`** (26 matches by the author's own pattern) and
**about 26 lines in the delta harness** citing documents that are not in the repository or ids its guide does not
define. Plus id-derived names in test data that the author did not list (section 2, F5).

## 1. Verdict

MERGE AFTER FIXES: F1 and F2 must be closed first.

The three things that matter most:
1. The guard is line-based and only counts V0 to V3 alone in parentheses, so a "work item V1" wrapped over two comment
   lines got through. The author's claim "FF-35 done for every added line outside docs and the delta harness" is
   therefore not quite true (F1).
2. The guard skips all of `docs/` and every `*.md`. So `docs/development/iceberg-fork.md` keeps "decision D7" and "work
   item I6d", the exact text FF-34 removed from `gradle/iceberg-fork.gradle`, and the build files send readers to that
   doc. The skip also lets new ids come back there (F2).
3. The product message changes are correct and safe. The four changed refusals are all in files that are new on this
   branch (none exists at `bb8929e1`). Exception type, code, trigger and status are unchanged, and every test that
   pinned the old text pins the new text. No non-comment code changed by accident. The FF-49 `unzip` fix is right.

## 2. Findings

### F1. MUST FIX BEFORE MERGE: a work-item id is left in a test comment, wrapped across lines
- File: `services/tables/src/test/java/com/linkedin/openhouse/tables/e2e/h2/CommitPathUpdateRulesTest.java:38-39`. The
  file is new on the branch (commit `56c65c3b`).
  ```
   * Rules of an update commit on an existing table that had no pin at the HTTP boundary before work
   * item V1: reserved properties ...
  ```
- Why the guard misses it: `\bwork items?\b` is matched one line at a time, and "V1" alone is exempt unless it is
  written "(V1)".
- Certainty: certain from reading. I ran a multi-line search for work/decision/pull request/review/fix-forward followed
  on the next comment line by an id or number, over every tracked file outside docs, `*.md` and the harness. This is
  the only hit.
- Fix:
  - Say it in words, e.g. "that had no pin at the HTTP boundary before the update commit was routed through the
    shared commit core".
  - Optionally, make the guard join comment continuation lines before matching, or at least match `work\s*$` followed
    by `^\s*(\*|//|#)\s*items?\b`.

### F2. MUST FIX BEFORE MERGE: `docs/development/iceberg-fork.md` keeps the planning ids, and the guard's `docs/` and `*.md` skip lets them come back
- The file is new on the branch (it is not at `bb8929e1`). It is the developer doc that `apps/spark-3.5`, the compat
  kit and the Spark 4.2 runtime build files point to ("see docs/development/iceberg-fork.md").
- Lines 9, 86, 95, 100, 168, 174, 177, 180, 182, 187, 188, 241, 256 and 262 cite:
  - decisions D8, D3, D7, D5, D11a, D14a, D14b, D31 and D33;
  - "work item I6d" and "work item X5";
  - "fork pull request 12, item I11";
  - "Refused on v3 until their work items".
- Line 95/100 is the publish-refusal reasoning: "(decision D7) ... is the repository owner's decision (work item
  I6d)". That is the text FF-34 removed from the Gradle message and comment.
- The author lists this as "deliberately left" ("about 14 ids"). The assignment covers every message a developer can
  see, and a developer doc is one.
- The skip is also wider than needed:
  - `docs/plans/` is not tracked in this repository at all (`git ls-files | grep -c ^docs/plans` = 0).
  - The only tracked files the `docs/` and `*.md` skip actually exempts that have matches are this doc and the harness
    guides. `NAMESPACES.md` and the other docs have none.
- Certainty: certain from reading.
- Fix:
  - Rewrite those 14 lines in words.
  - Narrow `SKIPPED_DIRS` to `integrations/spark/delta-harness/` plus, if wanted, `docs/plans/`. Drop the blanket
    `*.md` skip, or limit it to the harness guides.
  - Rerun the guard. It passes today on `bb8929e1` docs, which have no matches.

### F3. SHOULD FIX: the stated reason for skipping the delta harness is not true for much of it
- The script's docstring says the harness's "scenario and finding ids are defined in its own HARNESS-GUIDE.md". Of the
  letter-plus-number ids in the harness's non-markdown files, these are defined in `HARNESS-GUIDE.md` or
  `BASELINE-2026-11-22.md`: G2, G8 to G11, G13, G14, H3. These are not:
  - B1 to B5, C1 to C7, D5, E1, E5, E10, F1, G4, H1, H4 to H8, P1 to P3, P6, T2, Phase-28, Phase-29.
- About 26 lines cite documents that are not in the repository, several of them in assertion failure messages a
  developer reads:
  - `HazardReaderWriterScenarios.scala:44, 77, 79`: "update MODALITY-RECON H1", "MODALITY-RECON H2/Audit B".
  - `InteractionScenarios.scala:107, 116, 135, 328, 358`: "update AUDIT-FINDINGS G9/G11".
  - `NegativeDdlScenarios.scala:99`: "AUDIT-FINDINGS B".
  - `print-cp.init.gradle:7`: "see FINDINGS.md F1".
  - `MorMaintScenarios.scala:87, 103, 105`: "D5 DECIDER", "D5:".
  - `BranchWapScenarios.scala:115`, `Plan.scala:176`: "Phase-29", "Phase-28".
- Checked: no `FINDINGS.md`, `MODALITY-RECON` or `AUDIT-FINDINGS` file is tracked.
- The author does mention "Phase-29" and the `FINDINGS.md F1` reference. The full extent above is not stated, and the
  docstring's reason stands as if true.
- Why it can wait: the harness is a standalone tool, built by no CI workflow and by no project in `settings.gradle`,
  and none of its text reaches a product user.
- Fix:
  - Make the docstring's reason true: "its section labels are local to each file". Then replace the references to
    absent documents with what to check.
  - Or say plainly in the record that the harness is out of scope and why.

### F4. SHOULD FIX: the guard is easy to get around in ways that matter for a re-implementation
Each of these is certain from reading the regexes:
- Letter set `[CDFINTUVWX]`: the planning list also uses P (work items P1, P2, ... in `04-work.md`), J, R, O, B and H
  series. "(P1)" or "J26" would pass.
- V0 to V3 count only as "(V1)". V1, V2 and V3 are real work items (commit core, IRC read, IRC write; D20's title is
  "work item V3"). "since V2" or "V3 error mapping" (text this pull request removed from a test) would pass. This is a
  declared trade-off, and it is the cause of F1.
- Pull request regex `(?:8\d|9\d|1[0-5]\d|16[0-2])`:
  - It excludes 163, 164 and anything later, although the assignment's range is 80 to 164.
  - "pull requests 120 and 121" (plural) does not match.
  - Neither does a bare "#120".
- Ids in identifiers and lower case are invisible (see F5).
- The `*` allow-list entries for N1, N4, N6 and N8 in `SparkIrcServerPlanningWritesTest` allow those tokens on any line
  of that file. N1 is also a planning id ("N1-c", the plain REST path), so a later "(N1)" comment there would pass.
  Exact-line entries (only 2 lines: 382 and 442) would not hide it.
- Smallest fixes:
  - Raise the pull request bound to `1\d\d`, and allow `requests?` and `#`.
  - Add P and J to the letter set, checking false positives with `--list`.
  - Replace the `*` entries with the two exact lines.
  - Handle wrapped lines (F1).

### F5. SHOULD FIX: names that encode ids are only partly listed as left
- The author lists 5 method names: `refusedNamingX5`, `...AreRefusedNamingX5`, two `...NamesItsWorkItem` and
  `refuseWhatV3DoesNotHandleYet`. Some of these names now say the opposite of what the test asserts:
  `aFormatV3TypeIsRefusedAndNamesItsWorkItem` now asserts that the message no longer names one.
- Not listed: test-data strings, database, job and file names derived from work-item ids, which appear in logs, job ids
  and test tables:
  - `FormatV3MaintenanceTest`: about 23 lines, `"x6maint"`, `"x6-compaction"`, `"x6-orphan-deletes.puffin"`,
    `"x9-retention-backup"`, ... FF-49 names this file's X6/X9/X10/X12 explicitly.
  - `FormatV3ConcurrencyTest`: `"x10conc"`, `"x10-compaction"`, ...
  - `FormatV3BranchesJobsTest`: `"x10branch"`, `"x10b-*"`.
  - `FormatV3EqualityDeletesJobsTest`: `"x10eqdel"`.
  - `SparkIrcConcurrencyTest`.
  - `SparkIrcFormatV3TypesTest` and `FormatV3TypesTest`: `"x5..."` table names, about 50 lines.
  - `OperationsCatalogTest`: `"n16.fresh"`.
- Why it can wait: these are internal test data, and renaming test data is a test-only change. But they belong in the
  "left" list, and FF-49's "the test file also names X6, X9, X10, X12" is only half closed.
- Fix: list them in the record, or replace them with neutral names in the same commit as F1. Changing these strings
  changes no assertion that depends on the id. I checked that no CI script greps for them.

### F6. MINOR: some rewritten comments lost the reason the id carried
- `IcebergRestViewRegistration.java:81`: "Older files of a dropped view register too (decision B)" became "(the view is
  brought back as that file records it)". Decision B (D52(c), `records/v10-register-view.md:73`) is about why older
  files are allowed: the SQL was the view's once, the registrant could create it natively with the same privilege, and
  the audit names who did it. The new parenthesis restates the "keep in place" rule (D52(a)) instead. It is true but
  says nothing about the reason. Fix: one clause giving D52(c)'s reason.
- `IcebergRestViewRegistration.java:122`: "(decision A)" was deleted with no replacement. That is acceptable here,
  because the sentence already says what is audited.

### F7. MINOR: the allow-list's reason for `ViewsManagedFailureAuditTest` F4 and F5 is inaccurate
- The comment says "The test's own numbering of its cases, defined in the class". The class has F4 and F5 only, both on
  `bb8929e1`, and no F1 to F3 or definition.
- The entry is right, since it is LinkedIn main's text and may not change. Only the reason is wrong. Fix: "LinkedIn
  main's section labels".

### F8. MINOR: the new W8 refusal text could say what to do more directly
- `FormatVersionCommitPolicy.java:162-164`: "A table that uses it stays at format version 2, since OpenHouse does not
  translate its stored defaults to native ones; a new format v3 table does not set it."
- It is true and no longer points at a migration that does not exist, which is an improvement.
- "does not set it" reads as a description rather than an instruction. "Remove the property or keep the table at
  format version 2" would be clearer.
- The message is new on this branch, so it is not a behavior change against main. Not required.

## 3. Behavior changes

| What | Old → new | Against LinkedIn main? | Author's table |
|---|---|---|---|
| `FormatV3Types` refusal (`FORMAT_V3_TYPE_NOT_SUPPORTED`) | "... on format v3 tables (work item X5): %s." → "... on format v3 tables: %s." | No, the file is new | Listed |
| `FormatVersionCommitPolicy` opt-in on v3 | "... moves to v3 only through the migration that translates its defaults (work item W8)." → "... stays at format version 2, since OpenHouse does not translate ...; a new format v3 table does not set it." | No, new | Listed |
| `FormatVersionCommitPolicy` replication on v3 | "not supported yet (work item X7): ..." → "not supported: ..." | No, new | Listed |
| `gradle/iceberg-fork.gradle` publish refusal (`GradleException`) | drops "(decisions D7 and I6d)" | No, new | Listed |
| Deployables action, apps-jar check | a missing or unreadable jar used to pass; now the step fails (exit 9 from `unzip` under `bash -eo pipefail`) | CI only | Listed |
| Split build `plan` job | new step `check-private-ids.py`, default line only | CI only | Listed |
| Test println labels, smoke-difference reasons, kit `expected-outcomes.txt` reasons | id prefixes removed or reworded | Test output only. The kit's reason column is free text; I checked its parser at `rest-compat-kit/build.gradle:123-141`: it compares only test name and outcome | Listed |

The author's table matches mine; nothing is missing. For each of the four product messages, exception type, code
constant, trigger and status are unchanged (only the string literal differs in each hunk).

## 4. Tests

| Test | Can it fail for the right reason? |
|---|---|
| `FormatV3NativeApiTest`, type case | Yes. It pins "OpenHouse does not take the type " + type + " on format v3 tables: ". |
| `FormatV3NativeApiTest`, replica case | Yes. It pins the new X7 sentence. |
| `FormatV3NativeApiTest`, opt-in case | Yes. It pins the whole new W8 sentence. |
| `FormatV3TypesTest`, `IrcFormatV3RoutesTest` | Yes. They pin the new suffix together with the reason text (`REFUSED.get(type)`) or the type name. |
| `SparkIrcFormatV3TypesTest.refusedNamingX5` | Weaker. It pins `" on format v3 tables: "` plus the code and column. It still fails if the message regresses to the old text, because the old text had "tables (work item X5): ". |
| Removed `contains("work item ...")` checks | None of the new assertions checks that no id is present. The guard covers that for string literals in sources, but not for a message built at run time. Acceptable. |

Not pinned:
- the guard itself has no self-test (unlike `test-print-failed-tests.py`);
- the `unzip` fix is pinned only by the author's manual run of four jars.

## 5. Checked and found sound
- Head and base are as assigned. CI at writing:
  - `Default build, test groups` (the `plan` job, which runs the guard) passed. So did `Reference build, test groups`,
    `Reference build, apps-spark-4.2` and `Reference build, spark-4.2-openhouse-spark-itest`.
  - The rest were in progress. The Docker job, where the `unzip` step runs, was not finished.
- No accidental code change:
  - `git diff -w --shortstat` equals the plain diff, so there are no whitespace-only changes.
  - I went through every changed non-comment line in main and test sources. Each is a string-literal change listed in
    section 3, a test assertion change listed in section 4, or the CI or script lines.
  - No annotation, `@Test`, display name, method, class, Gradle task, property or `include` line changed.
- Files that exist on `bb8929e1` (12) changed only in comments the fork had added, and no LinkedIn message changed.
  The 12 files:
  - `apps/spark-3.5/build.gradle`;
  - `OpenHouseInternalTableOperations`;
  - the Spark 3.5 itest `build.gradle`;
  - Spark 3.5 `OpenHouseCatalog`;
  - `HouseTablesSpringApplication`;
  - `services/tables/build.gradle`;
  - `PolicyTag`;
  - `MainApplicationConfig`;
  - `PoliciesSpecMapper`;
  - `ReadBridgeConfigResolver`;
  - `OpenHouseInternalRepositoryImpl`;
  - `settings.gradle`.
- Nothing parses the changed messages: no client or test matches "work item", and there is no stored recording of
  pull request 161 on the base. Neither CI nor any script greps the old println markers.
- The guard on LinkedIn main: 13 matches over `git ls-tree bb8929e1` (same skips), all in the allow-list, so there are
  0 false failures. It runs in 1.3 s on the head. It scans the whole tree, needs no history, and works on a fresh base
  as long as that base's own matches are allow-listed. One consequence of that design: a sync of a newer LinkedIn
  main that adds an "F1"-like token will fail the step until the allow-list is extended. That is acceptable and
  visible.
- FF-49:
  - Build-file ids are gone: capabilities "(decision D23)", apps "work item X5".
  - `FormatV3MaintenanceTest` comments and println labels no longer cite X6/X9/X10/X12. Its strings still do (F5).
  - The capabilities header now says that publishing makes the API public and lists what consumers compile against.
    It also says the jar's content depends on the Iceberg line (transport `sourceSets`, Iceberg `compileOnly` so the
    POM names none). The `sourceSets` and `compileOnly` lines below it confirm this.
- `unzip` fix:
  - `shell: bash` in a composite step runs `bash --noprofile --norc -eo pipefail`, so a failed `unzip -Z1 ... >
    file` stops the step.
  - `grep -qx` on the listing finds only a root `log4j2.properties`, as before.
  - Can it still pass for the wrong reason? Only if a different, stale jar sits at the same path. That is outside this
    item.
  - The listing file in the workspace is harmless; the step already writes `scheduler-*.txt` there.
- Comment rewrites: I read about 50 across the tables service (`api/irc`, scan planner, view registration, write
  handler, commit core, repository, format-version package), the build files, `settings.gradle`, the Spark 3.5 smoke
  scenarios, the Spark 4.2 endpoint and hook tests, and the jobs tests.
  - The id was almost always a parenthetical next to a sentence that already explained the code, and removing it lost
    nothing.
  - Several rewrites improved accuracy: history narration became what holds now (`PolicyTag`, `SparkIrcEndpointsTest`),
    and "D19" became "as a commit refuses a change the update path never stores", which is exactly D19's title.
  - The one weak replacement is F6.
- The kit's new reason "while cluster.iceberg.format-v3.variant.enabled is off, its default" is true
  (`OpenHouseRestCompatibilityKitCatalogTests.java:74-76`).
- Allow-list entries F401 (flake8 noqa), `BranchJavaTest` F1/F2 (DataFile constants) and N1-N8 (row values) each have a
  true reason, with the caveats in F4 and F7.
- `FF-35`'s listed files that are not in the diff (`AppConstants`, `Operations`, `TablesClientFactory`,
  `IcebergRestScanController`) have no ids at the head.
- Both Iceberg lines: the changes are comments and string literals in files built on both lines, and the 1.12-only
  sources changed only in comments.
- The `s01_`..`s33_` smoke method names are the suite's own scenario numbering (they are also its table names), not
  planning ids, so they are false positives.

## 6. Not checked
- Whether every CI job finishes green (most were in progress); I did not wait.
- I did not build, compile or run spotless. The author reports compile and spotless on both lines.
- The capabilities header's claim about the thin Spark runtime's POM (I did not inspect the generated POM).
- PR body and commit messages, which cite FF and RF ids, were not counted as product text.
