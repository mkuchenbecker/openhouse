#!/usr/bin/env bash
# Builds the Iceberg commit pinned in iceberg-fork.properties and publishes the modules OpenHouse's
# Iceberg 1.5 variant consumes (iceberg-aws for the service's S3 storage among them) into
# <ICEBERG_FORK_HOME>/<version>, for the opt-in -PicebergFork build, plus the Spark 4.2 / Scala 2.13
# runtime the Spark 4.2 IRC read test runs on (a second Iceberg build: one Iceberg build has one
# Scala version), and the REST compatibility kit (iceberg-open-api's test and test-fixtures jars)
# the REST compatibility kit test runs against the service.
# Nothing is published remotely and ~/.m2 is not touched. Re-running is a no-op once the pinned
# version is published; FORCE=1 republishes. See docs/development/iceberg-fork.md.
#
# The published repository lives outside the checkout, under the Gradle user home and keyed by the
# published version (which carries the pinned commit): `./gradlew clean` never deletes it, git never
# sees it, and every worktree of this repository shares one copy per pin. gradle/iceberg-fork.gradle
# resolves the same location.
#
# Env: ICEBERG_FORK_HOME     checkout + repositories (default: ${GRADLE_USER_HOME:-~/.gradle}/iceberg-fork);
#                            set it the same way for the Gradle build
#      ICEBERG_GRADLE_ARGS   extra Gradle arguments for the Iceberg build
#      JAVA_HOME             a JDK 17 or 21 (Iceberg 1.12 needs 17+); else `java` on the PATH
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
props="$root/scripts/iceberg-fork/iceberg-fork.properties"
prop() { sed -n "s/^$1=//p" "$props"; }

repo_url="$(prop repoUrl)"
commit="$(prop commit)"
group="$(prop group)"
version="$(prop baseVersion)-ohfork.${commit:0:12}"
home="${ICEBERG_FORK_HOME:-${GRADLE_USER_HOME:-$HOME/.gradle}/iceberg-fork}"
src="$home/src"
repo="$home/$version"
marker="$repo/.published-$version"
# Separate marker, so a repo published before the Spark 4.2 runtime was added gains just that.
spark42_marker="$repo/.published-$version-spark-4.2_2.13"
# Likewise for the REST compatibility kit, added after both.
rck_marker="$repo/.published-$version-open-api"

if [[ -f "$marker" && -f "$spark42_marker" && -f "$rck_marker" && "${FORCE:-0}" != 1 ]]; then
  echo "Iceberg $group:$version already published in $repo (FORCE=1 to republish)"
  exit 0
fi

