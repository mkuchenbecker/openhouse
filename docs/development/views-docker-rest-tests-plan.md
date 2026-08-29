# Cover the views REST surface with a docker black-box suite, not another in-process test

The Iceberg REST views surface should get its own black-box HTTP suite —
`scripts/python/views_rest_integration_test.py`, run by the existing CI job against the
`oh-only` docker-compose recipe on host port 8000 — rather than more MockMvc cases or a
`@SpringBootTest(webEnvironment = RANDOM_PORT)` suite inside `services/tables`.

Landing it also changes `.github/workflows/build-run-tests.yml` for the **existing** tables
integration test, not only for the new one: the fixed `sleep 30` becomes a readiness probe,
teardown becomes unconditional, and a log dump is added on failure (§8). That is a second,
smaller proposal in this document, and it collides with a change the HTS port branch makes
to the same three steps — see §8.1 before implementing either.

The deciding argument is that the surface's hardest guarantees are properties of the
*deployment*, not of the controller. Which advice renders an unresolved `/v1` path, whether
`HEAD` really carries no bytes, whether the interceptor selected by the recipe's
`cluster.yaml` runs ahead of dispatch, whether the views routes are registered at all in the
shipped jar — none of that is decided by code an in-process test can instantiate. It is
decided by Tomcat, by `DispatcherServlet` wiring read from `application.properties`, and by
the config file bind-mounted into the container. A test that boots the same beans by hand
cannot fail on any of them.

**Status:** proposed; no suite exists yet. The views surface has **zero** black-box
coverage today — see §3.
**Branch:** `claude/iceberg-rest-spec-compliance-l0s2ju`.
**Companion:** [views-iceberg-rest-compliance.md](views-iceberg-rest-compliance.md) defines
the surface this suite pins (`/v1/config`, the six view routes, the `IcebergErrorResponse`
envelope, the per-route 404 vocabulary). Read it first;
[views-client-plugin-plan.md](views-client-plugin-plan.md) covers the client half.

## 1. Problem

The tables service now serves seven Iceberg REST routes, and nothing has ever sent one of
them a real HTTP request through a real server.

`scripts/python/integration_test.py` — the only thing the CI job runs against the container —
touches `/v1/databases/{db}/tables/...` and nothing else. It never issues a request to
`/v1/config`, `/v1/namespaces/...` or any `/views` path. The same is true of
`scripts/python/hts_integration_test.py` on `origin/claude/port-698-hts-e2e-mysql`, which
speaks only to House Tables on 8001. The one end-to-end views test,
`OpenHouseViewGateOnTestSpark3_5`, runs Spark against an embedded fixture inside the test
JVM: no container, no Tomcat connector, no `/var/config/cluster.yaml`, no interceptor chosen
by a recipe.

So the surface's deployment-level claims are all currently believed rather than observed:

- The views routes are registered unconditionally — no `@ConditionalOn*` annotation appears
  on the controller, handler, validator, service or either advice — but nothing checks that
  they answer in the built image.
- `V1RestUnresolvedPathExceptionHandler` beats the global `OpenHouseExceptionHandler` by
  `@Order`, and depends on `spring.mvc.throw-exception-if-no-handler-found=true` and
  `spring.mvc.static-path-pattern=favicon.ico` in `services/tables/src/main/resources/application.properties`.
  Advice ordering and dispatcher configuration are exactly what a MockMvc slice may quietly
  reconstruct differently.
- `IcebergRestViewsExceptionHandler` suppresses the envelope for `HEAD`, and Tomcat strips
  `HEAD` bodies independently. Two mechanisms, one observable outcome; only a socket can
  tell you the outcome holds.
- `OpenHouseViewsApiValidator` reads `cluster.tables.views.supported-dialects`, whose value
  in the container comes from the recipe directory bind-mounted at `/var/config/`
  (`ClusterProperties` loads `file:${OPENHOUSE_CLUSTER_CONFIG_PATH:/var/config/cluster.yaml}`).
  `infra/recipes/docker-compose/oh-only/cluster.yaml` does not set the key, so the deployed
  value is the `@Value` default `spark` — untested anywhere.
- The `/v1` request-mapping advice is scoped by path prefix, not by controller, so it now
  also owns 405, 415 and unresolved-path rendering for the **tables** routes under
  `/v1/databases/...`. That blast radius has no test at all.

## 2. Requirements

**Must**

1. Every assertion is made on the bytes a client sees — status line, headers, body — over a
   real socket to a container, with nothing between the client and Tomcat. The `oh-only`
   recipe publishes ports directly (`8000:8080`, `8001:8080`) with no proxy, so statuses,
   bodies and headers arrive unmodified.
2. The whole deployed surface is covered: the `/v1/config` document, the per-route 404 type
   split across all six view operations, the validation 400s that are reachable while the
   backend is stubbed, 405, 415, the unresolved-`/v1` 404, `HEAD` body emptiness, and 401.
