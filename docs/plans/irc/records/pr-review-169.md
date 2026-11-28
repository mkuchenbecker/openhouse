# Review of pull request 169: Iceberg REST, 11 of the kit's 13 REST-layer failures

Reviewed head: `ce78afdf49819afb9518abcbf885d27732dd7b9c` (the merge of `irc-integration` at `66a3c75a`, pull request 168,
into the branch; the merge touches none of this branch's lines, only adds 168's `@ConditionalOnIcebergRest` and the kit's
`--cluster.tables.irc.enabled=true`). Diff read: `git diff origin/irc-integration...HEAD` (merge base `66a3c75a`).

## 1. Verdict

**MERGE AS IS**, once the head's CI is green. No finding has to be closed first. CI at `ce78afdf` was still running
when this was written (Default and Reference `rest`, both `spark-3.5-openhouse-spark-itest`, Default `spark-4.2` itest,
the `apps` jobs). The only kit evidence is the cancelled `rest` job at `49d9a8a5`. Its tables-service test run was cut
off, so CI has not yet run the service suite on this branch.

The three things that matter most:
1. **No native answer changes.** Only files in `tables.api.irc` change (both source sets), plus docs, the list and one Spark test.
   The error advice is scoped to that package (`basePackageClasses = IcebergRestCatalogController.class`). Native routes
   reach none of the changed methods, and no native message, status or type changed.
2. **Every reworded message is true wherever it is produced.**
   - "Table does not exist" comes only from `NoSuchUserTableException`, and every throw site of that exception means
     "no house-table row" for the ids it carries.
   - "View does not exist" comes only from `NO_SUCH_VIEW`, which every site raises only for a missing or non-view row.
     A denial is a separate 403 (`AccessDeniedException`), not a hidden 404.
   - "Table with same name already exists" is raised after the create privilege check.
   - "Table already exists" (create and rename) is raised only where a table row holds the name. A view holder keeps the
     native text.
   - Invalid ids answer "does not exist" before any read or decision, and the native rules accept no such id for any
     database, table, view or namespace record. So nothing is revealed and nothing becomes accepted.
3. **The arithmetic holds.** Diffing the list against the base by machine, exactly 11 entries changed, all FAILED to PASSED,
   all unprefixed, so they count in both runs. No other entry's outcome, cause, type or prefix changed. Base 92/54/16 to
   head 81/65/16 entries. CI at `49d9a8a5`:
   - default: "160 tests, 63 passed, 81 failed, 16 skipped"
   - v3: "160 tests, 65 passed, 79 failed, 16 skipped"
   - both runs: "all 160 tests have the outcome and cause listed". The check compares the throwable type and the message
     prefix too (`build.gradle:313`).

   So 52 + 11 = 63 and 54 + 11 = 65, with no hidden swap. The list is byte-identical at `49d9a8a5` and `ce78afdf`.

## 2. Findings

None MUST FIX. None SHOULD FIX.

**M1 MINOR: a changed answer is missing from the pull request's tables.** On the 1.12 line, `POST .../tables/{t}/plan`
and `GET .../tables/{t}/credentials` now also answer 404 `NoSuchTableException` for an id the native rules refuse. Before,
they answered 400. Both go through `IcebergRestApiHandler.loadIcebergTable`, which now calls `requireValidTableIds`
(`IcebergRestApiHandler.java:335-338`).
- The pull request's "What was wrong" row names only "HEAD or GET of a namespace, table or view, or on a namespace's
  list". The plan route is a POST.
- No test pins either route for an invalid id.
- The answer is true and allowed by the specification (404 on a missing table), so only the record and a test are missing.
- Certain from reading.
- Fix: add one row to the pull request's table, and one assertion (404 `NoSuchTableException`) in
  `IrcPlanCredentialsRoutesTest`, or in the read test next to it.

**M2 MINOR: a test comment overgeneralises.** `IrcMetricsRouteTest.java:182-184` says "As on the table routes that post
or delete (the reads answer such an id as a missing table)". The plan route posts and now answers 404 (M1). The metrics
POST keeps 400, which is fine, but the comment's rule is false for one route. Fix: say "as on the writes (drop)".

**M3 MINOR: the "name taken" fallback is untested.** In `IcebergRestWriteHandler.java:582-600`, `nameTaken` makes an
extra House Tables read (`findViewById`, with retries) after the native create refused. When that read fails, it falls
back to the native message (`catch (RuntimeException lookup) { table = false; }`), and no test covers that fallback.
- Effect: only latency on the refusal path during an outage, then the native text. It reveals nothing new: the native
  create checks for an existing table before its privilege check, and its message already names the holder's type.
- Certain from reading.
- Fix (optional): a test with `findViewById` throwing, asserting the native message.

**M4 MINOR (note for the orchestrator, not the author): the ledger has no rows for these changes.**
`85-behavior-change-ledger.md` Part F ("The Iceberg REST routes: answers that changed after first being served") has no
rows for this pull request's changes: the message rewordings, and 400 to 404 for invalid ids on reads.

