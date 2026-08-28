# Rename CAS hardening - working notes

## State
- PR #36 branch `claude/tla-driven-commit-fixes` pushed at 9fedbce433b264d45496dcbf930eaa880f6e368a
- PR #37 branch `claude/tla-rename-followups` force-pushed at 29ea7079fc3f02f300df8be2a3d76972bfb6e448
- PR #36 body updated (two edits only: Sketch row, one Summary sentence)
- DONE

## Final predicate (UserTableHtsJdbcRepository.renameTableId)
WHERE lower(table.databaseId) = lower(:fromDatabaseId)
  AND lower(table.tableId) = lower(:fromTableId)
  AND table.version = :expectedVersion
  AND table.metadataLocation = :expectedMetadataLocation

Bound from `fromUserTableRow.getVersion()` and `fromUserTableRow.getMetadataLocation()`
(the state observed at findById), not from the caller's expectedMetadataLocation token.

## Null metadataLocation verdict: UNREACHABLE, strict equality used
Evidence:
1. `UserTable.metadataLocation` is `@NotEmpty`; `OpenHouseUserTableHtsApiValidator.validatePutEntity`
   runs bean validation on every PUT, the only path that creates or advances a row.
2. `UserTableVersionMapper.toVersion` dereferences
   `existingUserTableRow.get().getMetadataLocation().equals(...)` with no null check, so a row with a
   null location would already NPE on any subsequent PUT.
3. `restoreUserTable` copies the location from a soft-deleted row, itself copied from a user table row.
4. The DDL column is nullable, but no writer can produce null. Noted in report.

## Gradle notes
- Worktree `.git` is a file, so `-x CopyGitHooksTask` is REQUIRED on every gradle invocation.
- Test results land in `<root>/build/<module>/test-results/test/`, not `services/<module>/build/`.
- Fixtures gradle project names: `:tables-test-fixtures:tables-test-fixtures_2.12` (1.2 source,
  shared into the 1.5 variant via srcDirs).

## Results on #36
- housetables 136 tests, 0 failures
- internalcatalog 85 tests, 0 failures
- tables 480 tests, 0 failures
- spotlessCheck clean; checkstyle warnings all pre-existing (no new ones)

## ABA evidence (predicate temporarily neutralized to a tautology)
- HtsRepositoryTest.testRenameUserTableAfterDropAndRecreateUpdatesNoRows: expected <0> but was <1>
- UserTablesServiceTest.testUserTableRenameConflictsWithDropAndRecreate:
  Expected EntityConcurrentModificationException to be thrown, but nothing was thrown.

## PR #37 results after rebase
- housetables 138, internalcatalog 85, tables 485, all green; spotless and checkstyle clean.
- Parity test gained a fourth mode (drop and recreate). Removing the fixture guard fails both new
  parity cases, confirming the coverage is not vacuous.
