# Adopt Iceberg's `ViewCatalogTests` by subclass, and serve `rename-view` next

Correctness evidence for the views surface should come from `org.apache.iceberg.view.ViewCatalogTests`,
subclassed by hand against the embedded test server, pulled from the coordinate we already ship —
`com.linkedin.iceberg:iceberg-core:1.5.2.17:tests`. The REST Compatibility Kit (RCK) should not be
adopted: its view class is *only* `ViewCatalogTests` with different hooks, it exists solely at
Iceberg 1.7.0+, and its fixtures, walked against our seven routes, error in `@BeforeAll` on a
`listNamespaces()` call we answer with a 404, before any test body runs. That walkthrough is
Appendix B, read from source: RCK has never been executed here, because it does not exist at the
version we pin. Identical assertions, strictly worse reachability.

The gap this closes: every test that judges the views surface today, we wrote. Iceberg's parsers
accepting documents Iceberg's serializers produced is not evidence that a real client can drive this
server, and §2 says why the three suites we have cannot become that evidence by being extended.

On spec surface: after view persistence — which almost everything here waits on — serve exactly one
more route, `POST /v1/{prefix}/views/rename`. It converts 5 permanently-failing conformance tests
into runnable ones and is the only thing standing between us and Spark's `ALTER VIEW … RENAME TO`.
Note that the compliance document records `rename-view` as out of scope, owner-confirmed; §5.1 is a
proposed revision to that decision, not a decision.

`register-view`, the namespace *write* routes, the table routes and the OAuth endpoint stay
unserved, and §5 says why each buys nothing: the 1.5.2-era spec has no `register-view` at all,
`DatabasesService` has no create or drop whose semantics we could expose, and the OAuth endpoint is
marked deprecated for removal in the spec that defines it. The three read-only namespace routes are
the one open question — no client we plan to run calls them, but nobody has checked whether Spark
3.5 probes a namespace above the Iceberg client layer, and §5.2 names the one experiment that
decides whether they move ahead of `rename-view`.

Two details in the routes we already serve also get verdicts here. `listViews` must return every
identifier for a request carrying no `pageToken`, which is an obligation the persistence change
inherits rather than work available today, and `GET /v1/config` ignoring the `warehouse` parameter is
accepted as a permanent deviation for a server with one unnamed warehouse (§6).

**Status:** proposal. Nothing here is implemented; the harness in §4 does not exist yet.
**Branch:** `claude/iceberg-rest-spec-compliance-l0s2ju`.
**Companions:** [views-iceberg-rest-compliance.md](views-iceberg-rest-compliance.md) (the server
surface this document tests) · [views-client-plugin-plan.md](views-client-plugin-plan.md) (the
client that consumes it) · [views-docker-rest-tests-plan.md](views-docker-rest-tests-plan.md)
(black-box HTTP conformance against the deployed container — it owns the wire-level `message`,
`type` and `endpoints` assertions that §5.1 and the persistence prerequisite will both invalidate;
see §5.1) · [views-spark-docker-itests-plan.md](views-spark-docker-itests-plan.md) (its **Stage 3**,
a real `ViewsService` over a `ViewOperations`, is the "view persistence" every schedule below waits
on). Read the first one before this.
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
   every view-only test fails at its first `create()`; a permanently red suite is not a signal. It
   lands `@Disabled` with the reason recorded. Persistence alone does **not** flip it — the five
   rename-only tests would still fail — so the annotation names both persistence and `rename-view`,
   and §4.3 explains why the alternative is unsatisfiable.
10. RCK, at any version. Its assertions are the same class we already run, and its fixtures error
    in setup against this server before any test body executes (§3, Appendix B).
11. `register-view`, the namespace *write* routes (`createNamespace`, `dropNamespace`,
    `updateProperties`), the whole table surface, and `POST /v1/oauth/tokens`.
12. Emitting `next-page-token` from `listViews`. The spec forbids paginating a request that carries
    no `pageToken` (lines 2388-2390) and the 1.5.2 client follows no continuation token anyway.

**Out of scope**

13. View persistence itself. It is the prerequisite this document schedules around, not something
    this document designs. Every ordering claim in §5 is relative to it.
14. Bumping the Iceberg dependency to 1.7+. The only thing that would buy is RCK, which §3 rejects
    on other grounds.
15. Regenerating `docs/specs/catalog.md`. It needs a booted service and the external `widdershins`
    tool, and regenerates on the next scheduled spec refresh — the same decision as the compliance
    plan's §1.11.

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

