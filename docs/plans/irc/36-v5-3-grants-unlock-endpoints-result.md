# Grants and unlock routes, and the served endpoint list (V5-3, V10a): result

Bottom line: the service serves six OpenHouse routes for grants and unlock under the IRC base, each calling the
native handler, and its config now lists exactly the routes it serves. An Iceberg 1.12 client therefore uses HEAD
existence checks and views, and refuses unserved routes locally. One choice reverses part of what pull request 95
pinned: the IRC view list of a database whose views switch is off now answers an empty list (D28). The client for the
new routes is not built (C2-3). Written by a delegated agent from branch `irc/v5-3-grants-unlock-endpoints` (pull
request 102, merged as 6015e111). "Ran" means run. "Read" means taken from code only.

## A. The routes (ran)

Base `/iceberg/v1`. Requests and responses use the native bodies, through the same Spring converters. Each route
calls the native route's API handler method with the same arguments, so validation, authorization, storage and audit
are the native ones. Errors use the IRC error shape. Served on every Iceberg line.

| IRC route | Native route | Success |
| --- | --- | --- |
| `GET /namespaces/{ns}/tables/{t}/openhouse/acl-policies` | `GET /v1/databases/{ns}/tables/{t}/aclPolicies` | 200, byte-equal body |
| `POST` on the same path (table) | `PATCH` on the native path | 204 |
| `GET /namespaces/{ns}/openhouse/acl-policies` | `GET /v1/databases/{ns}/aclPolicies` | 200 |
| `POST` on the same path (database) | `PATCH` on the native path | 204 |
| `DELETE /namespaces/{ns}/tables/{t}/openhouse/lock` | `DELETE .../tables/{t}/lock` | 204 |
| `DELETE /namespaces/{ns}/tables/{t}/openhouse/lock/{reason}` | `DELETE .../tables/{t}/lock/{reason}` | 204 |

- Iceberg's `RESTClient` has only get, head, post and delete (pinned), so the native PATCH became a POST.
- Not adapted: `POST .../lock` and `GET .../aclPolicies/{principal}`, which no statement uses.
- A multi-level namespace is 404 on a table route and 400 on a namespace route, as on the other IRC routes. A
  missing table or a view name is the native 404. A missing database on the database ACL routes succeeds, as natively.

Native behavior the routes keep (read, and run against the native route as the oracle):
- A table grant needs a shared table (else 400 "db.t is not a shared table") and an unlocked one (else 400
  "... is in locked state and grants are not allowed for sharing"). A revoke has neither check. Listing makes no
  access decision.
- Unlock: lookup (404), replica check (500), then LOCK_ADMIN (403). The reasonless form removes only a LEGACY lock, and
  a reasoned lock gets 409. The reasoned form removes the lock only when the reason matches. An unlocked table gets
  204 and nothing is written.
- Table audit: GRANT or REVOKE with SUCCESS or FAILED, READ for listings, none for lock routes.
- Error types: 400 `BadRequestException`, 403 `ForbiddenException`, 404 `NoSuchTableException`, 409
  `LockConflictException`, 415 `HttpMediaTypeNotSupportedException`, 500 `ServiceFailureException`.

Equivalence evidence: `IrcGrantsUnlockRoutesTest` (18 tests) compares each case on twin tables with a recording
`AuthorizationHandler`: grants and listings, refusals (unshared, locked, UPDATE_ACL denied), missing names, 10 invalid
bodies, 415, every unlock path and lock kind, a denied LOCK_ADMIN, unknown reasons and a replica table. Outcome,
message, stored ACLs, lock state, version, access decisions and table audit match.

## What Iceberg's `HTTPClient` can and cannot do (ran)

On 1.5.2 (`IrcGrantsUnlockHttpClientTest`, real port, bearer header) every operation works: POST returns null on 204,
GET parses `{"results":[{principal, role}]}`, DELETE returns null. Through the default error handler 400 is
`BadRequestException` "Malformed request: <native message>", 403 `ForbiddenException`, 404 and 409 `RESTException`,
a missing token `NotAuthorizedException`, and 500 `ServiceFailureException`. The table error handler turns 404 into
`NoSuchTableException` and 409 into `AlreadyExistsException`, which is misleading, so the capability client must not
use it for unlock. The client cannot send PATCH or PUT, and cannot read the multi-word field
`expirationEpochTimeSeconds` without `@JsonProperty`, because its mapper is kebab-case (both pinned).

On 1.12 (Spark 4.2 module), `HTTPClient` plus `AuthManagers.loadAuthManager(...).catalogSession(...)` built from the
catalog properties `uri` and `token` works: GET returns an empty list, a GRANT on an unshared table is a
`BadRequestException`, REVOKE and both unlock forms return 204, and a denied principal gets `ForbiddenException`. The
design's claim (section 7) that a 400 stays `IllegalArgumentException` over REST is wrong, so C2-3 must map it.

## B. The endpoint list (V10a)

