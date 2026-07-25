# Legacy Spark-3.5 / Iceberg-1.5 tests gated on the `1.11` branch

These test suites are disabled on the `1.11` integration branch because they cannot run once the
embedded OpenHouse server moves to Iceberg 1.11: they load the legacy Spark-3.5 / Iceberg-1.5 client
(or run 1.5 data-plane operations) in the SAME JVM as the 1.11 server, so two `org.apache.iceberg.*`
class-sets collide in one classloader (`NoSuchMethodError` / `NoClassDefFoundError`). In production
the Spark job and the server are separate processes, so this collision is a test-harness artifact
only — not a product regression.

Nothing these suites cover is on the Spark-4.0 REST-first 1.11 lane, whose client is the stock
`org.apache.iceberg.rest.RESTCatalog` (not the custom `OpenHouseCatalog`) and which ships no custom
OpenHouse SQL extension.

## What is gated

| Module | Scope gated | Measured on 1.11 | Replacement coverage on the 1.11 lane |
|---|---|---|---|
| `integrations/spark/spark-3.5/openhouse-spark-itest` | whole module (`catalogTest`, `statementTest`, `test`) | catalogTest 58/58 fail · statementTest 60/60 fail · test 49/90 fail | Spark-4.0 REST port under `integrations/spark/spark-4.0/openhouse-spark-itest` + the delta-harness |
| `apps/spark-3.5` (`openhouse-spark-apps-1.5_2.12`) | 6 data-plane e2e cases (below) | 6/226 fail | none yet — no Spark-4.0 apps module exists |

### Gated apps-1.5 cases (compaction / merge-on-read delete-file, run 1.5 Iceberg data-plane in-JVM)
- `com.linkedin.openhouse.jobs.spark.OperationsTest.testDataCompactionPartialProgressNonPartitionedTable`
- `com.linkedin.openhouse.jobs.spark.OperationsTest.testDataCompactionPartialProgressPartitionedTable`
- `com.linkedin.openhouse.catalog.e2e.SparkMoRFunctionalTest.testBudgetedRewriteUsesDataLengthForTaskGrouping`
- `com.linkedin.openhouse.catalog.e2e.SparkMoRFunctionalTest.testCompactionCanRemoveEqualityDeleteFiles`
- `com.linkedin.openhouse.catalog.e2e.SparkMoRFunctionalTest.testCompactionCanRemovePositionDeleteFiles`
- `com.linkedin.openhouse.catalog.e2e.SparkMoRFunctionalTest.testDeleteFilesCanBeCreated`

The other 220 apps-1.5 tests still run and pass — only the 6 that drive real Iceberg data-plane
rewrites (which reach the colliding `RewriteFileGroup` / `GenericFormatModels` / delete-loader
classes) are gated.

## How to restore (the "both lanes" future work)
This is a **spike** cost-finding, not a permanent deletion. Two independent follow-ups would restore
coverage:
1. **spark-3.5 lane on Iceberg 1.10 (decision #5 "both lanes")** — give the Spark-3.5 itest its own
   Iceberg-1.10 embedded-server build so client and server match; then re-enable this module against
   that server. Requires a second server artifact build (not done).
2. **Spark-4.0 maintenance-apps module** — port the OPTIMIZE/VACUUM/compaction apps to Spark 4.0
   (decision #2; the Spark-4.0 fork changes exist but are not yet wired into an apps module), which
   would give the 6 gated apps cases a single-version 1.11 home.

The gates are plain `*.enabled = false` / `excludeTestsMatching` in the two `build.gradle` files —
trivially reversible once either follow-up lands.
