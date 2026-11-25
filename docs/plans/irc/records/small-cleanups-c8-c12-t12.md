# Small cleanups C8, C12, T12

Bottom line: pull request #136 (draft, base irc-integration), head 6e9469e1781bd6f7a81e5246e39bdefdc084d173, one commit.
Only C12 needed a change. C8 and T12 were already true at e2823b69. All 14 CI jobs green (1 skipped by design).

## C8 - no change
CLAUDE.md lines 36 and 49 already name tables-test-fixtures-iceberg-1.5/src/shared/main/resources/dummy.token
(fixed by dec16c80; the v9c record still said stale). File exists. Grep for old path iceberg-1.2/src/main/resources: nothing.

## C12 - build.gradle (root), inside plugins.withType(JavaPlugin), +8 lines
JavaCompile and Javadoc get options.encoding = 'UTF-8'. Non-ASCII is in ~100 main and test Java files, not two.
Command: LC_ALL=C ./gradlew :libs:optimizer:binpack:compileTestJava :services:housetables:compileTestJava
  :libs:optimizer:binpack:javadoc --rerun-tasks --no-build-cache
Before: error: unmappable character (0xE2) for encoding US-ASCII. After: BUILD SUCCESSFUL, 0 unmappable, plain and -PicebergLinkedIn.
No Scala project holds non-ASCII sources (only delta-harness, not in settings.gradle).

## T12 - no change
kit build.gradle already has filter { excludeTestsMatching kit classes; failOnNoMatchingTests = false } (98328ea2).
:test succeeds; :check ran restCompatKitTest: 160 tests, 42 passed, 95 failed, 23 skipped, matches expected-outcomes.txt.

## Other checks
check-test-groups.py: 775 tasks, 43 test tasks; partition holds.
CI on head: every job success (Default: apps-spark-3.5, apps-spark-4.2, rest, spark-3.5 itest, spark-4.2 itest, Docker/integration, test groups; Reference: same set, Docker job skipped).

## Not verified
Whole-repository build under LC_ALL=C; Javadoc of other projects; the title still lists all three items.
