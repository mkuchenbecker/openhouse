# Database Properties: Implementation Plan

Planning output for introducing database-level properties into OpenHouse through an explicit
look-aside mechanism. This document is the deliverable of a planning pass over the repository at
commit `e3db001`. It proposes three PRs and does not implement anything.

Every statement below is tagged. **Verified** means it was read in the repository at the cited
path. **Assumption** means it is an inference the implementer should confirm. **Decision** means a
choice this plan makes among workable alternatives, with the reason.

---

## 1. Architectural summary

OpenHouse has no persistent database entity today. A "database" is a derived concept: the distinct
set of `databaseId` values over table rows.

**Verified.** The HouseTables Service (HTS) persists one row per table in `user_table_row`, keyed
by `(database_id, table_id)`
(`services/housetables/src/main/java/com/linkedin/openhouse/housetables/model/UserTableRow.java`,
`services/housetables/src/main/resources/schema.sql`). The Tables Service lists databases by
listing every table and taking distinct database ids
(`services/tables/.../services/DatabasesServiceImpl.java#getAllDatabases` →
`OpenHouseInternalRepositoryImpl#findAllIds` → `catalog.listTables(Namespace.empty())`). The
Tables Service's `DatabaseDto` has only `databaseId` and `clusterId` and is never persisted
(`services/tables/.../model/DatabaseDto.java`).

**Verified.** The request path from a client to storage is:

```
client (generated TableApi / DatabaseApi, or hand-written OpenHouseCatalog / jobs TablesClient)
  → Tables Service: TablesController / DatabasesController
      → TablesApiHandler / DatabasesApiHandler (validator + mapper)
          → TablesService / DatabasesService
              → OpenHouseInternalRepository → OpenHouseInternalCatalog (Iceberg catalog)
                  → HouseTableRepositoryImpl (iceberg/openhouse/internalcatalog)
                      → generated HTS client UserTableApi (client/hts)
                          → HTS: UserHouseTablesController → UserTableHtsApiHandler
                              → UserTablesServiceImpl → UserTableHtsJdbcRepository (JPA)
```

**Verified.** The Java client for Iceberg engines, `OpenHouseCatalog`, implements
`SupportsNamespaces` but every namespace operation except `listNamespaces()` throws
`UnsupportedOperationException`
(`integrations/java/iceberg-1.5/openhouse-java-runtime/src/iceberg-version-specific/java/com/linkedin/openhouse/javaclient/OpenHouseCatalog.java:429-505`).
`listNamespaces()` calls the generated `DatabaseApi.getAllDatabasesV1()`.

**Verified.** The Tables Service does not expose any Iceberg REST Catalog endpoint. There is no
`/v1/{prefix}/namespaces` route, no `/v1/config`, and no `prefix` concept anywhere under
`services/tables`. The public API is OpenHouse-shaped: `/v1/databases/{databaseId}/tables/{tableId}`
and `/v0|v1|v2/databases` (`services/tables/.../controller/TablesController.java`,
`DatabasesController.java`).

**Verified.** `openhouse.databaseId` is already a reserved, server-stamped Iceberg table property
(`iceberg/openhouse/internalcatalog/.../CatalogConstants.java:17`), persisted in table metadata and
read back by the server on commit
(`OpenHouseInternalTableOperations.java:553-566`). A client holding a loaded Iceberg `Table` can
therefore obtain its database name from `table.properties()` without a server round trip.

**Verified.** `GetTableResponseBody` (the `LoadTable` payload) already carries a server-stamped,
read-only `config` map "following the Iceberg REST `LoadTableResponse.config` convention"
(`services/tables/.../api/spec/v0/response/GetTableResponseBody.java:108-118`), stamped in
`OpenHouseTablesApiHandler.java:48` by `ReadBridgeConfigResolver`. The Java client binds it to
`OpenHouseTableOperations#currentConfig()`. This is the natural seam for a later server-side
resolution of effective properties, which is why the client function proposed in PR three can keep
its signature when that happens.

---

## 2. Trace of the existing paths that matter

### 2.1 HTS persistence and schema lifecycle

**Verified.**

- JPA entities live in `com.linkedin.openhouse.housetables.model`; JDBC repositories in
  `repository.impl.jdbc`, enabled by `@EnableJpaRepositories(basePackages =
  "...repository.impl.jdbc")` on `config/db/DatabaseConfiguration.java`. The entity scan for tests
  is `housetables.model` (`e2e/SpringH2HtsApplication.java`).
- `spring.jpa.hibernate.ddl-auto=none`, `spring.sql.init.mode=always`,
  `spring.jpa.defer-datasource-initialization=true` (`src/main/resources/application.properties`).
  Hibernate never creates tables. `schema.sql` is a `CREATE TABLE IF NOT EXISTS` bootstrap that runs
  against whatever datasource is configured, including the docker MySQL recipes
  (`infra/recipes/docker-compose/common/mysql-services.yml` has no init script of its own).
