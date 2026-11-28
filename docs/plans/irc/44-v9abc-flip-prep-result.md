# Result: V9a, V9b, V9c, C6 and T9 (three pull requests, 109 to 111)

Bottom line: three of the five items needed to make Iceberg 1.12 the default build are done (V9a, V9b, V9c), and
the opt-in build now runs on every pull request (C6, T9). All three pull requests are merged into
`irc-integration`. After them, the old Iceberg 1.2 / Spark 3.1 project directories can be deleted without touching
another project's sources. Eleven other things still block that deletion; they are listed at the end, because V9d
and V9e work from that list. Written 2026-11-23 from the delegated agents' reports. Their reports are in the
orchestrator's scratchpad, not in the repository: `v9a-flip-prep.md`, `v9c-move-shared-sources.md` and
`c6-t9-optin-on-pull-requests.md`.

CI on pull request 111's own run was green on both jobs, with pull requests 109 and 110 already merged into its base.
So the three merges are green together on the default line and on the 1.12 line. This is the orchestrator's reading
of that run; the C6 agent's report was written while the run was still going.

## Pull request 109: V9a and V9b

Merge commit `6bc6fb05a3d06e19422152e783c05be7106b3d49`.

What changed:
- The fork's published artifacts now live under the Gradle user home, in `<Gradle user home>/iceberg-fork/<version>`,
  and all worktrees on a machine share them. Before, each checkout built its own copy inside `build/`, which `clean`
  deleted. The opt-in build now survives `clean`. `ICEBERG_FORK_HOME` overrides the location.
- Under the opt-in switch every Iceberg jar is the fork's, including `iceberg-aws`. Before, `iceberg-aws:1.2.0.6`
  sat in 14 projects next to the fork's 1.12 jars. The AWS SDK is 2.54.17 in place of 2.20.18.
- The pin moved to the fork tip `a88f556c` (the time-travel fix, I11).
- The service's own HTTP client had to be protected: the new SDK brought a second Netty line that split Netty between
  4.1.138 and 4.1.86 and made the service's calls to the house table service fail or hang. The one SDK module that
  caused it, `netty-nio-client`, is excluded under the switch.

Evidence (all run by the agent):
- Both `interact.branch.ttBeforeBranchPoint` harness cases (Parquet and ORC) pass on the new pin, first try, on a
  classpath with only fork Iceberg jars. The full harness was not run.
- From a fresh worktree: `-PicebergFork clean assemble testClasses` passed (3 min 52 s); a second clean plus compile
  passed (34 s). A cold publish took 2 min 05 s; an already published fork takes 0.014 s. Disk: 109 MB for the
  repository plus 305 MB for the shared source checkout. Before, 17 copies of about 400 MB each existed on one machine.
- All 11 Iceberg modules resolve to `1.12.0-ohfork.a88f556c5067`, and the AWS SDK is 2.54.17 everywhere.
- A snapshot summary on the Spark 3.5 apps classpath said Iceberg 1.2.0.6 before and now names the fork version.
- The dump of every resolvable configuration in all 60 projects on the default build is byte-identical before and
  after (36,565 lines).
- The compatibility kit passes with its expected-outcomes list unchanged.

Not run: the full harness, the full test suites on this branch alone (the three pull requests were checked together
in CI), and anything against real S3 (see T11).

Known gaps:
- `clean` also deletes the OpenAPI generator jar, which is downloaded again from Maven Central; Central answered HTTP
  429 twice during these runs. The default build does the same.
- The publish script's lock uses `flock`, which macOS does not have (C7).
- Under memory pressure one `services:tables` run failed `LockClientServerTest` 8 of 8; the cause was not proven to
  be the Netty split.

## Pull request 110: V9c

Merge commit `7e8941abfe5900e4faac2f5ceea90d9b70c363f2`.

What changed: 193 files that the newer projects borrowed from the Iceberg 1.2 / Spark 3.1 projects now live in the
newer projects under `src/shared`. Every move is a 100 percent rename. The rule in all five pairs: `src/shared` is
what both lines compile; the projects' own `src/main` and `src/test` keep their old meaning. A new file added under an
old shared directory by another branch merges cleanly but only the old project compiles it, so it belongs under the
newer project's `src/shared`. The workflow files and the dataloader README that name `dummy.token` follow the move.

| Shared set | Files |
| --- | --- |
| Tables test fixtures (main 9, resource 1, tests 6) | 16 |
| Java client (main 20, resource 1) and its tests (4) | 25 |
| Spark itest tests both lines run | 39 of 49 |
| Spark jobs, main 77 and tests 36 | 113 |

Evidence (all run by the agent, without the Gradle build cache):
- Deletion proof: in a throwaway tree, the four old directories (47 files) and their `settings.gradle` entries were
  deleted. The opt-in build then compiled all main, test and service code (`BUILD SUCCESSFUL`, 120 tasks, 60 compile
  tasks, 1 min 17 s). No file outside those directories and `settings.gradle` was touched. The default build cannot
  work in that tree; blockers 1 and 2 below say why.
- Every jar of both builds has the same entries as before: 52 jars on the default build and 44 on the opt-in build,
  zero differences. Compiled classes are byte-identical in every changed project.
- Default build, local full run: 4440 tests, 0 failed, 12 skipped, `BUILD SUCCESSFUL` in 41 min 5 s.
- Opt-in build, local, run in steps because the first run was killed for memory: 3886 tests, 0 failed, 6 skipped.
  The compatibility kit task passes with its listed outcomes (160 tests, 99 failed and 23 skipped as listed).

