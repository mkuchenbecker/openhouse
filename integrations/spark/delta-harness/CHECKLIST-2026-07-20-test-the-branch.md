# CHECKLIST — 2026-07-20 — TEST THE BRANCH (not a published snapshot)

New file per task. Re-read first each turn. Parent: PROJECT-PLAN.md overarching goal (test the
`com.linkedin.iceberg` fork) + sub-goal B follow-ups.

## FRAMING CORRECTION (user, 2026-07-20)
"what is your task? it's not to make a specific version work but rather to test the branch. you have
built everything and may need a rebuild, that's an implementation detail."
- Task = test the **branch** `openhouse-1.5.2` (HEAD d1603c807 = #251 column defaults).
- The harness has been resolving the **published** `com.linkedin.iceberg:*:1.5.2.15` from Maven Central,
  which PRE-dates #251 (compiler-proven: NestedField.builder/initialDefault absent) and may lag other
  recent branch commits too (#249/#248/…). So I've been testing a STALE snapshot, not the branch.
- Fix = build the fork from branch HEAD, publish to mavenLocal, point OpenHouse's iceberg dep at it,
  re-resolve + recompile + re-run. Then the tests (esp. colDefault #251) exercise the BRANCH.

## STEPS
0. [x] Persist this checklist.
1. [ ] FEASIBILITY: can the fork build here? (gradle/JDK/proxy). Probe: build+publishToMavenLocal the
       minimal artifact set OpenHouse consumes (api, core, common, bundled-guava, iceberg-spark-runtime-
       3.5_2.12, + parquet/orc/data as needed). If it can't build → STOP, report blocker to user.
2. [ ] Determine the minimal artifact set from oh-cp.txt (which com.linkedin.iceberg jars are actually
       on the harness runtime classpath).
3. [ ] Publish branch HEAD to mavenLocal. Decide version: honest new version (e.g. 1.5.2.99-branchHEAD)
       + bump `iceberg_1_5_version` + add mavenLocal() repo — NOT a silent 1.5.2.15 override.
4. [ ] Re-resolve OpenHouse classpath (FORCE_CP=1) against the branch build; confirm the branch jars
       (with #251 APIs) are on oh-cp.txt.
5. [ ] Recompile harness; smoke fork.colDefault → characterize #251 LIVE behaviour (should now differ:
       default may persist / read-apply). REWRITE the colDefault pins to the branch reality.
6. [ ] Full regression both modes against the branch build; triage any cases that shift vs the
       1.5.2.15 baseline (those shifts are THE findings — branch-vs-release behavioural deltas).
7. [ ] Record run; update PROJECT-PLAN + ICEBERG-FORK-AUDIT (now testing the branch, not the release);
       commit + push.

## STATUS: DONE. Branch STUB 2071/11/0 + branch REAL-HTS 2289/11/0, 0 fail. Recorded + committed + pushed.

---
## APPENDED 2026-07-20 — EXECUTION LOG (do not rewrite above)
- [x] 1 FEASIBILITY: fork builds here. gradle 8.14.3 + JDK17 (`-Dorg.gradle.java.home`), online via proxy
      (offline fails: shipkit/nexus/openapi plugins uncached). Version via `-PciVersion=`.
- [x] 2 MINIMAL SET: harness runtime cp carries only the SHADED `iceberg-spark-runtime-3.5_2.12` (+bundled-
      guava,+aws-1.2). That one shaded jar = all iceberg api+core+spark. So rebuild THAT only.
- [x] 3 BUILD: `:iceberg-spark:iceberg-spark-runtime-3.5_2.12:shadowJar -PciVersion=1.5.2.15-branchHEAD`
      → 42MB jar at /workspace/iceberg/spark/v3.5/spark-runtime/build/libs/. javap-verified to contain
      NestedField.builder/initialDefault/writeDefault (#251). (mavenLocal publish NOT needed — swap the
      jar path directly.)
- [x] 4 SWAP: run-openhouse.sh `ICEBERG_RUNTIME_JAR` hook sed-replaces the runtime jar on the resolved cp.
      Reversible (unset → release). Confirmed "[BRANCH MODE] override entries on cp: 1".
- [x] 5 #251 LIVE characterization (branch mode):
      • Spark-SQL path UNCHANGED vs release — still inert-but-silent (no DDL wiring on the branch either).
      • NEW api/core test `fork.colDefault.apiSerialization @ core` (reflection, runs both modes):
        release → builder ABSENT (pin API unsupported); branch → serializes `"initial-default":5` on an
        UNGATED struct `{"type":"struct",...,"type":"int","initial-default":5}`, round-trips. LIVE hazard.
- [x] 6 FULL REGRESSION both modes vs release baseline (STUB 2070/11/0, REAL-HTS 2286/11/0):
      • branch STUB     = **2071 passed / 11 skip / 0 fail** (2082).
      • branch REAL-HTS = **2289 passed / 11 skip / 0 fail** (2300). ZERO correctness deltas either mode.
- [x] 7 record (VERIFIED-RUN-openhouse.txt top block) + docs (ICEBERG-FORK-AUDIT) + commit + push.

### FINDING (branch-vs-release)
Running the WHOLE harness against branch HEAD shifts NOTHING vs the published 1.5.2.15 — 0 failures, 0
ORC↔Parquet divergence, every correctness assertion identical. The post-release branch commits (#251
col-defaults, #249 partitioned-dist=NONE, #248 avro bump, …) introduce no correctness regression on any
of the ~2000 tested behaviours. The ONLY new live surface is #251 at api/core, now pinned. #251 remains
customer-unreachable (no Spark wiring) — hazard is latent-but-serializable, not customer-triggerable.

## NOTES
- Existing colDefault pins (inert-but-silent) characterize the RELEASE 1.5.2.15. Keep the finding as a
  documented release-vs-branch delta, but the PINS must move to branch reality once we build HEAD.
- Watch: building HEAD may change behaviour across MANY cases (not just #251) if the branch is well
  ahead of .15 — each shift is a finding, not a regression to hide.
