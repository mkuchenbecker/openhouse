# Build on the owner's namespace stack (upstream 869, 870, 871) -- record

## Bottom line
In progress. Worktree /home/user/oh-ns, branch irc/build-on-owner-namespace-stack from origin/irc-integration 81b4cd4d.

## Facts [ran]
- Heads fetched 2026-11-27: 869 = 39f2324948bbb0a9c33ed5f110a98017d9e67486, 870 = e09a96252978e4e7154efc6e3572357db10ebb69, 871 = 464a220da77adffac18698c5c44df804ce9212d2 (unchanged since the scout).
- LinkedIn main now c97c4c15. The three branches' merge base with main AND with irc-integration is bb8929e1; none contains c97c4c15. So the merge brings NO LinkedIn main commit beyond bb8929e1 (the brief's assumption that it does is not true today); upstream main commit contained after the merge: bb8929e1 (as before).

## Log
- [ran] f67e88a1 = merge of 869 (no conflict; the merge created by `git merge --no-ff` through the hook wrapper).
- [ran] 770b8e85 = merge of 870. Conflict only in TablesServiceImpl.java import lines; resolved (both hunk-1 imports kept; hunk 2: this branch's set + Set + Collectors; BadRequestException/CommitFailedException/CommitStateUnknownException stay removed, unused). The pre-commit hook's `git stash --keep-index` dropped MERGE_HEAD; finished by hand with write-tree/commit-tree (parents f67e88a1, e09a9625), verified two parents. spotlessCheck run by hand afterwards: exit 0.
- [ran] 32524fd2 = merge of 871, no conflict; hook dropped MERGE_HEAD again, finished by hand (parents 770b8e85, 464a220d).
- [ran] Of the 61 files his stack changes vs bb8929e1, only 4 differ between HEAD and his 871 head, all by this branch's own earlier changes (OpenHouseCatalog 1.5, services/tables/build.gradle, MainApplicationConfig, TablesServiceImpl). No edit of his lines so far.
- Attribution: the session system notice gives `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`; used that instead of the brief's "Claude Fable 5.2".
- [ran] Compile, no source change needed: `./gradlew compileJava compileTestJava` (default, 1.12) BUILD SUCCESSFUL; `-PicebergLinkedIn compileJava compileTestJava :services:tables:compileNamespaceIntegrationTestJava` BUILD SUCCESSFUL; default `:services:tables:compileNamespaceIntegrationTestJava` OK. His 1.5 client (OpenHouseCatalog) compiles against 1.12 unchanged.
- [ran] His 15 test classes, both lines, identical results: PASSED HouseNamespaceDeadlineTest 3, HouseNamespaceRepositoryImplTest 10, IcebergNamespacesControllerTest 35, IcebergNamespacesH2Test 8 (namespaceIntegrationTest task), JdbcInitializationTest 2, NamespaceApiTransportTest 2, NamespaceCancellationTest 2, NamespaceMetadataTest 36, NamespaceTableIsolationTest 2, NamespacesControllerFailureTest 8, NamespacesServiceFailureTest 20, TablesServiceNamespaceTest 7; SKIPPED NamespaceMysqlWorkloadTest 2 (his own opt-in); FAILED locally NamespacesControllerTest 5 and NamespacesServiceTest 15: both extend LinkedIn main's HtsDatabaseTestBase (Testcontainers MySQL) and this container has no Docker daemon (IllegalStateException at DockerClientProviderStrategy). Environmental; left to CI.
- [ran] 5be7b4dc: stand-in HouseNamespacesInMemoryRepository (fixtures, @Import + @Primary in SpringH2TestApplication) + its test (8 tests, written first) + NamespaceRoutesBoundaryTest (6 tests) + import of his NamespaceExceptionHandler into SpringH2TestApplication (without it his routes answered DuplicateCreationException as 500 native shape in the embedded service: found by the boundary test). Default line: 8/8 + 6/6 + IcebergRestRoutesBoundaryTest 5/5 pass. Pushed.
- Finding [ran]: GET/DELETE /v1/databases/namespaces/tables and POST /v1/databases/namespaces/tables/properties (database literally named "namespaces", method no native route maps) now reach his controller (prefix "databases"); before, 405 [inferred for "before": the native pattern matched with another method]. Native-mapped methods keep their paths.
- Finding [ran]: in the fixture's embedded service no token interceptor is configured by default (all routes unauthenticated); with the dummy interceptor configured, his routes run the native DummyTokenInterceptor, /iceberg routes the IcebergRestAuthenticationInterceptor.
- [ran] Spark 3.5 s19/s24 + contract classes, both lines, before the expectation commit (with the stand-in): reference line 6 of 10 failed, default 4 of 10 failed (outcome TSVs saved in scratchpad smoke-ref/ and smoke-def/). Changes: legacy + rest-shim paths only (see cd6f90d8 message for the full list). rest-http path: no change on either line.
- [ran] cd6f90d8: expectations updated as declared differences "(d)" (baseline not edited); contract test rewritten to pin new answers. After: 10/10 pass on both lines. Pushed.
- [read] delta-harness (integrations/spark/delta-harness, not in Gradle or CI; run-openhouse.sh) has ddl.ns.createRejected / ddl.ns.dropRejected / surface createNamespace expecting refusal: would change [inferred, not run, not edited].
- [ran] Spark 4.2 smokeTest s19/s24 (default line only; module not in the reference build): 2/2 pass, unchanged (IRC path).
- [ran] Kit after: "160 tests, 42 passed, 95 failed, 23 skipped", the 118 non-passing exactly the listed ones; before (CI log of 81b4cd4d, job "build-run-tests / Default build, rest"): identical line. expected-outcomes.txt unchanged.
- [ran] Both lines: fixtures module test (all 9 classes: 47 tests) + services/tables IrcReadRoutesTest 23, IrcWriteRoutesTest 43, IrcViewRoutesTest 33, IrcConfigEndpointsTest 7, DatabasesControllerTest 5, TablesControllerTest 26 (mock app), ViewsControllerTest 86 (mock app): all pass. java 1.5 itest whole module: default 175 PASSED, reference 173 PASSED, 0 failed.
- [ran] check-test-groups.py: partition holds; namespaceIntegrationTest, housetables:test, internalcatalog:test in `rest`.
- [ran] final compile both lines: BUILD SUCCESSFUL. Head cd6f90d8df072cbd64c32612c8d9f0efdd1392d0.
- [ran] Draft pull request #153 opened (base irc-integration), head cd6f90d8df072cbd64c32612c8d9f0efdd1392d0. Waiting for CI.
- [ran] CI on cd6f90d8df072cbd64c32612c8d9f0efdd1392d0: 14 check runs, 13 success, 1 skipped ("iceberg-linkedin-build / Default build, Docker images and integration tests", skipped by design as on the base). Kit line in the Default rest job log identical to the base. His Docker-backed NamespacesServiceTest/NamespacesControllerTest ran and passed in both rest jobs. Dataloader workflows (present on the base) did not run for this head (path filters, inferred).

## Bottom line (final)
Merged 869, 870, 871 as three merge commits plus two commits of our own (stand-in + boundary test; Spark 3.5 expectations). Builds and passes on both lines, locally and in CI (all 13 jobs green). No file of his edited. Draft pull request #153.

## Review round (pr-review-153.md)
- [ran] 2026-11-28 re-fetch: heads unchanged (39f23249, e09a9625, 464a220d).
- [ran] commit (USE/SQL create-drop/labels) pushed: 0a485be3. Spark 3.5 12/12 both lines; 4.2 4/4.
- [ran] 94528ec9 pushed: boundary takeover pins (16 requests; before measured on detached worktree of 81b4cd4d: 405 x8, 400 x8), config test rewritten (F7), contract shared stand-in/real store (F3; divergence found and fixed: invalid document is WebClientResponseException.BadRequest), harness baseline doc (F5). Note: that commit message says "tables-test-fixtures test (all classes, 63)"; correct is fixtures 47 + namespaceIntegrationTest 16 = 63 together.
- [ran] compile both lines OK; kit unchanged 160/42/95/23; check-test-groups holds.
- [ran] PR #153 body updated with full Behavior changes (sections 1-7) and "Observed in the merged namespace stack". Waiting for CI on 94528ec93d8b1a6493e38fef4bf70bddab4a5264.
- [ran] CI on 94528ec9: all 12 test jobs + 2 test-groups jobs success; 'Default build, Docker images' (iceberg-linkedin-build) skipped as on base; two extra skipped runs named 'iceberg-linkedin-build' and 'build-run-tests' (no tests; likely from the description edit re-triggering the workflows' pull-request event [inferred]). Contract test 8/8 on real store in both rest jobs.
