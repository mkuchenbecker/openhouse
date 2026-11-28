# N8, V18, C15 and the CI print fix: result

Bottom line: pull request 139 merged N8, V18 and C15, and pull request 138 fixed what pull request 137 showed about the
CI step that prints failed tests. Both merged into `irc-integration` on 2026-11-24: 138 as `c2c52144`, 139 as `e6c52c21`.
Policy tags are now stored and answered in one order, the Iceberg 1.2 guards on the write and view beans are gone with the
tests that only tested them, and the two OpenAPI specs are byte-identical between builds. The CI fix is proven against a
real failed log but has not yet run on a red job in CI. Post-merge CI for 139 is not read yet. Marks: [ran] was run,
[read] was read in code or logs, [inferred] is reasoning. Records: `ci-print-failed-tests-split.md` and
`service-batch-n8-v18-c15.md` in the reports directory, and the two merge messages.

## The CI fix (pull request 138, work item C16 fix-forward)

- What happened [read]: the first red job since the CI split, on pull request 137 (run 45405939701, job 138486658178,
  Default build, apps-spark-3.5), printed "0 failed test cases" while Gradle said "381 tests completed, 62 failed, 6
  skipped". The merge of the split (`71-c16-ci-split-result.md`) had listed "no split job has failed yet" as a gap; this
  was that gap, and it showed a defect the split did not cause.
- Cause [read]: a test process ran out of memory and died (exit value 52, an OutOfMemoryError among the failures). Gradle
  writes a task's JUnit XML only when its test process survives, so the job had no XML (the upload found 2 files), and
  `scripts/ci/print-failed-tests.py` read only XML. The single post-merge jobs (`build-run-tests.yml`,
  `iceberg-linkedin-build.yml`) had the same blind spot. That the XML was missing is inferred from the log: the artifact
  could not be downloaded here.
- Fix: the script takes `--log FILE` and, for classes with no result file, prints the failures from the console (the
  FAILED line with its indented lines, including Caused by), echoes Gradle's "N tests completed" line, and prints a
  suite that counts failures but names no case. The three workflows write the Gradle console to
  `$RUNNER_TEMP/gradle-build.log` through `tee` under `set -o pipefail` (outside `build/`, which `clean` empties). A
  self-test with 4 tests runs in the "test groups" job. Which tests run did not change.
- Proof [ran]: the fixed script on the real log of the failed job with an empty result tree printed 62 failures, equal to
  Gradle's count. The self-test passed 4 of 4. CI on the pull request (head `ba8f4320`): all 14 jobs completed green, one
  skipped by design, which exercised `tee` and `pipefail` on the passing path.
- Not proven: a real red run with the new workflow; that the failing exit status survives `tee` is established by reading
  the pipelines. Why the test process of pull request 137 ran out of memory is that pull request's to fix (its new tests
  pushed the jobs test process over; its second head is green). Console excerpts are Gradle's short form, not full traces.

## N8: one order for policy tags (pull request 139)

- Cause [read]: the tags of a column policy were a `HashSet` of an enum, whose order follows identity hash codes, which
  differ per service process. The stored and returned order of (PII, HC) therefore varied between processes, and smoke
  scenario s31 had to accept either order (`64-n1c-n7-result.md`).
- Order chosen: the enum's declaration order, PII then HC. The code fixes it, so no process can differ [inferred as the
  reason]; the tags are still compared as sets. A `PolicyTag` getter and a Gson adapter write the tags in that order in
  the stored policies and in native API answers; s31 now expects that one order.
- Behavior change one: a native GET of a table stored with HC, PII answers PII, HC. The stored text, the Iceberg REST load
  answer and `SHOW TBLPROPERTIES` keep the stored order until the policies next really change.
- Behavior change two: a request carrying the same tags in another order no longer rewrites the stored policies, so it no
  longer makes a commit. A table stored by an older process with the other order is read as stored and is not rewritten
  by a read.
- Proof [ran]: `PolicyTagOrderTest` (4 tests at the HTTP boundary, both APIs) was committed first. On the unfixed code 2
  failed (a read of an other-order table answers HC, PII; a PUT of the same set rewrites the other-order text) and 2
  passed only by that process's hash order. With the fix 4 of 4 pass. Also passing on the 1.12 line: `IrcPolicyPatchTest`
  66, end-to-end `TablesControllerTest` 63, mock `TablesControllerTest` 26, `RepositoryTest` 71. Scenario s31 passed in CI
  on every Spark 3.5 path on both lines and on Spark 4.2 with and without server planning [read]; on the reference line
  the plain REST path skips s31 by N10.

