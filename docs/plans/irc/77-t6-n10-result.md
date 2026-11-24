# T6 and N10: grants seen through Spark SQL, and write checks on the Spark 3.5 plain REST path

Bottom line: a grant is now observed doing something on every smoke path. SHOW GRANTS lists what GRANT stored and
REVOKE removed, and a grant takes effect, natively and over the Iceberg REST routes. The 19 write-statement checks are
one shared list that Spark 4.2 and the Spark 3.5 plain REST path both run, and that path now runs on both Iceberg lines
with no skip. Only test code changed. Pull request 140 is merged into `irc-integration` on 2026-11-24 as merge commit
`dfcd15a0487f70ca893be907da9a20618a008630` (pull request head `f0b340e7`); it closes T6 and N10. Marks: [ran] was run
here, [CI] passed in the pull request's CI, [read] read in code, [inferred] arithmetic or reasoning.

## How grants are stored in tests

- The test services of every path store grants through `GrantStoringAuthorization`, one test source compiled by both
  integration test projects. It is put in through the extension point tests already used (a primary Spring bean) [read].
- No production authorization code or wiring changed [read]. Before this, the test services dropped grants, so SHOW
  GRANTS was always empty and the scenarios showed only that the statements did not fail.

## What the grants smoke scenario asserts

- Scenario s17 has 22 statements (was 7). It asserts the exact SHOW GRANTS listing after each GRANT and its absence
  after each REVOKE, for the table form and the database form, and that a GRANT on a table without the sharing policy
  is refused [ran].
- It is identical on every path: the OpenHouse catalog, the REST-shaped shim, Spark 3.5 on the plain REST catalog, and
  Spark 4.2 [ran on Spark 3.5; CI on Spark 4.2].

## The effect tests and the cross-path test

`IcebergRestGrantsUnlockSqlTest` has 17 tests (was 14) [ran]:
- A principal is refused a SELECT, reads after GRANT SELECT while still refused an INSERT, and is refused again after
  REVOKE. The same holds for CREATE TABLE on a database.
- A grant or revoke made through one of three catalogs on one service is seen through the other two.

## The write checks

- The 19 checks are one list, `SparkSqlWriteStatements`, run by Spark 4.2 over the Iceberg REST routes and by Spark 3.5
  on the plain REST path. Differences are declared per path: an undeclared difference fails, and so does a declared
  one that no longer happens. The baseline is Spark 4.2, where the checks came from.
- Spark 4.2: 19 of 19 [CI]. Spark 3.5 plain REST: 18 of 19 on the 1.12 line and on the 1.5.2 line [ran].
- The one declared difference: after CREATE OR REPLACE TABLE ... AS SELECT the snapshots are [append, append] where
  Spark 4 gives [append, overwrite]. Scenario s22 already declares the same difference.

## The Iceberg 1.5.2 line on the plain REST path

- The path runs there with no skip; the old skip was an assumption. Only the three namespace refusals differ: that
  client has no endpoint list, sends the request, and the service's catch-all refuses it, so the wording is the
  client's. The three are declared with that wording, taken from a local 1.5.2 run [ran].
- Line-specific declarations follow `IcebergLine.current()`, and `RestHttpPathLineTest` pins it to the build [ran].
- All three Spark 3.5 smoke paths now share one embedded service per test class, merged with the service that existed
  only for views, so the number of services does not grow. No statement's outcome changed apart from s17.

## Smoke counts (of 210 statements; the base was 195, [inferred] by arithmetic)

| Path | Iceberg 1.12 | Iceberg 1.5.2 |
| --- | --- | --- |
| legacy | 208 [ran] | 210 [ran] |
| rest-shim | 208 [ran] | 210 [ran] |
| rest-http | 201 [ran] | 201 [ran] |
| Spark 4.2 | 199 [CI] | not run |

The declared differences are unchanged: 2, 2, 9 and 11 (Spark 4.2).

## Finding: a principal holding only CREATE TABLE on a database

Over the Iceberg REST routes the create succeeds. Through the OpenHouse catalog the table is created and the statement
then fails with 403, because the catalog reads the new table back and, in the test authorization model, the creator
holds nothing on its own table. It is pinned in `IcebergRestGrantsUnlockSqlTest` and not fixed, because whether it is a
defect depends on what a deployed policy grants a table's creator, which is not in this repository. Put to Mike in
`58-waiting-on-mike.md`; nothing waits on it.

## Evidence

- [ran] JDK 17, Iceberg 1.12 line: the three smoke paths (32 tests each), the grants test (17), the write checks (19)
  and the line test (1). The same six classes with `-PicebergLinkedIn`: 133 tests, 0 failed, 0 skipped.
  `scripts/ci/check-test-groups.py`: the partition holds.
- [CI] On the pull request head `f0b340e7` all 14 jobs passed (two Docker-on-reference jobs skipped by design). That
  covers the full Spark 3.5 itest on both lines, every Spark 4.2 task and the jobs projects, which were left to CI.
- Post-merge CI for the merge `dfcd15a0` is not read yet.

## Known gaps

- The role-to-privilege mapping of the test backend is a model written for the tests; a deployed policy may differ.
- No effect test on Spark 4.2: its listing assertions run, but the effect tests run on Spark 3.5 with three catalogs on
  one service. No Spark 4.2 test was run locally; its count is what CI enforces through the declared differences.
- The base count of 195 statements is arithmetic, not a run of the base.
- The smoke service has a single principal; the effect tests need three, so they live in the grants test.
