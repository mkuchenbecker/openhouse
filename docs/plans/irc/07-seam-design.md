# Design note: the catalog plugin seam

**Proposal, not a decision. Delegated agent's reading of the code. Not agreed.**

Bottom line: the seam is feasible on the Iceberg 1.5.2 line without touching any class on today's
path. A new `RESTCatalog` subclass gets the adapter through Iceberg's public constructor. 1.2.0 can
do tables too, but only as a separate copy, and has no views. The three biggest behavior risks:
(1) CREATE OR REPLACE skips OpenHouse's replace gating, because the replace intent never reaches
`OpenHouseTableOperations`; (2) exception types and messages change unless the in-process adapter
rethrows the original exception; (3) CTAS goes through `CatalogHandlers.stageTableCreate`, which
makes two stage-create calls (one with an empty schema) and adds a `created-at` table property.

## Evidence keys

OpenHouse paths are in `/home/user/oh-upstream`. Iceberg paths are `git show li/openhouse-1.5.2:<path>`
unless marked `@1.2` (`li/openhouse-1.2.0`).

| Key | File |
| --- | --- |
| OHC | `integrations/java/iceberg-1.5/openhouse-java-runtime/src/iceberg-version-specific/java/com/linkedin/openhouse/javaclient/OpenHouseCatalog.java` |
| OHTO, CRH | `integrations/java/iceberg-1.2/openhouse-java-runtime/src/main/java/com/linkedin/openhouse/javaclient/OpenHouseTableOperations.java`, `CatalogRequestHeaders.java` |
| SOC | `integrations/spark/spark-3.5/openhouse-spark-runtime/src/main/java/com/linkedin/openhouse/spark/OpenHouseCatalog.java` |
| ICM | `integrations/spark/spark-3.5/openhouse-spark-runtime/src/main/scala/.../v2/mapper/IcebergCatalogMapper.scala` |
| OHIR | `services/tables/src/main/java/com/linkedin/openhouse/tables/repository/impl/OpenHouseInternalRepositoryImpl.java` |
| RC, RSC, RTO, CH, EH, RMR | `core/src/main/java/org/apache/iceberg/rest/` `RESTCatalog`, `RESTSessionCatalog`, `RESTTableOperations`, `CatalogHandlers`, `ErrorHandlers`, `RESTMetricsReporter` |
| RCA | `core/src/test/java/org/apache/iceberg/rest/RESTCatalogAdapter.java` (test sources) |
| SC, CU | `spark/v3.5/spark/src/main/java/org/apache/iceberg/spark/SparkCatalog.java`, `core/.../CatalogUtil.java` |
| SMK | `SparkSqlSmokeTest.java`, branch `irc/t1-smoke-suite` (Spark 3.5) |
| CPC | `CatalogPluginContractTest.java`, branch `irc/t3-plugin-tests`; source in the spark-3.1 itest module, also compiled by spark-3.5 (its `build.gradle`:13) |

## 1. Wiring

Spark builds the Iceberg catalog from `catalog-impl` with a no-arg constructor (SC:148-155,
CU:234-255). Today's class must not change, so the selector is `catalog-impl` itself:

| `catalog-impl` value | Path |
| --- | --- |
| `com.linkedin.openhouse.spark.OpenHouseCatalog` (default, unchanged) | Today |
| `com.linkedin.openhouse.spark.OpenHouseRestCatalog` (new) | `RESTCatalog` over the in-process adapter |
| a later value, not designed here | `RESTCatalog` over real HTTP |

A single extra property (say `openhouse.catalog-path`) cannot select today's path without being read
by a class on today's path. That is a choice for the owner.

New classes, all additions:

