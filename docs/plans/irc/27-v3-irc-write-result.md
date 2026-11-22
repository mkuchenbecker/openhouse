# IRC table write routes (V3): result

Bottom line: an unmodified Iceberg 1.5.2 `RESTCatalog`, and Spark 3.5 through Iceberg's own `SparkCatalog`
(`type=rest`), can now create, write, change and drop OpenHouse tables over IRC, under the native rules. After
every step the OpenHouse Java client sees the same stored metadata. They cannot yet set policies, grants or
locks, and schema column renames and drops are refused as natively. Written by a delegated agent from branch
`irc/v3-irc-write-routes` (pull request 94, merged as 14f23870). "Ran" means run. "Read" means taken from code or
earlier records only.

## What works (ran)

- The `RESTCatalog` creates tables (partitioned or not); appends, overwrites and deletes rows (position deletes);
  adds a column; sets and unsets properties; changes the sort order; creates branches and tags and commits to a
  branch; runs a replace transaction (RTAS); stages a create and commits it, with or without data; drops and
  renames tables; and runs two writers on one table without losing a commit (a stale-base commit gets
  `CommitFailedException` and the retry succeeds). Test: `IcebergRestCatalogWriteTest`, 10 tests.
- Spark 3.5 SQL runs CREATE TABLE, INSERT, SELECT, UPDATE, DELETE and MERGE (copy-on-write and merge-on-read),
  ALTER TABLE ADD COLUMN and SET TBLPROPERTIES, CTAS, RTAS on a `replace.enabled=true` table, DROP TABLE and
  RENAME. The OpenHouse catalog reads the same rows. Test: `IcebergRestSparkSqlTest`, 3 tests.

## What does not work, and why

- Renaming or dropping a column gets 400 "invalid schema evolution", exactly as natively: the native rule matches
  columns by name and forbids drops. Pinned in both itests.
- Partition evolution, and RTAS on a table without `replace.enabled`, get the native 400.
- Naming a location, or changing location, UUID or format version, gets 400 by design (D20).
- Policies are reserved properties and get the native 400. Grants, lock and unlock, replica tables (IRC has no
  table-type field), views, register, metrics reports, multi-table transactions and namespace create, update and
  drop (406) are not served.

## Rules and how each was proven

Evidence was run. "=" means the stored metadata or message equals the native one.

| Rule (inventory in `24-commit-rule-inventory.md`) | Over IRC | Test |
| --- | --- | --- |
| Create rules, properties, location, UUID, forced values | Native create handler; stored metadata = | `createStoresWhatTheNativeCreateStores` and three more |
| Create on an existing table | 409 `AlreadyExistsException`, native message | `createOfAnExistingTableIs409AlreadyExists` |
| Create privilege | 403, native message | `IrcWriteRoutesAuthTest.aDeniedCreateIs403AsNatively` |
| Update privileges, SYSTEM_ONLY access | 403, native message | `aDeniedCommitIs403AsNatively` |
| Legacy lock | 400 with the native message | `aLockedTableIsRefusedWithTheNativeMessage`, `replaceOfALockedTable...` |
| Capability gate | 422 `ClientCapabilityMissingException`, native message | `aTableRequiringACapabilityIsGatedOnCommitAsNatively` |
| Base version, retry cache, house-table CAS | Base is the stored table loaded in the same request; a refusal is 409 `CommitFailedException` | Mapping read in code. No test forces a race inside one request. |
| Reserved keys and raw `policies` | 400, native message | `reservedPropertyChangesGetTheNative400` |
| Partition and schema evolution | 400 | `partitionEvolutionGetsTheNative400`, `droppingAColumnGetsTheNative400` |
| Properties, schema, sort order, snapshots, refs | Stored metadata = | `propertySetAndUnset...`, `addingAColumn...`, `sortOrderChange...`, `appendStores...`, `aBranchAndATagAreStored` |
| Replace gates (`replace.enabled`, WAP, lock, policies kept) | Native messages; policies kept; stored state = | `replaceIsRefusedWithTheNativeMessageWhenRtasIsNotEnabled` and four more |
| Create with data (CTAS) | Native snapshots handler; stored metadata = | `stagedCreateThenCommitWithDataStoresWhatTheNativeCtasStores` |
| Drop | Native handler, 204; 404 is `NoSuchTableException` | `dropIsTheNativeDrop` |
| Rename: exists 409, case-only 400, across databases 400, missing 404, lock, privilege | Native handler and messages | `renameIsTheNativeRename`, `renameRefusalsAreTheNativeOnes` |
| Table count limit, max-ref-age, column-default checks | Same core path; no IRC test (read) | none |

## Replace detection rule

A non-create commit is a replace when its updates contain `SetCurrentSchema`, `SetDefaultPartitionSpec` and
`SetDefaultSortOrder`, and its requirements contain none of `AssertCurrentSchemaID`, `AssertDefaultSpecID` or
`AssertDefaultSortOrderID`. This is the rule in `OpenHouseRestAdapter.isReplace`. It holds on both lines (read, in
the 1.5.2 and 1.12 sources): `replaceTransaction` adds any of the three updates the replacement lacks,
`UpdateRequirements.forReplaceTable` never asserts the three ids, and `forUpdateTable` always does.

- What could fool it: only a hand-made request that sets all three without asserting them. It then gets the
  replace rules, as a native replace does. A replace that sent the assertions would be judged as an update. No
  Iceberg client does that.
