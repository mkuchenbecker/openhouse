# 10 — Iceberg OSS Test Inventory & Cribbing Checklist

*Prerequisites: `index.md`, `05-test-case-model.md`, `06-delta-model.md`.* This is the
work-list: what OSS Iceberg tests exist, how many, and a **first-pass grade** for each
behavior category so we evaluate — not blindly run — before cribbing.

> **DISPOSITION UPDATE (supersedes the grades below).** Per directive, **every** Apache
> Iceberg contract test is to be **ported** — nothing is excluded. The COPY/ADAPT/SKIP grades
> below no longer mean "whether to include"; they now mean **effort/risk + prerequisites**:
> - **COPY** = low effort, engine-identical.
> - **ADAPT** = needs OpenHouse catalog/policy/fixture rework.
> - **SKIP** → re-read as **BLOCKED/DEFER**: port it, but it has a prerequisite (a feature
>   OpenHouse must support, e.g. views/streaming, or a maintenance surface to assert against).
>   Flag the prerequisite; do not drop the test.
>
> See `11-build-and-port-plan.md` for the sequenced plan across both work axes.

## Source of counts

- **Repo/commit:** `apache/iceberg` @ `main`, commit `d41101270ed4f8738c0769e05e37f5126972ab33` (HEAD 2026-07-10).
- **Method:** direct count of `@Test` + `@TestTemplate` + `@ParameterizedTest` annotations per file (checked-out source, not estimates).
- **Dirs surveyed:** `spark/v3.5/{spark-extensions,spark}/src/test/java/org/apache/iceberg/spark/{extensions,source,sql}`.

## Headline numbers

| | count |
|---|---|
| Test files surveyed | 156 |
| Files with ≥1 test | 119 |
| **Declared test methods** | **1,271** (707 extensions + 342 source + 222 sql) |
| Effective executed cases | **several-fold higher** — see multipliers |
| OpenHouse today (baseline) | ~263, concentrated on catalog/DDL/policy surface |

**Two multipliers** inflate declared → executed, and they are exactly the axes our own
harness models (see `07`):
1. **Parameterization** — most classes are `@TestTemplate` × a `@Parameters` matrix (1–6 tuples).
2. **COW/MOR inheritance** — `TestCopyOnWriteDelete`/`TestMergeOnReadDelete` *extend* `TestDelete`
   (same for Merge/Update), re-running the parent's whole method set under each write mode on
   top of the shared 3-tuple `SparkRowLevelOperationsTestBase` matrix.

## How to read the grade

| Grade | Meaning |
|---|---|
| **COPY** | Pure Spark/Iceberg *engine* behavior, catalog-agnostic. Port with only catalog/namespace substitution (`hadoop.` → `openhouse.`). Fastest, and speeds review because reviewers can diff against upstream. |
| **ADAPT** | Relevant behavior, but needs rework for OpenHouse's catalog, policies, or fixture setup. |
| **SKIP/DEFER** | OpenHouse handles it differently (its own maintenance jobs), the feature may be unsupported (views, streaming), or it is Hive-migration-specific. **Evaluate support before touching.** |

> Every grade below is a **first pass**. The user's directive stands: *evaluate each test, do
> not blindly run.* Grades tell you where to spend evaluation effort, not what to skip reading.

---

## Category checklist

| # | Category | Declared methods | Delta-model fit | Grade | What to evaluate |
|---|---|---:|---|---|---|
| 1 | **DML: MERGE** | 83 | excellent — delta on row multiset | **COPY→ADAPT** | Core gap. Port `TestMerge` (76) + COW/MOR subclasses. Substitute catalog; confirm OpenHouse supports `MERGE INTO`. |
| 2 | **DML: UPDATE** | 51 | excellent | **COPY→ADAPT** | `TestUpdate` (42) + COW/MOR. Same substitution. |
| 3 | **DML: DELETE** | 72 | excellent | **COPY→ADAPT** | `TestDelete` (41) + COW(+4)/MOR(+8), `TestSparkReaderDeletes` (10, equality+position deletes), `TestDeleteFrom` (6). The MoR path exercises delete files — highest-value, most subtle. |
| 4 | **Partition evolution / transforms / bucketing** | 100 | good | **ADAPT** | `TestAlterTablePartitionFields` (25), transform fns bucket/truncate/days/hours/months/years (48), `TestIcebergSpark` UDF reg (18), `TestPartitionValues` (7). OpenHouse `PartitionTest` covers a slice; fill spec-evolution + transforms. |
| 5 | **Reads / scans / pushdown / SPJ / aggregates** | 293 | mixed — some absolute-state | **ADAPT + SKIP** | COPY the behavioral ones: `TestSelect` (26, incl. time-travel), `TestFilteredScan`/`TestFilterPushDown`/`TestAggregatePushDown`/`TestStoragePartitionedJoins`, `TestReadProjection` (schema-evo-on-read). SKIP/DEFER Spark-internal planning (`TestSparkPlanningUtil`, `TestRuntimeFiltering`) and **streaming** (`TestStructuredStreamingRead3` 33) unless OpenHouse targets streaming. |
| 6 | **INSERT / writes / distribution** | 100 | good | **ADAPT** | `TestSparkDataWrite` (14), `TestSetWriteDistributionAndOrdering` (14), `TestRequiredDistributionAndOrdering` (19), `TestConflictValidation` (11, isolation). Engine behavior; adapt fixtures. |
| 7 | **Snapshots / time-travel / branch / tag / WAP** | 56 | good — delta on snapshot set | **ADAPT** | `TestBranchDDL` (19), `TestTagDDL` (15), `TestSnapshotSelection` (12), `TestReplaceBranch` (7). OpenHouse has `Branch`/`WapId`; tags + time-travel-read + replace-branch are gaps. |
| 8 | **Metadata tables** | 90 | good — postcondition observation | **ADAPT** | `TestIcebergSourceTablesBase` (34), `TestPositionDeletesTable` (22), `TestMetadataTablesWithPartitionEvolution` (11), `TestChangelogTable` (10), `TestMetadataTables` (9). Confirm which metadata tables OpenHouse exposes. |
| 9 | **DDL (create/alter/drop/schema/props/namespace)** | 143 | n/a — mostly absolute | **MOSTLY COVERED** | OpenHouse's 263 already cover create/alter/drop/describe/show. Diff for gaps only. **`TestViews` (82) → SKIP unless OpenHouse supports Iceberg views** (evaluate first — it's the single biggest file). |
| 10 | **Procedures (CALL)** | 255 | mixed | **MOSTLY SKIP/DEFER** | OpenHouse runs maintenance via its **own jobs-scheduler**, not `CALL`. `rewrite_data_files`/`expire_snapshots`/`remove_orphan_files`/`rewrite_manifests` → verify via OpenHouse's job path, not these. Hive-migration procs (`add_files` 41, `migrate` 16, `snapshot` 13, `register_table`) → **N/A**. `rollback`/`cherrypick`/`set_current_snapshot`/`fast_forward` → evaluate as branch/snapshot ops. |
| 11 | **Types / defaults / nullability** | 9 | good | **ADAPT** | `TestTimestampWithoutZone` (8), `TestDataFrameWriterV2Coercion` (1). Note: recent OpenHouse work added column-default APIs — worth a small dedicated set beyond upstream. |
| 12 | **Other (row-lineage, catalog cache, FileIO, refresh)** | 19 | low | **SKIP** | `TestRowLevelOperationsWithLineage` (10) — evaluate if OpenHouse tracks `_row_id`/`_last_updated_sequence_number`. Rest is Spark/IO plumbing. |