- `integrations/java/iceberg-1.5/openhouse-java-runtime/src/main/java` (the existing 1.5-only source
  root), package `com.linkedin.openhouse.javaclient` (same package, so the adapter can call the
  package-private `markReplaceTransaction`, OHTO:101):
  - `OpenHouseRestAdapter implements RESTClient`. `OpenHouseRestAdapter(OpenHouseCatalog backing)`;
    `head`, `get`, `post`, `postForm`, `delete`, `close`. `close()` must not close `backing`:
    `RESTSessionCatalog` closes its init client right after the config call (RSC:184).
  - `OpenHouseRestCatalog extends RESTCatalog implements SupportsGrantRevoke, SupportsUnlock`.
    No-arg constructor calls `super(props -> adapter(props))` (RC:61). The function is called twice,
    raw then merged props (RSC:184, 212); build `backing` once, from the first call's props, named
    with the Spark catalog name (rename checks it, OHC:356-364). `setConf(Object)` keeps the Hadoop
    conf for `backing`. `protected OpenHouseCatalog newBackingCatalog()`. Grant and unlock methods
    delegate to `backing`.
- `integrations/spark/spark-3.5/openhouse-spark-runtime`, package `com.linkedin.openhouse.spark`:
  `OpenHouseRestCatalog extends javaclient.OpenHouseRestCatalog`, overriding `newBackingCatalog()` to
  return the Spark `OpenHouseCatalog`, so the `column-defaults` capability is still declared (SOC:28).

The dispatcher. `RCA` is test-only, so write a small one with the same shape. Routes Spark 3.5 uses:

| Route | Handler | Spark use |
| --- | --- | --- |
| `GET v1/config` | empty `ConfigResponse` | catalog init (RSC:184-202) |
| `GET/POST v1/namespaces`, `GET/DELETE v1/namespaces/{ns}`, `POST .../{ns}/properties` | `CatalogHandlers` namespace calls | SHOW DATABASES, namespace probes |
| `GET/POST v1/namespaces/{ns}/tables`, `GET/POST/DELETE .../tables/{t}`, `POST v1/tables/rename` | `listTables`, `stageTableCreate`/`createTable`, `loadTable`, `updateTable`, `dropTable`/`purgeTable`, `renameTable` | all table SQL |
| `POST .../tables/{t}/metrics` | no-op | `RESTMetricsReporter` (RSC:417-427) |
| `GET/POST .../{ns}/views`, `GET/POST/DELETE .../views/{v}`, `POST v1/views/rename` | `CatalogHandlers` view calls | every identifier resolution probes `loadView` (OHC:94-104) |

Not needed: OAuth tokens, `register`, `transactions/commit`, `HEAD` (1.5.2 `tableExists` loads the
table instead). Errors: `RCA` maps by exact class (RCA:77-92, 640). Proposal: the in-process adapter
rethrows the original `RuntimeException` and skips the `ErrorResponse` round trip (see row E below).

## 2. Which Iceberg line

- 1.5.2: everything above exists. `RESTCatalog` is a `ViewCatalog` (RC:47-48).
- 1.2.0: `RESTCatalog(Function)` exists (RC@1.2:54) and `CatalogHandlers` has the table handlers.
  It lacks, concretely: `ViewCatalog` on `RESTCatalog` (RC@1.2:43), `RESTViewOperations`, view
  handlers and view routes; `UpdateRequirement`/`UpdateRequirements` as top-level classes (1.2.0 nests
  them in `UpdateTableRequest`, CH@1.2:290, RTO@1.2:104-134); a `RESTMetricsReporter` class (inline
  `reportMetrics`, RSC@1.2:369); `registerTable`. Spark 3.1 has no views, so tables-only is enough
  there, but the adapter needs a second, version-specific copy.
- Both suites cover the 1.5.2 line: the spark-3.5 itest module compiles the contract suite's sources.

## 3. Behavior differences: today vs `RESTCatalog` over the adapter