- Production DDL is applied out of band and recorded in `services/housetables/ddl/`
  (`0000__baseline.sql`, `0001__add_entity_type_to_user_table_row.sql`). The baseline records the
  production table as `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci` with a
  functional index `((upper(database_id)), (upper(table_id)), ...)`.
- `DatabaseConfiguration` selects between an Iceberg-backed and a JDBC-backed repository bean for
  user tables and jobs. The services do not use that selection: `UserTablesServiceImpl` autowires
  `UserTableHtsJdbcRepository` directly, and `ToggleStatusesServiceImpl` autowires
  `ToggleStatusHtsJdbcRepository` directly. `TableToggleRule` has no Iceberg implementation at all.
  A new JDBC-only entity therefore follows the established toggle-rule precedent.
- Write concurrency on `UserTableRow` is optimistic: `@Version Long version`, and the service maps
  `ObjectOptimisticLockingFailureException | DataIntegrityViolationException` to
  `EntityConcurrentModificationException` (`UserTablesServiceImpl.java:168-180`), which the shared
  handler renders as 409.
- Non-scalar persisted values use a JPA converter (`EntityTypeConverter`) or a text column
  (`job_row.job_conf MEDIUMTEXT`).

### 2.2 HTS API layering and error mapping

**Verified.**

- Controllers delegate to a `HouseTablesApiHandler<K, V>` implementation, which runs a
  `HouseTablesApiValidator<K, V>` then a service, and wraps results in `EntityResponseBody<T>`
  (`api/handler/HouseTablesApiHandler.java`, `api/spec/response/EntityResponseBody.java`). Request
  bodies use `CreateUpdateEntityRequestBody<T>`. Handlers for an entity that does not support an
  operation throw `UnsupportedOperationException` (`OpenHouseToggleStatusesApiHandler`).
- All HTTP status mapping is centralised in
  `services/common/.../exception/handler/OpenHouseExceptionHandler.java`:
  `NoSuchEntityException` → 404, `AlreadyExistsException` → 409,
  `EntityConcurrentModificationException` → 409, `RequestValidationFailureException` → 400,
  `UnprocessableEntityException` → 422, generic `Exception` → 500. The handler is shared by HTS and
  the Tables Service.
- Identifier validation uses `ValidatorConstants.ALPHA_NUM_UNDERSCORE_REGEX = "^[a-zA-Z0-9_]+$"`.
- `UserHouseTablesOpenApiContractTest` pins the generated springdoc paths and status codes for the
  `UserTable` tag; a new tag needs either its own contract test or inclusion there.

### 2.3 Spec and client generation

**Verified.**

- Each service applies `openhouse.service-specgen-convention`, which runs the app and dumps
  `build/<service>/specs/<service>.json` via springdoc (`buildSrc/.../openhouse.service-specgen-convention.gradle`).
  `client/hts` and `client/tableclient` are generated from those specs by openapi-generator 5.3.0
  (`client/common/codegen.build.gradle`); both modules contain only a `build.gradle`.
- The generated API class is named from the controller `tags` value: `tags = {"UserTable"}` →
  `com.linkedin.openhouse.housetables.client.api.UserTableApi`; `{"ToggleStatus"}` →
  `ToggleStatusApi`; the Tables Service's `{"Database"}` →
  `com.linkedin.openhouse.tables.client.api.DatabaseApi`. Generated method names derive from
  controller method names, so a new controller method with a unique name and a single path yields
  a predictably named client method.
- `docs/specs/*.md` are rendered from the JSON with widdershins and committed by hand
  (`docs/specs/README.md`). They are documentation, not build inputs.

### 2.4 Tables Service → HTS client wiring

**Verified.**

- `MainApplicationConfig#provideApiInstance` builds `UserTableApi` and
  `#provideToggleApiInstance` builds `ToggleStatusApi`, both over one `ApiClient` pointed at
  `cluster.housetables.base-uri` (`services/tables/.../config/MainApplicationConfig.java:57-69`).
- `HouseTableRepositoryImpl` (in `iceberg/openhouse/internalcatalog`) wraps `UserTableApi` with a
  `RetryTemplate` and translates `WebClientResponseException` subclasses to
  `HouseTableNotFoundException` (404), `HouseTableConcurrentUpdateException` (409),
  `HouseTableCallerException` (other 4xx), and `HouseTableRepositoryStateUnknownException` (5xx,
  DNS timeout) (`HouseTableRepositoryImpl.java:191-225`).
- `ToggleStatusesRepositoryImpl` (`services/tables/.../toggle/repository/`) is the lighter
  precedent: a `@Repository` over `ToggleStatusApi` that blocks on the `Mono` and maps the response
  body. It is the model for a non-table HTS entity consumed by the Tables Service.
