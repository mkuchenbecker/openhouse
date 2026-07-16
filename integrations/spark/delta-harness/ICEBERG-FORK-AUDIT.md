# Iceberg fork audit — `com.linkedin.iceberg` `openhouse-1.5.2` vs Apache 1.5.2

Persistent record (for agents bootstrapping from this PR). The harness tests against the fork bytecode
`com.linkedin.iceberg:iceberg-spark-runtime-3.5_2.12:1.5.2.15` — NOT Apache. This audits what the fork
changed and which of our findings are fork-specific vs upstream.

**Baseline:** Apache 1.5.2 @ `cbb853073`. Custom range `cbb853073..openhouse-1.5.2` = **21 commits, 83
files, +2742/-739** (spark 46, core 21, api 5). ~6 are pure CI/release/version; ~15 functional.

## Headline: the "sneaky v3 backport" is COLUMN DEFAULTS (#251), and it is inert-but-latent

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
| **#251 d1603c807** | api/core schema | initial/write-default APIs + SchemaParser round-trip; no read-apply, no v3 gate, no Spark wiring | **HIGH** | partial (`insert.explicitColumns` pins the *absence* of wiring; no test for defaulted-read→NULL or v2 persistence) |
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
