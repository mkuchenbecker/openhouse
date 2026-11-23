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
#
# Real-HTS mode (HARNESS_REAL_HTS=1): boots the REAL embedded House Table Service as a 2nd Spring
# context and points the tables server at it (replacing the in-memory stub), and enables the undrop
# preparation axis + undropAdmin lifecycle cases (soft-delete/restore/purge). Requires the housetables
# classes on the classpath — run once with FORCE_CP=1 after adding them (print-cp.init.gradle already
# pulls :services:housetables). See HTS-EMBED-PLAN.md / HTS-EMBED-IMPL.md. Default (unset) uses the stub.
#
# Iceberg line. By default the classpath is the default build's, the fork's Iceberg 1.12
# (docs/development/iceberg-fork.md; run scripts/iceberg-fork/publish-local.sh first), so the whole
# harness JVM runs the fork's Iceberg. HARNESS_ICEBERG_LINKEDIN=1 resolves the classpath with
# -PicebergLinkedIn instead: LinkedIn's Iceberg 1.5.2 releases, the reference line the recorded
# baseline (BASELINE-2026-11-22.md) was measured on. Each line has its own work directory, so a
# cached classpath of one is never reused for the other. HARNESS_ICEBERG_FORK=1, which selected the
# 1.12 line while it was opt-in, is still accepted and does nothing.
set -euo pipefail
cd "$(dirname "$0")"
REPO_ROOT="$(cd ../../.. && pwd)"
HERE="$(pwd)"
WORK="${TMPDIR:-/tmp}/delta-harness-oh"
LINE_ARGS=""
if [[ "${HARNESS_ICEBERG_LINKEDIN:-0}" == "1" ]]; then
  if [[ "${HARNESS_ICEBERG_FORK:-0}" == "1" ]]; then
    echo "!! HARNESS_ICEBERG_LINKEDIN=1 and HARNESS_ICEBERG_FORK=1 contradict each other" >&2
    exit 1
  fi
  LINE_ARGS="-PicebergLinkedIn"
  echo ">> HARNESS_ICEBERG_LINKEDIN=1: classpath from the -PicebergLinkedIn reference build (work dir $WORK)"
else
  # The work directory names the 1.12 line had while it was opt-in, so its cached classpath is reused.
  WORK="${WORK}-iceberg-fork"
  if [[ "${HARNESS_ICEBERG_FORK:-0}" == "1" ]]; then
    echo ">> HARNESS_ICEBERG_FORK=1 is deprecated and does nothing: the Iceberg 1.12 line is the default" >&2
  fi
  echo ">> classpath from the default build, the Iceberg 1.12 line (work dir $WORK)"
fi
mkdir -p "$WORK"

JDK17="${JAVA17_HOME:-${JAVA_HOME:?set JAVA17_HOME to a JDK 17}}"
GRADLE="${GRADLE_BIN:-gradle}"
M2="${HOME}/.m2/repository/org/scala-lang"
# Locate a scala 2.12.18 jar in ~/.m2 first, then in the Gradle module cache (where the OpenHouse build
# itself downloads it), so the harness works without a populated ~/.m2.
scala_jar() {
  local a="$1" f="$M2/$1/2.12.18/$1-2.12.18.jar"
  [[ -f "$f" ]] || f="$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/org.scala-lang/$a/2.12.18" -name "$a-2.12.18.jar" 2>/dev/null | head -1)"
  [[ -f "$f" ]] || { echo "!! $a-2.12.18.jar not found in ~/.m2 or the Gradle cache" >&2; exit 1; }
  echo "$f"
}
SCALA_LIB="$(scala_jar scala-library)"
SCALAC_CP="$(scala_jar scala-compiler):$(scala_jar scala-reflect):$SCALA_LIB"

# Classpath resolution is the slow part (~82s of gradle). It only changes when OpenHouse deps
# change, so we cache it in $WORK/oh-cp.txt and reuse it for fast inner-loop iteration. Force a
# fresh resolve with FORCE_CP=1 (do this after pulling dep changes or the first run in a session).
if [[ "${FORCE_CP:-0}" != "1" && -s "$WORK/oh-cp.txt" ]]; then
  echo ">> reusing cached OpenHouse classpath ($WORK/oh-cp.txt) — set FORCE_CP=1 to re-resolve"
else
  echo ">> resolving OpenHouse itest runtime classpath (builds the runtime uber jar + fixtures)"
  ( cd "$REPO_ROOT" && "$GRADLE" -Dorg.gradle.java.home="$JDK17" -DcpOut="$WORK/oh-cp.txt" \
      --init-script "$HERE/scripts/print-cp.init.gradle" ${LINE_ARGS} ${GRADLE_EXTRA_ARGS:-} \
      :integrations:spark:spark-3.5:openhouse-spark-3.5-itest:printHarnessCp --console=plain )
fi
OHCP="$(cat "$WORK/oh-cp.txt")"
# The Iceberg runtime on the classpath must be the line that was asked for, or the run measures the
# wrong Iceberg: the fork's version carries "-ohfork.", LinkedIn's releases never do.
runtime_jars="$(printf '%s' "$OHCP" | tr ':' '\n' | grep -E '/iceberg-spark-runtime-3\.5_2\.12-[^/]*\.jar$' || true)"
if [[ -z "$LINE_ARGS" ]]; then
  if [[ -z "$runtime_jars" ]] || printf '%s\n' "$runtime_jars" | grep -qv -- '-ohfork\.'; then
    echo "!! the classpath's Iceberg runtime is not the fork's (the default line): ${runtime_jars:-none}" >&2
    exit 1
  fi
