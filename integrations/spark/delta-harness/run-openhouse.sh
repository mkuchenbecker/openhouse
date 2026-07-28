#!/usr/bin/env bash
# Build + run the delta-harness matrix against the REAL OpenHouse catalog
# (embedded OpenHouseLocalServer + stock Iceberg RESTCatalog over /iceberg/v1/*).
#
# Requirements:
#   - JDK 17 (the OpenHouse build pins Lombok 1.18.20, which is incompatible with JDK 21+).
#     Set JAVA17_HOME, or the script uses $JAVA_HOME if it is a 17.
#   - A Gradle able to build the repo (system gradle 8.x works; the pinned 7.6.2 wrapper
#     may be blocked from downloading in restricted networks).
#   - Scala 2.13.16 compiler jars in the local Maven cache (~/.m2), or adjust SCALAC_CP.
#
# Real-HTS mode (HARNESS_REAL_HTS=1): boots the REAL embedded House Table Service as a 2nd Spring
# context and points the tables server at it (replacing the in-memory stub), and enables the undrop
# preparation axis + undropAdmin lifecycle cases (soft-delete/restore/purge). Requires the housetables
# classes on the classpath — run once with FORCE_CP=1 after adding them (print-cp.init.gradle already
# pulls :services:housetables). See HTS-EMBED-PLAN.md / HTS-EMBED-IMPL.md. Default (unset) uses the stub.
set -euo pipefail
cd "$(dirname "$0")"
REPO_ROOT="$(cd ../../.. && pwd)"
HERE="$(pwd)"
WORK="${TMPDIR:-/tmp}/delta-harness-oh"
mkdir -p "$WORK"

JDK17="${JAVA17_HOME:-${JAVA_HOME:?set JAVA17_HOME to a JDK 17}}"
GRADLE="${GRADLE_BIN:-gradle}"
M2="${HOME}/.m2/repository/org/scala-lang"
# Phase 2 (spark4-upgrade): Spark 4.0 is Scala 2.13 only. Spark 4.0.0 ships scala-library 2.13.16, so
# compile the harness with the matching 2.13.16 scalac (was 2.12.18 for the Spark-3.5 lane).
SCALA_VER="${SCALA_VER:-2.13.16}"
SCALAC_CP="$M2/scala-compiler/$SCALA_VER/scala-compiler-$SCALA_VER.jar:$M2/scala-reflect/$SCALA_VER/scala-reflect-$SCALA_VER.jar:$M2/scala-library/$SCALA_VER/scala-library-$SCALA_VER.jar"

# Phase 2: resolve the classpath from the Spark-4.0/Scala-2.13 itest module (REST-first: stock
# iceberg-spark-runtime-4.0 + embedded OpenHouse server, no custom OH runtime).
HARNESS_ITEST_PATH="${HARNESS_ITEST_PATH:-:integrations:spark:spark-4.2:openhouse-spark-4.2-itest}"

# Classpath resolution is the slow part (~25-80s of gradle). It only changes when OpenHouse deps
# change, so we cache it in $WORK/oh-cp.txt and reuse it for fast inner-loop iteration. Force a
# fresh resolve with FORCE_CP=1 (do this after pulling dep changes or the first run in a session).
if [[ "${FORCE_CP:-0}" != "1" && -s "$WORK/oh-cp.txt" ]]; then
  echo ">> reusing cached OpenHouse classpath ($WORK/oh-cp.txt) — set FORCE_CP=1 to re-resolve"
else
  echo ">> resolving OpenHouse itest runtime classpath (builds the runtime uber jar + fixtures)"
  ( cd "$REPO_ROOT" && "$GRADLE" -Dorg.gradle.java.home="$JDK17" -DcpOut="$WORK/oh-cp.txt" \
      -DharnessItestPath="$HARNESS_ITEST_PATH" \
      --init-script "$HERE/scripts/print-cp.init.gradle" \
      "$HARNESS_ITEST_PATH:printHarnessCp" --console=plain )
fi
OHCP="$(cat "$WORK/oh-cp.txt")"

echo ">> compiling harness (scala $SCALA_VER) against the OpenHouse classpath"
mkdir -p "$WORK/classes"
# The harness is split across several .scala files (Framework / Scenario traits / Plan / Env),
# all in `package harness`. Compile every source under src/main/scala together so cross-file
# references resolve (order is irrelevant to scalac — it compiles the whole compilation unit set).
mapfile -t SCALA_SRCS < <(find "$HERE/src/main/scala/harness/openhouse" -name '*.scala' ! -name 'DvProbe.scala' | sort)
echo ">> ${#SCALA_SRCS[@]} source files"
"$JDK17/bin/java" -cp "$SCALAC_CP" scala.tools.nsc.Main \
  -classpath "$OHCP" -d "$WORK/classes" \
  "${SCALA_SRCS[@]}"

echo ">> running on JDK 17 (embedded OpenHouse server + stock RESTCatalog client)"
# Phase 2: Spark 4.0's documented --add-opens set (org.apache.spark.launcher JavaModuleOptions),
# a superset of the Spark-3.5 list used before.
OPENS=(
  --add-opens=java.base/java.lang=ALL-UNNAMED
  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED
  --add-opens=java.base/java.io=ALL-UNNAMED
  --add-opens=java.base/java.net=ALL-UNNAMED
  --add-opens=java.base/java.nio=ALL-UNNAMED
  --add-opens=java.base/java.util=ALL-UNNAMED
  --add-opens=java.base/java.util.concurrent=ALL-UNNAMED
  --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED
  --add-opens=java.base/sun.nio.cs=ALL-UNNAMED
  --add-opens=java.base/sun.security.action=ALL-UNNAMED
  --add-opens=java.base/sun.util.calendar=ALL-UNNAMED
  --add-opens=java.base/jdk.internal.ref=ALL-UNNAMED
)
SCALA_LIB="$M2/scala-library/$SCALA_VER/scala-library-$SCALA_VER.jar"
# Args are passed through as case-id filters (AND). E.g. `run-openhouse.sh delete parquet`
# runs just the delete tests on parquet — a ~25s inner loop. No args runs the full matrix.
exec "$JDK17/bin/java" "${OPENS[@]}" -Dio.netty.tryReflectionSetAccessible=true \
  -cp "$WORK/classes:$SCALA_LIB:$OHCP" harness.Main "$@"
