# IRC view routes (V4): result

Bottom line: an unmodified Iceberg 1.5.2 `RESTCatalog`, and Spark 3.5 through Iceberg's own `SparkCatalog`
(`type=rest`), can create, load, replace, change and drop OpenHouse views over IRC, and the stored state equals a
native write's. Views cannot be renamed, and an Iceberg 1.12 `RESTCatalog` does not yet use them because the config
route advertises no view support (item V10a). Written by a delegated agent from branch `irc/v4-irc-view-routes`
(pull request 95, merged as 71b3e826). "Ran" means run. "Read" means taken from code or earlier records only.

## What works (ran)

- The `RESTCatalog` runs `buildView().create()`, `loadView`, `viewExists`, `listViews`, `replace()`,
  `createOrReplace()`, property set and remove, and `dropView`. After each step the OpenHouse Java client sees the
  same metadata file and metadata. Test: `IcebergRestCatalogViewTest`, 5 tests.
- Spark 3.5 SQL runs CREATE VIEW and SELECT from it, SHOW VIEWS, SHOW TABLES (tables only), DESCRIBE, CREATE OR
  REPLACE VIEW (version 2, new column), ALTER VIEW SET and UNSET TBLPROPERTIES, a view over a view, and DROP VIEW.
  A view whose base table was dropped fails on SELECT with `TABLE_OR_VIEW_NOT_FOUND` and can still be dropped.
  Test: `IcebergRestSparkSqlViewTest`, 4 tests. Spark needs only Iceberg's SQL extensions, already in the session.

## What does not work, and why

- Rename: the native view API has no rename, so the route answers 406 `UnsupportedOperationException` and the
  view is unchanged (D27). The 1.5.2 client raises `RESTException`.
- A view in a database that holds nothing gets 404 `NoSuchNamespaceException`, as natively.
- Views with the gate closed (the default): see below.
- System properties (`openhouse.*`, `replace.drop-dialect.allowed`, `policies`) get the native 400. Naming a
  location, or changing location, UUID or format version, gets 400.
- Iceberg 1.12 `RESTCatalog` (read, not run): it calls view routes only when the config advertises
  `view-endpoints-supported=true` or an `endpoints` list. `/v1/config` advertises neither, so it treats views as
  unsupported. This is item V10a.

## Views disabled

The switch is the per-database `views` toggle rule (`ViewsFeatureGate`). It is off by default. Natively every view
route then answers 404 "Views are disabled" before any read, and IRC gives the same message (ran):

- A view's own routes (load, HEAD, commit, drop) answer `NoSuchViewException`. `loadView` raises it, `viewExists`
  and `dropView` return false, and table reads are unaffected.
- List and create answer `NoSuchNamespaceException`. Spark CREATE VIEW fails `[SCHEMA_NOT_FOUND]`, as in smoke test
  s28. `DROP VIEW IF EXISTS` is a no-op.
- The gate is checked first, so a create in a missing database also says "Views are disabled".

Tests: `withViewsDisabledEveryRouteIsTheNative404`, `withViewsDisabledTheClientSeesNoViews`,
`withViewsDisabledTablesReadAndViewStatementsFail`.

## Tables versus views (ran)

Natively both are House Tables rows with one key `(database_id, table_id)` and an `entity_type`; table routes read
only TABLE rows and view routes only VIEW rows. Pinned in `aTableRouteAskedForAViewAndAViewRouteAskedForATable`,
`headIs204ForAViewAnd404Otherwise` and `dropIsTheNativeDrop`.

| Route | Asked for | Answer |
| --- | --- | --- |
| GET, HEAD, commit, DELETE on `views/{x}` | a table name | 404 `NoSuchViewException`; Spark's view probe falls back to the table |
| POST `views` (create) | over a table | 409 `AlreadyExistsException`, "Name already exists as a table" |
| GET, HEAD, DELETE on `tables/{x}` | a view name | 404 `NoSuchTableException` |
| POST `tables` (create) | over a view | 409 `AlreadyExistsException` |
| View routes in a multi-level namespace | | 404 on entity routes, so Spark's probe moves on to the metadata table; 400 on list and create |

Rename of a table onto a view's name: the native target check reads tables only, and the real House Tables key
refuses the collision (read). The H2 fixture does not model it, so it is not asserted.

