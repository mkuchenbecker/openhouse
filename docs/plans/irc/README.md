# Iceberg REST Catalog and v3 for OpenHouse: planning notes

Bottom line: these notes plan the work. Nothing is implemented. Only `01-stated.md` carries Mike's
authority. Everything else is Claude's observation or judgement and is not agreed.

Each file is under 150 lines so it can be reviewed in one sitting.

| File | What it is | Whose voice |
| --- | --- | --- |
| `18-state.md` | Start here. Current state: how far from the goal, what is known red, what is running, and how to audit | Claude |
| `45-merged-pull-requests.md` | The tables of merged pull requests on both integration branches, with merge commits | Claude |
| `01-stated.md` | What Mike said | Mike |
| `02-facts.md` | What was checked in the repos, with what was not verified | Observation |
| `03-inferred.md` | Claude's judgements and three questions for Mike | Claude |
| `04-work.md` | The list of work and dependencies | Claude |
| `06-smoke-suite.md` | Proposed Spark SQL smoke scenarios | Claude |
| `08-findings.md` | What the first round of work found | Observation by delegated agents |
| `09-seam-result.md` | What the plugin seam does and where the two paths still differ | Observation by a delegated agent |
| `10-v2-defaults.md` | Whether column defaults on v2 tables survive the 1.12 line | Observation by a delegated agent |
| `11-harness-via-seam.md` | Delta-harness counts through the REST-shaped path, and the differences it showed | Observation by a delegated agent |
| `12-openhouse-on-1.12-scout.md` | Local scouting of OpenHouse on the fork's Iceberg 1.12 line: what compiles, what fails, and how the fork could be resolved | Observation by a delegated agent |
| `13-decisions.md` | Decisions Claude made on two-way doors, each with reason, references and how to reverse it (D7 carries a dated note on where the fork now lives) | Claude, not agreed unless it cites a statement |
| `14-native-defaults-design.md` | Design for native column defaults on the new Iceberg line, with work items W1 to W11 | Observation by a delegated agent, adopted with changes in D11 |
| `15-optin-build-result.md` | Result of building OpenHouse's Iceberg 1.5 variant against the fork's 1.12 line behind an opt-in switch | Observation by a delegated agent |
| `16-decisions-continued.md` | Decisions D13 to D21, continuing `13-decisions.md` | Claude, not agreed unless it cites a statement |
| `19-v2-irc-read-result.md` | Result of the IRC read routes (V2): routes, test counts, ten differences from the OpenHouse client, and what write routes will need | Observation by a delegated agent |
| `20-iteration-friction.md` | Diagnosis of slow test loops: memory, sleeping tests and uncached CI, with ranked changes | Observation by a delegated agent |
| `23-v1-commit-core-result.md` | Result of V1: the commit core, the candidate entry point, what bypasses it, test counts and what the IRC write routes can rely on | Observation by a delegated agent |
| `24-commit-rule-inventory.md` | Every write that reaches stored table metadata and every rule on the way, with the ten rules that cannot be stated over a metadata pair | Observation by a delegated agent |
| `25-optin-green-result.md` | Result of making the opt-in Iceberg 1.12 build green: test table, the two remaining failures, line detection and Spark SQL differences | Observation by a delegated agent |
| `22-decisions-continued-2.md` | Decisions D22 to D29: the IRC write behaviors accepted as built, the capabilities design adopted, its one-way doors set aside for Mike, the interim policy-key refusal, the testing additions after the red build, view rename answering 406, the empty IRC view list where views are off, and the order of the remaining large work | Claude, not agreed unless it cites a statement |
| `26-n3a-spark42-read-result.md` | Result of N3a: Spark 4.2 on Iceberg 1.12 reads OpenHouse over IRC, the two server changes, nine differences from Spark 3.5 and what writes did then | Observation by a delegated agent |
| `27-v3-irc-write-result.md` | Result of V3: the IRC table write routes, the rules and how each was proven, the replace rule, staged create, the error mapping, the differences from a native write and the follow-ups after merge | Observation by a delegated agent |
| `31-v4-irc-views-result.md` | Result of V4: the IRC view routes, the views-disabled behavior, tables versus views, the error mapping and the differences from a native view write | Observation by a delegated agent |
| `32-optin-green-ci-result.md` | Result of C5, W12 and I10: counts on both builds, the endpoint list the adapter advertises, the apps' capability declaration and the CI job | Observation by a delegated agent |
| `33-v5-policies-over-irc-result.md` | Result of V5-1 and V5-2: every policy statement over IRC, the patch literals, the seven differences from the native change, and test counts | Observation by a delegated agent |
| `34-c2-capabilities-resolver-result.md` | Result of C2-1 and C2-2: the capabilities module, the resolver by path, every statement on a plain `RESTCatalog` before and after, and the unchanged legacy counts | Observation by a delegated agent |
| `35-n3x1-spark42-extensions-result.md` | Result of N3-x1: the OpenHouse SQL extensions on Spark 4.2, policy statements over IRC, the adaptations from 3.5 and the user-visible differences | Observation by a delegated agent |
| `36-v5-3-grants-unlock-endpoints-result.md` | Result of V5-3 and V10a: the grants and unlock routes, what Iceberg's HTTP client can do with them, the advertised endpoint list, and the empty view list where views are off | Observation by a delegated agent |
| `37-decisions-continued-3.md` | Decisions D30 to D36: the server's location overrides a client's on create, the Iceberg 1.12 differences accepted and the one not, what "conforming" means (put to Mike), the rule that a suite passes under the switch only if it ran there, with the opt-in job on pull requests, D34 adopting the metrics and register choices of pull request 112, D35 putting the native directory-reuse fix to Mike, and D36 ignoring `openhouse.tableUUID` on IRC create | Claude, not agreed unless it cites a statement |
| `38-c2-3-rest-capability-client-result.md` | Result of C2-3: grants and unlock over a plain `RESTCatalog` on Spark 3.5 and 4.2, how the client is arranged, and every difference from the OpenHouse catalog | Observation by a delegated agent |
| `39-v13-compat-kit-result.md` | Result of V13a: Apache's compatibility kit against the service, 31 of 160 passing (38 after V13b, 39 after V6 and V7), every failure group, what the specification requires versus permits, and the work items | Observation by a delegated agent |
| `40-i6f-full-regression-result.md` | Result of I6f: the whole opt-in build and the Spark SQL harness on Iceberg 1.12, what blocks making it the default, and what the flip would take | Observation by a delegated agent |
| `43-optin-ci-red-result.md` | Result of pull requests 107 and 108: the opt-in Iceberg 1.12 job was red in CI on five runs, the two causes, why local runs missed them, and the first green run | Observation by delegated agents |
| `42-v13b-spec-deviations-result.md` | Result of V13b: the four IRC route changes before and after, the kit now at 38 of 160, which tests moved, what was not run, and the native rename defect | Observation by a delegated agent |
| `44-v9abc-flip-prep-result.md` | Result of V9a to V9c, C6 and T9 (pull requests 109 to 111): the fork outside the checkout, `iceberg-aws` from the fork, 193 files moved, the opt-in build on every pull request, and the 11 blockers to deleting the old projects | Observation by delegated agents |
| `46-v6-v7-metrics-register-result.md` | Result of V6 and V7 (pull request 112): the metrics report and register table routes, before and after, every refusal, counts on both builds, and the data-loss finding | Observation by a delegated agent |
| `47-v9d-deployables-result.md` | Result of V9d (pull request 113): the deployables and images under the 1.12 switch, proof the default is unchanged, what was not run, and the list for V9e | Observation by a delegated agent |
| `48-v16-directory-reuse-result.md` | Result of V16, N5 and T13 (pull request 116): the data-loss path run on both APIs, the three IRC ways in closed, the four native ways pinned, and the register refusal tests | Observation by a delegated agent |
| `49-t10-spark35-tests-result.md` | Result of T10 (pull request 115): five Spark 3.5 test classes never compiled now run, why seven failed first, and the three Spark 3.1 tests with no Spark 3.5 equivalent | Observation by a delegated agent |
| `50-n3b-spark42-smoke-result.md` | Result of N3b (pull request 117): the smoke suite on Spark 4.2 over IRC only, the 16 differing statements, the 18 extra write statements and what was not compared | Observation by a delegated agent |
| `51-decisions-continued-4.md` | Decisions D37 to D42: the Spark 4.2 differences (rename target fixed, the rest accepted), how V9e makes 1.12 the default, the multi-table commit route serving one table (D39), the real atomic commit put to Mike (D40) the over-long metadata location refused (D41, amended: a cluster property, default 255) and format v3 off by default, chosen per table (D42) | Claude, not agreed unless it cites a statement |
| `52-c9-n6-result.md` | Result of C9 in part (pull request 114) and N6 (pull request 119): which Docker checks are green in CI, why the Spark image never finished building, the rename target fixed on Spark 4.2 and 3.5, 11 differences left, and the `owner` property | Observation by delegated agents |
| `53-v8-multi-table-commit-result.md` | Result of V8 (pull request 120): the multi-table commit route serves one table and refuses several whole, the three investigation answers, before and after, why an undo is unsafe, counts and the proposal for real atomicity | Observation by a delegated agent |
| `54-v9e-flip-result.md` | Result of V9e (pull request 118): Iceberg 1.12 is the default build, how to select the reference line, what was deleted, evidence, known gaps and the follow-up list | Observation by a delegated agent |
| `55-c10-c14-result.md` | Result of C10 and C14 (pull requests 122 and 123): the Spark image builds in CI and the dataloader checks pass, the wait step that never waited, the generator jar as a cached dependency, and the first green run of the flipped layout | Observation by delegated agents |
| `56-decisions-continued-5.md` | Decisions D43 to D47: the format v3 acceptance and refusal rules, one native answer that changes, the unguarded v3 upgrade found on the 1.12 line, and an addendum with nine decisions recorded at the merge; D44 the synchronous scan planning route with no state; D45 the empty credentials route; D46 the retention backup and deletes, put to Mike; D47 view rename and the House Tables API, put to Mike | Claude, not agreed unless it cites a statement |
| `57-x1-format-v3-result.md` | Result of X1 and W6 (pull request 124): format v3 tables behind a cluster switch, what works, what is refused, the four native answers that change, evidence, what was not run and what remains | Observation by delegated agents |
| `67-decisions-continued-6.md` | Decisions D48 and D49: the `variant` column type behind its own cluster property, off by default; the scan planning route refuses format v3 tables until scan tasks carry sequence numbers | Claude |
| `66-v11b-v11c-result.md` | Result of V11b and V11c (pull request 131): server-side planning under Spark 4.2 writes and reads, the two pinned differences, the bound on one plan with its measurement table, evidence counts per build line, gaps | Observation by a delegated agent |
| `65-x10-concurrency-result.md` | Result of X10, concurrency part (pull request 130): row-level writes racing compaction, expiry, retention and orphan removal on format v3 tables, how the races are made deterministic, the result table, where each conflict check runs, the two behaviors pinned inside Iceberg, evidence counts per build line, gaps | Observation by a delegated agent |
| `64-n1c-n7-result.md` | Result of N1-c and N7 (pull request 128): Spark 3.5 on the plain Iceberg REST path, the 9 declared differences, the comparison with Spark 4.2, what was pinned, the one shared scenario list, evidence counts per build line, gaps | Observation by a delegated agent |
| `63-x6-maintenance-v3-result.md` | Result of X6 (pull request 125): every maintenance job on a format v3 table, what is pinned and not changed (the retention backup and deletes, sort stats on 1.5.2, production compaction arguments), the independent review and second round, evidence counts per build line, what is not covered | Observation by a delegated agent |
| `62-v11a-v12a-result.md` | Result of V11a and V12a (pull request 127): synchronous scan planning and the empty credentials route on the Iceberg 1.12 line, what each answers, that no existing route changed, the nine decisions, evidence counts per build line, gaps | Observation by a delegated agent |
| `61-w7-x4-result.md` | Result of W7 and X4 (pull request 126): Spark 3.5 and 4.2 read old Parquet and ORC files through native defaults on v3 tables, the one behavior change (widening a defaulted column), the two pinned limitations, evidence counts, gaps | Observation by a delegated agent |
| `60-v11-v12-investigation-result.md` | Result of the V11 and V12 investigation: the 96 kit failures by cause (none about scan planning or credentials), why scan planning is cheap and safe, why credential vending cannot be built from what the repository has, and what reading could not settle | Observation by a delegated agent, from reading code; nothing run |
| `58-waiting-on-mike.md` | The decisions that are Mike's, with the format v3 one-way doors; nothing blocks work | Claude |
| `59-v15-v3b-v3c-result.md` | Result of V15, V3b and V3c (pull request 121): the metadata location limit as a cluster property, the FileIO the IRC config route advertises, IRC failures audited, the behaviour change on a `schema.sql` store, evidence, gaps | Observation by delegated agents |
| `41-work-platforms-v3.md` | Work items for the native path, platforms and v3 functions, and V11a to V12e for IRC scan planning and credentials, moved out of `04-work.md` | Claude |
| `21-findings-continued.md` | The seventh to eleventh rounds of findings: what selective testing missed, the native rename defect, the red opt-in job, the flip-preparation findings, the directory-reuse data-loss risk, now confirmed by running it, and the twelfth round (T10, N3b) | Observation by delegated agents and from CI |
| `28-capabilities-over-irc-design.md` | Design for OpenHouse capabilities (policies, grants, unlock) over IRC, with the Spark 4.2 extension build and the one-way doors | Delegated agent from reading code, adopted in D23 |
| `29-capabilities-over-irc-work.md` | The design's work items and what is not verified | Delegated agent from reading code |
| `30-work-defaults.md` | Work items W1 to W14 for column defaults, moved out of `04-work.md` | Claude |
| `17-delegation.md` | Which kind of agent takes which work item, and the rule to commit under a shared lock | Claude |
| `05-iceberg-patch-ledger.md` | Each LinkedIn Iceberg patch against Apache 1.12.0 | Delegated agent, spot-checked |
| `07-seam-design.md` | Design note for the plugin seam | Delegated agent, not agreed |

Suggested reading order for review: `03-inferred.md`, then `04-work.md`. A judgement rejected in the
first changes the second.
