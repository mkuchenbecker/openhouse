# Synthesis checkpoint — OpenHouse commit path review @ 2a9dac8

## State
- Working tree = 2a9dac8 + docs-only commits (verified via git diff --stat). Code verification against tree is valid.
- Reports read: arch (18 findings), testing (12), blind (14 + F15 note).
- Skill + PRINCIPLES read.

## Candidate merge map (pre-verification)
- doCommit IOException handler post-commit / delete() UnsupportedOp: arch#1 + arch#12(partial) + testing F4 + blind F1 + blind F8 → likely ONE finding (same location/failure) with delete-landmine folded in. Blind F8 same location as F1 — merge.
- In-place metadata rewrite replicated create: arch#2 + testing F8 + blind F2.
- Rename bypasses CAS: arch#3 + testing F7 + blind F3.
- Property-bag commit payload: arch#4 only (briefed) — verify hard.
- Client routing not total: arch#5 only.
- processSchemas swallow: arch#6 + testing F9 + blind F13.
- CAS four sites/three semantics: arch#7 (briefed only).
- Replace inferred from diff / CAS-exempt: arch#8 + testing F6(part c) + blind F7(partial: replace unguarded entry).
- Snapshots endpoint CSU→500: arch#9 + testing F3(part b) + blind F6.
- failIfRetryUpdate pre-burn/per-JVM: arch#10 + testing F1 + blind F4.
- Message-string matching: arch#11 only.
- HTS boundary vocabulary: arch#12 (delete landmine overlaps #1/F4/F8).
- Client 400→absent: arch#13 only.
- Payload trusted wholesale/subtractive: arch#14 + blind F7 (refs wipe default — blind adds doUpdateSnapshotsIfNeeded empty-refs detail).
- Stack traces in responses: arch#15.
- Dropped causes: arch#16.
- Interrupt flag: arch#17.
- Concurrent drop → 500: arch#18 + blind F10.
- No idempotency at commit point: blind F5 only.
- 503 vs Iceberg REST convention: blind F6 (partially overlaps arch#9).
- HTS client retries rename/delete non-idempotent + block() no timeout: blind F9 only.
- TOCTOU lock/authz: blind F11 only.
- Replication skips eligibility checks: blind F12 only.
- Drop non-atomic + deleteRemovedMetadataFiles uses pre-transform metadata: blind F14 only.
- Testing-only coverage gaps: T-F1..F12 (test-suite findings; separate category).
  - F2 (no two-writer e2e), F3 (checkCommitStatus partitions), F5 (client CAS token untested), F6, F10, F11, F12.

## Verification status
- [ ] OpenHouseInternalTableOperations.java lines: 93-94,126-130,258-262,267-312,314-354,356-383,386-400,401-437,440-476,594-635,642-675,704-716,804-821
- [ ] HouseTableRepositoryImpl.java 226-254, 319-322, 58-61, 188-217
- [ ] UserTableHtsJdbcRepository.java 115-125
- [ ] UserTablesServiceImpl.java 98-167
- [ ] UserTableVersionMapper.java 20-47
- [ ] OpenHouseInternalRepositoryImpl.java 154-216, 288-312, 451-475, 565-599, 696-781
- [ ] IcebergSnapshotsServiceImpl.java 41-110; TablesServiceImpl.java 167-194
- [ ] OpenHouseExceptionHandler.java 130-160, 316-329, 402-415
- [ ] Client OpenHouseTableOperations.java 100-180, 205-209, 343-464
- [ ] MetadataUpdateUtils.java 36-58
- [ ] SnapshotsUtil.java 45-47
- [ ] OpenHouseInternalCatalog.java 73-85, 157-192, 229-243
- [ ] Test files for testing findings (ITOTest, DoCommitTest, SnapshotsControllerTest greps, HTRTest, etc.)
- [ ] Iceberg contract refs (/home/user/iceberg TableOperations.java 50-60, CommitFailedException CleanableFailure, SnapshotProducer 414-415, rest yaml)

## Verification results (all done)
Every cited anchor re-checked against the working tree (== 2a9dac8 for code):
- ITO.java: 93-94, 126-130, 269-271, 298-354, 367, 386-437, 440-476, 604-635, 642-664, 670-675, 704-715, 804-821 — ALL verify.
- HouseTableRepositoryImpl: delete() UOE :320-322; rename/deleteById retry on ISE + .block() no timeout :226-254 ✓.
- UserTableHtsJdbcRepository.renameTableId :115-125 no version predicate ✓. UserTablesServiceImpl: save :111, rename :162, CFE import/catch :27/:112, putUserTable non-transactional ✓.
- UserTableVersionMapper raw equals :34, fabricated causes :30/:44 ✓.
- OpenHouseInternalRepositoryImpl: COMMIT_KEY :196, retries comment :197-207, replace path no COMMIT_KEY :154-177, skipEligibilityCheck :288-312, versionCheck :451-475, refs-only-if-nonempty :565-573 and :696-708, overrideProperty stash :750-781 ✓.
- IcebergSnapshotsServiceImpl :89-109 no CSU catch ✓; TablesServiceImpl :183-193 has it ✓.
- ExceptionHandler: ECME 409 :130-143, OHCSU 503 :146-159, ISE 500 :316-329, generic 500 + toString :402-415 ✓.
- Client OHTO: 400→empty :108, routing no-else :146-155, interrupt no-restore :156-167, stamping :208-209/:369-376, replaceCommit :411-416, error map :418-464 ✓.
- MetadataUpdateUtils fs.create(path,true) :45, cause-dropping IOException :50-55 ✓.
- SnapshotsUtil.parse io unused :45-47 ✓. HouseTableRepository extends PagingAndSortingRepository :16-17 ✓.
- OpenHouseInternalCatalog: newTableOps :72-84, dropTable :157-192, renameTable :212-244 (arch#3 cited "292-244" — typo, corrected) ✓.
- Iceberg contract: TableOperations post-commit rule ~:50-60 ✓; CommitFailedException implements CleanableFailure ✓; SnapshotProducer CleanableFailure→cleanAll :411-419 ✓; BMTO deleteRemovedMetadataFiles(base, metadata) :135-136 ✓; REST yaml 500/502/504 = CommitStateUnknownException, 503 generic ✓.
- Test evidence: zero baseTableVersion in client/spark tests ✓; COMMIT_KEY only ITOTest:301 ✓; no isConflict in SnapCtl ✓; no ConcurrentInsert functional test ✓; assertEquals(4,...) at ITOTest :172-175/:213-216/:539-542 ✓; timeout delays writeTimeout-2 (58s)/31s at HTRTest :659/:673/:679 (F12 paraphrase OK) ✓; testDoCommitExceptionHandling :659-685 type-only oracles, UNKNOWN-only ✓; staged tests :552-648 ✓; replicated-create happy-path w/ mocked fs :376-509 ✓; snapshots validator does not require snapshotRefs ✓; TableUUIDGenerator skips path validation for replica :150-164 ✓.

## Verdict: 0 findings killed. 44 input findings → 29 merged findings (9 blocking, 20 follow-up).
Merge map final: B1=arch1+blindF1+blindF8+testF4; B2=arch2+blindF2+testF8; B3=arch3+blindF3+testF7; B4=arch6+testF9+blindF13; B5=arch10+testF1+blindF4; B6=arch5; B7=testF2; B8=testF3a+testF10; B9=testF5; FU10=arch4; FU11=arch14+blindF7; FU12=arch9+blindF6+testF3b; FU13=arch7; FU14=arch8 (x-ref testF6c); FU15=blindF5; FU16=arch12; FU17=blindF12; FU18=blindF11; FU19=blindF9; FU20=arch11; FU21=arch13; FU22=arch18+blindF10; FU23=blindF14; FU24=testF6; FU25=arch15; FU26=arch16; FU27=arch17; FU28=testF11; FU29=testF12. blindF15 = no-defect note → review body.

## Adjudicated disagreements
1. 503-vs-500 (arch9 vs blindF6): rule with blind (Iceberg REST convention; typed CSU on 500/502/504; 503 invites blind retries); unify endpoints + OpenAPI. FU.
2. processSchemas tier (arch blocker/blind minor/testing decide): BLOCKING — silent wrong committed metadata, live replication surface, one-line fix.
3. failIfRetryUpdate tier (arch sugg/blind major/testing blocker): BLOCKING bounded to smallest step (burn-after-success + 2 tests); posture: live spurious 409, zero tests on 2nd-line defense.
4. Property-bag (arch blocker): FOLLOW-UP w/ triggers (fork sync/iceberg upgrade/next commit-path feature); active exploits separately defended/filed; refactor-scale (principle 4).
5. Client routing hole (arch blocker probable): BLOCKING — one-line guard vs silent ack of unpersisted commit.
6. Payload trust/refs-wipe (arch sugg/blind major): FOLLOW-UP; posture: first-party client always sends refs; trigger = non-OH clients.

## Evidence corrections (findings survive)
- testF4 side-claim "houseTable may be empty builder" — unreachable: only checked-IOException source (:421) runs after mapping (:385).
- arch3 pointer OpenHouseInternalCatalog.java:292-244 → :212-244.
- testF12 quoted literal delays are paraphrases of writeTimeout-2/readTimeout+1 (=58s/31s).

## Convergence
Blind reproduced all 3 top code defects (B1,B2,B3) + B5, B4, FU11, FU12, FU22 → 8 of arch's 18 (incl. 4 of 6 arch blockers); 7 of 10 pre-flagged smells independently hit. Estimate ~55% of briefed code-defect findings independently reproduced; 100% at the most-severe tier. Blind-only: idempotency (FU15), HTS-client retry (FU19), TOCTOU (FU18), replication eligibility skip (FU17), drop/deleteRemovedMetadataFiles (FU23).
