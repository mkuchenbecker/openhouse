# Rung 3 pitfalls

## P1 — OpenHouse forces `format-version` to the cluster default (this is how v3 is enabled)

`OpenHouseInternalRepositoryImpl.computePropsForTableCreation` sets `TableProperties.FORMAT_VERSION`
to `clusterProperties.getClusterIcebergFormatVersion()` (default **2**, `ClusterProperties`
`@Value("${cluster.iceberg.format-version:2}")`) on every table create — a client `TBLPROPERTIES
('format-version'='3')` is overridden. So a v3 table can only be authored by raising the cluster
default. The embedded server reads Spring config from JVM system properties, so launching the probe
JVM with **`-Dcluster.iceberg.format-version=3`** makes the server author v3 metadata. This is the
mechanism the DV probe/battery uses (`FV=3` in `run-dvprobe.sh`); it is server-side, exactly the
"direct-metadata-write path" the goal calls for.

## P2 — Iceberg 1.11 Java floor = 17: dependency variant resolution refuses jvm-17 jars

1.11's Gradle module metadata declares `org.gradle.jvm.version=17`. The OpenHouse server modules
(`:iceberg:azure`, `:iceberg:openhouse:htscatalog/internalcatalog`, `services:*`) target Java 8
bytecode and request a **jvm-11-compatible** variant on their compile/runtime classpaths, so Gradle
refuses the 1.11 jars: *"looking for a library compatible with JVM runtime version 11, but ... is
only compatible with JVM runtime version 17 or newer."* (rung-2/1.10 declared jvm.version=11 and
matched, so no relaxation was needed then.)

**Fix (centralized, the intended place):** raise the target-JVM resolution attribute from 11 → 17 in
`openhouse.iceberg-conventions-1.5.2.gradle` and `openhouse.java-minimal-conventions.gradle` (both
already relax this attribute; rung-2 set 11). A 17 request still accepts jvm-8/11 libs, so mixed
lanes (spark-runtime-3.5 1.10, com.linkedin.iceberg 1.2) still resolve. Bytecode target stays 8.

**Dead ends tried first (recorded so they aren't repeated):**
- Forcing the attribute in `print-cp.init.gradle` via `allprojects { configurations.configureEach {
  if (canBeResolved) attributes 17 } }` — touched legacy `default`/`archives` configs (resolvable
  AND consumable) and created **variant ambiguity** on spotless-registered variants of
  `:tables-test-fixtures` (`cannot choose between spotless… variants`).
- Narrowing to `name endsWith 'ompileClasspath'/'untimeClasspath'` fixed the ambiguity but the init
  `allprojects` action runs **before** each module's own build script, so the convention plugin
  re-pinned the attribute to 11 afterwards (still failed at 11). `afterEvaluate` made it win, but at
  that point the clean fix is simply to change the convention plugins themselves — which is what was
  done. The init-script override was reverted to a comment.

## P3 — global `iceberg_1_10_version` bump over-reaches into the spark-3.5 lane

First attempt bumped `iceberg_1_10_version` itself to `1.11.0-openhouse`. That repointed
`:integrations:java:iceberg-1.5:openhouse-java-iceberg-1.5-runtime` (transitively on the itest
runtime classpath) at `iceberg-spark-runtime-3.5_2.12:1.11.0-openhouse`, which was never published
(spark-3.5 fork patches were not replayed onto 1.11) → *"Could not find …-3.5_2.12:1.11.0-openhouse"*.
**Fix:** keep `iceberg_1_10_version = 1.10.0-openhouse`, add a separate `iceberg_1_11_version`, and
switch only the server-core conventions (1.5.2) to it. The java-1.5 runtime keeps its spark-3.5
compileOnly at 1.10 (published); on the merged itest runtime classpath the two iceberg-core versions
dedupe to 1.11.

## P4 — avro pin must track the Iceberg lane

The spark-4.0 itest shares one avro across server+client (unshaded). Iceberg 1.11 declares avro
**1.12.1** (1.10 was 1.12.0). The `resolutionStrategy.force 'org.apache.avro:avro:…'` was bumped
1.12.0 → 1.12.1 so the shared classpath is not downgraded below what iceberg-avro needs.

## Full 1.11 matrix FAIL set (32 — identical to rung-2)

ParseException (12, custom policy/ACL/colTag SQL, no parser REST-first): `ddl.acl.grantShared`,
`ddl.acl.grantUnshared`, `ddl.colTag`, `ddl.policy.history`, `ddl.policy.neg.historyMaxAge`,
`ddl.policy.neg.historyVersions`, `ddl.policy.replication`, `ddl.policy.retention`,
`ddl.policy.sharing`, `ddl.rtas.replicationConflict`, `hazard.rtas.wipesColumnTags`,
`interact.rtas.props.reservedPlane`.
CTAS/RTAS stage-create → 501 (2): `ddl.ctas`, `interact.rtas.dropsColumn`.
REST-vs-native validation divergence (18): `ddl.neg.dropColumn`, `ddl.props.reservedOpenhouse`,
`ddl.rtas.disabled`, `ddl.repl.tableTypeImmutable`, `interact.ddl.dropColAfterData`,
`interact.flags.wapReplaceAtCreate`, `partition.dateDay.rejected`, `partition.evolutionAdd.rejected`,
`partition.evolutionDrop.rejected`, `surface.schema.nestedDropField`, `surface.msg.readabilityGuard`,
`control.lock.enforcement`, `interact.rtas.onLockedTable`, `hazard.lock.starvesMaintenance`,
`ddl.ns.createRejected`, `ddl.renameTable.conflict`, `hazard.rename.consumers`,
`surface.conc.appendAppend`.
