# Iceberg fork audit — `com.linkedin.iceberg` `openhouse-1.5.2` vs Apache 1.5.2

Persistent record (for agents bootstrapping from this PR). The harness tests against the fork bytecode
`com.linkedin.iceberg:iceberg-spark-runtime-3.5_2.12:1.5.2.15` — NOT Apache. This audits what the fork
changed and which of our findings are fork-specific vs upstream.

**Baseline:** Apache 1.5.2 @ `cbb853073`. Custom range `cbb853073..openhouse-1.5.2` = **21 commits, 83
files, +2742/-739** (spark 46, core 21, api 5). ~6 are pure CI/release/version; ~15 functional.

## Headline: the "sneaky v3 backport" is COLUMN DEFAULTS (#251), and it is inert-but-latent

> **CORRECTION (2026-07-20, compiler- + runtime-verified) — #251 is NOT in the DEPLOYED artifact.**
> The source analysis below is correct about the `openhouse-1.5.2` **branch HEAD** (`d1603c807`), but that
> commit **POST-dates** the `com.linkedin.iceberg` **1.5.2.15** release that the harness actually loads.
> In 1.5.2.15 the column-default APIs are simply **absent** (`Types.NestedField.builder()/initialDefault()
> /writeDefault()` don't compile; `SchemaParser` has no `INITIAL_DEFAULT`/`WRITE_DEFAULT`). Therefore:
> - The **cross-engine persistence hazard** (v2 table serializes a v3 default with no gate) is **latent in
>   source, NOT live** in what OpenHouse runs today — there is no serialization path to exercise.
> - What the customer path (`ALTER TABLE t ADD COLUMN c int DEFAULT 5`) does on 1.5.2.15, **measured**:
>   **accepted** at Spark parse time → **silently dropped** from the persisted schema (`DESCRIBE` = `c|int
>   |null`, no default) → **not backfilled on read** (old rows NULL) → **not applied on write** (omit-insert
>   rejected `INCOMPATIBLE_DATA_FOR_TABLE.CANNOT_FIND_DATA`). Inert-but-**silent** — arguably worse than a
>   rejection (operator believes a default was set; it was not).
> - **Tests built** (`fork.colDefault.addColumnInert @ parquet|orc`, green) hard-pin all four behaviours.
>   They are a **tripwire**: when OpenHouse bumps to a #251-containing artifact (and/or wires SparkTable),
>   every assert flips → suite fails → someone re-audits the now-live hazard against that build.
>
> **UPDATE 2026-07-20 — the branch WAS built and tested (we test the BRANCH, not the release).** Built the
> `openhouse-1.5.2` HEAD shaded `iceberg-spark-runtime-3.5_2.12` (`-PciVersion=1.5.2.15-branchHEAD`,
> javap-verified to carry the #251 APIs) and ran the whole harness against it via the reversible
> `ICEBERG_RUNTIME_JAR` swap hook in `run-openhouse.sh`. Results:
> - **OSS Spark-SQL path is byte-for-byte identical on the branch** — `ADD COLUMN … DEFAULT` is still
>   accepted→silently-dropped→NULL→`CANNOT_FIND_DATA`. OSS Spark 3.5 has no default-setting wiring.
> - **api/core hazard is LIVE and now pinned** — `fork.colDefault.apiSerialization @ core` (reflection,
>   runs in both artifacts) builds `optional int c` with `initial-default=5` and shows `SchemaParser` emits
>   `{"type":"struct",…,"type":"int","initial-default":5}` — an **ungated** struct (no format-version arg),
>   round-tripping. Confirms the v2-persists-v3-default-no-gate risk empirically.
> - **Whole-suite regression branch-vs-release: ZERO deltas.** branch STUB 2071/11/0 (2082), REAL-HTS
>   2289/11/0 (2300), every other correctness assertion identical to release; 0 ORC↔Parquet divergence. The
>   post-release branch commits (#251, #249, #248, …) introduce **no correctness regression** on any of the
>   ~2000 tested behaviours. (See VERIFIED-RUN-openhouse.txt.)
>
> **CORRECTION (2026-07-20) — "customer-unreachable" was WRONG. The READ-APPLY correctness bug IS live.**
> I initially reasoned the hazard needs a Spark DDL wiring OSS lacks. But LinkedIn's production Spark is a
> **private fork** that CAN set a column default (that is the whole reason #251 backports the api/core
> surface). So the customer path is reachable in prod, and the real bug is on the READ side:
> - #251 wires **NO read-application** — `PartitionUtil.constantsMap` does not inject `initial-default`, and
>   NOTHING in `core/data`, `iceberg-data`, or `spark/v3.5` references it (grep-verified on the branch).
> - `fork.colDefault.readsNullNotDefault @ core` PROVES it end-to-end: it simulates the private-Spark
>   `ADD COLUMN c int DEFAULT 5` by committing that exact schema (with `initial-default=5`) via the
>   low-level `TableMetadata` API (public `UpdateSchema` has no set-default op on the branch), then READS
>   back via Spark over the pre-existing data files → **`c = [NULL, NULL]`, not `[5, 5]`**. The default is
>   durably persisted but silently ignored on read.
> - The read path is **engine-shared** iceberg-core/spark: the same private Spark that WROTE the default
>   reads through this unchanged path, so it too sees NULL. **Silent data-correctness bug** — the operator
>   sets a default, existing rows come back NULL. This is precisely the "sneaky v3 backport that doesn't
>   alter the spec but risks correctness issues." The pin flips the day read-apply is wired.

Commit `d1603c807` (#251) "Add NestedField column-default APIs and Expressions.lit() (~upstream #9502)"
backports the **format-v3** column-default feature into a **v2** fork, **api/core only**:
- **Serialization: durable.** `SchemaParser` writes/reads `initial-default` + `write-default` into the
  schema JSON stored in `metadata.json` — with **NO format-version gate**. A v2 (or v1) table can
  persist them (`core/.../SchemaParser.java`, constants `INITIAL_DEFAULT`/`WRITE_DEFAULT`).
- **Read application: MISSING.** `git grep initialDefault|writeDefault` outside the 3 authored files =
  **0 consumers**. `core/.../util/PartitionUtil.java` `constantsMap()` — where Apache injects a
  missing-column default — is UNCHANGED. So a defaulted, added column reads back **NULL, not the default**.
- **Write wiring: MISSING.** `SparkTable` does NOT implement `SupportsColumnDefaultValue`; no DDL stamps
  a write-default. (This is the ROOT of harness bug `insert.explicitColumns` — the pin's flip-condition
  is therefore "read+write application wired", which this fork does NOT satisfy.)

**Verdict: BOTH (a) incomplete backport → NULLs instead of defaults, and (b) cross-engine hazard →
v3 semantics on a v2 table with no gate.** Stock Apache 1.5.2 / Trino / Flink don't know the keys →
they DROP them on the next metadata rewrite (round-trip loss) and read NULL. Inert only while no
producer ever sets a default; the moment one does (v3-engine migration, manual metadata edit, future
wire-up), both modes activate **silently — no error, no gate, no signal.** Matches the user's
description exactly ("doesn't alter the spec but risks correctness").

## Enumerated custom commits

| Commit | Subsystem | Change | Risk | Tested? |
|---|---|---|---|---|
| **#251 d1603c807** | api/core schema | initial/write-default APIs + SchemaParser round-trip; no read-apply, no v3 gate, no Spark wiring. **NOTE: on branch HEAD only — NOT in the deployed 1.5.2.15 artifact.** | **HIGH (latent)** | **yes** — `fork.colDefault.addColumnInert @ parquet\|orc` pins the deployed reality (accept→silent-drop→NULL→CANNOT_FIND_DATA); tripwire flips on a #251-containing bump. v2-persistence hazard not testable until then. |
| #249 d69c1fd91 | spark write | partitioned default distribution → NONE (Apache = HASH); DML still HASH | **med** (behavior divergence: more small files) | no |
| #189 04d2cd2af | core/spark compaction | budgeted rewrite + order-by-file-sequence-number (task selection/order only; not delete resolution) | low | partial |
| #233 a6aef6788 | core/spark compaction | bin-pack weight by data-file length, ignore delete size | low | no |
| #229 809534da0 | table/spark props | delete-file replication toggle | low | no |
| #219 25f1e5c9b | core io | delete-file HDFS replication factor | low | no |
| #228 efb092202 | spark read | split-size SparkSQLProperty | low | no |
| #234 c9c41c46f | spark action | stream-results for remove_orphan_files (OOM avoidance) | low (+) | no |
| #236 e1103d86c / #214 0b10d8734 | catalog / spark write | app-name into EnvironmentContext / snapshot summary | none | no |
| #224 b7851bb63 | spark parser | skip rewriting Spark views (LI Spark 3.5) | low | no |
| #241 3d6d0d7c1 | core catalog | CachingCatalog metadata-table load fix (upstream #11738) | none (fix) | no |
| #245 198cc0189 | core util | ParallelIterable memory/OOM fix (#9402/#10787/#10979) | none (fix) | no |
| #246 c3a07e2ef | core util | PartitionSet null-partition NPE fix (#10680) | none (fix) | no |
| #248 0ffc69d3f | build | Avro 1.11.4 (CVE-2024-47561) | none | no |
| #208–211,216,222 | build | version / publish / CI only | none | n/a |

## Correctness-risk ranking + proposed tests (PENDING user +1 — dormant-feature tests)
1. **#251 defaulted column reads NULL** — set `initial-default` programmatically on a v2 table, write
   pre-add rows, read → assert NULL today (pins incomplete backport; flips when read-apply lands).
   Pair with `assert !(SparkTable instanceof SupportsColumnDefaultValue)`.
2. **#251 v2 persists v3-only defaults, no gate** — serialize a schema-with-defaults, re-parse with a
   stock 1.5.2 SchemaParser, assert the field is lost; assert the fork writes the key on a v2 table
   with no exception (documents the missing gate).
3. **#249 partitioned writes no longer cluster** — INSERT into a partitioned table without setting
   `write.distribution-mode`; characterize NONE (Apache divergence); confirm DELETE/UPDATE still HASH.

## Cross-reference to harness findings
- **bug `insert.explicitColumns`** — ROOT is #251's non-wiring (SparkTable lacks SupportsColumnDefaultValue). Reclassified to a pin (correct).
- **G14 (dangling MoR delete)** — NOT fork-introduced; stock Iceberg 1.5 (no `remove-dangling-deletes` folding). #189/#233 don't touch delete resolution. Pin flips on an Iceberg bump, not a fork fix.
- **G13 (CDC over MoR update/merge)** — stock Iceberg 1.5 changelog-scan limitation; not fork-specific.
