# Iceberg REST catalog

OpenHouse exposes an Apache Iceberg REST Catalog facade for new clients while preserving the
existing OpenHouse APIs and business behavior. Namespaces are writable through it; tables are still
read-only.

## Enablement

The facade is disabled by default. Enable it with:

```properties
cluster.tables.iceberg-rest.enabled=true
```

`GET /v1/config` returns the `iceberg` route prefix and advertises only the implemented endpoints:

- `GET /v1/{prefix}/namespaces`
- `POST /v1/{prefix}/namespaces`
- `GET /v1/{prefix}/namespaces/{namespace}`
- `HEAD /v1/{prefix}/namespaces/{namespace}`
- `DELETE /v1/{prefix}/namespaces/{namespace}`
- `POST /v1/{prefix}/namespaces/{namespace}/properties`
- `GET /v1/{prefix}/namespaces/{namespace}/tables`
- `GET /v1/{prefix}/namespaces/{namespace}/tables/{table}`
- `HEAD /v1/{prefix}/namespaces/{namespace}/tables/{table}`

## Architecture

The OpenAPI-generated interfaces own the HTTP contract. `IcebergRestCatalogController` is a thin
Spring MVC adapter, and `IcebergRestApiHandler` translates the Iceberg protocol to existing
`TablesApiHandler` and `OpenHouseInternalCatalog` behavior. `IcebergRestNamespaceApiHandler` does the
same for the namespace routes, over `NamespacesService`. The facade does not add business rules or
change the existing OpenHouse endpoints.

Namespaces are stored objects. A namespace is persisted by dot-joining its levels, so at the shipped
depth its key is the `databaseId` string byte for byte and existing databases are unaffected. The
Tables Service owns the namespace API contract (`NamespacesService`), the House Tables Service owns
the record (`database_row`, `/hts/databases`), and `HouseNamespaceRepository` is the seam between
them.

### What "stored" means for a database that predates the namespace store

A namespace exists if **either** store says so, and the two answers are composed in
`NamespacesServiceImpl`:

1. **The namespace store has a row for it.** Every namespace created through `POST
   /v1/{prefix}/namespaces` gets one. So does every database a table has been written into since
   this change landed: `TablesServiceImpl.putTable` calls `NamespacesService.ensureNamespace` on the
   path that creates a table in a database, which registers the row idempotently. Writing the first
   table into a database is what creates that database in OpenHouse, so it is also what creates its
   namespace record.
2. **The table store holds a table that names it.** This is every database created *before* this
   change. It has no row, and it is still a namespace: `SHOW NAMESPACES` lists it,
   `namespaceExists` answers true, `GET /namespaces/{it}` returns it with an empty property map,
   and `DELETE` reports `409 NamespaceNotEmpty` because a derived name exists precisely because a
   table occupies it. The derivation is the same one `GET /databases` has always used
   (`OpenHouseInternalRepository.findAllIds`).

The practical consequences of a database being known only by derivation:

- Its property map reads as empty, because there is nowhere yet for a property to live.
- The first `POST /namespaces/{it}/properties` writes its row on the way through, after which it is
  a stored namespace like any other. Nothing else about it changes and no data moves.
- `POST /v1/{prefix}/namespaces` for it is a `409 AlreadyExists`, as it should be: it exists.

No backfill job runs, and none is needed for correctness. A bulk backfill would only let the derived
branch be deleted; it is a performance change, not a behavioural one, and it belongs with the
migration-state machinery that this change deliberately does not build.

Iceberg response types use a narrowly scoped Spring `HttpMessageConverter`. Errors are translated
by controller-scoped advice into the standard Iceberg error envelope.

## Compatibility and limitations

- Only single-level namespaces are supported. The depth cap is the cluster property
  `cluster.tables.namespace.max-depth`, which defaults to 1. **Setting it to anything else fails
  startup**, with a message naming the seams nesting would need. Raising the property today would
  widen one validator and nothing below it: the encoded form of a two-level namespace contains a
  `.`, which `/hts/databases` rejects; `NamespaceUtil.isTableNamespace` still means "exactly one
  level"; the metadata-table discriminator of the design's section 5.4 is absent; the namespace
  store has no `childrenOf` range query; and `/v1` does not route nested paths. A startup error is
  the honest form of that, in place of a `500` on the first two-level create.
