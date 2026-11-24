#!/usr/bin/env python3
"""Self-test of scripts/ci/print-failed-tests.py, on small synthetic result trees.

  test-print-failed-tests.py

Needs no Gradle and no checkout besides this directory; runs in the `test groups` job of the
pull request's build. Exits 0 when every case passes.
"""
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "print-failed-tests.py")

SUITE = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="%(cls)s" tests="2" failures="%(failures)s" errors="%(errors)s">
%(cases)s
</testsuite>
"""


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as out:
        out.write(text)


def result(root, project, task, cls, cases, failures=0, errors=0):
    path = os.path.join(root, "build", project, "test-results", task, "TEST-%s.xml" % cls)
    write(path, SUITE % dict(cls=cls, cases=cases, failures=failures, errors=errors))


def run(root, *args):
    done = subprocess.run(
        [sys.executable, "-I", SCRIPT, *args, "build"],
        cwd=root, capture_output=True, text=True,
    )
    return done.returncode, done.stdout


class PrintFailedTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.root = self.dir.name

    def tearDown(self):
        self.dir.cleanup()

    def test_failures_errors_and_class_level_setup_are_all_printed(self):
        result(self.root, "openhouse-spark-apps-1.5_2.12", "test", "a.b.OneTest", (
            '<testcase classname="a.b.OneTest" name="ok"/>'
            '<testcase classname="a.b.OneTest" name="bad"><failure message="m">boom\n at X</failure></testcase>'
            '<testcase classname="a.b.OneTest" name="err"><error message="e">npe</error></testcase>'
        ), failures=1, errors=1)
        # A failure in @BeforeAll is recorded as a test case named "classMethod".
        result(self.root, "other", "test", "a.b.TwoTest",
               '<testcase classname="a.b.TwoTest" name="classMethod"><failure>setup</failure></testcase>', failures=1)
        # Counted failures that name no test case still show.
        result(self.root, "other", "smokeTest", "a.b.ThreeTest", "", failures=1)
        code, out = run(self.root)
        self.assertEqual(code, 0)
        for expected in ("(failure) a.b.OneTest > bad", "(error) a.b.OneTest > err",
                         "(failure) a.b.TwoTest > classMethod", "a.b.ThreeTest > (suite)",
                         "==== 4 failed test cases ===="):
            self.assertIn(expected, out)

    def test_excluded_task_is_skipped(self):
        result(self.root, "kit", "restCompatKitTest", "k.KitTest",
               '<testcase classname="k.KitTest" name="x"><failure>f</failure></testcase>', failures=1)
        _, out = run(self.root, "--exclude", "restCompatKitTest")
        self.assertIn("==== 0 failed test cases ====", out)

    def test_console_failures_of_a_dead_test_jvm_are_printed(self):
        # The JVM died: no XML for OperationsTest, but SeenTest finished and has its XML.
        result(self.root, "apps", "test", "a.b.SeenTest",
               '<testcase classname="a.b.SeenTest" name="t"><failure>f</failure></testcase>', failures=1)
        log = os.path.join(self.root, "gradle.log")
        write(log, "\n".join([
            "2026-11-24T14:17:09.9Z OperationsTest > testA() FAILED",
            "2026-11-24T14:17:09.9Z     org.apache.spark.SparkException at DAGScheduler.scala:2856",
            "2026-11-24T14:17:09.9Z         Caused by: java.lang.OutOfMemoryError",
            "OperationsTest > testB(String) > [1] x, y FAILED",
            "    java.lang.IllegalStateException at OperationsTest.java:629",
            "SeenTest > t() FAILED",
            "    already in the XML",
            "OperationsTest > testC() PASSED",
            "2026-11-24T14:17:11.7Z 4 tests completed, 3 failed, 0 skipped",
            "> Task :apps:test FAILED",
            "",
        ]))
        _, out = run(self.root, "--log", log)
        self.assertIn("FAILED (console) OperationsTest > testA()", out)
        self.assertIn("Caused by: java.lang.OutOfMemoryError", out)
        self.assertIn("FAILED (console) OperationsTest > testB(String) > [1] x, y", out)
        self.assertNotIn("(console) SeenTest", out)
        self.assertIn("Gradle: 4 tests completed, 3 failed, 0 skipped", out)
        self.assertIn("==== 3 failed test cases ====", out)

    def test_missing_build_dir_exits_2(self):
        code, _ = run(self.root)
        self.assertEqual(code, 2)


if __name__ == "__main__":
    unittest.main()
