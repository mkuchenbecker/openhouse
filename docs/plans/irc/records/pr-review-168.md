# Review of pull request 168: one switch for the Iceberg REST base (`cluster.tables.irc.enabled`)

Head reviewed: `90ddc73daa2e80552c70cdd06e640573d753c3e7` (still the head at the time of writing). Base `origin/irc-integration` `81cb4b74`, which is also the merge base. One commit. LinkedIn main: `bb8929e1`. Read-only review. I ran nothing except `scripts/ci/check-private-ids.py` and `scripts/ci/check-iceberg-line-branches.py`, and both pass.

CI at the time of writing. These had finished, all green: Default build "Docker images and integration tests"; Reference build "spark-4.2-openhouse-spark-itest"; Reference build "apps-spark-4.2"; the two "test groups" planners. Still running, with no failures yet: Default build rest, spark-3.5-itest, spark-4.2-itest, apps-spark-3.5 and apps-spark-4.2; Reference build rest, spark-3.5-itest and apps-spark-3.5. So the services/tables suite (in "rest"), the kit and the Spark `/iceberg` suites have not reported on this head.

## 1. Verdict

**MERGE AFTER FIXES**: close F1 first.

What matters most:
1. **Off really is main for `/iceberg/**`.** I established this by reading `bb8929e1` and this head side by side. On both, with the property unset, every method on every path under `/iceberg` has no handler. So no interceptor runs, `NoHandlerFoundException` is raised (`spring.mvc.throw-exception-if-no-handler-found=true`) and `OpenHouseExceptionHandler.handleNoHandlerFoundException` answers: 400 `application/json`, main's five-field body, an empty body for HEAD, and an `UNRECOGNIZED` audit event. The author's pinned expectations are main's, not the branch's. The ledger and FF-11 said "404 through the native interceptor", and that is wrong. The author caught it and measured main instead.
2. **The "without a token" half of the off test sends a token** (F1). `MockMvcBuilderConfig`, a test `MockMvcBuilderCustomizer` in a scanned package, adds a default `Authorization: Bearer <testUser>` header to every request on the autowired `MockMvc`. So all 24 "without a token" requests carry a valid token. That also explains `aMappedNativeRouteWithoutATokenIsAnsweredAsOnMain`, which pins a 404 for a "tokenless" native request, says the cause "was not established", and calls it main's answer. A real main deployment answers a tokenless request with 401 from the token interceptor.
3. **Everything for `/iceberg` is behind the switch, and native behavior is unchanged on or off.** That covers the 11 beans in `src/main` `api.irc`, the 3 in `src/iceberg-1.12` and `TableCommitService`. No filter, advice, interceptor, scheduled task or metric is left unconditional. The IRC advice is package-scoped, so it cannot answer a native error even with the switch on. The interceptor registration with the switch off is byte-for-byte main's. Nothing that main ships as a deployment turns the switch on. Only the published test fixtures jar does (F2), and it did so before this pull request too.

## 2. Findings

### F1. MUST FIX BEFORE MERGE: the off test's "without a token" requests carry a token, and one test pins a wrong-labelled expectation as main's

Certain, from reading.

**Where.** `services/tables/src/test/java/com/linkedin/openhouse/tables/e2e/h2/IcebergRestRoutesOffTest.java`:
- lines 121-131 (`perform`): `if (credentials) { request.header("Authorization", ...) }`, with no removal in the false case;
- lines 177-181: the `credentials` loop;
- lines 196-213 (`aMappedNativeRouteWithoutATokenIsAnsweredAsOnMain`).

**Why the token is always there.** `services/tables/src/test/java/com/linkedin/openhouse/tables/config/MockMvcBuilderConfig.java` (on main too) is a `@Configuration MockMvcBuilderCustomizer` in `com.linkedin.openhouse.tables.config`. `SpringH2Application` scans that package (its `@ComponentScan` list). The class does this:
```
builder.defaultRequest(get("/").header("Authorization", "Bearer " + jwtAccessToken));
```
`MockHttpServletRequestBuilder.merge` copies a default header whenever the request has none of that name. Every `credentials=false` request therefore goes out with testUser's valid token.

