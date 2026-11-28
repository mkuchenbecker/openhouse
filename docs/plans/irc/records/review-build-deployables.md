# Review: build and deployables (slug `build-deployables`)

Reviewer scope: `settings.gradle`, root `build.gradle`, `gradle/`, `buildSrc/`, the build files of every module,
`scripts/iceberg-fork/`, Dockerfiles, `infra/recipes/`, `docs/development/`, and the CI files these pull requests
touched. Pull requests 87, 91, 97, 98, 105, 109, 110, 113, 114, 118, 122, 123, 136. Read-only; no Gradle was run, so
every claim about resolved versions comes from reading declarations, and says so where that is not enough.

## 1. Bottom line

The area is not sound for upstream as merged. The mechanics mostly work: the fork pin, the exclusive repository and the
publish guard. But three claims that the effort rests on are false or only partly true.

1. **LinkedIn's release pipeline is broken, on both lines.** `build-tag-publish.yml` is unchanged
   (`./gradlew publish -Pversion=...`, line 51). Its job never publishes the fork, so on the default line
   `gradle/iceberg-fork.gradle:23-29` throws at configuration time. That happens after the tag was pushed (line 33).
   If the fork were present, the guard at `gradle/iceberg-fork.gradle:72-85` would refuse instead. Passing
   `-PicebergLinkedIn` would publish, but then the Docker gradle tasks refuse, and the docs call that line "not
   deployed" (`docs/development/iceberg-fork.md:117-118`, `build.gradle:229-238`). So no line can both publish Maven
   artifacts and ship images. The gap is documented (#118 known gaps, `74-work-table-audit.md` C13), but it is not
   fixed. It blocks upstream.
2. **The reference line (`-PicebergLinkedIn`) is not LinkedIn main's build.** Shared changes leak into it:
   - `libs:datalayout`, a published artifact, moves from Spark 3.1.1 / Iceberg 1.2.0.25 /
     `iceberg-spark-runtime-3.1` to Spark 3.5.2 / 1.5.2.24 / `iceberg-spark-runtime-3.5`, and loses the root forces
     (Jackson 2.13.4, Guava 33.5.0, ORC 1.8.3, Avro 1.11.4).
   - The Spark 3.5 apps uber jar gains `log4j2.properties`. That changes the logging of every maintenance job run from
     that jar.
   - The Spark 3.5 runtime uber jar packs a new module's classes.
   - The released jobs-scheduler image runs a different jar.
   - Java client and runtime code changed under the same source roots (other reviewers' area).

   The merge claims "Jar entry lists equal" and "No product code changes" (#118) do not cover bytecode compiled
   against a different Spark, or POM contents.
3. **The default build cannot be built from a clean checkout with Gradle alone.**
   - Every Gradle command, `help`, IDE sync and the spotless git hook included, fails until
     `scripts/iceberg-fork/publish-local.sh` has run.
   - That script clones a personal GitHub fork and runs its `gradlew`, which downloads `gradle-wrapper.jar` with an
     unverified `curl`. It needs JDK 17+ and minutes of build time.
   - The fork is published under LinkedIn's own group `com.linkedin.iceberg` with a non-release version.
   - The failure message is clear. The dependency on a step outside Gradle is the cost.
4. **The default build changes every artifact's bytecode target from Java 8 to Java 17**
   (`buildSrc/.../openhouse.java-minimal-conventions.gradle:7-12`). This includes the client jars that Spark users
   load. It is a consequence of the fork being Java 17. For any LinkedIn consumer still on a Java 8 or 11 JVM it is a
   one-way change, and it is recorded nowhere as a behavior change.
5. **The two-line mechanism is spread through about 15 build files.**
   - The property is checked 40+ times.
   - Three per-line source roots, four `test-iceberg-*`/`iceberg-1.x` duplicates, redundant `@ConditionalOnClass` on
     code that only compiles on one line, and reflection in the Java client.
   - A deprecated no-op property (`-PicebergFork`) that upstream never had.
   - Module, variable and plugin names that say "1.5" for what is built on 1.12.
   - A single-line build would delete most of this.

The `src/shared` move (#110) is a pure move: 193 renames, 100% similarity, no content change. It is fine.

## 2. Defects

### D-1 BLOCKER: the release workflow pushes a tag and then cannot publish or build images
- What goes wrong: a push to `main` on `linkedin/openhouse` runs `build-tag-publish.yml`.
  - `build-and-run-tests` passes, because it uses the fork action.
  - Then `tag-publish-gradle` bumps and pushes a tag (line 33). Then `./gradlew publish -Pversion=...` runs (line 51)
    on a runner where the fork was never published. `gradle/iceberg-fork.gradle:23-29` throws:
    "The build uses Iceberg com.linkedin.iceberg:1.12.0-ohfork.a88f556c506e ... Run
    scripts/iceberg-fork/publish-local.sh first".
  - With the fork present, `gradle/iceberg-fork.gradle:72-85` refuses every non-`file:` publish.
  - The image steps that follow (lines 63-87) `docker build` from `build/<service>/libs`, which nothing built.
- Result: an orphan tag, no Maven artifacts, no images.
- Certain from reading. The author knew (#118: "would push its tag before its publish step stops at the guard"), and
  the docs say "there it would now stop at this check" (`docs/development/iceberg-fork.md:101-102`). It actually
  stops earlier, at the missing-fork check.
- Smallest fix:
  - For upstream: until the fork's artifacts are hosted (I6d), `build-tag-publish.yml` must run on the reference line
    (`-PicebergLinkedIn`), and the Docker refusal must not apply to the service images there.
  - Or: the default line must resolve a hosted Iceberg, and then the publish guard goes.
  - Either way the tag step must come after a successful publish.
  - This is an owner decision (C13). The code fix is SMALL.

### D-2 SHOULD FIX: the reference line is not LinkedIn main's `libs:datalayout`
- `libs/datalayout/build.gradle:9-17, 32-48`. At HEAD:
  ```
  icebergVersion = rootProject.ext.iceberg_1_5_version
  sparkVersion = '3.5.2'
  ...
  implementation "com.linkedin.iceberg:iceberg-spark-runtime-${sparkVersionSuffix}_2.12:" + icebergVersion
  ```
- At `bb8929e1`: `icebergVersion = rootProject.ext.iceberg_1_2_version` (1.2.0.25), `sparkVersion = '3.1.1'`,
  `iceberg-spark-runtime-3.1_2.12`, compileOnly the Spark 3.1 runtime, test fixtures `tables-test-fixtures_2.12`.
- Also `build.gradle:90-96` puts `':libs:datalayout'` in `excludedProjects` without a condition. #105 had it
  "under -PicebergFork only"; 98328ea2 in #118 made it apply to both lines.
- Effects:
  - The published `com.linkedin.openhouse:datalayout` POM and bytecode differ on the reference line.
  - Its test classpath no longer gets Jackson 2.13.4 / Guava 33.5.0 / ORC 1.8.3 / Avro 1.11.4.
  - A consumer of `datalayout` on Spark 3.1 now gets Spark 3.5 and Iceberg 1.5.2 transitively.
- Certain from reading. The change follows from deleting the Spark 3.1 runtime it compiled against (accepted, S44).
  The defect is the claim that the reference line is unaffected: `docs/development/iceberg-fork.md:7-11` and the
  #118 message do not list it.
- Fix: record it as a reference-line change. Or, if `datalayout` must stay byte-identical for LinkedIn, keep it on
  Spark 3.1 against Maven Central's `iceberg-spark-runtime-3.1` on the reference line. That is SMALL, but it probably
  is not wanted.

### D-3 SHOULD FIX: `log4j2.properties` in the Spark 3.5 apps uber jar, on both lines
- `apps/spark-3.5/build.gradle:73-77`:
  ```
  // log4j2.properties: without it the scheduler (JobsScheduler, started from the uber jar in
  // the jobs-scheduler image) logs errors only.
  resources {
      srcDirs += 'src/shared/main/resources'
  }
  ```
- At `bb8929e1` the apps 1.5 project took only the old project's `java` srcDirs, not its resources, so its jar had
  no `log4j2.properties`. The file (`rootLogger.level = info`, console appender, its own pattern) is now in
  `openhouse-spark-apps-1.5_2.12-uber.jar`. That jar is what LinkedIn's Spark maintenance jobs run from, through the
  jobs service and Livy, not only the scheduler.
- When Spark has no explicit `-Dlog4j.configurationFile`, Log4j2 takes the first `log4j2.properties` on the
  classpath. With the app jar on the driver or executor classpath, that can now be this one, which changes log level
  and format in production jobs.
- Likely; whether a given cluster's Spark finds it first depends on its configuration. Cannot tell without running.
- #118 lists it ("log4j2.properties now in the Spark 3.5 apps jars on both lines") but calls it no behavior change.
- Fix (SMALL): keep the file out of the apps jar and give the scheduler its configuration where it is started. Use
  `-Dlog4j2.configurationFile` in `jobs-scheduler.Dockerfile`, or give the scheduler its own resource set.

### D-4 SHOULD FIX: the fork build runs an unverified download, under LinkedIn's group id
- `scripts/iceberg-fork/publish-local.sh:69-74, 89-93` runs the fork's `./gradlew`.
  - The fork tree has no `gradle/wrapper/gradle-wrapper.jar` (`git ls-tree origin/openhouse-1.12.0 gradle/wrapper/`:
    only the properties file).
  - Its `gradlew:89-90` does `curl -o $APP_HOME/gradle/wrapper/gradle-wrapper.jar
    https://raw.githubusercontent.com/gradle/gradle/v8.14.5/gradle/wrapper/gradle-wrapper.jar`. There is no checksum,
    and the jar is then executed.
  - The distribution itself is pinned by `distributionSha256Sum`.
  - This runs on every developer machine and on every CI cache miss.
- Certain from reading.
- The source is a personal repository (`repoUrl=https://github.com/mkuchenbecker/iceberg.git`), pinned by full
  commit and checked after checkout (`publish-local.sh:72`, `test "$(git rev-parse HEAD)" = "$commit"`). That part
  is fine.
- The output is published as `com.linkedin.iceberg:*:1.12.0-ohfork.<sha12>`, LinkedIn's own coordinates for an
  artifact LinkedIn did not build. `exclusiveContent` (`gradle/iceberg-fork.gradle:47-61`) keeps the version from
  coming from anywhere else, which is good. But every POM OpenHouse writes on this line names a LinkedIn coordinate
  that no LinkedIn repository holds.
- The CI cache of the published fork (`.github/actions/iceberg-fork/action.yml`) is restored and used without any
  check against the pin beyond the key.
- Fix:
  - Verify the wrapper jar's SHA-256 in `publish-local.sh` before running it, or run the fork with a Gradle the
    OpenHouse wrapper already verified.
  - Use a group id that is not LinkedIn's until LinkedIn hosts it, or have LinkedIn host it (I6d).

### D-5 SHOULD FIX: every Gradle invocation on the default line needs the out-of-Gradle step
- `gradle/iceberg-fork.gradle:23-29` throws during root project configuration. `./gradlew help`, `tasks`, IDE
  import, `spotlessCheck` in the git hooks, and dependency or plugin queries all fail on a fresh clone. The docs say
  so (`docs/development/iceberg-fork.md:38-39`).
- The message is clear and names the script and the alternative.
- What the developer then needs: `git`; network access to `github.com/mkuchenbecker/iceberg` and Maven Central; JDK
  17 or 21 (the script does not check which JDK; on JDK 8/11 the failure comes from inside Iceberg's build, not from
  OpenHouse); about 4 GB heap (`-Dorg.gradle.jvmargs=-Xmx4g`); minutes of build time.
- A CI runner that does not use `.github/actions/iceberg-fork` fails the same way. `build-tag-publish.yml` is one
  (D-1).
- Certain from reading.
- Fix: until the artifacts are hosted, either fail only the tasks that need Iceberg (resolve lazily), or have the
  script check `java -version` first. For upstream, hosting is the real fix (I6d).

### D-6 MINOR: `-PicebergLinkedIn=false` selects the reference line
- Every check is presence-based. `settings.gradle:31` uses `providers.gradleProperty('icebergLinkedIn').present`;
  `build.gradle:43`, `buildSrc/.../openhouse.java-minimal-conventions.gradle:10`, `openhouse.iceberg-aws-conventions.gradle:5`,
  `openhouse.iceberg-conventions-1.5.2.gradle:10`, `apps/spark-3.5/build.gradle:22,83,96` and the others use
  `hasProperty('icebergLinkedIn')`.
- So `-PicebergLinkedIn=false`, or `icebergLinkedIn=false` in a `gradle.properties`, builds the reference line.
  The same applies to the deprecated `-PicebergFork`.
- Certain. Fix: read the value once in `settings.gradle`, expose a boolean, and use it everywhere.

### D-7 MINOR: the tables service on the default line likely mixes Jackson minors
- The root forces still pin `jackson-bom` and `jackson-databind` to 2.13.4 for every project except the three in
  `excludedProjects` (`build.gradle:100-103`).
- The fork's Iceberg 1.12 asks for `jackson-core`/`jackson-annotations` 2.22. The v9d record (`reports/v9d-deployables.md`
  §1) found `jackson-core:2.22.2` on the fixtures' classpath, which is under the forces. So the tables service boot
  jar likely ships `jackson-databind` 2.13.4 with `jackson-core` 2.22.x.
- Tests pass, so the common paths work. Jackson only promises compatibility between the same minor version.
- Cannot tell without running: `./gradlew :services:tables:dependencyInsight --dependency jackson-core --configuration runtimeClasspath`.
  Pin `jackson-core` with the BOM, or raise the force, deliberately.

### D-8 MINOR: the Spark 3.5 runtime's thin jar refers to classes its POM does not declare
- `integrations/spark/spark-3.5/openhouse-spark-runtime/build.gradle:58-60, 85-87`: the capabilities module is
  `compileOnly` and packed into the uber jar. The thin `jar` is also published (`openhouse.maven-publish.gradle`,
  `artifact jar`). It now references `com.linkedin.openhouse.capabilities.*` interfaces at run time
  (`spark/capabilities/*.java`, `ResolveUnlockTable.scala`, `Set*Exec.scala`, `ShowGrantsStatementExec.scala`).
- The comment claims "the thin jar's consumers and the published POM do not change". The POM does not; what the thin
  jar needs at run time does.
- This matches the existing pattern for the Java client, which was also uber-only, so it is pre-existing in kind.
- Likely. Fix: publish the capabilities module and declare it, or say that only the uber jar is supported.

### D-9 MINOR: the Spark image cannot be built from the reference line
- `infra/recipes/docker-compose/common/spark/spark-3.5-base-hadoop3.2.dockerfile` (`COPY /build/iceberg-spark-runtime/ /opt/spark/jars/`)
  needs `stageIcebergSparkRuntime`, which exists only on the default line (`build.gradle:244-262`).
- #113's own known gap: "A Docker builder without BuildKit would fail the default Spark 3.5 image for want of
  build/iceberg-spark-runtime/".
- No LinkedIn recipe at `bb8929e1` used this dockerfile (only a comment in `build.gradle` named it), so nothing
  LinkedIn ran breaks.
- Certain from reading.

### D-10 MINOR: documentation inaccuracies
- `docs/development/iceberg-fork.md:101-102`: the release workflow "would now stop at this check". It stops earlier,
  at the missing fork (D-1).
- `docs/development/iceberg-fork.md:266`: "`build-tag-publish.yml` calls the same workflow before a release" is true
  for the test job only. The publish job does not restore the fork.
- `docs/development/iceberg-fork.md:117-118` says the reference build "is not deployed". It is the only line that can
  publish to a remote repository (`:99`). The two statements contradict each other for a release engineer.
- `README.md:94-101` still says Spring Boot 2.6.6 and Livy 0.7.0-incubating. This predates the effort but is now next
  to newly edited rows. The build uses Spring Boot 2.7.8, and the image builds Livy 0.8.0-incubating-SNAPSHOT.
- `SETUP.md:328`: the ADLS `spark-shell` still uses `--packages org.apache.iceberg:iceberg-azure:1.5.0` next to the
  image's Iceberg 1.12 runtime. It mixed lines before too (1.5.0 with 1.2.0), now with 1.12. It has not been run.
- `scripts/iceberg-fork/publish-local.sh:4` still says "for the opt-in -PicebergFork build". It is kept because its
  hash is the CI cache key (#118 known gaps). A cache key on a script's bytes makes a comment fix cost a 6-minute
  rebuild.

## 3. Behavior changes against LinkedIn main

"Default" means a plain `./gradlew build` and the images built from it. "Ref" means `-PicebergLinkedIn`.
Owner justification where it applies:
- S35 "The openhouse build depends on the mkuchenbecker Iceberg fork for the new Iceberg line."
- S36 "The clean Iceberg base is Apache 1.12."
- S44 "i don't care about spark 1.2." The notes read this as the Iceberg 1.2 / Spark 3.1 line (D3: "This is Claude's
  reading of S44").
- S22 "Spark 3.5 is fine for now. The target to be looking at is 4.2+."

The flip itself is D38: "The orchestrator's decision, a two-way door."

| # | What changed (before → after, file:line) | PR | On by default | Justification given | Documented for follow-up | Upstream accepts as is? | Size |
|---|---|---|---|---|---|---|---|
| B1 | Iceberg of every Iceberg-1.5-variant module and of the services: LinkedIn 1.5.2.24 / 1.2.0.25 → fork `1.12.0-ohfork.a88f556c506e` built locally (`gradle/iceberg-fork.gradle:15-33`; `build.gradle:43-46`; main `build.gradle:32-34`) | 87, 109, 118 | yes | S35, S36; D38 | I6d (hosting) | No, not until the artifacts are hosted and the Iceberg line is LinkedIn's decision | FOUNDATION: all v3/IRC work assumes 1.12 |
| B2 | Modules and artifacts deleted: `openhouse-spark-runtime_2.12` (+uber), `openhouse-spark-apps_2.12` (+uber), `tables-test-fixtures_2.12`, `openhouse-java-runtime` and `-itest` of iceberg-1.2, `openhouse-spark-itest` of 3.1, plugin `openhouse.iceberg-conventions-1.2`, `spark-base-hadoop2.8.dockerfile` (`settings.gradle` main:27-47,60-63 → HEAD; #118 deletions) | 118 | yes | S44, D3 | #118 known gaps list the artifacts | Only if LinkedIn has retired Spark 3.1 | FOUNDATION (the 1.2 line cannot build the Java 17 service, D3) |
| B3 | Bytecode target Java 8 → 17 for every module (`openhouse.java-minimal-conventions.gradle` main:7-8 → HEAD:7-12; capabilities build.gradle:15-17); ref keeps 8 | 87, 118 | yes | Comment: "fork's Iceberg 1.12 artifacts ... are Java 17 bytecode". No S-item | No | No: needs an explicit statement that Java 8/11 consumers of the client and Spark runtime jars are dropped | FOUNDATION (tied to B1) |
| B4 | A plain build needs `publish-local.sh` first; every Gradle command fails without it (`gradle/iceberg-fork.gradle:23-29`) | 87, 109, 118 | yes | D7 (proxy refuses JitPack/GitHub Packages here) | I6d | No | SMALL once hosted |
| B5 | Remote Maven publishing refused on the default line (`gradle/iceberg-fork.gradle:72-85`); `build-tag-publish.yml` unchanged and now fails after tagging (D-1) | 118 | yes | D7; #118 | C13, "WAITS ON MIKE" | No | SMALL code, owner decision |
| B6 | `dockerPrereqs`/`dockerBuild`/`dockerUp` throw on the ref line (`build.gradle:229-238, 266, 316, 345`); on main they built both Spark lines' jars | 113, 118 | n/a (ref only) | "the reference build ... is not deployed" | docs | No: LinkedIn's deployable line would be the ref line until B5 is solved | SMALL |
| B7 | `dockerPrereqs` builds the Spark 3.5 runtime and apps jars plus `stageIcebergSparkRuntime`, no longer the Spark 3.1 jars (`build.gradle:264-288` vs main 199-226) | 113, 118 | yes | S44 | docs | With B2 | SMALL |
| B8 | Released jobs-scheduler image runs `openhouse-spark-apps-1.5_2.12` (Spark 3.5 apps, built on 1.12, Java 17) instead of `openhouse-spark-apps_2.12` (`jobs-scheduler.Dockerfile:6-7`, main:6) | 113, 118 | yes | S44 (Spark 3.1 jar gone) | docs `iceberg-fork.md:123-125` | Probably yes with B2, but it is a released image | SMALL |
| B9 | `log4j2.properties` added to the Spark 3.5 apps uber jar, both lines (`apps/spark-3.5/build.gradle:73-77`) (D-3) | 113, 118 | yes, also ref | Scheduler logging | No | No | SMALL |
| B10 | `libs:datalayout` on both lines: Spark 3.1.1 → 3.5.2, Iceberg 1.2.0.25 → line version, `iceberg-spark-runtime-3.1` → `-3.5`, fixtures 1.2 → 1.5, root forces removed (`libs/datalayout/build.gradle:9-28,48`; `build.gradle:90-96`) (D-2) | 91, 105, 118 | yes, also ref | S44 by consequence | No (not listed as a ref-line change) | Yes if Spark 3.1 is retired; must be declared | SMALL |
| B11 | Spark 3.5 runtime uber jar packs `com.linkedin.openhouse.capabilities.*` (unrelocated), both lines (`integrations/spark/spark-3.5/openhouse-spark-runtime/build.gradle:58-60,85-87`) | capabilities PRs (outside this list); packaging is build | yes, also ref | D23 (capabilities, S16) | No | Plausibly yes; needs relocation or a published module | SMALL to repackage |
| B12 | Default line S3 stack: `iceberg-aws` 1.2.0.6 → fork; AWS SDK 2.20.18 → 2.54.17; `kms` added; `netty-nio-client` excluded; default HTTP client becomes apache5 (`openhouse.iceberg-aws-conventions.gradle:5-23`); reaches published POMs of `services:tables`, `internalcatalog`, `cluster:storage` | 109 | yes | "no Iceberg jar of another line on any classpath" | Not run against real S3 (#118 "Not run: real S3") | Only with a real S3 run | SMALL to pin back the SDK, not with the fork's iceberg-aws |
| B13 | Default line forces: ORC 1.8.3 → 1.9.9, Avro 1.11.4 → 1.12.2, Caffeine pinned 3.2.4 (internalcatalog compiled 2.x) (`build.gradle:103-111`; `iceberg-fork.properties:17-19`) | 87 | yes | Fork needs them | docs | Yes with B1 | with B1 |
| B14 | `iceberg-parquet`/`iceberg-orc` added `runtimeOnly` to every user of `openhouse.iceberg-conventions-1.5.2` on the default line; excluded again in apps, itest, datalayout tests (`openhouse.iceberg-conventions-1.5.2.gradle:10-15`) | 87 | yes | Iceberg 1.12 registers readers together | docs | Yes with B1 | with B1 |
| B15 | Local recipes: Spark 3.1/Hadoop 2.8 image → Spark 3.5/Hadoop 3.2 image with Java 17 grafted onto `bde2020/hadoop-namenode:2.0.0-hadoop3.2.1-java8`, `JAVA_HOME` changed for the whole image, the fork's runtime in `/opt/spark/jars`, `LIVY_SERVER_JAVA_OPTS` added (`common/spark-services.yml:6-50`; the dockerfile) | 113, 118 | yes | S44, S22 | docs | Yes with B2, if LinkedIn uses these recipes | SMALL |
| B16 | Spark image builder default `OH_SPARK_BUILDER=dockerhub`: Spark from `apache/spark:3.5.2-...` (no `conf`, no licenses, no YARN shuffle jar), Livy built in `maven:3.9.4-eclipse-temurin-11` without `integration-test`/`coverage`; `-Ddownload.plugin.skip=true`; Livy pom fix (`spark-3.5-base-hadoop3.2.dockerfile:16, 60-98`). #114 said "default: today's builder stage, byte for byte", but HEAD defaults to dockerhub | 113, 114, 118, 122 | yes | CI runners could not download from archive.apache.org | docs | Yes; the unused `default` stage should go | SMALL |
| B17 | `spark.jars.packages` / `--packages` with Apache `iceberg-spark-runtime-3.1_2.12:1.2.0` removed from `jobs.yaml:12`, `livy_cli.py:25`, `SETUP.md`, the Azure `spark-client.sh:13`. Recipe jobs ran Apache 1.2.0 and now run the fork's 1.12 | 113, 118 | yes | Image carries the runtime | docs | Yes with B15 | SMALL |
| B18 | Spark 3.5 itest: the exclude pattern (`PartitionTest`, `AlterTable*`, `CreateTable*`, `Describe/Drop/ShowTables`, `InsertIntoTable`) removed, so Spark 3.5's own `*Spark3_5` tests in those families now compile and run; `iceberg-data` excluded from `testRuntimeClasspath` on both lines (`openhouse-spark-itest/build.gradle:20-40`) | 110, 118 | yes (tests only) | #110 known gap | — | Yes (more coverage) | — |
| B19 | `services:tables` tests use the 1.5 fixtures (`services/tables/build.gradle:143`, main `tables-test-fixtures_2.12`) | 91, 118 | yes, also ref (tests only) | B2 | — | Yes | — |
| B20 | New modules in both builds: `openhouse-catalog-capabilities` (not published, packed, B11). Default only: `spark-4.2` runtime and itest, `apps:spark-4.2`, `openhouse-rest-compat-kit` (none published; none apply `openhouse.maven-publish`) (`settings.gradle:52-64`) | 87+ | new | S23 | — | New modules are not behavior changes; `build` now runs their suites | — |
| B21 | Source encoding forced to UTF-8 for `JavaCompile`/`Javadoc` on both lines (`build.gradle:125-131`). Changes output only on a non-UTF-8 locale with non-ASCII sources | 136 | yes | Reproducibility | — | Yes | — |
| B22 | OpenAPI generator CLI resolved as a Gradle dependency from the build's repositories instead of `curl` from Maven Central (`client/common/build.gradle:13-29`; `jar_download.sh` deleted). Same version 5.3.0; generated code unchanged per #123 | 123 | yes, also ref | Cache across clean | — | Yes | — |
| B23 | Shadow excludes `META-INF/versions/21/` in apps and fixtures uber jars, both lines (`openhouse.apps-spark-common.gradle:85-87`; `openhouse.tables-test-fixtures-common.gradle:196-200`). No-op on ref per #113's scan ("[ran] ... no input of a default-build shadowJar has a META-INF/versions/21 entry") | 113 | yes | Shadow 7.1.2 limit | — | Yes | — |
| B24 | CI: `pr-validations.yml` runs `split-build.yml` twice (default, reference) instead of `build-run-tests.yml`; `build-run-tests.yml` needs the fork action and runs the Docker steps; `dataloader-tests.yml` triggers and steps changed; `post-merge-build.yml`, `split-build.yml` and two composite actions added, keyed to the branch `irc-integration` | 97, 114, 118, 122 | yes | D33 | docs CI section | No as is: branch names of this effort (`irc-integration`) in upstream workflows | SMALL |
| B25 | Renovate: the 1.2 line rule removed; it now manages only the ref line's version (`.github/renovate.json`) | 118 | yes | B2 | docs | Yes with B2 | — |
| B26 | `services:tables` compiles `src/iceberg-1.12` (14 files, 4,096 lines) on the default line only; on ref those routes answer 406 (`services/tables/build.gradle:104-121`) | 98 and later | default only | D44, D45 | — | Behavior is other reviewers' area; the build mechanism is fine | — |

Files at `bb8929e1` that changed, accounted for (from `git diff --diff-filter=MDR --stat` over my paths):
- `settings.gradle`: B2, B20; `-PicebergFork` no-op in cruft.
- Root `build.gradle`: B1, B5-B7, B10, B13, B21.
- `buildSrc`, six files: B3, B12, B14, B23; 1.2 plugin deleted (B2).
- `apps/spark-3.5/build.gradle`: B9; source move; per-line test roots.
- `apps/spark/build.gradle`: deleted, B2.
- `libs/datalayout/build.gradle`: B10.
- `client/common/build.gradle`, `codegen.build.gradle`, `jar_download.sh`: B22. The `codegen.build.gradle` change is a comment only.
- `integrations/java/iceberg-1.5/*/build.gradle`: source roots renamed, a pure move checked by #110 `-M`. The capabilities module is `compileOnly` and javac inlines the constants. That holds for `services:tables` (`CapabilityNames` constants), but not for the Spark runtime (D-8).
- `integrations/spark/spark-3.5/*/build.gradle`: B11, B18, plus new system properties for tests.
- `services/tables/build.gradle`: B19, B26.
- `tables-test-fixtures-iceberg-1.5/build.gradle`: source move.
- The 1.2 / 3.1 build files and their sources: B2.
- `jobs-scheduler.Dockerfile`: B8.
- The Spark dockerfiles and `spark-services.yml`: B15, B16. `jobs.yaml`, `spark-client.sh`, `livy_cli.py`: B17.
- `README.md`, `SETUP.md`, `docs/development/{intellij-setup,renovate-iceberg-sync}.md`: documentation, see D-10.
- `.github/renovate.json`, `renovate.yml`: B25. The other workflows: B24.

Plugin and wrapper versions are unchanged (`gradle/wrapper`, the `buildscript` classpath, shadow 7.1.2): checked by diff.

## 4. Cruft

### The two-line mechanism (a cost of keeping both lines)
- The property is checked in about 15 files and about 40 places: `settings.gradle` 12, `build.gradle` 11, `apps/spark-3.5/build.gradle` 3,
  the capabilities module 4, three `buildSrc` plugins, `libs/datalayout`, `services/tables`, the 3.5 itest, the
  delta-harness script and the CI files. No single place owns "which line"; each reads `hasProperty` itself (D-6).
  Do this instead: one boolean, set in `settings.gradle` (or a version catalog per line) and read everywhere.
- Per-line source roots:
  - `services/tables/src/iceberg-1.12` (main and test).
  - `integrations/java/openhouse-catalog-capabilities/src/iceberg-1.5` and `src/iceberg-1.12`: two copies of
    `RestTransport` (87 and 86 lines).
  - `apps/spark-3.5/src/test-iceberg-1.5` and `src/test-iceberg-1.12`: same-named helper classes, 125 and 342 lines.

  A single-line build deletes the 1.5 copies and folds the 1.12 roots into `src/main`/`src/test`.
- Redundant guards: `@ConditionalOnClass` on `IcebergRestScanController` (`services/tables/src/iceberg-1.12/.../IcebergRestScanController.java:58`)
  and `IcebergRestRegisterViewController` (`:31`). Both live in a source root that only compiles on the line where
  the class exists, so the condition guards nothing. Delete it now.
- Reflection to straddle the lines:
  - `OpenHouseRestAdapter.V3Endpoints` (`integrations/java/iceberg-1.5/openhouse-java-runtime/src/main/java/.../OpenHouseRestAdapter.java:373-395`,
    `Class.forName(... ".Endpoint")` and `getMethod("withEndpoints")`).
  - `TablesClientFactory` (`apps/spark-3.5/src/shared/main/java/.../TablesClientFactory.java:93`, `Class.forName(icebergLineClass)`).
  - `IcebergLine` still has an arm for Iceberg 1.2 (`IcebergLineTest.java:52`, `minor == 2 || minor == 5`).

  All of these are needed only while the ref line exists, and the 1.2 arm not at all.
- Test-line plumbing: system properties `openhouse.iceberg.line` (`apps/spark-3.5/build.gradle:95-98`; `apps/spark-4.2/build.gradle:166`)
  and `smoke.iceberg.build` (`openhouse-spark-itest/build.gradle:98-99`), with tests asserting the build set them.
  This exists only for the two-line build.
- The docker refusal (`build.gradle:229-238`, `refuseOnReferenceLine`) and the publish guard (`gradle/iceberg-fork.gradle:63-86`)
  each exist only because the two lines differ in what can ship.

### Leftovers of the opt-in period
- `-PicebergFork`: accepted, prints a warning, does nothing (`settings.gradle:31-43`). `HARNESS_ICEBERG_FORK=1`
  likewise (`integrations/spark/delta-harness/HARNESS-GUIDE.md:65`). Upstream never had either. Delete both.
- `publish-local.sh:4` "for the opt-in -PicebergFork build" (see D-10).
- `gradle/iceberg-fork.gradle` is named for the opt-in fork switch but is the default build's Iceberg definition.
- "until that project was deleted ... can be folded into src/main" appears in five build files:
  `apps/spark-3.5/build.gradle:65-67`, `openhouse-java-runtime/build.gradle:28-37`,
  `openhouse-java-itest/build.gradle:10-11`, `openhouse-spark-itest/build.gradle:10-18`,
  `tables-test-fixtures-iceberg-1.5/build.gradle:13-15`. Each `src/shared` and `src/iceberg-version-specific` is now
  a second source root of a single project. Fold them into `src/main` and `src/test` with `git mv`.
- `spark-3.5-base-hadoop3.2.dockerfile`: the `default` builder stage (lines 18-65), which the docs say "has never
  finished" on a runner and nothing selects. Two builders for one image. Keep one.

### Naming that follows history
- `iceberg_1_5_version` (`build.gradle:32`, `gradle/iceberg-fork.gradle:32`) holds `1.12.0-ohfork...` on the
  default line.
- These names say 1.5 and are built on 1.12: the plugin `openhouse.iceberg-conventions-1.5.2`, the projects
  `iceberg-1.5/openhouse-java-iceberg-1.5-runtime`, `tables-test-fixtures-iceberg-1.5_2.12` and
  `openhouse-spark-apps-1.5_2.12`, and "the Iceberg 1.5 variant" in docs and comments. #118 lists "the misleading 1.5
  names" as follow-up.
- `icebergAwsVersion = '1.2.0.6'` (`openhouse.iceberg-aws-conventions.gradle:2`) is the ref line's
  `iceberg-aws`, a 1.2 artifact on the 1.5.2 line. That predates the effort and is now one of two branches.

### Duplicated build logic
- Parsing the pin file: `gradle/iceberg-fork.gradle:12-13`, `apps/spark-4.2/build.gradle:24-25`,
  `spark-4.2/openhouse-spark-runtime/build.gradle:14-15`, `spark-4.2/openhouse-spark-itest/build.gradle:23`. The
  version rule (`baseVersion-ohfork.<12>`) is in Groovy, in bash (`publish-local.sh:28`) and in the composite action
  (`action.yml:22-24`).
- Marker-file checks with copied messages: `apps/spark-4.2:33-37`, `spark-4.2 runtime:27-31`, `spark-4.2 itest:27`,
  `rest-compat-kit:22-27`.
- Java 17 target restated in the capabilities module (`build.gradle:13-17`, "Same target as
  openhouse.java-minimal-conventions"), the Spark 4.2 runtime (`:33-36`) and the kit (`:29-32`).
- `extraJvmArgs` (19 `--add-opens`) copied in `apps/spark-4.2/build.gradle:156-161`, the Spark 4.2 runtime
  (`:100-105`) and the Spark 4.2 itest (`:104`). `LIVY_SERVER_JAVA_OPTS` repeats them in the dockerfile.
- `apps/spark-4.2/build.gradle:39-66` compiles `apps/spark-3.5`'s source directories and replaces two files by path
  exclusion. That is the cross-project `srcDirs` pattern #110 removed, brought back.
- `forcedModules = []` repeated in the Spark 4.2 modules and the kit.

### Comments that cite private ids or narrate history
- Build files and docs cite D7, D8, D23, D24, D33, I6d, V13, N3, N3-x1, N7, X5, T6, C2-3, W13: `settings.gradle` 4,
  `gradle/iceberg-fork.gradle` 4, the Spark 4.2 itest 4, the Spark 4.2 runtime 3, the capabilities module 3,
  `services/tables` 2, the 3.5 itest 2, and 14 in `docs/development/iceberg-fork.md`.
- `gradle/iceberg-fork.gradle:63-86`'s error message tells users about "decisions D7 and I6d", which mean nothing
  outside this effort.
- `docs/development/iceberg-fork.md` (461 lines) mixes build setup, CI internals, format v3 product behavior
  (`:200-235`), the Spark 4.2 test harness and the kit. Split it: build setup, CI, and product behavior belong in
  three places.

### Other
- `integrations/java/openhouse-rest-compat-kit/build.gradle:113-120`: a `test` task with `failOnNoMatchingTests = false`,
  "There are none yet".
- `integrations/spark/delta-harness/scripts/print-cp.init.gradle`: a developer tool tied to this effort's harness.
- `jobs-scheduler.Dockerfile:16,35`: `PATH` and `/usr/java/default` point at a JDK 1.8 path that the
  `openjdk:23-ea-11-slim` base does not have. This predates the effort, but its comment was edited next to it.

## 5. Tests

- "-PicebergLinkedIn builds LinkedIn's Iceberg 1.5.2 line, kept only as the behaviour reference" (#118). No test or
  check compares the ref line's resolved dependencies or POMs with `bb8929e1`. The evidence is jar entry lists,
  which cannot see B10 (same entries, different bytecode and POM) or B11 and B9 inside the uber jars. A
  `dependencies --configuration runtimeClasspath` diff per published module against `bb8929e1`, run in CI on the ref
  line, would pin it.
- "Without -PicebergFork the build is unchanged" (#87, #91, #109, #113) was true for its time, by entry-list and
  resolved-artifact diffs [the authors ran these]. At HEAD the claim no longer applies, because #118 removed the
  conditions (B9, B10).
- #113's "every jar, image and compose configuration is what it was" without the switch: the Livy pom fix changed the
  default Spark 3.5 image. The PR states this in its known gaps.
- #114's "OH_SPARK_BUILDER (default: today's builder stage, byte for byte)". HEAD's default is `dockerhub` (B16). It
  was true at #114 and was changed later without that claim being withdrawn.
- The publish guard was "verified by dry runs of the task graph" (#118). No test runs it. A Gradle TestKit test, or a
  CI step `./gradlew publish --dry-run` expecting failure, would pin it. Nothing tests the release workflow at all
  (D-1).
- The jobs-scheduler INFO check (`.github/actions/default-build-deployables/action.yml`) pins that the scheduler logs
  at INFO. It pins the cause of D-3 as expected behavior and does not check maintenance-job logging.
- `IcebergLineTest.expectedFromBuildVersion` (`IcebergLineTest.java:44-57`) accepts 1.2 as LEGACY, which can no
  longer occur. Harmless, but dead.
- `RestHttpPathLineTest` and `FormatV3MaintenanceTest` assert that the build set a system property. They test the
  build script, not the product.
- The kit's `test` task cannot fail for want of tests (`failOnNoMatchingTests = false`).
- Not covered by any test: real S3 with SDK 2.54.17 (B12), the Azure script, `SETUP.md`'s ADLS command, a Livy batch
  through the jobs service (`docs/development/iceberg-fork.md:156-157` says so).

## 6. Per pull request

| PR | "Leaves true" holds? | Why |
|---|---|---|
| 87 | partly | The mechanism is as described. "publishes into build/iceberg-fork/repo" was superseded by #109, and "docs explain" by later docs. The xalan inline and the source ports compile on both lines (not run by me). |
| 91 | partly | "Without the switch, settings.gradle and every build file behave as before" held then. Its datalayout-on-Spark-3.5 change became unconditional in #118 (B10). The line-detection and per-line expectation claims are outside this area. |
| 97 | cannot tell by reading | The C5/W12 code is other reviewers' area. I10, the CI job with no secrets and nothing published, holds; it is reshaped since. |
| 98 | yes (for its time) | The `@ConditionalOnClass` gating existed for the 1.2 line. At HEAD those guards are redundant (cruft above). |
| 105 | partly | "the data-layout library skips the root dependency forces ... under the switch only" is no longer true at HEAD (`build.gradle:90-96`, unconditional). |
| 109 | yes | The location under the Gradle user home, the lock, the pin a88f556c (= `origin/openhouse-1.12.0` tip), the fork's `iceberg-aws` and SDK 2.54.17 without `netty-nio-client`: all as described in the code. |
| 110 | yes | `git diff -M` on the merge: 193 renames, 0 changed lines in any moved file. The remaining changes are build files, two workflow token paths and a README. |
| 113 | partly | Held for the default build at the time. #118 then made B8 and B9 apply on every line, so "the Spark 3.5 apps jar carries log4j2.properties under the switch" is now "on both lines". |
| 114 | partly | The default builder is no longer "today's builder stage" (B16). The rest describes CI that #118 later swapped. |
| 118 | partly | "-PicebergLinkedIn builds LinkedIn's Iceberg 1.5.2 line" is not the same as LinkedIn main's build (B9-B11). "No product code changes" is true of `src/main` Java, not of what ships. The publish-guard and deletion claims hold. The release-workflow gap is admitted. |
| 122 | yes | The download skip, the waits and the triggers are in the code. "No version choice changed" is true of versions, but not of the builder default (that came with #118/#122's file). |
| 123 | yes | Same 5.3.0; `transitive = false`; `Sync` into the same directory; script deleted. "same bytes" cannot be checked by reading; it is the same Maven Central coordinate. |
| 136 | yes | Eight lines in the root `JavaPlugin` block. Nothing else. |

## 7. For the clean re-implementation

Essential design, what must survive:
1. One property, read once, decides the Iceberg line, or better one version catalog per line. Every module reads the
   resolved value; no module tests the property itself.
2. The fork's Iceberg comes from a hosted repository under a group LinkedIn controls, at an immutable release-like
   version. The local source-build script remains only as a developer override. With that, the remote-publish guard
   and the out-of-Gradle step disappear.
3. If a source build stays: pin by full commit and verify it after checkout (already done), verify the wrapper jar of
   the Iceberg build, and use an `exclusiveContent` repository for exactly that group and version (already done).
4. The Java target follows the Iceberg line: 17 with 1.12. Say it in one place and state it as a compatibility break
   for client jars.
5. Root forces per line in the version catalog, not as `if` branches inside `allprojects`.
6. The Spark 3.5 apps, Java client, itest and fixtures each have a single `src/main` and `src/test`. No `src/shared`
   and no `iceberg-version-specific` once only one line uses them.
7. Uber jars exclude `META-INF/versions/21+`, or the shadow plugin is upgraded. One comment, in one convention plugin.
8. The Spark image takes Spark from Apache's image and the Iceberg runtime from the build. One builder stage.
9. The scheduler image passes its own log configuration. The apps jar carries none.
10. Deleted Spark 3.1 / Iceberg 1.2 projects go in one commit, with the list of artifacts no longer published.
11. `datalayout` moves to Spark 3.5 in its own commit, declared as a POM change.
12. The release workflow publishes what CI built, from the line that can be resolved by consumers, and tags only after
    publishing succeeds.
13. The OpenAPI generator CLI as a Gradle dependency (#123) and UTF-8 encoding (#136) are clean and should stay as
    they are.

Do not carry over:
- `-PicebergFork` and `HARNESS_ICEBERG_FORK`.
- The docker and publish refusals, if the artifacts are hosted.
- `@ConditionalOnClass` inside line-specific source roots, and the 1.2 arm of `IcebergLine`.
- `apps/spark-4.2` compiling another project's directories. Make the shared job code a library project that both apps
  projects depend on.
- Duplicated pin parsing, marker checks and `--add-opens` lists. Use one convention plugin for "Spark 4.2 on Java 17".
- Decision and work-item ids in comments and error messages.
- The unused `default` builder stage.
- The 461-line `iceberg-fork.md` as one file.

Proposed commit sequence for upstream:
1. build: UTF-8 source encoding (#136 as is).
2. build: OpenAPI generator CLI as a Gradle dependency (#123 as is).
3. build: fold the 1.5 projects' borrowed source directories into their own `src/main`/`src/test`, as a pure `git mv`
   (#110's move, but into the standard roots, with the 1.2/3.1 projects reading them from the new place).
4. build: Spark 3.5 itest stops excluding its own `*Spark3_5` DDL/DML tests (B18 as a fix, on LinkedIn's line).
5. build: `datalayout` on Spark 3.5 (declared POM change, LinkedIn's line).
6. build, Spark 3.1 / Iceberg 1.2 removal: delete the projects, plugin, image and Renovate rule; the scheduler image
   runs the 3.5 apps jar, with log config passed in the Dockerfile. Owner/LinkedIn decision; lists the dropped
   artifacts.
7. build: per-line version catalogs and one line switch (no behavior change: the default stays LinkedIn 1.5.2).
8. build: an Iceberg 1.12 line selectable by the switch, resolving the fork from a hosted repository. Java 17 target,
   forces and S3 SDK under that line.
9. build: shadow `META-INF/versions/21` exclusion, plus the Spark image on Java 17 with the line's Iceberg runtime.
10. build: new modules (capabilities, then Spark 4.2, then the kit), each in its own commit with its CI job.
11. build: make 1.12 the default (the flip), together with the release workflow change. A separate, explicitly
    behavior-changing commit.
12. build: remove the 1.5.2 line and its per-line roots once LinkedIn no longer needs the reference.

## 8. Not checked

- No Gradle run, so no resolved dependency graph. All version statements are from declarations. D-7 and the exact
  contents of each uber jar or boot jar are inferred, not listed. The authors' entry-list evidence is cited, not
  reproduced.
- The Spark 4.2 itest build file, beyond the lines grepped; `scripts/ci/*` and `split-build.yml` in depth (test
  grouping logic); `.github/actions/default-build-deployables/action.yml` beyond a scan for downloads.
- Product code under `src/iceberg-1.12`, the Java client and the Spark runtime (other reviewers). They are mentioned
  only where the build makes them line-dependent.
- Whether the base images' tags (`apache/spark:3.5.2-scala2.12-java17-python3-r-ubuntu`,
  `maven:3.9.4-eclipse-temurin-11`, `eclipse-temurin:17-jre-jammy`) are still the content they were tested with. They
  are tag-pinned, not digest-pinned, as LinkedIn's were.
- The Azure terraform recipe end to end.
- Whether Log4j2 in LinkedIn's actual Spark deployments would pick up the jar's `log4j2.properties` (D-3).
