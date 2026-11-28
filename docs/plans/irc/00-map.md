# Map of the artifacts

Bottom line: yes, the work is reviewable by another agent. Every landed change is a merge commit on `irc-integration`
(86 pull requests, numbered 80 to 165 (the namespace stack's 153 is among them), as of 2026-11-28) whose message holds the reasons, the evidence and the known gaps; the notes in this
directory hold the decisions and the results; the raw agent records are now in `records/`. Section 6 describes what was paused on 2026-11-25; work resumed on 2026-11-27 and `18-state.md` is the current state. Everything here was checked on 2026-11-25 against the repositories and GitHub,
except what is marked "[not checked]". Nothing is merged into either fork's main or into LinkedIn's repositories.

## 1. Read in this order

1. `01-stated.md`: what the owner said, verbatim. Only this file carries his authority; agents never edit it.
2. This map.
3. `18-state.md`: current state, the pause, and how to resume.
4. `58-waiting-on-mike.md`: the open questions for the owner, with the irreversible choices.
5. Decisions, in order: `13-decisions.md` (D1 to D12), `16-decisions-continued.md` (D13 to D21),
   `22-decisions-continued-2.md` (D22 to D29), `37-decisions-continued-3.md` (D30 to D36),
   `51-decisions-continued-4.md` (D37 to D42), `56-decisions-continued-5.md` (D43 to D47),
   `67-decisions-continued-6.md` (D48 to D52), `90-decisions-continued-7.md` (D53 to D86).
6. Work tables: `04-work.md`, `30-work-defaults.md`, `41-work-platforms-v3.md`, and `74-work-table-audit.md`.
7. `45-merged-pull-requests.md`, then result files (`NN-...-result.md`) and `records/` as needed.
8. The retroactive review of 2026-11-27: `88-review-summary.md` first, then `85-behavior-change-ledger.md` (the
   behavior changes against LinkedIn main and what to do with each), `86-review-findings-and-fix-forward.md` (the
   defects and the fix-forward work list, where fixes start) and `87-clean-reimplementation-guide.md`. The nine
   reviewers' reports and their brief are `records/review-*.md`.
9. The owner's namespace line and the earlier drafts: `89-scout-namespace-and-prior-work-result.md` (summary of the scout's
   report `records/scout-namespace-and-prior-work.md`), and the decisions that follow it, `90-decisions-continued-7.md`.
   The brief the fix-forward agents work under is `records/fix-brief-common.md`.
10. The first pull requests merged after an independent review (2026-11-28): `91-first-reviewed-merges-result.md`; the
    reviewers' reports are `records/pr-review-*.md` and their common brief is `records/pr-review-brief-common.md`; the
    ledger's parts D and E and the status at the top of `86-...` follow from them.
11. The next five (152, 155, 156, 157, 159, merged 2026-11-28): `92-reviewed-merges-152-to-159-result.md`; ledger parts B (BC-056 to
    BC-058), C (BC-059, BC-060) and F (BC-061 to BC-074); decisions D69 to D76 in `90-decisions-continued-7.md`.
12. The next two (160 and 158, merged 2026-11-28; `irc-integration` at `4b9d152d`, 81 merged): `93-reviewed-merges-158-and-160-result.md`;
    ledger parts F (BC-075 to BC-086) and C (BC-087, BC-088); decisions D77 and D78 (and extensions of D69 and D75); the reviewers'
    reports `records/pr-review-158.md`, `-158-recheck.md` and `-160.md`.
13. The last two (163 and 164, merged 2026-11-28; `irc-integration` at `35bd941c`, 83 merged): `94-reviewed-merges-163-and-164-result.md`;
    ledger parts B (BC-089, BC-092 to BC-094) and C (BC-090, BC-091), BC-039 updated, and the ledger section on what the recording of
    LinkedIn main measured (from the open pull request 161); findings RF-069 to RF-071, RF-012 extended, FF-54 to FF-58 in `86-...`;
    decisions D79 to D83 and D62 extended; the reviewers' reports `records/pr-review-163.md`, `-164.md` and `-164-recheck.md`.
14. The last three (162, 165 and 161, merged 2026-11-28; `irc-integration` at `81cb4b74`, 86 merged):
    `95-reviewed-merges-162-165-161-result.md`; ledger parts F (BC-095 to BC-100) and C (BC-101, BC-102), BC-039 updated, and the
    rewritten ledger section on what the recording of LinkedIn main measured; findings RF-069 rewritten, RF-070 pinned, RF-012 closed,
    RF-072, RF-073 and FF-59 new, FF-20, FF-21 and FF-54 done, FF-22 and FF-53 partly done; decisions D84 to D86 (D62 extended a second
    time, D79 corrected, D83 marked merged); the reviewers' reports `records/pr-review-162.md`, `-162-recheck.md`, `-165.md`,
    `-165-recheck.md`, `-161.md` and `-161-recheck.md`. In flight: pull request 166 (`records/pr-review-166.md`, `-166-recheck.md`,
    `records/ci-check-name-and-build-hygiene.md`). The next free file number is 96.

## 2. Where everything lives

| Repository | Branch | What it is | Head (2026-11-25; irc-integration 2026-11-28) | Part of this effort |
| --- | --- | --- | --- | --- |
| openhouse | `irc-integration` | LinkedIn main (`bb8929e1`) plus every landed change | `81cb4b74` (2026-11-28; `81b4cd4d` on 2026-11-25) | yes |
| openhouse | `claude/openhouse-rest-v3-planning-g42c0h` | these notes; draft pull request 79, open, never merged | this commit | yes |
| openhouse | 72 `irc/...` branches | one per pull request, base `irc-integration` | various | yes |
| openhouse | `irc/x2-x3-v3-row-level` | X2 and X3 tests, no pull request yet | `1892643c` | yes, paused |
| openhouse | `irc/small-batch-w13-w14-n9` | local branch in the container only, no commit of its own | not pushed | yes, paused |
| iceberg | `openhouse-1.12.0` | Apache Iceberg 1.12.0 (`212aa894e`) with LinkedIn's patches reapplied | `a88f556c` | yes |
| iceberg | `openhouse-1.2.0`, `openhouse-1.5.2` | equal to LinkedIn's (not changed here) | `8d4afa5c`, `af77f055` | no |
| iceberg | 10 `irc/...` branches | the sources of iceberg pull requests 3 to 12 | various | yes |

- Audit trail: `git log --first-parent origin/irc-integration`. Each merge commit message has the paragraphs Why, Leaves
  true, Evidence, Known gaps and Commits, and names the decision record. Pull requests 80 to 150 are all merged (71,
  no exceptions); the 72 `irc/` branches are those 71 plus the unmerged X2 and X3 branch.
- The build pins the fork in `scripts/iceberg-fork/iceberg-fork.properties`: `commit=a88f556c506744bb8e99617d58c9c7f1a9f81e58`,
  which is the head of `openhouse-1.12.0`. `gradle/iceberg-fork.gradle` reads it; `docs/development/iceberg-fork.md` explains it.
- Merged on the iceberg fork: pull requests 3 to 12 into `openhouse-1.12.0` (checked; all merged 2026-11-22 and 23).
- That `openhouse-1.2.0` and `openhouse-1.5.2` equal LinkedIn's branches: [not checked] (no access to LinkedIn's repository).
- Not part of this work: both forks hold many other branches and open pull requests that predate this effort or
  belong to other efforts. On openhouse there are 43 open pull requests numbered below 79 (the `claude/ws...`, views,
  namespace and optimizer ones, among others); on iceberg the open pull requests 1 and 2. None was touched. Rule: a
  pull request or branch is part of this effort only if its base is `irc-integration`, its branch name starts with
  `irc/`, or it is listed in `45-merged-pull-requests.md`.

