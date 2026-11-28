#!/usr/bin/env python3
"""Fails when a source line cites an id that only a private planning list can resolve.

Messages, log lines, test names and comments must say what they mean in words. An id such as a
work item ("X5", "V11e", "C2-3"), a decision ("D7", "decision B"), a review finding ("RF-045",
"FF-34", "BC-008"), "work item", or a pull request of this fork by number tells a reader outside
the effort that wrote it nothing.

Scans every file git tracks, tests included (their names, assertion messages and comments are
read by every developer and reviewer), except prose documentation (`*.md`, `docs/`) and the
Spark delta harness, whose scenario and finding ids are defined in its own HARNESS-GUIDE.md. A
line that matches but is not such an id (a constant named F1, test data, a linter code) is listed
in scripts/ci/private-ids-allowed.txt, one per line, as `<path> | <id> | <the line, trimmed>`, or
with `*` for the line to allow that id on any line of that file. The whole tree is scanned, not a
diff, so the check needs no git history and works on any base; the allow-list holds the base's own
matches.

  check-private-ids.py [--list]

Run from the root of the checkout. `--list` prints every match in the allow-list's format,
allowed or not, and exits 0. Exits 1 when a match is not in the allow-list.
"""
import os
import re
import subprocess
import sys

ALLOWED = os.path.join("scripts", "ci", "private-ids-allowed.txt")
SKIPPED_FILES = {ALLOWED, os.path.join("scripts", "ci", "check-private-ids.py")}
SKIPPED_DIRS = ("docs/", "integrations/spark/delta-harness/")

# An id is a capital letter of the planning list's series followed by a number, optionally a
# lower-case suffix and a hyphenated part (X5, V11e, D14b, C2-3, N3-x1, N1-c), standing alone: not
# inside a word (FormatV3Types), a path or a version (v1, 1.5.2); a stated requirement is S and
# two or more digits (S47; S3 alone is Amazon's). V0 to V3 alone are names of
# Iceberg format and API versions and of constants here; they count only alone in parentheses,
# "(V2)".
ID = r"(?:[CDFINTUVWX]\d{1,3}[a-z]?(?:-[a-z0-9]{1,3})?|S\d{2,3}|(?:RF|FF|BC)-\d{1,3})"
PATTERNS = [
    re.compile(r"(?<![\w$.\-/#])" + ID + r"(?![\w])"),
    re.compile(r"\bwork items?\b", re.IGNORECASE),
    re.compile(r"\bdecisions? (?:[A-Z]{1,2}\d|[A-Z]\b|of [A-Z]{1,2}\d)"),
    re.compile(r"\b(?:pull request|PR) ?#?(?:8\d|9\d|1[0-5]\d|16[0-2])\b"),
    re.compile(r"docs/plans"),
]
FORMAT_VERSION = re.compile(r"V[0-3]")


def matches(line):
    found = []
    for pattern in PATTERNS:
        for m in pattern.finditer(line):
            token = m.group(0)
            if FORMAT_VERSION.fullmatch(token):
                before, after = line[: m.start()], line[m.end():]
                if not (before.endswith("(") and after.startswith(")")):
                    continue
            found.append(token)
    return found


def tracked_files():
    out = subprocess.run(["git", "ls-files", "-z"], capture_output=True, check=True).stdout
    for path in out.decode("utf-8").split("\0"):
        if not path or path in SKIPPED_FILES or path.endswith(".md"):
            continue
        if path.startswith(SKIPPED_DIRS) or not os.path.isfile(path):
            continue
        yield path


def read_allowed():
    allowed = set()
    with open(ALLOWED, encoding="utf-8") as f:
        for line in f:
            if line.strip() and not line.startswith("#"):
                path, token, text = (part.strip() for part in line.split(" | ", 2))
                allowed.add((path, token, text))
    return allowed


def main(argv):
    listing = "--list" in argv
    allowed = set() if listing else read_allowed()
    seen = set()
    refused = []
    for path in tracked_files():
        try:
            with open(path, encoding="utf-8") as f:
                lines = f.read().splitlines()
        except UnicodeDecodeError:
            continue
        for number, line in enumerate(lines, 1):
            for token in matches(line):
                key = (path, token, line.strip())
                anywhere = (path, token, "*")
                seen.add(anywhere if anywhere in allowed else key)
                if listing:
                    print(" | ".join(key))
                elif key not in allowed and anywhere not in allowed:
                    refused.append("%s:%d: %s: %s" % (path, number, token, line.strip()))
    if listing:
        return 0
    for key in sorted(allowed - seen):
        print("note: allow-list entry matches nothing now: %s" % " | ".join(key))
    if refused:
        print("Lines that cite a private planning id (say what it means in words instead):")
        print("\n".join(refused))
        print(
            "%d line(s). A match that is not such an id goes in %s as `<path> | <id> | <line>`."
            % (len(refused), ALLOWED)
        )
        return 1
    print("No private planning ids outside %s." % ALLOWED)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
