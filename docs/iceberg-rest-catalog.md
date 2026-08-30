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

Iceberg response types use a narrowly scoped Spring `HttpMessageConverter`. Errors are translated
by controller-scoped advice into the standard Iceberg error envelope.

## Compatibility and limitations

- Only single-level namespaces are supported. The depth cap is the cluster property
  `cluster.tables.namespace.max-depth`, which defaults to 1.
- Namespace properties are a free-form string map. Keys prefixed `openhouse.` are server-owned and
  rejected on write.
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
- Table-write, view, transaction, credential, and OAuth endpoints are not advertised.

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
