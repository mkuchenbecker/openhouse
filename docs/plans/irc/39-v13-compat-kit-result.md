# Apache's compatibility kit against the service (V13a): result

Bottom line: Apache's REST compatibility kit, 160 tests, now runs against the tables service under the opt-in
build, and 31 pass. Of the 129 that do not, 84 are OpenHouse's own rules that the specification permits, 11 are
routes the service does not serve yet, 22 are skipped by capability flags, 11 assert the reference
implementation's messages or cannot pass, and 1 is a defect not yet fixed. One defect found by the kit is fixed.
Whether OpenHouse should relax its rules to pass the kit is a question put to Mike (D32). Written by a delegated
agent (pull request 104, merged as fb8c1240). "Ran" means run. "Read" means
taken from code or the specification only.

## Counts (ran)
| Run | Passed | Failed | Skipped | Table suite (pass / fail / skip) | View suite (pass / fail / skip) |
| --- | --- | --- | --- | --- | --- |
| First run, no flags | 29 | 115 | 16 | 29 / 65 / 15 | 0 / 50 / 1 |
| With flags | 30 | 107 | 23 | 29 / 58 / 22 | 1 / 49 / 1 |
| With flags and the fix for defect 1 | 31 | 106 | 23 | 30 / 57 / 22 | 1 / 49 / 1 |

The last result was the same in four runs.
## Flags set (class a)
One commented override per flag, in two subclasses of the kit's table and view suites. Nothing else is changed.
- Set to OpenHouse's model: `requiresNamespaceCreate=false` (a database comes into being with its first table),
  `supportsNamespaceProperties=false`, `overridesRequestedLocation=true` (the server owns locations).
