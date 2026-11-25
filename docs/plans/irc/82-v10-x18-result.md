# Result of V10 (register-view) and X18 (pull requests 149 and 150)

Bottom line: both merged into `irc-integration` on 2026-11-25. Register-view (the rest of V10, pull request 149, merge
commit `81b4cd4d`) is served on the Iceberg 1.12 line, in place, using House Tables calls that already exist; on
LinkedIn's 1.5.2 line it still answers 406 and is not listed. An independent read-only review found no way past the path,
authorization or content checks, and found gaps that the author closed before the merge. X18 (pull request 150, merge
commit `cafcc9da`) turned off H2's result reuse in the tables service's own test stand-in and listed every other embedded
database in the tests; only test sources changed. Marks: [ran], [read], [inferred]; facts the orchestrator read are said
so. Records: `v10-register-view.md` and `compaction-partial-progress-409.md` (follow-up section) in the reports
directory, and the two merge messages.

## V10: register-view

Bottom line: a dropped view can be brought back from its metadata file as that file records it; the decisions are D52.

### What the client has and what the fork's document lacks [read]

- The 1.12 fork has `Endpoint.V1_REGISTER_VIEW` (`POST /v1/{prefix}/namespaces/{namespace}/register-view`),
  `RESTSessionCatalog.registerView` (it checks the endpoint list first), `RegisterViewRequest{name, metadata-location}`,
  and the client tests `registerView`, `registerExistingView` and `registerViewThatAlreadyExistsAsTable`.
- The fork's OpenAPI document has no register-view operation, only the table register. The route follows the Java client.
- LinkedIn's 1.5.2 client has no `registerView`.

### What is served

`POST /iceberg/v1/namespaces/{ns}/register-view` on the 1.12 line, secured like the other view routes. It answers 200
with the view as the file records it (metadata location is the file named). The view row is written by the view commit
engine's create, so no House Tables API is new. The code is under `src/iceberg-1.12`, so the 1.5.2 build is unchanged. The
native view audit gained one method and nothing existing in it changed; the orchestrator read that diff [read].

### Refusals [ran, by the route tests]

| Case | Status and error type |
| --- | --- |
| Invalid view id | 400 BadRequestException |
| Views disabled for the database | 404 NoSuchNamespaceException |
| Database missing | 404 NoSuchNamespaceException |
| Caller lacks CREATE_TABLE on the database | 403 ForbiddenException |
| Name is a view | 409 AlreadyExistsException |
| Name is a table | 409 AlreadyExistsException |
| File not directly in the directory the server allocates to this view, or spelled other than the engine stores it (the answer names the spelling to use) | 400 |
| A table, soft-deleted table or view row uses that directory or UUID | 400 |
| No file, not view metadata, or identity (UUID, location, OpenHouse properties, creator, creation time) not as the engine writes it | 400 |
| Current version a native create would refuse (dialect, default namespace, schema, duplicated dialects, case-duplicated key) | 400 |
| Location longer than the House Tables row holds | 400, the engine's refusal |
| A create of the name wins at the row write | 409 naming the winner |

All 400s read "Cannot register view db.v: ..." and do not repeat the file's content. No file is opened before the caller is
authorized.

### How the two lines differ

| | Iceberg 1.12 (default) | LinkedIn 1.5.2 (reference) |
| --- | --- | --- |
| Route | served | 406 |
| Listed in the config endpoints | yes | no |

### What the review found and how each finding was closed

An agent that had not written the code reviewed pull request 149 read-only before the merge, because the route is a new
write route that takes a file location from the caller (orchestrator's account). The last two commits close every item
(`b78de07f` tests first, `1ae8e370` fixes).

| Finding | Closed by |
| --- | --- |
| No audit record naming the registrant | `IrcRegisterViewAuthTest.theRegistrantIsAuditedAndTheViewKeepsItsCreatorAndCreationTime` |
| Validations a native create runs were skipped | `aDialectACreateRefusesIs400`, `duplicateDialectsAre400`, `aDefaultNamespaceACreateRefusesIs400`, `aSchemaACreateRefusesIs400`, `metadataOpenHouseDidNotWriteForThatViewThereIs400` |
| Half-success when the response load failed after the row was saved | response built from the metadata already read, no second read; test name not in the record |
| House Tables row limit not checked | `aLocationLongerThanTheRowHoldsIsTheEnginesRefusal` |
| Misleading refusal for a fully-qualified location | `theSameFileSpelledOtherwiseIs400NamingTheSpellingToUse` |
| Seven claims no test pinned | seven new tests (U1 to U7 in the record); names not in the record |
| (also changed) a lost create race gave no winner | `IrcRegisterViewRaceTest`, 409 naming the winner |

### Compatibility kit

Unchanged: 160 tests, 42 passed, 95 failed, 23 skipped, the reviewed list untouched [ran]. The kit's register tests fail
before they reach the route, because a view cannot create its database there.

### Known gaps

- Anyone with CREATE_TABLE on a database can bring back a view another principal dropped, from any of its files; the audit
  names the registrant and the view keeps its creator. Put to Mike (D52, `58-waiting-on-mike.md`).
- The content checks assume only the service writes into a view directory; that rests on storage permissions outside the
  repository. Validating the file as a create narrows it and does not remove it.
- The directory-in-use check lists every row of the database, as the table register does (V24).
- Local storage only.

### What was not verified

- Post-merge CI for 149 has not been read yet (read it first, D33).
- The directory-in-use cases for tables and soft-deleted tables are inferred from the same code; the test covers a view row
  [inferred].
- No real cluster, no object store or HDFS, and no client other than the 1.12 `RESTCatalog` test.

## X18: embedded H2 databases in the tests

Bottom line: only the tables service's own copy of the stand-in could serve a stale row besides the shared fixture fixed
by X16; it is fixed. One main-source default remains and is the owner's (`58-waiting-on-mike.md`).

| Embedded database in the tests | Can it serve a stale row | Action |
| --- | --- | --- |
| Shared fixture (X16, pull request 148) | yes | fixed in pull request 148 |
| `SpringH2Application` of the tables service (Boot's default H2 2.1.210, pooled, compare-and-set with concurrent reads) | yes | fixed: properties file `tables-service-test-h2.properties` and a property source |
| House Tables service's own tests (URL from `JdbcProviderConfiguration` in main source) | not checked | not changed; the owner's decision |
| Optimizer tests | no, MySQL in containers | none |
| `MockTablesApplication` and the jobs service tests | no, no datasource | none |
| Trino client test | no, one unpooled connection used in sequence | none |
| The optimizer apps | no tests; main `application.properties` | none |

### The reproduction [ran]

Pointed at the service's copy, the reproduction fails on every run before the fix: 819 stale reads through the repository
and 380178 through JDBC. After the fix it reads none. Commits: `f8197d67` (the reproduction) and `ad50d763` (the fix).
The five classes that start the changed application pass on both lines, 19 tests each.

### Open point

The House Tables service's default database URL in main source is an embedded H2, and a deployment without a configured
database would run on it. Whether its own tests race commits was not checked, and whether any real deployment uses the
default is unknown (put to Mike, `58-waiting-on-mike.md`).