3. Every assertion on an **Iceberg envelope** pins presence and absence: the exact `message`,
   `type` and `code`, plus the absence of `stack`, of `stacktrace`, of the full requested URL,
   and of the submitted document (no SQL, no schema). Identifiers are exempt — the validators
   echo the offending namespace or view name back by design
   (`ApiValidatorUtil.validateIdentifier`), so the absence rule covers payloads, not names.
   The rule does not extend to the legacy OpenHouse envelope in §6.3, which carries a
   `stacktrace` field that `OpenHouseExceptionHandler` populates.
4. Re-runnable against a warm container with no manual reset, and failure-safe: a case that
   fails leaves nothing behind that makes the next run fail differently.
5. Runs on every pull request inside the existing CI job, and the containers come down even
   when it fails.
6. No dependency beyond what `scripts/python/requirements.txt` already pins (`requests`).

**Should**

7. Pin the whole non-views blast radius, since the `/v1` advice is scoped by path prefix: the
   Iceberg 404 envelope for an unresolved path under `/v1/databases/...`, the Iceberg 405 and
   415 envelopes on the tables routes that previously answered with the bare
   `ResponseEntityExceptionHandler` shape, and the legacy OpenHouse-envelope 400 for an
   unresolved path off `/v1` — the last as proof the split is by prefix and stops there.
8. Prove readiness with a real probe instead of a fixed sleep.
9. Each case names the deployment property, advice, or dispatcher behavior it is the only
   test of, so a later reader can tell which cases are load-bearing.
10. Where container behavior is genuinely unknown (§6.5), the case records what the container
    does rather than assuming what MockMvc did.

**Won't, this milestone**

11. Any 2xx assertion for the six view operations. `ViewsDisabledService` answers all six
    with `404 "Views are disabled"`; a create/load round trip lands with persistence, and
    asserting one now would only assert the stub.
12. 403, 409 and `CommitFailedException` coverage. `AuthorizationInterceptor.check()` denies on
    two branches — a missing credential and an unauthenticated principal — but neither is
    reachable over HTTP here, because `DummyTokenInterceptor` answers 401 before dispatch for
    exactly the requests that would leave the context unauthenticated. So a `@Secured` method
    is only ever entered with an authenticated principal, and a 403 is not producible against
    this deployment. (The reachability is a property of the token interceptor, not of the
    authorization advisor: a recipe that swapped in Keycloak would need this revisited.) 409
    needs a store.
13. Pagination and `next-page-token`. The list route never returns an identifier, so paging
    has no observable behavior to pin.
14. Driving the container with a stock `RESTCatalog`. That is the client plugin's contract
    and is covered by its own tests.

**Out of scope**

15. A new compose recipe, a new CI job, and Testcontainers. `oh-only` is already built and
    started by the existing job; adding a second orchestration path to cover routes the
    running container already serves buys nothing.
16. TLS, and the `oauth` (Keycloak) container. `oh-only/cluster.yaml` selects
    `DummyTokenInterceptor`; the `oauth` service is referenced by no code in the repository
    and exists for manual experimentation.
17. Distinguishing "namespace absent" from "views disabled". There is no POST for databases —
    a database is materialized by creating a table — and while the service is stubbed no view
    route reaches a database lookup, so both render as the same 404. That is the designed
    views-disabled posture (a stock client must see the surface as absent so Spark's
    `ResolveViews` falls through to `loadTable`), not a coverage gap, and §9 says so in the
    suite's own words.

## 3. Present state, and how to check it

Two commands establish everything §1 claims about coverage:

```bash
grep -rn "namespaces\|/views\|/v1/config" scripts/python/          # no match today
git grep -n "ConditionalOn" -- services/tables/src/main/java/com/linkedin/openhouse/tables/controller \
    services/tables/src/main/java/com/linkedin/openhouse/tables/api/icebergrest                # no match
```

The rest of the present state, with where to read it:

| Fact | Where |
|---|---|
| Tables on host `8000`→8080, House Tables on `8001`→8080; build context is the repo root | `infra/recipes/docker-compose/common/oh-services.yml` |
| Config arrives by bind-mounting the recipe dir to `/var/config/` | `infra/recipes/docker-compose/oh-only/docker-compose.yml`; `cluster/configs/.../ClusterProperties.java` |
| The image copies `build/tables/libs/*.jar`, so `./gradlew build` must precede `up --build` | `tables-service.Dockerfile:22` |
| `DummyTokenInterceptor` on `/**` except `/actuator/**`, api-docs, swagger-ui, favicon, `/error` | `oh-only/cluster.yaml`; `services/tables/.../config/TablesMvcConfigurer.java:37-48` |
| A checked-in fixture token, 271 bytes, **no trailing newline** | `tables-test-fixtures/tables-test-fixtures-iceberg-1.2/src/main/resources/dummy.token` |
| Authorization can deny, but not reachably: `check()` returns `AuthorizationDecision(false)` on a missing credential and on an unauthenticated principal, and the token interceptor 401s both before dispatch | `services/tables/.../authorization/AuthorizationInterceptor.java:44-49`; `services/common/.../security/DummyTokenInterceptor.java:50-53` |
| `/actuator/health` is exposed and token-exempt; the k8s recipes already probe it | `services/tables/src/main/resources/application.properties`; `infra/recipes/k8s/templates/tables/tables-deployment.yaml:50` |
| CI: `clean build` → `up -d --build` → **`sleep 30`** → `integration_test.py` → `down` with **no `if: always()`** | `.github/workflows/build-run-tests.yml:24-44` |