- Tables Service e2e tests do not run HTS. They replace `HouseTableRepository` and
  `ToggleStatusesRepository` with `@Primary` JPA interfaces backed by H2
  (`services/tables/src/test/.../e2e/h2/HouseTablesH2Repository.java`,
  `ToggleH2StatusesRepository.java`). The same two substitutes are duplicated into
  `tables-test-fixtures/tables-test-fixtures-iceberg-1.2/src/main/java/com/linkedin/openhouse/tablestest/`
  so `OpenHouseLocalServer` (used by Spark itests) can start without HTS. Any new HTS-backed
  repository the Tables Service autowires must get an H2 substitute in both places or those
  contexts will fail to start.

### 2.5 Table load on the client

**Verified.**

- `OpenHouseTableOperations#doRefresh` calls `TableApi.getTableV1(database, table)`, treats 404 and
  400 as "table absent", surfaces other errors, reads `tableLocation` and `config`, and loads
  metadata from storage (`integrations/java/iceberg-1.2/openhouse-java-runtime/.../OpenHouseTableOperations.java:129-169`).
  No database call is made. `LoadTable` must stay this way in this increment.
- The jobs scheduler's hand-written `TablesClient` (`apps/spark/.../jobs/client/TablesClient.java`)
  reads `GetTableResponseBody.getTableProperties()` directly for maintenance properties
  (`:450-475`). It is a consumer of actual, not effective, properties today.

---

## 3. Requirements, kept separate

### 3.1 Explicit requirements (from the design discussion)

E1. A database entity with UUID, database name, and key-value properties.
E2. UUID is canonical identity; name is a logical catalog-level mapping.
E3. Create, get, delete, and property-update operations.
E4. Database metadata independent of table metadata and table lifecycle.
E5. No implicit creation or loading of database entities during table operations.
E6. Deleting a database entity leaves tables untouched.
E7. Database properties are inherited by tables by default.
E8. Explicit table properties override inherited database properties.
E9. Actual table properties and effective table properties remain distinct.
E10. `LoadTable` performs no database lookup in this increment.
E11. `getEffectiveTableProperties(table)` is introduced in the Tables Client, does an explicit
     database lookup and merges, is adopted per consumer, and keeps its public interface if
     resolution later moves to information returned by `LoadTable`.

### 3.2 Requirements derived from the Iceberg REST Catalog specification

Checked against `apache/iceberg` `open-api/rest-catalog-open-api.yaml` on `main`
(fetched 2026-11-05). Only the operations needed for E3 are listed.

R1. **createNamespace**: request `{namespace: [string], properties?: {string: string}}`;
    200 with `{namespace, properties}`; 409 "Conflict - The namespace already exists".
R2. **loadNamespaceMetadata**: 200 with `{namespace, properties}`; 404 "Not Found - Namespace not
    found". `properties` is nullable; a server that does not support properties returns null.
R3. **dropNamespace**: 204 no content; 404 when absent; 409 "Not Empty - Namespace to delete is
    not empty".
R4. **updateProperties**: request `{removals?: [string], updates?: {string: string}}`;
    200 with `{updated: [string], removed: [string], missing?: [string]}` where `updated` and
    `removed` are required; "Properties that are not in the request are not modified or removed by
    this call"; 404 when absent; 422 "A property key was included in both `removals` and
    `updates`".
R5. **namespaceExists** (HEAD): 204 or 404, no body. Not required by E3; listed because it is one
    line once R2 exists.
R6. Error body: `IcebergErrorResponse = {error: {message, type, code, stack?}}`.
R7. Multipart namespaces encode with `%1F`. OpenHouse namespaces are single-level
    (`CatalogAndDbNameFromNamespace` rejects more than two levels and treats a two-level name as
    `catalog.db`), so this is informational only.

Note on R3 and E6. The Iceberg contract says a non-empty namespace cannot be dropped. E6 says
deleting the database entity leaves tables untouched. These are compatible only if "drop" in this
increment means "delete the database metadata entity" and does not require the namespace to be
empty. The plan follows E6 and records the deviation from R3 in section 7.

### 3.3 Inferred implementation requirements (validated against the repository)

| Hypothesis | Finding | Status |
|---|---|---|
| Server-side UUID generation and immutability | No UUID generation exists in HTS. The Tables Service generates table UUIDs in `TableUUIDGenerator` but only for tables. HTS must generate the database UUID at create and never accept one from the caller. | Confirmed as a requirement for E1/E2 |
| Database-name uniqueness and indexing | Production collation is `utf8mb4_0900_ai_ci` (case-insensitive) and HTS compares `databaseId` with `IgnoreCase` everywhere. H2 in `MODE=MySQL` is case-sensitive by default. Uniqueness must be enforced at the service with an ignore-case existence check and at the store with a unique constraint; the two disagree on case in H2 tests. | Confirmed, with a test-vs-production divergence to manage |
| Atomic sparse property updates | `UserTableRow` already uses `@Version` optimistic locking with 409 on conflict. A single-row read-modify-write under `@Transactional` with `@Version` gives atomicity for a JSON-text property column. | Confirmed via existing pattern |
| Persistence schema and migration | Additive: new `CREATE TABLE IF NOT EXISTS` in `schema.sql` for bootstrap, plus `ddl/0002__*.sql` recording the production statement. No ALTER on existing tables. | Confirmed, low risk |
| Error translation HTS → Tables Service | HTS returns the shared `ErrorResponseBody`; the Tables Service sees `WebClientResponseException.NotFound/Conflict/...` and must re-throw typed exceptions the shared handler maps to the same codes. Precedent in `HouseTableRepositoryImpl#handleHtsHttpError` and `ViewFailureTranslator`. | Confirmed |
| Client handling of missing database | The client already treats a 404 from `getTableV1` as "absent" in `doRefresh`. For E6, a 404 on the database lookup must yield "no inherited properties", not an error. | Confirmed as a requirement |
| Reuse of generated clients and conventions | Both HTS and Tables clients regenerate automatically from controller annotations; a new `tags = {"Database"}` controller in HTS yields `housetables.client.api.DatabaseApi` with no build changes. | Confirmed |

