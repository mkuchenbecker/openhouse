# C2 + V5: OpenHouse capabilities over IRC (design)

Checkout read: `/home/user/oh-cap` at 14f23870 (`origin/irc-integration`), Iceberg `origin/openhouse-1.12.0` (28742df3e), planning branch `docs/plans/irc/`.
Marks: **read** = taken from code. **ran** = executed here. Nothing was built or tested for this report; every claim is read unless it cites an earlier report's run.

## Bottom line: recommended design

The engine runs Iceberg's own `SparkCatalog` + `RESTCatalog` and the OpenHouse SQL-extensions jar, with no OpenHouse catalog class. The design has three parts:

1. **Policies use the standard IRC commit route.** This covers retention, sharing, history, replication set and unset, and column tags. The exec nodes stay as they are: they already write the request property `updated.openhouse.policy` with `updateProperties()`. The server's IRC commit and create routes start treating that key as a request:
   - parse the patch;
   - merge it onto the stored policies with the client's merge rules;
   - run the native policy validators;
   - pass the result as `CommitContext.requestedPolicies`;
   - never store the key.
2. **Grants, SHOW GRANTS and UNLOCK go through a few OpenHouse routes under the IRC base.** The paths are `v1/{prefix}/namespaces/{ns}[/tables/{t}]/openhouse/...`. They use the IRC error shape and the native request and response bodies, and they call the same API handlers as the native routes, so authorization, validation and audit are the same. A small capability client in the extensions jar calls them. It builds Iceberg's `HTTPClient` and `AuthManager` from `RESTCatalog.properties()`, so it has the same `uri`, prefix, token and `header.*`.
3. **The server advertises what it serves in the config response,** as `openhouse.capabilities=policies,grants,unlock`. The SQL extensions find each feature by capability name through one resolver on all three paths:
   - legacy and in-process: the catalog implements the interface;
   - native REST: the advertised name, plus the capability client.

What a user types in Spark SQL is unchanged. Spark 3.5 legacy and rest-shim behavior is unchanged. Spark 4.2 needs a port of the extensions to Scala 2.13 and ANTLR 4.13 that depends only on a new, Spring-free capabilities module.

## 1. Inventory (statement → exec → catalog seam → client call → service → stored state)

Grammar: `OpenhouseSqlExtensions.g4:25-36` (read). There is no LOCK statement; locks are set only through the native `POST .../lock` route (`TablesController.java:413`, read).
"Policy patch" means `updateProperties().set("updated.openhouse.policy", <json>)` on a `SparkTable` whose properties contain `openhouse.tableId` (`Set*PolicyExec`, `SetColumnPolicyTagExec`, `UnSetReplicationPolicyExec`, for example `SetRetentionPolicyExec.scala:46,66`; read).