The columns are must-requirements 1 through 5 from §1, plus what each option costs to wire; must 6
governs §5's route choices, not this one. The rows are the four ways to get external evidence.
Row 1 is the recommendation, and row 2 is the one worth reading closely, because RCK is the obvious
answer and it is the wrong one: it fails requirement 4 outright, never reaching a test body against
this server.

| Option | 1. Iceberg's own code | 2. Runs at 1.5.2.17 | 3. No new lineage/module | 4. Runnable today | 5. Mechanical skips | Cost |
|---|---|---|---|---|---|---|
| **1. `ViewCatalogTests` subclass (recommended)** | Yes — the class, unmodified | Yes — published at our exact coordinate | Yes — one `testImplementation` line, classifier on a jar we already resolve | Yes — 10 skip, 28 run and fail with readable diffs | Yes — `tableCatalog()` returning `null` and `requiresNamespaceCreate()` staying `false` are the suite's own hooks | ~40 lines of test code |
| 2. RCK (`RESTCompatibilityKitViewCatalogTests`) | Yes, but *the same class* — RCK adds no assertion of its own | No — Apache's `iceberg-open-api` starts at 1.7.0, and the fork's 1.5.2.x `-tests` jar holds no classes (App. B) | No — a 1.7.x `iceberg-open-api` and its client beside our 1.5.2.17 | **No** — by source walkthrough, not by a run: `@BeforeAll` calls `listNamespaces()`, which we answer with a 404, erroring the class in setup | No — `tableCatalog()` is overridden to a non-null `RESTCatalog`, so the 10 table tests cannot skip | Namespaces + full table surface + rename first |
| 3. Status quo (our contract tests only) | No | n/a | n/a | n/a | n/a | Zero, and no external evidence ever |
| 4. RCK later, after the full surface | Yes | No | No | Later, by definition | No | The entire table domain re-projected onto the Iceberg wire |

**The verdict is option 1, and column 4 is what decides it: RCK never reaches a test body against
this server, while the subclass produces a per-test verdict today.** Byte identity is why paying
RCK's cost would buy nothing even if it ran. `RESTCompatibilityKitViewCatalogTests`
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
staged instrument. Its properties reach only the survivable part of that: `rck.requires-namespace-create`
can be set false, but `tableCatalog()` and the `@BeforeAll` namespace assertion are written into the
class with no property behind them, so no configuration turns it into one. (For the record, since it will
come up again: RCK reads `CATALOG_`-prefixed env vars — `CATALOG_URI`, `CATALOG_TOKEN`,
`CATALOG_WAREHOUSE`, `CATALOG_IO__IMPL` — or `rck.`-prefixed system properties, and `rck.local`
defaults to true, booting a local reference server unless explicitly set false.)

Appendix B carries the rest of the RCK evidence.

## 4. The subclass

### 4.1 What the suite is

`ViewCatalogTests` is an abstract JUnit 5 class introduced in Iceberg 1.5.0. It declares 38 test
methods — 37 annotated `@Test` and one `@ParameterizedTest` (`createOrReplaceView`, over
`@ValueSource(booleans = {false, true})`), for 39 executions — and its **declared** contract is 27
lines (1.5.2 source, 48-74). Read §4.2 before treating those 27 lines as the whole contract; the
undeclared half is that the catalog is empty when each test begins.

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
as skipped**, not failed. Five of the 7 `rename*` methods have no such guard; the other two
(658, 693) are among the ten above.

