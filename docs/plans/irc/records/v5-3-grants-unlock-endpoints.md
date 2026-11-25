# V5-3, V10a: grants and unlock routes under the IRC base; the config advertises the served endpoints

- **Branch:** `irc/v5-3-grants-unlock-endpoints`
- **Pull request:** draft https://github.com/mkuchenbecker/openhouse/pull/102, into `irc-integration`
- **Head:** `69f7713ce969f20086bc1617a774008cede4aec6`
- **Base:** cut from 1fa8cb34 (#99). Merged in `origin/irc-integration` twice: #100 (at 6b3bd86f) and #101 (at 3bc2ed36). Both merges were clean.
- **Marks:** **ran** means executed here. **read** means taken from code only.

## Bottom line (ran)

**V5-3: the routes**
- Six OpenHouse routes are served under `/iceberg/v1`, on every Iceberg line.
- Each calls the native route's API handler method with the same arguments, so validation, authorization, storage and audit are the native ones. They use the native bodies and answer errors in the IRC error shape.
- Tested against the native routes on twin tables: outcome, message, stored ACLs, lock state, version, access decisions and table audit are equal, apart from the differences listed below.
- Iceberg's own `HTTPClient` reaches every route: on 1.5.2 with a bearer header, and on 1.12 with an `AuthManager` built from the catalog properties.

**V10a: the config**
- The config response lists the spec routes registered on the line the service runs:
  - Iceberg 1.5 line (and 1.12 under the switch): 16 routes.
  - Iceberg 1.2 line: the 6 read routes.
- `openhouse.capabilities` is `policies,grants,unlock` on the 1.5 line and `grants,unlock` on the 1.2 line.
- Both are derived from Spring's handler mappings, so a route cannot be advertised without being served, or served without being advertised.

**Effect on clients**
- A 1.5.2 client ignores `endpoints`.
- A 1.12 client now:
  - checks existence with HEAD;
  - can create, select from and drop views;
  - refuses unserved routes locally, with "Server does not support endpoint ...", and never sends them.
- Under `-PicebergFork`, the Spark 3.5 `catalogTest` goes from 8 failures on the base to 0.

**One choice that reverses part of V4's pinned behavior**
- Once views are advertised, Spark 4.2's SHOW TABLES also lists views. The IRC view list answered the native 404 "Views are disabled", so SHOW TABLES failed in every database whose views gate is closed, which is the default.
- The IRC view list now answers an empty list there. A database that does not exist still gets 404.
- The native view list is unchanged.

## A. The routes (ran)

Base: `/iceberg/v1`. Requests use the native request body (`UpdateAclPoliciesRequestBody`); responses use the native response body (`GetAclPoliciesResponseBody`). Both are read and written by the same Spring converters as the native routes. The success status is the handler's.

| IRC route | Native route | Handler | Success |
|---|---|---|---|
| `GET /namespaces/{ns}/tables/{t}/openhouse/acl-policies` | `GET /v1/databases/{ns}/tables/{t}/aclPolicies` | `TablesApiHandler.getAclPolicies` | 200, native body, byte-equal |
| `POST /namespaces/{ns}/tables/{t}/openhouse/acl-policies` | `PATCH` on the same native path | `updateAclPolicies` | 204 |
| `GET /namespaces/{ns}/openhouse/acl-policies` | `GET /v1/databases/{ns}/aclPolicies` | `DatabasesApiHandler.getDatabaseAclPolicies` | 200 |
| `POST /namespaces/{ns}/openhouse/acl-policies` | `PATCH` on the same native path | `updateDatabaseAclPolicies` | 204 |
| `DELETE /namespaces/{ns}/tables/{t}/openhouse/lock` | `DELETE /v1/databases/{ns}/tables/{t}/lock` | `deleteLock(db, t, principal)` | 204 |
| `DELETE /namespaces/{ns}/tables/{t}/openhouse/lock/{reason}` | `DELETE /v1/databases/{ns}/tables/{t}/lock/{reason}` | `deleteLock(db, t, reason, principal)` | 204 |

- **PATCH becomes POST.** The native ACL update is a PATCH. Iceberg's `RESTClient` has only get, head, post and delete (pinned), so the IRC update is a POST with the same body.
- **No other routes.** Not adapted: `POST .../lock` (no SQL statement creates a lock) and `GET .../aclPolicies/{principal}` (no SQL statement uses it). This keeps the route set small and regular.

**Inventory of the native behavior (read; every row was also run against the native route as the oracle)**
- **Table ACL update.** Bean validation, then the identifier checks, then a lookup of the table (404 if missing), then UPDATE_ACL. A grant also needs:
  - a shared table: otherwise 400 "db.t is not a shared table";
  - an unlocked table: otherwise 400 "... is in locked state and grants are not allowed for sharing";
  - and then `authorizationHandler.grantRole` is called.

  A revoke has no sharing or lock check.
- **Table ACL listing.** Identifier checks and the table lookup only. It makes no access decision.
- **Database ACL update and listing.**
  - Update: UPDATE_ACL on the database.
  - Listing: no access decision.
  - Neither checks that the database exists.
- **Table audit.**
  - Updates: GRANT or REVOKE, SUCCESS or FAILED, with grantor, grantee and role.
  - Listings: READ.
  - Lock routes: none.
- **Unlock.** Lookup (404), then the replica check (500, `UnsupportedOperationException`), then LOCK_ADMIN (403).
  - The reasonless form removes only a LEGACY lock; a reasoned lock gets 409.
  - The reasoned form removes the lock only when the reason matches; otherwise 409.
  - An unlocked table gets 204 and nothing is written.
  - Spring converts the reason path variable exactly; an unknown value gets a 400 with an empty body.
- **What the OpenHouse client sends (read).**
  - GRANT, REVOKE: `PATCH` with `{role, principal, operation}`. Roles: SELECT and DESCRIBE map to TABLE_VIEWER, MANAGE GRANTS to ACL_EDITOR, ALTER to TABLE_ADMIN, CREATE TABLE to TABLE_CREATOR.
  - SHOW GRANTS: a GET.
  - UNLOCK: LEGACY uses the reasonless DELETE; SYSTEM_ONLY uses `DELETE .../lock/SYSTEM_ONLY`. The SQL accepts LEGACY and SYSTEM_ONLY in any case and upper-cases them.

**Multi-level namespaces, missing objects, views**
- A multi-level namespace on a table route is 404 `NoSuchTableException`; on a namespace route it is 400. This matches the other IRC routes, and neither reaches the service.
- A missing table or a view name is the native 404, shown as `NoSuchTableException`.
- A missing database on the database ACL routes behaves natively: no check, so the request succeeds.

**Error types**, consistent with the other IRC routes:

| Status | Type |
|---|---|
| 400 | `BadRequestException` |
| 403 | `ForbiddenException` |
| 404 | `NoSuchTableException` |
| 409 | `LockConflictException` (a coded exception's own name, as for 422 and 423 today) |
| 415 | `HttpMediaTypeNotSupportedException` |
| 500 | `ServiceFailureException` |

**Capabilities.** `grants` and `unlock` are advertised on the server. The in-process adapter is unchanged: it delegates grants and unlock through Java interfaces and serves no such routes.

### Equivalence and authorization evidence (ran)

`IrcGrantsUnlockRoutesTest` (18 tests) compares each case on twin tables. Same Spring context as the other auth tests, with a recording `AuthorizationHandler`.

| Area | Cases |
|---|---|
| Table grants | GRANT with the client's body; GRANT with every field; SHOW; REVOKE |
| Refusals on a table | unshared table (400); a LEGACY-locked and a SYSTEM_ONLY-locked table (400, while a revoke is accepted); UPDATE_ACL denied (403, nothing stored) |
| Listing | answered for a principal denied every privilege (no access decision on either side) |
| Missing names | a missing table and a view name (404) |
| Invalid input | 10 invalid bodies (400, same message, same audit FAILED or none); unknown field accepted; no body; text/plain (415 on both); invalid identifiers |
| Multi-level namespace | answered as on the other IRC routes |
| Database grants | GRANT/REVOKE/SHOW, including on a database holding nothing; UPDATE_ACL denied (403) |
| Unlock | LEGACY lock, both forms (204, one write each); SYSTEM_ONLY lock (409 twice, then 204); unlocked table (204, no write); missing table (404); LOCK_ADMIN denied (403, lock kept); unknown reasons (400); replica table (500) |
| Native routes | unchanged; a PATCH to the IRC path gets the catch-all's 400 |

Table audit events are compared on every case, and they match.

### What Iceberg's `HTTPClient` can and cannot do (ran)

**On 1.5.2** (`IrcGrantsUnlockHttpClientTest`, real port, bearer header):
- Every operation works:
  - POST returns null on 204;
  - GET parses `{"results":[{principal, role}]}`;
  - DELETE returns null.
- How errors surface:

  | Error | Default error handler | Table error handler |
  |---|---|---|
  | 400 | `BadRequestException` "Malformed request: <native message>" | same |
  | 403 | `ForbiddenException` | same |
  | 404 | `RESTException` "Unable to process: ..." | `NoSuchTableException` with the native message |
  | 409 lock conflict | `RESTException` | `AlreadyExistsException`, which is misleading: the capability client must not use the table handler for unlock |
  | no token | `NotAuthorizedException` | `NotAuthorizedException` |
  | 500 | `ServiceFailureException` "Server error: ServiceFailureException: msg" | same |

- **Cannot:**
  - send PATCH or PUT (pinned);
  - read the camel-case multi-word field `expirationEpochTimeSeconds` without `@JsonProperty`, because its mapper is kebab-case (pinned). Single-word fields read either way.

**On 1.12** (Spark 4.2 module): `HTTPClient` plus `AuthManagers.loadAuthManager(...).catalogSession(...)`, built from the catalog properties `uri` and `token`:
- GET returns an empty list;
- a GRANT on an unshared table is `BadRequestException` with the native message;
- REVOKE returns 204;
- both unlock forms return 204;
- a denied principal gets `ForbiddenException`.

**Design claim corrected.** The design's §7 says a 400 is still `IllegalArgumentException` over REST. Iceberg raises `BadRequestException`, which extends `RESTException`, unless the error type is `IllegalArgumentException`. C2-3 must map it if it wants the old exception type.

## B. The endpoint list

**Iceberg 1.12 behavior (read: `RESTSessionCatalog`, `Endpoint` on `origin/openhouse-1.12.0`)**
- **With no list,** the client assumes `DEFAULT_ENDPOINTS`:
  - namespace list, load, create, update and delete;
  - table list, load, create, update, delete, rename and register;
  - metrics and transactions.

  It adds views only if `view-endpoints-supported=true`.
- **With a list,** it uses exactly that list:
  - `tableExists`, `namespaceExists` and `viewExists` use HEAD if HEAD is listed;
  - `Endpoint.check` throws `UnsupportedOperationException("Server does not support endpoint: <M> <path>")` for any unlisted route;
  - the list calls return empty without a request if their route is unlisted.
- **Wire format:** a JSON array of strings `"<METHOD> /v1/{prefix}/<path>"`, with the template written literally.

**Iceberg 1.5.2 (read and ran).** `ConfigResponse` is a plain POJO with no `endpoints` field, read by `RESTObjectMapper` with `FAIL_ON_UNKNOWN_PROPERTIES=false`. It parses as before (`IrcConfigEndpointsTest`), and a real 1.5.2 `RESTCatalog` initializes and works (`IrcRestCatalogPolicyKeyTest`, the full suite, the Spark 3.5 `catalogTest`).

**The list as sent (ran), sorted by path, then GET, HEAD, POST, DELETE.**

On the 1.5 line (and the 1.12 fork):
```
GET    /v1/{prefix}/namespaces
GET    /v1/{prefix}/namespaces/{namespace}
HEAD   /v1/{prefix}/namespaces/{namespace}
GET    /v1/{prefix}/namespaces/{namespace}/tables
POST   /v1/{prefix}/namespaces/{namespace}/tables
GET    /v1/{prefix}/namespaces/{namespace}/tables/{table}
HEAD   /v1/{prefix}/namespaces/{namespace}/tables/{table}
POST   /v1/{prefix}/namespaces/{namespace}/tables/{table}
DELETE /v1/{prefix}/namespaces/{namespace}/tables/{table}
GET    /v1/{prefix}/namespaces/{namespace}/views
POST   /v1/{prefix}/namespaces/{namespace}/views
GET    /v1/{prefix}/namespaces/{namespace}/views/{view}
HEAD   /v1/{prefix}/namespaces/{namespace}/views/{view}
POST   /v1/{prefix}/namespaces/{namespace}/views/{view}
DELETE /v1/{prefix}/namespaces/{namespace}/views/{view}
POST   /v1/{prefix}/tables/rename
```
On the 1.2 line: the six read routes only.

**How it is derived.** `IcebergRestServedRoutes` reads `requestMappingHandlerMapping` once and takes every method and pattern that an IRC-package controller maps under `/iceberg/v1`, except:
- `/config`;
- `.../openhouse/...` (the OpenHouse routes are capabilities, not endpoints);
- methods marked `@IcebergRestRefusedRoute` (view rename, D27).

The catch-all maps no method. Capabilities are the `@IcebergRestCapability` names on the mapped methods:
- `policies` on table create and commit;
- `grants` and `unlock` on the OpenHouse routes.

It uses Spring types only, so the bean starts on the 1.2 line.

**Why JSON level, not reflection.** One code path serves 1.2, 1.5.2 and 1.12. The 1.12 serializer omits an empty `endpoints`, and the list is then added as a JSON node.

**Drift tests (`IrcConfigEndpointsTest`, 6 tests).** They check that:
- the list equals the expected list;
- every listed route is served (a probe gets neither the 406 nor "No IRC route");
- every route of the 1.12 `Endpoint` list that is not listed answers 406;
- an independent scan of the handler mappings finds every IRC route either listed or one of the accounted exceptions;
- the OpenHouse routes are not listed.

A further test asserts what this line's client reads: 1.5.2 ignores the list, and 1.12 reads it (that branch runs in the opt-in CI suite). The fixture boundary test checks both lines.

### 1.5.2 client compared with 1.12 client after this change (ran)

- **1.5.2:** no change apart from `openhouse.capabilities=policies,grants,unlock`.
- **1.12, Spark 4.2 (`SparkIrcEndpointsTest`, 7 tests; the service writes a Tomcat access log):**
  - **Existence checks.** `tableExists` and `namespaceExists` send only `HEAD ... 204/404`. For a missing table no GET is sent.
  - **Spark's DROP TABLE IF EXISTS.** Spark first resolves the name by loading the table (`GET .../tables/x 404`), then probes with `HEAD .../views/x 404`.
  - **Views.** CREATE VIEW, SELECT, SHOW VIEWS and DROP VIEW work in `irc42_views`, where the gate is opened by a toggle row: `POST .../views 200`, `GET .../views/v 200`, `DELETE .../views/v 204`. This needed no Iceberg extensions in the session.
  - **SHOW TABLES and SHOW VIEWS** where views are disabled: SHOW TABLES lists the tables, SHOW VIEWS is empty, and `GET .../views` answers 200.
  - **Refused on the client, nothing sent:**
    - ALTER VIEW RENAME;
    - CREATE NAMESPACE;
    - ALTER NAMESPACE SET PROPERTIES;
    - `registerTable`;
    - `commitTransaction`.

    Each gets `UnsupportedOperationException` "Server does not support endpoint: ...", and the access log has no such request. Before this change they reached the server and got a 406.
  - **Capabilities.** `RESTCatalog.properties()` has `openhouse.capabilities=policies,grants,unlock`.
- **1.12, Spark 3.5 under `-PicebergFork`.** Two results differ by line; both expectations are now stated with `IcebergLine`:
  - **View rename:** RESTException from the server's 406 on 1.5.2; refused on the client on 1.12.
  - **`tableExists`:** on 1.5.2 it loads the table, so a capability-gated table gets 422 and a denied principal 403; on 1.12 it sends HEAD and returns true, like the native existence route.

  The other 6 base failures needed no test change.

## Every difference from native behavior (ran)

1. **Missing body.** Spring's message names the method that was to receive the body, which is the IRC controller's method here. It is pinned.
2. **Unknown lock reason.** Native answers 400 with an empty body. IRC answers 400 with "Invalid reason: X; expected one of [LEGACY, SYSTEM_ONLY]", because the spec requires a message.
3. **415.** The native body is empty. The IRC error carries Spring's message, with type `HttpMediaTypeNotSupportedException`.
4. **500 (replica unlock).** The IRC message has no `java.lang.UnsupportedOperationException: ` prefix. This is the existing IRC mapping.
5. **Method.** The native update is PATCH; the IRC update is POST.
6. **Exception types on the client** are Iceberg's (see the `HTTPClient` section).
7. **Service audit.** Successful IRC requests are audited with URI `/iceberg/...` and service UNRECOGNIZED. Failed IRC requests are not in the service audit. This is the known V2/V3 gap. The table audit is equal.
8. **IRC view list** where views are disabled: 200 with an empty list. The native view list answers 404. The other IRC view routes keep the 404. This changed three V4 pins: `IrcViewRoutesTest`, Spark 3.5 `IcebergRestCatalogViewTest` and `IcebergRestSparkSqlViewTest` (SHOW VIEWS is now empty instead of failing).
9. **1.2 line.** `policies` is no longer advertised there: it was advertised but never served, because the write routes do not exist on 1.2.

Native routes: no status, message, stored value or write count changed. The native view list was not touched. The native unreadable-body message comes from the same code, moved into a static method.

## Choices where the brief left room

- **`@RequestBody` of the native type, not `byte[]`.** It gives the same converter and so the same parse and messages. The Spring exceptions (unreadable body 400, type mismatch 400, media type 415) are mapped in `IcebergRestErrors`.
- **Database ACL routes do not check that the database exists,** as natively. A CREATE TABLE grant comes before the first table.
- **409 type** is `LockConflictException`.
- **Reason message** names the path variable: "Invalid reason: ...".
- **Capability derivation by annotation.** This also drops `policies` on the 1.2 line.
- **The empty IRC view list** where views are disabled (above). Reverse by restoring the 404 in `IcebergRestViewHandler.listViews`; SHOW TABLES on 1.12 then breaks wherever the gate is closed.
- **Capability names.** Per the coordinator, the service now uses the module's `CapabilityNames` through a compileOnly dependency. Only String constants are used, and javac inlines them: no bytecode instruction loads the class (javap). The dependency dumps of six configurations are identical before and after: services:tables runtime, test runtime and test compile; both fixtures' runtime; the 1.2 fixture's test runtime. Client-capability names (`column-defaults`) stay with the service's constants.
- **Spark 4.2 service.** It gained a Tomcat access log and a views toggle row. This is test infrastructure only, in `src/service`.
- **Spark 4.2 HTTPClient test.** It also exercises the 1.12 `AuthManager`, which is the path C2-3 will use.

## Stopped on

Nothing.

## Test counts (ran)

| Suite | Before | After |
|---|---|---|
| `:services:tables:test` | 1499 (V5 record) | **1528/0/0** (+18 routes, +5 HTTPClient, +6 config); stored-metadata golden tests unchanged inside it |
| `tables-test-fixtures_2.12:test` (Iceberg 1.2) | 37 | **39/0**, boots |
| `tables-test-fixtures-iceberg-1.5_2.12:test` | 37 | **39/0** |
| Spark 3.5 `catalogTest` | 251 (V5); 255 after #100 | **255/0, 1 skipped** |
| `-PicebergFork` Spark 3.5 `catalogTest` | 8 failures on the base (coordinator) | **255/0, 1 skipped** |
| `-PicebergFork` Spark 4.2 `test` | 31 | **38/0** |
| `-PicebergFork` Spark 4.2 `extensionsTest` (new in #101) | n/a | **10/0** |
| `-PicebergFork` `:services:tables:compileJava compileTestJava` | n/a | pass |
| `spotlessCheck` (default, and Spark 4.2 under the switch) | n/a | pass |

`IcebergRestBeanSignaturesTest` passes.

## Commits

| Commit | Kind | Why |
|---|---|---|
| a72d2a97 | test | Grants and unlock over IRC against the native routes, with HTTPClient (red) |
| 9ca088fd | refactor | Native unreadable-body message as a shared static method, so it is not copied |
| 3339279b | test | Compare violation lists without order; pin the missing-body message |
| 46d25be6 | feat | IRC errors for native bodies and path variables |
| fe22c0b2 | feat | The six routes |
| 2e8d58bc | test | Config lists exactly the served routes and capabilities (red) |
| a0a6a7d3 | test | Probe view rename with a valid body |
| 55fe5599 | feat | Read the served routes and capabilities from the handler mappings |
| a9c1d816 | feat | Config lists the endpoints and derives the capabilities |
| 76644492 | test | Spark 4.2 cases for the 1.12 client (red on SHOW TABLES) |
| ab563f73 | test | IRC view list where views are disabled is empty (red) |
| 04ee8b0a | feat | The empty IRC view list |
| 0b818de7 | style | Format the Spark 4.2 test |
| 6b3bd86f | merge | #100 |
| d89f9a85 | test | Spark 3.5 tests read the new advertised capabilities |
| 7a54b9fb | refactor | Use `CapabilityNames` constants (compileOnly) |
| 6bfaa6e7 | test | Spark 3.5 tests state the 1.12 behavior with `IcebergLine` |
| 3bc2ed36 | merge | #101 |
| 69f7713c | test | Spark 4.2 resolver test reads the new advertised set |

## Merge commit (ready to use)

Title: `Merge V5-3, V10a: grants and unlock routes under the IRC base; config advertises served endpoints (#102)`

Body: see the hand-back message.

## Not run

- Spark 3.1 itests: no client or extension code changed.
- Spark 3.5 `statementTest` and `test`.
- The 1.5 Java itest: the adapter is unchanged.
- Apps tests.
- The `-PicebergFork` `:services:tables:test` (compiled only).
- The full default Spark 3.5 `catalogTest` after the `IcebergLine` commit and #101: only the three changed classes were rerun on the default line (21/0). #101 touches no Spark 3.5 or service code.
- A red run of the Spark 4.2 `extensionsTest` before its fix.
- CI on pull request 102.
- Real HTS / MySQL.

No JVM of mine is left running; Gradle daemons use the 60 s idle timeout.
