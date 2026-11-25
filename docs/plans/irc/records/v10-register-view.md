# V10: register-view over the Iceberg REST routes

Bottom line: register-view is served on the Iceberg 1.12 line, in place, with House Tables calls that already exist
(the view row PUT the view commit engine makes for a create, and the existing lookups); no new House Tables API or
query. On LinkedIn's 1.5.2 line it keeps answering 406 and is not listed. Kit outcomes unchanged (42/95/23).

Worktree `/home/user/oh-regview`, branch `irc/v10-register-view` from `origin/irc-integration` `2ba15929`.
Commits: `66a501ff` (tests only), `244fd745` (implementation), `5c06dd33` (two existing 406 pins moved to 1.5.2 only).
Draft pull request 149; first head 5c06dd33 green; current head 1ae8e370 (review round below) green.

## What the specification and the 1.12 client have (read)
- Fork 1.12 (`origin/openhouse-1.12.0`): `Endpoint.V1_REGISTER_VIEW` = `POST /v1/{prefix}/namespaces/{namespace}/register-view`,
  `ResourcePaths.registerView`, `RESTSessionCatalog.registerView` (checks the endpoint list first, then posts
  `RegisterViewRequest{name, metadata-location}`, expects `LoadViewResponse`, view error handler),
  `RESTCatalog.registerView`, `CatalogHandlers.registerView`, `RegisterViewRequest` and its parser.
  `ViewCatalogTests` has `registerView`, `registerExistingView`, `registerViewThatAlreadyExistsAsTable`.
- The fork's OpenAPI document `open-api/rest-catalog-open-api.yaml` has NO register-view operation (only table
  register). The route follows the Java client and `Endpoint`.
- LinkedIn 1.5.2: no `RegisterViewRequest`; its client has no registerView.

## What is served (1.12 line)
`POST /iceberg/v1/namespaces/{ns}/register-view`, `@Secured(AUTHENTICATED_VIEWS_OPS)` like the other view routes.
200 `LoadViewResponse`: metadata-location = the file named, metadata = that file. Checks in the native view create's
order (`ViewsServiceImpl.putView`), then the file:
| case | answer |
|---|---|
| invalid view id | 400 BadRequestException (native validator) |
| views disabled for the database | 404 NoSuchNamespaceException "Views are disabled" (as create) |
| database missing | 404 NoSuchNamespaceException "Database not found: db" |
| denied (CREATE_TABLE on the database) | 403 ForbiddenException, native create's message |
| name is a view | 409 AlreadyExistsException "View already exists: db.v" |
| name is a table | 409 AlreadyExistsException "Name already exists as a table: db.v" |
| file not `.metadata.json` directly in the directory allocated to (db, view, UUID of `<view>-<uuid>`) | 400 |
| a table, soft-deleted table or view row points into that directory or has that UUID | 400 |
| no file | 400 "there is no file at ..." |
| not view metadata | 400 |
| view metadata whose UUID, location, OpenHouse identity properties, pointer property, creator, source dialect or creation time are not what the engine writes for this view at this file | 400 |
| concurrent create of the name at the row write | 409 |
All 400s: "Cannot register view db.v: ...", without the file's content.

## Decisions (two-way doors)
- Kept in place, not rewritten: a rewrite through the engine's create would make a new version 1 and lose versions
  and history. Test: `aDroppedViewRegisteredFromItsMetadataFileIsTheViewItWas`, `aViewWithSeveralVersionsComesBackWithAllOfThem`.
- UUID kept: it must be the directory's UUID. Test: `metadataOpenHouseDidNotWriteForThatViewThereIs400`.
- Directory-in-use: the table register's rule, plus view rows, with existing lookups (`findAllByDatabaseId`,
  `searchSoftDeletedTables`, `findAllViewsByDatabaseId` paged). Views are neither renamed nor soft-deleted, so for views
  only live rows matter; a renamed-away or soft-deleted table holding the directory is caught by UUID or location.
  Test: `aDirectoryAnotherRowUsesIs400` (a view row only; table cases inferred from the same code).
- Creation time and creator: the file's (as a replace keeps them). Row tableVersion: INITIAL_VERSION, as a create's.
- Code under `src/iceberg-1.12` (controller and registration), like scan planning: 1.5.2 build unchanged.

## Known gaps
- Not in the view operation audit (`ViewAuditAspect` advises `ViewsApiHandler` only); the request-level audit is
  inferred to record it, not tested.
- The directory-in-use check lists every row of the database (as the table register, V17).
- Kit's register tests still fail earlier (a view cannot create its database), so the kit does not reach the route.

## Runs (all ran locally)
- Kit default line before and after: 160 / 42 passed / 95 failed / 23 skipped, list unchanged.
- Default line `--tests '*.Irc*' --tests '*View*'` in services:tables: 863 tests, 1 failed (IrcReadRoutesTest pinned
  register-view 406); fixed in 5c06dd33; then IrcReadRoutesTest 23/23 and IcebergRestRoutesBoundaryTest 5/5.
- Reference line: IrcReadRoutes 23, IrcConfigEndpoints 7, IrcViewRoutes 33, IrcViewRoutesAuth 4, IrcRegisterRoute 17,
  IcebergRestRoutesBoundary 5: all pass.
- check-test-groups.py (default line): partition holds. origin/irc-integration had nothing new (no merge).
- Full suites left to CI: `Default build, rest`, `Reference build, rest`.

## Review round (coordinator's list), 2026-11-25
Merged origin/irc-integration cafcc9da (eadb1783). Commits: b78de07f (tests first), 1ae8e370 (fixes).
Decisions recorded as given by the coordinator:
- A. Audited as a CREATE by the acting principal (ViewAuditAspect.auditCreate). Reason: anyone with CREATE_TABLE can
  bring back a view another principal dropped; the view keeps the file's creator, so the registrant must be
  accountable beyond the request audit. Test: IrcRegisterViewAuthTest.theRegistrantIsAuditedAndTheViewKeepsItsCreatorAndCreationTime.
- B. Older files of a dropped view stay registrable. Reason: that SQL was the view's once, the registrant could create
  any SQL natively, and the audit names them. Owner's design question (resurrection). Test: anOlderFileOfADroppedViewRegistersAsThatVersion.
- C. The current version must pass the native create validation (validateCreateView) and hold no case-duplicated
  OpenHouse key. Reason: content trust rests on storage permissions outside the repository. Tests: aDialectACreateRefusesIs400,
  duplicateDialectsAre400 (Iceberg's reader accepts the file; the validator refuses), aDefaultNamespaceACreateRefusesIs400,
  aSchemaACreateRefusesIs400, metadataOpenHouseDidNotWriteForThatViewThereIs400 (case-duplicated key).
F1 lastModifiedTime must be a number; response built from the metadata read (no second read). F2 engine row limit
(aLocationLongerThanTheRowHoldsIsTheEnginesRefusal). F3 refused, naming the stored spelling
(theSameFileSpelledOtherwiseIs400NamingTheSpellingToUse). F4 409 names the winner (IrcRegisterViewRaceTest).
U1-U7 tests: see the final report.
Runs: default line Irc*+View* 878 + boundary 5 = 883 pass; kit 160/42/95/23 unchanged; reference line 141 pass
(touched classes + view audit tests + boundary); test groups hold. Head 1ae8e370656d54b93142ee8d062f704309986064:
CI 13 success, 1 skipped by design.
