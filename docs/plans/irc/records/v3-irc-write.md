# V3: IRC table write routes

Branch `irc/v3-irc-write-routes` (mkuchenbecker/openhouse). Draft pull request into `irc-integration`: https://github.com/mkuchenbecker/openhouse/pull/94. Head `f449d323187e692cd6abd6b3fbab0cf5f8e58e0a`.

The branch was cut from `origin/irc/v1-candidate-commit` (pull request 89). Since then, pull request 89 has merged into `irc-integration` together with pull requests 91 and 92. `origin/irc-integration` at f60e32dd is merged into this branch (fb18f215), so the pull request no longer sits on top of 89.

## Bottom line (all ran)

**An unmodified Iceberg 1.5.2 `RESTCatalog` can now:**
- create tables, partitioned and unpartitioned;
- append, overwrite and row-level delete (position deletes);
- add a column;
- set and unset properties;
- change the sort order;
- create branches and tags, and commit to a branch;
- run a replace transaction (RTAS);
- stage a create and then commit it, with or without data;
- drop and rename tables;
- run two writers on one table without losing a commit. A stale-base commit gets `CommitFailedException`, and the client's retry then succeeds.

After every step, the OpenHouse Java client sees the same stored metadata (`IcebergRestCatalogWriteTest`, 10 tests).

**Spark 3.5 SQL, through Iceberg's own `SparkCatalog` (`type=rest`), can now run:**
- CREATE TABLE (partitioned), INSERT and SELECT;
- UPDATE, DELETE and MERGE, both copy-on-write and merge-on-read;
- ALTER TABLE ADD COLUMN and SET TBLPROPERTIES;
- CTAS, and RTAS on a table with `replace.enabled=true`;
- DROP TABLE and ALTER TABLE RENAME TO.

The OpenHouse catalog reads the same rows (`IcebergRestSparkSqlTest`, 3 tests).

**What they cannot do, and why:**
- **Rename or drop a column** (Spark ALTER RENAME/DROP COLUMN, `updateSchema().renameColumn` or `deleteColumn`). The native schema-evolution rule matches columns by name and forbids drops, so these get 400 "invalid schema evolution", exactly as the native API answers. Pinned in both itests.
- **Partition evolution** (`updateSpec`) and **RTAS on a table without `replace.enabled`** get the native 400.
- **Naming a location**, or **changing location, UUID or format version**, gets 400 by design (decision 4). Spark's ordinary CREATE TABLE, CTAS and RTAS never sent a location, so nothing had to stop on decision 4.
- **No V5 features yet:**
  - setting policies, which are reserved properties (the native 400);
  - grants, lock and unlock;
  - replica or replicated tables (IRC has no table-type field);
  - views, register, metrics reports (406), multi-table transactions and namespace create/update/drop (406).

**Run results:**
- `:services:tables:test` in full: 1373 tests, 0 failed.
- Spark 3.5 `catalogTest`: 238 tests, 0 failed, 1 skipped.
- `-PicebergFork` (Iceberg 1.12) compiles both main and test sources.

## Rules and whether IRC enforces them identically

Each row lists the evidence test (ran). "=" means the stored metadata or the message is equal to the native one.