---

## 4. PR sequence

The three-PR structure holds. Nothing in the repository argues for merging or splitting them: PR
one is self-contained inside one service and one schema; PR two depends only on the generated HTS
client that PR one produces; PR three depends only on the generated Tables client that PR two
produces. Each is independently reviewable and independently deployable, and the first two change
no existing behaviour.

### PR one: HouseTables Service

**Scope.** Database entity, persistence, indices, internal CRUD API, tests. No change to any
existing table endpoint, entity, or query.

**Responsibilities.**

1. Entity `DatabaseRow` in `housetables.model` with `@Id String databaseUuid`, `String databaseId`
   (the name), `@Version Long version`, `Long creationTime`, and `Map<String,String> properties`
   persisted through a JPA `AttributeConverter` to a JSON text column.
2. Repository `DatabaseHtsJdbcRepository extends HtsRepository<DatabaseRow, String>` in
   `repository.impl.jdbc` with `Optional<DatabaseRow> findByDatabaseIdIgnoreCase(String)` and
   `boolean existsByDatabaseIdIgnoreCase(String)`. JDBC-only, like `ToggleStatusHtsJdbcRepository`.
   It is picked up by the existing `@EnableJpaRepositories` base package; `DatabaseConfiguration`
   needs no edit.
3. Service `DatabasesService` / `DatabasesServiceImpl` with create (generate UUID, 409 if name
   exists ignoring case), get by name, delete by name, and `updateProperties(name, updates,
   removals)` implemented as one `@Transactional` read-modify-write returning the applied
   `updated`, `removed`, and `missing` key sets. A 422 (`UnprocessableEntityException`) when a key
   is in both sets, checked before any read.
4. API: `api/spec/model/Database.java` (uuid read-only, name, properties),
   `DatabaseKey.java` (name), request/response bodies for the sparse update, a
   `DatabasesApiHandler` + `OpenHouseDatabasesApiHandler`, a `HouseTablesApiValidator` impl, and a
   `DatabasesController` under `/hts/databases` with `tags = {"Database"}`.
5. `schema.sql`: `CREATE TABLE IF NOT EXISTS database_row (...)`. `ddl/0002__create_database_row.sql`
   recording the production statement with engine, charset, collation, and a unique functional
   index on `upper(database_id)` mirroring `idx_user_table_upper_db_table`.
6. Tests: repository (`e2e/database/DatabaseRepositoryTest`), service, controller via MockMvc, a
   validator unit test, and an OpenAPI contract test for the new tag.

**Why each piece is necessary.** E1 and E2 require the entity and its two identities. E3 requires
the four operations. E4 and E5 are satisfied by adding a separate table and touching nothing in
`UserTableRow`, `UserTablesServiceImpl`, or `UserHouseTablesController`. The repository, service,
handler, validator, and controller layers are not optional: the Tables Service can only reach HTS
through the generated client, which is generated from a controller, and the shared exception
handler only maps exceptions the service throws.

**Decisions.**

- *Properties as a JSON text column, not a child table.* Reason: one row means one optimistic lock
  and one statement for a sparse update, which is the atomicity hypothesis resolved with an
  existing pattern (`@Version` + `EntityConcurrentModificationException`). `job_conf MEDIUMTEXT` is
  the precedent for a serialized blob column. A child table would make `updateProperties` a
  multi-row transaction and bring the name-uniqueness and lock questions back for each row.
- *Primary key is the UUID; the name has a unique constraint.* Reason: E2. A primary key on the name
  would make the UUID decorative.
- *Lookups by name, ignore case.* Reason: every existing HTS lookup on `databaseId` is ignore-case
  and production collation is case-insensitive. A case-sensitive database lookup would let
  `Db1` and `db1` be two databases while the table store treats them as one.
- *HTS path is `/hts/databases`, OpenHouse-shaped, not Iceberg-shaped.* Reason: HTS is internal.
  Its contract is the `EntityResponseBody` convention that every other HTS entity uses, and the
  Iceberg shape belongs on the public Tables Service surface.
