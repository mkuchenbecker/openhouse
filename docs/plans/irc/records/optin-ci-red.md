# The red opt-in Iceberg job: diagnosis and fix-forward (pull request 107)

Written 2026-11-23 by a delegated agent on branch `irc/optin-ci-red` (worktree `/home/user/oh-cired`,
base `bfdcf266`). Every fact is marked [ran] (done here), [read] (taken from a log, file or record)
or [inferred] (reasoned from those).

## Bottom line

- The job "Opt-in build against the Iceberg fork" has two independent failures, both deterministic,
  both from tests that merged without being run in the configuration that CI runs.
- Failure 1, `:services:tables:test`, 4 failed on all five runs: fixed in pull request 107 and proven
  locally. It was test code, not a product defect. The local runs "passing" did not happen: the test
  was compiled under `-PicebergFork` but never run.
- Failure 2, Spark 4.2 `extensionsTest`, red on the four runs since pull request 103: **not fixed and
  not explained**. It passes locally. Its error text has never been seen. Pull request 107 adds what
  makes the next CI run show it. The job will stay red after the merge until that is fixed.
- Pull request 107, draft, into `irc-integration`, head `cf419dcef8d2d04353dcadf932cf0b13f6c531ad`.

## What limited the evidence [ran]

- `get_job_logs` returns at most 5000 lines from the end of a job log (a request for 6000, 7300 or
  100000 lines each returned 4999). The logs are 7240 to 10374 lines.
- The full-log URL is on `productionresultssa*.blob.core.windows.net`, which the session's network
  refuses (curl: CONNECT 403; WebFetch: host not allowed). `gh` has an invalid token.
- So for every run the first 2240 to 5370 lines of the job log are unreadable, and the failed tests'
  own lines fall in that part each time. The tails are saved under
  `/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/ci-logs/`.

## Per run [read unless marked]

All five: job conclusion failure, failing step "Run the opt-in suites under -PicebergFork", default
build job success. The compatibility-kit step that follows prints about 100 `FAILED` lines and
passes (its ledger expects them); it is not part of the red.

