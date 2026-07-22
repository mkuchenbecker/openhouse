#!/usr/bin/env bash
# DV probe runner: compiles the modular harness + DvProbe and runs harness.DvProbe with a
# configurable server format-version. Reuses the cached OpenHouse classpath from run-openhouse.sh.
set -euo pipefail
cd "$(dirname "$0")"
HERE="$(pwd)"
WORK="${TMPDIR:-/tmp}/delta-harness-oh"
JDK17="${JAVA17_HOME:-${JAVA_HOME:?set JAVA17_HOME}}"
M2="${HOME}/.m2/repository/org/scala-lang"
SCALA_VER="${SCALA_VER:-2.13.16}"
SCALAC_CP="$M2/scala-compiler/$SCALA_VER/scala-compiler-$SCALA_VER.jar:$M2/scala-reflect/$SCALA_VER/scala-reflect-$SCALA_VER.jar:$M2/scala-library/$SCALA_VER/scala-library-$SCALA_VER.jar"
FV="${FV:-3}"
FMT="${1:-parquet}"

[[ -s "$WORK/oh-cp.txt" ]] || { echo "no cached cp; run run-openhouse.sh first (FORCE_CP=1)"; exit 1; }
OHCP="$(cat "$WORK/oh-cp.txt")"

echo ">> compiling modular harness + DvProbe (scala $SCALA_VER)"
mkdir -p "$WORK/dvclasses"
# The modular harness is split across several .scala files (all package harness). DvProbe references
# OpenHouseEnv (in Env.scala), so compile the whole set together.
mapfile -t SCALA_SRCS < <(find "$HERE/src/main/scala/harness/openhouse" -name '*.scala' | sort)
"$JDK17/bin/java" -cp "$SCALAC_CP" scala.tools.nsc.Main -classpath "$OHCP" -d "$WORK/dvclasses" \
  "${SCALA_SRCS[@]}"

OPENS=(
  --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.invoke=ALL-UNNAMED
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED
  --add-opens=java.base/java.net=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED
  --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.util.concurrent=ALL-UNNAMED
  --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED
  --add-opens=java.base/sun.nio.cs=ALL-UNNAMED --add-opens=java.base/sun.security.action=ALL-UNNAMED
  --add-opens=java.base/sun.util.calendar=ALL-UNNAMED --add-opens=java.base/jdk.internal.ref=ALL-UNNAMED
)
SCALA_LIB="$M2/scala-library/$SCALA_VER/scala-library-$SCALA_VER.jar"
echo ">> running DvProbe (server cluster.iceberg.format-version=$FV, write.format=$FMT)"
exec "$JDK17/bin/java" "${OPENS[@]}" -Dio.netty.tryReflectionSetAccessible=true \
  -Dcluster.iceberg.format-version="$FV" \
  -cp "$WORK/dvclasses:$SCALA_LIB:$OHCP" harness.DvProbe "$FMT"
