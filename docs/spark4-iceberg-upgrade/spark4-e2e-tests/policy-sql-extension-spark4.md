# Feature: OpenHouse SQL POLICY DDL on the Spark-4.0 / Iceberg-1.11 REST lane

Status: IMPLEMENTED for `SET POLICY` (retention / replication / sharing / history). `UNSET POLICY`
parses + lowers but is blocked on a server gap. Column-tags parse but are not executable on the REST
lane (no server tag endpoint). Verified by GREEN targeted test runs (JDK 17), listed at the bottom.

> **UPDATE (supersedes the GRANT notes below):** `GRANT` / `REVOKE` / `SHOW GRANTS` are now
> IMPLEMENTED and verified on the REST lane — the execs call the existing server ACL endpoint
> (`/v1/databases/.../aclPolicies`) directly over HTTP, so no `/iceberg` ACL endpoint was needed. The
> "parse only / stays dropped" statements in this file are historical; see
> **`grant-revoke-rest-lane.md`** for the implemented design and `GrantRevokeTestSpark4_0`.

This is the client-side companion to the server foundation in `policy-rest-lane.md` (commit da20ad4):
the server already accepts the `updated.openhouse.policy` table property and folds it into the
reserved `policies` property. This change delivers the Spark-4.0 / Scala-2.13 port of the OpenHouse
Spark SQL extension so the `ALTER TABLE ... SET POLICY (...)` DDL parses and emits exactly that
property — no client/server contract change, the DDL maps onto the path that already works.

---

## New module

`integrations/spark/spark-4.0/openhouse-spark-runtime`
→ Gradle project `:integrations:spark:spark-4.0:openhouse-spark-runtime`, renamed in
`settings.gradle` to **`openhouse-spark-4.0-runtime_2.13`** (mirrors the `_2.12` naming of the 3.x
runtimes). It is a `scala` + `java-library` module (Scala 2.13.16, Spark 4.0.0, Iceberg
1.11.0-openhouse, Java 17). It deliberately does NOT use `openhouse.java-minimal-conventions` (that
pins Java 11 and would refuse the Java-17 Spark 4.0 jars), matching the sibling
`openhouse-spark-4.0-itest`.

### Layout (all ported from the Scala-2.12 spark-3.1 `openhouse-spark-runtime`)

```
src/main/antlr/com/linkedin/openhouse/spark/sql/catalyst/parser/extensions/
    OpenhouseSqlExtensions.g4                         # grammar, COPIED VERBATIM from spark-3.1
src/main/scala/com/linkedin/openhouse/spark/
    extensions/OpenhouseSparkSessionExtensions.scala  # entry point (unchanged)
    sql/catalyst/enums/GrantableResourceTypes.scala   # unchanged
    sql/catalyst/parser/extensions/
        OpenhouseSparkSqlExtensionsParser.scala        # ADAPTED: Spark-4.0 ParserInterface
        OpenhouseSqlExtensionsAstBuilder.scala         # ADAPTED: Scala-2.13 + inlined granularity
    sql/catalyst/plans/logical/
        SetRetentionPolicy / SetReplicationPolicy / UnSetReplicationPolicy /
        SetHistoryPolicy / SetSharingPolicy / SetColumnPolicyTag /
        GrantRevokeStatement / ShowGrantsStatement    # ADAPTED: extend LeafCommand (was Command)
    sql/execution/datasources/v2/
        OpenhouseDataSourceV2Strategy.scala            # ADAPTED: SparkStrategy; GRANT cases removed
        Set{Retention,Replication,History,Sharing}PolicyExec.scala,
        UnSetReplicationPolicyExec.scala,
        SetColumnPolicyTagExec.scala                   # ADAPTED: extend LeafV2CommandExec
```

DROPPED vs. the 3.1 module (deliberately, to avoid Iceberg-core-internal + OpenHouse-Java-client
deps that cannot function on the REST lane): `GrantRevokeStatementExec`, `ShowGrantsStatementExec`,
`mapper/IcebergCatalogMapper`, `constants/Principal`, and `OpenHouseCatalog.java`. The GRANT/SHOW
GRANTS *logical plans + AST builder + grammar* are kept (they compile as pure Catalyst), so those
statements still parse; they simply have no physical exec wired (see "What remains").

