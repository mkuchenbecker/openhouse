# OpenHouse capabilities over IRC: design

Adopted by `22-decisions-continued-2.md` D23. Written by a delegated agent from reading code; nothing in it was run.

Bottom line: the engine runs Iceberg's own `SparkCatalog` and `RESTCatalog` plus the OpenHouse SQL-extensions
jar, with no OpenHouse catalog class. Policies ride the standard IRC commit. Grants, SHOW GRANTS and UNLOCK go
through a few OpenHouse routes under the IRC base. The server advertises what it serves in the config response.
Work items C2 and V5 are in `29-capabilities-over-irc-work.md`. Read from `irc-integration` at 14f23870 and the
fork's `openhouse-1.12.0` (28742df3e). "Read" means taken from code.

## The design

1. Policies use the standard IRC commit route: retention, sharing, history, replication and column tags. The exec
   nodes stay as they are, because they already write the request property `updated.openhouse.policy`. The
   server's IRC commit and create routes start treating that key as a request: parse the patch, merge it onto the
   stored policies with the client's rules, validate it, apply it, and never store the key.
2. Grants, SHOW GRANTS and UNLOCK use routes `v1/{prefix}/namespaces/{ns}[/tables/{t}]/openhouse/...`. They use
   the IRC error shape and the native bodies, and call the same API handlers as the native routes, so
   authorization, validation and audit are the same. A small capability client in the extensions jar calls them,
   building Iceberg's `HTTPClient` and `AuthManager` from `RESTCatalog.properties()` (same `uri`, prefix, token and
   `header.*`).
3. The config response carries `openhouse.capabilities=policies,grants,unlock`. The SQL extensions find each
   feature by capability name through one resolver on all three paths. On the legacy and in-process paths the
   catalog implements the interface. On native REST the resolver uses the advertised name and the capability
   client.

What a user types in Spark SQL is unchanged, and Spark 3.5 legacy and rest-shim behavior is unchanged. Spark 4.2
needs a port of the extensions to Scala 2.13 and ANTLR 4.13 on a new, Spring-free capabilities module.

## The finding

Over a plain `RESTCatalog` today, a policy statement succeeds and applies nothing. The patch goes as
`set-properties` on the IRC commit. The key is not reserved (`BasePreservedKeyChecker:22`), `CandidateViews:57`
copies it into the table properties, and `IcebergRestWriteHandler:164-170` passes no policies. So a stray property
is stored and no policy is applied (inferred from those lines, not run). GRANT, REVOKE, SHOW GRANTS and UNLOCK say
"unsupported".

## Inventory: statement, today's behavior, and the design

Grammar: `OpenhouseSqlExtensions.g4:25-36`. There is no LOCK statement; locks are set only through the native
`POST .../lock` route (`TablesController.java:413`). A "policy patch" is
`updateProperties().set("updated.openhouse.policy", <json>)` made by the `Set*PolicyExec` nodes. All rows are read.

| Statement | Today: legacy catalog and rest-shim | Today: plain `RESTCatalog` | Under the design |
|---|---|---|---|
| `SET POLICY` for RETENTION, SHARING, HISTORY, REPLICATION (and `UNSET`), `MODIFY COLUMN ... SET TAG` | Policy patch. `OpenHouseTableOperations.buildUpdatedPolicies` (`:357-400`) merges it client-side onto the base's `policies`, drops the key (`:289-294`) and sends `PUT /v1/databases/{db}/tables/{t}` with `policies`. The service validates (`OpenHouseTablesApiValidator:213,371,265-300`, including replication and the RTAS-versus-replication rule) and stores `policies`. | Silently stored as a stray property; nothing applied | The same patch through the IRC commit, translated by the server: merged, validated, stored as `policies`, key dropped |
| `GRANT`, `REVOKE`, `SHOW GRANTS` on table or database | `IcebergCatalogMapper` reflects out of `SparkCatalog` and `CachingCatalog` (`:28-34`). The catalog is `SupportsGrantRevoke`, which sends `PATCH .../aclPolicies` (`TablesController:348`, `DatabasesController:105`). The service checks `UPDATE_ACL`, sharing and lock (`TablesServiceImpl:379-399`) and audits it. | `UnsupportedOperationException` | The resolver finds `grants`. The capability client sends `POST` (or `GET` for SHOW) `.../openhouse/acl-policies` with the native body to a route calling the same handler. |
| `ALTER TABLE t UNLOCK [REASON r]` (gated by `spark.sql.openhouse.unlock.enabled`) | `ResolveUnlockTable:25` requires `SupportsUnlock`; `UnlockTableExec` sends `DELETE /v1/.../lock[/{reason}]` (`TablesController:445,470`) | "does not support UNLOCK" | The resolver finds `unlock`, then `DELETE .../openhouse/lock[/{reason}]` |
| `X-OpenHouse-Client-Capabilities` | The legacy line declares `column-defaults`; the new line declares nothing (D11a). `header.*` can add more. | Declares nothing unless `header.X-OpenHouse-Client-Capabilities` is set. The gate answers 422 on load and commit. | Unchanged |
| Client name, version, action type and app id headers; replica table type and intermediate schemas | Set from catalog properties (`CatalogRequestHeaders:38-43`); replica logic in `OpenHouseTableOperations:297-318` | Only as `header.*`; replica not expressible | Unchanged. Replica logic is out of scope and stays on the OpenHouse Java client. |