Category sum = **1,271** ✔

---

## Priority order (gap-fill against OpenHouse's existing 263)

1. **Row-level DML** (#1–3, ~206 declared) — the core gap; perfect delta-model fit; start here.
2. **Partition evolution + transforms** (#4).
3. **Reads / time-travel / pushdown** (#5, behavioral subset only).
4. **Writes / distribution** (#6).
5. **Snapshots / branch / tag** (#7).
6. **Metadata tables** (#8).
7. **Types / defaults** (#11).
8. **Procedures** (#10) — mostly re-homed onto OpenHouse jobs; low cribbing yield.
9. **DDL** (#9) — diff-only; skip Views pending support decision.
10. **Streaming / lineage / internal-planning** — evaluate feature support before spending time.

---

## Suggested first slice (the wedge)

**The row-level DML family** — `TestDelete` + `TestUpdate` + `TestMerge` with their COW/MOR
subclasses. Rationale:

- **Highest-value gap**: OpenHouse's 263 barely touch MERGE/UPDATE/DELETE; this is real Iceberg
  contract surface.
- **Perfect delta-model fit** (see `06`): each is `{row multiset P} operation {row multiset Q}`,
  history-agnostic, and the COW-vs-MOR distinction maps directly onto an `Axis` value — so
  cribbing these *also* validates the harness's core loop.
- **~206 declared methods → several hundred effective** once the write-mode × format matrix is
  applied by our generator, from a modest authoring effort.

Concrete per-file checklist for the wedge (grade each method as copy / adapt / drop while porting):

- [ ] `TestDelete.java` (41) — base DELETE semantics
- [ ] `TestCopyOnWriteDelete.java` (+4 own, inherits 41) — COW axis
- [ ] `TestMergeOnReadDelete.java` (+8 own, inherits 41) — MOR axis (delete files)
- [ ] `TestSparkReaderDeletes.java` (10) — reader applying equality + position deletes
- [ ] `TestUpdate.java` (42) + `TestCopyOnWriteUpdate` (3) + `TestMergeOnReadUpdate` (6)
- [ ] `TestMerge.java` (76) + `TestCopyOnWriteMerge` (3) + `TestMergeOnReadMerge` (4)
- [ ] `SparkRowLevelOperationsTestBase.java` — the 3-tuple matrix → map to our `Axis` (do NOT copy verbatim; it becomes generator config)

---

## Coverage gaps in THIS inventory (not yet counted)

The survey scoped to `source` + `sql` + `extensions`. Still uncounted under
`.../org/apache/iceberg/spark/`:

- **`actions/`** — *action-level* RewriteDataFiles / ExpireSnapshots / RemoveOrphanFiles
  (distinct from the `CALL` procedure tests). **Directly relevant** to OpenHouse's job-based
  maintenance — likely a better cribbing target than the CALL procedures. **Recommend counting next.**
- **`functions/`** — system function unit tests (some overlap with #4 transforms).
- **`data/`** — data/generic read-write helpers.
- **~15 top-level `Test*Util.java`** — `TestSparkDistributionAndOrderingUtil`, `TestSparkV2Filters`,
  `TestSparkTableUtil`, etc. Mostly unit, low cribbing value.

---

## Evaluation stance (do not skip)

- **COPY ≠ blind-run.** Even for engine-identical tests, read each method: OpenHouse's catalog,
  default table properties, and policy layer can change expected behavior. The delta-model
  rewrite (`06`) forces you to understand each assertion anyway.
- **The COW/MOR/format matrix is generator config, not copied code.** Upstream bakes it into
  `@Parameters` + inheritance; we lift it into `Axis` (see `07`) so 3 authored deltas cover the
  whole write-mode × format cube.
- **Procedures are a trap.** 255 declared methods look like a lot of free coverage, but most map
  onto OpenHouse's own maintenance jobs or Hive migration — evaluate the *OpenHouse* surface,
  don't port `CALL`.
