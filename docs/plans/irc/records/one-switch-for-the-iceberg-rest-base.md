# One switch for the Iceberg REST base

Bottom line: in progress. Worktree `/home/user/oh-irc-switch`, branch `irc/one-switch-for-the-iceberg-rest-base`,
base `origin/irc-integration` `81cb4b74`.

## Steps
1. Read brief, FF item, ledger row (it cites RR BC8 and BC9: the audit service name for `/iceberg` URIs is in scope).
2. Inventory (read):
   - beans in `api.irc`: main source set: IcebergRestApiHandler, IcebergRestCatalogController, IcebergRestExceptionHandler
     (advice, `basePackageClasses` scoped), IcebergRestNamespaceHandler, IcebergRestOpenHouseController,
     IcebergRestServedRoutes, IcebergRestTableFileIo, IcebergRestViewController, IcebergRestViewHandler,
     IcebergRestWriteController, IcebergRestWriteHandler. 1.12: IcebergRestRegisterViewController (class present),
     IcebergRestScanController (class present + scan planning), IcebergRestViewRegistration.
   - outside: `services/TableCommitService` (@Component, only the IRC write handler calls it), `TablesMvcConfigurer`
     (native interceptor excludes `/iceberg/**`, IRC wrapper interceptor), `ServiceAuditAspect` (service name
     TABLES_SERVICE for `/iceberg/` URIs; main UNRECOGNIZED), `TableAuditAspect` pointcuts (by name; inert without beans).
   - not IRC: `controller/IcebergNamespacesController` (`/v1/{prefix}/namespaces`, BC-044/045 KEEP-DIRECTED): left.
   - main's answer by reading: `spring.mvc.throw-exception-if-no-handler-found=true`, static path `favicon.ico`, so
     `/iceberg/**` has no handler: NoHandlerFoundException -> OpenHouseExceptionHandler 400 "cannot be resolved by
     server"; interceptors never run without a handler. To be confirmed by test on main.
3. Main's answer, RUN on a detached worktree of bb8929e1 (`/home/user/oh-irc-switch-main`, probe test
   copied in, MockMvc, context of ViewsDualStorageH2IntegrationTest): every GET/HEAD/POST/DELETE on
   /iceberg/v1/config, /iceberg/v1/namespaces, /iceberg/v1/openhouse/namespaces, .../namespaces/db1/tables/t1 (both),
   /iceberg/x, with and without a token: 400, application/json, handler null, NoHandlerFoundException,
   body {status BAD_REQUEST, error "Bad Request", message "The combination of the method [M] and Path [uri=P]
   cannot be resolved by server. Please check ...", stacktrace null, cause "Not Available"} (HEAD: empty body),
   one service audit event UNRECOGNIZED/400. Same for /v1/nothing. The ledger's "404 through the native
   interceptor" is wrong: interceptors never run without a handler.
   Probe output: scratchpad/probe/main.txt.
4. Change written: ClusterProperties.IRC_ENABLED, @ConditionalOnIcebergRest on 14 IRC beans + TableCommitService,
   TablesMvcConfigurer follows bean definitions, ServiceAuditAspect /iceberg = TABLES_SERVICE only with a mapped
   handler, IcebergRestIgnoredSettings warning, fixtures properties file, PropertyOverrideContextInitializer on,
   kit explicit on, DEPLOY.md.
5. Off test: main bb8929e1 7/7 pass; base 81cb4b74 4/7 fail (right reasons); branch 7/7 both lines.
   Touched tests: default 103 pass, LinkedIn line 84 pass. Commit 90ddc73d pushed. Draft pull request 168.
   Waiting: fixture boundary test (both lines), CI.
6. Review of 168 (MERGE AFTER FIXES). Commits: c5132efb F1 (tokenless MockMvc; main 7/7, both lines 7/7;
   tokenless /iceberg = no-handler 400; tokenless native = 401 empty), deef5265 F2 README, 212b2f56 F3 Docker
   check, d3e36cd9 F4 field removed, 15cec57e F7 406 wording, 4c715a0c F9 MeasureRequests not scanned
   (IrcScanPlanCostTest 2/2), 0ea38e9f F8 derived by class-file scan (main 7/7, both lines 7/7). Pushed 0ea38e9f.
   Waiting for 166 and 167 to merge, then merge base (kit args: keep 167 structure + irc on in both runs).
7. Merged irc-integration 0b3cafb6 as 7e7a607d (hook dropped merge state; commit-tree with both parents).
   Kit conflict resolved: 167 structure + irc on in both runs. Kit local: default 160/52/92/16, v3 160/54/90/16,
   both "all 160 tests have the outcome and cause listed". Guards pass. Pushed 7e7a607d; waiting CI.
