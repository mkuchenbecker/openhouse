# T10 result: five Spark 3.5 test classes that were never compiled now run

Bottom line: five Spark 3.5 end-to-end test classes for ALTER TABLE and CREATE TABLE (12 tests) had never been
compiled since LinkedIn wrote them (LinkedIn pull request 221). They now compile and run on both builds, with no
failures. Seven of the 12 failed at first, all because of two stale test expectations that LinkedIn had already
corrected in the Spark 3.1 copies. There was no Spark 3.5 defect and no Iceberg 1.12 difference among them. Three
Spark 3.1 tests have no Spark 3.5 equivalent; they are ported to Spark 3.5 as the first step of V9e (D37). Written
2026-11-23 from the agent's report; the orchestrator's decision is marked as such.

- Pull request 115 into `irc-integration`, merged 2026-11-23, merge commit
  `5ea51d35aacd62f124ce2a05ad7c1c004ad2a3c4`. The change is 4 files, 4 lines added and 15 removed, with no product
  code.

## Why they never ran

The Spark 3.5 test project excluded `**/AlterTable*.java` and `**/CreateTable*.java`. LinkedIn's pull request 221
copied those excludes from the Spark 3.1 project, where they hid copies of the Spark 3.1 test classes, and they
also hid the five new Spark 3.5 classes. So the five were written and never compiled.

## What was wrong with them

- Six tests queued three "not found" answers before the create. The client reads twice, not three times, since
  LinkedIn's pull request 538 (April 2026), which fixed the Spark 3.1 copies and did not reach these. The fix
  queues two.
- One test (`CreateTableTestSpark3_5.testAuthZFailure`) expected a client exception class that the client has not
  thrown since 2024. It now expects the class, the message and status 403, as the Spark 3.1 copy does.
- The other five tests passed untouched. All 12 give the same results on the default build and on the Iceberg 1.12
  build.

## Counts

All figures were read from the test result files.

| Build | Spark 3.5 test project, before | After |
| --- | --- | --- |
| Default | 530 tests in 61 classes | 542 in 66 |
| Iceberg 1.12 switch | 530 in 61 | 542 in 66 |

There were no failures and no errors on either build. The Spark 3.1 project is unchanged at 350 tests in 45
classes. The style check and the format check pass. The branch was merged with `irc-integration` twice and
everything was run again each time.

## Ten Spark 3.1-only classes against Spark 3.5

The agent compared the ten Spark 3.1-only test classes with the Spark 3.5 project. Seven have an equivalent that
runs. Three tests have none:

- Two on bucket partitioning: that `DESCRIBE` shows a bucket transform, and that a bucketed create makes the
  right number of partitions.
- One on blocking `SET TBLPROPERTIES('policies'=...)`.

The decision (the orchestrator's, D37): they are ported to Spark 3.5 and run there before the Spark 3.1 project is
deleted, as the first step of V9e. Any difference from Spark 3.1 behaviour is reported as a finding.

## Smaller findings, not changed

- The premise that ALTER TABLE and CREATE TABLE would be tested nowhere holds only for the tests that use a mocked
  service. Against a real embedded service the smoke suite already covers them on Spark 3.5.
- A build in a plain POSIX locale fails to compile two test files. The agent set `LC_ALL=C.UTF-8`; the build sets no
  source encoding.
- The git hooks print `[[: not found` and go on, so they show nothing about the format check.

## Not run

No full build was run. The Spark 3.5 project's CI result on the last head was not yet in when the agent wrote.
