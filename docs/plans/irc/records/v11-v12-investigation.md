# V11 (IRC scan planning) and V12 (credential vending, remote signing): investigation
Sources: OpenHouse `irc-integration` at 72947228; Iceberg fork at a88f556c5, the commit pinned in
`scripts/iceberg-fork/iceberg-fork.properties` (Iceberg paths below are relative to the fork root, `rest/` meaning
`core/src/main/java/org/apache/iceberg/rest/`). [read] = read in code there; [inferred] = my reasoning. Nothing was run.

## Bottom line
- Neither item moves the compatibility kit: none of the 96 failures is about scan planning or credentials, and the kit
  has no test for either (grep of the kit, `CatalogTests`, `ViewCatalogTests`) [read]. Of the 96, 77 are OpenHouse
  rules (class b), 9 are unserved routes (namespace create/drop 7, view rename 2), and 10 are kit assumptions (class e).
  The count "conforming" would need is mostly D32 policy, not V11/V12 work.
- V11 is cheap and safe. A 1.12 client plans on the server only when `scan-planning-mode=server` is set in the
  table's load config or the client's catalog properties; the default is `client`. Listing the endpoint alone changes
  nothing [read]. The smallest valid step: a synchronous `POST .../plan` that answers `completed` with every task in one
  response, keeps no state, and is listed in `endpoints`. Do not reuse `CatalogHandlers.planTableScan` as it stands:
  it keeps state in a JVM-wide static map, and it cuts the file list at `min-rows-requested` files [read].
- V12 cannot be built properly from what the repository has. OpenHouse has no credential it could scope down: S3 and
  ADLS use the cluster's long-lived keys from cluster config, and HDFS uses the service's own delegation token
  [read]. Real vending needs a new external dependency (AWS STS with an IAM role, an Azure user-delegation SAS), and a
  new public contract for who gets which access. Those are owner decisions. HDFS cannot be vended to any 1.12 client:
  `HadoopFileIO` accepts no credentials [read]. The only step that is free of those decisions: serve
  `GET .../credentials` returning an empty list and honor the header by vending nothing, which the spec allows.

## Kit failures by cause (96 FAILED, from `expected-outcomes.txt`) [read]
| Cause | Tags (count) | Failed |
|---|---|---|
| Namespace has no independent existence (a view cannot create its database; empty-namespace listing is 404) | VIEWDB 24, LISTNS 2 | 26 |
| Namespace create/alter/drop not served (406) | NSWRITE 7 | 7 |
| View rename not served (406) | VRENAME 2 | 2 |
| View policy: Spark dialect only, reserved view properties | DIALECT 13, DIALECTRES 2 | 15 |
| Table policy: replace needs `replace.enabled` | RTAS 16 | 16 |
| Schema and partition evolution rules | SCHEMA 4, SPEC 9 | 13 |
| Other model differences: format v1 refused, ids with hyphens, create history, drop always purges | FMT 2, IDS 3, CREATEHIST 1, DROPPURGE 1 | 7 |
| Kit asserts reference message text, or 404 versus 500 on a missing metadata file | MSG 8, NOTFOUND 1 | 9 |
| Kit's own contradiction under `requiresNamespaceCreate=false` | KITNS 1 | 1 |
| Scan planning | none | 0 |
| Credentials or signing | none | 0 |

Total 96 (b 77, c 9, e 10); the 23 skipped are 22 capability flags and 1 kit `@Disabled`; each test is classed by the
first step it fails [read, header lines 9-16]. Serving namespace create would not fix VIEWDB unless
namespaces could also exist empty, which is an OpenHouse model change (D20) [inferred].

