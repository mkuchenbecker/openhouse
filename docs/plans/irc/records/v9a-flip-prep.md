# V9a, V9b and the fork pin bump

Date 2026-11-23. Branch `irc/v9a-flip-prep` (mkuchenbecker/openhouse), from `irc-integration` 7ddc5bb4, merged forward
to 3222cf78 (pull requests 106, 107 and 108). Draft pull request 109:
https://github.com/mkuchenbecker/openhouse/pull/109, head `515769d186545c8796fecfcf566d971efc3998ec`.
"Ran" means run here for this record; "read" means taken from code or records.

## Bottom line

- **Harness (ran).** Both `interact.branch.ttBeforeBranchPoint` cases pass on the new pin a88f556c: Parquet and ORC,
  2 of 2 on the first try. The classpath carried only fork Iceberg jars.
- **V9a (ran).** The published fork now lives at `${ICEBERG_FORK_HOME:-<Gradle user home>/iceberg-fork}/<version>`, for
  example `~/.gradle/iceberg-fork/1.12.0-ohfork.a88f556c5067`. From a fresh worktree:
  - `publish-local.sh` was a no-op (0.016 s, already published);
  - `-PicebergFork clean assemble testClasses` passed (3 min 52 s);
  - a second `clean` plus compile passed (34 s).
- **Publish timings (ran).**
  - Cold, at the new location with the new pin and Gradle's build cache warm: 2 min 05 s. That is three Iceberg
    builds: 1 min 13 s, 31 s and 15 s.
  - Already published: 0.014 s.
  - Disk: 109 MB for the repository plus 305 MB for the shared source checkout.
- **V9b (ran).** Under the switch no Iceberg artifact from another line is on any resolved classpath. Before this
  change, `iceberg-aws:1.2.0.6` sat in 14 projects next to the fork's 1.12 jars. After it:
  - every one of the 11 Iceberg modules is at `1.12.0-ohfork.a88f556c5067`;
  - the AWS SDK is at 2.54.17 everywhere;
  - Netty is the same per project as before the change.
- **Snapshot summary (ran, probe).** On the Spark 3.5 apps test classpath under the switch, a snapshot summary said
  `Apache Iceberg 1.2.0.6 (commit 1cf3a9a0...)` before. It now says
  `Apache Iceberg 1.12.0-ohfork.a88f556c5067 (commit a88f556c506744bb8e99617d58c9c7f1a9f81e58)`.
- **Default build untouched (ran).** The dump of every resolvable configuration and source set in all 60 projects
  (36,565 lines) is byte-identical before and after, on the final head.
- **One problem found and fixed on the way.** The SDK bump brought `netty-nio-client`, which split Netty between
  4.1.138 and 4.1.86. On that mix the service's WebClient calls to the house table service failed or hung. Excluding
  that one SDK module under the switch fixed it.

## Item 1: pin bump (ran)

- **Pin.** `commit=a88f556c506744bb8e99617d58c9c7f1a9f81e58`, the tip of `openhouse-1.12.0` (checked with
  `git ls-remote`). Compared with 28742df3 it adds 3 files: `SparkReadConf.java` (+6) and a WAP time-travel test for
  Spark 3.5 and for 4.2.
- **Harness run.**
  - Command: `HARNESS_ICEBERG_FORK=1 FORCE_CP=1 HARNESS_PARALLELISM=2 HARNESS_JVM_ARGS="-Xmx3g -XX:MaxDirectMemorySize=512m -XX:ReservedCodeCacheSize=160m"`
    with a private TMPDIR, the wrapper Gradle with `--max-workers=2`, and filter `ttBeforeBranchPoint`.
  - Output: `PASS interact.branch.ttBeforeBranchPoint @ parquet try=1`, `PASS ... @ orc try=1`, `2 passed, 0 skipped, 0 failed`.
  - Wall time: 3 min 09 s, including resolving the classpath.
  - The runtime was `iceberg-spark-runtime-3.5_2.12-1.12.0-ohfork.a88f556c5067.jar` and `iceberg-aws` was at the fork
    version on the harness classpath.
  - The full harness was not run.
