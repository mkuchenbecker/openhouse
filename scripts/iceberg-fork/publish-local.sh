#!/usr/bin/env bash
# Builds the Iceberg commit pinned in iceberg-fork.properties and publishes the modules OpenHouse's
# Iceberg 1.5 variant consumes into <ICEBERG_FORK_HOME>/<version>, for the opt-in -PicebergFork build,
# plus the Spark 4.2 / Scala 2.13 runtime the Spark 4.2 IRC read test runs on (a second Iceberg
# build: one Iceberg build has one Scala version), and the REST compatibility kit (iceberg-open-api's
# test and test-fixtures jars) the REST compatibility kit test runs against the service.
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
#      JAVA_HOME             a JDK 17 or 21 (Iceberg 1.12 needs 17+)
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

publish "$marker" 3.5 2.12 iceberg-bom iceberg-api iceberg-bundled-guava iceberg-common \
  iceberg-core iceberg-data iceberg-parquet iceberg-orc iceberg-spark:iceberg-spark-runtime-3.5_2.12
# Only the shaded runtime: the Spark 4.2 test puts nothing else of Iceberg on its classpath.
publish "$spark42_marker" 4.2 2.13 iceberg-spark:iceberg-spark-runtime-4.2_2.13
# The kit's tests (RESTCompatibilityKit*Tests) and its RCKUtils; its base classes (CatalogTests,
# ViewCatalogTests) are in iceberg-core's tests jar, published above.
publish "$rck_marker" 3.5 2.12 iceberg-open-api
echo "Published Iceberg $group:$version ($commit) to $repo"
