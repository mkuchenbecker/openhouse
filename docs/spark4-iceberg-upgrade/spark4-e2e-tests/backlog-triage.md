# Spark-4.0 / Iceberg-1.11 / REST-first e2e backlog — AGENTIC TRIAGE

Audit-grade disposition of the residual legacy Spark-3.1 / Spark-3.5 / apps-1.5 test backlog against the
Spark-4.0 REST-first lane (stock `org.apache.iceberg.rest.RESTCatalog` → OpenHouse `/iceberg/v1/*`
`IcebergRestCatalogController`, embedded server). Every group and every notable test is classified as one of:

- **ported+green** — re-expressed on the REST lane and verified with a targeted run.
- **decision-#4 parity gap** — needs the custom OpenHouse Spark SQL extension and/or a server change; not
  reachable on the stock REST lane today. Exact remaining work + cost recorded.
- **legacy-client-not-applicable** — validates the retiring `OpenHouseCatalog` Java client (mock-web-server
  or reflective-unwrap), not a coverage gap on the REST lane; the real behavior is covered by the existing
  Spark-4.0 port. Coverage map recorded.
- **needs-new-module / deferred** — needs a Spark-4.0 maintenance-apps home that does not exist.

Companion docs: `10-RESIDUALS.md` (the port's per-case fix checklist) and `20-legacy-gated.md` (the gate
inventory). This file does not edit those; the main session reconciles their checkboxes.

---

## HEADLINE FEASIBILITY FINDING (policy DDL on the REST lane)

**Goal evaluated:** load `com.linkedin.openhouse.spark.extensions.OpenhouseSparkSessionExtensions` on the
Spark-4.0 REST lane and make its custom DDL (`SET/UNSET POLICY`, `GRANT/REVOKE`, column `SET TAG`) work by
mapping to table properties that the stock `RESTCatalog` + `/iceberg` server persist.

**Verdict: NOT tractable in this pass.** Three independent, stacked blockers — each alone is disqualifying,
and the second is the deepest:

### Blocker 1 — the extension has no Spark-4.0 / Scala-2.13 build (module-creation cost)
The extension lives ONLY in `integrations/spark/spark-3.1/openhouse-spark-runtime` (and a byte-similar
copy in `spark-3.5/openhouse-spark-runtime`), both **Scala 2.12 / Spark 3.1–3.5**. It is a Scala + ANTLR
module:
- `src/main/antlr/.../OpenhouseSqlExtensions.g4` (custom grammar: `SET POLICY`, `UNSET POLICY`, `GRANT`,
  `REVOKE`, `SHOW GRANTS`, `MODIFY COLUMN ... SET TAG`)
- `.../parser/extensions/OpenhouseSparkSqlExtensionsParser.scala`, `OpenhouseSqlExtensionsAstBuilder.scala`
  (`OpenhouseParseException`)
- `.../plans/logical/*.scala` (Set{Replication,History,Retention,Sharing}Policy, SetColumnPolicyTag,
  GrantRevokeStatement, ShowGrantsStatement, UnSetReplicationPolicy)
- `.../execution/datasources/v2/*Exec.scala` + `OpenhouseDataSourceV2Strategy.scala`

There is **no `spark-4.0/openhouse-spark-runtime` module** — `integrations/spark/spark-4.0` contains only
`openhouse-spark-itest`, which is a **Java-only** module (`plugins { id 'java' }`, no `scala` plugin) and
by design ships NO custom OpenHouse Spark runtime — it wires the STOCK `iceberg-spark-extensions-4.0_2.13`.
Scala 2.12 class files cannot load in a Scala-2.13 / Spark-4.0 runtime (binary-incompatible). Loading the
extension therefore requires creating a **new `spark-4.0/openhouse-spark-runtime` Scala-2.13 module** and
porting the ANTLR grammar, AST builder, logical plans, the V2 strategy, and all `*Exec` commands to Spark
4.0's changed Catalyst / `ParserInterface` surface (Spark 4.0 added `ParserInterface` methods — e.g.
`parseQuery`, `parseRoutineParam` — and changed `V2CommandExec` / analyzer-injection points). This is a
substantial module port, the same order of magnitude as the deferred apps module.

