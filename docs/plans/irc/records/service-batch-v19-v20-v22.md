# Service batch V19, V20, V22

Bottom line: all three items are done, committed tests first, and pushed (draft pull request 142, head a9378b6ef60288a67f6d145850b2cde88d8e448b); local checks pass on both lines and the kit is unchanged at 42/95/23. CI was 5 of 14 done (0 failed) at hand-back.

## Log
- Read the rows, the audit and both earlier records. [read]
- V19 plan: option (a), `cluster.housetables.table-id-max-length`, default 255, checked by
  `HouseTableRowLimits` where the 255 constant is checked today (`OpenHouseInternalTableOperations:725`).
- V20 [read]: the 1.12 client (`RESTSessionCatalog.tableFileIO`, fork origin/openhouse-1.12.0 lines 1309-1340)
  builds a table's FileIO from catalog properties overlaid with the response's `config` when that is not
  empty; LinkedIn 1.5.2 (`tableFileIO`, bytecode read with javap) does the same with `RESTUtil.merge`. So a
  per-table `io-impl` is honoured on both lines. Today the load response carries the read-bridge config,
  create/commit/register an empty config, staged create none.
- V22 [read]: `ViewsDisabledService` (`@ConditionalOnMissingClass`), `ViewsServiceImpl` `@ConditionalOnClass`;
  tests on the choice: `ViewsDisabledServiceTest`, `ViewsPaginationControllerTest.theDisabledServiceStubReportsViewsDisabled`,
  `ViewsServiceBeanWiringTest.viewApiRuntimeRegistersExactlyOneRealViewsService`,
  `IcebergViewBeanBoundaryTest.exactlyOneViewsServiceExistsForEachIcebergRuntime`. The views gate is
  `ViewsFeatureGate` and stays.
- Written, not yet built: V19 pin `TableIdConfiguredLimitTest` (H2 `TABLE_ID` narrowed to 128), V20 pin
  `IrcTableFileIoTest` (s3 default + local by regex selector, with a real `RESTCatalog`; hdfs default + local),
  V22 removal (in the tree, uncommitted). V19 and V20 code changes prepared in the scratchpad.
- Waiting for 6 GB available memory (2-3 GB since the start).
- [ran] pins on base code, 1.12: TableIdConfiguredLimitTest 1/1 (native rename to 129 on a 128 store: 500;
  IRC: 500 CommitStateUnknownException; one metadata file left by each), IrcTableFileIoTest 2/2 (s3 default:
  local table's create/load/staged-create config {}, RESTCatalog gives it S3FileIO; hdfs default: {}).
- Commits pushed: 013aea78 (V22 removal), 2a8c5bf4 (V19 pin), 4dfff62a (V20 pin).
- V19 change written (property, HouseTableRowLimits, DEPLOY.md, unit tests); waiting for memory to build.
- [ran] V19 change, 1.12: TableIdConfiguredLimitTest 2/2, MetadataLocationLimitTest 10/10 (unchanged),
  MetadataLocationConfiguredLimitTest 3/3, DefaultClusterPropertiesTest 4/4, HouseTableRowLimitsTest 5/5. Pushed 74b39247.
- [ran] V20 change, 1.12: IrcTableFileIoTest 2/2, IcebergRestTableFileIoTest 4/4, IrcConfigFileIoTest 5/5. Pushed a9378b6e.
- [ran] 1.12, touched service classes (IRC routes, views, limits, FileIO): 371 tests, 0 failed, 0 skipped.
- Running: fixtures IcebergViewBeanBoundaryTest (1.12), then the same on -PicebergLinkedIn, then the kit.
- [ran] fixtures IcebergViewBeanBoundaryTest 1.12: 3/3. -PicebergLinkedIn: tables classes 366 (11 skipped, 0 failed),
  HouseTableRowLimitsTest 5, fixtures 3: all pass.
- Draft pull request 142 opened on head a9378b6e. Kit (1.12) running.
- [ran] kit 1.12: 160 tests, 42 passed, 95 failed, 23 skipped; "the 118 that do not pass are exactly those listed";
  expected-outcomes.txt untouched (no diff). check-test-groups.py running. CI on a9378b6e running.
- [ran] check-test-groups.py (1.12): partition holds. CI at hand-back: 5 completed (4 success, 1 skipped by design),
  9 in progress, 0 failed. Handed back before CI finished, on the caller's request.
