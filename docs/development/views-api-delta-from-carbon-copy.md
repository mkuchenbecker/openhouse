# The views wire contract was replaced, not extended: no `/v2` request survives

Nothing a client wrote against the bespoke `/v2` views API still works. The routes are
gone, the request and response documents share no field with their replacements, and the
error envelope changed shape. What replaced them is the Iceberg REST catalog views
surface: seven routes under `/v1`, carrying the spec's own request, response and error
documents, serialized by Iceberg's own parsers. A stock `RESTCatalog` can now bootstrap
and reach every view route; a hand-written `/v2` client cannot reach anything at all — it
gets an unresolved-route error, and on the retired `/v2` paths that error is still the
legacy `400`, not a `404`.

Two consequences reach past the views routes themselves.

**The error surface grew a blast radius the old one did not have.** The bespoke views
error rendering was confined to the views controller. The replacement includes a second,
*global* advice scoped by URL prefix to `/v1/**` — and every OpenHouse tables and
databases route lives under `/v1/databases/...`. Unresolved paths, wrong methods and
wrong content types on those long-standing table routes are now rendered as Iceberg error
envelopes with Iceberg statuses, where before they were OpenHouse envelopes or bare
framework responses.

**No view can be stored.** `ViewsDisabledService` is still the only `ViewsService`
implementation contributed by the tables service, so every route except `GET /v1/config`
answers a `404`. The success paths described below are the contract the routes will serve;
today the only reachable success is the config document. The `409` conflict paths are not
reachable at all.

The differences below are read off the two implementations directly: the bespoke `/v2`
surface as it stood in the views carbon copy, and the Iceberg REST rework that replaced it.
Where the two disagree, the behaviour described is the one the code produces, not the one
either design note claims.