- *No Iceberg-backed repository for the new entity.* Reason: the services bypass the
  Iceberg/JDBC switch already (section 2.1), and `TableToggleRule` set the precedent.

**Files and symbols likely to change or appear (all under `services/housetables/`).**

| Path | Change |
|---|---|
| `src/main/java/.../model/DatabaseRow.java` | new entity |
| `src/main/java/.../model/PropertiesMapConverter.java` | new `AttributeConverter<Map<String,String>, String>` (name indicative) |
| `src/main/java/.../repository/impl/jdbc/DatabaseHtsJdbcRepository.java` | new |
| `src/main/java/.../services/DatabasesService.java`, `DatabasesServiceImpl.java` | new |
| `src/main/java/.../dto/model/DatabaseDto.java`, `dto/mapper/DatabasesMapper.java` | new, following `UserTableDto`/`UserTablesMapper` |
| `src/main/java/.../api/spec/model/Database.java`, `DatabaseKey.java` | new |
| `src/main/java/.../api/spec/request/UpdateDatabasePropertiesRequestBody.java` | new (`updates`, `removals`) |
| `src/main/java/.../api/spec/response/UpdateDatabasePropertiesResponseBody.java` | new (`updated`, `removed`, `missing`) |
| `src/main/java/.../api/handler/DatabasesApiHandler.java`, `OpenHouseDatabasesApiHandler.java` | new |
| `src/main/java/.../api/validator/impl/OpenHouseDatabasesHtsApiValidator.java` | new |
| `src/main/java/.../controller/DatabasesController.java` | new, `/hts/databases`, `tags = {"Database"}` |
| `src/main/resources/schema.sql` | add `database_row` |
| `ddl/0002__create_database_row.sql` | new record of production DDL |
| `src/test/java/.../e2e/database/*`, `mock/api/*`, `mock/controller/*` | new tests |
| `docs/specs/housetables.md` | regenerate (documentation) |

**Behavioral acceptance criteria.**

- `PUT`/`POST /hts/databases` with a new name returns 201 and a server-generated UUID; the same
  name again (any case) returns 409; a request body carrying a UUID is rejected 400 or the UUID is
  ignored, never stored.
- `GET /hts/databases?databaseId=X` returns 200 with uuid, name as stored, and properties; an
  unknown name returns 404 with the standard `ErrorResponseBody`.
- `DELETE /hts/databases?databaseId=X` returns 204; a second delete returns 404; rows in
  `user_table_row` with `database_id = X` are unchanged and `GET /hts/tables` on them still
  succeeds.
- Property update with `updates={a:1,b:2}` then `updates={a:9}, removals=[b,c]` leaves exactly
  `{a:9}` and returns `updated=[a]`, `removed=[b]`, `missing=[c]`; a key present in both sets
  returns 422 and changes nothing; the version column increments once per successful update.
- Two concurrent updates to the same database: one succeeds, the other receives 409.
- Creating, reading, updating, or deleting a database never issues a query against
  `user_table_row`, and no existing `UserHouseTablesController` test changes.
- `UserHouseTablesOpenApiContractTest` still passes unchanged; a new contract test pins the
  `Database` tag's paths and codes.

### PR two: Tables Service

**Scope.** Public database CRUD with Iceberg namespace semantics, service and repository layers,
HTS client wiring, test substitutes, API spec regeneration. No change to `getTable`,
`createTable`, `updateTable`, `deleteTable`, or any table path.

**Responsibilities.**

1. A `DatabaseEntityRepository` abstraction in `services/tables/.../repository/` (name indicative)
   with `findByName`, `create`, `deleteByName`, `updateProperties`, and an HTS-backed
   `DatabaseEntityRepositoryImpl` over the generated `housetables.client.api.DatabaseApi`, created by
   a new bean next to `provideToggleApiInstance` in `MainApplicationConfig`. Error translation
   mirrors `HouseTableRepositoryImpl#handleHtsHttpError`: 404 → `NoSuchEntityException("Database",
   name)`, 409 → `AlreadyExistsException("Database", name)` or
   `EntityConcurrentModificationException`, 422 → `UnprocessableEntityException`, 5xx →
   a retryable state-unknown exception.
2. `DatabasesService` gains `createDatabase(name, properties, principal)`,
   `getDatabase(name, principal)`, `deleteDatabase(name, principal)`,
   `updateDatabaseProperties(name, updates, removals, principal)`. The existing list and ACL
   methods are untouched.
3. `DatabasesApiHandler` / `OpenHouseDatabasesApiHandler` and `OpenHouseDatabasesApiValidator`
   gain matching methods. The validator rejects a key in both `updates` and `removals` with 422
   before the service is called, and validates the name with the existing
   `ApiValidatorUtil.validateIdentifier`.
