# Sequencing Iceberg REST catalog support

**Conclusion first.** Three blocks gate REST support, in this order: **first-class namespaces**
(today databases are derived, not stored), **an Iceberg-native commit path**
(`requirements`/`updates` applied server-side, replacing the whole-document `baseTableVersion`
protocol), and **a catalog-level plumbing switch** with a stock-client-compatible client shim.
Everything else — multi-level namespacing, views persistence, the facade's known defects — is
either downstream of those three or independently small.

Two findings change the plan as sketched:

1. **The switch cannot be a table property alone.** A table property is only readable after the
   table has already been loaded through one of the two paths, so it cannot route `loadTable`,
   and it does not exist at all for `createTable`, `listTables`, or `listNamespaces`. The router
   has to be catalog-level, with a server-side override delivered through `GET /v1/config`. A
   table property is still valuable — as a *pin* that makes a commit over the wrong path fail
   loudly — but it is an assertion, not a router. §6.
2. **Multi-level and mono namespacing coexist for free if, and only if, the encoding is
   prefix-preserving** — `encode(Namespace.of("db")) == "db"`, byte for byte. That makes
   simultaneity an invariant to pin with a test rather than a dual-code-path problem. The
   encoding decision is a one-way door and must be made *before* the first REST write, not
   before the first REST read. §2.1.

## 1. Where the work stands

