# C2-3: REST capability client for GRANT, REVOKE, SHOW GRANTS and UNLOCK over plain RESTCatalog (record)

- **Pull request:** https://github.com/mkuchenbecker/openhouse/pull/103 (draft, into `irc-integration`)
- **Head:** `211bf24ba11c30f53748c6942d8488559788dba7`, branch `irc/c2-3-rest-capability-client`
- **Base:** `origin/irc-integration` at 6015e111. It had not moved at the final fetch, so nothing was merged.
- **Marks:** [ran] means executed here, on JDK 17. [read] means taken from code or earlier records.

## Bottom line

**What now works [ran].** GRANT, REVOKE, SHOW GRANTS (table and database) and ALTER TABLE ... UNLOCK [REASON ...] work through Iceberg's own `SparkCatalog type=rest` over an unmodified `RESTCatalog`.
- **Spark 3.5:** default build (Iceberg 1.5.2) and `-PicebergFork` (1.12).
- **Spark 4.2:** `-PicebergFork`.
- Together with the policy statements (V5), every OpenHouse statement now works on the target platform.

**Equivalence evidence [ran].**
- **Spark 3.5:** `IcebergRestGrantsUnlockSqlTest` runs 36 statement pairs on twin tables, through the OpenHouse catalog and through `RESTCatalog`.
  - Where both succeed, the outcome is equal, SHOW GRANTS rows included.
  - Where both fail, the REST failure carries the native message that the legacy failure carries.
  - After every statement, the stored grants and locks of the two databases are equal. They are read from the service.
- **Spark 4.2:** `SparkIrcGrantsUnlockTest` pins the very outcome strings the 3.5 test pins, and checks the stored grants and locks through the service's control API. 13 tests, green.

**How the client is arranged.**
- One shared class, `RestCapabilityClient`, in the capabilities module, plus a per-line `RestTransport` (HTTP client plus authentication).
- The module compiles `src/iceberg-1.5/java` by default and `src/iceberg-1.12/java` under `-PicebergFork`.
- Dependencies: the capabilities module plus Iceberg classes the engine's runtime already ships: `HTTPClient`, `RESTClient`, `ResourcePaths`, `RESTUtil`, `ErrorHandler(s)`, `OAuth2Util` on 1.5, and `AuthManagers` / `AuthSession` on 1.12. `iceberg-core` is compileOnly.
- No Spring, no Java client, no generated client.

**Connection and authentication.** The resolver builds the client from `RESTCatalog.name()` and `RESTCatalog.properties()`, the map merged with the server's config. From those, the client uses:
- the `uri`;
- the `prefix` and namespace separator, through `ResourcePaths.forCatalogProperties`;
- the `header.*` values;
- on 1.5, the `token`, or the OAuth `credential` exchanged at `oauth2-server-uri`, as `RESTSessionCatalog` 1.5.2 does at initialization;
- on 1.12, the catalog session of `AuthManagers.loadAuthManager(name, props)`.

## Arrangement across Iceberg lines (choice and why)

- **What differs between lines.** Authentication is the only API difference that matters:
  - 1.5.2 has no `AuthManager`; auth there is `OAuth2Util` headers.
  - On 1.12 the right path is `AuthManager` (OAuth2 refresh, basic, sigv4, custom `rest.auth.type`).
  - Everything else is common to both lines: `HTTPClient.builder(Map).uri().withHeaders().build()`, the 5-argument get, post and delete methods with `Map` headers, `ResourcePaths`, `RESTUtil.encodeString`, `ErrorHandler.parseResponse`, `ErrorResponse`, `OAuth2Util.authHeaders` [ran: javap of 1.5.2, sources of 1.12].
- **Why a compile-time switch works.** Each build uses exactly one Iceberg line for every module (settings.gradle, `gradle/iceberg-fork.gradle`) [read]. The capabilities module, which goes into both uber jars, can therefore compile the transport for that line.
- **Alternatives rejected:**
  - Reflection on `AuthManagers`: fragile, and string class names already broke once under relocation.
  - One implementation on `OAuth2Util.AuthSession`, which is deprecated in 1.12 and ignores `rest.auth.type`.
- **Shading.** The code names no Jackson, Apache HTTP or Caffeine type: DTOs are plain fields read by Iceberg's own mapper, and no `withObjectMapper` is used. It also uses no string class names. The relocation in the shaded runtimes is therefore irrelevant [ran: it works inside `iceberg-spark-runtime-3.5` 1.5.2, `iceberg-spark-runtime-3.5` 1.12 and `iceberg-spark-runtime-4.2` 1.12].
- **The resolver change.** It is the same 8 lines in both resolver copies (3.5 and 4.2).

