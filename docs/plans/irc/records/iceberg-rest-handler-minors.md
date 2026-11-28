# Iceberg REST handler minors (branch irc/iceberg-rest-handler-minors)

Bottom line: in progress. Worktree /home/user/oh-irc-minors, base origin/irc-integration 4b9d152d.

## Triage (confirmed against today's code, read)

- A (C1 of 158 re-check): confirmed. `storedNamespace` swallows `AuthorizationServiceException`; six callers
  (children 207, HEAD 269, list tables 276, list views gate-off 99 and empty 121, rename target 1216).
- B (C3): `committed == null` cannot come from today's service: unchanged path uses the stored row, which
  `requireViewPointerAndStorage` requires non-blank; changed path uses House Tables' answer to the save, whose
  tableLocation is the new metadata file. Only a House Tables answer without the location yields null.
- FF-53 / 152: (1) third read on view no-change path: gone (158 restructured commitView; read decision on the row
  read); (2) staged-create completion reads metadata only to pass a base: confirmed, fix; (3) audit kind approximated:
  left (exact kind needs the applied updates; computing it from stored values before the decision would let stored
  values pick the wording of the legacy-lock refusal); (4) refused transaction's audit event untested: confirmed, test.
- FF-53 / 156: IRC create at cluster > 2, switch off: wrapped 500; IRC commit lowering format-version: 500. Others are
  native or shared code (left).
- FF-53 / 157: record read twice on load: already closed (loadNamespace reads once); path-name pattern: owner's;
  duplication: refactor, left; MySQL: outside; FF-48: closed by 158.

## Progress (commits pushed)
- A: 85efafa5 test (seen failing 503 vs 404), f3ffe923 fix (hasRecordOrIsListed).
- B: 51d209dc test (mock unit test; failed: nothing thrown), 63fc1735 fix (CommitStateUnknown before read-back).
- C 152 F8: 7fc80c22 test (500 naming the file before), 1baed8ae fix (placeholder for assert-create).
- C 152 F12: caa8f01d test only (refused transaction audited as the commit).
- C 156 lower format-version: d4ece57c test (500 "Cannot downgrade" before), 331d24e5 fix.
- C 156 IRC create at cluster > 2 switch off: left (misconfiguration only; IRC create is the native create; a
  per-line check needed since on 1.5.2 Iceberg throws itself).
- Next: D tests.
- D: f6ed28a1 tests (busy 503/Retry-After and timeout via MockMvc; start-up refusal in isolated context; rename 409s
  with an injected House Tables conflict). All passed first run: no defect.
- Both lines run: default 16 classes 266 tests 0 failed; reference 11 classes 252 tests 4 skipped 0 failed.
- Draft pull request 162, head f6ed28a1af46e0060c241df81a2881a421c5bdcf. Waiting for CI.
## Review of 162 (MERGE AFTER FIXES) and the fixes

- F1 closed, d5080e6f: whenTheAuthorizationServiceFailsADatabaseThatHoldsOnlyAViewReadsAsBefore. Seen biting with the
  isListed branch removed locally (not committed): "expected: <204> but was: <503>" (HEAD).
- F8 closed, d5080e6f: the record-only rename row is also asked with the outage only on the record's decision;
  503 can then come only from the existence check (missing: 404; a wrong "exists": 406 cross-database refusal).
  Mutation "outage counts as exists" fails that test ("expected: <503> but was: <204>" on HEAD, the first row).
- F2 javadoc, 7635dd2c. Behavior unchanged; left with the smallest alignment.
- F3 closed, 91956108: timeout swap blocks the planner in beforeExecute until the test closes it.
- F4 closed, 2e15b32f: renamed and re-commented; join(30_000) + isAlive check.
- F5 closed, 2bdba951: one rule (FormatVersionRule.refuseUpdate, IRC-only caller), handler copy removed, refusal back
  after validation and lock rule; audit asserted (two UPDATE FAILED).
- Replace lowering: 4e73a4a9, run: 200, table stays v2.
- F6, F7, behavior list: pull request body.
- Merged base a0239ad7 (#163 stand-in) as e011e171, clean. Re-ran: default 19 classes 295 tests 1 skipped 0 failed;
  reference 15 classes 281 tests 25 skipped 0 failed. No test changed outcome under the faithful stand-in.
- Pushed e011e1710ab0c7a5ec0875ced92099882fcd9e86; waiting for CI.
- Merged base 35bd941c (#164) as 448fcd6f (the hook dropped the merge state; finished with write-tree/commit-tree, both
  parents). check-private-ids.py passes with no rewording needed. Re-ran: default 295/1 skipped, reference 281/25
  skipped, 0 failed. CI on 448fcd6f5a3cc8844eaab1b1856cab6384ef1e59: 13 success, 1 skipped by design (Default build,
  Docker images, under iceberg-linkedin-build). Bottom line: done, draft pull request 162.
