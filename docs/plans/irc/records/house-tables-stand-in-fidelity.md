# House Tables stand-in fidelity (FF-32, FF-33)

Bottom line: done. Pull request 163 (draft), head 88bec497159822b1d213a121ea6af93e9a543e42; all 13 CI jobs green (one Docker job skipped by design). One stand-in, contract 30/30 on real store and on the stand-in in both suites; two product gaps pinned (view auth UUID; RF-012 UUID-only directory).

## Steps
1. Worktree /home/user/oh-standin, branch irc/house-tables-stand-in-fidelity from 96acfca3.
2. Contract `HouseTableRepositoryContract` (fixtures main), real run `HouseTableRepositoryImplContractTest` (services/tables namespaceIntegrationTest, H2 MODE=MYSQL;IGNORECASE=TRUE with schema.sql), stand-in runs in both test suites. Before-failure lists: scratchpad/standin/fx-before.txt, ts-before.txt.
3. Real store findings: cross-database and same-name rename are 400 (HouseTableCallerException); sort direction dropped by HouseTableRepositoryImpl (getSortByStr sends properties only); soft-deleted listing unsorted when asked unsorted (HTS default sortBy=databaseId).
4. Product finding to check: OpenHouseInternalTableOperations IOException cleanup calls houseTableRepository.delete(entity); real impl throws UnsupportedOperationException.

Attribution: the session notice gives "Claude Opus 5.5" lines; followed over the common brief's "Claude Fable 5.2".

## Step 3 (uncommitted at time of writing): one stand-in
- Fixture `HouseTablesH2Repository` is now `HouseTableRepository + HouseTablesH2Store`; Spring Data composes fragment `HouseTablesH2StoreImpl` (per-context lock and soft-deleted map, projection reads, real-store answers). `HouseTablesH2Configuration`: DataSource bean (fixed URL with OPTIMIZE_REUSE_RESULTS=FALSE). (Correction, later: an early draft widened the location columns to 512; the code as merged does not: the stand-in keeps H2's 255, see the widths decision below.)
- Services copy deleted (HouseTablesH2Repository, ToggleH2StatusesRepository, HouseTablesH2ReadAfterCommitTest, tables-service-test-h2.properties); SpringH2Application uses @EnableJpaRepositories(tablestest) + the configuration. Fixture properties file deleted.
- Fixture contract 30/30; fixture suite 82/82 after correcting HouseTablesH2ViewAccessorTest (seeded views through the table route; pinned a descending sort and a sorted page the real store never answers; findById of a view expected empty, real 404).
- New HouseTablesH2DatabaseTest (persistence context, per-context rows/soft-deleted/lock, URL not overridable): 4/4 on the new stand-in.
- Full service suite run 1 (default line) in progress.

## Commits so far (pushed)
- 3e9e3984 contract (tests first); ddb6f68a HouseTablesH2DatabaseTest (tests first; 4/4 red on the old stand-in, verified in a scratch worktree); 0c45a0f5 the stand-in rewrite + corrected tests.
- Widths decision: stand-in keeps H2 255 (= production DDL); real-store run is bootstrap 128/512; contract takes widths per store. Width violations come from the columns (tests that ALTER columns, e.g. HouseTableRowLimitsUnsetTest, still work).
- Known gaps pinned (product relied on the stand-in): IrcViewRoutesAuthTest (authorization resource has no UUID; pre-existing in main's ViewsServiceImpl.authorizationReference), IrcRegisterViewRouteTest.aDirectoryNamedForALiveTablesUuidIsRegistered (RF-012 UUID branch dead).
- Next: full service suite default line, fixtures suite, java itest, rck, spark smoke; reference line service suite.

## Pull request 163 opened (draft), head 88bec497 (merge of base 4b9d152d). Reference line post-merge green locally. Default-line post-merge service suite running (scratchpad/standin/def-post.log). Waiting for CI.

## Final: default line post-merge service suite 1952 tests, 0 failed, 1 skipped. CI all green on 88bec497.
