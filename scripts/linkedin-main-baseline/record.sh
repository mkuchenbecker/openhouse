#!/bin/bash
# Records what LinkedIn main answers to the evidence suites, by running main's own code, and
# stores the recordings where the suites read them:
#
#   integrations/spark/spark-3.5/openhouse-spark-itest/src/test/resources/linkedin-main/
#     smoke-scenarios.tsv           every statement of SparkSqlSmokeScenarios
#     write-statements.tsv          every check of SparkSqlWriteStatements
#     catalog-plugin-contract.tsv   every exception and verdict of CatalogPluginContractTest
#   services/tables/src/test/resources/linkedin-main/
#     commit-path.tsv               every answer and verdict of the CommitPath*Test classes
#
# A normal test run only reads these files; this script is the only way to write them. It is run
# by hand, once per change of the suites that adds or changes a statement, never by CI.
#
# What it does:
#  1. Makes a detached worktree of LinkedIn main (bb8929e1) next to this checkout, unless one is
#     given as the first argument.
#  2. Copies into main's test source directories only: the suites (the shared smoke sources, the
#     grant-storing authorization backend, the commit hook catalog, CatalogPluginContractTest, the
#     CommitPath*Test classes and the House Tables stand-in of services/tables' tests) and the
#     main-only runners and empty difference lists in scripts/linkedin-main-baseline/recorder.
#     CatalogPluginContractTest is ported with two substitutions: main has no IcebergLine, and main
#     is the 1.5.2 line. It then checks that `git status` of the worktree shows changes under test
#     source directories only: main's production code and build files are untouched.
#
#     The test doubles are therefore NOT the same for every suite, and each recording's header says
#     which ran (# test doubles):
#     - commit-path: the stored recording ran main's code with this branch's earlier stand-in of
#       services/tables' tests (e2e/h2/HouseTablesH2Repository at commit 0fe4678a), copied into
#       main; not main's own. Since then the branch has
#       one stand-in, in tables-test-fixtures, held to the real store; the answers that changed
#       with it are declared as stand-in differences in CommitPathLinkedInMainDifferences. A new
#       recording runs main's own services stand-in unless FIXTURES_STAND_IN=branch also copies the
#       fixtures one (see README.md: what a re-record of the commit path needs).
#     - smoke-scenarios, write-statements, catalog-plugin-contract: the Spark itest's service takes
#       its stand-in from tables-test-fixtures (src/main, tablestest/HouseTablesH2Repository). That is
#       not a test source directory, so main runs main's own stand-in, and the branch runs its
#       rewritten one (compare-and-swap on save, the delete flag read the other way round,
#       case-insensitive listing, a guard on renames). FIXTURES_STAND_IN=branch measures whether
#       that changes any answer (see below).
#  3. Runs (each test task with --rerun: the record directory is not a task input), RUNS times (default 2, each in a fresh JVM), with main's Gradle wrapper and JDK 17 (what
#     main's CI uses), main's Spark 3.5 itest `catalogTest` for the recorders and the contract test,
#     and main's `services:tables:test` for the commit-path classes. The init script
#     recorder/main-itest-classpath.init.gradle removes iceberg-data from the itest's test runtime
#     classpath, as main's apps/spark-3.5 does and as this branch's itest does: on main's itest a
#     merge-on-read read otherwise fails in the test JVM only (shaded and unshaded ORC classes).
#  4. Merges the runs: a statement that gave different outcomes in different runs (main's answer
#     was not stable from one JVM to the next) keeps one line per outcome; and writes the files
#     above, sorted, with a header naming the commit, date, JDK, Spark, Iceberg and command.
#
# Usage: [RUNS=n] [RECORD_WORK=dir for the raw output] [TASKS="spark tables"] [MERGE_ONLY=1]
#        [FIXTURES_STAND_IN=branch] scripts/linkedin-main-baseline/record.sh [main-worktree]
#   RUNS               the number of runs, each in a fresh JVM (6 for the stored files)
#   RECORD_WORK        where the raw runs go (default a new temporary directory); keep it
#   TASKS              which suites to run: spark (smoke, write, contract), tables (commit path)
#   MERGE_ONLY=1       run nothing: merge the raw runs already in RECORD_WORK again (a header
#                      change only; the bodies stay the runs' union)
#   FIXTURES_STAND_IN=branch
#                      a measurement, not a recording: also copy the branch's tables-test-fixtures
#                      stand-in into main (the guard then allows that one file), run, and compare
#                      every key with the stored files instead of writing them. Prints the keys
#                      whose outcome is not among the stored ones.
# See README.md next to this script for when and how to re-record.
# Needs: a JDK 17 at MAIN_JAVA_HOME (default /usr/lib/jvm/java-17-openjdk-amd64), about 6 GB of free memory,
# and network access to the repositories main resolves from (LinkedIn's Iceberg 1.5.2 artifacts).
# Review the diff of the recordings before committing it: every changed line is a change of what
# main is recorded to do, and the suites' declared differences may need to follow.
set -euo pipefail

