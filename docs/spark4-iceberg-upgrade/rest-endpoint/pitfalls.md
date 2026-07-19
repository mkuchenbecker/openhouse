# Pitfalls and capability gaps

Technical facts only. These are the known gaps in the REST-first endpoint as delivered; do not
assume the delta-harness will pass CREATE TABLE without addressing #1.

## 1. CREATE TABLE via a stock RESTCatalog -- CLOSED (plain create); CTAS/stage-create still gapped

**Status: closed for plain `CREATE TABLE`.** `POST .../tables` no longer delegates to
`CatalogHandlers.createTable` (which called `catalog.buildTable(ident, schema).create()` and failed
because `OpenHouseInternalCatalog.defaultWarehouseLocation(...)` throws and no `openhouse.*` reserved
state is populated). Instead the REST `createTable` handler
(`IcebergRestCatalogController.createTable`) now translates the Iceberg `CreateTableRequest` into an
OpenHouse `CreateUpdateTableRequestBody` and calls the **same** `TablesApiHandler.createTable` bean
the native `TablesController` PUT path uses. Location allocation (`StorageSelector`), reserved-prop
population (`computePropsForTableCreation`), policy management, and creation eligibility checks all
run unchanged in the service/repository layer. The response is a real Iceberg `LoadTableResponse`
produced by loading the freshly created table through the same `CatalogHandlers.loadTable` path the
load handler uses, so the `RESTCatalog` immediately sees the new table.

Translation (mirrors the client-side `OpenHouseTableOperations` request-builder, the authoritative
reference):

- **Schema**: `SchemaParser.toJson(request.schema())` (inverse of
  `IcebergSchemaHelper.getSchemaFromSchemaJson`).
- **Identity/auth**: `clusterId` = server `ClusterProperties.getClusterName()`; caller principal =
  `AuthenticationUtils.extractAuthenticatedUserPrincipal()` (same as the native controller);
  `baseTableVersion` = `INITIAL_VERSION`.
- **Partitioning**: the Iceberg `PartitionSpec` is reduced to OpenHouse's single `TimePartitionSpec`
  + `List<ClusteringColumn>` by the new inverse methods `PartitionSpecMapper.toTimePartitionSpec` /
  `toClusteringColumns`. Specs OpenHouse cannot model are **rejected with HTTP 400** (mapped to the
  Iceberg `ErrorResponse` envelope), never silently dropped.
- **Sort order**: passed through as JSON when the request carries a sort order.
- **Table properties**: user properties passed through unchanged.

### Accepted vs rejected partition specs (the OpenHouse model subset)

OpenHouse models partitioning as **at most one time transform on one timestamp column** plus **up to
`MAX_ALLOWED_CLUSTERING_COLUMNS` identity/truncate/bucket clustering columns**. Precisely:

- **Unpartitioned** -> accepted (no time partitioning, no clustering).
- **Time partitioning**: a single `TIMESTAMP`/`TIMESTAMPTZ` column with `hour` / `day` / `month` /
  `year` -> accepted. Rejected: more than one time-transformed column; any non-time transform on a
  timestamp column (`identity`, `bucket[n]`, `truncate[n]`, `void`).
- **Clustering**: columns of type `STRING` / `INTEGER` / `LONG` / `DATE` with `identity`,
  `truncate[n]`, or `bucket[n]` -> accepted (`identity` maps to a null OpenHouse transform;
  truncate/bucket map to the corresponding `Transform`). Rejected: any other column type; any other
  transform (including `void`); more than `MAX_ALLOWED_CLUSTERING_COLUMNS` clustering columns.
- Anything else (e.g. `bucket`/`truncate`/`void` on an unsupported type) -> rejected with HTTP 400.

This subset matches exactly what OpenHouse's own create path (`PartitionSpecMapper.toPartitionSpec`)
can round-trip, so a REST-created table loads back with the same spec.

### CTAS / stage-create -- still gapped

`CreateTableRequest.stageCreate() == true` (Spark **CTAS/RTAS**) is **rejected with HTTP 501**. Iceberg
staged create returns metadata for an as-yet-uncommitted table and relies on a follow-up
commit-transaction to publish it atomically; OpenHouse's create path commits the table to the HTS
immediately and does not expose those staged-transaction semantics through this single call. Plain
`CREATE TABLE` followed by `INSERT` works; `CREATE TABLE ... AS SELECT` does not. Closing this would
require wiring the Iceberg transaction/commit-requirement flow (or an OpenHouse `stageReplace` /
`replaceCommit` equivalent) through the REST update path and is the next follow-up.

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