| Rule (V1 inventory) | IRC | Test |
| --- | --- | --- |
| Create: rules, properties, location, UUID and cluster-forced values (V2–V13, S10, R4–R7) | Native create handler | `createStoresWhatTheNativeCreateStores`, `createUnpartitioned...`, `createWithASortOrder...`, `createWithReservedProperties...`: stored metadata = |
| Create on an existing table (S3) | 409 `AlreadyExistsException`, native message | `createOfAnExistingTableIs409AlreadyExists` |
| Create privilege (S10) | 403, native message | `IrcWriteRoutesAuthTest.aDeniedCreateIs403AsNatively` |
| Table count limit (R5) | Native code path. Not exercised through IRC. | Read only |
| Update privileges and SYSTEM_ONLY access (S7, S8) | 403, native message | `aDeniedCommitIs403AsNatively` |
| Legacy lock (S6) | 400, native message ("cannot be updated." for a table update, "cannot be written to" for a snapshot or replace commit) | `aLockedTableIsRefusedWithTheNativeMessage`, `replaceOfALockedTable...` |
| Capability gate (S11) | 422 `ClientCapabilityMissingException`, native message | `aTableRequiringACapabilityIsGatedOnCommitAsNatively` |
| Base version, retry cache, house-table CAS (R12, P7, P11) | Base = the stored table, loaded in the same request. A refusal maps to 409 `CommitFailedException`. | Mapping read in code. The race itests cover the requirement path; no test forces a race inside a request. |
| Reserved keys and raw `policies` (R13) | 400, native message | `reservedPropertyChangesGetTheNative400` |
| Partition evolution (R15) | 400, native message | `partitionEvolutionGetsTheNative400` |
| Schema evolution (R16) | 400 | `droppingAColumnGetsTheNative400`, itests (rename) |
| Properties, schema, sort order, snapshots and refs (R17–R20, P9) | Stored metadata = | `propertySetAndUnset...`, `addingAColumn...`, `sortOrderChange...`, `appendStores...`, `aBranchAndATagAreStored` |
| Replace gates: `replace.enabled`, WAP, lock, policies kept (R8–R10, S6) | Native messages; policies kept | `replaceIsRefusedWithTheNativeMessageWhenRtasIsNotEnabled`, `replaceIsRefusedWhileWapIsEnabled`, `replaceOfALockedTable...`, `replaceKeepsTheStoredPolicies` |
| Replace stored state | = | `replaceStoresWhatTheNativeReplaceCommitStores` |
| Create with data, CTAS (V13 UUID from the manifest path) | Native snapshots handler; stored metadata = | `stagedCreateThenCommitWithDataStoresWhatTheNativeCtasStores` |
| Drop (native semantics) | Native handler, 204. 404 maps to `NoSuchTableException`. 403 is the native refusal. | `dropIsTheNativeDrop`, auth test |
| Rename: target exists 409, case-only 400, across databases 400, missing 404, lock, privilege | Native handler and messages | `renameIsTheNativeRename`, `renameRefusalsAreTheNativeOnes`, auth test |
| Max-ref-age (P5) and column-default checks (P6) | Same core path. No dedicated IRC test. | Read only |

## Replace detection rule

A non-create commit is treated as a replace when:
- its updates contain `SetCurrentSchema`, `SetDefaultPartitionSpec` and `SetDefaultSortOrder`; and
- its requirements contain none of `AssertCurrentSchemaID`, `AssertDefaultSpecID` or `AssertDefaultSortOrderID`.

This is the same rule as `OpenHouseRestAdapter.isReplace`. It works for both lines: `RESTSessionCatalog.replaceTransaction` adds any of the three updates the replacement lacks, `UpdateRequirements.forReplaceTable` never asserts the three ids, and `forUpdateTable` always does. I read both the 1.5.2 and the 1.12 sources.

**What could fool it:** only a hand-made request, or a non-Iceberg client, that sets all three without asserting them. Such a request gets the replace rules (`replace.enabled`, WAP and replication refusals, kept partition columns, merged policies) instead of the update rules. It therefore works only on tables that enable RTAS, and it then skips the update-only schema and partition-evolution checks, which is what a native replace allows anyway. A replace that sends the three assertions would be treated as an update and refused or accepted by the update rules. No Iceberg client does that.

**Concurrency:** a replace asserts only the table UUID, so a commit that lands between the client's load and its replace is replaced over. That is Iceberg's own replace semantics.

## Staged create

