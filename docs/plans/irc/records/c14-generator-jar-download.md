# C14: the OpenAPI generator CLI jar download

Pull request 123 into `irc-integration`, branch `irc/c14-generator-jar-download`, head
`59848fa487491b4ecd2f6438e1b12b7bda46d7f9` (base `440249d1`). Worktree `/home/user/oh-c14`.
Each fact is marked [ran] (I executed it and saw the result) or [read] (I read it in a file or log).

## 1. Facts before the change

- [read] `client/common/build.gradle`: `task setUp(type: Exec)` ran
  `sh client/common/jar_download.sh -o <root>/build/openapi-cli -v 5.3.0`, with `outputs.dir` and no inputs.
- [read] `jar_download.sh`: if `openapi-generator-cli-5.3.0.jar` exists in the directory, exit 0 (any
  file counts, no size or checksum check); otherwise one
  `curl -f https://repo.maven.apache.org/maven2/org/openapitools/openapi-generator-cli/5.3.0/openapi-generator-cli-5.3.0.jar --output <file> || exit 1`.
  No retry, no `-sS`, no URL in the output.
- [read] Stored in `build/openapi-cli/` under the ROOT build directory. [ran] The root project's
  `clean` deletes it: after `clean`, `setUp` executes again (base: curl runs again).
- [read] Consumers: `client/common/codegen.build.gradle` (`clientCodeGen`, an `Exec` of
  `client_codegen.sh -j build/openapi-cli -v 5.3.0`, `dependsOn ":client:common:setUp"`), applied by
  `:client:hts`, `:client:jobsclient`, `:client:tableclient`, `:client:optimizerclient`. Their jars feed
  `:client:secureclient`, `:services:tables`, `:services:jobs`, `:iceberg:openhouse:internalcatalog`,
  the Java runtime.
- [ran] Up to date on a second run: yes (`:client:common:setUp UP-TO-DATE`) as long as
  `build/openapi-cli` is untouched; never after `clean`.
- [read] Both CI jobs run `clean` (`./gradlew clean assemble testClasses`,
  `./gradlew -PicebergLinkedIn clean build --continue`), so each fetched the jar on every run; the
  `setup-gradle` cache does not cover `build/`.
- [read] CI run 45212160192, job 137823569657 (log saved, 953 lines): line 341
  `curl: (22) The requested URL returned error: 429`, line 347 `> Task :client:common:setUp FAILED`,
  lines 828-830 the summary `Process 'command 'sh'' finished with non-zero exit value 1`,
  `BUILD FAILED in 39s`. So the status IS in the log, but without the URL, interleaved with other
  tasks' output, 488 lines above the summary. The task statement "nothing in the log says why" is
  true of the failure summary, not of the whole log.
- [ran] Reproduced for real, no simulation: at the base commit, the first `./gradlew :client:common:setUp`
  in the new worktree failed with exactly that (`curl: (22) ... 429`, then the `sh` summary); the
  second attempt, seconds later, downloaded the jar.
- [ran] The jar: 24,708,877 bytes, SHA-256
  `f29d9d715a0d67cf1457d918ae7ed33f02d7fb2730c018d246a8d4a93d5ba7e1`, SHA-1
  `38149dc5a13274d34810bd8e5552f55b6bdc9284` (equal to the `.sha1` Maven Central publishes).

## 2. The change: option (a)

- `client/common/build.gradle`: configuration `openApiGeneratorCli` (`canBeConsumed = false`,
  `transitive = false`) with `org.openapitools:openapi-generator-cli:5.3.0`; `setUp` is now a `Sync`
  from that configuration into `build/openapi-cli`.
- `client/common/jar_download.sh` deleted. `codegen.build.gradle`: one comment.
- `client_codegen.sh`, the four client `build.gradle` files, the task name `:client:common:setUp`
  and the jar's place and name are unchanged, so no client project changes how it invokes the generator.
- Why not pass the resolved file straight to the generator: it would change `client_codegen.sh`'s
  `-j DIRECTORY -v VERSION` interface and `codegen.build.gradle` for no gain; the copy costs 24 MB
  after a `clean`.
- Option (b) not taken: (a) works without touching the client projects, and (b) would keep a
  hand-written downloader.
- Second commit (comment only) corrects the first commit's message, which wrongly says Gradle checks
  the jar against the repository's checksum (see gaps).

## 3. Proofs (JDK 17, Gradle 8.14.5, `--max-workers=2`; default line = no flag, reference line = `-PicebergLinkedIn`)

- [ran] Same jar bytes: SHA-256 above for the curl download, for
  `~/.gradle/caches/modules-2/files-2.1/org.openapitools/openapi-generator-cli/5.3.0/38149dc5.../openapi-generator-cli-5.3.0.jar`
  and for `build/openapi-cli/openapi-generator-cli-5.3.0.jar` after `setUp`.
