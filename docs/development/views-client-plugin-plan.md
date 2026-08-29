# Views client plugin: delegate to Iceberg's own REST client

The Spark plugin should implement Iceberg's `ViewCatalog` SPI by delegating to an
embedded `org.apache.iceberg.rest.RESTCatalog` pointed at the spec-compliant views
endpoints, rather than generating a second WebClient or keeping the in-memory mock
store. Delegation adds no runtime dependency and no shading rule, because the Spark
runtime bundle already supplies `org.apache.iceberg.**` unrelocated, and it buys
error translation for free: the server's views-disabled `404 NoSuchViewException`
arrives client-side as a `NoSuchViewException` with no OpenHouse-specific mapping
code, and Spark falls through to `loadTable`.

Two consequences an operator should know before enabling the client gate: view calls
do not honor the OpenHouse `trust-store` catalog property, using the JVM's default
trust material instead, and each auth-token refresh parks one idle HTTP client until
the catalog is closed. Both are argued in §5.2.

**Status:** implemented, including the end-to-end test with the gate on
(`OpenHouseViewGateOnTestSpark3_5`).
**Branch:** `claude/views-rest-client-impl`, off `claude/iceberg-rest-spec-compliance-l0s2ju`.
**Companion:** [views-iceberg-rest-compliance.md](views-iceberg-rest-compliance.md),
the server surface this client consumes (`/v1/config`, `/v1/namespaces/{ns}/views`
CRUD plus `HEAD`, the `IcebergErrorResponse` envelope, views-disabled rendering as
`404 NoSuchViewException`). Read it first.

## 1. Problem

OpenHouse's Spark plugin cannot resolve views. Its catalog extends
`BaseMetastoreViewCatalog`, so Spark routes view calls to it, but the only backend
behind those calls is an in-memory `mockViewStore` behind a default-off gate: a
`CREATE VIEW` that appears to succeed persists nothing, and no view survives the
session. The server now serves spec-compliant view endpoints, so the plugin needs a
real client for them.

## 2. Requirements

**Must**

1. With the client gate off, behavior is byte-identical to today's disabled state:
   no view REST call is made, whatever the server does.
2. With the gate on against a views-disabled server, `SELECT` on a table still
   resolves; Spark's `ResolveViews` falls through to `loadTable` over the real wire.
3. A failure reaching the views endpoints never breaks a table operation.
4. No new user-facing catalog property beyond the existing gate, and no new runtime
   dependency or shading rule.

**Should**

5. Server error semantics reach Spark through Iceberg's own error handling, with no
   OpenHouse-specific mapping code.
6. View calls present the same client identity to audit and telemetry that table
   calls present.

**Won't, this milestone**

7. Persistence. The server answers every view call with `404 "Views are disabled"`;
   this milestone delivers wire surface and behavior parity.
8. A generated `ViewApi` WebClient, the evolution path upstream's javadoc envisioned.
   The REST client is the glue, and this is the first step of moving the whole plugin
   to REST.
9. Spark 3.1 / iceberg-1.2. That runtime stays table-only (`BaseMetastoreCatalog`);
   its Iceberg has no view support at all.
10. `renameView` on the enabled path. See §5.4.

## 3. Current state (what the sync brought in)

The tables plugin stack, bottom to top:

- `integrations/java/iceberg-1.5/openhouse-java-runtime/.../javaclient/OpenHouseCatalog.java`
  — extends **`BaseMetastoreViewCatalog`** (changed from `BaseMetastoreCatalog` by upstream's
  gated-view commit), tables backed by the generated WebClient `tableclient`
  (`TablesApiClientFactory`, `TableApi`/`SnapshotApi`/`DatabaseApi`, `auth-token` catalog
  property → `Authorization: Bearer` default header).
- `integrations/spark/spark-3.5/openhouse-spark-runtime/.../spark/OpenHouseCatalog.java` —
  an empty subclass; Spark wires it via
  `spark.sql.catalog.<name>=org.apache.iceberg.spark.SparkCatalog` +
  `catalog-impl=com.linkedin.openhouse.spark.OpenHouseCatalog`.