| Statement / behavior | Today: legacy `OpenHouseCatalog` and rest-shim (read) | Today: plain `RESTCatalog` (read) | Under the design |
|---|---|---|---|
| `ALTER TABLE t SET POLICY (RETENTION=… [WITH TIMEZONE] [ON COLUMN … WHERE PATTERN=…])` | Policy patch. `OpenHouseTableOperations.buildUpdatedPolicies` (`:357-400`) merges it client-side onto the base's `policies`, drops the key (`:289-294`) and sends `PUT /v1/databases/{db}/tables/{t}` with `policies`. The service validates (`OpenHouseTablesApiValidator:213,371`) and stores the `policies` property. Rest-shim: same, through the adapter to the backing catalog. | The patch goes as `set-properties` to `POST /iceberg/v1/namespaces/{db}/tables/{t}`. The key is not reserved (`BasePreservedKeyChecker:22`, prefix `openhouse.` at `HouseTableSerdeUtils:20`). `CandidateViews:57` copies it into the table properties and `IcebergRestWriteHandler:164-170` passes no policies. **The policy is silently not applied and a stray property is stored.** | The same patch through the IRC commit, translated by the server: merged, validated, then stored as `policies`. The key is dropped. |
| `SET POLICY (SHARING=TRUE/FALSE)` | Policy patch `{"sharingEnabled":…}` | As above: silently stored | As above |
| `SET POLICY (HISTORY MAX_AGE=… VERSIONS=…)` | Policy patch `{"history":…}` | As above | As above |
| `SET POLICY (REPLICATION=({destination:…, interval:…},…))` / `UNSET POLICY (REPLICATION)` | Policy patch `{"replication":…}` / `{"replication":{}}`. The validator includes `ReplicationConfigValidator` and the RTAS-vs-replication incompatibility (`Validator:265-300`). | As above | As above, with the same validators and feature check |
| `ALTER TABLE t MODIFY COLUMN c SET TAG = (…)` | Policy patch `{"columnTags":{c:…}}`, merged per column (`:376-387`) | As above | As above |
| `GRANT/REVOKE <priv> ON TABLE/DATABASE x TO/FROM p` | `IcebergCatalogMapper` reflects out of `SparkCatalog`/`CachingCatalog` (`:28-34`). The catalog is `SupportsGrantRevoke` (rest-shim delegates, `OpenHouseRestCatalog.java:71,378`). The privilege maps to a role (`Privileges.java:10-14`). The client sends `PATCH /v1/databases/{db}[/tables/{t}]/aclPolicies` (`TablesController:348`, `DatabasesController:105`). The service checks `UPDATE_ACL`, sharing and lock (`TablesServiceImpl:379-399`) and records an audit (`TableAuditAspect` on `TablesApiHandler.updateAclPolicies`). | `UnsupportedOperationException: Catalog 'x' does not support Grant Revoke Statements` | The resolver finds `grants`. The capability client sends `POST …/openhouse/acl-policies` with the native body. The new route calls the same handler, so the checks and audit are the same. |
| `SHOW GRANTS ON TABLE/DATABASE x` | `SupportsGrantRevoke.get*AclPolicies` → `GET …/aclPolicies` | Unsupported | `GET …/openhouse/acl-policies` |
| `ALTER TABLE t UNLOCK [REASON r]` (gated by `spark.sql.openhouse.unlock.enabled`) | `ResolveUnlockTable:25` requires `SupportsUnlock`. `UnlockTableExec` (no load) sends `DELETE /v1/…/lock[/{reason}]` (`TablesController:445,470`), then invalidates the table. | "does not support UNLOCK" | The resolver finds `unlock` → `DELETE …/openhouse/lock[/{reason}]` |
| Capability declaration (`X-OpenHouse-Client-Capabilities`) | `OpenHouseCatalog.capabilities()` declares `column-defaults` on the legacy line and nothing on the new line (Spark `OpenHouseCatalog.java`; D11a). `header.*` can add more (`CatalogRequestHeaders:103-118`). | Declares nothing unless `header.X-OpenHouse-Client-Capabilities` is set. The gate answers 422 on load and commit (V2 and V3 ran this). | Unchanged. Plain clients declare nothing. |
| `client-name`, `client-version`, `action-type`, `app-id` headers | Set from catalog properties (`CatalogRequestHeaders:38-43`) | Only as `header.X-Client-Name` and similar | Unchanged (a configuration spelling, not SQL) |
| Replica table type, intermediate schemas, `openhouse.isTableReplicated` | Java client logic (`OpenHouseTableOperations:297-318`), used by the replication job | Not expressible (V3: no table type) | Out of scope. It stays on the OpenHouse Java client. |

## 2. The capability model

- **A capability is a lower-case name with a direction.**
  - **Client capability:** client → server, in the header `X-OpenHouse-Client-Capabilities`. It means "this client implements behavior X that a table may require" (`required-capability.<name>=true`). The server enforces only known names (`ClientCapabilityGate:25-31`). Today `column-defaults` is the only one.
  - **Server capability:** server → client, in config. It means "this endpoint serves OpenHouse feature X". The names are `policies`, `grants` and `unlock`. Column tags, history and replication are parts of `policies`, not separate names.
  - Both directions share one constants class in the new module, so a name means one thing.
- **Advertising.** `IcebergRestApiHandler.config()` (`:75-79`) adds `openhouse.capabilities` to the `ConfigResponse` **overrides**, so `RESTCatalog.properties()` shows what the server actually serves. The client merges it (`RESTSessionCatalog.java:218` on 1.12; `RESTCatalog.properties()` at `:109` returns the merged map).
  - It is not put in the `endpoints` list. The spec says entries are paths "from OpenAPI REST spec" (`rest-catalog-open-api.yaml:2312-2316`). Also, once a server sends any `endpoints`, 1.12 uses only that list (`RESTSessionCatalog.java:225-237`), which is V10's job.
  - The spec has no vendor-extension slot other than config properties (read: no `x-` mechanism for routes).
  - The in-process adapter's `CONFIG` (`OpenHouseRestAdapter:287-288`) advertises the same key, so the rest-shim exercises the same parsing.