4. `DatabasesController` gains routes under the existing `/v1/databases` family with
   `tags = {"Database"}` so they land in the already-consumed `DatabaseApi`:
   - `POST /v1/databases` body `{databaseId, properties?}` → 201 `{databaseId, databaseUUID,
     properties}`; 409 on conflict.
   - `GET /v1/databases/{databaseId}` → 200 `{databaseId, databaseUUID, properties}`; 404.
   - `DELETE /v1/databases/{databaseId}` → 204; 404.
   - `POST /v1/databases/{databaseId}/properties` body `{updates?, removals?}` → 200 `{updated,
     removed, missing}`; 404; 422.
5. H2 substitutes: `DatabaseEntityH2Repository` as a `@Primary` JPA interface in
   `services/tables/src/test/.../e2e/h2/` and in
   `tables-test-fixtures/tables-test-fixtures-iceberg-1.2/src/main/java/com/linkedin/openhouse/tablestest/`,
   plus the entity scan additions each application class needs.
6. Tests: controller MockMvc tests for every status in the acceptance criteria, service tests,
   validator tests, a repository translation test with a mocked `DatabaseApi`, and an assertion
   that the `TablesController` paths and `GetTableResponseBody` schema in the generated spec are
   byte-identical before and after.

**Why each piece is necessary.** E3 on the public surface requires the controller; the Tables
Service convention (section 2.4) requires handler, validator, service, and a repository that owns
HTS error translation. The repository abstraction is not for future extensibility: it exists
because the Tables Service test harness and `OpenHouseLocalServer` have no HTS and need a swappable
bean (section 2.4), exactly as `HouseTableRepository` and `ToggleStatusesRepository` do today.

**Decisions.**

- *Iceberg semantics on OpenHouse paths.* The request bodies, response bodies, sparse-update
  rules, and status codes follow R1 to R4. The paths stay under `/v1/databases`. Reason: the Tables
  Service has no REST catalog `prefix`, `/v1/config`, or `%1F` namespace handling (section 1), and
  the two existing consumers of database routes (`OpenHouseCatalog.listNamespaces` and the jobs
  `TablesClient`) already hold a `DatabaseApi` built from the `Database` tag. Mounting a parallel
  `/v1/{prefix}/namespaces` tree would be the start of a REST catalog compatibility layer, which
  the brief rules out of scope. Section 8 records this as the one open question that changes the
  URL contract.
- *Field name `databaseId` for the name, `databaseUUID` for the identity.* Reason: `databaseId`
  is what every existing Tables API, path variable, and `DatabaseDto` call the name; `tableUUID` is
  the established casing for the table analogue in `GetTableResponseBody`.
- *Error body stays `ErrorResponseBody`, not `IcebergErrorResponse` (R6).* Reason: a single
  `@ControllerAdvice` renders every error for both services. A per-route Iceberg error envelope
  would need a second advice or a controller-local handler. Status codes match the Iceberg
  contract; body shape does not. Recorded in section 7.
- *Delete does not check emptiness (R3 vs E6).* Reason: E6 is explicit. The delete removes only
  the metadata row.
- *Existing `GET /v1/databases` list is left as it is.* It continues to derive databases from
  table rows. Making it union with `database_row` would change a production response and is not
  needed for the stated goal.

**Files and symbols likely to change or appear.**

| Path | Change |
|---|---|
| `services/tables/src/main/java/.../config/MainApplicationConfig.java` | new bean `provideDatabaseApiInstance()` returning `housetables.client.api.DatabaseApi` |
| `services/tables/src/main/java/.../repository/DatabaseEntityRepository.java`, `impl/DatabaseEntityRepositoryImpl.java` | new |
| `services/tables/src/main/java/.../model/DatabaseDto.java` | add `databaseUUID`, `properties` (it is an `@Entity` used by the H2 substitute; adding columns is additive) |
| `services/tables/src/main/java/.../services/DatabasesService.java`, `DatabasesServiceImpl.java` | add four methods |
| `services/tables/src/main/java/.../api/handler/DatabasesApiHandler.java`, `impl/OpenHouseDatabasesApiHandler.java` | add four methods |
| `services/tables/src/main/java/.../api/validator/DatabasesApiValidator.java`, `impl/OpenHouseDatabasesApiValidator.java` | add validation incl. 422 rule |
| `services/tables/src/main/java/.../api/spec/v0/request/CreateDatabaseRequestBody.java`, `UpdateDatabasePropertiesRequestBody.java` | new |
| `services/tables/src/main/java/.../api/spec/v0/response/GetDatabaseResponseBody.java` | add `databaseUUID`, `properties` (nullable, read-only); existing list callers see additional fields only |
| `services/tables/src/main/java/.../api/spec/v0/response/UpdateDatabasePropertiesResponseBody.java` | new |
| `services/tables/src/main/java/.../controller/DatabasesController.java` | add four routes |
| `services/tables/src/main/java/.../dto/mapper/DatabasesMapper.java` | mappings for new fields |
| `services/tables/src/main/java/.../audit/TableAuditAspect.java` | optional pointcuts for the new handler methods, following the `getAllDatabases` precedent at `:471-495` |
| `services/tables/src/test/java/.../e2e/h2/DatabaseEntityH2Repository.java`, `SpringH2Application.java` | new substitute; entity scan |
| `tables-test-fixtures/.../tablestest/DatabaseEntityH2Repository.java`, `SpringH2TestApplication.java` | same, for `OpenHouseLocalServer` |
| `services/tables/src/test/java/.../e2e/h2/DatabasesControllerTest.java`, `DatabasesServiceTest.java`, `mock/api/DatabasesValidatorTest.java` | extend |
| `services/tables/build.gradle` | none expected; `client:hts` is already a dependency |
| `docs/specs/catalog.md` | regenerate (documentation) |

