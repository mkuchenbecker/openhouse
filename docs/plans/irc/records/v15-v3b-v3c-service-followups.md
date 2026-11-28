# V15, V3b, V3c: three service follow-ups (record)

Delegated agent, 2026-11-23. Branch `irc/v15-v3b-v3c-service-followups` in mkuchenbecker/openhouse, based on
`irc-integration` at a217cce9 (merge of pull request 120), with `irc-integration` at 440249d1 (pull request 118,
V9e) merged in before the final counts. Draft pull request 121. Marks: [ran] means run here on JDK 17; [read]
means read from code, DDL or notes only.

Build lines: after V9e a plain build is the Iceberg 1.12 line and `-PicebergLinkedIn` is LinkedIn's 1.5.2 line.
The work was written and first run before that merge, when the plain build was the 1.5.2 line and
`-PicebergFork` the 1.12 line.

## Bottom line

- V15: a write whose House Tables row would not fit is refused with 400 before anything is written, on both APIs.
  It covers table creates and commits, renames, and view writes. The limit is exact: 255 fits, 256 is refused.
- V3b: the IRC config route advertises the FileIO that matches the cluster's default storage type.
- V3c: a failed or refused IRC request produces the same `ServiceAuditEvent` as a failed native one, and IRC
  events now name the tables service.
- The House Tables store this repository's own `schema.sql` creates holds 512 characters of metadata location,
  not 255. By decision D41 as amended the limit is therefore a cluster property,
  `cluster.housetables.metadata-location-max-length`, 255 by default.
- Native behaviour change, stated plainly: on a store created from `schema.sql` (512), a write whose location is
  256 to 512 characters long worked before and is refused by default now, unless the property is set to 512.

## V15

### What the investigation found

- The stored location is `<table directory>/<5-digit version>-<uuid>.metadata.json`, and the table directory is
  `<storage root>/<database>/<table>-<uuid>`. On local and HDFS storage it is stored without a scheme. The fixed
  part is 96 characters plus the storage root. [ran]
- Database and table names are checked: a create with either over 128 characters is 400 before anything (the
  table name on both APIs [ran]; the database name natively [ran, existing test]). The brief's "no length
  check" is true only of the rename target. [ran]
- The service writes the metadata file first and the row second. On the H2 stand-in a row that does not fit fails
  with a JDBC `DataException`, which the catalog treats as an unknown failure. [ran]
- A later commit can cross the limit when the create did not, in two ways: the file name is 3 characters longer
  once `write.metadata.compression-codec=gzip` is set [ran], and 1 longer from version 100000 [ran, unit test].
  A rename does not change the location.
- A commit to an existing table that fails this way leaves its base version in the catalog's retry cache
  (`OpenHouseInternalTableOperations.CACHE`, five minutes), so every later commit on that base is 409 "stale". [ran]
