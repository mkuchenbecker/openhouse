#!/usr/bin/env python3
"""The groups that a pull request's build runs as parallel jobs (.github/workflows/split-build.yml).

A pull request's build is the whole `./gradlew clean build --continue` of a build line, cut by
project into groups that run side by side on their own runners. Each named group below is a
directory pattern; every directory it matches that holds a Gradle build file becomes a job of its
own, which runs that project's `check` (its test tasks, checkstyle and spotbugs). The last group,
`rest`, runs the whole build with the `check` of every matched project excluded. The init script
scripts/ci/test-groups.gradle does the cutting inside Gradle, by project directory.

So the jobs together run every task of `build`, once, whatever is added: a new test task goes
with its project, a new project under a pattern (say apps/spark-4.2) gets its own job, and a
project no pattern matches runs in `rest`. A matched directory that holds no project of the line
(a Spark 4.2 project on the reference line) is a job that says so and runs nothing.

The patterns are where the time goes: on the pull request run of 2026-11-24 (52 minutes for the
default build on one runner) the apps' tests took 38 minutes, the Spark 4.2 itest's five tasks 22
(one after another, in one project) and the Spark 3.5 itest's three 17.5; the rest ran beside them.

  test-groups.py matrix [ROOT]   the jobs, as a JSON list of {"group", "mode", "dirs"} objects
                                 for the jobs' matrix (ROOT: the checkout, default `.`)

Each job's `mode` and `dirs` are the init script's CI_GROUP_MODE and CI_GROUP_DIRS (directories
one per line). Reads the checkout's directories only; prints only.
"""
import glob
import json
import os
import sys

# A pattern per named group; each matching project directory is one job.
PATTERNS = [
    "apps/spark-*",
    "integrations/spark/spark-*/openhouse-spark-itest",
]

BUILD_FILES = ("build.gradle", "build.gradle.kts")


def project_dirs(root, pattern):
    found = []
    for path in sorted(glob.glob(os.path.join(root, pattern))):
        if any(os.path.isfile(os.path.join(path, name)) for name in BUILD_FILES):
            found.append(os.path.relpath(path, root).replace(os.sep, "/"))
    return found


def matrix(root):
    dirs = [d for pattern in PATTERNS for d in project_dirs(root, pattern)]
    if len(set(dirs)) != len(dirs):
        raise SystemExit("a directory matches two patterns: %s" % dirs)
    # The job's name and its report artifact's: the directory's last two parts (no slashes).
    jobs = [{"group": "-".join(d.split("/")[-2:]), "mode": "only", "dirs": d} for d in dirs]
    if len({job["group"] for job in jobs}) != len(jobs):
        raise SystemExit("two directories make the same group name: %s" % dirs)
    jobs.append({"group": "rest", "mode": "except", "dirs": "\n".join(dirs)})
    return jobs


def main(argv):
    if len(argv) in (2, 3) and argv[1] == "matrix":
        print(json.dumps(matrix(argv[2] if len(argv) == 3 else ".")))
        return 0
    sys.stderr.write(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
