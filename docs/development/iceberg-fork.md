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
`build/iceberg-fork/repo` (never `~/.m2`). It needs the fork's `-PpublishGroup`/`-PpublishVersion`
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

The Iceberg 1.2 / Spark 3.1 modules stay in the build (the 1.5 modules reuse their sources) and keep
resolving LinkedIn's 1.2 artifacts, but compile to Java 17 against server modules built on the
fork. Their results under the switch say nothing about either line; do not run them with it.

Some tests fail under the switch because Iceberg itself behaves differently: v2 tables reject
non-null column defaults, RTAS succeeds after a former partition source column is dropped, and
`CREATE NAMESPACE` fails with the catalog's `namespaceExists` message. Do not skip them.