`RESTCatalog` satisfies the type bound (`implements Catalog, ViewCatalog, SupportsNamespaces, …`),
and the class needs nothing beyond iceberg-core, relocated Guava, AssertJ and `junit-jupiter-params`
— all four already on the `services/tables` test classpath (root `build.gradle:101-105`, plus
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
@SpringBootTest(
    classes = SpringH2Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(
    initializers = {PropertyOverrideContextInitializer.class, AuthorizationPropertiesInitializer.class})
@Disabled("Enabled only when BOTH view persistence and POST /v1/views/rename have landed; with "
        + "persistence alone the 5 rename-only tests fail and the suite is permanently red. "
        + "See docs/development/views-spec-conformance-plan.md §4.3.")
public class IcebergRestViewCatalogConformanceTest extends ViewCatalogTests<RESTCatalog> {

  private static final List<Namespace> TEST_NAMESPACES =
      ImmutableList.of(
          Namespace.of("ns"), Namespace.of("ns1"), Namespace.of("ns2"),
          Namespace.of("other_ns"), Namespace.of("non_existing"));

  @LocalServerPort private int port;
  private RESTCatalog restCatalog;

  @BeforeEach
  void initCatalog() throws Exception {
    // AuthorizationPropertiesInitializer registers DummyTokenInterceptor, which answers 401 before
    // dispatch unless a Bearer token decodes to DUMMY_CODE. It also enables @Secured, so the whole
    // suite runs as the single principal DUMMY_ANONYMOUS_USER and asserts nothing about denial.
    String token = new DummyTokenInterceptor.DummySecurityJWT("DUMMY_ANONYMOUS_USER").buildNoopJWT();
    restCatalog = new RESTCatalog();
    restCatalog.initialize("openhouse", ImmutableMap.of(
        CatalogProperties.URI, "http://localhost:" + port,
        "header.Authorization", "Bearer " + token));
  }

  /**
   * ViewCatalogTests assumes an empty catalog at the start of every test — Iceberg's own
   * TestRESTViewCatalog rebuilds its backend in @BeforeEach and RCK purges in @BeforeEach — but
   * @SpringBootTest caches one context, and one backing store, across all 38 methods.
   */
  @AfterEach
  void purgeAndClose() throws IOException {
    try {
      for (Namespace ns : TEST_NAMESPACES) {
        try {
          restCatalog.listViews(ns).forEach(restCatalog::dropView);
        } catch (NoSuchNamespaceException | NoSuchViewException absent) {
          // Namespace never materialized, or views are still disabled: nothing to purge.
        }
      }
    } finally {
      restCatalog.close();
    }
  }

  @Override protected RESTCatalog catalog() { return restCatalog; }

  /** Null on purpose: aborts the 10 cross-entity tests as skipped, per the suite's own guard. */
  @Override protected Catalog tableCatalog() { return null; }

  // requiresNamespaceCreate() stays false — the suite then issues no namespace call at all.
}
```

**The `@AfterEach` is not optional, and an earlier draft of this plan omitted it.** §4.1 quotes
`ViewCatalogTests`' 27-line hook surface as though it were the whole contract. It is not: the
undeclared half is that the catalog is empty when each test starts. Nine of the 38 tests leave a
view behind — `createViewThatAlreadyExists`, `createViewConflict`, `renameViewNamespaceMissing`,
`renameViewTargetAlreadyExistsAsView`, `concurrentReplaceViewVersion` and the three
`testSqlFor*` methods — and 23 open by asserting the view does *not* exist. JUnit 5's default
method order is deterministic but unspecified, so whenever a leaking test sorts ahead of
`basicCreateView`, that test fails on its first line with a diff that says nothing about the
server, on some machines and not others. Every in-tree subclass of this class supplies the reset;
the plan cited RCK's `purgeCatalogTestEntries` twice as hostile machinery without noticing it is
the machinery this subclass needs.

**The context wiring is the module's convention, not decoration.** Ten of the twelve
application-booting tests in `e2e/h2` carry the same two initializers.
`PropertyOverrideContextInitializer` is what points `cluster.storage.root-path` at a fresh temp
directory — which matters the moment persistence writes metadata — and
`AuthorizationPropertiesInitializer` is what sets
`cluster.security.token.interceptor.classname`, defaulted to `null` by `ClusterProperties`.
Without the second, the `Authorization` header is inert decoration; with it, `@Secured` is live.
The suite takes the second option and runs as one authenticated principal.

`header.Authorization` rather than the REST `token` property, for the same reason as the client
plugin (§5.2 there): the OAuth2 session machinery must stay out of the loop.

Two mechanical notes for whoever writes this. `webEnvironment = RANDOM_PORT` has **no precedent in
this module** — `grep -rn 'webEnvironment\|LocalServerPort' services/tables/src/test/java` returns
nothing, because every existing test is MockMvc — so the fallback below is likelier than it looks.
And there is no `dummyToken()` helper anywhere in the repository; the four existing consumers of
`dummy.token` each read the classpath resource with their own idiom, none reachable from
`services/tables`, which is why the snippet above builds the JWT directly.

Host it in `services/tables` rather than in a new module or in
`integrations/java/iceberg-1.5/openhouse-java-itest`: the suite is evidence about the server, it
belongs beside the server's other e2e tests, and iceberg-core is already an `api` dependency there.
If `webEnvironment = RANDOM_PORT` turns out to fight the H2 test application, the fallback is
`OpenHouseLocalServer` from `tables-test-fixtures` (embedded Tomcat, OS-assigned port,
`getPort()`), which the Spark integration tests already boot the same way.

### 4.3 What the run should report, at each stage

These counts are derived from the suite's source, not from a run; §7 is how to turn the first row
into a measurement. The `@Disabled` annotation is keyed to this schedule, and the row that matters is
`rename-view` served, because that is the milestone that removes the annotation.

| Stage | Skipped | Failing | Passing |
|---|---|---|---|
| Today (`ViewsDisabledService`) | 10 (table-guarded) | 28 | 1 — `createViewErrorCases`, client-side only |
| View persistence lands (suite stays `@Disabled`) | 10 | 5 (rename-only) | up to 23, run manually per §7 |
| `rename-view` served — **the milestone that removes `@Disabled`** | 10 | 0 | up to 28 |
| Table surface served (not planned) | 0 | — | up to 38 |

**Why persistence alone does not enable the suite.** An earlier draft had the persistence change
delete the `@Disabled`. It cannot: at that stage the five rename-only tests fail, requirement 5
forbids annotating individual methods `@Disabled`, and `ViewCatalogTests` publishes no hook that
skips rename — so the three constraints are jointly unsatisfiable and `:services:tables:test` would
go from green to permanently red. The annotation therefore names *both* milestones, and the stage-2
row is a manual-run state rather than a shipping one.

The 38 methods partition as: 10 table-guarded, 7 rename, 2 of which are both, leaving 5 rename-only
and 23 view-only. Against today's stub only `createViewErrorCases` passes, and it never contacts the
server — its four `assertThatThrownBy` blocks all trip `Preconditions` inside `RESTViewBuilder.create()`
before any HTTP call. The *leading* assertion of most view-only tests,
`assertThat(catalog().viewExists(identifier)).as("View should not exist").isFalse()`, also passes.
Each test then dies at its first `.create()`.

That is the harness check requirement 4 asks for — but read the **failures**, not the leading
assertion, to get it. `viewExists` is the `ViewCatalog` default over `loadView`, and 1.5.2's
`RESTSessionCatalog.loadView` converts any `RESTException` into `NoSuchViewException`, so that
assertion passes against a malformed envelope, a 500 and a connection reset alike. The envelope is
proven by every test dying at `.create()` with `NoSuchNamespaceException: Views are disabled` — the
per-route type the compliance doc specifies — rather than with a `RESTException`.

**The `@Disabled` needs something that trips when its trigger arrives.** Otherwise the class lands
disabled, nothing in the repository notices when persistence lands, and a 38-test deliverable becomes
dead code — the exact surface requirement 5 exists to prevent, reintroduced one level up. The
checkable half is a guard test in the same package; the human half is a row in the execution
checklist, which the orchestrator owns.

```java
/** Trips when the stub is replaced, so the @Disabled conformance suite cannot be forgotten. */
@SpringBootTest
@ContextConfiguration(initializers = PropertyOverrideContextInitializer.class)
public class ConformanceSuiteReenableTriggerTest {

  @Autowired private ViewsService viewsService;

  @Test
  void viewsStillStubbedSoTheConformanceSuiteMayStayDisabled() {
    assertThat(viewsService)
        .as("ViewsDisabledService has been replaced. Serve POST /v1/views/rename, then delete the"
            + " @Disabled on IcebergRestViewCatalogConformanceTest, re-baseline §4.3, and delete"
            + " this test.")
        .isInstanceOf(ViewsDisabledService.class);
  }
}
```

Two opt-outs need a decision when persistence lands, and both are legitimate per-implementation
declarations rather than concealed failures. `overridesRequestedLocation()` gates location assertions
in `completeCreateView`, `createAndReplaceViewWithLocation` and `updateViewLocation`; set it `true`
if the server assigns view locations itself (as it does for tables) and `false` only if a
client-supplied `location` is honored verbatim. `supportsServerSideRetry()` gates a single retry
branch in `concurrentReplaceViewVersion` (line 1583); leave it `false` unless the commit path retries
a failed requirement server-side.

## 5. Remaining spec surface, in the order worth serving it

Every surface in the table below sits *after* view persistence: a rename route over a
catalog that cannot hold a view renames nothing, and every conformance test that would exercise
these fails on `create()` first regardless. The table's second column is the whole argument — an
item with nothing in it does not get built. `rename-view` is the only row with a Serve verdict.

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
already knows how to call it. Wiring it is one route, one service method, and one addition to
`IcebergRestViewPaths.IMPLEMENTED_ENDPOINTS` — that last part is not optional for 1.7+ clients, which
refuse to attempt an operation whose endpoint we do not advertise. Mount it un-prefixed, at
`POST /v1/views/rename`, matching the seven routes we already serve, while advertising the spec's
templated form in `IMPLEMENTED_ENDPOINTS`.

**But "one route, one service method" understates it by three semantic decisions,** and converting
all five rename tests needs all three. `renameViewNamespaceMissing` requires a 404
`NoSuchNamespaceException` for a rename into an absent namespace — and OpenHouse databases are
implicit, brought into existence by creating a table, so no write path today has a "does this
database exist" answer to give. That has to be defined, probably over `DatabasesService`.
`renameViewUsingDifferentNamespace` requires cross-namespace moves to **succeed**; the spec permits
refusing with 406, but the test does not. And `renameViewTargetAlreadyExistsAsView` pins the 409
message verbatim (§6.3).

Two assertions in the black-box plan also retire when this lands: its `/v1/config` `endpoints` array
is pinned in order to seven entries, and an eighth breaks it.

The client plugin currently throws `UnsupportedOperationException` from `renameView` whether views
are enabled or disabled, deliberately matching this server's scope (plugin plan §5.4). Serving the route is what
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

One is settled today. The other is checkable in the wire layer today but is an obligation the
persistence change inherits. Neither is urgent.

**Pagination.** The spec is explicit that an absent `pageToken` means *no paging*, not *first page*:
"Servers that support pagination must return all results in a single response with the value of
`next-page-token` set to `null` if the query parameter `pageToken` is not set in the request"
(lines 2388-2390). Independently, 1.5.2's `RESTSessionCatalog.listViews` issues one GET and follows
no continuation token, so anything we put in `next-page-token` is silently dropped and the client's
listing truncates. `ViewIdentifiersPage` documents this obligation and `IcebergRestWire.toListViewsJson`
omits the field on a null token, so the wire layer is right; what is unverified is the service, which
today throws before producing a page.

The obligation itself belongs to the compliance plan, which already states it ("with no `pageToken`,
return every identifier in one page and omit `next-page-token`"). What this document adds is who
checks it: the conformance suite's `listViews` test is the only thing that will catch a persistence
change that paginates eagerly. Note this is not work available today — it is an obligation the
persistence change inherits, which is why §5's "everything sits after persistence" applies here too.

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

**Step 0. Baseline the test count, before touching `build.gradle`.** Pulling the whole `-tests.jar`
puts Iceberg's own `TestRESTCatalog`, `TestJdbcCatalog` and friends on the test classpath. Gradle's
`Test` task scans `testClassesDirs` — the module's own compiled test classes — and not classpath
jars, and neither the root `tasks.withType(Test)` block (root `build.gradle:108-131`) nor
`services/tables/build.gradle` overrides `scanForTestClasses` or sets a `filter`, so those classes
should never execute. Confirm it rather than assume it: run `./gradlew :services:tables:test` **now**
and note the count (660 today), then run it again after Step 1 and check it is unchanged. If it
moves, add an explicit `filter { includeTestsMatching "com.linkedin.openhouse.*" }` rather than
removing the dependency.

**Step 1. Wiring, start to finish.** Add the one `testImplementation` line from §4.2 to
`services/tables/build.gradle`; add `IcebergRestViewCatalogConformanceTest` and
`ConformanceSuiteReenableTriggerTest` from §4.2 under
`services/tables/src/test/java/com/linkedin/openhouse/tables/e2e/h2/`; run the first explicitly,
since it lands `@Disabled`. A Gradle `-D` reaches the daemon, not the forked test JVM, so deactivate
the condition through the test task (or just delete the annotation locally):

```
./gradlew :services:tables:test --tests '*IcebergRestViewCatalogConformanceTest'
# with, temporarily, in services/tables/build.gradle:
#   test { systemProperty 'junit.jupiter.conditions.deactivate', 'org.junit*DisabledCondition' }
```

Expect 39 executions: 10 aborted with "Only valid for catalogs that support tables", 28 failed, and
**1 passed** — `createViewErrorCases`, whose four assertions are all client-side `Preconditions`
inside `RESTViewBuilder.create()` and never reach the server. Every failure must be a
`NoSuchNamespaceException` at `.create()` carrying `Views are disabled`; any *error* rather than
failure, and any `RESTException`, is a harness bug rather than a server finding.

**Step 2. The three musts nothing else catches.** Requirements 1, 2 and 3 are true by construction
and checked by nothing, so a future Iceberg bump could satisfy should-7 mechanically while quietly
breaking them. Print the resolved coordinate and confirm it is the one requirement 2 names and the
one Appendix A hashed:

```
./gradlew :services:tables:dependencies --configuration testRuntimeClasspath | grep -i iceberg
#   -> exactly one lineage, com.linkedin.iceberg, at 1.5.2.17
#   -> no org.apache.iceberg line, and no second version
```

Run it after every Iceberg bump. It is the only check behind requirements 1, 2 and 3.

**What the persistence change inherits from this plan.** Four items, all blocking a green run, and
each argued in a different section — collected here because the person who needs them is reading
their own design document, not this one:

| Obligation | Where it is argued |
|---|---|
| Return every identifier for a `listViews` request carrying no `pageToken` | §6 |
| Decide `overridesRequestedLocation()`: `true` if the server assigns view locations itself, `false` only if a client-supplied `location` is honored verbatim | §4.3 |
| Decide `supportsServerSideRetry()`: `false` unless the commit path retries a failed requirement server-side | §4.3 |
| Emit the exact error `message` strings Iceberg's handlers copy into the exceptions the tests assert on | Appendix C |

Removing the `@Disabled` is **not** on that list: it waits for `rename-view` as well, per §4.3.

**What a green run would prove**, once persistence lands and the class is enabled: that a real
Iceberg client, at the version we ship, can create, load, replace, list, drop and version views
against this server; that our error envelopes deserialize into the exception types the client
branches on; that requirement conflicts surface as `CommitFailedException`; and that view metadata
survives a round trip with its schemas, versions and version log intact.

**What it would not prove.** Nothing about namespaces, tables, or any cross-entity rule — those 10
tests are skipped, and a skip is silent. Nothing about `rename-view` until it is served. Nothing
about authorization beyond the happy path: `@Secured` is live (§4.2 wires the token interceptor), but
the suite runs as the single principal `DUMMY_ANONYMOUS_USER` and asserts nothing about denial. Nothing
about pagination beyond a single small page. Nothing about Spark: `ResolveViews`, dialect selection
and the fall-through posture are the client plugin's integration tests, not this suite's. And
nothing about clients newer than 1.5.2 — in particular the `endpoints` gating in 1.7+, which no test
in this repository exercises today.

## Appendix A. Artifact facts

Every value here was confirmed by downloading the artifact and inspecting it on 2026-08-28. The
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

The LinkedIn fork *does* publish `com.linkedin.iceberg:iceberg-open-api`,
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

## Appendix C. Message strings this suite makes part of the wire contract

Adopting `ViewCatalogTests` is not purely black-box: Iceberg's error handlers copy our
`IcebergErrorResponse.message` verbatim into the exception they raise, and several tests then assert
on that text. `ErrorHandlers.ViewErrorHandler` renders 404 as `NoSuchViewException("%s", message)`
and 409 as `AlreadyExistsException("%s", message)`; `ViewCommitErrorHandler` renders 409 as
`CommitFailedException("Commit failed: %s", message)`.

So the persistence change must emit these strings, or the tests that check them fail on wording:

| Assertion | Required server `message` |
|---|---|
| `createViewThatAlreadyExists`, `createViewConflict` | contains `View already exists: ns.view` |
| `renameViewSourceMissing` | starts with `View does not exist: ns.non_existing` |
| `replaceViewErrorCases`, `replaceViewConflict`, and two others | contains `View does not exist: ns.view` |
| `concurrentReplaceViewVersion` (retry off) | contains `Cannot commit` |
| `renameViewNamespaceMissing` | contains `Namespace does not exist: non_existing` |
| `renameViewTargetAlreadyExistsAsView` | contains `Cannot rename ns.viewOne to ns.viewTwo. View already exists` |

This collides with the black-box plan, which pins those same `message` fields over the wire to
exactly `Views are disabled` and requires the exact string. Both are correct for their own stage —
the strings differ because the service behind them differs — but the persistence change touches both
and the compliance document should own the resulting vocabulary. Until it does, treat this table as
the handoff.

Note also that five "view-only" tests already depend on the *table* 404's type:
`RESTViewBuilder.replace()` calls `tableExists` before every `replace()` and `createOrReplace()`, and
that works only because the unresolved-`/v1` handler renders `type: NotFoundException`, which
`ErrorHandlers.TableErrorHandler` maps to `NoSuchTableException` — the one type `tableExists` catches.