The row that matters most is the last one: the current job sleeps for a fixed 30 seconds and
tears down only on success, so a slow start is a flaky failure and a failed run leaks
containers. §8 replaces both.

## 4. Options

The columns are the requirements the four candidates actually differ on. Rows are the
options; **B is the recommendation**.

| Option | Real Tomcat & real `/var/config` (must 1) | Runs in the existing CI job (must 5) | Pins wire bytes incl. headers (must 1, 3) | Re-runnable & failure-safe (must 4) | New infra (out of scope 15) |
|---|---|---|---|---|---|
| A. Add views cases to `scripts/python/integration_test.py` | yes | yes | yes | no — see below | none |
| **B. New `scripts/python/views_rest_integration_test.py` against `oh-only`** | **yes** | **yes** | **yes** | **yes — per-run identifiers from the start** | **none** |
| C. `@SpringBootTest(RANDOM_PORT)` suite in `services/tables` | Tomcat yes, `/var/config` no | yes (`./gradlew build`) | yes | yes | none |
| D. Container-driven Java itest (Testcontainers) | yes | no — needs a new module and job | yes | yes | a second orchestration path |

Two criteria decide it, and each eliminates two options.

The first column eliminates C, the closest competitor, on exactly the thing this surface most
needs proved: it boots from the test classpath, so the deployed `cluster.yaml`, the deployed
jar layout and the recipe's choice of interceptor are all substituted by test fixtures — the
class of mistake the suite exists to catch. The same column, read with the fifth, eliminates
D: it would satisfy must 1, but pays for it with a build module and a CI job, to cover routes
the container the CI job already starts is already serving.

Must 4 then separates A from B, which are otherwise identical. `integration_test.py` is
idempotent *after a green run* — it deletes `d3.t1` and re-asserts its absence — but it is not
failure-safe: a run that fails between the create and the delete leaves `d3.t1` behind, and
the next run fails at its opening not-found assertion instead of at the real defect. It also
addresses one hardcoded identifier, so two runs cannot overlap. Adding views cases to it
extends that shape to a larger surface rather than confining it.

## 5. Shape of the suite

One new file, `scripts/python/views_rest_integration_test.py`, modeled on
`hts_integration_test.py` rather than on `integration_test.py`. That model is **not on this
branch** — it arrives with `origin/claude/port-698-hts-e2e-mysql`
(`scripts/python/hts_integration_test.py`), so read it there, and note that this suite does
not depend on that branch landing: the pieces below are described in full and can be written
from this document alone.

```
python scripts/python/views_rest_integration_test.py <token_file> [base_url]
#   token_file  required, same argument shape as integration_test.py
#   base_url    optional, defaults to http://localhost:8000
```

The pieces it copies, and why each is load-bearing here:

- `RUN_ID = f'{int(time.time())}_{uuid.uuid4().hex[:8]}'` and `namespace(suffix)` /
  `view_name(suffix)` helpers, so no two runs address the same identifier (§7).
- `describe(response)` — `"{method} {url} -> {status} {text}"` — embedded in every assertion
  message, so a CI failure is diagnosable from the log alone.
- Assertion helpers that pin presence and absence together:
  `assert_status`, `assert_iceberg_error(response, status, type_, message)` (exact `message`,
  `type` and `code`, `error` key set exactly `{message, type, code}`, and **no** `stack`),
  `assert_absent(response, forbidden_fragments)` for URL and payload redaction, and
  `assert_no_body(response)` asserting `response.content == b''`.
- An explicit `TESTS = [...]` list, a runner that counts passed and skipped, a `SkippedTest`
  exception for the discovery cases in §6.5, and `AssertionError` left to abort with a
  traceback.
- A single `read_token(path)` that strips whitespace. `integration_test.py` reads the file
  raw and gets away with it only because `dummy.token` happens to have no trailing newline;
  the stripping version does not depend on that.

The token is passed by path, not value, so the suite reuses the fixture the CI job already
passes to `integration_test.py`.

## 6. Cases

### 6.0 Which cases are load-bearing, and how the rest are written

Most of what follows is already pinned by `IcebergRestViewsControllerTest` at the unit level —
the config document, the six 404s and their type split, the 400 messages, the 405 and 415
envelopes, the accumulation contract. Requirement 9 asks each case to name the deployment
fact it is the only test of, and for most rows the honest answer is "none". Appendix A rejects
option C partly for duplicating that suite; this document has to hold itself to the same
standard.

Genuinely deployment-only, by inspection, are eight things: the `supported-dialects` default
read from the mounted recipe, `HEAD` on the collection route, `HEAD /v1/config` body
stripping, the real `Allow` and `Accept` header bytes, the bare 401 over a socket, the
absent-`Content-Type` POST, the unresolved-`/v1` 404 reached without credentials, and route
registration in the shipped jar.

