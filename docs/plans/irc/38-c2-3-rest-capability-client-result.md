# REST capability client (C2-3): result

Bottom line: GRANT, REVOKE, SHOW GRANTS (table and database) and ALTER TABLE ... UNLOCK now work through
Iceberg's own `SparkCatalog type=rest` over an unmodified `RESTCatalog`. They work on Spark 3.5 (the default
build on Iceberg 1.5.2, and the opt-in build on 1.12) and on Spark 4.2 under the opt-in build. With the policy
statements, every OpenHouse statement now works on the target platform. Successes match the OpenHouse catalog
exactly. Failures keep the native message but arrive as Iceberg exception types. Written by a delegated agent
from branch `irc/c2-3-rest-capability-client` (pull request 103, merged as c174e388). "Ran" means run on JDK 17.
"Read" means taken from code or earlier records.

## What was proven (ran)

- Spark 3.5: `IcebergRestGrantsUnlockSqlTest` runs 36 statement pairs on twin tables, one through the OpenHouse
  catalog and one through `RESTCatalog`.
  - Where both succeed, the outcome is equal, SHOW GRANTS rows included.
  - Where both fail, the REST failure carries the native message that the legacy failure carries.
  - After every statement, the stored grants and locks of the two databases are equal, read from the service.
- Spark 4.2: `SparkIrcGrantsUnlockTest` pins the same outcome strings as the 3.5 test and checks stored grants and
  locks through the service's control API. 13 tests, green.

## How the client is arranged

- One shared class, `RestCapabilityClient`, in the capabilities module, plus a per-line `RestTransport` (HTTP
  client and authentication). The module compiles `src/iceberg-1.5/java` by default and `src/iceberg-1.12/java`
  under `-PicebergFork`. Each build uses one Iceberg line for every module, so a compile-time switch works (read).
- It uses only Iceberg classes the engine's runtime already ships: `HTTPClient`, `RESTClient`, `ResourcePaths`,
  `RESTUtil`, the error handlers, and `OAuth2Util` on 1.5 or `AuthManagers` and `AuthSession` on 1.12. There is no
  Spring, no Java client and no generated client.
- Authentication is the only API difference between the lines (ran: javap of 1.5.2, sources of 1.12). On 1.5 the
  client uses the `token` or exchanges the OAuth `credential`. On 1.12 it uses the catalog session of
  `AuthManagers.loadAuthManager(name, props)`.
- The resolver builds the client from `RESTCatalog.name()` and `RESTCatalog.properties()`, the map merged with
  the server's config: the `uri`, the `prefix` and namespace separator, and the `header.*` values.
- Rejected: reflection on `AuthManagers` (fragile) and the deprecated `OAuth2Util.AuthSession`, which ignores
  `rest.auth.type`.
- The code names no Jackson, Apache HTTP or Caffeine type, so relocation in the shaded runtimes does not matter.
  It works inside `iceberg-spark-runtime-3.5` 1.5.2 and 1.12 and `iceberg-spark-runtime-4.2` 1.12 (ran).

## What the user sees on failure: legacy versus REST (ran, from the test report's outcome lines)

| Case | OpenHouse catalog (legacy) | REST catalog (this change) |
| --- | --- | --- |
| GRANT on an unshared or locked table (400) | `IllegalArgumentException: 400 , {json}` | `IllegalArgumentException: <native message>` |
| GRANT, REVOKE, UNLOCK by a denied principal (403) | `WebClientResponseWithMessageException: 403 Forbidden , {json}` | `ForbiddenException: Forbidden: <native message>` |
| Missing table (404) | `WebClientResponseWithMessageException: 404 Not Found , {json}` | `NoSuchTableException: <native message>` |
| UNLOCK with the wrong reason (409) | `WebClientResponseWithMessageException: 409 Conflict , {json}` | `IllegalStateException: <native message>` |
| Multi-level namespace | `ValidationException: Input namespace has more than one levels` | identical |
| UNLOCK with the switch off, or REASON BOGUS | `UnsupportedOperationException`, `OpenhouseParseException` | identical |
| No token (401) | `WebClientResponseWithMessageException` (read) | `NotAuthorizedException` (ran: unit test) |
| Replica unlock (500) | `WebClientResponseWithMessageException` (read) | `ServiceFailureException` (ran: unit test with a stub) |

