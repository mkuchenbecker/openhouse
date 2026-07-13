# delta-harness — DDL test plan

Companion to `TEST-PLAN.md` (DML). Same principles: a test is a **typed pipeline** `TableTest[S]`;
every step asserts a **delta** (schema / rows / properties / snapshots before→after), never an
absolute; behaviors are authored once and the axes multiply them; a genuine product bug is
**tagged** (`Plan.knownBugs` → `SKIP`) and recorded in `BUGS.md`, never built upon.

DDL is different from DML in one dangerous way: **a DDL is not only an operation, it is also an
alternate _preparation_** — it changes the starting state that every DML operation then runs
against. Crossed naively, one DDL preparation multiplies the entire 660-case DML matrix. This plan
exists so that multiplication is **deliberate and budgeted**, not accidental.

## Cross-budget policy (READ FIRST — this is what keeps 600 from becoming 60,000)

Every DDL test is exactly one of six **roles**, ordered by blast radius:

- **B — Behavior** (the DDL statement _is_ the operation). Headless segment after
  `createAndSeed(layout)`, asserting a schema/row delta. Crosses the **layout axis** (×6) — bounded.
- **N — Negative / contract** (OpenHouse rejects it). Authored once on parquet, asserts the **actual
  typed exception + message substring** (same discipline as the DML negatives). ×1.
- **P — Preparation multiplier** (evolved starting state DML runs on). **Does NOT cross the full DML
  matrix** — crosses a fixed **smoke slice** `{delete.byPredicate, update.byPredicate, merge.upsert,
  insert.append, read.projection}` (5 ops) × `{unpartitioned/parquet, partitioned/parquet}` ≈ 10.
- **S — Substrate flag** (a property that changes the physical path for _every_ operation). MoR is
  the only real one in OSS OpenHouse and it is already done (264 cases). **Encryption and replication
  were investigated as candidates and are NOT substrate flags** (see recon findings below) — so no new
  S multiplier exists. An S flag that claims to be on but changes nothing is itself a finding.
- **F — Full cross** (opt-in, expensive). `prep × all DML × all layouts` ≈ +650. **RTAS is the one F**,
  and it comes _before_ the branch mega-axis.
- **X — Cross-cutting mega-axis: WAP / branching** (largest, reserved for **last, after RTAS**).
  Enabling branches/WAP is not a preparation — it is a **~3× re-run of the entire combined DML+DDL
  surface** across `{main, branch, staged-unpublished}`: every op must hold on main, hold in isolation
  on a branch, and (under WAP) stage invisibly then publish. Its budget is decided on its own, at the
  end, once everything beneath it is green.

## Two tracks: data-plane (Spark SQL) vs control-plane (REST/Jobs)

The recon surfaced that **most of what makes OpenHouse ≠ Iceberg lives in the control plane**, which
the current SQL-only harness cannot reach. So the work splits:

- **Data-plane track (Spark SQL)** — fits the harness as-is (`.sql(label)(stmt)` already runs DDL):
  schema DDL, properties, sort order, rename, CTAS/RTAS, namespace, policy DDL, branches, **clustering
  columns, column tags, GRANT/REVOKE ACL**. Phases 12–24.
- **Control-plane track (REST + Jobs)** — needs a harness extension (a REST client to the embedded
  `OpenHouseLocalServer`, or direct `Operations`/service calls): **table lock, soft-delete/undrop,
  maintenance jobs (compaction / snapshot-expiration / retention / orphan-file)**. Phases 25–27.
  **Decision:** do we extend the harness to the control plane, or keep it Spark-SQL-only and defer
  these? They're the highest-differentiation OpenHouse features but the biggest framework lift.

## Build order (largest axis strictly last)

`data-plane bounded (B/N) → S/P smoke preps → [control-plane track, if opted in] → RTAS (F) → WAP/branching (X)`.
Each tier green before the next is spent.

**Budgeted total (grounded in the recon; the two substrate axes shrank, not grew):**
- Data-plane bounded B+N (schema, props+metadata-retention, sort, rename, CTAS/RTAS-contract,
  namespace, policy, clustering, column-tags, ACL, replication-contract) ≈ **+115**
