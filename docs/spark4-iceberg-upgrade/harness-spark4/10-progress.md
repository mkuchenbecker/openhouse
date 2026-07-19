# Rung 2 progress — built-vs-plan

## Gate result

| Stack | Result | Baseline (rung 1) | Delta |
|---|---|---|---|
| Spark 4.0 / Iceberg 1.10 / Scala 2.13 / JDK 17 / **REST** | **1637 passed / 28 skipped / 32 failed** (1697) | 1669 / 28 / 0 | **−32 green**, +32 failed, skips unchanged |

All 32 failures fall in three **expected REST-first categories** (below). No infra/classpath/port
regression: every core DML happy path (delete/update/merge/insert/read, CoW+MoR, parquet/orc/avro,
partitioned/unpartitioned, WAP, branches, time-travel, metadata tables, maintenance procedures,
streaming) is green. The 28 skips are identical to the baseline (same tagged known-bugs).

## 1. Spark-4.0 fork runtime published (`1.10.0-openhouse`)

`gradle -DsparkVersions=4.0 -DforceVersion=1.10.0-openhouse :iceberg-spark:iceberg-spark-4.0_2.13:...
:...-extensions-4.0_2.13:... :...-runtime-4.0_2.13:publishToMavenLocal -x test -x compileTestJava
-x compileTestScala` (JDK 17, jvm11 init script). Also published unshaded
**iceberg-parquet / iceberg-orc / iceberg-arrow** at `1.10.0-openhouse` (needed by the unshaded
client — the spark publish only did core/api/common/data). Artifacts under
`~/.m2/repository/org/apache/iceberg/iceberg-spark-{,extensions-,runtime-}4.0_2.13/1.10.0-openhouse/`.

## 2. Spark-4.0 classpath — new gradle module

`integrations/spark/spark-4.0/openhouse-spark-itest` (name `openhouse-spark-4.0-itest`), a
classpath-only module (no sources). Depends on: the embedded OpenHouse server
(`tables-test-fixtures-iceberg-1.5_2.12` → `services:tables` incl. `IcebergRestCatalogController`),
the OpenHouse java client (for the one exception type the harness imports), and the **UNSHADED**
stock `iceberg-spark-4.0_2.13` + `iceberg-spark-extensions-4.0_2.13` + `spark-sql_2.13:4.0.0`.
`print-cp.init.gradle` gained a `-DharnessItestPath` override; its F1 unshaded-exclusion is now
gated to the spark-3.5 (shaded) lane only. `GET /iceberg/v1/config` responds from the embedded
server — proven by 1637 cases whose `RESTCatalog.initialize` (config fetch) + table
load/create/commit all succeed through the controller.

## 3. Scala 2.12 → 2.13 port scope

**Zero source changes were needed for the language cross-compile** — `OpenHouseMatrix.scala`
compiled clean on scalac **2.13.16** against the Spark-4.0 classpath on the first attempt (no
`JavaConverters`, `.to(coll)`, `Seq`-variance, or Spark-3.5→4.0 API breaks in the surface the
harness touches). The only harness-source edits are behavioral/config, not 2.13 syntax:
- `wireCatalog`: `catalog-impl` → `org.apache.iceberg.rest.RESTCatalog`, `uri` → `<uri>/iceberg`,
  `auth-token`/`cluster` dropped, `token` added.
- `spark.sql.extensions`: dropped the custom `OpenhouseSparkSessionExtensions` (not shipped
  REST-first); Iceberg extension only.
- Added `spark.driver.host=127.0.0.1` (Spark-4.0 local executor remote class loading — see pitfalls).
`run-openhouse.sh`: scalac/`SCALA_LIB` → 2.13.16, classpath target → the spark-4.0 module,
`--add-opens` widened to Spark 4.0's documented set.

## 4. Failure enumeration (all 32)

### A. Custom OpenHouse policy/ACL/column-tag DDL — `ParseException` (12) — EXPECTED (Path A / D2)
The 8 OpenHouse policy-DDL SQL extensions are **not** stock Iceberg SQL; REST-first intentionally
ships **no** `OpenhouseSparkSessionExtensions` parser, so these statements fail to parse.
`ddl.acl.grantShared`, `ddl.acl.grantUnshared`, `ddl.colTag`, `ddl.policy.history`,
`ddl.policy.neg.historyMaxAge`, `ddl.policy.neg.historyVersions`, `ddl.policy.replication`,
`ddl.policy.retention`, `ddl.policy.sharing`, `ddl.rtas.replicationConflict`,
`hazard.rtas.wipesColumnTags`, `interact.rtas.props.reservedPlane`.

### B. CTAS / RTAS stage-create → HTTP 501 (2) — EXPECTED (known REST endpoint limit)
`ddl.ctas`, `interact.rtas.dropsColumn` — `IcebergRestCatalogController` rejects
`stageCreate=true` with 501 by design (plain CREATE+INSERT works, CTAS/RTAS does not).

### C. REST-vs-native behavior / validation divergence (18) — REST-first fidelity findings
The stock `RESTCatalog` + `/iceberg` controller create/commit path does **not** reproduce the
OpenHouse native server+`OpenHouseCatalog` validation that rung-1 exercised. Mostly negatives that
the native path rejected (mapped to `BadRequestException`) but REST now allows (nothing thrown), or
a different error type / lock-not-enforced. These are Spike-B REST write-path fidelity gaps, not
port regressions; server-side controller hardening is out of scope for this gate.
- Constraint negatives no longer rejected (`expected BadRequestException, nothing thrown`):
  `ddl.neg.dropColumn`, `ddl.props.reservedOpenhouse`, `ddl.rtas.disabled`,
  `ddl.repl.tableTypeImmutable`, `interact.ddl.dropColAfterData`, `interact.flags.wapReplaceAtCreate`.
- Partition-constraint negatives no longer rejected (`expected Exception, nothing thrown`):
  `partition.dateDay.rejected`, `partition.evolutionAdd.rejected`, `partition.evolutionDrop.rejected`,
  `surface.schema.nestedDropField`, `surface.msg.readabilityGuard`.
- OpenHouse table-LOCK not enforced over REST (`@ embedded`): `control.lock.enforcement`,
  `interact.rtas.onLockedTable`, `hazard.lock.starvesMaintenance`.
- Different error / semantics: `ddl.ns.createRejected` (expected UnsupportedOperationException, got
  NamespaceAlreadyExistsException — REST namespace endpoint is optimistic), `ddl.renameTable.conflict`
  (rename-conflict not rejected), `hazard.rename.consumers` (post-rename old name TABLE_NOT_FOUND),
  `surface.conc.appendAppend` (concurrent append row-count differs — REST optimistic-commit/retry).

## 5. Diff vs rung-1 baseline (1669/28/0)
- Net green: **−32** (1669 → 1637). Skips unchanged (28, identical set). No newly-passing cases
  (baseline had 0 failures).
- New failures: the 32 above. 14 are structurally expected under Path A REST-first (12 custom-SQL +
  2 CTAS-501); 18 are REST-vs-native write/validation-path divergences (recorded findings).
