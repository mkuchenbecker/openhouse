#!/usr/bin/env python3
"""The verdict of a build line's last job (`Build and Run Tests` in split-build.yml).

The job needs every other job of the line and runs whatever their outcome. It passes only when
each of them succeeded. A job that the workflow skips by design on this line (the Docker job on
the reference line) is named with --allow-skip; any other skipped job fails the verdict, as do a
failed and a cancelled one, so that a job that did not run cannot pass for one that ran green.

  check-needed-jobs.py [--allow-skip JOB]... NEEDS_JSON
  check-needed-jobs.py --self-test

NEEDS_JSON is the workflow's `toJSON(needs)`: {"<job id>": {"result": "...", ...}, ...}. Prints
one line per job and exits 0 when the verdict is success, 1 otherwise. `--self-test` checks the
verdict on fixed cases and needs nothing else.
"""
import json
import sys


def verdict(needs, allowed_skips):
    """Returns (ok, lines): whether every needed job passes, and one line per job."""
    ok = bool(needs)
    lines = []
    for job in sorted(needs):
        result = (needs[job] or {}).get("result", "")
        if result == "success":
            lines.append("%s: success" % job)
        elif result == "skipped" and job in allowed_skips:
            lines.append("%s: skipped, as this line does by design" % job)
        else:
            lines.append("%s: %s, so the line is not green" % (job, result or "no result"))
            ok = False
    for job in sorted(set(allowed_skips) - set(needs)):
        lines.append("%s: allowed to be skipped but not a needed job" % job)
        ok = False
    if not needs:
        lines.append("no needed job: nothing to vouch for")
    return ok, lines


def self_test():
    def needs(**results):
        return {job: {"result": result, "outputs": {}} for job, result in results.items()}

    cases = [
        ("all green", needs(plan="success", tests="success", docker="success"), [], True),
        ("a red group", needs(plan="success", tests="failure", docker="success"), [], False),
        ("a cancelled group", needs(plan="success", tests="cancelled", docker="success"), [], False),
        ("plan red, the rest skipped", needs(plan="failure", tests="skipped", docker="skipped"), [], False),
        ("Docker skipped on a line that runs it", needs(plan="success", tests="success", docker="skipped"), [], False),
        ("Docker skipped by design", needs(plan="success", tests="success", docker="skipped"), ["docker"], True),
        ("a skip allowed for one job only", needs(plan="success", tests="skipped", docker="skipped"), ["docker"], False),
        ("an allowed skip that ran red", needs(plan="success", tests="success", docker="failure"), ["docker"], False),
        ("an allowed skip that is not needed", needs(plan="success", tests="success"), ["docker"], False),
        ("no result", {"plan": {}}, [], False),
        ("nothing needed", {}, [], False),
    ]
    failures = 0
    for name, given, allowed, expected in cases:
        ok, _ = verdict(given, allowed)
        if ok != expected:
            failures += 1
            print("FAIL %s: verdict %s, expected %s" % (name, ok, expected))
    print("self-test: %d case(s), %d failure(s)" % (len(cases), failures))
    return 1 if failures else 0


def main(argv):
    if argv == ["--self-test"]:
        return self_test()
    allowed = []
    while len(argv) >= 2 and argv[0] == "--allow-skip":
        allowed.append(argv[1])
        argv = argv[2:]
    if len(argv) != 1:
        sys.stderr.write(__doc__)
        return 2
    ok, lines = verdict(json.loads(argv[0]), allowed)
    for line in lines:
        print(line)
    print("Build and Run Tests: %s" % ("success" if ok else "FAILURE"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