Lines of attack that found nothing:
- **No native change**: nothing found (details in section 5).
- **Authorization ordering**: nothing found. Invalid ids are answered before any read or decision. No denial is turned
  into "does not exist" (view denials stay 403, table load denials 403). The hide-or-reveal rule on namespace loads is
  untouched for valid ids.
- **Base switch**: no new class and no new bean. Every changed bean already carries `@ConditionalOnIcebergRest` (from
  168). `IcebergRestRoutesOffTest` is unaffected.
- **Guard scripts**: `check-private-ids.py` passes ("No private planning ids outside ..."), and
  `check-iceberg-line-branches.py` passes ("98 Iceberg-line branches ... all listed"). `check-test-groups.py`: no new
  test class, and the CI "test groups" job is green at the head.
- **Cruft**: no private ids, pull-request numbers or history narration in the added lines (grep of `^+`). No dead code
  (`onNamespace(Supplier)` is still used by the view list).

## 3. Behavior changes (all on `/iceberg`; no LinkedIn main baseline; no native change)

| Route(s) | Before | After | In author's table |
|---|---|---|---|
| Spec routes, missing table (load, HEAD n/a, commit, drop, rename source, metrics, plan, credentials) | 404 NoSuchTableException "User table db.t cannot be found" | same status and type, "Table does not exist: db.t" | yes |
| OpenHouse routes under base path (grants, unlock), missing table | native text | unchanged (handler type check) | yes |
| Create / staged create onto a table | 409 "Table db.t already exists" | 409 "Table already exists: db.t" | yes |
| Create onto a view's name | native 409 text | unchanged (after one House Tables lookup) | yes |
| Rename onto a table | 409 "Table local-cluster.db.u already exists" | 409 "Cannot rename db.t to db.u. Table already exists" | yes |
| View routes naming a view (load, HEAD, commit, drop, post-create load), NO_SUCH_VIEW | "No such view: db.v" / "No such view" | "View does not exist: db.v" | yes |
| View create, and register (1.12), onto a table's name | 409 "Name already exists as a table: db.v" | 409 "Table with same name already exists: db.v" | yes |
| GET / HEAD namespace, list namespaces with parent, list tables, list views, HEAD / GET table, HEAD / GET view, invalid id | 400 BadRequestException | 404 NoSuchNamespace / NoSuchTable / NoSuchView, "... does not exist: ..." | yes |
| POST plan, GET credentials (1.12), invalid id | 400 | 404 NoSuchTableException | **no (M1)** |
| Service audit of the reworded refusals | native message and cause | the answered message, no cause (as native) | yes (tests section) |
| Writes (create, register, commit, drop, rename, metrics POST, namespace writes), invalid id | 400 | 400 | yes |

## 4. Tests

- **`IrcReadRoutesTest`**:
  - `loadMissingTableIs404NoSuchTable` pins the exact new text on two paths and the native text. It fails without
    `981dbddf`.
  - `loadTableWithAnInvalidIdentifierIsAMissingTableWhileTheNativeApiRefusesIt` is a changed pin, 400 to 404, and the
    reason is stated.
  - `anIdTheNativeRulesRefuseNamesNothingOnTheReadRoutes` covers HEAD and GET namespace, the parent list, the table list,
    and HEAD and GET table (bad namespace and bad table) with exact messages, plus create 400 and two native 400s. Every
    404 would be 400 without `49d9a8a5`. Sound.
- **`IrcWriteRoutesTest`**:
  - `createOfAnExistingTableIs409AlreadyExists` has the create and the staged create in exact text, plus the native text.
  - `aMissingTableIs404` covers commit and drop with exact text, plus the native text.
  - `renameRefusalsAreTheNativeOnes` covers target-exists and missing-source text.
  - The old pins were "the IRC message equals the native one", which the new rule makes false. They were replaced by two
    explicit pins. All fail without their fixes.
- **`IrcViewRoutesTest`**:
  - The missing view, create over a table and drop pins now pin both texts.
  - `anIdTheNativeRulesRefuseNamesNoView` covers HEAD and GET view (bad namespace and bad view), the list with a bad
    namespace, and create 400 and native 400.
  - `aTableRouteAskedForAViewAndAViewRouteAskedForATable` now compares the create onto a view's name with the native
    message, and asserts it does not start with "Table already exists". That pins the `nameTaken` view branch.
- **1.12 register tests** (`IrcRegisterViewRouteTest`, `IrcRegisterViewRaceTest`, `IrcRestCatalogRegisterViewTest`):
  "Name already exists as a table" became "Table with same name already exists". These are changed pins, and the change
  is consistent with the view create.
- **`IrcServiceAuditTest`**: the create refusal is now "same audit except the message and the failure", and both are
  pinned exactly. The cause parity is preserved (no cause). Sound.