- **Declaring.** Unchanged. A plain `RESTCatalog` declares nothing. A user who sets `header.X-OpenHouse-Client-Capabilities=column-defaults` on a stock `RESTCatalog` gets no defaults applied (V2 report, difference 7). Documentation must say not to declare a client capability the client does not implement.
- **`required-capability.*` over IRC.** It means the same as natively. A table that requires a client capability is refused with 422 `CLIENT_CAPABILITY_MISSING` on load and on commit for a client that does not declare it (V2 and V3 ran this). Server capabilities never gate tables. A future feature that changes how a client must read or write a table is added as a client capability. A feature the server performs is added as a server capability.
- **Discovery by the SQL extensions.** One `OpenHouseCapabilities.resolve(TableCatalog)` replaces every `IcebergCatalogMapper` call site:
  1. **Legacy `OpenHouseCatalog`:** reports `{policies, grants, unlock}` because it implements the interfaces. Policy patches are translated client-side.
  2. **Rest-shim `OpenHouseRestCatalog`:** the same through delegation. It is found by `instanceof` before any property check, so it keeps today's behavior.
  3. **Native `RESTCatalog`:** reads `properties().get("openhouse.capabilities")`. For `grants` and `unlock` it builds a cached `OpenHouseRestCapabilityClient` from `properties()`.
  - In every case a missing name gives today's `UnsupportedOperationException` text.
  - The policy execs ask the resolver for `policies` before committing. On an old IRC server they fail loudly instead of storing a stray property.
- **What "composes with REST" means here.**
  - (a) The capability client is a sidecar to an unmodified `RESTCatalog`. There is no subclass, so any Iceberg version's `RESTCatalog` and Spark's own `SparkCatalog` work.
  - (b) It uses the same `uri`, `prefix` (`ResourcePaths.forCatalogProperties`), `AuthManager` (`AuthManagers.loadAuthManager`, `AuthManagers.java:39`), `header.*` (`RESTUtil.configHeaders`, `:433`) and `HTTPClient` (`HTTPClient.builder`, `:499`).
  - (c) Its routes are sub-resources of the IRC table and namespace paths, return the IRC `ErrorResponse`, and are parsed by `ErrorHandlers`.
  - (d) Policies ride the standard commit, so requirements, the 409 retry, audit and idempotency come from IRC.
  - (e) Advertising uses the standard config response.
  - (f) Every route uses GET, POST or DELETE. Iceberg's `RESTClient` has no PATCH or PUT (`HTTPRequest.java:37-40`, `RESTClient.java`), and the native ACL update is PATCH.

## 3. Server changes (V5)

1. **Policy patch on IRC commit, create and staged-create completion** (`IcebergRestWriteHandler`).
   - If the candidate's properties contain `updated.openhouse.policy`:
     - remove it;
     - parse it, with the same accepted shapes as the client's Gson parse including the system-only fallback (`OpenHouseTableOperations:395-405`);
     - merge it onto `base.getPolicies()` with exactly the client's five-field rules: retention, sharing, columnTags (per column, patch wins), replication, history. A null stored policy means the patch becomes the policy.
   - Then:
     - build a request-body view (schema, policies, properties);
     - run `validatePolicies` and `validateFeatureCompatibility`;
     - set `CommitContext.requestedPolicies` (`TableCommitService:59-62`).
   - `TableCommitCore` then applies the lock-flip refusal (`:66-68,108-118`), privileges, the SYSTEM_ONLY lock, the capability gate and persistence unchanged.
   - Rules that must keep holding:
     - the raw `policies` key and `openhouse.*` keys stay the native 400 (V3);
     - a legacy-locked table is refused with the native message;
     - an invalid retention, history or replication value gets the native 400 message;
     - RTAS with replication is incompatible;
     - the stored `policies` string is byte-equal to what the native PUT stores (it is server-formatted: `OpenHouseInternalRepositoryImpl:739`; smoke test s16 pins the format).
   - Audit: the existing `TableCommitService.commit` advice (V3).
