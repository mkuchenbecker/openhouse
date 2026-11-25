# C1: legacy path vs REST-shaped path (`rest-shim`), Spark 3.5 / Iceberg 1.5.2

Branch `irc/c1-rest-shaped-seam` (worktree `/home/user/oh-c1`). Legacy = `catalog-impl=com.linkedin.openhouse.spark.OpenHouseCatalog`
(unchanged, default). REST-shaped = `catalog-impl=com.linkedin.openhouse.spark.OpenHouseRestCatalog`: Iceberg `RESTCatalog`
over `OpenHouseRestAdapter` (in-process `RESTClient`) which calls today's `OpenHouseCatalog`.

Paths below: `ADP` = `integrations/java/iceberg-1.5/openhouse-java-runtime/src/main/java/com/linkedin/openhouse/javaclient/OpenHouseRestAdapter.java`,
`ORC` = `.../javaclient/OpenHouseRestCatalog.java` (same dir), Iceberg files are `li/openhouse-1.5.2:core/src/main/java/org/apache/iceberg/rest/...`
(`RSC` RESTSessionCatalog, `RTO` RESTTableOperations, `CH` CatalogHandlers). `SMK` = SparkSqlSmokeTest, `CPC` = CatalogPluginContractTest.

## Result

| Suite | rest-shim, final seam | rest-shim, every route on Iceberg's `CatalogHandlers` ("plain adapter", commit `b0d8242`) |
| --- | --- | --- |
| SMK original 21 scenarios | 21 pass unchanged, 0 overrides, 0 fail | 18 pass, 3 fail (s04, s05, s10) |
| SMK added s21-s30 (asserted on legacy first) | 10 pass | 5 fail (s21, s22, s23, s24, s25) |
| CPC original 18 | 18 pass unchanged, 0 overrides, 0 fail | 16 pass, 2 fail (namespaceOperationsAreUnsupported, rawCommitWithStaleBaseFails) |
| CPC added 2 | 2 pass | 2 fail |

(Plain-adapter counts are with the grant/unlock interfaces present; before them s17 and CPC grantsRoundTrip also failed.)
No test needed a new-path override; no expectation was changed.

## Differences that remain (final seam)