- **Compatibility kit.** `restCompatKitTest` passes with `expected-outcomes.txt` unchanged. The pin bump changed no kit
  outcome, and this branch does not touch the file.

## Item 2: V9a, where the fork lives and why (ran)

Choice: a directory under the Gradle user home, keyed by the published version. The version carries the first 12
characters of the commit. The source checkout is shared too (`<home>/src`). `ICEBERG_FORK_HOME` overrides the
location for both the script and Gradle. Why:

- `clean` never touches the Gradle user home, and git never sees it.
- Many worktrees exist on this machine, and each used to republish for one to four minutes. I counted 17
  `build/iceberg-fork/repo` copies here, about 400 MB each. One copy per pin per machine replaces them.
- A git-ignored directory at the repo root would survive `clean` but would still cost one publish per worktree.
- D7 said "a build-local Maven directory". This is still local and unpublished, but it is no longer inside the
  checkout. The decision record's wording should follow.

Safety:
- `flock` on `<home>/.lock` serializes concurrent runs. The lock's file descriptor is closed for the Iceberg Gradle
  child, and a run that waited re-checks the markers.
- A step's marker is removed before that step republishes, so a republish that stops halfway leaves no false
  "complete" marker.

Other wiring:
- Gradle exposes `ext.icebergForkRepo`. The Spark 4.2 runtime, the Spark 4.2 itest and the compatibility kit now read
  it from there instead of repeating `build/iceberg-fork/repo`.
- CI: the pin step also outputs the version. The restore and save steps use the path
  `~/.gradle/iceberg-fork/<version>` and the key `iceberg-fork-home-<os>-<commit>-<hash of publish-local.sh>`.
- Old `build/iceberg-fork` directories in other worktrees were not touched.

Proof (ran) from a fresh worktree `/home/user/oh-v9a-fresh` at c2b47b26, removed afterwards:

| Step | Result |
| --- | --- |
| `publish-local.sh` | no-op, 0.016 s ("already published in /root/.gradle/iceberg-fork/...") |
| `-PicebergFork clean assemble testClasses` | BUILD SUCCESSFUL, 3 min 52 s, 220 tasks |
| `-PicebergFork clean :iceberg:openhouse:htscatalog:compileJava :services:tables:compileJava` | BUILD SUCCESSFUL, 34 s |

The failure before this change was recorded by I6f (read), not re-run here.

A related gap: `clean` also deletes `build/openapi-cli/openapi-generator-cli-5.3.0.jar`, which `:client:common:setUp`
then downloads again. Maven Central answered HTTP 429 twice during `clean` runs here. The download is the same on the
default build; it was retried after a wait.

## Item 3: V9b, `iceberg-aws` from the fork (ran)

**Declarations (read).** `iceberg-aws` is declared only in `buildSrc/.../openhouse.iceberg-aws-conventions.gradle`.
Three projects apply it: `services:tables`, `iceberg:openhouse:internalcatalog` and `cluster:storage`. Eleven more
resolve it through them. Every other `com.linkedin.iceberg` declaration already uses `iceberg_1_5_version`, or belongs
to the 1.2 line, which is excluded under the switch. `iceberg-azure` is commented out. No `org.apache.iceberg`
artifacts are declared.

**Changes.**
- The script publishes `iceberg-aws` in its first Iceberg build. It has no separate marker, because the repository
  moved in this branch.
- Under the switch, the conventions take `iceberg-aws` at the fork version and the SDK modules `s3`, `sts`, `kms` and
  `url-connection-client` at `awsSdkVersion=2.54.17`, the fork's `awssdk-bom` from its `libs.versions.toml`.
- `netty-nio-client` is excluded from `s3`, `sts` and `kms`.
- Without the switch the four original declarations are unchanged.

**Test-first evidence (ran).**
1. With only the jar swapped, `S3StorageClientTest` failed 3 of 3 with
   `NoClassDefFoundError software/amazon/awssdk/services/kms/model/EncryptionAlgorithmSpec` from `AwsClientFactories`.
   Iceberg-aws 1.12 takes the SDK compileOnly.
