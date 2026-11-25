# V2: IRC read routes on the tables service

Branch `irc/v2-irc-read-endpoints` (mkuchenbecker/openhouse), based on `origin/irc-integration` at 4f15a3a3. Draft pull request into `irc-integration`: https://github.com/mkuchenbecker/openhouse/pull/88.

## Bottom line

An unmodified Iceberg 1.5.2 `RESTCatalog` (`org.apache.iceberg.rest.RESTCatalog` from the LinkedIn 1.5.2.24 artifacts, loaded with `CatalogUtil.loadCatalog`, `uri=<server>/iceberg`, `token=<bearer>`) works over real HTTP against the embedded service for all V2 routes:

| Route | Client call that uses it (1.5.2) | Works |
| --- | --- | --- |
| `GET /v1/config` | `initialize` | yes (ran) |
| `GET /v1/namespaces` (and `?parent=`) | `listNamespaces()`, `listNamespaces(ns)` | yes (ran) |
| `GET /v1/namespaces/{ns}` | `loadNamespaceMetadata`, `namespaceExists` (1.5.2 has no HEAD call) | yes (ran) |
| `HEAD /v1/namespaces/{ns}` | not called by 1.5.2; served for newer clients | wire test only (ran) |
| `GET /v1/namespaces/{ns}/tables` | `listTables` | yes (ran) |
| `GET /v1/namespaces/{ns}/tables/{t}` | `loadTable`, and `tableExists` (1.5.2 checks existence by loading) | yes (ran) |
| `HEAD /v1/namespaces/{ns}/tables/{t}` | not called by 1.5.2; served for newer clients | wire test only (ran) |

For tables written through Spark SQL and the OpenHouse Java client, the RESTCatalog shows the same schema (and schema id), partition specs, sort order, properties, location, UUID, refs, snapshot list, current snapshot, name, metadata file location, format version and the same planned data files as the OpenHouse client (`loadTableShowsWhatTheExistingClientShows`, ran). Missing table, missing namespace, denied principal, missing or bad token and an undeclared required capability all raise the matching Iceberg exception (ran).

Nothing else moved (ran, JDK 17):

| Suite | `origin/irc-integration` (4f15a3a3) | V2 branch | Difference |
| --- | --- | --- | --- |
| `:services:tables:test` | 1268 tests, 0 failed | 1296 tests, 0 failed | +28 new (`IrcReadRoutesTest` 22, `IrcReadRoutesAuthTest` 6) |
| Spark 3.5 itest `catalogTest` | 208 tests, 1 skipped, 0 failed | 219 tests, 1 skipped, 0 failed | +11 new (`IcebergRestCatalogReadTest`) |
| Spark 3.5 itest `statementTest` | 137, 0 failed | 137, 0 failed | none |
| Spark 3.5 itest `test` | 124, 0 failed | 124, 0 failed | none |

The baseline counts were run here on a separate worktree of 4f15a3a3 and match those recorded in pull request 84. The `:services:tables:test` run is on bddef9fa. The Spark run is on bddef9fa plus the uncommitted form of a2edc223 (the same tests, with a print where a2edc223 has three assertions). After a2edc223, only `IcebergRestCatalogReadTest` was rerun: 11 passed.

## What was built

Commits, oldest first. Every body says why the change exists.

