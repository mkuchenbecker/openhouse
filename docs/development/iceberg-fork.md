# Opt-in build against an Iceberg fork (`-PicebergFork`)

The default build compiles and tests the Iceberg 1.5 variant (Spark 3.5, Scala 2.12) against
LinkedIn's `com.linkedin.iceberg` 1.5.2 releases from Maven Central. `-PicebergFork` builds that
same variant against a newer Iceberg built from source instead, so work on that line can be proven
here without touching the default build. Without the property nothing changes.

## What is pinned

[`scripts/iceberg-fork/iceberg-fork.properties`](../../scripts/iceberg-fork/iceberg-fork.properties)
holds the fork URL, the exact commit, and the runtime versions that commit needs (Avro, ORC,
Caffeine). The published version is `<baseVersion>-ohfork.<12-char commit>`, which cannot collide
with a `com.linkedin.iceberg` release (those are four numeric parts, e.g. `1.5.2.24`). Nothing is
committed as a binary and nothing is published remotely; bump the commit to move the line.

## Use it

```bash
export JAVA_HOME=/path/to/jdk17        # the fork's Iceberg is Java 17 bytecode
scripts/iceberg-fork/publish-local.sh  # once per pinned commit; FORCE=1 republishes
./gradlew -PicebergFork :services:tables:test
./gradlew -PicebergFork :integrations:spark:spark-3.5:openhouse-spark-3.5-itest:test
```

`publish-local.sh` fetches the pinned commit into `build/iceberg-fork/src`, then runs the fork's
`publishToMavenLocal` for the modules OpenHouse consumes with `-Dmaven.repo.local` pointing at
`build/iceberg-fork/repo` (never `~/.m2`). A second Iceberg build publishes the shaded
`iceberg-spark-runtime-4.2_2.13` (one Iceberg build has one Scala version); it has its own marker
file, so a repository published before it existed gains only that jar. It needs the fork's `-PpublishGroup`/`-PpublishVersion`
support. A cold run builds Iceberg's core and Spark 3.5 runtime and takes several minutes; once the
version is published it is a no-op. `./gradlew clean` deletes `build/`, so publish again after it.

## What the switch changes

All in [`gradle/iceberg-fork.gradle`](../../gradle/iceberg-fork.gradle) or guarded by the same
property:

- `iceberg_1_5_version` becomes the fork version for every module on the 1.5 line.
- A repository limited to `com.linkedin.iceberg` at exactly that version, served only from
  `build/iceberg-fork/repo`.
- Every module compiles to Java 17 (`openhouse.java-minimal-conventions`).
- The root ORC/Avro forces are replaced by the pinned versions, and Caffeine is pinned.
- `iceberg-parquet`/`iceberg-orc` are added at runtime on the 1.5 line and kept off the Spark 3.5
  test classpaths, where the shaded `iceberg-spark-runtime` already carries them.

The Iceberg 1.2 / Spark 3.1 projects are left out of the build under the switch (decision D15,
`settings.gradle`): `apps/spark`, `integrations/java/iceberg-1.2/*`, `integrations/spark/spark-3.1/*`
and `tables-test-fixtures/tables-test-fixtures-iceberg-1.2`. The single-copy server modules compile
against the fork there, so those projects could only be a hybrid of 1.2 and 1.12 artifacts. The 1.5
projects that reuse their source directories name the directories instead, `services:tables` tests
use the 1.5 fixtures (built from the same sources), and `libs:datalayout`, which the Spark 3.5 apps
share, is built on the Spark 3.5 / fork line. Tasks that need a left-out project, such as
`dockerPrereqs`, do not work under the switch.

## Behavior that differs by Iceberg line

The client detects its line with `com.linkedin.openhouse.javaclient.IcebergLine`: it asks the
Iceberg on the classpath to build format v3 metadata in memory, which LinkedIn's 1.2 and 1.5.2
releases refuse and the 1.12 line accepts. Tests that expect different results per line use the
same class.

- Column defaults (decisions D5, D11a): on the 1.12 line the client applies no
  `openhouse.read-bridge.column-default.*` overlay and the Spark catalogs do not declare the
  `column-defaults` capability, so a table with `required-capability.column-defaults=true` is
  refused with 422 `CLIENT_CAPABILITY_MISSING`. Iceberg 1.12 itself refuses a non-null
  `initial-default` below format v3; tests that need one build a v3 table on that line.