2. With SDK 2.54.17 plus `kms`, the storage tests passed. `apache5-client`, iceberg-aws 1.12's default HTTP client,
   comes in through `s3`.
3. The full suites then showed a second break. SDK 2.54.17's `netty-nio-client` raised
   buffer/codec/codec-http/http2/common/handler/resolver/transport to 4.1.138, while resolver-dns, codec-dns,
   handler-proxy and reactor-netty 1.0.27 stayed at 4.1.86.
   - Run alone, `HouseTableRepositoryImplTest` failed `testWriteTimeout` and
     `testRetryForHtsFindByIdCallOnConcurrentException`, then hung until a 15-minute timeout.
   - In the full `services:tables` run, `LockClientServerTest` failed 8 of 8 with blocking-read timeouts. That run was
     under memory pressure, so this one is not proven to have the same cause.
4. With `netty-nio-client` excluded:
   - `HouseTableRepositoryImplTest` passes 27 of 27;
   - `LockClientServerTest` passes 8 of 8, and the storage tests pass;
   - every project's Netty set is identical to the set before the change, except `cluster:storage`, which now has no
     Netty at all.

**Classpaths under the switch (ran; dump of every resolvable configuration in every project, grepped):**

| | Before (pin a88f556c, old conventions) | After (final head) |
| --- | --- | --- |
| Iceberg modules | 10 at `1.12.0-ohfork.a88f556c5067`, plus `com.linkedin.iceberg:iceberg-aws:1.2.0.6` in 14 projects | all 11 (api, aws, bundled-guava, common, core, data, open-api, orc, parquet, spark-runtime 3.5 and 4.2) at `1.12.0-ohfork.a88f556c5067`; 0 entries at another version |
| AWS SDK | 2.20.18, including `netty-nio-client` and `apache-client` | 2.54.17, including `apache5-client` and `kms`; no `netty-nio-client` |
| Other differences | | `dev.failsafe:failsafe:3.3.2` added (an iceberg-aws dependency); source sets identical |

**Snapshot summary (ran).** The probe (`scratchpad/v9a/probe/SummaryProbe.java`) creates a HadoopTables table, appends
one file, and prints the summary's `iceberg-version`, `IcebergBuild.fullVersion()` and the first
`iceberg-build.properties` on the classpath.

| Classpath | iceberg-version in snapshot summary | first iceberg-build.properties |
| --- | --- | --- |
| apps-1.5 testRuntime, switch, before | `Apache Iceberg 1.2.0.6 (commit 1cf3a9a0c06d...)` | iceberg-aws-1.2.0.6.jar |
| apps-1.5 testRuntime, switch, after | `Apache Iceberg 1.12.0-ohfork.a88f556c5067 (commit a88f556c...)` | iceberg-aws-1.12.0-ohfork...jar (same version as all others) |
| apps-1.5 testRuntime, default build | null (LinkedIn 1.5.2.24 writes no `iceberg-version`) | iceberg-aws-1.2.0.6.jar |
| internalcatalog runtime, switch, before | 1.12 (iceberg-data happens to come first there) | iceberg-data |

On the default build, `IcebergBuild.fullVersion()` says `Apache Iceberg 1.2.0.6` on the apps classpath. This is
reported in the guide and not changed.

**What remains untested for S3.** No real S3 is available here. The following are untested:
- `S3FileIO` reads, writes and deletes against an endpoint;
- the `apache5` HTTP client talking to S3;
- credentials, STS and KMS (SSE-KMS);
- whether iceberg-aws 1.12's newer defaults work against LinkedIn's S3 setup, such as checksums, analytics-accelerator
  off, and multipart.

The mock tests (`S3StorageClientTest`, `S3StorageTest`) only build the client and check the properties. The service
starting under the switch is covered by the `services:tables` Spring tests and by the service JVMs of the Spark 4.2
itest and the compatibility kit; none of them uses S3 storage.

## Item 4: docs

`docs/development/iceberg-fork.md` now covers:
- the new location, sharing, `ICEBERG_FORK_HOME` and the lock;
- `clean build` working under the switch;
- the cold-publish timing;
- `iceberg-aws` and the SDK handling, with the Netty exclusion and the reason for it;
- the default build's version string;
- the WAP time-travel fix at the pin, and the D31 accepted differences;
- the CI cache path.

