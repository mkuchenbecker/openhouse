# Design and decisions

## 1. Delegate TABLE ops to `CatalogHandlers`; never bypass the Catalog/TableOperations path

All table operations call `org.apache.iceberg.rest.CatalogHandlers` (present in
`iceberg-core:1.10.0-openhouse`), which routes through the injected `Catalog`
(`OpenHouseInternalCatalog`) and its `TableOperations`. This is deliberate: OpenHouse's
reserved-key interception -- snapshot smuggling (`SNAPSHOTS_JSON_KEY`) and the commit-version
CAS (`COMMIT_KEY`) -- lives in `OpenHouseInternalTableOperations.commit(...)`. A REST commit
(`POST .../tables/{table}` -> `CatalogHandlers.updateTable` -> `catalog.loadTable(...).operations().commit(...)`)
hits that code automatically, so it is neither reimplemented nor bypassed.

For a stock client, INSERT/commit arrives as standard Iceberg `MetadataUpdate`s (AddSnapshot,
SetSnapshotRef, ...) inside `UpdateTableRequest`; `CatalogHandlers.commit` applies them to the
`TableMetadata` builder and the OpenHouse `doCommit` persists the new `metadata.json` and the HTS
row. The OpenHouse-specific `SNAPSHOTS_JSON_KEY` / `COMMIT_KEY` branches simply do not fire for a
stock client (they are keyed off OpenHouse-only properties), which is correct.

## 2. Namespaces implemented directly (no `SupportsNamespaces`)

`OpenHouseInternalCatalog` does not implement `org.apache.iceberg.catalog.SupportsNamespaces`
(there is a literal TODO in it), so `CatalogHandlers`' namespace methods -- which require a
`SupportsNamespaces` -- cannot be used. OpenHouse databases are **implicit**: a database exists iff
it has at least one table; there is no database registry and no per-namespace property store. The
client-side `OpenHouseCatalog` (integrations/java/iceberg-1.2) mirrors this -- it implements only
`listNamespaces()` (from the databases API) and throws `UnsupportedOperationException` for
create/drop/load/setProperties/removeProperties/namespaceExists.

Chosen server semantics:
- `listNamespaces` -> distinct database ids from `DatabasesService.getAllDatabases()` (authoritative;
  only DBs with >=1 table appear). The spec `parent` query param yields an empty list (OpenHouse is
  single-level).
- `createNamespace` -> no-op success, echoes the namespace with empty properties. **Properties are
  not persisted.**
- `loadNamespaceMetadata` -> empty properties for any valid single-level namespace (**optimistic
  existence**).
- `namespaceExists` (HEAD) -> 204 for any single-level namespace; 404 only for a malformed
  (multi-level) namespace.
- `dropNamespace` -> 501 Not Implemented (databases have no independent lifecycle).

Optimistic existence is intentional: it lets `CREATE NAMESPACE db`, `USE db`, and
`CREATE TABLE db.t` (in a brand-new db) all succeed even though the db has no tables yet. The
trade-off (HEAD/GET on a truly non-existent namespace returns "exists") is acceptable for the
REST-first spike and is recorded in `pitfalls.md`.

## 3. Manual (de)serialization with an Iceberg-configured `ObjectMapper`

Iceberg's REST request/response objects need custom Jackson (de)serializers (`Schema`,
`PartitionSpec`, `MetadataUpdate`, `UpdateRequirement`, `TableMetadata`, `Namespace`, ...). Rather
than reconfigure the Spring MVC `ObjectMapper` globally (which would affect every other controller),
the controller reads the raw request body as a `String` and writes the raw response as a `String`
using its own `ObjectMapper`, configured identically to Iceberg's package-private
`RESTObjectMapper`: `FIELD` visibility `ANY`, `FAIL_ON_UNKNOWN_PROPERTIES=false`,
`KebabCaseStrategy`, and `RESTSerializers.registerAll(mapper)`. Namespaces in the path are parsed
with `RESTUtil.decodeNamespace`.

Spring's `StringHttpMessageConverter` accepts `*/*`, so `@RequestBody String` works for
`application/json` bodies without any converter wiring.

## 4. Error handling -> Iceberg `ErrorResponse` envelope

The service already has a global `@ControllerAdvice` (`OpenHouseExceptionHandler`) with a catch-all
`@ExceptionHandler(Exception.class)` that emits OpenHouse's `ErrorResponseBody` -- which a stock
`RESTCatalog` client cannot parse. Spring resolves `@ExceptionHandler` methods declared **in the
controller** before those on any `@ControllerAdvice`, so the mapping is done with local
`@ExceptionHandler` methods on `IcebergRestCatalogController`. Each builds the body with Iceberg's
`ErrorResponse.builder()` and sets the spec status:

| Exception | HTTP |
| --- | --- |
| `NoSuchTableException`, `NoSuchNamespaceException` | 404 |
| `AlreadyExistsException`, `CommitFailedException`, `CommitStateUnknownException` | 409 |
| `NotAuthorizedException` | 401 |
| `ForbiddenException` | 403 |
| `ValidationException`, `BadRequestException`, `IllegalArgumentException` | 400 |
| `UnsupportedOperationException` | 501 |
| everything else | 500 |

(Iceberg's `BadRequestException`/`ForbiddenException`/`NotAuthorizedException` all extend
`RESTException`; Spring picks the most specific handler, so they map to 400/403/401 rather than the
generic 500.)

## 5. Auth

The existing `TablesMvcConfigurer` registers the cluster security-token `HandlerInterceptor` on
`/**` (excluding only actuator/swagger/error). `/iceberg/**` is therefore covered by the **same**
authentication as every other OpenHouse endpoint -- the `Authorization: Bearer <token>` the Spark
client sends (`spark.sql.catalog.<name>.token`) is validated and turned into a principal exactly as
for `/v1/databases/...`. **Nothing was relaxed or bypassed.** No new interceptor, no exclusion for
`/iceberg/**`.

Note on authorization (as opposed to authentication): OpenHouse's per-resource `@Secured`
authorization is applied only to methods annotated `@Secured` and evaluated by
`AuthorizationInterceptor`. The REST endpoints are not `@Secured`, matching the fact that
`AuthorizationInterceptor` currently returns "granted" for all requests (its per-resource decision
is a TODO in the codebase). Authentication (token -> principal) still applies.
