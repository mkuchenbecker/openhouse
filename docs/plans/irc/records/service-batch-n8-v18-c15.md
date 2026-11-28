# Service batch N8, V18, C15

Bottom line: N8, V18 and C15 are done in draft pull request 139 (head 1e32ff4f, merged with irc-integration c2c52144); CI on 1f5b84f7 green (13 success, 1 skipped by design), CI on 1e32ff4f see end. Local: :services:tables:test and the fixtures test ran to completion on both lines; base 1.12 counts differ only by the removed and added tests. Kit, Spark itests, spotless and the test-group partition rely on CI. Worktree /home/user/oh-svc, branch irc/service-batch-n8-v18-c15 from origin/irc-integration 4b696a55.

## Log
- Worktree created at 4b696a55. Reading N8 code (PolicyTag, PoliciesSpecMapper, TablePolicyManager, PolicyPatch, s31).
- N8 [written, not yet built]: PolicyTag getter + Gson @JsonAdapter write tags in enum declaration order (PII, HC); PoliciesSpecMapper.withTagsInDeclarationOrder + TablePolicyManager tolerant compare (legacy-order stored text not rewritten by a same-set request). New PolicyTagOrderTest (4 tests, both APIs). s31 tightened to one order, runs (HC, PII) and (PII, HC).
- V18 [written, not yet built]: guards removed on IRC write/view controllers+handlers, ViewCommitEngineConfiguration; catch-all list loses the 13 write/view entries; IcebergRestBeanSignaturesTest deleted; RoutesBoundaryTest and ViewBeanBoundaryTest trimmed to tests with other subjects. Kept: ViewsServiceImpl/ViewsDisabledService pair (not in list, removal deletes tested code), IcebergRestScanController (1.12-only classes).
- C15 [written, not yet built]: cause [read+ran on 102 existing spec copies in other worktrees]: only Page* schemas vary (4 property orders seen); Page is an interface, Jackson takes property order from getDeclaredMethods, which the JVM does not fix. Fix: OpenApiCustomiser sorting Page* schema properties alphabetically, in tables and housetables.
- Waiting for >= 6 GB free memory before the first build.
- [ran] PolicyTagOrderTest on base main code (fix reverted): 4 run, 2 failed (read of other-order table answers HC,PII; PUT of same set rewrites other-order text), 2 passed by chance of that JVM's hash order.
- [ran] With fix, 1.12 line: PolicyTagOrderTest 4/4, IrcPolicyPatchTest 66, e2e TablesControllerTest 63, mock TablesControllerTest 26, RepositoryTest 71, HouseTablesH2RepositoryTest 4: all pass.
- Commits pushed: 7f8de45f (N8 tests), 3ba9ac75 (N8 fix), fd1f41df (V18).
- [ran] V18, 1.12 line: IrcReadRoutesTest 23, IrcConfigEndpointsTest 6, IrcWriteRoutesTest 43, IrcViewRoutesTest 33, fixtures test (ViewBean 4, RoutesBoundary 5, LocalServer 6, ViewAccessor 13, TomcatBoot 2, SaveVersion 2): all pass.
- C15: generating "before" client sources (C15 change set aside) - waiting for memory.
- [ran] C15: before = fd1f41df (no customiser), after = customiser; generateOpenApiDocs --rerun twice: after1 == after2 byte-identical (tables sha256 8baba939..., housetables c36a6642...); before vs after equal as JSON, only 5 Page schemas reorder. Generated tableclient (136 files) / hts (79): same file set; changed only Page models+docs+tests+api/openapi.yaml, by line order and list commas. after1 vs after2 generated: 0 non-timestamp lines differ.
- Commit 1f5b84f7 (C15) pushed. Draft pull request 139 opened. origin/irc-integration had no new commits at 14:50.
- Local full suites queued (waiting for memory); CI running on 1f5b84f7.
- [ran] Branch 1f5b84f7, :services:tables:test 1.12: 172 classes, 1730 tests, 1 skipped, 0 failed. Reference (-PicebergLinkedIn): 167 classes, 1702 tests, 51 skipped, 0 failed. Fixtures test both lines: 6 classes, 32 tests, 0 failed.
- [read] CI on 1f5b84f7: all 14 checks completed, 13 success, 1 skipped (reference "Docker images and integration tests", skipped by design).
- Base (4b696a55) counts running in /home/user/oh-svc-base for comparison.
- [ran] Base 4b696a55, 1.12 line: :services:tables:test 1727 tests (branch 1730 = -1 IcebergRestBeanSignaturesTest +4 PolicyTagOrderTest), fixtures 41 (branch 32 = RoutesBoundary 6->5, ViewBean 12->4). Base reference-line run stopped at the coordinator's request (memory).
- [read] CI 1f5b84f7: rest job kit 42 passed / 95 failed / 23 skipped, task passed (expected-outcomes.txt untouched); s31 PASSED on Legacy, RestShim, RestHttp (default) and Legacy, RestShim (reference; RestHttp SKIPPED there by N10); Spark 4.2 smokeTest and smokeTestServerPlanning s31 PASSED; test-groups jobs green on both lines.
- Merged origin/irc-integration c2c52144 (CI files and scripts/ci only) as 1e32ff4f, pushed; no local rerun (no source change).
- [read] CI 1e32ff4f: 13 success, 1 skipped by design; rest job kit counts above.
