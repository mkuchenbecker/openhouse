# GRANT / REVOKE / SHOW GRANTS on the Spark-4.0 REST lane

Status: **DONE** (client works end-to-end against the real embedded server; exact request contract
verified). Branch `1.11`.

This documents how `GRANT` / `REVOKE` / `SHOW GRANTS` DDL was made to actually WORK on the Spark-4.0
/ Iceberg-1.11 REST-first lane, closing what was previously a documented deferral.

## 1. The `SupportsGrantRevoke` dead-end

The legacy spark-3.x execs
(`spark-3.5/.../execution/datasources/v2/GrantRevokeStatementExec.scala`,
`ShowGrantsStatementExec.scala`) extracted a custom `com.linkedin.openhouse.spark.OpenHouseCatalog`
implementing `com.linkedin.openhouse.javaclient.api.SupportsGrantRevoke` and called
`updateTableAclPolicies(...)` / `getTableAclPolicies(...)` on it.

On the Spark-4.0 REST lane the Spark catalog is the **stock**
`org.apache.iceberg.rest.RESTCatalog` (see `OpenHouseRestSparkITest`), which:

- does **not** implement `SupportsGrantRevoke`, and
- exposes only the Iceberg REST surface under `/iceberg` (table CRUD + snapshots + the
  `updated.openhouse.policy` translation) — it has **no** ACL sub-resource.

So downcasting the catalog is a dead end. There is nothing on the `RESTCatalog` to call.

## 2. Design: direct HTTP to the existing `/aclPolicies` endpoint

The OpenHouse server already exposes ACL endpoints on the tables/databases surface (served by
`TablesController` / `DatabasesController`), and the **same** server process that mounts the Iceberg
REST catalog under `/iceberg` also mounts these under `/v1/...` on the **same host/port**. (The
embedded itest `OpenHouseLocalServer` / `SpringH2TestApplication` component-scans both
`...tables.controller` and the Iceberg REST controller.)

The Spark-4.0 execs therefore call the ACL endpoint directly over HTTP:

- **Update** (GRANT/REVOKE): `PATCH {base}/v1/databases/{db}/tables/{t}/aclPolicies`
  (or `.../databases/{db}/aclPolicies` for a DATABASE resource), JSON body
  `{"operation":"GRANT|REVOKE","role":"<role>","principal":"<principal>"}`
  (`UpdateAclPoliciesRequestBody`). Success = HTTP 2xx (the server returns **204 No Content**).
- **Read** (SHOW GRANTS): `GET {base}/v1/databases/{db}/tables/{t}/aclPolicies` →
  `GetAclPoliciesResponseBody` = `{"results":[{"principal":...,"role":...}, ...]}`.

**Base URI + token derivation.** The exec reads the active Spark catalog config
`spark.sql.catalog.<name>.uri` (e.g. `http://host:port/iceberg`) and strips the trailing `/iceberg`
to get the server base, and reads `spark.sql.catalog.<name>.token` for the bearer token — mirroring
how `OpenHouseRestSparkITest.getOpenHouseCatalog` reconstructs catalog properties from the session
conf. No new config keys are introduced.

**HTTP client + JSON.** `java.net.http.HttpClient` (JDK 17, always on the classpath) with the
`PATCH`/`GET` methods; request/response JSON via a Jackson `ObjectMapper` (Jackson is provided at
runtime by both Spark 4.0 and Iceberg). No new module dependencies were added.

### Files

- `openhouse-spark-runtime/.../execution/datasources/v2/OpenHouseAclClient.scala` — shared helper:
  URI+token derivation, privilege↔role maps, the PATCH/GET calls.
- `.../v2/GrantRevokeStatementExec.scala` — GRANT/REVOKE physical exec.
- `.../v2/ShowGrantsStatementExec.scala` — SHOW GRANTS physical exec (emits `(privilege, principal)`
  rows).
- `.../catalyst/constants/Principal.scala` — ported PUBLIC↔`*` mapping (was missing on this lane).
- `.../v2/OpenhouseDataSourceV2Strategy.scala` — wired `case GrantRevokeStatement(...)` and
  `case ShowGrantsStatement(...)` to the new execs (they previously fell through to `case _ => Nil`).

The grammar (`grantStatement` / `revokeStatement` / `showGrantsStatement`), the logical plans
(`GrantRevokeStatement`, `ShowGrantsStatement`), the `GrantableResourceTypes` enum, and the AST
builder (`visitGrantStatement` / `visitRevokeStatement` / `visitShowGrantsStatement`) were **already
present** on the Spark-4.0 lane — only the physical execs + strategy wiring + `Principal` were
missing.

## 3. Privilege → role mapping (authoritative)

Replicated EXACTLY from `integrations/java/iceberg-1.2/.../javaclient/mapper/Privileges.java`, inlined
into `OpenHouseAclClient` (like the granularity tokens inlined in the AST builder) so this module
needs no cross-module dependency on the java-client:

| Privilege (SQL) | Role (server)  |
|-----------------|----------------|
| `SELECT`        | `TABLE_VIEWER` |
| `DESCRIBE`      | `TABLE_VIEWER` |
| `MANAGE GRANTS` | `ACL_EDITOR`   |
| `ALTER`         | `TABLE_ADMIN`  |
| `CREATE TABLE`  | `TABLE_CREATOR`|

