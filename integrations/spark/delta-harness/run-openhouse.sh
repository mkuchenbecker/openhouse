#!/usr/bin/env bash
# Build + run the delta-harness DELETE slice against the REAL OpenHouse catalog
# (embedded OpenHouseLocalServer + OpenHouseCatalog).
#
# Requirements:
#   - JDK 17 (the OpenHouse build pins Lombok 1.18.20, which is incompatible with JDK 21+).
#     Set JAVA17_HOME, or the script uses $JAVA_HOME if it is a 17.
#   - A Gradle able to build the repo (system gradle 8.x works; the pinned 7.6.2 wrapper
#     may be blocked from downloading in restricted networks).
#   - Scala 2.12.18 compiler jars in the local Maven cache (~/.m2), or adjust SCALAC_CP.
set -euo pipefail
cd "$(dirname "$0")"
REPO_ROOT="$(cd ../../.. && pwd)"
HERE="$(pwd)"
WORK="${TMPDIR:-/tmp}/delta-harness-oh"
mkdir -p "$WORK"

JDK17="${JAVA17_HOME:-${JAVA_HOME:?set JAVA17_HOME to a JDK 17}}"
GRADLE="${GRADLE_BIN:-gradle}"
M2="${HOME}/.m2/repository/org/scala-lang"
SCALAC_CP="$M2/scala-compiler/2.12.18/scala-compiler-2.12.18.jar:$M2/scala-reflect/2.12.18/scala-reflect-2.12.18.jar:$M2/scala-library/2.12.18/scala-library-2.12.18.jar"

# Classpath resolution is the slow part (~82s of gradle). It only changes when OpenHouse deps
# change, so we cache it in $WORK/oh-cp.txt and reuse it for fast inner-loop iteration. Force a
# fresh resolve with FORCE_CP=1 (do this after pulling dep changes or the first run in a session).
if [[ "${FORCE_CP:-0}" != "1" && -s "$WORK/oh-cp.txt" ]]; then
  echo ">> reusing cached OpenHouse classpath ($WORK/oh-cp.txt) — set FORCE_CP=1 to re-resolve"
else
  echo ">> resolving OpenHouse itest runtime classpath (builds the runtime uber jar + fixtures)"
  ( cd "$REPO_ROOT" && "$GRADLE" -Dorg.gradle.java.home="$JDK17" -DcpOut="$WORK/oh-cp.txt" \
      --init-script "$HERE/scripts/print-cp.init.gradle" \
      :integrations:spark:spark-3.5:openhouse-spark-3.5-itest:printHarnessCp --console=plain )
fi
OHCP="$(cat "$WORK/oh-cp.txt")"

echo ">> compiling harness (scala 2.12) against the OpenHouse classpath"
mkdir -p "$WORK/classes"
"$JDK17/bin/java" -cp "$SCALAC_CP" scala.tools.nsc.Main \
  -classpath "$OHCP" -d "$WORK/classes" \
  "$HERE/src/main/scala/harness/openhouse/OpenHouseMatrix.scala"

echo ">> running on JDK 17 (embedded OpenHouse server + OpenHouse catalog)"
OPENS=(
  --add-opens=java.base/java.lang=ALL-UNNAMED
  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED
  --add-opens=java.base/java.io=ALL-UNNAMED
  --add-opens=java.base/java.net=ALL-UNNAMED
  --add-opens=java.base/java.nio=ALL-UNNAMED
  --add-opens=java.base/java.util=ALL-UNNAMED
  --add-opens=java.base/java.util.concurrent=ALL-UNNAMED
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED
  --add-opens=java.base/sun.security.action=ALL-UNNAMED
  --add-opens=java.base/sun.util.calendar=ALL-UNNAMED
)
SCALA_LIB="$M2/scala-library/2.12.18/scala-library-2.12.18.jar"
# Args are passed through as case-id filters (AND). E.g. `run-openhouse.sh delete parquet`
# runs just the delete tests on parquet — a ~25s inner loop. No args runs the full matrix.
exec "$JDK17/bin/java" "${OPENS[@]}" -Dio.netty.tryReflectionSetAccessible=true \
  -cp "$WORK/classes:$SCALA_LIB:$OHCP" harness.Main "$@"