- `listNamespaces` reads the whole namespace store and the whole derived database list. That is
  bounded by the same limit `GET /databases` has always had, and it is unpaged: raising the number
  of databases a cluster can serve needs the design's `childrenOf(encodedParent)` range query,
  which is a new House Tables route and a client regeneration. Listing the children of a non-empty
  namespace short-circuits to empty at the shipped depth, so `dropNamespace` never depends on that
  scan.
- Namespace properties are bounded: at most 100 entries, 1 KiB per entry and 8 KiB in total,
  counted as UTF-8 key plus value bytes. A `null` property value is rejected rather than treated as
  a removal. The bounds are enforced at the Tables Service ingress and again at the House Tables
  boundary, which is reachable on its own.
- A namespace write is a compare-and-set on the version the writer read. Two concurrent updates do
  not both succeed: the loser gets `409 CommitFailedException`, and two concurrent creates of the
  same namespace resolve to one `201` and one `409`.
- Namespace property keys are held to the same preserved-key rule as table properties
  (`BasePreservedKeyChecker`): keys prefixed `openhouse.`, and `policies`, are server-owned and
  rejected on write with `400 ValidationException`. A reserved key inside `updates` on an existing
  namespace is a `400`, never a `404` — the namespace does exist, and saying otherwise would be
  false.
- Namespace lookup is case-insensitive but listing is not, so `GET`/`POST` responses name the
  **stored** namespace rather than echoing the requested spelling. `GET /namespaces/mydb` for a
  stored `MyDb` answers with `MyDb`.
- `listNamespaces` returns the complete listing in one page and never issues a continuation token.
  An empty `pageToken` (which every Iceberg Java client since 1.6.0 sends) is accepted.
- Dropping a namespace that still holds tables or child namespaces is a `409`
  (`NamespaceNotEmptyException`); there is no cascade.
- A namespace read route never answers `400` for a well-formed URL naming a namespace that cannot
  exist: a syntactically invalid namespace is a `404`, the same as an absent one.
- The optional `warehouse` configuration hint does not select a different OpenHouse warehouse.
- List responses support opaque continuation tokens and page sizes from 1 through 1000.
- Table loads return all snapshots. The `snapshots=refs` projection is explicitly unsupported.
- The Iceberg 1.11 `referenced-by` query parameter is accepted and ignored.
- Access delegation may be requested, but this read-only version does not vend credentials.
- Conditional ETag responses are not currently emitted.
- Renaming a table is supported within a database and refused across databases. The rule is the
  `/v1` API's own (`OpenHouseTablesApiValidator.validateRenameTable`), which the REST route calls
  rather than reimplements, and the specification permits a server to refuse a cross-namespace
  move. The refusal is a `400` carrying that validator's sentence. A rename into a namespace that
  does not exist is a `404`, decided before the refusal, so a typo is never reported as an
  unsupported capability.
- Registering an existing metadata file is **not** supported and
  `POST /v1/{prefix}/namespaces/{namespace}/register` is not advertised. OpenHouse allocates every
  table's location itself and its only drop purges everything under that location, so adopting a
  metadata file the catalog did not write would give it authority to delete files it never placed,
  carrying state that never passed the write path's checks.
- The metrics route accepts a report and discards it, which the specification permits. There is no
  sink for scan or commit reports. The table is not looked up, so a report about a table that does
  not exist is accepted like any other and the route never answers `404`.
- View, transaction, credential, and OAuth endpoints are not advertised.

Existing OpenHouse APIs remain supported. Client migrations can therefore be incremental.

## Observability and audit

Spring Boot records the facade through the standard `http.server.requests` metrics, including URI,
status, and latency. Table reads delegate through `TablesApiHandler`, retaining existing
authorization, lock visibility, and table-read audit behavior.

## Contract maintenance

`spec/iceberg-rest-catalog-open-api.yaml` is the full Apache Iceberg REST OpenAPI. OpenHouse support
is opt-in:

```yaml
operationId: listTables
x-openhouse-support: supported
```

Operations without the annotation are unsupported. The build codegens Spring interfaces only for
`supported` operations and generates `IcebergRestOpenHouseSupport.SUPPORTED_ENDPOINTS` for
`/v1/config`. Marking a new operation `supported` (or changing a supported signature) fails
compilation until the facade implements it.

To upgrade Iceberg OpenAPI: merge the newer upstream YAML into the checked-in file, add
`x-openhouse-support: supported` where needed, then compile.
