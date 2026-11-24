#!/usr/bin/env python3
"""The test groups that the pull request build runs as parallel jobs (.github/workflows/split-build.yml).

A pull request's build is the whole `./gradlew build` of a build line, cut into groups that run
side by side on their own runners. The named groups below each run their own Gradle test tasks;
the group `rest` runs `./gradlew build` with exactly those tasks excluded (`-x`). So the groups
together run every task `build` runs, once: a task that no named group lists, a test task added
later among them, runs in `rest`. A named task that no longer exists fails both its own group and
`rest` (Gradle refuses an unknown task, also after `-x`), so a rename cannot drop a suite silently.

The groups are cut where the time goes: measured on the pull request run of 2026-11-24 (52 minutes
for the default build), the apps' tests alone took 38 minutes, the Spark 4.2 itest's five tasks 22
(they run one after another, in one project), the Spark 3.5 itest's three 17.5, and everything
else ran beside them.

  test-groups.py matrix LINE         the line's groups, as a JSON list (the jobs' matrix)
  test-groups.py args LINE GROUP     the Gradle arguments of one group, one per line

LINE is `default` (Iceberg 1.12) or `reference` (-PicebergLinkedIn, LinkedIn's Iceberg 1.5.2,
which has no Spark 4.2 modules). Reads nothing; prints only.
"""
import json
import sys

APPS = ":apps:openhouse-spark-apps-1.5_2.12"
SPARK35_ITEST = ":integrations:spark:spark-3.5:openhouse-spark-3.5-itest"
SPARK42_ITEST = ":integrations:spark:spark-4.2:openhouse-spark-4.2-itest"

# Each named group's test tasks, by line. A task's own dependencies run with it (compiling what it
# needs); the tasks of one project run one after another there, as they did in the single build.
GROUPS = {
    "default": {
        "apps": [APPS + ":test"],
        "spark-3.5-itest": [SPARK35_ITEST + ":catalogTest", SPARK35_ITEST + ":statementTest", SPARK35_ITEST + ":test"],
        "spark-4.2-itest": [
            SPARK42_ITEST + ":test",
            SPARK42_ITEST + ":extensionsTest",
            SPARK42_ITEST + ":smokeTest",
            SPARK42_ITEST + ":testServerPlanning",
            SPARK42_ITEST + ":smokeTestServerPlanning",
        ],
    },
    "reference": {
        "apps": [APPS + ":test"],
        "spark-3.5-itest": [SPARK35_ITEST + ":catalogTest", SPARK35_ITEST + ":statementTest", SPARK35_ITEST + ":test"],
    },
}

REST = "rest"


def matrix(line):
    return list(GROUPS[line]) + [REST]


def args(line, group):
    if group == REST:
        excluded = [task for tasks in GROUPS[line].values() for task in tasks]
        return ["build"] + [arg for task in excluded for arg in ("-x", task)]
    return list(GROUPS[line][group])


def main(argv):
    if len(argv) == 3 and argv[1] == "matrix" and argv[2] in GROUPS:
        print(json.dumps(matrix(argv[2])))
        return 0
    if len(argv) == 4 and argv[1] == "args" and argv[2] in GROUPS and argv[3] in matrix(argv[2]):
        print("\n".join(args(argv[2], argv[3])))
        return 0
    sys.stderr.write(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
