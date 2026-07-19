# Rung 2 pitfalls — classpath & version conflicts (with fixes)

Co-hosting a **Spring Boot 2.7 (javax) server** and **Spark 4.0 (jakarta)** in one JVM, with a
**single unshaded Iceberg 1.10** spanning both, surfaced a chain of resolution conflicts. Each was
fixed by a version `force`/`exclude` in
`integrations/spark/spark-4.0/openhouse-spark-itest/build.gradle`, or by an infra config in the
harness. Facts only.

## P0 — publish drags in test compilation
`publishToMavenLocal ... -x test` still compiles test sources; a fork test
(`core/.../TestRewriteFileGroup.java`) fails to compile. Fix: also pass
`-x compileTestJava -x compileTestScala`. (Do NOT add `-x compileTestGroovy` — no such task; Gradle
8 errors on an unknown excluded task.)

## P1 — shaded runtime vs the REST controller (why UNSHADED)
`iceberg-spark-runtime-4.0` relocates jackson to `org.apache.iceberg.shaded.com.fasterxml.jackson`.
The server's `IcebergRestCatalogController.newRestObjectMapper` calls
`RESTSerializers.registerAll(com.fasterxml.jackson.databind.ObjectMapper)` with UNSHADED jackson, so
the shaded runtime's `RESTSerializers` overload does not exist → `NoSuchMethodError` at server boot.
A classloader picks one jar per `org.apache.iceberg.*` class name, so shaded (for Spark's data path)
and unshaded (for the server's REST serializers) cannot coexist split. Fix: use the **unshaded**
`iceberg-spark-4.0_2.13` + `iceberg-spark-extensions-4.0_2.13` (pull iceberg-core/data/parquet/orc/
arrow transitively) so ONE unshaded Iceberg 1.10 + ONE jackson + ONE parquet spans server and
client. Requires publishing iceberg-parquet/orc/arrow at `1.10.0-openhouse` too (spark publish only
did core/api/common/data). The rung-1 F1 exclusion in `print-cp.init.gradle` is now gated to the
spark-3.5 (shaded) lane only.

## P2 — javax↔jakarta validation
Spark 4.0 drags `jakarta.validation:jakarta.validation-api:3.0.2` (jakarta.* package), which Gradle
picks over OpenHouse's `2.0.2` (javax.* package). Hibernate 5.6 then can't find
`javax.validation.ValidatorFactory` → server boot fails. Fix: `force jakarta.validation-api:2.0.2`.

## P3 — old fork on the classpath
Server transitives still drag `com.linkedin.iceberg:*:1.2.0.6` (org.apache.iceberg.* v1.2 classes)
which shadow the v1.10 unshaded classes. Fix: exclude `com.linkedin.iceberg` `iceberg-core`/`-api`/
`-common`/`-bundled-guava`. KEEP `iceberg-aws:1.2.0.6` — its `org.apache.iceberg.aws.*` package
(e.g. `S3FileIO`, referenced at Spring boot) does not collide and has no 1.10 replacement published.

## P4 — jackson stack split
Server pulls `jackson-databind:2.13.4`; Spark 4.0 needs 2.18.2. Mixed databind 2.13 + core 2.19 +
module-scala 2.18 → `ExceptionInInitializerError` in `SparkThrowableHelper`/`ErrorClassesJsonReader`
(parsing Spark's error-conditions.json). Fix: `force` the whole jackson stack (databind/core/
annotations/module-scala_2.13/datatype-jsr310/datatype-jdk8/dataformat-yaml) to **2.18.2**.

## P5 — avro
Iceberg 1.10 needs avro **1.12.0** (`LogicalTypes.timestampNanos`, used by iceberg-avro
`TypeToSchema`); Spark 4.0 bundles avro 1.11.4 → `NoSuchMethodError` in `GenericDataFile` static
init. Fix: `force org.apache.avro:avro(+avro-mapred,avro-ipc):1.12.0`. Parquet already resolves
uniformly to 1.16.0 (iceberg 1.10's version) — no force needed.

## P6 — Spark 4.0 local executor remote class loading
On Spark 4.0 the local executor fetches codegen classes over the driver's netty RPC. The driver
advertised the box hostname (`192.0.2.2`, an RFC-5737 address in this container) while binding to
`127.0.0.1` → `RemoteClassLoaderError` / `Connection refused: /192.0.2.2:<port>` on every task.
`spark.driver.bindAddress=127.0.0.1` (already set) was enough on Spark 3.5; Spark 4.0 also needs
`spark.driver.host=127.0.0.1`. Fix: added that config in `OpenHouseMatrix.start()`.

## P7 — CTAS / stage-create → HTTP 501 (expected, tagged)
`IcebergRestCatalogController` rejects staged create (`stageCreate=true`, Spark CTAS/RTAS) with 501
by design. Plain CREATE + INSERT works; CTAS does not. Harness cases that use CTAS fail with this
root cause — expected, not a regression to chase.