## Final runs under the switch (ran, final head unless noted)

| Task | Result |
| --- | --- |
| `clean assemble testClasses` | pass (114 s, at 4f2830e9; the first try hit HTTP 429 on the openapi jar and was retried after a wait) |
| `:services:tables:test` | 1536 / 0 failed / 0 skipped (at 4f2830e9) |
| `:cluster:storage:test` | 11 / 0 / 0 (at 4f2830e9) |
| `:iceberg:openhouse:internalcatalog:test` | 319 / 0 / 0 (at 4f2830e9) |
| Spark 3.5 itest `catalogTest` | 269 / 0 / 1 (at 4f2830e9) |
| Spark 4.2 itest `test` | 38 / 0 / 0 (at 515769d1, after merging pull request 108). Earlier at 4f2830e9 it went 38 / 1 / 0: `SparkIrcEndpointsTest.existenceChecksAreHeadRequests` expected `HEAD /iceberg/v1/namespaces/irc42_head_probe 404` in the access log and found nothing. It passed on two reruns; this looks like an access-log race, not caused here |
| Spark 4.2 itest `extensionsTest` | 24 / 0 / 0 (at 515769d1) |
| `restCompatKitTest` | pass: 160 tests, 99 failed, 23 skipped, "the 122 tests that do not pass are exactly the ones listed"; list unchanged (at 4f2830e9) |
| `spotlessCheck` | pass, default and under the switch |

Pull request 108 changed only Spark 4.2 test sources and the guide, so only the Spark 4.2 itest was re-run after
merging it.

The first `services:tables` run (before the Netty fix and before merging pull request 107) failed in three ways:
- 4 `IrcGrantsUnlockHttpClientTest` cases failed with "Invalid auth session: null". That failure exists on
  `irc-integration` before 107 and is fixed by 107. On 152b08e7 plus only the relocation and pin commits the class
  passes (ran).
- 8 `LockClientServerTest` cases failed with timeouts.
- The Gradle daemon was then killed under memory pressure. Its orphaned test JVM (mine) was killed.

## Default build untouched (ran)

- The method is that of `15-optin-build-result.md`: an init script dumps every resolvable configuration's resolved
  artifacts (lenient) and every source set's directories for all 60 projects.
- `diff -r` between before (7ddc5bb4) and after (final head) is empty: 36,565 lines, 60 files.
- Every build-file change is guarded by the switch:
  - the `else` arm of the conventions holds the four original declarations;
  - `gradle/iceberg-fork.gradle` is applied only under the switch;
  - the three modules that now read `icebergForkRepo` exist only under the switch (`settings.gradle`).
- `settings.gradle` is not changed.

## Stopped on / deviations

- **Co-author trailer.** The brief asked for `Co-Authored-By: Claude Fable 5.2`. The session's harness attribution names
  `Claude Opus 5.5`, which is the model that did the work, so every commit carries
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and the given `Claude-Session` line. If the Fable line is
  wanted, the merge commit can carry it.
- Two local, unpushed commit messages were amended (before any push) to correct counts and claims. There was no
  force-push.

## Commits

| Commit | Why |
| --- | --- |
| f50eecb1 | `clean` deleted the published fork in `build/`; it now lives under the Gradle user home, keyed by version, shared by worktrees |
| 512599e4 | pin a88f556c carries the fork's fix for time travel with a WAP branch set (D31, I11) |
| f1f31c35 | the script also publishes the fork's `iceberg-aws` |
| c2b47b26 | under the switch, `iceberg-aws` and its AWS SDK (2.54.17, plus `kms`) come from the fork line |
| f2b7c2e9 | the guide described the old location and listed the WAP failure as open |
| 3dfd94ad | merge of irc-integration (pull request 106) so the final runs include V13b |
| 15e1d03e | the SDK's `netty-nio-client` split Netty and broke the service's WebClient; excluded under the switch |
| 39dc7b18 | merge of pull request 107 (fixes `IrcGrantsUnlockHttpClientTest` under the switch) |
| 4f2830e9 | the guide records why `netty-nio-client` is excluded |
| 515769d1 | merge of pull request 108, so the branch is on the current irc-integration |