| # | Area | Verdict | How (evidence) | Caught by |
| --- | --- | --- | --- | --- |
| A | Load | Same | `backing` reads the metadata file and applies `ReadBridge` (OHTO:128-191); `LoadTableResponse` carries that `TableMetadata` object, no JSON in-process (CH:268-281) | SMK s01-s12 indirectly |
| B | `config` map | Same in-process | Overlay is applied inside `backing`. Response `config` is empty, so the catalog FileIO is reused (RSC:873-876) | none |
| C | Refresh cost | Different, cost only | Every REST refresh builds fresh ops and re-reads the file; today skips when the location is unchanged (OHTO:156-164) | none |
| D | Commit base | Different | Handler reloads the table and re-applies updates on the fresh base (CH:299-302, 357-380). A property-only commit has only `AssertTableUUID` (UpdateRequirements:45-55), so a stale base is silently rebased instead of failing | CPC `rawCommitWithStaleBaseFails` |
| D2 | Retries | Different | Handler retries 4 times on `CommitFailedException` (CH:348-355), inside the client's own retries | none |
| E | Exception types | Different with `ErrorResponse`; same with rethrow | Unmapped classes become 500: on commit `CommitStateUnknownException` (EH:90-93), elsewhere `ServiceFailureException`. Hits OpenHouse `BadRequestException` and `WebClientResponseWithMessageException` (403, 409, 422). 406 becomes `RESTException` (EH:213-215). Prefixes added ("Commit failed: ", "Malformed request: ") | SMK s05, s07, s15, s19; CPC namespaces, capability, authorization, rename |
| F | CREATE TABLE | Same (likely) | `createTable` calls the OpenHouse builder `create()` (CH:216-237) | SMK s01 |
| G | CTAS (staged create) | Different | `stageTableCreate` calls `createTransaction()` only for the location: a server stage-create (OHC:1030-1057). It returns locally built metadata with `created-at` added and no server `openhouse.*` keys (CH:186-211). The commit calls `buildTable(EMPTY_SCHEMA).createOrReplaceTransaction()`: a second stage-create, empty schema (CH:289) | SMK s04 only if it fails; `created-at` caught by none |
| H | RTAS and gating | Different, high | REST builds the replacement on the client and never sends `stageReplace` (RSC:720-781). The commit goes to `commit()` on ops never marked replace (OHC:1009, OHTO:207). The server gates only on those flags (OHIR:175, 405-414) | SMK s05 |
| I | CREATE OR REPLACE | Same outcome | REST tries replace first, after a view probe (RSC:721-729, 784-796); today checks `current()` (OHC:983-991) | SMK s05 |
| J | Drop, purge | Same | Spark purges client-side after a non-purge drop on both paths (SC:359-384); OpenHouse ignores `purge` (OHC:315-319) | SMK s14; CPC drop |
| K | Rename | Same if `backing` has the Spark name | Identifiers pass through to `renameTable` (CH:311-313, OHC:344-379) | SMK s13, s13b |
| L | `namespaceExists` | Different | `RESTCatalog` has no override; the default calls `loadNamespaceMetadata`, so the message becomes "Describing database is not supported", not "Checking if database exists is not supported" (OHC:503-505) | CPC `namespaceOperationsAreUnsupported` |
| L2 | Other namespace calls | Same with rethrow | Handlers call the same OpenHouse methods (CH:108-170) | SMK s19; CPC |
| L3 | `listTables` empty ns | Different | Client throws `NoSuchNamespaceException` "Invalid namespace" (RSC:991-995); today `ValidationException` (OHC:259-262) | none |
| M | `tableExists` | Different | REST default loads the table; today `tableExistsV1` works on locked or unreadable tables (OHC:281-302) | none |
| N | Views | Same (likely) | Handlers call `backing`'s gated view methods; disabled-state exceptions map to the same types (EH:145-158) | SMK s18 (enabled only) |
| O | FileIO | Different class | REST defaults to `ResolvingFileIO` (RSC:114, 864-871); today `HadoopFileIO` (OHC:218-223) | none |
| P | `table.name()` | Same | `<catalog>.<ns>.<t>` (RSC:860-862) | SMK indirectly |
| Q | Metrics | Same with no-op route | Configured reporter still loaded (RSC:241); REST reporter swallows failures (RMR:43-60) | none |
| R | Caching | Same | `SparkCatalog` wraps either catalog in `CachingCatalog` (SC:755-760); REST adds none | none |
| S | `header.*` props | Same if `backing` gets the raw props | `CatalogRequestHeaders` reads them (CRH:66-97); the adapter ignores REST headers | CPC capability |

## 4. OpenHouse-only features on the REST-shaped path

