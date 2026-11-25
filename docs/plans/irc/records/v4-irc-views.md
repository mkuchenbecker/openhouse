# V4: IRC view routes

Branch: `irc/v4-irc-view-routes` (mkuchenbecker/openhouse).
Draft pull request into `irc-integration`: https://github.com/mkuchenbecker/openhouse/pull/95.
Head: `2446cbbec590bd63a9097e4107924bfc9efc86cf`.
The branch was cut from `origin/irc-integration` at 14f23870. `origin/irc-integration` at 0010df15 (pull request 96) is merged in as 33aa98fd. The merge was clean.

## Bottom line

All of the following ran.

**What an unmodified Iceberg 1.5.2 `RESTCatalog` can now do with OpenHouse views:**
- `buildView().create()`, `loadView`, `viewExists` and `listViews`.
- `buildView().replace()` and `createOrReplace()`, with new SQL and a new schema.
- `updateProperties().set/remove().commit()`.
- `dropView`.

After each step, the OpenHouse Java client (with `iceberg-views-enabled`) sees the same metadata file and the same metadata (`IcebergRestCatalogViewTest`, 5 tests).

**What Spark 3.5 SQL can now do** through Iceberg's own `SparkCatalog` (`type=rest`) with no OpenHouse catalog class (`IcebergRestSparkSqlViewTest`, 4 tests):
- `CREATE VIEW` and `SELECT` from it.
- `SHOW VIEWS`: shows `[db,v,false]` only.
- `SHOW TABLES`: shows the table only.
- `DESCRIBE`.
- `CREATE OR REPLACE VIEW`: version 2, with the new column.
- `ALTER VIEW ... SET TBLPROPERTIES` and `UNSET TBLPROPERTIES`.
- A view over a view.
- `DROP VIEW`. A later `SELECT` gives `TABLE_OR_VIEW_NOT_FOUND`.
- A view whose base table was dropped: `SELECT` fails with `TABLE_OR_VIEW_NOT_FOUND`, and the view can still be dropped.

The only Spark configuration views need is Iceberg's SQL extensions (`IcebergSparkSessionExtensions`, already in the base session). `spark.sql.iceberg.rewriteViewCommands.enabled` defaults to true on this fork.

**What they cannot do:**
- **Rename a view.** The native view API has no rename endpoint, and the OpenHouse client refuses rename too. The route answers the spec's 406 `UnsupportedOperationException`, "OpenHouse views do not support rename: a -> b". The 1.5.2 client raises `RESTException` ("Unable to process: ..."), and Spark's `ALTER VIEW ... RENAME TO` fails with that message. The view is unchanged.
- **Create a view in a database that holds nothing.** This is native: a database exists only while it holds a table or a view. The answer is 404 `NoSuchNamespaceException` "Database not found".
- **Use views where the views gate is closed.** This is the default (see "Views disabled" below).
- **Set or change system properties**: `openhouse.*`, `replace.drop-dialect.allowed` and `policies`. These get the native 400.
- **Name a location**, or change the location, UUID or format version. These get a 400.
- **Use views from Iceberg 1.12 `RESTCatalog` (read, not run).** It calls view routes only when the server advertises `view-endpoints-supported=true` or an `endpoints` list. `/v1/config` advertises neither, so a 1.12 client treats views as unsupported. This is a known gap, left for the config work (V10) or a one-line config default.

**Test results:**
- `:services:tables:test`, full: 1411 tests, 0 failed (on 9f9f48bb, before the merge). The rerun on the merged head is in "Test counts" below.
- Spark 3.5 `catalogTest`, full: 247 tests, 0 failed, 1 skipped (on 9f9f48bb).
- `-PicebergFork` compiles main and test sources (on 9f9f48bb and on the merged head).

## How the native API keeps tables and views apart, and the answers on each route

Natively, both are House Tables rows with the same key `(database_id, table_id)` and an `entity_type`. The table routes read only TABLE rows (`getUserTable`) and the view routes read only VIEW rows (`getUserView`, `findAllViewsByDatabaseId`). So `listTables` never shows a view and `listViews` never shows a table (`listingViewsListsOnlyViewsAndListingTablesOnlyTables`, itests).