**Behavioral acceptance criteria.**

- `POST /v1/databases {databaseId: "d1", properties: {k: v}}` returns 201 with a non-empty
  `databaseUUID`; repeating it returns 409; `databaseId` failing the identifier regex returns 400.
- `GET /v1/databases/d1` returns the stored properties; `GET /v1/databases/D1` returns the same
  row; `GET /v1/databases/nope` returns 404.
- `DELETE /v1/databases/d1` returns 204, then 404. A table created in `d1` before the delete still
  loads via `GET /v1/databases/d1/tables/t1` with identical `tableProperties`.
- `POST /v1/databases/d1/properties` applies R4 exactly, including `missing`; a key in both maps
  returns 422 and the stored properties are unchanged.
- Creating a table in a database with no `database_row` succeeds and creates no `database_row`
  (E5). Creating a `database_row` does not alter any existing table's `tableProperties` (E4).
- A spec diff before and after the PR shows changes only under paths starting with
  `/v1/databases` that carry no `/tables` segment, plus the `GetDatabaseResponseBody` schema.
- With HTS unreachable, the new routes return 503 and `GET /v1/databases/d1/tables/t1` behaves as
  it did before the PR.

### PR three: Tables Client

**Scope.** `getEffectiveTableProperties(table)`: explicit database look-aside, precedence merge,
absent-database handling, unit tests. No change to `doRefresh`, `doCommit`, or any catalog
method's existing behaviour.

**Responsibilities.**

1. A public method on `OpenHouseCatalog`:
   `Map<String, String> getEffectiveTableProperties(Table table)`. It reads the database name from
   `table.properties().get("openhouse.databaseId")`, falling back to
   `TableIdentifier` parsing of `table.name()` when the key is absent; calls
   `databaseApi.getDatabaseV1(databaseId)` (the generated name of the PR two `GET`); treats
   `WebClientResponseException.NotFound` as an empty property map; surfaces every other error
   through the same `WebClientResponseWithMessageException` /
   `WebClientRequestWithMessageException` wrapping `doRefresh` uses; and returns an unmodifiable
   map equal to `database.properties` overlaid by `table.properties()`.
2. A package-private pure merge helper so precedence is unit-tested without I/O.
3. Javadoc on the method stating that it performs one HTTP request to the Tables Service per call,
   does not cache, never mutates the `Table`, and that `table.properties()` remains the actual
   properties.
4. Tests in `integrations/java/iceberg-1.2/openhouse-java-runtime/src/test` using a Mockito-mocked
   `DatabaseApi` (the pattern `apps/spark/.../TablesClientTest` uses for `TableApi` and
   `DatabaseApi`): inherit, override, database absent, database 5xx, table missing the
   `openhouse.databaseId` key, and that a database key colliding with a server-stamped key
   (`openhouse.tableUUID`, `policies`) never wins.

**Why this placement.** `client/tableclient` is 100% generated and has no Iceberg dependency, so it
cannot take an Iceberg `Table` and has no hand-written source tree. `OpenHouseCatalog` already owns
a `DatabaseApi` instance, is the only hand-written class that both receives Iceberg `Table` objects
and holds the HTTP client, and is compiled into both the Iceberg 1.2 and 1.5 runtimes from one
source tree (`iceberg-1.5/openhouse-java-runtime/build.gradle:35-45`). Putting the method here means
one implementation serves every Spark and Java consumer that already has an `OpenHouseCatalog`.
The jobs scheduler's `TablesClient` holds generated response bodies rather than `Table` objects; its
adoption is a separate consumer change the brief excludes from this increment.

**Interface stability.** When resolution later moves server-side, the `LoadTable` payload can
carry the resolved map in the existing read-only `config` field or a sibling field, and this method
becomes a read of `OpenHouseTableOperations#currentConfig()` with no signature change. The method
takes a `Table` and returns a `Map<String, String>`; nothing in the signature names the transport.

**Files and symbols likely to change.**

| Path | Change |
|---|---|
| `integrations/java/iceberg-1.5/openhouse-java-runtime/src/iceberg-version-specific/java/.../OpenHouseCatalog.java` | add `getEffectiveTableProperties(Table)` |
| `integrations/java/iceberg-1.2/openhouse-java-runtime/src/iceberg-version-specific/java/.../OpenHouseCatalog.java` | same method; the two files are separate copies (verified by the sourceSets exclusion) |
| `integrations/java/iceberg-1.2/openhouse-java-runtime/src/main/java/.../EffectiveTablePropertiesResolver.java` (name indicative) | new, package-private, shared by both runtimes |
| `integrations/java/iceberg-1.2/openhouse-java-runtime/src/test/java/...` | new tests |
| `integrations/java/iceberg-1.2/openhouse-java-runtime/build.gradle`, `iceberg-1.5/...` | none expected; `client:tableclient` is already `compileOnly` and packaged |