- Kept at the kit's default, with a reason written out: nested namespaces, the empty namespace, names with a dot or
  a slash (all false), and `supportsVariant=false` (variant is v3, the service's tables are v2).

## Failure groups

Each test is counted under the first step it fails at. Classes: a capability flag, b OpenHouse model difference,
c route not served, d defect, e reference-implementation assumption.

| Class | Group | Tests | One line |
| --- | --- | --- | --- |
| a | Namespace properties | 7 | Skipped; databases have no properties |
| a | Empty namespaces | 5 | Skipped; a database exists only while it holds something |
| a | Variant | 3 | Skipped; format v2 |
| a | Nested namespaces, names with dot, names with slash | 6 | Skipped (2 each) |
| a | `tableCreationWithoutNamespace` | 1 | Skipped; namespace create is not required |
| b | VIEWDB | 23 | A view cannot create its database: a create in a namespace with no table is 404 |
| b | RTAS | 14 | Replace needs `replace.enabled=true`; otherwise 400 |
| b | DIALECT | 10 | Views accept only the spark dialect; 400 |
| b | DIALECTRES | 2 | Spark dialect only, and the reserved property `replace.drop-dialect.allowed` is refused |
| b | LOC (tables) | 9 | A create or replace that names a location is refused with 400, not overridden |
| b | VLOC (views) | 4 | Same for views |
| b | SPEC | 9 | Partition evolution and bucket specs are refused with 400 |
| b | SCHEMA | 4 | Column drop and rename are refused with 400 |
| b | FMT | 3 | A requested format version 1 or 3 is not honored; tables are v2 |
| b | IDS | 3 | `non-existing` is not a valid OpenHouse id: 400 where the kit expects 404 |
| b | LISTNS | 2 | Listing in a namespace that does not exist is 404, not an empty list |
| b | RENAMEDB | 1 | Rename across databases is 400 before the target namespace is looked up |
| c | NSWRITE | 7 | Namespace create and drop (406) |
| c | REGISTER | 2 | Register table (406) |
| c | VRENAME | 2 | View rename (406) |
| d | HTSKEY | 1 | `renameTableTargetAlreadyExistsAsView`: renaming a table onto a view's name succeeds. Not fixed |
| e | MSG | 8 | Status and type are right; the kit asserts the reference implementation's message text |
| e | NOTFOUND | 1 | Missing metadata file: the kit expects 404 NotFoundException; this server answers 500 |
| e | KITNS | 1 | `dropNonEmptyNamespace` asserts the namespace absent and then present. No server can pass it |
| e | DISABLED | 1 | `createTableInUniqueLocation` is `@Disabled` by the kit itself |

Totals: a 22, b 84, c 11, d 1, e 11, which is 129.

## Class b: what the specification requires versus permits

Quotes are from the fork's `open-api/rest-catalog-open-api.yaml` (read).
- Location (LOC, VLOC). The specification gives `CreateTableRequest.location` as an optional string and describes
  no behavior. Create allows `400: BadRequestErrorResponse`. Overriding is the reference implementation's
  behavior. The specification permits either.
- A namespace only while it holds a table (VIEWDB, LISTNS). createView, listTables and listViews say
  "404 ... The namespace specified does not exist". The specification requires 404 for an absent namespace, so
  OpenHouse conforms. It neither requires nor forbids a view creating its own namespace.
- Replace, partition evolution, schema evolution (RTAS, SPEC, SCHEMA). updateTable says requirements "will be
  validated before attempting to make and commit changes" and allows "400 ... if any unknown updates". Nothing
  requires a server to accept every known update. Refusing with 400 is permitted.
- Dialect (DIALECT, DIALECTRES). `SQLViewRepresentation` has only `dialect: type: string`, so a spark-only
  policy and reserved properties are permitted.
- Format version (FMT). The specification carries it only in metadata and in `upgrade-format-version`, and is
  silent on the `format-version` create property, so refusing is permitted. The staged create silently answers v2
  metadata when v1 or v3 is asked for, while the commit that completes it refuses a mismatch. That inconsistency
  should be fixed (work item 7).
- Ids (IDS). The specification sets no character rules, so a 400 for an id that can never exist is permitted.
- Rename across databases (RENAMEDB). renameTable lists 404 for a missing target namespace and
  "406: UnsupportedOperationResponse". Refusing is permitted, and the specification's own code for it is 406. The
  server answers 400.

## The two defects

1. An IRC create with a write order on a schema whose ids are not the fresh ones. FIXED.
   - Kit tests `testUpdateTableOrderThenRevert` and `testRegisterTable`. A create with schema ids 3 and 4 and the
     order `bucket(id,16) asc, id asc` should give 200 and gave 400 "Cannot find source column for sort field".
   - Cause: `IcebergCreates` sent the request's schema with the fresh-id sort order. Fix: send `metadata.schema()`
     (one line, IRC only).
   - Ran: the new `IrcWriteRoutesTest` case got 400 before and passes after, and
     `testUpdateTableOrderThenRevert` went from failed to passed.
2. Rename of a table onto a view's name succeeds. NOT FIXED.
   - A rename from `ns.table` to `ns.view` should give 409 and gives 204. The native rename looks up only tables.
     The in-memory house-table stand-in does not model the shared key, and the real House Tables refuses with 409
     (read, not verified). The fix is a faithful stand-in (T5) or an IRC-side view check, so it is listed as work.

## The twelve work items, each with its proof

1. Namespace writes (c, 7 tests): serve create and drop, or decide not to. Proof: NSWRITE tests leave the list.
2. Views create their database, or databases exist empty (b, 23 + 2 + 5 + 1 tests). Proof: VIEWDB, LISTNS and
   the empty-namespace tests leave the list.
3. Server-owned location (b, 13 tests): ignore a requested location instead of refusing it. Proof: LOC and VLOC.
4. Replace without `replace.enabled` (b, 14 tests). Proof: RTAS tests.
5. Partition evolution, bucket specs, column drop and rename (b, 13 tests): model decisions. Proof: SPEC, SCHEMA.
6. View dialects other than spark and reserved view properties (b, 12 tests). Proof: DIALECT tests.
7. Format version (b, 3 tests): honor v1 and v3, or refuse a mismatch at staged create. Proof: FMT tests and an
   IRC test that a staged create with `format-version=1` is a 400 or creates v1.
8. Register table (c, 2 tests) and view rename (c, 2 tests). Proof: REGISTER and VRENAME tests.
9. Cross-database rename: answer 406 and check the target namespace first. Proof: an IRC test.
10. Rename onto a view's name (d): a faithful stand-in or an IRC-side check. Proof: the kit test passes.
11. Optional alignment with the reference implementation (e): messages and a 404 for a missing metadata file.
    Not required by the specification. Proof: MSG and NOTFOUND tests.
12. Re-run the kit after each newly served route and remove the tests that start passing from the list.

## How the task decides pass or fail

`restCompatKitTest` runs every test of both suites through the flag subclasses, with `ignoreFailures`. A hook
records each test that did not pass (FAILED or SKIPPED). The `doLast` step compares them with
`expected-outcomes.txt` (format `<suite>.<test> | FAILED|SKIPPED | a-e | reason`). The task fails on any of:
- a listed test that passed or did not run ("remove it from the list");
- a test that does not pass and is not listed ("a regression"), or whose outcome differs from the listed one.

Checked in both directions (ran): one listed test removed and one passing test added, and the task failed naming
both. Nothing is disabled or excluded.

## Default build untouched, and tests run (ran)

- Without the switch the new module is not included: 60 projects before and after, and the resolved artifacts of
  every configuration, dumped at c174e388 and at the branch, are identical (35,655 lines).
- `publish-local.sh` also publishes `iceberg-open-api`'s tests; the first CI run republishes the fork cold.
- Under `-PicebergFork`: `restCompatKitTest` passes (31 / 106 / 23); `assemble`, `testClasses`, the services:tables
  compiles and `spotlessCheck` pass.
- Default build: `:services:tables:test` 1529 and `tables-test-fixtures_2.12` 39, 0 failed; Spark 3.5 itest
  `catalogTest` 269, 0 failed, 1 skipped; `spotlessCheck` passes.

## Not run, or only read

`:services:tables:test` under `-PicebergFork` (compiled only); the opt-in CI job itself; the other opt-in suites;
the real House Tables' 409 on rename onto a view; the specification quotes (read).
