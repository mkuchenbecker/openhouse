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

Every DDL test is exactly one of four **roles**, and each role has a fixed blast radius:

- **B — Behavior** (the DDL statement _is_ the operation under test). Authored like a DML
  operation: a headless segment composed after `createAndSeed(layout)`, asserting a schema/row
  delta. Crosses the **layout axis** (×6) — linear, bounded.
- **N — Negative / contract** (OpenHouse rejects the statement). Authored once on parquet, asserts
  the **actual typed exception + message substring** (same discipline as the DML negatives). ×1.
- **P — Preparation multiplier** (the DDL produces an evolved starting state that DML runs on).
  **Does NOT cross the full DML matrix.** It crosses a fixed **representative smoke slice** —
  `{delete.byPredicate, update.byPredicate, merge.upsert, insert.append, read.projection}` (5 ops)
  × a **reduced 2-layout set** `{unpartitioned/parquet, partitioned/parquet}` = ~10 cases per prep.
- **F — Full cross** (opt-in, expensive). `prep × all DML × all layouts` ≈ +650. Reserved for
  preparations whose whole purpose is to validate the incremental model. **Default: only RTAS**
  earns this, and only once its Gate #0 passes. No other prep gets F without a recorded reason.

**Table properties** get a sub-classification (from the property recon), because most of them must
_not_ be crossed with anything:
- **Feature-flag property** (changes observable behavior: `write.wap.enabled`, `write.{delete,
  update,merge}.mode`, `write.distribution-mode`, `format-version` as-read): **one behavioral test
  proving the flag's effect (role B/×1).** A flag graduates to a P/F preparation only if it changes
  the substrate for _all_ DML — which is exactly what MoR already did (264 cases); nothing else gets
  that budget without justification.
- **Tuning property** (no observable contract: `commit.retry.*` waits, compression codec, target
  file size, arbitrary user keys): assert **"accepted, table still works" once.** Never multiplied.
- **Reserved property** (`openhouse.*`, `policies`): role N (rejection) + a few "server stamps/forces
  it" positive assertions.
- **Property _combinations_**: **representative tuples, not the cross-product.** Test the specific
  enforced interaction (`wap.enabled` ⊕ `replace.enabled` exclusivity), not the cartesian grid.

**Budgeted total:** roles B+N+F(sort/props/etc.) ≈ **+110–140 cases**; each P prep ≈ +10; the one
optional RTAS **F** ≈ +650. So the plan lands near **+130 cases**, or **+780** iff we spend the RTAS
full cross. That one decision is the only thing between the two numbers — everything else is bounded
by construction.

## Gate #0 — OpenHouse DDL support matrix (verified against source; ❓ = must probe at runtime)

Established from the server rules (`OpenHouseInternalRepositoryImpl`, `BaseIcebergSchemaValidator`,
`BasePreservedKeyChecker`) and existing itests. Confirm each ❓ against the running catalog before
writing its test; a ❓ that rejects becomes an **N**, one that succeeds becomes a **B**.

**Supported (→ Behavior):** `ADD COLUMN(S)` incl. nested; `SET/UNSET TBLPROPERTIES` (user keys);
`WRITE ORDERED BY` / `WRITE UNORDERED` / distribution; `RENAME TO` (same catalog); `SET POLICY` /
`UNSET POLICY`; `CTAS`; `RTAS` **iff `replace.enabled=true`**; `CREATE BRANCH` + `cherrypick_snapshot`
/ `fast_forward` / `rollback_to_snapshot` / `set_current_snapshot`; `SHOW DATABASES` / `SHOW TABLES`;
`DROP TABLE` / `IF EXISTS` / `PURGE` (purge always forced true); `CREATE TABLE IF NOT EXISTS`.