## V11: scan planning
1. Spec [read, `open-api/rest-catalog-open-api.yaml`]. `POST .../tables/{table}/plan` (planTableScan, 707-794): the
   body is `PlanTableScanRequest` (5696-5750: snapshot-id, select, filter, case-sensitive, use-snapshot-schema,
   start/end-snapshot-id for incremental, stats-fields, min-rows-requested, which is a hint the server may ignore). It
   answers `completed` (tasks inline; `plan-id` is required by `CompletedPlanningWithIDResult`, 4345-4356),
   `submitted` (asynchronous, plan-id only), or `failed`. `GET .../plan/{plan-id}` (fetchPlanningResult, 796-860) and
   `DELETE .../plan/{plan-id}` (cancelPlanning, 865-915) exist for asynchronous plans. `POST .../tasks`
   (fetchScanTasks, 919-965) takes one opaque `plan-task` and returns more tasks, for pagination. Results carry
   `file-scan-tasks` (data-file, delete-file-references, residual-filter, 5783-5800), `delete-files` (including
   position deletes with content-offset, which is how v3 deletion vectors appear, 5653-5680), `plan-tasks`, and an
   optional `storage-credentials` (4334-4343).
2. Client [read]. `RESTSessionCatalog.restTableForScanPlanning` (`rest/RESTSessionCatalog.java:650-706`): server mode
   only if the table config says `scan-planning-mode=server` or the catalog property does (default CLIENT,
   `rest/RESTCatalogProperties.java:54-55`); server config wins (663-681). In server mode it fails fast unless
   `POST .../plan` is listed (684-688). `RESTTable` overrides only `newScan`/`newBatchScan`, and
   `allowDistributedPlanning()` is false (`rest/RESTTable.java:68-92`). Incremental and changelog scans and metadata
   tables still plan on the client [read; inferred for changelog]. `RESTTableScan.planFiles` (`rest/RESTTableScan.java:167-202`)
   sends the request. On `completed` it iterates inline tasks, calls `/tasks` for plan-tasks, and polls
   `GET .../plan/{id}` on `submitted` (209-224, 240-330). Cancel happens only if DELETE is listed (378). The scan's
   FileIO is the table's own unless credentials come back (213-214).
3. Spark [read]. On Spark 3.5 and 4.2 under Iceberg 1.12, `SparkScanBuilder.newBatchScan` calls
   `table.newBatchScan()` when distributed planning is off (`spark/v3.5/spark/src/main/.../SparkScanBuilder.java:822-828`,
   `SparkReadConf.java:367-370`), so SELECT, and the copy-on-write scans of MERGE/UPDATE/DELETE, go through the server.
   Spark pushes LIMIT as `minRowsRequested` (`SparkScanBuilder.java:806`; v4.2 `BaseSparkScanBuilder.java:271`). The
   fork already runs Spark's whole `TestSelect` suite with server planning (`spark/v3.5/spark-extensions/src/test/.../TestRemoteScanPlanning.java`,
   same for v4.2). The `-PicebergLinkedIn` 1.5.2 line has no server planning at all [inferred].
4. Reusable server pieces [read]. `CatalogHandlers.planTableScan/fetchPlanningResult/fetchScanTasks/cancelPlanTableScan`
   (`rest/CatalogHandlers.java:812-937`), `configureScan` (946-966), the request and response classes and their
   serializers, which `IcebergRestJson` already registers (`RESTSerializers`, IcebergRestJson.java:51). The response
   builder derives `delete-files` from the tasks (`rest/responses/BaseScanTaskResponse.java:84-88`).
   `ContentFileParser` writes `referenced-data-file`, `content-offset` and `first-row-id` for v3 (`ContentFileParser.java:128-138`).
   Two hazards: (a) all state is in a static `InMemoryPlanningState` (CatalogHandlers.java:117). Even the synchronous
   path stores every task there (planFilesFor, 981-1023), and it is removed only by cancel
   (`InMemoryPlanningState.java:135-137`). That is a memory leak, and it is wrong behind more than one service
   instance [inferred]. (b) `Iterables.limit(planTasks, minRowsRequested)` (line 1000) cuts the list to N *files*, so a LIMIT
   over files whose rows are all deleted could return fewer rows than client planning [read; consequence inferred].
