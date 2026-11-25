# V13a: Apache's REST compatibility kit against the tables service

## Bottom line

- **PR:** https://github.com/mkuchenbecker/openhouse/pull/104 (draft, into `irc-integration`)
- **Head:** 057120a29c923aa3f9993f574bb86418b2a37750. `irc-integration` has not moved from c174e388, so no merge was needed.
- **Kit result** (160 tests: table suite 109, view suite 51) [ran]:

| Run | Passed | Failed | Skipped | Table suite (pass / fail / skip) | View suite (pass / fail / skip) |
| --- | --- | --- | --- | --- | --- |
| First run, no flags | 29 | 115 | 16 | 29 / 65 / 15 | 0 / 50 / 1 |
| With flags | 30 | 107 | 23 | 29 / 58 / 22 | 1 / 49 / 1 |
| With flags and the class (d) fix | 31 | 106 | 23 | 30 / 57 / 22 | 1 / 49 / 1 |

- The last result was the same in four runs.
- Almost everything that fails is a model difference (class b, 84 tests) or a route not served yet (class c, 11 tests).
- One small defect found by the kit (an IRC create with a write order) is fixed, with a test written first.

## Flags set (class a)

The flags are set in `OpenHouseRestCompatibilityKitCatalogTests` and `OpenHouseRestCompatibilityKitViewCatalogTests`, one commented override per flag. Nothing else in the kit is changed.

Set to OpenHouse's model:
- `requiresNamespaceCreate=false`. The kit reads this from `rck.requires-namespace-create`; its default is true. OpenHouse has no namespace create: a database comes into being with its first table.
- `supportsNamespaceProperties=false`. The kit does not expose this flag, and the base class's default is true. OpenHouse databases carry no properties.
- `overridesRequestedLocation=true` (`rck.overrides-requested-location`). The server owns locations.

