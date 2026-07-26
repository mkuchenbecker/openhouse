# Spark-4.0 / Iceberg-1.11 upgrade — REMAINING WORK master checklist

Durable, review-oriented tracker for the remaining rungs of the modernization spike, worked
**sequentially**. Each item links to its own audit-grade write-up (problem → root cause → fix →
verification) under `docs/spark4-iceberg-upgrade/`. This file is the index + live status.

Branch: `1.11` (OpenHouse) / fork `1.11.x` (`mkuchenbecker/iceberg`). Real CI gate: `Branch 1.11 CI`
(push event). PR-path `Pull Request Validations` is red-by-design (fork not provisioned on PRs).

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

## 2. Rung 3 — Iceberg 1.11 + v3 DSv2 deletion vectors (THE GOAL)
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
- [ ] Flip `cluster.iceberg.format-version` to 3 for the verification path (embedded test server /
      configurable) and confirm the 1.11 fork metadata-writer emits valid v3 metadata
- [ ] Verify DSv2 deletion vectors: Spark-4.0 MOR `DELETE` on a v3 table writes a puffin deletion
      vector (not positional delete files), and the row is gone on read-back
- [ ] e2e test on the Spark-4.0 REST lane; write-up `rung3-v3-deletion-vectors.md`

## 3. Rung 7 — Server/metadata-writer runtime → Java 17 (keep Java-8 bytecode where consumed)
- [ ] Move the server RUNTIME to Java 17; keep `-source/-target 8` (or `release 8`) where the
      metadata-writer bytecode is consumed by Java-8 readers
- [ ] Build + boot + harness-gate; write-up `rung7-java17-runtime.md`

## 4. Rung 8 — FINAL VALIDATION: delta-harness full matrix on Spark 4.0 / Iceberg 1.11
- [ ] Run the delta-harness full matrix; capture results; write-up `rung8-final-validation.md`

## 5. Rung 9 — Spike: HDFS client on Java 17 runtime (RBF wire-compat) + v3 read cliff
- [ ] Assess HDFS client on Java 17 (RBF wire-compat) and the v3 read cliff; write-up
      `rung9-hdfs-java17-v3-readcliff.md`

---

## Change log (commits, newest first)
- (this commit) feat(spark-4.0): GRANT/REVOKE/SHOW GRANTS on the REST lane via direct HTTP to
  `/aclPolicies` (+ `GrantRevokeTestSpark4_0`, restored WapId GRANT, `grant-revoke-rest-lane.md`)
- `77eb4e9` docs: mark InvalidMetadata CI-perf FIXED
- `9cceeab` docs: consolidate residuals — backlog all-green except GRANT deferral
- `923d39b` Restore openhouse.tableUri assertion
- `f42268d` Verify column policy-tag DDL on REST lane
- `d91cfdf` UNSET POLICY (REPLICATION) clear semantics
- `1f2d80a` Port OpenHouse SQL POLICY DDL extension to Spark-4.0