All of the following are pinned in `aTableRouteAskedForAViewAndAViewRouteAskedForATable`, `headIs204ForAViewAnd404Otherwise`, `aCommitOnAMissingViewIs404NoSuchView` and `dropIsTheNativeDrop`.

| Route | Asked for | Answer | Client effect |
| --- | --- | --- | --- |
| GET or HEAD `views/{x}` | a table name | 404 `NoSuchViewException` | `loadView` raises `NoSuchViewException`, so Spark's view probe falls back to the table; `viewExists` is false |
| POST `views/{x}` (commit) | a table name | 404 `NoSuchViewException` | |
| DELETE `views/{x}` | a table name | 404 `NoSuchViewException` | `dropView` is false; the table is untouched |
| POST `views` (create) | over a table | 409 `AlreadyExistsException`, native "Name already exists as a table: db.t" | |
| GET or HEAD `tables/{x}` | a view name | 404 `NoSuchTableException` | `tableExists` is false; `RESTViewBuilder.replace()`'s table check passes |
| DELETE `tables/{x}` | a view name | 404 `NoSuchTableException` | |
| POST `tables` (create) | over a view | 409 `AlreadyExistsException` | |
| `tables/rename` onto a view's name | | The native rename's target check reads tables only. The real House Tables service refuses the key collision (`PRIMARY KEY (database_id, table_id)`, `DataIntegrityViolationException`, then 409). The H2 fixture in the service tests does not model this and answered 204, so it is not asserted (read, not run). | |
| View routes in a multi-level namespace | | 404 `NoSuchViewException` on entity routes (so Spark's probe of `db.t.snapshots` goes on to the metadata table); 400 on list and create (V3 rule) | |

## Views disabled

The switch is a per-database `views` feature toggle rule (`ViewsFeatureGate`, House Tables `table_toggle_rule`). It is off by default. Natively, every view route then answers 404 "Views are disabled" before any read. IRC gives the same 404 and message, with this type:
- `NoSuchViewException` on a view's own routes (load, HEAD, commit, drop). `RESTCatalog.loadView` raises `NoSuchViewException`, `viewExists` and `dropView` return false, and Spark table reads are unaffected.
- `NoSuchNamespaceException` on list and create. `listViews` and `buildView().create()` raise `NoSuchNamespaceException`. Spark `CREATE VIEW` fails `[SCHEMA_NOT_FOUND]`, the same class the native client gives in SparkSqlSmokeTest s28. `SHOW VIEWS` fails, while the OpenHouse client's `listViews` returns empty, because its own flag is off. `DROP VIEW IF EXISTS` is a no-op.
- `SHOW TABLES` and `SELECT` from tables work.

Tests: `withViewsDisabledEveryRouteIsTheNative404` (MockMvc), `withViewsDisabledTheClientSeesNoViews` (RESTCatalog), `withViewsDisabledTablesReadAndViewStatementsFail` (Spark). A view create in a database whose gate is closed reports "Views are disabled" even if the database is missing: natively the gate is checked first.

## Native view rules through IRC (each ran)

"=" means the stored pointer row and every metadata file equal the native write's after normalization (database names aside).

| Rule | Through IRC | Test |
| --- | --- | --- |
| Create stores server-owned UUID, location, system properties, source dialect, `replace.drop-dialect.allowed=false`, version summary `operation=create` | = | `createStoresWhatTheNativeCreateStores` |
| Replace: new version on current, `operation=replace`, schema added, system properties kept | = | `replaceStoresWhatTheNativeReplaceStores` |
| Property set and remove (complete-state PUT) | = | `propertySetAndRemoveStoreWhatTheNativeWritesStore` |
| No-op replace writes no file | Same: no file, same pointer | `aCommitThatChangesNothingWritesNothing` |
| Existing view on create | 409 `AlreadyExistsException`, native message | `createOfAnExistingViewIs409...` |
| Name held by a table | 409, native message | `createOverATableIs409AlreadyExists` |
| Database must exist | 404 `NoSuchNamespaceException`, native message | `createInAMissingNamespaceIs404NoSuchNamespace` |
| Reserved keys refused on create (`openhouse.*`, `policies`) | 400, native message | `createWithAReservedPropertyGetsTheNative400`, `policyKeysOnAViewAreTreatedAsNatively` |
| Supported dialects only | 400, native message | `createWithAnUnsupportedDialectGetsTheNative400` |
| System properties immutable on replace | 400, native message | `aCommitThatChangesASystemPropertyGetsTheNative400`, itest |
| Source dialect frozen | 400, native message | `aCommitThatRemovesTheSourceDialectGetsTheNative400` |
| Create privilege (database); replace and drop privilege (view) | 403, native message | `IrcViewRoutesAuthTest` (3), itest `aDeniedPrincipal...` |
| Reads make no authorization decision | Same | `readsMakeNoAuthorizationDecisionAsNatively`, itest |
| Base-version check (`baseMetadataLocation` equals the stored pointer) | IRC sends the pointer it loaded, so a base that moves between the IRC load and the native PUT gets the native 409 `CommitFailedException` | Read in code; no test forces a race inside a request |
| View audit (CREATE, REPLACE, DROP; FAILED on refusal) | Same events, from the native handler's aspect | `expectAudited` in create, replace, drop, existing-view and system-property tests |
| Missing view | 404 `NoSuchViewException`, native message | `aMissingViewIs404NoSuchView`, `dropIsTheNativeDrop` |
| Identifier rules, SQL and schema size, duplicate dialects, `defaultNamespace` segments | Native validator on every write | Read; the validator is on the same call path |
| Commit-state-unknown, HTS unavailable | Mapped (below) | Read only |

## Error mapping (view routes)

| Native | IRC status / type | 1.5.2 client raises |
| --- | --- | --- |
| `NO_SUCH_VIEW` 404 | 404 `NoSuchViewException` | `NoSuchViewException` |
| `DATABASE_NOT_FOUND` 404 | 404 `NoSuchNamespaceException` | `NoSuchNamespaceException` |
| `VIEWS_DISABLED` 404 | 404, `NoSuchViewException` on a view's routes, `NoSuchNamespaceException` on list and create | as typed |
| `VIEW_ALREADY_EXISTS`, `NAME_ALREADY_EXISTS_AS_TABLE` 409 | 409 `AlreadyExistsException` | `AlreadyExistsException` (create) |
| `CONCURRENT_VIEW_MODIFICATION` 409, or a failed requirement | 409 `CommitFailedException` | `CommitFailedException` (the view commit handler reads any 409 so) |
| `INVALID_VIEW_DEFINITION`, `UNSUPPORTED_VIEW_DIALECT`, `UNSUPPORTED_VIEW_SCHEMA` 400; IRC refusals (location, UUID, format version, a non-view requirement or update, unparseable body) | 400 `BadRequestException` | `BadRequestException` |
| `AccessDeniedException` 403 | 403 `ForbiddenException` | `ForbiddenException` (`loadView` wraps it in `NoSuchViewException`, but reads never deny) |
| `AuthorizationServiceException` 503 | 503 `ServiceUnavailableException`, native fixed message | `ServiceUnavailableException` |
| `VIEW_SERVICE_UNAVAILABLE` 503 | 503 `ServiceUnavailableException` | same |
| `COMMIT_STATE_UNKNOWN` 503 | 500 `CommitStateUnknownException` (as V3) | `CommitStateUnknownException` |
| `INTERNAL_VIEW_ERROR` 500 | 500 `ServiceFailureException` | `ServiceFailureException` |
| 422 codes (reserved, not emitted today) | 422 `ViewApiException` | `RESTException` |
| Rename | 406 `UnsupportedOperationException` | `RESTException` |

## Differences between an IRC view write and the native one

1. **The version summary** a client sends (Iceberg's `EnvironmentContext`: engine and iceberg-version) is not stored. The native engine writes `operation=create|replace` only. The OpenHouse client drops it too.
2. **Client-chosen version id, timestamp and schema id** are not used. The engine assigns them, as for the OpenHouse client.
3. **A commit is applied to the stored view, not to the client's base.** A view commit asserts only the UUID (Iceberg's `forReplaceView`), so two writers on stale handles both land, and the later replace's definition wins. This is the same as Iceberg's own catalog handlers. It is pinned: `twoWritersOnOneViewBothLand` and itest `twoWritersRacingOnOneView`, where both property changes survive.
4. **A create naming a location gets a 400.** The OpenHouse client silently drops it. Spark sends no location unless the user sets a `location` property, so nothing is sent as a matter of course and decision 3 needed no stop.
5. **Multiple SQL representations are accepted.** The OpenHouse client refuses anything but one; the server accepts several, one per dialect, if a source dialect is named. With several representations and no `openhouse.view.sourceDialect`, the native validator's 400 applies.
6. **The response is reloaded after a write** (one more House Tables read and metadata read), so a later commit could show.
7. **List of a missing database is 404**; natively it is an empty 200, as for tables in V2. List collects every native page (page size 100) and ignores `pageToken` and `pageSize`, as V2 does.
8. **Failures before the native handler are not in the view audit.** These are: a failed requirement, a refused update, a parse error, the location refusal, and the multi-level namespace refusal. The native audit also records nothing for requests refused before the handler.
9. **The response metadata is the stored file unchanged.** The wire `metadata` equals the stored JSON (`loadReturnsTheStoredMetadataAtTheNativePointer`). The OpenHouse and REST clients' JSON text differs only in properties-map order.

## Pull request 96 gap check (coordinator request)

I checked the view routes for the two shapes of gap pull request 96 closed for tables.
- **(a) A native validator the IRC route skips:** none. Every IRC view write calls `ViewsApiHandler`, whose create, update and delete run `OpenHouseViewsApiValidator`. Loads run `validateGetView`. The controller-level checks (identifier mismatch, `page` parameter) cannot arise, because IRC builds the body from the path.
- **(b) A property the OpenHouse client rewrites that a plain client sends raw:** none. `OpenHouseViewOperations` sends `metadata.properties()` raw. Its only rewrite is to add `openhouse.view.sourceDialect` on create from the single representation, and IRC derives it the same way. `OpenHouseViewBuilder` drops a location (IRC refuses one: difference 4).
- Pinned by `policyKeysOnAViewAreTreatedAsNatively`: raw `policies` gets the native 400 on both, and `updated.openhouse.policy` is stored as an ordinary property by both (stored state =).

## CI on pull request 95 (head 9f9f48bb)

Run 45074912842, job 137369894622, failed after about five minutes. Two causes:

1. **Mine, fixed in 86194651.** The 1.5 fixture's `IcebergViewBeanBoundaryTest.noSharedBeanSignatureNamesAnIcebergViewType` failed ("34 tests completed, 1 failed"). That test allows `org.apache.iceberg.view` types in bean signatures only for the view codec and the commit engine. My handler's private helpers and `ViewsServiceImpl`'s new helpers named `ViewMetadata` and `ViewVersion`. The fix:
   - the spelling and commit checks moved, unchanged, to the static non-bean class `IcebergRestViewSpelling`;
   - `ViewsServiceImpl` reads the metadata inline;
   - the IRC advice is scoped by package, so it never loads the view controller.

   After the fix the 1.5 fixture ran 34 tests, 0 failed.
2. **Not mine.** In `tables-test-fixtures_2.12` (Iceberg 1.2), every context boot fails with `NoClassDefFoundError: org/apache/iceberg/UpdateRequirement` from `IcebergRestWriteHandler`, which V3 added and which is on `irc-integration` (`git show origin/irc-integration:...IcebergRestWriteHandler.java` line 36 imports it). I reproduced it locally after my fix. This is the base branch's red full build. I did not chase it.

## Choices where the brief left room

- **A separate controller.** `IcebergRestViewController` and `IcebergRestViewHandler` are `@ConditionalOnClass(ViewMetadata)`, like `ViewsServiceImpl`. The 1.2 line therefore keeps the catch-all 406 for view routes and does not load view types. The view routes win over the catch-all by specificity.
- **`loadView` lives on `ViewsServiceImpl`, not on `ViewsService`.** The disabled implementation serves 1.2. `getView` now delegates to it, with the same gate, the same House Tables read and the same single metadata read. The view golden test pinned native stores before this change and is unchanged.
- **Commits are always sent to the native PUT, even with no updates.** The native engine's own no-op detection then writes nothing, while authorization and the view audit still run as natively. V3 short-circuited empty table commits instead.
- **Requirements:** `AssertViewUUID` failing gives 409. A table requirement on a view gives 400 (Iceberg's `ValidationException`).
- **Rename** is served as a route that always answers 406, after parsing the body (a bad body is 400).
- **`@Secured(AUTHENTICATED_VIEWS_OPS)`** is on every IRC view route, as on the native ones.
- **The itests use a second embedded server** with the views gate open for their databases and `IcebergRestCatalogReadTest`'s owner-only authorization, one per class, closed with it.

## Stopped on

Nothing. Rename was not built because no native path exists (decision 1). It answers 406, which the spec lists for this route.

## Test counts

| Suite | Before | After |
| --- | --- | --- |
| `:services:tables:test` | 1373 (V3 record, read) | 1411, 0 failed (ran, on 9f9f48bb). On the merged head 2446cbbe: see the addendum below. |
| Spark 3.5 `catalogTest` | 238, 1 skipped (V3 record, read) | 247, 0 failed, 1 skipped (ran, on 9f9f48bb). On the merged head, every `IcebergRest*` class ran (34 tests, 0 failed), with the 1.5 fixtures (34, 0 failed). |

The +38 on the service suite (before the merge) is:
- golden view tests: 4;
- `IrcViewRoutesTest`: 30;
- `IrcViewRoutesAuthTest`: 4.

After the merge, the service suite adds `policyKeysOnAViewAreTreatedAsNatively` plus pull request 96's own tests. Spark adds `IcebergRestCatalogViewTest` (5) and `IcebergRestSparkSqlViewTest` (4).

## Ran versus read

**Ran:**
- the red runs: the MockMvc tests (29 of 30 failed with 406; rename already 406), the auth tests (4 with 406), and the itests (8 of 9 with 406, with production code stashed);
- the view golden test before any code change;
- touched classes while iterating;
- full `:services:tables:test` and full Spark 3.5 `catalogTest` on 9f9f48bb;
- after the CI fix and the merge: the `Irc*`, golden, `ViewsServiceImplTest` and `ViewsServiceH2IntegrationTest` classes (233 tests, 0 failed), the 1.5 fixtures and the `IcebergRest*` itests;
- `publish-local.sh`, then `compileJava` and `compileTestJava -PicebergFork`;
- `spotlessApply` and `spotlessCheck` on `:services:tables` and the Spark 3.5 itest module.

**Read, not run:**
- the two spec versions. 1.12 adds `referenced-by` on load, `Idempotency-Key` on create, replace, drop and rename (not honored), 204 instead of 200 for rename, the wording "already exists as a table or view" on create 409, and `register-view` (out of scope, 406, pinned);
- the 1.5.2 and 1.12 `ErrorHandlers` (406 means `RESTException` on both) and 1.12's `view-endpoints-supported` gating;
- House Tables' primary key for the rename-onto-view case;
- the base-version race inside one request.

**Not run:**
- a 1.12 `RESTCatalog` against these routes;
- Spark 3.5 `statementTest` and `test`;
- the real MySQL House Tables mode;
- the delta-harness;
- the 1.2 fixture green (blocked by the base branch);
- CI on head 2446cbbe (not checked).

**JVMs:** I started no daemon of my own. The shared Gradle daemons, pids 2415 and 13774, predate my work; I left them. No test JVM of mine is left running.

## Addendum: the merged head

On the merged head 2446cbbe, the full `:services:tables:test` ran 1429 tests, 0 failed. That is 1411, plus pull request 96's tests, plus `policyKeysOnAViewAreTreatedAsNatively`.