### Blocker 2 — the policy "table property" is a LEGACY-CLIENT smuggling convention that is a NO-OP on the REST lane (server-side gap)
Even with the extension loaded, its `SET POLICY` handlers do NOT write the server's real policy. Each
`Set*PolicyExec` / `SetColumnPolicyTagExec` / `UnSetReplicationPolicyExec` does:

```scala
// SetReplicationPolicyExec.scala:12-17 (representative)
case iceberg: SparkTable if iceberg.table().properties().containsKey("openhouse.tableId") =>
  val key = "updated.openhouse.policy"
  iceberg.table().updateProperties().set(key, <json>).commit()
```

`updated.openhouse.policy` is a **client-side patch channel**, consumed ONLY by the legacy custom
`OpenHouseTableOperations` (`integrations/java/iceberg-1.2/openhouse-java-runtime/.../OpenHouseTableOperations.java`):
- `UPDATED_OPENHOUSE_POLICY_KEY = "updated.openhouse.policy"` (line 90)
- `doCommit → createUpdateTable` calls `setPolicies(buildUpdatedPolicies(metadata))` (line 215) and
  **filters the key out** of plain properties (line 218)
- `buildUpdatedPolicies` (lines 277-324) deserializes it into the `Policies` gen-model and sends it as the
  **structured `.policies` field of the `/tables` `CreateUpdateTableRequestBody`**.

The **stock `RESTCatalog` never uses `OpenHouseTableOperations`** — it uses stock `RESTTableOperations`,
which forwards `updated.openhouse.policy` as an ordinary property to `/iceberg`. Repo-wide search confirms
**zero** server-side (`services/`, `iceberg/`) references to `updated.openhouse.policy`: the `/iceberg`
controller has no handler that recognizes it. Net on the REST lane: the DDL would store a meaningless
`updated.openhouse.policy` property and **the real `policies` blob would never be set**.

