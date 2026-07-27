# Spark-4.0 / Iceberg-1.11 upgrade — REMAINING WORK master checklist

Durable, review-oriented tracker for the remaining rungs of the modernization spike, worked
**sequentially**. Each item links to its own audit-grade write-up (problem → root cause → fix →
verification) under `docs/spark4-iceberg-upgrade/`. This file is the index + live status.

Branch: `1.11` (OpenHouse) / fork `1.11.x` (`mkuchenbecker/iceberg`). Real CI gate: `Branch 1.11 CI`
(push event). PR-path `Pull Request Validations` is red-by-design (fork not provisioned on PRs).

**Companion docs:** `CHANGE-HISTORY.md` (per-repo sequential diff walkthrough + repos-in-scope +
pending PRs) · `KNOWN-GAPS.md` (open follow-ups, deployment gates, deferred scope).

## Status legend
`[ ]` not started · `[~]` in progress · `[x]` done + verified (CI-green) · `[!]` blocked/deferred

---

## 0. Completed before this phase (context)
- [x] Rung 1 — Iceberg 1.10 + Java 11 + Hadoop 3.3 (kept Spark 3.5)
- [x] Rung 2 — Spark 4.0 + Scala 2.13 + Java 17, REST cutover — **CI-green** (`77eb4e98`)
- [x] Spark-4.0 REST e2e harness + full `catalogtest` port + backlog triage (task #17) — all green
- [x] Server `/iceberg` policy translation + `OpenhouseSparkSessionExtensions` port (SET/UNSET/tags)

---

## 1. GRANT / REVOKE / SHOW GRANTS on the REST lane  ← DONE
Upgraded from the earlier documented deferral to a real implementation.

- [x] Port `GrantRevokeStatementExec` + `ShowGrantsStatementExec` (and `Principal` constant) to
      Spark-4.0 / Scala-2.13; shared HTTP logic in `OpenHouseAclClient`.
- [x] Wire them into the Spark-4.0 `SparkStrategy` (plan → exec)
- [x] Call the EXISTING server ACL endpoint (`PATCH /v1/databases/{db}/tables/{t}/aclPolicies`,
      body `UpdateAclPoliciesRequestBody`; `GET .../aclPolicies` → `GetAclPoliciesResponseBody`)
      directly over HTTP — the stock `RESTCatalog` does NOT implement `SupportsGrantRevoke`, so
      base URI (strip `/iceberg`) + bearer token are derived from the session catalog conf.
- [x] Privilege→role mapping mirrored EXACTLY from `javaclient/mapper/Privileges.java`:
      SELECT/DESCRIBE→TABLE_VIEWER, ALTER→TABLE_ADMIN, MANAGE GRANTS→ACL_EDITOR,
      CREATE TABLE→TABLE_CREATOR.
- [x] Test: `GrantRevokeTestSpark4_0`. A real ACL roundtrip is impossible in-JVM (embedded
      `OpaAuthorizationHandler` no-ops without an external OPA store), so matched the legacy
      client-contract bar: GRANT/REVOKE return 204 against the real embedded server, AND a capturing
      `HttpServer` stub proves the exact PATCH (operation+role+principal+path) and SHOW GRANTS row
      parsing. See `grant-revoke-rest-lane.md` §4.
- [x] Restored the dropped `GRANT` in `WapIdTestSpark4_0.testWapWorkflowWithVariousOperations`.
- [x] Write-up: `grant-revoke-rest-lane.md`; updated `10-RESIDUALS.md` (deferral → fixed).

## 2. Rung 3 — Iceberg 1.11 + v3 DSv2 deletion vectors (THE GOAL)  ← DONE
Pre-dispatch findings (durable):
- **Version: already 1.11.** `build.gradle` sets `iceberg_1_11_version = "1.11.0-openhouse"` and the
  Spark-4.0 runtime + itest modules resolve `1.11.0-openhouse`. No version bump needed — 1.11 is the
  release that matures v3 deletion vectors.
- **Format version is server-forced, not client-controlled.**
  `OpenHouseInternalRepositoryImpl.save` (~line 554) OVERWRITES `TableProperties.FORMAT_VERSION` with
  `clusterProperties.getClusterIcebergFormatVersion()` on every save — so a client `format-version`
  hint is ignored. The knob is the cluster config `cluster.iceberg.format-version` (default **2**,
  `ClusterProperties.java:43`).

- [x] Confirm the client + server are on Iceberg 1.11 (not 1.10) end-to-end — DONE (1.11.0-openhouse)
- [x] Enable `cluster.iceberg.format-version=3` for the verification path WITHOUT flipping the green
      suite: isolated `deletionVectorTest` Gradle task forks its own JVM with
      `-Dcluster.iceberg.format-version=3`; `DeletionVectorTestSpark4_0` is excluded from the
      default-v2 `test` task. Confirmed the 1.11 metadata-writer emits valid v3 (table property +
      `TableMetadata.formatVersion()==3` + on-disk `"format-version":3`).
- [x] Verify DSv2 deletion vectors: Spark-4.0 MOR `DELETE` on a v3 table writes a puffin deletion
      vector (`.delete_files` `file_format=PUFFIN`, physical `*-deletes.puffin` with a
      `deletion-vector-v1` blob, zero PARQUET pos-deletes), and the row is gone on read-back
      (survivors `[1,3,4,5,6]`)
- [x] e2e test on the Spark-4.0 REST lane (`DeletionVectorTestSpark4_0`, 2 methods PASSED); write-up
      `rung3-v3-deletion-vectors.md` (incl. global-v3-default cost assessment: v2 read cliff → Rung 9)

## 3. Rung 7 — Server/metadata-writer runtime → Java 17 (keep Java-8 bytecode where consumed)  ← DONE
Already satisfied by the 1.11 upgrade architecture; verified empirically (no code change needed).
- [x] Runtime = Java 17: CI + the embedded server (Spring Boot 2.7) build/boot/serve on JDK 17
      (`build-run-tests.yml` "Set up JDK 17"); no module pins a Java-8/11 toolchain
- [x] Bytecode = Java 8 where consumed: `java-minimal-conventions` keeps `targetCompatibility=1_8`;
      the metadata-writer `OpenHouseInternalCatalog` / `OpenHouseInternalTableOperations` compile to
      **major version 52 (Java 8)** on a JDK-17 compiler (verified via `javap`), and advertise Java 8
      to consumers so Java-8 readers of the metadata output keep working
- [x] Build + boot + harness-gate: the whole Spark-4.0 REST itest e2e suite boots the embedded
      server in the JDK-17 test JVM and is green on `Branch 1.11 CI`. Write-up
      `rung7-java17-runtime.md`

## 4. Rung 8 — FINAL VALIDATION: delta-harness full matrix on Spark 4.0 / Iceberg 1.11  ← DONE
- [x] Ran the full matrix against the real embedded OpenHouse catalog on the Spark-4.0 / Iceberg-1.11
      / REST-first stack: **2542 passed, 11 skipped, 21 failed (2574 cases)**, 98.8% pass. First run
      had 39 failures; ~18 were purely the harness not registering the OpenHouse SQL extension —
      fixed in `Env.scala` (`24dd99b`), re-run → 21.
- [x] All 21 residuals triaged (write-up `rung8-final-validation.md`): **zero OpenHouse product
      regressions.** They split into (A) harness assertions written for the legacy fork/custom-client
      lane (rename-conflict / ns-create exception types; grant-on-unshared server-400), (B) the REST
      lane *fixing* legacy bugs the harness still asserts broken (G10 retention & H3 column-tag
      survive RTAS — harness says "flip this test"), and (C) stock-Spark-4.0/Iceberg-1.11-vs-fork
      engine deltas (fork col-default feature absent on stock, branch ref-override, concurrent-append
      count, partition-reject message, rename-consumer handoff). Harness-baseline polish is noted as
      follow-up; not product work.

## 5. Rung 9 — Spike: HDFS client on Java 17 runtime (RBF wire-compat) + v3 read cliff  ← DONE
- [x] Server HDFS client moved to **Hadoop 3.3** (the target that matters; legacy 2.10.0 is unfit
      for Java 17 and lacks `FileSystem.openFile`): `hadoop-conventions` `2.10.0 → 3.3.6`. Fixed the
      transitive fallout at the source (commons-lang 2.x → lang3; Apache Directory `Strings` → lang3;
      codehaus Jackson 1.x → fasterxml). Verified: all `hadoop-conventions` modules compile main+test,
      `cluster:storage:test` green, server boots (`generateOpenApiDocs`), `TablesControllerTest` +
      `PoliciesSpecMapperTest` green on JDK 17 + Hadoop 3.3.6.
- [x] Java-17 runtime + RBF wire-compat (3.3.6 client ↔ 3.1/3.2 routers) + v3 read-cliff assessed;
      real-HDFS-cluster run remains a deployment gate (F-VACUITY-HADOOP). Write-up
      `rung9-hdfs-java17-v3-readcliff.md`

---

## Change log (commits, newest first)
- (this commit) Rung 8 — delta-harness full matrix: 2542 passed / 11 skipped / 21 failed (2574),
  all 21 triaged to harness-expectation or stock-engine-vs-fork deltas (0 product regressions);
  `rung8-final-validation.md`.
- `08b06e8` fix(build): migrate remaining commons-lang 2.x → commons-lang3 (Hadoop 3.3.6 fallout in
  apps-1.5 + datalayout); whole-repo `testClasses` green.
- `24dd99b` test(delta-harness): load the OpenHouse SQL extension in the harness SparkSession
  (recovers ~18 ddl.* ParseException cases).
- `2884455` Rung 9 — server HDFS client `hadoop-client 2.10.0 → 3.3.6` (Java-17-capable, RBF
  wire-compat), transitive fallout migrated to modern libs; build + boot + server tests green.
  `rung9-hdfs-java17-v3-readcliff.md`
- `3f685e7` docs(spark4): Rung 7 — server runtime Java 17 / metadata-writer Java-8 bytecode
  verified (already satisfied by the 1.11 architecture; `javap` proof major 52). `rung7-java17-runtime.md`
- `69d83d6` Rung 3 (THE GOAL) — v3 DSv2 deletion vectors proven on the Spark-4.0 REST lane. Isolated
  `deletionVectorTest` fork (`-Dcluster.iceberg.format-version=3`) + `DeletionVectorTestSpark4_0`
  (metadata-writer v3 proof + MOR-`DELETE`→puffin-deletion-vector proof); default-v2 `test` task
  untouched. Write-up `rung3-v3-deletion-vectors.md`.
- `a87befe` feat(spark-4.0): GRANT/REVOKE/SHOW GRANTS on the REST lane via direct HTTP to
  `/aclPolicies` (+ `GrantRevokeTestSpark4_0`, restored WapId GRANT, `grant-revoke-rest-lane.md`)
- `4a35709` docs(spark4): add REMAINING-WORK master checklist
- `77eb4e9` docs: mark InvalidMetadata CI-perf FIXED
- `9cceeab` docs: consolidate residuals — backlog all-green except GRANT deferral
- `923d39b` Restore openhouse.tableUri assertion
- `f42268d` Verify column policy-tag DDL on REST lane
- `d91cfdf` UNSET POLICY (REPLICATION) clear semantics
- `1f2d80a` Port OpenHouse SQL POLICY DDL extension to Spark-4.0
