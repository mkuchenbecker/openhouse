#!/usr/bin/env python3
"""Checks, by Gradle dry runs, that the pull request's test groups run exactly `build`.

For one build line: `./gradlew clean build -m`, then the same with the init script
scripts/ci/test-groups.gradle for each job that scripts/ci/test-groups.py lists. Passes when the
jobs' tasks together are exactly the tasks of `build` and each test task (a task named `test` or
ending in `Test`, or one of the itests' server-planning reruns) is in exactly one job. A project
that no group names is in `rest`, so this also shows where a new project lands.

  check-test-groups.py [-PicebergLinkedIn] [GRADLE_ARG]...

Run from the root of the checkout. A dry run configures the build but runs no task; it takes a
minute or two a job. Exits 0 when the partition holds, 1 when it does not.
"""
import json
import os
import re
import subprocess
import sys

ROOT = os.getcwd()
HERE = os.path.dirname(os.path.abspath(__file__))
TEST_TASK = re.compile(r"(test|[A-Za-z]*Test|[A-Za-z]*TestServerPlanning|testServerPlanning)")
NOT_TESTS = ("checkstyle", "spotbugs", "compile", "process")


def dry_run(extra, env=None):
    command = ["./gradlew", "clean", "build", "-m", "-q", "--console=plain"] + extra
    result = subprocess.run(command, cwd=ROOT, env=env, capture_output=True, text=True)
    if result.returncode != 0:
        sys.stderr.write(result.stdout + result.stderr)
        raise SystemExit("dry run failed: %s" % " ".join(command))
    return {line.split()[0] for line in result.stdout.splitlines() if line.startswith(":")}


def is_test(task):
    name = task.rsplit(":", 1)[1]
    return bool(TEST_TASK.fullmatch(name)) and not name.startswith(NOT_TESTS)


def main(extra):
    jobs = json.loads(subprocess.check_output([sys.executable, os.path.join(HERE, "test-groups.py"), "matrix", ROOT]))
    build = dry_run(extra)
    init = ["-I", os.path.join(HERE, "test-groups.gradle")]
    groups = {}
    for job in jobs:
        env = dict(os.environ, CI_GROUP_MODE=job["mode"], CI_GROUP_DIRS=job["dirs"])
        # `help` is what a job with no project of this line runs instead.
        groups[job["group"]] = dry_run(extra + init, env) - {":help"}
    union = set().union(*groups.values())
    ok = True
    for task in sorted(build - union):
        print("in build, in no job: " + task)
        ok = False
    for task in sorted(union - build):
        print("in a job, not in build: " + task)
        ok = False
    tests = sorted(t for t in build if is_test(t))
    for task in tests:
        owners = [g for g, tasks in groups.items() if task in tasks]
        if len(owners) != 1:
            print("test task in %d jobs: %s %s" % (len(owners), task, owners))
            ok = False
    for group, tasks in groups.items():
        print("%-40s %4d tasks, test tasks: %s" % (group, len(tasks), " ".join(sorted(t for t in tasks if is_test(t))) if group != "rest" else len([t for t in tasks if is_test(t)])))
    print("build: %d tasks, %d test tasks; %s" % (len(build), len(tests), "partition holds" if ok else "PARTITION BROKEN"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
