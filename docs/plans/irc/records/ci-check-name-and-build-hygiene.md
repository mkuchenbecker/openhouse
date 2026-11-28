# ci-check-name-and-build-hygiene (FF-27, FF-29, FF-30, guard follow-ups)

Bottom line: done after review round. Draft pull request 166, head e5da0456cdbe039ee57cd0db34069c09b09796fe, every CI job green; Jackson alignment withdrawn, both lines' Jackson pinned exactly (service and fixtures); partition check in its own parallel job (plan 8 s / 4 s); aggregate skips cancelled runs (superseded commit shows 'cancelled').

Worktree /home/user/oh-ci-hygiene, branch irc/ci-check-name-and-build-hygiene, base origin/irc-integration 35bd941c.

## Facts gathered
- LinkedIn's ruleset on linkedin/openhouse main (gh api repos/linkedin/openhouse/rules/branches/main, ruleset 433749)
  requires status check context "build-run-tests / Build and Run Tests". The heads of LinkedIn pull requests 921 and
  910 report exactly that one check (besides Renovate on main).
- Fork's gradlew downloads gradle-wrapper.jar from raw.githubusercontent.com/gradle/gradle/v8.14.5 with no checksum.
  SHA-256 of that file: 7d3a4ac4...6172, equal to Gradle's published checksum for 8.14.5 in gradle/actions
  wrapper-checksums.json and to OpenHouse's own committed wrapper jar.

## Progress (2026-11-28)
- Draft pull request 166 opened.
- d75f5238 guard follow-ups (docs/plans skip, dead entry fails, B E G O R letters, H except H2, "(V2;"). Letter counts
  head/main: B 0/0, E 1/1 (E712, allow-listed), G 0/0, H 46/19 all H2, O 0/0, R 0/0, "(V2;" 0/0.
- c7091618 FF-27: aggregate "Build and Run Tests" per line + check-needed-jobs.py self-test; partition checker in plan
  (locally 26 s default, 12 s reference; gap demo fails with "in build, in no job: :services:tables:check").
- bea1eb8a FF-29: wrapper sha, java>=17, boolean line property, lazy missing-fork failure (demos in commit msg).
- FF-30 facts: tables runtimeClasspath default line: core 2.22.2 (conflict resolution, Iceberg 1.12 asks bom 2.22.2),
  databind 2.13.4 (forced), annotations 2.14.2, jdk8 2.13.5, jsr310 2.14.2, xml 2.13.5, yaml 2.14.2, jaxrs 2.13.4,
  parameter-names 2.13.4, bom 2.13.4 (forced). Reference line: identical except core 2.14.3.
- 83fa5bb4 FF-30: tables on default line aligned to Jackson 2.22.2 (annotations 2.22); reference unchanged = main's list
  (verified main tree bb8929e1 resolves exactly the reference list). JacksonRuntimeVersionsTest fails before on default
  (databind 2.13 vs 2.22), passes after on both lines. 150 sampled tables tests pass on default line after.
- Found beyond: housetables and jobs services on default line also run core 2.22.2 with databind 2.13.4; optimizer
  services/apps run core 2.14.2 + databind 2.13.4 (as main). Not changed.
- Pushed head 83fa5bb4ae2517a1d9978364c80efa5dbb0c50c3 to pull request 166; base unchanged at 35bd941c. CI pending.
- CI evidence: bea1eb8a run plan job 140940200277 (cache miss) logged "The fork's gradle-wrapper.jar has the pinned
  SHA-256 7d3a...6172" and "Published Iceberg ..."; it saved the fork cache, which 83fa5bb4's plan restored.
  Superseded runs: aggregate jobs 140940270667 and 140942185865 reported failure with "tests: cancelled, so the line is
  not green" (cancelled path works). 83fa5bb4 plan jobs: default 140942121291 success (self-tests 11/0, guard 0
  failures, "build: 781 tasks, 44 test tasks; partition holds"), reference 140942121510 success (692, 35, holds).
- Pull request body set (full).
- Coordinator: base moved to 67989d16 (pull request 162). Merged as 06682b5e (tables sources/tests only, no conflict);
  guard passes, partition holds (781/44), JacksonRuntimeVersionsTest + IcebergRestViewHandlerCommitAnswerTest pass on
  default line. Pushed 06682b5e7698aadb5416c52bce151e5d86e1dafd; run on 83fa5bb4 superseded (cancelled).
- CI 06682b5e: all green; aggregates 140952373935 (default) and 140949330423 (reference) success; plan 140943512172
  and 140943511960; JacksonRuntimeVersionsTest passed in both rest jobs (140943994847, 140943833565).

## Review round (pr-review-166.md)
- 1ae1657f Jackson alignment withdrawn; exact pins on both lines for :services:tables and the fixtures (equal lists).
- 5edf2778 F5 partition check in own parallel job (no fork needed). Plan: base 8/8 s, 06682b5e 91/54 s, 2810a4cd 8/7 s;
  partition job 57 s beside the groups.
- 2810a4cd F6 aggregate if !cancelled(). Superseded 2810a4cd shows Build and Run Tests "cancelled" (140962434113,
  140962427994), not failure.
- a8fd7676 minors F10 F11 F8 F7 F9. e5da0456 merge of base 35cc7b1e. Pushed e5da0456cdbe039ee57cd0db34069c09b09796fe.
- Title/body updated. CI pending on e5da0456.
- CI e5da0456 all green: aggregates 140970034115, 140967730984 (partition: success listed); plan 8 s / 4 s; partition 63 s / 51 s; Jackson pins passed in both rest jobs; wrapper verified (cache miss) in rest job.
