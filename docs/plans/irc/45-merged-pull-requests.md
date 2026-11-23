# Merged pull requests

Bottom line: 35 pull requests are merged into `irc-integration` in mkuchenbecker/openhouse (the last is 116, merge
commit 0a3bf529) and 10 into `openhouse-1.12.0` in mkuchenbecker/iceberg (the last is 12, commit a88f556c). Nothing is
merged into either fork's main or into LinkedIn's repositories. Moved out of `18-state.md` to keep it under 150
lines; the current state is there. Dated 2026-11-23.

`irc-integration` in mkuchenbecker/openhouse is LinkedIn main (bb8929e1) plus these pull requests. Each is a
merge commit whose message states why the change exists, the evidence, and the known gaps.

| Pull request | Work item | Merge commit |
| --- | --- | --- |
| 80 | T1, smoke suite | 37b89f0b |
| 81 | T3, plugin contract tests | d5c34e74 |
| 82 | T4, commit-path tests | 85614361 |
| 85 | T2, delta-harness | 8bda0f6b |
| 83 | T5, faithful stand-in for the house table store | 2334b050 |
| 84 | C1, the REST-shaped seam | 4f15a3a3 |
| 86 | T2b, harness through the seam | b5b8ed87 |
| 87 | I6a to I6c, opt-in Iceberg 1.12 build | fdc7f426 |
| 88 | V2, IRC read routes | 4517643b |
| 90 | Post-merge CI workflow `post-merge-build.yml` (D18) | b78743fe |
| 91 | W3, W4, I6g, I6h: the opt-in Iceberg 1.12 build passes every listed suite except two seam view cases (`25-optin-green-result.md`) | b732cc7b |
| 89 | V1, the commit core (`23-v1-commit-core-result.md`) | 94877d49 |
| 92 | N3a: Spark 4.2 on Iceberg 1.12 reads OpenHouse over IRC, opt-in build only (`26-n3a-spark42-read-result.md`) | 8b9bfe83 |
| 93 | The post-merge build no longer cancels a running build | f60e32dd |
| 94 | V3: the IRC table write routes (`27-v3-irc-write-result.md`) | 14f23870 |
| 96 | V5-0, V5-gap: IRC writes run the native update validator and refuse policy keys | 0010df15 |
| 95 | V4: the IRC view routes (`31-v4-irc-views-result.md`) | 71b3e826 |
| 97 | C5, W12, I10: the opt-in build is green and under post-merge CI (`32-optin-green-ci-result.md`) | e353a36d |
| 98 | Fix-forward: the Iceberg 1.2 line boots again (`27-v3-irc-write-result.md`, follow-ups) | cc76d485 |
| 99 | V5-1, V5-2: policies through the IRC commit route (`33-v5-policies-over-irc-result.md`) | 1fa8cb34 |
| 100 | C2-1, C2-2: the capabilities module and resolver (`34-c2-capabilities-resolver-result.md`) | 19605614 |
| 101 | N3-x1: OpenHouse SQL extensions for Spark 4.2 (`35-n3x1-spark42-extensions-result.md`) | b07613f9 |
| 102 | V5-3, V10a: grants and unlock routes; the config advertises served endpoints (`36-v5-3-grants-unlock-endpoints-result.md`) | 6015e111 |
| 103 | C2-3, N3-x2: the grants and unlock client (`38-c2-3-rest-capability-client-result.md`) | c174e388 |
| 104 | V13a: Apache's compatibility kit (`39-v13-compat-kit-result.md`) | fb8c1240 |
| 105 | I6f: full regression of the opt-in build (`40-i6f-full-regression-result.md`) | 7ddc5bb4 |
| 106 | V13b: the IRC routes follow the specification in four places (`42-v13b-spec-deviations-result.md`) | bfdcf266 |
| 107 | Fix-forward: the grants client test follows Iceberg 1.12's HTTP client; the opt-in job prints failed tests (`43-optin-ci-red-result.md`) | 152b08e7 |
| 108 | Fix-forward: the Spark 4.2 test services keep their warehouse in a short temporary directory (`43-optin-ci-red-result.md`) | 3222cf78 |
| 109 | V9a, V9b: the fork is published under the Gradle user home and survives `clean`; every Iceberg jar is the fork's under the switch, `iceberg-aws` included; the pin is `a88f556c` (`44-v9abc-flip-prep-result.md`) | 6bc6fb05 |
| 110 | V9c: 193 shared files moved into the newer projects under `src/shared` (`44-v9abc-flip-prep-result.md`) | 7e8941ab |
| 111 | C6, T9: every pull request runs the default build and the opt-in build; the kit's warehouse leaves the checkout path (`44-v9abc-flip-prep-result.md`) | 6cc7b092 |
| 112 | V6, V7: the IRC metrics report and register table routes (`46-v6-v7-metrics-register-result.md`) | 8876a7f295aaca02b49baa3e306fe44774ccc1f9 |
| 113 | V9d: the uber jars, Docker prerequisites, scheduler image and Spark image build and start under the 1.12 switch (`47-v9d-deployables-result.md`) | b1dfe9b06156ba365631d30e748f8279a40c0bd5 |
| 116 | V16, N5, T13: an IRC create can no longer take a live table's directory; the native defect pinned; four register refusals tested (`48-v16-directory-reuse-result.md`) | 0a3bf5292fd861405d416b26227d1f504d2a1288 |

`openhouse-1.12.0` in mkuchenbecker/iceberg is Apache 1.12.0 (212aa894e) plus these pull requests.

| Pull request | What it holds | Commit |
| --- | --- | --- |
| 3 to 8 | The 13 reapplied LinkedIn patches, validated together | not listed here |
| 9 | Publishing group and version can be overridden | 984904f02 |
| 10 | I5: the patches applied to Spark 4.2 | 7cf3a1040 |
| 11 | W1 and W2: native-default tests and the ORC change | 28742df3e |
| 12 | I11: time-travel reads ignore the session write-audit-publish branch on Spark 3.5 | a88f556c |

Nothing is merged into either fork's main or into LinkedIn's repositories.
