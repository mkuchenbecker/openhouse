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
#     CommitPath*Test classes, the House Tables stand-in they rely on) and the main-only runners in
#     scripts/linkedin-main-baseline/recorder. CatalogPluginContractTest is ported with three
#     substitutions (main has neither IcebergLine nor SparkItestColumnDefaults; main is the 1.5.2
#     line). It then checks that `git status` of the worktree shows changes under test source
#     directories only: main's production code and build files are untouched.
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
# Usage: [RUNS=n] [RECORD_WORK=dir for the raw output] scripts/linkedin-main-baseline/record.sh [main-worktree]
# Needs: a JDK 17 at MAIN_JAVA_HOME (default /usr/lib/jvm/java-17-openjdk-amd64), about 6 GB of free memory,
# and network access to the repositories main resolves from (LinkedIn's Iceberg 1.5.2 artifacts).
# Review the diff of the recordings before committing it: every changed line is a change of what
# main is recorded to do, and the suites' declared differences may need to follow.
set -euo pipefail

COMMIT=bb8929e1
RUNS=${RUNS:-2}
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
  -e 's/SparkItestColumnDefaults.DATABASE/"d1_column_default"/' \
  -e '/^import com.linkedin.openhouse.javaclient.IcebergLine;/d' \
  -e '/^import com.linkedin.openhouse.tablestest.SparkItestColumnDefaults;/d' \
  "$REPO/$ITEST/src/shared/test/java/com/linkedin/openhouse/spark/catalogtest/CatalogPluginContractTest.java" \
  > "$MAIN_TEST/spark/catalogtest/CatalogPluginContractTest.java"
cp "$REPO"/$H2/CommitPath*.java "$REPO"/$H2/LinkedInMain*.java "$REPO/$H2/HouseTablesH2Repository.java" \
  "$MAIN/$H2/"

if git -C "$MAIN" status --porcelain | grep -v -E '^.. (integrations/spark/spark-3.5/openhouse-spark-itest/src/test/|services/tables/src/test/)'; then
  echo "changes outside main's test source directories: refusing to record" >&2
  exit 1
fi

for run in $(seq 1 "$RUNS"); do
  out=$WORK/run$run
  for task in spark tables; do
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

merge() { # suite destination-directory task
  local first=$WORK/run1/$3/$1.tsv
  test -s "$first" || {
    echo "no recording of $1 in run 1: see $WORK/run1/$3/gradle.log" >&2
    exit 1
  }
  mkdir -p "$2"
  {
    grep '^#' "$first" | grep -v '^# key<TAB>' | sed "s|^# command: .*|# command: RUNS=$RUNS scripts/linkedin-main-baseline/record.sh (each run in a fresh JVM; a statement main answered differently in different runs has one line per answer)|"
    echo "# runs: $RUNS"
    echo "# key<TAB>outcome, sorted by key"
    for run in $(seq 1 "$RUNS"); do grep -v '^#' "$WORK/run$run/$3/$1.tsv"; done | LC_ALL=C sort -u
  } > "$2/$1.tsv"
}
merge smoke-scenarios "$REPO/$ITEST/src/test/resources/linkedin-main" spark
merge write-statements "$REPO/$ITEST/src/test/resources/linkedin-main" spark
merge catalog-plugin-contract "$REPO/$ITEST/src/test/resources/linkedin-main" spark
merge commit-path "$REPO/services/tables/src/test/resources/linkedin-main" tables
echo "Recorded $RUNS run(s) of $COMMIT; raw output in $WORK. Review: git -C $REPO diff --stat"