- The real store was read, not run. The House Tables service turns a `DataIntegrityViolationException` into its
  409, which the tables service reads as a concurrent update, so there the answer is probably 409 ("created
  concurrently" for a create) and not 503, still with the file left. A MySQL that is not in strict mode would
  truncate the value silently. [read]

### Before and after [ran on both Iceberg lines; the seven seconds are the 1.5.2 line's]

| Request (stored location or name one over the limit) | Before | After |
| --- | --- | --- |
| Native create | 503 "isn't ack'ed" after about 7 s; `00000` file left | 400, nothing written |
| IRC create | 500 `CommitStateUnknownException` after about 7 s; file left | 400 `BadRequestException`, nothing written |
| IRC staged create | 200; its completion 500, file left | 400 at the staged create |
| Native create with data (staged create, then snapshots PUT) | staged 201; PUT 500, second file left | staged 201 as before; PUT 400, no second file |
| Native PUT that crosses the limit | 503, `00001` file left, table 409 for 5 minutes | 400, nothing written, next commit lands |
| IRC commit that crosses the limit | 500 `CommitStateUnknownException`, file left | 400 `BadRequestException`, next commit lands |
| Native rename to 256 characters | 500, `00001` file left | 400, nothing written |
| IRC rename to 256 characters | 500, file left | 400 `BadRequestException` |
| Native view create | 500 "Unexpected view service failure", `00001` file left | 400, nothing written |
| IRC view create | 500 `ServiceFailureException`, file left | 400 `BadRequestException` |

### The bounded columns of the row

DDL is `services/housetables/ddl/0000__baseline.sql` plus `0001` (production, verified there against `SHOW CREATE
TABLE`); bootstrap is `services/housetables/src/main/resources/schema.sql`, which the House Tables service runs
at start-up; the stand-in is the JPA entity `HouseTable` on H2, where every string is 255. All [read] unless marked.

| Column | DDL | Bootstrap | Can a request exceed it? | Done |
| --- | --- | --- | --- | --- |
| `metadata_location` | `VARCHAR(255)` | `VARCHAR(512)` | Yes: creates, commits, views [ran] | Fixed at 255 |
| `table_id` | `VARCHAR(255)` | `VARCHAR(128)` | Create no (128, validated) [ran]. Rename target yes [ran] | Over 255 fixed. 129 to 255 left |
| `database_id` | `VARCHAR(255)` | `VARCHAR(128)` | No: create validates 128, rename keeps the database | Left |
| `storage_type` | `VARCHAR(128)` | `VARCHAR(128)` | No: the server's storage type | Left |
| `entity_type` | `VARCHAR(128)` | `VARCHAR(128)` | No: `TABLE` or `VIEW`, set by the server | Left |
| `version` | `BIGINT` | `BIGINT` | No: the store's row version | Left |
| `creation_time` | `BIGINT` | `BIGINT` | No: a parsed long | Left |
| `table_version` | `VARCHAR(255)` | absent | Not written: the entity has no such field | Left |

- Table location and creator are not columns of the store. The location is part of `metadata_location`; the
  creator lives in the metadata file. The stand-in has columns for them, and for `tableUri` and `clusterId`, that
  the store does not have.
- `soft_deleted_user_table_row` holds names in 128 and the location in 512 in both files, so a table renamed to
  129 to 255 characters could not be soft-deleted. Nothing in the tables service's HTTP surface soft-deletes.

### Left, and why

- A rename to 129 to 255 characters still succeeds. It always has, on a 255 store, so refusing it would change a
  request that works. The table can then not be updated through the native PUT, whose body may not carry such a
  name. Pinned in `MetadataLocationLimitTest`, as an existing native defect (D9). [ran]
- The native staged create and staged replace are not judged. They write a metadata file and no row, so they
  cannot fail this way, and they succeed today.

## V3b

- The native path [read]: `OpenHouseCatalog.loadFileIO` uses the catalog's `io-impl` if the engine sets one, and
  `HadoopFileIO` otherwise. The set-up guide has an ADLS engine set `io-impl` to `ADLSFileIO` with its own account
  key. Nothing documents an S3 engine. The service's own FileIO per storage type is in `FileIOConfig`: hdfs and
  local `HadoopFileIO`, s3 `S3FileIO`, adls `ADLSFileIO`.
- Before [ran]: `io-impl=org.apache.iceberg.hadoop.HadoopFileIO` on all four storage types.
- After [ran]: hdfs and local unchanged; s3 `org.apache.iceberg.aws.s3.S3FileIO`; adls
  `org.apache.iceberg.azure.adlsv2.ADLSFileIO`. A storage type the route has no class for gets no `io-impl`.
- Only the class name is sent. All three classes are Iceberg's own. Endpoint and credentials stay the client's
  configuration, as on the native path.
- A cluster with no `storages` block is a local one (`StorageManager.getDefaultStorage`), which is what every
  other test context is; `cluster-test-properties.yaml` is not loaded by those tests. [ran]

## V3c

- Mechanism [read]: `ServiceAuditAspect` in `services/common`. Success: an advice around every method annotated
  `@ApiResponses`. Failure: an advice around every method of the native exception handler (and the view
  handler), which reads message, stack trace and cause from the handler's `ErrorResponseBody`.
- Before [ran]: an IRC success was already audited by the first advice, with the service named `UNRECOGNIZED`. An
  IRC failure produced no event: the IRC exception handler is not advised.
- After [ran]: the IRC handler calls `ServiceAuditAspect.auditFailedRequest` with the status and message it
  answers and the failure's stack trace and cause. The event is built by the code that builds the native one.
  Requests under `/iceberg/` name the tables service.
- Example of the success difference that was fixed: `GET /iceberg/v1/namespaces/db/tables/t` gave
  `serviceName=UNRECOGNIZED, method=GET, statusCode=200`, where `GET /v1/databases/db/tables/t` gave
  `serviceName=TABLES_SERVICE`. Every other field agreed.
- A missing or bad token (401) is written by the token interceptor without an exception on both APIs, and is
  audited on neither. [read]

## Decisions made (for the record)

1. The limit is a cluster property (`ClusterProperties`), default 255, the production DDL's width; a deployment
   on a `schema.sql` store sets 512. `HouseTableRowLimits` keeps the default and names both files; a value that
   is not positive means the default. The rename target's 255 stays a constant. (Coordinator's decision.)