| Stream | State | Reference |
|---|---|---|
| Read-only REST facade (`/v1/config`, list/load/exists tables) | Implemented, behind `cluster.tables.iceberg-rest.enabled` | [#34](https://github.com/mkuchenbecker/openhouse/pull/34) stack |
| Reference conformance harness (`CatalogTests`, 101 methods) | Implemented; 1 passing, 95 disabled with reasons | [#34](https://github.com/mkuchenbecker/openhouse/pull/34) |
| Views wire surface, spec-shaped | Implemented; backend stubbed, disabled by default | [#44](https://github.com/mkuchenbecker/openhouse/pull/44) |
| Views persistence backend | Not started | [#44](https://github.com/mkuchenbecker/openhouse/pull/44) plan 6 |
| Namespaces as an entity | Not started | §2.2 |
| Iceberg-native commits | Not started | §3 |

PR #34's `@Disabled` list is the most precise roadmap that exists, because each entry names the
endpoint that unblocks it. Grouped by the block that owns it:

| Block | Disabled reference tests |
|---|---|
| Write path (transactions 29, `commitTable` 20, `createTable` 13) | **62** |
| Namespace endpoints (`updateNamespaceProperties` 6, `createNamespace` 5, `dropNamespace` 4, `listNamespaces` 3, `loadNamespaceMetadata` 1) | **19** |
| `renameTable` 5, `dropTable` 4, `registerTable` 2, metrics 1 | 12 |
| Known facade defects | 2 |

Two blocks account for 81 of 95. Sequencing is really about those two.

## 2. Block A — identity and namespacing

### 2.1 Multi-level namespaces

Today the namespace *is* the `databaseId` string. `NamespaceUtil.MAX_NAMESPACE_DEPTH = 1`;
`OpenHouseInternalCatalog` and `OpenHouseInternalTableOperations` call
`tableIdentifier.namespace().toString()` and use the result as the database key; the REST handler
rejects anything but depth 1 in `decodeSingleLevelNamespace`. `NamespaceUtil` was written as the
single seam for this and already says so in its javadoc — that is the file to change, but the
call sites still assume the seam is a no-op.

Sub-blockers, roughly in dependency order:

- **Encoding choice (one-way door).** `Namespace.toString()` dot-joins, and `.` is currently
  outside the allowed identifier charset (`^[a-zA-Z0-9_]+$`), so dot-joining is unambiguous
  *today* — and it is prefix-preserving, which is what buys simultaneity with the mono-namespace
  world. Iceberg's own wire encoding uses `%1F` (unit separator) for multipart namespaces in
  `RESTUtil`. Whichever is chosen, it becomes the HTS key format permanently the moment a client
  creates a nested namespace over REST.
- **Charset widening.** The spec allows arbitrary UTF-8 in namespace levels. PR #34 already
  records the consequence: the reference suite's `non-existing` namespace (a hyphen) draws a
  `400 IllegalArgumentException` where the spec requires `404 NoSuchTableException`. Widening
  the charset and admitting a separator are the same edit and the same review.
- **Storage layout.** `BaseStorage.allocateTableLocation` produces
  `{rootPrefix}/{databaseId}/{tableId}-{uuid}`, and `Storage.isPathValid` round-trips through it.
  A `databaseId` containing the separator either yields a flat directory whose name contains
  dots, or a decision to nest. Existing table locations are already written; whatever is chosen
  must leave every depth-1 path byte-identical.
- **Authorization.** ACLs attach to a `DatabaseDto` keyed by `databaseId`
  (`DatabasesServiceImpl.checkDatabasePrivilege`). Nested namespaces need an explicit answer on
  whether privileges inherit down the tree. "No inheritance" is a defensible v1 and is much
  cheaper.
- **Metadata-table disambiguation.** `NamespaceUtil.isTableNamespace` gates
  `isValidIdentifier(...)`, and the depth-1 rule is what currently distinguishes a base table
  from a metadata-table fallback (`db.table.snapshots` resolves as metadata precisely because
  depth 2 is not a table namespace). Lifting the depth cap removes that discriminator; it needs
  replacing before the cap moves, or `db.table.history` starts resolving as a base table in
  namespace `db.table`.

**Simultaneity invariant.** State and test it explicitly: for every existing depth-1 namespace,
the HTS key, the storage path, the ACL subject, and the REST route are unchanged after
multi-level support lands. A prefix-preserving encoding makes this true by construction, so the
test is a regression guard rather than a compatibility shim.

### 2.2 Namespaces as a first-class entity ("database support")

Databases do not exist as stored objects. `DatabasesServiceImpl.getAllDatabases()` reads
`openHouseInternalRepository.findAllIds()` — every table primary key — and `distinct()`s the
`databaseId` out of it. Consequences:

- No `createNamespace`, `dropNamespace`, `loadNamespaceMetadata`, `updateNamespaceProperties`.
  That is the 19 disabled tests above, and it is why the conformance harness runs with
  `requiresNamespaceCreate=false`.
- An empty namespace cannot be represented. Neither can namespace properties.
- **It blocks multi-level namespacing in practice**, not just on paper: with derived databases
  there is no way to represent an intermediate namespace `a` that contains only the namespace
  `a.b` and no tables.

Needed: an HTS entity for namespaces with a properties map, alongside the `entity_type`
discriminator work already ported in [#45](https://github.com/mkuchenbecker/openhouse/pull/45) →
[#47](https://github.com/mkuchenbecker/openhouse/pull/47) for views. Decisions to make: whether
existing implicit databases are backfilled or materialized lazily on first write; whether
`dropNamespace` on a populated namespace is `NamespaceNotEmptyException` or a cascade; whether
namespace properties are readable by the existing `/v1/databases` API.

**This is the block to start with.** It is a prerequisite of §2.1, it is independently useful to
the current API, and it is invisible to existing clients.

## 3. Block B — the commit path

This is the largest block and the least compressible.

**Today.** The client builds `TableMetadata` locally, serializes the schema to a JSON *string*,
and PUTs a `CreateUpdateTableRequestBody` carrying `schema`, `timePartitioning`, `clustering`,
`tableProperties`, `policies`, `tableType`, the `stageCreate`/`stageReplace`/`replaceCommit`
flags, and `baseTableVersion`. The server *reconstructs* the change:
`OpenHouseInternalRepositoryImpl.save` runs `doUpdateSchemaIfNeeded`, `doUpdateSnapshotsIfNeeded`
and a property merge, and detects conflict by comparing `baseTableVersion` — a metadata **file
path** — against the current head, throwing `CommitFailedException` on mismatch.

**The spec.** `POST .../tables/{table}` carries `UpdateTableRequest{requirements[], updates[]}`:
typed `MetadataUpdate`s applied by `TableMetadata.Builder`, and `UpdateRequirement`s
(`assert-table-uuid`, `assert-ref-snapshot-id`, `assert-last-assigned-field-id`, …) checked
server-side, with `409 CommitFailedException` on a failed requirement and
`CommitStateUnknownException` on 5xx.

These do not translate field-by-field. What is actually required:

1. **A metadata-update applier and a requirement checker** in the internal catalog. Both are
   iceberg-core primitives; the work is wiring them to HTS's optimistic-concurrency version
   column rather than to a file-path comparison.
2. **Re-expressing OpenHouse's admission rules over `MetadataUpdate` lists.** `SchemaValidator`
   currently validates a submitted whole-schema against the table's schema. The same rules have
   to become checks over an `add-schema` / `set-current-schema` pair. Partition-spec
   immutability, reserved `openhouse.*` properties, and the policy validators are the same
   shape of problem.
3. **Staged create and transactions.** `stageCreate` / `stageReplace` / `replaceCommit` are
   OpenHouse's spelling of the spec's staged-create plus
   `POST /v1/{prefix}/transactions/commit`. 29 disabled tests.
4. **OH-only required fields must become server-defaulted.** A stock client sends no
   `clusterId`, no `baseTableVersion`, no `tableType`. All three are currently required or
   validated on the request body.
5. **OH concepts with no spec home** — `policies` (retention, sharing, history, replication,
   column tags) and `tableType` — need the reserved-property convention that PR #44 already
   established for views (`openhouse.source-dialect` as a spec-sanctioned `summary` entry). The
   open question is narrower than it looks: are policies *settable* over REST in v1, or
   read-only there and mutable only through the OpenHouse API? Read-only is a much smaller v1
   and does not foreclose the other.
6. **Drop semantics.** REST `dropTable` carries `purgeRequested`; OpenHouse has soft delete,
   purge, and restore as separate `/v1` routes. Mapping needs stating.

**Sequencing note.** Steps 1 and 2 are worth doing *underneath the current API first* — re-point
`OpenHouseInternalRepositoryImpl.save` at the applier while the wire shape stays
`CreateUpdateTableRequestBody`. That converts the riskiest part of REST support into a
behaviour-preserving refactor validated by the existing 660-test tables suite, before any new
wire surface depends on it.

## 4. Block C — facade defects

Small, independent, and each one blocks a stock client outright. Worth clearing early because
they are what stands between the conformance harness and its next passing test.

- **Empty `pageToken=` rejected with 400.** Every Iceberg Java client since 1.6.0 sends it on
  the list loop, so `RESTCatalog.listTables()` cannot succeed even where the endpoint exists.
- **Identifier charset returning 400 where the spec requires 404** (subsumed by §2.1, fixable
  sooner).
- **`/v1/config`**: the explicit `endpoints` list is already noted as needed; `overrides.clusterId`
  is an open decision in [#44](https://github.com/mkuchenbecker/openhouse/pull/44).
- **Token-interceptor leak** — `DummyTokenInterceptor` does not catch `SignatureException`, the
  empty-token `IllegalArgumentException`, or the `SecurityException` it throws itself, so a
  `/v1/...` route can return a 500 carrying a stack trace. One line in `services/common`; found
  during views planning, not a views defect.

## 5. Block D — views

The wire surface is spec-shaped and merged; the backend is stubbed and views are disabled, so
Spark's `ResolveViews` falls through to `loadTable`. Remaining: `ViewsService` persistence over
the ported `entity_type` discriminator, and the `ViewCatalogTests` conformance subclass proposed
in `views-spec-conformance-plan.md`. `rename-view` scope is open.

Views are the cheap case and the proof of the pattern: a resource with no installed base took
the spec shape at zero migration cost. Tables cannot do that, which is exactly why §6 exists.

## 6. Block E — the client, and the switch

### 6.1 A stock `RESTCatalog` cannot replace the plugin

`OpenHouseCatalog` implements `SupportsGrantRevoke`, and the Spark 3.5 extensions —
`GRANT`/`REVOKE`, `SHOW GRANTS`, and `SET POLICY {RETENTION, SHARING, HISTORY, REPLICATION,
COLUMN TAG}` — reach it through `IcebergCatalogMapper`, which reflectively unwraps
`SparkCatalog.icebergCatalog` → `CachingCatalog.catalog` and uses the result as an
`OpenHouseCatalog`. Drop a plain `RESTCatalog` in that slot and every one of those statements
fails.

So "REST native" is gated on one of two choices:

- move those operations onto spec surface — they have no spec home, so they become `openhouse.*`
  properties plus `updateNamespaceProperties` / `set-properties` updates; or
- **keep a thin OpenHouse subclass of `RESTCatalog`** that adds `SupportsGrantRevoke` and
  delegates everything else.

The second is recommended. It is exactly the shape PR #44 already validated on the client side
for views — delegate the `ViewCatalog` SPI to an embedded `RESTCatalog`, built lazily so a
failure in the new path cannot break the old one — generalized from the view SPI to the whole
catalog. It also keeps the extensions working unchanged during the switchover, which is what
makes a cohort rollout possible at all.

### 6.2 The switch cannot be a table property

A table property lives in table metadata. Reading it requires having already loaded the table
through one of the two paths, so it cannot decide which path loads the table; and it does not
exist at all for `createTable`, `listTables`, or `listNamespaces`. Proposed shape instead:

| Layer | Mechanism | Role |
|---|---|---|
| Catalog | `spark.sql.catalog.openhouse.plumbing=rest\|plugin` | The actual router. Chosen once per session. |
| Server | `GET /v1/config` → `overrides` | Lets the cluster move cohorts without redeploying Spark. This is the rollout lever. |
| Table | `openhouse.plumbing` property | **Assertion, not router.** The client refuses to commit over a path the table is pinned away from; the server rejects a REST commit to a plugin-pinned table. Per-table safety without per-table routing. |

`/v1/config` `overrides` is the spec-sanctioned place for server-driven client configuration and
is already being emitted, so the rollout lever costs almost nothing once the catalog-level router
exists.

### 6.3 Dual-path equivalence

While both paths are live, the same table must be readable and writable through both. That wants
a harness that runs the same operation sequence through the plugin and through REST and asserts
identical resulting `TableMetadata` — the counterpart to PR #34's conformance module, aimed at
*agreement between the two paths* rather than at spec conformance.

### 6.4 Client Iceberg versions — the LinkedIn fork question

The server pins `com.linkedin.iceberg:1.5.2.17` (and `1.2.0.20` for the Spark 3.1 line). PR #34
only got a stock Iceberg 1.11 client and the 1.5.2-based server into one JVM by relocating the
pinned classes out of `org.apache.iceberg` in the test-fixtures uber jar, and by compiling that
module at Java 17 while the rest of the repository stays Java 8. That is a test-harness trick; it
is not a production client story.

For real clients the prerequisite is a decision: **what is the minimum client Iceberg version
that gets REST?** `RESTCatalog` behaviour that matters here moved after 1.5 — `/v1/config`
endpoint advertisement is a ≥1.6 concept, and the list-pagination behaviour that produces the
empty-`pageToken` defect changed at 1.6. Anything below the chosen floor needs the missing
pieces backported into the LinkedIn fork, or stays on the plugin.

The pragmatic answer is almost certainly that **Spark 3.1 / Iceberg 1.2 never goes REST** — it
keeps the plugin for its whole remaining life. Saying so explicitly removes a large amount of
backporting from the critical path, and it is the single scoping decision that most reduces the
size of this programme.

## 7. Proposed sequence

The phases below refine the four-step sketch. One correction to it: views are *already*
spec-shaped on the wire as of #44, rather than bespoke — which is strictly better, and removes a
migration that the original sequencing assumed.

**Phase 0 — foundations, invisible to every existing client.** No switch, no new wire surface.

- Namespaces as a first-class HTS entity, depth-1 behaviour byte-identical to today (§2.2)
- `NamespaceUtil` becomes a real seam: call sites stop assuming depth 1, cap stays at 1 (§2.1)
- Metadata-update applier + requirement checker, landed *underneath the current API* (§3, steps 1–2)
- Facade defect fixes (§4)
- Views persistence backend + `ViewCatalogTests` (§5)

Most of the total work is here, and none of it needs a rollout plan.

**Phase 1 — REST write surface, dark.** `createTable`, `commitTable`, `dropTable`,
`renameTable`, transactions, and the namespace endpoints, all on Phase-0 foundations. Progress is
measured by deleting `@Disabled` entries from PR #34's module. Still gated by
`cluster.tables.iceberg-rest.enabled`.

**Phase 2 — the switch.** Thin OH subclass of `RESTCatalog` carrying `SupportsGrantRevoke`
(§6.1); catalog-level router; `/v1/config` `overrides` as the cohort lever; table-property pin as
an assertion; dual-path equivalence harness (§6.3). Roll out read-only first, then writes.

**Phase 3 — multi-level namespaces on.** Lift `MAX_NAMESPACE_DEPTH`, with the simultaneity
invariant of §2.1 under test. Deliberately *after* Phase 1, because the encoding must be settled
before REST writes create nested namespaces — but the decision itself is made in Phase 0.

**Phase 4 — REST-native integrations.** New engines use the REST catalog directly. The plugin
stays indefinitely for Spark 3.1 / Iceberg 1.2 and for anything still relying on the SQL
extensions.

### Dependency graph

```
namespaces-as-entity ──┬─> multi-level namespaces ──> (Phase 3)
                       │
                       └─> namespace REST endpoints ─┐
                                                     ├─> the switch ──> REST-native
metadata-update applier ──> REST write endpoints ────┘
                                                     │
client shim (SupportsGrantRevoke over RESTCatalog) ──┘

facade defects ──> conformance harness progresses   (independent)
views backend ──> view conformance                  (independent)
```

### Decisions needed before Phase 1 ends

1. Namespace encoding — dot-join or `%1F` — and the identifier charset that goes with it. One-way
   door; must precede the first REST-created nested namespace.
2. Whether namespace privileges inherit down the tree.
3. Whether `policies` and `tableType` are settable over REST in v1, or read-only there.
4. Minimum client Iceberg version eligible for REST, and confirmation that Spark 3.1 / Iceberg
   1.2 stays on the plugin permanently.
5. `dropNamespace` on a populated namespace: error or cascade.
6. Backfill or lazy-materialize existing implicit databases.
