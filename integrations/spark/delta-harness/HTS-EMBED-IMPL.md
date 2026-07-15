# HTS Embedding — Execution Plan & Verification (Option A)

Companion to `HTS-EMBED-PLAN.md` (the design). This is the **build** plan: concrete wiring, a phased
checklist, and an explicit verification step for every phase. Order is deliberate: **stand up the real
HTS → swap the stub → green the existing suite (smoke slice, then full loop) → only then add the undrop
battery.** Nothing new is tested until the *existing* surface is proven unchanged against real HTS.

Tracked live in the task list (#11–#16). Rule: no phase is "done" until its verification step passes.

---

## 0. Where things stand (facts established by recon)

- Harness boots the tables server via `OpenHouseEnv.start()` → `new OpenHouseLocalServer()` →
  `SpringH2TestApplication` (`OpenHouseMatrix.scala:3692`). Spark's catalog is wired to that server's
  REST URI. **No `cluster.housetables.base-uri` is set** — the `@Primary` `HouseTablesH2Repository`
  stub (HashMap soft-delete) short-circuits HTS entirely.
- The harness classpath is the itest module's `testRuntimeClasspath`, resolved as **unshaded individual
  jars** by `scripts/print-cp.init.gradle` (424 entries). It contains `tables-test-fixtures` and
  `internalcatalog` **but NOT `services:housetables`** — the real HTS classes are absent today.
- Because the harness uses **unshaded** jars (not the fixtures uber jar), the shadow-relocation
  fragility noted in `tables-test-fixtures-common.gradle:84` ("picking up HouseTableRepositoryImp / H2 /
  hibernate") **does not apply to the harness path**. That is the key de-risker for Option A here.
- `SpringH2HtsApplication` (real HTS on H2) exists but lives in housetables' **test** source set —
  not in the published jar.
- The `@Primary` stub is discovered by Spring-Data JPA repository scanning, whose base package defaults
  to `SpringH2TestApplication`'s package `com.linkedin.openhouse.tablestest` (same package as the stub).

## 0a. Honest invasiveness (correcting "0 files")

Option A is **0 production / 0 shared-module files** — but not literally zero test files. To flip the
tables server from stub to real HTTP client we need **one harness-owned tables boot class** (a
`@SpringBootApplication` whose package/JPA-scan excludes the stub and whose defaults set
`cluster.housetables.base-uri`) plus **classpath injection** of `services:housetables` into the
harness's own `print-cp.init.gradle`. All of it lives under `integrations/spark/delta-harness/` (+ its
init script) — **no edit to `services/*`, `iceberg/*`, `cluster/*`, or `tables-test-fixtures`.** If stub
suppression proves impractical from a harness-owned boot, the documented fallback is a **one-line**
`@ConditionalOnProperty` on the shared stub (a shared-fixtures edit) — kept as plan B only.

---

## Phase 1 — Real HTS on the harness classpath, booted as a 2nd context  (task #12)

**Do:**
1. `print-cp.init.gradle`: add `:services:housetables` (main runtime output + its runtime deps) and the
   housetables **test** output (for `SpringH2HtsApplication`) to the resolved harness classpath. If
   pulling the test output is awkward, replicate a 5-line `@SpringBootApplication` HTS boot (same
   `@ComponentScan`/`@EntityScan` as `SpringH2HtsApplication`) as a harness-owned class instead.
2. In the Scala harness, add `HtsEnv.start(port)`: `SpringApplication.run(<htsBootClass>, "--server.port=0")`,
   capture the actual port from the `WebServerApplicationContext` (mirror `OpenHouseLocalServer`'s
   tomcat-fix + port capture). Use a separate H2 datasource (own JDBC URL) so it is a clean HTS DB.

**Verify:**
- HTS context boots without bean/entity-scan errors; log prints the bound port.
- An in-JVM HTTP GET to `http://localhost:<htsPort>/hts/tables?databaseId=...` (or the actuator health
  endpoint) returns 200. Confirms the real controller/handler/service/JDBC stack is live.
- Guard: the tables context is **not** started yet in this phase — isolate HTS-boot failures.

---

## Phase 2 — Swap the stub for the real HTTP client  (task #13)

**Do:**
1. Add a harness-owned tables boot class, e.g. `harness.boot.RealHtsTablesApplication`:
   - `@SpringBootApplication` in a package that does **not** contain the stub.
   - `@ComponentScan` the tables packages incl. `internal.catalog` + `internal.catalog.repository`
     (so `HouseTableRepositoryImpl`, the real HTTP client, is the injected `HouseTableRepository`),
     **excluding** `com.linkedin.openhouse.tablestest`.
   - `@EnableJpaRepositories` over the real repo packages only — **not** `tablestest` — so the
     `@Primary` stub proxy is never created.
   - `@EntityScan` identical to `SpringH2TestApplication`.
   - default property `cluster.housetables.base-uri = http://localhost:<htsPort>`.
   - carry over the no-op file-securer `@Bean` from `SpringH2TestApplication`.
2. Give the harness a `realHts` mode that boots this app (replicating OpenHouseLocalServer's ~30 lines
   of SpringApplication+port capture) instead of `new OpenHouseLocalServer()`, ordered after HtsEnv.
3. Keep the stub path as the default (env flag `HARNESS_REAL_HTS=1` opts in) so the existing green
   baseline is always reproducible.

**Verify:**
- Boot logs show `HouseTableRepositoryImpl` injected as `HouseTableRepository` (not the stub proxy).
- A single Spark `CREATE TABLE` + `INSERT` round-trips: the row reads back, **and** the table row is
  present in the **HTS** H2 DB (query the HTS `/hts/tables` endpoint or its DB) — proving the write went
  through real HTS, not an in-JVM HashMap.
- A Spark `DROP TABLE` results in a **hard delete** at HTS (confirms `purge=true` path end-to-end) —
  this is the fact that forces undrop to be driven via the HTS admin API in Phase 4.

---

## Phase 3 — Regression gate: smoke slice, then full loop  (task #14)

**Do (tight loop first, per the user's instruction):**
1. Smoke a small case-id slice against real HTS: e.g. `HARNESS_REAL_HTS=1 ./run-openhouse.sh delete parquet`
   then a slightly wider slice (`create`, `branchWap`, `prep.rtas`, `control.lock`). ~25–60s each.
2. Fix any real-HTS-specific wiring fallout in the loop (timeouts, retry, error-mapping surfacing as
   different typed exceptions than the stub). Any behavior that legitimately differs under real HTS gets
   pinned as a characterization, not silently normalized.
3. Only once slices are green, run the **full 1697-case suite** under real HTS as the regression gate.

**Verify:**
- Full suite result under real HTS matches the stub baseline **case-for-case** except intended changes:
  same pass/skip/fail counts, with `control.undrop` still SKIP until Phase 4. Any diff is triaged and
  either fixed or explicitly pinned. Record the run in `VERIFIED-RUN-openhouse.txt` (real-HTS variant).

---

## Phase 4 — Undrop as a PREPARATION AXIS (surface-doubling)  (task #15)

This is the payload. Undrop is a P-axis prep like RTAS — the whole battery runs **on the restored
table**, verifying every feature's state survives the drop→undrop round-trip.

**Confirmed mechanism (endpoints):**
- Soft-delete is HTS-only (customer `DROP` → `catalog.dropTable(id, purge=true)` → HTS row hard-deleted,
  `OpenHouseInternalRepositoryImpl.deleteById:764`). Drive it directly on the embedded HTS:
  `DELETE {htsUri}/v1/hts/tables?databaseId=<db>&tableId=<t>&isSoftDelete=true`
  (`UserHouseTablesController` V1 delete, `isSoftDelete` param).
- List to recover the `deletedAtMs`: `GET {tablesUri}/v1/databases/<db>/softDeletedTables`
  (Tables API) or `GET {htsUri}/hts/tables/querySoftDeleted`.
- Restore: `PUT {tablesUri}/v1/databases/<db>/tables/<t>/restore?deletedAtMs=<ms>` (Tables API →
  `restoreTable` → HTS `restoreUserTable`).
- The harness must expose `htsUri` to cases (add to `Ctx`), since soft-delete bypasses the customer API.

**Do:**
1. `createAndSeedUndropped(layout, prep, n)`: seed the table via Spark → `DELETE .../v1/hts/tables?...&
   isSoftDelete=true` on the embedded HTS → list to get `deletedAtMs` → `PUT .../restore?deletedAtMs=`
   → return the restored table to Spark. Assert the table is queryable again post-restore.
2. Route the **full non-vacuous operation surface** through the restored table (mirror how `prep.rtas:*`
   and `branchWap:*` fan the DML/DDL catalog): `undrop:<op> @ <layout>` for core DML×M, schema states,
   branch/WAP, DDL×consumer, time travel, compaction.
3. For each feature, assert the **state survived restore** — these are the modality checks (the point):
   - snapshot lineage intact → time travel + `history` metadata reachable post-restore;
   - refs/branches survive → a pre-drop branch is still routable;
   - partition spec + sort order preserved → new writes honor them;
   - table properties + policies/PII tags preserved (contrast with RTAS G10, which wipes them);
   - MoR position-delete files survive → a live delete still filters post-restore;
   - schema-evolution state (added/renamed/widened cols) preserved.
   Any state that does **not** survive is a finding (candidate Gxx), pinned as a characterization.

**Verify:**
- The undrop battery runs and each state-survival assertion passes **or** is pinned as a documented
  finding with the exact lost-state described. Case count lands in the ~660 order (report built-vs-est
  per the discipline rule; prune vacuous crosses the same way the tracker already does).

---

## Phase 5 — Restore admin-lifecycle block + docs/PR refresh  (task #16)

**Do:**
1. Add the ~10–20 admin-lifecycle cases: TTL/purge-window, restore-before-purge (succeeds),
   restore-after-purge (rejected), restore name-collision, list-soft-deleted paging.
2. Un-SKIP `control.undrop` (was gated on the stub — REST-FIDELITY-EVAL.md).
3. Update `BUILD-STATUS.md` (undrop leg restored to **~660** surface-doubling + **~20** admin; correct
   the retracted ~10–20 note), `REST-FIDELITY-EVAL.md` (stub → real HTS), and `VERIFIED-RUN-openhouse.txt`.
4. Commit + push to `claude/hts-embed-plan-k7drzg`; refresh PR #11. Full-suite verify as the final gate.

**Verify:**
- Full suite (stub baseline **and** real-HTS variant) green; undrop battery + admin block included;
  BUILD-STATUS built-vs-estimate updated and leading; CI on #11 green.

---

## Risk register (watch during execution)

| Risk | Phase | Mitigation |
|---|---|---|
| Two Spring contexts in one JVM collide (ports, H2, static state) | 1–2 | separate H2 URLs; separate `SpringApplication` instances; distinct ports; boot HTS first |
| `@Primary` stub still wins despite scan exclusion | 2 | verify injected bean type in logs; fallback = 1-line `@ConditionalOnProperty` on the shared stub (plan B) |
| Real-HTS error mapping/retry surfaces different typed exceptions than the stub | 3 | triage each; pin legitimate differences as characterizations, don't mask |
| housetables deps perturb the harness classpath (dup Iceberg/Avro, à la FINDINGS F1) | 1 | reuse the existing single-Iceberg exclusion pattern in the init script |
| Undrop restore silently loses state (the thing we're hunting) | 4 | that's a *finding*, not a blocker — pin it (Gxx) and continue |
