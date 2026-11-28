# Review of pull request 153 (irc/build-on-owner-namespace-stack, head cd6f90d8)

Reviewer: independent sub-agent, read-only. Worktree `/home/user/oh-ns`. Nothing built or run; every claim below is from
reading code, git objects, the Spark 3.5.2 / LinkedIn Iceberg 1.5.2 jars in the Gradle cache (javap), and the fork's
Iceberg 1.12 sources.

## 1. Verdict

**MERGE AFTER FIXES.** Close first: F1 and F2. Both are documentation and labelling only, with no code change.

The three things that matter most:
1. **The merge is faithful.** His code is unedited and his history is reachable. For 869 and 871, `git merge-tree --write-tree`
   reproduces the committed trees byte for byte (`1a8c8b32`, `9573f0ed`). For 870 the only difference from git's automatic
   result is the conflicted import block of `TablesServiceImpl`. The resolution is correct: the three Iceberg exception
   imports were unused on this branch before the merge, and his stack never used them in his own code.
2. **The pull request's behavior-change account is incomplete (F1).** It leaves out:
   - `USE openhouse.<db>` on Spark 3.5 / Iceberg 1.5.2 changes.
   - House Tables' schema bootstrap changes: a new table is created at every House Tables start, with a collation that
     only MySQL 8 has.
   - More native paths are taken over by his routes than the three it lists.
   - `DROP NAMESPACE` of a database that has a record and tables succeeds and leaves the tables.
3. **Two pins record defects as if they were expected behavior (F2):**
   - `removeProperties` answers `false` on rest-shim, against his documented contract.
   - A created namespace is not listed by `listNamespaces()` / `SHOW DATABASES`.

## 2. Findings

### F1. MUST FIX BEFORE MERGE: behavior changes missing from the pull request's "Behavior changes against the base"
Certain from reading. Each item is on by default and has no switch. None is in `docs/plans/irc/85-behavior-change-ledger.md`
(grep for 869/870/871/"namespace record": no hits).

a. **`USE openhouse.<db>` (Spark 3.5, Iceberg 1.5.2 line, legacy and rest-shim paths).**
   - Mechanism: Spark 3.5.2 `CatalogManager.setCurrentNamespace` calls `assertNamespaceExist` for any non-session catalog.
     It uses `SupportsNamespaces.namespaceExists`, whose Spark default calls `loadNamespaceMetadata` and catches only
     `NoSuchNamespaceException` (javap of spark-catalyst 3.5.2). LinkedIn's 1.5.2 `SparkCatalog` does not override
     `namespaceExists` (javap: no such method).
   - Before: `UnsupportedOperationException "Describing database is not supported"`.
   - After: `[SCHEMA_NOT_FOUND]` for every existing database (none has a record), and success for a database that has a
     record.
   - On 1.12, `SparkCatalog.namespaceExists` goes to `OpenHouseCatalog.namespaceExists` (still refused), so nothing changes
     there.
   - No smoke scenario or test pins this.

b. **House Tables schema bootstrap.** File: `services/housetables/.../config/db/jdbc/JdbcProviderConfiguration.java:70-81`,
   which is his 869 code.
   - A new `SqlDataSourceScriptDatabaseInitializer` bean replaces Spring Boot's auto-configured one. When the platform is
     `all` (the default), it rewrites the platform to the JDBC driver id.
   - With `spring.sql.init.mode=always` (`services/housetables/src/main/resources/application.properties:9`), every House
     Tables start now runs `schema-mysql.sql` or `schema-h2.sql` before `schema.sql`.
   - For an existing MySQL deployment that sets nothing new, House Tables creates `catalog_namespaces` at start-up with
     `COLLATE=utf8mb4_0900_ai_ci`. That collation exists only on MySQL 8.0+, so on 5.7 or MariaDB the script fails and House
     Tables does not start (`continue-on-error` defaults to false).
   - Where `mode` is `never` (operators applying `ddl/`), nothing runs: the operator must apply
     `ddl/0002__add_namespace_metadata.sql`, or only the namespace routes fail (500 / `RESTException`).
   - House Tables also gains the routes `/hts/namespaces` (POST, GET, PUT, DELETE).
   - The tables service needs nothing from House Tables at boot. `MainApplicationConfig.provideNamespaceApiInstance` and
     `provideNamespaceRepository` only build a lazy WebClient. Existing House Tables clients are unchanged:
     `disableRetry(totalTransportAttempts == 1)` is `disableRetry(false)` for `UserTableApi` and `ToggleStatusApi`, which is
     Reactor Netty's default.