- Concurrency: a replace asserts only the table UUID, so a commit that landed between the client's load and its
  replace is replaced over. That is Iceberg's own replace semantics (D22).

## Staged create

The IRC staged create runs the native create checks in order (existence 409, privilege, capability gate, initial
version, table count limit). It returns the metadata the create would store, with the server-computed properties,
the allocated location `<db>/<t>-<openhouseUUID>` and the sort order, through the new
`OpenHouseInternalRepository.stageCreate`. That method writes nothing, so no table, row or file is left behind
(`stagedCreateLeavesNoTableNoRowAndNoFile`).

The completing commit (the one with `assert-create`) drops the reserved keys the staged response carried, takes the
OpenHouse UUID from the allocated location (any other location is 400), and creates the table there through
`TablesService.createTable(body, creator, uuid)`, or through the native create-with-data when it carries a
snapshot. The native staged create writes a metadata file and no row, and never removes the file.

## Error mapping

| Cause | Status and type | Client reading (1.5.2 and 1.12) |
| --- | --- | --- |
| Requirement failed, or the core refused a moved base | 409 `CommitFailedException`, native conflict wording | `CommitFailedException`; the client retries |
| Unknown commit state | 500 `CommitStateUnknownException`, native wording | Both lines read 500, 502 and 504 as unknown state. Natively it is 503. |
| Table or name exists | 409 `AlreadyExistsException` | `AlreadyExistsException` |
| Native validation, historic partition column, Iceberg `ValidationException`, unparseable update, no body | 400 `BadRequestException`, native message | `BadRequestException` |
| SYSTEM_ONLY lock access | 423, as on the read routes | `RESTException` |
| Authorization | 403 `ForbiddenException` | `ForbiddenException` |
| Missing table, or table route in a multi-level namespace | 404 `NoSuchTableException` | `NoSuchTableException` |
| Capability missing, or other unprocessable entity such as the table count limit | 422 with the native class name | `RESTException` |

## Differences from the equivalent native write

1. The Iceberg `table-uuid` is drawn by the create, so the staged response's is not the created table's. The
   OpenHouse UUID, which names the directory, is kept.
2. A staged create leaves nothing and has no `metadata-location`. The native one leaves a file.
3. A commit with no updates writes nothing and returns the stored table. The native snapshots PUT writes a version.
4. Drop ignores `purgeRequested`, because the native drop always deletes the data files, so
   `RESTCatalog.dropTable` with purge false deletes data too (D22).
5. The response metadata is reloaded after the commit, so a commit landing in between shows in it.
6. Failures at the IRC layer (failed requirement, parse error, refused location, UUID or spec) are not in the
   table audit. Policies cannot be set, and the table type is always PRIMARY.
7. Create-commit field ids are re-freshened by the native create. They equal the client's for flat schemas;
   nested schemas evolved inside a create transaction could be renumbered (not tested). Schema evolution messages
   embed the candidate's schema JSON, with ids from Iceberg's builder.

## Choices where the brief left room

- Create entry: reuse, not extraction. IRC creates go through `TablesApiHandler.createTable`. The only native
  change is that `putTable` takes its UUID through a supplier. New entries are `createTable(body, creator, UUID)`,
  `stageCreateTable` and `OpenHouseInternalRepository.stageCreate`.
- The commit base comes from `TableCommitService.loadBase`, with no read checks, so a legacy-locked table answers
  the native 400 and not 403.
- A replace, or any snapshot or ref change, is `SNAPSHOTS_UPDATE`; everything else is `TABLE_UPDATE`, as the
  native client chooses its route. An inexpressible partition spec is 400 on create.
- A multi-level namespace gives 404 for commit, drop and a rename source, and 400 for create and a rename target.
- The config route sends `io-impl=HadoopFileIO` and `rest-metrics-reporting-enabled=false` as defaults, not
  overrides. Without the first, a Spark merge-on-read DELETE failed in `ResolvingFileIO` (file-replication-factor
  not supported). Without the second, the itest log held 25 "Failed to report metrics" warnings, and now 0.
- Table audit: advices record IRC commits (UPDATE, COMMIT, REPLACE_COMMIT), the create-completing commit
  (CREATE) and the staged create (STAGED_CREATE).
- Service audit of failed IRC requests was reported, not built (follow-up V3c). The failure pointcut in
  `services/common` covers only the native exception handlers, casts the body to the native `ErrorResponseBody`,
  and labels `/iceberg/...` as UNRECOGNIZED. Fixing it means changing common code or duplicating its event builder.

## Counts (ran, one full invocation each)

`:services:tables:test`: 1373 tests, 0 failed (1329 before, read, at 7f7c5638). Spark 3.5 `catalogTest`: 238 tests,
0 failed, 1 skipped (219 before, read).

The service suite gained `IrcWriteRoutesTest` 38, `IrcWriteRoutesAuthTest` 3, golden +2 and pull request 92's +1.
The Spark suite gained 10 and 3 tests. Opt-in `compileJava` and `compileTestJava` pass, and `spotlessCheck` passes
on both modules. Existing V2 tests were updated, not removed.

## Not run

Spark 3.5 `statementTest` and `test`; a 1.12 `RESTCatalog` against these routes; the real house table store
(MySQL) mode; the delta-harness; CI on pull request 94. Read only: the two spec revisions for these routes (1.12
adds an `Idempotency-Key` header, not honoured) and the `ErrorHandlers` and `UpdateRequirements` sources.
