# Scout result: the owner's namespace line and the earlier drafts

Bottom line: the owner's live namespace work is the upstream stack of pull requests 869, 870 and 871 on
`linkedin/openhouse`, all non-draft, none approved yet. It stores an optional namespace record in House Tables that is
independent of tables, and serves the Iceberg REST namespace routes at the root of the tables service. `irc-integration`
should merge his stack head as merge commits and merge again when he pushes (trial merge: one conflicted file, import
lines only); the `/iceberg` namespace routes are then built on his service. This file summarizes the scout's report,
which is kept byte for byte as `records/scout-namespace-and-prior-work.md`; where this file and the report differ, the
report is right. The scout's date is 2026-11-27. Marks as in the report: [ran] the scout executed it, [read] it read it
in a file or pull request, [inferred] it concluded it without running it. No build or test was run by the scout.

Sections: 1 the stack and its state; 2 the design; 3 how `irc-integration` builds on it; 4 the compatibility kit
estimate; 5 inconsistencies inside his stack; 6 the earlier drafts, with a verdict each; 7 decisions of his that stand
or conflict; 8 his other live pull requests that overlap; 9 what was not read.

## 1. The stack and its state

- The owner's live namespace line is the stack 869 <- 870 <- 871 on `linkedin/openhouse` (branches
  `mkuchenbecker/database-properties-{hts,tables,client}`, all non-draft, no approvals yet, CI green on 869) [ran gh api].
  The fork's November drafts 76 and 77 are its direct predecessors. The fork's August line (57, 59, 61, 63 to 67, designs
  54 and 56) is a different, broader design he set aside ("This plan does not build on that branch", plan in 76) [read].

| Pull request | Title | Base | Head sha | Draft | Commits | Plus/minus | Files | CI |
|---|---|---|---|---|---|---|---|---|
| 869 | feat(hts): persist namespace metadata and properties | `main` | `39f23249` | no | 33 | +2447/-0 | 30 | `build-run-tests` success (combined status "pending": no statuses posted) |
| 870 | feat(tables): expose namespace property management | `mkuchenbecker/database-properties-hts` | `e09a9625` | no | 44 (on top of 869) | +2009/-7 | 25 | success (his own body says the workflow runs only for pull requests to main) |
| 871 | feat(client): resolve effective table properties from namespace defaults | `mkuchenbecker/database-properties-tables` | `464a220d` | no | 41 (on top of 870) | +686/-25 | 6 | success (same caveat) |

- Stacked: 869 is an ancestor of 870, 870 of 871 [ran `git merge-base --is-ancestor`]. Fetch worked for all three [ran].
  Merge base of each with LinkedIn main is `bb8929e1`. The branches are in the upstream repository itself.