- `5e7a2f5d` test: wire-shape tests at the HTTP boundary (MockMvc, full context). Every body is parsed with Iceberg's own `RESTObjectMapper`, through a test helper in `org.apache.iceberg.rest`, and with `ErrorResponseParser`. Each is checked for the spec's required fields and compared with the native answer. Run before any route existed: 26 of 28 failed with the global "cannot be resolved" 400 (or a 404 without the default token). The 2 that passed pin that native error bodies and the native 401 stay as they are.
- `1f0cbb5c` test: black-box `RESTCatalog` itest in the Spark 3.5 itest `catalogtest` package. Run before any route existed: it failed in setup with `BadRequestException` because `GET /iceberg/v1/config` could not be resolved.
- `d5e33967` feat: `TablesService.loadTable`. It does the same lookup and the same checks in the same order as `getTable`, and returns the `TableMetadata` from the same single catalog load (`OpenHouseInternalRepository.findWithMetadataById`). `getTable` is unchanged; its checks moved into a private method that both call.
- `4b5e6412` feat: the routes, the JSON writer (1.5.2 response classes plus `RESTSerializers`, on an IRC-only mapper), the error mapping and the IRC-scoped advice, plus the catch-all for unserved routes. The controller is `@Hidden` from springdoc, so the generated native clients do not change.
- `642538d6` feat: `/iceberg/**` runs the cluster token interceptor wrapped, so a refusal is an `ErrorResponse`. Native routes keep the unwrapped interceptor.
- `aa701692` feat: an IRC load is written to the table audit log as a READ, as `getTable` is.
- `f8705464` test: three wrong test assumptions corrected after the first green run, and the observed differences pinned.
- `bddef9fa` fix: the controller moved to `tables.api.irc`. The first full service run failed 177 tests in 10 mock-context classes, because `MockTablesApplication` scans `tables.controller` and mocks the API handlers. After the move the suite passed in full.
- `a2edc223` test: three more differences pinned (existence under a denied principal, listing a missing database, column defaults).

## Differences between RESTCatalog and the OpenHouse client for the same table (all ran)

The evidence is in `IcebergRestCatalogReadTest` unless another source is named.