2. The refusal is `CommitRejectedException`, the catalog's existing "rejected before anything is written" error,
   which both APIs already map to 400.
3. The check runs last of the pre-commit rules and before the retry cache. A request an earlier rule refuses
   keeps its answer; a refused commit does not block the table.
4. Native staged create and staged replace are exempt. The IRC staged create is checked, because it writes
   nothing and its client would otherwise write data first.
5. Views are fixed too: same row, same column, same failure. They answered 500, not 503.
6. The rename target is refused over 255 only.
7. V3b advertises the service's own class per storage type, and nothing for an unknown type.
8. V3b sends no storage parameter of the cluster.
9. V3c makes the IRC handler call the audit, instead of widening the aspect's pointcut.
10. V3c names `/iceberg/` requests as the tables service, successes included: one function decides it.

## Decided by the coordinator after the first report

1. The limit becomes a cluster property, default 255 (done, commit d80d38f3).
2. Native staged create and staged replace stay exempt.
3. A rename target of 129 to 255 characters stays as it is, pinned; it becomes a work item.

## Known gaps

- On a store created from `schema.sql`, a location of 256 to 512 characters is refused unless the property is
  set to 512. The property must never exceed the column's real width.
- The retry cache is still reachable by other late failures [read]: `doCommit` records the base in the cache
  before it validates snapshots and the sort order, writes the file and writes the row, so a snapshot validation
  400, a storage error or a House Tables failure on an existing table still makes every commit on that base 409
  for five minutes. Only the row-width failure no longer gets there.
- The H2 stand-in has a `tableUri` column the store does not have; two 128-character names overflow it at 255.
  It never decided a default-limit case, because the location overflows first. The 512 test widens it. [ran]
- The real store on MySQL was read, not run.

## Not run

- The real House Tables service on MySQL.
- A real S3 or ADLS store; an Iceberg client reading through the advertised class.
- Spark 3.5 `statementTest` and `test`; the delta-harness.

## Final verification (finishing agent, after the usage-limit cut-off)

Head 52d49a881cb7eabfae5ae3d78a1f7603b7f4aca3 (merge of `irc-integration` 75d6e185 onto d80d38f3).
`origin/irc-integration` was still 75d6e185 at fetch, so nothing more was merged. [ran]

d80d38f3 checked against the coordinator's list [read]: the property
`cluster.housetables.metadata-location-max-length` is an `@Value` field of `ClusterProperties` with default 255,
like its neighbours; the refusal message names the configured limit ("holds at most 512"); tests for the
default boundary (`HouseTableRowLimitsTest`, `DefaultClusterPropertiesTest`, the unchanged
`MetadataLocationLimitTest`) and for 512 (`MetadataLocationConfiguredLimitTest`: 256 and 512 fit, 513 refused on
both APIs, nothing written); the Javadoc and a `DEPLOY.md` section say when to set 512; `schema.sql` and
`services/housetables/ddl/` are not in the diff. Nothing was missing; no code was changed.