So the rule for writing the suite is:

- **Load-bearing rows pin everything** — status, exact `message`, `type`, `code`, headers,
  body emptiness. They are marked as such in the tables below ("the row that matters most").
- **Every other row asserts status and `type` only.** Do not re-pin exact message strings that
  the Java suite already owns. Otherwise rewording one constant in `IcebergRestWire` fails a
  Java test at `./gradlew build` and a Python test three CI steps later, in a different
  language, with a worse message — and the second failure teaches nothing the first did not.

The message text below is given so the implementer can recognize a correct response, not
because every row should assert it.

### 6.1 Bootstrap and the per-route 404 split

These seven cases are the surface's core contract. The row that matters most is
`POST .../views` with a **valid** body: it is the only case that proves the parse → validate →
service ordering in the deployed handler, because a valid document reaching the stub is the
only way a 404 rather than a 400 can come back.

That makes the create body load-bearing, and it is easy to get wrong. Iceberg's
`ViewVersionParser` requires `version-id`, `schema-id`, `timestamp-ms`, `summary`,
`representations` and `default-namespace`, and throws on any one of them missing; a body
carrying only `name`, `schema`, a `sql` representation and `properties` never reaches the
service and answers `400 "Malformed CreateViewRequest: ..."` — the opposite side of the
ordering this row exists to prove. Do not hand-write the literal. Generate it once from
`IcebergRestViewFixtures.createViewRequestJson()`
(`services/tables/src/test/java/com/linkedin/openhouse/tables/model/IcebergRestViewFixtures.java`),
which builds the request through the same parser the server uses, and paste the output into
the suite as a constant. The same applies to the commit body below
(`IcebergRestViewFixtures.commitViewRequest()`).

| Request | Expected |
|---|---|
| `GET /v1/config` | `200`, `Content-Type: application/json`, body keys exactly `{defaults, overrides, endpoints}`; `defaults == {}`; `overrides == {}` and specifically no `prefix` key; `endpoints` equal, in order, to `GET /v1/config`, then `GET`/`POST` on `/v1/{prefix}/namespaces/{namespace}/views`, then `GET`/`POST`/`DELETE`/`HEAD` on `/v1/{prefix}/namespaces/{namespace}/views/{view}` |
| `GET /v1/namespaces/{ns}/views` | `404`, `type` `NoSuchNamespaceException`, `message` `Views are disabled`, `code` `404`, no `stack` |
| `POST /v1/namespaces/{ns}/views` + a valid `CreateViewRequest` | `404`, `NoSuchNamespaceException`, `Views are disabled` |
| `GET /v1/namespaces/{ns}/views/{view}` | `404`, `type` **`NoSuchViewException`**, `Views are disabled` |
| `POST /v1/namespaces/{ns}/views/{view}` + a valid commit body | `404`, `NoSuchViewException` |
| `DELETE /v1/namespaces/{ns}/views/{view}` | `404`, `NoSuchViewException` |
| `HEAD /v1/namespaces/{ns}/views/{view}` | `404`, `response.content == b''` — status only, per the spec's bodyless exists route |

The valid create body is the generated `IcebergRestViewFixtures.createViewRequestJson()`
output pasted as a constant, as above. It must carry dialect `spark`, since that is the
deployed default (§6.2, last row) — the fixture's `SOURCE_DIALECT` already is `spark`, so the
generated literal needs no edit.

### 6.2 Validation 400s

These are reachable despite the stubbed backend because `OpenHouseViewsApiHandler` parses and
validates before it calls the service. The row that matters most is the last: it is the only
assertion anywhere that the container's `supported-dialects` value comes from the deployed
configuration rather than from a test property source.

| Request | Expected |
|---|---|
| `POST .../views`, body `{}` | `400` `BadRequestException`, `message` exactly the fixed `Malformed CreateViewRequest: the request body must be a JSON document with the required fields name, schema, view-version and properties, per the Iceberg REST catalog spec` |
| `POST .../views`, body `not json` (with `Content-Type: application/json`) | same `400` and the same fixed message; and the submitted text does not appear in the body |
| `POST .../views`, no body at all | same `400`, same fixed message |
| `POST .../views/{view}`, body `{}` | `400` `BadRequestException`, `message` exactly `Malformed CommitViewRequest: the request body must be a JSON document carrying requirements and updates, per the Iceberg REST catalog spec` |
| `GET /v1/namespaces/bad-name/views` | `400` `BadRequestException`, `message` `namespace : provided bad-name, Only alphanumerics and underscore supported` |
| `GET /v1/namespaces/bad-ns/views/bad-view` | `400`, `message` names **both** failures joined by `"; "` — the accumulation contract, observable only when two identifiers are bad at once |
| `GET .../views?pageSize=0` | `400` `BadRequestException`, `message` `pageSize : provided 0, must be greater than 0` |
| `GET .../views?pageSize=abc` | `400` `BadRequestException`, `message` `Malformed request parameter: a query or path parameter does not have its declared type` — the binding-failure path, which never reaches the handler |
| `POST .../views` + a valid body whose single representation has `dialect: "trino"` | `400` `BadRequestException`, `message` exactly `view-version.representations[0].dialect : must be one of the supported dialects: spark`. Note the message names the field path and the **configured** set, never the offending value — an assertion looking for `trino` fails |