- Grants, unlock. `ICM:23-37` unwraps `SparkCatalog`, then `CachingCatalog`, and returns whatever
  `catalog-impl` built. The extensions match `SupportsGrantRevoke` / `SupportsUnlock` on it
  (`GrantRevokeStatementExec.scala:28-30`, `UnlockTableExec.scala:20-21`,
  `ResolveUnlockTable.scala:25`). A plain `RESTCatalog` matches neither, so the statements fail.
  Smallest fix: `OpenHouseRestCatalog` implements both and delegates to `backing`. `ICM` is unchanged.
- Capability header. `backing` is the Spark `OpenHouseCatalog`, so `CRH` still adds
  `X-OpenHouse-Client-Capabilities: column-defaults` to every OpenHouse call (OHC:171, 188).
- Policies. `SET POLICY` sets `updated.openhouse.policy` on tables with `openhouse.tableId`
  (`SetRetentionPolicyExec.scala:46, 66`). Under REST that is a `SetProperties` update. The handler
  applies it to `backing`'s ops, and `doCommit` strips the key and merges it into `policies`
  (OHTO:284-287, 349-398). So it works, but the stale-base check is lost (row D).

## 5. Capabilities as a model

- A capability is a lower-case name string. One class holds the constants: `column-defaults`
  (exists, OHC:147), `grants`, `unlock`, `policies`.
- Client declares: unchanged, the header from `capabilities()` plus `header.*` (CRH:103-118).
- Server advertises: a reserved key in `ConfigResponse` defaults, e.g.
  `openhouse.capabilities=grants,unlock,policies`. `RESTSessionCatalog` merges it into the catalog
  props (RSC:202). The adapter returns it in-process. Today's path never reads it.
- Not needed for the adapter path: `backing` implements everything, so the interfaces can be
  unconditional. Deferred: gating the interfaces on the advertised set, REST endpoints for grants and
  unlock, server-side policy merging and capability checks for the HTTP path.

## 6. Risks, ranked, and the cheapest experiment for each

| # | Risk | Cheapest experiment |
| --- | --- | --- |
| 1 | RTAS skips gating and is committed as an ordinary update (row H) | Run SMK s05 with a second `catalogPath()` value for the adapter |
| 2 | Exception types and messages drift (row E, L) | Run SMK with the adapter in both error modes, rethrow and `ErrorResponse`; diff the failures |
| 3 | CTAS: two stage-creates, empty schema, `created-at` (row G) | SMK s04 plus `SHOW TBLPROPERTIES` on the CTAS table, on both paths |
| 4 | Stale property and policy commits rebased silently (row D) | Run CPC `rawCommitWithStaleBaseFails` on the adapter |
| 5 | CPC has no catalog-path parameter, so it checks only today's path | Parameterize CPC by path like SMK |
| 6 | `tableExists` on locked tables, FileIO class (rows M, O) | One test: lock a table, call `tableExists`, print `table.io()` class on both paths |
| 7 | HTTP path: `config` map feeds FileIO and auth (RSC:370, 394); overlays must survive JSON | Round-trip one `LoadTableResponse` with an overlay through `RESTSerializers` |

Fix candidates for 1 to 3 (not chosen): a dispatcher handler for replace that calls
`markReplaceTransaction` before `commit`, given an explicit replace signal from `OpenHouseRestCatalog`;
rethrow in-process; a dispatcher handler for staged create that calls the OpenHouse builder once.

## Not verified

- Nothing was built or run. Every verdict comes from reading code.
- Whether the server accepts a stage-create with an empty schema, or writes files for it.
- What the server does with an unflagged RTAS commit: reject, or apply as an update.
- `ResolvingFileIO` behaving like `HadoopFileIO` on HDFS. `BaseMetastoreCatalog`'s table name format
  (not read; row P assumes it matches).
- That `CatalogUtil.configureHadoopConf` passes the Hadoop conf to `RESTCatalog.setConf(Object)`.
- That Spark 3.5 CREATE NAMESPACE reaches `loadNamespaceMetadata` (inferred from the SMK s19 message).
- That `RESTViewBuilder` with a null location passes `CreateViewRequest.validate()`.
- Spark 3.1's `SparkCatalog` and `IcebergCatalogMapper` on 1.2.0 (not read).
- Whether a replace signal can be told apart from the request shape alone (UpdateRequirements:118-168
  suggests not reliably).
