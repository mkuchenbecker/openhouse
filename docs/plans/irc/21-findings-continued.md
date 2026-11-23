# Findings, continued

Continues `08-findings.md`, which is near its length limit.

Bottom line: the eleventh round is a data-loss risk when a create names the directory of a renamed-away table (V16, N5), now confirmed by running it and closed over IRC. Earlier: the testing policy of D18 (run what the change touched, merge, fix forward) let a red default build
and two wrong claims through in the seventh round. Each was cheap to catch. Briefs now require the cheap checks
(D26). All items are observations from delegated agents and from reading CI.

## Seventh round (what selective testing missed)

- The statement "every native rule holds through the IRC write routes" in the first report of pull request 94 was
  wrong on two counts. Both were found afterwards by reading the code and then shown by failing tests (pull request
  96, recorded in `27-v3-irc-write-result.md`): the native update validator was skipped, and the policy key was
  stored as a stray property.
- A change to `services/tables` main code is embedded by the Iceberg 1.2 fixture. The merge checks for pull request
  94 covered only the Iceberg 1.5 line, so the default build was red on `irc-integration` from 14f23870 until
  cc76d485 (pull request 98). The check that would have caught it takes about two minutes:
  `:tables-test-fixtures:tables-test-fixtures_2.12:test`.
- Default-build modules that no merge check had been exercising:
  - `:tables-test-fixtures:tables-test-fixtures_2.12:test`
  - `:libs:datalayout:test`
  - `:apps:openhouse-spark-apps_2.12:test`
  - the Spark 3.1 itest tasks, and the Spark 3.1 runtime
  - on the 1.5 side: `tables-test-fixtures-iceberg-1.5_2.12:test`, Spark 3.5 `statementTest` and `test`, and the
    1.5 Java itest
- The service did not compile under the opt-in Iceberg 1.12 build between pull requests 88 and 92 and nobody saw
  it, because the post-merge build covered only the default variant. Pull request 97 added a post-merge job for the
  opt-in build (`32-optin-green-ci-result.md`).
- The post-merge workflow first cancelled a running build when a newer merge arrived. Run 2 was cancelled that
  way. Pull request 93 changed it so a running build finishes.

## Eighth round (found while making the IRC routes follow the specification)

- Finding 3, a native defect, not fixed: renaming a table onto a view's name answers 500 on the native API. The
  house tables store refuses it with a key violation, which the native path turns into an unmapped
  `CommitFailedException`, so the client gets a 500 instead of a 409. The rename retries the commit four times and
  leaves five orphan metadata files (with their checksum files) in the source table's directory, and takes about
  1.8 s. The IRC rename goes through the same path and leaves the same files, though it now answers 409. Under D9 it
  is pinned by a test as it is, not fixed silently. Work item N4. This is the native side of defect 2 in
  `39-v13-compat-kit-result.md`. Detail in `42-v13b-spec-deviations-result.md`.
- The in-memory stand-ins for the house table store overwrote a view's row on a rename onto its key, which the real
  store never does, so tests reported a success that production refuses. Both stand-ins now refuse (pull request
  106). The same kind of gap as T5.

## Ninth round (the red opt-in job, 2026-11-23)

- The opt-in Iceberg 1.12 job was red on five post-merge runs in a row and notes said it was green. Two test-side
  causes, fixed by pull requests 107 and 108 (`43-optin-ci-red-result.md`).
- Product, both Iceberg lines, not fixed: the tables service accepts a create whose metadata location will not fit
  House Tables' 255-character column, then answers 503 with commit status unknown (`CommitStateUnknownException`
  at the client) and leaves the metadata file it wrote. Table names have no length check. Seen against the H2
  stand-in; the real store's DDL has the same `VARCHAR(255)`. Work item V15.
- Test: the Spark 4.2 itest's `test` task failed once in six post-merge runs (run 45099517406). The test is unknown
  and path length is ruled out. The job now prints failed tests, so the next occurrence will name it. Work item T8.
- Test: the compatibility kit's service builds its warehouse under the checkout too, and is green on the runner with
  about 23 characters to spare. Work item T9.

## Tenth round (the flip preparation, 2026-11-23)

- Test, possibly the same thing as T8: `SparkIrcEndpointsTest.existenceChecksAreHeadRequests` failed once locally on
  an empty access log and passed on two reruns. The cause is not known. If it recurs in CI, treat it with T8.
- Build, no work item: two generated OpenAPI client classes in `:client:hts` (`PageJob` and `PageUserTable`) and
  `META-INF/spring-configuration-metadata.json` of `services:tables` differ between two builds of the same sources,
  so the build is not reproducible bit for bit. The cause was not determined (`44-v9abc-flip-prep-result.md`).

## Eleventh round (V6 and V7, 2026-11-23)

- Data loss, first read from code, then run (2026-11-23, pull request 116, `48-v16-directory-reuse-result.md`): a
  create that carries the UUID of a live table that was renamed away becomes a second table in that table's
  directory. Dropping it answers 204 and deletes the first table's files, and every read of the first answers 500.
  Run on both APIs. IRC had three entry points, not two (a completion naming the directory, a completion whose
  snapshot's manifest list lies there, a create carrying `openhouse.tableUUID`); all are closed. Natively there
  are four, they need only the create privilege on the database, and they are unchanged and pinned by five tests.
  The fix is put to Mike (D35). Register refused this case from the start.
- Work items: V16 (done), N5 (pinned; fix put to Mike) and T13 (done: four tests, no code change).
- Open, small: an IRC create can still set `openhouse.isTableReplicated` (note on I9); each completion naming a
  location lists the database's house-table rows (V17).
- A note in `46-v6-v7-metrics-register-result.md` says `openhouse.replicaTableLocationId` names a directory; the
  pull request 116 agent read that the base checker filters it out first, so it does not in this build.
- Test, possibly T8: `SparkIrcEndpointsTest.existenceChecksAreHeadRequests` failed once on reading the access log
  before its last line was written. Pull request 112 made the test wait for that line. T8 stays open until a CI
  failure names its test.
- Environment: the shared disk filled twice during runs; reruns passed (`20-iteration-friction.md`).