## V18: the Iceberg 1.2 guards (pull request 139)

| Item | Decision |
| --- | --- |
| `@ConditionalOnClass` on the IRC write controller and handler | removed |
| `@ConditionalOnClass` on the IRC view controller and handler | removed |
| `@ConditionalOnClass` on `ViewCommitEngineConfiguration` | removed |
| 13 write and view entries in the catch-all 406 list | removed |
| The 1.2 wording in comments | removed |
| Class-presence registration of the scan planning controller | kept: its classes exist only on 1.12 |
| The 406 answers for routes a line does not serve | kept |
| `IcebergRestRequirements` | kept: it holds the replace-shape rule |
| `ViewsServiceImpl` and `ViewsDisabledService` (1.2-only pair) | kept: removing it deletes tested code; follow-up V22 |

- Tests removed with the guards: `IcebergRestBeanSignaturesTest` deleted (1 test); `IcebergRestRoutesBoundaryTest` from 6
  tests to 5; `IcebergViewBeanBoundaryTest` from 12 to 4. Every test with another subject stays.
- No route answers differently [ran, read]: the endpoint list test and the unserved-route list test are unchanged and
  pass; `IrcReadRoutesTest` 23, `IrcConfigEndpointsTest` 6, `IrcWriteRoutesTest` 43 and `IrcViewRoutesTest` 33 pass. That
  the 13 removed 406 entries could never be reached is reasoned from the mappings (the more specific mappings always
  win) and was not tested with unusual requests [inferred]. The compatibility kit in CI gave 42 passed, 95 failed, 23
  skipped with `expected-outcomes.txt` untouched [read].
- Differences from the row written in `04-work.md` earlier: `IcebergRestRequirements` and the 406 answers stay, and two of
  the three boundary tests were trimmed, not deleted.

## C15: byte-stable OpenAPI specs (pull request 139)

- Cause [read, ran]: only the `Page*` schemas vary between builds (four property orders seen across 102 earlier spec
  copies). Spring Data's `Page` is an interface, and Jackson takes property order from `getDeclaredMethods`, which the JVM
  does not fix.
- Fix: an `OpenApiCustomiser` in the tables and housetables services sorts the properties of the `Page*` schemas
  alphabetically.
- Proof [ran]: two generations with a fresh service JVM each give the same sha256 for both specs (tables `8baba939...`,
  housetables `c36a6642...`). Against the unfixed code the specs are equal as JSON and only the five `Page` schemas change
  property order. The generated clients keep their file sets (136 and 79 files) and two generations differ only in
  timestamp lines.
- The one change in the generated clients: the Page response models change their property order annotation and `hashCode`
  values; no field, method or type changes.
- Link to C17 [inferred]: the default line's large test tasks never came from Gradle's build cache although the reference
  line's did, and those suites depend on a client whose generated source changed with every build. Byte-stable specs may
  fix that. It was not measured, so C17 now carries it as its first lead.

## Evidence counts and what was left to CI

- `:services:tables:test`, 1.12 line [ran]: base 1727 tests, branch 1730 (1 removed, 4 added), 1 skipped, 0 failed. Reference
  line (`-PicebergLinkedIn`): 1702 tests, 51 skipped, 0 failed; its base count was not measured (stopped for memory).
  Fixtures test: 41 on the base, 32 on the branch (exactly the removed tests), 0 failed on both lines.
- CI on pull request 139 [read]: 13 jobs green and 1 skipped by design on the pre-merge head `1f5b84f7` and on `1e32ff4f`
  (which merged the CI fix, `c2c52144`, so the split CI ran with it). Pull request 138: 14 jobs completed on `ba8f4320`.
- Left to CI and not run locally: the compatibility kit, the Spark integration tests, `spotlessCheck` and the test-group
  partition check.
- Post-merge CI: passed for the merges of pull requests 134, 135, 136 and 138 (read 2026-11-24). For 135 the main build job
  took 53 minutes now that it carries the Spark 4.2 jobs suite. Post-merge runs queue one behind another, so confirmation
  lags when merges land close together. Not read yet for 139.
