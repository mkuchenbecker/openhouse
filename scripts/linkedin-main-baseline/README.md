# Recordings of LinkedIn main

The legacy-path smoke suite, the write statements, the catalog plugin contract and the commit-path
tests compare every answer with what LinkedIn main `bb8929e1` gave, recorded by running main's own
code. The recordings are data files on the test classpath:

| File | What | Compared by |
|---|---|---|
| `integrations/spark/spark-3.5/openhouse-spark-itest/src/test/resources/linkedin-main/smoke-scenarios.tsv` | every statement of `SparkSqlSmokeScenarios` | `LegacyPathSparkSqlSmokeTest` |
| `.../linkedin-main/write-statements.tsv` | every check of `SparkSqlWriteStatements` | `LegacyPathWriteStatementsTest` |
| `.../linkedin-main/catalog-plugin-contract.tsv` | every exception `CatalogPluginContractTest` catches, and its verdicts | `CatalogPluginContractTest` (legacy path only) |
| `services/tables/src/test/resources/linkedin-main/commit-path.tsv` | every HTTP answer of the `CommitPath*Test` classes, and their verdicts | the same classes |

Differences that this branch is allowed to have are declared, with main's answer quoted exactly and
the cause, in `LinkedInMainDifferences` (Spark itest) and `CommitPathLinkedInMainDifferences`
(`services/tables`). Tests recorded on main that no longer run must be named, with the reason, in
the `absentTests()` of the same classes.

A test run never writes a recording. `record.sh` is the only writer, and recording refuses to run
on any build but main's (the commit must be `bb8929e1`, and a class only this branch has must be
absent from the classpath), so setting the record directory by mistake fails instead of passing.

## When to record again

- A statement, check or request is added to, or changed in, one of the suites: its test fails with
  "no outcome of LinkedIn main is recorded for this statement".
- A test that was recorded is removed or disabled: its class fails with "recorded on LinkedIn main,
  but the test is gone or disabled", unless it is named in `absentTests()` with the reason.
- A test double the recording copies into main changes: `GrantStoringAuthorization`,
  `CommitRaceCatalog`, or the shared smoke sources. A change to the House Tables stand-in of
  `tables-test-fixtures` needs a new stand-in measurement (below); the commit-path suite cannot be
  recorded again until that stand-in is ported (see "Waiting for a re-record").

Do not edit a recording by hand. A statement main cannot answer gets no line; the branch declares a
difference quoting `NOT_OBSERVED_ON_MAIN` that pins its own answer.

## How

From the root of this checkout, with a JDK 17 and about 6 GB of free memory:

```
RUNS=6 RECORD_WORK=/some/dir/to/keep scripts/linkedin-main-baseline/record.sh /some/path/for/main
```

The script makes a detached worktree of `bb8929e1` at the given path (or next to the checkout),
copies in the test sources listed in its header, refuses to run if anything outside main's test
source directories changed, runs main's Spark 3.5 itest `catalogTest` six times in fresh JVMs
(about 15 minutes), checks that every run recorded the same statements, merges the runs (a
statement main answered differently in different JVMs keeps one line per answer), writes the three
Spark files, measures them with this branch's fixtures stand-in and writes the result into their
headers. The commit-path suite (`services:tables:test`) is refused until its stand-in is ported
(see "Waiting for a re-record"). A key with more than one recorded answer must be added to the
reviewed list in `LinkedInMainRecordingFilesTest`. Then:

1. `git diff` the recordings. Every changed line is a change in what main is recorded to do; if
   it is not explained by the change to the suites, find out why before going on.
2. Run the compared classes on both lines (`-PicebergLinkedIn` and the default build) and update
   the declarations: a new difference needs main's answer quoted exactly, this build's answer and
   the cause; a declaration whose statement now gives main's answer fails until it is removed.
3. Commit the recordings with the declarations, and say in the commit message what changed and
   why. Keep `RECORD_WORK` as evidence.

Options: `TASKS` (default `spark`; `tables` only with `MERGE_ONLY=1`); `MERGE_ONLY=1` merges the
raw runs already in `RECORD_WORK` again (for a header change only), taking the stand-in check from
the `stand-in-check.txt` a measurement left there.

## The test doubles

Each recording's header names, in `# test doubles:`, the House Tables stand-in and the
authorization backend main ran with. They are not the same for every suite:

- The stored commit-path recording ran main's code with this branch's earlier stand-in of
  `services/tables`' tests (`e2e/h2/HouseTablesH2Repository` at commit `0fe4678a`), copied into
  main; not main's own. The branch now runs the fixtures' stand-in; the answers that changed with it are
  declared as stand-in differences (kind "stand-in difference"), apart from the product's.
- The Spark suites run on main's own stand-in: the Spark itest's service takes it from
  `tables-test-fixtures` (`src/main`), which the recording does not replace. The branch runs its
  rewritten stand-in there. `FIXTURES_STAND_IN=branch RUNS=1 TASKS=spark record.sh` copies the
  branch's into main as well, runs once, prints every key whose answer is not among the stored
  ones, and writes its result to `stand-in-check.txt` in its `RECORD_WORK`; it writes nothing into
  the repository. A Spark recording makes this measurement itself and takes the `# stand-in check:`
  header line from it; the files test fails while the line is missing or does not say
  "identical". The stored line is from a measurement on 2026-11-28 with the stand-in that
  validates names as the real store does; earlier measurements with the branch's two previous
  stand-ins gave the same: no key differs.

## Waiting for a re-record

These need the commit-path suite (and for the second and third, the comparison code) changed and
everything recorded again. The stand-in work they waited for has merged (one stand-in, held to the
real store, validating names as it does), so they can be done now, in this order:

- The commit-path recording was made with the stand-in services/tables' tests had before the
  branch moved to the one stand-in of tables-test-fixtures, held to the real store. The answers
  that changed with it (the soft-deleted page size, case-insensitive keys, restore of a missing
  row or onto a live table) are declared as stand-in differences in
  `CommitPathLinkedInMainDifferences`, and the renamed test is in its `absentTests()`. Recording
  the commit path again with that stand-in on main needs more than copying sources: main's
  `services/tables` tests do not depend on `tables-test-fixtures`, and the commit-path tests now
  inject the fixtures' `HouseTablesH2Repository`, so main's test wiring would need that dependency
  (a build-file change, which the guard refuses) or a port of the stand-in into main's
  `services/tables` test sources. Until that port, `record.sh` refuses `TASKS` with `tables`.
- Placeholders erase identity: every UUID becomes `<uuid>` and every number of ten digits or more
  `<number>`, so whether two answers name the same table UUID or snapshot is not compared. Fix:
  number the placeholders by first appearance within one test (`<uuid#1>`, `<number#2>`) in
  `LinkedInMainRecording.normalizeValues` (both copies), then record again.
- The commit-path comparison does not see the bodies of successful writes (a 201 or 200 keeps only
  its status), compares errors by `$.message` only, and keeps the answers to one method and URI in
  a test as a sorted multiset, so which request got which answer is lost. Fix: add the sorted,
  normalized body of every 2xx response in `CommitPathHttp.perform`, and number requests sent from
  the test thread by occurrence (as `answerInOrder` does in the Spark copy), then record again.
