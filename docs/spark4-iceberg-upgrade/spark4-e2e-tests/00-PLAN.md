# Spark 4.0 / Iceberg 1.11 e2e test port — plan & checklist

## Why
The legacy Spark-3.5 / Iceberg-1.5 in-JVM e2e suite (`openhouse-spark-3.5-itest:catalogTest` = 58
tests + 6 `apps-1.5` e2e tests) cannot run against the new Iceberg-1.11 server: a 1.5 Iceberg client
and a 1.11 Iceberg server share the `org.apache.iceberg.*` package and collide in one classloader
(`NoSuchMethodError` at the first `spark.sql("CREATE TABLE …")`). In production these are separate
processes, so the collision is a test-harness artifact only.

Decision (user): **do not** force the 1.5 client to work against a 1.11 server. Instead, add a
**Spark-4.0 / Iceberg-1.11 / REST-first** version of these e2e tests, where client and server are a
single Iceberg version (1.11.0-openhouse) so they actually pass; gate the superseded legacy
in-JVM e2e tests off the `1.11` branch.

## Where
`integrations/spark/spark-4.0/openhouse-spark-itest` — previously a classpath-only module for the
delta-harness; now also owns e2e test sources under `src/test/java`.

## Harness (DONE — proven foundation)
- `com.linkedin.openhouse.tablestest.rest.OpenHouseRestSparkITest` — Spark-4.0 base class. Boots the
  singleton `OpenHouseLocalServer` and wires Spark to the STOCK `org.apache.iceberg.rest.RESTCatalog`
  at `<server>/iceberg` with bearer `token` (mirrors `harness.OpenHouseEnv.wireCatalog`). Only the
  stock `IcebergSparkSessionExtensions` — no custom OpenHouse SQL extension on this lane.
- `build.gradle` gained JUnit 5 + `test { useJUnitPlatform(); jvmArgs(<Spark-4.0 add-opens>) }`.
- First ported class: `CatalogOperationTestSpark4_0` (pure-SQL subset).

## Port list (source → target, all in package `com.linkedin.openhouse.spark.catalogtest`)
Sources live in `integrations/spark/spark-3.1/openhouse-spark-itest` (shared) and
`integrations/spark/spark-3.5/openhouse-spark-itest`.

- [ ] `CatalogOperationTest` → `CatalogOperationTestSpark4_0` (pure-SQL DONE; port the
      `getOpenHouseCatalog(...)` Java-API cases against the stock RESTCatalog; the `Policies`-model /
      `WebClientResponseWithMessageException` cases become stock Iceberg exceptions or move to a
      REST-lane behavioral note).
- [ ] `BranchTestSpark3_5` → `BranchTestSpark4_0` (branch/WAP/cherry-pick/fast-forward — mostly Spark
      SQL + Iceberg procedures; the WAP/branch procedures are stock Iceberg, should port cleanly).
- [ ] `WapIdTest` → `WapIdTestSpark4_0`.
- [ ] `CTASNonNullTest` / `CTASNonNullTestSpark3_5` → `CTASNonNullTestSpark4_0`.
- [ ] `RTASTest` → `RTASTestSpark4_0`.
- [ ] `PartitionTest` / `PartitionTestSpark3_5` → `PartitionTestSpark4_0`.
- [ ] `InvalidMetadataTest` → `InvalidMetadataTestSpark4_0`.
- [ ] `e2e/BranchJavaTest` → `BranchJavaTestSpark4_0` (Java-API branch ops via RESTCatalog).
- [ ] `e2e/SparkMultiSchemaEvolutionTest` → `SparkMultiSchemaEvolutionTestSpark4_0` (uses
      `TableMetadata.buildFrom(...).addSchema(schema)` — already on the 1.11 1-arg form).

## Porting rules
1. Extend `OpenHouseRestSparkITest`, not `OpenHouseSparkITest`.
2. Pure Spark-SQL cases port verbatim (table names, asserts unchanged).
3. `getOpenHouseCatalog(spark)` now returns a stock `RESTCatalog` — Iceberg Java-API cases
   (`createTable`/`loadTable`/`newAppend`/`TableOperations`) work through it; adjust only where a
   method or exception type differs 1.5→1.11.
4. Custom OpenHouse SQL (SET POLICY / GRANT / column-tags / `.policies`) is NOT available on the REST
   lane (decision #4). Drop those assertions or express the intent via table properties. Note each
   omission in `10-RESIDUALS.md`.
5. Exception expectations: `WebClientResponseWithMessageException` → the mapped stock Iceberg
   exception (`BadRequestException`, `NoSuchTableException`, `ForbiddenException`, …) surfaced by the
   REST error envelope.
6. Keep each test's intent identical; only the catalog wiring and exception/DDL surface change.

## Verify
`export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 LANG=C.UTF-8`
`./gradlew :integrations:spark:spark-4.0:openhouse-spark-itest:test -Dfile.encoding=UTF-8`
Target: all ported tests green (client=server=1.11, no in-JVM collision).

## Legacy gating (separate, for CI-green on `1.11`)
- `openhouse-spark-3.5-itest`: `catalogTest` (embedded-server e2e) — superseded by the port above;
  gate off the `1.11` branch. Confirm `statementTest` (custom SQL) / `test` (mock-based) status and
  gate only what the 1.11 server breaks.
- `apps-1.5`: gate the 6 failing embedded-server e2e tests (no Spark-4.0 apps module exists yet, so
  no 4.0 replacement — document as a residual under decision #2/#5).
