# HTS Embedding Plan — running the delta-harness against a REAL House Table Service

**Recommendation (lead with it): Option A — boot the real HTS as a second Spring context in the same
JVM and drive it over loopback HTTP. Zero changes to OpenHouse *main* code, zero business-logic
duplication, maximum fidelity. All work is confined to the `tables-test-fixtures` module.**

This is a *stacked* plan doc: it lands on a branch based on `claude/spark-scala-test-env-k7drzg`
(the delta-harness PR #9 branch), **not** on `main`, so it can be reviewed independently and folded
into PR #9 only if judged safe and non-invasive. No production code is touched by this document; it
is a design + recommendation.

---

## 1. The question the user asked

> "It sounds like we need an HTS impl that is wired directly. Key for me: we are **not adding or
> removing business logic**. If there is business logic in the HTTP layer, let me know and suggest a
> refactor. Small logic bits may be fine to re-create, but then we are testing a **parallel system and
> not the main system**. Evaluate both options, or even a third."

### 1a. Is there business logic in the HTTP layer? — **No.**

The HTS server is cleanly layered. I traced every soft-delete / restore / purge / versioning path:

| Layer | Class | What it does | Logic? |
|---|---|---|---|
| Controller | `UserHouseTablesController` | delegates to ApiHandler, builds `ResponseEntity` | none |
| ApiHandler | `OpenHouseUserTableHtsApiHandler`, `OpenHouseSoftDeletedUserTableHtsApiHandler` | validate request, map DTO, delegate | none (validation only) |
| **Service** | **`UserTablesServiceImpl`** | **`deleteUserTable`, `restoreUserTable`, `purgeSoftDeletedUserTables`, `getAllSoftDeletedTables`, version/MVCC checks** | **ALL of it** |
| Mapper | `SoftDeletedUserTablesMapper` | `calculateDeleteTimeAndTimeToLive` (default TTL 7 days) | logic (in service tier) |
| Repo | `SoftDeletedUserTableHtsJdbcRepository` | real JPA queries | persistence |

On the **client** side (what OpenHouse-tables calls), `HouseTableRepositoryImpl` is **pure transport**:
`@Autowired UserTableApi` (generated client) + `HouseTableMapper` + a `RetryTemplate` +
`handleHtsHttpError`. No decisions, no state. `deleteById(key)` merely defaults `purge=true` and calls
`apiInstance.deleteTable(..., !purge)`.

**Conclusion:** nothing needs to be extracted. **Option C (refactor main to lift logic out of the HTTP
layer) is unnecessary** and is not recommended — there is no stranded logic to lift.

### 1b. Why the harness needs this at all

The embedded `OpenHouseLocalServer` today wires a `@Primary` **in-memory stub**
(`tables-test-fixtures/.../HouseTablesH2Repository`, a `HashMap` of soft-deleted tables). It short-
circuits HTS entirely — no HTTP, no real TTL/purge/restore semantics. That is why the harness's
**undrop leg is SKIP-gated** (`control.undrop`, BUGS.md / REST-FIDELITY-EVAL.md). To exercise the real
undrop/restore/purge lifecycle we need a real HTS behind the catalog.

---

## 2. How the stub wins today (the exact seam)

```
Spark → OpenHouseCatalog → internalcatalog → HouseTableRepository  (interface, the seam)
                                                     │
              ┌──────────────────────────────────────┼─────────────────────────────────┐
              │ PRODUCTION                            │ EMBEDDED TEST (today)            │
        HouseTableRepositoryImpl                HouseTablesH2Repository (@Primary)        │
        (HTTP client → real HTS)                (HashMap stub, no HTS)                    │
```

`OpenHouseLocalServer.start()` boots `SpringH2TestApplication` (`@SpringBootApplication`, package
`com.linkedin.openhouse.tablestest`). Spring Boot's auto-config package therefore defaults to
`com.linkedin.openhouse.tablestest`, so **Spring-Data JPA repository scanning discovers the
`@Primary` `HouseTablesH2Repository` in that package** and it wins over the real
`HouseTableRepositoryImpl` (which is present but out-competed by `@Primary`).

So there are exactly two levers to flip to "real HTS":
1. **Suppress the `@Primary` stub** so `HouseTableRepositoryImpl` becomes the injected
   `HouseTableRepository`.
2. **Give it a real HTS to talk to**: set `cluster.housetables.base-uri`
   (`ClusterProperties.java:52`, `@Value("${cluster.housetables.base-uri:#{null}}")`) to a running HTS.

The repo **already has** a real-HTS-on-H2 boot: `services/housetables/.../e2e/SpringH2HtsApplication`
(`@SpringBootApplication` scanning `housetables.{api,dto.mapper,controller,services,repository,...}`
+ `@EntityScan housetables.model`). It boots the *real* `UserTablesServiceImpl` + JDBC repos on H2. It
lives in housetables' **test** source set — the only packaging obstacle for Option A.

---

## 3. The three options

### Option A — Two contexts, one JVM (real HTS over loopback HTTP)  ← RECOMMENDED

Boot a real HTS (H2-backed, à la `SpringH2HtsApplication`) as a **second Spring Boot context** on a
loopback port inside the harness JVM. Configure `OpenHouseLocalServer` to (i) not register the
`@Primary` stub and (ii) set `cluster.housetables.base-uri` to the HTS port. The catalog then reaches
HTS through the **real** `HouseTableRepositoryImpl` → generated `UserTableApi` → HTTP → real
controller/handler/service/JDBC.

- **Business logic:** 100% real, both sides. The delete→soft-delete→TTL→purge→restore path runs the
  production `UserTablesServiceImpl` + `SoftDeletedUserTablesMapper` + `SoftDeletedUserTableHtsJdbcRepository`.
- **Transport:** real. Exercises exactly the HTTP client, retry template, error mapping, and
  (de)serialization the production Spark path uses — the "parallel system" risk is **zero** because
  this *is* the main system end to end.
- **Precedent:** this is the repo's own e2e pattern (`SpringH2HtsApplication` exists for exactly this),
  and `jobs`/`tables` test resources already model a two-service localhost topology
  (`base-uri: localhost:8080` tables, `localhost:8181` … ; jobs test yaml wires an HTS base-uri too).
- **Mapper:** none new. The existing `HouseTableMapper` already speaks the generated **client** model
  (`housetables.client.model.UserTable`), which is the transport contract.

**Cost / invasiveness — confined to `tables-test-fixtures`:**
1. A fixtures-visible HTS boot entrypoint. `SpringH2HtsApplication` is in housetables *test* output
   (not published). Promote/replicate it into a fixtures-visible location (either a tiny
   `@SpringBootApplication` in the fixtures module reusing the same component/entity scans, or publish
   the housetables e2e boot via a test-fixtures artifact). ~1 small new class.
2. An `OpenHouseLocalServer` mode that: starts the HTS context on a port, sets
   `cluster.housetables.base-uri`, and keeps the `@Primary` stub off the classpath/scan for that run
   (e.g. a distinct boot profile that omits the stub package, or a `@Profile`/conditional on the stub).
3. Lifecycle wiring: start HTS before tables; stop in reverse; share one H2 or two.
4. Harness: un-SKIP the undrop lifecycle cases.

**OpenHouse main-code changes: 0.** All edits are in `tables-test-fixtures` (+ the harness). The one
subtlety is build-graph packaging (making the HTS boot reachable from the fixtures module without
dragging `services:housetables` into every consumer) — a Gradle wiring task, not a logic change.

### Option B — In-process bean adapter (delegate to real HTS beans, no HTTP)

Replace the `@Primary` stub with an adapter implementing `HouseTableRepository` that delegates
**directly to the real `UserTablesService` beans**, co-booted in the same Spring context. No HTTP hop.

- **Business logic:** real (delegates to `UserTablesServiceImpl`). Not duplicated.
- **Transport:** **bypassed.** The HTTP client, retry template, `handleHtsHttpError`, and
  (de)serialization are **not** exercised — so this *is* the "parallel system, not the main system"
  case the user flagged, for the transport slice specifically.
- **New glue required (this is the catch):** `UserTablesService` speaks the **api-spec** model
  (`housetables.api.spec.model.UserTable` / `housetables.dto.model.UserTableDto`). The existing
  `HouseTableMapper` only speaks the **client** model (`housetables.client.model.UserTable`,
  confirmed: `HouseTableMapper.java:6`). They are different types. So Option B needs a **new mechanical
  `HouseTable ↔ api.spec.UserTable/UserTableDto` mapper** — glue, not business logic, but new code the
  harness would own and that has no production counterpart.
- **Wiring risk:** a single Spring context must now scan **both** `tables.*`/`internal.catalog` **and**
  `housetables.*`, `@EntityScan` **both** `tables.model`/`internal.catalog.model` **and**
  `housetables.model`, and stand up **two** JPA repositories over H2. There is **no precedent** for a
  co-located tables+HTS context in the repo; entity-scan / bean-name / datasource collisions are a real
  integration cost.

**Invasiveness:** ~0–1 main files, but a new adapter + new mapper + a novel combined boot. Lower
fidelity than A (skips transport), more bespoke code than A.

### Option C — Extract HTS logic into a shared library, call directly  ← NOT recommended

Refactor OpenHouse main to pull soft-delete logic out of the service tier into a module both HTS and
the harness link. **Rejected:** §1a shows no logic is stranded in the HTTP layer — it already lives in
a clean service tier. Extraction would refactor production code for **zero** fidelity gain over A, and
is exactly the kind of invasive change the user wants to avoid on this stacked PR.

---

## 4. Scorecard

| Criterion | A: 2-contexts/HTTP | B: in-process adapter | C: extract |
|---|---|---|---|
| OpenHouse **main** files changed | **0** | 0–1 | many (refactor) |
| Business logic duplicated | **none** | none | none (but moved) |
| New glue code | **none** (reuses client mapper) | new adapter **+ new mapper** | new module + move |
| Transport path exercised (real system) | **yes (full)** | no (bypassed) | no |
| "Parallel system" risk | **none** | transport slice only | logic now shared, low |
| Follows existing repo pattern | **yes** (`SpringH2HtsApplication`) | no precedent | no |
| Main integration risk | HTS lifecycle + build packaging | dual entity-scan / dual JPA in one ctx | production refactor blast radius |
| Fidelity | **highest** | high (minus transport) | high |
| **Invasiveness verdict** | **lowest to main; test-only** | low-med, bespoke | **highest** |

**Recommendation: Option A.** Least invasive to production (0 files), no duplicated logic, no new
mapper, highest fidelity, and it mirrors a pattern the repo already ships. Option B is a viable
fallback only if the two-context lifecycle proves impractical in the harness JVM — and it buys a new
mapper + a novel co-boot while *losing* transport coverage. Option C is off the table.

---

## 5. Undrop is a PREPARATION AXIS — it re-runs the whole battery (~doubles the surface)

Undrop is a **P-axis preparation**, exactly like RTAS — **not** a small lifecycle sub-block. For every
table shape and every prep, the harness will: **seed → soft-delete → restore → then run the ENTIRE
battery on the restored table**. The thing under test is not "does restore return a table"; it is
**"does every feature's state survive the drop→undrop round-trip"** — snapshot lineage, refs/branches,
partition spec, sort order, table properties, policies/PII tags, MoR position-delete files, and schema-
evolution state. That is a full modality audit of restore's *destruction set* against every feature's
*state-dependency set* (the same lens that surfaced G9/G10 for RTAS). It roughly **doubles the runnable
surface (~660 order), matching the original BUILD-STATUS estimate** — my earlier "~10–20 cases" note was
wrong and is retracted.

**What `purge=true` actually changes — the setup mechanism, not the scope.** The customer `DROP TABLE`
path hard-codes `purge=true` (`HouseTableRepositoryImpl.deleteById(key)` → `deleteTable(..., !purge)`),
so a Spark drop **hard-deletes** and cannot be undropped. Therefore the harness cannot create an
undrop-prep table via Spark DDL; it must drive HTS's **soft-delete + restore directly** (the
`UserTablesService` soft-delete path + `restoreUserTable`), then hand the restored table back to Spark
and run the battery. This is precisely why a **real HTS** (Option A) is required to build the leg at all
— the in-memory stub can't model soft-delete/restore/purge with fidelity.

