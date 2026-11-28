# Review: Iceberg REST read and config routes, register and metrics, House Tables row limits, small service changes

Slug: `irc-read-register-limits`. PRs 88, 112, 116, 121, 139, 142, 147. Checkout `/home/user/oh-review` at `81b4cd4d`, base `bb8929e1`.
Read-only review: nothing was built or run. Every finding comes from reading the code at HEAD and at base.

## 1. Bottom line

The IRC read, config, register and metrics code is mostly sound and close to the native checks it claims to reuse. Most of the risk is in the three native-path changes that PRs 121, 142 and 139 made without a switch:

1. **The House Tables row-length check (PR 121) is on by default on every native table and view commit, and the default (255) is wrong for the store the service builds itself.** `services/housetables/src/main/resources/application.properties:9` is `spring.sql.init.mode=always`, and `schema.sql` creates `metadata_location VARCHAR(512)`. On every deployment whose House Tables tables came from that bootstrap (the H2 default, the docker/k8s recipes, any MySQL nobody pre-created), a create or commit whose metadata location is 256 to 512 characters worked on LinkedIn main and is now refused with 400. That includes every later commit to an existing table whose location is already in that range, so those tables, and the maintenance jobs that commit to them, stop working after the upgrade. The value cannot be set to "off": a value that is not positive means 255. On the production DDL store (255) the change is the intended one: a late 503/500 with a leftover file becomes an early 400. That is still a status change.
2. **The directory-in-use check (PRs 112 and 116) half rests on a field House Tables never returns.** `uses()` matches a row by `tableUUID` or by location prefix. `user_table_row` has no UUID column (`UserTable` has no such field), so in production the UUID branch never fires. The H2 stand-in stores whole `HouseTable` entities, UUID included, so tests exercise a branch production does not have. The location-prefix branch alone appears sufficient for every case named (rename, soft delete). The UUID half is dead code with an untested claim attached.
3. **Policy tag order (PR 139) changes native GET answers and stored text by default.** This one is small, a set reordered, and documented in the merge message.
4. **Letter case is handled inconsistently by the namespace check (PR 88, kept by PR 147), and the tests cannot see it.** The table probe is case-insensitive in production House Tables (`upper()`), the database-list fallback is exact, and the H2 stand-in is case-sensitive on both. So "same answers in every case including letter case" (PR 147) is only true on the stand-in.
5. **Cruft.** Comments cite private work ids (V14, V17, V20, N8, C15, D19, D20, D30). The Page-schema customiser is duplicated in two services. `HouseTableRowLimits` has unused constructors and a static helper. `@Autowired(required=false)` defaults guard nothing. A redundant `@ConditionalOnClass` sits on classes that only compile on 1.12.

No line of attack found a security hole in register: the path rules, read-after-authorization ordering, storage pinning and traversal guard all hold on reading.

## 2. Defects

### D1. SHOULD FIX (behavior change, see BC1): the row-length default refuses writes the bootstrap store accepts, including every commit to existing long-path tables
- Files:
  - `iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/OpenHouseInternalTableOperations.java:699`: `rejectIfHouseTableRowWillNotFit(properties, newMetadataLocation);`, implemented at lines 716-728.
  - `cluster/configs/.../ClusterProperties.java`: `@Value("${cluster.housetables.metadata-location-max-length:255}")`.
  - `HouseTableRowLimits.java`: `metadataLocationMaxLength > 0 ? metadataLocationMaxLength : DEFAULT_METADATA_LOCATION_MAX_LENGTH`.
- Facts:
  - `services/housetables/src/main/resources/application.properties:9 spring.sql.init.mode=always`.
  - `schema.sql:6 metadata_location VARCHAR (512)`, created with `CREATE TABLE IF NOT EXISTS`. Every store that did not pre-create its tables from `ddl/` has 512.
  - Only the out-of-band production DDL (`ddl/0000__baseline.sql:21`, "applied out of band by the MySQL/DDS team") has 255.
