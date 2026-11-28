# baseline-of-linkedin-main (FF-20 to FF-24)

Bottom line: done. Draft pull request 161, head 210d54f85ee43f17bdd2160bfae525d7c0908b59, all 13 CI jobs green (1 skipped by design). The suites compare with a 6-run recording of LinkedIn main bb8929e1; reference line differs on 9 answers, all namespace stack (Part D); default line on 17, two causes NEW (s26 row-lineage columns, Jackson 500 location text). FF-20, FF-21 done; FF-22 partly (list + check); FF-23, FF-24 left with plans. Worktrees created: /home/user/oh-baseline (branch irc/baseline-of-linkedin-main at 0d54d2fd), /home/user/oh-main (detached bb8929e1).

## Log
- 2026-11-28 start: read brief, FF-20..24, RF-060/063, review D2/D5/section 5.
- Main builds here with JDK 17 (/usr/lib/jvm/java-17-openjdk-amd64, main CI uses microsoft 17), its Gradle 8.14.5 wrapper; Spark 3.5.2, LinkedIn Iceberg 1.5.2.24 (from cache). compile ok in 1m18s.
- Recorder design: hooks outcome()/value() in SparkSqlSmokeScenarios/BaselineChecks; LinkedInMainRecording/Comparison/Answers (spark shared/smoke; copy in services/tables e2e/h2); LegacyPathSparkSqlSmokeTest + new LegacyPathWriteStatementsTest compare; CatalogPluginContractTest via thrown() + verdict; CommitPath* via CommitPathHttp.perform + verdict.
- spark run1/run2 on main: s10 MoR read fails on main only from main's itest test classpath (iceberg-data unshaded ORC) -> init script excluding iceberg-data on the test runtime classpath (no file change in main). s31 #5 tag order varied between run1 and run2 on main (HashSet order).
- Recording on main uses the branch's House Tables stand-in (test code; faithful CAS) and GrantStoringAuthorization (test backend), same fixtures as the branch, so only production code differs.
- record.sh (scripts/linkedin-main-baseline) written; fixes on the way: JDK 17 forced (Java 21 breaks main's Lombok), test tasks --rerun (record dir not a task input), key normalization of time-valued request params, "#n" numbering not treated as Spark expression ids, GET bodies with JSON keys sorted (main's Spring Page JSON key order varies per JVM; branch's order differs too -> not compared).
- Branch reference line vs main (first full run): smoke 6 differences (s19 CREATE/USE, s24 DESCRIBE/ALTER/DROP/USE: namespace records, ledger Part D), write statements 0, contract 3 (namespace), commit-path: only JSON key order (now normalized; re-recording with RUNS=6).
- Branch default line vs main: smoke 7 (s19/s24 namespace + NEW: s26 DESCRIBE TABLE EXTENDED lists _row_id and _last_updated_sequence_number metadata columns on a v2 table, Iceberg 1.12), write 0, contract 7 (namespace, capabilityMismatch).
- FF-22: scripts/ci/iceberg-line-branches.tsv + check-iceberg-line-branches.py + split-build plan job step.
- s31 tag order: main varies per JVM (seen HC,PII and PII,HC); branch always PII,HC which is one of main's answers -> not a declared difference.
- Commits pushed: 0d35dfb3 (FF-20), 1cd55eff (FF-21), 1eb9075c (FF-22). Merged origin/irc-integration 8a5a9980 (b872ed1b, clean); checker caught a changed rest-http branch -> 035765ea.
- Ref line (before merge): all touched classes green. Default line: green after declaring 17 (incl. NEW: s26 row-lineage columns; 3 commit-path 500s with jackson-core 2.22.2 vs 2.14.3 location text).
- Final re-recording (header now names loaded Iceberg jars; IcebergBuild said 1.2.0.6 from another jar) needed main-side empty CommitPathLinkedInMainDifferences (recorder/tables). Running RUNS=6 as raw-final3.
- PR body draft at scratch baseline/pr-body.md.
- CI time added: LegacyPathWriteStatementsTest ~14 s per line; check script 0.1 s.
- Race-thread answers now status only; re-recorded RUNS=6 (raw-final4). Final runs both lines: 183 tests each, all pass. check-test-groups: partition holds.
- Commit aaa84a6d pushed; draft pull request 161. Main worktree removed (sources and status saved in scratch baseline/main-worktree-final).
- Waiting on CI.
- CI on aaa84a6d: spark-3.5 itest red on both lines, only s26 (table Owner = OS user: root vs runner). Fixed by normalizing the OS user (0fe4678a), re-recorded RUNS=6 (raw-final5). Merged irc-integration 4b9d152d (#158; stand-in listing now case-insensitive) as 210d54f8; 183 tests each line green. Pushed 210d54f8. Waiting on CI.
- CI on 210d54f8: all success (Default build Docker on reference line skipped by design). Main worktree removed.

## Review fixes (review of pull request 161, MERGE AFTER FIXES)
- 5f4db2e4 M1: test-doubles header + stand-in measurement (FIXTURES_STAND_IN=branch): 0 of 424 Spark keys differ; claim corrected.
- 1877920a M2: NOT ON MAIN marker removed; old marker refused at parse; tests.
- d1b4eecf M3: checker finds IcebergV3.supported() and openhouse.iceberg.line, counts per line (92 occurrences, 50 lines).
- b361eeca S3: merge-on-read DELETE entry refiled as difference from main; write statement not added (shared by 4 platforms).
- 29bfbe28 S1: recorded tests that are gone or disabled fail unless in absentTests().
- 090e1d61 S6: record mode refused unless commit bb8929e1 and no branch-only class.
- 6b3db036: decided wordings (Jackson 2.16 default change; fork SparkTable).
- bd5459f0: README for record.sh.
- 73da643b merge of irc-integration a0239ad7 (#163 one stand-in); 6f2fc38d: 16 stand-in differences declared (kind "stand-in difference"), renamed test in absentTests, absent list scoped per class; record.sh updated; re-measured Spark with the new stand-in on main: 0 different. Both lines 195 tests green. Pushed 6f2fc38d. PR body updated.
