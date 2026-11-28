# V9e: Iceberg 1.12 is the default build

Bottom line: done. Pull request 118 is merged into `irc-integration` as merge commit
`440249d1acdd2930add70d5e3b792c6c15eb40b3`. A plain build is now the fork's Iceberg 1.12 line on Java 17. With it
all five flip items (V9a to V9e) are done. No Spark SQL difference was found. Not proven: the Spark image and the
dataloader integration tests on a CI runner, which run by hand only. Written 2026-11-23 from the delegated agent's
report on pull request 118 (head `e8287715`); the numbers below are its measurements.

## What the build is now

- No property: the fork's Iceberg 1.12 line, with the Spark 3.5 projects, the Spark 4.2 modules and Apache's
  compatibility kit, built for Java 17.
- `-PicebergLinkedIn`: LinkedIn's Iceberg 1.5.2 line with Spark 3.5 and a Java 8 target. No Spark 4.2, no kit, no
  deployables. It is kept only as the behaviour reference (D8).
- `-PicebergFork`: a deprecated no-op for one cycle. It is an error together with `-PicebergLinkedIn`.
- Delta-harness: `HARNESS_ICEBERG_LINKEDIN=1` selects the 1.5.2 line; `HARNESS_ICEBERG_FORK=1` does nothing.
- `./gradlew build` now includes the compatibility kit. A plain build needs `scripts/iceberg-fork/publish-local.sh`
  run once per pinned commit per machine, and stops with a message that names it otherwise.
- The default build refuses to publish to a remote Maven repository, because its Iceberg dependency is hosted
  nowhere. Publishing the fork's Iceberg jars anywhere stays Mike's decision (on the waiting list).

## What was deleted

- The Iceberg 1.2 / Spark 3.1 projects (D3): `:apps:spark`, the Iceberg 1.2 Java runtime and itest, the Spark 3.1
  runtime and itest, and the Iceberg 1.2 test fixtures; 46 files, their settings entries and conventions plugin.
- The Spark 3.1 image file, the fork env file, the old jobs file, one CI workflow, and the Renovate 1.2 rule.
- Published Maven names that are no longer built: `openhouse-spark-runtime_2.12`, `openhouse-spark-apps_2.12`,
  `tables-test-fixtures_2.12`, `openhouse-java-runtime` (each also with `-uber`), `openhouse-java-itest` and
  `openhouse-spark-itest`. No new name appears.
- The `jobs-scheduler` image keeps its name. Jar names inside the Spark image are unchanged.

## Evidence

- Pull request CI was green on both jobs at the final head, at the first attempt: the default job took 27 minutes
  and the reference job 19. Together that is 43 to 46 minutes, against 46 to 52 before.
- Locally, with every task executed: 4046 tests on the default line and 3739 on the reference line, 0 failed, 6
  skipped on each. `spotlessCheck` passed.
- Jar contents are equal to before on each line, apart from explained differences (a `log4j2.properties` and the
  classes of pull request 119).
- The delta-harness on the new default: 2556 of 2574 cases pass, 7 fail, 11 are skipped. That is the earlier 1.12
  result plus the two time-travel cases fixed since (I11). The 7 failures are the earlier ones with the same messages.
- The three Spark 3.1-only tests were ported and pass unchanged on Spark 3.5 on both lines (one asserts a more
  exact error class). So there is no Spark SQL difference between Spark 3.1 and 3.5 for them.
- Failed publishing was checked by dry runs only: the default refuses a remote publish before any task runs, the
  reference line and a local publish succeed. Nothing was published.

## Known gaps

- The Spark image, the Hadoop recipe and the dataloader integration tests have never finished on a CI runner on this
  line and run by hand only (C10, about to start).
- The release workflow pushes its tag before its publish step, which the guard stops. It triggers only on `main` of
  LinkedIn's repository and was not changed (C13).
- The tables service still carries guards for an Iceberg 1.2 runtime that no longer exists (V18).
- Not run: the Spark image, `oh-hadoop-spark`, real S3, harness real-HTS and rest-shim modes, any image build on the
  agent's machine.

## Follow-up work items

- V18: remove the service's Iceberg 1.2 guards and their three boundary tests. Low priority.
- C11: fold `src/shared` into the standard source roots, rename the misleading 1.5 names, and remove `-PicebergFork`
  and `HARNESS_ICEBERG_FORK` after one cycle. Low priority.
- C12: set the Java source encoding to UTF-8 in the build. Small.
- C13: the release workflow's order of tag and publish. Small; belongs with the publishing decision.