Counts on the head, JDK 17, every test task rerun with `--rerun` (tests / failed / skipped) [ran]:

| Suite | No property (Iceberg 1.12) | `-PicebergLinkedIn` (1.5.2) |
| --- | --- | --- |
| `:services:tables:test` | 1621 / 0 / 0 | 1621 / 0 / 0 |
| `:iceberg:openhouse:internalcatalog:test` | 327 / 0 / 0 | 327 / 0 / 0 |
| `:services:common:test` | 37 / 0 / 0 | 37 / 0 / 0 |
| `tables-test-fixtures-iceberg-1.5_2.12:test` | 41 / 0 / 0 | 41 / 0 / 0 |
| Spark 3.5 itest `catalogTest` | 272 / 0 / 1 | 272 / 0 / 1 |
| `restCompatKitTest` | 160: 39 passed, 98 failed, 23 skipped; exactly the listed 121, task passes; `expected-outcomes.txt` unchanged | not in that build |
| Spark 4.2 itest `test`, `smokeTest` | 41 / 0 / 0, 65 / 0 / 0 | not in that build |
| `spotlessCheck` | passed | passed |

Differences from the counts before the property commit (9c37e2eb): tables 1617 -> 1621 is
`MetadataLocationConfiguredLimitTest` (3) and one new `DefaultClusterPropertiesTest` case; internalcatalog
325 -> 327 is the two new `HouseTableRowLimitsTest` cases. The merge of 75d6e185 (C10, C14) touches none of these
modules. Every other suite is unchanged.

Push: 52d49a88 pushed to `origin` under the shared hook lock; `git stash list` was empty before and after. [ran]

CI: both jobs passed on 52d49a88, run 45276843042 [ran].

### Second base move (pull request 124, format v3)

`irc-integration` moved to e2e02bfa (X1, W6, format v3) during the CI wait; merged as d14d93aa. One conflict,
`OpenHouseInternalRepositoryImpl.stageCreate`: kept both, the format version check first and the row-limit check
last. In the commit path the format version policy is a `PreCommitCheck` and the row-limit check still runs after
`preCommitCheckRunner.checkAll` and before the retry cache (auto-merged). [read] The pre-commit hook dropped
`MERGE_HEAD`; finished with `git write-tree` / `git commit-tree` (both parents) / `git merge --quit` /
`git reset --soft`. [ran] Added 3de23544, `MetadataLocationLimitFormatV3Test` (3): v3 native and IRC creates at
255 fit and at 256 are refused, a v3 commit to 258 is refused, nothing written, the next commit lands; runs only
where Iceberg builds v3, like the other v3 tests (passes on 1.12, skips on 1.5.2). [ran]

Counts on 3de235443990663c05c7ab75e3d31aa912bf3656 (tests / failed / skipped) [ran]:

| Suite | No property (Iceberg 1.12) | `-PicebergLinkedIn` (1.5.2) |
| --- | --- | --- |
| `:services:tables:test` | 1665 / 0 / 1 | 1665 / 0 / 32 |
| `:iceberg:openhouse:internalcatalog:test` | 327 / 0 / 0 | 327 / 0 / 0 |
| `:services:common:test` | 37 / 0 / 0 | 37 / 0 / 0 |
| `tables-test-fixtures-iceberg-1.5_2.12:test` | 41 / 0 / 0 | 41 / 0 / 0 |
| Spark 3.5 itest `catalogTest` | 279 / 0 / 1 | 279 / 0 / 7 |
| `restCompatKitTest` | 160: 41 passed, 96 failed, 23 skipped; exactly the listed 119, file as on the base | - |
| Spark 4.2 itest `test`, `smokeTest` | 48 / 0 / 0, 65 / 0 / 0 | - |
| `spotlessCheck` | passed | passed |