### build.gradle essentials

- `id 'scala'`, `id 'java-library'` (NO minimal-conventions plugin).
- `configurations.antlr` + a `runAntlr` `JavaExec` task invoking the **ANTLR 4.13.1** tool
  (`org.antlr:antlr4:4.13.1`) — must match Spark 4.0's bundled `antlr4-runtime` 4.13.1, otherwise the
  generated parser throws an ATN-version mismatch at runtime. (The 3.1 module used 4.7.1.)
- Generated Java is emitted to `build/generated-src/antlr/main` and added to `main.java.srcDirs`;
  `compileScala.dependsOn compileJava` + `compileScala.classpath += compileJava.destinationDirectory`
  so the Scala AST builder / parser wrapper can see the generated `OpenhouseSqlExtensionsParser` /
  `...BaseVisitor` / `...BaseListener`.
- Everything else is `compileOnly` (scala-library 2.13.16, spark-sql_2.13:4.0.0,
  iceberg-spark-4.0_2.13, antlr4-runtime:4.13.1) — the itest gets all of these transitively from
  Spark 4.0 / Iceberg at test runtime. No gen-model / Java-client dep is needed (granularity strings
  are inlined — see below).

### settings.gradle

```
include ':integrations:spark:spark-4.0:openhouse-spark-runtime'
project(':integrations:spark:spark-4.0:openhouse-spark-runtime').name =
    'openhouse-spark-4.0-runtime_2.13'
```

---

## Spark-4.0 / Scala-2.13 API changes that forced adaptation

| Where | 3.x (Scala 2.12 / Spark 3.x) | 4.0 (Scala 2.13 / Spark 4.0) | Fix |
| --- | --- | --- | --- |
| `ParserInterface` | `parsePlan`, `parseExpression`, `parseTableIdentifier`, `parseFunctionIdentifier`, `parseMultipartIdentifier`, `parseTableSchema`, `parseDataType`, (+ custom `parseRawDataType`) | added `parseQuery(String): LogicalPlan` and `parseRoutineParam(String): StructType`; `parseRawDataType` no longer part of the contract; `parseDataType`/`parseTableSchema` now come from parent `DataTypeParserInterface` | `OpenhouseSparkSqlExtensionsParser` now overrides `parseQuery` + `parseRoutineParam` (both delegate), drops `parseRawDataType`. |
| Logical `Command` | `trait Command` supplied leaf semantics (`children = Nil`) | `Command` is a bare interface; leaf semantics moved to `LeafCommand` (`Command with LeafLike`) | all 8 logical plans `extends LeafCommand` (was `extends Command`). |
| Physical `V2CommandExec` | supplied `children` / `withNewChildrenInternal` | no longer leaf; `LeafV2CommandExec` adds them | all `Set*PolicyExec` / `UnSet.../ SetColumnPolicyTagExec` `extends LeafV2CommandExec` (was `V2CommandExec`). |
| `Strategy` type alias | `org.apache.spark.sql.Strategy` (= `SparkStrategy`) | alias removed | `OpenhouseDataSourceV2Strategy extends org.apache.spark.sql.execution.SparkStrategy`. `injectPlannerStrategy` still takes `SparkSession => SparkStrategy`, so the extension entry point is unchanged. |
| `scala.collection.JavaConversions` | present | REMOVED in Scala 2.13 | AST builder dropped the `JavaConversions.iterableAsScalaIterable` import (only `JavaConverters.asScala` was actually used; it stays, now deprecation-warned). |
| `String.toUpperCase` infix | n/a | 2.13 parses `x toUpperCase () match` as `toUpperCase(():Locale)` | not hit in the kept files (the affected `Principal` object was dropped). |
| gen-model dep | AST builder used `com.linkedin.openhouse.gen.tables.client.model.TimePartitionSpec.GranularityEnum.DAY.getValue()` | that class is only in the relocated *shadow* jar (the 4.0 itest uses the non-shadow client) | granularity tokens (`DAY`/`HOUR`/`MONTH`/`YEAR`) are inlined as string literals — identical values, and exactly the uppercase tokens the server's `updated.openhouse.policy` contract expects. Removes the client dep entirely. |
| `SparkSession.close()` | no checked exception | declares `IOException` | ported statement tests declare `throws IOException` on `tearDownSpark`. |