**Rejected (→ Negative):** `DROP COLUMN` ("Some columns are dropped"); `RENAME COLUMN` ("Column not
found in newSchema"); `ADD/DROP/REPLACE PARTITION FIELD` ("recreate the table with new partition
spec" — already in the suite); `SET`/`UNSET` of `openhouse.*` or `policies` (`ALTER_RESERVED_TBLPROPS`);
change `openhouse.tableType` (`ALTER_TABLE_TYPE`); `RTAS` without `replace.enabled` (`RTAS_DISABLED`);
`RTAS` while `wap.enabled`/replication on; `RENAME TO` cross-catalog; `CREATE`/`DROP`/`ALTER`/`DESCRIBE
NAMESPACE`.

**Forced / silently overridden on CREATE (→ "you didn't get what you set" findings):**
`format-version` → cluster default (2), user value ignored; `write.metadata.delete-after-commit.enabled`
→ cluster default. Contrast honored-if-set: `write.format.default`, `write.metadata.previous-versions-max`.

**❓ Probe first:** type widening (`int→bigint`, `float→double`, decimal precision↑); column
`COMMENT`; nullability `DROP/SET NOT NULL`; column reorder `FIRST/AFTER`; `SET/DROP IDENTIFIER FIELDS`;
`CREATE TABLE LIKE`; `SET LOCATION`; `WRITE LOCALLY ORDERED BY`; `CREATE TAG` / `DROP`/`REPLACE BRANCH`.

## Framework additions (small — DDL is just SQL, so `.sql(label)(stmt)` already runs it)

The only real gap is **structure introspection** for the assertions. Add typed `StepView` helpers:
- [ ] `columnsOf(view)` → `List[(name, type, nullable, comment)]` (from `spark.table(t).schema` — the
  `create.schema` test already reads this).
- [ ] `propertiesOf(view)` → `Map[String,String]` (`SHOW TBLPROPERTIES t`) — needed for the
  forced-override findings and user-key round-trips.
- [ ] `partitionSpecOf(view)` (`.partitions` metadata / `DESCRIBE EXTENDED`) — reused from Phase 7.
- [ ] `sortOrderOf(view)` — ❓ Spark has no clean surface; Gate #0 probe (`DESCRIBE EXTENDED` vs a
  metadata read). Blocks Phase 16's read-back assertion; find the surface before writing it.

The typed `Column[T]` handles stay for **value** assertions (DML). DDL asserts **structure** via the
helpers above. Schema-mutating behaviors (ADD COLUMN) assert on the column list, not on typed handles
for the new column (the typed `CoreTable` schema is fixed).

---

## Phase 12 — Schema DDL: ADD COLUMN family  (role B, × layouts)
- [ ] add a single top-level column → column present, type/nullable correct, existing rows read null
- [ ] add multiple columns in one statement
- [ ] add a nested struct child (`ADD COLUMN s.e int`) → nested field present, old rows null
- [ ] ❓ add column with `COMMENT` → comment stored (probe; likely B)
- [ ] ❓ add column at position `FIRST` / `AFTER c` → column order reflects it (probe; likely B)
- [ ] ❓ type widening `int→bigint`, `float→double`, decimal precision↑ → values preserved (probe)

## Phase 13 — Schema DDL negatives  (role N, ×1)
- [ ] `DROP COLUMN` → `ALTER_RESERVED`/`InvalidSchemaEvolution` "Some columns are dropped" (typed)
- [ ] `DROP COLUMN` on a nested field → same rejection
- [ ] `RENAME COLUMN` → "Column [..] not found in newSchema" (typed)
- [ ] ❓ narrowing type (`bigint→int`) → Iceberg rejection (probe; expected N)
- [ ] ❓ `SET NOT NULL` on an existing optional column → rejection (probe; expected N)

## Phase 14 — Table properties: user keys, reserved keys, forced overrides  (B + N + findings)
- [ ] user key `SET TBLPROPERTIES ('k'='v')` then read-back = v; then `UNSET` removes it (B)
- [ ] reserved `SET TBLPROPERTIES ('policies'=…)` → `ALTER_RESERVED_TBLPROPS` (typed N)
- [ ] reserved `SET TBLPROPERTIES ('openhouse.tableType'=…)` → `ALTER_TABLE_TYPE`/reserved (typed N)
- [ ] **finding:** create with `format-version=1` → read-back is **2** (server forces cluster default)
- [ ] **finding:** create with `write.metadata.delete-after-commit.enabled=true` → read-back is the
      cluster default, not the user's value
- [ ] honored-if-set: create with `write.format.default=avro` → read-back = avro; `previous-versions-max=5` → = 5
- [ ] tuning: create with `commit.retry.max-wait-ms` / a compression codec → accepted, table still writes/reads (assert-once, C)

## Phase 15 — Feature-flag properties with observable behavior  (role B, ×1 — prove the flag's effect)
- [ ] `write.wap.enabled=true`: a write stages a snapshot with no ref; base read excludes it;
      `cherrypick_snapshot` publishes it → now visible (the WAP contract)
- [ ] `write.distribution-mode=range` vs `none`: observable in write layout / summary (light assertion)
- [ ] (CoW vs MoR feature flags — already covered by the 264-case MoR axis + the physical discriminator)