Tables: base alone 1636 (1 / 29 skipped) plus this branch's 29 (26 before, 3 v3 limit tests); the 3 skip on 1.5.2.
Spark 3.5 and 4.2 increases are the base's format v3 tests. Pushed 3de23544; `git stash list` empty before and
after each commit and push. CI on 3de23544: both jobs passed, run 45288650359. Pull request body's description,
testing section and known gaps updated.

Leftover process: Gradle daemon pid 614 (3-hour idle timeout) is shared with the `oh-x1v3` worktree's build and
ran this branch's pre-push hook; not started knowably by me, so not killed. [ran]

## Merge commit (proposed)

```
Merge V15, V3b, V3c: refuse a write whose House Tables row would not fit, with the limit a cluster property; IRC FileIO default follows the storage; failed IRC requests are audited (#121)

Why: A table or view write whose metadata location did not fit the House Tables column was written to storage first and failed at the row write: the caller was told the commit state was unknown (native 503 or 500, IRC 500), the metadata file stayed, and a failed commit to an existing table blocked it for five minutes. The IRC config route always advertised HadoopFileIO whatever the storage, and a failed IRC request produced no service audit event.

Leaves true:
- The row-limit check runs last of the pre-commit checks (after the format version policy) and before the retry cache; the IRC staged create runs the format version check, then the row limit.
- A table create, commit, snapshots PUT, rename or lock, or a view write, on the native API or the IRC routes, whose metadata location would exceed cluster.housetables.metadata-location-max-length is refused with 400 (CommitRejectedException; IRC BadRequestException) before anything is written, and the next commit on the same base lands. The message names the configured limit.
- The property is in ClusterProperties, default 255 (services/housetables/ddl/, the production store). A deployment whose store was created from schema.sql (VARCHAR(512)) sets 512; DEPLOY.md says when. A value that is not positive means the default. schema.sql and the DDL are unchanged.
- A rename target over 255 characters is refused with 400 (a constant). The IRC staged create is checked; native stageCreate and stageReplace are not.
- The IRC config route's io-impl follows cluster.storages.default-type: hdfs and local HadoopFileIO, s3 S3FileIO, adls ADLSFileIO, an unknown type none. Only the class name is sent.
- A failed or refused IRC request produces the same ServiceAuditEvent as a failed native one; requests under /iceberg/ name the tables service.

Evidence (run locally on head 3de23544, with irc-integration e2e02bfa merged in, JDK 17; tests / failed / skipped, Iceberg 1.12 line, then -PicebergLinkedIn):
- :services:tables:test 1665 / 0 / 1, 1665 / 0 / 32 (the base alone: 1636, 1 and 29 skipped; the three format v3 limit tests skip on 1.5.2); :iceberg:openhouse:internalcatalog:test 327 / 0 / 0 both; :services:common:test 37 / 0 / 0 both; tables-test-fixtures-iceberg-1.5_2.12 41 / 0 / 0 both; Spark 3.5 itest catalogTest 279 / 0 / 1, 279 / 0 / 7; spotlessCheck passed both.
- 1.12 line only: restCompatKitTest 160 (41 passed, 96 failed, 23 skipped: exactly the 119 in expected-outcomes.txt, as on the base); Spark 4.2 itest test 48 / 0 / 0, smokeTest 65 / 0 / 0.
- MetadataLocationLimitTest: 255 fits, 256 refused at every route, nothing written. MetadataLocationConfiguredLimitTest (property 512): 256 and 512 fit, 513 refused on both APIs, nothing written. MetadataLocationLimitFormatV3Test: a format v3 table is held to the same limit (native and IRC v3 create, v3 commit), nothing written. IrcConfigFileIoTest per storage type; IrcServiceAuditTest field by field against the native event. Each new test was first committed pinning the old behaviour.
- CI on the pull request: both jobs (Build and Run Tests; Reference build on LinkedIn's Iceberg 1.5.2) passed on 3de23544, run 45288650359.

Known gaps:
- On a House Tables store created from schema.sql (512), a write whose location is 256 to 512 characters worked before and is refused by default now, unless the property is set to 512. The property must never exceed the column's real width.
- A rename to a name of 129 to 255 characters still succeeds, and on a schema.sql store (table_id VARCHAR(128)) still fails after the file is written; pinned, a work item.
- The retry cache is still reached by other late failures (snapshot validation 400, storage errors, House Tables failures) on an existing table.
- The real House Tables service on MySQL, real S3 or ADLS, and a client reading through the advertised class were not run. A table placed on a non-default storage type is advertised the default's class.

Commits:
- cf3d10e8 test: pin what a write whose house-table row will not fit answers and leaves behind (V15)
- 954bd67e fix: refuse a write whose house-table row would not fit, before anything is written (V15)
- 3e4232dc test: pin the FileIO the IRC config route advertises on each storage type (V3b)
- 0f7de5d2 feat: the IRC config route's FileIO default follows the cluster's default storage (V3b)
- 399e987b test: pin the service audit of IRC requests next to the native request's (V3c)
- 4ae3dcd9 feat: audit a failed or refused IRC request as the native failure is audited (V3c)
- 9c37e2eb Merge irc-integration (V9e) into the branch
- d80d38f3 feat: the metadata location limit is a cluster property, 255 by default (V15)
- 52d49a88 Merge irc-integration (C10, C14) into the branch
- d14d93aa Merge irc-integration (X1, W6: format v3) into the branch
- 3de23544 test: a format v3 table's metadata location is held to the House Tables row limit (V15)

Decision record: docs/plans/irc/51-decisions-continued-4.md (D41) on branch claude/openhouse-rest-v3-planning-g42c0h of mkuchenbecker/openhouse.
```
## Deviations from the brief

