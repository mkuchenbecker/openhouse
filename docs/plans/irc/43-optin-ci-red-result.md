# The opt-in Iceberg 1.12 build was red in CI, and is now green: result

Bottom line: the opt-in build (`-PicebergFork`, Iceberg 1.12) was not green in CI, although earlier notes said it
was. The job "Opt-in build against the Iceberg fork" in `post-merge-build.yml` failed on five post-merge runs in a
row, and nobody read the result until 2026-11-23. There were two causes, both in test code or test set-up, and
both are fixed forward by pull requests 107 and 108. Post-merge run 45120792269 on head 3222cf78 is the first run
where both jobs pass. Written 2026-11-23 from two delegated agents' reports. The product was right in both cases.

## What was red, and since when

- The default build was green on every one of these runs. Only the opt-in job was red.
- The opt-in job failed on five runs in a row. The head commits were 6015e111 (pull request 102), c174e388 (103),
  fb8c1240 (104), 7ddc5bb4 (105) and bfdcf266 (106).
- Pull request 102 was the first red run, so the first cause entered there. The second entered with pull request 103.
- The notes `25-optin-green-result.md`, `32-optin-green-ci-result.md` and `40-i6f-full-regression-result.md` said
  or implied the opt-in build was green. They described local runs, and in places listed the suites as compiled
  only. Each now carries a dated correction note at its top. `18-state.md` no longer says "green".

## Cause 1: four tests built Iceberg's HTTP client the old way (pull request 107)

- Pull request 107, merge commit `152b08e70d09b1f935f90636b1961dfd7348c803`.
- Four tests in `IrcGrantsUnlockHttpClientTest` (added by pull request 102) built Iceberg's `HTTPClient` the way
  Iceberg 1.5.2 does. Iceberg 1.12 refuses to send a request without an auth session and throws
  "Invalid auth session: null". Test code only. The product's own client sets a session and was right.
- The suite was compiled under the switch and never run there. `36-v5-3-grants-unlock-endpoints-result.md` lists it
  under "not run (compiled only)", and `40-i6f-full-regression-result.md` lists the tables service under "not run"
  on the reasoning that the CI job covers it. The CI job did cover it, and it failed.
- With a session set, one assertion still failed: on 1.12 Iceberg's default error handler writes
  "Unable to process (code: 404, type: ...)" where 1.5.2 writes "Unable to process:". The test now states the
  message per line. No assertion was removed or loosened.

## Cause 2: the warehouse path made one table's location too long (pull request 108)

- Pull request 108, merge commit `3222cf7842d78be031142ebd3eaa7f6a54a4db20`.
- The Spark 4.2 test services kept their warehouse under the checkout directory. House Tables holds a table's
  metadata location in 255 characters. On the GitHub runner the checkout path is 37 characters, 18 more than the
  local worktrees, which made the location of the table `legacy_lock_by_reason` 260 characters.
- `SparkIrcGrantsUnlockTest` failed in its set-up on the runner with a 503 "commit status unknown", and passed in
  every shorter local checkout. The longest location locally was 245 characters.
- The warehouse now lives in a short directory under the system's temporary directory, removed at exit, and the
  helper refuses a temporary directory deeper than 90 characters. A new test creates a table with a 60-character
  name and checks that its location fits 255 characters. With the old layout it fails in any checkout.

## Why local runs did not show either

- Nobody ran the suites under the switch after pull request 102. The local runs that were reported "green" were
  compiles, or ran other suites.
- The job's log was unreadable from the working session: the tool returns only the last 5000 lines and the full-log
  host is blocked, so the failed tests' own lines were out of reach. Pull request 107 therefore makes the job print
  each failed test with its error and keep the test reports for 7 days. That change worked on its first run and
  showed cause 2 at once.
- Pull requests are not built under the switch, so both failures merged without a red check (D33).

## The first green run

- Post-merge run 45120792269 on head 3222cf78: the default build passes in 24 minutes, and the opt-in build
  passes in 16 minutes, including the compatibility kit step.

## What is still unexplained or not fixed

- The Spark 4.2 itest's `test` task failed once in six post-merge runs (run 45099517406). The test is unknown and
  path length is ruled out (its longest location is 225 characters on the runner). Work item T8.
- The tables service accepts a create whose metadata location cannot fit House Tables, then answers 503 and leaves
  the metadata file. Present on both Iceberg lines. Not fixed. Work item V15, finding in `21-findings-continued.md`.
- The compatibility kit's service also keeps its warehouse under the checkout, with about 23 characters to spare on
  the runner. Work item T9.
- Pull request 108's fix is proven locally in a deeper checkout and by the green run above.