`SHOW GRANTS` reverse-maps role → privilege. Mirroring `Privileges.fromRole(...).getPrivilege()`
(used by the java-client `SparkMapper.toAclPolicyDto`), `fromRole` returns the FIRST enum with a
matching role, so `TABLE_VIEWER` → `SELECT` (declared before `DESCRIBE`). The `MANAGE GRANTS` /
`CREATE TABLE` privilege strings arrive from the parser with the space intact because the grammar
declares them as single lexer tokens (`GRANT_REVOKE: 'MANAGE GRANTS'`, `CREATE_TABLE: 'CREATE
TABLE'`).

`Principal("PUBLIC")` maps to the ACL wildcard `*`, and `SHOW GRANTS` renders `*` back to `PUBLIC`.

## 4. Feasibility finding: no full ACL round-trip in-JVM (auth enablement)

A real end-to-end round-trip (GRANT, then observe the grant in SHOW GRANTS) is **not possible against
the embedded server**, for a server-side reason unrelated to the client:

- The only `AuthorizationHandler` bean is `OpaAuthorizationHandler` (the sole `@Component`
  implementing the interface). It delegates to an external Open Policy Agent service.
- When `cluster.security.tables.authorization.opa.base-uri` is **null** (the embedded default — no
  OPA server exists in-JVM), `OpaAuthorizationHandler` short-circuits:
  `grantRole`/`revokeRole` **log "Skipping" and return (no-op)**, and `listAclPolicies` **returns
  `Collections.emptyList()`**. `checkAccessDecision` returns `true` (allow-all).
- Enabling `cluster.security.tables.authorization.enabled=true` would NOT help — that flag only
  toggles the `@Secured` method interceptor; the actual grant/list still route through
  `OpaAuthorizationHandler`, which still no-ops without an OPA base URI. There is no in-memory/dummy
  ACL store bean to wire.

So on the embedded server a GRANT is accepted (HTTP 204) but stores nothing, and SHOW GRANTS always
returns empty. The full round-trip is a property of a **deployed OPA store**, not of the Spark client.

### Bar achieved: LEGACY client-contract bar (matching spark-3.1)

This matches exactly what the legacy `spark-3.1 .../statementtest/GrantRevokeStatementTest` did — it
asserted the client emits the correct request against a mock dispatcher, not a real ACL store. The
Spark-4.0 test `GrantRevokeTestSpark4_0` proves the client contract two ways:

1. **Real embedded server** (`testGrantRevokeAgainstEmbeddedServerSucceeds`): create a table, enable
   sharing (the server rejects ACL updates on a non-shared table with HTTP 400 — a real server-side
   validation the client correctly triggers), then `GRANT SELECT` / `GRANT ALTER` / `REVOKE SELECT`
   all execute against the **actual** `/v1/databases/.../aclPolicies` endpoint and return **204**;
   `SHOW GRANTS` issues the real GET and yields the `(privilege, principal)` schema. This proves URI
   derivation, token presentation, privilege→role mapping, and endpoint targeting are all correct
   against the real server.
2. **Capturing stub** (`testGrantEmitsCorrectPatchRequest`, `testGrantAlterMapsToTableAdmin`,
   `testRevokeEmitsRevokeOperation`, `testShowGrantsParsesServerRows`): a local
   `com.sun.net.httpserver.HttpServer` (which also answers `GET /iceberg/v1/config` so a stock
   `RESTCatalog` initializes) captures the exact request and asserts path =
   `/v1/databases/{db}/tables/t/aclPolicies`, body `operation`=GRANT/REVOKE, `role`=mapped role,
   `principal`=grantee, and the `Bearer` auth header. `SHOW GRANTS` against a canned
   `{"results":[{"role":"TABLE_VIEWER","principal":"alice"}]}` emits exactly one row `(SELECT,
   alice)`, proving response parsing + role→privilege reverse-mapping.

## 5. Verification

Targeted (full suites OOM locally), Java 17, `-Dfile.encoding=UTF-8`:

```
./gradlew :integrations:spark:spark-4.0:openhouse-spark-4.0-itest:test \
  --tests "*.GrantRevokeTestSpark4_0" --tests "*.WapIdTestSpark4_0" --tests "*.PolicySqlDdlTestSpark4_0"
```

Result: all green.

```
GrantRevokeTestSpark4_0 > testGrantEmitsCorrectPatchRequest() PASSED
GrantRevokeTestSpark4_0 > testGrantAlterMapsToTableAdmin() PASSED
GrantRevokeTestSpark4_0 > testRevokeEmitsRevokeOperation() PASSED
GrantRevokeTestSpark4_0 > testGrantRevokeAgainstEmbeddedServerSucceeds() PASSED
GrantRevokeTestSpark4_0 > testShowGrantsParsesServerRows() PASSED
WapIdTestSpark4_0 > testWapWorkflowWithVariousOperations() PASSED    # restored inline GRANT
... (all WapId cases) PASSED
PolicySqlDdlTestSpark4_0 > (all cases) PASSED                        # extension not regressed
```

The dropped `GRANT SELECT ON TABLE ... TO lejiang` in
`WapIdTestSpark4_0.testWapWorkflowWithVariousOperations` was **restored** (it now executes on the REST
lane) and the test stays green.
