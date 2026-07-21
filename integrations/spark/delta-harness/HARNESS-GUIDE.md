# delta-harness — a guide to grokking the tests

This is the single doc to read to understand what this harness is, how it is built, why it is built
that way, and what it found. It is written for a human picking the harness up cold. If you read only
one file, read this one; `run-openhouse.sh` and the `*.scala` sources are the ground truth beneath it.

---

## 1. TL;DR

`delta-harness` is a self-contained Scala test rig that drives **real, customer-facing Spark SQL** at
a **real embedded OpenHouse catalog** and asserts what actually happened to the table. It is not a unit
test of OpenHouse internals — it is a behavioral matrix over the surface a data engineer touches:
`DELETE / UPDATE / MERGE / INSERT / OVERWRITE`, copy-on-write vs merge-on-read, DDL, branching/WAP,
time-travel, restore, maintenance procedures, streaming/CDC readers, the drop→undrop lifecycle, and the
behaviors specific to LinkedIn's **`com.linkedin.iceberg` 1.5.2 fork**.

- **Current result:** **STUB ~2,574** cases · **REAL-HTS ~2,792** cases · **0 failed** ·
  **0 ORC↔Parquet divergence.** (`VERIFIED-RUN-openhouse.txt` is the dated source of truth.)
- **How a "test" is written:** a **typed pipeline** (`TableTest[S <: Schema]`) — a preparation prefix
  (create+seed, or RTAS, or drop+undrop) composed with an operation suffix (the thing under test),
  where every step asserts a **delta** against the observed pre-state, never an absolute row set.
- **How it scales:** the same operation is crossed against many **substrates** (file format ×
  partitioning × CoW/MoR × replace-lineage × branch × restored-from-undrop). Format is a *per-case
  parameter*, so most blocks run on parquet **and** orc automatically.
- **What it's for:** finding **broken feature interactions**, not racking up green cases. The findings
  (14 product-behavior notes `G2–G14`, `WAP1`, the fork behaviors, an error-message readability audit)
  are the real output; the green count just says the tripwires are still where we left them.

---

## 2. How to run it

Requires **JDK 17** (the repo pins Lombok 1.18.20, which does not compile on JDK 21+):

```bash
export JAVA17_HOME=/usr/lib/jvm/java-17-openjdk-amd64   # or wherever your 17 lives

./run-openhouse.sh                       # full matrix (see VERIFIED-RUN for the current count)
./run-openhouse.sh delete parquet        # a fast slice (~25s): delete tests, parquet only
./run-openhouse.sh merge partitioned/avro
./run-openhouse.sh delete.byPredicate     # one operation across its layouts
```

Positional args are **AND-substring filters** on the case id. A narrow slice is ~25s end-to-end
(embedded-server + Spark startup dominates; the assertions themselves are milliseconds). Iterate on a
slice; run the whole matrix only as the final gate.

Two switches change *what* is exercised:

| Env var | Effect |
|---|---|
| `HARNESS_REAL_HTS=1` | Boots the **real** House Table Service as a second in-JVM Spring context and runs the drop→undrop battery (`undrop:*`, `undropAdmin.*`) for real. Default (unset) uses an in-memory stub and skips that leg. This is the difference between the ~2,574 and ~2,792 counts. |
| `ICEBERG_RUNTIME_JAR=<path>` | **Branch-testing mode.** Swaps the shaded Iceberg runtime jar on the classpath for a locally-built fork-branch-HEAD jar, so the whole suite runs against un-released fork bytecode. Reversible; hard-fails if the jar it's asked to replace isn't found (so a typo can't silently no-op). |

`HARNESS_PARALLELISM=N` overrides the worker count (default = CPU count; `<=1` = sequential).

### What the script does

`run-openhouse.sh` (1) resolves the OpenHouse classpath via a **system Gradle 8.x** (the Gradle wrapper
can't download behind the proxy — see pitfalls), (2) compiles **every** `.scala` file under
`src/main/scala/harness/openhouse/` with `scalac`, and (3) runs `harness.Main` on JDK 17 with the
`--add-opens` flags Spark 3.5 needs. Gradle is used *only* to produce the classpath and OpenHouse's own
jars; it does not build the harness.

---

## 3. The mental model (why a test looks the way it does)

A test is a **typed pipeline**: `TableTest[S <: Schema]`. The type parameter `S` names the table
implementation the test depends on, and every step references that schema's columns through typed
handles (`row.get(CoreTable.long0): Long`). The compiler therefore forbids mixing schemas or naming a
column the schema doesn't declare — a whole class of "the test drifted from the table shape" bug is
impossible by construction.

Four ideas do all the work:

1. **Schema = columns only.** `CoreTable` has one column per common type plus a `datepartition` string
   (`YYYY-MM-DD-HH`); `NestedTable` and `TypesTable` cover struct/complex and type-edge coverage. Each
   `Column[T]` carries its Scala type and a deterministic `literalAt(rowIndex)` generator, so seeding is
   reproducible and schema-checked.

2. **Preparation prefix + operation suffix, composed with `andThen`.** An *operation* (the thing under
   test — a `DELETE`, a `MERGE`, an `ADD COLUMN`) is authored **headless**: it assumes a seeded table
   and does not create one. The run composes a preparation *before* it. Because prep and op are the same
   kind of object, you can swap the prep without touching the op — which is the entire trick that lets
   "the whole DML catalog" be re-run on an RTAS'd table, a branch-routed table, or a table that has been
   through a real drop→undrop round-trip. **The op set is authored once; the substrate set multiplies it.**

3. **The layout axis.** `Layout` = file format × partitioning, expressed as a literal `CREATE`
   statement. Six base layouts (`{unpartitioned, partitioned} × {parquet, orc, avro}`), plus merge-on-read
   variants, plus dedicated single-data-file layouts used as a **physical CoW/MoR discriminator** (a
   strict-subset delete on one data file *must* produce a position-delete file under MoR and *must not*
   under CoW — that is asserted directly against `.all_delete_files`).

4. **Delta assertions, never absolutes.** Each step's validation thunk receives a `StepView` with
   `before`/`after` row snapshots and `snapshotsBefore`/`snapshotsAfter` commit counts. Every operation
   asserts a *change* ("2 rows fewer", "one new snapshot", "this key now excluded"), so the identical
   assertion holds under any layout, any seed size, any substrate. This is what makes one authored op
   valid across the whole substrate cross.

**The parallel runner** (`harness.Main`) runs cases on a worker pool; each worker gets its own
`spark.newSession()` (separate `SQLConf`) so session-global state some tests mutate — `spark.wap.branch`,
`spark.wap.id`, changelog temp views — never leaks between cases. Results are collected and printed in
original order, so output is identical to a sequential run. Each case owns its own table via an atomic
counter, so cases are independent.

**Known product bugs are tagged, not skipped-into-silence.** `Plan.knownBugs` maps a case-id substring
to a reason; a matching case is reported `SKIP (bug: …)` and tracked in `BUGS.md`. This is how a genuine
defect is deferred without either failing the suite or silently pretending it passed.

---

## 4. Where things live (the file map)

The harness used to be one 5,000-line file. It is now split by concern; every file is `package harness`
(the directory name is irrelevant to the package). Start at the top of this list and stop when you've
found what you need.

| File | What's in it |
|---|---|
| `Framework.scala` | The DSL and plumbing: `Ctx`, the REST/`HtsAdmin` clients, `Outcome`/`Check`, `Column`/`Schema`/`Rows`, the three tables (`CoreTable`/`NestedTable`/`TypesTable`), `RowGenerator`, `StepView`/`Step`, and `TableTest` itself. Read this first to understand the vocabulary. |
| `ScenarioKit.scala` | The shared **kit** every test group builds on: `Layout` + the layout lists, all the `createAndSeed*` preparations (plain / single-file / ordered / evolved / on-branch / RTAS / RTAS-MoR / mor-deleted / undropped), the format-multiplex hooks (`seedFmt`/`withSeedFmt`/`coreCreateParquet`), and cross-cutting helpers (`snapshotIds`, `coreTwoSnapshots`, `catalogRelative`, `countOf`, …). Every `*Scenarios` trait extends this. |
| `DmlScenarios.scala` | The core DML surface: the read / delete / update / merge / insert-append-overwrite operation catalog, the `operations` / `partitionedOperations` / `mutationOperations` lists, the DDL×consumer battery, the ADD COLUMN family, and the physical MoR discriminator. |
| `NestedTypesScenarios.scala` | Nested/complex-type coverage, type-edge coverage, and partition transforms + partition-evolution rejections. |
| `MorMaintScenarios.scala` | MoR delete-file **coexistence** (ops on a table already carrying a live position delete), MoR maintenance folds, MoR modality hazards, and MoR × branch merge. |
| `MaintControlScenarios.scala` | Time-travel, restore/rollback, maintenance procedures (`expire_snapshots`, `rewrite_data_files`, …), the REST control-plane ops (lock/unlock), and the undrop admin lifecycle. |
| `ForkScenarios.scala` | The `com.linkedin.iceberg` fork-behavior tests: `#251` column defaults, `#249` partition distribution, `#229`/`#219` delete-file / output-file replication, `#228` split-size, `#233`/`#189` compaction ordering. |
| `BranchWapScenarios.scala` | Branching and Write-Audit-Publish: the undrop 3-way compositions, direct-branch ops, the **WAP mega-axis** (staged-write publish visibility, systematic branch-DDL leak `G8`). |
| `NegativeDdlScenarios.scala` | Typed negatives / contract pins and the DDL phases (properties, sort order, rename, namespace, policy, CTAS/RTAS, column-tag/ACL, encryption). |
| `InteractionScenarios.scala` | 3-way compositions where the interesting behavior lives: DDL×history, RTAS×history/lineage/property-merge, branch×history/maintenance, and **the composite defect** (branch × expiration × merge, `G11`). |
| `SurfaceScenarios.scala` | Surface completion: error-message readability guard, branch leaks, WAP negatives, streaming/CDC, procedures, metadata tables, concurrency invariants, schema-evolution edges, write-path configs, expected-unsupported pins. |
| `HazardReaderWriterScenarios.scala` | Hazard/modality interactions (expired checkpoints, RTAS wiping tags, rename breaking consumers) and the reader×writer-class battery (changelog / incremental / streaming over CoW and MoR). |
| `Plan.scala` | `object Plan` — **the assembly**. This is where substrates × operations become the actual `Case` list, where `crossFmt` doubles a block across parquet/orc, and where `knownBugs` lives. If you want to know *what actually runs*, read `Plan.cases`. |
| `OpenHouseMatrix.scala` | Now just the one line that mixes the trait ingredients into `object Scenarios`. Kept as the historical entry-point name. |
| `Env.scala` | Boot + run: the embedded OpenHouse server wiring (`OpenHouseEnv`), the embedded real HTS (`HtsEnv`/`HtsBootApp`), the retrying `Runner`, and `Main`. |

The traits are mixed into `object Scenarios` **in source order**, so `val` initialization order is
identical to the original single object — see §6 (pitfall: trait init order).

---

## 5. The axes, and why the honest target is < 4,200 (vacuity)

Think of the suite as **substrates × operations × consumers**:

- **Operations** — the ~55 DML ops + the DDL ops + the procedures, each authored once as explicit
  literals.
- **Substrates (preparations)** — plain create+seed, RTAS'd (replace-lineage), branch-routed (via
  `spark.wap.branch`), restored-from-drop (real HTS), schema-evolved, sort-ordered, and merge-on-read —
  each of which multiplies the op catalog.
- **Consumers** — after a state-changing DDL, does each *reader* (plain scan, time-travel, changelog,
  incremental, streaming) still work?
- **Format** — a per-case parameter (see below), so blocks double across parquet/orc for free.

The naive product is ~4,200+ cases, but a large fraction would be **vacuous**, and the harness refuses
to inflate its count with them. The load-bearing vacuity arguments:

- A read or insert on a **delete-free MoR** table is byte-identical to CoW (no delete files to apply;
  append is mode-independent). So the real MoR surface is *mutation-ops × MoR* + *delete-file
  coexistence* + *reads-with-live-deletes*, not the whole op catalog × MoR.
- **RTAS / branch × format commute** — refs and metadata never touch file encoding — so those legs run
  parquet-only rather than × 3 formats.
- A DDL×consumer cross over a **rejected or one-shot** DDL has no post-state to consume, so only
  *state-changing DDL × real consumers* is non-vacuous.

When an estimate turns out inflated by vacuous cells, the honest move (documented in `BUILD-STATUS.md`)
is to correct the estimate in the open, not to chase the vacuous number. **Format, however, is NOT a
vacuity axis** — see the next section.

### Format multiplex: "format-inert" is a hypothesis, not an assumption

Every table-creating block reads a per-case thread-local seed format (`seedFmt`), and `Plan.crossFmt`
wraps a block so it runs once per format in `dataFormats` (parquet, orc), setting `seedFmt` around each
case. The mechanism is safe because cases are sequential per worker. The point is philosophical: **you
do not bake a file format into a test.** Whether a behavior is format-independent is something this
harness *verifies* (the fork carries patched ORC paths; `G8`/`G10` showed metadata surprises), it does
not assume. Only table-**less** operations (no `CREATE`) have no format axis. This is why the headline
includes "**0 ORC↔Parquet divergence**" — it's a checked result, not a design assumption.

---

## 6. Design decisions & pitfalls (the hard-won part)

**Catalog wiring is copied, not extended.** `OpenHouseEnv` composes an embedded `OpenHouseLocalServer`
+ Spark-catalog config lifted from `OpenHouseLocalServer` / `TestSparkSessionUtil` as *components* — no
OpenHouse test class is subclassed and no existing test is altered. The harness is a bolt-on observer,
not a fork of the test tree.

**The undrop leg needed a real HTS, and got one with a single backward-compatible production edit.**
Customer `DROP` hard-codes `purge=true`, so a customer can never populate the soft-deleted store; and
the embedded server's default `HouseTableRepository` is an in-memory **stub**, so an undrop test against
it would test the stub, not production. The fix (Option A) boots the genuine House Table Service as a
second in-JVM Spring context and points the tables server at it. The **only** production-code change in
the entire effort is one `@ConditionalOnProperty` on `HouseTablesH2Repository` (`havingValue="true",
matchIfMissing=true`) so the stub can be switched off — fully backward compatible (absent property ⇒
stub, exactly as before). Everything else is harness-side.

**Assertions are deltas, rejections are pins.** A negative test asserts a *rejection message substring*
and (per the readability audit) that the message is not a raw stacktrace / `[INTERNAL_ERROR]` / NPE.
These rejections are **tripwires, not contracts**: if OpenHouse later supports X, the pinned test is
meant to *flip* and be updated, not silently keep passing. The goal is catching a behavior change, in
either direction.

Pitfalls that will bite you (all learned the hard way):

- **JDK 17 only.** Lombok 1.18.20 in the repo does not compile on 21+. Set `JAVA17_HOME`.
- **Gradle wrapper can't download** behind the proxy (403). Use a system Gradle 8.x (`GRADLE_BIN`).
- **Avro needed a classpath fix** — a duplicate shaded/unshaded Iceberg on the classpath broke Avro
  until a dependency exclusion was added in `scripts/print-cp.init.gradle`.
- **Format is a hypothesis.** Do not "optimize" a block down to parquet-only because it "should" be
  format-inert — that is precisely the assumption the harness exists to check. Additively: every block
  covers at least ORC + Parquet; the 3-format blocks keep Avro. Never turn an additive format request
  into a destructive one (a prior mistake deleted Avro coverage — don't).
- **Trait init order (relevant to how this file is split).** `object Scenarios` is assembled by mixing
  the domain traits **in source order** on top of `ScenarioKit`, which linearizes the kit first — so
  `val` initialization order matches the original single object exactly. When you add a helper used by
  more than one trait, put it in `ScenarioKit` (a reference to a sibling trait's member won't resolve).
  The compiler catches the visibility mistakes; keeping mixin order = source order keeps the runtime
  order safe.
- **`cd` before you run.** Several wrappers reset the working directory; always
  `cd …/integrations/spark/delta-harness` before `./run-openhouse.sh`.
- **Git hooks are broken in this environment** — commit/push with `--no-verify`.

---

## 7. What the harness found

These are **product-behavior findings**, demonstrated live by named cases. Severity and evidence live in
`AUDIT-FINDINGS.md`; this is the map.

**Guard gaps (an op that can corrupt/mislead isn't blocked):**

- **G2 — RTAS on a LOCKED table succeeds.** The lock rejects an `UPDATE`, then `CREATE OR REPLACE`
  replaces the locked table (3 rows → 2). The replace path never reaches the lock check. *Data-loss
  class; cleanest one-line fix.* (`interact.rtas.onLockedTable`.)
- **G8 — table-global DDL "on a branch" silently mutates MAIN.** With `spark.wap.branch` set, `ADD
  COLUMN` / `SET TBLPROPERTIES` / `WRITE ORDERED BY` change main's schema/props/sort order — there is no
  branch dimension anywhere in the metadata commit path. (`branch.ddlLeak.*`, the WAP mega-axis Stage B.)
- **G9 / G10 — the replace path dodges update-path guards.** RTAS can change partition spec and drop
  columns that `ALTER` rejects (`G9`), and **RTAS silently wipes the `policies` plane** — retention,
  sharing, and PII column tags are gone after a replace while user props survive (`G10`). Highest-severity
  of the replace-path cluster. (`interact.rtas.*`, `hazard.rtas.wipesColumnTags`.)
- **G11 — branch × expiration × merge: routine snapshot expiration destroys merge connectivity.**
  Expiration retention is per-ref and head-anchored; nothing protects the ancestry *between* live refs.
  Consequences, all demonstrated: a `fast_forward` merge is **spuriously rejected** "main is not an
  ancestor" even though main never moved; a **cherry-pick silently loses the expired intermediate
  commit** (a partial merge presenting as success — the worst variant); the branch becomes permanently
  unmergeable; and **staged WAP snapshots get expired pre-publish**. OpenHouse's default 3-day expiration
  makes it automatic. (`interact.branch.expireMerge.*`.)
- **G12 — a lock starves maintenance for its whole lifetime** while *not* stopping RTAS (the mirror of
  G2): scheduled expire/compaction hit the lock gate and fail every cycle, so snapshots/files accrete
  unboundedly. (`hazard.lock.starvesMaintenance`.)
- **G3 / G4 / G5 / G6 / G7** — replica-path spec divergence, free WAP/replace toggling, ref-preservation,
  format-version on update, and the all-or-nothing `skipEligibilityCheck` on the replica path. (Details
  in `AUDIT-FINDINGS.md`; `G1` was investigated and **withdrawn** — the replication snapshot-walk is
  sound.)

**Behavior/limitation findings:**

- **G13 — CDC changelog is unsupported over a MoR table after an UPDATE/MERGE** ("Delete files are
  currently not supported in changelog scans"). MoR delete-only and all CoW work; MoR update/merge — the
  shapes MoR exists to optimize — break CDC silently. Stock Iceberg 1.5 limitation. (`readerWriter.changelog.{update,merge}.mor`.)
- **G14 — `rewrite_data_files` leaves a DANGLING position delete on MoR.** Compaction applies the delete
  (row set correct) but doesn't fold out the now-dangling delete file until `rewrite_position_delete_files`.
  Stock Iceberg 1.5 (no `remove-dangling-deletes` yet). Classified a **PIN, not a bug**, because the
  recovery path is verified to work (`maint.mor.rewritePositionDeleteFolds`, `delete_files` 1→0 × 3 MoR
  formats). **Operational takeaway: on MoR/1.5, pair `rewrite_data_files` with
  `rewrite_position_delete_files`.**
- **WAP1 — a staged (`spark.wap.id`) DELETE is not honored by WAP; it publishes to MAIN immediately.**
  In the same block, staged `INSERT`/`OVERWRITE`/`UPDATE`/`MERGE` all stage correctly. So an operator
  relying on WAP to stage-and-review a *deletion* gets an immediate, un-reviewed publish.
  (`wapStaged.delete.bypassesWap`, from the WAP mega-axis.)

**Error-message readability (Audit B).** A separate sweep grades rejection messages GOOD/MEH/BAD for a
non-expert SQL user. The systemic finding: the client drags the entire error body (including a
stacktrace) into the message, so even a GOOD server sentence reaches the user as `400 , {json + java
frames}` — surfacing only `ErrorResponseBody.message` would upgrade nearly every 4xx path at once.
Worst offenders (nested-field DELETE NPE, malformed replication interval 500-or-silent-coerce, DROP
COLUMN burying "you can't drop columns" in a double schema dump, CREATE/DROP NAMESPACE reporting the
wrong operation) are enumerated in `AUDIT-FINDINGS.md`.

The `BUGS.md` ledger holds the tagged, deferred defects (nested-DELETE optimizer NPE; **RENAME COLUMN is
a silent no-op** — a genuine OpenHouse regression code-traced to `#558`; encryption writes plaintext
because the KMS plugin is out-of-repo).

---

## 8. The `com.linkedin.iceberg` fork

The harness runs against **fork bytecode** (`com.linkedin.iceberg:iceberg-spark-runtime-3.5_2.12:1.5.2.15`),
not Apache 1.5.2. `ICEBERG-FORK-AUDIT.md` enumerates the fork's ~21 custom commits vs Apache; the tested
ones (each pinned by a `fork.*` case) are:

| Commit | Behavior the fork changes | Pinned by |
|---|---|---|
| `#249` | Partitioned default write distribution → NONE (Apache = HASH) → more, smaller files | `fork.partitionDist.default` |
| `#229` | `write.delete-file-replication` toggle for MoR delete files | `fork.deleteFileReplication` |
| `#219` | Per-output-file replication factor stamped by the delete-file write path | `fork.fileReplicationFactor` |
| `#228` | `spark.sql.iceberg.split-size` read split-size property | `fork.splitSize` |
| `#233` | Compaction bin-pack weight by data-file length (ignore delete size) | `fork.binPackByLength` |
| `#189` | Budgeted rewrite ordered by file-sequence-number | `fork.compactionOrder` |
| `#251` | Column-default APIs + `SchemaParser` serialization (branch HEAD only; **TABLED**) | `fork.colDefault.*` |

**The `#251` story is worth understanding** because it's a good example of the harness resisting an
overclaim. `#251` backports column defaults to api/core, but there is **no read-application code and no
Spark wiring** in the open fork (`SparkTable` doesn't implement `SupportsColumnDefaultValue`). So over
OSS Spark: `ADD COLUMN … DEFAULT 5` parses, but the default isn't written into the Iceberg schema, old
rows read NULL, and an INSERT omitting the column is rejected. The serialization *does* round-trip on a
branch build. The harness pins exactly that — the observable OSS-Spark DDL behavior and the serialization
— and explicitly does **not** claim the feature is "broken"; read-application may exist in LinkedIn's
private Spark, which this harness cannot see. A whole-suite **branch-vs-release** run (via
`ICEBERG_RUNTIME_JAR`) showed **0 correctness deltas**.

---

## 9. Adding a test (the recipe)

1. Pick the schema (`CoreTable` unless you need nesting or type edges).
2. Author the operation **headless** — a `TableTest` step that assumes a seeded table and asserts a
   **delta** via its `StepView` (`view.before`/`view.after`, `snapshotsBefore`/`snapshotsAfter`, and the
   metadata tables like `.all_delete_files` / `.snapshots`).
3. Put it in the trait that matches its domain (§4). If it needs a helper used by another trait, add the
   helper to `ScenarioKit`.
4. Wire it into `Plan`: add it to the relevant list, and use `crossFmt(...)` if it creates a table (so
   it runs parquet **and** orc). Don't bake a single format into it.
5. If it exercises a real product bug you're deferring, tag it in `Plan.knownBugs` with a reason and file
   it in `BUGS.md` — never let it silently pass or silently skip.
6. Run the slice, then the full gate; update `VERIFIED-RUN-openhouse.txt`.

---

## 10. Decisions settled along the way

- **D6 — format is a per-case parameter (blanket-double), not a baked-in constant.** Un-baking the
  format is what lets a test multiplex and compose; "format-inert" is verified, not assumed.
- **D5 — the dangling MoR delete (`G14`) is a PIN, not a bug**, because
  `rewrite_position_delete_files` is verified to recover it. MoR on 1.5 simply requires that extra
  maintenance step.
- **D7 — encryption/KMS is deferred** (owner): the plugin is out-of-repo, so the plaintext behavior is
  pinned and the intended-behavior assertion waits for the plugin.

---

*The receipts.* This guide is the self-contained narrative. The exhaustive ledgers it summarizes — the
dated run log (`VERIFIED-RUN-openhouse.txt`), the findings with code citations (`AUDIT-FINDINGS.md`), the
fork-commit audit (`ICEBERG-FORK-AUDIT.md`), the tagged-defect ledger (`BUGS.md`), and the block-by-block
build ledger (`BUILD-STATUS.md`) — live alongside the harness in the working PR that developed it. You do
not need them to grok the tests; reach for them when you want the evidence behind a specific claim here.