- What goes wrong:
  - On a bootstrap-created store, a native create, PUT, snapshots PUT, rename or lock with a location of 256 to 512 characters is answered 400. LinkedIn main answered 200/201.
  - The check runs on every commit, not only on creates, so an existing table whose location is already in that range cannot be committed to at all after upgrade: Spark writes, retention, compaction and snapshot expiry all fail.
  - The operator's only remedy is to know the column width and set the property. There is no "off".
- Sure: certain from reading.
- Smallest fix: let `0` or a negative value mean "no check" and default to it. That reproduces main; production then sets 255. An alternative: check only creates and renames, not commits whose location length is unchanged from the base. Test: on a 512 stand-in, a table at a 300-character location takes a commit with the property unset.

### D2. MINOR: the UUID branch of the directory-in-use check is dead in production, and the tests exercise it
- File: `services/tables/src/main/java/com/linkedin/openhouse/tables/api/irc/IcebergRestWriteHandler.java:849-855`:
  ```java
  if (tableUUID.toString().equals(row.getTableUUID())) { return true; }
  String location = row.getTableLocation();
  return location != null && withoutEndpoint(storage, location).startsWith(directory + "/");
  ```
  Rows come from `houseTableRepository.findAllByDatabaseId` and `searchSoftDeletedTables`.
- In production these map from `UserTable` (`services/housetables/.../api/spec/model/UserTable.java`), which has no UUID, and `user_table_row` has no UUID column. So `getTableUUID()` is always null.
- `services/tables/src/test/.../e2e/h2/HouseTablesH2Repository.java` is a JPA repository of the full `HouseTable` entity, so tests do see UUIDs.
- Javadoc at `IcebergRestWriteHandler.java:683` and `:804` ("points into or names by UUID") overstates what production checks.
- Impact today: none found. Every case named (rename keeps the directory, soft-deleted keeps its metadata location) is caught by the location prefix, because OpenHouse always writes metadata under the table directory (`rootMetadataFileLocation`).
- Fix: drop the UUID clause and its claim, or make the stand-in drop UUIDs as House Tables does, so tests run the production branch.
- Sure: certain from reading.

### D3. MINOR: the namespace check mixes case-insensitive and exact lookups; letter-case answers are untested against real House Tables
- File: `IcebergRestApiHandler.java:387-403`.
  - `holdsATable` goes through `findAllTablesByFilters` (House Tables `COMMON_FILTER_CLAUSES`, case-insensitive).
  - `requireListedDatabase` is `databaseIds().contains(databaseId)`, an exact string match on `SELECT DISTINCT databaseId`.
