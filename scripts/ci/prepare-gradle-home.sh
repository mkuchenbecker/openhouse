#!/usr/bin/env bash
# Prepares the runner's Gradle user home after setup-gradle restored the Gradle cache, for every
# Gradle command of a CI job (.github/workflows/split-build.yml, dataloader-tests.yml). Nothing here
# is saved with the cache (setup-gradle saves caches/ and notifications/ only), and nothing reaches a
# developer's build.
#
#   prepare-gradle-home.sh reader|writer
#
# - Touches "$RUNNER_TEMP/gradle-cache-restored", which dates the restore for
#   list-downloaded-dependencies.sh at the end of the job.
# - Prints how old the restored cache is (its newest file) and keeps the number for the download
#   list, which warns past 19 days: the readers' dynamic-version lists then go stale
#   (dependency-cache.gradle keeps them 20 days) and the jobs ask Maven Central again.
# - Gives Gradle more patience with a refused request. Gradle 8.14 retries a request that failed
#   with a 429, a 408, a 5xx or a connection error (ErrorHandlingModuleComponentRepository, with
#   NetworkingIssueVerifier), by default 3 tries 1 s and 2 s apart, and then disables the repository
#   for the rest of the Gradle command, which fails everything else that command still needs from
#   it. Here: 7 tries, 2 s apart and doubling, at most about two minutes for a request that keeps
#   failing. The two properties are Gradle's internal ones (org.gradle.internal.repository.*), read
#   as system properties of the build; what is resolved does not change, only how long Gradle waits.
# - For a reader only (a job that does not save the cache): installs dependency-cache.gradle in
#   init.d. The writer keeps Gradle's defaults, so that each merge refreshes what readers reuse.
set -eu
role="$1"
if [ "$role" != reader ] && [ "$role" != writer ]; then
  echo "usage: $0 reader|writer" >&2
  exit 2
fi
home="${GRADLE_USER_HOME:-$HOME/.gradle}"
here="$(cd "$(dirname "$0")" && pwd)"

touch "$RUNNER_TEMP/gradle-cache-restored"

newest="$(find "$home/caches/modules-2" -type f -printf '%T@\n' 2>/dev/null | sort -n | tail -n 1 | cut -d. -f1)"
if [ -n "$newest" ]; then
  days=$(( ($(date +%s) - newest) / 86400 ))
  echo "Restored Gradle cache: newest file from $(date -u -d "@$newest" '+%Y-%m-%d %H:%M UTC'), $days days old"
  echo "$days" > "$RUNNER_TEMP/gradle-cache-age-days"
else
  echo "Restored Gradle cache: none"
fi

mkdir -p "$home"
printf '\n%s\n%s\n' \
  'systemProp.org.gradle.internal.repository.max.tentatives=7' \
  'systemProp.org.gradle.internal.repository.initial.backoff=2000' >> "$home/gradle.properties"
echo "Gradle tries a refused request up to 7 times, from 2 s apart (gradle.properties in the Gradle user home)"

if [ "$role" = reader ]; then
  mkdir -p "$home/init.d"
  cp "$here/dependency-cache.gradle" "$home/init.d/"
  echo "Reader: dynamic versions and the Spring Boot plugin from the restored cache (init.d/dependency-cache.gradle)"
else
  echo "Writer: Gradle's own cache periods"
fi