5. What the service supplies [read and inferred]. A route in `IcebergRestCatalogController`, secured like load
   (`@Secured(GET_TABLE_METADATA)`, IcebergRestCatalogController.java:89-104), that runs `TablesServiceImpl.checkLoad`
   (lock, privilege, lock access, capability; TablesServiceImpl.java:97-107). It also needs a live Iceberg `Table`: today
   the repository loads one (`OpenHouseInternalRepositoryImpl.java:1041-1057`) and returns only its metadata, so a new
   repository or service method is needed to hand back the `Table` with its per-storage FileIO. Plan ids: a random
   id, needed only because the spec requires one. Nothing in the service resolves `select` names or the residual;
   Iceberg's scan does that. Paths in the response are what load already exposes, so no new authorization is needed
   [inferred]. Cost moves to the service: manifests are read on the service's heap, so a bound (a task cap answered
   400/`failed`, or stateless plan-tasks that encode a manifest range) is a design choice [inferred].
6. If advertised [read and inferred]. Registering the route lists it automatically (`IcebergRestServedRoutes.read`,
   86-124) and takes it out of the 406 catch-all (IcebergRestCatalogController.java:156-160).
   `IrcConfigEndpointsTest` pins the served list (services/tables/src/test/.../IrcConfigEndpointsTest.java:85-115), so
   it must be updated. Nothing in the service sets `scan-planning-mode` (`ReadBridgeConfigResolver.resolve` sends only
   column defaults, 52-58), so no client changes path. The one change: a client already configured with
   `scan-planning-mode=server` now plans on the server instead of failing fast. A 1.5.2 client ignores `endpoints`.

## V12: credential vending and remote signing
7. Spec [read]. Header `X-Iceberg-Access-Delegation: vended-credentials,remote-signing` (yaml 2151-2173); "the server
   may choose to supply access via any or none". `LoadTableResult` gains `storage-credentials` (prefix plus config),
   `remote-signing-config`, and config keys `s3.access-key-id`/`s3.secret-access-key`/`s3.session-token`/
   `s3.remote-signing-enabled` (4224-4285). `GET .../credentials?planId=` returns `LoadCredentialsResponse` (1362-1405,
   4162-4170). `POST .../sign` is the S3 signer route (1408 onward).
8. Clients [read]. `RESTSessionCatalog.tableFileIO` builds a table FileIO from table config plus credentials plus the
   signing endpoint (`rest/RESTSessionCatalog.java:1312-1341`). Credentials reach only FileIOs that implement
   `SupportsStorageCredentials`: S3FileIO (`aws/.../s3/S3FileIO.java:113, 387-472`, per-prefix clients, refresh),
   GCSFileIO, ResolvingFileIO. `ADLSFileIO` does not (`azure/.../adlsv2/ADLSFileIO.java:50`): it takes
   `adls.sas-token.<account>` from config (`AzureProperties.java:43,106`) and refreshes through
   `adls.refresh-credentials-endpoint` (85-89, 130). `HadoopFileIO` takes none (`core/.../hadoop/HadoopFileIO.java:48`).
   S3 refresh uses `client.refresh-credentials-endpoint` (`aws/.../AwsClientProperties.java:81-124`), and signing uses
   `S3V4RestSignerClient` when `s3.remote-signing-enabled=true` (`S3FileIOProperties.java:294`). No 1.12 client sends
   the header unless configured to (no main-code reference found) [read; that it is set with `header.` properties is inferred].
9. OpenHouse today [read]. Storage clients use the service's own identity: S3 from `cluster.storages.s3.parameters`
   through `AwsClientFactories.from` (`cluster/storage/.../s3/S3StorageClient.java:29-32`), with static keys in the
   recipes (`infra/recipes/docker-compose/oh-s3-spark/cluster.yaml:10-13`). ADLS from the storage parameters
   (`AdlsStorageClient.java:26-30`). HDFS reloads its own delegation-token file
   (`cluster/storage/.../hdfs/HdfsDelegationTokenRefresher.java`). No STS, SAS minting, presigner or signer exists in the
   service or the native client (grep). Engines bring their own storage credentials: SETUP.md:338-339 has Spark pass an ADLS
   shared key, and the V3b record says "credentials stay the client's configuration" (`59-v15-v3b-v3c-result.md:25`).
   OpenHouse's `SELECT` privilege is defined (`Privileges.java:19,46`) but no service code checks it.
