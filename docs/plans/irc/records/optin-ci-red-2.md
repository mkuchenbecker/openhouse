# The red opt-in Iceberg job, part 2: the Spark 4.2 failure (pull request 108)

Written 2026-11-23 by a delegated agent on branch `irc/optin-ci-red-2`, worktree
`/home/user/oh-cired2/work/openhouse/openhouse`, base `152b08e7` (the merge of pull request 107).
Continues `optin-ci-red.md` in this directory. Facts are marked [ran], [read] or [inferred].

## Bottom line

- The new CI steps from pull request 107 worked on their first run and showed the error.
- `:services:tables:test` is green in CI after pull request 107.
- The Spark 4.2 `extensionsTest` failure is explained and fixed: the checkout's path on a GitHub
  runner is 18 characters longer than the local one, which pushed a table's metadata location past
  the 255 characters House Tables keeps. Test harness, not product code. Reproduced locally by
  checking out at a deeper path, fixed, and proven there. In CI it is expected, not proven.
- Pull request 108, draft, into `irc-integration`, head `f4a7ed5062a57b6b4a69a0cacc588a07b01614aa`.

## Step 1: the post-merge run of 152b08e7 [read]

Run 45116142211. Opt-in job 137499318470: failure, 05:10:50Z to 05:27:24Z. Default job: success,
31 minutes.

- "Print the opt-in suites' failed tests" and "Upload the opt-in suites' test reports" both ran and
  succeeded. The summary starts 300 lines from the end of the log, inside any tail. An error
  annotation was emitted. Nothing needed fixing.
- The summary lists 11 result directories. `build/tables/test-results/test: 136 result files, 0
  failed test cases`. Only `extensionsTest` has a failure (4 files, 1 failed). Spark 4.2 `test`: 5
  files, 0 failed.
- The failure:
  `FAILED (failure) com.linkedin.openhouse.spark42.extensions.SparkIrcGrantsUnlockTest >
  initializationError` /
  `java.lang.IllegalStateException: create legacy_lock_by_reason:
  org.apache.iceberg.exceptions.CommitStateUnknownException: {"status":"SERVICE_UNAVAILABLE", ...
  "message":"Table with key[local-cluster.irc42x.legacy_lock_by_reason] isn't ack'ed by server about
  the commit status, nested exception: could not execute statement; SQL [n/a]; nested exception is
  org.hibernate.exception.DataException: could not execute statement ..."}` /
  `503 Service Unavailable from PUT http://localhost:34419/v1/databases/irc42x/tables/legacy_lock_by_reason`
  at `ExtensionsTestService.create(ExtensionsTestService.java:138)`,
  `SparkIrcGrantsUnlockTest.createTables(SparkIrcGrantsUnlockTest.java:45)`.
- So the earlier inference (the class fails in `@BeforeAll`) was right, and the earlier list of
  things ruled out missed the cause: path length.

## Root cause