c. **Route takeover is wider than the three paths listed.**
   - Mechanism: his `/v1/{prefix}/namespaces` accepts any prefix, including `databases`. Spring prefers a mapping that matches
     path and method over one that matches only the path, and answers 405 only when nothing matches the method.
   - Native 4-segment patterns under `/v1/databases/{databaseId}/X` exist for:
     - `tables` (POST)
     - `views` (GET, POST)
     - `aclPolicies` (GET, PATCH)
     - `softDeletedTables` (GET)
   - Native 5-segment patterns exist for `tables/{tableId}` and `views/{viewId}` (GET, PUT, DELETE).
   - Paths that answered 405 on the base and now reach his controller, for a database named `namespaces`:

     | Request | Now handled as |
     |---|---|
     | `GET`, `HEAD`, `DELETE /v1/databases/namespaces/tables` | load or drop of namespace `tables` |
     | `DELETE /v1/databases/namespaces/views` | drop of namespace `views` |
     | `DELETE /v1/databases/namespaces/aclPolicies` | drop of namespace `aclPolicies` |
     | `DELETE /v1/databases/namespaces/softDeletedTables` | drop of namespace `softDeletedTables` |
     | `POST /v1/databases/namespaces/tables/properties` | property update of namespace `tables` |
     | `POST /v1/databases/namespaces/views/properties` | property update of namespace `views` (was 405 through `views/{viewId}`) |

   - Separately, every `/v1/<any segment>/namespaces[/x[/properties]]` path that had no handler (404) now answers from his
     controller.
   - The pull request lists only GET/DELETE `.../tables` and POST `.../tables/properties`, and `NamespaceRoutesBoundaryTest`
     pins only those three.
   - Each extra path is a SYSTEM_ADMIN write or a metadata read on an existing route shape, so real exposure is low. It is
     still a change and must be listed.

d. **`DROP NAMESPACE openhouse.<db>` on 1.5.2 when the database has a record and holds tables succeeds.** It deletes the
   record and leaves the tables.
   - Spark 3.5 `DropNamespaceExec` calls only `namespaceExists`, then `dropNamespace(ns, cascade)`, and maps only
     `NonEmptyNamespaceException`. 1.5.2's `SparkCatalog.dropNamespace` calls the Iceberg catalog directly. His
     `TablesServiceImpl.dropNamespace` (`TablesServiceImpl.java:130-133`) never looks at tables.
   - So a `DROP DATABASE db` without CASCADE "succeeds" on a populated database.
   - This holds today only for databases created through his API, but it is what Spark users will meet. Not pinned.

e. **Spark `CREATE NAMESPACE` stores Spark's reserved properties in the record.** Spark 3.5 `CreateNamespaceExec` adds
   `owner = Utils.getCurrentUserName()` (javap: `getCurrentUserName` call). `COMMENT` and `LOCATION` become `comment` and
   `location` properties. These are visible in `DESCRIBE NAMESPACE EXTENDED`. Likely, from reading; not run.

Fix: add a–e to the pull request description's behavior-change section and to the ledger, as "accepted on the owner's
instruction, documented for follow-up". No code change needed.

### F2. MUST FIX BEFORE MERGE: defect pins labelled as expected behavior
Certain from reading.

- **`CatalogPluginContractTest.java:297-299`** pins `removeProperties` as `true` on legacy and `false` on rest-shim, with the
  comment "The rest-shim path answers false although the property is removed".
  - His `integrations/java/NAMESPACES.md` says `removeProperties` "returns whether any key was removed". So `false` is a
    defect of this branch's rest-shim path.
  - Cause, verified: Iceberg's `RESTSessionCatalog.updateNamespaceMetadata` returns `!response.updated().isEmpty()`
    (`core/.../rest/RESTSessionCatalog.java:940` on `openhouse-1.12.0`). The pull request says "I believe ... but I did not
    check it".