| Run | Head | Failed tasks | Counts seen |
| --- | --- | --- | --- |
| 45093336824 | 6015e111 (#102) | `:services:tables:test` | 1528 completed, 4 failed |
| 45099517406 | c174e388 (#103) | tables `test`; Spark 4.2 itest `test`; Spark 4.2 itest `extensionsTest` | 1528, 4 failed |
| 45104678473 | fb8c1240 (#104) | tables `test`; Spark 4.2 itest `extensionsTest` | 1529, 4 failed |
| 45106413808 | 7ddc5bb4 (#105) | tables `test`; Spark 4.2 itest `extensionsTest` | 1529, 4 failed; extensionsTest: 11 completed, 1 failed |
| 45111802869 | bfdcf266 (#106) | tables `test`; Spark 4.2 itest `extensionsTest` | 1536, 4 failed |

Run 45111802869 was in progress at 04:27Z and completed red at 04:42:08Z [ran].

Failed test names and first error lines:

- tables `test`, every run: not readable from CI. Reproduced locally at bfdcf266 [ran], all in
  `com.linkedin.openhouse.tables.e2e.h2.IrcGrantsUnlockHttpClientTest`:
  - `everyOperationIsReachableWithIcebergsHttpClient`: `java.lang.IllegalStateException: Invalid auth
    session: null` at `org.apache.iceberg.rest.HTTPClient.buildRequest(HTTPClient.java:289)`
  - `theClientsMapperReadsCamelCaseFieldsOnlyUnderTheirNativeName`: the same
  - `refusalsSurfaceAsIcebergExceptionsWithTheNativeMessage`: `AssertionFailedError: Unexpected
    exception type thrown, expected: <BadRequestException> but was: <IllegalStateException>` (the same
    cause)
  - `aServerFailureIsServiceFailureException`: `AssertionFailedError: ... expected:
    <ServiceFailureException> but was: <IllegalStateException>` (the same cause)
  That these four are CI's four [inferred]: the count matches on every run; the class was added by
  #102, the first red run; and every other test-bearing class of `services/tables` has its tests in
  at least one CI tail with no `FAILED` line (checked by script against the declared classes) [ran].
- Spark 4.2 `extensionsTest`: name and error not readable. The task has 23 tests in four classes
  (13, 5, 2, 3) [ran locally]. "11 completed, 1 failed" is only 23 - 13 + 1, so
  `SparkIrcGrantsUnlockTest` (13 tests, added by #103) fails as a class, in `@BeforeAll` [inferred].
  The tail shows `SparkIrcPolicyStatementsTest` and `SparkJvmClasspathTest` passing in that run [read].
- Spark 4.2 `test`, run 45099517406 only: nothing readable. It passed on the other four runs, so it
  is intermittent or was specific to that head [inferred].

Deterministic or not: both main failures are the same on every run where their test exists
[read]. No sign of Maven Central 429, a missing fork artifact or a cache problem in CI: the fork
restore and publish steps succeed and compilation succeeds on every run [read].

## Failure 1: cause, and why "local passed"

- Cause [ran]: `IrcGrantsUnlockHttpClientTest` builds Iceberg's `HTTPClient` with
  `HTTPClient.builder(...).uri(...).withHeader(...).build()`. On Iceberg 1.12 `HTTPClient` refuses to
  send without an `AuthSession`; 1.5.2 has no such type (checked with `javap` on both jars). Test code.
  The product's REST capability client has its own 1.12 transport that sets a session
  (`integrations/java/openhouse-catalog-capabilities/src/iceberg-1.12/.../RestTransport.java`) [read].
- With a session set, one assertion still failed on 1.12 [ran]: the default error handler's message
  is `Unable to process (code: 404, type: NoSuchTableException): ...` where 1.5.2 writes `Unable to
  process: ...` (`ErrorHandlers.createRESTException`, read in an Iceberg 1.12 checkout, not the pinned
  commit; the pinned jar behaved the same in the run) [read, ran].
- Why local passed: it did not. `36-v5-3-grants-unlock-endpoints-result.md` line 127 lists
  "`:services:tables:test` under `-PicebergFork` (compiled only)" under what was not run [read].
  `40-i6f-full-regression-result.md` lists `:services:tables`, `internalcatalog`, `htscatalog` and the
  Spark 4.2 tests under "Not run" ("the opt-in job covers them") [read].
  `32-optin-green-ci-result.md` ran the suite at 1390 tests, before #102 [read].
- The job's exact commands and the local ones are the same Gradle tasks [read]; the difference was
  only that nobody ran this task after #102.

## Failure 2: what was tried, what is ruled out

- `-PicebergFork ...:openhouse-spark-4.2-itest:extensionsTest` alone: 23 / 0 [ran].
- `...:test ...:extensionsTest` in one build, as CI orders them: 38 / 0 and 23 / 0 [ran].
- The service process alone, started as user `nobody` with an empty environment (no
  `JAVA_TOOL_OPTIONS`), and that class's set-up calls replayed through its control API: create with
  the sharing patch, create without, LEGACY lock, SYSTEM_ONLY lock all answered 200 and the lock
  states read back right [ran]. So it is not root versus an unprivileged user, and not this
  container's proxy settings.
- Read and ruled out [read, inferred]: a different class order (the order is alphabetical locally
  and, from which classes the CI tails hold, in CI; the class is then first in both); the service failing to start (the two later classes use the same
  service and pass); the native lock route or the sharing policy on the server (the Spark 3.5 twin,
  `IcebergRestGrantsUnlockSqlTest`, does both in CI under the switch and passes); file permission
  changes (the fixture application's file securer is a no-op); path-based logic.
- Not ruled out: anything that differs between a GitHub runner and this container beyond user and
  environment (JDK vendor, resolved dependency versions, load from the three other test tasks that run
  at the same time, the Gradle build cache). No guess was acted on.

## What changed (2 commits, 4 files, +195 -4)

1. `0045226b` test: `IrcGrantsUnlockHttpClientTest` gives the builder `AuthSession.EMPTY` where
   `HTTPClient.Builder` has `withAuthSession` (by reflection; the class compiles on both lines), and
   states the default handler's message per line for its two cases (404, 409). No assertion removed
   or loosened. No product code.
2. `cf419dce` ci: `post-merge-build.yml` gives the suites step an id and adds two last steps that
   run only when it failed: `scripts/ci/print-failed-tests.py` (new) prints each failed test case from
   the JUnit XML with the first 60 lines of its failure and every "Caused by", then the end of the
   Spark 4.2 service logs and Tomcat access logs, and emits up to ten error annotations;
   `actions/upload-artifact@v4` keeps reports, results and those logs 7 days as
   `iceberg-fork-test-reports`. `docs/development/iceberg-fork.md` describes both and says pull
   requests do not run this job.

## What was run [ran], JDK 17, two workers, one build at a time

| Command (all with `--max-workers=2 -Dorg.gradle.daemon.idletimeout=60000`) | Result |
| --- | --- |
| `-PicebergFork :services:tables:test`, 25 classes absent from the CI tails, before the fix | 160 run, 4 failed |
| `-PicebergFork :services:tables:test --tests 'IrcGrantsUnlock*' --tests IrcConfigEndpointsTest`, auth session only | 29 run, 1 failed (the message assertion) |
| the same, final test | 29 run, 0 failed (HTTP client class 5 / 0) |
| the same on the default build, final test | 29 run, 0 failed |
| `-PicebergFork` Spark 4.2 `extensionsTest` | 23 / 0 |
| `-PicebergFork` Spark 4.2 `test` + `extensionsTest` | 38 / 0, 23 / 0 |
| `:services:tables:spotlessApply spotlessCheck` | pass (one line re-wrapped) |
| `actionlint` 1.7.12 on `post-merge-build.yml` | pass |
| `print-failed-tests.py` on a made-up tree and on the real results | 2 failures printed as expected; 12 files, 0 failures |

Not run: the whole `:services:tables:test` under the switch; `tables-test-fixtures` tests and the
two-way compile of `:services:tables` (no main code was touched); the new CI steps on GitHub.
The first local attempt failed before any test at `:client:common:setUp` (Maven Central answered 429
for the OpenAPI generator jar); the jar was copied from `/home/user/oh-rckfix/build/openapi-cli`.
The fork repository was copied from `/home/user/oh-rckfix/build/iceberg-fork/repo` (same pinned
commit); `publish-local.sh` then reported it already published.

## Proven or expected

- Failure 1: proven locally on both builds. Expected, not proven, in CI: pull-request CI runs only
  the default build, so the first run of the opt-in job on this change is the post-merge one.
- The new CI steps: linted and the script exercised locally; never run on GitHub.
- Failure 2: open. Expected outcome of the next post-merge run: tables `test` passes, `extensionsTest`
  still fails, and the job log's last lines name the test and its error.

## Known gaps and things to decide

- The job stays red until failure 2 is fixed. A follow-up item is needed once the error is visible.
- A way to see the error before merging: run `post-merge-build.yml` by hand (`workflow_dispatch`)
  on `irc/optin-ci-red`. Not done: the brief forbids re-running runs, a hand run also runs the
  30-minute default job, and that job writes a Gradle cache for the branch, which could push older
  caches (the 410 MB fork repository among them) out of the repository's cache quota.
- Pull requests are never built under `-PicebergFork`. Both failures entered that way. Until that
  changes, a change to tests that run under the switch needs a local run under the switch.
- `actions/upload-artifact@v4` is new to this repository; its newest major could not be checked from
  here. v4 was chosen because it is known to exist.
- One commit ran its hook while available memory read 4 GB, under the 5 GB rule; later commands waited.
- On Iceberg 1.12 a `RESTException` from the default error handler now reads `Unable to process
  (code: ..., type: ...): ...`. Only this test pinned the old text; nothing in the product depends on
  it as far as was read.
