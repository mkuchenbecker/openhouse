#!/usr/bin/env python3
"""Fails when a source line cites an id that only a private planning list can resolve.

Messages, log lines, test names, comments and documentation must say what they mean in words. An
id such as a work item ("X5", "V11e", "C2-3", "P1"), a decision ("D7", "decision B"), a stated
requirement ("S47"), a review finding ("RF-045", "FF-34", "BC-008"), "work item", or a pull request
of this fork by number ("pull request 154", "#154") tells a reader outside the effort that wrote it
nothing. Test data named after an id ("x6maint", "n16.fresh") is reported too.

Scans every file git tracks, tests and documentation included (test names, assertion messages and
the build's documents are read by every developer and reviewer). One directory is skipped: the
Spark delta harness, a standalone tool that no CI workflow and no project of settings.gradle
builds, whose scenario and finding labels are partly local to each file and partly refer to
documents that are not in this repository; it is left as it is until its future is decided. A
comment wrapped over two lines is matched joined, so "work" at the end of one line and "item V1"
at the start of the next is found. A match that is not such an id (a constant named F1, test data,
a linter code) is listed in scripts/ci/private-ids-allowed.txt, one per line, as
`<path> | <id> | <the line, trimmed>`, or with `*` for the line to allow that id on any line of
that file (only for ids that cannot also be a planning id there). The whole tree is scanned, not a
diff, so the check needs no git history and works on any base; the allow-list holds the base's own
matches.

  check-private-ids.py [--list | --self-test]

Run from the root of the checkout. `--list` prints every match in the allow-list's format,
allowed or not, and exits 0. `--self-test` checks the patterns on fixed examples. Exits 1 when a
match is not in the allow-list.
"""
import os
import re
import subprocess
import sys

ALLOWED = os.path.join("scripts", "ci", "private-ids-allowed.txt")
SKIPPED_FILES = {ALLOWED, os.path.join("scripts", "ci", "check-private-ids.py")}
SKIPPED_DIRS = ("integrations/spark/delta-harness/",)

# An id is a capital letter of the planning list's series followed by a number, optionally a
# lower-case suffix and a hyphenated part (X5, V11e, D14b, C2-3, N3-x1, N1-c), standing alone: not
# inside a word (FormatV3Types), a path or a version (v1, 1.5.2); a stated requirement is S and
# two or more digits (S47; S3 alone is Amazon's). V0 to V3 alone are also names of Iceberg format
# and API versions and of constants here; they count only alone in parentheses, "(V2)", or after
# words that cite an item ("work item V1", "since V2").
ID = r"(?:[CDFIJNPTUVWX]\d{1,3}[a-z]?(?:-[a-z0-9]{1,3})?|S\d{2,3}|(?:RF|FF|BC)-\d{1,3})"
PATTERNS = [
    re.compile(r"(?<![\w$.\-/#])" + ID + r"(?![\w])"),
    re.compile(r"\bwork items?\b", re.IGNORECASE),
    re.compile(r"\bdecisions? (?:[A-Z]{1,2}\d|[A-Z]\b|of [A-Z]{1,2}\d)"),
    # This fork's pull requests are numbered from 80 on; "pull request(s)" or "PR(s)" with any
    # such number, with no upper bound. A bare "#N" counts for 80 to 999 unless it follows a
    # repository name ("apache/iceberg#14507"): upstream issue numbers in that range are cited
    # as owner/repo#N, or listed in the allow-list.
    re.compile(r"\b(?:pull requests?|PRs?) ?#?(?:8\d|9\d|[1-9]\d{2,})\b"),
    re.compile(r"(?<![\w/&#])#(?:8\d|9\d|[1-9]\d{2})\b"),
    re.compile(r"docs/plans"),
    # Test data named after a work item: x6maint, x10-compaction, x5n3_t, n16.fresh.
    re.compile(r"(?<![\w$.\-/#\\])(?:x\d{1,2}|n\d{2})(?=[a-z_.\-])"),
]
FORMAT_VERSION = re.compile(r"V[0-3]")
CITING = re.compile(r"(?:\bwork items?|\bitems?|\bsince|\buntil|\bbefore|\bafter|\bof) $", re.I)
COMMENT = re.compile(r"^\s*(?:\*|//|#|--)\s?")


def matches(line):
    found = []
    for pattern in PATTERNS:
        for m in pattern.finditer(line):
            token = m.group(0)
            if FORMAT_VERSION.fullmatch(token):
                before, after = line[: m.start()], line[m.end():]
                if not (before.endswith("(") and after.startswith(")")) and not CITING.search(
                    before
                ):
                    continue
            found.append(token)
    return found


def wrapped(line, following):
    """The matches of a comment line joined with the comment line after it that neither has."""
    if not (COMMENT.match(line) and COMMENT.match(following)):
        return []
    joined = matches(line.rstrip() + " " + COMMENT.sub("", following, count=1).strip())
    for token in matches(line) + matches(following):
        if token in joined:
            joined.remove(token)
    return joined


def self_test():
    found = [
        ("// had no pin before work", " * item V1: reserved"),
        ("see (P1) here", None),
        ("J26 decided it", None),
        ("pull requests 170 and 171", None),
        ("as #120 did", None),
        ("pull request 1234", None),
        ("since V2 the route", None),
        ('String db = "x6maint";', None),
        ('"n16.fresh"', None),
        ("(decision D7)", None),
        ("S47 says", None),
    ]
    not_found = [
        "FormatV3Types.checkOnV3",
        '@PostMapping(V1 + "/views/rename")',
        "apache/iceberg#14507",
        "Amazon S3 storage",
        'row(5L, "n5")',
        'row(2L, "n2-u")',
        "ImmutableMap.of(V3, true)",
        "pull request 12 of the Iceberg fork",
        "color: #fff",
    ]
    failed = 0
    for line, following in found:
        got = matches(line) + (wrapped(line, following) if following else [])
        if not got:
            print("self-test: not found in %r %r" % (line, following))
            failed += 1
    for line in not_found:
        if matches(line):
            print("self-test: wrongly found %s in %r" % (matches(line), line))
            failed += 1
    print("self-test: %d failure(s)" % failed)
    return 1 if failed else 0


def tracked_files():
    out = subprocess.run(["git", "ls-files", "-z"], capture_output=True, check=True).stdout
    for path in out.decode("utf-8").split("\0"):
        if not path or path in SKIPPED_FILES:
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
    if "--self-test" in argv:
        return self_test()
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
            following = lines[number] if number < len(lines) else ""
            for token in matches(line) + wrapped(line, following):
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
