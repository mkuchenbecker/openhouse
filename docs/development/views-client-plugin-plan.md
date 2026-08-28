# Views Client Plugin: Thin REST Glue — Review Notes & Implementation Plan

**Status:** Implemented on `claude/views-rest-client-impl` (lane C), amended with the binding
dispositions F1/F2/F4/F8/F9 from the blind architecture review (see
[views-execution-checklist.md](views-execution-checklist.md) §2). The gate-on end-to-end itest
is deferred to integration (needs lane S's server).
**Branch:** `claude/views-rest-client-impl`, off `claude/iceberg-rest-spec-compliance-l0s2ju`
(the branch carrying the server-side plan)
**Companion:** [views-iceberg-rest-compliance.md](views-iceberg-rest-compliance.md) — the
server-side plan this client consumes. Read that first; this document assumes its endpoint
surface (`/v1/config`, `/v1/namespaces/{ns}/views` CRUD + `HEAD`, `IcebergErrorResponse`
envelope, views-disabled → `404 NoSuchViewException`).

## 1. Context and scope decisions (owner-confirmed)

- **Thin glue over REST.** The engine-side plugin implements Iceberg's `ViewCatalog` SPI by
  delegating to iceberg-core's own REST client machinery against the spec-compliant views
  endpoints. No generated `ViewApi` WebClient (the evolution path upstream's javadoc
  envisioned) — the REST client *is* the glue, and this is the first step of an eventual
  move of the whole plugin to REST.
- **Spark 3.5 / iceberg-1.5 only.** The iceberg-1.2 / Spark 3.1 runtime stays table-only
  (`BaseMetastoreCatalog`); its Iceberg has no view support at all.
- **Server stays stubbed.** Every view call answers `404` "Views are disabled". The client
  work is wire surface + behavior parity, not persistence. No scope creep.
- **Upstream's gated-view client code (`d5cd328`) is superseded, with salvage.** Verdict in
  §3: the *gate and shell* survive nearly verbatim; only the mock backend is replaced.

## 2. Current state (what the sync brought in)

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

## 3. Salvage assessment of the gated-view code

Not too far divergent — the superseded part is exactly the part upstream labeled MOCK. Diff
against it rather than trashing it:

| Hunk (in `OpenHouseCatalog.java`, iceberg-1.5) | Verdict | Why |
|---|---|---|
| `extends BaseMetastoreViewCatalog` + class javadoc's Spark-routing analysis | **Keep** | Required for `SparkCatalog` to route view calls at all; the disabled-parity analysis is the hard-won part |
| `VIEWS_ENABLED_PROPERTY` gate + `viewsEnabled` flag + `initialize` parsing | **Keep** | The client-side default-off gate stays even with a real server: when off, no view REST call is ever made, preserving exact table-only behavior regardless of server state |
| Disabled-state branches in `loadView`/`listViews`/`dropView`/`buildView`/`renameView` | **Keep verbatim** | Behavior contract with `SparkCatalog`, documented per-method; independent of backend |
| `mockViewStore` + inline `ViewOperations` in `newViewOps` | **Replace** | The mock backend; superseded by REST delegation |
| `buildView(...).withLocation("mock://…")` default-location hack | **Delete** | Location assignment is the server's job; with REST delegation the builder no longer flows through `newViewOps` |
| Store-backed enabled paths of `listViews`/`dropView`/`renameView` | **Replace** | Become REST delegations (rename: see §4.4) |
| `OpenHouseViewSparkITest` (3.1 + disabled-parity assertions) | **Keep** | Tests the kept contract; backend-agnostic |
| `OpenHouseViewEnabledTestSpark3_5` mock round-trip | **Replace** | Depends on the in-memory store; a create/load round-trip cannot pass against the stubbed server. Superseded by disabled-posture e2e + MockWebServer contract tests (§6); the round-trip returns as-is in the persistence milestone |
| Upstream javadoc evolution note ("generated `ViewApi` … mirroring `newTableOps`") | **Supersede** | The direction is Iceberg's REST client, not a second generated WebClient |

## 4. Target design

### 4.1 Delegation, not reimplementation

`OpenHouseCatalog` (iceberg-1.5 copy only) holds an embedded
`org.apache.iceberg.rest.RESTCatalog`, constructed **and initialized lazily on the FIRST view
operation** — never in `OpenHouseCatalog.initialize()` (disposition F4: `RESTCatalog`'s
initialization eagerly fetches `/v1/config`, so doing it in `initialize()` would let a
views-endpoint bootstrap/config failure break **table** operations; done lazily, a bootstrap
failure fails only that view operation, nothing is cached, and the next view op retries).
Consequences: with the gate off, zero cost and zero `/v1/config` call — and even with the
gate on, nothing crosses the wire until a view operation actually happens. On the enabled
path `loadView`/`viewExists`/`listViews`/`dropView` and `buildView` delegate to it;
`renameView` stays unsupported in both gate states (§4.4):

- `loadView` → REST `GET .../views/{view}` (`LoadViewResult` carries full metadata inline —
  no FileIO needed for views)
- `viewExists` → delegated; NOTE (disposition F2): in iceberg 1.5.2.17 `RESTSessionCatalog`
  implements `viewExists` via the `ViewCatalog` default — a `GET` load-and-catch — **not**
  the spec's `HEAD` route. The server's `HEAD` endpoint is simply unused by this client
  version.
- `listViews` → REST `GET .../views`; NOTE (disposition F2): 1.5.2.17's
  `RESTSessionCatalog.listViews` performs a **single GET with no `next-page-token` paging**;
  the server obligation (return all results when `pageToken` is absent, per the spec) is
  recorded in the server plan. Per disposition F1, the delegation catches
  `NoSuchNamespaceException`/`NoSuchViewException` and answers an **empty list**:
  `SparkCatalog.listViews` catches nothing, so a list-route 404 (spec type
  `NoSuchNamespaceException`, or the views-disabled envelope) would otherwise leak a raw
  error out of `SHOW VIEWS`.
- `buildView(...)` → the REST view builder (create → `POST` `CreateViewRequest`;
  replace/createOrReplace → commit `POST` with `assert-view-uuid` requirements + updates —
  the client, not us, owns commit semantics, which is the "plugin as glue" position from the
  upstream #694 discussion). Wire detail observed in 1.5.2.17: `replace()` first probes
  `tableExists` (`GET .../tables/{name}` → 404 expected) before loading the view and
  committing. Create-route 404s arrive as `NoSuchNamespaceException` (disposition F1's
  server-side rendering), which `SparkCatalog.createView` normalizes to Spark's
  `AnalysisException`.
- `dropView` → REST `DELETE`
- `newViewOps` → becomes an unreachable path; throws `IllegalStateException` with a pointer
  to the delegation (the `BaseMetastoreViewCatalog` builder machinery is bypassed)

Error translation comes from iceberg-core's `ErrorHandlers` parsing the
`IcebergErrorResponse` envelope — the exact payoff of the server-side plan: the server's
views-disabled `404 NoSuchViewException` arrives client-side as `NoSuchViewException` with
**no OpenHouse-specific error-mapping code**, and Spark falls through to `loadTable`.

### 4.2 Embedded REST catalog configuration

Derived (at lazy-build time, from the catalog properties captured in `initialize()`) — no
new user-facing keys beyond the existing gate:

| REST catalog property | Source |
|---|---|
| `uri` | the existing `uri` property (same service; REST paths mount alongside `/v1/databases/...`) |
| `header.Authorization` = `Bearer <token>` | the existing `auth-token` property. Use `header.*` passthrough, **not** the REST `token` property — the OAuth2 session machinery (token refresh, `/v1/oauth/tokens`) must stay out of the loop |
| `header.` client-name/version/session headers | mirror what `TablesApiClientFactory` sets, so audit/telemetry sees the same identity on view calls. Actual semantics: `User-Agent` is set **unconditionally** (`openhouse-java-client/` + the `client-version` property, else the jar manifest's Implementation-Version, else `unknown`); `X-Client-Name` only when `client-name` is set; `session-id` **only when `app-id` is set** — tables calls otherwise synthesize a random UUID session that view calls deliberately don't get, since a fabricated UUID would break correlation |
| `prefix` | unset (server serves un-prefixed paths; `/v1/config` returns no override) |

TLS/truststore (**resolved**): Iceberg 1.5.2.17's `HTTPClient` builds its Apache
HttpClient 5 connection manager with `useSystemProperties()`, so the embedded catalog trusts
the JVM's default trust material and honors the standard `javax.net.ssl.trustStore*` system
properties. It exposes no per-catalog truststore hook, and hand-rolling a custom
`RESTClient` just to honor the OpenHouse `trust-store` property would contradict the
thin-glue position. Decision: the `trust-store` catalog property keeps configuring the
tables WebClient only; for view calls over https the service certificate must chain from the
JVM trust material (default cacerts or `javax.net.ssl.trustStore*`). When `trust-store` is
set and views are enabled, the catalog logs a warning at embedded-catalog build time to make
the asymmetry visible.

Token refresh (**resolved**): `updateAuthToken()` propagates by **recreate, not header
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

### 4.3 Both gates stay, and compose

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

### 4.4 Rename

Server-side `rename-view` is out of scope (plain 404, deliberately unclaimed). Delegating
`renameView` would turn that 404 into a misleading `NoSuchViewException`. So `renameView`
throws `UnsupportedOperationException("Renaming views is not supported")` on the enabled
path too — honest, and consistent with the server plan's scope line.

## 5. Implementation phases

1. **Step 0 — verify the runtime jars** (**answered affirmative**, dispositions F8/F9,
   verified against decompiled LinkedIn-fork `1.5.2.17` artifacts): `RESTSessionCatalog`
   implements the view SPI (`ViewSessionCatalog`, via `BaseViewSessionCatalog`), all needed
   REST/view classes are present in `iceberg-core-1.5.2.17` and
   `iceberg-spark-runtime-3.5_2.12-1.5.2.17`, and view REST paths/`ResourcePaths` match the
   server plan. `header.*` passthrough confirmed (`RESTUtil.extractPrefixMap(props,
   "header.")`); TLS finding recorded in §4.2. The hand-rolled-`RESTClient` fallback branch
   is dropped — not needed.
2. **Embedded catalog wiring:** lazy `RESTCatalog` construction on first view operation
   behind the gate (F4 — never in `initialize()`); property derivation per §4.2;
   `updateAuthToken` hook (recreate-on-refresh); `close()` propagation.
3. **Delegation:** replace the mock-backed enabled paths of `loadView`/`listViews`/
   `dropView`/`buildView`, add `viewExists`, neutralize `newViewOps`, delete `mockViewStore`
   and the mock-location hack, set `renameView` per §4.4. Disabled branches untouched.
4. **Javadoc truth-up:** class and method docs describe REST delegation and drop the
   "generated ViewApi" evolution note (superseded).
5. **Tests** (§6).
6. **Docs:** update this file's status (done — see header). No runtime README/catalog doc
   describes `iceberg-views-enabled` yet; user-facing docs land with the persistence
   milestone, when the flag does something visible end-to-end.

Touched files (expected): the iceberg-1.5 `OpenHouseCatalog.java`, its tests, and the two
view itests — nothing in the shared iceberg-1.2 sources, nothing in build files (per §2's
shading analysis), nothing user-facing beyond the existing gate.

## 6. Verification

- **Unit / wire-contract (MockWebServer** — the established pattern in the java-itest
  modules and `openhouse-spark-itest`'s `mock` package**):** implemented as
  `OpenHouseCatalogViewsRestTest` in
  `integrations/java/iceberg-1.5/openhouse-java-itest` (runs against the shadow jar, like
  `SmokeTest`). The fixture serves `GET /v1/config` before any view call — the embedded
  catalog fetches config at its lazy init. Canned spec-JSON responses drive: `loadView`
  parses a full `LoadViewResult`; the disabled envelope
  `{"error":{...,"type":"NoSuchViewException","code":404}}` surfaces as
  `NoSuchViewException`; a list-route 404 (`NoSuchNamespaceException` type, and the
  views-disabled envelope) yields an **empty** `listViews` (the F1 catch); create sends a
  spec-shaped `CreateViewRequest` (asserting the recorded request body's key set —
  kebab-case, no `clusterId`, no repeated path identity); a create-route 404 surfaces as
  `NoSuchNamespaceException`; replace sends `assert-view-uuid` requirements + typed updates;
  `Authorization: Bearer` present on every view call including `/v1/config` (and no OAuth
  route is ever called); lazy-init property (zero HTTP traffic with the gate off, and none
  before the first view op with it on); `updateAuthToken` rebuilds the embedded catalog with
  the new token; `renameView` stays wire-silent; `newViewOps` unreachable. The review round
  added: F4 fault-injection (a failed `/v1/config` bootstrap fails only that view op, caches
  nothing, and the next view op re-bootstraps); list-route **500 propagates** as an
  exception (F1's negative space — only 404s become an empty listing); `viewExists`
  enabled-path true/false with the recorded request pinned as `GET` on the view path;
  `close()` discard-and-rebuild contract; identity headers asserted on the recorded config
  request (unconditional `User-Agent` included); a malformed (non-JSON) 404 body pinned as
  still surfacing `NoSuchViewException`. Per disposition F2 there is **no pagination test**
  (1.5.2.17 does no `next-page-token` paging) and no `HEAD` fixture (`viewExists` issues
  GET).
- **ITest (embedded service via `tables-test-fixtures-iceberg-1.5`), Spark 3.5:**
  - Gate off (default): `SHOW VIEWS` empty/analysis-safe, `CREATE VIEW` →
    `AnalysisException`, table reads/writes unaffected — the kept disabled-parity
    assertions (`OpenHouseViewSparkITest`, shared into the 3.5 suite). The old
    `OpenHouseViewEnabledTestSpark3_5` mock round-trip is removed with the mock store (per
    §3's salvage table).
  - Gate on, stubbed server (deferred to integration — needs lane S's service): `SELECT` on
    an existing table still resolves (loadView 404 → fall-through, end-to-end through the
    REST path); `CREATE VIEW` fails with the normalized analysis error, not a raw 404.
- **Regression:** full `:integrations:...:openhouse-java-runtime` (iceberg-1.5) build,
  java-itest, and spark-3.5 itest suites green; spark-3.1 untouched.
- `./gradlew spotlessCheck` green on touched files: editing `OpenHouseCatalog.java`
  (iceberg-1.5, one of the three known-red synced files) meant bringing it spotless-clean as
  part of this change; removing `OpenHouseViewEnabledTestSpark3_5.java` retires a second;
  `OpenHouseViewSparkITest.java` (spark-3.1) stays untouched and out of scope.
