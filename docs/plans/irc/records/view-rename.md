# View rename over IRC: stopped before implementing

Bottom line: not implemented, on purpose. The House Tables row store has no atomic way to rename a view row: its only
rename is table-only by design and pinned by a test, and the view API offers only create-or-update and delete. So with
today's APIs a view rename is two House Tables calls, with a window in which both names (or neither) resolve. The brief
names this as a stop condition. Serving the route needs either a House Tables change (the owner's decision) or the owner's
acceptance of a non-atomic rename. Nothing was changed, committed, pushed or opened; no pull request exists.

Dated 2026-11-24. Worktree `/home/user/oh-vren`, local branch `irc/view-rename` at `adc28470` with no changes, not pushed.

## Why it was missing (read)

- D27 (`docs/plans/irc/22-decisions-continued-2.md:83-90`): the IRC route answers 406 because the native view API has
  no rename. "Reverse by: adding a native view rename, then serving the IRC route."
- Route today: `IcebergRestViewController.java:111-119` (`@IcebergRestRefusedRoute`, so it is not in the config
  endpoint list) calling `IcebergRestViewHandler.java:159-166`, which always throws the 406
  "OpenHouse views do not support rename: a -> b". The controller is `@ConditionalOnClass(ViewMetadata)` (line 38), so
  it registers on both the 1.12 line and the 1.5.2 reference line.
- The deeper reason, which D27 does not state: House Tables cannot rename a view row.

## The blocker, with file and line (read)

1. Table rename is one House Tables call, a single conditional UPDATE, so it is atomic:
   - `OpenHouseInternalCatalog.renameTable` (internalcatalog `OpenHouseInternalCatalog.java:241-271`) writes a new
     metadata file with the new `openhouse.tableId` / `databaseId` / `tableUri`, then
     `OpenHouseInternalTableOperations.java:563-579` calls `houseTableRepository.rename(...)` (one HTTP PATCH,
     `HouseTableRepositoryImpl.java:248-262`).
   - House Tables: `UserTablesServiceImpl.renameUserTable` (`services/housetables/.../UserTablesServiceImpl.java:198-226`)
     runs `UserTableHtsJdbcRepository.renameTableId` (`UserTableHtsJdbcRepository.java:309-328`), one UPDATE whose WHERE
     carries `TABLE_ROW_PREDICATE` (line 66: entity type null or TABLE). Its Javadoc: "Table-only: views are not
     renameable." A failure mid-way leaves only an orphan metadata file; the source still resolves.
   - Pinned: `HtsControllerTest.testRenameTableRefusesToMoveAView` (`HtsControllerTest.java:2354-2376`): renaming a VIEW
     row answers 404 and moves nothing.
2. The view row API is GET, PUT (create-if-absent or version-checked update) and DELETE
   (`UserHouseTablesController.java:284-413`). The view commit engine writes rows only through `saveView`
   (`ViewCommitEngineImpl.java:354`; a conflict on create becomes `AlreadyExistsException`), and drop is
   `deleteViewById` only, no file purge (`ViewsServiceImpl.java:266`).
3. So a view rename on today's APIs is: write metadata v(n+1) with the new identity properties, PUT the target row
   (create), DELETE the source row. Between the two calls both names resolve to the same view (same UUID, same
   history). If the DELETE fails or the process dies, both rows stay: two views sharing one UUID and one metadata
   directory, which then diverge on the next replace of either. Reversing the order (delete, then create) gives a window
   where neither resolves, and a failure loses the name (metadata stays on storage, unreferenced). Compensation (delete
   the target if the source delete fails) narrows but does not close either window, and can itself fail.

## Options

- A. House Tables: let the existing rename move a view row (a view-scoped twin of `renameTableId`, entity-type
  preserved, same conditional single UPDATE), either as a new endpoint (for example `PATCH /hts/views/rename`) or by
  widening the table rename. Atomic exactly as table rename is. Needs a House Tables API change, a regenerated
  House Tables client (`client/hts`), and the deployed House Tables service to have it; widening the existing endpoint
  also changes the behavior `testRenameTableRefusesToMoveAView` pins. Owner's decision.
- B. Tables service only: create-then-delete with best-effort compensation. No House Tables change, but not atomic in
  the sense table rename is (both-names window; a mid-way failure leaves a duplicate view with the same UUID). Would need
  the owner to accept that state explicitly.
- C. Keep the 406 (D27). No change; the kit stays at its 2 view-rename failures.

Recommendation: A, as a new view-scoped House Tables endpoint (leaves table rename and its pinned refusal unchanged),
then serve the IRC route on it. Until the owner decides, C. I recommend against B: a view that two names resolve to with
one UUID is a state no other OpenHouse path can produce, and nothing cleans it up.

## What the implementation would need once A exists (read, for planning)

- Authorization: table rename requires `CREATE_TABLE` on the database plus `UPDATE_TABLE_METADATA` on the table and the
  system-only lock check (`TablesServiceImpl.java:339-377`); view writes go through `ViewWritePrivilegeMapper`. Not
  analysed further.
- Cross-database: tables refuse it natively (400); IRC answers 404 for a missing target namespace, else 406
  (`IcebergRestWriteHandler.java:953-1008`). House Tables itself writes the source database into the target
  (`UserTablesServiceImpl.java:215-219`).
- Target held by a view on table rename: House Tables refuses on the shared key and IRC maps it to 409
  (`IcebergRestWriteHandler.java:986-1007`).

## What was run versus read

Ran: git fetch, worktree creation, greps and file reads. No Gradle build, no test, no kit run. The kit count
(41 passed, 96 failed, 23 skipped) is taken from the brief and `18-state.md`, not re-measured. Spark `ALTER VIEW ...
RENAME TO` behavior is from `36-v5-3-grants-unlock-endpoints-result.md:85-88` and `31-v4-irc-views-result.md`, not re-run.