Two distinct sub-blocks result, and both need building:
1. **Undrop-prep battery (~660 order):** restored-table × the full non-vacuous operation surface (all
   DML×M, schema states, branch/WAP, DDL×consumer, TT, compaction). This is the surface-doubling leg.
2. **Restore admin-lifecycle (~10–20):** TTL/purge window, restore-before-purge, restore-after-purge
   (rejected), restore-name-collision, list-soft-deleted. A *small* block that lives *alongside* — not
   *instead of* — the prep battery.

---

## 6. Recommended sequencing (if Option A is approved)

1. Fixtures-visible HTS boot entrypoint (promote/replicate `SpringH2HtsApplication`).
2. `OpenHouseLocalServer` real-HTS mode: start HTS on a port, set `cluster.housetables.base-uri`,
   suppress the `@Primary` stub for that boot; wire start/stop lifecycle.
3. Green the existing suite unchanged against real HTS (regression gate — behavior must not move).
4. Add the undrop preparation (`createAndSeedUndropped`: seed → HTS soft-delete → HTS restore) and
   route the **full non-vacuous battery** through it — the surface-doubling P-axis leg (~660, per §5.1).
5. Add the restore admin-lifecycle sub-block (~10–20, per §5.2).
6. Update BUILD-STATUS.md (undrop leg restored to ~660 as the real surface-doubling block, +~20 admin)
   and REST-FIDELITY-EVAL.md (stub → real HTS).

All of the above is test/fixtures code. If review agrees it's non-invasive, it merges into PR #9; if
not, it stays an isolated stacked PR.
