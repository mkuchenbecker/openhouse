# tla-fixes PR — working notes

## Workspace
- Worktree: /home/user/worktrees/tla-fixes, branch claude/tla-driven-commit-fixes off origin/main (2a9dac8)

## Analysis (done)
Rename call chain:
- Controller PATCH /hts/tables/rename (UserHouseTablesController.renameTable, 5 query params)
- OpenHouseUserTableHtsApiHandler.renameEntity -> UserTablesServiceImpl.renameUserTable
- UserTableHtsJdbcRepository.renameTableId: unconditional JPQL UPDATE, no @Version check/bump  <-- BUG
- Caller: OpenHouseInternalTableOperations.doCommit:386-400 -> HouseTableRepositoryImpl.rename -> generated client renameTable
- Client is codegen'd at build time from service OpenAPI spec (client/hts), so controller param additions flow through automatically.
- 409 from HTS -> WebClientResponseException.Conflict -> HouseTableConcurrentUpdateException -> CommitFailedException (retriable) — already wired in doCommit catch + handleHtsHttpError.
- Expected-base token available in doCommit: houseTable.getTableVersion() (= previous tableLocation property = HTS row.metadataLocation, scheme-less, same channel as PUT CAS token).
- H2 fixtures implementing HouseTableRepository.rename (need signature update + guard):
  - tables-test-fixtures/tables-test-fixtures-iceberg-1.2/src/main/java/com/linkedin/openhouse/tablestest/HouseTablesH2Repository.java
  - services/tables/src/test/java/com/linkedin/openhouse/tables/e2e/h2/HouseTablesH2Repository.java
- EntityConcurrentModificationException maps to 409 in OpenHouseExceptionHandler.

## Plan
1. specs/tla/OpenHouseCommitRename.tla: base spec + Renamer process; RenameGuard constant.
   cfgs: rename_unguarded (DivergenceCheck TRUE, RenameGuard FALSE -> NoSnapshotLoss violated),
         rename_guarded (both TRUE -> all invariants hold). Commit logs.
2. Code fix:
   - renameTableId: conditional on version, bumps version, returns int, @Modifying(clearAutomatically=true)
   - UserTablesServiceImpl.renameUserTable(+expectedMetadataLocation): findById, expected-loc precheck, 0-rows -> EntityConcurrentModificationException
   - Controller: optional expectedMetadataLocation param; handler passes fromUserTable.metadataLocation
   - HouseTableRepository(.Impl).rename + H2 fixtures + OpenHouseInternalTableOperations caller (passes houseTable.getTableVersion(), null if INITIAL_VERSION)
3. Tests: HtsRepositoryTest (version bump, 0-rows conflict), UserTablesServiceTest (rename-vs-commit race -> conflict, happy path, stale expected loc), HtsControllerTest (endpoint with/without expected param), internalcatalog HouseTableRepositoryImplTest existing rename tests updated.
4. Gradle: :services:housetables:test, :iceberg:openhouse:internalcatalog:test, :services:tables:test, spotlessApply/check.
5. Push + draft PR on mkuchenbecker/openhouse.

## Status
- [ ] spec + TLC runs
- [ ] code fix
- [ ] tests
- [ ] gradle green
- [ ] push + PR

## Progress log
- TLC done: unguarded NoSnapshotLoss VIOLATED (348/183 states, 7-step trace);
  unguarded MonotonicVersion VIOLATED (48/28); guarded PASS (18135/6130, depth 18).
  Committed specs/tla (logs renamed .out due to .gitignore *.log). Commit 1: docs: add TLA+ model...
- Code fix applied: repo JPQL (+version CAS, +bump, returns int), service (+expectedMetadataLocation,
  findById + precheck + 0-rows conflict), handler, controller (+optional expectedMetadataLocation param),
  HouseTableRepository(+Impl) 6-arg rename, ITO passes houseTable.getTableVersion() (null if INITIAL_VERSION),
  both H2 fixtures guard + throw HouseTableConcurrentUpdateException.
- Tests updated/added: HtsRepositoryTest (bump assert, stale-version 0-rows test), UserTablesServiceTest
  (happy w/ expected, w/o expected, stale expected 409, doAnswer race test), HtsControllerTest (expected param
  ok + stale conflict), HouseTableRepositoryImplTest (6-arg + query param assertion).
- Gradle: must use JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 and -x CopyGitHooksTask (worktree .git is a file).

## DONE
- All suites green (housetables / internalcatalog 85 tests / tables), fixtures 1.2+1.5 compile, spotless clean.
- Pushed claude/tla-driven-commit-fixes; DRAFT PR #36: https://github.com/mkuchenbecker/openhouse/pull/36
- Report finalized: reports/11-tla-fixes-pr.md