- **`CatalogPluginContractTest.java:294`** (`assertFalse(ns.listNamespaces().contains(n))`) and **`SparkSqlSmokeTest.java:363-367`**
  (s19 `.here(succeeds())` with "SHOW DATABASES does not list it").
  - A namespace that `createNamespace` reports created and that listing omits breaks `SupportsNamespaces` expectations.
  - His documentation calls it intended ("may not appear there"). For this branch's purpose it is still an open defect to
    put to him.
  - The method Javadoc presents it as design.

Fix: mark each as a known defect with its cause. For example, rename the assertion message or comment to
"DEFECT (pinned): ...", and say in the `because(...)` text that the s19 success leaves an unlisted namespace.

### F3. SHOULD FIX: the stand-in's fidelity is asserted, not checked
Files: `HouseNamespacesInMemoryRepository.java`, `HouseNamespacesInMemoryRepositoryTest.java`.

- I compared it line by line with his `NamespacesServiceImpl`, `NamespaceRow`, `HouseNamespaceRepositoryImpl` and the
  schemas. It matches on every point a route can reach:
  - Name unique case-insensitively, with the spelling kept: MySQL `utf8mb4_0900_ai_ci` and H2 `VARCHAR_IGNORECASE` versus
    `toLowerCase(ROOT)`. Names are ASCII-only by the create-body pattern.
  - `@Version` starts at 0, so revision "0" at create.
  - A changed document moves the version by one on flush; an unchanged one by one through `OPTIMISTIC_FORCE_INCREMENT`.
  - The uuid/revision check comes before the name/properties check, as in his code. `EntityConcurrentModificationException`
    plays his 409, `DuplicateCreationException` his create 409, `NotFoundException` his 404.
  - Delete removes only the record, and a create afterwards gets a new uuid.
- Not modelled:
  - His `IllegalStateException` when House Tables' database type is `ICEBERG`, which a real deployment answers as 500 /
    `RESTException`.
  - A House Tables store without `catalog_namespaces`.
  - `InvalidNamespaceDocumentException`: the stand-in throws `IllegalArgumentException`. This is unreachable from his routes,
    since `TablesServiceImpl` always sends the stored name and a non-null map.
- The 8 tests check the stand-in against the author's reading of his code; nothing runs the same cases against the real
  store. Smallest fix: run the same 8 cases, parameterised, against `HouseNamespaceRepositoryImpl` over the real House Tables
  in the existing `namespaceIntegrationTest` source set (`IcebergNamespacesH2Test` already boots House Tables on H2).
- It can wait: today's reading shows no divergence on any reachable path.

### F4. SHOULD FIX: the Spark pins describe the embedded store, not a real deployment
The s19/s24 and contract expectations hold only where House Tables has `catalog_namespaces` and a JDBC backend.

- With House Tables on `ICEBERG`, or MySQL without `ddl/0002`, every namespace statement on the legacy and rest-shim paths
  answers `RESTException "Namespace operation failed"`. Examples are DESCRIBE, ALTER, USE, and 1.5.2 CREATE and DROP.
  `namespaceExists`'s Spark default does not catch it, so the answer is not `SCHEMA_NOT_FOUND`.
- Say so in `namespaceRecords()`'s `why` text and in the pull request. It can wait: these are test labels.

### F5. SHOULD FIX: delta-harness expectations, precisely
`integrations/spark/delta-harness/src/main/scala/harness/openhouse/NegativeDdlScenarios.scala:231-241` and
`SurfaceScenarios.scala:36-37`.

- Default 1.12 classpath: `ddl.ns.createRejected` and `ddl.ns.dropRejected` still pass, because "Checking if database exists
  is not supported" contains "not supported".
