# Review of pull request 166 (`irc/ci-check-name-and-build-hygiene`)

Head reviewed: `06682b5e7698aadb5416c52bce151e5d86e1dafd` (equal to the pull request's head per the API), base
`origin/irc-integration` `67989d16` (= merge base). Commits: `d75f5238` (guard), `c7091618` (aggregate check),
`bea1eb8a` (build hygiene), `83fa5bb4` (Jackson), `06682b5e` (merge of the base; brings only base files). The Jackson
commit touches only `build.gradle`, `gradle/iceberg-fork.gradle`, `scripts/iceberg-fork/iceberg-fork.properties`,
`services/tables/build.gradle` and the new test, so it reverts cleanly.

## 1. Verdict

Two verdicts, because the Jackson commit decides it.

- **Pull request without `83fa5bb4`: MERGE AS IS.** Nothing must be fixed first; the findings on that part are
  SHOULD FIX or MINOR (section 2, F5 to F12).
- **`83fa5bb4` (Jackson) on its own: DO NOT MERGE in this form.** It moves the tables service's whole JSON
  engine (databind 2.13.4 to 2.22.2 and eight other modules) on the default line to fix a mix that I could not
  find to be a defect: link-clean, no runtime version check anywhere, no warning in any log, and the same
  pattern LinkedIn main itself uses (Iceberg asks for a newer Jackson, LinkedIn forces databind back to 2.13.4).
  The justification written in the code is wrong (Iceberg 1.12 does not need jackson-core 2.22), the evidence
  that answers did not change was not gathered, and the Spark suites cannot show anything because the test
  fixtures still run databind 2.13.4. Replace it with the cheaper option: keep databind as it is and pin
  each line's exact Jackson list with the same test (F1). If the orchestrator still wants alignment, see the
  conditions in F2 and F3.

The three things that matter most:
1. The Jackson alignment is a behavior change against main and the base with no measured need. Its stated
   reason ("its iceberg-core needs jackson-core 2.22, and databind and the other modules must be on the core's
   minor") is false on the jars (F1).
2. It is not measured. A probe against the jars shows at least one native 400 message text that changes, and
   the `stacktrace` field of every native error answer changes. Pull request 161's comparison has not run.
   The Spark-level suites run the embedded service through `tables-test-fixtures`, which still resolves
   databind 2.13.4, so they test a Jackson that is no longer shipped (F2, F3).
3. FF-27 is done right. The required context is exactly `build-run-tests / Build and Run Tests` (verified
   with the same GET of LinkedIn's ruleset), it reports on the head, and the verdict script cannot go green
   for a failed, cancelled or wrongly skipped job. Its cost: every pull request's test jobs now start
   about 80 s later, and up to about 8 minutes later on one kind of fork-cache miss (F5).

## 2. Findings

### F1. MUST FIX BEFORE MERGE (Jackson commit): the alignment fixes no demonstrated defect, and its justification in code is false
- What: on the default line `:services:tables` gets every Jackson module forced to 2.22.2 (`build.gradle:103-109`):
  `def jacksonForced = (icebergFork && it.path == ':services:tables') ? rootProject.ext.icebergForkJacksonVersion : '2.13.4'`.
  `scripts/iceberg-fork/iceberg-fork.properties:18-20` justifies it with "its iceberg-core needs jackson-core 2.22,
  and databind and the other modules must be on the core's minor", and the test's Javadoc
  (`JacksonRuntimeVersionsTest.java:21`) says "the fork's Iceberg 1.12, which needs jackson-core 2.22".
- What I measured on the jars on disk (a class-file reader that resolves every method, field and class reference
  through the superclass and interface chain; script in the scratchpad, `jkscripts/linkcheck.py`):
  - jackson-databind 2.13.4 against jackson-core 2.22.2: 707 classes, 0 missing classes, 0 missing members
    (apart from `ordinal()` inherited from `java.lang.Enum`, the same 16 against 2.13.4 and 2.14.3). The abstract
    method sets of `JsonParser`, `JsonGenerator`, `ParserMinimalBase`, `GeneratorBase`, `ObjectCodec` and `TreeCodec`
    are identical in core 2.14.3 and 2.22.2, so databind's `TokenBuffer`, `TreeTraversingParser` and friends
    implement everything core 2.22 can call. The check detects real gaps: reversed (databind 2.22 against core
    2.14.3) it reports 20 missing classes and 54 missing members.
  - The base's other modules (jsr310 2.14.2, jdk8 2.13.5, xml 2.13.5, yaml 2.14.2, jaxrs-base and json-provider
    2.13.4, jaxb-annotations 2.13.4, parameter-names 2.13.4) against core 2.22.2 plus databind 2.13.4: 0 missing
    members, apart from JDK-inherited methods.
  - No version check that warns or throws: no standard module calls `getMapperVersion` or compares a `Version`
    (bytecode of databind 2.13.4, jdk8, jsr310 and parameter-names; only `PackageVersion` parses its own string).
    azure-core's `JacksonVersion` (1.49.1 and 1.58.1) only logs, and only for a major version newer than 2 or for
    anything older than 2.10.0.
  - The fork's Iceberg against core **2.14.3** plus databind 2.13.4: iceberg-api, iceberg-common, iceberg-aws and
    iceberg-data are clean; iceberg-core misses exactly one real member, `JsonNode.properties()` (databind 2.15+)
    in `org.apache.iceberg.rest.requests.RemoteSignRequestParser` (`JsonNode.forEach` is `Iterable`'s default
    method). So Iceberg 1.12 needs no jackson-core API newer than 2.14. Its one newer need is a databind one, on
    the remote-signing request parser, which the tables service never runs: it serves no signer route, and
    `RESTSerializers.registerAll` only registers the deserializer, whose `properties()` call links lazily.
    That is a latent NoSuchMethodError, not reachable from the service's routes.
  - The fork's `iceberg-core` and `iceberg-aws` Gradle metadata request jackson-bom, core and databind 2.22.2,
    as LinkedIn's `iceberg-core-1.5.2.24.module` requests 2.14.3 for all three, which main forces back to databind
    2.13.4. The base's "mix" is main's own policy applied to a newer Iceberg.
  - CI on the base `67989d16`: every default-line job green with the mix. The base's `rest` job log has no
    Jackson warning; the only Jackson lines are checkstyle notes on generated clients.
- So: the mix is real but not a defect I can show, while the fix moves databind 2.13 to 2.22 under every native
  answer. Under the owner's rule this is the wrong trade.
- Fix (smallest): drop the force from `83fa5bb4` (`build.gradle:96-109` back to the two literal forces,
  `ext.icebergForkJacksonVersion` and `jacksonVersion` out). Keep `JacksonRuntimeVersionsTest` with the default
  line pinned to its exact list as the base resolves it (core 2.22.2, databind 2.13.4, annotations 2.14.2, jdk8
  2.13.5, jsr310 2.14.2, xml 2.13.5, yaml 2.14.2, jaxrs 2.13.4, jaxb 2.13.4, parameter-names 2.13.4, bom 2.13.4,
  per the author's record). Declare the core-ahead-of-databind mix in the comment with the facts above. That
  closes FF-30's "pin it with the BOM or raise the force deliberately" by pinning. If alignment is wanted anyway,
  correct both comments to say what is true: a choice, not a need.
- Sure: certain from the jars for the link facts. That nothing behaves differently with the mix is shown only
  by the base's green CI.

### F2. MUST FIX BEFORE MERGE (Jackson commit, if kept): "no native answer changed" is assumed, and a probe shows answers that do change
- I ran a scratch probe from the scratchpad (`jexp/`; javac and java on the cached Jackson jars only, no project
  build). It uses a mapper configured like Spring Boot 2.7's (Jdk8Module, JavaTimeModule,
  `ParameterNamesModule(DEFAULT)`, `FAIL_ON_UNKNOWN_PROPERTIES` and `DEFAULT_VIEW_INCLUSION` off, dates not as
  timestamps), classes compiled with `-parameters` as the Spring Boot plugin does, and two classes with exactly
  Lombok's generated shape. One is `CreateUpdateTableRequestBody`: protected no-args and all-args constructors,
  getters only, `@Builder.Default` initialisers in the no-args constructor. The other is `GetTableResponseBody`:
  `@Value @Builder`, package-private all-args constructor, `@JsonProperty(access = READ_ONLY)` fields. It ran 32
  request bodies (wrong types, coercions, null, duplicates, unknown properties, empty and non-JSON bodies, enums)
  and one serialization, each on three classpaths: main's (core 2.14.3, databind 2.13.4), the base's (core 2.22.2,
  databind 2.13.4) and this branch's (everything 2.22).
  - Base to this branch: serialization byte-identical, including property order. Creator choice identical: the
    no-args constructor is used, so `tableType` defaults to `PRIMARY_TABLE` and does not become null under 2.18's
    creator-detection rewrite. 31 of 32 outcomes and messages identical. **One message differs**: for
    `{"tableProperties":""}` the text goes from ``element of `java.util.LinkedHashMap<java.lang.String,java.lang.String>` ``
    to ``element of `java.util.LinkedHashMap` ``. The native 400 for an unreadable body embeds this text
    (`OpenHouseExceptionHandler.unreadableMessage`: `"Unacceptable JSON " + exception.getMessage()`), and so does
    `cause` (`getExceptionCause` returns the Jackson exception's message).
  - Certain from reading: every native error answer carries `stacktrace` (`getAbbreviatedStackTrace`, the full
    trace up to 6000 characters), whose Jackson frames and line numbers change with any databind version.
  - Main to base (for context, not this pull request): only core's location text changes (the `REDACTED` source,
    the decided BC-093 answer), plus three column numbers (`not json` 4 to 1, a trailing comma 17 to 16, an empty
    body column 0 to none).
  - Message templates compared across the two databind jars: also changed are "deeply-nested JSON Array" to
    "deeply-nested Array" (only with `UNWRAP_SINGLE_VALUE_ARRAYS`, off), `PropertyBindingException`'s closing
    `])` to `)` (only when unknown or ignored properties fail, off here), and "JSON Creator returned null"
    removed. None is exercised by default.
- What exercises what: the native DTOs use Strings, longs, booleans, Maps, Lists, enums and nested Lombok POJOs.
  There is no `java.time`, no `Optional`, no polymorphic or default typing (`@JsonTypeInfo` is absent), and the
  only `@JsonInclude` is NON_NULL on two view bodies. So jsr310, jdk8 and default typing are not on the native
  surface. jackson-dataformat-xml on the classpath makes Spring MVC register its XML converter, so an
  `Accept: application/xml` request is answered by xml 2.22 instead of 2.13.5. I did not measure that. The IRC
  routes echo `e.getMessage()` of databind in their 400s (`IcebergRestJson.parse`), so their texts can move too.
- What would make "no native answer changed" measured:
  (a) Merge pull request 161 first, then this branch merges the base, and its CI runs
      `LinkedInMainComparisonTest` on the default line. That covers the 321 recorded commit-path answers: status,
      plus the error's `message` or a GET's body. It does not compare `stacktrace`.
  (b) Pull request 161's recording has no malformed-body native answer (`grep -c 'Unacceptable JSON'
      commit-path.tsv` = 0), and that is the only class where I found a change. Record a few on main (wrong
      type, empty string into a map or POJO, bad enum, non-JSON) and compare them.
  (c) Spark SQL outcomes cannot measure this change while the fixtures still run databind 2.13.4 (F3).
  Make (a) and (b) conditions of merging the Jackson commit, and declare the `stacktrace` difference in the
  behavior-change list.
- Sure: certain for the measured message and for `stacktrace`. The probe uses replicas of the DTOs' shape, not
  the DTO classes, and not Spring's converter path.

### F3. MUST FIX BEFORE MERGE (Jackson commit, if kept): the embedded tables service in the Spark suites still runs databind 2.13.4
- `buildSrc/src/main/groovy/openhouse.tables-test-fixtures-common.gradle:18` takes `project(':services:tables')`
  into the fixture project and shades `com.fasterxml` (`:133`) from the fixture project's own resolution. The
  force picks 2.22.2 only for `it.path == ':services:tables'` (`build.gradle:103`). The fixture project's path
  (`:tables-test-fixtures:tables-test-fixtures-iceberg-1.5_2.12`) gets `'2.13.4'`. So every Spark 3.5 itest, apps
  test and datalayout test that runs the service through the fixtures runs tables code on databind 2.13.4
  (compiled against 2.22.2), not what the boot jar ships. That includes pull request 161's Spark SQL comparison.
  The "default line's suites ran green" evidence therefore covers the new Jackson only through `services/tables`'
  own tests, the Docker job and the Dataloader integration tests.
- Fix: apply the same force to the fixture project's path (and test the fixtures' shaded Jackson the same way),
  or drop the alignment (F1).
- Sure: certain from reading the build logic. I did not resolve the graph.

### F4. SHOULD FIX (Jackson commit, if kept): the scope contradicts its own reasoning
- `:services:housetables` and `:services:jobs` keep core 2.22.2 with databind 2.13.4 on the default line (the
  author's record). If the mix were a defect, those two would need the same fix. If it is not, the tables service
  did not need it. The House Tables client in the tables JVM now serializes with 2.22, and the House Tables
  service deserializes with 2.13.4. Serialization output was identical in my probe and the generated client
  model is plain, so I expect no wire difference, but it is not measured. The `namespaceIntegrationTest` task
  (`services/tables/build.gradle:191`) runs House Tables' code in the tables project's JVM at 2.22, so that test
  also exercises a combination that is never deployed.
- Fix: decide per service with one reason, written once.

### F5. SHOULD FIX (FF-27): the plan job now delays every job of both lines
- Measured on CI: base plan jobs took 12 s (default) and 6 s (reference). On this head they took 91 s and 54 s with
  every cache a hit: JDK, Gradle cache restore (about 30 s), fork restore, then `check-test-groups.py` (36 s). All
  test and Docker jobs `needs: plan`, so each starts about 80 s (default) or 48 s (reference) later.
- On a fork-cache miss the plan job publishes the fork (`bea1eb8a`'s plan ran 8 min before it was superseded).
  On a pull request that does not change the pin or the script (the base's entry evicted), the action does not
  save (`.github/actions/iceberg-fork/action.yml`, the `Save` step's condition). Every test and Docker job then
  misses too and publishes again: about 8 more minutes on the critical path than before, when the jobs published
  in parallel and nothing waited for plan.
- Fix: run the partition check in its own job that only the aggregate needs (not the test jobs), or let the plan
  job save the fork whenever it published. Can wait: a cost, not wrong results.

### F6. MINOR (FF-27): `if: always()` makes superseded and cancelled runs report a red `Build and Run Tests`
- `split-build.yml:308`. A run cancelled by the concurrency group still runs the verdict, which reports failure
  ("tests: cancelled"), as the author saw on `c7091618`, `bea1eb8a` and `83fa5bb4`. It only affects superseded
  SHAs, and branch protection reads the head's check. `if: ${{ !cancelled() }}` would show those as cancelled
  instead. Both block a merge equally. Cosmetic.

### F7. MINOR (guard): the `docs/plans/` skip also hides planning notes that do get merged
- `scripts/ci/check-private-ids.py:41`, `SKIPPED_DIRS = (..., "docs/plans/")`. The reason given is that the notes
  live on a branch that is never merged. If a pull request does merge them into `irc-integration`, the guard now
  passes it, and the notes (full of ids and private context) would go upstream with the next contribution. A
  one-line check that no tracked file exists under `docs/plans/`, run on non-draft pull requests only, would keep
  the draft case working. Can wait: no such tree exists today.

### F8. MINOR (guard): edges of the new letters
- Probed with the guard's own `matches()`. `H2-x` and `H2a` are not matched, so a planning id H2 in any suffix
  form passes. "H264", "the G1 collector", "R2 bucket", "R10 region" and "O365" are matched (false positives;
  each costs an allow-list line). None of these occur in the tree today (`--self-test` 0 failures; scan exit 0 in
  the worktree). "(V2; x)" is now caught, as intended.

### F9. MINOR (FF-29): the docs claim the IDE import works without the fork; not measured, and likely not true
- `docs/development/iceberg-fork.md` (bea1eb8a hunk) and the comment at `gradle/iceberg-fork.gradle:307-309` say
  the IDE import works on a fresh clone. The `afterResolve` hook throws on any resolution with an unresolved fork
  dependency, and an IDE sync resolves every module's compile classpath. The author measured `help`, `projects`
  and `spotlessCheck` only. Fix: measure it, or say "help, projects, spotless" only.

### F10. MINOR (FF-29): a partially published fork now passes configuration
- The four per-module marker checks (Spark 4.2 runtime in three modules, the REST kit's `-open-api` marker) used
  to stop configuration when their set was incomplete. Now a missing marker is a warning, and only an unresolved
  module (graph level) gets the hint. A publish interrupted after the POM was written but before the jar leaves a
  graph that resolves and an artifact that is missing. Gradle still fails loudly ("Could not find ... .jar"),
  without the hint. The jars come only from `exclusiveContent` on the fork's directory at that exact version
  (`gradle/iceberg-fork.gradle:71-84`), so no other Iceberg can be substituted silently: not from Central, and not
  from `~/.m2` (mavenLocal is only a publishing repository in `openhouse.maven-publish.gradle:79`).

### F11. MINOR (FF-29): the Java check refuses early-access version strings
- `publish-local.sh`: for `17-ea` or `21-ea`, `${java_version%%.*}` is not numeric, so the check refuses. It
  fails closed with a clear message.

### F12. MINOR (author's behavior list): the workflow list misses the release pre-check's image
- `build-run-tests.yml` (in `bb8929e1`, run before every release by `build-tag-publish.yml`) builds and
  integration-tests the tables image. With `83fa5bb4` that image carries Jackson 2.22. The author lists that
  effect only under `dataloader-tests.yml`.

## 3. Behavior changes

| # | Change | Where | Toward or away from main | Default? | In the author's list |
|---|---|---|---|---|---|
| 1 | Tables service (boot jar, image) on the default line: databind 2.13.4 to 2.22.2, annotations 2.14.2 to 2.22, jdk8, jsr310, xml, yaml, jaxrs, jaxb and parameter-names to 2.22.2, bom to 2.22.2 | `build.gradle:96-109` | Away (main: databind 2.13.4) | On, default line | Yes |
| 1a | One native 400 message text (Map coercion), and `cause` with it | from 1 | Away | On | No (measured here) |
| 1b | `stacktrace` field of every native error answer (Jackson frames) | from 1 | Away | On | No |
| 1c | XML answers (Accept: application/xml) through xml 2.22.2 | from 1 | Away, size unknown | On | No |
| 1d | IRC 400 texts that echo databind messages | from 1 | n/a (new surface) | On | No |
| 1e | Embedded service in the Spark suites stays on databind 2.13.4, unlike the shipped one | fixtures | n/a (test fidelity) | On | No |
| 2 | Check `build-run-tests / Build and Run Tests` reports again, as the verdict over all jobs | `split-build.yml:307-325` | Toward | On | Yes |
| 3 | Extra check `iceberg-linkedin-build / Build and Run Tests`; also two such checks after each merge | same | Away (extra, optional) | On | Yes (pull request), not post-merge |
| 4 | Plan job about 80 s longer on a hit, up to about 8 min longer on one miss case; every job waits | `split-build.yml:87-112` | Away (cost) | On | Partly ("runs longer") |
| 5 | `-PicebergLinkedIn=false` (also in `gradle.properties`) builds the default line; values other than empty/true/false refused | `settings.gradle:37-50` | n/a (main has no line property) | n/a | Yes |
| 6 | No fork: configuration warns instead of failing; Iceberg-resolving tasks fail with the hint | `gradle/iceberg-fork.gradle` | n/a | On | Yes |
| 7 | `publish-local.sh` refuses JDK < 17 and an unverified wrapper jar; fork cache key changes once (script hash) | script | n/a | On a cache miss | Yes |
| 8 | Private-id guard: dead allow-list entries fail; more letters; `docs/plans/` skipped | `check-private-ids.py` | n/a (CI process) | On | Yes |

## 4. Tests

- `JacksonRuntimeVersionsTest`:
  - Reference line: `containsExactlyElementsOf(REFERENCE_LINE)` fails on any added, removed or moved module.
    The list is consistent with what I can establish without Gradle: core 2.14.3 is what `iceberg-core-1.5.2.24.module`
    requests, and databind and bom 2.13.4 are main's forces (`bb8929e1:build.gradle`). The other entries I could
    not confirm without resolving (the author says he resolved main's tree).
  - Default line: asserts the minor only. It fails for the right reason if any module leaves 2.22, and it failed
    before the force (author). If F1's fix is taken, change it to an exact list as on the reference line.
  - The "task input" trick: `inputs.property('jacksonRuntime', provider)` is sound and needed, because
    `org.gradle.caching=true` would otherwise replay a cached test result after a version change. The provider
    resolves `runtimeClasspath` when the task's inputs are fingerprinted. The system property set in `doFirst` is
    not fingerprinted, but the input property covers it.
  - `testsRunOnTheSameCoreAndDatabind` checks the test JVM's own core and databind (`testRuntimeClasspath`), so
    it pins what the tests ran on, not what ships. The first test pins what ships. Both ran and passed in both
    `rest` jobs on the head (job logs 140943994847 and 140943833565).
  - Unpinned: the fixtures' Jackson (F3), the House Tables and jobs services' Jackson (F4).
- `check-needed-jobs.py --self-test`: 11 cases, each would fail if the verdict rule were wrong (red, cancelled,
  plan red with the rest skipped, Docker skipped where not allowed, an allowed skip that ran red, an allowed skip
  that is not a needed job, no result, nothing needed). Ran here: 11 cases, 0 failures. In CI, the verdict lines
  on the head are "docker: success / plan: success / tests: success / Build and Run Tests: success" (default) and
  "docker: skipped, as this line does by design" (reference).
- `check-private-ids.py --self-test`: covers the new letters, H2, "(V2;", both skipped directories, and the
  dead-entry rule through `scan()`. Ran here: 0 failures; the scan exits 0.
- No test for the boolean line property or for the missing-fork path (the author demonstrated them by hand). They
  would need Gradle TestKit; acceptable for build hygiene.

## 5. Checked and found sound

- Required check: `gh api repos/linkedin/openhouse/rules/branches/main` returns ruleset 433749 with
  `required_status_checks: [{"context":"build-run-tests / Build and Run Tests"}]` and no integration id. The head's
  check runs include exactly that name (id 140952373935, success).
- The verdict cannot be green when a needed job failed, was cancelled, or was skipped (other than Docker on the
  reference line): `ok` starts false for empty needs; any result other than success fails; the allowed skip
  applies only to the named job and only to `skipped`. The matrix `tests` result is the aggregate of its legs.
  `--allow-skip docker` is passed only when `inputs.line == 'reference'`.
- Callers of `split-build.yml`: only `pr-validations.yml` and `post-merge-build.yml`. `build-tag-publish.yml` calls
  `build-run-tests.yml` (unchanged, its own single job), and `dataloader-tests.yml` calls nothing. So the new job
  appears on pull requests and after merges only, which is right.
- The aggregate job's id `build-and-run-tests` does not clash with the cache key value of the same spelling: it
  runs no setup-gradle.
- Wrapper jar: the pinned SHA-256 `7d3a4ac4...6172` equals OpenHouse's committed `gradle/wrapper/gradle-wrapper.jar`
  (its properties: Gradle 8.14.5) and the bytes served at the pinned URL today. The fork commit's
  `gradle-wrapper.properties` is 8.14.5 and carries `distributionSha256Sum`, so the distribution the jar fetches
  is verified too. The fork's `gradlew:89-90` downloads only if the jar is absent, so the verified jar is the one
  run. A mismatch, or a missing `sha256sum`/`shasum`, fails closed. Offline with a verified jar present: no
  download. On a CI cache hit the script exits before both checks. gradle.org's published checksum file was not
  reachable from here (403 at the proxy).
- Line property: no `hasProperty('icebergLinkedIn')` reader is left (`git grep`). Every build file, buildSrc
  plugin and module reads `gradle.ext.icebergLinkedIn`. The workflows and `check-test-groups.py` pass
  `-PicebergLinkedIn` with no value, which still means the reference line. Absence, `=false`, `=true`, an empty
  value and `=yes` behave as documented, by reading `settings.gradle:37-50`.
- Missing fork: the fork group at the fork version comes only from the fork directory (`exclusiveContent`), so no
  silent substitution. The remote-publish guard still stops `build-tag-publish.yml`'s `./gradlew publish` before
  any task.
- The reference line's forces are unchanged (`'2.13.4'` whenever `!icebergFork`).
- Spring 5.3.25, spring-boot 2.7.8, spring-boot-autoconfigure 2.7.8 and jackson-databind-nullable 0.2.1 link
  against Jackson 2.22 (missing only the optional CBOR, Smile and JAXB formats, which they guard). swagger-core
  2.2.9 (springdoc 1.7.0) references `BeanDescription.findJsonValueMethod()`, removed in databind 2.22, but only on
  a fallback reached when `findJsonValueAccessor` is absent, which never happens. No defect.
- CI on the head: 20 check runs, every one success or skipped by design: the reference Docker job, and the two
  caller checks skipped on the `edited` event.
- Private ids in added lines: none outside the guard's own examples, which the guard skips.
- Product code: only the dependency force and the one test (plus build logic). No cruft found beyond F9's doc
  claim.

## 6. Not checked

- What `:services:tables` actually resolves on either line, and main's resolution (no Gradle). I relied on the
  author's record for the version lists, and checked them against the module metadata and forces.
- A link check of the whole tables runtime classpath against Jackson 2.22. I checked only Spring, Spring Boot,
  swagger-core, databind-nullable, azure-core's version check and the fork's Iceberg jars.
- XML answers and the full Spring converter path (the probe used plain `ObjectMapper` and replicas of the DTOs).
- That `providers.gradleProperty` in `settings.gradle` sees `ORG_GRADLE_PROJECT_icebergLinkedIn`.
- The IDE import without the fork (F9).
- Pull request 161's comparison with this branch (not run; that is F2's condition).
