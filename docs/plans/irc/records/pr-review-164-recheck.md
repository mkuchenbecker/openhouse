# Re-check of pull request 164 at head `f4ae7353`

- Head: `f4ae73532dc938c14cf7cd633387e0cec258f117`.
- Base: `origin/irc-integration` at `a0239ad7`, which is also the merge base.
- New commits since `d57bb536`: `0145cfcb`, `9b1d5daa`, `d7e59c7c`, `bc95534a`, and the merge `f4ae7353`, whose
  parents are `bc95534a` and `a0239ad7`.
- Read-only. I ran the guard, its `--self-test`, my own scanners and `gh api` GETs.

**Verdict: MERGE.** No finding is still open that blocks the merge. Three minor notes can follow (section 6).

## 1. F1 and F2: closed

**F1.** `CommitPathUpdateRulesTest.java:38-42` now says the rules are "pinned at the HTTP boundary so that the shared
commit core (`TableCommitCore`) keeps them".
- That is true: this class pins update-commit rules through MockMvc.
- No id is left.

**My own searches over the merged tree** (all tracked files, outside the delta harness and the guard's own two files):
- Multi-line search: any of work, decision, pull, review, fix-forward, item, since or until at a line end, followed on
  the next comment line by an id, "item(s)", "request N" or "#N". **0 hits.**
- Single-line scan of every line added since `bb8929e1` (this now includes pull request 163's lines), with wider
  letter sets than the guard uses. The only hits left are:
  - false positives: H2, S3, V1 to V3 as versions, the snapshot placeholders `S1-1` in the golden JSON, "the plan",
    and "authorization decision a route makes";
  - the N1, N4, N6 and N8 row values, which are allow-listed.
- Docs and `*.md` outside the harness: no letter-plus-number id.

**F2.** All 14 lines of `docs/development/iceberg-fork.md` are rewritten without ids. I checked five of them against
`/home/user/openhouse/docs/plans/irc/`:

| Line was | Now says | Planning note | Faithful? |
|---|---|---|---|
| D3 | "the shared server modules need Java 17 and cannot also serve that line" | D3 "Why: the shared server modules cannot serve both lines once they need Java 17" | Yes |
| D7 | "built from a pinned fork commit on the build machine" | D7: pinned source build into a build-local Maven directory, published nowhere | Yes |
| I6d | "for the repository owner to decide" | I6d is the hosting decision put to the owner | Yes |
| D31, first two items | "the rows read are the same"; the default "silently dropped" | D31 says exactly these | Yes |
| D31, time-travel paragraph | "would make a working read fail for a user who stages writes ... Spark 4.2, which needed no change" | D31's reason for not accepting it, and its Spark 4.2 note | Yes |

- D8, D14a and D14b now cite only the apache/iceberg issue.
- D5 and D11a became "native Iceberg default values" (D5's title).
- D33 was dropped next to `pr-validations.yml`; the sentence still holds.

## 2. F8: the reworded refusal

- Old text: "... for format v2 tables only. A table that uses it stays at format version 2, since OpenHouse does not
  translate its stored defaults to native ones; a new format v3 table does not set it."
- New text (`FormatVersionCommitPolicy.java:159-164`): "... for format v2 tables only, and OpenHouse does not translate
  a table's stored defaults to native ones. Keep a table that sets it at format version 2, or create the format v3
  table without the property."
- What is unchanged: the `CommitRejectedException`, the code `OPT_IN_ON_V3` / `COLUMN_DEFAULTS_OPT_IN_ON_V3`, and the
  trigger `isTrue(...COLUMN_DEFAULTS_CAPABILITY_PROP)` on a commit that leaves v3. `git diff -w` shows only the
  string literal.
- Both pieces of advice work:
  - The check runs only when the property is true on a v3 result, so a v3 create without it is not refused here.
  - Per the `FormatVersionRule` javadoc (lines 17-53), a table becomes v3 only when a create, replace or update asks
    for it, and the version is never raised otherwise. So a v2 table that sets the property stays v2 and is not
    refused.
- Pinned in one place, `FormatV3NativeApiTest.java:815-821`, which was updated to the new sentence. Nothing else in the
  tree pins the old text.

## 3. The hardened guard

**Runs:**
- On the head: passes, in 2.8 s.
- `--self-test`: 0 failures. It has 11 positive and 9 negative examples, including the wrapped "work / item V1" case.
- On the base `a0239ad7` with the same allow-list: refuses 481 lines. So it would have failed before this branch's
  cleanup, which shows it can fail.

**LinkedIn main (`bb8929e1`):**
- 25 matches with the new rules (including wrapped lines). All are in the allow-list, so there are 0 false failures.
- The upstream entries (`PR #612`, `#569`, `#3046`, `#812`, linkedin/iceberg `#229` and `#254`, `#264`, `#267`) are
  real upstream references. They are pinned by exact line, or by `*` per file for `#264` and the F/x86 tokens.

**Brittleness of exact-line entries: acceptable.**
- If an upstream sync edits one of those lines, the plan job fails. The failure names the file, line and token, and
  says how to allow it.
- The guard runs only in this fork's CI, and the cost is one allow-list line.
- It cannot change behavior, and it does not hide a new id.

**"Rename pending" entries cannot hide a new id.**
- Each entry is `(file, lower-case token, *)`, for example `FormatV3MaintenanceTest | x6 | *`.
- The `*` passes only that exact lower-case test-data token in that file.
- A new "X6", "(work item X6)", "D7" or "#150" in the same file is a different token, and is still refused.

**Bypasses still left (minor, by design or decision):**
- Ids inside identifiers (`refusedNamingX5`) are not detected. This is listed as left by your decision.
- The letter set still omits B, E, G, H, O and R. G and H appear in the planning list only as harness ids or as H2.
- "(V2;" (an opening parenthesis without the closing one) is not counted. The `CITING` words partly cover this.

**Dead entry:** the `integrations/spark/spark-3.1/.../ColumnDefaultSparkITest.java | #267` entry matches nothing on the
head. That module is deleted, and the CI log prints it as a "note". It is harmless; drop it or keep it for `bb8929e1`.

## 4. The merge

- Files changed between `a0239ad7` and the head: 132. Files changed by the branch's own commits
  (`4b9d152d..bc95534a`): 132. They are the same set.
- So every file of pull request 163 that the branch did not touch is byte-identical to `origin/irc-integration`.
- The 126 branch-only files are blob-identical to `bc95534a`.
- Six files were changed by both sides: `services/tables/build.gradle` and five service tests (`IrcRegisterViewRouteTest`,
  `CommitPathAuthorizationTest`, `IrcDirectoryReuseRoutesTest`, `IrcHouseTableReadsTest`, `IrcRegisterRouteTest`).
  In these, the diff from `a0239ad7` to the head touches comment lines only, so pull request 163's content is kept.
- New commits: `git diff -w d57bb536 bc95534a` outside `scripts/ci` and `docs` changes non-comment lines only in:
  - the F8 string;
  - its test assertion;
  - the split-build step, which now runs `--self-test` and then the check.

## 5. CI on `f4ae7353`

- All jobs completed successfully:
  - Default: rest, apps-spark-3.5, apps-spark-4.2, both itests, test groups, and Docker images and integration tests
    (where the FF-49 `unzip` step runs).
  - Reference: rest, both apps, both itests, test groups.
- The reference Docker job and the wrapper jobs were skipped, as is normal.
- The plan job "Default build, test groups" ran the step "Check for private planning ids", which succeeded. Its log
  shows "self-test: 0 failure(s)", the dead-entry note, and "No private planning ids outside
  scripts/ci/private-ids-allowed.txt".

## 6. Open items (can follow, none blocking)

1. Remove the dead allow-list entry for the deleted `spark-3.1` `ColumnDefaultSparkITest` (`#267`). Minor.
2. Identifier and test-data names that encode ids are listed with proposed names, by your decision. That is not a
   finding.
3. The guard's remaining blind spots (identifiers, the letters B, E, G, H, O and R, "(V2;"). Minor; they can follow.

Delta harness: left by your decision. Not re-examined.
