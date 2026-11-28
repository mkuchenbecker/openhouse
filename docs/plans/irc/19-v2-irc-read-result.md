# V2: IRC read routes on the tables service

Delegated agent, 2026-11-22. Branch `irc/v2-irc-read-endpoints` in mkuchenbecker/openhouse, based on
`irc-integration` at 4f15a3a3. Merged as pull request 88. Marks: (ran) means run here on JDK 17; (read)
means read from code or spec only.

## Bottom line

An unmodified Iceberg 1.5.2 `RESTCatalog` (LinkedIn 1.5.2.24 artifacts, `uri=<server>/iceberg`,
`token=<bearer>`) works over real HTTP against the embedded service for every V2 route.

## Routes

| Route | Client call that uses it (1.5.2) | Works |
| --- | --- | --- |
| `GET /v1/config` | `initialize` | yes (ran) |
| `GET /v1/namespaces` and `?parent=` | `listNamespaces()`, `listNamespaces(ns)` | yes (ran) |
| `GET /v1/namespaces/{ns}` | `loadNamespaceMetadata`, `namespaceExists` | yes (ran) |
| `HEAD /v1/namespaces/{ns}` | not called by 1.5.2; served for newer clients | wire test only (ran) |
| `GET /v1/namespaces/{ns}/tables` | `listTables` | yes (ran) |
| `GET /v1/namespaces/{ns}/tables/{t}` | `loadTable`, and `tableExists` (1.5.2 loads the table) | yes (ran) |
| `HEAD /v1/namespaces/{ns}/tables/{t}` | not called by 1.5.2; served for newer clients | wire test only (ran) |

For tables written through Spark SQL and the OpenHouse Java client, `RESTCatalog` shows the same schema,
specs, sort order, properties, location, UUID, refs, snapshots, metadata file location, format version and
planned files as the OpenHouse client (ran). Every error case raises the matching Iceberg exception (ran).

## Test counts before and after (ran)

| Suite | At 4f15a3a3 | On the branch | Difference |
| --- | --- | --- | --- |
| `:services:tables:test` | 1268 tests, 0 failed | 1296, 0 failed | +28 new |
| Spark 3.5 itest `catalogTest` | 208, 1 skipped, 0 failed | 219, 1 skipped, 0 failed | +11 new |
| Spark 3.5 itest `statementTest` | 137, 0 failed | 137, 0 failed | none |
| Spark 3.5 itest `test` | 124, 0 failed | 124, 0 failed | none |

## Differences between `RESTCatalog` and the OpenHouse client (all ran)

1. `last-updated-ms`: `RESTCatalog` shows the load time, because Iceberg's builder restamps it when the
   client rebuilds the metadata. The server sends the stored value. Otherwise the JSON is byte-identical.
2. FileIO: `RESTCatalog` uses `ResolvingFileIO`, the OpenHouse client `HadoopFileIO`. The server advertises
   no `io-impl`. Reads work. LinkedIn's delete-file replication patch handles non-Hadoop wrappers badly,
   which matters for V3 (`08-findings.md`).
3. Table operations: `RESTCatalog` commits go to the IRC routes, which answer 406 until V3.
4. `tableExists`: 1.5.2 loads the table. A principal who may not read it gets `ForbiddenException`, and a
   table needing an undeclared capability gets `RESTException` (422). The OpenHouse client answers `true`.
5. `listTables` of a missing database: `RESTCatalog` raises `NoSuchNamespaceException`, the OpenHouse
   client returns an empty list.
6. Namespace calls answer on `RESTCatalog`; the OpenHouse client throws `UnsupportedOperationException`.
7. Column defaults: `RESTCatalog` shows no `initial-default` for a bridged table, and only receives the
   read-bridge config in the load response. The OpenHouse engine catalog overlays it.
8. Exception types differ by client for denied principals, missing capabilities and bad tokens, with the
   same messages. A missing or bad token fails `RESTCatalog` at `initialize`.
9. Metrics: `RESTCatalog` posts every scan report, which answers 406 and is logged and swallowed. The C1
   seam avoids this by advertising `rest-metrics-reporting-enabled=false`.
10. Catalog `properties()` on `RESTCatalog` includes `uri` and the bearer `token`, as is standard.

Equal in every run: table name, location, metadata file location, UUID, refs, snapshots, schema and id,
specs, sort orders, properties (including every `openhouse.*` key), and planned files.

## Choices made where the brief left room

- A namespace exists exactly while the native database list shows it. The native API has no empty databases.
- Multi-level namespaces answer 400. `?parent=` of an existing database returns `[]`, of a missing one 404.
- Listing and existence make no authorization decision, as the native routes do. Load is gated as `getTable`.
- Invalid identifiers answer 400. A missing table, including one in a missing database, answers 404.
- `metadata-location` is the location the native API reports. The metadata is not rebuilt.
- `snapshots=all` or absent returns every snapshot; `refs` keeps those a branch or tag references.
- `pageToken` and `pageSize` are accepted and the full list is returned.
- `warehouse` on `/v1/config` is ignored. Defaults and overrides are empty, so no `prefix` or `io-impl`.
- The native per-table config passes through unchanged, only for a client the gate admits. V5 decides more.
- The capability gate is the native gate. A missing capability gives the native 422.
- Error status is the native status. The type is the Iceberg exception for that status. No stack trace.
- Spec routes not yet served answer 406. Any other path under `/iceberg` answers 400.
- `/iceberg/**` wraps the token interceptor so a refusal is an error body. Native routes are unchanged.
- IRC loads are written to the table audit log as READ. Failed IRC requests are not in the service audit.
- The routes live in `tables.api.irc`. In `tables.controller` they broke the mock-context tests.

## What the write routes will need (V3)

- A base `TableMetadata`: `TablesService.loadTable` provides it. Commits also need V1's complete candidate.
- A mapping of IRC requirements (`assert-table-uuid`, `assert-ref-snapshot-id`) to the native base-version
  check. The house table row's location is spelled differently from the DTO's.
- Error semantics: 1.5.2 maps 409 to `CommitFailedException` and 500, 502, 504 to
  `CommitStateUnknownException`. Native "commit state unknown" is a 503, so the mapping must be chosen.
- A decision on advertising `io-impl` and `rest-metrics-reporting-enabled=false`, or V6, before writes.
- Reserved `openhouse.*` properties must be refused in property updates, as the native PUT refuses them.
- Native rules for stage create, replace, locks, capability checks and table type must hold through IRC.
- Failed IRC requests are missing from the service audit.
- Clients from Iceberg 1.7 on read `endpoints` from config; with none, they assume write routes exist (V10).

## Ran versus read

Ran: the baseline and branch suites above; red runs of the new tests before any production code; spotless
checks on `:services:tables` and the Spark 3.5 itest module. One full service run was stopped by the
session's 30-minute limit while another build ran; the rerun finished in 7 minutes.

Read, not run: the 1.12 spec and its differences from the 1.5.2 spec (`If-None-Match`, `referenced-by`,
`endpoints` in config, a namespace separator override); `RESTSessionCatalog` and `ErrorHandlers` source;
the claim that clients from 1.7 on use `endpoints`.

## Not run

- `:services:tables` against the real MySQL house tables service.
- The Spark 3.1 itest (no `RESTCatalog` path is wanted on the 1.2 line, D3) and the java iceberg-1.5 itest.
- The delta-harness, and a 1.7 or newer `RESTCatalog` against these routes.
- CI on pull request 88 (not checked by the author).