## What the user sees on failure: legacy vs REST [ran, from the test report's OUTCOME lines; {db} is the database]

| Case | OpenHouse catalog (legacy) | REST catalog (this change) |
|---|---|---|
| GRANT on an unshared table (400) | `IllegalArgumentException: 400 , {"status":"BAD_REQUEST",...,"message":"{db}.unshared is not a shared table",...}` | `IllegalArgumentException: {db}.unshared is not a shared table` |
| GRANT on a locked table (400) | `IllegalArgumentException: 400 , {...,"message":"{db}.grant_locked is in locked state and grants are not allowed for sharing",...}` | `IllegalArgumentException: {db}.grant_locked is in locked state and grants are not allowed for sharing` |
| GRANT or REVOKE on a table by a denied principal (403) | `WebClientResponseWithMessageException: 403 Forbidden , {...,"message":"Operation on table {db}.shared failed as user irc_stranger is unauthorized",...}` | `ForbiddenException: Forbidden: Operation on table {db}.shared failed as user irc_stranger is unauthorized` |
| GRANT on a database by a denied principal (403) | `WebClientResponseWithMessageException: 403 Forbidden , {...Operation on database [{db}] failed as user [irc_stranger] is unauthorized...}` | `ForbiddenException: Forbidden: Operation on database [{db}] failed as user [irc_stranger] is unauthorized` |
| UNLOCK by a denied principal (403) | `WebClientResponseWithMessageException: 403 Forbidden , {...is unauthorized to act on Locked table...}` | `ForbiddenException: Forbidden: Operation on table {db}.denied_lock failed as user irc_stranger is unauthorized to act on Locked table` |
| GRANT, SHOW GRANTS or UNLOCK on a missing table (404) | `WebClientResponseWithMessageException: 404 Not Found , {...User table {db}.missing cannot be found...}` | `NoSuchTableException: User table {db}.missing cannot be found` |
| UNLOCK without a reason on a SYSTEM_ONLY lock (409) | `WebClientResponseWithMessageException: 409 Conflict , {...Table {db}.t has a SYSTEM_ONLY lock. Remove it with reason SYSTEM_ONLY....}` | `IllegalStateException: Table {db}.t has a SYSTEM_ONLY lock. Remove it with reason SYSTEM_ONLY.` |
| UNLOCK REASON SYSTEM_ONLY on a LEGACY lock (409) | `WebClientResponseWithMessageException: 409 Conflict , {...has a LEGACY lock, not SYSTEM_ONLY....}` | `IllegalStateException: Table {db}.t has a LEGACY lock, not SYSTEM_ONLY.` |
| Multi-level namespace, GRANT or SHOW | `ValidationException: Input namespace has more than one levels {db}.x` | identical |
| UNLOCK with the switch off | `UnsupportedOperationException: ALTER TABLE UNLOCK is disabled...` | identical |
| UNLOCK REASON BOGUS | `OpenhouseParseException: Invalid lock reason 'BOGUS'...; line 1 pos 46` | identical |
| No token (401) | `WebClientResponseWithMessageException` [read] | `NotAuthorizedException: Not authorized: <msg>` [ran: unit test] |
| Replica unlock (500) | `WebClientResponseWithMessageException` 500 [read] | `ServiceFailureException: Server error: <type>: <msg>` [ran: unit test with stub; V5-3 ran the server side] |

**Successes are identical on both paths [ran].** This covers:
- GRANT and REVOKE of SELECT, ALTER (TO/FROM PUBLIC in any case) and MANAGE GRANTS on a table;
- GRANT and REVOKE of CREATE TABLE on a database with tables, and on a database with no table yet;
- SHOW GRANTS rows (`[SELECT,gu_reader], [ALTER,PUBLIC], [MANAGE GRANTS,gu_admin]`), also for a denied principal, because listing makes no access decision;
- REVOKE on a locked table;
- UNLOCK of LEGACY, of LEGACY with `REASON Legacy`, and of SYSTEM_ONLY with `REASON system_only`;
- UNLOCK of an unlocked table, with either reason.

## Every remaining difference