- Review state [ran]: no approvals. Requested reviewers: 869 `teamurko`; 870 and 871 `teamurko` and `abhisheknath2011`.
  187 of the review entries are the owner's own (an "informational review" agent run plus replies). External review
  comments only on 869 (2026-11-17 and 18): abhisheknath2011 (index on the namespace name; docs on the handler; rename
  update response; merge the properties update body into `UpdateNamespaceRequestBody`; "why two response models";
  class-level 500; "pessimistic lock impact on MySQL, could we go optimistic") and cbb330 ("use the same concurrency
  model as Iceberg: HTTP 409 on version conflict, client throws CommitFailedException"). The 2026-11-20 commit
  `524be665 Make HTS namespace updates optimistic document replacements` and the single `UpdateNamespaceRequestBody`
  show these were acted on [read]. No comment on 870 or 871 from anyone but the owner.
- Last updates: 869, 870 and 871 on 2026-11-24 [ran].
- History inside 869 [ran `git log`]: started 2026-11-05 as "database properties" with two tables (a database row and
  a property-key table); his own review asked "two tables here seems to only be a way for them to diverge... treat
  properties as a document"; 11-06 "store properties in the database row"; 11-10 renamed to Namespace; 11-12 "Preserve
  namespace insertion spelling with case-insensitive identity"; 11-13 "trust namespace service inputs"; 11-20 optimistic
  document replacement.

## 2. The owner's namespace design

### Data model (House Tables) [read 869]
- New table `catalog_namespaces`, DDL `services/housetables/ddl/0002__add_namespace_metadata.sql`, also in `schema.sql`,
  `schema-h2.sql` (`VARCHAR_IGNORECASE`) and `schema-mysql.sql` (`utf8mb4_0900_ai_ci`): `namespace_uuid VARCHAR(36)`
  primary key, `namespace_name VARCHAR(128) NOT NULL UNIQUE` (`uq_namespace_name`), `properties LONGTEXT NOT NULL` (a JSON
  document), `version BIGINT NOT NULL`.
- Entity `NamespaceRow` (`@Entity @Table(name="catalog_namespaces")`): `@Id namespaceUuid` (random UUID at construction,
  not updatable), unique `namespaceName` (not updatable), `Map<String,String> properties` through
  `NamespacePropertiesConverter` (Jackson, `ESCAPE_NON_ASCII`), `@Version long version`. Properties are one JSON
  document, versioned with the row (JPA optimistic lock).
- Identity for lookups is the name, case-insensitive by the column collation; the spelling at creation is kept. Drop and
  re-create gives a new UUID.
- Relation to table rows: none. No foreign key, no join, no occupancy check. "A namespace record is not a mandatory
  parent of a table" (`services/housetables/NAMESPACES.md`). Tables can exist with no record and a record can exist with
  no tables. The legacy list of databases (`/databases`) stays table-derived (870 body: "The legacy database list
  remains table-derived, so an empty namespace record need not appear there").
- Backend: JDBC only. `NamespacesServiceImpl` throws `IllegalStateException` (HTTP 500) when
  `cluster.housetables.database.type == ICEBERG`.

### House Tables API [read 869]
- `NamespacesController`, `@RequestMapping("/hts/namespaces")`:
  - `POST /hts/namespaces`, body `CreateNamespaceRequestBody{namespaceName, properties}`, answers 201
    `NamespaceMetadataResponse`; 409 on a duplicate.
  - `GET /hts/namespaces/{namespaceName}` answers `NamespaceMetadataResponse{namespaceUuid, namespaceName, properties,
    revision}`; 404.
  - `PUT /hts/namespaces/{namespaceName}`, body `UpdateNamespaceRequestBody{namespaceUuid, namespaceName, properties
    (complete replacement), revision}`: a conditional replace; 409 on a stale uuid or revision
    (`EntityConcurrentModificationException`); 400 `InvalidNamespaceDocumentException` if the name spelling differs or
    properties is null. An unchanged map still bumps the version (`OPTIMISTIC_FORCE_INCREMENT`).
  - `DELETE /hts/namespaces/{namespaceName}` answers 204; 404.
  - Errors: `NamespaceErrorResponse{status, error, message, type}` with stable `type` strings.
  - There is no list operation. Namespace records cannot be enumerated in House Tables.
- Service interface `NamespacesService` (House Tables): `createNamespace(name, properties)` throws
  `DuplicateCreationException`; `getNamespace(name)` and `deleteNamespace(name)` throw `NotFoundException`;
  `updateNamespace(name, document)` throws `NotFoundException`. Each operation runs in its own `REQUIRES_NEW`
  `TransactionTemplate`. Repository `NamespaceHtsJdbcRepository extends JpaRepository<NamespaceRow,String>` with
  `findByNamespaceName`. New checked exceptions in `services/common`: `DuplicateCreationException`, `NotFoundException`.
- House Tables trusts its caller (no input validation beyond `@NotNull`).

### Tables service API [read 870]
- Internal catalog: `HouseNamespaceRepository` (create, getByName, delete, update, same exceptions).
  `HouseNamespaceRepositoryImpl` wraps the generated HTS `NamespaceApi`, with 30 s read and 60 s write timeouts, cancel
  on interrupt, and 409 mapped to `EntityConcurrentModificationException`. Models `NamespaceMetadata` (uuid, name,
  properties, revision) and `NamespacePropertyUpdate` (updated, removed, missing).
- `MainApplicationConfig`: new beans `NamespaceApi provideNamespaceApiInstance()` (HTS client with Reactor Netty retry
  disabled: `NAMESPACE_TOTAL_TRANSPORT_ATTEMPTS = 1`) and `HouseNamespaceRepository provideNamespaceRepository(NamespaceApi)`;
  the signature of `getHtsConfiguredApiClient(int totalTransportAttempts)` changes.
- Controller `IcebergNamespacesController`, `@RequestMapping("/v1/{prefix}/namespaces")`: the Iceberg REST specification
  1.5.2 paths, at the root of the tables service, not under `/iceberg`. `{prefix}` is accepted and ignored.

| Method | Route | Secured | Success | Body |
|---|---|---|---|---|
| POST | `/v1/{prefix}/namespaces` | `SYSTEM_ADMIN` | 200 | `CreateNamespaceRequest{namespace: list of one, pattern [a-zA-Z0-9_]{1,128}, properties: map, key at most 512, no lone surrogate}` answers `NamespaceResponse{namespace, properties}` |
| GET | `/v1/{prefix}/namespaces/{namespace}` | `GET_TABLE_METADATA` | 200 | `NamespaceResponse` |
| POST | `/v1/{prefix}/namespaces/{namespace}/properties` | `SYSTEM_ADMIN` | 200 | `UpdateNamespacePropertiesRequest{updates, removals}` answers `{updated, removed, missing}` (the specification's shapes) |
| DELETE | `/v1/{prefix}/namespaces/{namespace}` | `SYSTEM_ADMIN` | 204 | none |

- Not served: `GET /v1/{prefix}/namespaces` (list), `HEAD /v1/{prefix}/namespaces/{namespace}` (exists), `parent=`
  children listing, `GET /v1/config`.
- Service `NamespacesService` (tables), implemented by `TablesServiceImpl` (now `implements TablesService,
  NamespacesService`): `createNamespace`, `loadNamespaceMetadata`, `updateNamespaceProperties`, `dropNamespace`, each
  with the principal. Authorization: `authorizationUtils.checkDatabasePrivilege(namespace.toLowerCase(Locale.ROOT),
  principal, privilege)` plus `@Secured` on the controller. An update reads once, checks `updates` and `removals` are
  disjoint (else 422 `InvalidPropertyUpdateException`), merges, and does one conditional replace with the read
  revision, with no retry (a conflict answers 409 `CommitFailedException`).
- Errors, `NamespaceExceptionHandler` (`@RestControllerAdvice(assignableTypes = IcebergNamespacesController.class)`,
  highest precedence), in the Iceberg `ErrorModel` shape: 422 `UnprocessableEntityException`; 404
  `NoSuchNamespaceException`; 409 `AlreadyExistsException` (duplicate) or `CommitFailedException` (concurrent); 400
  `BadRequestException`; 403 `ForbiddenException`; 401 `NotAuthorizedException`; 500 `RESTException` for any other
  runtime exception.
- Drop deletes the record only. It does not check for tables or views (the occupancy query was removed in the review
  update: "The review update removes the namespace occupancy query and its table-client dependency"). A missing record
  answers 404.
- Tests: a new source set `namespaceIntegrationTest` in `services/tables/build.gradle` (the tables service with a real
  House Tables on H2), wired into `check`.

### Client changes [read 871]
- Despite its title, 871 does not merge namespace properties into table properties. Its body and
  `integrations/java/NAMESPACES.md`: "This API does not merge namespace properties into table properties, change
  `loadTable`, or add namespace support to the Iceberg 1.2 catalog." The effective-property resolution described in
  869's purpose is not implemented anywhere in the stack [read; inferred: deferred or dropped during review
  simplification].
- 871 makes the Iceberg 1.5 `OpenHouseCatalog` implement `createNamespace`, `loadNamespaceMetadata`, `setProperties`,
  `removeProperties` and `dropNamespace` through the generated tables-client `NamespaceApi` (the catalog name is sent as
  `{prefix}`). `listNamespaces()` stays table-derived; `namespaceExists()` stays unsupported. The client validates one
  level and `[a-zA-Z0-9_]{1,128}`. `dropNamespace` returns false on 404 and maps DROP with 400 to
  `NamespaceNotEmptyException` (a leftover: the service no longer returns that).
- `TablesApiClientFactory` gains `createNamespaceApiClient(...)`, and `WebClientFactory.createWebClient(...,
  boolean disableRetry)`.

### Out of scope, explicitly [read]
- Multi-level namespaces (exactly one level).
- Effective-property resolution (the title of 871 notwithstanding).
- A namespace list from records, `namespaceExists`, the Iceberg 1.2 client.
- A drop guard against tables or views (removed).
- Iceberg-backed House Tables.
- A change of the production `SYSTEM_ADMIN` authorization handler ("LinkedIn rollout also requires a separate
  authorization-handler change").

### Other semantics fixed by his design
Case-insensitive identity (MySQL `utf8mb4_0900_ai_ci`, also accent-insensitive [inferred from the collation]; H2
`VARCHAR_IGNORECASE`); drop and re-create gives a new UUID; no retries anywhere (one transport attempt), so a lost
acknowledgement leaves the outcome unknown (500); JDBC House Tables only.

## 3. How `irc-integration` builds on it

### Where things are today on `irc-integration` [read `/home/user/oh-review`, head `81b4cd4d`]
- `/iceberg/v1/namespaces` (list) and `/iceberg/v1/namespaces/{namespace}` (load, and HEAD for exists) are served by
  `IcebergRestCatalogController` through `IcebergRestApiHandler.listNamespaces`, `loadNamespace` and `namespaceExists`.
  Existence is "holds a table" (a one-row paged search), else "in the native database list" (`requireNamespace`,
  `requireListedDatabase`). Load always answers an empty property map. `parent=` answers an empty list after an
  existence check.
- `POST /v1/namespaces`, `DELETE /v1/namespaces/*` and `POST /v1/namespaces/*/properties` are in `UNSERVED_SPEC_ROUTES`
  (406) of the `/iceberg/**` catch-all.
- `IcebergRestApiHandler.databaseId(String)` refuses anything but one non-empty level (400).
- Native `ViewsServiceImpl.requireDatabaseExists` (LinkedIn main, unchanged here) refuses a view create in a database
  holding no table or view (404 `DATABASE_NOT_FOUND`).

### Route collision check [read, inferred]
- 870 mounts `/v1/{prefix}/namespaces` at the service root; `irc-integration` mounts `/iceberg/v1/...` with a
  `/iceberg/**` catch-all. They do not overlap (the first literal segment differs). `TablesMvcConfigurer` applies the
  native token interceptor to `/**` except `/iceberg/**`, so his routes get the native interceptor, not
  `IcebergRestAuthenticationInterceptor`; his own advice renders 401 and 403 in the Iceberg error shape anyway.
- After a merge the same service would answer the namespace routes at two places with different rules: 870's
  `/v1/{prefix}/namespaces` (records only, `SYSTEM_ADMIN`, no list or HEAD) and `/iceberg/v1/namespaces` (derived
  existence, read-only). They must be unified: the `/iceberg` routes should call his `NamespacesService`
  (`TablesServiceImpl`) rather than either side being dropped. His controller keeps its path (it is his design and his
  client 871 targets it), so the `/iceberg` routes become a second front for the same service [inferred recommendation].

### Per route
| Spec route | What his work provides | What an `/iceberg` route still needs | Semantics his design decides |
|---|---|---|---|
| list, including `parent=` | Nothing. House Tables has no list; 871's `listNamespaces()` stays table-derived | A list of records: a new `GET /hts/namespaces` (and `HouseNamespaceRepository.list()`), then the union with the derived list, deduplicated case-insensitively, answering the stored spelling. `parent=`: one level only, so an empty list after an existence check, as today | One level only, so no children ever |
| create | Full: `createNamespace` (`SYSTEM_ADMIN`, 409 on a duplicate in any case, properties stored) | The route and a translation into the `/iceberg` conventions; decide create for a database that exists only derived (tables, no record): his design creates the record with no table check, so create of a populated database succeeds with 200, where the specification and the kit would expect 409 `AlreadyExistsException` [inferred; the kit does not test it] | May exist with no table: yes. Names `[a-zA-Z0-9_]{1,128}`, one level, spelling preserved, identity case-insensitive. Authorization `SYSTEM_ADMIN` (database privilege on the lowercased name) |
| load and HEAD | Load: `loadNamespaceMetadata` (`GET_TABLE_METADATA`), 404 when there is no record. HEAD: nothing (871: `namespaceExists()` unsupported) | Existence and load = record or derived (table or view). A derived-only database must still load (empty map, as today) or every existing database becomes 404 on the IRC routes. The message must be `Namespace does not exist: <ns>` (the kit asserts the prefix and name; 870's message is `Namespace does not exist` without the name) | Properties are free-form strings (keys at most 512 UTF-16 units, no lone surrogates, case-sensitive; values unbounded); no reserved keys; no server-stamped keys |
| properties update | Full: `updateNamespaceProperties` (sparse, 422 on overlap, 409 on a lost race, 404 when there is no record); the response is `{updated, removed, missing}` | The route, and the derived-only case: his design answers 404 for a database with tables but no record. Options: create the record on the first property write (fork pull request 57 did this) or keep 404 | No reserved keys, no size bound on the map (fork pull request 57 had 100 entries, 1 KiB and 8 KiB and reserved `openhouse.*` and `policies`; upstream dropped both) |
| drop | Full for the record: deletes the record only, never inspects tables or views; 404 when there is no record | The specification says 409 `NamespaceNotEmptyException` when not empty. His latest design deliberately allows dropping a populated database's record. An `/iceberg` route that adds an occupancy guard diverges from his `/v1` route; one that does not leaves the database "existing" (derived) after a successful drop. Needs his ruling | Drop of a non-empty namespace is allowed (record only) in 870; his earlier ruling W5 (fork pull request 56) said "the error, never a cascade" |
| children (`parent=`) | not applicable | as list | Nesting is out of scope: exactly one level |

### Base facts [ran]
- LinkedIn main today: `c97c4c15` (2026-11-25, "Guard partition statistics for retention on non-partition columns
  (#921)"), one commit after `bb8929e1`, touching only `TableStatsCollectorUtil.java` (apps/spark) and its test.
  869, 870 and 871 all have merge base `bb8929e1` with main and with `irc-integration` (`81b4cd4d`). The stack's last
  "Merge branch 'main'" commit (2026-11-23) brought in `bb8929e1`.

### Trial merges [ran, in a scratch worktree at `origin/irc-integration` `81b4cd4d`, `git merge --no-commit --no-ff`, then `git merge --abort`]
- 869 alone: "Automatic merge went well". No conflict.
- 870 (contains 869): one conflicted file, `services/tables/src/main/java/com/linkedin/openhouse/tables/services/TablesServiceImpl.java`,
  two hunks, both import blocks only. Hunk 1: our side adds `tables.formatversion.FormatVersionRule`; 870 adds
  `tables.exception.InvalidPropertyUpdateException`; keep both. Hunk 2: our side adds `java.util.UUID`,
  `java.util.function.Supplier`, `org.apache.iceberg.TableMetadata`, `org.apache.iceberg.catalog.TableIdentifier` and has
  removed `BadRequestException`, `CommitFailedException` and `CommitStateUnknownException` (moved to the commit core);
  870's side keeps those three and adds `java.util.Set` and `java.util.stream.Collectors`; resolution is our set plus
  `Set` and `Collectors`, dropping the three Iceberg exceptions unless still used (870's namespace methods do not use
  them) [inferred].
- Cause on our side: `git log --first-parent bb8929e1..origin/irc-integration -- TablesServiceImpl.java` shows
  `e2e02bfa` (X1 and W6 format v3, pull request 124), `14f23870` (V3 IRC write routes, 94), `94877d49` (V1 commit core,
  89) and `4517643b` (V2 IRC read routes, 88). The body conflict is nil: 870 adds four methods and a field; our side
  moved table writes into the commit core; git merged the bodies cleanly.
- Also touched on both sides and auto-merged: `services/tables/build.gradle` (ours: 127, 118, 102, 91) and
  `MainApplicationConfig.java` (ours: 139, the OpenAPI customiser). Not built.
- 871 (contains 870): the same single conflict; its own files (`OpenHouseCatalog.java` of the 1.5 client, touched on our
  side only by `fdc7f426`, pull request 87; `TablesApiClientFactory`, `WebClientFactory`, docs, tests) merge cleanly.
- Difficulty: trivial (minutes). Semantic risks not visible to git [inferred, not built]: (1) Iceberg 1.12 is now the
  default build (pull request 118), so 871's client code and 870's code compile against 1.12 for the first time; (2)
  every Spring context of the tables service now needs a `HouseNamespaceRepository` bean: production gets
  `MainApplicationConfig.provideNamespaceRepository`, but the H2 test application and the compatibility kit's service
  (`SpringH2TestApplication`, used by `RestCompatKitService`) would get the real House Tables client pointing at no House
  Tables, harmless until an `/iceberg` namespace route calls it; (3) `MockTablesApplication`-style test contexts that mock
  repositories may need a `@MockBean HouseNamespaceRepository` (fork pull request 57 needed exactly that) [inferred].
- Where his files touch code this effort refactored: `TablesServiceImpl.java` (V1 commit core 89, V2 and V3 IRC 88 and
  94, X1 format v3 124): his change only adds `implements NamespacesService`, the `HouseNamespaceRepository` field and
  four methods and does not touch table paths. `MainApplicationConfig.java`: his `getHtsConfiguredApiClient(int)` change
  plus two beans, ours the OpenAPI customiser (139). The House Tables client in the internal catalog: his
  `HouseNamespaceRepositoryImpl` is a new class beside `HouseTableRepositoryImpl`; no overlap.
- Test stand-in for House Tables: `HouseTablesH2Repository.java` in `tables-test-fixtures-iceberg-1.5` and the
  `services/tables` test copy (T5, pull request 83) stand in for `HouseTableRepository` only. Nothing stands in for
  `HouseNamespaceRepository`. His 870 tests avoid that with the `namespaceIntegrationTest` source set running the real
  House Tables on H2. For the kit and the H2 service tests an in-process `@Primary` stand-in (a map or an H2 table with
  case-insensitive names and a version compare-and-set, as T5 did for tables) is needed. Fork pull request 77 had
  exactly this and is the reference.

### What to do, in order of the scout's options
- (a) Recommended: merge his branches into `irc-integration` (the head of `mkuchenbecker/database-properties-client`,
  that is `pull/871/head`, as one merge commit; or 869, 870, 871 as three merges). Cost: one trivial import conflict, then
  a merge each time he pushes (the history of `irc-integration` gains his review churn, but `irc-integration` is a
  throwaway proof per S58). Keeps all 71 merges' SHAs.
- (b) Rebase or re-create `irc-integration` on his stack: 71 merge commits re-made on a new base, every merge SHA in the
  notes and records changes, the reviewers' reports cite SHAs, and every time he amends, force-pushes or rebases on main
  the whole thing repeats. Gain is only a prettier history that S58 says will be re-implemented anyway. Not recommended.
- (c) Cherry-pick or copy his files: loses the link to his branches and makes following review changes manual. Not
  recommended.
- Following his review changes: his stack is merge-based (he merges main into it), so (a) stays cheap:
  `git fetch https://github.com/linkedin/openhouse.git pull/871/head` (or the branch) and merge again. If he
  force-pushes a rewritten history a re-merge still works (git finds `bb8929e1` as base) but may re-conflict on the same
  import block. Record the merged head sha in the merge message so the next merge can show `git log <old>..<new>`. Do
  not edit his files on `irc-integration`; put what the IRC routes need (the list in House Tables, the stand-in) in our
  own files, or propose it to him as pull requests on top of his stack.
- Then the `/iceberg` namespace routes call his `NamespacesService` (existence = record or table or view; answer
  `Namespace does not exist: <ns>`); the missing pieces go in our own files: a House Tables list of records, an
  in-process stand-in for `HouseNamespaceRepository` in the H2 test application and the kit service, HEAD and list over
  records; and two open semantics go to him: drop of a populated namespace over `/iceberg`, and one base path for the
  specification routes (`/v1/{prefix}` in his pull request, `/iceberg/v1` here).
- Independent of this, merging LinkedIn main's one new commit (`c97c4c15`) into `irc-integration` is conflict-free [ran
  trial merge: "Automatic merge went well"; the file it changes was touched on our side only by `7e8941ab`, V9c, pull
  request 110].

## 4. The compatibility kit estimate [read `expected-outcomes.txt` and `CatalogTests.java` at `origin/openhouse-1.12.0`; inferred, nothing run]

- The 33 "namespace model" failures are VIEWDB 24, NSWRITE 7 and LISTNS 2. Assumed: an `/iceberg` layer over his
  `NamespacesService` with existence = record or derived, its own `Namespace does not exist: <ns>` message, and kit
  flags `supportsNamespaceProperties=true`; the catalog suite keeps `requiresNamespaceCreate=false`, the view suite
  flips it to `true`.
- Pass on his design as is, with the `/iceberg` wrapper only: `testCreateExistingNamespace` (the message "Namespace
  already exists" matches), `testDropNamespace`, `testDropNonexistentNamespace` (404 gives false),
  `testLoadNamespaceMetadata`, `listViews`, `listViewsAndTables` (an empty list once the record makes the namespace
  exist): 6.
- Need more: `testCreateNamespace` and `testListNamespaces` (both assert that `listNamespaces()` contains a created
  empty namespace: needs a House Tables list of records): 2. VIEWDB 24 need the native view create to accept a
  namespace record as existence (`ViewsServiceImpl.requireDatabaseExists`, a native behavior change under S60) plus
  the flag flip; of those, the 4 rename tests (`renameView`, `renameViewNamespaceMissing`,
  `renameViewTargetAlreadyExistsAsView`, `renameViewUsingDifferentNamespace`) would then fail on view rename (406, D47),
  so at best about 20 pass [inferred; later steps not checked].
- Ruled out by his design as is: `testDropNonEmptyNamespace` (expects `NamespaceNotEmptyException` "is not empty"; 870
  drops the record of a populated database) unless the `/iceberg` route adds a guard; the view suite's
  `dropNonEmptyNamespace` (KITNS, same reason once the flag flips).
- Currently skipped (class a) and affected: NSPROPS 7 would run and, the scout expects, pass (two of them need the name
  in the 404 message); EMPTYNS 5 are about `Namespace.empty()` (zero levels), not a namespace without tables, so his
  one-level design rules them out, and the kit file's reason "a database exists only while it holds a table or view" is
  the wrong reason for them [read `CatalogTests`: `createNamespace(Namespace.empty())`]; NESTED 2 ruled out; NOCREATE 1
  (`tableCreationWithoutNamespace`) ruled out, because in his design a table create never needs a namespace record, so
  the catalog suite must keep `requiresNamespaceCreate=false`.
- Net if everything in "need more" is done: about 6 + 2 + 20 = about 28 of the 33 pass, plus 7 NSPROPS move from
  skipped to passed; left: 2 drop-non-empty (his ruling), 4 view rename, plus anything unseen behind VIEWDB.

## 5. Inconsistencies the scout saw inside his stack [read]

- `services/housetables/NAMESPACES.md` (869) still says "The public API rejects drop while a table or view occupies the
  namespace", and 869's body says the same; 870 removed that check and its `services/tables/NAMESPACES.md` says drop
  does not inspect tables.
- 871 still maps DROP 400 to `NamespaceNotEmptyException`.
- 871's title promises effective-property resolution it does not contain.

## 6. The earlier drafts on the fork and their verdicts [read pull request bodies, the 76 plan document on `origin/claude/peaceful-clarke-h11mw8:docs/development/database-properties-implementation-plan.md`; code spot-checked where marked]

How they relate to 869 to 871 [read]:
- Two generations on the fork. August line (57 -> 59 -> 61 -> 63 -> 64 -> 65 -> 67, designs 54, 56): "namespaces as
  stored objects" as the source of truth for database existence: `database_row` keyed by the encoded name (no UUID), a
  registrar on every table-creating path (59), a bulk backfill with a verification marker (61), the derived fallback
  deleted and a read gate (63), multi-level nesting with dot-join persistence, `max-depth`, a metadata-table
  discriminator, `%1F` wire handling (64, 65, 67), reserved keys `openhouse.*` and `policies`, property bounds 100
  entries, 1 KiB and 8 KiB, a drop guard (409 when tables exist), all six specification routes plus `/v1/config` under
  `/v1/{prefix}`. November line (76 plan, 77 implementation, both 2026-11-05): "database properties", explicitly scoped
  down; the 76 plan says the August work "differs from this brief in ways the brief rules out ... This plan does not
  build on that branch", lifting only pieces as reference. 77 implements it in 4 commits (House Tables `DatabaseRow` with
  UUID, tables CRUD under `/v1/databases`, client `getEffectiveTableProperties`).
- Upstream 869 to 871 (also opened 2026-11-05, reworked through 2026-11-24) are the cleaned-up successors of 76 and 77,
  not of 57. Since 77: routes moved from `/v1/databases` to the specification paths `/v1/{prefix}/namespaces` with the
  Iceberg error shape; reads need `GET_TABLE_METADATA` (77: authentication only); entity renamed Namespace, table
  `catalog_namespaces`; the drop occupancy check (in the 76 plan, from pull request 56's W5) was removed;
  `getEffectiveTableProperties` was dropped from 871; the H2 `@Primary` stand-ins of 77 were replaced by a separate
  real-House-Tables integration source set; retries disabled; no property bounds, no reserved keys. So: a different,
  much narrower design than the August line; a subset of 76 and 77 plus the specification route shape.

| Pull request | What it did | Equivalent in `irc-integration` | Differences that matter | Verdict, and what to adopt |
|---|---|---|---|---|
| 60 | `UpdateRequirementValidator` and `MetadataUpdateApplier` in internalcatalog, delegating to Iceberg's `validate` and `applyTo`, but (1) a null base gives `CommitFailedException` instead of an NPE, (2) `AssertViewUUID`, `AddViewVersion`, `SetCurrentViewVersion` on a table give `ValidationException` (400), (3) an allow-list of update types with a guard test tied to the classpath, an unknown type refused naming the class | `IcebergRestRequirements.validate` (loops `requirement.validate(base)`) and `IcebergRestWriteHandler.commitOn` and `commitCreate` (`update.applyTo(builder)`) [read] | Ours has no allow-list and no null-base guard: `commitCreate` passes `existing.orElse(null)`, so a create transaction carrying for example `assert-table-uuid` throws an NPE; a view update in a table commit throws `UnsupportedOperationException` from Iceberg's default `applyTo`, which `IcebergRestErrors` maps to 500 `ServiceFailureException` (a 1.12 client may treat a commit 500 as state unknown) [read; inferred, not run] | 60 is better on these three points. Adopt: the null-base guard (409), 400 for view-typed requirements and updates on a table, an allow-list of update types with the classpath guard test |
| 62 | Ran the native whole-document commit path through the 60 engine (`WholeDocumentCommitDeriver`), no behavior change, 526 tests untouched | V1 commit core (89) "native table writes run through one commit core that also takes a candidate": a different seam (candidate metadata into the native rules), not requirements and updates [read merge title, D19, D20] | 62 makes `requirements[]` plus `updates[]` the internal representation (the 55 design); ours keeps the native document as the representation and checks IRC requirements in the IRC layer | Equivalent for the goal; ours is merged and reviewed against the kit and the smoke suite. Keep ours; 62's derivation table is a useful checklist for the re-implementation (S58) |
| 66 | IRC write routes (create, stage-create, commit, drop) at `/v1/{prefix}`, server defaults for clusterId, baseTableVersion and tableType, an `IS_REST_COMMIT_KEY` seam bypassing `abortIfWriterBaseDivergedFromCatalog` and `failIfRetryUpdate`, `IcebergRestPolicyMerger`, drop always purges, an empty `pageToken=` accepted, a bad identifier on a table route = 404, OpenHouse exceptions mapped (not 500); the commit response is a fresh `loadTable` | `IcebergRestWriteController` and `IcebergRestWriteHandler` (V3, 94), `PolicyPatch`, D22 (drop always purges), V13b specification fixes (106), `tableDatabaseId` (404 for multi-level table namespaces) [read]; our commit response is also a fresh load | The same choices nearly point for point. Ours is under `/iceberg` and keeps register, rename, views and transactions | Equivalent. Nothing to adopt beyond what 60 adds. 66's note that the fresh-load response can return metadata newer than the commit under a concurrent write applies to ours too (open item) |
| 68 | `renameTable` route via `TablesApiHandler.renameTable` (keeps the audit pair), cross-database rename 400 after a destination-namespace 404 check; metrics accepted and discarded without a table lookup; registerTable declined (adopting a file the catalog did not write gives it authority to delete files it never placed) | Rename: `IcebergRestWriteHandler` drop-and-rename section (404 target namespace, 406 cross-database, 409 when a view holds the name). Metrics: `reportMetrics` with `GET_TABLE_METADATA` (V6 and V7, 112). Register: served, restricted to directories the server allocated to that table and no table uses (`registerTable`, `refuseADirectoryInUse`, `requireSnapshotsIn`) [read] | Register: ours answers 68's objection (the file must lie in a server-allocated, unused directory), so the authority concern is met. Cross-database rename: ours 406, 68 400 (the specification lists 406) | `irc-integration` is better on register and on the 406. Equivalent otherwise |
| 69 | Commit conflict message "Cannot commit: Requirement failed: ..."; a missing metadata.json gives 404 `NotFoundException` (not 500); the harness configures `table-default.*` and `table-override.*` | Kit NOTFOUND row: `testLoadTableWithMissingMetadataFile` still fails, "the server answers 500" [read expected-outcomes] | 69 fixed what our kit still lists as class e | Adopt 69's (and 72's, below) missing-file 404 on the IRC routes: one kit test, small |
| 70 | Decision record (agent-authored, no owner ruling recorded): decline scan planning (runner-up: synchronous-only `planTableScan`, no plan store) and decline credentials and auth (runner-up: `loadCredentials` plus access delegation for S3 and ADLS) | D44 (synchronous `POST .../plan`, no state, the same as 70's runner-up), D45 (`GET .../credentials` an empty list), V11a and V12a (127) [read decisions] | The opposite conclusion on both. 70's reasons (the control plane turning into a data path; no per-table storage principal) are partly reflected in D44 and D45 (no state; no vending) | Not an owner decision, so it does not override. Flag to the owner that an earlier draft declined both and why; our choice is a two-way door (the endpoint list) |
| 71 | Decision record: defer the eight REST view operations; "OpenHouse persists no view anywhere" | V4 IRC views (95), D27, D28, D52; views are now persisted natively on LinkedIn main (757, 843, 728, 694) | 71's premise is obsolete: LinkedIn main persists views in House Tables since then | `irc-integration` is better (built on what landed). 71's follow-on, "the Spark plugin's `iceberg-views-enabled` flag enables a mock in-memory view store", should be re-checked against main [not checked] |
| 72 | A retry budget only for failures a re-read could answer (settled metadata failures not retried 20 times: 365 s to 0.5 s in four tests); `TableMetadataFileNotFoundException` (a subtype of `InvalidTableMetadataException`) mapped to 404 on the REST edge; `NamespaceUtil.encode` as the one persistence encoder; the depth rule stated once | `OpenHouseInternalTableOperations.refreshMetadata` still calls `refreshFromMetadataLocation(metadataLoc, null, 20, ...)` and classifies failures after the fact (`classifyMetadataRefreshFailure`) [read] | Ours retries settled failures; a missing file is 500 on IRC | Adopt the 404 subtype (IRC only; native stays 500, as 72 itself preserved). The retry predicate is a native latency change: adopt only with the S60 justification (it changes no answer, only time) |
| 55 | Design: requirements and updates as the authoritative internal commit; a client router `OpenHouseCatalog` over the plugin leg plus `OpenHouseRESTCatalog extends RESTCatalog`; owner constraint "canary arbitrarily long, rollback by restart may never complete" | The seam and client design in `07-seam-design.md`, D1, the C2 capability resolver [read titles] | A different internal representation (see 62). The owner constraint applies to any switch this effort adds (format v3, namespaces) | Use as reference; apply the owner constraint (section 7, O10) |
| 53 | Sequencing: first-class namespaces, native commit path, a catalog-level switch (not a table property) with `/v1/config` overrides as the rollout lever; six owner questions | `00-map.md` and `04-work.md` sequencing | Its open questions 1, 2, 5, 6 are now partly answered by 869 to 871 (one level; drop does not cascade, and does not guard either; no backfill: records optional) | Reference only |
| 34 | `tests/iceberg-rest-catalog-compat`: Iceberg 1.11 `CatalogTests` against an in-process server (relocated 1.5.2 server classes), `@Disabled` per missing endpoint as roadmap. Stacked on upstream cbb330's 689 to 691 | `integrations/java/openhouse-rest-compat-kit`, running Apache's RCK (`RESTCompatibilityKitCatalogTests` and the view suite) unchanged, out of process, with a reviewed `expected-outcomes.txt` and capability flags [read] | Ours runs both suites, no `@Disabled`, one reviewed list, flags documented. 34 runs in-process (faster) but relocates classes | `irc-integration` is better. Fix in ours: the EMPTYNS reason text is wrong (section 4) |
| 35 | Commit-protocol analysis (nine blocking defects beside the commit point: a post-commit `IOException` reporting a cleanable failure for a committed write, an in-place rewrite of a committed metadata.json, an unguarded rename), a TLA+ model, a REST-native commit prototype | V1 commit core; X10 concurrency (130); T5 stand-in compare-and-set | The scout did not check which of the nine are fixed on `irc-integration` [not read] | Worth a reviewer pass: take 35's nine-item register as a checklist against the commit core |
| 36, 37 | Rename guard: a version- and metadata-location-conditional `renameTableId`, 409 on a lost race, `HouseTableRepository.rename` carries the expected base, both H2 fixtures updated (37: explicit token parameter, fixture conflict branch covered). Upstream twin: 700 | None. `UserTableHtsJdbcRepository.renameTableId` on `irc-integration` is still unconditional (only a table-type predicate) [read lines 300 to 329]; the IRC rename route uses it; T5 notes "Rename and restore stay unchecked in the stand-in, as in the real store" | The race (a rename overwriting a concurrent commit's `metadataLocation`) is reachable over IRC as natively | 36, 37 and 700 are better. Adopt via upstream 700 when refreshed (section 8) |
| 41 | Evidence archive for 35 | none | none | Reference |
| 43, 45, 46, 47 | Carbon copies of upstream ruolin59's 694 (views `/v2` contract), 696 (entityType discriminator), 697 (typed House Tables view lifecycle), 698 (MySQL end-to-end) | Upstream landed 694 (as `/v1` views), 696, then 757, 728 and 843 instead of 697 and 698 (closed unmerged) [ran gh]; `irc-integration` builds on what landed | 46 and 47's typed `/hts/views` endpoints and the MySQL end-to-end (DDL validated against real MySQL) did not land upstream | `irc-integration` matches main. The MySQL end-to-end recipe (47) is the reference for fault 5 (nothing ran against real House Tables storage) |
| 44, 52 | Views as an Iceberg REST surface instead of bespoke `/v2`; open owner items including "whether to re-ratify `rename-view` as in scope"; 52: dialect matching became case-insensitive, unrecorded | IRC view routes under `/iceberg` over the native views service; D27 and D47 (view rename 406, put to Mike) | 44's question on rename-view is the same as D47, still open | Equivalent; D47 stands |
| 57 (and 59, 61, 63, 64, 65, 67, 54, 56) | The namespace line described above | none (namespace writes are 406) | Superseded by 869 to 871 for the data model; still the richest reference for the IRC-side behaviors his design leaves open: the union of stored and derived existence (57 B3), stored spelling in answers (B6), reserved keys and bounds (B7), the drop occupancy check reading the table store (63), `%1F` decoding for nested namespaces (65), the PyIceberg endpoint golden (57) | Use as reference for the `/iceberg` layer over his service; do not adopt its data model, registrar, backfill or nesting (his design rules them out) |

No fork draft has a comment or review by anyone [ran gh: zero comments and reviews on 34, 44, 54, 56, 57, 60, 62, 66,
68 to 72, 76, 77; only 36 has review comments].

## 7. Decisions the owner already made in that earlier work

Only decisions stated as his (an "owner ruling" or "owner constraint" in a pull request body, or his own upstream pull
request) are listed as his. Agent recommendations in fork drafts (70, 71, 68's register decline, 72) are not his.

| # | Decision | Source | On `irc-integration` |
|---|---|---|---|
| O1 | A namespace record is optional metadata, independent of tables: it may exist with no table, table operations never consult it, a table may exist without it | 869 body: "Tables can exist without that record, and table creation remains independent of namespace deletion."; 870: "namespace records are optional defaults, not parent records"; `NAMESPACES.md`: "A namespace record is not a mandatory parent of a table." | Contradicts: the kit flags and D32's working reading treat "a database exists only while it holds a table or view" as an OpenHouse rule; `supportsNamespaceProperties()` says "OpenHouse databases carry no properties" |
| O2 | Empty databases are desired and explicit creation is a goal | Fork 56 body, "Latest revision: owner rulings applied", ruling 3: "Empty databases are desired, and explicit creation is in scope ... The database outliving its last table is intended behaviour"; "Explicit creation is a goal" | Contradicts D20 ("Namespace create, update and drop stay unsupported, as natively") and D32's working reading |
| O3 | Drop of a populated database: fork 56 ruling W5 "the error, never a cascade" (a populated drop is 409 `NamespaceNotEmptyException`); upstream 870 (later, his own pull request): drop "does not list, delete, or modify tables or views ... Tables and views can remain after metadata is dropped" | 56 body; the 76 plan adopted W5; 870 body "Drop and table independence" and "The review update removes the namespace occupancy query" | Silent (drop not served). His two statements conflict for the IRC route: 870 never cascades (consistent with W5's "never a cascade") but no longer errors. Question for him: may `/iceberg` DELETE refuse a populated namespace (the specification and the kit expect 409) while his `/v1` route does not? |
| O4 | One level; names `[a-zA-Z0-9_]{1,128}`; spelling preserved, identity case-insensitive | 870 body "Namespace names contain 1 to 128 ASCII letters, digits, or underscores. Creation preserves submitted spelling. Lookup ... use case-insensitive identity" | Agrees (one level, same charset). Supersedes the fork's multi-level line (54, 64, 65, 67) for now |
| O5 | Mutations need `SYSTEM_ADMIN`; reads need `GET_TABLE_METADATA`; authorization on the lowercased database name | 869 and 870 bodies; `TablesServiceImpl.authorizeNamespace` | Silent. An `/iceberg` namespace write route must use the same privileges (not `CREATE_TABLE`, which fork 54 had proposed for create) |
| O6 | The Iceberg namespace routes are served at `/v1/{prefix}/namespaces` in the tables service, with the Iceberg `ErrorModel` body, `{prefix}` = the client's catalog name | 870 `IcebergNamespacesController`; `services/tables/NAMESPACES.md` citing the Apache Iceberg 1.5.2 REST specification. Earlier drafts (34, 57, 66) and cbb330's upstream 689 to 691 also used `/v1/{prefix}` | Differs: D16 mounts all IRC routes under `/iceberg/v1` with no `{prefix}`. Not contradicted outright (both can coexist; no path collision), but his latest pull request places the specification routes at the root. Question for him: one base for the specification routes, or both? |
| O7 | Effective properties: namespace defaults combine with table properties only for consumers that opt in, table values win; existing consumers and all table operations are unchanged; changing defaults rewrites no table | 869 "Purpose"; 870 "Summary"; fork 54 owner ruling on OQ-2: "you set the namespace default after enabling each thing individually". 871 does not implement the resolution | Silent. Consequence: `/iceberg` loadTable must not merge namespace properties into table properties, and an IRC create must not copy them in |
| O8 | No automatic retries on namespace writes; a lost acknowledgement leaves the outcome unknown and the caller must re-read | 869 "HTS does not retry automatically"; 870 "Each namespace request has one transport attempt"; `NAMESPACE_TOTAL_TRANSPORT_ATTEMPTS = 1` | Silent. The `/iceberg` layer must not add retries over his service |
| O9 | Concurrency of namespace updates: an optimistic version, 409 `CommitFailedException` on a lost race, no server retry | 869 commit `524be665`; 870 "Concurrent update or drop conflicts return 409 with `CommitFailedException`" (answers cbb330's review) | Agrees with D20's mapping style |
| O10 | Rollback must never depend on a restart; every intermediate state must be indefinitely sustainable; no promotion gate is a duration | Fork 56 ruling 1 and 1b; fork 55 "Owner constraint applied throughout" | Partly silent: the cluster switches this effort added (format v3 D42, variant D48) are `ClusterProperties` values read at startup [read: `@Value("${cluster.iceberg.format-v3.enabled:false}")`]. Under his constraint a switch that needs a restart to roll back is not a rollback. Check before adding a namespace switch: his 869 to 871 add none |
| O11 | `compare` modes read the real store, not a cache (control-surface ruling) | Fork 56 ruling 2 | Not applicable now (no resolver or cache in 869 to 871) |
| O12 | JDBC House Tables only for namespaces; the deprecated Iceberg-backed House Tables returns 500 | 869 body | Silent |

Decisions of this effort that are now wrong or need revisiting, as the scout lists them:
- D20, last clause "Namespace create, update and drop stay unsupported, as natively": wrong under S57 and O1, O2.
- D32's working reading, the item "databases that exist only while they hold tables or views" in the list of OpenHouse
  rules: wrong under O1, O2 (records exist without tables); the rest of D32 is untouched.
- D16 (IRC routes under `/iceberg`): not wrong, but O6 puts his specification namespace routes at the root; needs his call.
- The kit flag reasons in `OpenHouseRestCompatibilityKitCatalogTests` (`supportsNamespaceProperties`,
  `supportsEmptyNamespace`, `requiresNamespaceCreate`) and the EMPTYNS reason in `expected-outcomes.txt` are wrong or
  will be once his stack is merged.
- D44 and D45 conflict with fork 70's recommendation, and D52 and V6 register with fork 68's, but neither draft is an
  owner ruling; no change required, worth one line to him.

## 8. Overlap with his other upstream pull requests [ran gh and trial merges onto `origin/irc-integration` in the scratch worktree, each aborted]

| Pull request | State | What it does | Overlap with `irc-integration` | Trial merge | What to do |
|---|---|---|---|---|---|
| 700 fix: guard table rename against concurrent commits | draft, base main, last update 2026-08-27, merge base 97 commits behind main | `renameTableId` conditional on `@Version` plus an optional `expectedMetadataLocation`, 409 to `CommitFailedException`; H2 fixtures updated. The same as fork 36 and 37 | `irc-integration` serves rename over IRC on the same unconditional `renameTableId` (it only gained a table-type predicate on main through the views work); the stand-in rename is unchecked (T5). Not duplicated, not fixed | 7 conflicted files: House Tables `OpenHouseUserTableHtsApiHandler`, `UserTableHtsJdbcRepository`, `UserTablesServiceImpl`, two House Tables tests, `services/tables/.../e2e/h2/HouseTablesH2Repository.java` (T5, 83), and `tables-test-fixtures-iceberg-1.2/.../HouseTablesH2Repository.java` (deleted on our side by 118). Most of the House Tables conflicts come from LinkedIn main moving (views typed House Tables access, 757), not from this effort | Ask him to refresh 700 on main (his pull request); then merge it into `irc-integration` like 869 to 871. Until then, record the IRC rename race as a known inherited defect |
| 756 Add table lock and unlock DDL | draft, base `mkuchenb/pr-726-lock-reason-base`, 2026-09-18, 84 behind | `ALTER TABLE ... LOCK/UNLOCK` SQL, lock client | Main already has 726 lock reasons, 737 SYSTEM_ONLY, 787 UNLOCK DDL; `irc-integration` added the IRC unlock route (V5-3, 102) and the Spark 4.2 extension copies (N3x1) | 33 conflicted files (Spark 3.5 and 4.2 grammar, parser and strategy, lock client, tables lock service and tests): mostly superseded content | Nothing now. If he revives LOCK DDL, the Spark 4.2 grammar copy on our side needs the same rule |
| 741, 742, 743, 744 integration tests (shared execution core, docs, standard DML, RTAS) | 741 to 743 open, 744 draft; stacked; 2026-10-22; 44 behind | A Scala scenario harness (40 files, +5809) running DML and RTAS through Spark on Parquet and ORC | `irc-integration`'s smoke suite (`06-smoke-suite.md`) and the Spark 3.5 and 4.2 IRC tests cover similar ground; no file overlap | 741, 743 and 744 each: 2 conflicts, `build.gradle` and `settings.gradle` (module registration only) | Reference or adopt: running his scenario core over the IRC catalog (Spark 4.2) would give DML and RTAS evidence per S58 "the durable value is the tests". Not urgent |
| 657 black-box RTAS suite | draft, 2026-07-28, 113 behind | Spark SQL RTAS contract tests (may drop or add a column, repartition, keeps identity) | Kit RTAS class b (16 tests): replace needs `replace.enabled=true`; our replace detection D22 | 1 conflict: `integrations/spark/spark-3.5/openhouse-spark-itest/build.gradle` | Use as the native RTAS contract when checking IRC replace; no merge needed |
| 661 VACUUM SQL extension | draft, base main at `c97c4c15`, updated 2026-11-27 (active) | Spark 3.5 `VACUUM <t> [REMOVE ORPHAN FILES] [RETAIN n HOURS]` | D50 maintenance jobs on Spark 4.2 over IRC; our Spark 4.2 extension copy | 1 conflict: Spark 3.5 `OpenhouseDataSourceV2Strategy.scala` | Track; if it lands, the Spark 4.2 copy needs it too |
| 662, 663 OPTIMIZE, ANALYZE extensions | drafts, stacked on 660, 2026-07-31, 113 behind | Spark 3.5 SQL maintenance extensions | as 661 | not tried (stacked on 660, stale) | Track only |
| 905, 923, 906, 907, 908, 909, 924 temporary tables | open (905 base main), updated 2026-11-27 (active) | `openhouse.temporary` and `openhouse.expirationTime` exempted from preserved keys, rules in the commit path (`OpenHouseInternalTableOperations`), validator pairs (no replication, WAP, lock or retention), optimizer recognition and deletion jobs | Directly on the commit path this effort refactored (V1 commit core) and on the IRC write rules (preserved keys, policies over IRC) | 905: clean. 923: 3 conflicts (`OpenHouseInternalRepositoryImpl.java`, `TablesServiceImpl.java`, Spark itest `ExpiredTemporaryTableTest.java`). 907: 4 (adds `OpenHouseInternalTableOperations.java`) | A second live stack of his touching our commit core. Merge it the same way when it settles; until then expect his rules to apply over IRC automatically where they sit in `OpenHouseInternalTableOperations`, and not where they sit in the native validator or repository (923), which the IRC path reuses only partly [inferred; check per rule] |
| 608 post-commit stats push (wip, no review) | draft, 2026-05-28 | A post-commit operations framework | commit path | 7 conflicts (optimizer app, `InternalRepositoryUtils`, `IcebergSnapshotsServiceImpl`) | Ignore (wip) |
| 627 InHouse Replication (wip) | draft, 2026-06-01 | POC docs and scripts | none | not tried | Ignore |

Also relevant, not his: cbb330's upstream 689, 690 and 691 "Iceberg REST" parts 1 to 3 (a read-only facade, the
full Iceberg 1.11 OpenAPI vendored with `x-openhouse-support` markers, codegen), open since 2026-08, mergeable state
blocked, dirty or behind. Fork 34 was stacked on them. `irc-integration` is a different, hand-written facade under
`/iceberg`; if upstream revives 689 to 691, the two facades collide in purpose (not in paths). cbb330 also reviewed 869
(asked for the 409 and `CommitFailedException` model, which was adopted).

## 9. What the scout did not read

- Full diffs of the tests of 869 to 871 (it read file lists, all main code, docs and bodies); 871's
  `executeNamespaceRequest` past the 401 case.
- The fork 54, 55 and 56 design documents themselves (only the pull request bodies, which carry the owner rulings); the
  76 plan in part (sections 1 and 5 excerpts).
- The fork drafts' diffs for 57 to 72 (bodies only); 35's nine-defect register was not checked against `irc-integration`.
- Fork 44's plan documents (`views-spec-conformance-plan.md` and others) and 52's full delta.
- Upstream 742's body; 906, 908, 909 and 924 beyond file lists; 660 (base of 662 and 663).
- No build or test was run; every "would pass" in section 4 is inferred from test source and route code.
- Whether the 1.12 default build compiles 870 and 871 (not built, by instruction).

Housekeeping the scout reported [ran]: the scratch worktree `/home/user/oh-scout` was removed and nothing was committed
in it. Left in `/home/user/oh-c1`: local branches `scout/up-869`, `scout/up-870`, `scout/up-871` (heads `39f23249`,
`e09a9625`, `464a220d`), kept for the merge into `irc-integration`; the other `scout/up-*` branches were deleted
(refetch with `git fetch https://github.com/linkedin/openhouse.git pull/N/head:scout/up-N`). Remote-tracking refs were
also fetched: `upstream/main` (now `c97c4c15`) and `origin/` branches `claude/peaceful-clarke-h11mw8`,
`claude/db-properties-impl`, `claude/namespace-impl-v2`, `claude/ws1-*`, `claude/ws2-*`,
`claude/multi-level-namespaces`, `claude/database-abstraction`.