Not run: the Docker compose steps of the two workflows whose paths changed; the default suites again on the final
merge commit (after the merge of pull request 107 only `spotlessCheck`, `assemble`, `testClasses` and
`:services:tables:test` ran on the default build); consumers outside this repository.

Known gaps:
- The default build differs byte for byte between two builds of the same sources in three places: two generated
  OpenAPI client classes in `:client:hts`, and `spring-configuration-metadata.json`. The cause was not determined
  (recorded in `21-findings-continued.md`).
- `:integrations:java:openhouse-rest-compat-kit:test` fails with "No tests found" under the switch. It fails the same
  way without this change and is not in CI's list (T12).
- Under the switch the Spark 3.5 apps uber jar and the 1.5 fixtures uber jar cannot be built, before or after: the
  shadow plugin stops on "Unsupported class file major version 65" (blocker 3).
- `integrations/python/dataloader/CLAUDE.md` lines 36 and 49 still name the old `dummy.token` path (C8).

## Pull request 111: C6 and T9

Merge commit `6cc7b0923a69bd1241ab02c6467126ccefa935df`.

What changed:
- Every pull request now runs the default build and the Iceberg 1.12 build. The opt-in job's steps moved, verbatim,
  into one reusable workflow, `.github/workflows/iceberg-fork-build.yml`, which `post-merge-build.yml` and
  `pr-validations.yml` both call. No step exists twice.
- A pull request restores the fork cache but saves it only if the pull request changes the pin or the publish
  script. The two jobs use different concurrency groups, so they never cancel each other.
- T9: the compatibility kit's test warehouse no longer sits under the checkout path. It uses a short temporary
  directory, as the Spark 4.2 services do. `expected-outcomes.txt` is unchanged.

Evidence (run by the agent): from a 17-character checkout path the kit passes 38, fails 99 and skips 23, matching
the list. From an 87-character path, before the fix, it passed 1 and failed 136; after the fix it again passes 38,
fails 99 and skips 23. `actionlint` reported 0 errors. The change detection gave `changed=false` against base
`6bc6fb05` and `changed=true` against a base that changed the pin. On pull request 111 the detection and cache restore
steps succeeded and the publish step took under a second.

Not proven: a pull request that changes the pin and saves the cache; a deliberately broken suite under the switch
turning a pull request red (the work item's proof for C6); cancellation of an older opt-in run by a newer push; the
post-merge call of the reusable workflow before its first run. A stacked pull request whose base changed the pin
misses the cache on every push and saves nothing.

## What still blocks deleting the old projects (for V9d and V9e)

Not fixed by these pull requests. From section 5 of the V9c report.

1. `libs/datalayout/build.gradle` lines 8 to 14: on the default build it depends on the Spark 3.1 runtime and the
   Iceberg 1.2 fixtures.
2. `services/tables/build.gradle` lines 126 to 128: default-build tests depend on `tables-test-fixtures_2.12`.
3. Uber jars under the switch fail with "Unsupported class file major version 65" (a dependency with Java 21 class
   files; `parquet-jackson` 1.17.x and `jackson-core` 2.18 and later have them; which one is on the classpath is not
   determined). The jobs scheduler image needs the apps uber jar, so V9d meets this first.
4. Root `build.gradle`: `dockerPrereqs` (lines 234 and 238) depends on the old Spark 3.1 runtime and apps
   `shadowJar`; also `iceberg_1_2_version` (line 33), `spark_version = "3.1.1"` (line 30) and
   `openhouse.iceberg-conventions-1.2.gradle`.
5. Docker files: `jobs-scheduler.Dockerfile` line 6; `spark-base-hadoop2.8.dockerfile` lines 86 and 87;
   `spark-3.5-base-hadoop3.2.dockerfile` line 86. The image-internal jar names `openhouse-spark-apps_2.12-latest-all.jar`
   and `openhouse-spark-runtime_2.12-latest-all.jar` are used in `oh-hadoop-spark/jobs.yaml`, `livy_cli.py`, the
   dataloader integration tests and the Azure sandbox script.
6. CI: `build-run-tests.yml`, `dataloader-tests.yml` and `build-tag-publish.yml` (which publishes every module and
   builds the scheduler image from the old apps jar).
7. Published artifact names stop being published (`openhouse-spark-runtime_2.12`, `openhouse-spark-apps_2.12`,
   `tables-test-fixtures_2.12`, `openhouse-java-runtime`, `openhouse-java-itest`, `openhouse-spark-itest`), and the
   `all-modules` POM shrinks. Outside consumers were not looked at.
8. `settings.gradle`: six entries and two rename blocks (27 lines).
9. Tests that exist only on the old line: ten Spark 3.1-only tests go with the project. Five have running Spark 3.5
   counterparts. The other five (`AlterTableTestSpark3_5`, `AlterTableSchemaTestSpark3_5`, `CreateTableTestSpark3_5`,
   `CreateTablePartitionedTestSpark3_5`, `CreateTableWithPropsTestSpark3_5`) are never compiled, because the Spark 3.5
   itest's patterns `**/AlterTable*.java` and `**/CreateTable*.java` match them. After V9e those statements would
   run nowhere. Work item T10; whether the classes compile and pass was not checked.
10. `apps/spark/src/main/resources/log4j2.properties`: only the old apps jar packages it. Moving the scheduler image
    to the Spark 3.5 apps jar changes its logging unless the file is carried over on purpose.
11. Documents and comments that name the old projects: `ARCHITECTURE.md` lines 71 and 176, `SETUP.md` lines 307 and
    325, `docs/development/intellij-setup.md` line 21, `renovate-iceberg-sync.md` line 8, `iceberg-fork.md` line 49,
    comments in three IRC classes, and `apps/spark-3.5/build.gradle` line 37.