COMMIT=bb8929e1
RUNS=${RUNS:-2}
TASKS=${TASKS:-spark tables}
FIXTURES_STAND_IN=${FIXTURES_STAND_IN:-main}
# What each recording's header says about the test doubles main ran with.
DOUBLES_SPARK="House Tables: main's own stand-in of tables-test-fixtures (tablestest/HouseTablesH2Repository at $COMMIT), not this branch's; authorization: smoke-scenarios the branch's GrantStoringAuthorization (test code, copied; stores grants), write-statements the fixture's OpaAuthorizationHandler with no OPA server (allows every request), catalog-plugin-contract the same plus the test's own handler in enforcingAuthorization"
DOUBLES_TABLES="House Tables: this branch's earlier stand-in of services/tables' tests (e2e/h2/HouseTablesH2Repository at commit 0fe4678a, copied into main; not main's own), since replaced on the branch by the one held to the real store; the branch now runs tables-test-fixtures' stand-in, and the answers that changed with it are declared as stand-in differences; authorization: the services tests' own configuration (AuthorizationPropertiesInitializer, and CommitPathAuthorizationTest's handler)"
# The result of the last FIXTURES_STAND_IN=branch measurement, for the Spark recordings' header.
STAND_IN_CHECK_SPARK="identical with either stand-in of tables-test-fixtures (main's, or the branch's copied in: the repository and store held to the real House Tables store), checked on 2026-11-28 with one run each, before and after the branch moved to that stand-in"

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
MAIN=${1:-$REPO/../openhouse-linkedin-main-$COMMIT}
# Main builds with JDK 17, as its CI does (actions/setup-java, java-version 17); its Lombok does
# not run on a later JDK.
export JAVA_HOME=${MAIN_JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}
export PATH=$JAVA_HOME/bin:$PATH LC_ALL=C.UTF-8
"$JAVA_HOME/bin/java" -version 2>&1 | grep -q 'version "17' || {
  echo "$JAVA_HOME is not a JDK 17: set MAIN_JAVA_HOME" >&2
  exit 1
}
WORK=${RECORD_WORK:-$(mktemp -d)}

if [ ! -d "$MAIN" ]; then
  git -C "$REPO" worktree add --detach "$MAIN" "$COMMIT"
fi
test "$(git -C "$MAIN" rev-parse --short=8 HEAD)" = "$COMMIT" || {
  echo "$MAIN is not at $COMMIT" >&2
  exit 1
}

ITEST=integrations/spark/spark-3.5/openhouse-spark-itest
SMOKE=$ITEST/src/shared/smoke/java/com/linkedin/openhouse/spark/smoke
MAIN_TEST=$MAIN/$ITEST/src/test/java/com/linkedin/openhouse
H2=services/tables/src/test/java/com/linkedin/openhouse/tables/e2e/h2

mkdir -p "$MAIN_TEST/spark/smoke" "$MAIN_TEST/spark/grants" "$MAIN_TEST/javaclient" \
  "$MAIN_TEST/spark/catalogtest"
for f in SparkSqlSmokeScenarios SparkSqlWriteStatements BaselineChecks StoredTable CommitRace \
  LinkedInMainRecording LinkedInMainComparison LinkedInMainAnswers; do
  cp "$REPO/$SMOKE/$f.java" "$MAIN_TEST/spark/smoke/"