2. **Routes** in `IcebergRestCatalogController`, before the `/**` catch-all (`:210`), each delegating to the existing API handler:
   - `GET|POST v1/namespaces/{ns}/tables/{t}/openhouse/acl-policies` → `TablesApiHandler.getAclPolicies` / `updateAclPolicies`;
   - `GET|POST v1/namespaces/{ns}/openhouse/acl-policies` → the `DatabasesApiHandler` equivalents;
   - `DELETE v1/namespaces/{ns}/tables/{t}/openhouse/lock[/{reason}]` → the lock handler.
   - Bodies are the native `UpdateAclPoliciesRequestBody` and `GetAclPoliciesResponseBody`. Errors go through `IcebergRestErrors`, with native status and message.
   - Multi-level namespace: 404, as on the read routes.
   - The token interceptor already covers `/iceberg/**` (`IcebergRestAuthenticationInterceptor`).
3. **Config** advertises `openhouse.capabilities`. Each name is added only in the item that makes it true.
4. **Possible V3 gap, to check first (read only):** IRC updates never call `validateUpdateTable`; only `OpenHouseTablesApiHandler:129` does. A plain `SET TBLPROPERTIES ('replace.enabled'='true')` on a replicated table may therefore skip the RTAS-vs-replication check. Not verified.

## 4. Spark 4.2 extensions build (N3 part)

Read from the 3.5 sources and Iceberg's 4.2 extensions:

- **New module.** `integrations/spark/spark-4.2/openhouse-spark-runtime`, with Scala 2.13 and Spark 4.2.0, compiling against `iceberg-spark-runtime-4.2_2.13` (1.12 fork) and only under `-PicebergFork`, like the N3 itest.
  - Sources: copy the 3.5 tree (about 30 files). There is no shared source set: the Scala versions and Spark APIs differ.
- **ANTLR.** The 3.5 module generates with 4.7.1 (`build.gradle`). Spark 4 ships ANTLR 4.13 (Iceberg: `antlr413 = "4.13.1" # For Spark 4.0 support`, `libs.versions.toml:29`). Regenerate with 4.13.1, because the serialized-ATN format changed after 4.9.
- **Parser.** Add `parseRoutineParam` and `parsePlanWithParameters` (Iceberg 4.2 overrides both: `IcebergSparkSqlExtensionsParser.scala:118,133`). The `OpenhouseParseException` constructor form `(msg, Some(line), Some(pos))` matches Iceberg 4.2's `IcebergParseException` (`:339`). The named `cause =` form in `UnlockAnalysisException` is unverified.
- **`IcebergCatalogMapper` breaks on 4.2.** `SparkCatalog.cacheEnabled` is a local variable there (`v4.2/SparkCatalog.java:796`), so `DynFields("cacheEnabled")` (`IcebergCatalogMapper:33`) fails. Replace it with:
  - `HasIcebergCatalog.icebergCatalog()`, which is public (`:1091`; `SparkSessionCatalog:470`);
  - an `instanceof CachingCatalog` check, then the private `CachingCatalog.catalog` field (`CachingCatalog.java:63`). That field is the only reflection left.
  - The resolver does this once for both lines.
- **Session conf.** `ResolveUnlockTable` reads `spark.sessionState.conf`. Use `SQLConf.get` instead to avoid Spark 4's classic-session split (unverified whether it is needed).
- **Dependencies.** The execs import `javaclient.api.*` and the generated `gen.tables.client.model.CreateUpdateLockRequestBody.ReasonEnum` (`UnlockTableExec`, `AstBuilder:3`). That pulls in the OpenHouse Java client and Spring WebClient. The 4.2 jar instead depends on a new `openhouse-catalog-capabilities` module, which is Java, depends only on `iceberg-api` and `iceberg-core` (compileOnly), and contains:
  - the interfaces, `UnlockTableTarget`, an `UnlockReason` enum, `Privileges`, the capability names, and the REST capability client.
  - No Spring, no generated client, and no shading of Jackson: request and response POJOs go through Iceberg's own (relocated) mapper.
- **Unchanged on 4.2 (read):** `LeafV2CommandExec`, `resolveOperators`, `injectParser`/`ResolutionRule`/`PlannerStrategy`, `Spark3Util.catalogAndIdentifier` and `SparkTable`. Iceberg 4.2 uses or contains them all.

## 5. Alternatives rejected