- `REPLACE TABLE ... AS SELECT` that drops a former partition source column succeeds on 1.12 and
  fails Iceberg validation on 1.5.2 (decision D14a, apache/iceberg#11868).
- `CREATE NAMESPACE` and `DROP NAMESPACE` are unsupported on both lines, with the message "Checking
  if database exists is not supported" on 1.12 instead of "Describing database is not supported"
  (decision D14b, apache/iceberg#14507).

On the REST-shaped path (`catalog-impl` `OpenHouseRestCatalog`), Iceberg 1.12's client calls only
the endpoints a server advertises, and with none advertised assumes a default set without views. So
on the 1.12 line the in-process adapter's config response lists every route it dispatches (all but
config itself, derived from its route table); on the legacy line it lists none, as before, because
Iceberg 1.5.2 has no endpoints in its config response. `OpenHouseRestAdapterEndpointsTest` keeps the
list and the routes equal on both lines.

The jobs client (`TablesClientFactory`, apps) declares `column-defaults` by the same rule as the
Spark catalogs. The scheduler's own jar carries neither `IcebergLine` nor Iceberg, so there the line
is not detectable and it declares the capability as before, with a warning.

## CI

`post-merge-build.yml` has a second job, `iceberg-fork-build`, that runs after each merge to
`irc-integration` and by hand (`workflow_dispatch`): `publish-local.sh` (the published
`build/iceberg-fork/repo` is cached, keyed on the pinned commit and the script), then
`-PicebergFork assemble testClasses`, then the opt-in suites: `:services:tables:test`,
`:iceberg:openhouse:internalcatalog:test`, `:iceberg:openhouse:htscatalog:test`, the 1.5 Java itest,
the Spark 3.5 itest (`catalogTest`, `statementTest`, `test`), the Spark 4.2 SQL extensions' tests and
the Spark 4.2 itest (`test`, `extensionsTest`). It does not block or wait for the default build job.

## Spark 4.2 over the IRC read routes

`:integrations:spark:spark-4.2:openhouse-spark-4.2-itest` exists only under the switch
(`settings.gradle`). Its tests run Spark 4.2 (the pin file's `spark42Version`) with the fork's
`iceberg-spark-runtime-4.2_2.13`, Iceberg's own `SparkCatalog` and `RESTCatalog`
(`type=rest`, `uri=<service>/iceberg`, `token=<bearer>`) and no OpenHouse catalog or client class on
the classpath, against the tables service's IRC routes:

```bash
./gradlew -PicebergFork :integrations:spark:spark-4.2:openhouse-spark-4.2-itest:test
```

The service runs in its own JVM, which the test starts on a free port (`IrcReadTestServiceProcess`)
and stops when the test JVM exits: the service exactly as the Spark 3.5 tests embed it
(`SpringH2TestApplication`, in-memory H2), with the dummy token interceptor and an authorization
handler that denies one principal. It cannot share the Spark 4.2 JVM, which needs jackson-databind
2.21 where the service runs on 2.13. The service JVM seeds the tables through the existing OpenHouse
Java client over the native API (Iceberg's generic writers write the Parquet and ORC files, the
client commits each append) and records what that client shows, for comparison. Its log and work
directory are under `build/openhouse-spark-4.2-itest/tmp/test/service-*`.

The service serves the IRC write routes; `SparkIrcWriteTest` pins that `CREATE TABLE` and `INSERT`
succeed from Spark 4.2 and the rows read back, on tables it creates and drops.

## OpenHouse SQL extensions on Spark 4.2

`:integrations:spark:spark-4.2:openhouse-spark-4.2-runtime_2.13` (only under the switch) is the
Spark 3.5 runtime's SQL extensions ported to Spark 4.2 / Scala 2.13: the same statements (SET and
UNSET POLICY, MODIFY COLUMN ... SET TAG, GRANT, REVOKE, SHOW GRANTS, UNLOCK), configured the same
way next to Iceberg's:

```
spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions,com.linkedin.openhouse.spark.extensions.OpenhouseSparkSessionExtensions
```

It has no OpenHouse catalog class and does not depend on the OpenHouse Java client, the generated
client or Spring: it finds OpenHouse features through `:integrations:java:openhouse-catalog-capabilities`
and Iceberg's own `SparkCatalog` and `RESTCatalog`. Its `uber` jar holds its classes and the
capabilities module's; Spark and `iceberg-spark-runtime-4.2_2.13` are the engine's own.

```bash
./gradlew -PicebergFork :integrations:spark:spark-4.2:openhouse-spark-4.2-runtime_2.13:test
./gradlew -PicebergFork :integrations:spark:spark-4.2:openhouse-spark-4.2-itest:extensionsTest
```

Over a plain `RESTCatalog` every OpenHouse statement works. The policy statements ride the IRC
commit (the server advertises `policies` and applies the patch). GRANT, REVOKE, SHOW GRANTS and
UNLOCK go through the REST capability client in the capabilities module (`capabilities.rest`), which
calls the OpenHouse routes under the IRC base with the catalog's own URI, prefix, `header.*` values
and authentication, and gives the outcomes Spark 3.5 gives over `RESTCatalog`. The client's
transport is the one per-line part: the module compiles `src/iceberg-1.5/java` by default and
`src/iceberg-1.12/java` (Iceberg's `AuthManager`) under `-PicebergFork`. `extensionsTest` runs in its
own JVM and service JVM, because Spark fixes a session's extensions when the session is built.