1. **Exception types for 401, 403, 404, 409 and 500.** Iceberg types (and `IllegalStateException` for 409) replace the Java client's `WebClientResponseWithMessageException`. That type and its `getStatusCode()` cannot be kept without importing the Java client and Spring. The smoke test s27 pins it on the legacy path only.
2. **Message text.**
   - REST carries the bare native message, with Iceberg's usual prefix for 401, 403 and 500, as every other REST-catalog request does.
   - Legacy carries `"<status> <reason> , <native JSON body>"`.
   - For 400 the type is kept (`IllegalArgumentException`), but the text loses the `400 , {json}` wrapper.
3. **OAuth credential.** The client exchanges the credential for a token on each statement (1.5), or opens a fresh `AuthManager` catalog session (1.12). It does not share the catalog's cached token: one extra token request per statement. The identity is the same.
4. **Headers.** REST sends the catalog's `header.*` and Iceberg's `X-Client-Version` / `X-Client-Git-Commit-Short`. The Java client's own client-name and similar headers come only from `header.*`. The server's service audit records IRC requests under `/iceberg/...` (V5-3 difference 7) [read].
5. **A `RESTCatalog` built with a custom `RESTClient` builder** (not HTTP) still gets an HTTP capability client. The in-process `OpenHouseRestCatalog` is matched earlier by its interfaces, so it is not affected.
6. **A client-side `openhouse.capabilities` claim counts when the server sends no override** (C2 known gap). With grants and unlock now offered, a client that claims them against a server without the routes gets that server's error instead of "does not support". This server always sends the override.
7. **Two `DELETE` forms.** UNLOCK without REASON and `REASON LEGACY` both use the reasonless route, as the Java client does. That is not a difference, but it is noted.
8. **Spark 4.2 parse errors.** The positions differ from 3.5 only because the table names differ. The OpenHouse parse messages are the same (N3-x1).

## Legacy and in-process paths and default build unchanged [ran]

| Suite | Before (from records [read]) | After [ran] |
|---|---|---|
| capabilities module `test` | 28 | 45 (+17 `RestCapabilityClientTest`) |
| Spark 3.5 runtime `test` | 24 | 27 (the REST cases: 1 replaced by 2 parameterized, plus 2 new) |
| Spark 3.5 itest `catalogTest` | 255, 1 skipped (V5-3 record) | 269, 1 skipped, 0 failed (+14 `IcebergRestGrantsUnlockSqlTest`; 2 pins renamed and flipped) |
| Spark 3.5 itest `statementTest` | 137 | 137, 0 failed |
| Spark 3.1 itest `catalogTest` | 87, 1 skipped | 87, 1 skipped, 0 failed |
| Spark 3.1 itest `statementTest` | 126 | 126, 0 failed |
| `-PicebergFork assemble testClasses spotlessCheck` | pass | pass |
| `-PicebergFork` capabilities `test` | n/a | 45 / 0 (executed earlier; FROM-CACHE in the final run, same inputs) |
| `-PicebergFork` Spark 4.2 runtime `test` | 160 | 163 / 0 |
| `-PicebergFork` Spark 4.2 itest `test` | 38 | 38 / 0 |
| `-PicebergFork` Spark 4.2 itest `extensionsTest` | 10 | 23 / 0 (+13 `SparkIrcGrantsUnlockTest`) |
| `-PicebergFork` Spark 3.5 itest `catalogTest` | 255, 1 skipped | 269, 1 skipped, 0 failed |

- **Smoke tests.** s17 and s27 (`LegacyPathSparkSqlSmokeTest`, `RestShimPathSparkSqlSmokeTest`) are inside the 3.5 `catalogTest` and passed in both builds, unchanged.
- **Before counts.** These are the previous records' figures, not re-run on 6015e111. The deltas equal exactly the tests this change adds.
- **Default-build classpaths.** I dumped every resolvable configuration and source set of all 60 projects before any change and at the end (scratchpad `c23/dump.gradle`, `cp-before`, `cp-after`, `cp.diff`). Only `:integrations:java:openhouse-catalog-capabilities` changed:
  - `compileClasspath` and `testCompileClasspath` gain `iceberg-core-1.5.2.24`;
  - `testRuntimeClasspath` gains iceberg-core and its runtime dependencies;
  - main gains the source directory `src/iceberg-1.5/java`.
  - No other project changed, including the Spark 3.5 runtime's `fatJarPackagedDependencies`.

## Pins and assertions that changed

