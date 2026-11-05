# Database properties: implementation plan

Status: plan only. Nothing in this document is implemented.

This plan introduces a database-level metadata entity (UUID, name, key-value properties) into
OpenHouse and lets a client resolve a table's *effective* properties by looking the database up
explicitly. It is grounded in `linkedin/openhouse` at commit `e3db001` (2026-11-03). Line numbers
below refer to that commit. Findings marked **verified** were read from the code; findings marked
**assumed** were not confirmed and should be checked during implementation.

The plan is delivered against `mkuchenbecker/openhouse`, whose `main` (`59101d3`, 2026-08-27) is a
squashed sync of upstream that predates several precedents this plan relies on: the `entity_type`
discriminator and `services/housetables/ddl/`, the `ViewExceptionHandler` controller-scoped advice,
the `tableExists` endpoint, and `docs/design/replication-simplification.md`. The fork should be
re-synced with upstream before PR one starts. See section 7.

---

## 1. Architectural summary

**verified**

- OpenHouse has no persisted database entity. A "database" exists only as the set of distinct
  `databaseId` values on user-table rows. HTS derives the list with
  `SELECT DISTINCT databaseId FROM UserTableRow`
  (`services/housetables/.../repository/impl/jdbc/UserTableHtsJdbcRepository.java:88-97`, used by
  `UserTablesServiceImpl.listDatabases`, lines 343-363). The Tables Service derives it from
  `OpenHouseInternalCatalog.listTables(Namespace.empty())`
  (`iceberg/openhouse/internalcatalog/.../OpenHouseInternalCatalog.java:108-120`), which
  `DatabasesServiceImpl.getAllDatabases` turns into `DatabaseDto{databaseId, clusterId}`
  (`services/tables/.../services/DatabasesServiceImpl.java:31-51`).
- The Tables Service exposes four database endpoints, none of which reads or writes properties:
  `GET /v0|v1/databases`, `GET /v2/databases` (paged), and `GET`/`PATCH .../{databaseId}/aclPolicies`
  (`services/tables/.../controller/DatabasesController.java:37-121`). `GetDatabaseResponseBody` has
  only `databaseId` and `clusterId`.
- HTS is a Spring Data JPA service (Hibernate, `javax.persistence`) with four entities
  (`UserTableRow`, `SoftDeletedUserTableRow`, `JobRow`, `TableToggleRule`). Schema is bootstrapped
  by `src/main/resources/schema.sql` (`CREATE TABLE IF NOT EXISTS`) under
  `spring.jpa.hibernate.ddl-auto=none` and `spring.sql.init.mode=always`
  (`services/housetables/src/main/resources/application.properties:8-10`). Production MySQL DDL is
  applied out of band and recorded in `services/housetables/ddl/NNNN__*.sql`
  (`ddl/0000__baseline.sql:1-16`). There is no Flyway or Liquibase. No HTS entity stores a map or JSON
  column today; the closest is `job_row.job_conf MEDIUMTEXT`.
- Both services generate their OpenAPI spec from springdoc annotations at build time
  (`buildSrc/src/main/groovy/openhouse.service-specgen-convention.gradle`), and the clients in
  `client/hts` and `client/tableclient` are regenerated from those specs by openapi-generator 5.3.0
  with the `webclient` library (`client/common/codegen.build.gradle`, `client/common/client_codegen.sh`).
  `client/tableclient` contains only `build.gradle`; there is no hand-written source. Generated API
  classes are named by OpenAPI tag, so the `"Database"` tag yields
  `com.linkedin.openhouse.tables.client.api.DatabaseApi`. The Tables Service rewrites operation IDs to
  `methodName + V<n>` from the first path segment (`MainApplicationConfig.springDocOperationIdCustomizer`,
  `services/tables/.../config/MainApplicationConfig.java:173-192`).
- The Iceberg-facing client catalog is `com.linkedin.openhouse.javaclient.OpenHouseCatalog`, one copy
  per Iceberg version under `integrations/java/iceberg-1.2/.../src/iceberg-version-specific/java/`
  and `integrations/java/iceberg-1.5/...`, with shared code in
  `integrations/java/iceberg-1.2/openhouse-java-runtime/src/main/java/com/linkedin/openhouse/javaclient/`
  (inherited by 1.5 via `iceberg-1.5/.../build.gradle:28-45`). It implements `SupportsNamespaces`
  but every namespace method except `listNamespaces()` throws `UnsupportedOperationException`
  (iceberg-1.2 `OpenHouseCatalog.java:383-459`; iceberg-1.5 `OpenHouseCatalog.java:429-504`).