1. **last-updated-ms.** RESTCatalog shows the time of the load, not the stored value. The server sends the stored value: the wire test `loadTableReturnsTheStoredMetadataAtTheNativeMetadataLocation` asserts the `metadata` JSON equals the stored file's JSON. The 1.5.2 client's `LoadTableResponse.tableMetadata()` runs `TableMetadata.buildFrom(m).withMetadataLocation(loc).build()`, and Iceberg's builder stamps `currentTimeMillis` when it rebuilds. With `last-updated-ms` blanked, the rest of the metadata JSON is byte-identical between the two clients (`differencesBetweenTheTwoClients`).
2. **FileIO.** RESTCatalog uses `ResolvingFileIO` and the OpenHouse client uses `HadoopFileIO`. The server advertises no `io-impl` (empty config, per decision 7). Reads worked, and the planned files and record counts match. LinkedIn's delete-file replication patch has a known problem with non-Hadoop FileIO wrappers (`08-findings.md`, second round), which matters for V3 writes.
3. **Table operations.** RESTCatalog's are `RESTTableOperations`, so its commits go to the IRC routes, which answer 406 until V3. The OpenHouse client's are `OpenHouseTableOperations`.
4. **tableExists.** RESTCatalog 1.5.2 checks existence by loading the table. So a principal that may not read the table gets `ForbiddenException`, while the OpenHouse client's existence route answers `true` for the same principal. A table that requires a capability the client does not declare gets `RESTException` (422), while the OpenHouse client answers `true`. The server's `HEAD` route itself behaves like the native one (no authorization, no gate), but a 1.5.2 client never calls it.
5. **listTables of a database that does not exist.** RESTCatalog raises `NoSuchNamespaceException` (the spec's 404). The OpenHouse client returns an empty list.
6. **Namespace calls.** `namespaceExists`, `loadNamespaceMetadata` (returns `{}`) and `listNamespaces(ns)` (returns `[]` for an existing database, `NoSuchNamespaceException` otherwise) all answer on RESTCatalog. The OpenHouse client throws `UnsupportedOperationException` for each. Both list the same top-level namespaces.
7. **Column defaults (read bridge).** Take a table that requires `column-defaults`, loaded by a RESTCatalog that declares the capability (`header.X-OpenHouse-Client-Capabilities=column-defaults`). The engine's OpenHouse catalog shows field `country` with `initial-default "US"`; RESTCatalog shows no default. RESTCatalog receives the read-bridge config in `LoadTableResponse.config`. Iceberg merges that config into a per-table FileIO, where `io().properties()` contains `openhouse.read-bridge.column-default.2="US"`, and applies nothing else.
8. **Exception types.** A denied principal gets `ForbiddenException` from RESTCatalog and `WebClientResponseWithMessageException(403)` from the OpenHouse client. A missing capability gets `RESTException("Unable to process: CLIENT_CAPABILITY_MISSING: ...")` and `WebClientResponseWithMessageException(422)`, with the same message. A missing or bad token fails RESTCatalog at `initialize` (the config call) with `NotAuthorizedException`. A missing table is `NoSuchTableException` on both.
9. **Metrics reports.** RESTCatalog posts every scan report to `POST .../tables/{t}/metrics`. That route is out of scope, so it answers 406, and `RESTMetricsReporter` logs and swallows it. Evidence: 3 stack traces in the itest log ("This server does not support POST /v1/namespaces/irc_read/tables/t_written/metrics yet"). The OpenHouse client sends no reports to the server. The C1 seam avoids this by advertising `rest-metrics-reporting-enabled=false`.
10. **Catalog `properties()`.** RESTCatalog returns its merged configuration, which includes `uri` and the bearer `token`. The same map is the FileIO's `properties()`, so the token sits there too, as is standard for Iceberg.

Equal in every run: table name (`openhouse.irc_read.t_written` when the RESTCatalog is also named `openhouse`; the format is `<catalog>.<db>.<table>` on both), table location, metadata file location, UUID, refs, snapshot list and current snapshot, schema and schema id, specs, sort orders, properties (including every `openhouse.*` key), and planned files.

## Choices made where the brief left room

- **Namespace existence.** A namespace exists exactly while the native database list shows it: it holds a table, or (via the list) only views. Reason: the native API has no empty databases (`getAllDatabases` derives databases from table ids), so an empty database does not exist on either API. A database whose last table is dropped stops being a namespace (wire test). For cost, the check runs the per-database table search first and scans the full database list only when that is empty.
- **Multi-level namespaces** answer 400 `BadRequestException`, matching the existing client's `ValidationException`. `?parent=<existing db>` returns `[]`; a missing parent returns 404; an empty parent lists the top level.
- **Authorization on listing and existence.** None, because the native list, search and exists routes make no decision. Load is `@Secured(GET_TABLE_METADATA)` and runs the service checks, as native `getTable` does.
- **Invalid identifiers** answer 400 `BadRequestException` through the native validators. A missing table, including one in a missing database, is 404 `NoSuchTableException`.
- **Metadata location.** The response's `metadata-location` is the location the native API reports (`tableLocation`). The `metadata` is the object loaded by the internal catalog and is not rebuilt, because rebuilding changes `last-updated-ms` and the metadata log. The field is set on the serialized `LoadTableResponse` tree.
- **`snapshots` parameter.** The 1.5.2 client always sends `snapshots=all` (seen in the service audit log). `all` or absent returns every snapshot. `refs` keeps only the snapshots a branch or tag references and leaves every other field as stored; it is filtered at JSON level so nothing is rebuilt. Any other value is 400.
- **Pagination.** `pageToken` and `pageSize` are accepted and the full list is returned. The 1.5.2 client sends neither, and the 1.5.2 response classes cannot carry `next-page-token`.
- **`warehouse` on `/v1/config`** is ignored. Defaults and overrides are empty (decision 7), so there is no `prefix`, no `io-impl` and no `endpoints`.
- **Per-table config for capable clients.** The native route's `config` (read-bridge keys) is passed through unchanged as `LoadTableResponse.config`, and only for a client the gate admits. Reason: the IRC route gives the same client the same information as the native route, with no new server-side overlay. The cost is that a stock RESTCatalog builds a per-table FileIO from it and applies no defaults (difference 7). V5 decides how capabilities are served over IRC.
- **Capability gating** is the native gate, unchanged. An IRC client that does not send `X-OpenHouse-Client-Capabilities` gets the native 422 and message. The error type is `ClientCapabilityMissingException`, because Iceberg has no exception for 422.
- **Error mapping.** The status is the native status for the same exception; the type is the Iceberg exception name for that status: 400 `BadRequestException`, 401 `NotAuthorizedException`, 403 `ForbiddenException`, 404 `NoSuchTableException` or `NoSuchNamespaceException`, 500 `ServiceFailureException`, 503 `ServiceUnavailableException`. A 422 or 423 carries the OpenHouse exception name. No stack trace is sent.
- **Out-of-scope routes.** Spec routes not served yet (namespace writes, table create, commit, drop, register, unregister, metrics, rename, transactions, views, oauth/tokens, plan/tasks/credentials/sign, functions) answer 406 `UnsupportedOperationException`. 406 is the spec's own `UnsupportedOperationResponse`; a 1.5.2 client raises `RESTException` for it. A path under `/iceberg` that is not an IRC route answers 400 `BadRequestException`, as Iceberg's own test adapter does. Neither is a Spring default page.
- **401 body.** A wrapper runs the same token interceptor on `/iceberg/**` and replaces only the body of its refusal. Native routes keep the unwrapped interceptor and their empty-body 401.
- **Audit.** IRC loads go to the table audit log as READ (an added advice). Successful IRC requests reach the service audit through `@ApiResponses`. Failed IRC requests do not, because the service audit's failure pointcut covers only `common.exception.handler` and `ViewExceptionHandler` and casts to the native error body. Left for later rather than changing common code.
- **Package.** The routes live in `tables.api.irc`. The `tables.controller` package broke the mock-context tests (see `bddef9fa`).

## What V3 (write routes) will need, noticed here

- A base `TableMetadata` from the service layer: `TablesService.loadTable` now provides it with the native checks. Commits will need V1's "commit a complete candidate metadata" to apply `UpdateTableRequest` requirements and updates to that base.
- A mapping from IRC optimistic concurrency (requirements such as `assert-table-uuid` and `assert-ref-snapshot-id`) to the native base-version check (`baseTableVersion` = native `tableLocation`). The HTS row's location is spelled differently from the DTO's: the DTO prepends the storage endpoint and normalizes.
- 1.5.2 error semantics on commit: 409 maps to `CommitFailedException`; 500, 502 and 504 map to `CommitStateUnknownException`. Native "commit state unknown" is a 503, which a RESTCatalog would treat as `ServiceUnavailableException`, not unknown. This needs a deliberate mapping.
- A decision on advertising `io-impl` (HadoopFileIO, as C1 does) and `rest-metrics-reporting-enabled=false` (or V6), before RESTCatalog writes. Otherwise writes go through `ResolvingFileIO`, which LinkedIn's delete-file replication patch handles badly, and every commit posts a metrics report that gets a 406.
- Reserved `openhouse.*` properties appear in the client's metadata, so `SetProperties` and `RemoveProperties` updates on them must be refused as the native PUT refuses them. Native rules for stage create, replace (RTAS), locks, capability (`checkWrite`) and table type must hold through IRC.
- Failed IRC requests are missing from the service audit (above).
- Clients from Iceberg 1.7 on read `endpoints` from config; with none advertised, they assume the default write endpoints exist (V10).

## Ran versus read

Ran, JDK 17 with `--max-workers=2 -Xmx4g`:
- baseline `:services:tables:test` and the Spark 3.5 itest catalogTest, statementTest and test on 4f15a3a3;
- the same suites on the branch, as stated in the table above;
- red runs of the new tests before production code;
- spotlessApply and spotlessCheck on `:services:tables` and the Spark 3.5 itest module.

One full service run on the branch was stopped by the session's 30-minute background limit while another agent's build was running. The rerun after the fix completed in 7 minutes.

Read, not run:
- the 1.12 spec and its differences from the 1.5.2 spec;
- `RESTSessionCatalog` and `ErrorHandlers` source on `li/openhouse-1.5.2`;
- the claim that clients from Iceberg 1.7 on use `endpoints`.

Spec differences for these routes (read): the 1.12 spec adds `If-None-Match` and 304 on load table, `referenced-by` and `read-restrictions` on load, `endpoints` and `idempotency-key-lifetime` in config, a `namespace-separator` override, and wording that an empty `parent` counts as absent (1.5.2 says "not provided or empty"). Both specs define `pageToken` and `pageSize` and `snapshots=all|refs`.

Not run:
- `:services:tables` with the real MySQL house tables service;
- the Spark 3.1 itest (the new test is Spark 3.5 only: no `RESTCatalog` path is wanted on the 1.2 line, per D3);
- the java iceberg-1.5 itest;
- the delta-harness;
- a newer (1.7+) `RESTCatalog` against these routes;
- CI on the pull request (not checked).
