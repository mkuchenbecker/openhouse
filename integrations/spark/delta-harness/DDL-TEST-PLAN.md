# delta-harness — DDL test plan

Companion to `TEST-PLAN.md` (DML). Same principles: a test is a **typed pipeline** `TableTest[S]`;
every step asserts a **delta** (schema / rows / properties / snapshots before→after), never an
absolute; behaviors are authored once and the axes multiply them; a genuine product bug is
**tagged** (`Plan.knownBugs` → `SKIP`) and recorded in `BUGS.md`, never built upon.

DDL is different from DML in one dangerous way: **a DDL is not only an operation, it is also an
alternate _preparation_** — it changes the starting state that every DML operation then runs
against. Crossed naively, one DDL preparation multiplies the entire 660-case DML matrix. This plan
exists so that multiplication is **deliberate and budgeted**, not accidental.

**Sizing philosophy (updated):** the goal is coverage, not case-minimization. **Cross it all once**
(ceiling ~30k cases — 100k is too far, 30k is fine), **measure the suite wall-time**, then
**recommend what is worth maintaining** and prune from evidence rather than pre-emptively.

**Measured timing (660-case baseline = 597s wall):** ~82s gradle classpath resolve + Scala compile,
~35s embedded-server + Spark startup, ~480s cases → **~0.73s / case marginal + ~120s fixed startup**.
Projection: ~5k ≈ 1h · ~8.5k ≈ 1.75h · ~15k ≈ 3h · ~30k ≈ 6h. So the **full run is a nightly/CI
artifact**, not an interactive loop.

**Loop discipline (the reason we measured):** the inner loop is **startup-bound, not case-bound** —
a single-id slice still pays the ~120s fixed tax (mostly the ~82s gradle re-resolve). So: (1) **cache
the classpath** (`oh-cp.txt`) and skip the gradle step on unchanged deps → a slice drops to ~40s;
(2) fix a failing case by running **only its id** (`run-openhouse.sh <case.id>`), never the full
suite; (3) recompile-only when source changes. Full-suite wall-time is recorded in
`VERIFIED-RUN-openhouse.txt` each run so the outer-loop cost stays visible and honest.

## Cross-budget policy (READ FIRST — this is what keeps 600 from becoming 60,000)

Every DDL test is exactly one of six **roles**, ordered by blast radius:

- **B — Behavior** (the DDL statement _is_ the operation). Headless segment after
  `createAndSeed(layout)`, asserting a schema/row delta. Crosses the **layout axis** (×6) — bounded.
- **N — Negative / contract** (OpenHouse rejects it). Authored once on parquet, asserts the **actual
  typed exception + message substring** (same discipline as the DML negatives). ×1.
- **P — Preparation multiplier** (evolved starting state DML runs on). **Does NOT cross the full DML
  matrix** — crosses a fixed **smoke slice** `{delete.byPredicate, update.byPredicate, merge.upsert,
  insert.append, read.projection}` (5 ops) × `{unpartitioned/parquet, partitioned/parquet}` ≈ 10.
- **S — Substrate flag** (a property that changes the physical path for _every_ operation). In-code
  recon settled the two candidates: **MoR is the only real substrate** (done, 264 cases). **Encryption
  is ABSENT** in OSS OpenHouse — the `encryption()` hook is un-wired, files are plaintext on disk — so
  it is not a substrate; it collapses to ~2 negatives + a plaintext-on-disk finding. **Replication's
  data-mover is EXTERNAL** (not in-repo; the `REPLICATION` job is dead code here) and OpenHouse copies
  snapshots verbatim with no path rewrite — so it is not a per-operation substrate either; it is a
  bounded (~15) contract/negatives surface. No _new_ S multiplier exists; MoR remains the only one.
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

**Budgeted total (EXPANSIVE — cross-all-once, ~30k ceiling with headroom; ~0.73s/case → time in ()):**
- Data-plane bounded B+N (schema, props+metadata-retention, sort, rename, CTAS/RTAS-contract,
  namespace, policy, clustering, column-tags, ACL, replication-SQL, encryption-negatives) ≈ **+130**
- **Preparations promoted to FULL DML cross** (each ≈ +660, not smoke): schema-evolved, sort-ordered ≈ **+1,320**
- **RTAS full cross (F)** ≈ **+660**
- Control-plane track (lock / undrop / maintenance × layouts / replication repository-layer) ≈ **+50** *(gated on the harness-extension decision)*
- **WAP/branching mega-axis (X)** — re-run the combined surface on `{branch, staged}` ≈ **2× ≈ +5,700**