- `openhouse.databaseId`, `openhouse.tableId`, `openhouse.tableUUID` and the other `HouseTable`
  fields are stamped into Iceberg table properties by the server
  (`services/tables/.../repository/impl/OpenHouseInternalRepositoryImpl.java:600-611`) and are
  therefore present in `Table.properties()` on any client-loaded table
  (`apps/spark/.../jobs/util/TableStatsCollectorUtil.java:266-279` reads them that way). An attempt to
  remove them (#799) was reverted (#851); `docs/design/replication-simplification.md` still intends
  to remove them eventually.

**Prior art on the fork.** `mkuchenbecker/openhouse` already holds a broader design
([PR 56](https://github.com/mkuchenbecker/openhouse/pull/56), `docs/development/database-abstraction-api-design.md`)
and an implementation of six Iceberg REST namespace routes
([PR 57](https://github.com/mkuchenbecker/openhouse/pull/57), branch `claude/namespace-impl-v2`,
59 files, about 11.5k lines). That work differs from this brief in ways the brief rules out: it keys
`database_row` by name with no UUID, treats a database as existing when any table names it
(derived fallback), registers a namespace row on every table create
([PR 59](https://github.com/mkuchenbecker/openhouse/pull/59)), ships a `/v1/config` facade behind a
feature flag, and checks in the full Iceberg OpenAPI YAML to codegen Spring interfaces. This plan
does not build on that branch. Pieces of it worth lifting as reference when implementing are named
in section 5 (`PropertiesConverter`, `NamespacePropertiesValidator`, `IcebergRestExceptionHandler`,
`HouseNamespaceRepositoryImpl`, the `/hts/databases` controller shape). Two rulings recorded in PR 56
section 5.11 are consistent with this plan and are adopted: `dropNamespace` on a populated database
is `409 NamespaceNotEmptyException`, never a cascade, and a database may outlive its last table.

---

## 2. Existing request and persistence paths

### 2.1 Table read (`GET /v1/databases/{db}/tables/{tbl}`)

**verified**

1. `TablesController.getTable` (`services/tables/.../controller/TablesController.java:56-72`) reads
   the principal via `AuthenticationUtils.extractAuthenticatedUserPrincipal()` and calls
   `TablesApiHandler.getTable`.
2. `OpenHouseTablesApiHandler.getTable` (`api/handler/impl/OpenHouseTablesApiHandler.java:52-60`)
   runs `OpenHouseTablesApiValidator.validateGetTable`, then `TablesService.getTable`, then
   `TablesMapper.toGetTableResponseBody`, then overlays `config` via `ReadBridgeConfigResolver`.
3. `TablesServiceImpl.getTable` (`services/TablesServiceImpl.java:74-88`) calls
   `OpenHouseInternalRepository.findById`, then `AuthorizationUtils.checkTablePrivilege(GET_TABLE_METADATA)`.
4. `OpenHouseInternalRepositoryImpl.findById` (`repository/impl/OpenHouseInternalRepositoryImpl.java:839-853`)
   calls `OpenHouseInternalCatalog.loadTable(TableIdentifier.of(db, tbl))`.
5. `OpenHouseInternalTableOperations.doRefresh` (`internalcatalog/.../OpenHouseInternalTableOperations.java:167+`)
   calls `HouseTableRepository.findById`.
6. `HouseTableRepositoryImpl.findById` (`internalcatalog/.../repository/HouseTableRepositoryImpl.java:173-188`)
   calls the generated `UserTableApi.getUserTable(db, tbl)` over WebClient with a 30 s timeout and
   a retry template (`getHtsRetryTemplate`, lines 86-101; `HtsRetryUtils`: 3 attempts, 2 s base,
   x2, 30 s cap). HTTP status is translated by `handleHtsHttpError` (lines 196-225): 404 →
   `HouseTableNotFoundException`, 409 → `HouseTableConcurrentUpdateException`, 400/401/403/429 →
   `HouseTableCallerException`, 5xx → `HouseTableRepositoryStateUnknownException`.
7. The HTS `ApiClient` is built in `MainApplicationConfig.getHtsConfiguredApiClient`
   (`services/tables/.../config/MainApplicationConfig.java:77-98`) from
   `cluster.housetables.base-uri`. `UserTableApi` and `ToggleStatusApi` are exposed as beans
   (lines 57-69); `provideToggleApiInstance` is the pattern for a third HTS API bean.
8. HTS: `UserHouseTablesController` (`GET /hts/tables`,
   `services/housetables/.../controller/UserHouseTablesController.java:67-80`) →
   `OpenHouseUserTableHtsApiHandler` (validate, service, map) → `UserTablesServiceImpl.getUserTable`
   (lines 56-70, case-insensitive lookup, `NoSuchUserTableException` → 404 via the shared
   `OpenHouseExceptionHandler` in `services/common`).
9. `InternalRepositoryUtils.convertToTableDto` sets `tableProperties` to the full Iceberg
   properties map, including the `openhouse.*` keys and `policies`
   (`repository/impl/InternalRepositoryUtils.java:~97-140`).

### 2.2 Table write (`PUT`) and concurrency

**verified**

- Writes go through `OpenHouseInternalTableOperations.doCommit` → `HouseTableRepositoryImpl.save`
  → `UserTableApi.putUserTable` (no retry, 60 s timeout).
- HTS `PUT /hts/tables` is an upsert. `UserTableVersionMapper.toVersion`
  (`dto/mapper/UserTableVersionMapper.java:20-47`) compares the client's `tableVersion` to the
  existing row's `metadataLocation`, then `UserTablesServiceImpl.persistTypedEntity` (lines 146-184)
  relies on JPA `@Version` and rethrows `ObjectOptimisticLockingFailureException` /
  `DataIntegrityViolationException` as `EntityConcurrentModificationException` (409). The handler
  returns 201 for a new row and 200 for an overwrite (`OpenHouseUserTableHtsApiHandler.java:97-107`).
- On the Tables side, HTS 409 becomes `CommitFailedException` → `EntityConcurrentModificationException`
  (409); HTS 5xx becomes `CommitStateUnknownException` → `OpenHouseCommitStateUnknownException` (503)
  (`TablesServiceImpl.saveTableDto`, lines 205-232).

### 2.3 Client load

**verified**

- `OpenHouseCatalog` does not override `loadTable`; `BaseMetastoreCatalog.loadTable` calls
  `newTableOps(id).current()`, which is `OpenHouseTableOperations.doRefresh`
  (`integrations/java/iceberg-1.2/.../javaclient/OpenHouseTableOperations.java:129-169`). It calls
  `TableApi.getTableV1(namespace, name)`, treats 404 and 400 as "no table", and then reads the
  metadata JSON from storage. Table properties on the client therefore come from the metadata file,
  not from `GetTableResponseBody.tableProperties`.
- `OpenHouseTableOperations` holds the `TableIdentifier` it was built with
  (`@Getter(AccessLevel.PROTECTED) private TableIdentifier tableIdentifier`, line 58-59).

### 2.4 Authorization

**verified**

- `@Secured` is effectively a no-op: `AuthorizationInterceptor` logs and always returns
  `AuthorizationDecision(true)` (`services/tables/.../authorization/AuthorizationInterceptor.java:87`).
- Real checks are in the service layer through `AuthorizationUtils` and the `AuthorizationHandler`
  SPI. Database-scoped checks exist: `AuthorizationUtils.checkDatabasePrivilege(databaseId, principal, privilege)`
  (`utils/AuthorizationUtils.java:167-176`) builds a `DatabaseDto` and calls
  `AuthorizationHandler.checkAccessDecision(principal, DatabaseDto, Privileges)`
  (`authorization/AuthorizationHandler.java:32`). `Privileges` has no database-specific entries
  (`authorization/Privileges.java:11-20`). The default `OpaAuthorizationHandler` allows everything when
  no OPA base URI is configured.
- `DatabasesServiceImpl.getDatabaseAclPolicies` and `getAllDatabases` perform no privilege check;
  `updateDatabaseAclPolicies` requires `UPDATE_ACL` on the database (lines 53-80).

### 2.5 Cross-cutting behaviour a new endpoint inherits

**verified**

- Token interceptor applies to `/**` (`config/TablesMvcConfigurer.addInterceptors`, lines 37-49).
- `ServiceAuditAspect` audits any controller method annotated with swagger `@ApiResponses`
  (`services/common/.../audit/ServiceAuditAspect.java:70`). Its failure pointcut names
  `OpenHouseExceptionHandler` and `ViewExceptionHandler*` explicitly (lines 111-113), so a new
  controller-scoped advice must be added there to have failures audited.
- `TableAuditAspect` has one advice per handler signature (`tables/audit/TableAuditAspect.java`,
  database ones at 471-540); a new handler needs its own if it should be table-audited.
- Component scanning: `TablesSpringApplication` scans `com.linkedin.openhouse.tables` and the internal
  catalog; test applications (`e2e/h2/SpringH2Application`, `mock/MockTablesApplication`,
  `tables-test-fixtures/.../SpringH2TestApplication`) list packages explicitly and substitute
  `HouseTableRepository` with an `@Primary` H2 repository (`e2e/h2/HouseTablesH2Repository.java`).
  No HTS process runs in Tables tests.
- HTS: `HouseTablesSpringApplication` scans `com.linkedin.openhouse.housetables.*`; JPA repositories
  must live under `repository.impl.jdbc` (`DatabaseConfiguration.java:27`). The H2 test application
  `e2e/SpringH2HtsApplication.java` has an explicit `@ComponentScan` list and `@EntityScan("...housetables.model")`.

---

## 3. Requirements

### 3.1 Explicit requirements (from the design discussion)

E1. A database entity with a UUID, a database name, and key-value string properties.
E2. UUID is the canonical identity; the name is a logical catalog-level mapping.
E3. Create, get, delete, and property-update operations.
E4. Database metadata is independent of table metadata and table lifecycle operations.
E5. No existing table operation creates or loads a database entity.
E6. Deleting a database entity leaves tables untouched.
E7. Tables inherit database properties by default; an explicit table property overrides the
    inherited one.
E8. Actual table properties and effective table properties remain distinguishable.
E9. `LoadTable` is unchanged in this increment.
E10. `getEffectiveTableProperties(table)` is introduced in the Tables Client, performs an explicit
     database lookup and merges, is adopted per consumer, and keeps a stable public interface if
     resolution later becomes local to data returned by `LoadTable`.

### 3.2 REST-derived requirements (Apache Iceberg REST Catalog spec, `rest-catalog-open-api.yaml`, main)

Verified against the spec text:

R1. **Create**: `POST /v1/{prefix}/namespaces` with `CreateNamespaceRequest{namespace: string[], properties?: map}`
    (`properties` defaults to `{}`). Success is **200** with `CreateNamespaceResponse{namespace, properties}`.
    409 when the namespace already exists (example type `AlreadyExistsException`).
R2. **Get**: `GET /v1/{prefix}/namespaces/{namespace}` → 200 `GetNamespaceResponse{namespace, properties}`.
    `properties` is `null` when the server does not support properties and `{}` when none are set.
    404 → `NoSuchNamespaceException`.
R3. **Delete**: `DELETE /v1/{prefix}/namespaces/{namespace}` → **204**, no body. 404 when absent.
    "Namespace must be empty": 409 `NamespaceNotEmptyException` otherwise.
R4. **Update properties**: `POST /v1/{prefix}/namespaces/{namespace}/properties` with
    `UpdateNamespacePropertiesRequest{removals: string[] (unique), updates: map}`. Properties not
    named in the request are left unchanged. Response 200
    `UpdateNamespacePropertiesResponse{updated: string[], removed: string[], missing?: string[]}`;
    `missing` is nullable and servers need not implement it. 404 when absent. **422**
    `UnprocessableEntityException` when a key appears in both `removals` and `updates`.
R5. **Error envelope**: every non-2xx body is `IcebergErrorResponse{error: ErrorModel}` with
    `ErrorModel{message: string, type: string, code: int (400..600), stack?: string[]}`;
    `additionalProperties: false` on the wrapper.
R6. **Namespace encoding**: `namespace` is an array of levels in bodies; in paths, levels are joined
    by the unit separator `0x1F` (`%1F`). OpenHouse enforces exactly one level
    (`NamespaceUtil.MAX_NAMESPACE_DEPTH = 1`, `services/common/.../utils/NamespaceUtil.java:27`), so a
    path value containing `%1F` must be rejected as not found or bad request, and the body array must
    have exactly one element.
R7. Not required by the goal and **not proposed**: `GET /v1/{prefix}/namespaces` (list; OpenHouse's
    existing `/v1/databases` list stays as is), `HEAD` namespaceExists, `/v1/config`, pagination. The
    spec says a server that omits `endpoints` from `/v1/config` is assumed to support the full default
    set; since no `/v1/config` is proposed, a stock Iceberg `RESTCatalog` client cannot be pointed at
    this server, and that is not a goal of this increment.

### 3.3 Inferred implementation considerations, validated

I1. **Server-side UUID, immutable.** Nothing in HTS uses UUIDs today; the only server-generated key
    is `table_toggle_rule.id AUTO_INCREMENT` (verified). Table UUIDs are minted in the Tables Service
    (`TableUUIDGenerator`) and stored in Iceberg metadata, not in HTS. Decision: HTS mints the database
    UUID on insert and rejects any PUT whose `databaseUUID` differs from the stored one. HTS owns the
    record and is the only writer that can guarantee one UUID per row.
I2. **Name uniqueness and indexing.** Table lookups are case-insensitive everywhere
    (`findByDatabaseIdIgnoreCaseAndTableIdIgnoreCase`, `upper()` in JPQL; production collation
    `utf8mb4_0900_ai_ci`, `ddl/0000__baseline.sql:30`), and production has a functional index on
    `upper(database_id)`. A database named `MyDb` must therefore be found for a table whose
    `databaseId` is `mydb`. Decision: `database_name` is `UNIQUE` and looked up with `IgnoreCase`
    derived queries; uniqueness is case-insensitive under the production collation. Risk: H2 in
    `MODE=MySQL` compares case-sensitively unless told otherwise (**assumed**), so the service does an
    explicit case-insensitive existence check before insert and the unique key is the race-closing
    guard in production (section 7).
I3. **Atomic sparse updates.** JPA `@Version` on the row plus `UserTablesServiceImpl.persistTypedEntity`'s
    exception translation already gives compare-and-set at row granularity (verified). Decision: HTS
    exposes whole-row PUT with version CAS (its existing key-value convention,
    `HouseTablesApiHandler<K,V>.putEntity`); the Tables Service performs read → merge → PUT, computes
    `updated`/`removed`/`missing`, and maps HTS 409 to Iceberg `CommitFailedException` (409) after a
    bounded retry. Alternative (rejected): a bespoke HTS `PATCH` that applies `updates`/`removals`
    server-side. It saves one round trip but puts Iceberg request semantics into HTS, which today
    speaks only rows and keys.
I4. **Persistence schema and migration.** New table `database_row`, added to `schema.sql` and recorded
    as `ddl/0002__create_database_row.sql` for the out-of-band MySQL apply (the `ddl/` convention,
    verified). Properties as a JSON string in `MEDIUMTEXT` via a JPA `AttributeConverter<Map<String,String>, String>`
    (precedent: `EntityTypeConverter`; `job_conf MEDIUMTEXT`). A child key/value table was considered
    and rejected: the entity is always read and written as a whole map, and row-level CAS already
    makes the update atomic.
I5. **Error translation HTS → Tables.** `HouseTableRepositoryImpl.handleHtsHttpError` is the pattern
    (verified). A new repository reuses the same exception classes
    (`internalcatalog/.../repository/exception/*`) and the namespace service maps them to Iceberg
    exceptions (section 5.2).
I6. **Client handling of a missing database.** Absence is the normal state for every database that
    exists today (E5 means nothing backfills). Decision: 404 on the lookup yields the table's own
    properties unchanged; any other failure propagates. Silently returning actual properties on a
    transient error would hand a consumer the wrong answer without telling it.
I7. **Generated clients and conventions.** Both new APIs flow into `client/hts` and
    `client/tableclient` automatically once their controllers exist (verified). For the generated
    `tableclient` to carry typed models, the Tables Service DTOs must be springdoc-introspectable
    `@Schema` classes; Iceberg's own `org.apache.iceberg.rest.requests/responses` classes serialize
    through custom parsers and would not produce usable schemas (**assumed**; PR 57 needed a custom
    `HttpMessageConverter` for that reason). Decision: hand-written DTOs with Iceberg's field names.

---

## 4. PR sequence

The three-PR structure holds. Each PR is reviewable and mergeable alone: PR one adds an HTS entity
nothing calls yet; PR two adds Tables Service endpoints nothing in the repo calls yet; PR three
consumes them. No repository evidence argues for a different split. PR two depends on PR one only
through the regenerated `client/hts`, and PR three on PR two only through the regenerated
`client/tableclient`, both of which are produced from source in the same build.

| PR | Scope | Out of scope for this PR |
|---|---|---|
| **One: HouseTables Service** | `DatabaseRow` entity, `database_row` schema and DDL record, JDBC repository, transport model and key, mapper, service, validator, handler, `/hts/databases` controller, metrics constants, tests | Any change to `UserTableRow`, table endpoints, or the derived database list |
| **Two: Tables Service** | `NamespacesController` with the four Iceberg-shaped routes, DTOs, validator, handler, `NamespacesService`, `HouseDatabaseRepository(Impl)` over the generated HTS `DatabaseApi`, HTS bean, Iceberg error advice, audit pointcut, H2/mock test substitutes, tests | `LoadTable`, `GET /v1/databases` list, `/v1/config`, HEAD, pagination |
| **Three: Tables Client** | `getEffectiveTableProperties(Table)` in the shared `javaclient` package, `OpenHouseCatalog.loadNamespaceMetadata` in both Iceberg copies, identifier extraction, 404 handling, precedence rule, tests | Adoption by any consumer (`apps/spark` `TablesClient`, jobs), `setProperties`/`createNamespace`/`dropNamespace` on the client catalog |

An end-to-end test through `tables-test-fixtures` may follow separately and is not a prerequisite.

---

## 5. Files, interfaces, models, specs

### 5.1 PR one: HouseTables Service

All paths under `services/housetables/src/main/java/com/linkedin/openhouse/housetables/` unless noted.
Each row states why it is needed for the goal.

| Change | File (new unless noted) | Why |
|---|---|---|
| Entity | `model/DatabaseRow.java` | E1. Fields: `@Id String databaseUuid`, `String databaseName`, `@Version Long version`, `@Convert(converter = PropertiesConverter.class) Map<String,String> properties`, `Long creationTime`, `Long lastModifiedTime`. Mirrors `UserTableRow` (Lombok builder, protected constructors). Single `@Id`, so no `@IdClass`. |
| Converter | `model/PropertiesConverter.java` | I4. `AttributeConverter<Map<String,String>, String>`, JSON via the Gson already used in HTS models. Precedent `model/EntityTypeConverter.java`. PR 57 has a reference implementation. |
| Schema | `src/main/resources/schema.sql` (modified) | I4. `CREATE TABLE IF NOT EXISTS database_row (database_uuid CHAR(36) NOT NULL, database_name VARCHAR(128) NOT NULL, version BIGINT, properties MEDIUMTEXT, creation_time BIGINT, last_modified_time BIGINT, ETL_TS DATETIME(6) ..., PRIMARY KEY (database_uuid), UNIQUE KEY uk_database_row_name (database_name))`. Column names follow Spring's snake_case naming of the entity fields, as `user_table_row` does. |
| DDL record | `services/housetables/ddl/0002__create_database_row.sql` | I4. Operations record for the out-of-band MySQL apply, with `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci` to match `user_table_row` so the two `database` columns compare the same way. |
| Repository | `repository/impl/jdbc/DatabaseHtsJdbcRepository.java` | E3, I2. `extends HtsRepository<DatabaseRow, String>`; `Optional<DatabaseRow> findByDatabaseNameIgnoreCase(String)`, `boolean existsByDatabaseNameIgnoreCase(String)`, `@Modifying int deleteByDatabaseNameIgnoreCase(String)`. Must live in this package for `@EnableJpaRepositories` (`config/db/DatabaseConfiguration.java:27`). No Iceberg-backed variant; the toggle and soft-delete entities are JDBC-only too, so `DatabaseConfiguration` is untouched. |
| Transport model and key | `api/spec/model/Database.java`, `api/spec/model/DatabaseKey.java` | E1. `@Value @Builder` with `@Schema`/`@JsonProperty` like `UserTable`. `databaseName` validated with `ALPHA_NUM_UNDERSCORE_REGEX` (same rule as `databaseId` on tables). `databaseUuid` and `version` are server-populated: null on create. Reuses `CreateUpdateEntityRequestBody<Database>` and `EntityResponseBody<Database>`, which springdoc names `CreateUpdateEntityRequestBodyDatabase` / `EntityResponseBodyDatabase`. |
| DTO and mapper | `dto/model/DatabaseDto.java`, `dto/mapper/DatabasesMapper.java` | Convention: every HTS entity has a DTO separate from its transport model (`UserTableDto`, `JobDto`) and a MapStruct `@Mapper(componentModel="spring")`. |
| Service | `services/DatabasesService.java`, `services/DatabasesServiceImpl.java` | E3, I1, I3. `getDatabase(name)`, `putDatabase(Database)`, `deleteDatabase(name)`. Put rule: `version == null` → create only (mint UUID, `creationTime`, 409 `AlreadyExistsException` if the name exists case-insensitively); `version != null` → update only (row must exist, `databaseUuid` must match stored, `@Version` CAS; `ObjectOptimisticLockingFailureException` / `DataIntegrityViolationException` → `EntityConcurrentModificationException`, following `UserTablesServiceImpl.persistTypedEntity`). `lastModifiedTime` stamped on every write. Returns `Pair<DatabaseDto, Boolean existed>` so the handler can answer 200/201 as the user-table handler does. |
| Validator | `api/validator/impl/OpenHouseDatabasesHtsApiValidator.java` | Convention (`OpenHouseJobTablesHtsApiValidator`). Bean Validation via `ApiValidatorUtil.collectViolations`; name regex; keys and values non-null. A size bound on the property map is an implementation preference, not a requirement; if adopted, PR 57's `NamespacePropertiesValidator` (100 entries, 1 KiB per entry, 8 KiB total) is a starting point and should be enforced here and in PR two. |
| Handler | `api/handler/DatabaseHtsApiHandler.java`, `api/handler/OpenHouseDatabaseHtsApiHandler.java` | Convention (`JobTableHtsApiHandler` / `OpenHouseJobTableHtsApiHandler`). Get → 200 or `NoSuchEntityException("database", name)` (404). Put → 201/200. Delete → 204 or 404. |
| Controller | `controller/DatabaseHouseTablesController.java` | E3. `GET /hts/databases?databaseName=`, `PUT /hts/databases`, `DELETE /hts/databases?databaseName=`. Tag `"Database"` so `client/hts` gets `DatabaseApi`. Paths start with `/hts` so `ServiceAuditAspect` attributes them to `HOUSETABLES_SERVICE`; every method carries `@ApiResponses` so it is audited. |
| Metrics | `metrics/DatabaseMetricsConstant.java` (or additions to `services/common/.../metrics/MetricsConstant.java`) | Convention: service methods wrap calls in `MetricsReporter.executeWithStats`. |
| Spec post-processing | `services/housetables/build.gradle:31-62` (modify only if needed) | springdoc dropped `entityType` from `UserTable` and the build patches it in. Verify during implementation whether `Map<String,String> properties` survives introspection; patch the same way if not (**assumed** it does). |
| Tests | `src/test/.../e2e/database/DatabaseControllerTest.java`, `DatabaseRepositoryTest.java`, `DatabasesServiceTest.java`; `mock/handler/OpenHouseDatabaseHtsApiHandlerTest.java`; `mock/mapper/DatabasesMapperTest.java`; `model/TestHouseTableModelConstants.java` additions; `e2e/SpringH2HtsApplication.java` scan list (modified) | Mirrors `e2e/job/*` and `e2e/usertable/HtsControllerTest`. A store cleaner like `e2e/fixture/UserTableStoreCleaner` if the repository seals `deleteAll`. |
| Docs | `docs/specs/housetables.md` regenerated via `scripts/spec_update.sh` | Checked-in spec is generated; do not hand-edit (`docs/specs/README.md`). |

Not changed in PR one: `UserTableRow`, `UserTablesServiceImpl.listDatabases`, `DatabaseConfiguration`.

### 5.2 PR two: Tables Service

Paths under `services/tables/src/main/java/com/linkedin/openhouse/tables/` unless noted.

**Routes.** Literal Iceberg paths with an empty prefix:

| Route | Status | Body |
|---|---|---|
| `POST /v1/namespaces` | 200 | `CreateNamespaceRequestBody` → `GetNamespaceResponseBody` |
| `GET /v1/namespaces/{namespace}` | 200 | `GetNamespaceResponseBody` |
| `DELETE /v1/namespaces/{namespace}` | 204 | none |
| `POST /v1/namespaces/{namespace}/properties` | 200 | `UpdateNamespacePropertiesRequestBody` → `UpdateNamespacePropertiesResponseBody` |

Choosing the literal spec paths rather than `/v1/databases/{databaseId}/properties` costs nothing in
the existing conventions (the operation-ID customizer still yields a `V1` suffix; the token
interceptor matches `/**`) and is what makes R1-R5 a compatibility claim rather than a resemblance.
The alternative, OpenHouse-style paths with Iceberg bodies, is listed in section 8 as the one
material choice a reviewer may want to reverse.

| Change | File | Why |
|---|---|---|
| DTOs | `api/spec/v0/request/CreateNamespaceRequestBody.java`, `UpdateNamespacePropertiesRequestBody.java`; `api/spec/v0/response/GetNamespaceResponseBody.java`, `UpdateNamespacePropertiesResponseBody.java`, `IcebergErrorResponseBody.java` (+ `ErrorModel`) | R1-R5, I7. Field names exactly as the spec (`namespace` as `List<String>`, `properties`, `updates`, `removals`, `updated`, `removed`, `missing`, `error{message,type,code,stack}`), `@Schema` annotated so springdoc and the generated client carry them. |
| Controller | `controller/NamespacesController.java` | E3, R1-R4. Tag `"Namespace"` → generated `NamespaceApi` in `client/tableclient`. Path variable `namespace` is a single level; `%1F` present → 404 `NoSuchNamespaceException` (R6). Methods carry `@ApiResponses` for audit. |
| Error advice | `exception/NamespaceExceptionHandler.java` | R5. `@RestControllerAdvice(assignableTypes = NamespacesController.class) @Order(HIGHEST_PRECEDENCE)`, precedent `exception/ViewExceptionHandler.java`. Maps `NoSuchNamespaceException` 404, Iceberg `AlreadyExistsException` 409, `NamespaceNotEmptyException` 409, `CommitFailedException` 409, `UnprocessableEntityException` 422, `ValidationException`/`RequestValidationFailureException`/`HttpMessageNotReadableException` 400, `AccessDeniedException` 403, `HouseTableRepositoryStateUnknownException`/`DependencyUnavailableException` 503 (`ServiceUnavailableException`), everything else 500. Body is `IcebergErrorResponseBody`, never `ErrorResponseBody`. PR 57's `IcebergRestExceptionHandler` is a reference. |
| Audit pointcut | `services/common/.../audit/ServiceAuditAspect.java:111-113` (modified) | Failures through the new advice are only audited if the pointcut names it, as it does `ViewExceptionHandler*`. |
| Validator | `api/validator/impl/OpenHouseNamespacesApiValidator.java` | R4, R6. Exactly one namespace level; `ApiValidatorUtil.validateIdentifier` on it; `removals` ∩ `updates` non-empty → `UnprocessableEntityException` (422); reserved keys (`openhouse.` prefix, `policies`, the rule in `repository/impl/BasePreservedKeyChecker.java`) in `properties` or `updates` → 400. |
| Handler | `api/handler/NamespacesApiHandler.java`, `api/handler/impl/OpenHouseNamespacesApiHandler.java` | Convention (`DatabasesApiHandler`). Validate → service → map → `ApiResponse`. |
| Service | `services/NamespacesService.java`, `services/NamespacesServiceImpl.java` | E3, E7, I3. `create(name, props, principal)`, `get(name)`, `drop(name, principal)`, `updateProperties(name, updates, removals, principal)`. Update is read → merge → PUT with the read version, retried on `HouseTableConcurrentUpdateException` up to `HtsRetryUtils.MAX_RETRY_ATTEMPT`, then `CommitFailedException`. Drop checks `HouseTableRepository.findAllByDatabaseId(name)` for any table or view row and throws `NamespaceNotEmptyException` if non-empty (R3; PR 56 ruling W5); never cascades (E6). The server stamps `openhouse.databaseUUID` into the returned `properties` map so clients see the canonical identity without a new field in the spec shape; the reserved-key rule keeps clients from writing it. Authorization: see section 8, Q2. |
| HTS repository | `iceberg/openhouse/internalcatalog/.../repository/HouseDatabaseRepository.java`, `HouseDatabaseRepositoryImpl.java`; model `internalcatalog/.../model/HouseDatabase.java` (`@Entity`, like `HouseTable`, so the H2 substitute works); mapper `internalcatalog/.../mapper/HouseDatabaseMapper.java` | I5. Over the generated `com.linkedin.openhouse.housetables.client.api.DatabaseApi`; same timeouts, retry template and `handleHtsHttpError` translation as `HouseTableRepositoryImpl`. Lives beside `HouseTableRepository` because that is where HTS access and its exception vocabulary already live. PR 57's `HouseNamespaceRepositoryImpl` is a reference. |
| HTS bean | `config/MainApplicationConfig.java` (modified) | `@Bean DatabaseApi provideDatabaseApiInstance()` next to `provideToggleApiInstance` (lines 66-69). Note the generated HTS `DatabaseApi` and the generated Tables `DatabaseApi` share a simple name in different packages; import explicitly. |
| Test substitutes | `src/test/.../e2e/h2/HouseDatabasesH2Repository.java` (`@Repository @Primary`), `mock/MockTablesApplication.java` (`@MockBean HouseDatabaseRepository`), `tables-test-fixtures/*/.../SpringH2TestApplication.java` + an H2 repository there | Without these, every existing Tables context fails to start once the new repository bean exists. |
| Tests | `mock/controller/NamespacesControllerTest.java` (MockMvc, error envelope shape, 422, `%1F`), `e2e/h2/NamespacesServiceTest.java` (create/get/update/drop against H2, precedence of `updates` over existing, `missing`, non-empty drop 409), `internalcatalog/.../HouseDatabaseRepositoryImplTest.java` (MockWebServer, status translation), `api/spec/NamespaceApiContractTest.java` (freeze operation IDs and status codes, precedent `ViewApiContractTest`) | Acceptance criteria in section 6. |
| Docs | `docs/specs/catalog.md` regenerated | Generated file. |

Not changed in PR two: `TablesController`, `TablesServiceImpl`, `OpenHouseInternalRepositoryImpl`,
`OpenHouseInternalCatalog`, `DatabasesController`, `GetTableResponseBody`, `LoadTable` behaviour
(E9), the derived `GET /v1/databases` list.

### 5.3 PR three: Tables Client

`client/tableclient` holds no hand-written source, has no Iceberg dependency, and is shaded into
two Iceberg-version runtimes, so the function cannot live there without adding an Iceberg
dependency to a generated module. The hand-written client-side code that already has `Table`,
`DatabaseApi` and the error-mapping conventions is `com.linkedin.openhouse.javaclient`, shared by
both Iceberg versions (verified). That is where it goes.

| Change | File | Why |
|---|---|---|
| Namespace read on the catalog | `integrations/java/iceberg-1.2/.../iceberg-version-specific/.../OpenHouseCatalog.java:433-436` and `iceberg-1.5/...:479-481` (modified) | Replace the `UnsupportedOperationException` in `loadNamespaceMetadata` with `NamespaceApi.loadNamespaceMetadataV1(level0)`; 404 → `NoSuchNamespaceException`; other errors → `WebClientResponseWithMessageException` (the mapping used by `getDatabaseAclPolicies`, lines 557-578). Add a `NamespaceApi` field initialised next to `databaseApi` (iceberg-1.2 line 153-155, iceberg-1.5 line 190-193). This is the Iceberg-native surface of the lookup and keeps HTTP error mapping in one place. Other namespace methods stay unsupported. |
| Effective properties | `integrations/java/iceberg-1.2/openhouse-java-runtime/src/main/java/com/linkedin/openhouse/javaclient/EffectiveTableProperties.java` (shared source) | E7, E8, E10. `public static Map<String,String> getEffectiveTableProperties(SupportsNamespaces catalog, Table table)`: resolve `databaseId` (below), call `catalog.loadNamespaceMetadata(Namespace.of(databaseId))`, return an unmodifiable map of database properties with `table.properties()` put on top. `NoSuchNamespaceException` → return `table.properties()` unchanged (I6). Any other exception propagates. `OpenHouseCatalog` gains a one-line instance method `getEffectiveTableProperties(Table)` delegating to it, in both Iceberg copies, so a caller holding the catalog need not know the helper class. |
| Identifier extraction | `OpenHouseTableOperations.java:58-59` (modified: widen `tableIdentifier` getter to public) | The `TableIdentifier` the table was loaded with is the authoritative name; read it via `((BaseTable) table).operations()` when it is an `OpenHouseTableOperations`. Fall back to `table.properties().get(CatalogConstants.OPENHOUSE_DATABASEID_KEY)` for tables not loaded through this catalog. This keeps the function working if `docs/design/replication-simplification.md` later removes `openhouse.*` keys from Iceberg properties. |
| Tests | `integrations/java/iceberg-1.2/openhouse-java-itest/src/test/.../EffectiveTablePropertiesTest.java` (Mockito on the relocated `NamespaceApi`, precedent `OpenHouseTableOperationsTest.java:59-77`), `SmokeTest.java` additions with `MockWebServer` for `loadNamespaceMetadata` 200/404/500 | Acceptance criteria in section 6. |

Not changed in PR three: `OpenHouseTableOperations.doRefresh` (E9), `apps/spark` `TablesClient`,
any job or consumer, `createNamespace`/`setProperties`/`dropNamespace` on the client.

Interface stability (E10): the signature takes a `Table` and returns `Map<String,String>`. If
`LoadTable` later carries database properties (for example through `GetTableResponseBody.config`
and `ReadBridge`), the implementation reads them from the `Table` and skips the lookup; callers do
not change.

---

## 6. Behavioural acceptance criteria

### PR one

1. `PUT /hts/databases` with `{entity:{databaseName:"db1", properties:{...}}}` returns 201 and a body
   whose `databaseUuid` is a server-generated UUID and whose `version` is set; a second identical PUT
   (no version) returns 409.
2. `GET /hts/databases?databaseName=DB1` returns the row created as `db1` (case-insensitive) with the
   stored spelling, UUID, version and properties; an unknown name returns 404 with the
   `ErrorResponseBody` shape.
3. `PUT` with a stale `version` returns 409; with a matching `version` and a changed `properties`
   map returns 200 and bumps `version` and `lastModifiedTime`; with a `databaseUuid` that differs
   from the stored one returns 400 or 409 (pick one in the validator and freeze it in the contract test).
4. `DELETE /hts/databases?databaseName=db1` returns 204 and a following `GET` returns 404; deleting
   an unknown name returns 404. No user-table row is read or modified by any of these operations.
5. A property map of several KiB round-trips byte-for-byte through `MEDIUMTEXT`.
6. The generated `client/hts` build produces `DatabaseApi` with `getDatabase`, `putDatabase`,
   `deleteDatabase`, and the HTS OpenAPI contract test freezes their status codes.
7. All existing HTS tests pass unchanged; `schema.sql` on a fresh H2 and on an H2 that already has
   the other tables both start.

### PR two

1. `POST /v1/namespaces` `{"namespace":["db1"],"properties":{"a":"1"}}` → 200
   `{"namespace":["db1"],"properties":{"a":"1","openhouse.databaseUUID":"<uuid>"}}`; repeating it →
   409 `{"error":{"message":..., "type":"AlreadyExistsException","code":409}}`.
2. `GET /v1/namespaces/db1` → 200 with the same body; `GET /v1/namespaces/nope` → 404 with
   `type: NoSuchNamespaceException`; `GET /v1/namespaces/a%1Fb` → 404.
3. `POST /v1/namespaces/db1/properties` `{"updates":{"a":"2","b":"3"},"removals":["zz"]}` → 200
   `{"updated":["a","b"],"removed":[],"missing":["zz"]}`, and a following `GET` shows exactly
   `a=2, b=3` plus the server key; a key in both `updates` and `removals` → 422; `openhouse.x` or
   `policies` in `updates` → 400; a `namespace` array with two levels → 400.
4. `DELETE /v1/namespaces/db1` → 204 when no table or view row has `databaseId = db1`; → 409
   `type: NamespaceNotEmptyException` when one does, and that table is still loadable afterwards;
   → 404 when the entity does not exist even if tables with that `databaseId` exist.
5. Creating a table in a database that has no entity, and loading any table, never calls the HTS
   `DatabaseApi` (assert on the `@MockBean`/spy that `getDatabase` was not invoked).
6. `GET /v1/databases` is unchanged: a database entity with no tables does not appear, and no test
   asserting the existing list changes.
7. HTS 5xx on the lookup → 503 in the Iceberg envelope; HTS 409 on the update → 409
   `CommitFailedException` after retries are exhausted.
8. Error responses from the namespace routes never contain `stacktrace` or `cause`; failures are
   recorded by `ServiceAuditAspect`.
9. The generated `client/tableclient` build produces `NamespaceApi` with
   `createNamespaceV1`, `loadNamespaceMetadataV1`, `dropNamespaceV1`, `updatePropertiesV1`
   (method names follow the controller method names; freeze them in the contract test).

### PR three

1. For a table in a database whose entity has `{a:1, b:2}` and whose own properties include
   `{b:9, c:3}`, `getEffectiveTableProperties` returns `{a:1, b:9, c:3, openhouse.databaseUUID:...}`
   plus the table's other properties, and `table.properties()` is unchanged.
2. When the database lookup returns 404, the result equals `table.properties()` and no exception is
   thrown. When it returns 500 or times out, an exception propagates; the function never returns a
   partial answer.
3. The function makes exactly one `GET /v1/namespaces/{db}` call per invocation and no table calls;
   `loadTable` makes no namespace call (E9).
4. `databaseId` is taken from the `TableIdentifier` for tables loaded through `OpenHouseCatalog`,
   and from `openhouse.databaseId` otherwise; a table with neither raises `IllegalArgumentException`
   naming the table.
5. `OpenHouseCatalog.loadNamespaceMetadata(Namespace.of("db1"))` returns the properties map;
   `Namespace.of("a","b")` raises `NoSuchNamespaceException` (or `ValidationException`, consistent
   with `listTables`); `createNamespace`, `setProperties`, `dropNamespace` still throw
   `UnsupportedOperationException`. Both Iceberg-version catalogs behave the same.
6. The returned map is unmodifiable.

---

## 7. Compatibility and operational risks

1. **`schema.sql` runs against every datasource at startup.** `spring.sql.init.mode=always` with
   `CREATE TABLE IF NOT EXISTS` means the HTS process will attempt to create `database_row` on
   MySQL at first boot unless the DDL was applied out of band first. The repository convention
   (`ddl/0000__baseline.sql:4-6`) says production DDL is applied by the MySQL team; coordinate
   `0002__create_database_row.sql` before deploying PR one, and confirm whether the service account
   has DDL rights (unknown from the repo).
2. **Collation drift.** `database_row.database_name` must carry the same collation as
   `user_table_row.database_id` in every environment, or a database can be reachable under a spelling
   no table uses. Production pins `utf8mb4_0900_ai_ci`; `schema.sql` pins nothing. H2 tests compare
   case-sensitively (**assumed**), so case-insensitive uniqueness is only test-covered through the
   service's pre-insert check, not through the constraint.
3. **The derived database list and the entity disagree by design.** A database created through
   `POST /v1/namespaces` with no tables is invisible to `GET /v1/databases` and to Spark
   `SHOW DATABASES`; a database with tables but no entity returns 404 from `GET /v1/namespaces/{db}`.
   This is what E4/E5 require, and consumers of effective properties must treat 404 as "no inherited
   properties" (I6). Document it in `docs/specs/catalog.md`.
4. **`DROP DATABASE` semantics on engines.** Spark's `DROP DATABASE` maps to `dropNamespace`, which
   stays unsupported on the client in this increment; the server route exists for REST callers only.
   If a later PR wires it, the 409-on-non-empty rule prevents silent property loss on a populated
   database.
5. **Generated client churn.** Adding a tag to either service regenerates `client/hts` and
   `client/tableclient` and changes `docs/specs/*.md`. The HTS `DatabaseApi` and Tables `DatabaseApi`
   simple-name collision is a readability hazard in `MainApplicationConfig` and tests.
6. **Audit coverage gap.** A controller-scoped advice is only failure-audited if
   `ServiceAuditAspect`'s pointcut names it. Forgetting the pointcut edit means failed namespace
   calls are not audited while successful ones are.
7. **Fork lag.** `mkuchenbecker/openhouse` `main` lacks `entity_type`, `ddl/`, `ViewExceptionHandler`,
   `tableExists` and the views stack. Implementing against the fork as-is would re-create precedents
   this plan cites. Re-sync first.
8. **Reserved key exposure.** `openhouse.databaseUUID` becomes an inherited effective property on
   every table in a database with an entity. That is intended (it is how consumers learn the
   identity), but any consumer that later writes effective properties back to a table would be
   rejected by `BasePreservedKeyChecker`. Effective properties are read-only by construction and the
   function's Javadoc should say so.
9. **Client-side timeouts.** `loadNamespaceMetadata` adds one HTTP round trip per
   `getEffectiveTableProperties` call with no caching. Consumers iterating thousands of tables
   (jobs scheduler) should not adopt it per table without batching by database; that is a consumer
   decision outside this plan.
10. **Replication-simplification interaction.** If `openhouse.*` keys are removed from Iceberg
    properties later, the `TableIdentifier`-first extraction in PR three keeps working; the
    property fallback silently disappears. Note it in that design when it resumes.

---

## 8. Unresolved questions that materially affect implementation

Only two. Everything else above is decided with a stated recommendation.

**Q1. Route shape.** This plan uses literal Iceberg paths (`/v1/namespaces...`) with an empty prefix
and the Iceberg error envelope on those routes only. The alternative is OpenHouse-style paths
(`POST /v1/databases`, `GET|DELETE /v1/databases/{databaseId}`, `POST /v1/databases/{databaseId}/properties`)
carrying Iceberg request and response bodies. The alternative keeps one resource vocabulary in
`docs/specs/catalog.md` and puts the new operations on the existing `DatabaseApi`, but it makes
R1-R5 a resemblance rather than compatibility, and an Iceberg-aware caller would need a mapping
layer. The choice changes the controller, the contract test and the generated client class name, so
it should be ratified before PR two.

**Q2. Which existing privilege gates namespace writes.** `Privileges` has no database-level entries
and the OPA policy model is `user_roles[db_id][principal]`. Recommendation: `create`, `drop` and
`updateProperties` call `AuthorizationUtils.checkDatabasePrivilege(name, principal, UPDATE_TABLE_METADATA)`,
and `get` performs no check, matching `getAllDatabases` and `getDatabaseAclPolicies` today. Adding a
`CREATE_DATABASE`/`ALTER_DATABASE` privilege is possible but changes the SPI every deployment's
`AuthorizationHandler` implements, and the brief does not ask for it. The deployment owner must
confirm, because a wrong default either blocks database creation for everyone (if `SYSTEM_ADMIN` is
chosen) or lets any table writer in the database set properties every table inherits.
