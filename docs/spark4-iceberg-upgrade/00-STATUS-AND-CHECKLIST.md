# Spark 4.0 / Iceberg 1.11 upgrade — CURRENT status & checklist

**This is the authoritative current-state doc.** The rung ledger in `BUILD-STATUS.md` is the
historical record (pre-consolidation, monolithic harness, `1637/28/32`); it is NOT current. All work
is now consolidated onto the single branch **`1.11`** (OpenHouse), open as **PR #17** (`1.11` → `main`).

Related repos/branches:
- OpenHouse: branch `1.11` (PR #17) — the consumer, single integration branch.
- Iceberg fork: `mkuchenbecker/iceberg` `1.10.x` and `1.11.x` (custom patches on Apache 1.10/1.11 + the F-REPL replication fix). Resolved as `org.apache.iceberg:*:1.10.0-openhouse` / `1.11.0-openhouse`.
- Spark fork: `mkuchenbecker/spark` `branch-4.0` — OPTIMIZE / ANALYZE CLUSTERING QUALITY / VACUUM ported to Spark 4.0 (**merged**, PR #6).

## Current harness result (modular harness, `1.11`, Spark 4.0 / Iceberg 1.11 / stock RESTCatalog)
`2521 passed · 11 skipped · 42 failed` (2574 cases) → after the `/lock` fix, 3 lock cases recover
(**≈ 2524 / 11 / 39**; full matrix not yet re-run post-lock-fix). CTAS/RTAS pass; deletion vectors green.

## DONE ✅
- [x] Spark 4.0 + Scala 2.13 + JDK 17, REST-first (stock `org.apache.iceberg.rest.RESTCatalog` → `/iceberg` endpoint; no custom Spark catalog jar).
- [x] Iceberg 1.11 fork (`1.11.0-openhouse`); custom patches replayed onto Apache 1.11.
- [x] **Deletion vectors** verified on 1.11 (parquet + orc: `deletion-vector-v1` puffin blobs, reads correct, server v3-write path). Also works on 1.10.
- [x] Iceberg REST catalog endpoint: `/iceberg/v1/...` config/namespaces/table load-list-drop-rename/commit, **CREATE TABLE** (translated into OpenHouse's create service), **CTAS + RTAS**.
- [x] REST commit-path **validation parity** — recovered 13/18 server-side guards (lock, reserved props, partition/schema evolution) that the raw catalog commit bypassed; reconciled with main's #640 RTAS gate (single gate, no double-apply).
- [x] Modular harness (#13) re-ported to Spark 4.0 / Scala 2.13 / REST; spark-4.0 itest classpath module; DV battery.
- [x] **F-REPL** — delete-file replication was broken since 1.5.2 (Parquet/ORC bypass `HadoopOutputFile.create()`); fixed in the fork (1.10.x + 1.11.x), validated on **real HDFS**.
- [x] **Real-HDFS docker validation** (`oh-hadoop-spark`, Hadoop 3.2.1 + Spark 3.5.2): table write/read on HDFS ✅, server metadata-direct-write (3.3.4 client ↔ 3.2 HDFS) ✅. Fixed the broken docker recipe (Spark 3.5 / Hadoop 3.2 / Java-11 base).
- [x] `/lock` 500 fixed at root (commons-lang 2.x dropped from the 1.11 classpath → `NoClassDefFoundError` in the *existing* validator; one-line import → `commons-lang3`; **no API/behavior change**). Swept + fixed 2 more latent instances (databases validator, OTel config).
- [x] Consolidated onto single `1.11` branch with granular commit history (main + each change as its own commit). Stacked PRs #12/#16 closed as superseded.

## Decisions (all made) — status
- [x] **#1 CI = build fork from source, gated to `1.11` pushes only.** Implemented: `branch-1.11.yml` caller + fork-provision steps `if github.ref == refs/heads/1.11`; in-tree JVM-17 resolution-attribute fix (scoped to classpath configs, skips resolved configs) so a stock `./gradlew` works. Fork build in the CI runner **proven working** (jars published). Config-crash fixed. **Branch build compiling — verifying green (IN PROGRESS).**
- [x] **#3 `/lock`** — fixed at root, existing API untouched. DONE.
- [x] **#4 Behavioral residuals** — document only. (24 custom-SQL ParseException + ~15 REST-lane behavioral differences.) Documented.
- [ ] **#2 Custom Spark 4.0 wired into OpenHouse's build/testing** — decided YES; **NOT STARTED**. Blocked on disk (building the full Spark distribution needs ~15–30 GB; ~6 GB free; the 21 GB of finished-validation docker images are the reclaimable space — awaiting prune go-ahead).
- [ ] **#5 Both lanes coexist (Spark 3.5 / Iceberg 1.10 **and** Spark 4.0 / Iceberg 1.11)** — decided YES; **PARTIAL**. Build carries both lanes' modules; the docker recipe spark-lane + full both-lanes validation is pending (tied to #2).

## REMAINING (critical path)
1. **[in progress] Finish CI green on the `1.11` branch build** — fork provisioning + config-crash fixed; confirm the real compile passes; fix anything it surfaces. (PR-into-main check stays red until a separate decision to provision the fork on PRs or skip the 1.11-dependent modules there.)
2. **[blocked on docker-prune] #2 Custom Spark 4.0 build + publish + point OpenHouse's spark-4.0 lane at it** so OPTIMIZE/VACUUM are exercised.
3. **[after #2] #5 Both-lanes coexistence** — keep Spark-3.5/Iceberg-1.10 alongside Spark-4.0/Iceberg-1.11; reconcile the docker recipe spark-lane; validate both in one pass.
4. **[deferred / cost finding] Real publishing** — fork currently = git branches + CI-build-from-source + local mavenLocal. Production ship needs a proper artifact repo.

## Accepted / documented, NOT open work
- 24 custom OpenHouse SQL `ParseException` (SET POLICY/GRANT/column-tags) — no Spark SQL extension parser on the stock REST lane; validated via table properties (decision). Parity TODO / syntactic sugar.
- ~15 REST-lane behavioral differences (namespace/rename exception types, concurrency-CAS, partition-date message) — documented (decision #4).
- Perf observation: Spark 4.0 AQE thread accumulation makes the parallel harness run slow (~75 min); correctness unaffected.