Successes are identical on both paths (ran): GRANT and REVOKE of SELECT, ALTER and MANAGE GRANTS on a table; GRANT
and REVOKE of CREATE TABLE on a database, with or without tables; SHOW GRANTS rows, also for a denied principal;
REVOKE on a locked table; UNLOCK of LEGACY, of SYSTEM_ONLY with its reason, and of an unlocked table.

## Every remaining difference

1. Exception types for 401, 403, 404, 409 and 500 are Iceberg's (and `IllegalStateException` for 409). The Java
   client's type cannot be kept without importing the Java client and Spring. Smoke test s27 pins it on the legacy
   path only.
2. REST carries the bare native message, with Iceberg's usual prefix for 401, 403 and 500. Legacy carries
   `"<status> <reason> , <native JSON body>"`. For 400 the type is kept but the `400 , {json}` wrapper is lost.
3. The client exchanges an OAuth credential on each statement (1.5) or opens a fresh catalog session (1.12). It
   does not share the catalog's cached token, so there is one extra token request per statement. The identity is
   the same.
4. REST sends the catalog's `header.*` and Iceberg's `X-Client-Version`. The Java client's own client-name headers
   come only from `header.*`. The service audit records these requests under `/iceberg/...` (read).
5. A `RESTCatalog` built with a custom non-HTTP `RESTClient` builder still gets an HTTP capability client.
6. A client-side `openhouse.capabilities` claim counts when the server sends no override. A client that claims
   grants against a server without the routes gets that server's error instead of "does not support". This server
   always sends the override.

## Existing paths and the default build (ran)

| Suite | After |
| --- | --- |
| capabilities module `test` | 45 (28 before, plus 17 new) |
| Spark 3.5 itest `catalogTest` | 269, 1 skipped, 0 failed (255 before, plus 14 new) |
| Spark 3.5 itest `statementTest` | 137, 0 failed |
| Spark 3.1 itest `catalogTest` and `statementTest` | 87 (1 skipped) and 126, 0 failed |
| `-PicebergFork` Spark 4.2 runtime `test` | 163 / 0 |
| `-PicebergFork` Spark 4.2 itest `test` and `extensionsTest` | 38 / 0 and 23 / 0 (13 new) |
| `-PicebergFork` Spark 3.5 itest `catalogTest` | 269, 1 skipped, 0 failed |
| `-PicebergFork assemble testClasses spotlessCheck` | pass |

- The before counts are earlier records' figures, not re-run. Smoke tests s17 and s27 passed unchanged.
- Default-build classpaths of all 60 projects were dumped before and after. Only the capabilities module changed:
  it gains `iceberg-core` and the source directory `src/iceberg-1.5/java`. No other project changed.

## Pins changed

- Spark 3.5: the grant and unlock "unsupported" pins became "reaches the server through the capability client"
  and now expect "ok". Spark 4.2: the two `...UntilTheCapabilityClientLands` pins flipped the same way, and the
  offered set is now policies, grants and unlock.

## Choices where the brief left room

1. 409 maps to `IllegalStateException`, because `AlreadyExistsException` is misleading and the default handler's
   message is vague.
2. 400 maps to `IllegalArgumentException` on every route, which keeps the type that smoke test s17 pins.
3. No caching: a new client per resolution and a transport per call, so no leaked pools or stale tokens.
4. The Java client's pre-request checks are reproduced with the same exception and message.
5. A test-only authorization handler, `RecordingAuthorization`, stores grants so stored state can be compared.
6. The post-merge opt-in job gains the capabilities module's tests, since the 1.12 transport compiles only there.

## Not run

The full `./gradlew build`; `:services:tables:test` (no service code changed); the Java 1.5 itest; the 3.5 itest
plain `test` task in full; the Spark 3.5 runtime tests under `-PicebergFork`; CI on pull request 103; a red run of
the Spark 4.2 `extensionsTest` before the resolver change.