# Iceberg 1.12's build needs Java 17 or later. The fork's gradlew runs $JAVA_HOME/bin/java, else
# `java` on the PATH; an older one would fail inside Iceberg's build, minutes in, with an error that
# does not say why.
java_cmd="${JAVA_HOME:+$JAVA_HOME/bin/}java"
java_version="$("$java_cmd" -version 2>&1 | sed -n 's/.* version "\([^"]*\)".*/\1/p' | head -n 1)" || true
java_major="${java_version%%.*}"
if [[ "$java_major" == 1 ]]; then
  java_major="$(echo "$java_version" | cut -d. -f2)"
fi
if ! [[ "$java_major" =~ ^[0-9]+$ ]] || (( java_major < 17 )); then
  echo "publish-local.sh: Iceberg 1.12 needs Java 17 or later; $java_cmd is '${java_version:-not a JDK}'." >&2
  echo "Set JAVA_HOME to a JDK 17 or 21." >&2
  exit 1
fi

# One checkout serves every pin and every worktree, so concurrent runs take turns (flock is absent
# on macOS by default; there, do not run two at once).
mkdir -p "$home"
if command -v flock >/dev/null 2>&1; then
  exec 9>"$home/.lock"
  flock 9
fi
# Another run may have finished publishing while this one waited.
if [[ -f "$marker" && -f "$spark42_marker" && -f "$rck_marker" && "${FORCE:-0}" != 1 ]]; then
  echo "Iceberg $group:$version already published in $repo (FORCE=1 to republish)"
  exit 0
fi

if [[ ! -d "$src/.git" ]]; then
  git init -q "$src"
  git -C "$src" remote add origin "$repo_url"
fi
git -C "$src" remote set-url origin "$repo_url"
if ! git -C "$src" cat-file -e "$commit^{commit}" 2>/dev/null; then
  git -C "$src" fetch --depth 1 origin "$commit"
fi
git -C "$src" -c advice.detachedHead=false checkout -q --force "$commit"
test "$(git -C "$src" rev-parse HEAD)" = "$commit"

# The fork's gradlew downloads its wrapper jar, if absent, with no checksum and then runs it. Fetch
# it here instead and run the build only with the jar whose SHA-256 is pinned in the properties; a
# jar left by an earlier run that does not match is replaced, and a download that does not match
# stops the script before anything runs.
wrapper_jar="$src/gradle/wrapper/gradle-wrapper.jar"
wrapper_sha256="$(prop gradleWrapperJarSha256)"
sha256() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }
if [[ ! -f "$wrapper_jar" || "$(sha256 "$wrapper_jar")" != "$wrapper_sha256" ]]; then
  rm -f "$wrapper_jar"
  curl -fsSL -o "$wrapper_jar.download" "$(prop gradleWrapperJarUrl)"
  actual="$(sha256 "$wrapper_jar.download")"
  if [[ "$actual" != "$wrapper_sha256" ]]; then
    rm -f "$wrapper_jar.download"
    echo "publish-local.sh: the fork's gradle-wrapper.jar has SHA-256 $actual, not the pinned" >&2
    echo "$wrapper_sha256 (scripts/iceberg-fork/iceberg-fork.properties); not running it." >&2
    exit 1
  fi
  mv "$wrapper_jar.download" "$wrapper_jar"
fi
echo "The fork's gradle-wrapper.jar has the pinned SHA-256 $wrapper_sha256"

# publish <marker> <sparkVersion> <scalaVersion> <module>...: one Iceberg build, then the marker.
publish() {
  local done_marker="$1" spark="$2" scala="$3"
  shift 3
  if [[ -f "$done_marker" && "${FORCE:-0}" != 1 ]]; then
    return 0
  fi
  local tasks=()
  for m in "$@"; do tasks+=(":$m:publishToMavenLocal"); done
  mkdir -p "$repo"
  # A republish that stops half way must not leave the earlier marker claiming a complete set.
  rm -f "$done_marker"
  (
    cd "$src"
    # shellcheck disable=SC2086
    ./gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx4g \
      -DsparkVersions="$spark" -DscalaVersion="$scala" -DflinkVersions= -DkafkaVersions= \
      -Dmaven.repo.local="$repo" -PpublishGroup="$group" -PpublishVersion="$version" \
      ${ICEBERG_GRADLE_ARGS:-} "${tasks[@]}" 9>&-
  )
  touch "$done_marker"
}

# iceberg-aws: the service's S3FileIO and AwsClientFactories, so that no 1.2-era Iceberg jar sits
# next to the fork's core (its iceberg-build.properties would also name the wrong version).
publish "$marker" 3.5 2.12 iceberg-bom iceberg-api iceberg-bundled-guava iceberg-common \
  iceberg-core iceberg-data iceberg-parquet iceberg-orc iceberg-aws \
  iceberg-spark:iceberg-spark-runtime-3.5_2.12
# Only the shaded runtime: the Spark 4.2 test puts nothing else of Iceberg on its classpath.
publish "$spark42_marker" 4.2 2.13 iceberg-spark:iceberg-spark-runtime-4.2_2.13
# The kit's tests (RESTCompatibilityKit*Tests) and its RCKUtils; its base classes (CatalogTests,
# ViewCatalogTests) are in iceberg-core's tests jar, published above.
publish "$rck_marker" 3.5 2.12 iceberg-open-api
echo "Published Iceberg $group:$version ($commit) to $repo"
