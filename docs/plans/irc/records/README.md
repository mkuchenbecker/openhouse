# Records: the agents' raw working records

Bottom line: these 92 files (91 markdown records and one build log) are the raw, unedited records written by the
agents that did each piece of work, plus the ten files of the retroactive review of 2026-11-27 (the common brief and
the nine reviewers' reports, `review-*.md`), and the scout's report and the fix-forward work's brief and records of the same day. They were copied byte for byte from the orchestrator's session directory
`/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/reports/`, which does not outlive the
container. The line limit and wording rules of the notes do not apply to them, so they use abbreviations and other
shorthand. Where they and a note disagree, the record and the merge commit message are closer to the source.

- A note that cites `scratchpad/reports/<name>.md`, or that full path, means `records/<name>.md` here.
- A result file in the parent directory (`NN-...-result.md`) is the reviewed summary of one or more records.
- Scan before copying: no personal email address and no credential were found. The hits were prose about bearer
  and test tokens (for example the `token=<bearer>` catalog property) and `noreply@anthropic.com`. Nothing withheld.
- Marks inside records: [ran] I executed it and saw the result; [read] I read it in a file or log; [inferred]
  I concluded it without running it.
- "Pull request" numbers are on `mkuchenbecker/openhouse` unless marked iceberg. They come from
  `../45-merged-pull-requests.md` by work item id, or from the record itself. "not stated" means neither says.
- "(by title)" in the last column means the note's title matches the record's subject; it does not cite it by name.
- The two unmerged pieces of work are `x2-x3-v3-row-level.md` and `small-batch-w13-w14-n9.md`; start there to resume.
- `full-rest-stub.log` is a raw build log (4071 lines) kept with the records; it is not cited by a note.

| Record | Work item ids | Pull request | Result file or note that summarizes it |
| --- | --- | --- | --- |
| `c1-path-differences.md` | C1 | pull request 84 | not stated |
| `c10-spark-image-ci.md` | C10 | pull request 122 | `55-c10-c14-result.md` |
| `c14-generator-jar-download.md` | C14 | pull request 123 | `55-c10-c14-result.md` |
| `c2-3-rest-capability-client.md` | C2-3, N3-x2 | pull request 103 | `38-c2-3-rest-capability-client-result.md` |
| `c2-capabilities-resolver.md` | C2-1, C2-2 | pull request 100 | `34-c2-capabilities-resolver-result.md` |
| `c3-c4-rest-shaped-path.md` | C3, C4 | pull request 146 | `80-c3-c4-result.md` |
| `c5-optin-ci.md` | C5, W12, I10 | pull request 97 | `32-optin-green-ci-result.md` |
| `c6-t9-optin-on-pull-requests.md` | C6, T9 | pull request 111 | `44-v9abc-flip-prep-result.md` |
| `c9-docker-steps-1.12-ci.md` | C9 | pull request 114 | `52-c9-n6-result.md` |
| `capabilities-over-irc-design.md` | C2, V5 (design) | not stated | `28-capabilities-over-irc-design.md` (by title) |
| `ci-post-merge-split.md` | C20 | pull request 144 | `79-c20-x11-x12-x13-result.md` |
| `ci-print-failed-tests-split.md` | CI print fix | pull request 138 | `75-n8-v18-c15-and-ci-fix-result.md` |
| `ci-split-build-job.md` | C16 | pull request 133 | `71-c16-ci-split-result.md` |
| `compaction-partial-progress-409.md` | X16 | pull request 148 | `81-v17-x16-result.md`, `82-v10-x18-result.md` |
| `harness-via-seam.md` | T2b | pull request 86 | `09-seam-result.md` |
| `i6-openhouse-on-1.12-scout.md` | I6 (scout) | not stated | `12-openhouse-on-1.12-scout.md` (by title) |
| `i6-optin-build.md` | I6a to I6c | pull request 87 | `15-optin-build-result.md` (by title) |
| `i6-optin-green.md` | I6g, I6h, W3, W4 | pull request 91 | `25-optin-green-result.md` |
| `i6f-optin-full-regression.md` | I6f | pull request 105 | `40-i6f-full-regression-result.md` |
| `iceberg-1.12-baseline.md` | I1 (baseline) | not stated | not stated |
| `iceberg-combined-validation.md` | I-patches validation | not stated (iceberg pull requests 3 to 8 hold the combined patches) | not stated |
| `iceberg-native-defaults.md` | W1, W2 | iceberg pull request 11 | not stated |
| `iceberg-patch-ledger.md` | patch ledger | not stated | `05-iceberg-patch-ledger.md` (by title) |
| `iceberg-spark42-port.md` | I5 | iceberg pull request 10 | not stated |
| `iceberg-wap-time-travel.md` | I11 | iceberg pull request 12 | not stated |
| `iteration-friction.md` | diagnosis | not stated | `20-iteration-friction.md` (by title) |
| `jobs-batch-x11-x12-x13.md` | X11, X12, X13 | pull request 145 | `79-c20-x11-x12-x13-result.md` |
| `jobs-spark42-scout.md` | N11 | pull request 135 | `70-n11-jobs-spark42-scout-result.md`, `73-n11-jobs-spark42-result.md` |
| `jobs-test-heap-margin.md` | C19 | pull request 143 | `78-v19-v20-v22-n16-x9-result.md` |
| `n16-jobs-catalog-leak.md` | N16, X9 | pull request 141 | `78-v19-v20-v22-n16-x9-result.md` |
| `n1c-n7-spark35-rest-smoke.md` | N1-c, N7 | pull request 128 | `64-n1c-n7-result.md` |
| `n3-spark42-irc-read.md` | N3a | pull request 92 | `26-n3a-spark42-read-result.md` |
| `n3-x1-spark42-extensions.md` | N3-x1, N3-x2 | pull request 101 | `35-n3x1-spark42-extensions-result.md` |
| `n3b-spark42-smoke-suite.md` | N3b | pull request 117 | `50-n3b-spark42-smoke-result.md` |
| `n6-spark42-rename-target.md` | N6 | pull request 119 | `52-c9-n6-result.md` |
| `native-defaults-design.md` | W1 to W11 (design) | not stated | `14-native-defaults-design.md` (by title) |
| `optin-ci-red-2.md` | fix-forward | pull request 108 | `43-optin-ci-red-result.md` |
| `optin-ci-red.md` | fix-forward | pull request 107 | `43-optin-ci-red-result.md` |
| `service-batch-n8-v18-c15.md` | N8, V18, C15 | pull request 139 | `75-n8-v18-c15-and-ci-fix-result.md` |
| `service-batch-v19-v20-v22.md` | V19, V20, V22 | pull request 142 | `78-v19-v20-v22-n16-x9-result.md` |
| `small-batch-w13-w14-n9.md` | W13, W14, N9 | not stated | none (unmerged; cited in `18-state.md`) |
| `small-cleanups-c8-c12-t12.md` | C8, C12, T12 | pull request 136 | not stated |
| `t10-spark35-excluded-tests.md` | T10 | pull request 115 | `49-t10-spark35-tests-result.md` |
| `t6-n10-grants-and-rest-write-checks.md` | T6, N10 | pull request 140 | `77-t6-n10-result.md` |
| `v1-candidate-commit.md` | V1 | pull request 89 | `23-v1-commit-core-result.md` |
| `v1-rule-inventory.md` | V1 inventory | not stated | `24-commit-rule-inventory.md` (by title) |
| `v10-register-view.md` | V10 | pull request 149 | `82-v10-x18-result.md` |
| `v11-v12-investigation.md` | V11, V12 | not stated | `60-v11-v12-investigation-result.md` (by title) |
| `v11a-v12a-plan-credentials.md` | V11a, V12a | pull request 127 | `62-v11a-v12a-result.md` |
| `v11b-v11c-planning-followups.md` | V11b, V11c | pull request 131 | `66-v11b-v11c-result.md` |
| `v11e-plan-v3-and-late-columns.md` | V11e | pull request 132 | `69-v11e-result.md` |
| `v13-rest-compat-kit.md` | V13a | pull request 104 | `39-v13-compat-kit-result.md` |
| `v13b-spec-deviations.md` | V13b | pull request 106 | `42-v13b-spec-deviations-result.md` |
| `v14-create-history.md` | V14 | pull request 134 | `72-v14-create-history-result.md` |
| `v15-v3b-v3c-service-followups.md` | V15, V3b, V3c | pull request 121 | `59-v15-v3b-v3c-result.md` |
| `v16-directory-reuse-guard.md` | V16, N5, T13 | pull request 116 | `48-v16-directory-reuse-result.md` |
| `v17-irc-listing.md` | V17 | pull request 147 | `81-v17-x16-result.md` |
| `v2-defaults-on-1.12.md` | W-defaults on v2 | not stated | `10-v2-defaults.md` (by title) |
| `v2-irc-read.md` | V2 | pull request 88 | `19-v2-irc-read-result.md` |
| `v3-irc-write.md` | V3 | pull request 94 | `27-v3-irc-write-result.md` |
| `v4-irc-views.md` | V4 | pull request 95 | `31-v4-irc-views-result.md` |
| `v5-3-grants-unlock-endpoints.md` | V5-3, V10a | pull request 102 | `36-v5-3-grants-unlock-endpoints-result.md` |
| `v5-policies-over-irc.md` | V5-1, V5-2 | pull request 99 | `33-v5-policies-over-irc-result.md` |
| `v6-v7-metrics-register.md` | V6, V7 | pull request 112 | `46-v6-v7-metrics-register-result.md` |
| `v8-multi-table-commit.md` | V8 | pull request 120 | `53-v8-multi-table-commit-result.md` |
| `v9a-flip-prep.md` | V9a, V9b | pull request 109 | `44-v9abc-flip-prep-result.md` |
| `v9c-move-shared-sources.md` | V9c | pull request 110 | `44-v9abc-flip-prep-result.md` |
| `v9d-deployables.md` | V9d | pull request 113 | `47-v9d-deployables-result.md` |
| `v9e-iceberg-1.12-default.md` | V9e | pull request 118 | `54-v9e-flip-result.md` |
| `view-rename.md` | view rename | not stated | none (stopped before implementing) |
| `w7-x4-v3-default-reads.md` | W7, X4 | pull request 126 | `61-w7-x4-result.md` |
| `x1-format-v3.md` | X1, W6 | pull request 124 | `57-x1-format-v3-result.md` |
| `x10-v3-maintenance-concurrency.md` | X10 (concurrency) | pull request 130 | `65-x10-concurrency-result.md` |
| `x10b-v3-maintenance-branches-evolution.md` | X10 (rest), X15 | pull request 137 | `76-x10-shapes-result.md` |
| `x2-x3-v3-row-level.md` | X2, X3 | not stated | none (unmerged; cited in `18-state.md`) |
| `x5-v3-types.md` | X5 | pull request 129 | `68-x5-v3-types-result.md` |
| `x6-maintenance-v3.md` | X6 | pull request 125 | `63-x6-maintenance-v3-result.md` |
| `full-rest-stub.log` | not stated | not stated | not stated |
| `review-brief-common.md` | retroactive review brief | all 71 merged pull requests (80 to 150) | retroactive review, 2026-11-27 (the brief all nine reviewers read; summarized in `../85-` to `../88-`) |
| `review-irc-commit-write.md` | retroactive review | pull requests 89, 94, 96, 106, 120, 134 | retroactive review, 2026-11-27 |
| `review-irc-read-register-limits.md` | retroactive review | pull requests 88, 112, 116, 121, 139, 142, 147 | retroactive review, 2026-11-27 |
| `review-irc-views-capabilities.md` | retroactive review | pull requests 95, 99, 102 (149 for fit only) | retroactive review, 2026-11-27 |
| `review-client-seam-spark.md` | retroactive review | pull requests 84, 86, 92, 100, 101, 103, 107, 117, 119, 128 | retroactive review, 2026-11-27 |
| `review-format-v3.md` | retroactive review | pull requests 124, 126, 129 (91, 97 and iceberg pull request 11 for the defaults work) | retroactive review, 2026-11-27 |
| `review-scan-planning.md` | retroactive review | pull requests 127, 131, 132 | retroactive review, 2026-11-27 |
| `review-jobs.md` | retroactive review | pull requests 130, 135, 137, 141, 143, 145 (125 excluded) | retroactive review, 2026-11-27 |
| `review-build-deployables.md` | retroactive review | pull requests 87, 91, 97, 98, 105, 109, 110, 113, 114, 118, 122, 123, 136 | retroactive review, 2026-11-27 |
| `review-ci-fixtures-tests.md` | retroactive review | pull requests 80, 81, 82, 83, 85, 90, 93, 104, 108, 111, 115, 133, 138, 140, 144, 146, 148, 150 | retroactive review, 2026-11-27 |
| `fix-brief-common.md` | fix-forward work | the fix-forward pull requests of 2026-11-27 | the common brief every fix-forward agent works under (see `../86-review-findings-and-fix-forward.md`) |
| `scout-namespace-and-prior-work.md` | scout of the owner's namespace line | upstream pull requests 869 to 871 and the earlier drafts on the fork | `../89-scout-namespace-and-prior-work-result.md` |
| `authorize-first-on-rest-commits.md` | FF-01, FF-02, FF-03 | in flight (branch `irc/authorize-first-on-rest-commits`) | `../18-state.md` (in flight) |
| `build-on-owner-namespace-stack.md` | merge of the owner's namespace stack | in flight (branch `irc/build-on-owner-namespace-stack`) | `../18-state.md` (in flight) |