## 3. The notes by kind

`README.md` is the index with one row per file; do not read every file in order.
- Stated by the owner: `01-stated.md`. Requirements and inferences: `02-facts.md`, `03-inferred.md`.
- Designs: `07`, `14`, `28`, `29`, `05` (patch ledger), `06` (smoke suite proposal), `24` (commit rule inventory).
- Decisions: D1 to D86 in the eight files listed in section 1. Waiting on the owner: `58`.
- Work tables: `04`, `30`, `41`; their audit: `74`. Merged list: `45`. State: `18`. Delegation rules: `17`.
- Findings: `08`, `20`, `21` and the first-round results `09` to `12`.
- Result files, one per merged group of work: `15`, `19`, `23`, `25`, `26`, `27`, `31` to `36`, `38` to `40`,
  `42` to `44`, `46` to `50`, `52` to `55`, `57`, `59` to `66`, `68` to `73`, `75` to `82`. File 60 and 70 are scouts.
- The raw records: `records/` with its own `README.md`: record, work item, pull request, result file. It now also holds the
  pull request review reports (`pr-review-151.md` to `pr-review-165.md`, with the `-recheck` reports of 158, 161, 162, 164 and 165) and the common briefs. The authors' records of the last three merges are `baseline-of-linkedin-main.md` (161), `iceberg-rest-handler-minors.md` (162) and `stand-in-follow-up.md` (165); the records of 163 and 164 are `house-tables-stand-in-fidelity.md` and `private-ids-out-of-code.md`. In flight, not results: `ci-check-name-and-build-hygiene.md` with `pr-review-166.md` and `pr-review-166-recheck.md` (pull request 166). 127 files in `records/` on 2026-11-28 counting its README.