### 6.3 Request-mapping failures

Everything here is rendered before a controller method is selected, by the `/v1`-scoped
advice rather than the views-scoped one. The row that matters most is the last: it is the
only check that the change did **not** reach the non-`/v1` surface.

| Request | Expected |
|---|---|
| `PUT /v1/namespaces/{ns}/views/{view}` | `405` `MethodNotAllowedException`, `message` `The route exists but does not support this method`, and an `Allow` header naming at least GET, POST, DELETE, HEAD |
| `DELETE /v1/namespaces/{ns}/views` | `405` `MethodNotAllowedException`, `Allow` naming at least GET and POST |
| `HEAD /v1/namespaces/{ns}/views` | `404` with `response.content == b''` — **not** 405. After exact-method matching fails, `RequestMethodsRequestCondition` falls a `HEAD` request back onto any mapping declaring `GET`, so this dispatches to `listViews` and the advice suppresses the envelope. (The item route declares `HEAD` explicitly and so never reaches the fallback.) Easy to get wrong in either direction, and only a real request settles it |
| `POST .../views` with `Content-Type: text/plain` **and a non-empty body** | `415` `UnsupportedMediaTypeException`, `message` `The route consumes application/json`, and an `Accept` header naming `application/json`. The body is required: `createView` declares `@RequestBody(required = false)`, so Spring skips the `consumes` condition entirely on a bodyless request and the case degrades into the 400 of §6.2 — which is exactly why the "no body at all" row there is correct |
| `GET /v1/namespaces/{ns}/views/{view}/rename` | `404` `NotFoundException`, `message` exactly `Route does not exist`; and neither the namespace, the view name, nor the string `rename` appears anywhere in the body — the redaction contract, asserted as absence |
| `POST /v1/oauth/tokens` | `404` `NotFoundException`, `Route does not exist` — deliberately unclaimed protocol surface |
| `HEAD /v1/absent-{RUN_ID}` | `404`, `response.content == b''` |
| `GET /v1/databases/{db}/tables/{t}/absent-{RUN_ID}` | `404` **Iceberg** envelope, `NotFoundException`, `Route does not exist` — the tables surface now under this advice |
| `POST /v1/databases/{db}/tables/{t}` | `405` with the **Iceberg** envelope, not the bare `ResponseEntityExceptionHandler` 405 the route used to give. The advice's guard is `uri.startsWith("/v1/")`, so this is a wire change for existing OpenHouse tables clients |
| `POST /v1/databases/{db}/tables` with `Content-Type: text/plain` and a non-empty body | `415` with the Iceberg envelope, for the same reason |
| `GET /absent-{RUN_ID}` (no `/v1` prefix) | the legacy OpenHouse envelope: `400`, body carrying `status`/`error`/`message`, and **no** `error.type` key — proof the split is by path prefix in the deployed app |

### 6.4 Authentication

The interceptor rejects before dispatch and writes no body, so these assert the status and
nothing else. Asserting the body would pin container error-page behavior rather than ours.
The row that matters most is the last, because it is a property of ordering that no
controller test can produce.

| Request | Expected |
|---|---|
| `GET /v1/config`, no `Authorization` header | `401` |
| `GET .../views/{view}`, `Authorization: Basic abc` | `401` |
| `GET .../views/{view}`, `Authorization: Bearer notajwt` | `401` (`MalformedJwtException` is caught) |
| `GET .../views/{view}`, a well-formed JWT signed by the wrong key | `401` — passes only with the §6.6 fix |
| `GET .../views/{view}`, `Authorization: Bearer ` (empty token) | `401` — passes only with the §6.6 fix |
| `GET .../views/{view}`, a correctly signed JWT whose subject carries a wrong `CODE` | `401` — passes only with the §6.6 fix |
| `GET /v1/absent-{RUN_ID}`, no `Authorization` header | `404` `Route does not exist`, **not** `401`. `DispatcherServlet` throws `NoHandlerFoundException` before building a handler chain, so no interceptor runs. Route existence under `/v1` is therefore observable without credentials; pinning it makes any future change to that a visible diff rather than a silent one |

### 6.5 Discovery cases

Two behaviors are inferred rather than observed today. Each gets a case that records what
the container actually does; the point of running the suite the first time is to settle them.