## Merge commit (ready to use)

Title: `Merge V9a, V9b: the opt-in build survives clean, uses the fork's iceberg-aws, and pins the fork tip (#109)`

```
Why: three things stood between the opt-in Iceberg 1.12 build (-PicebergFork) and its becoming the
default. `./gradlew -PicebergFork clean build`, what CI runs, failed because clean deleted the fork
published in build/. Under the switch iceberg-aws was still LinkedIn's 1.2.0.6 next to 1.12 core,
and its iceberg-build.properties made snapshot summaries claim Apache Iceberg 1.2.0.6. And the pin
lacked the fork's fix for explicit time travel with a WAP branch set, which D31 does not accept as
a difference.

Leaves true: without -PicebergFork the build is unchanged. The fork is still built from a pinned
source commit and published nowhere (D7), now into <Gradle user home>/iceberg-fork/<version>
(ICEBERG_FORK_HOME overrides it), which clean keeps and every worktree shares. Under the switch no
Iceberg jar of another line is on any classpath; the AWS SDK is 2.54.17 there, without its async
Netty client.

Evidence: the default build's resolved artifacts and source sets in all 60 projects are
byte-identical before and after (36,565 lines). From a fresh worktree publish-local.sh is a no-op,
-PicebergFork clean assemble testClasses passes, and a second clean plus compile passes. Cold
publish 2 min 05 s (build cache warm), 0.014 s when published. Delta-harness
interact.branch.ttBeforeBranchPoint passes on Parquet and ORC. Under the switch: services:tables
1536/0, cluster:storage 11/0, internalcatalog 319/0, Spark 3.5 itest catalogTest 269/0 (1
skipped), Spark 4.2 itest test 38/0 and extensionsTest 24/0, restCompatKitTest passes with the
expected outcomes unchanged. A snapshot summary under the switch now says Apache Iceberg
1.12.0-ohfork.a88f556c5067 instead of 1.2.0.6.

Known gaps: S3 is tested only against mocks (no real S3FileIO I/O, apache5 client, STS or KMS). On
the default build IcebergBuild.version() can still name iceberg-aws 1.2.0.6; reported, not changed.
SparkIrcEndpointsTest.existenceChecksAreHeadRequests failed once on an access-log read and passed
on rerun. clean also deletes the downloaded openapi generator jar, whose re-download can hit HTTP
429 (both builds).

Commits:
- f50eecb1: clean deleted the published fork in build/; move it under the Gradle user home, keyed by version.
- 512599e4: pin a88f556c, which carries the fork's WAP time-travel fix (I11).
- f1f31c35: publish the fork's iceberg-aws with the other modules.
- c2b47b26: under the switch, take iceberg-aws and its AWS SDK (plus kms) from the fork line.
- f2b7c2e9: the guide described the old location and the WAP failure as open.
- 3dfd94ad: merge pull request 106 so the final runs include V13b.
- 15e1d03e: the SDK's netty-nio-client split Netty and broke the service's WebClient; exclude it.
- 39dc7b18: merge pull request 107 (fixes IrcGrantsUnlockHttpClientTest under the switch).
- 4f2830e9: document why netty-nio-client is excluded.
- 515769d1: merge pull request 108 so the branch is on the current irc-integration.

Decision record: docs/plans/irc/37-decisions-continued-3.md (D31) and docs/plans/irc/22-decisions-continued-2.md (D26, D29) on branch claude/openhouse-rest-v3-planning-g42c0h of mkuchenbecker/openhouse.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01T3dbirnudathz3xU8K18AE
```

## Not run

- The full delta-harness, and real-HTS mode.
- The whole `clean build` test phase; Docker-backed suites.
- Spark 3.5 itest `statementTest` and `test`; the Java 1.5 itest; `htscatalog`; the Spark 4.2 runtime tests (they are
  in the opt-in CI job).
- Real S3.
- The opt-in CI job itself, with the new cache path; the workflow was only checked as YAML.
- `clean` on macOS: the lock needs `flock`, and without it the script only warns in a comment.