- **Shading constraint that makes thin glue viable:** the java-runtime `shadowJar` relocates
  everything *except* `org.apache.iceberg.**`; `iceberg-spark-runtime-3.5` is `compileOnly`
  and supplies all Iceberg classes at run time — including `org.apache.iceberg.rest.*`
  (the Spark runtime bundle shades iceberg-core in). So delegating to Iceberg's REST client
  adds **zero new runtime dependencies and no new shading rules**.

The gated view path today: catalog property `iceberg-views-enabled` (default `false`); when
enabled, view ops run against an in-memory `mockViewStore` via an inline `ViewOperations`.
When disabled, each `ViewCatalog` method mirrors, method-for-method, how `SparkCatalog`
treats a non-`ViewCatalog` catalog: `loadView` → `NoSuchViewException` (so Spark's
`ResolveViews` falls through to `loadTable`), `listViews` → empty, `dropView` → `false`,
`buildView` → `NoSuchNamespaceException` (normalized by `SparkCatalog.createView` to an
`AnalysisException`), `renameView` → `UnsupportedOperationException`.

## 4. Salvage assessment of the gated-view code

Not too far divergent — the superseded part is exactly the part upstream labeled MOCK. Diff
against it rather than trashing it:

| Hunk (in `OpenHouseCatalog.java`, iceberg-1.5) | Verdict | Why |
|---|---|---|
| `extends BaseMetastoreViewCatalog` + class javadoc's Spark-routing analysis | **Keep** | Required for `SparkCatalog` to route view calls at all; the disabled-parity analysis is the part that is expensive to re-derive |
| `VIEWS_ENABLED_PROPERTY` gate + `viewsEnabled` flag + `initialize` parsing | **Keep** | The client-side default-off gate stays even with a real server: when off, no view REST call is ever made, preserving exact table-only behavior regardless of server state |
| Disabled-state branches in `loadView`/`listViews`/`dropView`/`buildView`/`renameView` | **Keep verbatim** | Behavior contract with `SparkCatalog`, documented per-method; independent of backend |
| `mockViewStore` + inline `ViewOperations` in `newViewOps` | **Replace** | The mock backend; superseded by REST delegation |
| `buildView(...).withLocation("mock://…")` default-location hack | **Delete** | Location assignment is the server's job; with REST delegation the builder no longer flows through `newViewOps` |
| Store-backed enabled paths of `listViews`/`dropView`/`renameView` | **Replace** | Become REST delegations (rename: see §5.4) |
| `OpenHouseViewSparkITest` (3.1 + disabled-parity assertions) | **Keep** | Tests the kept contract; backend-agnostic |
| `OpenHouseViewEnabledTestSpark3_5` mock round-trip | **Replace** | Depends on the in-memory store; a create/load round-trip cannot pass against the stubbed server. Superseded by disabled-posture e2e + MockWebServer contract tests (§7); the round-trip returns as-is in the persistence milestone |
| Upstream javadoc evolution note ("generated `ViewApi` … mirroring `newTableOps`") | **Supersede** | The direction is Iceberg's REST client, not a second generated WebClient |

## 5. Target design

### 5.1 Delegation, not reimplementation

`OpenHouseCatalog` (iceberg-1.5 copy only) holds an embedded
`org.apache.iceberg.rest.RESTCatalog`, constructed **and initialized lazily on the FIRST view
operation** — never in `OpenHouseCatalog.initialize()` (the architecture review: `RESTCatalog`'s
initialization eagerly fetches `/v1/config`, so doing it in `initialize()` would let a
views-endpoint bootstrap/config failure break **table** operations; done lazily, a bootstrap
failure fails only that view operation, nothing is cached, and the next view op retries).
Consequences: with the gate off, zero cost and zero `/v1/config` call — and even with the
gate on, nothing crosses the wire until a view operation actually happens. On the enabled
path `loadView`/`viewExists`/`listViews`/`dropView` and `buildView` delegate to it;
`renameView` stays unsupported in both gate states (§5.4):