## The capability model

- A capability is a lower-case name with a direction. A client capability goes client to server in the header
  `X-OpenHouse-Client-Capabilities` and means "this client implements behavior X that a table may require"
  (`required-capability.<name>=true`); `column-defaults` is the only one. A server capability goes server to client
  in config and means "this endpoint serves feature X": `policies` (which includes tags, history and replication),
  `grants` and `unlock`. Both directions share one constants class in the new module.
- Advertising: `IcebergRestApiHandler.config()` (`:75-79`) adds `openhouse.capabilities` to the config response
  overrides, so `RESTCatalog.properties()` shows what the server serves (`RESTSessionCatalog.java:218` on 1.12). It
  is not put in the `endpoints` list: the spec says entries are paths from its own OpenAPI document, it has no
  vendor-extension slot other than config properties, and once a server sends any `endpoints`, 1.12 uses only that
  list (V10's job). The in-process adapter's `CONFIG` advertises the same key.
- Declaring is unchanged. A plain `RESTCatalog` declares nothing, and a user who sets the header on a stock
  `RESTCatalog` gets no defaults applied (V2 report, difference 7). Documentation must say not to declare a
  capability the client does not implement. `required-capability.*` means the same as natively: a table that
  requires a client capability is refused with 422 `CLIENT_CAPABILITY_MISSING` on load and commit for a client
  that does not declare it. Server capabilities never gate tables.
- Discovery is one `OpenHouseCapabilities.resolve(TableCatalog)` replacing every `IcebergCatalogMapper` call site.
  The legacy catalog and the rest-shim catalog (found by `instanceof` first) report `{policies, grants, unlock}`
  because they implement the interfaces, and translate policy patches client-side. A native `RESTCatalog` reads
  `properties().get("openhouse.capabilities")` and builds a cached `OpenHouseRestCapabilityClient` for `grants`
  and `unlock`. A missing name gives today's `UnsupportedOperationException` text, and the policy execs ask for
  `policies` first, so an old IRC server fails loudly instead of storing a stray property.
- The capability client is a sidecar to an unmodified `RESTCatalog`, with no subclass. Every route uses GET, POST
  or DELETE, because Iceberg's `RESTClient` has no PATCH or PUT.

## Server changes (V5)

1. Policy patch on IRC commit, create and staged-create completion (`IcebergRestWriteHandler`). If the candidate's
   properties contain `updated.openhouse.policy`: remove it; parse it with the client's accepted shapes, including
   the system-only fallback (`OpenHouseTableOperations:395-405`); merge it onto `base.getPolicies()` with the
   client's five-field rules (retention, sharing, columnTags per column with the patch winning, replication,
   history); run `validatePolicies` and `validateFeatureCompatibility`; and set `CommitContext.requestedPolicies`
   (`TableCommitService:59-62`). `TableCommitCore` then applies its checks unchanged. The raw `policies` key and
   `openhouse.*` keys stay the native 400, and the stored `policies` string must be byte-equal to the native one.
2. Routes in `IcebergRestCatalogController`, before the `/**` catch-all (`:210`), each delegating to the existing
   API handler: `GET|POST .../tables/{t}/openhouse/acl-policies`, the same under `.../namespaces/{ns}/`, and
   `DELETE .../tables/{t}/openhouse/lock[/{reason}]`. Bodies are the native ACL request and response bodies; errors
   go through `IcebergRestErrors` with native status and message. A multi-level namespace is 404.