Compounding this, the true `policies` property is **server-reserved**: `policies` and any `openhouse.*`
key are rejected by the REST controller's `enforceReservedPropsUnchanged`
(`IcebergRestCatalogController`, `isReservedKey` + `ALTER_RESERVED_TBLPROPS` → HTTP 400) on UPDATE and
stripped on CREATE (`OpenHouseInternalRepositoryImpl.computePropsForTableCreation` +
`BasePreservedKeyChecker`). So a client cannot set `policies` directly either. The server DTO for policy is
the structured `Policies` object (retention, history, replication, sharingEnabled, columnTags, lockState —
one JSON blob keyed `policies`, authored server-side), reachable only through the `/tables` service (or the
controller's internal RTAS re-route), never as a raw client TBLPROPERTY.

**Making SET POLICY work on the REST lane therefore additionally requires a SERVER change:** teach
`IcebergRestCatalogController` / `OpenHouseInternalTableOperations` to recognize the `updated.openhouse.policy`
patch on the `/iceberg` commit path and route it into the structured `Policies` path — a new server feature
that intersects the reserved-key guard another change owns. Out of scope for a test-port pass.

### Blocker 3 — GRANT/REVOKE/SHOW GRANTS need a `SupportsGrantRevoke` catalog + a server endpoint
`GrantRevokeStatementExec` / `ShowGrantsStatementExec` reflectively unwrap the Spark catalog to the
underlying Iceberg `Catalog` (`IcebergCatalogMapper.toIcebergCatalog`, via `DynFields` on
`SparkCatalog.icebergCatalog`) and cast to `com.linkedin.openhouse.javaclient.api.SupportsGrantRevoke`,
calling `updateTableAclPolicies` / `getTableAclPolicies`. The stock `RESTCatalog` does **not** implement
`SupportsGrantRevoke`, and `/iceberg` exposes no grant/ACL endpoint, so on the REST lane these throw
`UnsupportedOperationException("... does not support Grant Revoke Statements")`. Grant/revoke is doubly
infeasible (no catalog capability + no server endpoint).

**Consequence for the ports the correction asked for:** none of the statementtest policy cases,
`CatalogOperationTestSpark4_0.testAlterTableUnsetReplicationPolicy`, nor the `RTASTestSpark4_0` /
`WapIdTestSpark4_0` inline policy omissions can be made to pass on the REST lane today. They are left as
recorded gaps (below) with the exact remaining work. Nothing was re-enabled that cannot pass (no faked green).

---

## GROUP 1 — Custom-SQL policy cases (statementtest + disabled/omitted policy assertions)

### 1a. Nature of the statementtest suite (~60 cases)
All six statementtest classes are **parser-plan unit tests**, not e2e. They register the custom
`OpenhouseSparkSessionExtensions` over a **`type=hadoop`** catalog (or, for GrantRevoke, a mock
`GrantRevokeHadoopCatalog implements SupportsGrantRevoke`) — the OpenHouse server is NEVER contacted. They
assert on `Dataset.queryExecution().explainString(...)` substrings (that the custom plan parsed) or on a
mock catalog's captured fields, and assert `OpenhouseParseException` for malformed DDL. They exercise the
**parser**, whose home is the missing Scala-2.13 extension module (Blocker 1). Files:
`integrations/spark/spark-3.1/openhouse-spark-itest/src/test/java/com/linkedin/openhouse/spark/statementtest/`.

| Class | Cases | DDL exercised | Server-side end-state it maps to | REST-lane disposition |
|---|---|---|---|---|
| `SetHistoryPolicyStatementTest` | 2 | `SET POLICY (HISTORY MAX_AGE=.. VERSIONS=..)` | `policies.history` blob | **parity gap** — B1 (parser) + B2 (`updated.openhouse.policy` no-op server-side) |
| `SetTableReplicationPolicyStatementTest` | ~4 | `SET/UNSET POLICY (REPLICATION=({destination,interval}..))` | `policies.replication` | **parity gap** — B1 + B2 |
| `SetSharingPolicyStatementTest` | ~8 | `SET POLICY (SHARING=TRUE/FALSE)` | `policies.sharingEnabled` | **parity gap** — B1 + B2 |
| `SetTablePolicyStatementTest` | (retention) | `SET POLICY (RETENTION=.. on COLUMN .. WHERE pattern=..)` | `policies.retention` | **parity gap** — B1 + B2 |
| `SetColumnPolicyTagStatementTest` | ~7 | `MODIFY COLUMN c SET TAG = (PII, HC)` | `policies.columnTags` | **parity gap** — B1 + B2 |
| `GrantRevokeStatementTest` | ~30 | `GRANT/REVOKE .. ON TABLE/DATABASE`, `SHOW GRANTS` | ACL via `SupportsGrantRevoke` | **parity gap** — B1 + B3 (no grant catalog/endpoint) |

None are portable to the REST lane as-is: they test the parser, which is not present, and the plans they
build reduce to either a no-op property write (B2) or a `SupportsGrantRevoke` call the stock catalog cannot
service (B3). There is no table-property expression of these end-states through the stock `/iceberg` path
(reserved-key guard, Blocker 2).

### 1b. Disabled / omitted policy assertions in the existing Spark-4.0 port
| Item | Location | Disposition | Root cause / remaining work |
|---|---|---|---|
| `testAlterTableUnsetReplicationPolicy` (`@Disabled`) | `CatalogOperationTestSpark4_0` | **parity gap** (left disabled) | `SET/UNSET POLICY` DDL unparseable (B1) and would no-op server-side (B2); readback needs the `Policies` gen-model off-classpath. Fix = new Scala-2.13 extension module + server `updated.openhouse.policy` handler on `/iceberg`. |
| `SET POLICY (HISTORY MAX_AGE=24H)` + `policies` prop assert (dropped) | `RTASTestSpark4_0.testRTAS` | **parity gap** (omission kept) | Same B1+B2. `policies` is reserved and unreadable via stock loadTable. |
| `SET POLICY (SHARING=TRUE)` + `GRANT SELECT ... TO lejiang` (dropped) | `WapIdTestSpark4_0.testWapWorkflowWithVariousOperations` | **parity gap** (omission kept) | Sharing = B1+B2; grant = B1+B3. |
| `openhouse.tableUri` assertion (dropped) | `CatalogOperationTestSpark4_0.testRenameTableCatalogApi` | **parity gap** (omission kept) | `openhouse.*` reserved props not surfaced by stock REST `loadTable`; needs the `/iceberg` `loadTable` response to carry `openhouse.*`. Server change. |

Additional REST-lane-specific note: even if the extension parsed the DDL, `Set*PolicyExec` guards on
`table.properties().containsKey("openhouse.tableId")`; `openhouse.*` props are not surfaced by the stock
REST `loadTable` (see the `openhouse.tableUri` residual), so the guard would itself fail with
`UnsupportedOperationException("... non-Openhouse table")` — a fourth, independent obstacle.

### 1c. Exact fix-cost to close Group 1 (for a later pass)
1. Create `integrations/spark/spark-4.0/openhouse-spark-runtime` (Scala 2.13, ANTLR 4.x): port the grammar
   + AST builder + logical plans + `OpenhouseDataSourceV2Strategy` + `*Exec` commands to Spark 4.0's
   `ParserInterface` / Catalyst APIs; register via `spark.sql.extensions` in
   `OpenHouseRestSparkITest.getBuilder` and add it as `testImplementation`.
2. Server: add an `updated.openhouse.policy` (and column-tag) translation on the `/iceberg` commit path
   (`IcebergRestCatalogController` + `OpenHouseInternalTableOperations`) that routes the patch into the
   structured `Policies` object — coexisting with the reserved-key guard.
3. Surface `openhouse.*` (at least `tableId`, `tableUri`) and the `policies` blob in the stock REST
   `loadTable` response so the exec guards pass and readback assertions work.
4. For GRANT/REVOKE: add a grant/ACL endpoint on `/iceberg` and a REST-lane catalog that implements
   `SupportsGrantRevoke` (or reroute grant DDL to a REST ACL call). Largest of the four.

Items 1–3 unblock SET POLICY (replication/history/retention/sharing/column-tags) and the 1b entries;
item 4 additionally unblocks GrantRevoke. This is a multi-module feature, not a test port.

---

## GROUP 2 — DEFAULT `test` group (spark-3.1 / spark-3.5 itest `test` task, ~49 failing of 90)

**Finding: the ENTIRE default `test` group is legacy-client mock-web-server validation — none is a genuine
real-server e2e coverage gap on the REST lane.** Every case in this task extends `SparkTestBase`
(`integrations/spark/spark-3.1/openhouse-spark-itest/.../SparkTestBase.java`), which stands up an
`okhttp3.mockwebserver.MockWebServer` and wires the custom `com.linkedin.openhouse.spark.OpenHouseCatalog`
+ `OpenhouseSparkSessionExtensions` at it. The tests `enqueue` canned `/tables` HTTP responses
(`mockResponse(404|201|200|403, ...)`) and assert the legacy client emits the right doRefresh/doCommit
request sequence and maps response envelopes / exceptions
(`WebClientResponseException`, `TableAlreadyExistsException`). Example: `CreateTableTestSpark3_5` enqueues
three 404 `doRefresh` + a 201 `doCommit` + a 200 `doRefresh` and only asserts `assertDoesNotThrow` (its own
comment: "When we are out of mock, we should verify the created schema as well"). These validate the
**retiring Java client**, which the stock `RESTCatalog` replaces on the 4.0 lane.

### 2a. Mock-client-internals tests → legacy-client-not-applicable
`integrations/spark/spark-3.1/openhouse-spark-itest/.../spark/mock/`: `DoCommitTest`, `DoRefreshTest`,
`ServerClientExceptionMappingTest`, `mapper/IcebergCatalogMapperTest`. These assert the
`OpenHouseTableOperations` commit/refresh protocol and error mapping and the reflective `IcebergCatalogMapper`
unwrap — pure legacy-client mechanics with no analogue on the stock `RESTCatalog`. **No REST-lane equivalent
is meaningful.**

### 2b. e2e ddl/dml mock tests → legacy-client-not-applicable; real behavior already covered
The `spark/e2e/ddl`, `spark/e2e/dml`, `spark/e2e/extensions` classes (CreateTable, CreateTableWithProps,
CreateTablePartitioned, AlterTable, AlterTableSchema, DescribeTable, DropTable, ShowTables, ShowDatabases,
UseCatalog, InsertIntoTable, InsertOverwriteTable, CTAS, SelectFromTable, RenameTable, MultiComments,
GrantStatement, SetRetentionPolicy) are all `@ExtendWith(SparkTestBase.class)` mock-server tests. The
underlying **behaviors** are already exercised end-to-end against the real embedded server by the existing
Spark-4.0 catalogtest port:

| Legacy mock behavior | Covered on REST lane by |
|---|---|
| CREATE TABLE | `CatalogOperationTestSpark4_0.testCasingWithCTAS`, `.testCreateTablePartitionedByDate` |
| CREATE ... TBLPROPERTIES | `RTASTestSpark4_0.testRTAS` (create with `prop1/prop2`, readback) |
| CREATE ... PARTITIONED BY | `PartitionTestSpark4_0`, `CatalogOperationTestSpark4_0.testCreateTablePartitionedByDate` |
| ALTER TABLE (schema add col) | `SparkMultiSchemaEvolutionTestSpark4_0` |
| ALTER TABLE (WRITE ORDERED / props) | `CatalogOperationTestSpark4_0.testAlterTableSetSortOrder` etc. |
| INSERT INTO / SELECT | `WapIdTestSpark4_0.*`, `CatalogOperationTestSpark4_0.*`, `RTASTestSpark4_0.*` (widely) |
| CTAS | `CatalogOperationTestSpark4_0.testCasingWithCTAS`, `RTASTestSpark4_0.testCreateRTAS` |
| DROP TABLE | used as cleanup throughout the port (real drops) |
| RENAME TABLE | `CatalogOperationTestSpark4_0.testRenameTable{,CatalogApi,CaseSensitivity,FailsConflict}` |
| DESCRIBE / SHOW TABLES / USE / SHOW DATABASES | stock Spark-4.0 + stock `RESTCatalog` behavior (engine-native; not OpenHouse-specific) |
| authz 403 / error-envelope mapping | legacy-client mock concern; on REST lane surfaced as stock `RESTCatalog` exceptions (`BadRequestException` shown in `RTASTestSpark4_0.testRTASFailsWhenReplaceDisabled`) |
| `SET POLICY (RETENTION ...)` / `GRANT` (extensions/) | Group 1 parity gap (B1/B2/B3) |

No additive real-server port is warranted: the substantive DDL/DML behaviors are covered, and what remains
unique to these classes (client request-sequencing, `/tables` envelope mapping) is intrinsically legacy-client
and not applicable to the stock REST client. The ~41 that "pass" on 1.11 are the pure-mock cases that never
co-load a second Iceberg class-set; the ~49 that "fail" do so from the two-Iceberg-version in-JVM collision,
not from a behavioral regression.

---

## GROUP 3 — apps-1.5 data-plane (6 tests) — DEFERRED to the batched-apps rewrite

The 6 gated maintenance-job data-plane cases (`apps/spark-3.5`:
`OperationsTest.testDataCompactionPartialProgress{NonPartitioned,Partitioned}Table` and
`SparkMoRFunctionalTest.{testBudgetedRewriteUsesDataLengthForTaskGrouping,testCompactionCanRemoveEqualityDeleteFiles,testCompactionCanRemovePositionDeleteFiles,testDeleteFilesCanBeCreated}`) exercise real
compaction / merge-on-read delete-file rewrites via `Operations`/`SparkActions` and are gated because the
legacy Spark-3.5 / Iceberg-1.5 data-plane collides in-JVM with the 1.11 embedded server. There is no
`apps/spark-4.0` module. Per the current direction these are **deferred to the separate batched-apps
rewrite** (the maintenance apps are being reworked into batched apps independently); building a bespoke
Spark-4.0 apps module here would conflict with that effort. No plan or module is proposed in this pass —
the 6 remain gated (`apps/spark-3.5/build.gradle`) and will land on their single-version 1.11 home when the
batched-apps rewrite provides one.

---

## Net disposition summary

| Group | ported+green | parity gap (decision #4 / server) | legacy-client-not-applicable | deferred/needs-module |
|---|---|---|---|---|
| 1 — statementtest policy (~60) + 4 port omissions | 0 | ~60 + 4 | 0 | 0 |
| 2 — default `test` (mock-client, ~90) | 0 | 2 (extensions/ SET RETENTION, GRANT → Group 1) | ~88 (all mock e2e + mock-internals) | 0 |
| 3 — apps-1.5 data-plane (6) | 0 | 0 | 0 | 6 |

No clean, genuinely-passing REST-lane port exists for any backlog item under the current architecture:
Group 1 is blocked by the missing Scala-2.13 extension module + the client-only policy smuggling convention
+ reserved-key server guards (and, for grants, a missing catalog capability/endpoint); Group 2 is legacy
mock-client validation whose real behaviors the existing Spark-4.0 port already covers; Group 3 is deferred
to the batched-apps rewrite. Nothing was re-enabled that cannot pass.
</content>
</invoke>