done
cp "$REPO/$ITEST/src/shared/authorization/java/com/linkedin/openhouse/spark/grants/GrantStoringAuthorization.java" \
  "$MAIN_TEST/spark/grants/"
cp "$REPO/$ITEST/src/test/java/com/linkedin/openhouse/javaclient/CommitRaceCatalog.java" "$MAIN_TEST/javaclient/"
cp "$HERE"/recorder/spark-itest/*.java "$MAIN_TEST/spark/catalogtest/"
sed -e 's/IcebergLine.current() == IcebergLine.V3/false/' \
  -e 's/IcebergLine.current() == IcebergLine.LEGACY/true/' \
  -e '/^import com.linkedin.openhouse.javaclient.IcebergLine;/d' \
  "$REPO/$ITEST/src/shared/test/java/com/linkedin/openhouse/spark/catalogtest/CatalogPluginContractTest.java" \
  > "$MAIN_TEST/spark/catalogtest/CatalogPluginContractTest.java"
cp "$REPO"/$H2/CommitPath*.java "$REPO"/$H2/LinkedInMain*.java "$MAIN/$H2/"
# The branch's declarations name classes main does not have; main declares no difference from itself.
cp "$HERE"/recorder/tables/*.java "$MAIN/$H2/"

FIXTURE_DIR=tables-test-fixtures/tables-test-fixtures-iceberg-1.2/src/main/java/com/linkedin/openhouse/tablestest
BRANCH_FIXTURE_DIR=$REPO/tables-test-fixtures/tables-test-fixtures-iceberg-1.5/src/shared/main/java/com/linkedin/openhouse/tablestest
ALLOWED='^.. (integrations/spark/spark-3.5/openhouse-spark-itest/src/test/|services/tables/src/test/)'
git -C "$MAIN" checkout -q -- "$FIXTURE_DIR"
git -C "$MAIN" clean -q -f -- "$FIXTURE_DIR"
if [ "$FIXTURES_STAND_IN" = branch ]; then
  # The branch's stand-in is the repository and the store behind it, held to the real store by
  # HouseTableRepositoryContract.
  cp "$BRANCH_FIXTURE_DIR"/HouseTablesH2*.java "$MAIN/$FIXTURE_DIR/"
  ALLOWED="$ALLOWED|^.. $FIXTURE_DIR/HouseTablesH2[A-Za-z]*\\.java\$"
fi
if git -C "$MAIN" status --porcelain | grep -v -E "$ALLOWED"; then
  echo "changes outside main's test source directories: refusing to record" >&2
  exit 1
fi

for run in $(seq 1 "$RUNS"); do
  [ "${MERGE_ONLY:-0}" = 1 ] && break
  out=$WORK/run$run
  for task in $TASKS; do
    if [ $task = spark ]; then
      args=(:integrations:spark:spark-3.5:openhouse-spark-3.5-itest:catalogTest --rerun
        --tests 'com.linkedin.openhouse.spark.catalogtest.LinkedInMain*'
        --tests 'com.linkedin.openhouse.spark.catalogtest.CatalogPluginContractTest')
    else
      args=(":services:tables:test" --rerun --tests 'com.linkedin.openhouse.tables.e2e.h2.CommitPath*')
    fi
    mkdir -p "$out/$task"
    # A test that fails on main is recorded as failing; the build's exit code is not the point.
    (cd "$MAIN" &&
      OPENHOUSE_LINKEDIN_MAIN_RECORD_DIR="$out/$task" \
      OPENHOUSE_LINKEDIN_MAIN_COMMIT="$(git -C "$MAIN" rev-parse HEAD)" \
      OPENHOUSE_LINKEDIN_MAIN_COMMAND="RUNS=$RUNS scripts/linkedin-main-baseline/record.sh, run $run: ./gradlew -I main-itest-classpath.init.gradle ${args[*]}" \
      ./gradlew --max-workers=2 --continue -I "$HERE/recorder/main-itest-classpath.init.gradle" \
        "${args[@]}" > "$out/$task/gradle.log" 2>&1) || true
  done
done

merge() { # suite destination-directory task test-doubles [stand-in-check]
  local first=$WORK/run1/$3/$1.tsv
  test -s "$first" || {
    echo "no recording of $1 in run 1: see $WORK/run1/$3/gradle.log" >&2
    exit 1
  }
  for run in $(seq 2 "$RUNS"); do
    # A run that stopped early would hide in the union: every run must have the same keys.
    cmp -s <(grep -v '^#' "$first" | cut -f1 | sort -u) \
      <(grep -v '^#' "$WORK/run$run/$3/$1.tsv" | cut -f1 | sort -u) || {
      echo "run $run of $1 recorded other statements than run 1: see $WORK/run$run/$3/gradle.log" >&2
      exit 1
    }
  done
  mkdir -p "$2"
  {
    grep '^#' "$first" | grep -v '^# key<TAB>' | sed "s|^# command: .*|# command: RUNS=$RUNS scripts/linkedin-main-baseline/record.sh (each run in a fresh JVM; a statement main answered differently in different runs has one line per answer)|"
    echo "# test doubles: $4"
    if [ -n "${5:-}" ]; then echo "# stand-in check: $5"; fi
    echo "# runs: $RUNS"
    echo "# key<TAB>outcome, sorted by key"
    for run in $(seq 1 "$RUNS"); do grep -v '^#' "$WORK/run$run/$3/$1.tsv"; done | LC_ALL=C sort -u
  } > "$2/$1.tsv"
}

if [ "$FIXTURES_STAND_IN" = branch ]; then
  # A measurement: compare with the stored files, write nothing into the repository.
  git -C "$MAIN" checkout -q -- "$FIXTURE_DIR"
  git -C "$MAIN" clean -q -f -- "$FIXTURE_DIR"
  status=0
  for suite in smoke-scenarios write-statements catalog-plugin-contract; do
    merge $suite "$WORK/merged" spark "$DOUBLES_SPARK (the branch's tables-test-fixtures stand-in copied in)"
    stored=$REPO/$ITEST/src/test/resources/linkedin-main/$suite.tsv
    python3 - "$WORK/merged/$suite.tsv" "$stored" <<'PY' || status=1
import sys
def rows(path):
    out = {}
    for line in open(path, encoding="utf-8"):
        if line.startswith("#") or not line.strip():
            continue
        key, outcome = line.rstrip("\n").split("\t", 1)
        out.setdefault(key, set()).add(outcome)
    return out
measured, stored = rows(sys.argv[1]), rows(sys.argv[2])
bad = sorted(k for k in set(measured) | set(stored) if not measured.get(k, set()) <= stored.get(k, set()) or k not in measured)
print("%s: %d keys measured, %d not as stored" % (sys.argv[2].rsplit("/", 1)[1], len(measured), len(bad)))
for k in bad:
    print("  " + k + "\n    stored:   " + " | ".join(sorted(stored.get(k, ["(none)"]))) + "\n    measured: " + " | ".join(sorted(measured.get(k, ["(none)"]))))
sys.exit(1 if bad else 0)
PY
  done
  echo "Measured $RUNS run(s) with the branch's fixtures stand-in; raw output in $WORK"
  exit $status
fi

case " $TASKS " in *" spark "*)
  merge smoke-scenarios "$REPO/$ITEST/src/test/resources/linkedin-main" spark "$DOUBLES_SPARK" "$STAND_IN_CHECK_SPARK"
  merge write-statements "$REPO/$ITEST/src/test/resources/linkedin-main" spark "$DOUBLES_SPARK" "$STAND_IN_CHECK_SPARK"
  merge catalog-plugin-contract "$REPO/$ITEST/src/test/resources/linkedin-main" spark "$DOUBLES_SPARK" "$STAND_IN_CHECK_SPARK"
  ;;
esac
case " $TASKS " in *" tables "*)
  merge commit-path "$REPO/services/tables/src/test/resources/linkedin-main" tables "$DOUBLES_TABLES"
  ;;
esac
echo "Recorded $RUNS run(s) of $COMMIT; raw output in $WORK. Review: git -C $REPO diff --stat"
