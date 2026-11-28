# Re-check of pull request 168

**Head:** `7e7a607d6094e1dbf4e27f480e8cf164b493d14a`. This is the pull request's head now, a merge of `0ea38e9f` and `0b3cafb6`.

**Base:** `origin/irc-integration` at `0b3cafb6`. This is the merge base and the second parent.

**What I ran:** nothing beyond reading code and logs. I read class files with `javap` from the spring-webmvc jars on disk: 5.3.18, the version `cluster/configs` pins, and 5.3.26, for comparison. I read CI logs with `gh api` GET requests.

## Verdict: MERGE

Every finding from the first review is closed. One residual gap is left in F8, and it can follow in a later pull request (see section 3).

A deployment that takes this build and changes nothing gets the same answer under `/iceberg/**` as LinkedIn main does. This now rests on three things:
- reading the code;
- the off test, which sends real tokenless requests and has passed 7 of 7 on both lines in CI;
- the Docker job, which runs the shipped `oh-only` recipe in a real container and prints main's 400 and body, with and without a token.

## 1. F1: closed

**The tokenless requests really carry no token.**
- `IcebergRestRoutesOffTest.setup()` builds the tokenless client as `MockMvcBuilders.webAppContextSetup(webApplicationContext)`, adds the context's `Filter` beans, then calls `addDispatcherServletCustomizer(d -> d.setThrowExceptionIfNoHandlerFound(true))`.
- That client is built on the same context, so it adds no Spring context.
- A plain `webAppContextSetup` does not apply Boot's `MockMvcBuilderCustomizer` beans. `MockMvcBuilderConfig`, which adds the default `Authorization` header, is therefore not applied to it.
- `perform` checks the request that was actually executed: `assertEquals(credentials ? "Bearer " + token : null, result.getRequest().getHeader("Authorization"))`. A token leaking into a tokenless request fails this check.

**"An interceptor runs only once a handler is found" is true for main's configuration.**
- In spring-webmvc 5.3.18, `AbstractHandlerMapping.getHandler` calls `getHandlerInternal`, falls back to `getDefaultHandler`, and returns `null` when both are null (bytecode offsets 0-20). It returns before any `HandlerExecutionChain` exists.
- `DispatcherServlet.doDispatch` then goes to `noHandlerFound` before `applyPreHandle`.
- Main's `application.properties` has the same settings as the head's: `spring.mvc.throw-exception-if-no-handler-found=true`, and the static path pattern `favicon.ico` only. There is no default handler, so `NoHandlerFoundException` is raised.
- `OpenHouseExceptionHandler.handleNoHandlerFoundException` answers it, the same 400 with or without a token.

**The native 401 with an empty body is main's answer.**
- `DummyTokenInterceptor.preHandle` (`services/common`, unchanged from main) handles a missing header or one without the `Bearer` prefix by calling `setStatus(SC_UNAUTHORIZED)` and returning `false`. It writes no body and raises no exception, so no advice and no audit event follow.
- The test asserts exactly this: 401, a non-null handler, no resolved exception, an empty body, and no audit event.

## 2. F3: closed

**The step runs against the recipe as shipped.**
- The step is `oh-only-iceberg-off` in `.github/actions/default-build-deployables/action.yml`. It runs after the unchanged `docker compose -f infra/recipes/docker-compose/oh-only/docker-compose.yml up -d --build`, with no override file.
- Nothing under `infra/recipes/docker-compose/oh-only` or `common` mentions `irc` or `CLUSTER_TABLES_*` (grep).
- The diff touches nothing under `infra/`.

**A non-400 fails the step.**
- A composite step with `shell: bash` runs with `-e`, and `test "$code" = 400` and `grep -q '"message":"The combination of the method \[GET\] and Path \[uri=/iceberg/v1/config\] cannot be resolved by server'` run inside the loop. Either failing ends the step with an error.
- The step is also added to the condition that prints the containers' logs.

**It ran on this head.** Job 141002341802, "Default build, Docker images and integration tests", succeeded. Its log prints:
- `token= status=400 body={"status":"BAD_REQUEST","error":"Bad Request","message":"The combination of the method [GET] and Path [uri=/iceberg/v1/config] cannot be resolved by server. Please check and making sure you are using the right version of API.","stacktrace":null,"cause":"Not Available"}`
- the same line again with `token=yes`.

## 3. F8: closed, with a residual gap that can follow

**What the test does now.** `classesCarryingTheSwitch()` reads the class files matching `classpath*:com/linkedin/openhouse/tables/**/*.class` with ASM metadata, without component scanning. It collects every class with `ConditionalOnIcebergRest` or that annotation as a meta-annotation. On the branch it also asserts that `TableCommitService` is among them, so the scan cannot come back empty without failing. A bean of any collected class fails the test.