**References:** [Iceberg REST Catalog OpenAPI](https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml) ·
[Iceberg View Spec](https://iceberg.apache.org/view-spec/) ·
fork PRs [#43](https://github.com/mkuchenbecker/openhouse/pull/43) (the carbon copy, a
byte-faithful copy of upstream
[linkedin/openhouse#694](https://github.com/linkedin/openhouse/pull/694)) and
[#44](https://github.com/mkuchenbecker/openhouse/pull/44) (the rework).
"The spec" throughout means the first of these; route descriptions and status vocabulary are
quoted from it. Client-side behaviour is Iceberg `1.5.2.17`, the version this server bundles.

## 1. Route surface

The old surface mounted five routes under a new `/v2` prefix, chosen so that views broke
no existing client: `/v1/databases/{databaseId}/tables/{tableId}` stayed table-only. All
five are gone. The table below is the complete retired surface; the row that matters most
is `PUT`, because it is the only one whose *semantics* — not merely its spelling — have no
direct successor.

| Method & path | Request body | Success | Privilege |
|---|---|---|---|
| `GET /v2/databases/{databaseId}/views/{viewId}` | — | `200` `GetViewResponseBody` | `SELECT` |
| `GET /v2/databases/{databaseId}/views?page&size&sortBy` | — | `200` `GetAllViewsResponseBody` | `LIST_VIEW` |
| `POST /v2/databases/{databaseId}/views` | `CreateUpdateViewRequestBody` | `201` `GetViewResponseBody` | `CREATE_VIEW` |
| `PUT /v2/databases/{databaseId}/views/{viewId}` | `CreateUpdateViewRequestBody` | `200` on replace, `201` on create | `UPDATE_VIEW_METADATA` |
| `DELETE /v2/databases/{databaseId}/views/{viewId}` | — | `204`, no body | `DELETE_VIEW` |

`PUT` was a whole-definition upsert: it created the view when absent and replaced it when
present, which is why it declared both `200` and `201`. The list route paged with
`page` (default `0`), `size` (default `50`) and an optional single `sortBy` field.
Every route declared `400/401/403/404/503`; `POST` and `PUT` additionally declared `409`
and `422`.

The current surface serves seven routes. Six are the spec's view operations; the seventh,
`GET /v1/config`, is client bootstrap, has no predecessor, and is the row that matters most —
without it nothing else is reachable. Paths are served
un-prefixed — the config document returns no `prefix` override — and collide with nothing
in the tables API, whose routes live under `/v1/databases/...`.

| Method & path | Request body | Success | Privilege |
|---|---|---|---|
| `GET /v1/config` | — | `200` catalog config | authenticated only |
| `GET /v1/namespaces/{namespace}/views?pageToken&pageSize` | — | `200` `ListTablesResponse` | `LIST_VIEW` |
| `POST /v1/namespaces/{namespace}/views` | `CreateViewRequest` | `200` `LoadViewResult` | `CREATE_VIEW` |
| `GET /v1/namespaces/{namespace}/views/{view}` | — | `200` `LoadViewResult` | `SELECT` |
| `POST /v1/namespaces/{namespace}/views/{view}` | `CommitViewRequest` | `200` `LoadViewResult` | `UPDATE_VIEW_METADATA` |
| `DELETE /v1/namespaces/{namespace}/views/{view}` | — | `204`, no body | `DELETE_VIEW` |
| `HEAD /v1/namespaces/{namespace}/views/{view}` | — | `204`, no body | `SELECT` |

Four route-level differences change how a client is written.

**Create is `200`, not `201`, and replace is always `200`.** The spec's views surface uses
`200` for create and replace and `204` for delete and exists, and never uses `201`. A
client that keyed on the old `201`/`200` split to learn whether a `PUT` created or
replaced has no equivalent signal; on the new surface, create and commit are distinct
routes, so the question does not arise.

**Replace moved from `PUT` to `POST` on the item path.** It is no longer an upsert. A
`POST` to a view that does not exist is a `404`, not a create. Creation happens only
through `POST` on the collection path.

**`HEAD` is new.** Existence is a first-class operation in the new contract, returning `204`
when the view exists and `404` when it does not, with no body on either. The old surface had
no existence check; a client had to `GET` the view and catch the `404`. The Iceberg 1.5.2.17
client still does exactly that — its `viewExists` is a load-and-catch, not a `HEAD` — so the
route is served for spec conformance and for newer clients rather than because the bundled
client exercises it.

**`GET /v1/config` is new and is the load-bearing route.** Without it a stock `RESTCatalog`
cannot bootstrap, so nothing else is reachable. It is deliberately served even while views
are disabled: bootstrap has to precede the per-route `404`s. It is the only view-surface
route with no `@Secured` privilege — it requires an authenticated principal and nothing
more. Its body is `{"defaults": {}, "overrides": {}, "endpoints": [...]}`, where
`endpoints` lists the seven implemented routes in the spec's capability-advertisement
format (`"GET /v1/{prefix}/namespaces/{namespace}/views"`, and so on). Advertising them
explicitly is load-bearing: the spec defines a default endpoint set for servers that omit
the field, and that default set contains every table route and no view route — wrong in
both directions for this server.

Call for call, the mapping is:

| `/v2` call | `/v1` successor |
|---|---|
| `GET /v2/databases/{d}/views/{v}` | `GET /v1/namespaces/{d}/views/{v}` |
| `GET /v2/databases/{d}/views?page&size&sortBy` | `GET /v1/namespaces/{d}/views?pageToken&pageSize` (§2.4) |
| `POST /v2/databases/{d}/views` | `POST /v1/namespaces/{d}/views`, success `200` not `201` |
| `PUT /v2/databases/{d}/views/{v}` | split in two: `POST /v1/namespaces/{d}/views` to create, `POST /v1/namespaces/{d}/views/{v}` to commit. A `POST` to the item path of a view that does not exist is a `404` |
| `DELETE /v2/databases/{d}/views/{v}` | `DELETE /v1/namespaces/{d}/views/{v}` |
| — | `HEAD /v1/namespaces/{d}/views/{v}` |
| — | `GET /v1/config`, which must be called first |

Finally, what a `/v2` client gets today: `GET /v2/databases/d/views/v` no longer resolves,
and because the `/v1`-scoped advice does not claim it, it renders through the unchanged
legacy path — a `400` with the OpenHouse envelope and the message "The combination of the
method … cannot be resolved by server". It is not a `404`.

## 2. Request and response bodies

### 2.1 Create

The old create body repeated the path identity, carried the schema as an escaped JSON
string, and put the definition fields at the top level. The new one is Iceberg's
`CreateViewRequest`: the namespace comes from the path only, the schema is a structured
object, and the definition lives inside `view-version`. The table maps each old field to
where its content now travels; the rows that change a client's code most are `schema`
(string to object) and `sourceDialect` (top-level required field to an optional summary
entry).

| Old field (`CreateUpdateViewRequestBody`) | New home (`CreateViewRequest`) |
|---|---|
| `viewId` (required) | `name` (required) |
| `databaseId` (required, had to match the path) | path `{namespace}` only — no body field |
| `clusterId` (required, had to match the server) | no body field and, today, no wire home at all; the server is the cluster. `GET /v1/config` returns empty `overrides`, so a client that needs the value must carry it out of band (§6) |
| `schema` (required, escaped Iceberg schema JSON *string*) | `schema` (required, structured `Schema` object) |
| `representations[]` (required, top level) | `view-version.representations[]` (same `{type, sql, dialect}` shape, spec location) |
| `sourceDialect` (required) | `view-version.summary["openhouse.source-dialect"]`, optional with one representation |
| `defaultCatalog` | `view-version.default-catalog` |
| `defaultNamespace[]` | `view-version.default-namespace` |
| `viewProperties{}` | `properties{}` (required by the spec, may be empty) |
| `baseViewVersion` | not a create field; on commit it becomes an `assert-view-uuid` requirement |

The new body additionally requires `view-version` as a whole (with `version-id`,
`timestamp-ms`, `schema-id`, `summary` and `representations`) and accepts an optional
`location`. Field naming is kebab-case throughout, where the old body was camelCase.
Bodies are parsed by `CreateViewRequestParser`, not by Spring's Jackson binding, so
required-field enforcement and naming come from the Iceberg dependency rather than from
bean annotations.

### 2.2 Replace

The old replace body was the same `CreateUpdateViewRequestBody`, sent whole, with
optimistic concurrency expressed as a `baseViewVersion` string the server compared against
the current metadata pointer. It required that string on `PUT` and rejected it (unless it
was the `INITIAL_VERSION` token) on `POST`.

The new replace body is the spec's `CommitViewRequest` — carried by Iceberg 1.5.2.17's
`UpdateTableRequest`, which is the shared commit envelope for tables and views, since that
release has no separate `CommitViewRequest` class. It is `{identifier?, requirements[],
updates[]}`. Concretely, a client that used to send a full definition plus a
`baseViewVersion` must now send:

- an optional `identifier`, which — when present — must name exactly the namespace and
  view in the path, or the request is a `400`;
- `requirements[]`, restricted on this surface to `assert-view-uuid`. Any other
  requirement type is a `400` rather than being silently ignored, because a client that
  sent it is asserting something this server would not check;
- `updates[]`, restricted to the view-update set: `assign-uuid`,
  `upgrade-format-version`, `add-schema`, `set-location`, `set-properties`,
  `remove-properties`, `add-view-version`, `set-current-view-version`. A table-only update
  action is a `400`.

An `add-view-version` payload is held to the same representation rules a create's
`view-version` gets, and a `set-properties` payload to the same reserved-key rules.

### 2.3 Read and create responses

The old read contract was pointer-only and identical for `GET`, `POST` and `PUT`:
`{viewId, databaseId, clusterId, viewUri, metadataLocation, viewVersion, creationTime}`.
The SQL, schema, representations, version history, UUID, properties and resolution context
were deliberately omitted — they lived in the metadata file, and the API did not return
them.

The new contract is the spec's `LoadViewResult`: `{metadata-location, metadata, config?}`,
where `metadata` is the *complete* view metadata document — `view-uuid`, `format-version`,
`location`, `current-version-id`, `versions[]`, `version-log[]`, `schemas[]`,
`properties`. The definition is the payload. Of the old response's seven fields, only
`metadataLocation` has a direct counterpart (`metadata-location`); `viewUri` and
`creationTime` are not returned and must be derived — `creationTime` from
`version-log[].timestamp-ms`, `viewUri` from the identifier the client already holds.
`clusterId` is not returned at all. The optional `config` map is not populated by this
server.

### 2.4 List

The old list response wrapped a Spring `Page` under a `pageResults` key, so a client read
`pageResults.content[]` alongside `pageable`, `totalElements`, `size` and `number`. Its
elements were sparse `GetViewResponseBody` objects: identifiers populated, pointer fields
absent, `creationTime` serialized as `0` because it is a primitive.

The new list response is the spec's `ListTablesResponse`:

```json
{"identifiers": [{"namespace": ["my_database"], "name": "my_view"}]}
```

with an optional `next-page-token` alongside it. There is no page metadata, no total
count, and no sparse view objects — identifiers only. The `page`/`size`/`sortBy` query
parameters are replaced by `pageToken`/`pageSize`; sorting is not part of the contract.
The document is assembled field by field around Iceberg's own `TableIdentifier`
serializer, because 1.5.2.17's `ListTablesResponse` model predates the spec's pagination
fields. A complete listing omits `next-page-token` entirely, which is the spec's
termination signal.

## 3. Error handling

This is where the two surfaces differ most, and where the change reaches beyond views.

### 3.1 The envelope

The old surface used the OpenHouse-wide `ErrorResponseBody`, rendered by the shared
`OpenHouseExceptionHandler`:

```json
{"status": "NOT_FOUND", "error": "Not Found", "message": "Views are disabled",
 "stacktrace": "…", "cause": "…"}
```

`status` is the `HttpStatus` enum *name*, not the numeric code. The body carries an
abbreviated server stack trace and the cause chain. The internal `ViewErrorCode` was never
serialized — only the status it selected reached the wire — so a client could not
distinguish a view that was absent from a database that was absent from views being
disabled, beyond reading the free-text message.

The new surface uses the spec's `IcebergErrorResponse`, serialized by Iceberg's own
`ErrorResponseParser`:

```json
{"error": {"message": "Views are disabled", "type": "NoSuchViewException", "code": 404}}
```

Three differences follow. The status code is now numeric and inside the envelope. The
`type` field carries an exception name that a stock client maps back to a typed exception,
so the taxonomy that used to be invisible is now the primary machine-readable signal. And
the envelope's optional `stack` field is *never* populated — nothing on this surface
serializes a stack trace or a cause chain.

The `type` strings are derived from Iceberg's own exception classes at compile time
(`NoSuchViewException.class.getSimpleName()` and friends), so the wire vocabulary cannot
drift from what a client's `ErrorHandlers` understands.

### 3.2 The per-route 404 vocabulary, and why it exists

The spec does not use one `404` type for the whole views surface. On the collection routes
— list and create — a `404` means *the namespace does not exist*, and the spec's example is
`NoSuchNamespaceException`. On the item routes — load, replace, drop, exists — a `404`
means *the view does not exist*, and the example is `NoSuchViewException`. The routes'
own descriptions say as much: "Not Found - The namespace specified does not exist" for the
collection, "Not Found - NoSuchViewException, view to load does not exist" for the item.

The implementation encodes this with a `routeSensitive404` flag on two `ViewErrorCode`
values, `DATABASE_NOT_FOUND` and `VIEWS_DISABLED`. Both store the collection-route
rendering (`NoSuchNamespaceException`); the views advice swaps in `NoSuchViewException`
when the failing request targeted the item route. Which route was targeted is decided from
the dispatcher's matched-pattern attribute when one was recorded — the authoritative
answer — with a trailing-slash-tolerant URI match as the fallback, so URL normalization
cannot flip the vocabulary.

This split is the mechanism that makes the views-disabled posture invisible to a stock
client. Because `loadView` against a disabled server returns a plain
`NoSuchViewException`, Spark's view resolution concludes the view is simply not there and
falls through to `loadTable`, and table behaviour is unchanged. Because `listViews`
returns `NoSuchNamespaceException`, `SHOW VIEWS` answers with an empty list rather than an
error. Because `createView` returns the namespace `404`, `CREATE VIEW` surfaces as a Spark
`AnalysisException` rather than a raw runtime error. Had every route returned the same
type, at least one of those three behaviours would have been wrong. The end-to-end
`OpenHouseViewGateOnTestSpark3_5` pins all three against the real server, and pins that
the gate-on behaviour is indistinguishable from gate-off.

### 3.3 Status and shape, condition by condition

Ten conditions keep their status and change only their envelope; five change more than
that, and those five are what a client's error handling has to be rewritten around. Of the
fifteen, only the two `409` rows need a working backend — every other row is reachable
against the server as it stands today (§6).

The envelope-only swaps:

| Condition | Old status and shape | New status and shape |
|---|---|---|
| View not found | `404` OpenHouse envelope | `404` `NoSuchViewException` |
| Namespace not found (list, create) | `404` OpenHouse envelope | `404` `NoSuchNamespaceException` |
| Views disabled | `404` OpenHouse envelope, message "Views are disabled" | `404`, same message, type per route (§3.2) |
| Name already taken by a view or table | `409` OpenHouse envelope | `409` `AlreadyExistsException` |
| Commit requirement failed | `409` OpenHouse envelope | `409` `CommitFailedException` |
| Structural validation failure | `400` OpenHouse envelope, reasons joined with `"; "` | `400` `BadRequestException`, same joined reasons in `message` |
| Unsupported dialect / unusable schema | `400` OpenHouse envelope | `400` `BadRequestException` |
| Access denied | `403` OpenHouse envelope | `403` `ForbiddenException` |
| Authorization service unavailable | `503` OpenHouse envelope | `503` `ServiceUnavailableException` |
| Unexpected server fault | `500` OpenHouse envelope carrying `exception.toString()` and a stack | `500` `InternalServerError`, fixed message "Internal Server Error", no stack |

The five that changed further. The first two moved status; the last three keep their status
and gain a body the old surface did not send, which breaks any client that reads the
presence of a body as a signal:

| Condition | Old status and shape | New status and shape |
|---|---|---|
| Admission-control refusal | `422` OpenHouse envelope | **`400`** `ValidationException` |
| Unresolved path under `/v1` | `400` OpenHouse envelope | **`404`** `NotFoundException`, message "Route does not exist" |
| Malformed or missing request body | `400`, message beginning "Unacceptable JSON" | `400` `BadRequestException`, fixed message "Malformed CreateViewRequest…" / "Malformed CommitViewRequest…" |
| Unbindable query parameter (e.g. `pageSize=abc`) | framework default: bare `400`, no body | `400` `BadRequestException`, fixed message, offending value not echoed |
| Wrong method / content type on a `/v1` path | bare `405` / `415` with `Allow` / `Accept` | `405` `MethodNotAllowedException` / `415` `UnsupportedMediaTypeException` envelope, headers retained |

The `422` removal is a spec-conformance change: `422` appears nowhere in the spec's views
surface, so the four admission codes (`VIEW_ADMISSION_FAILED`,
`REQUIRED_REPRESENTATION_MISSING`, `DEPENDENCY_CYCLE`, `MAX_VIEW_DEPTH_EXCEEDED`) moved to
`400` with the distinct `ValidationException` type. The fixed messages for malformed
bodies and unbindable parameters are an OpenHouse choice, not a spec requirement: Iceberg's
parser messages and Spring's binding-failure messages can echo fragments of the submitted
document, and every error message on this surface is copied verbatim into service audit
events.

### 3.4 Which advice renders what, and the `/v1` blast radius

Three advices now share the error surface, in this precedence order.

`IcebergRestViewsExceptionHandler` is a `@RestControllerAdvice(assignableTypes =
IcebergRestViewsController.class)` at `HIGHEST_PRECEDENCE`. It renders everything a views
*controller method* raises: `ViewApiException` (status and type from the code, with the
per-route `404` swap), `AccessDeniedException` as `403`, `AuthorizationServiceException` as
`503`, `TypeMismatchException` as `400`, and anything else as `500`. Because it is scoped
by controller type, no other controller's error contract is touched by it.

`V1RestUnresolvedPathExceptionHandler` is a *global* `@ControllerAdvice` at
`HIGHEST_PRECEDENCE + 1`. It exists because `NoHandlerFoundException`,
`HttpRequestMethodNotSupportedException` and `HttpMediaTypeNotSupportedException` are
raised *before* any controller method is selected, so the views-scoped advice can never see
them. It decides purely on the request URI: a path starting with `/v1/` gets the Iceberg
envelope; anything else falls back to the legacy rendering — for unresolved paths, the
byte-identical legacy `400` body, obtained by calling the now-public
`OpenHouseExceptionHandler.unresolvedRouteErrorResponseBody`; for `405`/`415`, the
framework defaults of a bare status plus `Allow`/`Accept`.

`OpenHouseExceptionHandler` still renders everything else, including every
tables-controller failure. Its only change is mechanical: the legacy unresolved-route body
was extracted into a public static method so the `/v1` advice can fall back to the exact
same template rather than duplicating it.

**The blast radius.** The `/v1` prefix test is a plain `uri.startsWith("/v1/")`, and the
OpenHouse tables and databases routes all live under `/v1/databases/...`. So for the
*tables* API, which the carbon copy left entirely alone, three request-mapping failures now
render differently. The first row is the one that will break a client: it is the only status
change.

| Request | Before | Now |
|---|---|---|
| A mistyped or retired path under `/v1/databases/...` | `400`, OpenHouse envelope naming the method and path | `404`, `{"error":{"message":"Route does not exist","type":"NotFoundException","code":404}}` |
| A wrong method on a known tables route | bare `405`, `Allow` set | `405`, Iceberg envelope, `Allow` still set |
| A wrong content type on a known tables route | bare `415`, `Accept` set | `415`, Iceberg envelope, `Accept` still set |

Only paths *outside* `/v1/` keep the old behaviour byte for byte — which is why a probe of
the retired `/v2` views routes still renders the legacy `400`. The message hygiene rule
applies here too: the requested URL is attacker-chosen text and is never echoed into the
envelope or into audit events; the method and path are logged server-side instead.

Both new advices implement the `AuditedResponseRenderer` marker so that `ServiceAuditAspect`
still emits a service audit event for the failures they render. Without it, moving error
rendering out of the shared handler would have silently stopped auditing those failures.

### 3.5 HEAD body suppression

The spec's exists route "does not return a response body" on any status. The views advice
therefore checks the request method and, for `HEAD`, returns the status alone with no
envelope — a disabled `HEAD` is `404` with a zero-length body, not a `404` carrying JSON.
The same suppression is applied by the `/v1` advice to an unresolved `HEAD` probe and to a
`HEAD` that hits a method-not-allowed. The success path is bodyless by construction: the
controller builds `HEAD` and `DELETE` responses through a `ResponseEntity<Void>`.

One detail affects auditing rather than the wire: when a view does not exist, `viewExists` *throws* rather than returning a `404`
directly, so the absent-view case travels the same exception path as every other failure
and produces the same failure-path audit event. The client cannot tell the difference,
since the envelope is suppressed for `HEAD` either way.

### 3.6 The 401, which deviates from the spec

Authentication is rejected by the token interceptor before dispatch, on both the old and
the new surface, and both send a bare `401` with no body. Neither advice sees the request,
so neither can render an envelope.

This deviates from the spec. `UnauthorizedResponse` documents a `401` carrying an
`IcebergErrorResponse` body — the example is
`{"error":{"message":"Not authorized to make this request","type":"NotAuthorizedException","code":401}}`
— and every view route references it. The spec annotates that example as representative
rather than prescriptive about the `message` and `type` values, but it does describe a JSON
body, and this server sends none. In practice an Iceberg client treats a bodyless `401` as
an authentication failure regardless, so the deviation is tolerable; it is nonetheless a
deviation, and the one place on the views surface where the response shape is not the
spec's.

## 4. Where the wire contract is decided

Every status and envelope in §3 is produced in one place. `OpenHouseViewsApiHandler` is the
only code that converts a service outcome into the vocabulary the Spring advice renders: the
service reports absence as a return value and contention as a checked exception, and the
handler turns both into the unchecked `ViewApiException` the advice knows how to write. A
service or repository that threw that exception directly would bypass the seam and could
render a status the tables in §3 do not list.

That matters to a client only as a guarantee — the status set is closed and enumerable, and
§3.3 is complete rather than indicative. Appendix A has the interface itself, which no
client observes.

## 5. Behaviour that changed without a signature changing

Validation runs before the service is called, so the `400`s in this section are reachable
against the server as it stands. Nothing behind them is: a request that clears validation
still ends at the views-disabled `404` (§6).

### 5.1 Pagination

The old contract was Spring pagination with a server-chosen default: `page=0`, `size=50`.
A client that sent no parameters got at most fifty views and had to page for the rest.

The new contract inverts that default. The spec requires that a request carrying no
`pageToken` be answered with **all** results in a single page, with no continuation token.
The obligation is concrete: the Iceberg 1.5.2.17 client's `listViews` issues one `GET` and
follows no `next-page-token`, so a server that paginated an un-tokened request would
silently truncate that client's listing. The obligation is carried in the type system —
`ViewPageRequest.isUnpaged()` keys on the *token* alone, so a caller who sends only a
`pageSize` is still making a first request that must be answered completely — and it is
stated as a contract on `ViewsService.listViews`. `pageToken` is opaque and never
shape-validated; `pageSize` must be at least `1` if present. A complete listing omits
`next-page-token`; the spec permits either an explicit `null` or an omitted field as the
termination signal.

### 5.2 Dialect validation

Three changes a client can observe.

**Dialect comparison became case-insensitive.** The old validator compared the raw
representation dialect against a lowercase set (`supportedDialects.contains(dialect)`), so
a representation declaring `SPARK` was a `400`. The new validator lowercases before the
membership test, so `SPARK` clears validation. Duplicate detection was already case-insensitive
in both, and still is: two representations claiming `SPARK` and `spark` are rejected as
duplicates.

**The source dialect became optional in the single-representation case.** The old
`sourceDialect` was a required top-level field on every create and replace. It now lives in
`view-version.summary` under the key `openhouse.source-dialect`, and is required only when
a request supplies more than one representation. With a single representation the
unique-dialect rule makes the server-side default well defined, so a stock client's create
needs no OpenHouse-specific field to clear validation. When present it must still name a
supported dialect and a supplied representation.

**Non-SQL representations are still rejected, and the branch is still reachable.** Iceberg
1.5.2.17 parses a representation with an unrecognized `type` into an
`UnknownViewRepresentation` rather than failing, so such a request reaches the validator and
is rejected with `representations[i].type : must be 'sql'`, exactly as the old string
comparison did.

Two smaller differences change what the validator accepts, and when it rejects. The old
validator size-capped the
schema *before* parsing it, so an oversized document was never fed to the parser; the new
one receives an already-parsed schema and measures the canonical re-serialized form, so an
oversized schema is parsed before it is rejected. And the old validator rejected an
explicitly empty `defaultNamespace` and rejected null values in `viewProperties`; the new
one accepts an empty resolution namespace, which the spec permits as "unset", and has no
null-value rule because the parser produces a string-to-string map. Identifier rules
(alphanumeric plus underscore, 128 characters), the SQL and schema byte ceilings (256 KiB
and 512 KiB, measured in UTF-8), and the reserved `openhouse.`-prefix and `policies`
property keys are unchanged, as is the accumulate-everything-then-throw behaviour that
reports all violations joined with `"; "`.

### 5.3 Multi-level namespaces

The old API had no namespace concept: `databaseId` was a path segment with identifier
rules. The spec encodes a multi-level namespace as one path segment whose parts are
separated by the unit separator `0x1F` (`%1F` url-encoded).

OpenHouse namespaces are single-level, so a segment containing that separator names a
namespace this catalog can never contain. The handler reports it as the spec's `404` for an
absent namespace rather than as a validation `400`, which means it is subject to the
per-route vocabulary: `NoSuchNamespaceException` on list and create,
`NoSuchViewException` on the item routes. A client asking for something that cannot exist
is told it does not exist, which is both true and the answer a stock client knows how to
handle.

### 5.4 The views-disabled posture, and what a client concludes

Both surfaces ship with view business logic stubbed out, and in both the stub answers
`404` with the message "Views are disabled". What a client *concludes* from that `404` is
completely different.

Against the old surface, the `404` arrived as an OpenHouse `ErrorResponseBody` on a
non-standard path. No Iceberg client could reach the route at all, so the posture was
observable only to a hand-written OpenHouse client, which had to interpret the free-text
message.

Against the new surface, the `404` arrives in the spec envelope with the spec's per-route
type, so a stock `RESTCatalog` reads it as "this catalog serves no such view" and behaves
accordingly — table reads unaffected, `SHOW VIEWS` empty, `CREATE VIEW` a clean analysis
error, `viewExists` and `dropView` returning `false`. The client cannot distinguish
"views are switched off here" from "that view does not exist", and that
indistinguishability is the design: it is what lets the surface ship enabled while the
backend is stubbed, with zero client-side special-casing.

The bean wiring reinforces it. `ViewsServiceConfiguration` contributes
`ViewsDisabledService` as a `@ConditionalOnMissingBean` fallback rather than as one half of
a pair of mutually exclusive property conditions, so a deployment that satisfies neither
condition degrades to the safe posture instead of failing context startup. There is no
coherent behaviour for a catalog that claims view support and cannot store a view; there is
one for a catalog that has no views.

## 6. Where the current surface is not conformant

No view can be stored, so most of this document describes a contract rather than observable
behaviour. `ViewsDisabledService` is the only `ViewsService` the tables service contributes:
every route except `GET /v1/config` answers `404` "Views are disabled", and no view can be
created, loaded, listed, replaced or dropped. Everything §1, §2, §3.3 and §5 describe about
success responses is the contract the routes will serve, not behaviour that can be exercised
today, and the `409` paths — `AlreadyExistsException` and `CommitFailedException` — are
unreachable because only a real service can raise the checked conflicts that produce them.

Eight further gaps are known and bounded. The `401` body and the multi-level-namespace
handling are the two where this server's behaviour contradicts the spec; the rest are
unimplemented options or vocabulary the spec never fixed.

| Gap | What this server does | What the spec expects |
|---|---|---|
| `401` body (§3.6) | bare `401`, no body | an `IcebergErrorResponse` body |
| Multi-level namespaces (§5.3) | reports the namespace as absent | servers decode the `0x1F` separator; OpenHouse cannot represent such a namespace |
| `419` | not implemented | every view route references `AuthenticationTimeoutResponse` |
| Commit-state-unknown | an unexpected failure on replace renders `500` `InternalServerError`, so a client cannot distinguish "the commit definitely did not happen" from "the outcome is unknown" | `500`, `502` and `504` carrying `type: CommitStateUnknownException`. Matters only once commits can be applied |
| `405` / `415` type strings | `MethodNotAllowedException`, `UnsupportedMediaTypeException` — self-describing names chosen here | no examples for those statuses on any route |
| Unclaimed protocol surface | `rename-view`, `register-view`, the tables and namespaces REST routes and the OAuth token endpoint land on the `/v1` unresolved-path `404`; `GET /v1/config` advertises exactly the seven routes served | out of scope, and the `404` is the correct signal — the server never advertises surface it does not serve |
| Optional protocol features | `LoadViewResult.config` never populated; `GET /v1/config` returns empty `defaults` and `overrides` and ignores the `warehouse` parameter; no `prefix` served; `ETag`, `Idempotency-Key` and `referenced-by` unsupported | all optional |
| `clusterId` | no wire home at present | no spec field; the natural homes are a `GET /v1/config` override or an `openhouse.`-prefixed view property |

One documentation item is outstanding but is not a conformance gap: `docs/specs/catalog.md`
has not been regenerated. It requires a booted service plus the external `widdershins` tool,
and it predates the views surface entirely — the retired `/v2` routes were never folded into
it either.

## Appendix A. The service-layer contract

No client observes this interface. It is here because it constrains what the routes above
can report: the service reports absence as a value and contention as a checked exception,
and something has to convert both into the unchecked exceptions the Spring advice renders.

The interface
changed in three ways beyond the obvious retyping from OpenHouse DTOs to Iceberg
catalog-domain types (`ViewMetadata`, `MetadataUpdate`, `UpdateRequirement`,
`TableIdentifier`, `Schema`, `ViewVersion`).

**Absence became a value.** The carbon copy's `getView` returned a `ViewDto` and signalled
a missing view by throwing an unchecked `ViewApiException(NO_SUCH_VIEW)`; `deleteView`
returned `void`. Now `loadView` returns `Optional<ViewMetadata>`, `dropView` returns
`boolean`, `viewExists` returns `boolean`, and `replaceView` returns
`Optional<ViewMetadata>`. A view that is not there is half of what these methods are for,
so it is no longer signalled by unwinding the stack.

**Contention became checked.** `ViewNameConflictException` (carrying a `Kind` of `VIEW` or
`TABLE`) and `ViewCommitConflictException` are checked exceptions. Two clients racing to
create the same view, or a commit losing a compare-and-swap, are ordinary outcomes of those
paths, so the compiler now makes every caller state what it does about them.

**Nullable parameters were replaced by types that model absence.** The old
`getAllViews(databaseId, page, size, sortBy, principal)` and
`putView(requestBody, principal, failOnExist)` gave way to `listViews(databaseId,
ViewPageRequest, principal)` and `createView(ViewCreationRequest, principal)`.
`ViewPageRequest` exists because a pair of nulls cannot distinguish "no paging was
requested" — which carries a spec obligation, §5.1 — from "the first page of some size".
`ViewCreationRequest` removes the create path's one nullable parameter (the caller-requested
`location`) and stops the signature from growing an argument each time
`CreateViewRequest` gains a field.

**The boundary is deliberate.** The Spring advice that writes responses renders *unchecked*
`ViewApiException`s and cannot be changed from the service layer. So `OpenHouseViewsApiHandler`
is the adapter: it converts `Optional.empty()` and `false` into
`ViewApiException(NO_SUCH_VIEW)`, and catches the two checked conflicts and rethrows them
as their `ViewApiException` equivalents. That conversion belongs in exactly one place — a
service or repository that throws `ViewApiException` directly has skipped the seam. The
handler is also where the wire envelopes are unwrapped, so the service seam stays reusable
by a future non-REST caller and wire-shape churn stays out of the service contract.

`ViewsDisabledService` is the one implementation, and it is the single place where throwing
rather than answering "absent" is correct: an empty `Optional` would claim "this catalog
serves views and has none", which is a different and false statement.
