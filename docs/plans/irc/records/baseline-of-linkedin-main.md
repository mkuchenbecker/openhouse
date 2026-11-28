# baseline-of-linkedin-main (FF-20 to FF-24)

Bottom line: in progress. Worktrees created: /home/user/oh-baseline (branch irc/baseline-of-linkedin-main at 0d54d2fd), /home/user/oh-main (detached bb8929e1).

## Log
- 2026-11-28 start: read brief, FF-20..24, RF-060/063, review D2/D5/section 5.
- Main builds here with JDK 17 (/usr/lib/jvm/java-17-openjdk-amd64, main CI uses microsoft 17), its Gradle 8.14.5 wrapper; Spark 3.5.2, LinkedIn Iceberg 1.5.2.24 (from cache). compile ok in 1m18s.
- Recorder design: hooks outcome()/value() in SparkSqlSmokeScenarios/BaselineChecks; LinkedInMainRecording/Comparison/Answers (spark shared/smoke; copy in services/tables e2e/h2); LegacyPathSparkSqlSmokeTest + new LegacyPathWriteStatementsTest compare; CatalogPluginContractTest via thrown() + verdict; CommitPath* via CommitPathHttp.perform + verdict.
- spark run1/run2 on main: s10 MoR read fails on main only from main's itest test classpath (iceberg-data unshaded ORC) -> init script excluding iceberg-data on the test runtime classpath (no file change in main). s31 #5 tag order varied between run1 and run2 on main (HashSet order).
- Recording on main uses the branch's House Tables stand-in (test code; faithful CAS) and GrantStoringAuthorization (test backend), same fixtures as the branch, so only production code differs.