The grammar `.g4` was copied **verbatim** — the ANTLR grammar itself needed no change; only the tool
version (4.13.1) differs.

---

## Harness wiring

`integrations/spark/spark-4.0/openhouse-spark-itest`:

1. `build.gradle`: added
   `testImplementation project(':integrations:spark:spark-4.0:openhouse-spark-4.0-runtime_2.13')`.
2. `OpenHouseRestSparkITest.getBuilder`: `spark.sql.extensions` now registers BOTH
   `org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions` AND
   `com.linkedin.openhouse.spark.extensions.OpenhouseSparkSessionExtensions`.

Because the OpenHouse parser only intercepts SQL that matches its own DDL (`isOpenhouseCommand`
gate: `alter table ... set/unset policy`, `... modify column ... set tag`, `grant`, `revoke`,
`show grants`) and delegates everything else to the stock parser, adding it is transparent to every
existing REST-lane test.

---

## What works, and how it is tested (all GREEN)

### `SET POLICY` — end to end through the server

`SET POLICY (RETENTION ...)` / `(REPLICATION ...)` / `(SHARING=...)` / `(HISTORY ...)` parse →
`Set*PolicyExec` sets `updated.openhouse.policy = <json>` on the stock RESTCatalog table → the
`/iceberg` server folds it into the reserved `policies` property.

- **`PolicySqlDdlTestSpark4_0`** (new, extends `OpenHouseRestSparkITest`) — 4 tests, drives the real
  DDL against the embedded server and reads `policies` back via `SHOW TBLPROPERTIES`:
  `testSetRetentionPolicyViaSqlDdl`, `testSetReplicationPolicyViaSqlDdl`,
  `testSetSharingPolicyViaSqlDdl`, `testSetHistoryPolicyViaSqlDdl`. **All GREEN.**

### `SET / UNSET POLICY` — parse + plan (no server)

Ports of the legacy `statementtest` cases, using a local hadoop-backed Iceberg catalog + both
extensions, asserting the parsed/planned command via `explainString`. In the `...spark4-0itest`
module, package `com.linkedin.openhouse.spark.statementtest`:

- **`SetTablePolicyStatementTestSpark4_0`** (retention, 4 tests)
- **`SetTableReplicationPolicyStatementTestSpark4_0`** (replication + UNSET replication, 4 tests)
- **`SetHistoryPolicyStatementTestSpark4_0`** (history, 2 tests)
- **`SetSharingPolicyStatementTestSpark4_0`** (sharing + comment stripping + catalog/db resolution,
  9 tests)

**All 19 GREEN.** One adaptation from the 3.1 source: the leading-digit identifier `0_.0_` renders
as `` `0_`.`0_` `` (backtick-quoted) in Spark 4.0's `Identifier.toString`, vs `0_.0_` in Spark 3.1 —
a pure rendering delta (parse/plan intent identical), so those two sub-assertions expect the quoted
form.

### Restored inline SET POLICY in otherwise-green ports

- **`RTASTestSpark4_0.testRTAS`** — restored `ALTER TABLE ... SET POLICY (HISTORY MAX_AGE=24H)`.
  BEHAVIORAL DELTA: the legacy custom-catalog lane RESET `policies` to `""` on RTAS; the REST lane's
  `/iceberg` RTAS **preserves** the pre-existing policy. Assertion updated to the verified REST-lane
  behavior (policy survives). GREEN.