Cumulative on the 660 DML baseline: **~790** data-plane bounded *(~11 min)* → **~2,110** +full-cross
preps *(~35 min)* → **~2,770** +RTAS *(~45 min)* → **~2,820** +control-plane → **~8,500** +branch
*(~1.75 h)*. Headroom to ~30k exists (branch × layouts, maintenance × matrix, WAP staging variants) if
we want more; **~8.5k is the natural landing point, ~1.75 h full run.** The branch axis is the only
order-of-magnitude lever and stays last.

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

**Recon findings that reshaped this plan (verified in code):**
- **Encryption: KMS-based and REAL, but the plugin is in a private repo (external, like the
  replication mover).** In OSS the Iceberg `encryption()` hook is un-wired
  (`OpenHouseInternalTableOperations` overrides `io()` but not `encryption()` → inherits the no-op
  `PlaintextEncryptionManager`; no `parquet.crypto.factory.class`, no KMS client), so **OSS writes
  plaintext by default**. The production KMS plugin — which wires `encryption()` / a
  `KeyManagementClient` (or a Parquet crypto factory) — lives outside this repo. → In the embedded
  OSS harness only the **plaintext-default path** is exercisable (encryption props inert;
  `.parquet`/`metadata.json` readable with no key). Encryption-ON is **out of scope for OSS** exactly
  like the cross-region replication mover; document the **plugin contract** it must satisfy so a
  future private-plugin harness can drive it. Encryption is a must-work feature — the fact that OSS
  has no default wiring (plaintext, silently) is itself worth stating.
- **Replication: EXTERNAL data-mover, brittle raw-snapshot copy.** The copy+commit executor is **not
  in-repo** — no `OperationTask` registers `OPERATION_TYPE=REPLICATION`, `JobsScheduler` throws
  "Unsupported job type REPLICATION", no `ReplicationSparkApp`. Only the primary-side scheduling and
  the destination **commit-acceptance** path exist. **Mechanism (clarified):** every primary
  operation — including snapshot expiration — is itself a **snapshot** that then replicates to the
  replica via the verbatim snapshot-list copy (no path rewrite), so the replica carries
  **source-region absolute paths**; correctness depends on the external mover staging files where they
  resolve. **How DDL/maintenance breaks it:** because the replica references the same file paths, a
  source-side expiration / orphan-file-deletion that physically deletes files the replica's copied
  snapshot list still points at leaves **dangling refs — with no existence validation on commit**.
  → bounded ~15 cases, SQL-reachable + repository-layer (Phase 23 / 27). Cross-cluster +
  `enable_tabletype` are out of scope for one server. **Missing-guard + message-readability audits
  (below) are in flight to find where a block *should* exist and where errors read as stacktraces.**
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

## Phase 23 — Replication / table-type contract  (N + finding, bounded) — NEW
SQL-reachable (data-plane):
- [ ] `SET/UNSET POLICY (REPLICATION=…)` round-trip: destination upper-cased, default interval `1D`,
      cron derived; **finding:** malformed interval (`'5X'`) → uncaught `NumberFormatException` → **HTTP
      500** (should be a typed 400; `"3X"` silently accepted as daily) — see `AUDIT-FINDINGS.md` B#2
