# The Database abstraction: API boundary design

**Conclusion first.** Store databases as their own House Tables entity — a new `database_row`
table with its own repository, service and `/hts/databases` routes — and put a **three-mode
resolver** in front of it in the Tables Service, backed by a **static write-through cache** that
holds every database in memory. Population is by an **idempotent registrar** with two callers:
populate-on-write (the floor, no completeness guarantee) and an explicit backfill (the ceiling,
with a verification marker that `trust` mode refuses to start without). The migration runs through
nine independently safe states, each behind its own flag, and the derived path stays compiled and
reachable until the last one. Deciding criterion: it is the only option that satisfies "no
behaviour change through the migration" — with one named, measured exemption (§2.2 M3) — **and**
collapses `GET /v1/databases` from a `SELECT DISTINCT` scan of `user_table_row` to a cache read,
and it is the only one that can represent an empty database, the thing the whole Iceberg REST
namespace surface is blocked on.

This document is an **API boundary design**. It specifies contracts, modes, counters, states and
guarantees. It does not specify implementations, and it does not design migration tooling; the
migration *state plan* (§5.9) is in scope because a state a tool cannot roll back is a design
defect, not a tooling defect.

**Companions:** [rest-support-sequencing.md](rest-support-sequencing.md) §2.2 (why this block is
first) · [views-execution-checklist.md](views-execution-checklist.md) (the `entity_type`
discriminator stack this design builds on) · a sibling agent owns the multi-level namespace design;
§5.7 is the seam where the two meet and is deliberately one-sided.

---

## 1. Problem statement

**Databases do not exist.** `DatabasesServiceImpl.getAllDatabases()` asks for *every table primary
key in the cluster* — `openHouseInternalRepository.findAllIds()` — and `distinct()`s the
`databaseId` out of the result in Java. What comes back is not table keys, and this matters for the
cost argument below: House Tables answers the unfiltered query with `SELECT DISTINCT database_id`,
so the wire already carries one row per database and the Java `distinct()` is already redundant.
The call chain is:

```
GET /v1/databases
  → DatabasesServiceImpl.getAllDatabases()                    services/tables/…/services/DatabasesServiceImpl.java:32
  → OpenHouseInternalRepositoryImpl.findAllIds()              …/repository/impl/OpenHouseInternalRepositoryImpl.java:882
  → OpenHouseInternalCatalog.listTables(Namespace.empty())    iceberg/openhouse/internalcatalog/…/OpenHouseInternalCatalog.java:102
  → HouseTableRepositoryImpl.findAll()                        …/repository/HouseTableRepositoryImpl.java:269
  → GET /hts/tables/query with no filters                     services/housetables/…/controller/UserHouseTablesController.java
  → UserTablesServiceImpl.getAllUserTables → listDatabases()  the isListDatabases arm: every field null
  → SELECT DISTINCT databaseId FROM UserTableRow              …/repository/impl/jdbc/UserTableHtsJdbcRepository.java:42
                                                              one UserTableDto per DATABASE on the wire
```

`OpenHouseInternalCatalog.listTables` marks its own empty-namespace arm as an anti-pattern in a
`TODO`. It is: it borrows the *table* vocabulary to ask a *database* question. HTS hands back one
`UserTableDto` per database, mapped through `houseTableMapper::toHouseTableWithDatabaseId` with
`@BeanMapping(ignoreByDefault = true)` so every field but `databaseId` is dropped; the catalog then
fabricates a table identity for each one
(`TableIdentifier.of(houseTable.getDatabaseId(), "Unused")`) purely so the answer can travel in a
type that has no business carrying it. The list is the right length. Nothing else about it is
right.

Six consequences, in the order they hurt:

1. **An empty database cannot be represented.** A database exists exactly while it has at least one
   live table. This blocks `createNamespace`, `dropNamespace`, `loadNamespaceMetadata` and
   `updateNamespaceProperties` — the 19 disabled reference tests in
   [#34](https://github.com/mkuchenbecker/openhouse/pull/34)'s `CatalogTests` subclass — and it is
   why that suite runs with `requiresNamespaceCreate()` and `supportsNamespaceProperties()` both
   returning `false`.
2. **Namespace properties have nowhere to live.** There is no property map on a database at all.
3. **It blocks multi-level namespaces in practice**, not only on paper: an intermediate namespace
   `a` containing only namespace `a.b` and no tables has no representation.
4. **Every `/v1/databases` call costs a `DISTINCT` scan of `user_table_row`.** Not a million rows
   on the wire — HTS collapses the set in SQL, so wire and heap are O(databases). The cost is the
   scan being collapsed: over every row of the busiest table in the schema, on a resource every
   other OpenHouse operation contends for. There is no index that answers "which databases exist";
   there is only a table to scan. The maintenance scheduler pays it on every run
   (`TablesClient.getDatabases`), for every job type, from every replica.
5. **Database-scoped state has a model but no storage.** `DatabaseMetadata.jobExecutionProperties`
   exists, `DatabaseMetadata.isMaintenanceJobDisabled(jobType)` reads it — and
   `TablesClient.getDatabaseMetadataList()` builds every `DatabaseMetadata` with the map left at its
   empty default. The per-database maintenance switch is unreachable code today.
6. **Database-scoped decisions are scattered across seven mechanisms** with no common home: a glob
   table in MySQL, a cluster-level regex, a storage path convention, an external OPA document, a
   CLI regex, a table-property override, and an identifier charset applied at a dozen boundaries
   with no owner. §5.2 inventories them — and finds that three of the seven do not belong on a
   database record at all, which is itself a result.

**What is already built, and is not this document's work.** The `entity_type` discriminator stack
ported in [#45](https://github.com/mkuchenbecker/openhouse/pull/45) →
[#47](https://github.com/mkuchenbecker/openhouse/pull/47) and merged into
[#44](https://github.com/mkuchenbecker/openhouse/pull/44) establishes the *pattern* a second catalog
entity follows — a nullable discriminator column with an out-of-band DDL record, a pinned collation,
typed repository predicates, typed deletes that refuse to cross types, a neutral collision-detection
read, a typed put that supplies its own type, and a `PutResult` that names create-vs-replace. It
does **not** establish a database entity: `EntityType` has exactly two constants, `TABLE` and
`VIEW`. §"Already built" in the covering report enumerates the files.

**Merge dependency, stated once and true everywhere below.** None of that stack is on `main`.
`services/housetables/ddl/`, `EntityType`, `PutResult`, `UserViewQuery` and `ViewMetricsConstant`
exist only on the #45–#47 → #44 branch stack. Every reference to them in this document is a
reference to unmerged code, and this design cannot begin until that stack lands.

---

## 2. Requirements

### 2.1 What a Database *is* in OpenHouse

A **Database** is the single-level namespace that OpenHouse tables and views live in, and it is the
unit of seven separate things that are currently held together only by the string being spelled the
same way in seven places:

| Role | Where it is exercised today |
|---|---|
| **Naming** — level 1 of a table identifier, `database.table` | `NamespaceUtil.MAX_NAMESPACE_DEPTH = 1`; `tableIdentifier.namespace().toString()` at every call site |
| **Authorization subject** — ACLs attach to a database, and a table inherits from its database | `DatabasesServiceImpl.checkDatabasePrivilege`; OPA `data.user_roles[input.db_id]` (`infra/recipes/docker-compose/common/opa/policy.rego`) |
| **Storage layout** — a path component of every table location | `BaseStorage.allocateTableLocation` → `{endpoint}{rootPrefix}/{databaseId}/{tableId}-{uuid}`; round-tripped by `Storage.isPathValid` |
| **Storage selection** — which backing store a new table lands on | `RegexStorageSelector.selectStorage(db, table)` matches the regex against `db + "." + table` |
| **Feature-rollout cohort** — which tables a server-side feature is on for | `table_toggle_rule.database_pattern` + `WildcardTableToggleRuleMatcher` |
| **Maintenance scope** — which tables a job walks, and which whole-database jobs run | `DatabaseTableFilter` / `--databaseFilter`; `DatabaseOperationTask`; `TableDirectoryDeletionTask` |
| **Reporting and ownership boundary** — what `/v0,/v1,/v2 /databases` enumerate | `DatabasesController`; `GetAllDatabasesResponseBody` |

**What a Database must guarantee.**

- **G1 — Durable existence.** A database exists because a record says so, not because a table
  happens to reference it. Its lifetime is independent of its table population.
- **G2 — Stable identity.** The `databaseId` is the key. It is case-insensitively unique (matching
  the existing `findByDatabaseIdIgnoreCaseAndTableIdIgnoreCase` contract on tables), and its bytes
  are unchanged for every database that exists today.
- **G3 — Complete enumeration.** Listing databases returns every database, and every database that
  contains a live table is in that list. A table can never reference a database that is not listed.
- **G4 — Bounded properties.** A database carries a `Map<String,String>` of properties with a
  reserved `openhouse.` prefix that users cannot write, mirroring `BasePreservedKeyChecker`. The map
  is bounded in entries and bytes — see §5.8.4; without a bound the in-memory cache has no memory
  bound and the recommended option collapses.
- **G5 — Authorization continuity.** The database remains the ACL subject. Making databases stored
  must not change any access decision.
- **G6 — Path stability.** A table's storage prefix is frozen at create time by the path already
  written into `user_table_row.metadata_location` and the `storage_type` recorded beside it, and is
  never recomputed from live cluster config. This is a property of the **table**, not of the
  database. It cannot be a property of the database: `BaseStorage.allocateTableLocation` takes its
  prefix from `getClient().getRootPrefix()` inside itself, per storage *type*, and the module graph
  puts a database record out of its reach (§5.2 #4).

**Who owns it.** The **Tables Service** owns the Database *API contract* (`DatabasesService`,
`/v0,/v1,/v2 /databases`, and — via Workstream 1 — `/v1/{prefix}/namespaces`). The **House Tables
Service** owns the *record* (`database_row`, `/hts/databases`). Neither owns the other's vocabulary:
HTS speaks rows and keys, Tables speaks `DatabaseDto` and Iceberg `Namespace`.

**What depends on it.** `DatabasesController` (two handlers across three route strings — `/v0/databases`
and `/v1/databases` share one, `/v2/databases` is the paged second); the REST namespace endpoints
Workstream 1 is designing; `TablesClient.getDatabases()` and everything in `apps/spark`'s scheduler
built on it; `OpaAuthorizationHandler` for every table and database access decision;
`BaseStorage.allocateTableLocation` for every table create; `OpenHouseInternalCatalog.listTables`
for the empty-namespace arm; and the 19 disabled conformance tests.

### 2.2 Must

- **M1 — Represent an empty database.** A database with zero tables is listable, loadable, and
  droppable. (Without this the whole namespace block stays blocked.)
- **M2 — Carry a bounded property map** with a reserved `openhouse.` space.
- **M3 — No behaviour change through the migration, with exactly one named exemption.** Every
  database, client and job observes byte-identical responses in every state up to and including
  fleet-wide `trust`, except this: from the first state that *serves* the stored answer (S6), a
  database whose last table was dropped after S2 stays listed. That is not the soft-delete corner
  case — `ensure(db, WRITE_PATH)` runs from S2, `delete(databaseId)` is S8-only, and §5.8 has no
  de-registration caller, so hard drop, soft delete and purge all reach it. The exemption is
  bounded, **measured before it is taken** (S5's `extra_in_stored_empty` membership is its exact
  size), gated (§5.9.2 S6's entry gate), classified, and ratifiable (§5.11 Q3). All other new
  behaviour — user-created empty databases, properties, drop — arrives in S8, separately flagged.
- **M4 — Treat MySQL read volume as a budget, not an afterthought.** State a per-replica ceiling and
  meet it. `GET /v1/databases` must stop costing a `SELECT DISTINCT` scan of `user_table_row` on
  every call. (The wire cost is already O(databases); the scan is what has to go — §1, §5.10.)
- **M5 — Controllable, observable rollout.** A resolver with `original` / `compare` / `trust`, where
  `compare` reads the new path, compares, **discards the new result**, and emits divergence signal
  that identifies *where* the two disagree. As stated, "the new path" means the database; §5.5.1
  substitutes the cache for `listDatabases` and routes that deviation to ratification (§5.11 Q2)
  rather than quietly redefining the requirement.
- **M6 — Population with a stated completeness guarantee.** Explicit backfill carries one;
  populate-on-write does not; both exist and the design says which applies when. Cold databases are
  covered by backfill or by a deliberate poke.
- **M7 — 100k databases.** The static write-through cache must have a *provable* memory ceiling at
  100k × 10 KB ≈ 1 GB, and a defined, safe behaviour when the ceiling is exceeded.
- **M8 — Independently safe migration states**, each with: what is true, what can go wrong, how it is
  detected, how it is rolled back. Transitions are flag-controlled and reversible.
- **M9 — Every crossed boundary is an explicit contract**: the resolver seam, the cache seam, the HTS
  repository seam, the `DatabasesService` seam, and the REST namespace surface.

### 2.3 Should

- **SH1 — Reuse the `entity_type` stack's shapes** (typed put, `PutResult`, service-owned query value
  object, out-of-band DDL record, pinned collation) so a reviewer of #45–#47 recognises this.
- **SH2 — Give database-scoped conditionals a home, or say they have none.** Each conditional in
  §5.2 is classified as a table property, a database entry, or neither — and each database entry
  names the field it occupies, so the abstraction is defined by what it must carry rather than by
  what it is hoped to absorb.
- **SH3 — Delete the `listTables(Namespace.empty())` anti-pattern arm** once `trust` is fleet-wide.
- **SH4 — Make the maintenance switch reachable.** `DatabaseMetadata.jobExecutionProperties` gets a
  source.
- **SH5 — Keep the derived path compiled and flag-reachable** until the final state, so rollback is a
  configuration change and never a deploy — a `cluster.yaml` remount plus a rolling restart, not a
  new artifact (§5.5).

### 2.4 Won't (this phase)

- **W1 — Won't implement anything.** This is a boundary design; the implementation design is the
  next phase. Basis: the owner's phase split.
- **W2 — Won't design the backfill *tool*.** The backfill *mechanism contract* — idempotency,
  resumability, watermark, completion marker, verification predicate — is specified in §5.8.3 because
  `trust` mode's precondition depends on it. How the job is packaged, scheduled and operated is
  downstream. Basis: the mechanism is what `trust` gates on; the packaging is not.
- **W3 — Won't lift `MAX_NAMESPACE_DEPTH`.** Multi-level namespaces are Workstream 1 and Phase 3 of
  the sequencing plan. This design is depth-1 and must stay byte-identical at depth 1 so that a
  prefix-preserving encoding lands on top of it for free. Basis: rest-support-sequencing §2.1.
- **W4 — Won't widen the identifier charset.** `^[a-zA-Z0-9_]+$` stays. It is the same edit and the
  same review as the namespace separator, which Workstream 1 owns. Consequence, corrected: the
  `BLOCKED_IDENTIFIER_CHARSET` blocker is an **error-mapping** defect, not a charset defect. The
  harness's own reason string says the non-existing namespace is "rejected with 400
  (IllegalArgumentException) instead of 404 (NoSuchTableException)", and Iceberg supplies
  `supportsNamesWithDot()` / `supportsNamesWithSlashes()` so a server can decline the wider charset
  and still conform. It is retired by Workstream 1's §5.2, not by a charset widening. Basis:
  one-way door, wrong owner.
- **W5 — retired; the decision is made.** `dropNamespace` / `dropDatabase` on a populated database
  fails with `409` / `NamespaceNotEmptyException`. There is no cascade. This was listed as deferred
  while §5.3's `delete(databaseId)` row already committed to it and Workstream 1 had decided the
  same way and holds the conformance test — an internal contradiction, not a deferral. Recorded as
  a decision so it is not relitigated. Basis: both designs converged; the state that owns it (S8)
  is unchanged.
- **W6 — Won't move ACL storage.** OPA keeps `data.user_roles[db_id]`. The database record becomes
  the *subject* that is checkable for existence; it does not become the ACL store. Basis: G5, and
  moving the ACL store is a security review of its own.
- **W7 — Won't emit database-scoped audit events.** Table audit (`TableAuditAspect`) has an
  allowlist and byte caps; a database-scoped equivalent is a separate design. Basis: no consumer
  yet.

### 2.5 Out of scope

Iceberg-native commits (sequencing §3); the catalog-level plumbing switch (§6); views persistence
(§5); the facade defects (§4) except where a database identifier is the cause; the client shim.
None of these are prerequisites of, or blocked by, this design.

---

## 3. Options, with the recommendation

Columns are the must-requirements from §2.2. ✅ satisfied · ⚠️ partly, at a cost named in the notes
· ❌ not satisfiable by the option.

| # | Option | M1 empty db | M2 properties | M3 no behaviour change | M4 MySQL budget | M5 3-mode resolver | M6 backfill guarantee | M7 100k scale | M8 independently safe states | M9 contracts |
|---|---|---|---|---|---|---|---|---|---|---|
| A | **Status quo** — derive from `user_table_row` PKs | ❌ | ❌ | ✅ (trivially) | ❌ a `DISTINCT` scan of `user_table_row` per call | n/a | n/a | ⚠️ works; scan cost grows with tables | n/a — nothing to migrate | ❌ no seam |
| B | **Derive + memoise** the distinct set in the Tables Service | ❌ | ❌ | ⚠️ list becomes stale | ⚠️ one `DISTINCT` scan per refresh | ⚠️ nothing to compare against | n/a | ⚠️ refresh cost grows with tables | ⚠️ one flag, but staleness arrives with it — no state where the new path exists unobserved | ⚠️ cache seam only |
| C | **`entity_type = DATABASE` sentinel row** in `user_table_row` | ✅ | ❌ no property column | ❌ neutral reads and the soft-delete store change | ✅ | ✅ | ✅ | ✅ | ❌ **the sharpest separator** — a sentinel row is visible to every neutral read the instant it is written, so population *is* the behaviour change; there is no state between them | ⚠️ overloads the table seam |
| D | **New `database_row` HTS entity + resolver + static write-through cache** | ✅ | ✅ | ✅ one named exemption (M3) | ✅ O(1) steady state | ✅ | ✅ | ✅ bounded by G4 | ✅ nine states, each flag-reversible through S7 | ✅ five named contracts |
| E | New `database_row`, **no cache** — read MySQL per request | ✅ | ✅ | ✅ one named exemption (M3) | ⚠️ one 100k-row query per call, N replicas | ✅ | ✅ | ⚠️ list of 100k per call | ✅ D's plan minus S4 | ✅ |
| F | **Separate namespace service** | ✅ | ✅ | ⚠️ new hop in every table create | ⚠️ new datastore | ✅ | ✅ | ✅ | ⚠️ a new deployable is not rolled back by a flag | ✅ but a new deployable |

**Recommended: D.** The deciding criterion is **M3 against M1**, and **M8** is what makes the
verdict stick. D is the only option that both represents an empty database and leaves every
existing read path byte-identical, because the new rows live in a table no existing query touches —
which is also exactly why its population can be switched on a full state before anything reads it.
C fails on both counts at once. A `DATABASE` row in `user_table_row` is invisible to the *typed*
predicates (`TABLE_ROW_PREDICATE`, `VIEW_ROW_PREDICATE`) but visible to every **neutral** one:
`findAll()`, `findAllByFilters(...)`, `findAllDistinctDatabaseIds()`, and `getNeutralEntity` — the
collision-detection read the views stack added precisely so that two entity types cannot silently
share a key. So C has no state in which its rows exist and are not yet observed: the first row
written is the behaviour change. That is M8 failing, not merely M3, and M8 is the column that says
why no amount of care in C's rollout would recover it. C also has no column for a property map, and
`soft_deleted_user_table_row` carries no discriminator at all, so a database row routed there would
restore as a table.

E is D minus the cache. Its M4 penalty is real but narrower than it first looks: today's answer is
*already* one row per database on the wire (§1, §5.10), so E is equal to today on serialization and
better only on the scan, paying one 100k-row query per call per replica where D pays none. The
genuine cost — a query per call, per replica, per scheduler run, on a shared resource — is what
keeps M4 decisive; the "100k rows on the wire" argument does not. F is D plus an operational burden
nobody asked for. B cannot answer M1 at all, which is the point of the exercise.

Alternatives C, E and F are developed in Appendix A.

---

## 4. Sketch

```
                                    Tables Service (N replicas)
  ┌───────────────────────────────────────────────────────────────────────────────────┐
  │  DatabasesController          IcebergRestNamespaceController  [Workstream 1]      │
  │  /v0,/v1,/v2 /databases       /v1/{prefix}/namespaces…                            │
  │            │                            │                                         │
  │            └──────────┬─────────────────┘                                         │
  │                       ▼                                                           │
  │              DatabasesService                    ← §5.6 contract (widened, not    │
  │                       │                            reshaped: existing four methods│
  │                       ▼                            keep their signatures)         │
  │        ┌──── DatabaseResolver ────┐               ← §5.5 contract. THE SEAM.      │
  │        │   mode: original         │                 One flag. Three modes.        │
  │        │         compare          │                                               │
  │        │         trust            │                                               │
  │        └────┬──────────────┬──────┘                                               │
  │             │              │                                                      │
  │   ORIGINAL  │              │  STORED                                              │
  │             ▼              ▼                                                      │
  │   OpenHouseInternal   DatabaseCache             ← §5.4 contract. Static,          │
  │   Repository          (all databases,             write-through, bounded,         │
  │   .findAllIds()        in heap)                   watermark-refreshed.            │
  │        │                   │  ▲                                                   │
  │        │                   │  │ ensure()  ← DatabaseRegistrar: ONE idempotent     │
  │        │                   │  │             primitive, FOUR callers (§5.8)        │
  │        │                   ▼  │                                                   │
  │        │        HouseTableDatabaseRepository    ← §5.3 contract                   │
  └────────┼───────────────────┼──────────────────────────────────────────────────────┘
           │ GET /hts/tables/query (unfiltered)     │ GET/PUT/DELETE /hts/databases
           ▼                                        ▼
  ┌───────────────────────────────────────────────────────────────────────────────────┐
  │  House Tables Service                                                             │
  │    user_table_row  (database_id, table_id, …, entity_type)    ← untouched         │
  │    database_row    (database_id, properties, …)               ← NEW               │
  └───────────────────────────────────────────────────────────────────────────────────┘
```

Read it in one sentence: **one flag chooses which of two paths answers a database question; in
`compare` the second path runs but its answer is thrown away and its disagreement is counted; the
stored path never touches MySQL on the hot path because the whole database set is in heap.**

---

## 5. Details

### 5.1 Master checklist

Sub-checklists follow in the sections named. `[ ]` items are this design's obligations; items marked
**(next phase)** are named here so the boundary is complete, and are not this document's work.

- [x] **A. Establish what already exists.** §1 and the covering report. Net-new is the
      `database_row` entity, the registrar, the resolver, the cache, and the migration plan; reused
      is the discriminator stack's *shapes*, not its code.
- [ ] **B. Business requirements** — §2.1. Seven roles, six guarantees, one owner per layer.
- [ ] **C. Conditional inventory** — §5.2. Sub-checklist there. Every item classified **table
      property** / **database entry** / **neither**, with a reason — and every "database entry"
      naming the field it lives in, a column or a reserved `openhouse.` key. A classification with
      no field is not a classification.
- [ ] **D. Contracts** — one section each, with preconditions, postconditions, errors, idempotency,
      consistency, and an explicit "may not assume" clause.
  - [ ] D1 HTS repository seam — §5.3
  - [ ] D2 Cache seam — §5.4
  - [ ] D3 Resolver seam — §5.5
  - [ ] D4 `DatabasesService` seam — §5.6
  - [ ] D5 REST namespace surface (Workstream 1 boundary) — §5.7
- [ ] **E. Population** — §5.8. Registrar primitive; explicit backfill with a completion guarantee;
      populate-on-write without one; the cold-database poke. Sub-checklist there.
- [ ] **F. Scale and MySQL budget** — §5.8.4 and §5.10. Property bound (G4) → memory bound (M7) →
      overflow behaviour. Read ceiling per replica.
- [ ] **G. Migration state plan** — §5.9. Nine states, each with truth/hazard/detection/rollback.
      Sub-checklist there.
- [ ] **H. Divergence observability** — §5.5.3. Counter taxonomy with a denominator, bounded
      cardinality, identifiers in sampled logs.
- [ ] **I. Pre-migration audits** — §5.9.1. Three queries that must be run before S1, because each
      can make the migration wrong in a way no state transition can fix.
- [ ] **J. Rollback and cohort mechanics** — §5.5 and §5.9. What a flag change actually costs (a
      `cluster.yaml` remount plus a rolling restart, bounded by *T*), and how S6's cohort is
      expressed given that `cluster.yaml` is one ConfigMap per Deployment. **This design's
      obligation**, not the next phase's: the nine-state plan's safety argument rests on it.
- [ ] **K. Open questions** — §5.11. Two deliberate deviations and one exemption, each with its
      alternative priced, routed to the owner for ratification.
- [ ] **(next phase)** Implementation design: JPA mappings, cache data structure, refresh scheduling,
      HTTP client wiring, test plan.
- [ ] **(next phase)** Backfill tooling: packaging, scheduling, operator runbook.

### 5.2 Inventory of database conditionals and special-cased databases

This is a deliverable, not background. Each row states the mechanism, where it lives, and the
model — **table property**, **database entry**, or **neither** — with the reason. "Database entry"
means the fact belongs on the `database_row` record, and the row **must name the field it lives
in** — a column in §5.3, or a reserved `openhouse.` property key with its shape. A classification
with nowhere to live is a wish, not a model. "Table property" means it belongs in table metadata
and stays there.

| # | Mechanism | Location | Model | Why |
|---|---|---|---|---|
| 1 | **Feature-toggle rules globbed by database name.** `table_toggle_rule(feature, database_pattern, table_pattern)`; `AntPathMatcher` match on both; presence ⇒ ACTIVE | `services/housetables/…/model/TableToggleRule.java`; `…/services/WildcardTableToggleRuleMatcher.java`; `…/services/ToggleStatusesServiceImpl.java` | **Neither** | The rule's subject is not a database. `WildcardTableToggleRuleMatcher.matches` requires **both** `tablePattern` and `databasePattern` to match, so an activation is a fact about a *(database, table-pattern)* pair. Resolving rule `(f, db=db1, table=t_*)` onto the `db1` row would activate `f` for `db1.other`; storing the per-table resolution instead makes the entry O(tables) and breaks the 8 KiB bound M7 rests on (§5.8.4). The fix `TableFeatureToggle`'s own `TODO` asks for — "evaluate rules from a locally replicated snapshot so a table read no longer blocks on HouseTables" — is a **toggle-service** change: replicate the rule table, not its resolution. Listed so that a reader does not reach for the database record to fix it. |
| 2 | **Per-table self-service override** of #1: `<featureId>.enabled` table property, unparseable ⇒ fails closed | `services/tables/…/toggle/TableFeatureToggle.java` (`ENABLED_PROPERTY_SUFFIX`) | **Table property** — already correct, keep | This is the exact two-level shape the whole inventory should converge on: a database-scoped decision with a per-table opt-out. It is also the precedent for why authorization-bearing features must use `isFeatureActivated` and not the override form. |
| 3 | **Storage selection by regex over `db.table`.** `RegexStorageSelector` compiles one cluster-level regex; a match selects a named storage type, else cluster default | `cluster/storage/…/selector/impl/RegexStorageSelector.java`; configured under `cluster.storages.storage-selector` | **Database entry** — reserved property key **`openhouse.storage.type`**, one `StorageType` name, ≤ 32 bytes; **table property** (realisation) | Editing the cluster regex silently changes where *future* tables in an existing database land, splitting one database across two stores with no record of why. The chosen store must be durable on the database, and `openhouse.storage.type` is the field it lives in: `RegexStorageSelector` becomes the fallback that *populates* it on first use, not the standing source of truth. The already-persisted per-table `user_table_row.storage_type` stays as the frozen realisation — a table's store must never be recomputed. |
| 4 | **Database as a storage path component.** `{endpoint}{rootPrefix}/{databaseId}/{tableId}-{uuid}`, round-tripped by `Storage.isPathValid` | `cluster/storage/…/BaseStorage.java:allocateTableLocation`, `Storage.java:91` | **Neither** — there is no reachable seam | G6 is met without a database entry, and cannot be met with one. `rootPrefix` comes from `getClient().getRootPrefix()` *inside* `BaseStorage.allocateTableLocation`, per storage **type**. For a stored per-database prefix to reach it, `cluster:storage` would have to read a database record — and `cluster/storage/build.gradle` declares only `api project(':cluster:configs')` and `api project(':iceberg:azure')`, so reaching `services:tables` or `services:housetables` is an upward reference. The only alternative changes `allocateTableLocation`'s signature, which Workstream 1 has committed to leaving unchanged and which three callers depend on: `TableUUIDGenerator`'s `Paths.get(rootPrefix, databaseId)` assertion, `apps/spark`'s orphan-directory deletion, and `Storage.isPathValid`'s round-trip. G6 is therefore restated (§2.1): **the prefix is frozen per table by the path already written plus `user_table_row.storage_type`** — which #5 already implies. Durable per-database prefixes, if ever genuinely required, are a joint Workstream 1 / Workstream 2 decision with a named owner, not a row on this table. |
| 5 | **Replica-table location pin.** `openhouse.replicaTableLocationId` overrides the `{tableId}-{uuid}` leaf | `BaseStorage.calculateTableLocationId` | **Table property** — already correct, keep | Per-table by construction; a replica's source table id is a fact about that table. Listed because it is the only existing property that participates in path construction, and it constrains #4: the database entry owns the *prefix*, never the leaf. |
| 6 | **ACLs keyed by database, in an external document.** OPA `data.user_roles[input.db_id]`, with a table arm nested under it; `DatabaseDto.builder().databaseId(id).build()` is synthesised on demand with no existence check | `infra/recipes/docker-compose/common/opa/policy.rego`; `services/tables/…/services/DatabasesServiceImpl.java` (`checkDatabasePrivilege`, `getDatabaseAclPolicies`) | **Database entry** (the subject), **not** the ACL store (W6) | `getDatabaseAclPolicies("does_not_exist")` returns `[]` and `updateDatabaseAclPolicies` on a non-existent database succeeds — because there is nothing to check existence against. Once databases are stored, existence *becomes* checkable, which is a behaviour change; M3 forbids taking it before S8. Recorded as a deliberate non-change with a state that owns it. |
| 7 | **Maintenance scoping by database regex.** `--databaseFilter` (default `.*`) → `DatabaseTableFilter.applyDatabaseName`, applied in six places in `TablesClient` | `apps/spark/…/util/DatabaseTableFilter.java`; `…/scheduler/JobsScheduler.java:511,724`; `…/client/TablesClient.java` | **Database entry** — the same reserved key space as #8: **`openhouse.maintenance.disabled`** and **`openhouse.maintenance.<jobType>.disabled`**, string `"true"` / `"false"` | An operator-supplied regex on a job invocation is the least durable place a per-database policy can live: it is invisible to the database's owner, unversioned, and different for every job type. The durable form is #8's map, read through `GetDatabaseResponseBody` into `DatabaseMetadata.jobExecutionProperties`. `--databaseFilter` survives as an operator **override** for a single run, not as the policy. |
| 8 | **Per-database maintenance disable — a model with no storage.** `DatabaseMetadata.isMaintenanceJobDisabled(jobType)` reads `jobExecutionProperties["disabled"]` and `"{jobType}.disabled"`; `TablesClient.getDatabaseMetadataList()` constructs every `DatabaseMetadata` without ever setting the map | `apps/spark/…/util/DatabaseMetadata.java`; `…/client/TablesClient.java:321-326`; consumed by `DatabaseOperationTask.shouldRun()` | **Database entry** — the `properties` map of §5.3, under the reserved keys named in #7; and it is the first consumer of database properties | The dead-code finding. The per-database switch is written, wired into `shouldRun()`, and can never return `true`, because `getDatabaseMetadataList()` builds every `DatabaseMetadata` with the map at its empty default. Its **sibling** on `TableMetadata` is tested (`TableMetadataTest`); this one is not, which is why the deadness went unnoticed. `GetDatabaseResponseBody` gaining a properties map turns three existing classes on at once. This is the strongest argument that the abstraction is *wanted*, not merely spec-driven. |
| 9 | **Reserved property namespace.** `openhouse.` prefix + the `policies` key are unwritable | `services/tables/…/repository/impl/BasePreservedKeyChecker.java`; `HouseTableSerdeUtils.IS_OH_PREFIXED` | **Table property** (existing) → **mirrored as a database-property rule** | G4. `updateNamespaceProperties` is a user-facing write into the database property map. Without the mirrored rule a client can set `openhouse.databaseId` on a namespace and create a second, contradictory source of truth. The rule must exist *before* the property map is writable (S8), not after. |
| 10 | **Identifier charset, applied at every boundary.** One constant — `ValidatorConstants.ALPHA_NUM_UNDERSCORE_REGEX` — referenced at roughly ten sites | `services/common/…/api/validator/ValidatorConstants.java`; applied at `OpenHouseDatabasesApiValidator:60`, `OpenHouseTablesApiValidator:529`, `OpenHouseUserTableHtsApiValidator:27,33,117`, and the `@Pattern` annotations on `UserTable`, `UserTableKey`, `SoftDeletedUserTableKey`, `TableToggleStatusKey` | **Database entry** — but only the key column's **length and collation** | There is no duplicated *rule*. There is one constant and repeated **application**, which §5.3 then endorses and requires ("HTS may not assume that the caller has validated anything"). Removing the repetition is not this design's work and would be a regression. The identifier rule itself belongs to Workstream 1's `NamespaceUtil` and nowhere else, because it is depth- and encoding-aware — charset per level, depth `1..max-depth`, encoded length ≤ 128 — and this design has declared (W3) that it must not know the encoding. A rule the storage entity cannot express in full does not belong there. What **is** the stored entity's to pin is the column: `VARCHAR(128)`, and a collation identical to `user_table_row.database_id`'s (§5.3, audited in §5.9.1). |
| 11 | **`clusterId` on every database response.** `databasesMapper.toDatabaseDto(dbId, clusterProperties.getClusterName())` | `…/services/DatabasesServiceImpl.java:35,48`; `DatabaseDto.clusterId` | **Neither** — a response projection | Deliberately *not* stored. It is a fact about the serving cluster, not about the database. Persisting it would create a second source of truth for cluster identity that goes stale on any cluster rename, and would make a database row cluster-specific for no gain. The resolver must project it identically in all three modes, and a mismatch is a divergence class (§5.5.3 `attribute_mismatch{field="clusterId"}`). |
| 12 | **Case folding asymmetry.** The derived path `distinct()`s in **Java** over exact strings (`DatabaseDto.equals`), while every HTS table lookup is `…IgnoreCase…` and the MySQL collation folds case | `DatabasesServiceImpl.getAllDatabases()`; `UserTableHtsJdbcRepository.findByDatabaseIdIgnoreCaseAndTableIdIgnoreCase` | **Database entry** (one case-insensitive key), and a **pre-migration audit** | If any two tables spell their database differently in case, today's `/v1/databases` lists **both spellings** and the stored path will list **one**. That is a real, pre-existing inconsistency that the migration will surface as a divergence. It must be audited before S1 (§5.9.1) because no state transition can repair it after the fact — it is a data question, not a code question. |
| 13 | **`ensure` has no de-registration counterpart, so a database outlives its last table — in every case, not just the soft-deleted one.** `ensure(db, WRITE_PATH)` runs from S2; `delete(databaseId)` is S8-only (§5.3); §5.8 names no caller that removes a row | §5.8; `SoftDeletedUserTableHtsJdbcRepository`; `services/housetables/ddl/0000__baseline.sql` | **Database entry** — the database outlives its tables (G1) | Today, dropping the last table in a database deletes the database. Under G1 it does not, and this is the **general** case: hard drop, soft delete and purge all reach it, so *every* database that loses its last table after S2 diverges permanently. Framing it as the soft-delete corner understates its membership, and three things follow from getting that right. **(a)** It is M3's one named exemption (§2.2), taken at S6, not at S8. **(b)** The backfill's verification predicate cannot be count equality — routine table drops make `derived_count == stored_count` unsatisfiable within a day of S2 — so §5.8.3 states it as a set difference against a named expected-extras set. **(c)** Downstream, `TablesClient.getDatabases()` (`:221,254`) will loop `searchTablesV1(dbName)` over table-less databases for nothing, and `getDatabaseMetadataList()` (`:321-326`) will hand them to `DatabaseOperationTask` and `TableDirectoryDeletionTask`; both are wasted work rather than incorrect work, and both are bounded by the exemption's measured size. Classified in `compare` as `extra_in_stored_empty` rather than an alarm. Naming it up front is what stops it being triaged as a bug at 3am. |
| 14 | **The empty-namespace anti-pattern arm.** `listTables(Namespace.empty())` returns one identifier per **database** — HTS has already collapsed the set — with the table id replaced by the literal `"Unused"`: the table vocabulary borrowed to carry a database answer | `iceberg/openhouse/internalcatalog/…/OpenHouseInternalCatalog.java:102-114` (its own `TODO` says so) | **Neither** — deleted by this design | It is a conditional *on the absence of* the database abstraction. Requirement SH3; removed at migration state S9. |
| 15 | **Empty-string `databaseId` as an "all databases" sentinel** on the HTS query seam | `HouseTableRepositoryImpl.findAllByDatabaseId` (`Strings.isNotEmpty` guard) | **Neither** — a query-vocabulary defect | Fixed by giving the new seam a typed query value object in the shape of `UserViewQuery`, where "every database" is a distinct factory method rather than a magic empty string (§5.3). |
| 16 | **Namespace depth cap.** `MAX_NAMESPACE_DEPTH = 1`; `isTableNamespace` doubles as the metadata-table discriminator | `services/common/…/utils/NamespaceUtil.java` | **Neither** — Workstream 1's seam | Noted so the boundary is complete. This design must not change it and must not depend on it changing (W3). |
| 17 | **Case-sensitive `databaseId` equality on the table-write path, case-insensitive everywhere else.** `OpenHouseTablesApiValidator:123,258` compare the path `databaseId` against the request body's with `.equals` and reject a case difference with `400`; `:325`, `OpenHouseUserTableHtsApiValidator:88,89,96`, `OpenHouseInternalCatalog:218` and `OpenHouseInternalTableOperations:390-394` all use `equalsIgnoreCase` | `services/tables/…/api/validator/impl/OpenHouseTablesApiValidator.java`; `services/housetables/…/api/validator/impl/OpenHouseUserTableHtsApiValidator.java`; `iceberg/…/OpenHouseInternalCatalog.java`; `…/OpenHouseInternalTableOperations.java` | **Neither** — a live constraint on G2 | Distinct from #12, which is a *divergence between two listings*; this is a `400` a client sees today. G2 says the key is case-insensitively unique and the stored path will honour that under the pinned collation — but these two `.equals` sites will still reject `PUT /v1/databases/DB/tables/t` carrying `databaseId: db`, whatever the column's collation says. Nothing in this design changes them, and nothing in this design may assume they are gone. Recorded so that the stored key's case semantics are not mistaken for the API's. |

**Inventory sub-checklist**

- [x] Grep database-name comparisons (`databaseId.equals/matches/startsWith/contains`) → #10 and #17; there are **no** hardcoded database-*name* comparisons in main source. Recorded as a positive finding: there is no special-cased database *by name* anywhere in the tree.
- [x] Grep allow/deny/allowlist/blocklist → the only allowlist is `cluster.iceberg.tables.audit.table-properties-allowlist` (property keys, not databases). Recorded as not-applicable.
- [x] Grep database patterns / globs / regexes → #1, #3, #7.
- [x] Grep config keyed by database (`*.yaml`, `*.properties`) → none; every `database:` key in cluster config is the *JDBC* database of HTS itself, not an OpenHouse database. Recorded so a reviewer does not re-run the search.
- [x] Grep per-database storage, path and selection → #3, #4, #5.
- [x] Grep authorization by database → #6.
- [x] Grep job/maintenance scoping by database → #7, #8.
- [x] Grep feature toggles by database → #1, #2.
- [x] Read the derived read path end to end for asymmetries → #11, #12, #13, #14, #15.
- [ ] **(owner)** Confirm no LinkedIn-internal deployment carries a database-keyed config that is absent from this repository. The inventory is complete for the open-source tree; a fork-only override would change #3 and #7.

### 5.3 Contract — the House Tables repository seam

**Owner:** House Tables Service. **Consumer:** Tables Service, through
`HouseTableDatabaseRepository` (the counterpart of `HouseTableRepositoryImpl`).

**Record.** A new table `database_row`, recorded as an out-of-band DDL file in
`services/housetables/ddl/` in the established style (`0000__baseline.sql`,
`0001__add_entity_type…`, `0002__pin_entity_type_collation.sql`) — the service does not execute it;
it is an operations record and a reconstruction snapshot. That directory does not exist on `main`;
it arrives with the #45–#47 → #44 stack, which is this design's merge dependency (§1). The record's
fields, as a contract rather than a schema:

| Field | Contract |
|---|---|
| `databaseId` | The primary key. `VARCHAR(128)`, with **charset and collation identical to `user_table_row.database_id`'s as measured in production** — and pinned in the same DDL that creates the column, for the reason `0002__pin_entity_type_collation.sql` documents: an unpinned column lets SQL and Java disagree about which spellings are the same value. This document deliberately does **not** name the collation value, because it is not knowable from the repository: `0000__baseline.sql` declares `database_id VARCHAR(128) NOT NULL` with no collation and its own header warns that "a derived definition cannot capture … character set or collation", and `0002` pins only `entity_type`. §5.9.1's third pre-flight audit measures it. The binding requirement is **equality**, not a particular value: pin `database_row.database_id` to `utf8mb4_0900_as_ci` while `user_table_row.database_id` sits on the MySQL 8 server default `utf8mb4_0900_ai_ci` and an accented spelling is one database in one store and two in the other — reopening the exact defect class `0002` was written to close. Case-insensitively unique under whatever that collation turns out to be. |
| `properties` | The bounded map of G4, and the field the "database entry" rows of §5.2 live in: `openhouse.storage.type` (#3), `openhouse.maintenance.disabled` and `openhouse.maintenance.<jobType>.disabled` (#7, #8). Reserved `openhouse.` keys are server-written and user-unwritable (#9). Serialized representation is an implementation-phase decision; the *bound* is not — see §5.8.4. |
| `version` | Optimistic concurrency, matching `UserTableRow.@Version`. Every write is compare-and-set; a lost update is a caller-visible conflict, never a silent overwrite. |
| `creationTime`, `lastModifiedTime` | `lastModifiedTime` is **load-bearing**, not decorative: it is the cache's refresh watermark (§5.4). It must be monotonic per row and indexed. |
| `origin` | How the row came to exist: `BACKFILL`, `WRITE_PATH`, `API`, `POKE`. Not user-visible. It is what makes a backfill's completeness auditable after the fact and what distinguishes "this database was created by a user" from "this database was inferred from its tables" — a distinction S8's drop semantics will need. |

**Operations.** Route prefix `/hts/databases`, mirroring `/hts/tables` and `/hts/views`.

| Operation | Preconditions | Postconditions | Errors | Idempotent |
|---|---|---|---|---|
| `findById(databaseId)` | none | returns the row or empty | 5xx propagates; **absence must mean genuine absence** — a repository or hydration failure propagates rather than reading as "free", exactly as `getNeutralEntity`'s contract states | yes |
| `findAll(query)` where `query` is a typed value object, never a magic empty string (#15). Two factories: `allDatabases()`, and `matching(idPattern)` — **whose language is stated, because it is the only query shape that can answer a structural question about the key space.** The pattern language is the one already in the tree, `ValidatorConstants.ALPHA_NUM_UNDERSCORE_PATTERN_SEARCH_REGEX` = `^%?[a-zA-Z0-9_]+%?$`: an optional leading and/or trailing `%`, no other metacharacter, anchored at both ends, translated to SQL `LIKE`. Not a glob, not a regex. Case semantics are the `databaseId` column's collation — which is the second reason that pin is load-bearing. **Cache-servable:** yes, whenever `isAuthoritative()`, by applying the same folding in heap | none | the matching set, in `databaseId` order | 5xx propagates; `400` on a pattern outside that language | yes |
| `findAllModifiedSince(watermark)` | `watermark` from a prior response | rows with `lastModifiedTime > watermark`, plus tombstones | 5xx propagates | yes |
| `put(database)` | payload's key is non-empty and is valid per the **owning validator** — Workstream 1's `NamespaceUtil` (§5.7), never a literal restated here. `database_row`'s own precondition is the column's and only the column's: length ≤ 128, and uniqueness under the collation above. An implementer who reads this section alone and hard-codes `^[a-zA-Z0-9_]+$` into a `database_row` validator has frozen into the storage entity a rule this design has declared it does not own (#10, W3) | row created or replaced; returns a `PutResult`-shaped outcome naming create-vs-replace, so the HTTP layer renders 201-vs-200 without decoding a boolean | `409` on version conflict; `400` on validation | **no** (it is a CAS), but `ensure` below is |
| `ensure(databaseId, origin)` | none | row exists; existing row untouched including its `origin` and `lastModifiedTime` | 5xx propagates | **yes** — this is the registrar primitive of §5.8 |
| `delete(databaseId)` | S8 only | row removed | `409` / `NamespaceNotEmptyException` if the database still has live tables — never a cascade (the retired W5, now decided) | yes |

**The consumer may not assume:** that a database row exists for a database that has tables (until
S3 verified); that `findAll` order is stable across releases beyond `databaseId` ordering; that
`put` and `ensure` are the same operation; that `matching` accepts anything richer than the
prefix/suffix language above; or that a delete is visible to another replica before its next
refresh interval.

**HTS may not assume:** that the caller has validated anything. Validation is duplicated at the HTS
boundary in the established style (`OpenHouseUserTableHtsApiValidator`), because HTS is reachable
independently of the Tables Service.

**Deliberately absent:** any `entity_type` on `database_row`. A database is not an occupant of the
`(database_id, table_id)` key space and must not compete for it. This is the concrete form of the
option-C rejection.

### 5.4 Contract — the cache seam

**Shape: a static write-through cache.** Every database is resident in the Tables Service heap.
"Static" means the working set is the whole set: there is no eviction policy, no admission policy,
and no per-key TTL, because a cache that can evict cannot answer "list all databases" without going
back to MySQL, which is the cost the cache exists to remove.

```
interface DatabaseCache {
  Optional<Database> get(String databaseId);       // point read
  List<Database>     list();                       // complete, in databaseId order
  Page<Database>     list(Pageable pageable);      // paged projection of the same order
  boolean            isAuthoritative();            // false ⇒ trust mode must not be served
  void               applyWrite(Database persisted);   // write-through, post-commit
  void               applyDelete(String databaseId);   // write-through, post-commit
}
```

**Consistency contract, stated exactly, because this is where M3 is won or lost.**

- **Read-your-writes, same replica: exact.** A write is applied to the cache only after the MySQL
  write commits, from the row the write returned — never from the request. Order is MySQL first,
  heap second; a heap update cannot fail in a way that leaves MySQL wrong.
- **Point reads, any replica: strongly consistent.** A cache *miss* falls through to a `findById`
  against HTS. A miss is bounded — it happens only for a database this replica has not yet seen —
  so the fallthrough cannot be a load source at steady state. This is what keeps
  `loadNamespaceMetadata` and `databaseExists` correct across replicas without waiting for a
  refresh.
- **List reads, other replicas: eventually consistent, bounded by the refresh interval.** This is
  the one genuine consistency change in the design. Today `/v1/databases` is a live query and a
  table created on replica A is in replica B's list immediately. Under the cache it is in replica
  B's list within `cluster.databases.cache.refresh-interval` (recommended default **30s**). The
  cost of avoiding it is a query per call, which is option E. **This requires owner ratification**
  and is §5.11 Q1, with that default.
- **Refresh is incremental, not a reload.** Each replica polls
  `findAllModifiedSince(watermark)` on the interval. Typical result: zero rows. This is what makes
  M4's ceiling hold at N replicas.
- **Negative results are not cached** as a separate structure; the cache is the complete set, so
  absence in an authoritative cache *is* the negative result — but only when `isAuthoritative()`.
- **The cache has an oracle, because `compare` is not one.** A periodic **cache audit** re-reads the
  full `database_row` set from HTS on a long interval (default **1h**, staggered per replica so the
  fleet does not converge on one minute) and compares it against the heap: the set difference in
  both directions, and each projected field over the intersection. Divergence increments
  `database_cache_audit_divergence_total{kind}` and, above a threshold, drops `isAuthoritative()`,
  which degrades the resolver rather than serving a wrong list. This is not optional garnish: the
  cache is the one net-new component that `compare` structurally cannot see (§5.5.1), and without an
  oracle a cache defect presents as `missing_in_stored`, whose stated first action sends the
  operator to re-run a backfill against data that is already correct.

**Authoritativeness and the overflow rule (M7).** The cache is authoritative iff the full load
completed, the load was not truncated by the weight cap, and the last refresh is within
`2 × refresh-interval`. If the weight cap is exceeded at load time, the cache **does not** serve a
partial set: `isAuthoritative()` returns `false`, the resolver refuses `trust` and degrades to
`compare` (or `original` if compare is also unavailable), and `database_cache_overflow_total`
increments. A partial cache serving `trust` would silently under-report databases, which is the
worst failure this design can have — it makes `listNamespaces` lie. Failing loudly into the old path
is strictly better.

**The consumer may not assume:** that `list()` reflects another replica's write within the current
request; that `get()` returning empty means the database does not exist when `isAuthoritative()` is
false; or that the cache survives a restart (it does not — it reloads).

### 5.5 Contract — the resolver seam

This is the seam. One interface, one flag, three modes.

```
interface DatabaseResolver {
  List<DatabaseDto>  listDatabases();
  Page<DatabaseDto>  listDatabases(int page, int size, String sortBy);
  Optional<DatabaseDto> findDatabase(String databaseId);
  boolean            databaseExists(String databaseId);
}
```

Flag: `cluster.databases.resolver.mode = original | compare | trust`. Default `original`.

**What a mode change actually costs — stated, because the nine-state plan's safety argument rests
on it.** It is not a hot reload, and this design does not pretend otherwise. `ClusterProperties` is
a `@Configuration` bean whose fields are `@Value`-bound against a file-backed `@PropertySource`, and
`@Value` resolves once at bean construction. There is no `@RefreshScope`, no Spring Cloud Config and
no dynamic property source anywhere in the repository, and the four `@ConfigurationProperties` beans
bind at startup too. Every S1–S7 rollback is therefore **a re-mounted `cluster.yaml` plus a rolling
restart of the Tables Service**, bounded by the fleet's rolling-restart time ***T***.

That is still a configuration change and not a deploy — no new artifact, no review, no release train
— which is what SH5 asks for. But it is minutes rather than seconds, and §5.9.2 costs S6 and S7
against *T* rather than against zero. Making the mode hot-reloadable is a genuine option and a
genuine prerequisite *if T is unacceptable*; it is a mechanism this design neither owns nor assumes,
and claiming it without building it would have made every rollback in §5.9 look free.

#### 5.5.1 The three modes

| Mode | Reads original | Reads stored | Returns | MySQL cost vs today | Rollback |
|---|---|---|---|---|---|
| `original` | yes | **no** | original | identical | n/a — this *is* the rollback target |
| `compare` | yes | yes | **original** (the stored result is discarded) | identical, + one cache read for `listDatabases`; point reads may fall through to HTS on a miss (§5.4) | flag → `original`, plus a rolling restart |
| `trust` | **no** | yes | stored | O(1) — the `DISTINCT` scan disappears | flag → `compare` or `original`, each plus a rolling restart (bounded by *T*) |

**`compare` must be unable to change the response.** Three rules make that structural rather than
aspirational:

1. The stored read and the comparison run **after** the original result is fully materialised —
   and the comparator receives an **unmodifiable copy** of it and returns only counters.
   Materialisation alone is not immutability: without the copy nothing stops the comparator sorting
   the returned list in place in order to compare order, which is precisely the response-changing
   bug this rule claims to exclude. The copy is what makes the claim structural; "we materialised
   it first" is aspirational.
2. The stored-read-and-compare block catches **`Exception`, not `Throwable`**. A caught exception is
   counted (`database_resolver_compare_error_total{class}`) and dropped; an `Error` is rethrown, and
   an `InterruptedException` restores the interrupt before returning. Swallowing `Throwable` would
   hide the `OutOfMemoryError` that a cache overflow produces — the exact symptom §5.4's
   authoritativeness rule exists to catch — and would silently corrupt shutdown. A failure in the
   *new path* can never fail a request in `compare`; an `Error` is not a failure in the new path, it
   is a failure of the process, and it must not be laundered into a counter.
3. The comparison is time-boxed. Exceeding the box counts
   `database_resolver_compare_skipped_total{reason="deadline"}` and returns the original.

**`compare` reads the cache, not the database — and that is a deviation from the requirement as
stated, not a virtue.** The requirement (M5, and the owner's wording) is: read the *database*,
compare, discard the database result, count divergence. This design substitutes the cache for the
database, because `compare` is on for a long time, on every replica, and a compare that doubled the
query load would make the safest state the most expensive one. The substitution is worth taking. It
is not free, and it is stated here in full rather than presented as a design win:

- **It is narrower than "no MySQL".** It applies to `listDatabases` only. `findDatabase` and
  `databaseExists` are point reads, and §5.4 already commits those to falling through to `findById`
  against HTS on a miss — so "no MySQL" is false for them, and most false in exactly the state where
  a cold or churning cache makes misses most common. The claim is therefore scoped to
  `listDatabases` and nowhere else.
- **It leaves the one net-new component unchecked.** `compare` compares the derived path against the
  cache, so the cache is the only component in the design that `compare` structurally cannot
  validate. A cache defect would present as `missing_in_stored`, whose first action (§5.5.3) sends
  the operator to re-run a backfill against data that is already correct. §5.4's periodic cache
  audit is the oracle that closes this, and it is a **requirement of the substitution**, not an
  extra.
- **It requires owner ratification** — §5.11 Q2, alongside §5.4's list-consistency change (Q1). The
  alternative is implementable and its price is known: `compare` reads through to HTS, costing one
  `database_row` full read per comparison per replica for the whole soak. If the owner prefers it,
  that row goes into §5.10 and this paragraph is deleted.

#### 5.5.2 What "the same" means, per operation

| Operation | Compared on |
|---|---|
| `listDatabases()` | the **set** of `databaseId`s, and for each id in both, the projected `clusterId`. **Order is not compared.** The derived source is `findAllDistinctDatabaseIds()`, whose `@Query("SELECT DISTINCT databaseId FROM UserTableRow")` carries no `ORDER BY` at all, so its sequence is whatever the engine returns and comparing it manufactures divergence. The comparator emits `compare_skipped{reason="unordered_source"}` for the order check only; the set and attribute checks always run |
| `listDatabases(page,size,sortBy)` | `totalElements`; the page's element set; **and the page's order — including when `sortBy` is null**, asserting `databaseId` ASC on both sides |
| `findDatabase(id)` | presence, then each projected field |
| `databaseExists(id)` | the boolean |

**Why those two are the way round they are.** The obvious reading is the opposite one — skip order
when `sortBy` is absent, compare it when it is present — and it is backwards. Absent `sortBy` is
**deterministic**: `DatabasesServiceImpl` passes a null default into
`PageableUtil.createPageable(page, size, sortBy, null)`, the resulting empty sort string travels to
HTS, and HTS's `listDatabases(page, size, sortBy)` calls
`createPageable(page, size, "", "databaseId")`, which falls back to
`Sort.by("databaseId").ascending()`. That is the same order §5.4 gives the cache, so it is
comparable and must be compared. The *unpaged* call is the non-deterministic one, and it is the one that carries no skip
today. Getting this backwards would have S6 entered on an S5 soak that structurally could not
observe an ordering difference — S6's own named residual hazard — while the skip fired on the only
ordering the two paths are guaranteed to agree on.

#### 5.5.3 Divergence counters — designed to say *where*

A counter that only says "they differed" sends an operator to a full-table diff. These say which
side, which kind, and how much, with bounded cardinality.

**Names** (service-owned constants class in the style of `ViewMetricsConstant`, so a rename is a
Tables Service change and not a `services:common` release):

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `database_resolver_comparison_total` | counter | `op` | **The denominator.** Without it a divergence count is unreadable. |
| `database_resolver_agreement_total` | counter | `op` | Comparisons that found nothing. Agreement rate = this / comparison. |
| `database_resolver_divergence_events_total` | counter | `op`, `kind` | One per diverging *comparison*. |
| `database_resolver_divergence_items_total` | counter | `op`, `kind` | Incremented by the **magnitude** — how many databases differ, not how many requests noticed. |
| `database_resolver_divergence_magnitude` | distribution summary | `op`, `kind` | The shape of the divergence over time: is it one stuck database or a growing gap? |
| `database_resolver_compare_error_total` | counter | `class` | The compare block threw. |
| `database_resolver_compare_skipped_total` | counter | `reason` | `unordered_source` (the unpaged derived read has no `ORDER BY`; order only, §5.5.2), `deadline`, `cache_not_authoritative`. |
| `database_resolver_reads_total` | counter | `mode`, `source` | `source ∈ {derived, cache, hts_point_read}`. This is the M4 instrument. |
| `database_cache_size` | gauge | — | Entries resident. |
| `database_cache_weight_bytes` | gauge | — | Against the cap. |
| `database_cache_overflow_total` | counter | — | Authoritativeness lost. |
| `database_cache_staleness_seconds` | gauge | — | Now minus last successful refresh. |
| `database_cache_audit_divergence_total` | counter | `kind` | The §5.4 cache audit found the heap and `database_row` disagreeing. This is the only instrument that watches the cache itself; `compare` cannot. |

**`kind` — the taxonomy is the design.** Each value names a *cause*, which is what makes the counter
actionable:

| `kind` | Meaning | Expected? | First action |
|---|---|---|---|
| `missing_in_stored` | Has tables, no `database_row` | **No, after S3** | Backfill gap or populate-on-write gap. Poke it (§5.8.3); investigate the write path. |
| `extra_in_stored_empty` | Row exists, zero live tables | **Yes, from S3** — this is #13 in its general form: any database whose last table was dropped after S2 | None; this is G1 working. But its **magnitude is not noise** — it is the exact size of M3's named exemption, and S6's entry gate reads it before the exemption is taken (§5.9.2). Review it once at S6, exclude it from alarms thereafter. |
| `extra_in_stored_nonempty` | Row exists, tables exist, derived did not list it | **No** | Almost certainly a read straddling a concurrent drop; re-compare once before escalating. |
| `case_variant_collapse` | Derived lists ≥2 case spellings, stored has one | **Yes if the pre-migration audit found any** (§5.9.1); no otherwise | Pre-existing data inconsistency, surfaced not caused. Resolve as data. |
| `attribute_mismatch` (tag `field`) | Present in both, a projected field differs | **No** | Today the only possible `field` is `clusterId` (#11). |
| `order_mismatch` | Same set, different sequence | Tolerable for `/v1`; **not** for `/v2` paging | Paged clients see shifted page boundaries. |
| `count_mismatch` | `totalElements` differs on a paged compare | **No** | Usually implies one of the set kinds above. |
| `page_content_mismatch` | Same total, different page N | **No** | Ordering or a straddled write. |

**Cardinality and identifiers.** Tags are bounded — `op` (4 values) × `kind` (8) × `field` (1), plus
the cache audit's own small `kind` space — so the whole taxonomy is a few dozen series. Database identifiers are **never** tags. They go to a
rate-limited WARN log carrying the diverging ids (capped at the first N per interval), a
`divergence_id` correlation field shared with the counter increment, and the counts per kind. That
is the pair that answers "where": the counter says which kind and how many, the log says which
databases.

**The consumer may not assume:** that `compare` implies the stored path is correct (that is what S3's
verification marker is for, not what compare is for); or that a zero divergence count means
comparisons ran — always read it against `database_resolver_comparison_total`.

### 5.6 Contract — the `DatabasesService` seam

`DatabasesService` is already the public service boundary and **keeps all four of its existing
signatures unchanged**: `getAllDatabases()`, `getAllDatabases(page,size,sortBy)`,
`updateDatabaseAclPolicies(...)`, `getDatabaseAclPolicies(...)`. Its implementation stops calling
`openHouseInternalRepository.findAllIds()` and calls `DatabaseResolver` instead. That single
substitution is the entire Tables-Service-side change of states S4–S6, which is why the states are
cheap to enter and leave.

Widened with, **available only from S8**:

| Method | Contract |
|---|---|
| `createDatabase(databaseId, properties, actingPrincipal)` | Requires a create privilege on the database (a new `Privileges` constant, in the shape of the `CREATE_VIEW` / `LIST_VIEW` constants the views work added). Idempotency is a decided question — recommended: `201` on create, `409 AlreadyExists` on a second call, matching the Iceberg spec's `createNamespace`. |
| `dropDatabase(databaseId, actingPrincipal)` | A populated database is **not** dropped: `409` / `NamespaceNotEmptyException`, never a cascade (W5, retired and decided; Workstream 1 decided the same way and holds the conformance test). Requires a drop privilege on the database. |
| `getDatabase(databaseId)` | `404` when absent — **only from S8**. Before S8 the absence of a database is not observable, per M3 and #6. |
| `updateDatabaseProperties(databaseId, updates, removals, actingPrincipal)` | Reserved-key rule of #9 applies; bounds of §5.8.4 apply; CAS on `version`. |

**The consumer may not assume**, before S8, that any of the four existing methods can fail with a
"database not found" — because today they cannot, and M3 says they still cannot.

### 5.7 Contract — the REST namespace surface (Workstream 1 boundary)

**Deliberately one-sided.** A sibling agent owns the namespace endpoint design. This section states
only what that design may rely on from this one, and what this design will not do.

**What Workstream 1 may assume, from S8 onward.** This list is **provisional and known
incomplete**, and it says so rather than closing in tone while remaining open in fact. It grows by
amendment to this section, agreed by both workstream owners and recorded here with the state that
first provides the capability; a capability Workstream 1 needs and does not find below is a request
to amend, not a gap to work around.

- `DatabaseResolver.findDatabase(id)` / `databaseExists(id)` are the existence oracle, and they are
  strongly consistent for point reads across replicas (§5.4).
- `DatabasesService` exposes create / drop / get / update-properties with the contracts of §5.6.
  `dropDatabase` on a populated database fails with `409` / `NamespaceNotEmptyException`; there is
  no cascade (the retired W5).
- The property map has a reserved `openhouse.` space that the namespace surface must not let a client
  write (#9), and bounds that the namespace surface must enforce at ingress (§5.8.4).
- `listDatabases` is complete and ordered by `databaseId`; paging is a projection of that order.
- **A structural predicate over the key space**: `findAll(matching(idPattern))` (§5.3), whose
  pattern language, case semantics and cost are stated there. The first four capabilities can only
  ask about a database Workstream 1 already names; this one can ask whether *any* key has a given
  prefix or suffix, which is what a namespace tree needs in order to decide whether an intermediate
  level is occupied. It was missing, and its absence is why the list is marked provisional rather
  than merely short.

**What this design does not decide, and Workstream 1 owns:**

- The namespace encoding (dot-join vs `%1F`) and the charset that goes with it. This design is
  depth-1 and prefix-preserving by construction: for every database that exists today, the
  `database_row` key is the same bytes as the current `databaseId`. That is the invariant that lets a
  prefix-preserving encoding land on top with no migration.
- Whether privileges inherit down a namespace tree.
- The HTTP shape, status codes and error envelopes of `/v1/{prefix}/namespaces`.
- Whether namespace properties are readable through the existing `/v0,/v1,/v2 /databases` API.

**Handoff obligation, both directions**, in the wording both workstreams carry verbatim:

> `databaseId` bytes are unchanged for every database that exists today, and the namespace store's
> key is `encode(ns)` — the same string that appears in `house_table.database_id`, for every
> namespace at every depth.

The second clause costs this design nothing: at depth 1, `encode(ns) == ns.level(0)`, so it is
vacuous for everything Workstream 2 builds. Carrying it anyway is what makes the sentence a
*shared* invariant rather than two compatible-sounding ones, and it is testable from either side.

### 5.8 Population: one primitive, four callers

#### 5.8.1 The registrar primitive

```
DatabaseRegistrar.ensure(databaseId, Origin) -> Database
```

Idempotent, safe to call concurrently, and **never mutates an existing row** — not its properties,
not its `origin`, not its `lastModifiedTime`. Everything below is a caller of it. That is what makes
four population strategies one thing to reason about, one thing to test, and one thing to roll back.

**Ordering against the table write, and the residue that ordering chooses.** `ensure(db,
WRITE_PATH)` runs **before** the table `put`, and the two writes are **not atomic**: there is no
transaction spanning `database_row` and `user_table_row`, and this design does not introduce one.
So a crash between them always leaves the same residue, and the order is chosen to make that
residue the harmless one — a `database_row` with no table yet. Before S6 nothing reads it; from S6
it is `extra_in_stored_empty`, the class M3's exemption already covers; and it needs no repair,
because the retry that follows creates the table. The other order produces a table whose database
is unregistered: `missing_in_stored`, a G3 violation, invisible until someone lists, and repairable
only by a backfill re-run or a poke. The asymmetry is the whole reason the order is stated rather
than left to the implementer.

#### 5.8.2 The two mechanisms, and when each applies

| | **Explicit backfill** | **Populate-on-write-if-absent** |
|---|---|---|
| Trigger | An operator-driven pass over `SELECT DISTINCT database_id FROM user_table_row`, in key order, paged, resumable from a watermark | `ensure(databaseId, WRITE_PATH)` on every table create (and, from S8, every namespace-scoped write) |
| Completeness | **Guaranteed**, and *provable* — see the marker below | **None.** It covers only databases that are written to. |
| Cold databases | Covered | **Not covered.** A database whose tables are never written again never materialises. This is the reason backfill is not optional. |
| Cost | One pass, bounded, offline | One extra idempotent call per table create; a cache hit after the first |
| Rollback | Rows carry `origin=BACKFILL` and can be deleted as a set | Flag off; rows stay (harmless — nothing reads them before S4) |
| When it applies | Once, at state S3, and re-runnable as a repair | Always on from S2 onward, so the *registered* set only ever grows — which is also precisely why a database outlives its last table (#13, and M3's named exemption) |

They are not alternatives. **Populate-on-write is the floor and explicit backfill is the ceiling.**
Populate-on-write is turned on *first* (S2) so that the set of databases the backfill must cover
stops growing before the backfill starts; the backfill then closes the fixed remainder. Running the
backfill without populate-on-write already on means the backfill races table creation and can never
be declared complete.

#### 5.8.3 The completion guarantee, and the poke

The backfill's completion guarantee is a **verification marker**, not a log line:

1. The backfill records a resumable watermark (last `database_id` processed, in key order).
2. On finishing, it runs a **verification pass** that computes both **set differences** between
   `SELECT DISTINCT database_id FROM user_table_row` and `SELECT database_id FROM database_row`,
   and enumerates the second one by id. It records `backfill_completed_at`, `backfill_verified_at`,
   `missing_from_stored` (the ids in the first difference) and `expected_extras` (the ids in the
   second).
3. **`trust` mode refuses to start** unless `backfill_verified_at` is present, `derived \ stored` is
   **empty** — a hard precondition, since a database with tables and no row is a G3 violation — and
   every member of `stored \ derived` is in the **named expected-extras set**: the databases whose
   last table was dropped after S2 (#13), listed by id.

   **Count equality is explicitly not the predicate.** `derived_count == stored_count` reads as the
   obvious check and is unsatisfiable in practice: `ensure` has no de-registration counterpart, so
   routine table drops push the two counts apart within a day of S2 and never bring them back.
   A precondition that routine operation makes permanently false is a precondition operators learn
   to override. The set difference, with the extras named rather than counted, is satisfiable,
   auditable, and says *which* databases are the exemption. That is the guarantee: not a promise in
   a runbook, a precondition the resolver enforces.

**The deliberate poke.** A cold database that the backfill somehow missed, or that appears through a
path nobody anticipated, is repaired by calling the same `ensure` primitive. Three pokes exist:

- **Operator poke** — `ensure` over an explicit list of database ids. The minimal, auditable repair.
- **Compare-mode self-heal** — when the comparator classifies `missing_in_stored`, it calls
  `ensure(id, POKE)`. Elegant, because the divergence detector becomes the repair mechanism, and it
  guarantees that any database anyone actually *looks at* is materialised. **Gated by its own flag**
  (`cluster.databases.resolver.compare.self-heal`), default **off**, because a read path that writes
  is a load hazard: a systematic backfill gap would turn every list request into a write storm. Turn
  it on deliberately, with the write rate-limited.
- **Re-run the backfill.** Idempotent by construction, so this is always available.

#### 5.8.4 Bounds (the link from G4 to M7)

The 1 GB memory ceiling is only a ceiling if the property map is bounded **at the write API**, before
anything is stored. Recommended bounds, enforced at ingress in the `DatabasesService` /
`updateNamespaceProperties` path *and* re-enforced at the HTS boundary:

| Bound | Recommended | Why this number |
|---|---|---|
| Entries per database | 100 | Matches the practical shape of Iceberg namespace properties |
| Bytes per key + value (UTF-8) | 1 KiB | A **sanity limit on one entry, not a binding bound**: 100 × 1 KiB is 100 KiB, twelve times the total below. It exists to reject a single absurd value early, with a clear error, rather than to size anything. Same order as `TableAuditAspect`'s per-value cap, scaled down |
| Total serialized bytes per database | 8 KiB | **The bound that actually binds.** 100k × 8 KiB = 800 MB serialized worst case, inside the owner's 10 KB/row sizing. One entry may approach 1 KiB only if the rest are small; the total is checked, not inferred |
| Cache weight cap | configurable, default 1 GiB | The M7 figure. Exceeding it costs authoritativeness, not correctness (§5.4) |

The owner's sizing — 100k rows at 10 KB/row ≈ 1 GB — is deliberately generous, and these bounds are
what make it a guarantee. A realistic row (a 128-byte key, a few columns, a small property map) is
one to two orders of magnitude smaller; the ceiling is a safety property, not a forecast.

**Population sub-checklist**

- [ ] `ensure` is idempotent and never mutates an existing row — asserted, not assumed.
- [ ] Populate-on-write is enabled (S2) strictly before the backfill starts (S3).
- [ ] Backfill is resumable from a watermark and re-runnable.
- [ ] Verification pass computes **both set differences** and enumerates the expected-extras set by
      id. Count equality is explicitly not the predicate.
- [ ] `ensure` runs **before** the table put; the non-atomicity and the acceptable residue are
      stated (§5.8.1).
- [ ] `trust` mode's precondition reads the marker and refuses without it.
- [ ] Expected-extra classification (#13, and S8's empty databases) is defined *before* verification
      runs, or verification fails on correct data.
- [ ] Self-heal is a separate flag, default off, rate-limited.
- [ ] Property bounds are enforced at two boundaries, not one.

### 5.9 Migration state plan

Nine states. Each is a resting place: the system can sit in it indefinitely and be correct. Each
transition is one flag. **Every state's rollback through S7 is a configuration change — a
re-mounted `cluster.yaml` plus a rolling restart of the Tables Service, bounded by the fleet's
rolling-restart time *T*** (§5.5). No new artifact, no review, no release train; but not
instantaneous either, and the rows below cost it that way. S8 is a flag but is not cleanly
reversible, and S9 needs a deploy — which is why those two are last and separate.

Flags:

- `cluster.databases.entity.enabled` — the HTS routes and repository exist and are reachable
- `cluster.databases.populate-on-write.enabled`
- `cluster.databases.resolver.mode` — `original | compare | trust`
- `cluster.databases.resolver.compare.self-heal`
- `cluster.databases.cache.enabled`, `.refresh-interval`, `.max-weight`
- `cluster.databases.mutable.enabled` — the S8 gate

#### 5.9.1 S0 — pre-flight audits (before any code ships)

Three questions that no later state can repair, because two are properties of the existing rows and
the third is a property of the deployed schema:

1. **Case-variant audit** (#12). `SELECT database_id FROM user_table_row GROUP BY BINARY database_id`
   compared against a case-folded grouping. Any database spelled two ways today is listed twice by
   the derived path and once by the stored path — a permanent, expected divergence unless the data is
   fixed. **If this returns rows, fix the data or accept and pre-classify the divergence before S1.**
   Three sites produce or consume the asymmetry, and the third is a **producer**, which is why the
   audit is a floor and not a proof: `DatabasesServiceImpl.getAllDatabases()` folds nothing (a Java
   `distinct()` over exact strings); `UserTableHtsJdbcRepository.findByDatabaseIdIgnoreCase…` folds
   everything; and `OpenHouseInternalCatalog.renameTable` (`:218-220`) *preserves the source
   spelling* when two namespaces differ only in case — "Preserve existing case if databases are the
   same". A rename is therefore a way for a database to acquire a second spelling **after** this
   audit has run, so the audit establishes the starting position and `case_variant_collapse`
   (§5.5.3) watches for new ones.
2. **Database-count and cardinality audit.** `COUNT(DISTINCT database_id)` and the distribution of
   tables per database. This is the number the whole cache sizing rests on; the design assumes 100k,
   and a real measurement either confirms it or changes M7.
3. **Collation audit** (#10, §5.3). `SHOW FULL COLUMNS FROM user_table_row` against production, for
   `database_id`'s charset and collation. This is not a formality and it cannot be answered from the
   repository: `ddl/0000__baseline.sql` declares the column with no collation and its own header
   states that a derived definition "cannot capture … character set or collation". Whatever this
   returns is what `database_row.database_id` must be pinned to. Run it **before the DDL for S1 is
   written**, not after — S1 is the state that creates the column, and a column created with the
   wrong collation is repaired by an `ALGORITHM=COPY` table rebuild, not by a flag.

- *What is true:* nothing has changed.
- *What can go wrong:* the audits are skipped and a data problem is discovered in `compare` and
  mistaken for a code defect.
- *Detection:* n/a — this state *is* the detection.
- *Rollback:* n/a.

#### 5.9.2 The states

| State | Flags | What is true | What can go wrong | How it is detected | Rollback |
|---|---|---|---|---|---|
| **S1 — schema present, unused** | `entity.enabled=true`; everything else off | `database_row` exists and is empty. No code reads or writes it. `/hts/databases` is reachable but unused. | DDL applied wrong (unpinned collation, wrong charset, missing index on `lastModifiedTime`) | A schema-shape assertion in the HTS test suite; the deployment recipes that mount `ddl/` in filename order re-apply the migration path on every run, which is how the views stack keeps its DDL honest | `entity.enabled=false`. The table stays and is inert. Dropping it is a DDL operation applied out of band by the MySQL/DDS team, not a configuration rollback — naming the drop as "the rollback" would have broken the preamble's own rule on its first row. |
| **S2 — populate on write** | `+ populate-on-write.enabled=true` | Every table create also calls `ensure(db, WRITE_PATH)`. Nothing reads `database_row`. The registered set starts growing and only ever grows — which is also why a database will outlive its last table from here on (#13, M3's exemption). | `ensure` failure fails a table create — **the one real hazard in this state** | Table-create error rate; `database_registrar_error_total` | Flag off. Rows remain and are inert. **Design obligation:** `ensure` failure must be non-fatal to the table create in S2–S5 (it is a population optimisation there, not a correctness requirement) and **fatal from S6**, the first state where a missing row is observable as a missing database. Make both halves explicit, or S2 turns a new dependency into a new outage mode and S6 turns a swallowed error into a lost database. |
| **S3 — backfill run and verified** | unchanged | `database_row` contains every database that has a live table. `backfill_verified_at` is set. | Backfill races creates (mitigated: S2 precedes it); backfill partially completes and is believed complete | The verification pass is the detection. A non-empty `derived \ stored`, or a `stored \ derived` member outside the named expected-extras set, ⇒ not verified ⇒ `trust` refuses to start (§5.8.3) | Delete rows where `origin=BACKFILL` **and** `lastModifiedTime < backfill_started_at` — better, that carry the backfill's run id; clear the marker; re-run. An unqualified `origin=BACKFILL` delete is wrong and contradicts S2's "the registered set only grows": §5.8.1's `ensure` never mutates an existing row, so `origin` records who arrived **first**, and a database the backfill registered but that has been written to since is a live database, not backfill residue. |
| **S4 — cache on** | `+ cache.enabled=true` | Each replica loads the full set at startup and refreshes on the watermark. Nothing serves from it yet. | Load exceeds the weight cap; refresh silently stops | `database_cache_size`, `database_cache_weight_bytes`, `database_cache_staleness_seconds`, `database_cache_overflow_total` — all four exist for this state | Flag off. |
| **S5 — compare** | `resolver.mode=compare` | Responses are still the derived ones, byte for byte. The stored path runs and disagreements are counted. | Compare throws or is slow and affects the response — structurally prevented (§5.5.1); a divergence is mis-triaged | The §5.5.3 taxonomy, read against its denominator | `mode=original`. |
| **S6 — trust, cohort** | `resolver.mode=trust` on a cohort | Some replicas serve from the cache. Both answers are in production simultaneously — safe precisely because S5 proved they agree. **Entry gate:** read S5's `extra_in_stored_empty` membership first. That set is exactly the databases that will newly appear in `/v1/databases`, and it is M3's named exemption; entering S6 without reviewing it is taking the exemption blind. | The exemption is larger than expected. Ordering is no longer the residual hazard: §5.5.2 now compares paged order including the default `sortBy`, so S5 does observe it, and the skip has moved to the unpaged read where it belongs. | The exemption's size, from S5's counters, before entry; then `/v2/databases` paging assertions and client-side error rates; keep `mode=compare` on the remaining replicas so the comparison keeps running while trust is live | `mode=compare` on the cohort — a `cluster.yaml` remount plus a rolling restart of the cohort, bounded by *T*. **The cohort needs a mechanism, and it is not free:** `cluster.*` properties come from one ConfigMap mounted at `/var/config/cluster.yaml` per Deployment (`infra/recipes/k8s/templates/tables/`) and are bound at startup (§5.5), so "a subset of replicas" is not expressible *within* one Deployment. The cohort is a **second Deployment** — same Service, same image, its own ConfigMap — which is a deployment-topology prerequisite this design names and does not own (checklist J). If that topology is unavailable, **skip S6**: go S5 → S7 and accept that the first `trust` blast radius is the fleet, with rollback bounded by *T*. Say which before entering, because the two plans have different rollback stories. |
| **S7 — trust fleet-wide** | `resolver.mode=trust` everywhere | `GET /v1/databases` no longer scans `user_table_row`. M4 is met. The derived path is still compiled and one flag away. | Cache authoritativeness lost fleet-wide (all replicas restart into an overflow); and the fleet-wide restart's own MySQL burst (§5.10) | `database_cache_overflow_total`; the resolver's own degrade-to-compare behaviour is the safety net | `mode=compare`, then `original` — **each is a `cluster.yaml` remount plus a rolling restart**, so the worst case for leaving `trust` entirely is 2*T*, not zero. Size *T* before entering S7; it is the number that decides whether S7 is a safe state or a commitment. |
| **S8 — databases become mutable** | `+ mutable.enabled=true` | Create / drop / properties are live. **Behaviour changes here, deliberately and for the first time.** Empty databases exist; `getDatabase` can 404; ACL operations on an absent database may start failing. | Every deferred behaviour change lands at once, on top of M3's already-taken exemption; `compare` becomes semantically meaningless for the `extra_in_stored` kinds | This state must not be entered while any replica is in `compare`, or the divergence counters become noise. Enforce the ordering. | Flag off. Existing user-created empty databases must then be handled — **this is the one transition that is not cleanly reversible**, and it is why it is separate from S7 and from S9. |
| **S9 — remove the derived path** | delete code | `findAllIds()` is gone from `DatabasesServiceImpl`; `listTables(Namespace.empty())`'s anti-pattern arm (#14) is deleted; the resolver collapses to one implementation. | A rollback now requires a deploy | Normal release process | A deploy. Enter S9 only after S7 — and, if taken, S8 — has soaked. |

**Migration sub-checklist**

- [ ] Pre-flight audits run and their results recorded (S0). Non-negotiable.
- [ ] Each flag defaults to the safe value and is independently settable.
- [ ] `ensure` failure is non-fatal to table creates in S2–S5 and fatal from S6; both halves stated.
- [ ] S2 strictly precedes S3.
- [ ] `trust` enforces the verification marker as a precondition in code, not in a runbook.
- [ ] S6 keeps some replicas in `compare` so the comparison keeps running against live `trust`.
- [ ] S8 is blocked while any replica is in `compare`.
- [ ] S8's irreversibility is stated to the owner before entering it.
- [ ] S6's cohort mechanism is chosen and written down — second Deployment, or skip S6 — before S5
      ends.
- [ ] *T*, the fleet's rolling-restart time, is measured and recorded. Every rollback through S7 is
      bounded by it, and leaving `trust` entirely costs 2*T*.
- [ ] S6's entry gate: S5's `extra_in_stored_empty` membership is read and reviewed before entry.
- [ ] Every state above has a named metric that says whether it is healthy.

### 5.10 Scale, and the MySQL read budget

**Today.** One `GET /v1/databases` = one `SELECT DISTINCT database_id` scan of `user_table_row` in
HTS, and **one row per database** on the wire from HTS to Tables. The wire and heap cost is already
O(databases) — HTS collapses the set in SQL, and the Java `distinct()` in `DatabasesServiceImpl` is
redundant. What costs is the scan being collapsed: over every row of the hottest table in the
schema, once per call, per replica, per scheduler run (once per job type). The cache removes a
**scan** cost, not a serialization cost, and that is the honest version of the M4 argument.

**Target ceiling, per Tables Service replica, from S7 (trust fleet-wide):**

| Work | Frequency | Cost |
|---|---|---|
| Full cache load | once per process lifetime | 100k rows, paged (100 queries at 1000/page) |
| Incremental refresh | once per `refresh-interval` (default 30s) | one indexed range scan on `lastModifiedTime > watermark`; typical result **zero rows** |
| Point-read fallthrough | once per cache miss | one primary-key lookup; misses are bounded by the set of databases this replica has not seen |
| Writes | one per database create / property update | one upsert |
| `findAll(matching(pattern))` | per call, and **only** when the cache is not authoritative | one `LIKE` scan of `database_row` (100k rows) — never of `user_table_row`; served from heap otherwise (§5.3) |
| Cache audit | once per audit interval (default 1h, staggered) | one full read of `database_row`, 100 paged queries (§5.4) |
| **`GET /v1/databases`** | per request | **zero MySQL queries** |

At 20 replicas and a 30-second interval that is 40 indexed range queries per minute across the
fleet, against today's per-request `DISTINCT` scans. The load reduction is the *reason* the cache is
stateful, and it is why "static write-through" is the right shape rather than a TTL cache: a TTL
cache re-reads the whole set on expiry, which is option B's cost with option D's complexity.

**The moment the table does not cover: a fleet-wide restart.** "Once per process lifetime" is a
steady-state figure, and the rolling restarts this design's own rollbacks require (§5.5, §5.9) start
every process within one restart window. Twenty replicas × 100 paged queries ≈ **2000 queries in a
burst**, against `database_row` and not `user_table_row`, spread over the rolling restart's stagger
rather than issued at once. In absolute terms it is small. It is named anyway, because it arrives at
exactly the moment an operator is already rolling something back, and because it is the one number
in this section that the per-replica ceiling does not describe.

**Why the constraint is MySQL and not the heap.** 1 GB of heap on a service that already holds an
Iceberg metadata cache (`cluster.iceberg.tables.metadata-cache`) is a sizing conversation. A
`DISTINCT` scan of `user_table_row` on every `/v1/databases` call, from every replica, on every
scheduler run, is a shared resource that every other OpenHouse operation contends for. That asymmetry is what makes
the stateful design correct rather than merely faster.

### 5.11 Open questions: what the owner is being asked to ratify

Three. Two are deviations this design takes deliberately; the third is the exemption M3 names. Each
row states the alternative and its price, so ratification is a choice rather than an approval.

| # | Question | This design's answer | The alternative, and what it costs |
|---|---|---|---|
| **Q1** | **List reads become eventually consistent across replicas.** Today `/v1/databases` is a live query and a table created on replica A is in replica B's list immediately; under the cache it is there within `refresh-interval` (§5.4) | Accept, default **30s** | Option E: no cache, one 100k-row query per call per replica. Every contract in §5.3, §5.5 and §5.6 survives unchanged, which is why the cache is behind its own flag (S4) — this alternative stays available after the fact |
| **Q2** | **`compare` reads the cache, not the database**, deviating from M5 as stated (§5.5.1) | Accept, scoped to `listDatabases`, with §5.4's periodic cache audit as the oracle the substitution creates the need for | `compare` reads through to HTS: one `database_row` full read per comparison per replica, for the whole soak. Implementable; if chosen, that row goes into §5.10 and the deviation paragraph in §5.5.1 is deleted |
| **Q3** | **M3's named exemption** (§2.2): from S6, a database whose last table was dropped after S2 stays listed, because `ensure` has no de-registration counterpart | Accept, measured at S5 and gated at S6 | Give `ensure` a de-registration counterpart on the table-drop path. It holds M3 exactly — and it buys a new write on the drop path, which is the same new-outage-mode hazard S2 already names for `ensure`, plus a drop/re-create race, plus a `has_live_tables` fact that must be maintained and can itself go stale. Rejected on that trade, not on effort; the owner may reverse it |

**Retired from this list.** W5 — `dropNamespace` on a populated database — was recorded here as an
owner call while §5.3 had already committed to the answer. It is now decided: `409` /
`NamespaceNotEmptyException`, never a cascade.

---

## 6. Appendix

### A. Alternatives, developed

**A.1 — Option C: `entity_type = DATABASE` in `user_table_row`.** The attraction is real: the
discriminator, its converter, its pinned collation, its typed predicates, its typed deletes, its
neutral collision read and its `PutResult` are all built and reviewed (#45–#47). Adding a third
constant to `EntityType` looks like the cheapest possible change.

It fails on four counts. **(i) The key does not fit.** The primary key is
`(database_id, table_id)`; a database has no `table_id`. A sentinel — empty string or a reserved
name — is either rejected by the `^[a-zA-Z0-9_]+$` charset or occupies a name a user could pick.
**(ii) Neutral reads change.** `TABLE_ROW_PREDICATE` and `VIEW_ROW_PREDICATE` would hide the new
rows from typed queries, but `findAll()`, `findAllByFilters(...)`, `findAllDistinctDatabaseIds()`
and `getNeutralEntity(...)` carry no type predicate — the last one deliberately, so writers can spot
a collision at a shared key. Database rows would appear in all four, which is exactly the
behaviour change M3 forbids, in the most load-bearing read path in the system. **(iii) There is no
property column.** `user_table_row` has `metadata_location`, `storage_type`, `creation_time`,
`version` — a table's shape. M2 would need a new column on the hottest table in the schema.
**(iv) The soft-delete store has no discriminator at all.** `soft_deleted_user_table_row` carries no
`entity_type`; the views work handles this by making view deletes always hard, with a comment saying
why. A database row routed into that store would restore as a table. Taken together these are not
four inconveniences, they are four ways for a database row to be mistaken for a table by code that
predates the concept.

**A.2 — Option E: the entity without the cache.** Correct, simple, and it satisfies M1, M2, M3, M5,
M6, M8 and M9. Its M4/M7 penalty is real but **smaller than the obvious framing suggests**, and the
obvious framing is wrong: today's `/v1/databases` already puts one row per database on the wire,
because HTS answers with `SELECT DISTINCT database_id` (§1, §5.10). So E is *equal* to today on
serialization and *better* on the scan — it reads 100k rows from `database_row` where today reads a
`DISTINCT` over every table row. What survives, and what keeps M4 decisive, is that E pays a query
**per call, per replica, per scheduler run** on a shared resource where D pays none, and that a
100k-element list has to be built in heap on every one of them. That weakens D-over-E; it does not
overturn it. E is worth keeping in mind as the *degraded* mode: if the cache is ever found to be unworkable, E is where the
design lands, and every contract in §5.3, §5.5 and §5.6 survives unchanged. That is a property worth
having, and it is why the cache is behind its own flag (S4) rather than being fused to the resolver.

**A.3 — Option F: a separate namespace service.** Clean boundaries, independent scaling, and a
natural home for the multi-level namespace tree later. It is rejected on cost, not on shape: it adds
a deployable, a datastore, a deployment recipe, a client, a failure mode in the table-create path,
and an operational surface — for a dataset of 100k rows that fits in the heap of a service that
already exists. If databases ever grow properties large enough to be a service of their own, F is
the migration target and the §5.3 contract is the interface it would implement.

**A.4 — Option B: memoise the derived set.** Cheap, and it would help M4 today. It cannot represent
an empty database, cannot carry properties, and has nothing to compare against, so M1, M2 and M5 are
all out of reach. It is listed because it is the tempting incremental step, and naming why it is a
dead end prevents it from being proposed again as "a smaller first step" — it is not a step toward D
at all, since none of its code survives.

### B. Definitions

| Term | Meaning here |
|---|---|
| **Database** | The single-level OpenHouse namespace; §2.1 |
| **Derived path / original** | Computing the database set from `user_table_row` primary keys, today's behaviour |
| **Stored path** | Reading `database_row`, through the cache |
| **Registrar** | The idempotent `ensure(databaseId, origin)` primitive; §5.8.1 |
| **Poke** | A deliberate call to the registrar for a database that no write path will reach; §5.8.3 |
| **Authoritative cache** | Loaded completely, not truncated, and refreshed recently enough to serve `trust`; §5.4 |
| **Expected divergence** | A `compare` disagreement that the design predicts and classifies, so it does not read as a fault; §5.5.3 |
| **Verification marker** | The persisted evidence that the backfill finished and its output matched the derived set up to the named expected extras; the precondition `trust` enforces; §5.8.3 |
| **Expected-extras set** | The databases that are in `database_row` and not in the derived set because their last table was dropped after S2; enumerated by id, never counted; §5.8.3, #13 |
| ***T*** | The Tables Service fleet's rolling-restart time. Every rollback through S7 is a `cluster.yaml` remount plus a rolling restart, so *T* is the unit every rollback in §5.9 is priced in; §5.5 |

### C. Evidence index

Every claim in this document is grounded in one of these files.

**The derived path** — including the part that is already `SELECT DISTINCT`
`services/tables/src/main/java/com/linkedin/openhouse/tables/services/DatabasesServiceImpl.java` ·
`services/tables/src/main/java/com/linkedin/openhouse/tables/repository/impl/OpenHouseInternalRepositoryImpl.java` (`findAllIds`, ~L882) ·
`iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/OpenHouseInternalCatalog.java` (`listTables`, L102; `renameTable`'s case preservation, L218-220) ·
`iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/repository/HouseTableRepositoryImpl.java` (`findAll`, L269) ·
`iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/mapper/HouseTableMapper.java` (`toHouseTableWithDatabaseId`, `@BeanMapping(ignoreByDefault = true)`) ·
`services/housetables/src/main/java/com/linkedin/openhouse/housetables/services/UserTablesServiceImpl.java` (`isListDatabases`, `listDatabases`) ·
`services/housetables/src/main/java/com/linkedin/openhouse/housetables/repository/impl/jdbc/UserTableHtsJdbcRepository.java` (`findAllDistinctDatabaseIds`, L42-43, L51-53) ·
`services/common/src/main/java/com/linkedin/openhouse/common/utils/PageableUtil.java` (`createPageable`'s `defaultSortBy` fallback)

**Rollback and rollout mechanics**
`cluster/configs/src/main/java/com/linkedin/openhouse/cluster/configs/ClusterProperties.java` (`@Configuration` + `@Value` + file-backed `@PropertySource`; no `@RefreshScope` anywhere in the tree) ·
`infra/recipes/k8s/templates/tables/tables-configmap.yaml`, `tables-deployment.yaml` (one ConfigMap mounted at `/var/config/cluster.yaml` per Deployment) ·
`cluster/storage/build.gradle` (the module graph that puts a database record out of `allocateTableLocation`'s reach) ·
`services/tables/src/main/java/com/linkedin/openhouse/tables/utils/TableUUIDGenerator.java` (L215, `Paths.get(rootPrefix, databaseId)`) ·
`apps/spark/src/test/java/com/linkedin/openhouse/jobs/util/TableMetadataTest.java` (the sibling test that exists; there is no `DatabaseMetadataTest`) ·
`services/common/src/main/java/com/linkedin/openhouse/common/api/validator/ValidatorConstants.java` (`ALPHA_NUM_UNDERSCORE_REGEX`, one constant) ·
`services/tables/src/main/java/com/linkedin/openhouse/tables/api/validator/impl/OpenHouseTablesApiValidator.java` (L123, L258 case-sensitive; L325 case-insensitive)

**The API surface**
`services/tables/src/main/java/com/linkedin/openhouse/tables/controller/DatabasesController.java` ·
`services/tables/src/main/java/com/linkedin/openhouse/tables/services/DatabasesService.java` ·
`services/tables/src/main/java/com/linkedin/openhouse/tables/model/DatabaseDto.java` ·
`services/tables/src/main/java/com/linkedin/openhouse/tables/api/validator/impl/OpenHouseDatabasesApiValidator.java`

**The discriminator stack this design builds on (#45–#47, merged in #44)**
`services/housetables/src/main/java/com/linkedin/openhouse/housetables/model/EntityType.java` ·
`.../model/EntityTypeConverter.java` · `.../model/UserTableRow.java` ·
`.../repository/impl/jdbc/UserTableHtsJdbcRepository.java` ·
`.../services/UserTablesService.java` · `.../services/PutResult.java` · `.../services/UserViewQuery.java` ·
`.../controller/UserHouseTablesController.java` ·
`services/housetables/ddl/0000__baseline.sql`, `0001__add_entity_type_to_user_table_row.sql`, `0002__pin_entity_type_collation.sql`

**Inventory sources**
`services/housetables/src/main/java/com/linkedin/openhouse/housetables/model/TableToggleRule.java` ·
`.../services/WildcardTableToggleRuleMatcher.java` · `.../services/ToggleStatusesServiceImpl.java` ·
`services/tables/src/main/java/com/linkedin/openhouse/tables/toggle/TableFeatureToggle.java` ·
`.../toggle/FeatureToggleAspect.java` · `.../repository/impl/BasePreservedKeyChecker.java` ·
`cluster/storage/src/main/java/com/linkedin/openhouse/cluster/storage/BaseStorage.java` ·
`.../storage/Storage.java` · `.../storage/selector/impl/RegexStorageSelector.java` ·
`.../storage/selector/StorageSelector.java` ·
`apps/spark/src/main/java/com/linkedin/openhouse/jobs/util/DatabaseTableFilter.java` ·
`.../jobs/util/DatabaseMetadata.java` · `.../jobs/client/TablesClient.java` ·
`.../jobs/scheduler/JobsScheduler.java` · `.../jobs/scheduler/tasks/DatabaseOperationTask.java` ·
`infra/recipes/docker-compose/common/opa/policy.rego` ·
`services/common/src/main/java/com/linkedin/openhouse/common/utils/NamespaceUtil.java` ·
`services/common/src/main/java/com/linkedin/openhouse/common/metrics/MetricsConstant.java` ·
`services/housetables/src/main/java/com/linkedin/openhouse/housetables/metrics/ViewMetricsConstant.java`

**Conformance**
`tests/iceberg-rest-catalog-compat/src/test/java/com/linkedin/openhouse/tables/icebergrestcompat/OpenHouseIcebergRestCatalogTests.java` (on `pr34`) — the 19 namespace `@Disabled` entries and the `requiresNamespaceCreate()` / `supportsNamespaceProperties()` overrides this design unblocks.