**What it covers:**
- Nested classes: `Outer$Inner.class` matches `*.class`, and ASM reads its annotations.
- Classes in `api.irc` are also caught by package, without the scan.
- `@Bean` methods: the annotation is `@Target(ElementType.TYPE)`, so it cannot be put on a `@Bean` method at all.

**What it can miss:**
- (a) A class outside `com.linkedin.openhouse.tables` that carries the switch, for example in `com.linkedin.openhouse.internal.catalog`, `cluster` or `common`, all of which the service scans. There is none today.
- (b) A `@Bean` method that exists only for the routes but is guarded with its own `@ConditionalOnProperty(IRC_ENABLED)` instead of the annotation. There is none today.

`nothingIsMappedUnderIceberg` still catches any such bean that maps a handler. To close the gap, widen the pattern to `classpath*:com/linkedin/openhouse/**/*.class`, and add a check that no bean definition's factory method carries a `ConditionalOnProperty` naming `cluster.tables.irc.enabled`.

## 4. The merge: sound

**Parents.** The parents are `0ea38e9f` (the fix series) and `0b3cafb6` (the base).

**Nothing of 166 or 167 was lost.** `git diff 0b3cafb6 7e7a607d` touches 33 files, and every one is a file this pull request changes. There are no changes to any other workflow, to the build files from 166, or to the kit's `expected-outcomes.txt`, ratchet, list or documentation from 167. In `action.yml` the only change is the added step and its log-print condition.

**`RestCompatKitService`.**
- It keeps 167's structure: one argument list, then `args[1..]` appended per run, with the `format-v3` run adding its property.
- It adds `--cluster.tables.irc.enabled=true` to the shared list, so both runs have the routes on.

**Kit summaries in the Default build `rest` job (141002341863):**

| Run | Tests | Failed | Skipped | Passed | Listed outcome and cause |
|---|---|---|---|---|---|
| default | 160 | 92 | 16 | 52 | all 160 match `expected-outcomes.txt` |
| v3 | 160 | 90 | 16 | 54 | all 160 match `expected-outcomes.txt` |

These match 52/92/16 and 54/90/16 as claimed.

## 5. Shared `ServiceAuditAspect`: sound

**Native paths are unchanged in all three services, with the routes on or off.**
- `getServiceName` branches only on `uri.startsWith("/iceberg/")`. Every other URI goes to `getServiceNameFromRequestURI`.
- The only change in `getServiceNameFromRequestURI` against the base is that the `/iceberg/` clause was removed, which restores main's method exactly.
- So native audit is unchanged for `/jobs`, `/hts`, `/databases`, `/v<n>/databases` and everything else.

**With the routes on, the tables service's `/iceberg` audit is the base's.**
- The catch-all `@RequestMapping(BASE_PATH + "/**")` has no method, consumes or produces condition, so every `/iceberg/**` request gets a handler.
- In 5.3.18, `lookupHandlerMethod` sets `BEST_MATCHING_HANDLER_ATTRIBUTE` before `handleMatch` (bytecode offset 336).
- So the service name is `TABLES_SERVICE`, as on the base.

**With the routes off**, the tables, House Tables and Jobs services all audit `/iceberg/...` as `UNRECOGNIZED`, as main does.

## 6. CI on this head: every job green

**Default build:** test groups, test-group partition, rest, apps-spark-3.5, apps-spark-4.2, spark-3.5-openhouse-spark-itest, spark-4.2-openhouse-spark-itest, Docker images and integration tests, Build and Run Tests: all success.

**Reference build:** test groups, test-group partition, rest, apps-spark-3.5, apps-spark-4.2, spark-3.5-openhouse-spark-itest, spark-4.2-openhouse-spark-itest, Build and Run Tests: all success. Its Docker job is skipped, as by design.

**The off test:** `IcebergRestRoutesOffTest` has 14 PASSED lines across the two `rest` logs, which is 7 per line, and none FAILED.

## Other findings from the first review

- **F2:** closed by the fixtures README sentence.
- **F4:** the field is removed, and the javadoc and `DEPLOY.md` say only `true` turns the routes on.
- **F5:** the author's list covers it.
- **F7:** the `DEPLOY.md` sentence and the javadoc now separate routes on from routes off.
- **F9:** `MeasureRequests` is no longer a `@TestConfiguration`. It is `@Import`ed only by `IrcScanPlanCostTest`, and a class with no annotation is not picked up by scanning.
- **F6:** the commit subject still has "(FF-11)". This is cosmetic and for the squash message. It does not block the merge.

## Not checked

- I did not run the tests myself.
- I did not read the reference line's logs for the kit. The kit runs in the default `rest` job.