- `loadView` → REST `GET .../views/{view}` (`LoadViewResult` carries full metadata inline —
  no FileIO needed for views)
- `viewExists` → delegated; in iceberg 1.5.2.17 `RESTSessionCatalog`
  implements `viewExists` via the `ViewCatalog` default — a `GET` load-and-catch — **not**
  the spec's `HEAD` route. The server's `HEAD` endpoint is simply unused by this client
  version.
- `listViews` → REST `GET .../views`; 1.5.2.17's
  `RESTSessionCatalog.listViews` performs a **single GET with no `next-page-token` paging**;
  the server obligation (return all results when `pageToken` is absent, per the spec) is
  recorded in the server plan. The delegation catches
  `NoSuchNamespaceException`/`NoSuchViewException` and answers an **empty list**:
  `SparkCatalog.listViews` catches nothing, so a list-route 404 (spec type
  `NoSuchNamespaceException`, or the views-disabled envelope) would otherwise leak a raw
  error out of `SHOW VIEWS`.
- `buildView(...)` → the REST view builder (create → `POST` `CreateViewRequest`;
  replace/createOrReplace → commit `POST` with `assert-view-uuid` requirements + updates —
  the client, not us, owns commit semantics, which is the "plugin as glue" position from the
  upstream #694 discussion). Wire detail observed in 1.5.2.17: `replace()` first probes
  `tableExists` (`GET .../tables/{name}` → 404 expected) before loading the view and
  committing. Create-route 404s arrive as `NoSuchNamespaceException` (the architecture review's
  server-side rendering), which `SparkCatalog.createView` normalizes to Spark's
  `AnalysisException`.
- `dropView` → REST `DELETE`
- `newViewOps` → becomes an unreachable path; throws `IllegalStateException` with a pointer
  to the delegation (the `BaseMetastoreViewCatalog` builder machinery is bypassed)

Error translation comes from iceberg-core's `ErrorHandlers` parsing the
`IcebergErrorResponse` envelope — the exact payoff of the server-side plan: the server's
views-disabled `404 NoSuchViewException` arrives client-side as `NoSuchViewException` with
**no OpenHouse-specific error-mapping code**, and Spark falls through to `loadTable`.

### 5.2 Embedded REST catalog configuration

Derived (at lazy-build time, from the catalog properties captured in `initialize()`) — no
new user-facing keys beyond the existing gate:

| REST catalog property | Source |
|---|---|
| `uri` | the existing `uri` property (same service; REST paths mount alongside `/v1/databases/...`) |
| `header.Authorization` = `Bearer <token>` | the existing `auth-token` property. Use `header.*` passthrough, **not** the REST `token` property — the OAuth2 session machinery (token refresh, `/v1/oauth/tokens`) must stay out of the loop |
| `header.` client-name/version/session headers | mirror what `TablesApiClientFactory` sets, so audit/telemetry sees the same identity on view calls. Actual semantics: `User-Agent` is set **unconditionally** (`openhouse-java-client/` + the `client-version` property, else the jar manifest's Implementation-Version, else `unknown`); `X-Client-Name` only when `client-name` is set; `session-id` **only when `app-id` is set** — tables calls otherwise synthesize a random UUID session that view calls deliberately don't get, since a fabricated UUID would break correlation |
| `prefix` | unset (server serves un-prefixed paths; `/v1/config` returns no override) |

**TLS and truststore.** View calls trust the JVM's default trust material, not the
OpenHouse `trust-store` catalog property. Iceberg 1.5.2.17's `HTTPClient` builds its Apache
HttpClient 5 connection manager with `useSystemProperties()`, so the embedded catalog trusts
the JVM's default trust material and honors the standard `javax.net.ssl.trustStore*` system
properties. It exposes no per-catalog truststore hook, and hand-rolling a custom
`RESTClient` just to honor the OpenHouse `trust-store` property would contradict the
thin-glue position. Decision: the `trust-store` catalog property keeps configuring the
tables WebClient only; for view calls over https the service certificate must chain from the
JVM trust material (default cacerts or `javax.net.ssl.trustStore*`). When `trust-store` is
set and views are enabled, the catalog logs a warning at embedded-catalog build time to make
the asymmetry visible.

**Token refresh.** `updateAuthToken()` propagates by **recreate, not header
mutation** — the embedded catalog's headers are captured immutably at its initialization, so
the hook displaces the embedded catalog and lets the next view operation lazily rebuild it
from the updated `auth-token` property (a no-op when the gate is off or no view op ever
ran). The displaced instance is NOT closed at refresh time: an in-flight view operation may
still hold it from the lazy accessor's fast path, and closing under it would leak an
`IllegalStateException` into Spark's table resolution. Displaced instances are parked and
reclaimed by `close()` — a bounded leak of one idle HTTP client per refresh, reclaimed at
close()/JVM exit. `OpenHouseCatalog` now implements `Closeable`; `close()` drains the parked
instances and the current one, and a later view operation re-initializes a fresh embedded
catalog (accepted resurrection semantics).

### 5.3 Both gates stay, and compose

- **Client gate off (default):** no view REST calls at all; behavior byte-identical to
  today's disabled state (kept hunks, kept tests).
- **Client gate on, server stubbed (this milestone):** every view op crosses the wire and
  gets the spec envelope; `loadView` → `NoSuchViewException` → Spark still resolves tables.
  This is the end-to-end proof that the default-off posture holds through the *real* code
  path, with zero mock backend.
- **Client gate on, server enabled (future milestone):** works with no further client change
  — that is the point of the thin glue.

One composition to know: with the gate on against a server that does not serve `/v1/config`
(e.g. an older deployment), unqualified-identifier resolution fails with a REST bootstrap
error on the first view probe until the gate is turned off — correct, loud behavior for a
misconfigured pairing, deliberately not masked as `NoSuchViewException`.

### 5.4 Rename

Server-side `rename-view` is out of scope (plain 404, deliberately unclaimed). Delegating
`renameView` would turn that 404 into a misleading `NoSuchViewException`. So `renameView`
throws `UnsupportedOperationException("Renaming views is not supported")` on the enabled
path too, matching the server's scope decision.

## 6. Implementation

1. **Runtime-jar facts, verified against the decompiled LinkedIn-fork `1.5.2.17`
   artifacts:** `RESTSessionCatalog`
   implements the view SPI (`ViewSessionCatalog`, via `BaseViewSessionCatalog`), all needed
   REST/view classes are present in `iceberg-core-1.5.2.17` and
   `iceberg-spark-runtime-3.5_2.12-1.5.2.17`, and view REST paths/`ResourcePaths` match the
   server plan. `header.*` passthrough confirmed (`RESTUtil.extractPrefixMap(props,
   "header.")`); the TLS consequence is recorded in §5.2. No hand-rolled `RESTClient` is
   needed.
2. **Embedded catalog wiring:** lazy `RESTCatalog` construction on first view operation
   behind the gate, never in `initialize()`; property derivation per §5.2;
   `updateAuthToken` hook (recreate-on-refresh); `close()` propagation.
3. **Delegation:** replace the mock-backed enabled paths of `loadView`/`listViews`/
   `dropView`/`buildView`, add `viewExists`, neutralize `newViewOps`, delete `mockViewStore`
   and the mock-location hack, set `renameView` per §5.4. Disabled branches untouched.
4. **Javadoc truth-up:** class and method docs describe REST delegation and drop the
   "generated ViewApi" evolution note (superseded).
5. **Tests** (§7).
6. **Docs:** no runtime README or catalog doc
   describes `iceberg-views-enabled` yet; user-facing docs land with the persistence
   milestone, when the flag does something visible end-to-end.

Touched files (expected): the iceberg-1.5 `OpenHouseCatalog.java`, its tests, and the two
view itests — nothing in the shared iceberg-1.2 sources, nothing in build files (per §2's
shading analysis), nothing user-facing beyond the existing gate.

## 7. Verification

- **Unit / wire-contract (MockWebServer**, the established pattern in the java-itest
  modules and `openhouse-spark-itest`'s `mock` package**):** implemented as
  `OpenHouseCatalogViewsRestTest` in
  `integrations/java/iceberg-1.5/openhouse-java-itest`, running against the shadow jar like
  `SmokeTest`. The fixture serves `GET /v1/config` before any view call, since the embedded
  catalog fetches config at its lazy init. Canned spec-JSON responses drive the cases below;
  the create and replace rows are the ones that pin wire compliance.

  | Case | What it pins |
  |---|---|
  | `loadView` on a full `LoadViewResult` | Iceberg's parser reads the server's load document |
  | Disabled envelope `{"error":{…,"type":"NoSuchViewException","code":404}}` | Surfaces as `NoSuchViewException`, so Spark falls through |
  | Malformed (non-JSON) 404 body | Still surfaces `NoSuchViewException` |
  | List-route 404 (`NoSuchNamespaceException` type, and the disabled envelope) | `listViews` returns an empty list |
  | List-route 500 | Propagates as an exception; only 404s become an empty listing |
  | Create | Recorded body's key set is spec-shaped: kebab-case, no `clusterId`, no repeated path identity |
  | Create-route 404 | Surfaces as `NoSuchNamespaceException` |
  | Replace | Sends `assert-view-uuid` requirements and typed updates |
  | `viewExists` enabled path | True and false, with the recorded request pinned as `GET` on the view path |
  | Every view call, including `/v1/config` | Carries `Authorization: Bearer`; no OAuth route is ever called |
  | Identity headers on the recorded config request | `User-Agent` unconditional; the rest per §5.2 |
  | Gate off, and gate on before the first view op | Zero HTTP traffic |
  | Failed `/v1/config` bootstrap | Fails only that view op, caches nothing; the next view op re-bootstraps |
  | `updateAuthToken` | Rebuilds the embedded catalog with the new token |
  | `close()` | Discard-and-rebuild contract |
  | `renameView` | Wire-silent |
  | `newViewOps` | Unreachable |

  There is no pagination test and no `HEAD` fixture: 1.5.2.17 does no `next-page-token`
  paging, and its `viewExists` issues a GET.

- **ITest (embedded service via `tables-test-fixtures-iceberg-1.5`), Spark 3.5:**
  - Gate off (default): `SHOW VIEWS` empty/analysis-safe, `CREATE VIEW` →
    `AnalysisException`, table reads/writes unaffected — the kept disabled-parity
    assertions (`OpenHouseViewSparkITest`, shared into the 3.5 suite). The old
    `OpenHouseViewEnabledTestSpark3_5` mock round-trip is removed with the mock store (per
    §4's salvage table).
  - Gate on, stubbed server (`OpenHouseViewGateOnTestSpark3_5`, added at integration):
    `SELECT` on an existing table still resolves, with the `loadView` 404 and its
    fall-through crossing the real wire; `SHOW VIEWS` is empty; `CREATE VIEW` fails with the
    normalized analysis error, not a raw 404.
- **Regression:** full `:integrations:...:openhouse-java-runtime` (iceberg-1.5) build,
  java-itest, and spark-3.5 itest suites green; spark-3.1 untouched.
- `./gradlew spotlessCheck` green on touched files: editing `OpenHouseCatalog.java`
  (iceberg-1.5, one of the three known-red synced files) meant bringing it spotless-clean as
  part of this change; removing `OpenHouseViewEnabledTestSpark3_5.java` retires a second;
  `OpenHouseViewSparkITest.java` (spark-3.1) stays untouched and out of scope.