**What goes wrong.**
1. The "with and without credentials" axis that the assignment requires, and that the pull request body asserts ("That is why the token makes no difference"), is not tested. The 48 requests are 48 requests with a token. The conclusion still holds by reading: with no handler, no interceptor is in the chain. But the test that is supposed to pin it cannot fail on that axis.
2. `aMappedNativeRouteWithoutATokenIsAnsweredAsOnMain` asserts 404 "User table d1.t1 cannot be found" and `TABLES_SERVICE` for a request it calls tokenless. Its javadoc calls this "answered as on LinkedIn main" and says "why the token interceptor lets the request through in this context was not established". The cause is the default header. On main, a tokenless `GET /v1/databases/d1/tables/t1` reaches `DummyTokenInterceptor.preHandle`, which answers `SC_UNAUTHORIZED` and returns false (`services/common/.../DummyTokenInterceptor.java:50-52`). The pinned expectation describes a request the test does not send. This is the "expectation read off the harness" defect, even though it was run on main's code.

**Smallest fix.** Send the tokenless cases through a second `MockMvc` on the same `WebApplicationContext`, so no new Spring context is created. Build it the way `IrcReadRoutesAuthTest.withoutCredentials()` and `ViewsControllerTest` do:
```
MockMvcBuilders.webAppContextSetup(wac)
    .addDispatcherServletCustomizer(d -> d.setThrowExceptionIfNoHandlerFound(true))
    .build();
```
The customizer is needed: a bare `webAppContextSetup` does not apply Boot's `throw-exception-if-no-handler-found`, and would answer 404 instead of main's 400. Add the context's `CachingRequestBodyFilter` if the audit payload matters. Then:
- keep the `/iceberg/**` expectations for both settings (400, null handler, main's body);
- change the native mapped-route case to expect 401 with an empty body and a non-null handler, which is what main's code answers to a tokenless request, or delete it;
- correct the javadoc.

### F2. SHOULD FIX (orchestrator's decision): the published test fixtures jar now turns `/iceberg` on, for downstream users too

Certain, from reading.

**Where.** `tables-test-fixtures/tables-test-fixtures-iceberg-1.5/src/shared/main/resources/openhouse-tables-test-fixtures.properties:5`, which says `cluster.tables.irc.enabled=true`. It is loaded by `@PropertySource` on `SpringH2TestApplication`. The fixtures project applies `openhouse.maven-publish` (`buildSrc/.../openhouse.tables-test-fixtures-common.gradle:4`, the same on `bb8929e1`), so LinkedIn main publishes this artifact for other people's tests.

**What changes.** A downstream test that embeds `SpringH2TestApplication` from this build gets the Iceberg REST routes, where main's fixtures have none. It is a test artifact, not a deployment default. It does not change anything against the base, where the routes were on with no switch. The author disclosed it in the pull request body. It is "something main ships" all the same.

**Options.** Accept it and say so in `DEPLOY.md` or the fixtures' documentation. Or keep the fixtures default off and have this branch's launchers turn it on: the kit already passes `--cluster.tables.irc.enabled=true`, and the Spark 3.5 and 4.2 launchers and the apps-test launchers would need the same argument.

**Why it can wait.** No deployment is affected, and the state is unchanged against the base.

### F3. SHOULD FIX: no real-container evidence for "off"

Certain, from reading.

**Where.** `.github/actions/default-build-deployables/action.yml:84-91` starts the `oh-only` recipe (the deployment default, property unset) and runs `scripts/python/integration_test.py`, which sends nothing to `/iceberg` (grep: no match). So the off answer is evidenced only by MockMvc, plus the author's local run of the same class on main. Nothing runs it in a servlet container built from the shipped jar.

**Smallest fix.** One step after the wait, roughly:
```
curl -s -w '\n%{http_code}' localhost:8000/iceberg/v1/config
```
Expect 400 and the "cannot be resolved by server" message. A second check with no Authorization header would also cover F1's axis in a real deployment.

**Why it can wait.** Reading and MockMvc agree, and the job is already green on this head.

### F4. MINOR: dead field `ClusterProperties.clusterTablesIrcEnabled` that can disagree with the switch

Certain, from reading.

**Where.** `cluster/configs/src/main/java/com/linkedin/openhouse/cluster/configs/ClusterProperties.java:174-175`:
```
@Value("${" + IRC_ENABLED + ":false}") private boolean clusterTablesIrcEnabled;
```
Nothing reads it (grep).

**What goes wrong.** Spring's string-to-boolean conversion takes `on`, `yes` and `1` as true. `@ConditionalOnProperty(havingValue = "true")` does not. So `cluster.tables.irc.enabled=on`, set in properties, an environment variable or a command-line argument, leaves the routes off while this getter says on. YAML `yes` and `on` reach Spring as `"true"`, so `cluster.yaml` is not affected.

**Fix.** Delete the field, or say "the value `true` only" in its javadoc and in `DEPLOY.md`.

### F5. MINOR: the behavior-change list omits the House Tables and Jobs services

Certain, from reading.

`ServiceAuditAspect` is in `services/common` and is scanned by all three services. For House Tables and Jobs, an `/iceberg/...` request was audited as `TABLES_SERVICE` on the base and is now `UNRECOGNIZED`, as on main: they map nothing there, so the handler attribute is null. This moves toward main, but the pull request body describes the change for the tables service with the routes off only.

### F6. MINOR: the commit subject carries a private planning id

The commit subject is "One switch for the Iceberg REST routes, off by default (FF-11)". The guard script checks files, not commit messages. Strip the id before anything goes upstream.

### F7. MINOR: one `DEPLOY.md` sentence is true only with the base switch on

**Where.** `DEPLOY.md`, section "Iceberg REST scan planning": "This is off by default: the two routes then answer 406, as every route the service does not serve". With the base switch off they answer 400, like every other `/iceberg` path.

The new opening clause ("which must be on") implies the condition but does not state it. The same holds for the scan-planning javadoc in `ClusterProperties.java:181-183`. Suggest: "With the routes on and scan planning off, the two routes answer 406 ...".

### F8. MINOR: the off test's list of IRC-only beans outside `api.irc` is hand-kept

**Where.** `IcebergRestRoutesOffTest.java:84-85`: `ROUTES_ONLY_CLASSES = [TableCommitService]`.

A future IRC-only bean outside `api.irc` that is not mapped (a filter or a scheduled task, say) would not fail the test. A mapped controller anywhere would still fail `nothingIsMappedUnderIceberg`. This is acceptable today: I found no other such bean (section 5).

### F9. MINOR, pre-existing and disclosed by the author: a test filter is scanned into other contexts

`IrcScanPlanCostTest.MeasureRequests` (`services/tables/src/iceberg-1.12/test/.../api/irc/IrcScanPlanCostTest.java:97-119`) is a `@TestConfiguration` in the scanned `api.irc` test package. Its servlet `Filter` therefore joins other default-line contexts, including the off test's. It acts only on URIs ending in `/plan`, which none of the off test's paths do. So it does not affect this pull request's evidence, but the default-line off context is not exactly the production bean set.

## 3. Behavior changes against the base (my table)

| # | What | Base `81cb4b74` | This head, property unset (default) | Property `true` | Toward or away from main |
|---|---|---|---|---|---|
| 1 | Tables service: any method on `/iceberg/**` | Served: IRC routes, the catch-all 406, the IRC interceptor and the IRC error shape | 400 `application/json` from `OpenHouseExceptionHandler.handleNoHandlerFoundException`; no handler, no interceptor; HEAD has an empty body | As base | Toward (identical to main) |
| 2 | Tables service: audit `serviceName` for `/iceberg/...` | `TABLES_SERVICE` | `UNRECOGNIZED` | `TABLES_SERVICE` (the catch-all always sets the handler attribute) | Toward |
| 3 | House Tables and Jobs: audit `serviceName` for `/iceberg/...` | `TABLES_SERVICE` | `UNRECOGNIZED` | `UNRECOGNIZED` | Toward (missing from the author's list, F5) |
| 4 | Token interceptor path patterns | `/**` minus main's five exclusions minus `/iceberg/**`, plus the IRC wrapper | Exactly main's: `/**` minus the five exclusions | As base | Toward |
| 5 | Scan planning on, base off | Plan and credentials served (1.12 line) | Not served; bounds not checked; start-up warning from `IcebergRestIgnoredSettings` | n/a | Toward |
| 6 | IRC beans (14 in `api.irc`, plus `TableCommitService`) | Created | Not created (no instance; only `IcebergRestCatalogController.class` is loaded as a class literal by `TablesMvcConfigurer`, with no static initialization) | Created | Toward |
| 7 | Actuator `/actuator/beans` listing (exposed on main) | Lists the IRC beans | Does not list them; adds `icebergRestIgnoredSettings` only in case 5 | As base | Toward (not listed by the author; trivial) |
| 8 | Published test fixtures' embedded service | `/iceberg` on (no switch) | `/iceberg` on (fixture property) | On | Unchanged against base; away from main (F2; disclosed) |

Differences from the author's list: rows 3 and 7 are missing, both toward main. Row 8 is disclosed in the body but not listed as a behavior change. Nothing in the author's list is wrong.

Native routes: no change on or off against the base, and none against main from this pull request (section 5).

## 4. Tests

**`IcebergRestRoutesOffTest` (new).** It reuses the cached context of `ViewsDualStorageH2IntegrationTest`: same `classes`, `@AutoConfigureMockMvc`, initializers and `@MockBean ViewsFeatureGate`. That is 0 new Spring contexts, and the property is unset (no yaml, properties file or initializer in that context sets it). Test by test:
- **`everyRequestUnderIcebergIsAnsweredAsAPathNothingMaps`:** can fail for the right reason on the routes. Base answers 200 or 406 with a non-null handler. The "without a token" half cannot fail for the token reason (F1).
- **`aNativePathNothingMapsIsAnsweredTheSameWay`:** a control, and sound.
- **`aMappedNativeRouteWithoutATokenIsAnsweredAsOnMain`:** wrong as labelled (F1).
- **`aNativeRoutesErrorIsAnsweredByTheNativeAdvice`:** with the switch off there is no IRC advice, so it cannot fail for the scoping reason. It is a control; the real scoping test is the new `IrcReadRoutesTest` method.
- **`noBeanOfTheIcebergRestRoutesIsCreated`:** fails for any unconditional bean in `api.irc` or `TableCommitService`. Hand-kept list (F8).
- **`nothingIsMappedUnderIceberg`:** enumerates every `RequestMappingInfoHandlerMapping` handler method and every URL handler map pattern. One forgotten controller in `api.irc`, or any controller mapping `/iceberg`, fails it. Good. There is no `RouterFunctionMapping` in this service.
- **`theTokenInterceptorIsRegisteredAsOnMain`:** can fail. Base excluded `/iceberg/**` and added the IRC interceptor.
- The author's run reports 7/7 on main, and 4/7 failing on base for the right reasons. I did not reproduce that.

**`IrcReadRoutesTest.nativeErrorsAreAnsweredByTheNativeAdviceWithTheRoutesOn` (new method, switch on).** It can fail for the right reason: a mis-scoped `HIGHEST_PRECEDENCE` IRC advice would answer the native `HttpMessageNotReadableException` and the no-handler 400 in the IRC shape. Sound.

**`IcebergRestIgnoredSettingsTest` (new).** Registers the class directly, so the class condition is evaluated. It covers unset/false/`TRUE` against true/unset/false. It would fail if the condition were inverted or missing. It checks bean creation, not that the warning is logged (acceptable).

**`IcebergRestScanControllerStartupTest` (changed; 1.12 only).** The planning-on contexts now set the base switch. That expectation change is right, because the route now needs both switches. The new case (planning on, timeout 0, base unset or false) would fail if the scan controller lacked `@ConditionalOnIcebergRest`: the 0 bound would stop the context from starting.

**`IcebergRestRoutesBoundaryTest` (fixtures).** Only the javadoc changed. It distinguishes 406 from 400, so it would fail rather than pass vacuously if the fixtures' switch were lost.

**Vacuous passes.** I looked for IRC suites that might now run with the routes off:
- Every services/tables test that touches `/iceberg`, uses `IrcHttp` or `CommitPathHttp`, or autowires `TableCommitService` includes `PropertyOverrideContextInitializer`. I checked about 80 classes by grep.
- Every Spark 3.5 and 4.2, apps and kit launcher uses `SpringH2TestApplication`, which reads the fixtures' property file.
- No IRC suite has an assumption tied to the server's answer (grep of `assume*` in integrations, apps and fixtures).
- The kit's `expected-outcomes.txt` is unchanged.

**Contexts and heap.** No new `@SpringBootTest` context. 7 plus 1 throwaway `AnnotationConfigApplicationContext`s (no Boot, no database), each closed.

**Claimed and not pinned.** Two claims rest only on reading: that the token is irrelevant under `/iceberg` (F1), and the real-container answer (F3).

## 5. Checked and found sound

**Main's answer, by reading `bb8929e1`:**
- `services/tables/src/main/resources/application.properties` sets `spring.mvc.throw-exception-if-no-handler-found=true` and `static-path-pattern=favicon.ico`. The file is identical on head.
- No native mapping can match `/iceberg/...`. The native patterns are `/v1/databases...`, `/databases...` and, on head, the owner's `/v1/{prefix}/namespaces` (first segment `v1`, so no match).
- Production excludes `SecurityAutoConfiguration` on both (`TablesSpringApplication`, unchanged), so there is no Spring Security filter.
- The only servlet filter is `CachingRequestBodyFilter`, the same on both.
- Spring's `AbstractHandlerMapping.getHandler` returns null without building a chain, so no interceptor runs.
- `OpenHouseExceptionHandler.handleNoHandlerFoundException` is unchanged against main. The branch's other edits to that class (making two helpers static, extracting `unreadableMessage`) do not touch this path.

**Off on this head:**
- Every `api.irc` `@Component`, `@RestController` or `@RestControllerAdvice` carries `@ConditionalOnIcebergRest`. That is 11 in `src/main` and 3 in `src/iceberg-1.12`.
- The non-bean classes in `api.irc` are not beans: `IcebergRestAuthenticationInterceptor` (constructed only in the on branch), `IcebergRestScanPlanner`, `IcebergRestBoundedPlans`, the errors, JSON and spelling helpers, and the annotations.
- `TableCommitService` is called only by `IcebergRestWriteHandler`. `TableAuditAspect`'s pointcut on it is inert without the bean.
- There is no IRC bean in other loaded modules. The only new `@Component` against main outside `services/tables` is `HouseTableRowLimits`, which is native and not IRC.
- With the beans absent, `ServiceAuditAspect.auditFailedRequest` and the `AUDITED_REQUEST_PAYLOAD` attribute are inert.
- Component scanning reads ASM metadata, so IRC classes are not loaded by it.

**Interceptor switch.** `TablesMvcConfigurer.icebergRestRoutesMapped()` uses `getBeanNamesForType(IcebergRestCatalogController.class, false, false)`. It runs during `RequestMappingHandlerMapping` creation, after conditions are evaluated, with no eager initialization. All IRC controllers share the same condition, so the interceptors follow the routes. The off branch's patterns are identical to main's.

**Advice scoping.**
- `IcebergRestExceptionHandler` is `basePackageClasses = IcebergRestCatalogController.class`. `HandlerTypePredicate` with selectors rejects a null handler type and every native controller type. So with the switch on it cannot answer a native error or the no-handler 400, and turning it off does not change which advice answers a native error.
- There is no pre-existing defect here.
- `NamespaceExceptionHandler` is scoped to the owner's controller and is not IRC (BC-044, KEEP-DIRECTED, out of scope).

**Sub-switches.** Scan planning:

| Base switch | Planning | Result |
|---|---|---|
| off | off | nothing |
| off | on | nothing served; warning; bounds not checked |
| on | off | as base: 406, not in the config answer |
| on | on | served |

The warning bean reads both properties with `equalsIgnoreCase("true")`, as `@ConditionalOnProperty` does. "Warn and start" is sound: off must answer like main, and main ignores the property.

Register view needs the base switch plus the 1.12 class; on 1.5.2 there is no controller. Format v3 (`cluster.iceberg.format-v3.*`) is native and not an IRC switch, and its behavior is unchanged.

**Where the property is turned on:**
- `PropertyOverrideContextInitializer` (a `services/common` test fixture);
- the fixtures properties file (F2);
- `RestCompatKitService` (the kit's service source set);
- `IcebergRestScanControllerStartupTest` (test).

No Docker recipe, compose file, Helm value, main `application.properties` or `cluster.yaml` sets it (grep of `infra/` and the whole tree for `irc.enabled`). The Docker job is green with the property unset.

**Documentation and guards.** The `DEPLOY.md` section and the property javadoc are true (except F7). `check-private-ids.py` passes. `check-iceberg-line-branches.py` passes with 92 branches, all listed, and none added. The diff has no private ids in files.

**Both lines.** The new annotation and conditions are in `src/main` (compiled on both lines). The 1.12-only beans are conditioned in `src/iceberg-1.12`. The off test has no line branch, and its context reuse holds on both lines.

## 6. Not checked

- I ran nothing: no Gradle, no test, and no reproduction of the author's run of the off test on `bb8929e1`.
- Behavior in a real servlet container (F3).
- The results of the CI jobs still running at the time of writing: Default and Reference `rest` (the services/tables suite and the kit), and the Spark 3.5 and 4.2 itests and apps jobs listed above. Any failure there is unread.
- The full regression of IRC behavior with the switch on, beyond confirming that the on path's code is identical to the base. That is left to CI.
- The owner's namespace routes (`/v1/{prefix}/namespaces`, BC-044 and BC-045), which are out of scope.
