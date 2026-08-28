# Views Client Plugin: Thin REST Glue — Review Notes & Implementation Plan

**Status:** Plan (no implementation yet)
**Branch:** `claude/iceberg-rest-spec-compliance-l0s2ju` (same branch as the server-side plan)
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

`OpenHouseCatalog` (iceberg-1.5 copy only) holds a lazily-created, embedded
`org.apache.iceberg.rest.RESTCatalog`, initialized in `initialize()` **only when
`iceberg-views-enabled=true`** (zero cost, zero `/v1/config` call in the default-off state).
The five `ViewCatalog` methods and `buildView` delegate to it on the enabled path:

- `loadView` → REST `GET .../views/{view}` (`LoadViewResult` carries full metadata inline —
  no FileIO needed for views)
- `viewExists` → REST `HEAD` (comes free with delegation)
- `listViews` → REST `GET .../views` (client handles `next-page-token` paging)
- `buildView(...)` → the REST view builder (create → `POST` `CreateViewRequest`;
  replace/createOrReplace → commit `POST` with `assert-view-uuid` requirements + updates —
  the client, not us, owns commit semantics, which is the "plugin as glue" position from the
  upstream #694 discussion)
- `dropView` → REST `DELETE`
- `newViewOps` → becomes an unreachable path; throws `IllegalStateException` with a pointer
  to the delegation (the `BaseMetastoreViewCatalog` builder machinery is bypassed)

Error translation comes from iceberg-core's `ErrorHandlers` parsing the
`IcebergErrorResponse` envelope — the exact payoff of the server-side plan: the server's
views-disabled `404 NoSuchViewException` arrives client-side as `NoSuchViewException` with
**no OpenHouse-specific error-mapping code**, and Spark falls through to `loadTable`.

### 4.2 Embedded REST catalog configuration

Derived in `initialize()` from the existing catalog properties — no new user-facing keys
beyond the existing gate:

| REST catalog property | Source |
|---|---|
| `uri` | the existing `uri` property (same service; REST paths mount alongside `/v1/databases/...`) |
| `header.Authorization` = `Bearer <token>` | the existing `auth-token` property. Use `header.*` passthrough, **not** the REST `token` property — the OAuth2 session machinery (token refresh, `/v1/oauth/tokens`) must stay out of the loop |
| `header.` client-name/version/session headers | mirror what `TablesApiClientFactory` sets, so audit/telemetry sees the same identity on view calls |
| `prefix` | unset (server serves un-prefixed paths; `/v1/config` returns no override) |

TLS/truststore: the `trust-store` property configures the WebClient today; Iceberg's
`HTTPClient` trusts the JVM's default store. Step-0 item: confirm the deployment's certs
chain from the default store; if not, wire a custom `HTTPClient` builder. Token refresh via
`updateAuthToken()` must also update the embedded catalog's header (small hook; step 0
confirms the cheapest mechanism — recreate vs. header mutation).

### 4.3 Both gates stay, and compose

- **Client gate off (default):** no view REST calls at all; behavior byte-identical to
  today's disabled state (kept hunks, kept tests).
- **Client gate on, server stubbed (this milestone):** every view op crosses the wire and
  gets the spec envelope; `loadView` → `NoSuchViewException` → Spark still resolves tables.
  This is the end-to-end proof that the default-off posture holds through the *real* code
  path, with zero mock backend.
- **Client gate on, server enabled (future milestone):** works with no further client change
  — that is the point of the thin glue.

### 4.4 Rename

Server-side `rename-view` is out of scope (plain 404, deliberately unclaimed). Delegating
`renameView` would turn that 404 into a misleading `NoSuchViewException`. So `renameView`
throws `UnsupportedOperationException("Renaming views is not supported")` on the enabled
path too — honest, and consistent with the server plan's scope line.

## 5. Implementation phases

1. **Step 0 — verify the runtime jars** (LinkedIn fork, 1.5.2): `RESTSessionCatalog`
   implements the view SPI (`ViewSessionCatalog`), `org.apache.iceberg.rest.*` is present in
   `iceberg-spark-runtime-3.5_2.12`, and view REST paths/`ResourcePaths` match the server
   plan. Also confirm the `header.*` passthrough and TLS notes in §4.2. Record findings here.
   (Same fallback posture as the server plan: if the fork strips these classes, hand-roll a
   minimal `RESTClient`-based `ViewOperations` — still no generated client.)
2. **Embedded catalog wiring:** lazy `RESTCatalog` construction in `initialize()` behind the
   gate; property derivation per §4.2; `updateAuthToken` hook; `close()` propagation.
3. **Delegation:** replace the mock-backed enabled paths of `loadView`/`listViews`/
   `dropView`/`buildView`, add `viewExists`, neutralize `newViewOps`, delete `mockViewStore`
   and the mock-location hack, set `renameView` per §4.4. Disabled branches untouched.
4. **Javadoc truth-up:** class and method docs describe REST delegation and drop the
   "generated ViewApi" evolution note (superseded).
5. **Tests** (§6).
6. **Docs:** update this file's status; note the new behavior in the runtime README/catalog
   docs where `iceberg-views-enabled` is described.

Touched files (expected): the iceberg-1.5 `OpenHouseCatalog.java`, its tests, and the two
view itests — nothing in the shared iceberg-1.2 sources, nothing in build files (per §2's
shading analysis), nothing user-facing beyond the existing gate.

## 6. Verification

- **Unit / wire-contract (MockWebServer** — already the established pattern in
  `openhouse-spark-itest`'s `mock` package and java-itest**):** canned spec-JSON responses
  drive: `loadView` parses a full `LoadViewResult`; the disabled envelope
  `{"error":{...,"type":"NoSuchViewException","code":404}}` surfaces as
  `NoSuchViewException`; create sends a spec-shaped `CreateViewRequest` (assert the recorded
  request body's key set — kebab-case, no `clusterId`, no repeated identity); replace sends
  requirements + updates; list paginates on `next-page-token`; `Authorization` header
  present on every view call.
- **ITest (embedded service via `tables-test-fixtures-iceberg-1.5`), Spark 3.5:**
  - Gate off (default): `SHOW VIEWS` empty, `CREATE VIEW` → `AnalysisException`, table
    reads/writes unaffected — the kept disabled-parity assertions.
  - Gate on, stubbed server: `SELECT` on an existing table still resolves (loadView 404 →
    fall-through, end-to-end through the REST path); `CREATE VIEW` fails with the normalized
    analysis error, not a raw 404.
- **Regression:** full `:integrations:...:openhouse-java-runtime` and spark-3.5 runtime/itest
  suites green; spark-3.1 untouched and green.
- `./gradlew spotlessCheck` on touched modules (same pre-existing-red caveat as the server
  plan: `OpenHouseCatalog.java` iceberg-1.5 is one of the three known-red synced files —
  touching it means bringing it to spotless-clean as part of this change, which stops being
  "unrelated reformatting" once the file is edited anyway).