Iceberg 1.12 with no list assumes the default endpoints (namespace writes, register, metrics, transactions) and adds
views only if `view-endpoints-supported=true` (read). With a list it uses exactly that list: existence checks use HEAD
if listed, and an unlisted route throws `UnsupportedOperationException("Server does not support endpoint: ...")`
before any request. The wire format is a JSON array of `"<METHOD> /v1/{prefix}/<path>"`. Iceberg 1.5.2 ignores it.

The list on the 1.5 line (and the 1.12 fork) is 16 routes: namespace list, load and HEAD; table list, create, load,
HEAD, commit and drop; the same six for views; and `POST /tables/rename`. On the 1.2 line it is the 6 read routes.
`openhouse.capabilities` is `policies,grants,unlock` on the 1.5 line and `grants,unlock` on the 1.2 line.
`IcebergRestServedRoutes` derives both from Spring's handler mappings: every method and pattern an IRC-package
controller maps under `/iceberg/v1`, except `/config`, `.../openhouse/...` and methods marked
`@IcebergRestRefusedRoute` (view rename, D27). Capabilities are the `@IcebergRestCapability` names on mapped methods.
So a route cannot be advertised without being served, or the reverse. It uses Spring types only, so the bean starts
on 1.2, and the list is added at JSON level.

Drift tests (`IrcConfigEndpointsTest`, 6 tests): the list equals the expected one, every listed route is served, every
unlisted 1.12 `Endpoint` answers 406, and an independent scan finds every IRC route listed or accounted for.

Client behavior after the change (ran):
- 1.5.2: no change apart from `openhouse.capabilities=policies,grants,unlock`.
- 1.12 with Spark 4.2 (`SparkIrcEndpointsTest`, 7 tests, with a Tomcat access log): `tableExists` and
  `namespaceExists` send only HEAD. CREATE VIEW, SELECT, SHOW VIEWS and DROP VIEW work where the gate is open. Where
  views are disabled, SHOW TABLES lists tables, SHOW VIEWS is empty and `GET .../views` is 200.
- Refused on the client, nothing sent: ALTER VIEW RENAME, CREATE NAMESPACE, ALTER NAMESPACE SET PROPERTIES,
  `registerTable` and `commitTransaction`. Before, each reached the server and got a 406.
- 1.12 with Spark 3.5 under `-PicebergFork`: view rename is refused on the client (on 1.5.2 a `RESTException` from the
  406), and `tableExists` sends HEAD and returns true (on 1.5.2 it loads, so a gated table gets 422). Tests state both
  with `IcebergLine`.

## Every difference from native behavior (ran)

1. A missing body: Spring's message names the IRC controller's method. A replica unlock 500 has no
   `java.lang.UnsupportedOperationException: ` prefix, as in the existing IRC mapping.
2. An unknown lock reason: native is 400 with an empty body, IRC is 400 "Invalid reason: X; expected one of [LEGACY,
   SYSTEM_ONLY]", because the spec requires a message. A 415 likewise carries Spring's message.
3. The ACL update is PATCH natively and POST over IRC. Client exception types are Iceberg's.
4. Successful IRC requests are in the service audit with URI `/iceberg/...` and service UNRECOGNIZED. Failed IRC
   requests are not, which is the known V2 and V3 gap. The table audit is equal.
5. The IRC view list where views are disabled answers 200 with an empty list. The native view list answers 404, and
   the other IRC view routes keep the 404. This changed three V4 pins (`IrcViewRoutesTest`,
   `IcebergRestCatalogViewTest`, `IcebergRestSparkSqlViewTest`). Reason: with views advertised, Spark 4.2 SHOW
   TABLES also lists views, and the 404 failed it in every database with views off, the default.
6. On the 1.2 line `policies` is no longer advertised, because it was advertised but never served.

Native routes: no status, message, stored value or write count changed. The native view list was not touched.

## Choices where the brief left room

- `@RequestBody` takes the native type, not `byte[]`, for the same converter and messages.
- The service uses `CapabilityNames` through a compileOnly dependency. The constants are inlined (`javap`).
- Reverse the empty view list by restoring the 404 in `IcebergRestViewHandler.listViews`.

## Test counts (ran)

| Suite | Before | After |
| --- | --- | --- |
| `:services:tables:test` | 1499 | 1528, 0 failed (+18 routes, +5 HTTPClient, +6 config) |
| Iceberg 1.2 and 1.5 fixture tests | 37 each | 39 each, 0 failed |
| Spark 3.5 `catalogTest`, default and `-PicebergFork` | 255; 8 failed on the base | 255, 0 failed, 1 skipped |
| `-PicebergFork` Spark 4.2 `test`, `extensionsTest` | 31, 10 | 38, 10, 0 failed |

Also passing: the golden tests, `IcebergRestBeanSignaturesTest`, `-PicebergFork` service compilation, `spotlessCheck`.

## Not run

- Spark 3.1 itests; Spark 3.5 `statementTest` and `test`; the 1.5 Java itest; the apps' tests; `:services:tables:test`
  under `-PicebergFork` (compiled only); real House Tables (MySQL); continuous integration on pull request 102.
- The full default Spark 3.5 `catalogTest` after the last two commits (three changed classes reran, 21, 0 failed).
- A red run of `extensionsTest` before its fix.