- P smoke preps (schema-evolved, sort-ordered — encryption/replication preps **dropped**) ≈ **+20**
- Control-plane track (lock / undrop / maintenance jobs) ≈ **+20** *(gated on the harness-extension decision)*
- **RTAS full cross (F)** ≈ **+650**
- **WAP/branching mega-axis (X)** ≈ **+150** (smoke-on-branch) … **up to ~+2,400** (full 3× cross)

Cumulative on the 660 DML baseline: **~795** (data-plane) → **~815** (+control-plane) → **~1,465**
(+RTAS) → **~1,615 / up to ~3,900** (+branch). The branch axis is the only order-of-magnitude lever.

## Gate #0 — OpenHouse support matrix (verified against source; ❓ = probe at runtime)

**Supported (→ Behavior):** `ADD COLUMN(S)` incl. nested; `SET/UNSET TBLPROPERTIES` (user keys);
`WRITE ORDERED BY`/`UNORDERED`/distribution; `RENAME TO` (same catalog); `SET/UNSET POLICY`
(retention/history/sharing/replication); `MODIFY COLUMN … SET TAG (PII|HC)`; `GRANT`/`REVOKE`/`SHOW
GRANTS` (table- and **database**-scoped); clustering columns at CREATE; `CTAS`; `RTAS` **iff
`replace.enabled=true`**; `CREATE BRANCH` + `cherrypick_snapshot`/`fast_forward`/`rollback_to_snapshot`
/`set_current_snapshot`; `SHOW DATABASES`/`SHOW TABLES`; `DROP TABLE`/`IF EXISTS`/`PURGE`.

