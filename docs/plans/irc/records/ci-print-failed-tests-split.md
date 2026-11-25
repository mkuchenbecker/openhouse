# CI: print the failed tests of a failed split job

Bottom line: the step printed 0 because the job had no JUnit XML at all: the test JVM died, and
Gradle writes a task's XML only when its JVM survives. The script now also reads the failures from
the build's console log. Pull request 138 (draft), head ba8f43202e136848d3932642d477e3083b2afac9.

## Defect
Pull request 137, run 45405939701, job 138486658178 (Default build, apps-spark-3.5): Gradle said
"381 tests completed, 62 failed, 6 skipped"; the step printed "no result files found" and
"0 failed test cases".

## Cause
- The task ended "Process 'Gradle Test Executor 4' finished with non-zero exit value 52"; an
  OutOfMemoryError is among the failures. No XML was written; the upload found "2 files".
- scripts/ci/print-failed-tests.py reads only TEST-*.xml (glob in main). Paths, working
  directory and --exclude were right and the same script works when XML exists.
- The same defect applied to the single jobs (build-run-tests.yml, iceberg-linkedin-build.yml).
- Not seen directly: the artifact could not be downloaded (proxy blocks the storage host).

## Fix
- print-failed-tests.py --log FILE: prints failures of classes with no result file from the
  console ("Class > test FAILED" plus indented lines), echoes Gradle's "N tests completed" line;
  also prints a suite that counts failures but names no test case.
- The three workflows tee the Gradle step to $RUNNER_TEMP/gradle-build.log under pipefail (not
  build/, which `clean` empties) and pass --log.
- scripts/ci/test-print-failed-tests.py (4 unit tests) runs in the plan ("test groups") job.

## Proof
- Real job log, empty result tree: 62 console failures printed, "==== 62 failed test cases ====".
- Self-test: 4 tests OK. Pull request CI: all 14 jobs green (see below).

## Not verified
- A real red run with the new workflow (the fix is untested end to end on a runner).
- What killed the test JVM (exit 52 / OutOfMemoryError); out of scope.
- Console excerpts are Gradle's short form (exception class and line), not full traces.

## Pull request CI (head ba8f432)
All completed success except "iceberg-linkedin-build / Default build, Docker images" (skipped, by design):
test groups, apps-spark-3.5, apps-spark-4.2, rest, both itests, Docker images (default); the same
five plus test groups on the reference line.