- Production consequences:
  - `HEAD /namespaces/DB` answers 204 when `db` holds a table.
  - It answers 404 when `db` holds only views.
  - `GET /namespaces/DB` answers namespace `DB` (the caller's spelling, line 204), while `GET /namespaces/DB/tables` answers identifiers `db.t` (the stored spelling, line 224).
- The stand-in (`HouseTablesH2Repository.findAllByDatabaseId`, derived exact query; soft-deleted `table.getDatabaseId().equals(databaseId)` at line 310) is case-sensitive everywhere. So `IrcHouseTableReadsTest` and the read-route tests cannot observe the production behavior.
- PR 147 kept every answer the same as PR 88, so this is not a PR 147 regression.
- Fix: make `requireListedDatabase` case-insensitive, or answer with the stored spelling. Test against a case-insensitive stand-in.
- Sure: likely (reading the queries; not run).

### D4. MINOR: a race between the two reads of the directory-in-use check
- File: `IcebergRestWriteHandler.java:810-846`. Live rows are read, then soft-deleted rows in pages, as separate House Tables reads.
- A restore of a soft-deleted renamed-away table can move its row from the soft-deleted list to the live list between those reads. It is then in neither read, and a register or staged-create completion of the old name lands in its directory.
- Rename and soft delete are each atomic in House Tables (`UserTablesServiceImpl.renameUserTable` is one conditional UPDATE; `deleteUserTable` is `@Transactional`), so the only window is restore. The window is narrow and the outcome is data loss on a later drop.
- Fix: read soft-deleted rows first, then live rows (a restore moves soft to live, so that order cannot miss it), or re-check after create.
- Sure: likely (reading).

### D5. MINOR: register refuses format v3 metadata even where v3 creates are enabled
- File: `IcebergRestWriteHandler.java:874`. `refuseWhatACreateCannotStore` calls `requireTheClustersFormatVersion("Registration", ...)` unconditionally.
- `commitCreate:485-487` exempts v3 when `createsAtFormatV3`.
- `create()` has a v3 branch (`:568-572`) that register can therefore never reach unless the cluster default is 3.
- Not a regression. It is an unadvertised refusal, and the shared `create()` carries a branch register cannot use.
- Sure: certain from reading.

### D6. MINOR: the per-table `io-impl` can point a client at the wrong class, or override the client's own choice
- File: `IcebergRestTableFileIo.java:263-271`.
  - A table on a storage type with no known class gets no per-table entry. It therefore inherits the catalog default `io-impl` advertised for the default storage (`IcebergRestApiHandler.java:125-126`), the very mismatch the class comment says it avoids.
  - Conversely, when a table's storage class differs from the default, the per-table `io-impl` is laid over the client's own catalog properties. A client that set its own `io-impl` (a wrapping or credential-vending FileIO) loses it for those tables, whereas the catalog-level default is only a default.
- Also, `IcebergRestTableFileIo.config(Table)` calls `FileIOManager.getStorage(table.io())`, which throws `IllegalArgumentException` (mapped to IRC 400) for any FileIO instance not identical to the four managed ones (`FileIOManager.java:83-95`). If any load path ever wraps the FileIO, every load answers 400. Not observed on reading; flagged as fragile.
- Sure: certain on the first two, cannot tell on the third without running.

### D7. MINOR: create, commit and register responses omit the read-bridge config that a load answers
- `loaded()` (`IcebergRestWriteHandler.java:1086-1093`) builds config from `tableFileIo.config` only. `IcebergRestApiHandler.loadTable:252` merges `readBridgeConfigResolver.resolve(tableDto)` first.
- A table registered with `required-capability.column-defaults=true` is handed to the client without its column-default keys until reloaded.
- Low impact: PR 88 notes RESTCatalog does not apply those keys anyway.
- Sure: certain from reading.

### D8. MINOR: the metrics route and every IRC request copy their whole body into the service audit
- `ServiceAuditAspect.auditSuccessfulRequests` fires on `@ApiResponses`, which every IRC controller method carries.
- `CachingRequestBodyFilter` wraps with an unbounded `ContentCachingRequestWrapper` and parses the body into `requestPayload`.
- So any well-formed metrics report of any size, from a principal with read access to one table, lands in the audit stream. The counter tag is bounded (an enum) and the log line is debug-only.
- The same exposure exists on native routes; the new part is a high-frequency route designed for client telemetry. `rest-metrics-reporting-enabled=false` in the config answer mitigates only well-behaved clients.
- Fix: exclude the metrics route from payload capture, or cap the captured size.
- Sure: certain from reading.

### D9. MINOR: the row-name limit ignores the production soft-delete table's narrower column
- `ddl/0000__baseline.sql:63-64`: `soft_deleted_user_table_row.table_id VARCHAR (128)` even in the production DDL, while `user_table_row.table_id` is 255.
- `HouseTableRowLimits` defaults the name limit to 255 and says "only a rename can exceed" 128. On a production store a rename to 129 to 255 characters succeeds, and that table then cannot be soft-deleted.
- Not introduced by these PRs (main had the same gap). The new limit's documentation (ClusterProperties, DEPLOY.md) presents 255 as safe.
- Sure: likely (reading DDL and the soft-delete path's use of that table; not run).

No defect found in:
- register path rules: exact directory match after endpoint stripping; `/../` refused; other schemes or hosts fail the string compare; only the selected storage's FileIO reads.
- read-before-authorization: the name check (409, as natively), then `checkDatabasePrivilege`, then path and rows, then the file read.
- creator: the acting principal; `openhouse.*` keys from the file are dropped.
- load authorization: `TablesServiceImpl.checkLoad` in native order.
- the endpoint list: built from handler mappings, so advertised equals served by construction.

## 3. Behavior changes against LinkedIn main

| # | What existed | Before (base file:line) | After (HEAD file:line) | PR | Default on? | Justification in code/notes | Documented for follow-up | Upstream would accept as is | Size to restore or gate |
|---|---|---|---|---|---|---|---|---|---|
| BC1 | Native table create, commit, snapshots PUT, rename and lock where the metadata location is longer than 255 | `OpenHouseInternalTableOperations.java:426` (no check): file written, then row write; on a 255 store a late failure (503/500 claimed in the merge message, file left), on a 512 bootstrap store success | `:699`, `:716-728`: 400 `CommitRejectedException` before any write, for any length > `cluster.housetables.metadata-location-max-length` (default 255) | 121 | Yes, no off value | ClusterProperties javadoc; DEPLOY.md "Metadata location length limit"; merge "Known gaps: Behaviour change: on a ... schema.sql (512) ... refused by default now" | DEPLOY.md only; no follow-up item | No: a default that breaks the bootstrap store, with no off switch | SMALL (default 0 = off; one class, the property, tests) |
| BC2 | Native view create and replace with a location longer than 255 | `ViewCommitEngineImpl` at base: no check, file then row | `ViewCommitEngineImpl.java:140, 172, 285` plus `ViewFailureTranslator` maps to `INVALID_VIEW_DEFINITION` 400 | 121 | Yes, where native views are enabled | Same as BC1 | Same | Same as BC1 | SMALL (same switch) |
| BC3 | Native rename to a name longer than 255 on a production store | Late HTS failure after the metadata file is written | 400 before the write (`OpenHouseInternalTableOperations.java:724-726`); limit is `cluster.housetables.table-id-max-length`, default 255 | 121 (constant), 142 (property) | Yes | DEPLOY.md "Table name length limit" | Yes (DEPLOY.md) | Likely: turns a late 5xx with a leftover file into a 400 | SMALL |
| BC4 | Native GET table answer: order of `columnTags.*.tags` | `PolicyTag.java:14 @Getter`: stored order (Gson `LinkedHashSet`) | `PolicyTag.java:52-54`: sorted PII, HC on every read and write | 139 | Yes | PolicyTag javadoc; merge "Behaviour changes: N8" | Merge message | Likely (a set; order was per-process) | SMALL |
| BC5 | Stored `policies` text: tag order on write | Jackson `HashSet` order, per process | Declaration order (Gson `@JsonAdapter(InDeclarationOrder)`) | 139 | Yes | Same | Same | Likely | SMALL |
| BC6 | Native PUT whose only policy difference is tag order, carried with other changes | `TablePolicyManager.java:70`: string compare, policies rewritten | `:72-74`: no rewrite when equal after reordering (a commit whose only change was tag order was already skipped on main by `updateNeeded` set equality) | 139 | Yes | Code comment | Merge message | Yes | SMALL |
| BC7 | Generated `tables.json` and `housetables.json`: property order of the `Page<...>` schemas, and therefore the generated clients' Page models (`@JsonPropertyOrder`, `hashCode`) | JVM-dependent order | Alphabetical (`MainApplicationConfig.pageSchemaPropertyOrderCustomizer`, `HouseTablesSpringApplication.pageSchemaPropertyOrderCustomizer`) | 139 | Yes (build output) | Javadoc; merge "C15" | Merge message | Likely: order only, and main's order was not stable | SMALL |
| BC8 | Service audit `serviceName` for `/iceberg/...` URIs | `ServiceAuditAspect.java:200`: UNRECOGNIZED | `:257-259`: TABLES_SERVICE | 121 | Yes (but no `/iceberg` route existed on main) | Javadoc | No | Yes | n/a (new routes) |
| BC9 | Requests under `/iceberg/**` | Native token interceptor, then 404 | Served by the IRC controllers; native interceptor excluded, IRC wrapper added (`TablesMvcConfigurer.java:45-58`) | 88 | Yes, with no switch at all | Merge "additive under /iceberg" | No | Probably wants a switch upstream | SMALL (`@ConditionalOnProperty` on the IRC controllers) |
| BC10 | View beans' class guards; `ViewsDisabledService` | `@ConditionalOnClass(ViewMetadata)` on `ViewsServiceImpl`, `ViewCommitEngineConfiguration`; `ViewsDisabledService` when absent | Unconditional; disabled service deleted | 139, 142 | n/a on remaining lines | Merge V18/V22 | Yes | Yes, but only because the 1.2 fixtures artifact `tables-test-fixtures_2.12` was deleted by PR 118, where that service was live (native view routes answered 404 "Views are disabled" there) | n/a here; belongs to PR 118's review |

Every pre-existing file these PRs changed, accounted for:
- `TableAuditAspect.java` (88): adds advice only on IRC-only methods (`IcebergRestApiHandler.loadTable`, `TableCommitService.commit(…5 args)`, `TablesService.createTable(…UUID)`, `stageCreateTable`). Native pointcuts are unchanged. Native `putTable` calls the private overload, which is not advised. No change.
- `TablesMvcConfigurer.java` (88): BC9.
- `OpenHouseInternalRepository.java` and `Impl` (88): adds `findWithMetadataById`, the same load as `findById`. No native change. (121) adds `stageCreate`'s row check; `stageCreate` is IRC-only (not on base).
- `TablesService.java` and `TablesServiceImpl.java` (88): `getTable` refactored into `checkLoad` in the same order (lock, privilege, system lock, capability). Checked by reading. The `putTable` commit-core refactor in the same file belongs to another PR and reviewer.
- `ClusterProperties.java` (121, 142): two new properties; BC1 and BC3.
- `OpenHouseInternalCatalog.java` (121): passes limits to ops. Same as BC1.
- `OpenHouseInternalTableOperations.java` (121, 142): BC1 and BC3. `prepareCommit` now runs after `newMetadataLocation` is computed. Moving `version` and `newMetadataLocation` above it has no side effect (the UUID is random either way).
- `ViewCommitEngineConfiguration.java` and `ViewCommitEngineImpl.java` (121, 139): BC2 and BC10.
- `ServiceAuditAspect.java` (121): the native event builder was split into two methods with the same fields and order. BC8. No native change otherwise; checked by reading.
- `OpenHouseExceptionHandler.java` (121): two private helpers made `public static`. Proxy-based Spring AOP (no weaving in the build) means the `handler.*.*` pointcut does not start advising them. No change.
- `ViewFailureTranslator.java` (121): BC2.
- `DEPLOY.md` (121, 142): documentation of BC1 and BC3.
- `HouseTablesSpringApplication.java` and `MainApplicationConfig.java` (139): BC7.
- `PolicyTag.java`, `PoliciesSpecMapper.java`, `TablePolicyManager.java` (139): BC4 to BC6.
- `ViewsServiceImpl.java`, `ViewAuditAspect.java`, deleted `ViewsDisabledService.java` (139, 142): BC10.
- Tests (`OpenHouseInternalTableOperationsTest`, `DefaultClusterPropertiesTest`, `ViewsServiceBeanWiringTest`, `ViewsPaginationControllerTest`, deleted `ViewsDisabledServiceTest`): test-only.

## 4. Cruft

Comments citing this effort's private ids:
- `IcebergRestApiHandler.java:384` "(V17)".
- `IcebergRestTableFileIo.java:17` "(work item V20)".
- `IcebergRestWriteHandler.java:77, 90, 230, 351, 465, 504, 681, 1063` (D30, V14, D19, D20).
- `PolicyTag.java:32` and `PoliciesSpecMapper.java:159` "(work item N8)".
- `MainApplicationConfig.java:200` and `HouseTablesSpringApplication.java:33` "(work item C15)".
- `HouseTableRowLimitsTest` "(work item V19)".
- `ReadBridgeConfigResolver` "(work item W6)".
- Action: rewrite each to explain the code.

Two implementations of one thing:
- `pageSchemaPropertyOrderCustomizer` is copied verbatim into `MainApplicationConfig` and `HouseTablesSpringApplication`. Move it to `services/common` (BaseApplicationConfig).
- `tableUUID(...)` (`IcebergRestWriteHandler.java:618-650`) and `allocatedUUID(...)` (`:754-795`) parse `<table>-<uuid>` and compare with the allocation in two near-copies. Merge them.

Unused code and parameters:
- `HouseTableRowLimits.requireTableIdFits` (static) is unused in main sources.
- The constructors `HouseTableRowLimits(int)` and `HouseTableRowLimits(ClusterProperties)` are reachable only from tests.
- The `@Named("withTagsInDeclarationOrder")` annotation on a method no MapStruct mapping references (`PoliciesSpecMapper.java:167`).
- `IcebergRestCatalogController.getConfig(warehouse)`, `listNamespaces(pageToken, pageSize)` and `listTables(pageToken, pageSize)` take parameters that are ignored. Keep the ignoring, which the spec allows, but drop the bound parameters or document them in one place.
- The UUID branch of `uses()` (D2).

Switches that guard nothing:
- `@Autowired(required = false) HouseTableRowLimits ... = DEFAULT` in `OpenHouseInternalCatalog.java:77`, `OpenHouseInternalRepositoryImpl.java:135` and the `ObjectProvider.getIfAvailable` in `ViewCommitEngineConfiguration.java:39`. `HouseTableRowLimits` is a `@Component` in a scanned package. These exist for tests built without context.
- The mutable `operations.houseTableRowLimits = ...` field injection into `OpenHouseInternalTableOperations` would be cleaner as a constructor argument.
- `ViewCommitEngineImpl` carries both `@AllArgsConstructor` and `@RequiredArgsConstructor` to keep a defaulted non-final field.

Two-line straddling:
- `@ConditionalOnClass` on `IcebergRestScanController` and `IcebergRestRegisterViewController`, which live in `src/iceberg-1.12` and only compile where the class exists. Redundant; remove.
- `IcebergRestJson.config` writes `endpoints` at the JSON level because 1.5.2 lacks `Endpoint`. Still needed while the 1.5.2 line exists.
- `UNSERVED_SPEC_ROUTES` entries for register-view, plan and credentials exist only for the 1.5.2 line. Still needed there.

Annotations used for a side effect:
- `@ApiResponses` on every `@Hidden` IRC method exists only so `ServiceAuditAspect`'s `@annotation(apiResponses)` pointcut audits successes. Name the coupling, or give the audit a pointcut on the IRC package.

Narration and history in comments:
- `PolicyTag` javadoc "Before, the order was a HashSet's...".
- The `IcebergRestApiHandler.config` javadoc's history of the 1.5.2 client behavior.
- `IcebergRestCatalogController` "(yet, or on this Iceberg line)".

## 5. Tests

Claims no test would catch if false, or that are tested only against a stand-in that differs from production:
- Letter-case parity of namespace answers (PR 147 "every answer unchanged ... including view-only databases and letter case"). The stand-in is case-sensitive; production House Tables is not (D3).
- The directory-in-use check by UUID (PRs 112 and 116). It passes in tests because the stand-in keeps `tableUUID`; production rows have none (D2).
- "that the paged and unpaged queries of the real store return the same rows" (PR 147): confirmed by reading. Both go to `findAllTablesByFilters` with the same `COMMON_FILTER_CLAUSES AND TABLE_ROW_PREDICATE` (`UserTableHtsJdbcRepository.java:142-170`). Holds.
- `IrcHouseTableReadsTest` counts reads against the H2 stand-in. The real `HouseTableRepositoryImpl` read paths (retry template, `getUserTables` vs `getPaginatedUserTables`) are not exercised.
- PR 121 "native 503 or 500" before the fix on a 255 store: the tests ran on H2 with Hibernate's default VARCHAR(255). That is plausible but not the MySQL store. On MySQL in non-strict mode, the old behavior is silent truncation (a corrupt pointer), not an error. Nobody checked.
- No test covers an existing table whose location is 256 to 512 characters on a 512 store taking a commit after upgrade with the property unset. That is the BC1 regression, and it is unpinned.
- PR 142 V20 was run end to end in one direction only (stated in the merge message). The `IllegalArgumentException` path of `FileIOManager.getStorage` is untested.
- PR 139 "the spec is equal as JSON to before": no checked-in spec and no test compares HEAD's `tables.json` to base's. Spec content changes from IRC (`@Hidden`) and from `IcebergRestExceptionHandler` (a `@RestControllerAdvice` with no `@ResponseStatus`) were not verified by generation.

Pins of defects:
- `NativeCreateDirectoryReusePinnedDefectTest` pins native data loss (a create into a renamed table's directory, whose drop deletes the other table's files). Its description matches the code: `TableUUIDGenerator.validatePathOfProvidedRequest` checks only that a file exists at `<root>/<propsDb>/<propsTable>-<uuid>`; replica and replicated tables skip it; the snapshot PUT takes the UUID from the manifest list. `OpenHouseInternalCatalog.dropTable` is `deletePrefix(tableLocation)`. The pin is still right while the defect is unfixed. It is a real data-loss defect on LinkedIn main and deserves an upstream issue rather than only a pin.
- `TableIdConfiguredLimitTest` first pinned the late 500. It now asserts the fix. Fine.

Removed tests:
- PR 139 removed `IcebergRestBeanSignaturesTest`, one `IcebergRestRoutesBoundaryTest` case and eight `IcebergViewBeanBoundaryTest` cases. PR 142 removed `ViewsDisabledServiceTest` and wiring tests. Their subject was gone (1.2 guards), so the removal is consistent.
- The claim "13 catch-all 406 entries the more specific mappings always pre-empted" is reasoned, not tested (stated in the merge message).

## 6. Per pull request

| PR | "Leaves true" holds? | Why |
|---|---|---|
| 88 | Partly | Routes, load-check order, errors, wrapper interceptor and READ audit hold on reading. "Config returns empty defaults" was later changed (PRs 112, 121). The namespace case handling is inconsistent (D3), which the claim "exists while the native database list shows it" glosses over: a table probe answers first, case-insensitively. |
| 112 | Partly | Metrics route as described. Register refusals and ordering hold. "A directory a live or soft-deleted row uses" holds through the location prefix only; the UUID half is dead in production (D2). "Register is served only where the write routes are; the 1.2 runtime keeps 406" is moot now. |
| 116 | Yes / partly | The IRC completion check and the ignoring of `openhouse.tableUUID` hold. "The native API is unchanged; no native class is touched" holds for this PR. The pinned native defect is described correctly. The two-read race (D4) is not mentioned. |
| 121 | Partly | The check order and the 400s hold. "Format v3 included" holds. The audit of IRC failures holds (the native aspect is untouched apart from a refactor). The stated behavior change is real and on by default, and it reaches existing tables on every commit, a scope the merge message understates ("a write whose location is 256 to 512 characters worked before"). `io-impl` per storage type holds. |
| 139 | Yes | Tag order, set semantics and no rewrite on reorder hold on reading. Spec "only Page order changes": cannot tell by reading (not generated). V18 guard removal holds. |
| 142 | Yes / partly | V19 holds (default 255; 128 honored). V20 holds for the documented cases; D6 edge cases. V22 holds on remaining lines; the deleted 1.2 artifact is PR 118's. |
| 147 | Partly | Same filter, one-row probe and one listing fewer hold. "Every answer unchanged" holds on reading against production queries. The letter-case evidence comes only from a case-sensitive stand-in. The "created between listing and check" caveat is stated. |

## 7. For the clean re-implementation

Essential design to keep:
1. IRC routes under `/iceberg`, `@Hidden` from springdoc, with their own `ObjectMapper` (Iceberg's `RESTSerializers`) and their own exception advice scoped to the IRC package, so native JSON and error bodies are untouched.
2. One switch (`@ConditionalOnProperty`, off by default upstream) for the whole IRC surface.
3. Token interceptor reuse through a wrapper that only rewrites the refusal body.
4. Load equals native `getTable` checks in native order from one catalog load (`findWithMetadataById` plus `checkLoad`). Metadata served as stored, `metadata-location` from the row.
5. Namespaces are databases, one level. Existence is a one-row paged table probe, then the database list. Make both lookups the same case rule.
6. List and exists without authorization, exactly as native. Paging parameters accepted and ignored.
7. The endpoint list derived from Spring handler mappings, so advertised equals served.
8. Config: `rest-metrics-reporting-enabled=false` and `io-impl` by default storage as defaults (not overrides); capabilities as an override.
9. Metrics: authorize on the row (read privilege), count by type, debug log, 204. Exclude the body from audit payload capture.
10. Register as a native create read from the file: the file must lie directly in the directory the server allocates for that name and UUID; its location must equal that directory; manifest lists must lie inside it; no live or soft-deleted row may point into it (by location); the privilege is checked before any row or file is read; reserved properties are dropped; the caller is the creator.
11. A House Tables row-width check before the metadata write, but opt-in (off by default), configured per column, and on creates and renames rather than every commit (or every commit only when a width is configured).
12. Policy tags in declaration order on write. Reads may keep stored order, or sort, but decide one.
13. Deterministic OpenAPI output via one shared customiser in services/common.

Do not carry over:
- The UUID branch of `uses()`.
- The `required=false` defaults.
- Field injection of limits into table operations.
- Static `requireTableIdFits` and the test-only constructors.
- Work-id comments.
- The duplicated customiser.
- Redundant `@ConditionalOnClass` in `src/iceberg-1.12`.
- `@ApiResponses` used only as an audit trigger without saying so.
- Two parsers of `<table>-<uuid>`.

Commit sequence for upstream (each one idea):
1. refactor: `TablesServiceImpl.getTable` extracts `checkLoad`; add `findWithMetadataById` / `loadTable` (no behavior change).
2. refactor: `ServiceAuditAspect` builder split, plus `auditFailedRequest(...)` for handlers with other error bodies (no behavior change).
3. fix (behavior, documented): policy tags written in declaration order; reorder-only is not a change.
4. build: deterministic Page schema order (shared customiser).
5. feat (off by default): `cluster.housetables.metadata-location-max-length` and `table-id-max-length`, 0 = no check; the check in `prepareCommit` and the view commit engine.
6. feat (switch off): IRC read routes, config, namespaces, list, exists, load; IRC error advice; interceptor wrapper; READ audit; endpoint list from mappings.
7. feat: IRC metrics route.
8. feat: IRC register, with the shared directory rules and a test stand-in that drops UUIDs like House Tables.
9. feat: per-table `io-impl` in IRC table responses.
10. issue or test: the native create directory-reuse defect, reported with the pin, fix proposed separately.

## 8. Not checked

- The write, commit, staged-create, rename and drop logic of `IcebergRestWriteHandler` beyond what register and completion share; view routes; scan planning. These belong to other reviewers.
- `TablesServiceImpl.putTable` and the commit-core refactor (`TableCommitCore`), in the same file as PR 88's change but from other PRs.
- Generating `tables.json` and `housetables.json` at base and HEAD to diff content. Nothing was built; the specs are not checked in.
- Real House Tables on MySQL (strict vs non-strict truncation of over-long values), S3 and ADLS FileIO, and a real client reading through the advertised classes.
- Whether native views are enabled by default per database (it affects BC2's reach).
- Deletion of the Iceberg 1.2 fixtures artifact `tables-test-fixtures_2.12` (PR 118), where `ViewsDisabledService` was live.
- `IcebergRestErrors` mapping completeness beyond the statuses used by the routes reviewed.
- Test bodies were sampled by name and assertion lines, not read end to end, apart from the pinned-defect test's header and the stand-in repository.