- The retroactive review of 2026-11-27: the ledger `85`, the findings and fix-forward list `86`, the re-implementation
  guide `87` and the summary `88`; its records are `records/review-brief-common.md` and the nine `records/review-*.md`
  (irc-commit-write, irc-read-register-limits, irc-views-capabilities, client-seam-spark, format-v3, scan-planning,
  jobs, build-deployables, ci-fixtures-tests).
- The scout of the owner's namespace line: `89-scout-namespace-and-prior-work-result.md`, its record
  `records/scout-namespace-and-prior-work.md`; decisions D53 to D83 in `90-decisions-continued-7.md`; the reviewed merges: `91-first-reviewed-merges-result.md` (154, 151, 153) `92-reviewed-merges-152-to-159-result.md` (152, 155, 156, 157, 159) `93-reviewed-merges-158-and-160-result.md` (160, 158), `94-reviewed-merges-163-and-164-result.md` (163, 164) and `95-reviewed-merges-162-165-161-result.md` (162, 165, 161).

## 4. The code on `irc-integration`, by area

Paths verified with `git ls-tree` on `origin/irc-integration` at `81b4cd4d`.
- Iceberg REST routes: `services/tables/src/main/java/com/linkedin/openhouse/tables/api/irc/` (19 classes); code that
  compiles only on the 1.12 line (register view, scan planning): `services/tables/src/iceberg-1.12/`, tests
  `IrcRegisterView*Test`, `IrcScanPlanning*Test` beside it; the shared route tests are `services/tables/src/test/.../e2e/h2/Irc*Test.java`.
- Capability routes (policies, grants, unlock): `IcebergRestOpenHouseController.java` in that package; tests
  `IrcPolicyPatchTest`, `IrcGrantsUnlockRoutesTest`, `IrcGrantsUnlockHttpClientTest`. Client side:
  `integrations/java/openhouse-catalog-capabilities/`.
- Format v3 switch and commit policy: `services/tables/src/main/java/com/linkedin/openhouse/tables/formatversion/`
  (`FormatVersionCommitPolicy.java`, `FormatVersionRule.java`, `FormatV3Types.java`); tests `FormatV3*Test` in `services/tables/src/test/.../e2e/h2/`.
