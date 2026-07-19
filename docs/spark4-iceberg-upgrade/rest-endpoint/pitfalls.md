# Pitfalls and capability gaps

Technical facts only. These are the known gaps in the REST-first endpoint as delivered; do not
assume the delta-harness will pass CREATE TABLE without addressing #1.

## 1. CREATE TABLE via a stock RESTCatalog does not populate OpenHouse reserved state (BLOCKER for create)

`POST .../tables` -> `CatalogHandlers.createTable` -> `catalog.buildTable(ident, schema)
.withLocation(request.location())...create()`. Two things a stock client does not supply:

- **Table location.** `OpenHouseInternalCatalog.defaultWarehouseLocation(...)` throws
  `UnsupportedOperationException("Location will be provided explicitly")`. A stock `RESTCatalog`
  create request typically carries **no** `location`, so `create()` fails before any commit. OpenHouse
  normally allocates the location in `OpenHouseInternalRepositoryImpl` via `StorageSelector`, which
  is not on the `CatalogHandlers` path.
- **Reserved HTS/property state.** `OpenHouseInternalRepositoryImpl.computePropsForTableCreation`
  sets the OpenHouse-prefixed properties that `HouseTableMapper.toHouseTable` reads to build the HTS
  row -- `openhouse.tableId`, `openhouse.databaseId`, `openhouse.tableUUID`, `openhouse.tableLocation`,
  `openhouse.clusterId`, `openhouse.tableType`, policies, etc. `CatalogHandlers.createTable` sets
  none of these, so even if a location were provided, the HTS `save` at the end of
  `OpenHouseInternalTableOperations.doCommit` would persist a row with null `databaseId`/`tableId`/
  `tableUUID` (or fail the HTS write).

Consequence: pure delegation is sufficient for **load / list / exists / drop / rename / INSERT
(commit into an already-created table)**, but **not** for creating a brand-new table from a stock
client. Closing this requires the REST `createTable` handler to allocate a location and populate the
OpenHouse reserved properties before delegating to `catalog.buildTable(...).create()` (still the
Catalog/TableOperations path, not a bypass) -- i.e. porting the relevant part of
`computePropsForTableCreation`. That was intentionally **not** done here per the "delegate to
CatalogHandlers, do not re-derive" scoping; it is the first follow-up if the harness needs CREATE.

CTAS/stage-create (`CreateTableRequest.stageCreate()==true`) is delegated to
`CatalogHandlers.stageTableCreate`, which has the same location/reserved-prop gap.

## 2. Namespace existence is optimistic

`loadNamespaceMetadata` and `HEAD namespace` report "exists" for **any** valid single-level
namespace, because OpenHouse has no namespace registry to consult. This is what allows
`CREATE NAMESPACE db` + `USE db` + `CREATE TABLE db.t` in a new database to proceed. The cost:
`HEAD /namespaces/does-not-exist` returns 204 instead of 404, and `loadNamespaceMetadata` never
404s. `listNamespaces`, by contrast, is authoritative (only DBs with >=1 table).

## 3. `DROP NAMESPACE` returns 501

OpenHouse databases have no independent lifecycle, so `DELETE /namespaces/{ns}` returns 501 Not
Implemented. `DROP NAMESPACE` from Spark will surface that error. A database disappears on its own
when its last table is dropped.

## 4. Namespace properties are not persisted

`createNamespace` echoes the requested namespace but discards properties; `loadNamespaceMetadata`
always returns empty properties. There is no OpenHouse store for namespace-level properties.

## 5. Views not implemented

View endpoints (`/namespaces/{ns}/views/...`) are intentionally omitted. `OpenHouseInternalCatalog`
is not a `ViewCatalog`.

## 6. Multi-level namespaces rejected

OpenHouse is single-level (`databaseId`). `createNamespace`/`loadNamespaceMetadata` throw
`ValidationException` (400) for multi-level namespaces; `HEAD` returns 404 for them. Path namespaces
are decoded with `RESTUtil.decodeNamespace`; in practice OpenHouse db ids contain no unit-separator
or reserved characters, so the single URL-decode Spring performs on the path variable is sufficient.

## 7. Metadata / config tables, pagination tokens

`loadTable` on a metadata table (e.g. `db.t.snapshots`) maps to 404 (as in stock `CatalogHandlers`).
`listNamespaces`/`listTables` return all results in one page (no `pageToken` honored); fine for the
spike.

## Build / toolchain note (not a code gap)

The repo pins **Gradle 7.6.2** via the wrapper, but that distribution cannot be downloaded in this
environment (the wrapper's distribution URL is redirected to github.com, which is access-gated ->
HTTP 403). The build was therefore run with the locally installed **Gradle 8.14.3**
(`/opt/gradle`) and **JDK 17** (`/usr/lib/jvm/java-17-openjdk-amd64`; JDK 21 is the default but
breaks the project's Lombok -- `JCTree$JCImport.qualid` was removed in JDK 21).

Gradle 8's variant matching is stricter than 7.6.2's: it refuses to place iceberg 1.10 (which
declares `jvm.version=11` via Gradle Module Metadata) onto configurations whose
`TARGET_JVM_VERSION` attribute is 8. The repo's `openhouse.iceberg-conventions-1.5.2` plugin already
raises that attribute to 11 for compile classpaths, but under Gradle 8 some resolvable configs it
does not cover (e.g. `testFixturesCompileClasspath`) still fail. To verify the build **without
modifying any tracked file** (the iceberg-conventions plugin is owned by the upgrade rung), an
untracked init script raised `TARGET_JVM_VERSION=11` on every resolvable config in `afterEvaluate`:

```
gradle --no-daemon --init-script <scratch>/jvm11.init.gradle :services:tables:build -x test
```

Under the project's real Gradle 7.6.2 wrapper the init script is unnecessary. Both
`:services:tables:compileJava` and `:services:tables:build -x test` pass. Tests were not run
(`-x test`) per the task's compile gate.