- `HouseTable` (the entity the H2 stand-in stores) has plain `String` columns, which Hibernate
  creates as VARCHAR(255) [read]. The real store's baseline DDL has `metadata_location
  VARCHAR(255)` too (`services/housetables/ddl/0000__baseline.sql`); `schema.sql` says 512 [read].
- A metadata location is `<warehouse>/<database>/<table>-<uuid>/<version>-<uuid>.metadata.json`:
  the warehouse path, 96 characters and the two names [ran, counted].
- Both Spark 4.2 test services put the warehouse at
  `<checkout>/build/openhouse-spark-4.2-itest/tmp/<task>/<service>-<19 digits>/warehouse` [read].
- Runner checkout `/home/runner/work/openhouse/openhouse` is 37 characters; the local worktrees were
  19. Computed for the runner: `shared` 245, `grant_locked` 251, `legacy_lock` 250,
  `legacy_lock_by_reason` 260, which is the first table that failed in CI [ran, computed; read].
- Exact limit seen locally in a 45-character checkout: a 255-character location stored, 257 and 259
  refused with 503 [ran].
- Why local passed: the longest location locally was 245 [ran]. The same code, a shorter path.

## Fix (2 commits, 5 files, +117 -7)

1. `3cf4febc` test: `ExtensionsServiceWarehouseTest` creates a table with a 60-character name
   through the service and checks that its metadata location fits 255 characters. With the old
   layout that location is the checkout's path plus about 260 characters, so it fails in any
   checkout. Fails at this commit.
2. `f4a7ed50` fix: `ServiceWarehouse` (new, service source set) creates the warehouse as an `oh42-*`
   directory under the system's temporary directory, deletes it at JVM exit, and refuses a temporary
   directory deeper than 90 characters with an explaining message. `IrcReadTestService` and
   `IrcExtensionsTestService` use it and name it in `ready.json`. Logs and work directories stay
   under `build/`. `docs/development/iceberg-fork.md` updated.
No existing test changed. No product code changed.

## What was run [ran], JDK 17, two workers, one build at a time, 5 GB available before each

All from the 45-character checkout, with `--max-workers=2 -Dorg.gradle.daemon.idletimeout=60000`.

| Command | Result (run / failed) |
| --- | --- |
| `-PicebergFork ...openhouse-spark-4.2-itest:extensionsTest`, unchanged 152b08e7 | 7 / 2, the CI error (`grant_locked`, `statements`) |
| the same with only the new test added | 8 / 3, the new test fails at its create |
| `-PicebergFork ...:test ...:extensionsTest` with the fix | 38 / 0 and 24 / 0 |
| the same again on the formatted, final tree | 38 / 0 and 24 / 0 |
| default build, `...openhouse-spark-4.2-itest:test --dry-run` | "project 'spark-4.2' not found": the suites do not exist there |
| `-PicebergFork ...openhouse-spark-4.2-itest:spotlessApply spotlessCheck` | pass |

After the runs no `oh42-*` directory was left in `/tmp`. The first attempt of the first command
failed before any test at `:services:jobs:generateOpenApiDocs` (it waits for port 8002, which other
builds on this machine use); the rerun went through.

Both counts required by the new rule: under `-PicebergFork` 38 / 0 and 24 / 0; on the default
build 0 / 0 because the module is not part of that build.

## Proven or expected

- Proven locally in a checkout deeper than the runner's. In CI: expected. Pull requests do not run
  the opt-in job, so the post-merge run of this merge is the first.
- Expected next post-merge run: the opt-in job green, unless the unexplained Spark 4.2 `test`
  failure of run 45099517406 happens again; the job would now print it.

## Not changed, for a decision

- Product: the tables service accepts a create whose metadata location will not fit House Tables,
  then answers 503 "commit status unknown" and leaves the metadata file it wrote (257- and
  259-character files were on disk) [ran, against the H2 stand-in only]. Table names have no length
  check [read]. Present on both Iceberg lines; not specific to 1.12.
- The REST compatibility kit's service builds its warehouse the same way [read]. Longest location
  215 characters in a 20-character checkout [read from `/home/user/oh-rckfix/build`], so about 232
  on the runner: green with 23 to spare; a checkout deeper than about 60 characters would change its
  outcomes. Left alone because it is green and its outcomes are a reviewed list.
- Spark 4.2 `test`, failed once in six runs. Its longest location is 207 locally, 225 on the runner,
  so path length was not its cause [ran, computed]. Unknown.

## Should pull request CI run the opt-in job? (recommendation only; nothing changed)

Yes. It is cheap.

- Runner time [read from six runs]: the opt-in job takes 11.5 to 18 minutes; 16.5 on the last run
  (set-up 0.6, compile 2.2, suites 12.5, kit 1.1). The default job takes 24 to 34 minutes and runs
  beside it, so a pull request's wall-clock time does not grow. Cost: about 15 runner-minutes per
  push to a pull request.
- Fork repository cache [read]: the key is the pinned commit plus the script's hash. A pull request
  can read a cache saved on its base branch (GitHub's rule, from memory, not checked here), so it is
  a hit and the save step is skipped. A pull request that changes the pin or `publish-local.sh`
  misses: a cold fork build (60 to 75 minutes per `32-optin-green-ci-result.md`) and one cache of
  about 410 MB saved for that pull request only.
- Gradle cache [read]: the job already sets `cache-read-only` for every ref but `irc-integration`,
  so pull requests would read and not write.
- How: move the opt-in job to its own reusable workflow and call it from both
  `post-merge-build.yml` and `pr-validations.yml`, with the pull-request concurrency rule, rather
  than copying its steps.
- Risk: a pull request check goes red for the one unexplained intermittent failure above.
- What it buys: both failures of this job entered through pull requests that compiled but never ran
  a suite under the switch. Five red post-merge runs would have been two red pull requests.

## Housekeeping

- The worktree is at a deep path on purpose; `/home/user/oh-cired2` itself is a plain directory.
- `/home/user/oh-cired` (branch `irc/optin-ci-red`, merged) is still there, unchanged.
- An idle Gradle daemon that had served only this worktree was stopped by PID. Another daemon that
  also served `/home/user/oh-v9c` was left alone. No Gradle build, test JVM or service of this task
  is running.
