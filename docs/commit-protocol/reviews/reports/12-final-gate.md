# Final verification gate — docs/commit-protocol/ at 2a9dac8 (HEAD f846ffe)

Environment check: `2a9dac8` is in history; every commit after it is additive. The only pre-existing
files the newer commits touched are `services/tables/src/test/java/com/linkedin/openhouse/tables/e2e/h2/HouseTablesH2Repository.java`
(+35 lines inserted mid-file) and `SpringH2Application.java` (+1 line). Neither file is cited anywhere
in the doc set, so no cited line number was moved by the newer commits. Verification below is against
the current tree, which equals `2a9dac8` for every cited file.

---

## Job 1 — writing-review of README.md

Genre: documentation file (synthesis/entry-point report), all structure rules bind; not a design doc,
so DESIGN-DOCS.md was not applied. Criteria: local STRUCTURE.md and humanizer SKILL.md v2.11.2 (both
readable; no degraded mode). Two full passes (structure, then sentences), merged.

**Verdict: pass — 0 blockers, 2 suggestions, 2 nits.** Structure is sound: the conclusion leads as a
disputable claim ("sound at its core and sharp at its edges"), the four conclusions are numbered and
referenceable, layers run TLDR → conclusions → reading guide → verification with no new conclusions
downstream, and every quantitative claim except the two below matches the linked documents (29/9
findings, ~55% blind reproduction with 100% at top tier, 15–25 eng-weeks, the 35-test count = 15 + 14 + 6
`@Test` methods, fix sha `9407819`, both puml diagrams, Appendix E §8 repro commands, the resthandler
prototype directory).

### Findings

| # | location | principle | claim | evidence | failure scenario | severity | confidence | reviewer |
|---|---|---|---|---|---|---|---|---|
| 1 | README.md:15-19 (conclusion 2) | Layering, rule 7 (no layer introduces conclusions its lower layer does not support); none (internal consistency) | The README states Appendix E's results at a strength Appendix E explicitly disclaims | "proves the invariant holds with it — and predicts the CAS-exempt rename path as the next counterexample of the same class" vs Appendix E §6.5 ("TLC verifies these bounds only... no TLAPS proof attempted") and §7 (rename modeling is a *recommendation* that "would likely find a lost-update counterexample"; no rename configuration or TLC run exists in tla/) | A reader cites the model as having proven the fix or produced a rename counterexample; when asked for the trace or proof, neither exists, discounting the verification results that are real | suggestion | confirmed | writing-review |
| 2 | README.md:57 (reading-guide row `services/tables/.../resthandler/`) | none (internal consistency) | The row groups all three test classes under the services/tables resthandler path, but `RestNativeCommitOperationsTest` lives at `iceberg/openhouse/internalcatalog/src/test/java/com/linkedin/openhouse/internal/catalog/RestNativeCommitOperationsTest.java` | Row text names the three classes after the `services/tables/.../resthandler/` path; only `IcebergRestCommitControllerTest` (e2e/h2) and `RestUpdateValidatorTest` are under services/tables | A reader looking for the 35-test matrix under services/tables finds 29 tests and concludes the count is wrong | suggestion | confirmed | writing-review |
| 3 | README.md:37 ("A working prototype of that commit path ships on this branch") | Rule 8 (state the present; history/session state to the ledger); stands on its own (rule 4) | Branch-relative deixis goes stale on merge and assumes the reader knows which branch they hold | quoted phrase; the path that follows it is the durable pointer | A post-merge reader cannot tell whether "this branch" describes their checkout | nit | confirmed | writing-review |
| 4 | README.md:45-57 (Reading guide table) | Rule 2 (a table comes with the sentence stating what it decides) | The table has no sentence above it stating what it shows or which row matters | The section is heading → table; mitigation: the first row itself carries "the primary document" as a marked-row ranking | A skimming reader does not learn where to start until parsing the whole table | nit | probable | writing-review |

### Considered and cleared (false-positive guardrails applied)

