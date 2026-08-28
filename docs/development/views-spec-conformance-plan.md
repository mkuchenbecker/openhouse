# Adopt Iceberg's `ViewCatalogTests` by subclass, and serve `rename-view` next

Correctness evidence for the views surface should come from `org.apache.iceberg.view.ViewCatalogTests`,
subclassed by hand against the embedded test server, pulled from the coordinate we already ship —
`com.linkedin.iceberg:iceberg-core:1.5.2.17:tests`. The REST Compatibility Kit (RCK) should not be
adopted: its view class is *only* `ViewCatalogTests` with different hooks, it exists solely at
Iceberg 1.7.0+, and against this server it errors in `@BeforeAll` before reaching a single test body.
Identical assertions, strictly worse reachability.

On spec surface: after view persistence — which almost everything here waits on — serve exactly one
more route, `POST /v1/{prefix}/views/rename`. It converts 5 permanently-failing conformance tests
into runnable ones and is the only thing standing between us and Spark's `ALTER VIEW … RENAME TO`.
Namespaces, `register-view`, the table routes and the OAuth endpoint stay unserved, and §5 says why
each one buys nothing: a stock 1.5.2 `RESTCatalog` calls no namespace route on the view path, the
1.5.2-era spec has no `register-view` at all, and the OAuth endpoint is marked deprecated for removal
in the spec that defines it.