3. Config advertises `openhouse.capabilities`, each name added only in the item that makes it true.
4. A possible V3 gap, to check first: IRC updates never call `validateUpdateTable` (only
   `OpenHouseTablesApiHandler:129` does), so a replicated table may skip the RTAS-versus-replication check.

## Spark 4.2 extensions build (read)

- A new module `integrations/spark/spark-4.2/openhouse-spark-runtime` (Scala 2.13, Spark 4.2.0) compiles against the
  fork's 4.2 runtime, only under `-PicebergFork`. Sources are a copy of the 3.5 tree (about 30 files).
- ANTLR: regenerate with 4.13.1, because the serialized-ATN format changed after 4.9 and 3.5 uses 4.7.1. Parser:
  add `parseRoutineParam` and `parsePlanWithParameters`, as Iceberg 4.2 does
  (`IcebergSparkSqlExtensionsParser.scala:118,133`).
- `IcebergCatalogMapper` breaks on 4.2, because `cacheEnabled` is a local variable there (`SparkCatalog.java:796`).
  Use the public `HasIcebergCatalog.icebergCatalog()` and an `instanceof CachingCatalog` check, then the private
  `CachingCatalog.catalog` field, the only reflection left. The resolver does this once for both lines.
  `ResolveUnlockTable` should use `SQLConf.get` instead of `spark.sessionState.conf`.
- Dependencies: the execs import the OpenHouse Java client, which pulls in Spring WebClient. The 4.2 jar instead
  depends on a new Java module `openhouse-catalog-capabilities` that needs only `iceberg-api` and `iceberg-core`
  (compileOnly). It holds the interfaces, `UnlockTableTarget`, an `UnlockReason` enum, `Privileges`, the capability
  names and the REST capability client, with no Spring and no generated client.

## Alternatives rejected

- A. An OpenHouse `RESTCatalog` subclass on the native path. It breaks "no OpenHouse catalog class", ties every
  client to OpenHouse's class per Iceberg line, and takes the plugin choice from the user. It stays valid as a
  fallback, because the resolver accepts it by `instanceof`.
- B. Calling the native routes directly. The ACL update is PATCH, which `RESTClient` cannot send, the native error
  body is not the IRC `ErrorResponse`, and it needs a second base URI.
- C. A dedicated policy route instead of the property. Engines without the jar would lose policies, the execs
  would change on every path, the update would not be atomic with the commit retry, and it adds a second contract.
- D. Typed policy properties such as `openhouse.policy.retention.count`. A behavior change on the legacy path; it
  can be added later. E. Advertising through the `endpoints` list: off-spec for vendor paths, and V10 owns it.

## One-way doors (the owner decides before any release outside these forks)

Building them inside the forks is a two-way door (D24).
1. `updated.openhouse.policy` accepted over IRC, with its patch JSON shape. A public IRC endpoint lets Trino,
   PyIceberg and others depend on it.
2. The config key `openhouse.capabilities` and the names `policies`, `grants`, `unlock`.
3. The route paths and methods: `.../openhouse/acl-policies` (GET, POST) and `.../openhouse/lock[/{reason}]` (DELETE).
4. The `openhouse-catalog-capabilities` module's public API, if published as a client library.

Already public, unchanged: `X-OpenHouse-Client-Capabilities`, `required-capability.*`, 422 on a missing capability.

## Spark SQL behavior differences the design introduces

The legacy and rest-shim paths on Spark 3.5 have no differences: C2 is a refactor behind `instanceof`, and smoke
tests s16, s17 and s27 stay green unchanged. These apply to `SparkCatalog type=rest` with the extensions.

- `SET/UNSET POLICY` and `MODIFY COLUMN ... SET TAG`: today they store a stray property and apply nothing. With the
  design the policy is applied as on the legacy path. A validation failure gives the native message as an Iceberg
  `BadRequestException`, not `WebClientResponseWithMessageException`, and a server that does not advertise `policies`
  gives `UnsupportedOperationException`. `SET TBLPROPERTIES ('updated.openhouse.policy'=...)` also applies the policy.
- Concurrent policy statements merge onto the policies stored at commit time, not the client's loaded base. The
  result is identical unless two writers race.
- `GRANT`, `REVOKE` and `SHOW GRANTS` work, where today they throw `UnsupportedOperationException`. A 400 is still
  `IllegalArgumentException` with the IRC message, and other errors are `ForbiddenException` and
  `NoSuchTableException`. `UNLOCK` works, and a missing table gives `NoSuchTableException` (smoke test s27 pins
  `WebClientResponseWithMessageException` on the legacy path).
