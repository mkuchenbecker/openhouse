# Model the views API on the Iceberg REST spec, not on a bespoke `/v2` shape

The views wire surface added by PR #43 should be replaced with one modeled on the
Iceberg REST catalog spec: the same seven operations, at the spec's paths, carrying
the spec's request, response, and error documents, serialized by Iceberg's own
parsers. The backend stays stubbed and views stay disabled by default. Only the wire
shape changes.

The deciding argument is installed base. Views are a net-new resource with no
clients, so adopting the spec costs nothing today, and Iceberg views reach engines
(Spark `ResolveViews`, Trino) through no protocol other than the REST catalog. Every
release of the bespoke `/v2` shape turns that adoption into a breaking migration.

**Status:** the server surface is implemented; the backend is still stubbed.
**Branch:** `claude/iceberg-rest-spec-compliance-l0s2ju`, branched off PR
[mkuchenbecker/openhouse#43](https://github.com/mkuchenbecker/openhouse/pull/43)
(carbon copy of upstream [linkedin/openhouse#694](https://github.com/linkedin/openhouse/pull/694)).
**References:** [Iceberg View Spec](https://iceberg.apache.org/view-spec/) ·
[Iceberg REST Catalog OpenAPI](https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml)

## 1. Requirements

**Must**

1. Every operation the `/v2` surface serves keeps a home: create, load, replace,
   list, delete, plus an existence check and client bootstrap.
2. Every field the `/v2` request and response bodies carry keeps a home, with no
   custom wire extensions. §3.2 maps each one.
3. A stock, unmodified Iceberg `RESTCatalog` can bootstrap against the service and
   reach every view route without client-side patching. Views themselves stay
   disabled this milestone, so what it reaches is the disabled response, not data.
4. With views disabled, Spark's `ResolveViews` falls through to `loadTable`, so
   table behavior is unchanged.
5. Error responses on the views routes never carry a stack trace, the requested
   URL, or the submitted document.

**Should**

6. Wire compliance comes from the Iceberg dependency's own models and parsers, not
   from hand-written models pinned by our tests.
7. Nothing about the surface forces a new client-side runtime dependency or shading
   rule.

**Won't, this milestone**

8. Persistence. The service stays views-disabled; only the wire shape changes.
9. Arbitrary commit semantics. The wire shape is `requirements`/`updates`; the
   service may reject update combinations it does not support with a typed 400.
   Shape compliance and capability scope are independent axes.

**Out of scope**

10. `rename-view`, `register-view`, the tables and namespaces REST routes, and the
    OAuth token endpoint. A probe of an unclaimed route gets the spec's plain 404,
    so the server never advertises protocol surface it does not serve.
11. Regenerating `docs/specs/catalog.md`. It needs a booted service and the external
    `widdershins` tool, and it predates the views surface entirely; it regenerates on
    the next scheduled spec refresh.

## 2. Context

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

## 3. Independent compliance review: PR #43 vs the spec

### 3.1 Where the PR diverges

The surface diverges from the spec in every dimension below. Rows 2 through 5 are the
ones that make a stock client impossible: the request, load, create, and replace
documents share no field names, no shapes, and no verbs with the spec's. Rows 8, 9,
and 10 are additive or cosmetic by comparison. Appendix A develops the counterarguments
raised upstream and why they do not survive a resource with no installed base.

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
| 11 | Status vocabulary | `422` for admission failures; `201` create | Views surface uses `200` for create/replace, `204` for delete/exists; no `422` anywhere in the spec's views surface  |

### 3.2 Nothing is lost: OH-concept → REST-concept mapping

Owner's constraint: all data the PR's API passes is needed. Each field maps into spec
structures without custom wire extensions:

| PR field | Spec home |
|---|---|
| `databaseId` | path `{namespace}` (single-level; multi-level → `404 NoSuchNamespaceException`) |
| `viewId` | path `{view}` / `TableIdentifier{namespace, name}` |
| `clusterId` | dropped from the body (server-owned; the server *is* the cluster — matches the upstream objection to repeating identity in the body). Available through `GET /v1/config` `overrides`. Whether it is also exposed as an `openhouse.clusterId` view property is deferred to the persistence milestone |
| `schema` (string) | `CreateViewRequest.schema` (structured `Schema`); `metadata.schemas[]` on read |
| `representations` | `view-version.representations[]` (`SQLViewRepresentation{type, sql, dialect}` — identical shape, now in its spec location) |
| `sourceDialect` | `view-version.summary` entry (e.g. `openhouse.source-dialect`) — `summary` is a spec-sanctioned string map; validator enforces it references a present representation's dialect |
| `defaultCatalog` / `defaultNamespace` | `view-version.default-catalog` / `default-namespace` (spec-native fields) |
| `viewProperties` | `properties` (create) / `metadata.properties` (read); `openhouse.` prefix stays reserved |
| `baseViewVersion` | `CommitViewRequest.requirements[]` → `assert-view-uuid`; staleness detectable client-side by `metadata-location` comparison |
| `viewUri`, `creationTime` | derivable / `version-log[].timestamp-ms`; `openhouse.` properties may carry server-owned annotations exactly as tables do |

## 4. Target design

### 4.1 Endpoints

The seven routes below are the whole surface, all mounted by the tables service with
no `{prefix}` (config returns none). `GET /v1/config` is the load-bearing one: without
it a stock `RESTCatalog` cannot bootstrap, so nothing else is reachable.

| Method & path | Request | Success | Privilege |
|---|---|---|---|
| `GET /v1/config` | — | `200 {defaults:{}, overrides:{}}` | authenticated |
| `GET /v1/namespaces/{namespace}/views?pageToken&pageSize` | — | `200 ListTablesResponse` | `LIST_VIEW` |
| `POST /v1/namespaces/{namespace}/views` | `CreateViewRequest` | `200 LoadViewResult` | `CREATE_VIEW` |
| `GET /v1/namespaces/{namespace}/views/{view}` | — | `200 LoadViewResult` | `SELECT` |
| `POST /v1/namespaces/{namespace}/views/{view}` | `CommitViewRequest` | `200 LoadViewResult` | `UPDATE_VIEW_METADATA` |
| `DELETE /v1/namespaces/{namespace}/views/{view}` | — | `204` | `DELETE_VIEW` |
| `HEAD /v1/namespaces/{namespace}/views/{view}` | — | `204` | `SELECT` |


**`GET /v1/config` declares `endpoints`:** the body is
`{"defaults": {}, "overrides": {}, "endpoints": [...]}` where `endpoints` explicitly lists the
seven implemented routes in the spec's capability-advertisement format
(`"GET /v1/{prefix}/namespaces/{namespace}/views"`, …). An empty config would make a ≥1.6
client assume the default endpoint set, which is wrong in both directions for this server.

**List pagination obligation (server side):** when `pageToken` is absent the service must
return **all** results in one page — the 1.5.2.17 client's `listViews` issues a single GET and
follows no `next-page-token`, so an eagerly paginating server would silently truncate that
client's listing. A `null` continuation token serializes as an omitted `next-page-token` field,
the spec's termination signal. (1.5.2.17's `ListTablesResponse` model predates the pagination
fields, so the list document is assembled field-by-field around Iceberg's own `TableIdentifier`
serializer.)

Errors on these routes only: `IcebergErrorResponse` envelope, never the OpenHouse
`ErrorResponseBody`, and never with a serialized stack. `ViewErrorCode` carries a spec `type`
string per value: `NO_SUCH_VIEW → NoSuchViewException/404`,
`VIEW_ALREADY_EXISTS`/`NAME_ALREADY_EXISTS_AS_TABLE → AlreadyExistsException/409`,
`CONCURRENT_VIEW_MODIFICATION → CommitFailedException/409`,
`COMMIT_STATE_UNKNOWN → CommitStateUnknownException/500`. That last one is the spec's own
rendering for a commit whose outcome the server cannot determine (the `replaceView` route
documents `500` with exactly that type), and it is deliberately distinct from a `409`: a conflict
tells the client its write definitely did not land, while this one tells it to re-read the view
before deciding anything. **Per-route 404 vocabulary:**
`DATABASE_NOT_FOUND` and `VIEWS_DISABLED` (message "Views are disabled") render as
`NoSuchNamespaceException` on the create and list routes and as `NoSuchViewException` on
load/replace/drop/`HEAD` — matching the spec's own per-route 404 types, so a stock client
treats the surface as absent and Spark's `ResolveViews` falls through to `loadTable`,
preserving the design's default-off posture. A multi-level namespace (`%1F` separator) is that
same 404: OpenHouse namespaces are single-level. `HEAD` failures carry no body at any status,
per the spec. Admission codes move off `422`, which the spec's views surface does not use,
onto `400` with the distinct `ValidationException` type. Validation failures: `400
BadRequestException`-typed envelope listing all accumulated violations in `message`
(multi-failure accumulation from the PR is kept). Malformed or missing request bodies are also
views-surface `400 BadRequestException` envelopes with fixed messages (parser messages may echo
the submitted document, and every error message is copied into audit events).

**The `/v1/**` request-mapping failure surface:** the tables service runs with
`throw-exception-if-no-handler-found`, and the global handler renders unknown paths as
OpenHouse-envelope 400s. The views error rendering owns `/v1/**`:
`NoHandlerFoundException` under `/v1/**` → `404 NotFoundException` Iceberg envelope with the
fixed message `"Route does not exist"` — the requested URL is attacker-chosen text and is never
echoed into the envelope or audit events; method and path are logged server-side instead (this
404 is the "plain 404" for `rename-view`/`register-view` probes). A wrong method on a known
`/v1` route → `405 MethodNotAllowedException` envelope (plus `Allow`); a wrong content type →
`415 UnsupportedMediaTypeException` envelope (the 405/415 type strings are self-describing —
the spec has no examples for those statuses). A parameter that cannot bind to its declared
type (e.g. non-numeric `pageSize`) → `400 BadRequestException` with a fixed message.
`AccessDeniedException` on the view routes → `403 ForbiddenException` envelope with no
stacktrace leakage; uncoded infrastructure failures → `503 ServiceUnavailableException`;
unexpected server faults → `500 InternalServerError` with a fixed message. 401 stays a bare
status: authentication is rejected by the token interceptor before dispatch. Non-`/v1` paths
keep their legacy behavior byte-for-byte (the OpenHouse-envelope 400 for unknown paths; the
framework's bare 405/415 with `Allow`/`Accept` headers). Advice ordering
(`@Order(HIGHEST_PRECEDENCE)` on the views-scoped advice, `+1` on the `/v1` advice) beats the
un-ordered global handler. The two view path templates are owned once
(`IcebergRestViewPaths`) and consumed by the controller mappings, the audit redactor's scope,
the per-route 404 decision (keyed off the matched-pattern attribute, trailing-slash tolerant)
and the `/v1/config` endpoints list, so the route shape cannot drift apart across those
consumers.

### 4.2 Serialization strategy — use Iceberg's own models and parsers

`com.linkedin.iceberg:iceberg-core:1.5.2.17` is already an `api` dependency of
`services/tables` (via `openhouse.iceberg-conventions-1.5.2`). It ships the REST view protocol
classes with their canonical JSON parsers:
`o.a.i.rest.requests.CreateViewRequest(Parser)`, `UpdateTableRequest(Parser)` (the commit
envelope, shared with views — there is no separate `CommitViewRequest` class in 1.5.2.17),
`o.a.i.rest.responses.LoadViewResponse(Parser)`, `ListTablesResponse`,
`ErrorResponse(Parser)`, `o.a.i.view.ViewMetadata(Parser)`, `ViewVersion`,
`SQLViewRepresentation`, `o.a.i.UpdateRequirement.AssertViewUUID`, and
`o.a.i.rest.RESTSerializers` to register them all on a Jackson `ObjectMapper`.

Using these instead of hand-rolled Lombok models makes wire compliance a property of the
dependency, not of our tests: kebab-case naming, required-field enforcement, update/requirement
polymorphism, and single-`SQL`-representation rules come from the reference implementation.

**Decided mechanism (checklist §2):** the controllers consume and produce `String` bodies
parsed/serialized with Iceberg's own parsers (`RESTCatalogAdapter` style) — no custom Jackson
`HttpMessageConverter`s, and the global Spring mapper plays no part in these routes. Bodies are
received as raw bytes and decoded as UTF-8 explicitly (RFC 8259), sidestepping
`StringHttpMessageConverter`'s ISO-8859-1 default. Malformed-JSON errors therefore belong to
the views error surface, not the global handler. The serialization helpers live in
`IcebergRestWire` (`services/tables .../api/icebergrest/`), including a dedicated
`RESTSerializers`-registered mapper for the two documents 1.5.2.17 has no parser entry point
for (the list-views and config bodies).

The resolved `iceberg-core-1.5.2.17` jar contains every class listed above; `javap` on the
jar is how to re-check it after a dependency bump.

### 4.3 What happens to the PR's surface (this branch replaces, not coexists)

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

### 4.4 Validation (structural only, as before)

`OpenHouseViewsApiValidator` reworked to the new shapes, still accumulating all violations:
- namespace: identifier charset/length rules (single-level enforcement is not a validation 400:
  a multi-level namespace is the spec's 404, decided at the handler); view name: same rules.
- `CreateViewRequest`: parser-enforced required fields and schema parsing, plus: ≥1
  representation, SQL-typed representations only, unique normalized dialects, every dialect in
  the configured supported set; UTF-8 size caps (measured on the canonical serialized schema
  and on each representation's SQL); reserved property keys rejected.
- **`openhouse.source-dialect` summary key is optional:** with a single representation the
  server defaults it to that representation's dialect (the unique-dialect rule makes this well
  defined), so a stock client's create passes unmodified; it is required exactly when
  representations are plural, and when present must name a supported dialect and a supplied
  representation.
- `CommitViewRequest` (`UpdateTableRequest`): requirements limited to `assert-view-uuid`;
  update actions limited to the view-update set (`assign-uuid`, `upgrade-format-version`,
  `add-schema`, `set-location`, `set-properties`, `remove-properties`, `add-view-version`,
  `set-current-view-version`); `add-view-version` payloads held to the same representation
  rules as create; `set-properties` to the same reserved-key rules; an `identifier` in the
  body, when present, must match the path.
- `pageSize` ≥ 1 if present; `pageToken` opaque (no shape validation).

## 5. Implementation

1. **Dependency check (step 0):** done, affirmative — see §4.2.
2. **Error envelope:** `IcebergRestViewsExceptionHandler` (views-scoped
   `@RestControllerAdvice`, `@Order(HIGHEST_PRECEDENCE)`) renders `ViewApiException`,
   `AccessDeniedException`, `AuthorizationServiceException` and unexpected faults as
   `ErrorResponse`-serialized envelopes with the per-route 404 types;
   `V1RestUnresolvedPathExceptionHandler` owns `NoHandlerFoundException` for `/v1/**` and
   falls back to `OpenHouseExceptionHandler.unresolvedRouteErrorResponseBody` (extracted for
   reuse) everywhere else. Both advices implement the new `AuditedResponseRenderer` marker so
   `ServiceAuditAspect` still audits failures they render (the aspect's failed-request pointcut
   was widened to the marker and made tolerant of pre-serialized `String` envelope bodies).
   `ViewErrorCode` carries the type strings, derived from Iceberg's exception classes at
   compile time.
3. **Wire plumbing:** `IcebergRestWire` — static Iceberg parsers for
   create/commit/load-result/error documents; a dedicated `RESTSerializers`-registered mapper
   for the list-views and config documents; fixed redacted messages for parse failures.
4. **Controller:** `IcebergRestViewsController` with the seven routes (§4.1), `@Secured` as
   today (`LIST_VIEW`, `CREATE_VIEW`, `SELECT` for load and `HEAD`, `UPDATE_VIEW_METADATA`,
   `DELETE_VIEW`; `/v1/config` authenticated-only), `String`/`byte[]` bodies per §4.2,
   delegating to the reshaped `ViewsApiHandler` (parse → validate → unwrap → service →
   serialize). **Wire envelopes are unwrapped at the handler:** `ViewsService` speaks
   `ViewMetadata`, `List<MetadataUpdate>`, `List<UpdateRequirement>`, `TableIdentifier`,
   `Schema`, `ViewVersion` and page tokens (`ViewIdentifiersPage`) — never
   `CreateViewRequest`/`UpdateTableRequest`.
5. **Validator rework** per §4.4 (`OpenHouseViewsApiValidator` on Iceberg types; shared
   `ApiValidatorUtil` identifier rules kept).
6. **Stub service:** `ViewsDisabledService` unchanged in posture; throws
   `ViewApiException(VIEWS_DISABLED)` from the new interface methods. `GET /v1/config` is
   served by the handler even while views are disabled — bootstrap precedes the 404s — and
   declares the endpoints list.
7. **Audit redaction:** `ViewRequestPayloadRedactor` scoped to the `/v1` view write routes,
   recursively replacing every `schema` and `sql` value in both request shapes (create and
   commit, including `add-schema` and `add-view-version` nestings).
8. **`/v2` surface removed** with its tests; test intent migrated:
   - `IcebergRestViewsContractTest` freezes the wire surface: exact serialized key sets
     (kebab-case) for `LoadViewResult`, `ListTablesResponse` and the error envelope,
     round-trips through Iceberg parsers, a spec-example create document, the `/v1/config`
     body, and the error-taxonomy/type-vocabulary pins (no 422 on this surface).
   - `IcebergRestViewsControllerTest` (MockMvc): per-route views-disabled envelope,
     `HEAD` 404 with empty body, 401s, malformed/missing bodies, multi-level namespaces,
     `/v1/**` vs legacy unresolved paths, the full error-code matrix on item and
     collection routes, 403/503/500 envelopes, and audit redaction on the failure path.
   - `IcebergRestViewsValidatorTest` and `...MultiDialectTest` (source-dialect optionality and the spark+trino shape),
     `ViewRequestPayloadRedactorTest`, `ViewsDisabledServiceTest`,
     `IcebergRestViewsPrivilegeTest` reworked to the new shapes.
9. **Docs:** this file's status updated. `docs/specs/catalog.md` regeneration is deferred: it
   requires booting the service plus the external `widdershins` tool, and the document
   predates the views surface entirely (the retired `/v2` routes were never folded in either);
   regenerate on the next scheduled spec refresh.

## 6. Verification

- `./gradlew :services:common:test :services:tables:test` passes: tables 660, common 29.
- `./gradlew :services:common:spotlessCheck :services:tables:spotlessCheck` — clean. The three
  known spotless-red files synced from upstream (`OpenHouseCatalog.java` iceberg-1.5,
  `OpenHouseViewEnabledTestSpark3_5.java`, `OpenHouseViewSparkITest.java`) are client-plugin
  files and were not touched here.
- Contract tests pin serialized key sets and round-trips as described in §4.8; the
  views-disabled MockMvc pins are exactly the §2.2 smoke shapes: `GET /v1/config` 200;
  `GET .../views/x` 404 with
  `{"error":{"message":"Views are disabled","type":"NoSuchViewException","code":404}}`;
  `HEAD` 404 with empty body.
- Two e2e tests pin the unknown-`/v1`-path contract (the Iceberg 404 envelope); the
  non-`/v1` legacy rendering is pinned separately.
- A stock `RESTCatalog` pointed at a running instance, confirming bootstrap and the
  `loadView` 404 fall-through, is covered by the client plugin's gate-on integration test
  (`OpenHouseViewGateOnTestSpark3_5`).

## Appendix A. The upstream dispute


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
- The **v1-vs-v2 debate** raised in review on [upstream #694](https://github.com/linkedin/openhouse/pull/694) dissolves under spec modeling: the spec's
  own paths are versioned `/v1/...`, distinct from the OH tables routes, and collide with
  nothing (`/v1/namespaces/...` and `/v1/config` are unclaimed in the tables service).
- One author point stands and is preserved: **the commit model does not require implementing
  arbitrary commit semantics now.** The wire shape is `requirements`/`updates`; the (stubbed)
  service — and its later real implementation — may reject update combinations it does not
  support with a typed 400. Shape compliance and capability scope are independent axes.