- **`WapIdTestSpark4_0.testWapWorkflowWithVariousOperations`** — restored
  `ALTER TABLE ... SET POLICY (SHARING=TRUE)` + a `policies` readback assertion inside the WAP
  workflow. (The 3.1 source's `GRANT SELECT ... TO lejiang` stays dropped — see below.) GREEN.

---

## What remains (and why)

### `UNSET POLICY (REPLICATION)` — blocked on the server

`UnSetReplicationPolicyExec` emits `updated.openhouse.policy = {"replication": {}}`. The `/iceberg`
server's `translatePolicyPatch` treats a present-but-empty `replication` as a *set* to an empty
config, and `ReplicationConfigValidator` rejects it with HTTP 400
`"...replication.config : Incorrect replication policy specified. Replication config cannot be null."`
There is no client-only encoding that clears a sub-policy under the current server contract (the
server merge only *overrides present* sub-policies; it has no clear/tombstone convention). Empirically
confirmed by running `CatalogOperationTestSpark4_0.testAlterTableUnsetReplicationPolicy` with the
extension wired: the `SET` steps succeed, the failure is precisely at the `UNSET` step.

→ `CatalogOperationTestSpark4_0.testAlterTableUnsetReplicationPolicy` stays `@Disabled` with the
reason updated to point here. Server fix needed: `/iceberg` must accept a policy patch that CLEARS a
sub-policy (e.g. an explicit null/tombstone for `replication`), mirroring the legacy client's
separate `UnSetReplicationPolicyExec` semantics. Then remove `@Disabled` and it should pass (the SET
portions it exercises already do).

### `GRANT` / `REVOKE` / `SHOW GRANTS` — parse only, no server endpoint

These still parse (grammar + logical plans + AST builder retained), but no physical exec is wired on
the REST lane. The legacy execs required a catalog implementing
`com.linkedin.openhouse.javaclient.api.SupportsGrantRevoke` (the OpenHouse custom Spark catalog),
which does not exist on the REST lane — the catalog is a stock `RESTCatalog`. Executing a GRANT
therefore falls through the strategy to an unsupported-plan error. Server work needed: an `/iceberg`
(or adjacent) ACL endpoint, plus a Spark-4.0 exec that calls it. `WapIdTestSpark4_0`'s inline
`GRANT SELECT ... TO lejiang` remains dropped for this reason.

### Column tags (`ALTER TABLE ... MODIFY COLUMN ... SET TAG (...)`) — wired, untested here

`SetColumnPolicyTagExec` IS ported and wired (it only needs `SparkTable.updateProperties`, and the
server's `translatePolicyPatch` does handle a `columnTags` patch). It is not covered by a green test
in this pass (column-tag validation requires specific column/tag semantics not exercised here). Treat
it as "should work, unverified" — a follow-up should add a targeted column-tag e2e test.

---

## Reconstruction checklist (if this must be rebuilt)

1. Copy the spark-3.1 `openhouse-spark-runtime` `src/main/antlr` + `src/main/scala` trees.
2. Apply the 7 API adaptations in the table above.
3. Drop `GrantRevokeStatementExec`, `ShowGrantsStatementExec`, `mapper/IcebergCatalogMapper`,
   `constants/Principal`, `OpenHouseCatalog.java`; remove the GRANT/SHOW-GRANTS cases from the
   strategy.
4. Write the `scala`+`java-library` build.gradle with the ANTLR-4.13.1 `runAntlr` task and the
   `compileScala.dependsOn compileJava` wiring; all Spark/Iceberg/scala deps `compileOnly`.
5. Add the two `settings.gradle` lines.
6. Add the `testImplementation` dep + the `spark.sql.extensions` entry in the itest harness.
7. Port the four `statementtest` cases + add `PolicySqlDdlTestSpark4_0`; restore the RTAS/WAP inline
   SET POLICY.

---

## Verification (targeted runs, JDK 17)

```
# new runtime module builds
./gradlew :integrations:spark:spark-4.0:openhouse-spark-4.0-runtime_2.13:jar          # BUILD SUCCESSFUL

# parse/plan statement tests (local hadoop catalog, no server)
./gradlew :integrations:spark:spark-4.0:openhouse-spark-4.0-itest:test \
  --tests "com.linkedin.openhouse.spark.statementtest.*Spark4_0"                       # 19 PASSED

# end-to-end SET POLICY DDL through the embedded server
./gradlew :integrations:spark:spark-4.0:openhouse-spark-4.0-itest:test \
  --tests "com.linkedin.openhouse.spark.catalogtest.PolicySqlDdlTestSpark4_0"          # 4 PASSED

# restored inline SET POLICY in otherwise-green ports
./gradlew :integrations:spark:spark-4.0:openhouse-spark-4.0-itest:test \
  --tests "com.linkedin.openhouse.spark.catalogtest.RTASTestSpark4_0.testRTAS" \
  --tests "com.linkedin.openhouse.spark.catalogtest.WapIdTestSpark4_0.testWapWorkflowWithVariousOperations"
                                                                                        # 2 PASSED
```