## Phase 16 — Sort order / write distribution  (role B; ❓ read-back surface)
- [ ] `WRITE ORDERED BY (foo_col_long)` → sort order set; `write.distribution-mode=range` appears
- [ ] multi-column `WRITE ORDERED BY a, b`; `… DESC NULLS FIRST`
- [ ] `WRITE UNORDERED` clears it
- [ ] ❓ assertion surface for sort order (Gate #0) — if none, assert via the `distribution-mode` side effect

## Phase 17 — Rename table  (B + N)
- [ ] `RENAME TO` (same db) → old name gone, new name loads with identical schema + rows
- [ ] `RENAME TO` onto an existing name → conflict rejection (typed)
- [ ] cross-catalog `RENAME TO` → `UnsupportedOperationException "Cannot rename tables across catalogs"` (typed N)

## Phase 18 — CTAS / RTAS  (B + N + findings + the one optional F)
- [ ] `CTAS` from a seeded source → new table has the projected rows + schema
- [ ] **finding:** SQL `CTAS` drops `NOT NULL` (target column becomes optional) and drops sort order
- [ ] `RTAS` with `replace.enabled=true` → table replaced, new schema/rows; user props preserved, `policies=""`
- [ ] `RTAS` without the flag → `RTAS_DISABLED` "REPLACE TABLE AS SELECT is not enabled" (typed N)
- [ ] `RTAS` while `write.wap.enabled=true` → rejected (WAP/RTAS mutual exclusion) (typed N)
- [ ] `CREATE OR REPLACE` on a non-existent table → just creates it (no opt-in needed)
- [ ] **[decision] RTAS as a full-cross preparation (role F)** — `createViaRtas(layout).andThen(op)` over
      all DML × all layouts, validating the incremental model. **Gated on your Rule-2 call below.**

## Phase 19 — Namespace / catalog DDL  (mostly N)
- [ ] `CREATE NAMESPACE` / `DROP NAMESPACE` / `ALTER NAMESPACE SET PROPERTIES` / `DESCRIBE NAMESPACE`
      → each `UnsupportedOperationException "… is not supported"` (typed N)
- [ ] `SHOW DATABASES` / `SHOW TABLES IN db` → succeed and list the managed table (B)
- [ ] a database is created implicitly by creating a table in it (B)

## Phase 20 — Policy DDL (OpenHouse extension: `ALTER TABLE … SET POLICY`)  (B + rich N)
- [ ] `SET POLICY (RETENTION=…)` on a time-partitioned table → policy stored/read-back
- [ ] `SET POLICY (HISTORY MAX_AGE=…d VERSIONS=…)` within bounds → stored
- [ ] `SET POLICY (SHARING=true)` → stored; `UNSET POLICY (REPLICATION)` → cleared
- [ ] negatives (validator bounds): history `MAX_AGE` > 3 days; `VERSIONS` > 100 or < 2; retention
      granularity coarser than partition; retention on non-time-partition without a column pattern (typed N each)

## Phase 21 — Branches & tags  (B; ❓ tags)
- [ ] `ALTER TABLE … CREATE BRANCH b` → branch ref exists; write to `t.branch_b` is isolated from main;
      `VERSION AS OF 'b'` reads the branch
- [ ] ❓ `CREATE TAG` / `DROP BRANCH` / `REPLACE BRANCH` (probe; B or N per Gate #0)

## Phase 22 — DDL-as-preparation multipliers  (role P — smoke slice only; F only where decided)
- [ ] **P:** `createSeedAddColumn(layout)` (add a column + backfill) × smoke-slice(5) × {unpart,part}/parquet
      — DML stays correct against an evolved schema (~10 cases)
- [ ] **P:** `createSeedOrdered(layout)` (table with a sort order) × smoke-slice × 2 layouts (~10 cases)
- [ ] **F (optional):** RTAS preparation × full DML × full layouts — see Phase 18 decision

---
**Execution protocol** (same as DML): add a phase, run it, each case passes or is a tagged known-bug
with a recorded reason. Genuine product bug → tag + `BUGS.md`, don't build on it. ❓ probes run first
within a phase to settle B-vs-N before the rest of that phase is written.

**Decision (Rule 2 / Phase 18 + 22):** RTAS gets the **F** full-cross budget (+~650) because it
validates the incremental model — but it is **deferred to Phase 22 (last)**, so Phases 12–21 (the
bounded ~+130) are built and green first, and the +650 is spent only when we deliberately reach it.
Every other preparation stays on the **P** smoke slice. This is the only lever between ~+130 and ~+780.