- `HARNESS_ICEBERG_LINKEDIN=1` (1.5.2):
  - `createRejected`: CREATE succeeds (store present) or answers `RESTException` (store absent), never
    `UnsupportedOperationException`.
  - `dropRejected`: answers `NoSuchNamespaceException` (an `AnalysisException`).
  - The surface `createNamespace` check fails when CREATE succeeds.
  - A successful CREATE persists `a_new_db` / `nope_ns` records, so a second run against the same House Tables then gets
    `NamespaceAlreadyExistsException`.
- The pull request says "would change" without naming the line. The harness is outside CI, so this can follow.

### F6. SHOULD FIX: deployment documentation for this branch
- `DEPLOY.md` documents the House Tables store and its bootstrap `schema.sql` (lines 186-230). It says nothing about:
  - the new `catalog_namespaces` table and `ddl/0002`;
  - the platform-specific bootstrap scripts and their MySQL 8 requirement;
  - the second namespace API (`/v1/{prefix}/namespaces`, record-only, SYSTEM_ADMIN) next to the read-only, table-derived
    `/iceberg/v1/namespaces`.
- `docs/specs/catalog.md` and `docs/specs/housetables.md` do not list the new routes. That is upstream's gap too; flag it to
  him rather than editing his files.
- This can follow; it is documentation.

### F7. MINOR: `theConfigDoesNotAdvertiseTheOwnersRoutes` cannot fail for the reason named
`NamespaceRoutesBoundaryTest.java:245`.

- Iceberg's endpoint strings (`"POST /v1/{prefix}/namespaces"`) are the same whoever serves them. The config route
  enumerates only the IRC package's handlers, so his routes cannot appear there by construction.
- The test actually checks that `/iceberg` does not advertise its own namespace writes, which was already true.
- Rename it, or drop it.

### F8. MINOR: private ids and history narration in new test comments and test output
- Locations:
  - `CatalogPluginContractTest` ("upstream 871", "Before upstream 871 ...", "the owner's").
  - `SparkSqlSmokeTest` (`NAMESPACE_RECORDS = "(d) namespace records, upstream 869-871"`: a pull request number in a
    difference category printed in test output; also "upstream 870's").
  - `NamespaceRoutesBoundaryTest` ("upstream 870", "the owner's routes/controller").
- Replace with descriptive names, for example "namespace records (SupportsNamespaces over /v1/{prefix}/namespaces)".
- The file's existing D14b/T6 ids are pre-existing.

### F9. MINOR, feedback for the owner on his stack (verified in merged code; not this pull request's to fix)
- (c) confirmed:
  - `services/housetables/NAMESPACES.md` ("The public API rejects drop while a table or view occupies the namespace")
    contradicts `TablesServiceImpl.dropNamespace` (`:130-133`, record delete only) and `services/tables/NAMESPACES.md:49-51`.
  - The 1.5 client still maps DROP + 400 to `NamespaceNotEmptyException("Namespace contains tables or views")`
    (`OpenHouseCatalog.java:615`).
  - Nothing can produce that 400: DELETE has no body, the `{namespace}` path variable is not validated, and
    `HouseNamespaceRepositoryImpl.delete` does not map a House Tables 400, so one would surface as 500.
- (d) confirmed: no code reads namespace properties for tables. The only callers of `HouseNamespaceRepository` are the four
  `TablesServiceImpl` namespace methods. 871's title ("resolve effective table properties from namespace defaults")
  promises what `integrations/java/NAMESPACES.md:7` disclaims.
- (a) confirmed as reported, with additions:
  - DESCRIBE (both lines) and ALTER ... SET DBPROPERTIES (both lines) go from Unsupported to `SCHEMA_NOT_FOUND`.
  - 1.5.2 CREATE goes from Unsupported to success, through Spark's default `namespaceExists`, then `loadNamespaceMetadata`,
    a 404, `false`, and then create.
  - 1.5.2 DROP of a table-only database goes from Unsupported to `SCHEMA_NOT_FOUND`.
  - 1.12 CREATE and DROP are unchanged, because 1.12's `SparkCatalog.namespaceExists` delegates to the still-refused
    `OpenHouseCatalog.namespaceExists`.
  - Add USE (F1a) and populated-with-record DROP (F1d).
