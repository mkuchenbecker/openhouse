# Views API: Iceberg REST Spec Compliance — Review Notes & Implementation Plan

**Status:** Plan (no implementation yet)
**Branch:** `claude/iceberg-rest-spec-compliance-l0s2ju`, branched off PR
[mkuchenbecker/openhouse#43](https://github.com/mkuchenbecker/openhouse/pull/43)
(carbon copy of upstream [linkedin/openhouse#694](https://github.com/linkedin/openhouse/pull/694)).
**References:** [Iceberg View Spec](https://iceberg.apache.org/view-spec/) ·
[Iceberg REST Catalog OpenAPI](https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml)

## 1. Context

PR #43 / upstream #694 adds a `/v2/databases/{databaseId}/views` wire surface (models, routes,
handler, structural validation) with the business logic deliberately stubbed: the only
`ViewsService` implementation returns a 404 `VIEWS_DISABLED` so Spark's `ResolveViews` falls
through to `loadTable`.

Upstream review feedback flagged, as blocking, that the surface diverges from the Iceberg REST
catalog specification in nearly every dimension: request model, load response, list response,
replace semantics, and error envelope. The author's counter-position was that partial alignment
buys no interop and that consistency with the existing (non-REST) tables API matters more.

This document is an independent review of that dispute against the spec, and the plan for
reworking the surface on this branch. **Scope decision (owner-confirmed):** solve the same
problems the PR's APIs solve — the five operations plus existence-check and client bootstrap —
but model them on the Iceberg REST spec. Ancillary protocol surface (rename-view,
register-view, table routes, OAuth endpoints) is out of scope. The backend stays stubbed
(views-disabled posture unchanged).

## 2. Independent compliance review: PR #43 vs the spec

### 2.1 Where the PR diverges

| # | Dimension | PR #43 (`/v2`) | Iceberg REST spec |
|---|-----------|----------------|-------------------|
| 1 | Routes | `/v2/databases/{databaseId}/views[/{viewId}]` | `/v1/{prefix}/namespaces/{namespace}/views[/{view}]` |
| 2 | Create request | `CreateUpdateViewRequestBody`: requires `viewId`, `databaseId`, `clusterId` (repeats path identity, adds OH-only field), `schema` as an escaped JSON *string*, top-level `representations`, required `sourceDialect`, optional `baseViewVersion` | `CreateViewRequest`: `name`, optional `location`, `schema` as a structured object, `view-version` (which owns `representations`, `default-catalog`, `default-namespace`, `summary`), required `properties`. Namespace comes from the path only |
| 3 | Create response | `201` with pointer-only body | `200` with `LoadViewResult` = `{metadata-location, metadata, config?}` where `metadata` is the **complete** view-metadata (`view-uuid`, `format-version`, `location`, `current-version-id`, `versions[]`, `version-log[]`, `schemas[]`, `properties`) |
| 4 | Load response | Pointer only: `viewId`, `databaseId`, `clusterId`, `viewUri`, `metadataLocation`, `viewVersion`, `creationTime`. SQL/schema/history intentionally omitted | Full `LoadViewResult` as above — the definition *is* the payload |
| 5 | Replace | `PUT` whole-definition upsert; optimistic concurrency via `baseViewVersion` string compared to a metadata pointer; `200/201` split | `POST` to the view path with `CommitViewRequest` = `{identifier?, requirements[], updates[]}`; requirements = `assert-view-uuid`; updates are typed (`add-schema`, `add-view-version`, `set-current-view-version`, `set-location`, `set-properties`, `remove-properties`, `assign-uuid`, `upgrade-format-version`); `409 CommitFailedException` on failed requirement; `500/502/504 CommitStateUnknownException` |
| 6 | List | Spring `Page<GetViewResponseBody>` (`pageResults.content[]`, `pageable`, `totalElements`, …) with sparse elements (`null` fields, `creationTime: 0`); `page`/`size`/`sortBy` params | `ListTablesResponse` = `{identifiers: [{namespace, name}], next-page-token}`; `pageToken`/`pageSize` params; token is opaque, absence/null terminates |
| 7 | Errors | Global `ErrorResponseBody` `{status, error, message, stacktrace, cause}`; the 14-value `ViewErrorCode` enum is intentionally **never serialized** — only the HTTP status reaches the wire | `IcebergErrorResponse` = `{error: {message, type, code, stack?}}`, `type` carrying the exception name (`NoSuchViewException`, `AlreadyExistsException`, `CommitFailedException`, …) |
| 8 | Existence check | none | `HEAD .../views/{view}` → `204`/`404` |
| 9 | Client bootstrap | none | `GET /v1/config` → `{defaults, overrides}`; required for a stock `RESTCatalog` client to connect |
| 10 | Field naming | camelCase | kebab-case (`metadata-location`, `view-uuid`, `version-id`, `timestamp-ms`) |
| 11 | Status vocabulary | `422` for admission failures; `201` create | Views surface uses `200` for create/replace, `204` for delete/exists; no `422` anywhere in the spec |

### 2.2 Judgment on the upstream dispute

The upstream blocking feedback is correct on substance, and the author's two strongest
counterarguments do not survive scrutiny:

- **"Partial conformance buys nothing today."** True as stated — and an argument *for* full
  modeling of these endpoints, not for a bespoke shape. Views are a net-new resource with zero
  client installed base; this is the one moment adopting the spec costs nothing. Every release
  of the bespoke `/v2` shape makes later adoption a breaking migration. Iceberg views only have
  engine support (Spark `ResolveViews`, Trino) through the catalog/REST protocol, so an
  OH-flavored views API guarantees custom client work later.
- **"Consistency with tables."** Tables carry a legacy contract with an installed base; views
  don't. Consistency achieved by copying a non-standard shape onto a greenfield resource
  converts one legacy surface into two. The tables migration question is real but separable —
  and is not made harder by views being spec-shaped (if anything it produces the shared
  REST-model plumbing a tables migration would reuse).
- The **v1-vs-v2 debate** (abhisheknath2011's comment) dissolves under spec modeling: the spec's
  own paths are versioned `/v1/...`, distinct from the OH tables routes, and collide with
  nothing (`/v1/namespaces/...` and `/v1/config` are unclaimed in the tables service).
- One author point stands and is preserved: **the commit model does not require implementing
  arbitrary commit semantics now.** The wire shape is `requirements`/`updates`; the (stubbed)
  service — and its later real implementation — may reject update combinations it does not
  support with a typed 400. Shape compliance and capability scope are independent axes.

### 2.3 Nothing is lost: OH-concept → REST-concept mapping

Owner's constraint: all data the PR's API passes is needed. Each field maps into spec
structures without custom wire extensions:

| PR field | Spec home |
|---|---|
| `databaseId` | path `{namespace}` (single-level; multi-level → `404 NoSuchNamespaceException`) |
| `viewId` | path `{view}` / `TableIdentifier{namespace, name}` |
| `clusterId` | dropped from the body (server-owned; the server *is* the cluster — matches the upstream objection to repeating identity in the body). Exposed via `GET /v1/config` `overrides` and/or `openhouse.clusterId` view property if needed |
| `schema` (string) | `CreateViewRequest.schema` (structured `Schema`); `metadata.schemas[]` on read |
| `representations` | `view-version.representations[]` (`SQLViewRepresentation{type, sql, dialect}` — identical shape, now in its spec location) |
| `sourceDialect` | `view-version.summary` entry (e.g. `openhouse.source-dialect`) — `summary` is a spec-sanctioned string map; validator enforces it references a present representation's dialect |
| `defaultCatalog` / `defaultNamespace` | `view-version.default-catalog` / `default-namespace` (spec-native fields) |
| `viewProperties` | `properties` (create) / `metadata.properties` (read); `openhouse.` prefix stays reserved |
| `baseViewVersion` | `CommitViewRequest.requirements[]` → `assert-view-uuid`; staleness detectable client-side by `metadata-location` comparison |
| `viewUri`, `creationTime` | derivable / `version-log[].timestamp-ms`; `openhouse.` properties may carry server-owned annotations exactly as tables do |

## 3. Target design

### 3.1 Endpoints (all mounted by the tables service; no `{prefix}` — config returns none)

| Method & path | Request | Success | Privilege |
|---|---|---|---|
| `GET /v1/config` | — | `200 {defaults:{}, overrides:{}}` | authenticated |
| `GET /v1/namespaces/{namespace}/views?pageToken&pageSize` | — | `200 ListTablesResponse` | `LIST_VIEW` |
| `POST /v1/namespaces/{namespace}/views` | `CreateViewRequest` | `200 LoadViewResult` | `CREATE_VIEW` |
| `GET /v1/namespaces/{namespace}/views/{view}` | — | `200 LoadViewResult` | `SELECT` |
| `POST /v1/namespaces/{namespace}/views/{view}` | `CommitViewRequest` | `200 LoadViewResult` | `UPDATE_VIEW_METADATA` |
| `DELETE /v1/namespaces/{namespace}/views/{view}` | — | `204` | `DELETE_VIEW` |
| `HEAD /v1/namespaces/{namespace}/views/{view}` | — | `204` | `SELECT` |

Out of scope: `rename-view`, `register-view` (absent; if probed, plain 404 — deliberate, not
`406`, to avoid claiming protocol surface we don't serve), tables/namespaces REST routes,
OAuth token endpoint.

Errors on these routes only: `IcebergErrorResponse` envelope. `ViewErrorCode` gains a spec
`type` string per value, e.g. `NO_SUCH_VIEW → NoSuchViewException/404`,
`DATABASE_NOT_FOUND → NoSuchNamespaceException/404`,
`VIEW_ALREADY_EXISTS`/`NAME_ALREADY_EXISTS_AS_TABLE → AlreadyExistsException/409`,
`CONCURRENT_VIEW_MODIFICATION → CommitFailedException/409`,
`VIEWS_DISABLED → NoSuchViewException/404` (message "Views are disabled") — a stock client
treats it as absent and Spark falls through to `loadTable`, preserving the design's default-off
posture. Admission codes move off `422` (not spec vocabulary) onto `400`/`409` with distinct
`type` strings. Validation failures: `400 BadRequestException`-typed envelope listing all
accumulated violations in `message` (multi-failure accumulation from the PR is kept).

### 3.2 Serialization strategy — use Iceberg's own models and parsers

`com.linkedin.iceberg:iceberg-core:1.5.2` is already an `api` dependency of
`services/tables` (via `openhouse.iceberg-conventions-1.5.2`). Iceberg 1.5.x ships the REST
view protocol classes with their canonical JSON parsers:
`o.a.i.rest.requests.CreateViewRequest(Parser)`, `UpdateTableRequest` (the commit envelope,
shared with views), `o.a.i.rest.responses.LoadViewResponse(Parser)`, `ListTablesResponse`,
`ErrorResponse(Parser)`, `o.a.i.view.ViewMetadata(Parser)`, `ViewVersion`,
`SQLViewRepresentation`, `o.a.i.UpdateRequirement.AssertViewUUID`, and
`o.a.i.rest.RESTSerializers` to register them all on a Jackson `ObjectMapper`.

Using these instead of hand-rolled Lombok models makes wire compliance a property of the
dependency, not of our tests: kebab-case naming, required-field enforcement, update/requirement
polymorphism, and single-`SQL`-representation rules come from the reference implementation.
The controller uses a dedicated `ObjectMapper` (configured via `RESTSerializers.registerAll`)
scoped to these routes, so the global Spring mapper and every other service's wire shape are
untouched.

**Step 0 (de-risk):** verify the LinkedIn iceberg fork 1.5.2 jar actually contains these
classes (`javap` after dependency resolve). Fallback if absent/stripped: keep the spec shapes
but hand-model them, with the contract test pinning serialized JSON byte-for-byte to spec
examples.

### 3.3 What happens to the PR's surface (this branch replaces, not coexists)

**Removed:** `ViewsController` (`/v2` routes), `CreateUpdateViewRequestBody`,
`GetViewResponseBody`, `GetAllViewsResponseBody`, the OH `ViewRepresentation` component,
`PUT` upsert semantics, `page/size/sortBy` list params, Spring `Page` in the wire contract,
`ViewDto`'s tri-modal write/read/list role (the upstream code-smell finding — the service
interface speaks typed Iceberg models instead), `ViewValidationErrorCode`'s `422` mapping.

**Kept (unchanged or lightly adapted):** the `ApiValidatorUtil` shared-validator refactor and
all non-view validator touchpoints; `CodedApiException` seam; `ServiceAuditPayloadRedactor`
seam (redactor reworked to strip `sql`/`schema` from the new request shapes);
`Privileges` additions and `@Secured` wiring; `ViewErrorCode` enum (gains `type` strings);
`ViewsService`-stubbed/default-off posture; cluster property
`cluster.tables.views.supported-dialects`; identifier and byte-size limits (128-char ids,
256 KiB SQL / 512 KiB schema); reserved `openhouse.`/`policies` property-key rejection.

### 3.4 Validation (structural only, as before)

`OpenHouseViewsApiValidator` reworked to the new shapes, still accumulating all violations:
- namespace: single level, identifier charset/length rules; view name: same rules.
- `CreateViewRequest`: parser-enforced required fields, plus: schema round-trips through
  Iceberg `SchemaParser`; ≥1 representation, unique normalized dialects, every dialect in the
  configured supported set; `summary` source-dialect key references a present representation;
  UTF-8 size caps; reserved property keys rejected.
- `CommitViewRequest`: requirements limited to `assert-view-uuid`; update actions limited to
  the view-update set; structurally valid `AddViewVersion` payloads (same representation rules
  as create).
- `pageSize` ≥ 1 if present; `pageToken` opaque (no shape validation).

## 4. Implementation plan (phases, each independently green)

1. **Dependency check (step 0):** resolve `:services:tables` compile classpath; confirm the
   REST view classes above exist in the LinkedIn 1.5.2 fork. Record result here.
2. **Error envelope:** views-scoped `@RestControllerAdvice` producing
   `ErrorResponse`-serialized bodies; `ViewErrorCode` type mapping; tests for every code.
3. **Wire plumbing:** dedicated `ObjectMapper` bean + `RESTSerializers` registration;
   request-parsing helpers.
4. **Controller:** `IcebergRestViewsController` with the seven routes (§3.1), `@Secured` as
   today, delegating to a reshaped `ViewsApiHandler` (validate → service → map). Handler/service
   interfaces move to Iceberg types (`CreateViewRequest`, `UpdateTableRequest`,
   `ViewMetadata`, identifier lists + page token).
5. **Validator rework** per §3.4 (delete obsolete checks, keep shared utils).
6. **Stub service:** `ViewsDisabledService` unchanged in posture; throws
   `ViewApiException(VIEWS_DISABLED)` from the new interface methods. `GET /v1/config` returns
   static empty defaults/overrides (served even while views are disabled — bootstrap must
   precede the 404s).
7. **Audit redaction:** `ViewRequestPayloadRedactor` reworked for the new request JSON
   (redact `sql`, `schema` subtrees).
8. **Remove `/v2` surface** and its tests; migrate the test intent:
   - `ViewApiContractTest` → freezes the REST wire surface instead: exact serialized key sets
     (kebab-case) for `LoadViewResult`, `ListTablesResponse`, error envelope; round-trips
     through Iceberg parsers; spec-example fixtures.
   - Validator/controller/handler/redactor/privilege tests reworked to the new shapes;
     multi-dialect test kept (spark+trino config accepts two representations).
   - New: `HEAD` semantics, `/v1/config`, commit-request structural tests,
     `VIEWS_DISABLED → NoSuchViewException` envelope test (the Spark fall-through guarantee).
9. **Docs:** regenerate `docs/specs/catalog.md` per `docs/specs/README.md`; update this file's
   status.

## 5. Verification

- `./gradlew :services:common:test :services:tables:test` — full green (907+ existing tests
  must stay green; view tests reworked as above).
- `./gradlew spotlessCheck` on touched modules. Known pre-existing red on three files synced
  from upstream (`OpenHouseCatalog.java` iceberg-1.5, `OpenHouseViewEnabledTestSpark3_5.java`,
  `OpenHouseViewSparkITest.java`) — out of scope here, must not widen.
- Contract test compares serialized JSON against fixtures lifted verbatim from the spec's
  examples (kebab-case keys, envelope nesting).
- Manual/MockMvc smoke: `GET /v1/config` 200; `GET .../views/x` 404 with
  `{"error":{"message":"Views are disabled","type":"NoSuchViewException","code":404}}`;
  `HEAD` 404 with empty body.
- Stretch (documented, not gating): point a stock `RESTCatalog` at a running instance and
  confirm bootstrap + `loadView` 404 fall-through.