- **`IrcMetricsRouteTest`**: the comparison moved from load (now 404) to drop (400). Sound; see M2 for the comment.
- **Spark 3.5 `IcebergRestCatalogViewTest.aMissingViewIsNoSuchView`**: the pin changed to "View does not exist: ...".
  CI not finished.
- **Unpinned**:
  - plan and credentials with an invalid id (M1)
  - the `nameTaken` lookup failure (M3)
  - a commit to a missing view over the IRC route (the text comes through `onView`, as for drop, which is pinned)

## 5. Checked and found sound

- **No native change**:
  - Non-test files changed: `IcebergRestApiHandler`, `IcebergRestErrors`, `IcebergRestExceptionHandler`,
    `IcebergRestViewHandler`, `IcebergRestWriteHandler`, `IcebergRestViewRegistration` (1.12), the docs and the list.
  - `IcebergRestErrors.toError` is called only by the scoped advice and `IcebergRestScanController`.
  - No class outside `api.irc` uses the handlers, except `TableAuditAspect`'s pointcuts on `loadTable` and
    `loadIcebergTable`. That audit records FAILED READ either way, so it is unchanged.
  - The validators, `TablesServiceImpl` and the House Tables client are unchanged.
- **`HandlerMethod` parameter**: the advice applies only to handlers in the package. A request with no handler (unmapped
  native path) never reaches it, as 168's `nativeErrorsAreAnsweredByTheNativeAdviceWithTheRoutesOn` pins.
  `/iceberg/**` has a catch-all handler.
- **`NoSuchUserTableException`**: every throw site (`TablesServiceImpl` 157/168/417/436/539/595/638/704,
  `TableCommitService:117`, `IcebergRestApiHandler:371`) means "no row", with the ids it carries. The custom-message
  constructor is used only in House Tables.
- **`NO_SUCH_VIEW`**: raised only by `ViewsServiceImpl.noSuchView` (missing or non-view row, row vanished on drop) and by
  `translateReadFailureTo` for a House Tables 404. `NAME_ALREADY_EXISTS_AS_TABLE` is raised after the privilege check
  (`ViewsServiceImpl.java:276-293`). Register decides on the privilege before the occupant check.
- **Native rename**: `AlreadyExistsException` comes only from a table found at the target. `findById` loads through the
  catalog, which does not see view rows. A view target is refused earlier.
- **Native create**: `AlreadyExistsException` comes from `TablesServiceImpl` (a table row) or from the repository's save
  and stage paths (the occupant's type). `nameTaken` keeps the native text for a view holder.
- **Ids**:
  - databaseId, tableId and viewId match `^[a-zA-Z0-9_]+$` on every create path. Only clusterId allows a hyphen.
  - View ids also have a length bound on both get and create.
  - Namespace records validate 1 to 128 letters, digits and underscores.
  - So no entity can exist with a refused id. Letter case is unaffected (accepted).
  - The multi-level separator rules are unchanged (namespace routes 400, table and view routes 404).
  - Cross-database rename validates both ids (400) before `requireNamespace`, so writes keep 400.
- **Specification and reference**:
  - Each new text is the reference's: `BaseMetastoreCatalog`, `BaseMetastoreViewCatalog:61, 224, 315`,
    `InMemoryCatalog:182, 251, 521`.
  - The kit assertions read in `CatalogTests` and `ViewCatalogTests` (1.12 fork) match the new answers.
  - The OpenHouse client claims check out: `CatalogPluginContractTest:396, 408, 764-776`.
- **The two left**:
  - MISSINGMETA: the specification's loadTable 404 is "NoSuchTableException, table to load does not exist", and 5XX is
    a server-side problem. Leaving 500 is right by the specification.
  - VIEWERRTYPE: the specification fixes no type for a 400. Both keep their categories in the list (the lines are
    unchanged).
- **Docs**: the "What would move the number" rows sum to 81 (27 + 14 + 32 + 4 + 2 + 2). No stale "52 passed" or
  "92 failed" elsewhere outside the plans.
- **Smoke pins**: s27 (UNLOCK, an OpenHouse route) keeps "User table ... cannot be found". The Spark 4.2 IRC failure
  tests pin Spark's own `TABLE_OR_VIEW_NOT_FOUND`.

## 6. Not checked

- No test or build was run (read-only).
- CI at `ce78afdf`: Default and Reference `rest` (tables service suite, kit at the merged head with the switch flag),
  both `spark-3.5-openhouse-spark-itest` (the changed `IcebergRestCatalogViewTest` pin, smoke), Default `spark-4.2`
  itest, and `apps` were in progress.
- That each new assertion fails with its fix reverted: judged by reading only. The author's reverted runs were not
  reproduced.
- Reference-line behavior was checked by reading. No 1.5-only source set exists for these classes, and the Reference
  `apps-spark-4.2` and `spark-4.2` jobs are green at the head.