- REST-shaped seam in the Java client and Spark 3.5: `OpenHouseRestCatalog.java` and `OpenHouseRestAdapter.java` in
  `integrations/java/iceberg-1.5/openhouse-java-runtime/`; `OpenHouseRestCatalog.java` in
  `integrations/spark/spark-3.5/openhouse-spark-runtime/`; tests `OpenHouseRestAdapterTest`, `RestShimPathSparkSqlSmokeTest`.
- Spark 4.2: `integrations/spark/spark-4.2/` (itest `Spark42IrcSparkSqlSmokeTest`) and `apps/spark-4.2/` (jobs, `Spark42TypeRestCatalogTest`).
- Maintenance jobs: `apps/spark-3.5/src/shared`; evidence `FormatV3MaintenanceTest` and the other `FormatV3*JobsTest` in `apps/spark-3.5/src/test/`.
- REST compatibility kit: `integrations/java/openhouse-rest-compat-kit/`, reviewed list `expected-outcomes.txt`
  (54 passed, 90 failed, 16 skipped, unchanged through pull requests 157, 160, 158 and 163; not re-read for 162, 165 and 161, whose merge messages do not mention it).
- Shared Spark SQL smoke scenarios and write checks: `integrations/spark/spark-3.5/openhouse-spark-itest/src/shared/smoke/`
  (`SparkSqlSmokeScenarios.java`, `SparkSqlWriteStatements.java`), run by `LegacyPath`, `RestShimPath`, `RestHttpPath` tests and `SparkIrcWriteStatementsTest` on 4.2.
- Delta-harness: `integrations/spark/delta-harness/` (`BASELINE-2026-11-22.md`, `HARNESS-GUIDE.md`).
- Test fixtures: `tables-test-fixtures/` (`tables-test-fixtures-iceberg-1.5/`, with `OpenHouseLocalServer`, `HouseTablesH2Repository`).
- Build, two Iceberg lines: `settings.gradle`, `gradle/iceberg-fork.gradle`, `scripts/iceberg-fork/` (`publish-local.sh`); `-PicebergLinkedIn` selects the reference line.
- CI: `.github/workflows/` (`build-run-tests.yml`, `split-build.yml`, `post-merge-build.yml`) and `scripts/ci/`
  (`test-groups.py`, `check-test-groups.py`, `print-failed-tests.py`).

## 5. How a claim is backed

Work table row (`04`, `30`, `41`) to result file (named in `45`) to record (`records/README.md` maps it) to pull request
to its merge commit message to the tests that message names. In records and results [ran] means executed and seen,
[read] means read in code or logs, [inferred] means concluded without either; only [ran] was run. A suite counts only
if it ran on the 1.12 line (D33). Post-merge results are read after each merge, not assumed.

## 6. What was paused on 2026-11-25 (superseded)

Superseded: work resumed on 2026-11-27. X2 and X3 merged as pull request 151, W13 is done by pull request 154, and the post-merge
build of 149 was read long ago; see `18-state.md`. What follows is the state of 2026-11-25. All work was paused by the owner since 2026-11-25 about 05:10 UTC. Nothing is running.
- X2 with X3 (`records/x2-x3-v3-row-level.md`): branch `irc/x2-x3-v3-row-level`, head `1892643c`, tests only, pushed.
  They pass on the default build and found no defect. Remaining: the final run on the 1.5.2 reference line, the
  test-groups check (`python3 scripts/ci/check-test-groups.py`), open the pull request, wait for CI.
- W13, W14, N9 (`records/small-batch-w13-w14-n9.md`): only W13 investigated, by reading. The jobs scheduler's jar carries
  neither Iceberg nor the class that tells the client its Iceberg line, so it declares the column-defaults capability on
  both lines and logs a warning on every start. On the 1.12 line that declaration looks wrong. Nothing changed. This
  needs a decision by the orchestrator before any change on the wire.