**Rejected (→ Negative):** `DROP COLUMN` ("Some columns are dropped"); `RENAME COLUMN` ("Column not
found in newSchema"); `ADD/DROP/REPLACE PARTITION FIELD` and **clustering** evolution ("recreate the
table" — partition already in suite); `SET/UNSET` of `openhouse.*`/`policies` (`ALTER_RESERVED_TBLPROPS`);
change `openhouse.tableType` (`ALTER_TABLE_TYPE`); `RTAS` without `replace.enabled` (`RTAS_DISABLED`);
`RTAS` while `wap.enabled` **or replication** enabled; `RENAME TO` cross-catalog;
`CREATE/DROP/ALTER/DESCRIBE NAMESPACE`; `GRANT` on an unshared table (`GRANT_ON_UNSHARED_TABLES`) or a
locked table (`GRANT_ON_LOCKED_TABLES`); lock/unlock on a REPLICA (`checkReplicaTable`); update/rename
of a locked table (`LOCKED_TABLE_OPERATION`); REPLICA create without a valid `openhouse.tableUUID`;
`isTableReplicated=true` create without a sane `last-updated-ms`.

**Forced / silently overridden on CREATE (→ "you didn't get what you set" findings):**
`format-version` → cluster default (2); `write.metadata.delete-after-commit.enabled` → cluster default.
Honored-if-set: `write.format.default`, `write.metadata.previous-versions-max`.

**Recon findings that reshaped this plan:**
- **Encryption: does not exist in OSS OpenHouse** (no KMS / Parquet modular encryption / column
  encryption). Collapses to a single negative (encryption/KMS props ignored), not an axis.
- **Replication is not a per-operation substrate.** Replica tables are written only by an external
  cross-cluster job; user DML results are unchanged. It reduces to bounded negatives + policy
  round-trips. Cross-cluster mechanics + `enable_tabletype` toggle are **out of scope** for a single
  embedded server. Note: **OpenHouse's own tests do not cover the RTAS-while-replication rejection** —
  a real gap we fill.
- **Column tags (`SET TAG PII|HC`) and sharing are metadata/ACL-plane only** — they do NOT mask or
  alter query results. Test = round-trip + assert reads unaffected.
- **Genuinely new axes:** clustering columns (separate from partitioning); table lock enforcement;
  soft-delete/undrop lifecycle; maintenance jobs that mutate table state; database-scoped grants.

**❓ Probe first:** type widening (`int→bigint`…); column `COMMENT`; nullability `SET/DROP NOT NULL`;
column reorder `FIRST/AFTER`; `SET/DROP IDENTIFIER FIELDS`; `CREATE TABLE LIKE`; `SET LOCATION`;
`WRITE LOCALLY ORDERED BY`; `CREATE TAG`/`DROP`/`REPLACE BRANCH`; clustering SQL surface
(`CLUSTERED BY`?); whether OSS `AuthorizationHandler` enforces GRANT (may be a stub → tests degrade to
parse+persist).

## Framework additions

Data-plane (small — DDL is just SQL): typed `StepView` introspection helpers —
- [ ] `columnsOf(view)` → `List[(name,type,nullable,comment)]` (schema already read by `create.schema`)
- [ ] `propertiesOf(view)` → `Map[String,String]` (`SHOW TBLPROPERTIES`) — for forced-override findings
- [ ] `partitionSpecOf(view)` / `sortOrderOf(view)` — ❓ sort-order surface (Gate #0; else assert via `distribution-mode`)

Control-plane (larger — the Phase 25–27 gate): a REST client to the embedded server (lock/undrop/jobs
endpoints) or direct `Operations`/service invocation, plumbed as new `TableTest` steps. Only built if
the control-plane track is opted in.

---

# Data-plane track (Spark SQL)

## Phase 12 — Schema: ADD COLUMN family  (B, ×layouts)
- [ ] add single / multiple / nested-child column → present, existing rows read null
- [ ] ❓ add with `COMMENT`; ❓ add at `FIRST`/`AFTER`; ❓ type widening `int→bigint`/`float→double`/decimal↑

## Phase 13 — Schema negatives  (N)
- [ ] `DROP COLUMN` (top-level + nested) → "Some columns are dropped"; `RENAME COLUMN` → "not found in newSchema"
- [ ] ❓ narrowing type; ❓ `SET NOT NULL` on an optional column → rejection

## Phase 14 — Table properties + metadata retention  (B + N + findings)
- [ ] user key set→read-back→unset (B); reserved `policies`/`openhouse.tableType` set → typed N
- [ ] **finding:** `format-version=1` → read-back 2; `delete-after-commit.enabled` forced to cluster default
- [ ] honored-if-set: `write.format.default=avro`, `previous-versions-max=5` → read-back matches
- [ ] metadata-version retention: old `metadata.json` pruned to `previous-versions-max` (B)
- [ ] tuning: retry-wait / compression codec accepted, table still round-trips (assert-once)

## Phase 15 — Feature-flag properties  (B)
- [ ] `write.distribution-mode=range` vs `none` observable in write layout (light)
- [ ] (WAP flag effect deferred into the Phase 29 mega-axis; MoR already covered)

## Phase 16 — Sort order / write distribution  (B; ❓ read-back surface)
- [ ] `WRITE ORDERED BY` single/multi/`DESC NULLS FIRST`; `WRITE UNORDERED` clears; `distribution-mode=range` appears

## Phase 17 — Rename table  (B + N)
- [ ] rename same-db → old gone / new loads identical; onto existing → conflict; cross-catalog → typed N

## Phase 18 — CTAS / RTAS contract  (B + N + finding)
- [ ] CTAS rows+schema; **finding:** CTAS drops NOT NULL + sort order
- [ ] RTAS w/ `replace.enabled` → replaced, props preserved, `policies=""`
- [ ] RTAS without flag → `RTAS_DISABLED` (N); RTAS ⊕ WAP → N; **RTAS ⊕ replication → N (OpenHouse's own gap)**
- [ ] `CREATE OR REPLACE` on a non-existent table → creates it

## Phase 19 — Namespace / catalog DDL  (N + B)
- [ ] CREATE/DROP/ALTER/DESCRIBE NAMESPACE → typed N each; SHOW DATABASES/TABLES → B; implicit db-on-create

## Phase 20 — Policy DDL (`ALTER TABLE … SET/UNSET POLICY`)  (B + rich N)
- [ ] SET RETENTION (time-partitioned) / HISTORY (in-bounds) / SHARING; UNSET REPLICATION → round-trip
- [ ] negatives: history `MAX_AGE` > 3d, `VERSIONS` > 100 / < 2; retention granularity coarser than
      partition; retention on non-time-partition without a column pattern; replication bad interval format (parse)

## Phase 21 — Clustering columns  (B + N) — NEW
- [ ] CREATE with clustering column(s) (identity) → spec reflects it; write/read round-trips
- [ ] clustering with `TRUNCATE[w]` / `BUCKET[n]` transform; ❓ SQL surface (`CLUSTERED BY` vs API)
- [ ] negatives: > max clustering columns; clustering-evolution (ALTER) → `PARTITION_EVOLUTION` typed N

## Phase 22 — Column tags + sharing / ACL  (B + N; ❓ enforcement) — NEW
- [ ] `MODIFY COLUMN c SET TAG (PII|HC)` → tag round-trips; **reads unaffected** (no masking — assertion)
- [ ] `SET POLICY (SHARING=TRUE)` then `GRANT SELECT … TO p` → accepted; `SHOW GRANTS` lists it
- [ ] negatives: GRANT with sharing off → `GRANT_ON_UNSHARED_TABLES`; ❓ database-scoped GRANT/REVOKE
- [ ] ❓ probe whether OSS `AuthorizationHandler` enforces (else degrade to parse+persist)

## Phase 23 — Replication / table-type contract  (N, bounded) — NEW
- [ ] REPLICA_TABLE create without valid `openhouse.tableUUID` → typed N
- [ ] `isTableReplicated=true` create without sane `last-updated-ms` (missing / future) → typed N
- [ ] change `openhouse.tableType` on an existing table → `ALTER_TABLE_TYPE` typed N

## Phase 24 — Data-plane preparation multipliers  (P — smoke slice)
- [ ] `createSeedAddColumn(layout)` × smoke-slice × {unpart,part}/parquet — DML holds on an evolved schema
- [ ] `createSeedOrdered(layout)` × smoke-slice × 2 layouts — DML holds under a sort order

---

# Control-plane track (REST + Jobs) — GATED on the harness-extension decision

## Phase 25 — Table lock enforcement matrix  (B + N) — NEW
- [ ] lock via REST → update / rename / GRANT on the locked table rejected (`LOCKED_TABLE_OPERATION` /
      `GRANT_ON_LOCKED_TABLES`, typed); read requires `LOCK_ADMIN`; unlock restores mutability

## Phase 26 — Soft-delete / undrop lifecycle  (B + N) — NEW
- [ ] drop (soft) → table appears in soft-deleted list → restore → loads with identical schema/rows
- [ ] restore onto an in-use name → `AlreadyExistsException`; purge → gone; hard-vs-soft default per drop path

## Phase 27 — Maintenance jobs (state-changing control-plane ops)  (B) — NEW
- [ ] snapshot-expiration → old snapshots dropped, time-travel reachability shrinks
- [ ] data-compaction (`RewriteDataFiles`) → file count/layout changes, new snapshot, rows preserved
- [ ] retention → rows past the retention window deleted; orphan-file deletion → dangling files removed

---

# Terminal multipliers (spent last, on purpose)

## Phase 28 — RTAS full cross  (F ≈ +650)
- [ ] `createViaRtas(layout).andThen(op)` × all DML × all layouts — the incremental-model validation

## Phase 29 — WAP / branching mega-axis  (X — the ~3× multiplier, decided on its own)
- [ ] every DML **and** DDL op re-run on `{main, branch, staged}`: main correctness, branch isolation,
      WAP stage→`cherrypick`→publish visibility. Budget (smoke-on-branch ~+150 vs full 3× ~+2,400)
      chosen only when we reach it, with everything beneath it green.

---
**Execution protocol** (same as DML): add a phase, run it; each case passes or is a tagged known-bug
with a recorded reason. ❓ probes run first within a phase to settle B-vs-N. Genuine product bug →
tag + `BUGS.md`, don't build on it.

**Open decisions:**
1. **Control-plane track (Phases 25–27):** extend the harness to REST/Jobs, or keep it Spark-SQL-only
   and defer? Highest OpenHouse-differentiation coverage vs. the biggest framework lift.
2. **Branch mega-axis budget (Phase 29):** smoke-on-branch (~+150) or the full 3× (~+2,400)? Decided last.