- [ran] Generated sources, per line: at the base commit
  `./gradlew [-PicebergLinkedIn] clean :client:hts:clientCodeGen :client:jobsclient:clientCodeGen :client:tableclient:clientCodeGen :client:optimizerclient:clientCodeGen`,
  copy `build/{hts,jobsclient,tableclient,optimizerclient}/generated`; then on the branch, same build
  directory, no `clean`, the same four tasks each with `--rerun` (spec tasks `UP-TO-DATE`, the four spec
  files' SHA-256 equal before and after); `diff -r before after`. Both lines: 320 files each side, 0
  "Only in", 97 files differ in 1 line each, 93 of them
  `@javax.annotation.Generated(value = "...JavaClientCodegen", date = "<timestamp>")` and 4
  `- Build date: <timestamp>` (README), 0 other lines. Byte-identical except the generator's own
  timestamp.
- [ran] Why the spec was held fixed: with no code change, two from-clean generations at the base commit
  on the default line produced different `housetables.json` and `tables.json` (order of the Spring
  `Page` properties), 734 differing non-timestamp lines in the generated trees. The generated sources
  were never byte-reproducible from clean; a from-clean before/after diff would measure that, not this change.
- [ran] From clean, twice, each line:
  `./gradlew [-PicebergLinkedIn] clean :client:common:setUp :client:hts:compileJava :client:jobsclient:compileJava :client:tableclient:compileJava :client:optimizerclient:compileJava :services:tables:compileJava :client:secureclient:test`
  BUILD SUCCESSFUL; then the same with `--offline`: BUILD SUCCESSFUL, `:client:common:setUp` executed
  (the copy had been deleted by `clean`) with no network. Without `clean`: `:client:common:setUp UP-TO-DATE`.
- [ran] In those runs `:services:tables:compileJava` (and on the default line `:client:secureclient:test`)
  came FROM-CACHE, so, each line: `./gradlew [-PicebergLinkedIn] --offline :services:tables:compileJava --rerun :client:secureclient:test --rerun`
  BUILD SUCCESSFUL, 20 tests passed, 0 failed (3 result files: 6, 6, 8 tests).
- [ran] On the final head, each line: `./gradlew [-PicebergLinkedIn] --offline clean :client:common:setUp :client:tableclient:compileJava :client:common:spotlessCheck` BUILD SUCCESSFUL.
- [ran] First resolution (`--info`): `Downloading https://repo.maven.apache.org/maven2/org/openapitools/openapi-generator-cli/5.3.0/openapi-generator-cli-5.3.0.jar`, once; never again afterwards.
- [ran] Simulated failure (throwaway edit, not committed: the project's repository replaced by a local
  server answering one status): 429 and 503 both give
  `Execution failed for task ':client:common:setUp'. > Could not resolve all files for configuration ':client:common:openApiGeneratorCli'. ... > Could not get resource 'http://127.0.0.1:18429/maven2/org/openapitools/openapi-generator-cli/5.3.0/openapi-generator-cli-5.3.0.pom'. > Could not HEAD '...'. Received status code 429 from server: Too Many Requests`
  (503: `Service Unavailable`). The server saw 3 requests each time; BUILD FAILED in 8 s / 7 s.
- [ran] `spotlessCheck` (whole build) BUILD SUCCESSFUL on both lines; `--warning-mode all` shows no
  deprecation from `client/common/build.gradle`. No workflow touched, so no `actionlint`.

## 4. CI

- [read] Run 45216713993 (event `pull_request`, attempt 1, head `59848fa487491b4ecd2f6438e1b12b7bda46d7f9`): conclusion success, no job re-run.
  - Job 137839700298 "build-run-tests / Build and Run Tests": success, 16:27:42Z to 16:49:38Z.
    Log: `> Task :client:common:setUp` executed in "Compile everything" (BUILD SUCCESSFUL in 1m 41s), then
    `UP-TO-DATE` in the next two Gradle steps (16m 25s, 1m 42s).
  - Job 137839700671 "iceberg-linkedin-build / Reference build on LinkedIn's Iceberg 1.5.2": success,
    16:27:46Z to 16:48:53Z. Log: `> Task :client:common:setUp` executed, the four `clientCodeGen` ran,
    BUILD SUCCESSFUL in 20m 39s.
  - Both logs end with "Cache is read-only", so both runners fetched the jar from Maven Central through
    Gradle in this run, and it answered.

## 5. Not covered / known gaps

- [ran] Gradle's retry is 3 requests in quick succession, for 429 as for 503, and it did not wait for
  `Retry-After: 1`. A sustained rate limit on an empty cache still fails the build (now with URL and
  status). What is removed is the repeated fetch, not Maven Central's limit.
- [read] CI's Gradle cache is written only on `irc-integration` (post-merge build,
  `gradle-cache-write: true`; reference job `cache-read-only` unless `refs/heads/irc-integration`).
  Until a post-merge build with this change has saved it, each pull-request job fetches the jar once
  through Gradle. I did not observe a post-merge cache save; that the saved cache then contains the jar
  is inferred from `setup-gradle` caching the Gradle user home.
- [ran] No checksum verification: a stand-in repository served a jar next to a non-matching `.sha1`;
  Gradle never requested the `.sha1` and `setUp` copied the jar. The build has no dependency
  verification metadata. The old script verified nothing either. Commit 829c251b's message claims the
  opposite; commit 59848fa4 corrects it. A pinned SHA-256 in `setUp` would be about ten lines; not
  added, because it was asked for option (b) only.
- [read] The version `5.3.0` is still written twice (`build.gradle`, `codegen.build.gradle`), as before.
- The nondeterministic spec order (section 3) is a separate defect, not addressed.
- Not run locally: the full `clean build` of either line (CI does), Docker steps.

## 6. Machine notes

- No separate Gradle user home was used. The shared Gradle cache gained
  `org.openapitools/openapi-generator-cli/5.3.0` (wanted: every worktree on this machine now resolves
  it offline) and one 17-byte probe artifact `org.c14probe/probe-1795451081/1.0` from the checksum
  test, left in place rather than editing a cache other builds are using.
- `git stash list` was empty before and after each of my commits and pushes. Once, between them, it
  showed a foreign entry `On irc/x1-format-v3: GitHookUnstagedStash1795450991`; it was gone 20 seconds
  later (another worktree's hook in progress). I did not touch it.
- The hooks print `pre-commit: 14: [[: not found` (they are run by `sh`), so a failing
  `spotlessCheck` would not print its hint; the exit code still propagates.