**Status:** proposal. Nothing here is implemented; the harness in §4 does not exist yet.
**Branch:** `claude/iceberg-rest-spec-compliance-l0s2ju`.
**Companions:** [views-iceberg-rest-compliance.md](views-iceberg-rest-compliance.md) (the server
surface this document tests) and [views-client-plugin-plan.md](views-client-plugin-plan.md) (the
client that consumes it). Read the first one before this.
**References:** [Iceberg REST Catalog OpenAPI](https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml)
(line numbers below are `apache/iceberg` `main`, 6026 lines, fetched 2026-08-28) ·
[`ViewCatalogTests` at 1.5.2](https://github.com/apache/iceberg/blob/apache-iceberg-1.5.2/core/src/test/java/org/apache/iceberg/view/ViewCatalogTests.java)

## 1. Requirements

**Must**

1. The evidence is Iceberg's own test code, executed unmodified. Assertions we wrote about our own
   wire shape cannot be the proof that our wire shape is right.
2. It runs against the Iceberg version we actually serve — 1.5.2.17 (`build.gradle:34`). Evidence
   gathered with a different client describes a different client.
3. Wiring it adds no second Iceberg lineage to any classpath, no new Gradle module, and no
   client-version bump.
4. The harness is verifiable *before* view persistence exists: the suite must start, reach test
   bodies, and produce a per-test pass/skip/fail verdict against today's stubbed server.
5. Every test that cannot pass is skipped by a mechanism the suite itself defines (an opt-out hook or
   its own `Assumptions`), never by us annotating individual methods `@Disabled`. A test we silence
   by hand is a test we can silence by accident.
6. Each spec route we add names the tests or the client operation it unlocks. Completeness against
   the OpenAPI document is not a reason on its own.

**Should**

7. The suite version tracks the Iceberg version bump through the existing sync
   ([renovate-iceberg-sync.md](renovate-iceberg-sync.md)) rather than floating on Iceberg `main`,
   whose copy of the class has already drifted to 50 tests.
8. What a green run does *not* cover is written next to the instructions for producing one (§7).

**Won't, this milestone**

9. Enabling the suite in the default `:services:tables:test` run. Against `ViewsDisabledService`
   every view-only test fails at its first `create()`; a permanently red suite is not a signal.
   It lands `@Disabled` with the reason recorded, and the persistence change flips it.
10. RCK, at any version. §3 and Appendix B.
11. `register-view`, the namespace *write* routes (`createNamespace`, `dropNamespace`,
    `updateProperties`), the whole table surface, and `POST /v1/oauth/tokens`.
12. Emitting `next-page-token` from `listViews`. The spec forbids paginating a request that carries
    no `pageToken` (lines 2388-2390) and the 1.5.2 client follows no continuation token anyway.

**Out of scope**

13. View persistence itself. It is the prerequisite this document schedules around, not something
    this document designs. Every ordering claim in §5 is relative to it.
14. Bumping the Iceberg dependency to 1.7+. The only thing that would buy is RCK, which §3 rejects
    on other grounds.
15. Regenerating `docs/specs/catalog.md`, unchanged from the compliance plan's §1.11.

## 2. Context: what exists, and what has judged it

The server serves seven routes — `GET /v1/config`; `GET|POST /v1/namespaces/{namespace}/views`;
`GET|POST|DELETE|HEAD /v1/namespaces/{namespace}/views/{view}` — un-prefixed, with all seven
advertised in the config `endpoints` list (`IcebergRestViewPaths.IMPLEMENTED_ENDPOINTS`). The
backend behind them is `ViewsDisabledService`: every view call is a `404` rendered as
`NoSuchViewException` or `NoSuchNamespaceException` per route.

Everything that currently judges this surface, we wrote. `IcebergRestViewsContractTest` pins
serialized key sets and round-trips through Iceberg's parsers; `IcebergRestViewsControllerTest` pins
statuses and envelopes through MockMvc; the client plugin's `OpenHouseCatalogViewsRestTest` drives a
real `RESTCatalog` against canned JSON in a `MockWebServer` — canned by us. Iceberg's parsers accepting
a document we assembled with Iceberg's serializers is a weaker claim than it looks: it does not test
that `create` then `load` returns what a catalog client expects, that a failed requirement produces
the exception the client branches on, or that `viewExists` on a fresh identifier is false for the
right reason. A conformance suite written by the people who wrote the client tests exactly that.

## 3. Options for the conformance evidence

The columns are the must-requirements from §1; the rows are the four ways to get external evidence.
Row 1 is the recommendation, and row 2 is the one worth reading closely, because RCK is the obvious
answer and it is the wrong one: it fails requirement 4 not marginally but totally — it never reaches
a test body against this server.

| Option | 1. Iceberg's own code | 2. Runs at 1.5.2.17 | 3. No new lineage/module | 4. Runnable today | 5. Mechanical skips | Cost |
|---|---|---|---|---|---|---|
| **1. `ViewCatalogTests` subclass (recommended)** | Yes — the class, unmodified | Yes — published at our exact coordinate | Yes — one `testImplementation` line, classifier on a jar we already resolve | Yes — 10 skip, 28 run and fail with readable diffs | Yes — `tableCatalog()` returning `null` and `requiresNamespaceCreate()` staying `false` are the suite's own hooks | ~40 lines of test code |
| 2. RCK (`RESTCompatibilityKitViewCatalogTests`) | Yes, but *the same class* — RCK adds no assertion of its own | No — `iceberg-open-api` starts at 1.7.0 | No — a 1.7.x `iceberg-open-api` and its client beside our 1.5.2.17 | **No** — `@BeforeAll` calls `listNamespaces()`; our 404 errors the class in setup | No — `tableCatalog()` is overridden to a non-null `RESTCatalog`, so the 10 table tests cannot skip | Namespaces + full table surface + rename first |
| 3. Status quo (our contract tests only) | No | n/a | n/a | n/a | n/a | Zero, and no external evidence ever |
| 4. RCK later, after the full surface | Yes | No | No | Later, by definition | No | The entire table domain re-projected onto the Iceberg wire |

**The verdict is option 1, and the deciding fact is byte identity.** `RESTCompatibilityKitViewCatalogTests`
extends `ViewCatalogTests<RESTCatalog>` and contributes four hook overrides, a `@BeforeAll`, a
`@BeforeEach` and an `@AfterAll` — not one assertion about view behavior. The assertions all live in
`ViewCatalogTests`, and that class is *the same file*: `ViewCatalogTests.java` is byte-identical
between tags `apache-iceberg-1.5.2` and `apache-iceberg-1.7.0` (sha256
`664c16d90e74605388a699b8b462d0b12188aa3c10222f8406fbbdb0b1669213`, `diff` → 0 lines), and the
compiled `ViewCatalogTests.class` shipped by the LinkedIn fork is byte-identical to Apache's
(sha256 `9ecb76c050b4083f82d662b521694f871768160219e375418690d62406b5fe76` in both
`com.linkedin.iceberg:iceberg-core:1.5.2.17:tests` and `org.apache.iceberg:iceberg-core:1.5.2:tests`).
Adopting RCK would drag a 1.7+ client and a new artifact lineage onto the test classpath to run
assertions we can run today from a jar we already resolve.

**And RCK's wrappers are actively hostile to this server.** Its `@BeforeAll` asserts on
`restCatalog.listNamespaces()`, which we answer with the `/v1/**` unresolved-route 404 — the class
errors in setup and reports zero tests, not 28 failures. Its `@BeforeEach` calls
`RCKUtils.purgeCatalogTestEntries`, which walks `namespaceExists` → `listTables` → `dropTable` →
`listViews` → `dropView` → `dropNamespace`. Its `requiresNamespaceCreate()` defaults to **true**, and
its `tableCatalog()` returns the same non-null `RESTCatalog`, so the 10 cross-entity tests lose their
skip. RCK is a suite for a catalog that already serves namespaces, tables and rename; it is not a
staged instrument, and there is no configuration that makes it one. (For the record, since it will
come up again: RCK reads `CATALOG_`-prefixed env vars — `CATALOG_URI`, `CATALOG_TOKEN`,
`CATALOG_WAREHOUSE`, `CATALOG_IO__IMPL` — or `rck.`-prefixed system properties, and `rck.local`
defaults to true, booting a local reference server unless explicitly set false.)

Appendix B carries the rest of the RCK evidence.

## 4. The subclass

### 4.1 What the suite is

`ViewCatalogTests` is an abstract JUnit 5 class introduced in Iceberg 1.5.0. It declares 38 `@Test`
methods — 39 executions, because `createOrReplaceView` is a `@ParameterizedTest` over
`@ValueSource(booleans = {false, true})` — and its whole contract is 27 lines (1.5.2 source, 48-74):

```java
public abstract class ViewCatalogTests<C extends ViewCatalog & SupportsNamespaces> {
  protected abstract C catalog();
  protected abstract Catalog tableCatalog();          // may return null
  protected boolean requiresNamespaceCreate() { return false; }
  protected boolean overridesRequestedLocation() { return false; }
  protected boolean supportsServerSideRetry()  { return false; }
}
```

Two properties of that contract are what make the suite usable against a views-only server. First,
all 40 `createNamespace` calls in the file sit inside one of its 38 `if (requiresNamespaceCreate())`
blocks, and the class contains no `listNamespaces`, `namespaceExists` or `dropNamespace` call at all
— so with the default `false`, the suite never touches a namespace route. Second, 10 methods open
with `Assumptions.assumeThat(tableCatalog()).as("Only valid for catalogs that support tables").isNotNull()`
(lines 294, 324, 352, 382, 415, 448, 478, 658, 693, 788), so returning `null` makes JUnit **abort them
as skipped**, not failed. The 7 `rename*` methods have no such guard.

`RESTCatalog` satisfies the type bound (`implements Catalog, ViewCatalog, SupportsNamespaces, …`),
and the class needs nothing beyond iceberg-core, relocated Guava, AssertJ and `junit-jupiter-params`
— all four already on the `services/tables` test classpath (`build.gradle:101-105`, plus
`spring-boot-starter-test` from the Boot conventions). It is compiled at class-file major 52
(Java 8), matching `sourceCompatibility = VERSION_1_8`.

### 4.2 Wiring

One dependency line in `services/tables/build.gradle`:

```gradle
// Iceberg's own view-catalog conformance suite (org.apache.iceberg.view.ViewCatalogTests).
// Classifier notation, not testFixtures(...): iceberg-core's Gradle module metadata declares
// only apiElements and runtimeElements, so no test-fixtures variant exists to select.
testImplementation "com.linkedin.iceberg:iceberg-core:${rootProject.ext.iceberg_1_5_version}:tests"
```

The classifier resolves because the root repository declares `metadataSources { gradleMetadata();
mavenPom(); artifact() }` (`build.gradle:71-79`). Use the fork's artifact, not Apache's: the class
is byte-identical, and a second `org.apache.iceberg` lineage on the test classpath is a real
divergence risk for zero gain.

And a subclass, in the existing embedded-server test package:

```java
// services/tables/src/test/java/com/linkedin/openhouse/tables/e2e/h2/
@SpringBootTest(classes = SpringH2Application.class, webEnvironment = RANDOM_PORT)
@Disabled("Enabled by the view-persistence change; see docs/development/views-spec-conformance-plan.md §4.3")
public class IcebergRestViewCatalogConformanceTest extends ViewCatalogTests<RESTCatalog> {

  @LocalServerPort private int port;
  private RESTCatalog restCatalog;

  @BeforeEach
  void initCatalog() {
    restCatalog = new RESTCatalog();
    restCatalog.initialize("openhouse", ImmutableMap.of(
        CatalogProperties.URI, "http://localhost:" + port,
        "header.Authorization", "Bearer " + dummyToken()));   // services/common/.../dummy.token
  }

  @Override protected RESTCatalog catalog() { return restCatalog; }

  /** Null on purpose: aborts the 10 cross-entity tests as skipped, per the suite's own guard. */
  @Override protected Catalog tableCatalog() { return null; }

  // requiresNamespaceCreate() stays false — the suite then issues no namespace call at all.
}
```

`header.Authorization` rather than the REST `token` property, for the same reason as the client
plugin (§5.2 there): the OAuth2 session machinery must stay out of the loop. The token is the
existing `dummy.token` resource the Spark fixtures already use
(`services/common/src/main/resources/dummy.token`, on this module's classpath).

Host it in `services/tables` rather than in a new module or in
`integrations/java/iceberg-1.5/openhouse-java-itest`: the suite is evidence about the server, it
belongs beside the server's other e2e tests, and iceberg-core is already an `api` dependency there.
If `webEnvironment = RANDOM_PORT` turns out to fight the H2 test application, the fallback is
`OpenHouseLocalServer` from `tables-test-fixtures` (embedded Tomcat, OS-assigned port,
`getPort()`), which the Spark integration tests already boot the same way.

### 4.3 What the run reports, at each stage

This is the schedule the `@Disabled` annotation is keyed to. The middle row is the one that matters:
it is the milestone where the suite starts being evidence rather than a harness check.

| Stage | Skipped | Failing | Passing |
|---|---|---|---|
| Today (`ViewsDisabledService`) | 10 (table-guarded) | 28 | 0 whole methods |
| View persistence lands | 10 | 5 (rename-only) | up to 23 |
| `rename-view` served | 10 | 0 | up to 28 |
| Table surface served (not planned) | 0 | — | up to 38 |

The 38 methods partition as: 10 table-guarded, 7 rename, 2 of which are both, leaving 5 rename-only
and 23 view-only. Against today's stub, no method passes end to end — but the *leading* assertion of
most view-only tests, `assertThat(catalog().viewExists(identifier)).as("View should not exist").isFalse()`,
does pass, because 1.5.2's `viewExists` is the `ViewCatalog` default (`loadView`, catch
`NoSuchViewException`) and our views-disabled 404 renders exactly that type. Each test then dies at
its first `.create()`. That is the harness check requirement 4 asks for: setup works, the client
reaches us, our error envelope is understood, and the failures are all in one place.

Two opt-outs need a decision when persistence lands, and both are legitimate per-implementation
declarations rather than concealed failures. `overridesRequestedLocation()` gates location assertions
in `completeCreateView`, `createAndReplaceViewWithLocation` and `updateViewLocation`; set it `true`
if the server assigns view locations itself (as it does for tables) and `false` only if a
client-supplied `location` is honored verbatim. `supportsServerSideRetry()` gates a single retry
branch in `concurrentReplaceViewVersion` (line 1583); leave it `false` unless the commit path retries
a failed requirement server-side.

## 5. Remaining spec surface, in the order worth serving it

Everything below except the two items in §6 sits *after* view persistence: a rename route over a
catalog that cannot hold a view renames nothing, and every conformance test that would exercise
these fails on `create()` first regardless. The table's second column is the whole argument — an
item with nothing in it does not get built. `rename-view` is the only row above the line.

| Surface | What it unlocks | Relative to persistence | Verdict |
|---|---|---|---|
| `POST /v1/{prefix}/views/rename` (spec 1956) | 5 rename-only conformance tests, +2 more if tables ever exist; Spark `ALTER VIEW … RENAME TO`; `RESTSessionCatalog.renameView` (1.5.2, line 1106) | Immediately after | **Serve** |
| Namespace reads: `listNamespaces`, `loadNamespaceMetadata`, `namespaceExists` (spec 250-524) | No conformance test; no stock-client view call. Possibly a Spark-side probe — unverified | After rename, conditional on §5.2's check | Defer, pending one experiment |
| Namespace writes: `createNamespace`, `dropNamespace`, `updateProperties` | Same: nothing | — | Won't |
| `register-view` (spec 2020) | Nothing. Absent from the 1.5.2-era spec, so our client cannot call it | — | Won't |
| Table routes (list/create/load/update/drop/register/rename, scan planning, functions) | The 10 cross-entity tests, and all of `RESTCompatibilityKitCatalogTests` | Far after | Won't |
| `POST /v1/oauth/tokens` (spec 181) | Nothing; spec says not to implement it | — | Won't |

### 5.1 `rename-view` is the one to serve

`POST /v1/{prefix}/views/rename` takes a `RenameTableRequest` (`source` and `destination`, both
required), accepts an optional `Idempotency-Key`, and answers `204` on success, `404` for a missing
source view or target namespace, `406` for an unsupported cross-namespace move, `409` when the target
already exists. It is in the 1.5.2-era spec (line 1358 of the `apache-iceberg-1.5.2` document), and
1.5.2's `RESTSessionCatalog.renameView` posts to it directly, so our current client generation
already knows how to call it. Serving it is one route, one service method, and one addition to
`IcebergRestViewPaths.IMPLEMENTED_ENDPOINTS` — that last part is not optional for 1.7+ clients, which
refuse to attempt an operation whose endpoint we do not advertise.

The client plugin currently throws `UnsupportedOperationException` from `renameView` on both gate
states, deliberately matching this server's scope (plugin plan §5.4). Serving the route is what
retires that decision.

### 5.2 Namespaces block nothing, and the one thing that might is unverified

A stock 1.5.2 `RESTCatalog` calls no namespace route on the view path. `RESTSessionCatalog.initialize`
(1.5.2, lines 166-245) makes exactly one bootstrap call — `GET /v1/config` — plus
`POST /v1/oauth/tokens` if and only if the `credential` property is set, which our plugin never sets.
`ViewCatalogTests` with `requiresNamespaceCreate()` false makes none either. So the namespace routes
are required by RCK and by nothing else we plan to run.

**The open risk, stated plainly: nobody has checked whether Spark 3.5's view-resolution path issues a
namespace probe above the Iceberg client layer.** The research behind this document read
`RESTSessionCatalog`, not the Spark extension. If `ResolveViews` or `SparkCatalog` calls
`namespaceExists` before delegating, our 404 changes what the user sees. That is one experiment
against the gate-on integration test, not a design question, and it should be run before this
document's ordering is treated as settled. If it comes back positive, the three read-only namespace
operations move above `rename-view`: OpenHouse namespaces are single-level and map to `databaseId`,
and `DatabasesService` already exposes `getAllDatabases()`, so `listNamespaces`,
`namespaceExists` and `loadNamespaceMetadata` are thin reads over data we already have.

The write operations are a different question and the answer stays no. `DatabasesService` has no
create or drop — OpenHouse databases are implicit, brought into existence by creating a table — so
`createNamespace`, `dropNamespace` and `updateProperties` have no existing semantics to expose, only
semantics to invent. Inventing them to satisfy a suite we are not adopting is the wrong trade.

### 5.3 `register-view` and the tables domain

`register-view` (`{name, metadata-location}` → `LoadViewResponse`, schema at spec lines 4220-4229) is
absent from the 1.5.2-era document entirely, so a 1.5.2.17 client will never call it, and no
conformance test touches it. Serving it also means accepting a client-supplied metadata location as
the truth about a view's contents, which is an ownership question this project has not answered for
tables either. Leave it on the unresolved-route 404.

The table routes are the whole OpenHouse tables domain re-projected onto the Iceberg wire, alongside
the existing `/v1/databases/...` API — a migration, not an addition. What they would buy here is the
10 cross-entity tests, three of which additionally need staged table creation (`stage-create` on
`POST .../tables`, then a table commit — not the multi-table `POST /v1/{prefix}/transactions/commit`,
which 1.5.2's single-table `createTransaction` does not use). Ten tests is not a reason to migrate a
domain.

## 6. Two conformance details in what we already serve

Both are checkable now and neither is urgent.

**Pagination.** The spec is explicit that an absent `pageToken` means *no paging*, not *first page*:
"Servers that support pagination must return all results in a single response with the value of
`next-page-token` set to `null` if the query parameter `pageToken` is not set in the request"
(lines 2388-2390). Independently, 1.5.2's `RESTSessionCatalog.listViews` issues one GET and follows
no continuation token, so anything we put in `next-page-token` is silently dropped and the client's
listing truncates. `ViewIdentifiersPage` documents this obligation and `IcebergRestWire.toListViewsJson`
omits the field on a null token, so the wire layer is right; what is unverified is the service, which
today throws before producing a page. The persistence change must return all identifiers for an
un-tokened request, and the conformance suite's `listViews` test is what will catch it if it does not.

**`GET /v1/config` ignores `warehouse`.** The spec defines a `404 NoSuchWarehouseError` for an
unknown warehouse (line 166); `IcebergRestViewsController.getConfig()` takes no query parameter and
always answers 200. A 1.5.2 client sends `?warehouse=` only when the `warehouse` catalog property is
set (`fetchConfig`, 1.5.2 line 894), and our plugin never sets it. Recorded as an accepted deviation:
this server has exactly one warehouse and no name for it, so there is no value to validate against.

**Non-issues, recorded so they are not reopened.** `X-Iceberg-Access-Delegation` appears on table
paths only (spec 583, 712, 802, 981, 1055) and on no view route. `Idempotency-Key` is opt-in and a
server signals support by publishing `idempotency-key-lifetime` in `/v1/config` (spec 2315-2325),
which we correctly omit — note that on the current spec the key is listed on view *replace* (1803)
and *drop* (1906) but not on create, the reverse of what one would guess. `referenced-by` is an
optional query parameter on `loadView` (1770) as well as on the table routes; it is a lineage hint,
no 1.5.2 client sends it, and ignoring an unknown query parameter is correct. Metrics reporting,
credential vending, remote signing and ETag are table-only. Empty `defaults` and `overrides` are the
right answer for a server with no prefix and no warehouse to impose. Advertising
`HEAD .../views/{view}` is harmless even though Iceberg 1.7.0 defines no `Endpoint` constant for it.

The `endpoints` list itself stays load-bearing: `RESTSessionCatalog` 1.7.0 gates all 20 of its
operations on `Endpoint.check`, and when a server sends no `endpoints`, the assumed default set
(spec lines 105-135; `DEFAULT_ENDPOINTS`, 1.7.0 lines 137-152) contains **no view route at all**.

## 7. Verification

**Wiring, start to finish.** Add the one `testImplementation` line from §4.2 to
`services/tables/build.gradle`; add `IcebergRestViewCatalogConformanceTest` from §4.2 under
`services/tables/src/test/java/com/linkedin/openhouse/tables/e2e/h2/`; run it explicitly, since it
lands `@Disabled`. A Gradle `-D` reaches the daemon, not the forked test JVM, so deactivate the
condition through the test task (or just delete the annotation locally):

```
./gradlew :services:tables:test --tests '*IcebergRestViewCatalogConformanceTest'
# with, temporarily, in services/tables/build.gradle:
#   test { systemProperty 'junit.jupiter.conditions.deactivate', 'org.junit*DisabledCondition' }
```

Expect 39 executions: 10 aborted with "Only valid for catalogs that support tables", 29 failed
(28 methods, one of them parameterized twice), 0 passed. Any *error* rather than failure — anything
in setup, or a `RESTException` about a config bootstrap — is a harness bug, not a server finding.

**Step 0, before trusting any of the above.** Pulling the whole `-tests.jar` puts Iceberg's own
`TestRESTCatalog`, `TestJdbcCatalog` and friends on the test classpath. Gradle's `Test` task scans
`testClassesDirs` — the module's own compiled test classes — and not classpath jars, and neither the
root `tasks.withType(Test)` block (`build.gradle:107-130`) nor `services/tables/build.gradle`
overrides `scanForTestClasses` or sets a `filter`, so those classes should never execute. Confirm it
rather than assume it: run `./gradlew :services:tables:test` before and after adding the dependency
and check the test count is unchanged (660 today). If it moves, add an explicit
`filter { includeTestsMatching "com.linkedin.openhouse.*" }` rather than removing the dependency.

**What a green run would prove**, once persistence lands and the class is enabled: that a real
Iceberg client, at the version we ship, can create, load, replace, list, drop and version views
against this server; that our error envelopes deserialize into the exception types the client
branches on; that requirement conflicts surface as `CommitFailedException`; and that view metadata
survives a round trip with its schemas, versions and version log intact.

**What it would not prove.** Nothing about namespaces, tables, or any cross-entity rule — those 10
tests are skipped, and a skip is silent. Nothing about `rename-view` until it is served. Nothing
about authorization: the suite runs as one principal and asserts nothing about `@Secured`. Nothing
about pagination beyond a single small page. Nothing about Spark: `ResolveViews`, dialect selection
and the fall-through posture are the client plugin's integration tests, not this suite's. And
nothing about clients newer than 1.5.2 — in particular the `endpoints` gating in 1.7+, which no test
in this repository exercises today.

## Appendix A. Artifact facts, re-verified

Everything in this table was confirmed by download and inspection on 2026-08-28, not from memory. The
first row is the one the recommendation rests on: the artifact we already resolve carries the suite.

| Fact | Value |
|---|---|
| `com.linkedin.iceberg:iceberg-core:1.5.2.17:tests` | `iceberg-core-1.5.2.17-tests.jar`, 1,172,649 B, sha256 `0d561f9da3904727a0cbb099f836d41c0ef0a37cecca015bba906fe6e487defe` |
| `ViewCatalogTests.class` in it | sha256 `9ecb76c050b4083f82d662b521694f871768160219e375418690d62406b5fe76`, class-file major 52 |
| Same class in `org.apache.iceberg:iceberg-core:1.5.2:tests` (1,156,543 B jar) | sha256 identical |
| `ViewCatalogTests.java`, tags `apache-iceberg-1.5.2` vs `apache-iceberg-1.7.0` | sha256 `664c16d9…` both; `diff` → 0 lines; drifts only on `main` (50 tests) |
| iceberg-core Gradle module metadata (1.5.2.17) | variants `apiElements`, `runtimeElements` only — no test-fixtures variant, hence classifier notation |
| Test methods | 38 `@Test` (39 executions; `createOrReplaceView` is `@ParameterizedTest` over two booleans) |
| Namespace calls | 40 `createNamespace`, all inside one of 38 `if (requiresNamespaceCreate())` blocks; zero `listNamespaces`/`namespaceExists`/`dropNamespace` |
| Table guards | 10 `Assumptions.assumeThat(tableCatalog()).isNotNull()` (lines 294, 324, 352, 382, 415, 448, 478, 658, 693, 788) |

## Appendix B. RCK, developed

RCK was introduced in Iceberg 1.7.0: `open-api/src/test/java/org/apache/iceberg/rest/RESTCompatibilityKitCatalogTests.java`
and `…ViewCatalogTests.java`, with `RCKUtils` and `RESTServerExtension` in that module's
`testFixtures`. All three paths 404 at every tag through `apache-iceberg-1.6.1`. Apache publishes
`org.apache.iceberg:iceberg-open-api` at 1.7.0 through 1.11.0 on Central, with `tests` and
`test-fixtures` classifiers and no main jar.

One correction to earlier notes: the LinkedIn fork *does* publish `com.linkedin.iceberg:iceberg-open-api`,
at 1.5.2.0-rc1 through 1.5.2.20. It does not help. The 1.5.2.17 `-tests.jar` is 6,184 B and contains
no classes at all — just `META-INF/MANIFEST.MF`, `iceberg-build.properties`, `LICENSE` and `NOTICE`
— because that module predates RCK. There is no RCK at our pinned version from either publisher.

The view class in full is 60 lines of wiring: it extends `ViewCatalogTests<RESTCatalog>`, holds a
static `RESTCatalog` from `RCKUtils.initCatalogClient()`, and overrides `catalog()`, `tableCatalog()`
(same catalog, non-null), `requiresNamespaceCreate()` (property `rck.requires-namespace-create`,
default **true**), `supportsServerSideRetry()` (default true) and `overridesRequestedLocation()`
(default false). Its `@BeforeAll` asserts `restCatalog.listNamespaces()` excludes the test
namespaces; its `@BeforeEach` runs `RCKUtils.purgeCatalogTestEntries`, which for each test namespace
calls `namespaceExists`, then `listTables`/`dropTable`, `listViews`/`dropView`, then `dropNamespace`.
Walked against our seven routes, the first call in `@BeforeAll` lands on the `/v1/**` 404 and the
class errors before any test body runs.

## Appendix C. Where this document corrects earlier notes

Recorded so the next reader does not re-derive them: (1) the pure-view test count is **23**, not ~21
— 38 methods minus 10 table-guarded minus the 5 rename tests that are not also table-guarded;
(2) `Idempotency-Key` is listed on view replace and drop, not on create-view, so the config-signal
argument holds but the route list in earlier notes was inverted; (3) `referenced-by` is not
table-only — it is an optional query parameter on `loadView` too, and ignoring it is still correct;
(4) the three transaction-based cross-entity tests commit through staged table create/replace, not
through `POST /v1/{prefix}/transactions/commit`; (5) `supportsServerSideRetry()` gates exactly one
test (`concurrentReplaceViewVersion`), not a branch in several; (6) `com.linkedin.iceberg` does
publish an `iceberg-open-api` module, but its tests jar is empty of classes.