**IRC:** it runs the native create validation and the create's checks in order: existence (409), create privilege, capability gate, initial version and table count limit. It then returns the metadata the create would store: the server-computed properties, the allocated location `<db>/<t>-<openhouseUUID>`, and the sort order, all through the new `OpenHouseInternalRepository.stageCreate`. That method writes nothing, so no table, no house-table row and no file is left behind (`stagedCreateLeavesNoTableNoRowAndNoFile`).

**The completing commit** (the one carrying `assert-create`):
- It drops the reserved keys the staged response carried.
- It takes the OpenHouse UUID from the location the staged create returned. A location the server did not allocate is a 400.
- It creates the table there through `TablesService.createTable(body, creator, uuid)`, or through the native create-with-data when it carries a snapshot.

**Native staged create today:** it writes a metadata file, writes no row, and never removes the file. Files from earlier test runs are still on disk (`nativeStagedCreateWritesAMetadataFileAndNoRow`).

## Error mapping (IRC write routes)

| Cause | Status / type | Client reading (1.5.2 and 1.12) |
| --- | --- | --- |
| Requirement failed, or the core's guard refused a moved base (`EntityConcurrentModificationException`) | 409 `CommitFailedException`, with the native conflict wording | `CommitFailedException` (the client retries) |
| Unknown commit state (`OpenHouseCommitStateUnknownException` or `CommitStateUnknownException`) | 500 `CommitStateUnknownException` (the spec's own example), with the native wording | Commit handler on both lines: 500/502/504 mean unknown state. Natively this is 503, which 1.5.2 reads as unavailable; 1.12 also reads 503 as unknown. |
| Table or name exists | 409 `AlreadyExistsException` | Create route: `AlreadyExistsException` |
| Native validation (`RequestValidationFailureException`, `UnsupportedClientOperationException` other than the SYSTEM_ONLY lock, `InvalidSchemaEvolutionException`, `CommitRejectedException`, historic partition column, Iceberg `ValidationException`, unparseable or unknown update, no body) | 400 `BadRequestException`, native message | `BadRequestException` |
| SYSTEM_ONLY lock access | 423 (as on the read routes) | `RESTException` |
| Authorization | 403 `ForbiddenException` | `ForbiddenException` |
| Missing table, or a table route in a multi-level namespace | 404 `NoSuchTableException` | `NoSuchTableException` |
| Capability missing | 422 `ClientCapabilityMissingException` | `RESTException` |
| `UnprocessableEntityException`, such as the table count limit | 422 with the native class name | `RESTException` |

## Differences between an IRC write and the equivalent native write

1. **The Iceberg `table-uuid`** is drawn by the create, as for every native create. The staged response's Iceberg UUID is therefore not the created table's. The table's OpenHouse UUID, which names its directory, is kept (an OpenHouse table has two different UUIDs).
2. **Staged create leaves nothing**; the native one leaves an orphan metadata file. The staged response has no `metadata-location`.
3. **A commit with no updates writes nothing** and returns the stored table. The native snapshots PUT writes a version even when nothing changes.
4. **Drop ignores `purgeRequested`**: the native drop always purges files. So `RESTCatalog.dropTable` (purge=false) deletes the data, as the OpenHouse catalog does.
5. **Response metadata is reloaded after the commit.** If another commit lands in between, the response shows the newer state.
6. **Commits load the base without the read checks**, as the native update routes do. The checks are then the write checks.
7. **Failures before the core are not in the table audit:** a failed requirement, a parse error, and a refusal of location, UUID or spec at the IRC layer.
8. **Policies cannot be set**, and the table type is always PRIMARY.
9. **Create-commit field ids** are re-freshened by the native create. They equal the client's for flat schemas; nested schemas evolved inside a create transaction could in principle be renumbered. Not tested.
10. **IRC write messages** for schema evolution embed the candidate's schema JSON, with ids assigned by Iceberg's builder (as V1 noted).

## Choices where the brief left room

- **Create entry:** reuse rather than extraction.
  - IRC creates are spelled as the native client's request and go through `TablesApiHandler.createTable`, with validation and audit.
  - The only native code change is that `putTable` takes its UUID through a supplier.
  - New entries: `createTable(body, creator, UUID)`, `stageCreateTable`, and `OpenHouseInternalRepository.stageCreate`.
- **The commit base** comes from `TableCommitService.loadBase`, with no read checks, instead of `TablesService.loadTable`. Native updates do not run read checks: with `loadTable`, a legacy-locked table would have answered 403 instead of the native 400 lock message.
- **Commit kind:** a replace, or any snapshot or ref change, is `SNAPSHOTS_UPDATE`; everything else is `TABLE_UPDATE`. This mirrors the native client's choice of route.
- **Inexpressible partition spec:** 400 on create, with the same rule as V1's commit refusal. A bucket transform turned out to be expressible (as clustering).
- **Multi-level namespace:**
  - commit, drop and a rename source get 404 `NoSuchTableException`, like pull request 92's read rule;
  - create and a rename destination get 400.
  - Test: `writeRoutesInAMultiLevelNamespace`.
- **Config:** defaults (not overrides) `io-impl=HadoopFileIO` and `rest-metrics-reporting-enabled=false`.
  - Without `io-impl`, a Spark merge-on-read DELETE failed: `UnsupportedOperationException: Creating output file at ...-deletes.orc with properties: {file-replication-factor=3} is not supported` from `ResolvingFileIO`.
  - Without the metrics setting, the itest log held 25 "Failed to report metrics" warnings with stack traces. After the change: 0.
- **Table audit:** three advices record IRC commits (UPDATE / COMMIT / REPLACE_COMMIT), the create-completing commit (CREATE, as the native client's table PUT on INITIAL is audited), and the staged create (STAGED_CREATE). Test: `irCWritesAreAuditedAsTheirNativeWrites`.
- **Service audit of failed IRC requests (decision 9): reported, not built.** The failure pointcut is in `services/common` and covers only `common.exception.handler` and `ViewExceptionHandler`. It casts the body to the native `ErrorResponseBody`, and `getServiceNameFromRequestURI` labels `/iceberg/...` as UNRECOGNIZED even on success. Fixing it means either changing common code (the pointcut, the byte[] body and the service name) or duplicating its event builder in the tables module. Both are more than a small change.
- **Existing V2 tests were updated, not removed:**
  - the config test expects the two defaults;
  - the four write routes are no longer in the 406 list;
  - the read itest expects `HadoopFileIO`.

## Stopped on

Nothing.

## Test counts

| Suite | Before | After |
| --- | --- | --- |
| `:services:tables:test` | 1329 at 7f7c5638 (V1 record, read; not rerun here). Pull request 92 later added one read test. | **1373, 0 failed (ran, one full invocation)** |
| Spark 3.5 `catalogTest` | 219, 1 skipped (V1 record, read). The merged branches added itests. | **238, 0 failed, 1 skipped (ran)** |

The +44 on the service suite is my work: golden +2, `IrcWriteRoutesTest` 38, `IrcWriteRoutesAuthTest` 3, plus pull request 92's +1. The Spark suite gains `IcebergRestCatalogWriteTest` 10 and `IcebergRestSparkSqlTest` 3.

## Commits (oldest first)

1. **1e4d8c83** test: pin native plain create and create-with-data in the golden test, before any code change.
2. **82815e0f** test: MockMvc equivalence and rule tests (red: 406).
3. **e85aa63f** test: `RESTCatalog` and Spark SQL itests (red: 406).
4. **88fe9ec1** feat: create entry and a write-nothing staged create (the service).
5. **dedef9f6** feat: replace intent through the candidate entry.
6. **dfd88df3** feat: the IRC write routes, error mapping and JSON.
7. **823b5469** test: three wrong test assumptions corrected (two UUIDs; bucket is expressible; the leftover native file).
8. **50ea71cc** test: config expectations (red).
9. **e192fafe** feat: config defaults, plus a 400 for an empty body.
10. **fb18f215** merge of `origin/irc-integration`.
11. **3934bbe4** test: multi-level namespace (red), plus the 1.12-safe `AddSchema` in the test helper.
12. **b397f469** feat: multi-level rule.
13. **296767b9** test: table audit (red).
14. **f449d323** feat: audit advices.

## Ran versus read

**Ran:**
- every red run named in the commits;
- the `IrcWrite*`, `IrcReadRoutesTest`, `TableCommitServiceTest` and golden classes;
- the full `:services:tables:test`;
- the full Spark 3.5 `catalogTest`;
- `publish-local.sh`, then `compileJava` and `compileTestJava -PicebergFork`;
- `spotlessApply` and `spotlessCheck` on `:services:tables` and the Spark 3.5 itest module.

**Read, not run:**
- the two spec versions and their differences for these routes: 1.12 adds the `Idempotency-Key` header (not honoured, and no lifetime is advertised), the rule "unknown updates must be 400" (honoured), and the create 409 wording that covers views;
- the `ErrorHandlers` and `UpdateRequirements` sources on 1.5.2 and 1.12;
- the base test counts.

**Not run:**
- Spark 3.5 `statementTest` and `test`;
- a 1.12 `RESTCatalog` against these routes;
- the real-HTS (MySQL) mode;
- the delta-harness (per the brief);
- CI on pull request 94.

**JVMs:** a Gradle daemon (pid 2415, started about when my last itest run started) was still alive at the end. I could not confirm I started it (the daemons are shared), so I did not kill it.

## Follow-up: validation and policy-key gaps

Branch `irc/v3-irc-write-gaps`, draft pull request https://github.com/mkuchenbecker/openhouse/pull/96 into `irc-integration`. Head `ef21dc29a29eb8379514143dc4d56c449486fe1a`, on top of 14f23870. `irc-integration` had not moved, so no merge was needed.

**Bottom line (ran):** both gaps were real, each shown by a failing test, and both are closed.
- **Gap 1.** IRC commits and replaces never called `validateUpdateTable`.
  - Before the fix:
    - RTAS+replication, RTAS+WAP and a case-insensitive duplicate column were each accepted with 200 and stored;
    - a replace with 5 clustering columns got a 500;
    - a replace that dropped the time partitioning a retention policy needs got a service-layer message instead of the native validator's.
  - Now each of these gets the native 400 and the native message, and nothing is stored.
- **Gap 2.** `updated.openhouse.policy` was stored as a stray property with 200 on commit, create, staged create and staged-create completion, and through a real `RESTCatalog.updateProperties`. All of these now get a 400 `BadRequestException` that names the key, and nothing is stored.

### Native pre-service checks vs IRC routes (before -> after)
The native create handler runs `validateCreateTable`. The native update runs `validateUpdateTable`, and the native snapshots PUT runs `validatePutSnapshots`, which ends in one of those two. In the table, "yes" means the route runs the check.

| Check (OpenHouseTablesApiValidator) | IRC create | IRC staged create | IRC staged-create completion | IRC commit | IRC replace |
|---|---|---|---|---|---|
| Bean validation, cluster/db/table ids match | yes / yes | yes / yes | yes / yes | no -> yes (cannot be violated: ids come from the path) | no -> yes (same) |
| Schema parses, no case-insensitive duplicates, at least one column | yes / yes | yes / yes | yes / yes | **no -> yes** (test) | no -> yes |
| PUT may not set stageCreate (update only) | n/a | n/a | n/a | no -> yes (cannot be violated) | no -> yes (cannot be violated) |
| At most 4 clustering columns (update list + ClusteringSpecValidator) | yes, but the body build refuses first with the "inexpressible" 400 | same | same | no -> yes (spec changes are refused later anyway) | **no (500) -> yes** (test) |
| Sort order parses | yes / yes | yes / yes | yes / yes | no -> yes (cannot be violated: the sort order is already bound) | no -> yes |
| Replica UUID, replicated last-updated-ms (create only) | yes / yes | yes / yes | yes / yes | n/a | n/a |
| validatePolicies (retention, replication, history vs schema and partitioning) | yes (IRC sends no policies) | yes | yes | no -> yes (stored policies vs new schema) | **no -> yes** (retention test) |
| Feature compatibility (RTAS vs WAP, RTAS vs replication, opt-out) | yes / yes | yes / yes | yes / yes | **no -> yes** (2 tests + opt-out test) | no -> yes |
| Clustering transform parameters | yes / yes | yes / yes | yes / yes | no -> yes (cannot be violated: Iceberg rejects bad widths) | no -> yes |
| validatePutSnapshots shape checks (body present, not staged, inner base = outer) | n/a | n/a | yes (through the native snapshots handler) | n/a: request shape that IRC cannot carry | same |

No native check needs a field that an IRC request cannot carry, except table type and policies. For those, the stored values are used, as the client does with its loaded table.

### Keys the OpenHouse client rewrites before sending (`OpenHouseTableOperations.constructMetadataRequestBody`)
- `updated.openhouse.policy`: stripped and merged into policies. Over IRC it was stored as a stray property and is now refused on create and on commit.
- `policies`: parsed into structured policies. On commit it was already refused as a reserved key. On create a non-empty value was silently dropped; it is now refused. The empty value the server sets is still accepted.
- `openhouse.tableType`: read into the request's tableType. On commit it was already refused as reserved. On create a value other than PRIMARY_TABLE was silently dropped (the table became PRIMARY); it is now refused.
- `openhouse.isTableReplicated`: the client overrides it from the base on update. Over IRC a change is refused as reserved. On create it reaches the validator raw, just as it does from the client, so it is not the same shape and was left alone.

### Tests
- Added: `IrcWriteNativeValidationTest` (6), `IrcWritePolicyKeyTest` (10, including the native-path test, which passed before and after), `IrcRestCatalogPolicyKeyTest` (1, a real `RESTCatalog` on a random port).
- Changed setup: `IrcWriteRoutesTest.replaceIsRefusedWhileWapIsEnabled` now sets the compatibility opt-out along with WAP, because the native API cannot reach that state without it.
- Full `:services:tables:test`: **1390, 0 failed** (1373 + 17). The golden test passes unchanged.
- `publish-local.sh`, then `compileJava` and `compileTestJava -PicebergFork`: passed.

### Commits
1. 7a5284be test: validation refusals (red).
2. 64bc10e8 test: WAP-on-RTAS setup uses the opt-out.
3. 1f8a0ca6 refactor: `toClusteringColumns` (the mapping without the limit).
4. 3fb06465 feat: commit and replace run `validateUpdateTable` (`IcebergUpdates`).
5. a82dcb29 test: policy key refusals (red).
6. e4a7f510 feat: refuse `updated.openhouse.policy`.
7. ea0b085c test: `policies` and table type on create (red).
8. ef21dc29 feat: refuse them.

### Known gaps
- An IRC create with more than 4 clustering columns still gets the 400 "inexpressible partition spec" message instead of the native clustering message. The status is the same; only the message differs.
- A commit whose spec the mapper cannot handle (an unsupported clustering type) is still a 500, as before.
- Policies over IRC remain refused until V5-1.

### Ran vs read
- **Ran:** every red run; the touched classes; the full service suite; the fork compile; spotlessApply and spotlessCheck.
- **Read:** the native handler and validator inventory; the client's rewrite list; the Spark 3.1/3.5 policy execs.
- **Not run:** Spark itests; a 1.12 `RESTCatalog` at runtime; CI on #96.
