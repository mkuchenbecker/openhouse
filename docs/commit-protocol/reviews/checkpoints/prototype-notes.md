# Prototype checkpoint notes
- [x] worktree created at /home/user/worktrees/rest-proto (branch proto/rest-native, base 0ba4fe1)
- [x] read appendix-d design doc
- [ ] explore codebase
- [ ] implement controller/service/validator
- [ ] tests
- [ ] gradle green
- [ ] push

## Environment findings
- Gradle 7.6.2 needs JDK17; installed openjdk-17, set org.gradle.java.home in ~/.gradle/gradle.properties
- Worktree .git file breaks CopyGitHooksTask -> always run gradle with `-x CopyGitHooksTask`
- Fork jar com.linkedin.iceberg:iceberg-core:1.5.2.17 HAS all rest/* classes (CatalogHandlers, RESTSerializers incl UpdateTableRequest serde, UpdateRequirements, ErrorResponse, LoadTableResponse) - verified by unzip
- e2e H2 idiom: services/tables/src/test/.../e2e/h2 SpringH2Application (explicit @ComponentScan - must add resthandler pkg), PropertyOverrideContextInitializer, MockMvc, HouseTablesH2Repository @Primary (plain JPA save, NO CAS -> store-race simulated via @SpyBean throwing HouseTableConcurrentUpdateException)
- doCommit with no smuggled props: merge skipped, CAS early-return, failIfRetryUpdate metric-only. Stamps openhouse.tableVersion=prior tableLocation.
- HouseTableRepositoryStateUnknownException -> Throwable branch -> checkCommitStatus (refresh probes; row unchanged -> UNKNOWN) -> CommitStateUnknownException. Speed via commit.status-check props if needed.
- Spotless googleJavaFormat 1.7 (spotlessApply), checkstyle, spotbugs.
- Plan: classes in c.l.o.tables.resthandler (scanned by prod app via c.l.o.tables base pkg); tests in e2e.h2.resthandler + internalcatalog ops tests + validator unit tests.

## Progress
- Implemented: resthandler/{IcebergRestCommitController, IcebergRestCommitService, RestUpdateValidator, IcebergRestExceptionHandler, IcebergRestSerde}
- KEY GOTCHA #1: exposing raw ObjectMapper @Bean suppressed Boot Jackson autoconfig -> broke ALL legacy endpoints (kebab-case hijack). Fixed with IcebergRestSerde wrapper component.
- KEY GOTCHA #2: @SpyBean on interface JPA repo can't callRealMethod ("abstract real method") and stubbing leaks; solved with fault-injection hooks (SAVE_FAILURES queue + SAVE_ATTEMPTS counter + default save override) in HouseTablesH2Repository.
- Tests green: RestNativeCommitOperationsTest (6, internalcatalog), RestUpdateValidatorTest (14), IcebergRestCommitControllerTest (15, e2e H2) — full matrix a-h covered.
- SpringH2Application: added resthandler pkg to component scan.
- TODO: full module suites (running), spotlessApply, checkstyle/spotbugs, commit+push, report 07-prototype.md

## DONE
- Full suites + spotbugs green (597 PASSED, exit=0)
- spotless/checkstyle clean on new files
- Pushed: e2141be (feat), 62f78e5 (test) -> claude/openhouse-commit-protocol-cl3xg9
- Report: reports/07-prototype.md
