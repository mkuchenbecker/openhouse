# The Database abstraction: API boundary design

**Conclusion first.** Store databases as their own House Tables entity — a new `database_row`
table with its own repository, service and `/hts/databases` routes — and put a **three-mode
resolver** in front of it in the Tables Service, backed by a **static write-through cache** that
holds every database in memory. Population is by an **idempotent registrar** with two callers:
populate-on-write (the floor, no completeness guarantee) and an explicit backfill (the ceiling,
with a verification marker that `trust` mode refuses to start without). The migration runs through
nine independently safe states, each behind its own **runtime-switchable** flag — a change takes
effect without a process restart, reaches every replica in seconds, and can be addressed to a subset
of replicas (§5.4a) — and the derived path stays compiled and reachable until the last one. Deciding
criterion: it is the only option that satisfies "no *unintended* behaviour change through the
migration" — the one behaviour that does change before S8 is the intended arrival of G1, a database
outliving its last table (§2.2 M3) — **and**
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
- **M3 — No *unintended* behaviour change through the migration.** Every database, client and job
  observes byte-identical responses in every state up to and including fleet-wide `trust`, with one
  intended difference: from the first state that *serves* the stored answer (S6), a database whose
  last table was dropped stays listed. That is **G1 arriving** — a database's lifetime is
  independent of its table population — and it is the point of the abstraction, not a cost of it
  (§5.2 #13, owner-confirmed). It is not an exemption and is no longer written as one. It is
  nevertheless **measured before it is served** (S5's `database_stored_only_total` membership is its
  exact size) and **gated** (§5.9.2 S6's entry gate), because an operator who has not been shown the
  size of it first will read it as a fault at 3am. The remaining new capability — user-created empty
  databases, properties, drop — arrives at S8 behind its own flag.
- **M4 — Treat MySQL read volume as a budget, not an afterthought.** State a per-replica ceiling and
  meet it. `GET /v1/databases` must stop costing a `SELECT DISTINCT` scan of `user_table_row` on
  every call. (The wire cost is already O(databases); the scan is what has to go — §1, §5.10.)
- **M5 — Controllable, observable rollout, and a standing invariant check.** A resolver with
  `original` / `compare` / `trust`, where the comparison **reads `database_row` itself** — the store,
  not the cache — compares, **discards the compared result**, and emits divergence signal that
  identifies *where* the two disagree. Reading the store is the requirement as stated and it is not
  substitutable: the mode exists to validate the *store*, and a comparison against the cache cannot
  catch a bug in the cache, which is the one net-new component in the design. The MySQL cost is
  bounded by sampling and a per-replica ceiling (§5.5.1), not by dodging the read. The comparison is
  not an instrument with an end date: it runs for as long as both paths exist.
- **M6 — Population with a stated completeness guarantee.** Explicit backfill carries one;
  populate-on-write does not; both exist and the design says which applies when. Cold databases are
  covered by backfill or by a deliberate poke.
- **M7 — 100k databases.** The static write-through cache must have a *provable* memory ceiling at
  100k × 10 KB ≈ 1 GB, and a defined, safe behaviour when the ceiling is exceeded.
- **M8 — Independently safe migration states**, each with: what is true, what can go wrong, how it is
  detected, how it is rolled back — and each **indefinitely sustainable**, because no state has a
  bounded occupancy and the canary is assumed arbitrarily long. Every transition through S7 is a flag
  change that takes effect **without a process restart** and is reversible in seconds. That requires
  a runtime-switchable control surface, and this design owns it (§5.4a): **no state transition is
  safe until it exists.**
- **M9 — Every crossed boundary is an explicit contract**: the control surface, the resolver seam,
  the cache seam, the HTS repository seam, the `DatabasesService` seam, and the REST namespace
  surface. Six, not five: the control surface was previously treated as somebody else's mechanism,
  and the migration plan's entire safety argument turned out to rest on it.

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
- **SH5 — Keep the derived path compiled and flag-reachable** until the final state, so rollback is
  a runtime flag change and never a deploy, a restart, or a new artifact — one value flip that every
  replica has applied within the propagation bound *P* (§5.4a, §5.5).

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

| # | Option | M1 empty db | M2 properties | M3 no unintended change | M4 MySQL budget | M5 3-mode resolver | M6 backfill guarantee | M7 100k scale | M8 independently safe states | M9 contracts |
|---|---|---|---|---|---|---|---|---|---|---|
| A | **Status quo** — derive from `user_table_row` PKs | ❌ | ❌ | ✅ (trivially) | ❌ a `DISTINCT` scan of `user_table_row` per call | n/a | n/a | ⚠️ works; scan cost grows with tables | n/a — nothing to migrate | ❌ no seam |
| B | **Derive + memoise** the distinct set in the Tables Service | ❌ | ❌ | ⚠️ list becomes stale | ⚠️ one `DISTINCT` scan per refresh | ⚠️ nothing to compare against | n/a | ⚠️ refresh cost grows with tables | ⚠️ one flag, but staleness arrives with it — no state where the new path exists unobserved | ⚠️ cache seam only |
| C | **`entity_type = DATABASE` sentinel row** in `user_table_row` | ✅ | ❌ no property column | ❌ neutral reads and the soft-delete store change | ✅ | ✅ | ✅ | ✅ | ❌ **the sharpest separator** — a sentinel row is visible to every neutral read the instant it is written, so population *is* the behaviour change; there is no state between them | ⚠️ overloads the table seam |
| D | **New `database_row` HTS entity + resolver + static write-through cache** | ✅ | ✅ | ✅ one intended G1 arrival, gated | ✅ O(1) steady state | ✅ | ✅ | ✅ bounded by G4 | ✅ nine states, each reversible through S7 by one runtime flag, no restart | ✅ five named contracts |
| E | New `database_row`, **no cache** — read MySQL per request | ✅ | ✅ | ✅ one intended G1 arrival, gated | ⚠️ one 100k-row query per call, N replicas | ✅ | ✅ | ⚠️ list of 100k per call | ✅ D's plan minus S4 | ✅ |
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

Read it in one sentence: **one flag chooses which of two paths answers a database question; the
other path still runs on a sample, its answer is thrown away and its disagreement is counted; and
the stored path never touches MySQL on the hot path because the whole database set is in heap.**

The flag is not in `cluster.yaml`. It is read per operation from the **control surface** (§5.4a):
changing it takes effect without a restart, reaches every replica within seconds, and can be
addressed to a subset of replicas. That is what makes every state in §5.9 escapable in seconds, and
it is this workstream's deliverable rather than a mechanism assumed from elsewhere.

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
  - [ ] D0 Control surface — §5.4a. **The one this workstream owns end to end**: every rollback in
        §5.9 is a write to it, and no state below is safe before it exists
  - [ ] D1 HTS repository seam — §5.3
  - [ ] D2 Cache seam — §5.4
  - [ ] D3 Resolver seam — §5.5
  - [ ] D4 `DatabasesService` seam — §5.6
  - [ ] D5 REST namespace surface (Workstream 1 boundary) — §5.7
- [ ] **E. Population** — §5.8. Registrar primitive; explicit backfill with a completion guarantee;
      populate-on-write without one; the cold-database poke. Sub-checklist there.
- [ ] **F. Scale and MySQL budget** — §5.8.4 and §5.10. Property bound (G4) → memory bound (M7) →
      overflow behaviour. Read ceiling per replica.
- [ ] **G. Migration state plan** — §5.9. Nine states, each with truth/hazard/detection/rollback,
      each escapable in seconds through S7, and each **indefinitely sustainable** — the canary is
      assumed arbitrarily long, so no state is priced as a moment. Sub-checklist there.
- [ ] **H. Divergence observability** — §5.5.3. Counter taxonomy with a denominator, bounded
      cardinality, identifiers in sampled logs.
- [ ] **I. Pre-migration audits** — §5.9.1. Three queries that must be run before S1, because each
      can make the migration wrong in a way no state transition can fix.
- [ ] **J. The control surface** — §5.4a. A runtime-switchable source for the resolver mode and every
      migration flag: where the value is read, how a change propagates and within what bound, how a
      replica behaves when the source is unreachable, and how a change is addressed to a subset of
      replicas. **This workstream's deliverable**, not a downstream one: every rollback in §5.9 is a
      write to this surface, so the nine-state plan has no safety argument without it. It specifies
      no deployment topology and depends on none.
- [ ] **K. Open questions** — §5.11. Two, each with its alternative priced. Four earlier entries are
      decided and retired there rather than carried.
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
| 13 | **A database outlives its last table — in every case, and by design.** `ensure(db, WRITE_PATH)` runs from S2; `delete(databaseId)` is S8-only (§5.3); §5.8 names no caller that removes a row | §5.8; `SoftDeletedUserTableHtsJdbcRepository`; `services/housetables/ddl/0000__baseline.sql` | **Database entry** — the database outlives its tables (G1) | Today, dropping the last table in a database deletes the database. Under G1 it does not — and the owner has confirmed that **this is desired**: a database is a first-class object with its own lifecycle, and surviving the deletion of its last table is the *point* of the abstraction, not a cost of it. It is also the **general** case: hard drop, soft delete and purge all reach it, so *every* database that loses its last table keeps its record. Framing it as the soft-delete corner understates its membership, and three things follow from getting that right. **(a)** It is the first delivery of G1, and it lands at S6, the first state that serves the stored answer — measured before it is served and reviewed at S6's gate (§5.9.2), because intended behaviour that arrives unannounced is still a 3am page. It is **not** an exemption to M3 and is no longer written as one. **(b)** The backfill's verification predicate is therefore **one-directional**: `derived \ stored` must be empty, and `stored \ derived` is expected by construction and gates nothing (§5.8.3). Count equality and an enumerated expected-extras set both fail, for the same reason and in the same way. **(c)** Downstream, `TablesClient.getDatabases()` (`:221,254`) will loop `searchTablesV1(dbName)` over table-less databases for nothing, and `getDatabaseMetadataList()` (`:321-326`) will hand them to `DatabaseOperationTask` and `TableDirectoryDeletionTask`; both are wasted work rather than incorrect work, and both are bounded by the measured size of the stored-only set. Reported by `database_stored_only_total`, never as a divergence and never alarmed (§5.5.3). Naming it up front is what stops it being triaged as a bug at 3am. |
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
  B's list within `databases.cache.refresh-interval` (recommended default **30s**, a runtime knob on
  the control surface of §5.4a). The cost of avoiding it is a query per call, which is option E.
  This is a **standing** property, not a migration-window one: with an arbitrarily long canary it
  holds for as long as the cache is on, and §5.9.2's S6 row states what a client can observe when
  some replicas serve from it and some do not. **This requires owner ratification** and is §5.11 Q1,
  with that default.
- **Refresh is incremental, not a reload.** Each replica polls
  `findAllModifiedSince(watermark)` on the interval. Typical result: zero rows. This is what makes
  M4's ceiling hold at N replicas.
- **Negative results are not cached** as a separate structure; the cache is the complete set, so
  absence in an authoritative cache *is* the negative result — but only when `isAuthoritative()`.
- **The cache has its own oracle, and it composes with the comparison.** A periodic **cache audit**
  re-reads the full `database_row` set from HTS on a long interval (`databases.cache.audit-interval`,
  default **1h**, staggered per replica so the fleet does not converge on one minute) and compares it
  against the heap: the set difference in both directions, and each projected field over the
  intersection. Divergence increments `database_cache_audit_divergence_total{kind,source}` and, above
  a threshold, drops `isAuthoritative()`, which degrades the resolver rather than serving a wrong
  list. **The composition is the point.** The comparison of §5.5.1 validates **derived ↔ store**;
  this audit validates **store ↔ cache**; only the two together validate the answer `trust` actually
  serves, and neither alone does. The audit also runs **inline and free at the comparison's rate**:
  whenever a sampled comparison has just read the store, that same read is compared against the heap
  (`source="inline_sample"`), so store↔cache is checked as often as derived↔store and the periodic
  pass (`source="periodic"`) is the coverage *between* samples rather than the sole oracle. Without
  it a cache defect would present as `missing_in_stored`, whose stated first action sends the
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

### 5.4a Contract — the control surface

**Owner: this workstream.** Every flag in §5.9 is read through this seam, and every rollback in this
document is one write to it. It is a contract and not a mechanism note because the migration plan's
safety argument is entirely a claim about how fast a mistake can be undone, and that claim is a
property of this surface and of nothing else.

**Why it cannot be `cluster.yaml`.** `ClusterProperties` is a `@Configuration` bean whose fields are
`@Value`-bound against a file-backed `@PropertySource`, and `@Value` resolves once at bean
construction; there is no `@RefreshScope`, no Spring Cloud Config and no dynamic property source
anywhere in the repository, and the four `@ConfigurationProperties` beans bind at startup too. A
`cluster.*` property therefore changes only by restarting the process. That evidence is unchanged;
its conclusion is not "rollback costs a rolling restart" but **these flags do not live in
`ClusterProperties`**. A rollback that waits on a restart is a rollback that may never complete, and
a state whose safety rests on one is not safe. The flags below are named `databases.*`, without the
`cluster.` prefix, precisely so that no reader assumes `cluster.yaml` is their source.

```
interface DatabaseControlSurface {
  ResolverMode  mode();                  // original | compare | trust
  boolean       enabled(Flag flag);      // the §5.9 booleans
  double        rate(RateKnob knob);     // sampling fractions and per-minute ceilings
  Duration      interval(IntervalKnob knob);
  Snapshot      snapshot();              // all of the above, coherent, for one operation
  Instant       appliedAt();             // when this replica last accepted a change
  boolean       isFresh();               // appliedAt within the staleness ceiling
}
```

**Where the value is read.** At the decision point, per operation — never captured in a field at
construction, never memoised across requests. One operation reads `snapshot()` **once** and carries
it, so a request cannot straddle two modes: a list call that begins in `compare` finishes in
`compare` even if the mode changes underneath it. This single rule is what makes a mid-flight flip a
transition rather than a race, and it is why the surface returns a snapshot at all.

**How a change propagates.** An operator writes one value at one source of truth. Every replica
converges on it within the **propagation bound *P***: **target 5s, hard ceiling 30s, exported and
alarmed on**, measured as the interval between the write and the last replica's `appliedAt`. *P* is
the unit every rollback in §5.9 is priced in, replacing the rolling-restart time *T* that the
previous draft used. This design does not name the mechanism — a watched record, a polled endpoint
and a push channel all conform — but it names three obligations on it: converge within *P*; expose
`appliedAt` per replica, so convergence is observed rather than assumed; and apply a change to the
whole value it names rather than to a partially written one.

**When the source is unreachable: hold, never fail, never revert.**

- A replica that cannot reach the source **keeps serving its last known values, indefinitely.** It
  does not fail requests, does not degrade the resolver, and above all does **not** fall back to a
  compiled default. Reverting to a default on a partition is itself an unrequested mode change, and
  it would arrive at the moment an operator is least able to see it.
- Staleness is exported (`database_control_staleness_seconds`) and alarmed above a threshold,
  because the failure this creates is real and must be visible: a partitioned replica will not
  receive a rollback. **A rollback is complete when every replica's `appliedAt` covers it, not when
  the operator has written it** — which is why `appliedAt` is in the contract rather than in an
  implementation.
- The exception is a replica's first read at process start: with no last-known value it starts at
  the safe default (`original`, everything off).

**Targeting a subset of replicas.** A value may be addressed to a subset rather than to the whole
fleet. The contract is the *addressing*, not the topology: a change carries a selector over whatever
replica attributes the mechanism already supplies, and a replica applies the most specific value
matching it, falling back to the fleet-wide value. Two properties are required. **Membership is
observable** — every replica exports the mode it is actually serving, so "which replicas are on
`trust`" is answered by a metric and never inferred from a deployment manifest. And **a fleet-wide
write overrides every subset value at once**, so a rollback never has to enumerate the subsets a
rollout created.

**What this deliberately does not specify: any deployment topology.** This design does not require,
recommend, or assume a second Deployment, a separate ConfigMap, a per-pod annotation, or any other
arrangement of the fleet. How an operator's platform expresses "these replicas" is not this
document's business and not its dependency. The requirement is only that the surface can express a
subset at all — S6 *is* a subset rollout, and a control surface that can address only the whole fleet
turns S6 into S7.

**Independence and audit.** Each flag is independently settable; no transition in §5.9 requires two
values to change together, and if one ever did the design would owe a compound value rather than a
documented sequence, because a sequence has an intermediate state and an intermediate state is a
state. Every applied change is logged with key, old value, new value, selector and source, and
counted (`database_control_change_total{key}`).

**The consumer may not assume:** that all replicas hold the same value at the same instant (they
converge within *P*, and a partitioned replica may not); that a value read at the start of an
operation is still current at its end (it holds a snapshot, deliberately); or that writing a value
is the same as it taking effect (read `appliedAt`).

**Precondition, stated once and inherited by every state below: no transition in §5.9 may be taken
before this surface exists and *P* has been measured.** S1 through S7 are safe *because* they are
escapable in seconds. Without the surface they are escapable only by a restart whose completion is
not guaranteed, and the nine-state plan is nine one-way doors wearing flag labels.

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

Flag: `databases.resolver.mode = original | compare | trust`, read from the control surface of
§5.4a. Default `original`.

**What a mode change actually costs — stated, because the nine-state plan's safety argument rests
on it.** The mode is read per operation from §5.4a, not from `ClusterProperties`, so a change takes
effect **without a process restart** and every replica has applied it within the propagation bound
*P* (target 5s, ceiling 30s). Every S1–S7 rollback is one write of one value, and `trust → original`
is that one write, not a sequence of hops.

That is a deliberate change of position and the reason is worth stating plainly. An earlier draft
priced these rollbacks as a `cluster.yaml` remount plus a rolling restart, bounded by the fleet's
rolling-restart time *T*, and declined to make a dynamic-config mechanism a prerequisite. The owner
has since supplied *T*: long enough that it matters, to be assumed long, and to be assumed never to
complete under a rollback. A rollback that may never complete is not a rollback — and every "this
state is safe because you can back out of it" claim in §5.9 was resting on one. The mechanism that
removes that dependency is therefore not a downstream nicety and not somebody else's mechanism; it
is this workstream's deliverable (§5.4a, checklist J), and **no state transition may be taken before
it exists.**

#### 5.5.1 The three modes

| Mode | Reads original | Reads stored | Returns | MySQL cost vs today | Rollback |
|---|---|---|---|---|---|
| `original` | yes | **no** | original | identical | n/a — this *is* the rollback target |
| `compare` | yes | yes (a **sampled read-through to `database_row`**) | **original** (the stored result is discarded) | identical, **plus** a rate-limited store read — a ceiling per replica per minute, never one per request (below) | one value → `original`, applied within *P* |
| `trust` | yes, **sampled** — the comparison keeps running | yes | stored | zero MySQL on the request path; the sampled comparison keeps a bounded background cost, including the derived scan on its own tighter ceiling | one value → `compare` or `original`, applied within *P*. `trust → original` is **one** write |

**The comparison must be unable to change the response.** Four rules make that structural rather
than aspirational, and all four survive unchanged now that the stored read is a read-through to HTS
rather than a heap read — the read is issued after the authoritative result exists, on a copy, and
its result is only ever counted:

1. The stored read and the comparison run **after** the authoritative result is fully materialised —
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
   `database_resolver_compare_skipped_total{reason="deadline"}` and returns the authoritative
   result.
4. The read-through has **its own budget**: a request timeout shorter than the comparison's time
   box, and a bounded concurrency separate from the request path's, so a slow `database_row` read
   can never consume capacity the authoritative path needs. Exhausting it counts
   `database_resolver_compare_skipped_total{reason="budget"}` and skips the comparison. This rule is
   new with the read-through; a heap read needed no budget, and a MySQL read does.

**What the comparison reads: `database_row` itself.** The comparison issues a **read-through to
HTS** — `findAll(allDatabases())` for the list operations, `findById` for the point operations — and
compares that against the derived answer. It does not consult the cache. This is the requirement as
stated (M5), and the reason is structural rather than procedural: the mode exists to validate the
**store**, and a comparison against the cache cannot catch a bug in the cache, which is the one
net-new component in the design. An earlier draft substituted the cache to keep the comparison
cheap; that substitution is withdrawn, and with it §5.11's old Q2.

**The cost is bounded by sampling, not by dodging the read.** MySQL load is a real constraint (M4),
and a read-through on every request on every replica for an unbounded period is not affordable. The
contract is therefore a sampled, ceilinged comparison:

| Knob | Default | Contract |
|---|---|---|
| `databases.resolver.compare.sample-rate` | `0.05` | The fraction of *eligible* operations compared. Sampling is decided **before** the stored read is issued, so an un-sampled comparison costs nothing at all — not a read, not a copy, not a deadline |
| `databases.resolver.compare.max-store-reads-per-minute` | `4` per replica | A hard ceiling the sample rate cannot exceed. A traffic spike cannot turn a sample rate into a load event; the ceiling wins and the excess counts `compare_skipped{reason="rate_limited"}` |
| `databases.resolver.compare.max-derived-reads-per-minute` | `1` per replica | The same ceiling for the other direction. In `trust` the served answer is the stored one and the comparison's extra read is the derived `DISTINCT` scan — the expensive one — so it gets the tighter ceiling |
| `databases.resolver.compare.point-read-sample-rate` | `0.01` | Point comparisons are primary-key lookups: individually cheap, and individually far less informative than a list comparison, which covers the whole key space in one read |

Every one of these is a **runtime-switchable knob on the control surface** (§5.4a). The rate is
turned up to investigate and back down to shed load, in seconds, with no restart. That is what makes
an unbounded comparison affordable: the cost is a dial an operator holds, not a property of the
state they are in. It is also why §5.9 can leave `compare` on forever without §5.10's budget
failing.

**What a rate buys, so a rate can be chosen against evidence rather than guessed.** The load-bearing
fact is that a list comparison is not a sample of one database: it compares the **whole set** in one
read.

- **A persistent divergence — a missing row, a stale backfill, a case collapse — diverges on *every*
  comparison.** One sampled comparison detects it. What sampling costs is detection *latency*, and
  the ceiling sets that exactly: at 4 store reads per minute per replica, a replica observes a
  persistent divergence within 15 seconds of its first comparison after the divergence appears, and
  *some* replica in a 20-replica fleet observes it within a second or two. Sampling does not weaken
  detection of the failure class the migration is actually afraid of, which is the class that makes
  `trust` under-report.
- **An intermittent divergence** — one affecting a fraction *q* of operations, such as a comparison
  straddling a concurrent drop — needs *n* sampled comparisons for 99% confidence, where
  *n* = ln(0.01) / ln(1 − *q*) ≈ 4.6 / *q*. At the defaults a 20-replica fleet takes ≈ 80 sampled
  list comparisons per minute, ≈ 115,000 per day. So *q* = 10⁻² is caught within a minute,
  *q* = 10⁻⁴ within about 35 minutes, and *q* = 10⁻⁶ within about two days. **Choose the rate
  against the smallest *q* worth catching, not against a calendar** — and note that a long-running
  canary is an *advantage* here, because coverage accumulates for as long as the state lasts. Under
  the previous "soak for a window" framing the rate would have had to be sized to a deadline; with no
  deadline it can be sized to the load budget instead, and the confidence arrives on its own.
- **Coverage is reported, not assumed.** `database_resolver_comparison_total` is read against
  `database_resolver_compare_eligible_total`; that ratio is the achieved sample rate, and it is what
  a promotion gate in §5.9 states a number for. No gate in this design is phrased as a duration.

**Two oracles, composed — which is why §5.4's cache audit survives the change.** The sampled
comparison validates **derived ↔ store**. The cache audit validates **store ↔ cache**. Composed,
they validate the answer `trust` actually serves; neither alone does. And they compose cheaply: when
a comparison sample runs, the store read it has already issued is compared against the served cache
answer too, at no extra read, so store↔cache is checked at the comparison's rate and the periodic
audit becomes the coverage *between* samples rather than the only oracle. Both instruments are
required; §5.9's gates read both.

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
today. Getting this backwards would have S6 entered on S5 evidence that structurally could not
observe an ordering difference — S6's own named residual hazard — while the skip fired on the only
ordering the two paths are guaranteed to agree on.

**The comparison is symmetric, and it does not end.** Which mode is on decides which answer is
*served*, not which pair is compared: in `compare` the derived answer is served and the stored read
is the extra one; in `trust` the stored answer is served and the derived read is the extra one. Both
are sampled, both run off the response path, and the comparison therefore keeps running as a
**standing invariant check** for as long as both paths exist — which, with the derived path compiled
until S9, is indefinitely. It stops being a migration instrument with an end date and becomes the
thing that notices if the store and the table population ever drift apart again. §5.5.3 states what
that changes about how the counters are read and alarmed.

#### 5.5.3 Divergence counters — designed to say *where*, and read as a standing signal

A counter that only says "they differed" sends an operator to a full-table diff. These say which
side, which kind, and how much, with bounded cardinality. Because the comparison never ends
(§5.5.2), they are an **ongoing health signal rather than a one-time migration gate**, and the
thresholds at the end of this section are written for that reading.

**Names** (service-owned constants class in the style of `ViewMetricsConstant`, so a rename is a
Tables Service change and not a `services:common` release):

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `database_resolver_comparison_total` | counter | `op` | **The divergence denominator.** Without it a divergence count is unreadable. |
| `database_resolver_compare_eligible_total` | counter | `op` | **The coverage denominator.** Operations that *could* have been compared. `comparison_total / eligible_total` is the achieved sample rate, and it is what a §5.9 promotion gate states a number for. |
| `database_resolver_agreement_total` | counter | `op` | Comparisons that found nothing. Agreement rate = this / comparison. |
| `database_resolver_divergence_events_total` | counter | `op`, `kind` | One per diverging *comparison*. |
| `database_resolver_divergence_items_total` | counter | `op`, `kind` | Incremented by the **magnitude** — how many databases differ, not how many requests noticed. |
| `database_resolver_divergence_magnitude` | distribution summary | `op`, `kind` | The shape of the divergence over time: is it one stuck database or a growing gap? |
| `database_resolver_compare_error_total` | counter | `class` | The compare block threw. |
| `database_resolver_compare_skipped_total` | counter | `reason` | `sampled_out` (below the sample rate — the expected majority), `rate_limited` (the per-minute ceiling won), `budget` (the read-through's own budget, §5.5.1 rule 4), `deadline`, `unordered_source` (the unpaged derived read has no `ORDER BY`; order only, §5.5.2), `cache_not_authoritative`. |
| `database_stored_only_total` | gauge | — | Databases in the store with no live tables. **Not a divergence**: it is G1 working (§5.2 #13). Reviewed at S6's entry gate, reported thereafter, never alarmed. |
| `database_resolver_reads_total` | counter | `mode`, `source` | `source ∈ {derived, cache, hts_point_read}`. This is the M4 instrument. |
| `database_cache_size` | gauge | — | Entries resident. |
| `database_cache_weight_bytes` | gauge | — | Against the cap. |
| `database_cache_overflow_total` | counter | — | Authoritativeness lost. |
| `database_cache_staleness_seconds` | gauge | — | Now minus last successful refresh. |
| `database_cache_audit_divergence_total` | counter | `kind`, `source` | The §5.4 cache audit found the heap and `database_row` disagreeing. `source` is `periodic` (the hourly pass) or `inline_sample` (the free check riding on a comparison's store read). This is the instrument that watches **store ↔ cache**; the comparison watches **derived ↔ store**; §5.5.1 explains why both are needed. |
| `database_resolver_mode` | gauge | `mode` | The mode this replica is **actually serving**. Subset membership during S6 is read here and never inferred from a deployment manifest (§5.4a). |
| `database_control_staleness_seconds` | gauge | — | Now minus this replica's `appliedAt` (§5.4a). A replica above the threshold will not receive a rollback. |
| `database_control_change_total` | counter | `key` | Control-surface changes this replica has applied. |

**`kind` — the taxonomy is the design.** Each value names a *cause*, which is what makes the counter
actionable:

| `kind` | Meaning | Expected? | First action |
|---|---|---|---|
| `missing_in_stored` | Has tables, no `database_row` | **No, after S3** | Backfill gap or populate-on-write gap. Poke it (§5.8.3); investigate the write path. |
| `extra_in_stored_empty` | Row exists, zero live tables | **Yes, from S3, permanently** — #13 in its general form: any database whose last table was dropped, plus every user-created empty database from S8 | None, ever. This is G1 working and the owner has confirmed it is desired, so it is **not counted as divergence**: it is reported on `database_stored_only_total` and excluded from `divergence_events_total`. Its magnitude is still not noise — it is the exact set that newly appears in `/v1/databases`, and S6's entry gate reads it once before that happens (§5.9.2). |
| `extra_in_stored_nonempty` | Row exists, tables exist, derived did not list it | **No** | Almost certainly a read straddling a concurrent drop; re-compare once before escalating. |
| `case_variant_collapse` | Derived lists ≥2 case spellings, stored has one | **Yes if the pre-migration audit found any** (§5.9.1); no otherwise | Pre-existing data inconsistency, surfaced not caused. Resolve as data. |
| `attribute_mismatch` (tag `field`) | Present in both, a projected field differs | **No** | Today the only possible `field` is `clusterId` (#11). |
| `order_mismatch` | Same set, different sequence | Tolerable for `/v1`; **not** for `/v2` paging | Paged clients see shifted page boundaries. |
| `count_mismatch` | `totalElements` differs on a paged compare | **No** | Usually implies one of the set kinds above. |
| `page_content_mismatch` | Same total, different page N | **No** | Ordering or a straddled write. |

**From S8, the set check is one-directional.** Once users can create empty databases and drop
databases, the derived path stops being an oracle for *membership* — it never could see a database
with no tables. The comparison does not become meaningless and S8 does not have to wait for
`compare` to end: the set check narrows to the half that stays true forever, **`derived \ stored`
must be empty** (a G3 violation, always paged), while `stored \ derived` is expected by construction
and is reported on `database_stored_only_total`. The attribute, order, count and page checks are
unaffected. This is what lets `compare` and S8 coexist indefinitely, which an unbounded canary
requires (§5.9.2, S8).

**Cardinality and identifiers.** Tags are bounded — `op` (4 values) × `kind` (7 divergence values;
`extra_in_stored_empty` is kept in the table above for classification but is reported on its own
gauge, not as a divergence) × `field` (1), plus
the cache audit's own small `kind` × `source` space — so the whole taxonomy is a few dozen series. Database identifiers are **never** tags. They go to a
rate-limited WARN log carrying the diverging ids (capped at the first N per interval), a
`divergence_id` correlation field shared with the counter increment, and the counts per kind. That
is the pair that answers "where": the counter says which kind and how many, the log says which
databases.

**What is alarmed on, now that the comparison never ends.** A standing invariant check is read
differently from a migration gate: the question stops being "did anything ever differ" and becomes
"is the rate of disagreement where it was yesterday".

| Signal | Threshold | Why |
|---|---|---|
| `missing_in_stored` | **Page** on any occurrence surviving one re-comparison | A G3 violation, and the only kind that can make `trust` under-report. It must be identically zero in every state after S3, forever |
| `extra_in_stored_nonempty`, `attribute_mismatch`, `count_mismatch`, `page_content_mismatch` | Ticket above the trailing 7-day baseline; page if sustained above it for an hour | Straddled writes leave a small non-zero floor. The baseline is the honest threshold; zero is not |
| `case_variant_collapse` | Ticket on a **new** id, never on the count | The pre-flight audit's set is known and expected; `renameTable` can add to it (§5.9.1) |
| `database_stored_only_total` | **No alarm.** Reviewed once at S6's gate, reported thereafter | Alarming on it would be alarming on the design working |
| `comparison_total` at zero while `compare_eligible_total` climbs | **Page** | The check has silently stopped. A standing invariant check that is not running is worse than one that is failing, because it looks healthy |
| `database_cache_audit_divergence_total` | **Page** on any occurrence, either `source` | The cache has no other oracle |
| `database_control_staleness_seconds` | **Page** above 5 × *P* | A replica not receiving values will not receive a rollback (§5.4a) |

**The consumer may not assume:** that `compare` implies the stored path is correct (that is what S3's
verification marker is for, not what compare is for); that a zero divergence count means comparisons
ran — always read it against `database_resolver_comparison_total`; or that a zero
`comparison_total` means nothing was eligible — read it against
`database_resolver_compare_eligible_total`.

### 5.6 Contract — the `DatabasesService` seam

`DatabasesService` is already the public service boundary and **keeps all four of its existing
signatures unchanged**: `getAllDatabases()`, `getAllDatabases(page,size,sortBy)`,
`updateDatabaseAclPolicies(...)`, `getDatabaseAclPolicies(...)`. Its implementation stops calling
`openHouseInternalRepository.findAllIds()` and calls `DatabaseResolver` instead. That single
substitution is the entire Tables-Service-side change of states S4–S6, which is why the states are
cheap to enter and leave.

Widened with, **available from S8**. The gate is sequencing, not reluctance: explicit database
creation is a **goal of this design** (M1, and owner-confirmed), and a database is a first-class
object with its own lifecycle rather than a shadow of its tables. It is gated at S8 for one reason —
these four methods are the first *user-visible* change in the plan, and they are placed after the
read path has been proven rather than beside it. Nothing about the gate is a judgement on the
capability, and S8's flag turns it on in seconds like every other (§5.4a).

| Method | Contract |
|---|---|
| `createDatabase(databaseId, properties, actingPrincipal)` | **The capability this abstraction exists to deliver**, alongside M1's empty database. Requires a create privilege on the database (a new `Privileges` constant, in the shape of the `CREATE_VIEW` / `LIST_VIEW` constants the views work added). Idempotency is decided: `201` on create, `409 AlreadyExists` on a second call, matching the Iceberg spec's `createNamespace`. A created database has `origin=API` and no tables, and that is a normal, permanent, intended state for it. |
| `dropDatabase(databaseId, actingPrincipal)` | A populated database is **not** dropped: `409` / `NamespaceNotEmptyException`, never a cascade (W5, retired and decided; Workstream 1 decided the same way and holds the conformance test). Requires a drop privilege on the database. |
| `getDatabase(databaseId)` | `404` when absent — **only from S8**. Before S8 the absence of a database is not observable, per M3 and #6. |
| *(all four)* | A database with zero tables is listable, loadable, droppable and property-bearing, exactly like any other. There is no second-class empty database, and no path deletes one implicitly. |
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
it is a stored-only database, which is intended and permanent behaviour rather than damage (G1,
#13); and it needs no repair, because the retry that follows creates the table. The other order produces a table whose database
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
| When it applies | Once, at state S3, and re-runnable as a repair | Always on from S2 onward, so the *registered* set only ever grows — which is also precisely why a database outlives its last table (#13), the intended arrival of G1 |

They are not alternatives. **Populate-on-write is the floor and explicit backfill is the ceiling.**
Populate-on-write is turned on *first* (S2) so that the set of databases the backfill must cover
stops growing before the backfill starts; the backfill then closes the fixed remainder. Running the
backfill without populate-on-write already on means the backfill races table creation and can never
be declared complete.

#### 5.8.3 The completion guarantee, and the poke

The backfill's completion guarantee is a **verification marker**, not a log line:

1. The backfill records a resumable watermark (last `database_id` processed, in key order).
2. On finishing, it runs a **verification pass** that computes both **set differences** between
   `SELECT DISTINCT database_id FROM user_table_row` and `SELECT database_id FROM database_row`. It
   records `backfill_completed_at`, `backfill_verified_at`, `missing_from_stored` (the ids in
   `derived \ stored`) and the **size and a sample** of `stored \ derived`.
3. **`trust` mode refuses to start** unless `backfill_verified_at` is present and `derived \ stored`
   is **empty** — a hard precondition, since a database with tables and no row is a G3 violation.
   That is the whole predicate.

   **`stored \ derived` is deliberately not part of it.** Those are databases with no live tables,
   and the owner has confirmed that this is desired: a database's lifetime is independent of its
   tables (G1), so a stored database with no tables is the abstraction working, not a discrepancy to
   clear. Its size and a sample are recorded because an operator entering S6 should see how many
   there are (§5.9.2's entry gate), not because the number gates anything.

   **Two richer predicates were considered and both are wrong, in the same way.**
   `derived_count == stored_count` is unsatisfiable in practice: routine table drops push the counts
   apart within a day of S2 and never bring them back. An **enumerated expected-extras set** — every
   member of `stored \ derived` named in advance — is satisfiable only for an instant: the next
   table drop adds a member the marker does not name, so it is stale before it is read, and with an
   unbounded canary (§5.9) it is permanently stale. Both make routine operation falsify a
   precondition, which is exactly how operators learn to override preconditions. The one-directional
   predicate is satisfiable, stays satisfiable, needs no maintained list, and asserts the only thing
   that matters: **every database with a table has a row.** Simpler than the previous draft's
   predicate, and simpler *because* the empty database became intended rather than exceptional.

**The deliberate poke.** A cold database that the backfill somehow missed, or that appears through a
path nobody anticipated, is repaired by calling the same `ensure` primitive. Three pokes exist:

- **Operator poke** — `ensure` over an explicit list of database ids. The minimal, auditable repair.
- **Compare-mode self-heal** — when the comparator classifies `missing_in_stored`, it calls
  `ensure(id, POKE)`. Elegant, because the divergence detector becomes the repair mechanism, and it
  guarantees that any database anyone actually *looks at* is materialised. **Gated by its own flag**
  (`databases.resolver.compare.self-heal`), default **off**, because a read path that writes
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
- [ ] Verification pass computes **both set differences**, but only `derived \ stored` is the
      precondition. Neither count equality nor an enumerated expected-extras set is the predicate,
      and §5.8.3 says why each fails.
- [ ] `ensure` runs **before** the table put; the non-atomicity and the acceptable residue are
      stated (§5.8.1).
- [ ] `trust` mode's precondition reads the marker and refuses without it.
- [ ] Stored-only databases (#13, and S8's user-created ones) are recorded and reported, never
      gated on: they are G1 working, not a divergence to be cleared.
- [ ] Self-heal is a separate flag, default off, rate-limited — and, like every flag here,
      switchable at runtime (§5.4a), so turning it off during a write storm does not wait on a
      restart.
- [ ] Property bounds are enforced at two boundaries, not one.

### 5.9 Migration state plan

Nine states. Each is a resting place, and **"indefinitely" is meant literally**: the canary is
assumed to be arbitrarily long, so every state below must be correct *and affordable* for months,
not for the length of a rollout window. **Every rollback through S7 is one write to the control
surface of §5.4a, applied on every replica within the propagation bound *P* — seconds, no restart,
no new artifact, no release train.** S8 is the one transition that is not cleanly reversible; S9 is
reversible only by reverting a deploy. Those two are last and separate for exactly that reason.

**Three consequences of an unbounded canary, applied to every row below.**

1. **No promotion gate is a duration.** "It soaked for a week" is not evidence, and no gate in this
   plan is phrased that way any more. Forward transitions gate on **evidence**: a stated number of
   comparisons at a stated coverage, with stated counters at stated values, and the pre-flight and
   cache audits clean. A gate phrased in hours is unfalsifiable in a plan with no clock — and
   §5.5.1's confidence model gives the honest substitute, since coverage accumulates on its own for
   as long as a state lasts.
2. **Mixed operation is a permanent mode, not a moment.** S6 is some replicas on the stored path and
   some on the derived path, answering the same clients, possibly for months. Its safety argument is
   stated below in those terms, including exactly what a client can observe when two replicas
   disagree.
3. **Every cost is a standing cost.** The comparison's MySQL reads, the cache's memory and its
   staleness, and the double read path are priced per unit time in §5.10, not per transition. The
   sampling knobs of §5.5.1 are what make the first of them affordable indefinitely; without them an
   unbounded `compare` is an unbounded read amplification, which is why sampling is part of the
   contract rather than an optimisation.

Flags — **every one of them read through the control surface of §5.4a, none from `cluster.yaml`,
and each switchable at runtime with no restart**. They are named `databases.*` rather than
`cluster.databases.*` so that no reader mistakes their source:

- `databases.entity.enabled` — the HTS routes and repository exist and are reachable
- `databases.populate-on-write.enabled`, and `databases.populate-on-write.fatal` (the S2→S6 switch
  from "non-fatal to a table create" to "fatal")
- `databases.resolver.mode` — `original | compare | trust`, addressable to a subset of replicas
- `databases.resolver.compare.self-heal`
- `databases.resolver.compare.sample-rate`, `.max-store-reads-per-minute`,
  `.max-derived-reads-per-minute`, `.point-read-sample-rate` (§5.5.1)
- `databases.cache.enabled`, `.refresh-interval`, `.max-weight`, `.audit-interval`
- `databases.mutable.enabled` — the S8 gate

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
| **S1 — schema present, unused** | `entity.enabled=true`; everything else off | `database_row` exists and is empty. No code reads or writes it. `/hts/databases` is reachable but unused. **Precondition for this and every state below: the control surface of §5.4a exists and *P* has been measured.** | DDL applied wrong (unpinned collation, wrong charset, missing index on `lastModifiedTime`) | A schema-shape assertion in the HTS test suite; the deployment recipes that mount `ddl/` in filename order re-apply the migration path on every run, which is how the views stack keeps its DDL honest | `entity.enabled=false`, applied within *P*. The table stays and is inert. Dropping it is a DDL operation applied out of band by the MySQL/DDS team, not a configuration rollback — naming the drop as "the rollback" would have broken the preamble's own rule on its first row. |
| **S2 — populate on write** | `+ populate-on-write.enabled=true` | Every table create also calls `ensure(db, WRITE_PATH)`. Nothing reads `database_row`. The registered set starts growing and only ever grows — which is also why a database will outlive its last table from here on (#13), the intended arrival of G1. | `ensure` failure fails a table create — **the one real hazard in this state** | Table-create error rate; `database_registrar_error_total` | Flag off, applied within *P*. Rows remain and are inert. **Design obligation:** `ensure` failure must be non-fatal to the table create in S2–S5 (it is a population optimisation there, not a correctness requirement) and **fatal from S6**, the first state where a missing row is observable as a missing database. Both halves are one control-surface value (`populate-on-write.fatal`), so that switch is itself a flip applied within *P* and not a second deploy — otherwise S2 turns a new dependency into a new outage mode, or S6 turns a swallowed error into a lost database. |
| **S3 — backfill run and verified** | unchanged | `database_row` contains every database that has a live table. `backfill_verified_at` is set. | Backfill races creates (mitigated: S2 precedes it); backfill partially completes and is believed complete | The verification pass is the detection. A non-empty `derived \ stored` ⇒ not verified ⇒ `trust` refuses to start (§5.8.3). `stored \ derived` is recorded and gates nothing | Delete rows where `origin=BACKFILL` **and** `lastModifiedTime < backfill_started_at` — better, that carry the backfill's run id; clear the marker; re-run. An unqualified `origin=BACKFILL` delete is wrong and contradicts S2's "the registered set only grows": §5.8.1's `ensure` never mutates an existing row, so `origin` records who arrived **first**, and a database the backfill registered but that has been written to since is a live database, not backfill residue. **This is the one rollback in the plan that is a data operation rather than a flag** — and it is the one place that is acceptable, because nothing reads `database_row` until S6, so this rollback has no deadline and no user-visible surface. |
| **S4 — cache on** | `+ cache.enabled=true` | Each replica loads the full set at startup and refreshes on the watermark. Nothing serves from it yet. The cache audit starts here, and a clean audit is an S6 entry condition. | Load exceeds the weight cap; refresh silently stops. Both are **standing** risks rather than entry risks: the cache is resident and refreshing for as long as this state lasts, which is unbounded | `database_cache_size`, `database_cache_weight_bytes`, `database_cache_staleness_seconds`, `database_cache_overflow_total` — all four exist for this state | Flag off, applied within *P*. |
| **S5 — compare** | `resolver.mode=compare` | Responses are still the derived ones, byte for byte. The stored path is **read through to `database_row`** on a sampled basis and disagreements are counted (§5.5.1). **The state is affordable indefinitely**, which is exactly what the sampling knobs buy. | Compare throws, or is slow, and affects the response — structurally prevented by §5.5.1's four rules; the sample rate left so low that the coverage a promotion gate asks for never accumulates | The §5.5.3 taxonomy, read against **both** denominators: `comparison_total` for divergence, `compare_eligible_total` for coverage | `mode=original`, applied within *P*. |
| **S6 — trust on a subset of replicas** | `resolver.mode=trust`, addressed to a subset (§5.4a) | Some replicas serve the stored answer, the rest serve the derived one, to the same clients — **and this is a permanent operating mode, not a moment in a rollout.** It is safe because the comparison says the two paths agree, and because the one place they deliberately disagree is bounded, named and reviewed before entry. **Entry gate — evidence, not elapsed time:** `missing_in_stored` identically zero across at least 10,000 sampled list comparisons at a measured coverage at or above the configured sample rate; `database_cache_audit_divergence_total` zero over the same window; and `database_stored_only_total` membership read and reviewed, because that set is exactly what newly appears in `/v1/databases` and reviewing it is how G1's arrival stops being a surprise. | **What a client can observe while two replicas disagree — stated exactly, because it must hold for months.** Only two things differ. **(a) Stored-only databases.** A database with no live tables is listed by a `trust` replica and not by an `original`/`compare` one, so a client polling `/v1/databases` sees it appear and disappear with whichever replica answered. It is intended (G1), its membership is enumerable before entry, and nothing keys off it: the listing is not a lock, and no OpenHouse operation fails because a database is or is not in it. **(b) List latency for a newly registered database.** `ensure` runs before the table put, so the writing replica has it at once; other `trust` replicas list it within `cache.refresh-interval` (30s), while `original` replicas list it immediately. A client that creates a table in a brand-new database and lists straight away may not see the database for up to the refresh interval, and may see it, then not see it, on successive calls. The table itself is unaffected — table reads never traverse the database list. Both differences exist across replicas in S7 too; the subset makes them non-uniform, not new. | The §5.5.3 taxonomy read at its standing thresholds; `database_resolver_mode` per replica, so subset membership is **observed** and never inferred; `/v2/databases` paging assertions; client-side error rates | One write, addressed to the same subset or fleet-wide, applied within *P*. A fleet-wide write overrides every subset value at once (§5.4a), so a rollback never has to enumerate the subsets a rollout created. **No deployment topology is named, needed or depended on here**: expressing "these replicas" is the control surface's job, and this design states the requirement rather than a mechanism for meeting it. |
| **S7 — trust fleet-wide** | `resolver.mode=trust` everywhere | `GET /v1/databases` no longer scans `user_table_row` on any replica. M4 is met fleet-wide for the first time — in S6 it was met only on the subset (§5.10). The derived path is still compiled and one flag away, and the sampled comparison still runs against it. | Cache authoritativeness lost fleet-wide (every replica restarts into an overflow); the standing comparison's derived-scan ceiling set too high, so the scan the state exists to remove is quietly re-introduced in the background | `database_cache_overflow_total`; the resolver's degrade-to-`compare` behaviour as the safety net; `database_resolver_reads_total{source}`, which shows the scan gone from the request path and bounded in the background | **One write.** `mode=original` is a single value change applied within *P* — not two hops, not bounded by any restart. This row previously read "leaving `trust` entirely costs 2*T*, so size *T* before entering"; that is deleted, and with it the only reason S7 was a commitment rather than a state. |
| **S8 — databases become mutable** | `+ mutable.enabled=true` | Create / drop / properties are live. Explicit database creation is **a goal of this design, not a late concession** (owner-confirmed): a database is a first-class object with its own lifecycle, and this is the state where users get to exercise it. Empty databases exist by intent; `getDatabase` can 404; ACL operations on an absent database may start failing. | Every deferred *user-visible* change lands at once. The previous draft also barred this state while any replica was in `compare` — under an unbounded canary that gate makes S8 **unreachable**, because `compare` never ends. It is replaced rather than relaxed: from S8 the comparison's set check is **one-directional** (§5.5.3) — `derived \ stored` must be empty, still a G3 violation and still paged, while `stored \ derived` is expected by construction and reported rather than counted as divergence. The comparison stays meaningful with create and drop live, so `compare` and S8 coexist indefinitely. | The one-directional check above; create/drop authorization tests; the reserved-key rule of #9 live **before** the property map is writable | Flag off, applied within *P*, and it does stop new creates at once. But **this is the one genuinely irreversible transition in the plan**: user-created empty databases and user-written properties exist after it and have no derived equivalent to fall back to, so turning the flag off leaves data the `original` path cannot represent. Everything before S8 is escapable in seconds; S8 is not escapable at all. It is the only one, and it is §5.11 Q2. |
| **S9 — remove the derived path** | delete code | `findAllIds()` is gone from `DatabasesServiceImpl`; `listTables(Namespace.empty())`'s anti-pattern arm (#14) is deleted; the resolver collapses to one implementation. | **It deletes the standing invariant check.** With no derived path there is no second source to compare the store against, and `missing_in_stored` — the one signal that catches a G3 violation — can no longer be computed. The cache audit survives; the derived↔store oracle does not. | Normal release process | A revert and a deploy: a release train, not *P*. S9 is therefore **optional and unscheduled** — the only state this design does not recommend taking on a plan. Take it when the standing check has been quiet long enough that losing it is acceptable, or leave the derived path compiled indefinitely at the cost of some dead code and roughly one sampled scan per replica per minute. That is a cheap price for an oracle, and an unbounded canary makes keeping it the default expectation. |

**Migration sub-checklist**

- [ ] **The control surface (§5.4a) exists, its subset addressing works, and *P* is measured and
      recorded — before S1.** Nothing below is safe without it, and no transition may be taken
      before it.
- [ ] Pre-flight audits run and their results recorded (S0). Non-negotiable.
- [ ] Each flag defaults to the safe value, is independently settable, and takes effect without a
      restart.
- [ ] Every replica's served mode is observable as a metric, so subset membership during S6 is read
      rather than inferred.
- [ ] No promotion gate anywhere in this plan is phrased as an elapsed duration; every one names
      counters and a coverage.
- [ ] `ensure` failure is non-fatal to table creates in S2–S5 and fatal from S6; both halves stated.
- [ ] S2 strictly precedes S3.
- [ ] `trust` enforces the verification marker as a precondition in code, not in a runbook.
- [ ] S6 keeps some replicas in `compare` so the comparison keeps running against live `trust`.
- [ ] From S8 the comparison's set check is one-directional (§5.5.3), so `compare` and S8 coexist.
      The old "S8 is blocked while any replica is in `compare`" gate is **deleted**: with an
      unbounded canary it was a permanent block, not a gate.
- [ ] S6's subset is expressed through the control surface, not through a deployment arrangement,
      and a fleet-wide write can override it in one operation.
- [ ] S6's entry gate: `database_stored_only_total` membership is read and reviewed before entry,
      and the gate's other conditions are counter values, not a calendar.
- [ ] Every state's cost is checked as a **standing** cost (§5.10), affordable indefinitely, not as
      a transition cost.
- [ ] S8's irreversibility — the only one in the plan — is stated to the owner before entering it.
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
| Sampled comparison, stored side | ≤ `compare.max-store-reads-per-minute` (default 4/min) | one paged full read of `database_row` — **a ceiling, not a per-request cost**, and a runtime-switchable one (§5.4a, §5.5.1) |
| Sampled comparison, derived side (in `trust`) | ≤ `compare.max-derived-reads-per-minute` (default 1/min) | one `DISTINCT` scan of `user_table_row` — the expensive read, which is why its ceiling is four times tighter |
| **`GET /v1/databases`** | per request | **zero MySQL queries.** The comparison's reads are background and rate-limited; they are never on the request path |

At 20 replicas and a 30-second interval that is 40 indexed range queries per minute across the
fleet, against today's per-request `DISTINCT` scans. The load reduction is the *reason* the cache is
stateful, and it is why "static write-through" is the right shape rather than a TTL cache: a TTL
cache re-reads the whole set on expiry, which is option B's cost with option D's complexity.

**Priced per unit time, because nothing here is transient.** With an arbitrarily long canary every
row above is a standing cost, and each has to be acceptable forever rather than merely survivable
for a rollout window:

- **The comparison's reads** are the cost that changed when the comparison started reading MySQL
  (§5.5.1). Bounded by the two per-minute ceilings above, a 20-replica fleet pays ≤ 80 paged
  `database_row` reads and ≤ 20 `DISTINCT` scans per minute, fleet-wide, in perpetuity — against
  today's one `DISTINCT` scan *per request*. Both ceilings are dials on the control surface, so the
  standing cost is something an operator adjusts rather than something a state imposes.
- **The cache's memory** (≤ 1 GiB, §5.8.4) is resident from S4 for as long as the cache is on. That
  is a permanent allocation, not a migration-window one.
- **List staleness** (30s, §5.4) is permanent for the same reason, and §5.9.2's S6 row says what a
  client sees because of it.
- **The double read path** is the honest one: a replica in `compare` still serves from the derived
  path and still pays the `DISTINCT` scan **per request**. So M4's saving is proportional to the
  fraction of the fleet in `trust` — met on the S6 subset, met fleet-wide only at S7. A permanently
  mixed fleet is a permanently partial saving. That is acceptable, and it must not be mistaken for
  M4 being met.

**The moment the table does not cover: a fleet-wide restart.** "Once per process lifetime" is a
steady-state figure, and a fleet-wide restart starts every process within one window: twenty
replicas × 100 paged queries ≈ **2000 queries in a burst**, against `database_row` and not
`user_table_row`. It is small in absolute terms. It is named anyway — but note what changed: under
§5.4a **no rollback restarts anything**, so this burst no longer arrives at the moment an operator
is already rolling something back. It now arrives only on a deploy, which is a planned event with a
stagger of its own.

**Why the constraint is MySQL and not the heap.** 1 GB of heap on a service that already holds an
Iceberg metadata cache (`cluster.iceberg.tables.metadata-cache`) is a sizing conversation. A
`DISTINCT` scan of `user_table_row` on every `/v1/databases` call, from every replica, on every
scheduler run, is a shared resource that every other OpenHouse operation contends for. That asymmetry is what makes
the stateful design correct rather than merely faster.

### 5.11 Open questions: what the owner is being asked to ratify

**Two.** Four earlier entries are decided and retired below. Each remaining row states the
alternative and its price, so ratification is a choice rather than an approval.

| # | Question | This design's answer | The alternative, and what it costs |
|---|---|---|---|
| **Q1** | **List reads become eventually consistent across replicas.** Today `/v1/databases` is a live query and a table created on replica A is in replica B's list immediately; under the cache it is there within `refresh-interval` (§5.4). With an unbounded canary this is permanent, and in S6 it is also non-uniform — §5.9.2's S6 row states exactly what a client observes | Accept, default **30s** | Option E: no cache, one 100k-row query per call per replica. Every contract in §5.3, §5.5 and §5.6 survives unchanged, which is why the cache is behind its own flag (S4) — this alternative stays available after the fact |
| **Q2** | **S8 is a one-way door, and this design cannot make it otherwise.** Every state through S7 is escapable in seconds by one write to the control surface (§5.4a). S8 creates databases and properties the derived path cannot represent, so turning its flag off stops new creates but does not undo the ones already made. **It is the only irreversible transition in the plan**, and the owner should enter it knowing that | Accept, as a separately approved step with its own decision, not as the next tick of a rollout | There is no alternative that keeps both the capability and the escape route. Not taking S8 forfeits explicit database creation, empty databases and namespace properties — M1 and M2, the reasons this design exists. The honest choice is *when*, not *whether* |

**Retired from this list — decided, not deferred.**

- **`compare` reads the cache** (formerly Q2). **Declined by the owner.** The comparison reads
  `database_row` itself; its cost is bounded by sampling and per-replica ceilings, both runtime
  knobs, rather than by substituting the cache (§5.5.1, M5). A comparison against the cache cannot
  catch a bug in the cache, which is the one net-new component.
- **The database that outlives its last table** (formerly Q3, and M3's "named exemption"). **Desired,
  per the owner.** It is G1 working: a database is a first-class object with its own lifetime. It is
  measured and reviewed once at S6's entry gate so that its arrival is not a surprise, but it
  ratifies nothing and gates nothing (§5.2 #13, §5.8.3).
- **Explicit database creation.** **Confirmed in scope and a goal**, not a grudging late addition.
  It stays behind S8 only because it is the first user-visible change in the plan and belongs after
  the read path is proven (§5.6).
- **The cohort's deployment topology** (formerly checklist J). **Not this design's concern.** The
  only thing it leaves behind is a requirement on the control surface: a value must be addressable
  to a subset of replicas, and subset membership must be observable (§5.4a).
- **W5** — `dropNamespace` on a populated database: `409` / `NamespaceNotEmptyException`, never a
  cascade.

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
| **Verification marker** | The persisted evidence that the backfill finished and that every database with a live table has a row (`derived \ stored` empty); the precondition `trust` enforces; §5.8.3 |
| **Stored-only database** | A database that has a `database_row` and no live tables — because its last table was dropped, or because a user created it at S8. Intended behaviour (G1), reported on `database_stored_only_total`, never a divergence and never a gate; §5.2 #13, §5.8.3 |
| **Control surface** | The runtime-switchable source of the resolver mode and every migration flag: read per operation, converging on every replica within *P*, addressable to a subset, holding its last value when unreachable; §5.4a |
| **Standing invariant check** | The sampled comparison after it stops being a migration instrument: it runs for as long as both paths exist, and its counters are a health signal rather than a promotion gate; §5.5.2, §5.5.3 |
| ***P*** | The control surface's propagation bound: the interval between an operator writing a value and the last replica having applied it. Target 5s, ceiling 30s. Every rollback through S7 is priced in *P*. It replaces *T*, the fleet's rolling-restart time, which the previous draft used and which the owner has since said may never elapse; §5.4a |

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
`cluster/configs/src/main/java/com/linkedin/openhouse/cluster/configs/ClusterProperties.java` (`@Configuration` + `@Value` + file-backed `@PropertySource`; no `@RefreshScope` anywhere in the tree — the evidence for why the §5.4a flags do **not** live here) ·
`infra/recipes/k8s/templates/tables/tables-configmap.yaml`, `tables-deployment.yaml` (`cluster.yaml` is mounted and bound at startup; cited for that fact only — this design names no deployment topology) ·
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
