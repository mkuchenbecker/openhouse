# Findings, continued

Continues `08-findings.md`, which is near its length limit.

Bottom line: the testing policy of D18 (run what the change touched, merge, fix forward) let a red default build
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
