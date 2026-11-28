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
- A test double the recording copies into main changes: the House Tables stand-in of
  `services/tables`' tests, `GrantStoringAuthorization`, `CommitRaceCatalog`, or the shared smoke
  sources. Open work that needs this: see "Waiting for a re-record" below.

Do not edit a recording by hand. A statement main cannot answer gets no line; the branch declares a
difference quoting `NOT_OBSERVED_ON_MAIN` that pins its own answer.

## How

From the root of this checkout, with a JDK 17 and about 6 GB of free memory:

```
RUNS=6 RECORD_WORK=/some/dir/to/keep scripts/linkedin-main-baseline/record.sh /some/path/for/main
```

The script makes a detached worktree of `bb8929e1` at the given path (or next to the checkout),
copies in the test sources listed in its header, refuses to run if anything outside main's test
source directories changed, runs main's Spark 3.5 itest `catalogTest` and `services:tables:test`
six times in fresh JVMs (about 15 minutes), checks that every run recorded the same statements,
merges the runs (a statement main answered differently in different JVMs keeps one line per
answer) and writes the four files. Then:

1. `git diff` the recordings. Every changed line is a change in what main is recorded to do; if
   it is not explained by the change to the suites, find out why before going on.
2. Run the compared classes on both lines (`-PicebergLinkedIn` and the default build) and update
   the declarations: a new difference needs main's answer quoted exactly, this build's answer and
   the cause; a declaration whose statement now gives main's answer fails until it is removed.
3. Commit the recordings with the declarations, and say in the commit message what changed and
   why. Keep `RECORD_WORK` as evidence.

Options: `TASKS=spark` or `TASKS=tables` runs one side (the other side's files are not written);
`MERGE_ONLY=1` merges the raw runs already in `RECORD_WORK` again (for a header change only).

## The test doubles

Each recording's header names, in `# test doubles:`, the House Tables stand-in and the
authorization backend main ran with. They are not the same for every suite:

- The commit-path tests run on the branch's stand-in of `services/tables`' tests, copied into
  main, so only production code differs.
- The Spark suites run on main's own stand-in: the Spark itest's service takes it from
  `tables-test-fixtures` (`src/main`), which the recording does not replace. The branch runs its
  rewritten stand-in there. `FIXTURES_STAND_IN=branch RUNS=1 TASKS=spark record.sh` copies the
  branch's into main as well, runs once, and prints every key whose answer is not among the stored
  ones; it writes nothing into the repository. Checked on 2026-11-28: no key differs, and the
  Spark recordings' header says so (`# stand-in check:`). When a change to either stand-in lands,
  run the check again and update `STAND_IN_CHECK_SPARK` in the script with the result before the
  next recording.

## Waiting for a re-record

These need the commit-path suite (and for the second and third, the comparison code) changed and
everything recorded again; they wait for the stand-in work of `irc/house-tables-stand-in-fidelity`
to merge, because that changes both stand-ins:

- The commit-path recording was made with the stand-in from before the case-insensitive listing
  change (pull request 158). No recorded route lists a database's tables, so no row is affected
  today; record again once the stand-ins settle.
- Placeholders erase identity: every UUID becomes `<uuid>` and every number of ten digits or more
  `<number>`, so whether two answers name the same table UUID or snapshot is not compared. Fix:
  number the placeholders by first appearance within one test (`<uuid#1>`, `<number#2>`) in
  `LinkedInMainRecording.normalizeValues` (both copies), then record again.
- The commit-path comparison does not see the bodies of successful writes (a 201 or 200 keeps only
  its status), compares errors by `$.message` only, and keeps the answers to one method and URI in
  a test as a sorted multiset, so which request got which answer is lost. Fix: add the sorted,
  normalized body of every 2xx response in `CommitPathHttp.perform`, and number requests sent from
  the test thread by occurrence (as `answerInOrder` does in the Spark copy), then record again.