- (b) wider than reported (F1c). Root cause: `{prefix}` is never read or validated (`IcebergNamespacesController.java:38`).
  A fixed or validated prefix would remove every collision.
- His own `services/tables/src/test/.../e2e/h2/SpringH2Application` also does not scan `com.linkedin.openhouse.tables.exception`.
  His advice is therefore missing there too, the same gap this pull request fixed for the fixtures.

## 3. Behavior changes (mine), against `81b4cd4d`

| # | Surface | Change | Default | Toward or away from LinkedIn main | In the pull request |
|---|---|---|---|---|---|
| 1 | Tables service routes | Adds POST, GET (and implicit HEAD), POST properties, DELETE `/v1/{any}/namespaces[/{ns}]` | on | away (main has none) | yes |
| 2 | Native routes, database `namespaces` | 405 becomes his handler on 8 method/path pairs (F1c); 404 becomes his handler under `/v1/*/namespaces` | on | away | partly (3 of 8) |
| 3 | Spark 3.5 1.5.2, legacy and rest-shim | CREATE NAMESPACE succeeds and stores a record (unlisted) | on | away | yes |
| 4 | Spark 3.5 both lines | DESCRIBE and ALTER SET DBPROPERTIES give `SCHEMA_NOT_FOUND` instead of Unsupported | on | away | yes |
| 5 | Spark 3.5 1.5.2 | DROP NAMESPACE of a table-only database gives `SCHEMA_NOT_FOUND` | on | away | yes |
| 6 | Spark 3.5 1.5.2 | USE `openhouse.<db>` gives `SCHEMA_NOT_FOUND` instead of Unsupported (success with a record) | on | away | **no** |
| 7 | Spark 3.5 1.5.2 | DROP NAMESPACE of a database with a record and tables succeeds and leaves the tables | on | away | **no** |
| 8 | Java client API | `SupportsNamespaces` create, load, set, remove and drop now call the service; `namespaceExists` and `listNamespaces(ns)` unchanged | on | away | yes |
| 9 | Java client init | One more `ApiClient`/WebClient per catalog (no retry), the same headers and token; no new request on existing calls | on | neutral | no (harmless) |
| 10 | House Tables start-up | Platform-specific `schema-*.sql` run before `schema.sql`; `catalog_namespaces` auto-created under `mode=always`; MySQL 8 collation required | on | away | **no** |
| 11 | House Tables routes | `/hts/namespaces` CRUD | on | away | no |
| 12 | OpenAPI documents | Tables spec: +4 operations, namespace schemas, tag `Namespace`. House Tables spec: +4. Generated clients gain `NamespaceApi`; existing APIs unchanged; IRC controllers are `@Hidden`, so no name collision | on | away (additive) | no |
| 13 | Tables service beans | New controller, advice (scoped to his controller, highest precedence, disjoint from `IcebergRestExceptionHandler`'s package scope), two config beans; `TablesServiceImpl` also implements `NamespacesService` and needs a `HouseNamespaceRepository` bean | on | additive | partly |
| 14 | `/iceberg` routes, kit, Spark 4.2 | unchanged | - | - | yes |

## 4. Tests

- **`HouseNamespacesInMemoryRepositoryTest` (8).**
  - Every test can fail if the stand-in changes.
  - None can fail if his real store differs (F3).
  - The expectations agree with his code as I read it.
- **`NamespaceRoutesBoundaryTest` (6).**
  - Handler assertions can fail: `getHandler` would throw on a 405, and the assertions name the handler.
  - The HTTP test can fail on status and body.
  - Unpinned: five of the eight takeover pairs (F1c).
  - Config test: F7.
- **`CatalogPluginContractTest.namespaceOperationsUseTheNamespaceRecord`, legacy and rest-shim.**
  - Can fail on each step.
  - Old assertions checked: the seven Unsupported answers were LinkedIn main's and are now really different.
  - The new expectations match his `NAMESPACES.md`, except the defect pins (F2).
  - The per-path name avoids collisions; each smoke class runs its own `SpringH2TestApplication`
    (`SparkSqlSmokeTest.smokeServerUri`), so the stand-in is fresh per class and s19's leftover record cannot leak.
  - Unpinned: setProperties or removeProperties on a missing record; drop of a populated namespace.
- **`SparkSqlSmokeTest.namespaceRecords()`.**
  - The baseline is unedited (`SparkSqlSmokeScenarios` untouched); the differences are declared per line and path.
  - Can fail.
  - Unpinned: USE (F1a), populated DROP (F1d).
- **Unpinned anywhere:**
  - House Tables bootstrap on MySQL (F1b).
  - The real-deployment failure mode without the table (F4).

## 5. Checked and found sound

- **Merge commits.** `f67e88a1`, `770b8e85` and `32524fd2` each have two parents: first the branch, second
  `39f23249` / `e09a9625` / `464a220d`, equal to `scout/up-869/870/871` in `/home/user/oh-c1`. All three upstream heads are
  ancestors of HEAD. 869 is in 870, and 870 is in 871. Base `bb8929e1` for all three.
- **His files.** Exactly 4 of his 61 files differ from his 871 head:
  - `OpenHouseCatalog` (1.5 runtime), `services/tables/build.gradle` and `MainApplicationConfig`: each equals `git merge-file`
    of base/ours/theirs. The halves are disjoint:
    - `OpenHouseCatalog`: `loadFileIO` and builder location versus namespace methods and init.
    - `build.gradle`: the iceberg-1.12 srcDirs and capabilities `compileOnly` versus the `namespaceIntegrationTest` source set.
    - `MainApplicationConfig`: the Page schema customizer versus the transport-attempts change and namespace beans.
  - `TablesServiceImpl`: imports only.
    - Kept: `FormatVersionRule`, `InvalidPropertyUpdateException`, `Set`, `Collectors`.
    - Dropped: `BadRequestException`, `CommitFailedException`, `CommitStateUnknownException`. Their only uses were the
      base's commit try/catch, which this branch had already removed; his diff touches no line using them.
    - Zero remaining references; no duplicate imports.
  - No file outside his 61 changed in the merges.
  - `settings.gradle` and the House Tables client sources are untouched by his stack.
- **Stand-in placement.** It is only on test classpaths. The fixtures module is consumed only through
  `testImplementation` / `service*` harness configurations: services/tables, libs/datalayout, apps/spark-3.5, Spark
  3.5/4.2 itests, the kit, apps/spark-4.2. It is not `@Component`, so no broad scan picks it up. `@Primary` on an `@Import`ed
  class is honoured.
- **Startup without the stand-in.** It is true that the service starts without it: the House Tables client repository
  builds only a WebClient. No suite exercised the real client in the embedded application before, so none stops exercising
  anything. His real-client tests (`IcebergNamespacesH2Test`) use `HouseTablesSpringApplication` and inject the real
  repository themselves.
- **Advice import.** Fixture-only. Production `TablesSpringApplication` scans `com.linkedin.openhouse.tables`, which
  includes `tables.exception`. The advice is `assignableTypes = IcebergNamespacesController`, so no other answer changes.
- **Routes against `{prefix}` values.**
  - `iceberg`: `/v1/iceberg/...` reaches his controller; the `/iceberg/**` catch-all needs first segment `iceberg`.
  - `hts`, `jobs`: no tables-service routes, so his controller.
  - `databases`: F1c.
  - `/iceberg/v1/namespaces*`: unchanged handlers and interceptor.
  - His routes run the native token interceptor.
- **Authorization.** It runs before the record lookup on all four routes (`authorizeNamespace` first), so a 403 does not
  reveal whether a record exists.
- **Open fork pull requests.** 151, 152 and 154 share zero files with this pull request (REST file lists).

## 6. Not checked

- No build, test, kit or CI log was run or read. The author's run results (kit line identical, 10/10, 47/47, CI green) are
  taken as reported.
- Whether LinkedIn's production House Tables sets `spring.sql.init.mode` or runs MySQL 8.
- His design (authorization model, timeouts, transport retries, validation bounds) beyond what bears on this branch.

## 7. Not in this pull request and should be (or should be named)

- The ledger entries and pull request text for F1a–e.
- Delta-harness updates for the 1.5.2 line (F5).
- DEPLOY.md: `catalog_namespaces`, `ddl/0002`, MySQL 8, the second namespace API (F6).
- Unifying `/iceberg/v1/namespaces` (table-derived, read-only) with his records. This is out of scope here, but two
  namespace APIs with different existence rules now coexist; the scout recommended routing `/iceberg` through
  `NamespacesService`.
- **Collision list for the fix-forward work.** Files this merge changes that the in-flight work is likely to touch:
  - `services/tables/.../services/TablesServiceImpl.java`: the import block next to `FormatVersionRule`, the new
    `@Autowired HouseNamespaceRepository` field, and four methods after the field block. A future re-merge of his stack will
    re-conflict on the same import block.
  - `services/tables/.../config/MainApplicationConfig.java`
  - `services/tables/build.gradle`
  - the 1.5 runtime `OpenHouseCatalog.java`
  - `tables-test-fixtures/.../SpringH2TestApplication.java`
  - `SparkSqlSmokeTest.java` and `CatalogPluginContractTest.java`
  - `services/housetables/.../JdbcProviderConfiguration.java` and `schema.sql`
- Not touched by this merge: `OpenHouseInternalTableOperations`, the format-version package, and every `api/irc` handler.
  Fix-forward work on those does not collide with it.

## Re-check at 94528ec9

Scope: `cd6f90d8..94528ec9` (commits `0a485be3` and `94528ec9`), the updated pull request description, and the CI check
runs on `94528ec9`. Read only; nothing was run.

**Verdict: MERGE.** F1 and F2 are closed. There is no new blocker.

### His stack is still untouched

- The two fix commits change 11 files, and none of them is one of his 61 files. Checked with `comm` against his file list.
- The three merge commits are ancestors of `94528ec9` and are unchanged.

### F1: closed

Every item is now in the description's "Behavior changes against the base", stated correctly, and pinned where the author
says it is.

**a. `USE`** is pinned in scenarios s19 and s24 through a new `use()` helper, which restores the session's catalog and
namespace afterwards. It is declared for every path and line:

| Path and line | s24 `USE openhouse.smoke_t1` | s19 `USE openhouse.smoke_t1_new_ns` (after CREATE) |
|---|---|---|
| Legacy and rest-shim, 1.5.2 | `SCHEMA_NOT_FOUND`, labelled KNOWN DEFECT | succeeds: the record exists |
| Legacy and rest-shim, 1.12 | "Checking if database exists is not supported" (`ICEBERG_112`) | same refusal (`ICEBERG_112`) |
| rest-http (Spark 3.5) | succeeds | `SCHEMA_NOT_FOUND` |
| Spark 4.2 | succeeds | `SCHEMA_NOT_FOUND` |

- This matches the Spark 3.5.2 `assertNamespaceExist` and Iceberg 1.5.2 / 1.12 `SparkCatalog` paths I read.
- The rest-http and Spark 4.2 rows are this branch's earlier IRC behavior. They were declared now because the scenario is
  new.
- The baseline answer ("Describing database is not supported") is added to the shared scenarios. It is derived from
  reading, not measured on LinkedIn main, and it is consistent with Spark's default `namespaceExists`.

**b. House Tables start-up** is in section 6 of the description, marked "read, not tested". It is correct.

**c. Route takeover.** The 16 rows in `NamespaceRoutesBoundaryTest.TAKEN_OVER` cover:
- my eight 405 pairs, which now answer 404 because no such record exists;
- `GET /v1/databases/namespaces` and `GET /v1/<any>/namespaces`: 400 before, 405 now (his POST matches the path);
- `POST /v1/<any>/namespaces` and `POST /v1/databases/namespaces`: 400 before, 200 now, with a record stored;
- GET, properties and DELETE on `/v1/<any>/namespaces/<ns>`.

What the measurement corrected and added to my finding:
- **Unmatched paths:** the base answered 400 "cannot be resolved by server", not the 404 I assumed.
- **Any prefix:** any prefix creates a record. That is the most visible item, and it is listed.

The before statuses were measured against a worktree of `81b4cd4d`. I could not re-measure them; they are consistent with
the native mappings I read. Each row asserts both the handler (except the two 405 GET rows) and the status, so the test can
fail.

**d. Populated drop** is pinned by the new `CatalogPluginContractTest.sqlCreateAndDropNamespace`:
- On 1.5.2: `DROP NAMESPACE` succeeds, the record is gone, and `tableExists` is still true. This is labelled KNOWN DEFECT.
- On 1.12: both statements are refused.

**e. Spark's own properties** are pinned in the same test: the key set is exactly `{comment, location, owner}`, and
`comment` is `c`.

**Still open, not blocking:**
- `docs/plans/irc/85-behavior-change-ledger.md` still has no entry for these changes. It lives in the planning checkout, not
  in this pull request; record it there.
- F6 (DEPLOY.md) is unchanged. It is only listed under "for the owner", and it can follow.

### F2: closed

Every pinned defect now carries "KNOWN DEFECT" and its cause, in the comment or the assertion message:
- `listNamespaces()` does not list a created namespace;
- rest-shim `removeProperties` answers false, citing `RESTSessionCatalog`'s `updated` check;
- populated DROP leaves the tables;
- the s19 CREATE namespace is not listed;
- DESCRIBE, ALTER, DROP and USE of a table-only database report it missing (the shared `noRecordDefect` reason).

The class and method Javadocs no longer present these as design.

### F3: closed

**The contract.** `HouseNamespaceRepositoryContract` runs the 8 cases against two stores:
- the stand-in, through `HouseNamespacesInMemoryRepositoryTest`;
- his real `HouseNamespaceRepositoryImpl` over a real House Tables service on H2, through
  `services/tables/src/namespaceIntegrationTest/.../HouseNamespaceRepositoryImplContractTest`.

**The divergence it found.** An invalid document now raises `WebClientResponseException.BadRequest` in the stand-in. That
matches the real path:
- a name mismatch raises his `InvalidNamespaceDocumentException`, which House Tables answers as 400;
- `@NotNull properties` fails validation with 400;
- `HouseNamespaceRepositoryImpl` does not map 400, so the caller sees `BadRequest`.

**Placement.** The abstract contract sits in the fixtures' main source set. I consider that acceptable:
- The module is test support, and JUnit and spring-boot-starter-test are already its `implementation` dependencies.
- Every consumer takes it through a test or harness configuration.
- Project consumers get the plain jar. The published `uber` shadow jar relocates `org.junit`, so its copy of the contract is
  inert. JUnit scans class directories, not jars, so no consumer runs it by accident.
- The only cost is a JUnit-annotated class in a published artifact. That is MINOR.

**CI.** The real-store run is wired into CI on both lines, but I did not read the job logs:
- His `services/tables/build.gradle` makes `check` depend on `namespaceIntegrationTest`.
- The `rest` group runs `build` minus the other groups (`scripts/ci/test-groups.gradle`).
- "Default build, rest" and "Reference build, rest" both succeeded on `94528ec9` (check-runs API).

### F5, F7, F8: done

- **F5:** `BASELINE-2026-11-22.md` records the three harness cases that now fail on 1.5.2 only. It says they were derived,
  not re-run.
- **F7:** `theConfigListsOnlyRoutesServedUnderIceberg` sends each advertised endpoint under `/iceberg` and fails if one
  reaches the catch-all, so it can now fail.
- **F8:** no added line still has "upstream 8xx", "the owner's", "his" or a pull request number. The category is now
  "(d) stored namespace records".

F4 is also closed: the Javadoc and the description state that the answers hold only with a JDBC House Tables store that has
the table.

### Nothing new blocks the merge

- CI: 13 jobs succeeded and 3 were skipped as on the base.
- One fragility, not a blocker: the `use()` helper restores state with `spark.catalog().setCurrentDatabase`. That is safe
  only while the scenarios start from `spark_catalog`, as they do today.
