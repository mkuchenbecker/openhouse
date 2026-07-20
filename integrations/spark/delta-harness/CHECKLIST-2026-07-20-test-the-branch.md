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

## STATUS: step 1 (build feasibility probe).

## NOTES
- Existing colDefault pins (inert-but-silent) characterize the RELEASE 1.5.2.15. Keep the finding as a
  documented release-vs-branch delta, but the PINS must move to branch reality once we build HEAD.
- Watch: building HEAD may change behaviour across MANY cases (not just #251) if the branch is well
  ahead of .15 — each shift is a finding, not a regression to hide.