- Views are fixed although the brief names tables: same row, same column, and a 500 where tables gave 503.
- The rename target is checked although the brief lists the table id only as a column to report.
- The tests are committed as pins of the old behaviour and then changed with the fix, so every commit is green.
- The merge of `irc-integration` was finished with `git commit-tree`, because the pre-commit hook's stash dropped
  `MERGE_HEAD`. The tree is the one `git merge` staged (472aea08), with both parents.
- The Spark 3.5 `catalogTest` run that was in flight when V9e merged was stopped and redone on the merged tree.
- The property commit carries its test and code together: the test cannot compile without the property.
- `irc-integration` moved again (75d6e185) while the property was in work, and I pushed d80d38f3 before seeing
  it. It is merged as 52d49a88, and the tables suite and `spotlessCheck` were rerun on that tree.
- A first version of the property's bean required `ClusterProperties` and broke three Spring test classes of
  `internalcatalog` (68 tests) that have no cluster configuration; found by running that module, fixed before
  the commit. The bean now falls back to the default where no cluster configuration is present.

## Commits

- cf3d10e8 test: pin what a write whose house-table row will not fit answers and leaves behind (V15)
- 954bd67e fix: refuse a write whose house-table row would not fit, before anything is written (V15)
- 3e4232dc test: pin the FileIO the IRC config route advertises on each storage type (V3b)
- 0f7de5d2 feat: the IRC config route's FileIO default follows the cluster's default storage (V3b)
- 399e987b test: pin the service audit of IRC requests next to the native request's (V3c)
- 4ae3dcd9 feat: audit a failed or refused IRC request as the native failure is audited (V3c)
- 9c37e2eb Merge irc-integration (V9e: Iceberg 1.12 is the default build) into the V15, V3b, V3c branch
- d80d38f3 feat: the metadata location limit is a cluster property, 255 by default (V15)
- 52d49a88 Merge irc-integration (C10, C14) into the V15, V3b, V3c branch (pushed by the finishing agent)
- d14d93aa Merge irc-integration (X1, W6: format v3) into the V15, V3b, V3c branch
- 3de23544 test: a format v3 table's metadata location is held to the House Tables row limit (V15)