- Post-merge build of pull request 149 (commit `81b4cd4d`), read by me at 07:30 UTC on 2026-11-25 with `gh api`
  on the check runs: 16 jobs completed, 15 success, 1 skipped (the reference line's Docker job, skipped by design),
  none failed. The earlier read at 05:11 UTC had 6 passed and 9 running.

## 7. What is NOT in git

- The working container: worktrees `/home/user/oh-*` (80 directories), build output, scratch logs, and the locally
  published fork jars under `~/.gradle/iceberg-fork/` (regenerated by `scripts/iceberg-fork/publish-local.sh`).
- The conversation transcripts of the orchestrator and the agents. What they decided is in the decision files and the
  merge messages; how they got there is only in the records. Nothing else, now that `records/` is pushed.
- To rebuild, follow "How to resume" in `18-state.md`: clone, fetch, run `publish-local.sh` once, then build.

## 8. Known weak spots a reviewer should probe

- The notes were written by small agents from the records and corrected by the orchestrator. Where a note and a
  record disagree, the record and the merge commit message are closer to the source.
- The 150-line limit made `18-state.md` and `04-work.md` shed detail; the totals in `04-work.md` were kept by editing
  numbers, and `74-work-table-audit.md` is the last full recount.
- Statements marked [read] or [inferred] were not run.
- Commits by the code agents carry a different co-author line than the merge commits.
- Decisions the orchestrator made alone are in the decision files and are meant to be reversible until release; the
  irreversible ones are in `58-waiting-on-mike.md`.
- Only two changes got an independent review before merge (the v3 maintenance work, pull request 125, and the
  register-view route, pull request 149). The rest did not.

## 9. The instrument for the claim "existing behavior did not change": the recording of LinkedIn main

Bottom line: a new reader who wants to know whether this branch changes what LinkedIn main answers should start from the stored
recording of main, not from the notes. Pull request 161 (merge `81cb4b74`) recorded main `bb8929e1` with its own build and made four
suites compare with it in CI on both Iceberg lines. Where it lives in the code repository (on `irc-integration`; paths verified with
`git ls-tree` at `81cb4b74`):
- `scripts/linkedin-main-baseline/`: `README.md` (when and how to record again, the test doubles, what waits for a re-record),
  `record.sh` (the only writer of the recordings; it refuses to run on anything but main's commit), `recorder/` (the main-side sources
  and the empty difference lists it copies in).
- The recordings, the `linkedin-main` resource directories: `integrations/spark/spark-3.5/openhouse-spark-itest/src/test/resources/linkedin-main/`
  (`smoke-scenarios.tsv`, `write-statements.tsv`, `catalog-plugin-contract.tsv`) and
  `services/tables/src/test/resources/linkedin-main/commit-path.tsv`.
- The `LinkedInMain*` test classes: `LinkedInMainRecording`, `LinkedInMainComparison`, `LinkedInMainAnswers` (one copy under
  `.../openhouse-spark-itest/src/shared/smoke/` and one under `services/tables/src/test/.../e2e/h2/`), `LinkedInMainDifferences` (the
  Spark itest's declared differences) and `CommitPathLinkedInMainDifferences` (`services/tables`), `LinkedInMainComparisonTest`,
  `LinkedInMainRecordingFilesTest`; the comparing tests are `LegacyPathSparkSqlSmokeTest`, `LegacyPathWriteStatementsTest`,
  `CatalogPluginContractTest` and the `CommitPath*Test` classes.
- The list of tests that branch on the Iceberg line: `scripts/ci/iceberg-line-branches.tsv` and `scripts/ci/check-iceberg-line-branches.py`.

Which notes explain it: `95-reviewed-merges-162-165-161-result.md` section 3 (what 161 leaves true, the declared differences by kind,
line and cause, and what a green run proves and does not prove, in full); `85-behavior-change-ledger.md`, the section "What the
recording of LinkedIn main measured" (each declared difference with its ledger row); `86-...`, FF-20 to FF-24 and FF-55 (what is done
and the full re-record that is still needed); `90-...` D79 (the test-double differences) and D80, D81 (the Jackson text and the
row-lineage columns); `records/pr-review-161.md` and `records/pr-review-161-recheck.md` (the reviewer's check that the stored files
are main's and that no declaration hides a product change).