Em dashes and the title-case H1 are uniform house style across the whole doc set (guardrails: dashes
and polish alone are not evidence). No bold-label bullet lists (the four bolded lead sentences are
complete topic sentences on numbered prose items, the correct form for items needing paragraphs of
argument). No stock-word pileups, hedge stacks, generic endings, prompt echo, or heading-restating
first sentences. "Analysis, Verification, and a Path Forward" maps to real parts of the set, not a
forced triad.

---

## Job 2 — pointer re-verification sweep

Method: every §2/§3 citation in protocol.md; 5+ spot-checks elsewhere in protocol.md; appendix-a's
sha, CAS call-site/impl, test name/line, timeline, and key-references block; the 9 blocking findings'
primary pointers in appendix-b; appendix-e's tla/ inventory, log conclusions, and code refs. Line
identity confirmed by printing the cited ranges (grep/sed) and, for git claims, `git log`/`git show`.

**Totals: 84 citations checked → 81 verified, 3 drifted.**

### Drifted (correct values)

| Citation | Where | Status | Correct value |
|---|---|---|---|
| `MetadataUpdateUtils.java:37-59` | protocol.md §4 row S6 | drifted (range) | `updateMetadataField` spans **36-57**; the truncating `fs.create(new Path(hdfsPath), true)` is at **45**. Appendix-b's own cite (36-57, line 45) is exact — protocol.md's is the stray. |
| `ITOTest:659-685` (`testDoCommitExceptionHandling`) | appendix-b finding 8 | drifted (range) | `@Test` at **654**, decl at **655**, method ends ~**682**; cited range starts inside the method and runs into the next test's javadoc |
| "report 01 §4/§5", "report 02", "smell #2/#4/#5", "window S4b", "the task statement" | appendix-e §§1,2,5,6,7 | drifted (dangling labels) | These are production-artifact names that resolve to nothing in the published set. Intended referents exist under other names — "report 01" ≈ protocol.md (which has **no numbered smells** and defines **S4**, not "S4b"), "report 02" ≈ appendix-a (its Residual gaps #2 is the location-reuse assumption), the "10 pre-flagged smells" are named only in appendix-b's convergence note. Readers cannot follow any of these seven labels. |

### Verified — protocol.md §2 (7/7)

| Citation | Claim | Status |
|---|---|---|
| `UserTableRow.java:28` | `@Version Long version` | verified (exact) |
| `OpenHouseInternalTableOperations.java:258` | `int version = currentVersion() + 1` | verified (exact) |
| `OpenHouseTableOperations.java:208-209` | client stamps `baseTableVersion` from its base | verified (exact: `setBaseTableVersion(` 208, ternary 209) |
| `OpenHouseTableOperations.java:369-370` | snapshots-path stamp | verified (exact) |
| `OpenHouseInternalRepositoryImpl.java:451-475` | `versionCheck`, scheme-normalized | verified (exact, incl. `getSchemeLessPath`) |
| `OpenHouseInternalTableOperations.java:604-635` | `abortIfWriterBaseDivergedFromCatalog`, URI-normalized, #612 | verified (exact) |
| `UserTableVersionMapper.java:20-47` | `toVersion`, raw string compare, carries `@Version` | verified (exact; raw `.equals` at 34) |

### Verified — protocol.md §3 (21/21)

| Citation | Status |
|---|---|
| `OpenHouseTableOperations.java:142-169` (doCommit routing) | verified (exact) |
| `OpenHouseTableOperations.java:364-391` (full snapshot list + refs body) | verified |
| `OpenHouseTableOperations.java:97-128` (doRefresh; client never writes metadata.json) | verified |
| `OpenHouseTableOperations.java:418-464` (status→exception map; 409/5xx/404/400 rows) | verified (method ends ~465; all four mappings present) |
| `IcebergSnapshotsController.java:41-66` | verified (endpoint block 41→~66; `/iceberg/v2/snapshots` routes) |
| `IcebergSnapshotsServiceImpl.java:36-110` | verified (decl 36, method end 110) |
| `OpenHouseInternalRepositoryImpl.java:111-226` (save) | verified (annotated method 110-226; decl 114, end 226) |
| `OpenHouseInternalRepositoryImpl.java:187-196` (staging as properties; `commitKey`) | verified (exact; COMMIT_KEY at 196) |
| `OpenHouseInternalRepositoryImpl.java:201-207` (`commit.num-retries` forced "0") | verified (exact; `overrideProperty(..., COMMIT_NUM_RETRIES, "0")` at 206-207) |
| `OpenHouseInternalTableOperations.java:250-489` (doCommit) | verified (annotations 250-252, decl 253, end 489) |
| `:686-718` processSchemas | verified (exact) |
| `:191-201` `%05d-%s` + UUID path | verified (exact) |
| `:642-664` failIfRetryUpdate (5-min/1000 Guava cache at 93-94; burn-before-commit at 654) | verified (exact) |
| `:274-278` tableVersion←old, tableLocation←new | verified (exact) |
| `:356-383` `TableMetadataParser.write` + cache seed (367) | verified |
| `:401-411` HTS save branch | verified (save at 404) |
| `:412-419` staged tables skip HTS | verified (exact) |
| `:424-476` failure translation (IOException 424-437; ValidationException remap 440-445; Throwable/checkCommitStatus 452-476) | verified |
| `:670-675` isStaleSnapshotError message-match | verified (exact) |
| `UserTablesServiceImpl.java:98-127` putUserTable; optimistic-lock exceptions → EntityConcurrentModificationException | verified (exact; save at 111, catch at 112-114) |
| `HouseTableRepositoryImpl.java:58-61` writes never retried | verified (the "no retries on table write operations" comment at 60) |

### Verified — protocol.md spot-checks elsewhere (4/5; 1 drifted above)

| Citation | Status |
|---|---|
| `UserTableHtsJdbcRepository.java:115-125` (JPQL rename, no version predicate/bump) | verified (exact) |
| `OpenHouseInternalCatalog.java:157-192` (drop: row then purge) | verified (exact) |
| `OpenHouseInternalTableOperations.java:314-354` (subtractive merge; removeSnapshots) | verified (exact) |
| `SnapshotsUtil.java:45-47` (terminal `parse` never uses its `FileIO`) | verified (exact) |

### Verified — appendix-a (12/12; one tolerance note)

| Item | Status |
|---|---|
| Fix sha `940781958e20c40a5764cda147df9b7613ed2133`, subject `fix(catalog): abort doCommit on stale-base divergence (#612)`, 2026-05-29, Mike Kuchenbecker | verified via `git log` (exact) |
| Fix parent is rollback `d4fc9fe` (fix applied to reverted tree) | verified (`%P`) |
| Fix diff: +52 main, +102 test lines | verified (matches "+102 lines" claim) |
| CAS call site `ITO:269`; impl `ITO:604-635`; ordering javadoc `:598-599` | verified (exact) |
| Test `testDoCommitMustAbortStaleBaseRebaseToPreventSnapshotLoss` at `ITOTest:258`; asserts "Cannot commit" + `never().save`; javadoc names incident-12185 and snapshot 3635817277608242413 | verified (exact) |
| Pre-fix `9407819^` try-block "lines 259-262" (no CAS; failIfRetryUpdate only) | verified (quote's last line is 263; substance exact) |
| Pre-fix merge "~305-345" | verified (starts 305) |
| Timeline `c9ccbdd` #509 2026-05-15 / `d4fc9fe` #619 2026-05-29 / `702a043` #625 2026-06-01 / `3faac06` #640 2026-06-29 | verified (all shas, dates, subjects) |
| `CatalogConstants.java:29` `COMMIT_KEY = "commitKey"` | verified (exact) |
| `RepoImpl:179/181/187/196/216` staging walk | verified (exact) |
| Scheme-less HTS comment `doCommit` line 261 | verified (exact) |
| Dedup `CACHE` at `ITO:93` / getIfPresent 648 | verified (exact) |

### Verified — appendix-b blocking findings 1-9 primary pointers (all verified; F8 drift above)

| Finding | Pointers checked | Status |
|---|---|---|
| 1 | ITO:424-437, 421, 804-821, 404, 425, 428, 429-431; HTRImpl:320-322 (`delete` throws UnsupportedOperationException, not among caught types) | all verified (exact) |
| 2 | ITO:420-422, 367; MetadataUpdateUtils.java:36-57 with `fs.create(...,true)` at 45; ITOTest:376-509 replicated-create happy-path tests | all verified |
| 3 | HtsJdbc:115-125; ITO:386-400; HTRImpl:239-254; HtsSvc:140-167 (existsById at 147); ITO:610-616 CAS skip; OpenHouseInternalCatalog.java:212-244 renameTable | all verified |
| 4 | ITO:704-715 (catch(Exception) only logs inside stream lambda) | verified (exact) |
| 5 | ITO:93-94, 642-664, put at 654, runs at 271; `COMMIT_KEY` in test sources only at ITOTest:301 (at 2a9dac8) | all verified |
| 6 | ClientOps:146-155 (no final else), 171-180, 343-349 | all verified (exact) |
| 7 | ITOTest:257-325; zero `isConflict` in SnapshotsControllerTest; no `*ConcurrentInsert*` test in repo; RepositoryTest exists | all verified |
| 8 | ITO:452-476 verified; ITOTest:659-685 **drifted** (see table above) | 1 drift |
| 9 | ClientOps:208-209, 369-376, 411-416 (`replaceCommit(true)` at 414); DoCommitTest testMetadataWithDataChange at 276 ending in assertDoesNotThrow at 297 | all verified |

### Verified — appendix-e (11/11 artifacts/results; label drift above)

| Item | Status |
|---|---|
| tla/ inventory: OpenHouseCommit.tla, prefix/postfix/prefix_large/postfix_large/prefix_sharedcache .cfg, five tlc-*.log; tla2tools.jar correctly noted as not committed | verified |
| `DivergenceCheck` FALSE in prefix*, TRUE in postfix* cfgs; guards in spec at tla:132/146/168 | verified |
| tlc-prefix.log: "Invariant NoSnapshotLoss is violated", 223 generated / 103 distinct, depth 11 | verified (exact) |
| tlc-postfix.log: "No error has been found", 221 / 99, depth 11 | verified (exact) |
| tlc-postfix-large.log: no error, 11,536 / 4,197, depth 20 | verified (exact) |
| tlc-prefix-large.log: NoSnapshotLoss violated (3 writers / 6 commits) | verified |
| tlc-prefix-sharedcache.log: no error, 105 / 45 | verified (exact) |
| Invariants `NoSnapshotLoss == everCommitted ⊆ catSnaps`, `MonotonicVersion` in spec | verified |
| Code refs: UserTablesServiceImpl.java:111; REPO:196/187/451-475; ITO:269, 604-635, 642-664, 648-654, 93-94 | verified (exact) |
| §8 repro commands reference existing cfg/spec files | verified |
| README's dependent claims (per-JVM cache serialization §5; single-instance masking) | verified against sharedcache log |

### Sweep summary

- Checked: **84** — verified: **81** — drifted: **3** (protocol.md `MetadataUpdateUtils.java:37-59` → 36-57; appendix-b F8 `ITOTest:659-685` → 654-682; appendix-e's dangling "report 01/02 / smell #N / S4b / task statement" labels).
- No cited file was touched by the commits after `2a9dac8`; the two pre-existing files those commits did touch (H2 test fixtures) are cited nowhere in the set.
- Every git sha named in the set (`9407819`, `c9ccbdd`, `d4fc9fe`, `702a043`, `3faac06`, `2a9dac8`) exists with the claimed date, subject, and parent structure.