## Error mapping

| Native | IRC status and type | 1.5.2 client raises |
| --- | --- | --- |
| No such view 404 | 404 `NoSuchViewException` | `NoSuchViewException` |
| Database not found 404 | 404 `NoSuchNamespaceException` | `NoSuchNamespaceException` |
| View already exists, or exists as a table 409 | 409 `AlreadyExistsException` | `AlreadyExistsException` |
| Concurrent modification 409, or failed requirement | 409 `CommitFailedException` | `CommitFailedException` |
| Invalid definition, dialect or schema 400; IRC refusals | 400 `BadRequestException` | `BadRequestException` |
| Access denied 403 | 403 `ForbiddenException` | `ForbiddenException` |
| Authorization or view service unavailable 503 | 503 `ServiceUnavailableException` | same |
| Commit state unknown 503 | 500 `CommitStateUnknownException` (as V3) | same |
| Internal view error 500 | 500 `ServiceFailureException` | same |
| Rename | 406 `UnsupportedOperationException` | `RESTException` |

## Differences from a native view write

1. The version summary a client sends (engine and Iceberg version) is not stored; the engine writes the operation.
2. Client-chosen version id, timestamp and schema id are not used.
3. A commit applies to the stored view, not the client's base. It asserts only the UUID, so two writers on stale
   handles both land and the later definition wins (`twoWritersOnOneViewBothLand`).
4. A create naming a location gets 400. The OpenHouse client silently drops it.
5. Several SQL representations are accepted, one per dialect, if a source dialect is named. The OpenHouse client
   accepts one.
6. The response is reloaded after a write, so a commit landing in between shows.
7. List of a missing database is 404. Natively it is an empty 200. List ignores `pageToken` and `pageSize`.
8. Failures before the native handler (failed requirement, parse error, location refusal, multi-level namespace)
   are not in the view audit.

## Gap check requested after pull request 96

Both shapes of gap that pull request 96 closed for tables were checked for views (read, plus one test).

- A validator the IRC route skips: none. Every write calls `ViewsApiHandler`, whose create, update and delete run
  `OpenHouseViewsApiValidator`. The controller-only checks cannot arise because IRC builds the body from the path.
- A property the native client rewrites that a plain client sends raw: none. The client adds
  `openhouse.view.sourceDialect` on create and IRC derives it the same way.
- `policyKeysOnAViewAreTreatedAsNatively`: raw `policies` gets the native 400 on both paths, and
  `updated.openhouse.policy` is stored as an ordinary property by both.

## Choices where the brief left room

- A separate controller and handler, both `@ConditionalOnClass(ViewMetadata)`, so the 1.2 line keeps the catch-all
  406 for view routes and never loads view types.
- `loadView` lives on `ViewsServiceImpl`; the native `getView` delegates to it.
- Commits always go to the native PUT, even with no updates, so no-op detection, authorization and audit are native.
- An `AssertViewUUID` failure is 409; a table requirement on a view is 400.
- `@Secured(AUTHENTICATED_VIEWS_OPS)` is on every IRC view route, as natively.
- The itests use a second embedded server with the views gate open.

## Counts

| Suite | Before | After |
| --- | --- | --- |
| `:services:tables:test` | 1373 (read) | 1429, 0 failed (ran, merged head 2446cbbe) |
| Spark 3.5 `catalogTest` | 238, 1 skipped (read) | 247, 0 failed, 1 skipped (ran, on 9f9f48bb) |

The first run of CI on the branch failed on the 1.5 fixture's `IcebergViewBeanBoundaryTest`, which forbids Iceberg
view types in bean signatures. The checks moved to a static class and the fixture then ran 34 tests, 0 failed. It
also showed the 1.2 fixture failing on `IcebergRestWriteHandler`, which became pull request 98 (`27-v3-irc-write-result.md`).

## Not run

A 1.12 `RESTCatalog` against these routes; Spark 3.5 `statementTest` and `test`; the real MySQL House Tables mode;
the delta-harness; the 1.2 fixture green (it was red on the base); CI on the merged head. Read only: the two spec
revisions (1.12 adds `referenced-by`, an `Idempotency-Key` header that is not honoured, 204 for rename and
`register-view`, which answers 406) and the base-version race inside one request.
