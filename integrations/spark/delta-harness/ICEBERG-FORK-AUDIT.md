# Iceberg fork audit — `com.linkedin.iceberg` `openhouse-1.5.2` vs Apache 1.5.2

Persistent record (for agents bootstrapping from this PR). The harness tests against the fork bytecode
`com.linkedin.iceberg:iceberg-spark-runtime-3.5_2.12:1.5.2.15` — NOT Apache. This audits what the fork
changed and which of our findings are fork-specific vs upstream.

**Baseline:** Apache 1.5.2 @ `cbb853073`. Custom range `cbb853073..openhouse-1.5.2` = **21 commits, 83
files, +2742/-739** (spark 46, core 21, api 5). ~6 are pure CI/release/version; ~15 functional.

## #251 — column-default backport (api/core)

Commit `d1603c807` (#251) "Add NestedField column-default APIs and Expressions.lit() (~upstream #9502)"
adds the format-v3 column-default feature to the v2 fork, api/core only. **Status: TABLED per repo owner.**

**Presence**
- On the `openhouse-1.5.2` branch HEAD (`d1603c807`).
- Not in the published `com.linkedin.iceberg:1.5.2.15` artifact (the default harness runtime). Verified
  against the 1.5.2.15 jars: `Types.NestedField.builder()/initialDefault()/writeDefault()` and
  SchemaParser's `INITIAL_DEFAULT`/`WRITE_DEFAULT` are absent. `d1603c807` post-dates the 1.5.2.15 release.

**What #251 contains (branch source)**
- API: `NestedField.builder()…withInitialDefault(…)/withWriteDefault(…)`; `Expressions.lit(…)`.
- Serialization: `SchemaParser` writes/reads `initial-default` + `write-default` in the schema JSON
  (`core/.../SchemaParser.java`, constants `INITIAL_DEFAULT`/`WRITE_DEFAULT`). `toJson` takes no
  format-version parameter, so the keys serialize regardless of the table's format version.
- Read application: no references to `initialDefault`/`writeDefault` outside `SchemaParser` in the open
  fork (`git grep` over core/data/spark = 0). `core/.../util/PartitionUtil.java` `constantsMap()` is
  unchanged from Apache.
- Write wiring: `SparkTable` does not implement `SupportsColumnDefaultValue`.

**Measured behavior** (harness = OSS Spark 3.5 over the fork runtime; `fork.colDefault.*` tests)
- OSS Spark `ALTER TABLE t ADD COLUMN c int DEFAULT 5`: accepted at parse time; the default is not
  written into the Iceberg schema (`DESCRIBE` shows `c|int|null`); pre-existing rows read NULL; an INSERT
  that omits `c` is rejected `INCOMPATIBLE_DATA_FOR_TABLE.CANNOT_FIND_DATA`. Identical on 1.5.2.15 and the
  branch build.
- Branch runtime, default set via the low-level `TableMetadata` API: `SchemaParser` serializes
  `{"…","type":"int","initial-default":5}` (no format-version field) and round-trips. Reading over
  pre-existing data files via OSS Spark returns NULL. Whether a default is applied on read is not covered
  by this harness — no read-application code exists in the open fork; any such path (e.g. in LinkedIn's
  private Spark) is not exercised here.
- Whole-suite branch-vs-release regression: 0 deltas (branch STUB 2071/11/0, REAL-HTS 2289/11/0; see
  VERIFIED-RUN-openhouse.txt).

**Tests** (characterization pins): `fork.colDefault.addColumnInert @ parquet|orc` (OSS Spark DDL path),
`fork.colDefault.apiSerialization @ core` (SchemaParser serialization; runs in both artifacts — release
records API-absent, branch records the serialized `initial-default`), `fork.colDefault.readApplyProbe @
core` (DIAG-only: records the OSS-Spark read result, asserts only that the default persists into the
schema). See ICEBERG fork checkout to rebuild the branch runtime; see run-openhouse.sh `ICEBERG_RUNTIME_JAR`.

## Enumerated custom commits

| Commit | Subsystem | Change | Risk | Tested? |
|---|---|---|---|---|
| **#251 d1603c807** | api/core schema | initial/write-default APIs + SchemaParser serialization; no read-apply and no Spark wiring in the open fork. On branch HEAD only — not in the 1.5.2.15 artifact. | n/a (tabled) | yes — `fork.colDefault.*` (addColumnInert, apiSerialization, readApplyProbe). See #251 section. |
| #249 d69c1fd91 | spark write | partitioned default distribution → NONE (Apache = HASH); DML still HASH | **med** (behavior divergence: more small files) | **yes** — `fork.partitionDist.default @ parquet\|orc` (default=32 files vs hash=4; active in BOTH release + branch). |
| #189 04d2cd2af | core/spark compaction | budgeted rewrite + order-by-file-sequence-number (task selection/order only; not delete resolution) | low | **yes** — `fork.compactionOrder @ parquet` (file_sequence_number monotonic across commits; shares rewrite path w/ #233). |
| #233 a6aef6788 | core/spark compaction | bin-pack weight by data-file length, ignore delete size | low | **yes** — `fork.binPackByLength @ parquet\|orc` (rows preserved through rewrite). |
| #229 809534da0 | table/spark props | delete-file replication toggle (`write.delete-file-replication`) | low | **yes** — `fork.deleteFileReplication @ mor` (prop round-trips; MoR delete correct). HDFS repl not observable on local FS. |
| #219 25f1e5c9b | core io | per-output-file replication factor: `OutputFileFactory.FILE_REPLICATION_FACTOR = "file-replication-factor"` — stamped by the DELETE-file write path only (NOT a settable table prop; corrects an earlier key guess). Same mechanism as #229. | low | **yes** — `fork.fileReplicationFactor @ core` (factory stamps the key into the FileIO property map). |
| #228 efb092202 | spark read | split-size SparkSQLProperty (`spark.sql.iceberg.split-size`) | low | **yes** — `fork.splitSize @ parquet\|orc` (planner task-group count 1 vs 6 via planTasks() with open-file-cost=1; rows correct). |
| #234 c9c41c46f | spark action | stream-results for remove_orphan_files (OOM avoidance) | low (+) | no |
| #236 e1103d86c / #214 0b10d8734 | catalog / spark write | app-name into EnvironmentContext / snapshot summary | none | no |
| #224 b7851bb63 | spark parser | skip rewriting Spark views (LI Spark 3.5) | low | no |
| #241 3d6d0d7c1 | core catalog | CachingCatalog metadata-table load fix (upstream #11738) | none (fix) | no |
| #245 198cc0189 | core util | ParallelIterable memory/OOM fix (#9402/#10787/#10979) | none (fix) | no |
| #246 c3a07e2ef | core util | PartitionSet null-partition NPE fix (#10680) | none (fix) | no |
| #248 0ffc69d3f | build | Avro 1.11.4 (CVE-2024-47561) | none | no |
| #208–211,216,222 | build | version / publish / CI only | none | n/a |

## Untested fork changes (candidates, no user decision yet)
- **#251 column defaults** — TABLED (see #251 section above). `fork.colDefault.*` cover the OSS Spark DDL
  path + SchemaParser serialization; read-application is out of scope for this harness (no such code in
  the open fork).
- **#234 stream-results for remove_orphan_files** (OOM avoidance), **#236/#214** app-name into
  EnvironmentContext/snapshot summary, **#224** skip rewriting Spark views — not yet tested (low value:
  OOM-avoidance / audit-metadata / view paths, not locally observable). #241/#245/#246 are upstream fixes.

## Cross-reference to harness findings
- **bug `insert.explicitColumns`** — ROOT is #251's non-wiring (SparkTable lacks SupportsColumnDefaultValue). Reclassified to a pin (correct).
- **G14 (dangling MoR delete)** — NOT fork-introduced; stock Iceberg 1.5 (no `remove-dangling-deletes` folding). #189/#233 don't touch delete resolution. Pin flips on an Iceberg bump, not a fork fix.
- **G13 (CDC over MoR update/merge)** — stock Iceberg 1.5 changelog-scan limitation; not fork-specific.
