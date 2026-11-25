# T6, N10: grants observable through Spark SQL; write checks on the Spark 3.5 plain REST path (record)

## Bottom line

- SHOW GRANTS now lists what GRANT stored and REVOKE removed, on every smoke path (s17, 22 statements, all
  identical everywhere); a grant has an observable effect natively and over IRC; test code only.
- The 19 write checks are one shared list; Spark 3.5 rest-http runs them (18 of 19, RTAS append declared)
  and runs on both Iceberg lines (no skip; 3 namespace refusals declared per line).
- Draft pull request 140, head f0b340e769c00101f6af09ae144a518fc3c5cf19. CI on that head: all 14 jobs
  green (2 Docker-on-reference skipped by design).

## Commits

- fe0ae995 T6: backend, smoke service, s17, effect and cross-path tests.
- 98f77a43 N10: shared write checks, StoredTable, rest-http on both lines.
- 1294f757 merge of origin/irc-integration (apps/ only; no overlap).
- f0b340e7 N10: full 1.5.2 wording of the three namespace refusals (after a local 1.5.2 run).

## Counts (smoke statements 210; base 195)

| Path | 1.12 | 1.5.2 |
|---|---|---|
| legacy | 208 [ran] | 210 [ran] |
| rest-shim | 208 [ran] | 210 [ran] |
| rest-http | 201 [ran] | 201 [ran] |
| Spark 4.2 | 199 [CI pass, inferred] | n/a |

Write checks: Spark 4.2 19/19 (baseline) [CI]; rest-http 18/19 on 1.12 and on 1.5.2 [ran].

## Finding

only_granted with CREATE TABLE on the database: IRC create ok; OpenHouse catalog creates the table then
fails 403 reading it back. Pinned in IcebergRestGrantsUnlockSqlTest, not fixed (depends on deployed policy).

## Decisions

1. One backend source shared by both itests (src/shared/authorization/java): same service behavior.
2. Spark 3.5 smoke: one service per class for all paths (merged with the views service), no new server.
3. Effect and cross-path tests in IcebergRestGrantsUnlockSqlTest (has principals and three catalogs on
   one service); smoke service has a single principal ("undefined").
4. Write-check baseline is Spark 4.2 over IRC (where the checks came from).
5. 3.5 rows read through the OpenHouse catalog in Spark (iceberg-data is excluded from that classpath).
6. Line-specific declarations follow IcebergLine.current(); RestHttpPathLineTest pins it to the build.

## Not verified

- Locally on -PicebergLinkedIn only the touched catalogTest classes ran (133 tests); rest left to CI.
- No local Spark 4.2 run; relied on CI.
- Commit attribution uses the session's system attribution line (Opus 5.5), not the brief's.
