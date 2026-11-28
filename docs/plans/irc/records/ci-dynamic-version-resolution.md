# CI dependency resolution (ci-dynamic-version-resolution)

Bottom line (in progress): the json-smart failures are a Maven Central round trip for a dynamic
version that every job makes because the restored Gradle cache's lookups are older than Gradle's
24-hour default; the cache was last written by irc-integration's post-merge run of 81b4cd4d
(2026-11-25 ~05:04 UTC), and the failing jobs ran 2026-11-27 22:37 and 23:40 UTC.

Worktree /home/user/oh-ci-deps, branch irc/ci-dynamic-version-resolution, base origin/irc-integration 81b4cd4d.

## Evidence gathered
- Failed jobs (300 most recent runs, 2026-11-22..27, 27 failed jobs searched): exactly three die resolving
  from Maven Central: 140769187919 (json-smart range + mysql 8.+, both "Repository maven is disabled"),
  140788565624 (json-smart range), 138928771502 (three jackson jars, 429).
- Restored entry in both json-smart failures: "Cache hit for restore-key: gradle-home-v1|Linux-X64|iceberg-linkedin-build[db78f2e3...]-81b4cd4d..."
  (the post-merge run of 81b4cd4d, created 2026-11-25T05:04:19Z; no merge since).
- Console logs carry no "Download https" lines (plain console, no --info): download counts need a new measure.
- Writer of the restored entry: post-merge run 45551019677 (81b4cd4d): Reference rest job 138992000868 saved at
  ~05:18 UTC 2026-11-25, Default rest job 138991990128 at ~05:23 UTC. Failing jobs restored it 65-66 h later.
- Readers log a requested key with their own merge SHA (e.g. ...-af124ec6...): that is setup-gradle's primary key,
  not what was restored; "Cache hit for restore-key" names the entry actually restored.
