# Rung 3 progress — built-vs-plan

## Gate result

| Stack | Result | Rung-2 baseline | Delta |
|---|---|---|---|
| Spark 4.0 / Iceberg **1.11** / Scala 2.13 / JDK 17 / REST | **1637 passed / 28 skipped / 32 failed** (1697) | 1637 / 28 / 32 | **0** — identical pass/skip/fail set |

The 1.11 bump is behavior-neutral for the harness matrix: same 1637 green, same 28 skips, and the
**exact same 32 failures** as rung-2 (no new failures, none newly-passing, no regression). The 32
are the pre-categorized REST-first gaps (12 policy/ACL/colTag `ParseException`, 2 CTAS/RTAS→501, 18
REST-vs-native validation divergences) — see rung-2 `harness-spark4/10-progress.md` §4. Not port
bugs; out of scope to fix here.

## Step 1 — DV support at Iceberg 1.10 (probe, no rebuild)

Ground truth (corrects the rung premise): **Iceberg 1.10.0 already writes mature deletion vectors**
at `format-version=3` merge-on-read. Evidence, via `run-dvprobe.sh` on the rung-2 (1.10) stack:

- **v3** (`FV=3`): DELETE+UPDATE+MERGE(merge-on-read) → 3 files in `.all_delete_files` with
  `content=1` (POSITION_DELETES), **`file_format=PUFFIN`**, physical `*-deletes.puffin` files each
  carrying a **`deletion-vector-v1`** blob (grep-verified in the puffin footer). One DV per data
  file. Reads correct: count 4/6, surviving rows `1a 4Z 5e 6f`.
- **v2** (`FV=2`, contrast): same DML → `content=1`, **`file_format=PARQUET`**,
  `*-deletes.parquet` classic position-delete files, **0 puffin files**.

So DVs are reachable *before* the 1.11 bump; 1.11 is not the enabling version for OpenHouse's REST
path. The gate of rung-3 is therefore "1.11 keeps the matrix green AND still does DVs", which holds.

## Step 2 — 1.11 fork bump

- **1.11 tag available?** Yes — `apache-iceberg-1.11.0` (commit `6976e020`) present in the clone
  after `git fetch origin --tags`. Branch `openhouse-1.11-port` cut from it.
- **Patch replay:** the fork's net delta vs `apache-iceberg-1.10.0` is core+api + spark-3.4/3.5 +
  two build files. **spark/v4.0 carries NO fork patch — it is stock.** rung-3 is Spark-4.0-only, so
  only the **core+api** patches were replayed (file-replication-factor #219, delete-file-replication
  plumbing, `TableScanUtil` ContentScanTask, `RewriteFileGroup`/`RewriteDataFiles` max-file-size,
  `FileIO.newOutputFile` default, `forceVersion` build hook). 10 of 12 core/api/build files applied
  cleanly via `git apply --3way`.
- **Dropped as now-upstream/redundant in 1.11:**
  - `CatalogProperties.APP_NAME` — already present upstream in 1.11 (3-way produced no net change).
  - `baseline.gradle` `com.palantir.revapi` apply block — 1.11 applies revapi via the upstream
    `org.revapi.revapi-gradle-plugin` in `build.gradle`; the palantir variant is gone. Conflict
    resolved by keeping 1.11's block, dropping the fork's.
- **Deferred (NOT replayed):** spark-3.4/3.5 fork patches (out of rung-3 scope; those lanes are not
  built for the Spark-4.0 gate). Honest status: deferred, not proven-upstream.
- **Java floor:** 1.11 raised to JDK 17 (`sourceCompatibility=17`, `options.release=17`, build
  refuses JDK 11). Built with system gradle 8.14.3 + JDK 17 and an init script pinning the
  `TargetJvmVersion` attribute to **17** (was 11 for the 1.10 lane).
- **Published:** `core, api, common, data, parquet, orc, arrow, bundled-guava, spark-4.0_2.13,
  spark-extensions-4.0_2.13, spark-runtime-4.0_2.13` all at **`1.11.0-openhouse`** in
  `~/.m2/repository/org/apache/iceberg/` (runtime uber jar ~48 MB). BUILD SUCCESSFUL.

## Step 3 — point OpenHouse + harness at 1.11

- Root `build.gradle`: **kept** `iceberg_1_10_version = "1.10.0-openhouse"` (still used by the
  spark-3.5 lane + iceberg-1.5 java runtime, which reference an unpublished spark-runtime-3.5 at
  1.11) and **added** `iceberg_1_11_version = "1.11.0-openhouse"`.
- `buildSrc/.../openhouse.iceberg-conventions-1.5.2.gradle`: server core →
  `rootProject.ext.iceberg_1_11_version`; target-JVM resolution attribute 11 → **17**.
- `buildSrc/.../openhouse.java-minimal-conventions.gradle`: target-JVM attribute 11 → **17**
  (17 also accepts jvm-8/11 libs, so the 1.10 lane still resolves).
- Spark-4.0 itest module `.../openhouse-spark-itest/build.gradle`: `icebergVersion` →
  `1.11.0-openhouse`; shared-classpath avro force `1.12.0` → `1.12.1` (1.11's declared avro).
- Server (`services:tables`) is rebuilt implicitly by the harness classpath resolution
  (`printHarnessCp` builds the runtime + fixtures + service jars against 1.11 core).

## Step 4 — matrix on 1.11

`1637 / 28 / 32`, identical to rung-2 (see table above). Full FAIL list in `20-pitfalls.md`.

## Step 5 — DV battery on 1.11

Same evidence shape as the 1.10 probe, now on Iceberg **1.11** + Spark 4.0 + OpenHouse:
`format-version=3` (server-authored), merge-on-read DELETE+UPDATE+MERGE → **3 `deletion-vector-v1`
puffin files** (`content=1`, `file_format=PUFFIN`, one DV per data file), reads reflect the deletes
(count 4, rows `1a 4Z 5e 6f`), committed through the OpenHouse REST server's v3-metadata-write path.
Behavior matches 1.10 exactly.