| # | What differs | Legacy | REST-shaped | Where it comes from | Test that shows it | Fixable in new classes / service change / decision |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | When the RTAS / REPLACE gate runs, and what it leaves behind | `replaceTransaction()` sends a stage-replace (`stageReplace=true`) before any data is written; a gated table fails there (`OpenHouseCatalog.java` iceberg-1.5 `:999-1011, :1059-1088`) | No stage-replace call. The client builds the replacement locally (RSC `:720-781`), Spark writes the data files, then the commit (sent as a replace, ADP `updateTable`, `markReplaceTransaction` at ADP `:404`) is rejected by the server's gate (`OpenHouseInternalRepositoryImpl.java:175-176`). Same exception class and message (s05, s23 pass), but the rejected attempt's data file stays in the table directory | RSC `:720-781`; ADP `:388-425` | No test (not visible to Spark SQL). Observed on disk: `/tmp/smoke_t1/s05_replace_off-<uuid>/data` held 2 ORC files for the two rest-shim runs I could identify by timestamp (06:15:59, 06:21:21) vs 1 for the other runs; directories are removed when a later run drops the table, so this is a by-hand observation | Fixable in the new classes only by calling the backing catalog's stage-replace from `OpenHouseRestCatalog`'s builder before delegating (not a REST request), or by a REST extension for the replace intent: **needs a decision** |
| 2 | How a replace commit is recognized | Explicit flag set where the replace is known (`OpenHouseCatalog.replaceTransaction`, `markReplaceTransaction`) | Inferred from the request shape Iceberg 1.5.2's `RTO` builds: `SetCurrentSchema` + `SetDefaultPartitionSpec` + `SetDefaultSortOrder` with none of `AssertCurrentSchemaID` / `AssertDefaultSpecID` / `AssertDefaultSortOrderID` (ADP `isReplace` `:436`). Deterministic for this client; another client version or an HTTP client could build different requests | RTO `:109-139`, `UpdateRequirements.forReplaceTable/forUpdateTable` | `OpenHouseRestAdapterTest` (5 cases); SMK s05, s22, s23 | **Needs a decision** (explicit replace signal on the wire before an HTTP path) |
| 3 | A requirement-free commit (properties, `SET POLICY`) from a handle whose metadata is out of date | Sent with `baseTableVersion` = the handle's metadata location; the server answers 409, the client refreshes and retries | The handler loads the table fresh and applies the updates to the current metadata (ADP `:398-418`), so the server's `baseTableVersion` check never fires; the commit succeeds on the first attempt. Expected final state is the same for `updateProperties` and `SET POLICY` (the client's retry would re-apply the same change), with fewer server calls: **inferred from code, the concurrent case was not reproduced**. A raw `ops.commit(staleBase, ...)` on a handle still fails as today (seam check, ORC `StaleBaseCheckingOperations` `:253`) | REST protocol semantics: `UpdateRequirements.forUpdateTable` emits only `AssertTableUUID` for property changes | No test (not observable through Spark SQL); CPC `rawCommitWithStaleBaseFails` covers the raw case and passes | **Needs a decision** (REST semantics vs today's version check; carrying the base version would need a non-spec requirement or a server change) |
| 4 | Server and storage calls per operation | `refresh()` skips the metadata read when the location is unchanged (`OpenHouseTableOperations.doRefresh`); a commit sends one request | Every client `refresh()` builds fresh backing ops and re-reads the metadata file; every commit first loads the table (GET + metadata read) in the handler, then commits | RTO `:99-102`; CH `loadTable`; ADP `:398` | None | Cost only. Fixable later with caching in the adapter; **decision** whether it matters |
| 5 | CTAS when another writer creates the same table between stage-create and commit | Same ops instance from stage-create to commit; the server answers 409, `CommitFailedException("409 , {...}")` | The create commit runs on fresh ops: requirement `AssertTableDoesNotExist` fails with `CommitFailedException("Requirement failed: table already exists")` | ADP `:390-395` | None. **Inferred from code, not reproduced** | Fixable in the new classes (keep the staged ops per identifier between the two requests); **needs a decision** (stateful handler) |
| 6 | Metrics reporter instances | One `metrics-reporter-impl` instance per catalog (`BaseMetastoreCatalog.metricsReporter()`) | Two: RSC's (used by create/replace transactions, RSC `:241`) and the seam's (tables from `loadTable`/`create`, ORC `metricsReporter()` `:230`). REST metrics reporting is off (adapter config default), so nothing is posted | RSC `:241, :417-427`; ORC `:230` | None | Fixable in the new classes (would need access to RSC's reporter, which is private) / accept |
| 7 | Tables not wrapped by the stale-base check | All `OpenHouseTableOperations` check `base == current` | Tables reached through a transaction (`txn.table()`) and metadata tables keep `RTO`, which sends a stale-base commit | ORC `checkingStaleBase` | None | Fixable in the new classes (wrap more entry points); low value |
| 8 | Java API surface of the catalog object | `OpenHouseCatalog` (public `newTableOps`, protected `updateAuthToken`, `BaseMetastoreViewCatalog` API) | `OpenHouseRestCatalog extends RESTCatalog`; code that casts the Iceberg catalog to `OpenHouseCatalog` or subclasses it for `updateAuthToken` does not work. No such code in this repo (grep) | ORC | None | **Decision** (document, or expose a backing accessor) |
| 9 | Catalog properties that mean something to `RESTSessionCatalog` | Ignored by today's catalog | `token`, `credential`, `oauth2-server-uri`, `prefix`, `snapshot-loading-mode`, `rest-metrics-reporting-enabled`, `header.*` (headers ignored by the adapter) are read by RSC; an OAuth token refresh would go to `v1/oauth/tokens`, which has no route | RSC `:166-246` | None. **Inferred from code** | Fixable in the new classes (strip or reject them in `initialize`) |
| 10 | View replace / alter (`CREATE OR REPLACE VIEW`, view property changes) | `OpenHouseViewOperations` commit, client-side | `RESTViewBuilder.replace` builds the new version client-side and commits through `CH.updateView`, which retries up to 4 times on `CommitFailedException` | RSC `:1220-1290`; CH `:447-456, :469-513` | None (SMK s18 covers create/select/drop only). **Not verified** | Fixable in the new classes (own view-commit handler like the table one) |
| 11 | Multi-table `RESTCatalog.commitTransaction` | No such API | No route: fails with `BadRequestException` from the error handler | ADP route table | None | n/a (new API); **decision** whether to route it |
| 12 | Iceberg 1.2 / Spark 3.1 line | Today's path | No seam (out of scope) | design note §2 | CPC on spark-3.1 runs `legacy` only | Separate copy needed; **decision** |

## Differences found and fixed in the new classes (behavior now matches)

| What differed (plain adapter) | Legacy | Plain REST-shaped | Fix (file) | Test |
| --- | --- | --- | --- | --- |
| CTAS failed entirely | One stage-create; commit on staged metadata | `CH.stageTableCreate` built metadata locally (+`created-at`), then the create commit stage-created again with an empty schema; server: 400 "schema ... should contain at least one column" (CH `:186-211, :289`) | ADP `stageCreate` (one backing stage-create, returns the server-staged metadata) and create branch of `updateTable` (fresh ops, no second stage-create) | SMK s04, s21 |
| RTAS / REPLACE not gated | Gated by `replace.enabled` and the other replace checks | Committed as an ordinary update: rejected as schema evolution when the type changed (s05), applied silently when compatible (s23: ungated `REPLACE TABLE` succeeded; s22: `openhouse.creationTime` kept instead of reset to 0) | ADP `isReplace` + `markReplaceTransaction` on the commit ops | SMK s05, s22, s23 |
| Handler retries | Client retries only | `CH.commit` retried 4 times inside the handler on top of the client's retries (CH `:348-355`) | ADP `updateTable`: one attempt | none (by construction) |
| FileIO class | `HadoopFileIO` | `ResolvingFileIO` (RSC `:114`); merge-on-read delete files failed: "Creating output file ... with properties {file-replication-factor=3} is not supported" | Adapter config default `io-impl=org.apache.iceberg.hadoop.HadoopFileIO` (user value still wins) | SMK s10; CPC loadedTableNameAndFileIO |
| REST metrics reporter | Only `metrics-reporter-impl` | Also `RESTMetricsReporter` posting to `.../metrics` | Adapter config default `rest-metrics-reporting-enabled=false` | none |
| `namespaceExists` message | "Checking if database exists is not supported" | "Describing database is not supported" (default method loads metadata) | ORC `namespaceExists` via `HEAD v1/namespaces/{ns}` | CPC namespaceOperationsAreUnsupported |
| `setProperties` / `removeProperties` on a namespace | "Set/Remove properties ... not supported" | "Describing database is not supported" (`CH.updateNamespaceProperties` describes first, CH `:153`) | ADP `updateNamespaceProperties` | SMK s24 (ALTER NAMESPACE), CPC namespaceOperationsAreUnsupported |
| `tableExists` on a table locked against reads | `true` (no metadata load) | `WebClientResponseWithMessageException` 423 (load-based default) | ORC `tableExists` via `HEAD .../tables/{t}` | CPC tableExistsOnTableLockedAgainstReads |
| `SHOW TABLES` with no database | `ValidationException` "DatabaseId was not provided..." | `NoSuchNamespaceException` "Invalid namespace" from the client (RSC `:991-995`) | ORC `listTables` sends the empty namespace through the adapter | SMK s25 |
| Raw commit on a stale base | `CommitFailedException` "Cannot commit: stale table metadata" | Applied (RTO does not check `base == current`) | ORC `StaleBaseCheckingOperations` on tables from `loadTable` / `create` | CPC rawCommitWithStaleBaseFails |
| Grants, unlock | Work | `GRANT`: UnsupportedOperationException "does not support Grant Revoke Statements" | ORC implements `SupportsGrantRevoke`, `SupportsUnlock` (delegation) | SMK s17, s27; CPC grantsRoundTrip |
| `registerTable` | `BaseMetastoreCatalog.registerTable` | No route | ADP `REGISTER_TABLE` -> `CH.registerTable` | none |
| `properties()` | Raw properties | Merged with config defaults | ORC `properties()` returns the backing catalog's | none |
| Exception classes and messages | Original OpenHouse exceptions | Would be re-mapped by `ErrorResponse` (design note row E) | ADP rethrows the original exception, no `ErrorResponse` | SMK s05, s07, s15, s17, s19, s24, s29; CPC throughout |

Checked and the same on both paths (probe of ~70 statements plus the suites): `table.name()` / DESCRIBE EXTENDED Name
`openhouse.<db>.<t>` (s26, CPC), CREATE/CTAS/RTAS property key sets, `ALTER ... COMMENT`, `WRITE ORDERED BY`, `DROP COLUMN`
rejection, metadata tables (`history`, `files`, `snapshots`, `refs`, `delete_files`), `CALL system.expire_snapshots`,
view probes on a views-disabled catalog (s28), rename errors (s29), missing-table errors, `CREATE TABLE IF NOT EXISTS`,
the per-table `config` / `ReadBridge` overlay (applied inside the backing catalog; the `LoadTableResponse` carries the
overlaid `TableMetadata` object in-process, CPC capabilityMismatch passes), `X-OpenHouse-Client-Capabilities` (CPC
capabilityMismatch, 422 for the non-declaring catalog), authorization 403s (CPC enforcingAuthorization).

## Design note points that turned out wrong or incomplete

- Row G / risk 3 ("CTAS: two stage-creates, empty schema, created-at"): worse than a difference: the server rejects the
  empty-schema stage-create (400 "should contain at least one column"), so CTAS does not work at all through
  `CatalogHandlers` (s04). Answers the "Not verified" item on empty-schema stage-create.
- Row H / "Not verified" (unflagged RTAS commit): the server applies it as an ordinary update when the schema is
  compatible (s23: an ungated `REPLACE TABLE` succeeds; s22: creationTime differs) and rejects it as a schema evolution
  when not (s05). Row I's "same outcome" for CREATE OR REPLACE therefore does not hold through `CatalogHandlers`.
- "Not verified": whether a replace can be told from the request shape ("suggests not reliably"): for Iceberg 1.5.2's
  `RESTTableOperations` it can, deterministically (ADP `isReplace`, `OpenHouseRestAdapterTest`); it is not robust across
  client versions (difference 2).
- Row L2 ("other namespace calls same with rethrow"): wrong for `setProperties` / `removeProperties`:
  `CatalogHandlers.updateNamespaceProperties` describes the namespace first, so the message becomes "Describing
  database is not supported" (CPC, s24).
- §1 "Not needed: ... HEAD (1.5.2 tableExists loads the table instead)": HEAD is needed to keep today's answers for
  `tableExists` (locked tables, row M) and `namespaceExists` (row L); the seam overrides both to send HEAD.
- Row O (FileIO "different class"): understated: `ResolvingFileIO` breaks merge-on-read writes on the LinkedIn fork
  (s10), not only a class difference.
- Row Q (metrics "same with no-op route"): the REST reporter is added next to the configured one (`MetricsReporters.combine`,
  RSC `:417-427`), so it is a second reporter, not the same; the seam turns REST reporting off.
- §1 "build backing once, from the first call's props, named with the Spark catalog name": the client-builder function
  receives only properties, not the name; the backing catalog is built in `initialize(name, props)` instead.
- Row D2 (handler retries 4 times): correct for `CatalogHandlers`; the seam does not use that handler for tables.
- Verified (were "Not verified"): `CatalogUtil` hands the Hadoop conf to `RESTCatalog.setConf(Object)` (the backing
  catalog reads metadata through it); Spark 3.5 CREATE NAMESPACE reaches `loadNamespaceMetadata` on both paths (s19);
  `RESTViewBuilder` with a null location passes `CreateViewRequest.validate()` (s18 passes on rest-shim);
  `table.name()` format is the same (row P).

## Not verified

- Behavior over real HTTP / JSON (no HTTP path exists); the `ReadBridge` overlay surviving JSON serialization.
- Difference 5 (concurrent CTAS) and 9 (OAuth properties), 10 (view replace) are inferred from code, not reproduced.
- Whether Spark cleans the orphan data file of a gated RTAS later (it stays until the table is dropped in the runs seen).
- HDFS (all runs use the local file system); `ResolvingFileIO` was not tried on HDFS.
- Long-running behavior: auth-token refresh, thread-safety of the shared adapter under concurrent Spark jobs.