Kept at the kit's default, written out with a reason:
- `supportsNestedNamespaces=false`
- `supportsEmptyNamespace=false`
- `supportsNamesWithDot=false`
- `supportsNamesWithSlashes=false`
- `supportsVariant=false` (the service's tables are format v2; variant is v3)

`supportsServerSideRetry` stays at the kit's true. No test failed because of it: the tests that use it fail earlier, on partition evolution.

## Failure groups

Each test is counted under the first step it fails at. A later step may fail for another reason once that one is gone.

| Class | Group | Tests | One line |
| --- | --- | --- | --- |
| a | Namespace properties | 7 | Skipped; databases have no properties |
| a | Empty namespaces | 5 | Skipped; a database exists only while it holds something |
| a | Variant | 3 | Skipped; format v2 |
| a | Nested namespaces | 2 | Skipped |
| a | Names with dot | 2 | Skipped |
| a | Names with slash | 2 | Skipped |
| a | `tableCreationWithoutNamespace` | 1 | Skipped because namespace create is not required |
| b | VIEWDB | 23 | A view cannot create its database: creating a view in a namespace that holds no table is 404 NoSuchNamespace |
| b | RTAS | 14 | Replace needs `replace.enabled=true`; otherwise 400 |
| b | DIALECT | 10 | Views accept only the spark dialect; 400 |
| b | DIALECTRES | 2 | Spark dialect only, and the reserved view property `replace.drop-dialect.allowed` is refused; 400 |
| b | LOC (tables) | 9 | A table create or replace that names a location is refused with 400, not overridden |
| b | VLOC (views) | 4 | Same for views |
| b | SPEC | 9 | Partition evolution and bucket specs are refused with 400 |
| b | SCHEMA | 4 | Column drop and rename are refused with 400 |
| b | FMT | 3 | A requested format-version (1 or 3) is not honored; tables are v2 |
| b | IDS | 3 | `non-existing` is not a valid OpenHouse id: 400 where the kit expects 404 |
| b | LISTNS | 2 | Listing views or tables in a namespace that does not exist is 404, not an empty list |
| b | RENAMEDB | 1 | Rename across databases is 400 before the target namespace is looked up |
| c | NSWRITE | 7 | Namespace create and drop (406) |
| c | REGISTER | 2 | Register table (406) |
| c | VRENAME | 2 | View rename (406) |
| d | HTSKEY | 1 | `renameTableTargetAlreadyExistsAsView`: renaming a table onto a view's name succeeds. Not fixed. |
| e | MSG | 8 | Status and type are correct; the kit asserts the reference implementation's message text |
| e | NOTFOUND | 1 | Missing metadata file: the kit expects the reference server's 404 NotFoundException; this server answers 500 |
| e | KITNS | 1 | `dropNonEmptyNamespace` with namespace create off asserts the namespace is absent and then present, with nothing in between. No server can pass it. |
| e | DISABLED | 1 | `createTableInUniqueLocation` is `@Disabled` by the kit itself |

Totals: a 22, b 84, c 11, d 1, e 11, which is 129.

## Class (b): what the spec requires versus permits

Quotes are from the fork's `open-api/rest-catalog-open-api.yaml` [read].

- **Location (LOC, VLOC).**
  - The spec only says `CreateTableRequest.location: type: string`, an optional field. It describes no behavior.
  - Create allows `400: BadRequestErrorResponse`.
  - The kit's "override, don't refuse" is a reference-implementation behavior. The spec permits either.
- **Namespace only while it holds a table (VIEWDB, LISTNS).**
  - createView: `404: description: Not Found - The namespace specified does not exist`.
  - listTables and listViews: the same 404.
  - The spec requires 404 for an absent namespace, so OpenHouse conforms. The kit's expectation comes from catalogs where namespaces exist implicitly. The spec neither requires nor forbids a view creating its own namespace.
- **Replace, partition evolution, schema evolution (RTAS, SPEC, SCHEMA).**
  - updateTable: "Requirements are assertions that will be validated before attempting to make and commit changes", plus "400 ... if any unknown updates or requirements are received".
  - Nothing requires a server to accept every known update.
  - Refusing with 400 is permitted, not required against.
- **Dialect (DIALECT, DIALECTRES).**
  - `SQLViewRepresentation` only has `dialect: type: string`.
  - A spark-only policy and reserved properties are permitted.
- **Format version (FMT).**
  - The spec carries format-version only in metadata and in `upgrade-format-version`. It says nothing about the `format-version` create property, so this is permitted.
  - Note: the staged create silently answers v2 metadata when v1 or v3 is asked for, while the commit that completes a staged create refuses a mismatch. That is an inconsistency to fix (see work items).
- **Ids (IDS).** The spec sets no character rules. A 400 for an id that can never exist is permitted.
- **Rename across databases (RENAMEDB).**
  - renameTable lists `404 ... NoSuchNamespaceException, The target namespace of the new table identifier does not exist` and `406: UnsupportedOperationResponse` ("The server does not support this operation").
  - The spec permits refusing a cross-database rename. Its own code for "not supported" is 406; the server answers 400.

## Class (d) defects

**1. IRC create with a write order on a schema whose ids are not the fresh ones. FIXED.**
- Kit tests: `testUpdateTableOrderThenRevert`, `testRegisterTable`.
- Request: `POST /iceberg/v1/namespaces/newdb/tables` with a schema whose ids are 3 and 4, and the write order `bucket(id,16) asc, id asc`.
- Expected: 200.
- Actual: 400 `BadRequestException` "Cannot find source column for sort field: bucket[16](1) ASC NULLS FIRST".
- Cause: `IcebergCreates` sent the request's schema with the fresh-id sort order.
- Fix: send `metadata.schema()`. IRC only.
- Before and after [ran]: the new `IrcWriteRoutesTest.createWithASortOrderOnASchemaWithOtherIdsSortsByTheNamedColumns` got 400 before the fix and passes after it. In the kit, `testUpdateTableOrderThenRevert` went from failed to passed, and `testRegisterTable` now reaches register (c).

**2. Rename of a table onto a view's name succeeds. NOT FIXED.**
- Kit test: `renameTableTargetAlreadyExistsAsView`.
- Request: `POST /iceberg/v1/tables/rename` from `ns.table` to `ns.view`, where `ns.view` is a view.
- Expected: 409 AlreadyExists.
- Actual: 204.
- The native rename looks up only tables. `IrcViewRoutesTest` already notes that the in-memory house-table stand-in does not model the shared key, and that the real House Tables refuses with 409 [read, not verified].
- Fixing this means making the stand-in faithful (T5), or an IRC-side view check. That is not a status-code change, so it is listed as work.

## Work items, each with what would prove it

1. **Namespace writes (c, 7 tests).** Serve create and drop namespace, or decide not to. Proof: NSWRITE tests leave the list.
2. **Views can create their database, or databases can exist empty (b, 23 + 2 + 5 + 1 tests).** Proof: VIEWDB, LISTNS and the empty-namespace tests leave the list once the flags are flipped.
3. **Server-owned location (b, 13 tests).** Ignore a requested location instead of refusing it (one-way door). Proof: LOC and VLOC tests pass.
4. **Replace without `replace.enabled` (b, 14 tests).** Proof: RTAS tests.
5. **Partition evolution and bucket specs; column drop and rename (b, 13 tests).** Model decisions. Proof: SPEC and SCHEMA tests.
6. **View dialects other than spark, and reserved view properties (b, 12 tests).** Proof: DIALECT tests.
7. **Format version (b, 3 tests).** Honor v1 and v3, or at least refuse a mismatch at staged create instead of answering v2. Proof: FMT tests, plus an IRC test that a staged create with `format-version=1` is a 400 (or creates v1).
8. **Register table (c, 2 tests)** and **view rename (c, 2 tests).** Proof: REGISTER and VRENAME tests.
9. **Cross-database rename.** Answer 406 (the spec's "unsupported") instead of 400, and check the target namespace first. Proof: an IRC test, plus `renameTableNamespaceMissing` if it becomes possible.
10. **Rename onto a view's name (d).** Make the house-table stand-in enforce the shared key, or add an IRC-side check. Proof: `renameTableTargetAlreadyExistsAsView` passes.
11. **Optional alignment with the reference implementation (e).** Messages, and a 404 NotFoundException for a missing metadata file. Proof: MSG and NOTFOUND tests. Not required by the spec.
12. **Re-run the kit after each newly served route,** and remove the tests that start passing from the list.

## How the task decides pass or fail

`restCompatKitTest` runs every test of both suites, through the flag subclasses, with `ignoreFailures`. Its `afterTest` hook records each test that did not pass (FAILED or SKIPPED). The `doLast` step compares those with `expected-outcomes.txt` (format `<suite>.<test> | FAILED|SKIPPED | a-e | reason`). The task fails on any of:
- a listed test that passed or did not run ("remove it from the list");
- a test that does not pass and is not listed ("a regression");
- a test whose outcome differs from the listed one.

I checked both directions [ran]: I removed one listed test and added one that passes, and the task failed naming both. Nothing is disabled or excluded. The module's own `test` task excludes the kit classes only so they do not run twice. The kit's own `@Disabled` on `createTableInUniqueLocation` shows as SKIPPED and is listed as (e).

## What changed in the service

- `services/tables/src/main/java/com/linkedin/openhouse/tables/services/IcebergCreates.java`: one line. The native create body takes the schema from `metadata.schema()`. Only the IRC write handler uses this class.
- Before and after evidence is under defect 1 above.

## Proof the default build is untouched [ran]

- Without the switch, `settings.gradle` includes the new module only inside `if (icebergFork)`. The default build has 60 projects before and after.
- I dumped the resolved artifacts of every resolvable configuration in every project (an init script) at c174e388 and at the branch. `diff -r` of the two dumps (60 files, 35,655 lines) is empty.

## Tests run [ran]

Under `-PicebergFork`:
- `restCompatKitTest` passes (31 / 106 / 23, matching the list).
- `assemble testClasses` passes.
- `:services:tables:compileJava :services:tables:compileTestJava` passes.
- `spotlessCheck` passes on the module and on services:tables.

Default build:
- `:services:tables:test`: 1529 tests, 0 failed.
- `:tables-test-fixtures:tables-test-fixtures_2.12:test`: 39 tests, 0 failed.
- Spark 3.5 itest `catalogTest`: 269 tests, 0 failed, 1 skipped.
- `spotlessCheck` on services:tables passes, and the pre-push hook's full default-build `spotlessCheck` passes.

## What I added to publish-local.sh

- A third `publish` call for `iceberg-open-api`, which publishes its tests and test-fixtures jars, with its own marker `.published-<v>-open-api`.
- The skip-if-done check now also requires that marker.
- Locally this took 31 seconds, mostly from the build cache.
- In CI the cache key includes the script's hash, so the first run after this change republishes the whole fork (cold, several minutes).

## Not run, or only read

- `:services:tables:test` under `-PicebergFork` (only compiled).
- The opt-in CI job itself. Only its YAML is checked to parse.
- The other opt-in suites (Spark 4.2 and so on), because nothing they use changed.
- The real House Tables' 409 on rename onto a view [read only].
- The spec quotes [read].
- One leftover JVM: the pre-push hook started a Gradle daemon (pid 30083, `--daemon` with the default 3-hour idle timeout). My attempt to kill it was denied by the permission classifier, so it is still running until it idles out. No service JVM of mine is left; the kit's service JVM stops with each run.

## Merge commit

Title: Merge V13a: Apache's REST compatibility kit runs against the service under the opt-in build (#104)

Body:

Why: The owner's goal is that OpenHouse becomes a conforming Iceberg REST Catalog server, and nothing measured the gap. Apache Iceberg's own REST compatibility kit now runs against the tables service's IRC routes under -PicebergFork, so the gap is a reviewed list instead of a guess, and any change to it fails a build.

Leaves true:
- `:integrations:java:openhouse-rest-compat-kit` exists only under -PicebergFork.
- `restCompatKitTest` runs all 160 tests of the kit's table and view suites with Iceberg 1.12's RESTCatalog against the service in its own JVM.
- Two subclasses only answer the kit's capability flags with OpenHouse's model.
- The 129 tests that do not pass are listed in `expected-outcomes.txt`, each with its outcome, class (a model flag, b model difference, c not served, d defect, e reference-implementation assumption) and reason.
- The task passes exactly when the outcome matches that list in both directions.
- The opt-in post-merge job runs it in its own final step.
- `publish-local.sh` publishes the kit (`iceberg-open-api`'s tests and test fixtures).
- An IRC create whose schema ids differ from the fresh ones keeps its write order instead of being refused with 400.

Evidence (run locally):
- Kit: first run 29 passed, 115 failed, 16 skipped; with flags and the fix 31 passed, 106 failed, 23 skipped. The result was the same in four runs, and the comparison was checked in both directions.
- New `IrcWriteRoutesTest` case: 400 before the fix, passes after.
- Default build: `:services:tables:test` 1529, 0 failed; `tables-test-fixtures_2.12` 39, 0 failed; Spark 3.5 itest `catalogTest` 269, 0 failed, 1 skipped.
- Under the switch: `assemble testClasses` and the services:tables compiles pass.
- Default-build resolved classpaths of all 60 projects are identical before and after (35,655 lines).

Known gaps:
- Class b, 84 tests: views cannot create a database, server-owned locations are refused rather than overridden, replace needs `replace.enabled`, partition and schema evolution are refused, spark-only view dialect, cluster format version, id rules, cross-database rename.
- Class c, 11 tests: namespace writes, register, view rename.
- Class d, 1 test: rename onto a view's name succeeds against the in-memory house-table stand-in. Not fixed.
- Class e, 11 tests: the kit asserts the reference implementation's messages, plus one self-contradicting test and one the kit disables.
- A staged create silently answers v2 when another format version is asked for.

Commits:
- e2061996 build: publish-local.sh publishes the kit, so the opt-in build can run it.
- 4ef0bd6e test: pins the create-with-write-order defect at the HTTP boundary before the fix.
- 666f94af fix: an IRC create sends the new table's schema, which the sort order is bound to, so schema and order agree.
- 36434a95 test: the kit module, the flag subclasses and the reviewed outcome list, which measure the gap and guard it.
- dca6fb34 ci: the kit runs after each merge in its own step, so a red kit never hides the other suites.
- 057120a2 docs: how to run the kit and read the list.

Decision record: docs/plans/irc/22-decisions-continued-2.md (D26, D29) and docs/plans/irc/16-decisions-continued.md (D16, D18) on branch claude/openhouse-rest-v3-planning-g42c0h of mkuchenbecker/openhouse.

Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01T3dbirnudathz3xU8K18AE
