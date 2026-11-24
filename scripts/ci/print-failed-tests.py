#!/usr/bin/env python3
"""Prints every failed test case found in Gradle's JUnit XML results under a build directory.

For a red CI job. Gradle's closing summary names only the failed task ("There were failing tests"),
and the test's own FAILED line is wherever the test ran: thousands of lines up a log that several
test tasks write to at once. Run as a job's last step, this puts each failed test's name and the
start of its failure (and of every "Caused by") at the end of the log, where a reader, or a tool
that fetches only a log's tail, finds it.

  print-failed-tests.py [--exclude TASK]... [--tail GLOB]... [--lines N] [--log FILE] BUILD_DIR

BUILD_DIR   searched for <project>/test-results/<task>/TEST-*.xml at any depth
--exclude   a test task whose results are skipped (repeatable), for a task that reports its
            failures itself
--tail      also print the last lines of each file matching this glob (repeatable), for a log
            that a test's separate server process wrote
--log       Gradle's console output of the build (tee'd by the step that ran it). Gradle writes a
            task's JUnit XML only when the task's test JVM lives to the end: when it dies (an
            OutOfMemoryError, a System.exit; "Process 'Gradle Test Executor N' finished with
            non-zero exit value") the task fails with no XML at all, and the tests that failed
            before the death exist only as "Class > test FAILED" lines in the console. With this,
            the failures of every test class that has no result file are printed from the log,
            and the log's "N tests completed, M failed" lines are repeated, so that the two can be
            compared
--lines     how many lines of each failure and of each tailed file to print (default 40)

Reads only; changes nothing. Exits 0 whether or not a test failed (the task that ran the tests
already failed the job) and 2 when BUILD_DIR is not a directory. With GITHUB_ACTIONS set, the
first ten failures also become error annotations on the run.
"""
import argparse
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

MAX_ANNOTATIONS = 10


def excerpt(text, lines):
    """The first `lines` lines of a failure, then each later "Caused by:" line with two frames."""
    all_lines = (text or "").rstrip().splitlines()
    kept = all_lines[:lines]
    index = lines
    while index < len(all_lines):
        if all_lines[index].lstrip().startswith("Caused by:"):
            kept.append("\t...")
            kept.extend(all_lines[index : index + 3])
            index += 3
        else:
            index += 1
    if len(all_lines) > len(kept):
        kept.append("\t... (%d lines in all)" % len(all_lines))
    return kept


def failures_in(path):
    """(class, test, kind, text) for each failed or errored test case of one result file."""
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as problem:
        return [("?", os.path.basename(path), "unreadable", str(problem))]
    found = []
    for case in root.iter("testcase"):
        for kind in ("failure", "error"):
            for node in case.findall(kind):
                text = node.text or node.get("message") or "(no message)"
                found.append((case.get("classname", "?"), case.get("name", "?"), kind, text))
    if not found and root.tag == "testsuite" and (
        int(root.get("failures") or 0) + int(root.get("errors") or 0)
    ):
        # Counted failures with no failing test case: report the suite itself.
        found.append((root.get("name", "?"), "(suite)", "error", "the suite reports failures but names no test case"))
    return found


FAILED_LINE = re.compile(r"^(?:\S+Z )?([A-Za-z_][\w.$]*) > (.*) FAILED\s*$")
SUMMARY_LINE = re.compile(r"^(?:\S+Z )?(\d+ tests completed, \d+ failed.*)$")


def console_failures(path):
    """([(simple class, test, text)], [summary line]) from Gradle's console output.

    A failed test is "<Class> > <test> FAILED" and its indented lines (exception, "Caused by").
    """
    failures, summaries, current = [], [], None
    with open(path, errors="replace") as log:
        for raw in log:
            line = raw.rstrip("\n")
            match = FAILED_LINE.match(line)
            if match:
                current = (match.group(1), match.group(2), [])
                failures.append(current)
                continue
            if current is not None and re.match(r"^(?:\S+Z )?\s{4,}\S", line):
                current[2].append(re.sub(r"^\S+Z ", "", line).strip())
                continue
            current = None
            summary = SUMMARY_LINE.match(line)
            if summary:
                summaries.append(summary.group(1))
    return [(c, t, "\n".join(x) or "(no detail in the console)") for c, t, x in failures], summaries