- Local mechanism probe (ran; standalone project, scratch Gradle user home, Gradle 8.14.5, dependency
  com.nimbusds:nimbus-jose-jwt:4.41.1 only; logs in scratchpad/cideps/mech/run*.log):
  - fresh home: --info shows "Downloading .../net/minidev/json-smart/maven-metadata.xml"; [1.3.1,2.3] -> 2.3
  - lookup 8 s old, cacheDynamicVersionsFor 1 hour: 0 maven-metadata lines; 3 seconds: "Cached resource
    .../json-smart/maven-metadata.xml is up-to-date" (a round trip to Central)
  - Central unreachable (proxy port 9): lookup older than duration -> "json-smart:[1.3.1,2.3] FAILED";
    within duration -> "-> 2.3". That is the CI failure and its removal, reproduced.
  - A metadata re-check rewrites only modules-2/metadata-2.107/*.bin, no file under files-2.1/resources-2.1,
    so counting new cache files sees artifact downloads only; metadata round trips need --info.
- Where the range comes from (ran): com.nimbusds:nimbus-jose-jwt:4.41.1's POM declares json-smart <version>[1.3.1,2.3]</version>;
  path: hadoop-client 2.10.0 > hadoop-common > hadoop-auth > nimbus-jose-jwt 4.41.1 (hadoopVersion = '2.10.0' in
  buildSrc/src/main/groovy/openhouse.hadoop-conventions.gradle, same on bb8929e1).
  Resolves today (both lines alike): dummytokens runtimeClasspath [1.3.1,2.3] -> 2.3; htscatalog compileClasspath
  [1.3.1,2.3] -> 2.4.7 (conflict with json-path 2.6.0's 2.4.7). Same on LinkedIn main bb8929e1 (temporary detached
  worktree, removed): -> 2.3 and -> 2.4.7. So the range is LinkedIn's.
- Second dynamic version in the failure: mysql:mysql-connector-java:8.+ (services/optimizer and services/housetables,
  both declared the same on bb8929e1) -> 8.0.33 on both lines and on main.
- Reports: scratchpad/cideps/before/*.txt, scratchpad/cideps/main/*.txt
- All dynamic versions the build resolves (ran: scratch init script probing every resolvable configuration of every
  project, lenient, both lines; scratchpad/cideps/probe-{ref,def}/): json-smart [1.3.1,2.3] (nimbus-jose-jwt POM)
  -> 2.3 / 2.4.7; mysql-connector-java 8.+ (services/optimizer, services/housetables build files) -> 8.0.33;
  log4j-core {strictly [2.17.1, 3[} -> 2.25.3 (2.25.4 in two default-line configurations) and logback-core
  {strictly [1.2.9, 2[} -> 1.2.11, both from com.github.spotbugs:spotbugs-annotations:4.8.1 (the spotbugs
  plugin's, LinkedIn's). No SNAPSHOT or changing modules.
- FOUND AND AVOIDED: cacheDynamicVersionsFor(30, 'days') makes every lookup stale (ran: fresh lookup + 30 days ->
  metadata fetched; Central unreachable -> FAILED). Boundary measured: 24 days works, 25 fails (int milliseconds,
  2^31 ms = 24.8 days; DefaultCachePolicy hands the value to ResolutionControl.cacheFor(int, TimeUnit)). The script
  uses 20 days and says why.
- Committed script retested (20 days): lookup minutes old, network ok -> 0 metadata lines; Central unreachable -> 2.3.
- Commits: 51dd5a25 (the fix), f3048b4b (TEMPORARY --info evidence step, to be removed). Draft pull request #155.
- Resolution identity (ran, 20-day script via -I): 14 dependencies/dependencyInsight reports identical both lines;
  every resolvable configuration of every project (probe) identical: ref 28983 lines, def 34845 lines.
- Waiting for the pull request's run on f3048b4b.
- CI EVIDENCE (pull request run 46086770755, head f3048b4b, job 140798103029 Reference apps-spark-4.2, restored
  ...-81b4cd4d entry): with the init script: 0 maven-metadata requests (json-smart -> 2.3 / 2.4.7, mysql -> 8.0.33,
  log4j -> 2.25.3, logback -> 1.2.11); without it: "Downloading .../mysql-connector-java/maven-metadata.xml" and
  ".../json-smart/maven-metadata.xml". Same resolution both legs. Job downloaded 0 dependency files.
- BEYOND SCOPE (found, not fixed): with the script one request remains per reader job: "Resource missing. [HTTP HEAD:
  .../org.springframework.boot.gradle.plugin-2.7.8.jar]" (the plugin marker of `id 'org.springframework.boot'
  version '2.7.8'` in libs/optimizer/*, LinkedIn's; pluginManagement repos with artifact() metadata source).
  Gradle caches a missing artifact for 24 h (DefaultCachePolicy.cacheMissingArtifactsFor is private: no public
  setting). A refused HEAD there could still kill a job (inferred; not observed).
- Added (pending commit): dataloader-tests.yml integration job, same init.d step, off the default branch only
  (its setup-gradle is read-only there; log of job in run 45551019674 shows cache-read-only: true).
- Run 46086770755 on f3048b4b: all 13 jobs success (Docker skip on reference is by design). Per job (with script /
  without): maven-metadata 0 / 2 in every read-only job; reference rest's no-script leg got "429 Too Many Requests"
  live and failed json-smart and mysql. Downloads beyond cache: Ref rest 0, Ref apps-4.2 0, Ref 3.5-itest 4
  (junit-platform launcher/runner/suite-api/suite-commons 1.11.0), Ref apps-3.5 0, Ref 4.2-itest 0, Def Docker 2
  (opentelemetry-javaagent 2.12.0, parquet-jackson 1.13.1), Def 4.2-itest 0, Def apps-4.2 3 (jackson-jaxrs-base,
  jackson-jaxrs-json-provider, jackson-module-jaxb-annotations 2.21.4 = the 11-25 429 jars), Def 3.5-itest 5, Def
  rest 0, Def apps-3.5 1 (parquet-jackson).
- Hence option (b) added: writer runs ciResolveDependencies (scripts/ci/resolve-all-dependencies.gradle).
- Commits: 51dd5a25 fix, f3048b4b temp, 44e5608f temp removed, b841dbe2 dataloader, 9642fa96 writer warm,
  7cbfdd61 merge of origin/irc-integration 7cf347d7 (#154). Pushed head 7cbfdd61. check-test-groups: partition holds.
  Probe identity re-run on merged head: identical (ref 28988 lines/52 projects, def 34850/57).
- FINAL: head 7cbfdd61beceb7d9c1bfa145a70aa46072da6d30, all split-build jobs and both Dataloader jobs success
  (reference Docker skipped by design; two top-level "skipped" entries are the edited-event runs). Download counts
  same as the evidence run (writer fetch only takes effect after merge). Pull request #155 body complete. Done.

## Review round (pr-review-155.md: MERGE AFTER FIXES)
- Merged bc0ff1e8 (d0e8d505). Review fixes commit 16a21187; temp evidence step ed17c207 (to be removed).
- Gradle 8.14.5 bytecode (read): resolution retries in ErrorHandlingModuleComponentRepository$...Access,
  props org.gradle.internal.repository.max.tentatives (default 3) / .initial.backoff (1000 ms, doubling);
  transient = NetworkingIssueVerifier: SocketException, SocketTimeout, NoHttpResponse, ConnectionClosed, 5xx,
  408, 429. org.gradle.internal.network.retry.* is used only by publishers. Measured: user-home gradle.properties
  systemProp gives 4 waits 500/1000/2000/4000 ms.
- Spring Boot plugin requests mapped to spring-boot-gradle-plugin (marker POM's one dependency); buildEnvironment
  same module set on both lines, marker node gone.
- log4j/logback: settled by fixed versions in the graph (insight: By constraint / conflict resolution); alone the
  ranges resolve to 2.26.1 / 1.6.8 (scratch project, 2026-11-28). Lists decide only json-smart and mysql.
- Warm task: 0 failures after leaving out incrementalScalaAnalysisFor* (45 plain-resolution variant failures there).
- Dispatched post-merge-build.yml on the branch at ed17c207.
- CORRECTION of my earlier entry: the marker HEAD 429 was observed in BOTH legs of job 140798103014 (three 429s),
  and in the no-script leg json-smart and mysql FAILED with no request because Central was already disabled.
- Dispatch run 46094839702 (head ed17c207, branch had no cache): Reference writer fetch 4 s, 52 projects, 29570
  files, 0 failures; saved deps 656840800 B, home 93394474 B, transforms 25538315 B. Default line: fork publish
  failed (429 via plugin portal redirect, ~134 s, consistent with 7 tries), build skipped, fetch BUILD FAILED.
  Cold reader 140823748421: marker module never requested; spring-boot-gradle-plugin downloaded directly.
- PR run 46094836051 (ed17c207): restored entry 0 days old; readers: 0 repository lines.
- 5f6faa27: temp step removed, fetch gated on the build; 14adc5c1: merge of 68e2557d (#152). PR body rewritten.
- FINAL (round 2): head 14adc5c11d518d10f7b5bb501b6aca8dfd0ea4f6. Every job success (reference Docker skipped
  by design; two top-level skipped = edited-event). Restored cache 0 days old in every job; no 429 in any log;
  downloads: Ref 3.5-itest 4, Def 3.5-itest 5, Def apps-3.5 1, Def Docker 2, Def apps-4.2 3, others 0 (unchanged,
  as expected before the writer's fetch runs on irc-integration). Dispatch run 46094839702 ended with default
  rest and 3.5-itest failed (cold cache, fork publish 429), everything else success.

## Follow-up: default line still downloading after #155 (worktree /home/user/oh-ci-deps2, branch irc/ci-warm-up-default-line)
- PR 157 (63c65e76) default jobs: same 11 files as before. Cause (evidence): default writer job 140839247995
  (post-merge 4d9362ee) fetch stalled 02:50:04-02:52:11 then 52 "could not resolve: ... Repository maven is
  disabled" lines incl. all 11 files; entry saved 02:52:30 without them. Next writer 140852687277 (0d54d2fd):
  fetch 5 s, 0 failures, 29 downloads incl. all 11, saved 04:04:30. Refused request's status unknown (not printed).
- Fix b5ec038d: innermost cause printed; failure count file; second fetch after 60 s; warning annotation. Draft PR opened.
- PR 159 run on b5ec038d: restored 0d54d2fd entry (04:04 UTC); every job of both lines downloaded 0 files, no
  429; all jobs success (reference Docker skipped by design). Success measure met by the warmed entry; the
  second-try path is exercised only when a writer's first fetch is refused.