| Request | What is unknown | How the case is written |
|---|---|---|
| `GET /v1/namespaces/ns1%1Fns2/views/{view}` (and the item route's collection sibling) | `OpenHouseViewsApiHandler` maps a multi-level namespace to a 404, but that is proven only through MockMvc, which bypasses Tomcat's URI parsing entirely. Tomcat may reject an encoded control character before Spring ever sees it, and Spring Boot 2.7.8 matches on the encoded path with `PathPatternParser` | Assert the response is either the spec `404` envelope or a `4xx` rejection from the container — never a `200` and never a `5xx` — and record the observed status and body in a comment beside the case. A stricter container rejection is not a defect; a `5xx` is |
| `HEAD /v1/config` | Whether Tomcat strips the body for a route whose advice does not suppress it | Assert `200` and `response.content == b''` |

A third candidate — a well-formed JWT signed by the wrong key — was decidable by reading the
code, and is a defect rather than an open question. It is written up next.

### 6.6 A token-interceptor leak the suite must land a fix for

`DummyTokenInterceptor` catches `MalformedJwtException | NoSuchAlgorithmException |
InvalidKeySpecException | JSONException`. Three reachable inputs fall outside that set:

- a well-formed JWT signed by the **wrong key** → `io.jsonwebtoken.SignatureException`;
- `Authorization: Bearer ` with an empty token → `IllegalArgumentException` from jjwt;
- a correctly signed token whose subject carries a wrong `CODE` → `SecurityException`, which
  the interceptor **throws itself** eleven lines above the catch that does not cover it.

Each propagates out of `applyPreHandle` with the handler already resolved, so
`processHandlerException` runs. On a views route that means the views advice's
`@ExceptionHandler(Exception.class)` and a fixed-message `500` — wrong, but redacted. On a
tables route under `/v1/databases/...` no views advice applies and
`OpenHouseExceptionHandler.handleGenericException` returns `message = exception.toString()`
**and an abbreviated stack trace in the response body**. Any client that sends a wrong-key
JWT can read it.

So this is not a views defect at all; the views suite is just where it surfaced. The fix is
one line in `services/common`, widened past the single exception the discovery framing
implied:

```java
} catch (JwtException            // subsumes MalformedJwtException, SignatureException, ExpiredJwtException
    | NoSuchAlgorithmException
    | InvalidKeySpecException
    | JSONException
    | SecurityException
    | IllegalArgumentException e) {
```

Land it with the suite, and assert `401` for all three inputs in §6.4 rather than carrying
them as open questions. The deeper problem — an interceptor in `services:common` deciding a
protocol rendering (`response.setStatus(401)`) with no contract, for every service that
installs it — is out of scope here and belongs in the compliance document.

## 7. Idempotency against a warm container

Today it is free and the suite must not come to depend on that. Every view route answers
`404` from `ViewsDisabledService`, so no case creates anything, and there is nothing to clean
up: running the script twice against the same container gives identical results, and a case
that fails halfway leaves the service byte-identical to how it found it.

The suite still derives every namespace and view name from `RUN_ID`, for one forward-looking
reason: the moment persistence lands, a leftover view from an earlier run would flip an
expected `404` into a `200`, and the failure would look like a regression in the code under
test. Nothing in the present case list needs the scoping — the tables-surface case in §6.3
carries a database id, but that request resolves to no handler at all, so it never reaches
the House Tables store and the id in it is never looked up.
When persistence lands, the create-path cases grow a `try/finally` that issues
`DELETE /v1/namespaces/{ns}/views/{view}` — the same shape `hts_integration_test.py` uses —
and the `RUN_ID` scoping is already in place to make that correct.

The suite writes nothing to disk, needs no database connection, and therefore has no
`SkippedTest` paths except the discovery cases in §6.5.

## 8. CI changes

Three edits to `.github/workflows/build-run-tests.yml`. They touch neither
`pr-validations.yml` (that workflow only calls this one) nor `scripts/python/requirements.txt`
(`requests` is already pinned) — but two of the three do change how the **existing** tables
integration test runs, and they conflict with another branch. Read §8.1 first.

**Replace the fixed sleep with a health probe.** `/actuator/health` is excluded from the
token interceptor and exposed by `management.endpoints.web.exposure.include` on both
services, and the k8s recipes already use it as both liveness and readiness — so it is the
probe this repository has already decided on, and unlike a request to `/v1/databases` it
needs no token:

```yaml
      - name: Wait for the services to report healthy
        run: |
          for i in $(seq 1 60); do
            tables=$(curl -fsS http://localhost:8000/actuator/health || true)
            hts=$(curl -fsS http://localhost:8001/actuator/health || true)
            echo "poll $i: tables=${tables:-<none>} hts=${hts:-<none>}"
            if echo "$tables" | grep -q '"status":"UP"' \
               && echo "$hts" | grep -q '"status":"UP"'; then
              echo "ready after $((i * 5))s"
              exit 0
            fi
            sleep 5
          done
          echo "timed out after 300s"
          docker compose -f infra/recipes/docker-compose/oh-only/docker-compose.yml ps
          exit 1
```

This matters beyond flakiness: `oh-only` declares no healthchecks and its `depends_on` has no
`condition:`, so `up -d` returns as soon as the containers are created. The current 30-second
sleep is the only thing standing between CI and a race.

Be precise about what the probe proves. Neither service configures a readiness group
(`management.endpoint.health.probes.enabled` is unset in both `application.properties`), and
the tables service excludes `DataSourceAutoConfiguration` and registers no `HealthIndicator`
for House Tables or OPA — so its health is `DiskSpace` plus `Ping`, which means "the Spring
context started and the connector is accepting", not "every dependency is reachable". Polling
both services separately, as above, recovers most of that gap: House Tables answering `UP` on
8001 is the dependency the tables service most needs. It is a strictly better signal than a
fixed sleep and a weaker one than an end-to-end round trip.

**Add the suite as its own step**, after the existing tables step so a failure names which
surface broke:

```yaml
      - name: Run Views REST Integration Tests
        run: |
          python scripts/python/views_rest_integration_test.py \
            ./tables-test-fixtures/tables-test-fixtures-iceberg-1.2/src/main/resources/dummy.token
```

**Make teardown unconditional and dump logs on failure.** Today the `down` step has no
`if: always()`, so a failing test leaks the containers into the runner's cleanup and throws
away the only evidence of why:

```yaml
      - name: Dump container logs
        if: failure()
        run: docker compose -f infra/recipes/docker-compose/oh-only/docker-compose.yml logs --tail=400

      - name: Stop Docker Containers
        if: always()
        run: docker compose -f infra/recipes/docker-compose/oh-only/docker-compose.yml down
```

`oh-only` stays the target recipe: it is the one the job already builds and starts, and the
views surface has no persistence, so a backing store is irrelevant to every case here. If
CI later moves to the MySQL-backed variant, the suite is unaffected — it takes the base URL
as an optional argument and addresses only the tables service on 8000.

### 8.1 These edits collide with the HTS port branch

`origin/claude/port-698-hts-e2e-mysql` rewrites the same three steps in the same file, in an
incompatible direction. It switches the compose file from `oh-only` to **`oh-only-mysql`**,
replaces the `sleep 30` with its own poll of `/v1/databases` and `/hts/tables/query`, and adds
a `Run HouseTables Integration Tests` step.

Whichever lands second gets a conflict in `Start Docker Containers`, the wait step, and
`Stop Docker Containers`. The dangerous resolution is the quiet one: a merge that keeps this
document's `oh-only` teardown while the job started `oh-only-mysql` leaves `down` addressing a
recipe that was never brought up, so the containers leak — the exact failure §8 exists to fix.

The resolution, whichever order they land in:

- **The recipe is the HTS branch's call.** `oh-only-mysql` is a superset; every case in this
  document passes against it unchanged, because they all address the tables service on 8000
  and none of them touches persistence. If that branch lands first, change the compose paths
  in §8 to `oh-only-mysql` and change nothing else.
- **The readiness probe should be this document's.** `/actuator/health` needs no token, covers
  both services, and works against either recipe; the `/v1/databases` poll needs the fixture
  token and pins a route that the branch's own test then re-exercises. Keep one wait step, and
  make it this one.
- **The two test steps are additive.** The HTS step and the views step name different scripts
  and different ports; both stay.
- **`if: always()` on teardown and the log dump are additive** and neither branch has them.

The lower-risk sequencing is to land the readiness step, the log dump and `if: always()` on
their own first — they fix a live flake and are useful to both branches — and let this
document add only the views step. If that happens, strike the first and third edits from §8
and say so here.

## 9. What this cannot cover, and why that is the design

Written into the script's module docstring so a reader does not mistake the omissions for
oversights:

- **No success path for any view operation.** All six answer `404 "Views are disabled"`. The
  suite proves the surface is reachable, correctly shaped and correctly *absent* — the
  posture the compliance plan chose so that a stock client falls through to `loadTable`.
- **No 403.** Not because authorization always allows — `AuthorizationInterceptor.check()`
  denies on a missing credential and on an unauthenticated principal — but because
  `DummyTokenInterceptor` answers 401 before dispatch for exactly those requests, so a
  `@Secured` method is only entered with an authenticated principal. A recipe that swapped the
  token interceptor out would need this revisited. OPA is wired only into the tables and
  databases services, and the recipe's `opa/data.json` grants the dummy principal everything
  anyway.
- **The 401 carries no body, and the spec says it should.** `UnauthorizedResponse` in the REST
  catalog spec is an `application/json` `IcebergErrorResponse`; OpenHouse sends a bare status,
  because `DummyTokenInterceptor` writes only `setStatus`. A stock client's
  `ErrorHandlers.defaultErrorHandler` cannot build a `NotAuthorizedException` from an empty
  body and falls back to a generic `RESTException`. The suite pins the status alone (§6.4), so
  it will freeze this deviation rather than flag it — recorded here so that is a decision
  rather than an oversight. The fix lives where §6.6's does: the interceptor decides a
  protocol rendering with no contract.
- **No 409, no `CommitFailedException`, no `assert-view-uuid` failure.** All require state.
- **No pagination.** The list route returns no identifiers, so `pageSize`'s only observable
  behavior is the `400` in §6.2.
- **"Namespace absent" and "views disabled" are indistinguishable.** There is no POST for
  databases — a database is materialized by creating a table — and while the service is
  stubbed no view route performs a database lookup, so both render as the same 404 with the
  same message. That is deliberate: a client that could tell them apart could tell that the
  views surface exists but is switched off, which is precisely what the disabled posture
  hides.
- **Nothing about the client.** Whether Iceberg's `ErrorHandlers` turns these envelopes back
  into the right exception types belongs to the plugin's own tests.

## 10. Verification

From a clean tree, on this branch:

```bash
cd /path/to/openhouse

# 1. Build. tables-service.Dockerfile copies build/tables/libs/*.jar, so this must come
#    first or the image is built from a stale (or missing) jar.
./gradlew clean build

# 2. Start the recipe. No healthchecks, so step 3 is not optional.
docker compose -f infra/recipes/docker-compose/oh-only/docker-compose.yml up -d --build

# 3. Wait for readiness.
until curl -fsS http://localhost:8000/actuator/health | grep -q '"status":"UP"'; do sleep 5; done

# 4. Sanity-check the surface by hand before trusting the suite.
TOKEN=$(cat tables-test-fixtures/tables-test-fixtures-iceberg-1.2/src/main/resources/dummy.token)
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8000/v1/config
#   -> {"defaults":{},"overrides":{},"endpoints":["GET /v1/config", ... 7 total ...]}
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8000/v1/namespaces/d1/views/v1
#   -> {"error":{"message":"Views are disabled","type":"NoSuchViewException","code":404}}
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8000/v1/config
#   -> 401

# 5. Run the suite.
pip install -r scripts/python/requirements.txt
python scripts/python/views_rest_integration_test.py \
  tables-test-fixtures/tables-test-fixtures-iceberg-1.2/src/main/resources/dummy.token

# 6. Prove idempotency: the same command, twice, with identical output.
python scripts/python/views_rest_integration_test.py \
  tables-test-fixtures/tables-test-fixtures-iceberg-1.2/src/main/resources/dummy.token

# 7. Prove the suite fails loudly: stop the service and re-run.
docker compose -f infra/recipes/docker-compose/oh-only/docker-compose.yml stop openhouse-tables
python scripts/python/views_rest_integration_test.py \
  tables-test-fixtures/tables-test-fixtures-iceberg-1.2/src/main/resources/dummy.token  # connection error

docker compose -f infra/recipes/docker-compose/oh-only/docker-compose.yml down
```

Step 6 is the one that distinguishes this suite from `integration_test.py`. That script is
re-runnable after a *green* run — it deletes `d3.t1` and re-asserts its absence — but not
after a red one: a failure between the create and the delete leaves `d3.t1` behind, and the
next run fails at its opening not-found assertion rather than at the real defect. This suite
is re-runnable after either. Step 7 guards against the failure mode where every case is
vacuously satisfied.

On the first green run, the two §6.5 cases stop being discovery cases: their observed status
and body are recorded in the script beside each assertion, and the assertions tighten to
equality.

## Appendix A. The alternatives, developed

**A — grow `scripts/python/integration_test.py`.** Eighty-one lines, hardcoded to
`http://localhost:8000` and to `d3.t1`, with four ordered test functions called directly from
`__main__` and no runner, no readiness wait and no cleanup. It opens by asserting `d3.t1` does
*not* exist and closes by asserting it does not exist again, which is idempotent only when
every case in between passes; a failure between create and delete leaves the table behind and
the next run fails at the first line for the wrong reason. Adding the thirty-odd views cases of §6 to
that file would put the views surface behind a table lifecycle it has nothing to do with, and
a `d3.t1` left over from a failed run would fail the views cases too. The correct move is a
sibling file that borrows nothing from it but the token-file argument.

**C — `@SpringBootTest(webEnvironment = RANDOM_PORT)` in `services/tables`.** The strongest
rejected option, and worth stating plainly what it *would* have bought: a real Tomcat
connector, real HTTP, real `HEAD` body stripping, real header writing, and the real filter and
advice chain — most of §6.3 and §6.4. What it cannot buy is the deployment: it boots from the
test classpath with a test `cluster.yaml`, so the `supported-dialects` default read from the
mounted recipe (§6.2, last row), the interceptor the recipe selects (§6.4), the shipped jar's
component scan (§6.1), and the `application.properties` the image actually runs with are all
substituted. Those are the failures worth catching, because they are the ones that would ship.
Its real advantage — running under `./gradlew build` with no container — is already covered:
the existing MockMvc suite (`IcebergRestViewsControllerTest`) pins the same envelopes at the
unit level, and duplicating it one layer up would double the maintenance without adding a
distinct failure mode.

**D — Testcontainers-driven Java itest.** Satisfies every requirement in §2 and adds a build
module, a Docker-in-CI path parallel to the compose recipe, and a second definition of "how
OpenHouse starts locally". The compose recipes are the documented way to run the stack and
CI already starts one; a second orchestration path would have to be kept in sync with the
recipes forever to test routes the first one already serves. Reconsider only if CI stops
starting a container.

**Why python and not `curl` in the workflow.** Assertions in `run:` blocks are shell string
comparisons with no structured body access, no way to assert a key's *absence*, and no
failure message beyond the exit code — which forecloses requirement 3 outright.
`scripts/python/` already holds the harness this repository uses for exactly this job.
