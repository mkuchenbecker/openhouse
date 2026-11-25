# V11a, V12a: IRC scan planning (synchronous) and the credentials route: record

Bottom line: done. On the Iceberg 1.12 line, POST .../plan is served synchronously (`completed`, every task inline,
no state), and GET .../credentials answers an empty list. Both run the load's checks. The server never sends
scan-planning-mode. LinkedIn's 1.5.2 reference line still answers both 406. Draft pull request 127, head
37d85483c60730fe1c44e49167cc69709f59cbb1, CI green (both checks).

Worktree /home/user/oh-v11a, branch irc/v11a-v12a-plan-credentials, base 72947228, merged with irc-integration 57db4015.

## Commits
- a183800c test: pinning test (plan and credentials 406, not listed; header leaves load and create unchanged).
- 792ac267 feat: routes in services/tables/src/iceberg-1.12 (default build only), the Table now carried in
  TableDtoWithMetadata, IcebergRestApiHandler.loadIcebergTable, and the tests. The endpoint list, the unserved
  list and the fixtures' boundary test change in the same commit.
- 6658e71c test: Spark 4.2 server-planning parity, and no-plan-request checks ordered last (test, smokeTest).
  The smoke service now has an access log.
- 37d85483 merge of irc-integration (W7, X4).

## Counts [ran, on 37d85483]
- :services:tables:test 1.12: 1695 tests, 0 failed, 1 skipped (FormatV3NativeApiTest, existing).
- :services:tables:test 1.5.2: 1679 tests, 0 failed, 39 skipped (the 16 tests that exist only on 1.12 are absent).
- fixtures-iceberg-1.5 test: 41 passed. Spark 4.2 test: 67 passed. smokeTest: 66 passed.
- restCompatKitTest: 160 = 41 passed, 96 failed, 23 skipped; expected-outcomes.txt untouched; the task passed.
- spotless: spotlessApply run on every touched project; spotlessCheck and spotlessJavaCheck UP-TO-DATE on current
  sources (not re-executed).
- Plan requests seen: Spark test JVM 25 of 999, all from SparkIrcScanPlanningTest; smoke JVM 0 of 1228.
- CI on 37d85483: Build and Run Tests success; Reference build on LinkedIn's Iceberg 1.5.2 success.

## Decisions (two-way doors)
1. Per-line source dir (as in openhouse-catalog-capabilities) plus @ConditionalOnClass(PlanTableScanRequest).
   On 1.5.2 both routes stay 406. Test: IrcPlanCredentialsRoutesTest on both lines.
2. The load checks run before the body is parsed. Test: IrcPlanCredentialsAuthTest.
3. use-snapshot-schema=false with a snapshot id reads the branch whose head is that snapshot (table schema);
   otherwise the snapshot's schema. Test: aBranchIsPlannedWithTheTablesSchema.
4. Incremental scans: start exclusive, end inclusive (the specification); CatalogHandlers takes the start inclusive.
   Test: anIncrementalScanPlansTheAppendsBetweenTwoSnapshots.
5. 400 for an unparsable body or filter, an unbindable filter, an unknown snapshot, a start without an end, and a
   negative min-rows-requested. An unknown selected name is ignored, as on the client.
6. GET and DELETE on plan/{id} and POST on tasks: 406, not listed.
7. Credentials use Iceberg's LoadCredentialsResponse; planId is ignored.
8. A plan or credentials call emits the load's READ audit event.
9. Spark test tasks order @Order classes last via junit.jupiter.testclass.order.default; other classes keep
   their discovery order (checked by timestamps).

## Not verified
- Memory and latency of planning large tables; Spark 3.5; MERGE, UPDATE and DELETE with server planning;
  S3 and ADLS storage (all tests use local storage); extensionsTest (not touched, not run locally; it is part of CI).