def annotation(value):
    """A value escaped for a GitHub Actions workflow command."""
    return value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("build_dir")
    parser.add_argument("--exclude", action="append", default=[], metavar="TASK")
    parser.add_argument("--tail", action="append", default=[], metavar="GLOB")
    parser.add_argument("--log", metavar="FILE")
    parser.add_argument("--lines", type=int, default=40)
    args = parser.parse_args()
    if not os.path.isdir(args.build_dir):
        print("print-failed-tests: %s is not a directory" % args.build_dir, file=sys.stderr)
        return 2

    pattern = os.path.join(args.build_dir, "**", "test-results", "*", "TEST-*.xml")
    files = sorted(glob.glob(pattern, recursive=True))
    tasks = {}
    for path in files:
        task = os.path.basename(os.path.dirname(path))
        if task not in args.exclude:
            tasks.setdefault((os.path.dirname(path), task), []).append(path)

    failed = 0
    print("==== Failed tests (JUnit XML under %s) ====" % args.build_dir)
    for (directory, task), paths in sorted(tasks.items()):
        cases = [(path, found) for path in paths for found in failures_in(path)]
        print("%s: %d result files, %d failed test cases" % (directory, len(paths), len(cases)))
        for path, (classname, test, kind, text) in cases:
            failed += 1
            print()
            print("FAILED (%s) %s > %s" % (kind, classname, test))
            for line in excerpt(text, args.lines):
                print("    " + line)
            if os.environ.get("GITHUB_ACTIONS") and failed <= MAX_ANNOTATIONS:
                first = (text.strip().splitlines() or ["(no message)"])[0]
                title = "%s: %s > %s" % (task, classname.rsplit(".", 1)[-1], test)
                print("::error title=%s::%s" % (annotation(title), annotation(first)))
    if not tasks:
        print("no result files found (excluded tasks: %s)" % (", ".join(args.exclude) or "none"))
    if args.log:
        try:
            console, summaries = console_failures(args.log)
        except OSError as problem:
            console, summaries = [], []
            print("print-failed-tests: cannot read %s: %s" % (args.log, problem), file=sys.stderr)
        # Classes that have a result file are reported above (the console repeats those failures).
        with_xml = {
            os.path.basename(path)[len("TEST-") : -len(".xml")].rsplit(".", 1)[-1] for path in files
        }
        missing = [entry for entry in console if entry[0].rsplit(".", 1)[-1] not in with_xml]
        print()
        print("%s: %d failed test cases of classes with no result file" % (args.log, len(missing)))
        for summary in summaries:
            print("Gradle: " + summary)
        for classname, test, text in missing:
            failed += 1
            print()
            print("FAILED (console) %s > %s" % (classname, test))
            for line in excerpt(text, args.lines):
                print("    " + line)
            if os.environ.get("GITHUB_ACTIONS") and failed <= MAX_ANNOTATIONS:
                first = (text.strip().splitlines() or ["(no message)"])[0]
                print("::error title=%s::%s" % (annotation("%s > %s" % (classname, test)), annotation(first)))
    print()
    print("==== %d failed test cases ====" % failed)

    for tail in args.tail:
        for path in sorted(glob.glob(tail, recursive=True)):
            try:
                with open(path, errors="replace") as log:
                    lines = log.read().splitlines()
            except OSError as problem:
                lines = ["(unreadable: %s)" % problem]
            print()
            print("==== Last %d of %d lines of %s ====" % (min(args.lines, len(lines)), len(lines), path))
            for line in lines[-args.lines :]:
                print(line)
    return 0


if __name__ == "__main__":
    sys.exit(main())