- [ ] RTAS while replication enabled → `RTAS_DISABLED` naming "replication" (typed N — OpenHouse's own gap)
- [ ] REPLICA_TABLE create without valid `openhouse.tableUUID` → typed N
- [ ] `isTableReplicated=true` create without sane `last-updated-ms` (missing / future) → typed N
- [ ] change `openhouse.tableType` on an existing table → `ALTER_TABLE_TYPE` typed N

Repository-layer (needs the control-plane extension — see Phase 27):
- [ ] REPLICA commit (base=REPLICA+clusterA, incoming=PRIMARY+clusterB) → skips eligibility, retains type
- [ ] intermediate-schema replay is REPLICA-only; PRIMARY commit does not get `newIntermediateSchemas`
- [ ] snapshots stored **verbatim** (no path rewrite) — proves the "no transformation" property
- [ ] replication is a **snapshot walk** (expiration = a replicated snapshot in the chain) → the copy
      is ordered/consistent by construction (no dangling-ref race; G1 withdrawn)

## Phase 24 — Data-plane preparation multipliers  (FULL DML cross — promoted from smoke per cross-all-once)
- [ ] `createSeedAddColumn(layout)` × **all DML × all layouts** — DML holds on an evolved schema (~+660)
- [ ] `createSeedOrdered(layout)` × **all DML × all layouts** — DML holds under a sort order (~+660)

## Phase 24b — Encryption (OSS plaintext-default path; KMS plugin private → out of scope)  (N + finding) — NEW
- [ ] set `write.metadata.encryption.*` / `parquet.encryption.*` / `encryption.key-id` on CREATE/ALTER →
      stored as inert user props, no error (OSS has no crypto wiring)
- [ ] **finding:** written `.parquet` (magic `PAR1`) + `metadata.json` are **plaintext on disk**, readable
      with no key material configured — OSS default is unencrypted
- [ ] document the **plugin contract** the private KMS plugin must satisfy (override `encryption()` /
      wire a `KeyManagementClient` or `parquet.crypto.factory.class`) so a future private-plugin harness
      can drive encryption-ON — mirrors the external replication-mover contract

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
- [ ] expiration/OFD on a source is safe for the replica — it replicates as a snapshot in the walk
      (not a dangling-ref break; G1 withdrawn)

---

# Terminal multipliers (spent last, on purpose)

## Phase 28 — RTAS full cross  (F ≈ +650)
- [ ] `createViaRtas(layout).andThen(op)` × all DML × all layouts — the incremental-model validation

## Phase 29 — WAP / branching mega-axis  (X — the ~3× multiplier, decided on its own)
- [ ] every DML **and** DDL op re-run on `{main, branch, staged}`: main correctness, branch isolation,
      WAP stage→`cherrypick`→publish visibility. Budget (smoke-on-branch ~+150 vs full 3× ~+2,400)
      chosen only when we reach it, with everything beneath it green.

---

# Cross-cutting audits (findings, not just pass/fail) — populate `BUGS.md` / `FINDINGS.md`

These two run ACROSS the phases above rather than as a numbered phase. They produce **findings and
recommendations**, and each concrete instance becomes a test assertion where feasible.

Both audits are complete — full results, with file:line and severity, are in **`AUDIT-FINDINGS.md`**.
Headlines:

## Audit A — Missing guards (where an op breaks the table but isn't blocked) → `AUDIT-FINDINGS.md`
Model guard: RTAS-while-{WAP,replication}. Gaps G2–G7 (G1 withdrawn — see below). Highlights:
- **G2 (concrete bug, file it):** RTAS/REPLACE on a **locked** table is not blocked — the replace
  branches skip the `isTableLocked → LOCKED_TABLE_OPERATION` check. One-line RTAS-guard analogue.
- **G4:** `write.wap.enabled`/`replace.enabled` are free-toggle user props → disabling WAP strands
  staged snapshots. **G3/G7:** `skipEligibilityCheck` is an all-or-nothing bypass on the replica path.
- **G1 WITHDRAWN:** the "source expiration → dangling replica ref" hypothesis is invalid — replication
  is a **snapshot walk** (expiration is itself a replicated snapshot in the chain), so the copy is
  ordered/consistent by construction and no dangling-ref race exists.
- Where a block already exists → a negative test asserts it; where it's missing → a finding + recommendation.

## Audit B — Error-message readability (SQL-noob test; a stacktrace is "dumb") → `AUDIT-FINDINGS.md`
- **Systemic S1 (highest leverage):** the java client wraps a 400 as `"400 , {full JSON body incl. a
  6000-char stacktrace field}"` — even GOOD server messages reach the user buried in Java frames.
  Surfacing only `ErrorResponseBody.message` upgrades nearly every 4xx at once.
- **Systemic S2:** the catch-all 500 is `exception.toString()` → bare `NullPointerException` etc.
- **Worst offenders:** nested-field DELETE `[INTERNAL_ERROR] NPE`; malformed replication interval →
  uncaught `NumberFormatException` **500** (not a clean 400 — corrects Phase 23); commit 5xx →
  `CommitStateUnknownException(rawBody)`; DROP/RENAME COLUMN dump full `Schema.toString()` twice.
- **GOOD tier** (RTAS/locked/validator-bounds messages) is the bar to raise the BAD tier to.
- **Harness action:** extend each negative to also **assert the message is not a raw
  stacktrace/`[INTERNAL_ERROR]`/500** (a readability regression guard).

---
**Execution protocol** (same as DML): add a phase, run it; each case passes or is a tagged known-bug
with a recorded reason. ❓ probes run first within a phase to settle B-vs-N. Genuine product bug →
tag + `BUGS.md`, don't build on it.

**Open decisions:**
1. **Control-plane track (Phases 25–27):** extend the harness to REST/Jobs, or keep it Spark-SQL-only
   and defer? Highest OpenHouse-differentiation coverage vs. the biggest framework lift.
2. **Branch mega-axis budget (Phase 29):** smoke-on-branch (~+150) or the full 3× (~+2,400)? Decided last.
