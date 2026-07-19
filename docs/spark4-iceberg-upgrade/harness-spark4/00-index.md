# Rung 2 harness — Spark 4.0 / Scala 2.13 / JDK 17 (REST-first)

Records the build and run of the delta-harness on the **Spark 4.0 / Scala 2.13 / Java 17** stack
against a **stock Iceberg `RESTCatalog`** talking to the embedded OpenHouse server's
`/iceberg/v1/*` endpoint (`IcebergRestCatalogController`). This is the rung-2 gate; the frozen
rung-1 baseline it diffs against is **1669 passed / 28 skipped / 0 failed**.

| File | Covers |
|---|---|
| `00-index.md` (this) | What was built, how to run it |
| `10-progress.md` | Built-vs-plan ledger: artifacts published, module added, port scope, matrix result |
| `20-pitfalls.md` | Every classpath/version conflict hit and its fix (javax↔jakarta, jackson, avro, driver host, CTAS-501) |

## How to run

```
cd integrations/spark/delta-harness
JAVA17_HOME=/usr/lib/jvm/java-17-openjdk-amd64 GRADLE_BIN=gradle FORCE_CP=1 bash run-openhouse.sh
```

`run-openhouse.sh` now defaults to Scala **2.13.16** scalac and resolves its classpath from the new
`:integrations:spark:spark-4.0:openhouse-spark-4.0-itest` module (override with
`HARNESS_ITEST_PATH` / `SCALA_VER`). No args runs the full matrix; args are AND-substring case
filters (e.g. `bash run-openhouse.sh delete.byInList`).

## Stack (rung 2)

- Client: **stock** `org.apache.iceberg:iceberg-spark-4.0_2.13` + `iceberg-spark-extensions-4.0_2.13`
  (`1.10.0-openhouse`, UNSHADED — see `20-pitfalls.md` for why not the shaded runtime), stock
  `org.apache.iceberg.rest.RESTCatalog`, no custom OpenHouse Spark catalog/extension.
- Server: embedded `OpenHouseLocalServer` (Spring Boot 2.7, Java-8 bytecode) booting the full tables
  context incl. `IcebergRestCatalogController` at `/iceberg/v1/*`.
- Catalog config (`OpenHouseMatrix.scala` `wireCatalog`): `catalog-impl=org.apache.iceberg.rest.RESTCatalog`,
  `uri=<serverBaseUrl>/iceberg`, `token=<dummy.token>`.