- **A. Keep an OpenHouse `RESTCatalog` subclass on the native path** (like the rest-shim, but over HTTP).
  - What the user types is unchanged. An engine without the jar sees plain IRC.
  - Rejected for three reasons:
    - it breaks "no OpenHouse catalog class";
    - it ties every client to OpenHouse's catalog class per Iceberg line, so it cannot be reused outside LinkedIn;
    - `SparkCatalog` builds the catalog from `catalog-impl`, so the plugin choice stops being the user's.
  - It is still valid as a fallback. The resolver would accept it through `instanceof`.
- **B. Call the native routes directly**, with a configured or advertised native base URI.
  - It needs no server code.
  - Rejected for three reasons:
    - the ACL update is PATCH, which `RESTClient` cannot send;
    - the native error body is not the IRC `ErrorResponse`, so Iceberg's error handlers produce generic messages;
    - it needs a second base URI outside the catalog's prefix, which does not compose.
- **C. A dedicated policy route instead of the property.**
  - Rejected for three reasons:
    - engines without the jar (`SET TBLPROPERTIES`, or other IRC engines) would lose policies;
    - the execs would change on every path;
    - the update would not be atomic with Iceberg's commit retry.
  - It would also be a second wire contract next to the property, which the legacy path already accepts.
- **D. Typed policy properties** (for example `openhouse.policy.retention.count`).
  - A nicer contract for non-OpenHouse engines.
  - Rejected now because it is a behavior change on the legacy path, where only the JSON patch is understood. It can be added later as more accepted keys.
- **E. Advertise through the `endpoints` list.** Off-spec for vendor paths, and V10 owns that list. The config key is enough.

## 6. One-way doors (owner decides before any release outside these forks)

None is published until the owner agrees. Building them inside the forks is a two-way door.

1. **`updated.openhouse.policy` accepted over IRC, with its patch JSON shape.** The legacy client already accepts it. Accepting it on a public IRC endpoint lets Trino, PyIceberg and others depend on it.
2. **The config key and its names:** `openhouse.capabilities` and `policies`, `grants`, `unlock`.
3. **The route paths and methods:** `…/openhouse/acl-policies` (GET, POST) and `…/openhouse/lock[/{reason}]` (DELETE). Reusing the native bodies keeps the new surface to paths only.
4. **The `openhouse-catalog-capabilities` module's public API**, if it is published as a client library for use outside LinkedIn.

Unchanged and already public: `X-OpenHouse-Client-Capabilities`, `required-capability.*`, 422 `CLIENT_CAPABILITY_MISSING`.

## 7. Spark SQL behavior differences the design introduces

The legacy and rest-shim paths on Spark 3.5 have **no differences**: C2 is a refactor behind `instanceof`, and smoke tests s16, s17 and s27 must stay green unchanged. The differences below apply to `SparkCatalog type=rest` with the extensions, on 3.5 (N1) and 4.2 (N3).

| Statement | Today (plain RESTCatalog) | With the design |
|---|---|---|
| `SET/UNSET POLICY …`, `MODIFY COLUMN … SET TAG` | Succeeds, stores a stray `updated.openhouse.policy` property, applies nothing (read) | Applies the policy as on the legacy path. Validation failures give the native message as an Iceberg `BadRequestException`, not `WebClientResponseWithMessageException`. Against a server that does not advertise `policies`, it fails with `UnsupportedOperationException`. |
| `SET TBLPROPERTIES ('updated.openhouse.policy'=…)`, with or without the jar | Stray property | Policy applied, as on the legacy path |
| Concurrent policy statements | — | Merged onto the policies stored at commit time. The legacy path merges onto the client's loaded base and sends the whole object. The result is identical unless two writers race. |
| `GRANT/REVOKE` | `UnsupportedOperationException` | Works. A 400 (unshared or locked table) is still `IllegalArgumentException`, but the message is the IRC message, not `"400 , <body>"`. Other errors are `ForbiddenException` and `NoSuchTableException` instead of `WebClientResponseWithMessageException`. |
| `SHOW GRANTS` | `UnsupportedOperationException` | Same rows as the legacy path |
| `UNLOCK` | "does not support UNLOCK" | Works. A missing table gives `NoSuchTableException` with the native message (smoke test s27 pins `WebClientResponseWithMessageException` on the legacy path). |