elif printf '%s\n' "$runtime_jars" | grep -q -- '-ohfork\.'; then
  echo "!! HARNESS_ICEBERG_LINKEDIN=1 but the classpath's Iceberg runtime is the fork's: $runtime_jars" >&2
  exit 1
fi
echo ">> Iceberg runtime: $(printf '%s' "$runtime_jars" | xargs -n1 basename 2>/dev/null | paste -sd ' ' -)"

# ── Test-the-BRANCH override ────────────────────────────────────────────────────────────────────
# The harness normally resolves the PUBLISHED com.linkedin.iceberg:iceberg-spark-runtime-3.5_2.12
# (e.g. 1.5.2.15) — a Maven-Central snapshot that can LAG the openhouse-1.5.2 branch HEAD (it predates
# #251 column-defaults, etc.). To test the actual BRANCH, build the shaded runtime jar from branch HEAD
# (`gradle :iceberg-spark:iceberg-spark-runtime-3.5_2.12:shadowJar`) and point this at it:
#   ICEBERG_RUNTIME_JAR=/workspace/iceberg/spark/v3.5/spark-runtime/build/libs/<jar> ./run-openhouse.sh
# That single shaded jar carries all of iceberg api+core+spark, so swapping it makes the whole harness
# JVM (Spark side + embedded server) run the branch. Unset → back to the published release. Reversible.
if [[ -n "${ICEBERG_RUNTIME_JAR:-}" ]]; then
  [[ -f "$ICEBERG_RUNTIME_JAR" ]] || { echo "!! ICEBERG_RUNTIME_JAR not found: $ICEBERG_RUNTIME_JAR" >&2; exit 1; }
  # How many spark-runtime-3.5 entries does the resolved cp actually have? If zero, the pattern no longer
  # matches (module/version rename, jar absent) and swapping would SILENTLY leave the published jar in place
  # — so fail loudly instead of pretending we tested the branch.
  matches="$(printf '%s' "$OHCP" | tr ':' '\n' | grep -cE '/iceberg-spark-runtime-3\.5_2\.12-[^/]*\.jar' || true)"
  if [[ "$matches" -eq 0 ]]; then
    echo "!! ICEBERG_RUNTIME_JAR set but no iceberg-spark-runtime-3.5_2.12 jar found on the resolved classpath" >&2
    echo "!! (pattern changed, or cp cache is stale — re-run with FORCE_CP=1). Refusing to run the PUBLISHED jar." >&2
    exit 1
  fi
  # Replace the resolved spark-runtime-3.5 jar path (any version) with the override. Use a `|` sed delimiter
  # and a literal-ized replacement so `&`/`#`/`/` in the path are not interpreted.
  repl="$(printf '%s' "$ICEBERG_RUNTIME_JAR" | sed -e 's/[&|\\]/\\&/g')"
  OHCP="$(printf '%s' "$OHCP" | tr ':' '\n' \
           | sed -E "s|.*/iceberg-spark-runtime-3\.5_2\.12-[^/]*\.jar|$repl|" \
           | paste -sd ':' -)"
  inserted="$(printf '%s' "$OHCP" | tr ':' '\n' | grep -Fc "$ICEBERG_RUNTIME_JAR" || true)"
  [[ "$inserted" -ge 1 ]] || { echo "!! branch-mode swap produced 0 override entries — aborting" >&2; exit 1; }
  echo ">> [BRANCH MODE] iceberg-spark-runtime swapped ($matches slot(s)) -> $ICEBERG_RUNTIME_JAR"
  echo ">> [BRANCH MODE] override entries on cp: $inserted"
fi

echo ">> compiling harness (scala 2.12) against the OpenHouse classpath"
mkdir -p "$WORK/classes"
# The harness is split across several .scala files (Framework / Scenario traits / Plan / Env),
# all in `package harness`. Compile every source under src/main/scala together so cross-file
# references resolve (order is irrelevant to scalac — it compiles the whole compilation unit set).
mapfile -t SCALA_SRCS < <(find "$HERE/src/main/scala/harness/openhouse" -name '*.scala' | sort)
echo ">> ${#SCALA_SRCS[@]} source files"
"$JDK17/bin/java" -cp "$SCALAC_CP" scala.tools.nsc.Main \
  -classpath "$OHCP" -d "$WORK/classes" \
  "${SCALA_SRCS[@]}"

# Register the harness's Spring initializer (real-HTS mode drops the in-memory HTS stub; see HtsStubSuppressor).
mkdir -p "$WORK/classes/META-INF"
echo "org.springframework.context.ApplicationContextInitializer=harness.HtsStubSuppressor" > "$WORK/classes/META-INF/spring.factories"

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
# Args are passed through as case-id filters (AND). E.g. `run-openhouse.sh delete parquet`
# runs just the delete tests on parquet — a ~25s inner loop. No args runs the full matrix.
exec "$JDK17/bin/java" ${HARNESS_JVM_ARGS:-} "${OPENS[@]}" -Dio.netty.tryReflectionSetAccessible=true \
  -cp "$WORK/classes:$SCALA_LIB:$OHCP" harness.Main "$@"