- **Spark 3.5 `IcebergRestOpenHouseStatementsTest`:**
  - `grantStatementsAreUnsupported` became `grantStatementsReachTheServerThroughTheCapabilityClient`. Every grant statement is now "ok", after sharing is set through the OpenHouse catalog.
  - `unlockIsUnsupported` became `unlockReachesTheServerThroughTheCapabilityClient`. The disabled message is unchanged; with the switch on, "ok" for both reasons.
- **Spark 4.2 `SparkIrcOpenHouseStatementsTest`:** the two `...UntilTheCapabilityClientLands` pins flip the same way. `theResolverReadsTheServersAdvertisedCapabilities` now expects offered = {policies, grants, unlock}.
- **Resolver unit tests on both lines:**
  - `restCatalogAdvertisingGrantsAndUnlockStillOffersNeither...` became `...OffersThemThroughTheCapabilityClient`, run with cache on and off.
  - New: `theCapabilityClientIsBuiltFromTheCatalogsOwnMergedProperties` and `eachCapabilityIsOfferedOnlyWhereItIsAdvertised`.
  - `restCatalogAdvertisingOnlyOtherNamesRefusesPolicies` additionally asserts offered = {grants, unlock}.

## Choices where the brief left room

1. **409 maps to `IllegalStateException`** with the native message. The table handler's `AlreadyExistsException` is misleading, and the default handler's `RESTException` "Unable to process:" is vague.
2. **400 maps to `IllegalArgumentException`** on every route. That keeps the legacy type for GRANT and REVOKE, which smoke test s17 pins. For SHOW GRANTS and UNLOCK the legacy type would have been `WebClientResponseWithMessageException`, but no SQL statement produces a 400 there.
3. **No caching.** A new client per resolution, and a transport per call: no leaked pools, no refresh threads, no stale tokens. The cost is one HTTP client build per statement, plus one token request per statement for credential-based catalogs.
4. **The Java client's pre-request checks are reproduced** with the same exception and message (multi-level namespace `ValidationException`; unknown privilege `NoSuchElementException`, which SQL cannot reach because the grammar refuses it first). Role-to-privilege mapping for SHOW GRANTS uses the module's `Privileges.fromRole`, as the Java client does.
5. **Test fixture for stored grants.** `RecordingAuthorization` is a test-only authorization handler (two copies: the 3.5 itest test sources and the 4.2 itest service sources). The default fixture drops grants, so stored state could not be compared otherwise. In the 4.2 service it replaces `DenyStrangerConfig`, with the same denial behavior. The denied principal is named `irc_stranger` on both lines, so the outcome strings match literally.
6. **Spark 4.2 comparison.** The 4.2 test pins the same literal strings as the 3.5 test. There is no live Spark 3.5 in the 4.2 JVM.
7. **CI.** The opt-in post-merge job gains `:integrations:java:openhouse-catalog-capabilities:test`, since the 1.12 transport compiles only under the switch. This is not a new task.
8. **Docs.** I updated `docs/development/iceberg-fork.md`, which said grants and unlock fail over `RESTCatalog`.
9. **Commit trailer.** I used `Co-Authored-By: Claude Opus 5.5`, as the session's system attribution says and as N3-x1 did. The brief's "Claude Fable 5.2" can be applied in the hand-built merge commit if wanted.
10. **D28.** It does not exist on the planning branch (head 66b1e6cd; the decisions files end at D27) [ran: git grep]. The record therefore cites D23, D24, D26 and D18.

## Stopped on

Nothing.

## Merge commit (ready to use)

Title: `Merge C2-3: GRANT, REVOKE, SHOW GRANTS and UNLOCK work over plain RESTCatalog on Spark 3.5 and 4.2 (#103)`

Body: see the hand-back message.

## Not run

- The full `./gradlew build`.
- `:services:tables:test`: no service code changed.
- The Java 1.5 itest.
- The 3.5 itest `test` task in full: its `catalogTest` and `statementTest` ran, the plain `test` filter did not.
- The Spark 3.5 runtime tests under `-PicebergFork`.
- CI on pull request 103.
- A cold `publish-local.sh`: it was a no-op, already published.
- A red run of the Spark 4.2 `extensionsTest` before the 4.2 resolver change. Only the 4.2 runtime unit test was run red (5 failures); the itest's test sources compiled.

No JVM of mine is left running: no `IrcExtensionsTestService` or test executor of mine; the Gradle daemons use the 60 s idle timeout.