10. Per storage [inferred unless marked]. **hdfs**: vending means issuing HDFS delegation tokens for the caller.
    No Iceberg 1.12 FileIO can take one, Spark gets its own through Kerberos, and remote signing is S3 only. Not
    possible as spec vending, so declare it not applicable. **local**: nothing to vend; test-only. **s3**: real vending
    needs STS AssumeRole with a session policy scoped to the table prefix (a new AWS SDK STS dependency, an IAM role,
    a secret). Handing out the cluster's static keys would vend root access and must not be done. Remote signing is
    possible without STS (the service signs with its own key, and the policy check is a path-prefix test), but it puts
    every data request through the service. Testable here: only against MinIO, which exists in docker-compose recipes
    only (`infra/recipes/docker-compose/common/s3-services.yml`); the service's S3 tests are Mockito mocks
    (`services/tables/src/test/.../storage/s3/S3StorageTest.java:22-24`) [read]. Signing could be tested in-JVM with
    Iceberg's `RemoteSignerServlet` pattern (`core/src/test/.../rest/RemoteSignerServlet.java`) plus MinIO. **adls**:
    needs a user-delegation SAS (Entra identity) or an account-key SAS (a secret). Azurite exists only in
    `iceberg/azure` tests (`AzuriteContainer.java`) [read].
11. Smallest valid increment: `GET .../credentials` answering `{"storage-credentials":[]}` after the load checks,
    plus documentation that the header is accepted and nothing is vended. Valid under "any or none". Its value is
    low; it only makes the route exist [inferred].

## Proposed work items
| Id | Depends on | Done when | Black-box test (written first) | Door |
|---|---|---|---|---|
| V11a | V9, V2 | `POST .../plan` served synchronously, `completed`, all tasks inline, random plan-id, no server state, `min-rows-requested` ignored, same checks as load; listed in `endpoints`; nothing sets `scan-planning-mode` | IRC client with `scan-planning-mode=server`: planned files, deletes and residuals equal client planning, for filters, a snapshot id and a branch, on v2 with position and equality deletes and on v3 with deletion vectors; 403 and 404 as load; a client without the property sends no plan request; endpoint list updated | two-way |
| V11b | V11a | Spark 3.5 and 4.2 over IRC with server planning pass the read smoke and the MERGE/UPDATE/DELETE writes | the existing smoke and write suites rerun with the catalog property set; same results as client planning | two-way |
| V11c | V11a | Large plans bounded: stateless `plan-tasks` (snapshot plus manifest range encoded in the token) with `POST .../tasks`, or a task cap answered `failed` | a table whose task count is above the page size plans identically; a forged token is 400 | two-way (token format is internal) |
| V11d | V11a, owner | Server sets `scan-planning-mode=server` per table or cluster | load config carries it only where configured | one-way for the owner: it changes every client's read path |
| V12a | V9, V2 | `GET .../credentials` served, load checks, empty list; header accepted, ignored | 200 with an empty list; 403 and 404 as load; load response unchanged with and without the header | two-way |
| V12b | owner decision | Decision record: which storages vend; the identity service (STS role, Entra); which privilege grants read versus write access; credential lifetime; HDFS declared not applicable | none (decision) | one-way for the owner |
| V12c | V12b | S3 vending: STS-scoped credentials per table prefix in `storage-credentials`, refresh through `/credentials` | MinIO STS in a container test: the vended key reads this table's prefix and is refused on another table's | one-way (new dependency, secret, contract) |
| V12d | V12b | S3 remote signing: `/sign` with a prefix check, `s3.remote-signing-enabled` in the load config when requested | MinIO: signed GET of this table's file works, another table's is 403 | one-way (contract, service in the data path) |
| V12e | V12b | ADLS SAS vending through config and the refresh endpoint | Azurite: SAS limited to the table directory | one-way |

## What reading could not settle
- Whether the service runs as several instances behind one address (this decides whether any plan state is acceptable).
- Real service memory and latency cost of planning large tables.
- Whether MinIO's STS supports a session policy scoped to a prefix as AWS does, in the image the recipes use.
- Whether LinkedIn's HDFS deployment would want delegation-token issuance through an OpenHouse extension at all.