**Behavioral acceptance criteria.**

- Given table `{a:1, openhouse.tableUUID:u}` and database `{a:9, b:2, openhouse.tableUUID:x}`, the
  result is `{a:1, b:2, openhouse.tableUUID:u}` and `table.properties()` is unchanged.
- Database 404 → result equals `table.properties()` exactly and no exception.
- Database 500 or connection failure → the wrapped exception types `doRefresh` throws today.
- Exactly one `DatabaseApi` call per invocation; zero calls to `TableApi`; `doRefresh` issues no
  database call before or after the PR (asserted with a mock that fails on any `DatabaseApi`
  invocation during `loadTable`).
- The returned map is unmodifiable.

---

## 5. Compatibility and operational risks

1. **Production DDL is manual.** `schema.sql` creates the table in H2 and in the docker MySQL
   recipes, but production receives DDL out of band (`ddl/0000__baseline.sql` header). PR one must
   ship `ddl/0002__*.sql` and the HTS deploy must follow the DDL, or every new endpoint fails with
   a SQL error while table endpoints keep working. No existing table is altered, so the DDL is
   `ALGORITHM`-trivial.
2. **Case sensitivity differs between H2 and MySQL.** A unique constraint on `database_id` is
   case-insensitive under `utf8mb4_0900_ai_ci` and case-sensitive in H2. Tests that assert 409 for
   `D1` after `d1` will pass only if the service does the ignore-case existence check before
   insert. Keep that check; do not rely on the constraint alone.
3. **Generated client churn.** Every new controller method changes `housetables.json` /
   `tables.json` and regenerates `DatabaseApi`. Method names in generated clients follow
   controller method names; renaming a controller method later is a client-breaking change.
   Choose names once.
4. **Test contexts that autowire the Tables Service.** Adding an HTS-backed repository bean to the
   Tables Service breaks `SpringH2Application` (tables tests) and `SpringH2TestApplication`
   (tables-test-fixtures, used by Spark itests in `integrations/spark/*`) until H2 substitutes
   exist in both. PR two must include both or those suites fail at context start.
5. **Audit coverage.** `TableAuditAspect` audits `DatabasesApiHandler.getAllDatabases` and the ACL
   methods by explicit pointcut. New handler methods are unaudited unless pointcuts are added.
6. **`GetDatabaseResponseBody` grows.** `GET /v1/databases` returns this body today; adding
   nullable read-only fields is additive for the generated clients (jobs `TablesClient`,
   `OpenHouseCatalog.listNamespaces`), which ignore unknown fields. Confirm no consumer asserts
   strict JSON equality on it outside `DatabasesControllerTest` (which uses `content().json`,
   lenient).
7. **Double lookups for client consumers.** Each `getEffectiveTableProperties` call is one HTTP
   request. A consumer calling it per table in a listing loop multiplies Tables Service load by the
   table count. The Javadoc must say so; a cache is out of scope.
8. **Authorization gap.** The new write routes need a privilege. Nothing in `Privileges` names
   database create, drop, or alter. Shipping them unguarded would let any authenticated principal
   create or delete database metadata. See section 8.

---

## 6. Unresolved architectural questions

Only questions whose answer changes the implementation and does not follow from the stated
requirements.

1. **Which privilege gates database create, delete, and property update?** `Privileges` has
   `UPDATE_ACL`, `SYSTEM_ADMIN`, and table-scoped privileges only. `AuthorizationHandler`
   supports database-scoped decisions (`checkAccessDecision(principal, DatabaseDto, privilege)`).
   Options: reuse `SYSTEM_ADMIN` for all three writes in this increment, or add
   `CREATE_DATABASE` / `ALTER_DATABASE` to `Privileges` and the deployed OPA policies. The first
   is implementable inside PR two; the second needs a policy change outside this repository.
   Recommendation: `SYSTEM_ADMIN` for writes, authenticated for reads, in this increment.
2. **Public path shape.** This plan mounts Iceberg-shaped operations under `/v1/databases` for the
   reasons in PR two. If the intent is a literal `/v1/{prefix}/namespaces/...` surface callable by
   an unmodified Iceberg `RESTCatalog`, that requires `/v1/config`, prefix routing, `%1F`
   decoding, and the `IcebergErrorResponse` envelope, which is a REST catalog compatibility
   project rather than this increment. Confirm the former is acceptable.
3. **Error envelope.** Tied to question 2: status codes follow the Iceberg contract; the body stays
   OpenHouse's `ErrorResponseBody`. Confirm.

Everything else in the brief resolves to a decision above with repository support.
