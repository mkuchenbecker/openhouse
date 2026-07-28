# Iceberg REST Catalog endpoint (REST-first cutover)

This adds a server-side implementation of the Iceberg REST Catalog spec to the OpenHouse
`tables` service, so a **stock** `org.apache.iceberg.rest.RESTCatalog` (Spark 4.0) can drive
OpenHouse without any custom Spark/client catalog jar.

## What was added

| File | Role |
| --- | --- |
| `services/tables/src/main/java/com/linkedin/openhouse/tables/controller/IcebergRestCatalogController.java` | The REST controller: config, namespaces, tables, rename, and Iceberg-envelope error mapping. |

No other production files were changed. The controller injects the existing beans
`org.apache.iceberg.catalog.Catalog` (the `OpenHouseInternalCatalog` bean) and
`com.linkedin.openhouse.tables.services.DatabasesService`.

## Mounting

The controller is mounted under **`/iceberg/v1`** (class-level `@RequestMapping`), a distinct
prefix from the existing OpenHouse `/v1/databases/...` API, so there is no path collision. A stock
`RESTCatalog` is pointed at `uri = http://<host>:<port>/iceberg` and appends the spec paths
(`/v1/config`, `/v1/namespaces/...`, `/v1/namespaces/{ns}/tables/...`, `/v1/tables/rename`).

The `GET /iceberg/v1/config` response returns empty `defaults`/`overrides` and **no** `prefix`, so
the client uses the paths directly under `/iceberg/v1`.

## Endpoints implemented

Config
- `GET /iceberg/v1/config` -> `ConfigResponse` (empty).

Namespaces (implemented directly -- catalog has no `SupportsNamespaces`; see design + pitfalls)
- `GET  /iceberg/v1/namespaces` -> list (distinct DBs that have >=1 table, from `DatabasesService`)
- `POST /iceberg/v1/namespaces` -> create (no-op success, echoes namespace, empty props)
- `GET  /iceberg/v1/namespaces/{ns}` -> load metadata (empty props, optimistic existence)
- `HEAD /iceberg/v1/namespaces/{ns}` -> exists (204 for any single-level ns; 404 if multi-level)
- `DELETE /iceberg/v1/namespaces/{ns}` -> 501 Not Implemented

Tables (delegate to `org.apache.iceberg.rest.CatalogHandlers` -> `Catalog` -> `TableOperations`)
- `GET  /iceberg/v1/namespaces/{ns}/tables` -> `CatalogHandlers.listTables`
- `POST /iceberg/v1/namespaces/{ns}/tables` -> `CatalogHandlers.createTable` / `stageTableCreate`
- `GET  /iceberg/v1/namespaces/{ns}/tables/{table}` -> `CatalogHandlers.loadTable`
- `HEAD /iceberg/v1/namespaces/{ns}/tables/{table}` -> `CatalogHandlers.tableExists`
- `DELETE /iceberg/v1/namespaces/{ns}/tables/{table}?purgeRequested=` -> `dropTable`/`purgeTable`
- `POST /iceberg/v1/namespaces/{ns}/tables/{table}` -> `CatalogHandlers.updateTable` (commit)
- `POST /iceberg/v1/tables/rename` -> `CatalogHandlers.renameTable`

Views are intentionally not implemented.

## Pointing Spark 4.0 at it

Using the stock Iceberg `SparkCatalog` + `RESTCatalog`:

```
spark.sql.catalog.openhouse                = org.apache.iceberg.spark.SparkCatalog
spark.sql.catalog.openhouse.catalog-impl   = org.apache.iceberg.rest.RESTCatalog
spark.sql.catalog.openhouse.uri            = http://<tables-host>:<port>/iceberg
spark.sql.catalog.openhouse.token          = <bearer-token>     # forwarded as Authorization: Bearer
# warehouse is not required (config returns no prefix); set only if your deployment needs it:
# spark.sql.catalog.openhouse.warehouse    = <ignored by this endpoint>
```

Notes
- Do **not** set `type=rest` together with `catalog-impl`; use `catalog-impl=org.apache.iceberg.rest.RESTCatalog`
  (equivalently `type=rest` alone). Do not set both.
- The `token` is sent as `Authorization: Bearer <token>` and is honored by the same OpenHouse
  authentication path as every other endpoint (see design doc, "Auth").

## Build / verify

- `:services:tables:compileJava` passes.
- `:services:tables:build -x test` passes.

See `pitfalls.md` for the exact toolchain used (the pinned Gradle 7.6.2 wrapper distribution is not
downloadable in this environment) and for the capability gaps.
