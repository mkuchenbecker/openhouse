#!/usr/bin/env python3
"""Checks that every Iceberg-line branch in test code is in the reviewed list.

A test that asks which Iceberg line the build is (IcebergLine.current(), IcebergV3.supported(),
the openhouse.iceberg.line property) can expect one thing on the reference line (LinkedIn Iceberg
1.5.2) and another on the default line (Iceberg 1.12): it accepts a difference of the default
line. scripts/ci/iceberg-line-branches.tsv lists every such place (file, the line's text and how
many times it occurs in the file), with its kind and its reason. This fails when test code has a
branch the list does not name, when the list names one the code no longer has, when a count is
not the number of occurrences, or when an entry has no known kind or no reason. It does not check
that a branch still differs between the lines.

  check-iceberg-line-branches.py

Run from the root of the checkout; reads the files git tracks. Exits 0 when the list and the code
agree, 1 when they do not.
"""
import collections
import os
import subprocess
import sys

LIST = os.path.join(os.path.dirname(os.path.abspath(__file__)), "iceberg-line-branches.tsv")
PATTERNS = (
    "IcebergLine.current()",
    "IcebergLine#current()",
    "IcebergV3.supported()",
    "openhouse.iceberg.line",
)
KINDS = {
    "difference from main",
    "declared difference",
    "one line has the feature",
    "Iceberg client version",
    "detection",
}


def in_test_code(path):
    return path.endswith(".java") and "/src/main/" not in path and "/main/java/" not in path


def branches():
    files = subprocess.run(
        ["git", "ls-files", "*.java"], capture_output=True, text=True, check=True
    ).stdout.split()
    found = collections.Counter()
    for path in files:
        if not in_test_code(path):
            continue
        with open(path, encoding="utf-8") as source:
            for line in source:
                if any(pattern in line for pattern in PATTERNS):
                    found[(path, line.strip())] += 1
    return found


def listed():
    entries = {}
    problems = []
    with open(LIST, encoding="utf-8") as listing:
        for number, line in enumerate(listing, 1):
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            fields = line.split("\t")
            if (
                len(fields) != 5
                or not fields[2].isdigit()
                or fields[3] not in KINDS
                or not fields[4].strip()
            ):
                problems.append(
                    "line %d: not file, text, count, a known kind and a reason: %s" % (number, line)
                )
                continue
            entries[(fields[0], fields[1])] = int(fields[2])
    return entries, problems


def main():
    found = branches()
    entries, problems = listed()
    for path, text in sorted(set(found) - set(entries)):
        problems.append("not in %s: %s: %s" % (os.path.basename(LIST), path, text))
    for path, text in sorted(set(entries) - set(found)):
        problems.append("listed but no longer in the code: %s: %s" % (path, text))
    for key in sorted(set(entries) & set(found)):
        if entries[key] != found[key]:
            problems.append(
                "listed %d times but occurs %d times: %s: %s"
                % (entries[key], found[key], key[0], key[1])
            )
    if problems:
        sys.stderr.write("\n".join(problems) + "\n")
        sys.stderr.write(
            "Each Iceberg-line branch in test code accepts a difference between the lines: list it"
            " in scripts/ci/iceberg-line-branches.tsv with its kind and reason.\n"
        )
        return 1
    print(
        "%d Iceberg-line branches in test code (%d distinct lines), all listed"
        % (sum(found.values()), len(found))
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
