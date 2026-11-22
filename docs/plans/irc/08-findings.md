# Findings from the first round of work

Bottom line: the test foundation exists and is green, the first four Iceberg patches are reapplied, and
writing the tests exposed existing defects and gaps in LinkedIn main. Everything below was observed by
delegated agents on 2026-11-22. Nothing here is a decision.

## What now exists

| Item | Result | Where |
| --- | --- | --- |
| T1 Spark SQL smoke suite | 21 scenarios, green locally and in CI | openhouse fork, pull request 80 |
| T3 Catalog plugin contract tests | 18 tests, green on Iceberg 1.2 and 1.5 lines | openhouse fork, pull request 81 |
| T4 Service commit-path tests | 50 tests in four groups, green | openhouse fork, pull request 82 |
| I3/I4 first slice | 4 LinkedIn patches reapplied, each with a failing-then-passing test | iceberg fork, pull request 3 |

## Existing behavior that looks like a defect (pinned as it is today)

Fixing any of these is a behavior change and needs a decision.

- An unparseable snapshot in a snapshot commit returns 500, not 400.
- A table update that supplies a lock state on a table that never had one returns 500 (null pointer).
- A staged replace that drops a column used by the sort order returns 500.
- Invalid sort order JSON returns 500. Exact duplicate column names return 500; case-only duplicates return 400.
- Renaming a table to a name that differs only by letter case from another existing table succeeds,
  because identifiers are case-sensitive on rename.
- Loading a table with an invalid identifier through the plugin reports "table does not exist". The
  plugin swallows the 400, so the caller cannot tell invalid from missing.
- A snapshot commit that changes nothing still writes a new metadata version.
- A comment in the code says a 422 on commit surfaces as a commit-state-unknown error. It does not. It
  surfaces as a plain client error with status 422.

## Gaps in what tests can observe

- Grants: the embedded test server skips grants and lists none. No fixture in the repo can observe that a
  grant took effect, on any catalog path.
- Commit conflicts: the test stand-in for the house table store has no version check, so conflicts
  cannot be produced at the HTTP boundary. A job to make the stand-in faithful is in progress.
- Soft delete: the same stand-in inverts the delete flag relative to the real client, so soft-delete
  behavior seen in tests does not match production. Same job.
- Authorization: before T4, no black-box test ever received a 403. T4 now pins a 403 for every operation.

## Other observations

- The Iceberg fork's CI cannot currently prove anything: about 40 jobs start at once and Maven Central
  rate-limits them (HTTP 429) before any code compiles. Local runs are the only validation of the patches.
- Reapplying the write-distribution default required editing five upstream test assertions, the same
  ones LinkedIn's own commit edited.
- Apache 1.12 already writes an application name into snapshot summaries under its own key. LinkedIn's
  patch adds its own key next to it. Both are kept.
- The repo's commit hooks fail under JDK 17 unless extra compiler exports are passed.
