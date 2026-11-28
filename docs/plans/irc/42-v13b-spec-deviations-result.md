# The IRC routes follow the specification in four places (V13b): result

Bottom line: the four places where Apache's compatibility kit found the IRC routes deviating from the specification
are fixed, in pull request 106 (merge commit `bfdcf266e6f13e0d09b21b63b49e7002a55d876e` on `irc-integration`).
The kit now passes 38 of 160 tests, up from 31. The native API is unchanged. Two things were found and left alone:
a native defect (the rename of a table onto a view's name answers 500 and leaves orphan files) and a gap (a create
transaction keeps no schema history). Written by a delegated agent; "ran" means run, "read" means read in code only.
This file supersedes the counts in `39-v13-compat-kit-result.md`.

## Kit counts (ran)

| | Passed | Failed | Skipped |
| --- | --- | --- | --- |
| Before (`39-v13-compat-kit-result.md`) | 31 | 106 | 23 |
| After | 38 | 99 | 23 |

The kit task passes against the updated list of expected outcomes, before and after the merge.

Class totals for the 122 that do not pass (Claude's arithmetic from the group changes, not a separate run): a 22,
b 78, c 11, d 0, e 11. The defect class is now empty.

## Which tests moved

Seven tests now pass and left the expected-outcomes list: `testCompleteCreateTable`, `testLoadTable`,
`testCompleteCreateTransaction`, `testCompleteCreateTransactionV2`,
`testCompleteCreateOrReplaceTransactionCreate`, `renameTableNamespaceMissing` and
`renameTableTargetAlreadyExistsAsView`.

Eight tests still fail, now at a later step, so each moved to another group:

| Test | From | To | Why it fails now |
| --- | --- | --- | --- |
| `testCompleteCreateOrReplaceTransactionReplace` | LOC | RTAS | Replace needs `replace.enabled` |
| `testCompleteReplaceTransaction` | LOC | RTAS | Replace needs `replace.enabled` |
| `testCreateTableWithDefaultColumnValue` | LOC | FMT | Asks for format version 3, now refused 400 |
| `testCompleteCreateTransactionMultipleSchemas` | LOC | CREATEHIST | Stores the final schema as the first, with id 0; the kit expects id 1 |
| `completeCreateView` | VLOC | DIALECT | Uses the trino dialect |
| `createAndReplaceViewWithLocation` | VLOC | DIALECT | Uses the trino dialect |
| `updateViewLocation` | VLOC | DIALECT | Uses the trino dialect; then it would meet the location-only commit refusal |
| `createViewWithCustomMetadataLocation` | VLOC | VIEWDB | A view cannot create its database |

The groups LOC, VLOC, RENAMEDB and HTSKEY are empty and gone. CREATEHIST is a new group, class b.

## Change 1: a requested location on create and replace (D30 carried out)

Before: an IRC create, staged create or view create that named a location got 400. A table or view replace that
named a location got 400 too (all four observed in new tests before the fix).

After (ran): the requested location is not read. The table or view is created at the server's location and the
response returns it. A table replace commit drops `SetLocation` updates. A view commit that sets a new current
version, which is what a view replace sends, skips `SetLocation`.

Still refused with 400, unchanged: an ordinary table commit that sets the location; a view commit that only sets
the location; and the completion of a staged create at a location other than the one the server staged (the
client's data files are already there). The native API never receives a location, so it is untouched.

Five new tests at the HTTP boundary (three table, two view) each check that the response location is the server's,
that the stored state equals a write without a location, that a replace keeps the location, and that nothing
exists under the requested path. The kit, with its server-owns-location switch, only asserts a location is non-null,
so none of its tests needs the requested one honored.

## Change 2: rename across databases

Before: an IRC rename across databases answered 400, as the native one does.

After (ran): when the databases differ, ignoring case, the IRC route checks in order the native id rules (400),
then that the target database exists (404 `NoSuchNamespaceException`), then runs the native cross-database check and
answers its refusal as 406 with the native message. Same-database rules are unchanged. The native route still
answers 400, pinned in two tests. The old IRC case that expected 400 was replaced by the new test.

## Change 3: format version on create and staged create

Before (ran): an IRC create asking for format version 1 answered 200 and stored version 2. The native create
overwrites the property with the cluster's version (read).

After (ran): an IRC create or staged create whose `format-version` differs from the cluster's is refused 400
("Create of table ... sets the table property format-version to ...: OpenHouse creates tables with format version
2"), and nothing is written. The cluster's own version is accepted. This matches the refusal the commit that
completes a staged create already had. The three FMT kit tests and `testCreateTableWithDefaultColumnValue` stay
failing, now with the reason "refused 400". Honoring version 1 or 3 is a later item.

## Change 4: renaming a table onto a view's name

What production does (read): the house tables store keys tables and views by one primary key, and a rename onto an
occupied key is a key violation, answered 409. Its own tests pin this; they were read, not run.

Before: both in-memory stand-ins for the store overwrote the view's row and answered 204, so tests saw a success
that production never gives. After (ran): both stand-ins refuse a rename onto a key any entity holds, and a new
test on each fails before and passes after.

The IRC rename now answers 409 `AlreadyExistsException` ("Cannot rename ns.t to ns.v. View already exists"). Before,
once the stand-in was faithful, it answered 409 with the wrong exception type.

The native answer is pinned as it is (ran): 500 with a message starting `CommitFailedException`, because the
exception from the store is not mapped. It also writes 10 files in the source table's directory (5 metadata files
and their checksum files, one pair per commit attempt, 1 attempt and 4 retries), and takes about 1.8 s, measured
once in a probe that is not committed. The view and table are unchanged. This is a native defect, not fixed (D9).
See `21-findings-continued.md` and work item N4.

## Other test changes

Two old tests that pinned the 400 on a location (one table, one view) are replaced by the create tests above,
because D30 reverses D20. The Spark 3.5 integration test `IcebergRestCatalogWriteTest.refusedWrites` now expects
that create to succeed at the server's location; the full `catalogTest` run found it. One comment changed in an
IRC view test.

## Test counts (ran, on the merged head unless noted)

- Default build: `:services:tables:test` 1536 tests, 0 failed (before the merge, which touches none of its files);
  both test-fixture modules 40 tests each, 0 failed; `:libs:datalayout:test` 17, 0 failed (before the merge);
  Spark 3.5 `catalogTest` 269 tests, 0 failed, 1 skipped; Spark 3.1 `catalogTest` 87, 0 failed, 1 skipped (before
  the merge).
- Under the opt-in switch: the service compiles, format checks pass, the kit passes against its list (38, 99, 23),
  and the Spark 4.2 integration test has 38 tests, 0 failed.

## Not run

- The house tables service's own tests and its MySQL schema: read only.
- `:services:tables:test` under the opt-in switch: compiled only.
- Service, Spark 3.1 and datalayout tests after the merge: not re-run, as the merge changed none of their inputs
  except an opt-in block in datalayout.
- The opt-in CI job.

## Known gaps

- Native defect: see change 4. Work item N4.
- A create transaction keeps only its final schema, partition spec and sort order, with id 0, where the kit expects
  the history (group CREATEHIST). Work item V14.
- Honoring format versions 1 and 3 on create is later work.
- Every other class b and c group in `39-v13-compat-kit-result.md` is unchanged.