## 8. Work items (tests first; each leaves a valid state)

| ID | Leaves true | Needs | Proven by | Door |
|---|---|---|---|---|
| V5-0 | Red pins: an IRC commit with `updated.openhouse.policy` stores the stray key; a policy statement on `type=rest` "succeeds"; GRANT and UNLOCK say unsupported | — | `IrcWriteRoutesTest` case, plus a Spark 3.5 `IcebergRestSparkSqlTest` case (the shared session already loads the extensions, `TestSparkSessionUtil:26`) | 2-way |
| V5-gap | The RTAS-vs-replication check and other `validateUpdateTable` checks are pinned on IRC updates, then fixed if missing | — | A red MockMvc test | 2-way |
| C2-1 | `openhouse-catalog-capabilities` module (names, interfaces, `UnlockReason`, `Privileges`, `Principal`). The Java runtime's catalogs implement it through adapters. Old `javaclient.api` types are kept. | — | Unit tests. Spark 3.5 `statementTest` and `catalogTest` unchanged. | 2-way (internal) |
| C2-2 | Resolver in the 3.5 extensions replaces `IcebergCatalogMapper` at all 5 call sites. Policy execs check `policies`. `HasIcebergCatalog` is used where present. | C2-1 | Resolver unit tests (legacy, shim and fake-`RESTCatalog` properties). Smoke tests s16, s17 and s27 green on legacy and rest-shim. | 2-way |
| V5-1 | Server-side policy patch translation on commit, create and staged completion, with validators | V5-0 | Equivalence test: IRC patch versus native PUT with client-merged policies gives a byte-equal `policies` property. Every rule in §3.1 refused with the native status and message. Audit event. | Wire: 1-way at release |
| V5-2 | Config overrides `openhouse.capabilities=policies`. The adapter's CONFIG says the same. | V5-1 | Config test. V5-0's policy pins flip to green. | 1-way at release |
| V5-3 | ACL and lock routes under `/iceberg`, advertising `grants,unlock` | V5-2 | MockMvc equivalence with the native routes: status, message, the authorization handler's calls, audit, and lock state after | 1-way at release |
| C2-3 | `OpenHouseRestCapabilityClient` (`HTTPClient` + `AuthManager` + `ResourcePaths`), used by the resolver | C2-2, V5-3 | Spark 3.5 itest: GRANT, REVOKE, SHOW GRANTS and UNLOCK on `type=rest` | 2-way |
| N1-c | `SparkSqlSmokeTest` gains a third path, `rest-http` (`SparkCatalog type=rest`). Policy, grant and unlock cases pass. Differences are listed per §7. | C2-3 | s16, s17 and s27 plus a test per SET POLICY form, on all three paths | 2-way |
| N3-x1 | Spark 4.2 / Scala 2.13 extensions module: ANTLR 4.13.1, the new parser methods, the resolver, `SQLConf.get`, depends only on C2-1 | C2-2 | Ported parser and statement unit tests | 2-way |
| N3-x2 | Spark 4.2 itest: every OpenHouse statement over IRC, with no OpenHouse catalog class on the classpath | N3-x1, V5-3 | A 4.2 itest beside N3's read itest, asserting the stored state through the OpenHouse Java client | 2-way |

## 9. Not verified

- **Not run here:** anything at all. The stray-property outcome on today's IRC path is inferred from `CandidateViews:57` and `BasePreservedKeyChecker:22`; V5-0 pins it.
- **Not checked:**
  - whether `HasIcebergCatalog` exists on LinkedIn's Iceberg 1.5.2 Spark 3.5 `SparkCatalog` (the current reflection works there, so it is not needed);
  - whether `parsePlanWithParameters` is abstract in Spark 4.2;
  - whether `AnalysisException(…, cause = …)` compiles on Spark 4.2;
  - whether `sessionState` is reachable from `org.apache.spark.sql.SparkSession` in 4.2;
  - whether unannotated request and response POJOs round-trip through Iceberg's relocated Jackson mapper;
  - the V5-gap (§3.4).
- **Not checked:** whether `RESTCatalog` sends `session-id` from `app-id` as the OpenHouse client does.
- **Assumed:** that the client's Gson parse of the policy patch and the server's `Policies` mapper accept the same JSON. V5-1's equivalence test must cover every exec's literal output (`Set*PolicyExec`).
