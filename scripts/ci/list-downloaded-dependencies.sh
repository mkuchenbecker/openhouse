#!/usr/bin/env bash
# Lists the dependency files a CI job downloaded beyond the Gradle cache it restored
# (.github/workflows/split-build.yml), as group:module:version and file name, and their count.
# A file Gradle downloads lands in the Gradle user home's caches/modules-2/files-2.1 with the time
# of download; a restored one keeps the time it was saved with. So the files newer than MARKER, a
# file touched right after the restore, are the job's downloads: each a request to the repository
# that it can refuse (Maven Central's 429). A dynamic version's lookup writes no file there and is
# not counted. Prints only; never fails the job.
#
#   list-downloaded-dependencies.sh MARKER
set -u
marker="$1"
files="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
if [ ! -e "$marker" ] || [ ! -d "$files" ]; then
  echo "Dependency files downloaded by this job: unknown (no marker or no $files)"
  exit 0
fi
list="$(find "$files" -type f -newer "$marker" \
  | sed -E 's#^.*/files-2\.1/([^/]+)/([^/]+)/([^/]+)/[^/]+/([^/]+)$#\1:\2:\3 \4#' | sort)"
count="$(printf '%s' "$list" | grep -c . || true)"
echo "Dependency files downloaded by this job beyond the restored Gradle cache: $count"
if [ -n "$list" ]; then
  printf '%s\n' "$list"
fi
exit 0
