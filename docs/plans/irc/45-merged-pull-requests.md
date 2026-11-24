# Merged pull requests

Bottom line: 63 pull requests are merged into `irc-integration` in mkuchenbecker/openhouse (the last is 141, merge
commit 3079859d) and 10 into `openhouse-1.12.0` in mkuchenbecker/iceberg (the last is 12, commit a88f556c). Nothing is
merged into either fork's main or into LinkedIn's repositories. Moved out of `18-state.md` to keep it under 150
lines; the current state is there. Dated 2026-11-24.

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
| 115 | T10: five Spark 3.5 ALTER TABLE and CREATE TABLE test classes (12 tests) compile and run on both builds (`49-t10-spark35-tests-result.md`) | 5ea51d35aacd62f124ce2a05ad7c1c004ad2a3c4 |
| 117 | N3b, closing N3: the Spark SQL smoke suite runs on Spark 4.2 over IRC only, 161 of 177 statements as on the baseline (`50-n3b-spark42-smoke-result.md`) | c1303849f3baf92b95896b75317171886768fec8 |
| 114 | C9, in part: the tables and scheduler images built from the 1.12 jars start and pass the integration script in CI on every pull request; the Spark image and dataloader checks run by hand only (`52-c9-n6-result.md`) | 1fec3a0affab866b2c13425d135c9e565effb8a0 |
| 119 | N6: `RENAME TO <catalog>.<db>.<t>` works over IRC on Spark 4.2 and on Spark 3.5 over a plain REST catalog; 166 of 177 statements as on the baseline (`52-c9-n6-result.md`) | 92c5e117cb80345b46cb8edd2d690358d9be5bba |
| 120 | V8: the IRC multi-table commit route serves one table and refuses several whole with 400; the config lists 19 endpoints (`53-v8-multi-table-commit-result.md`) | a217cce94f9592c088a4193dad87a06969c0fd4c |
| 118 | V9e: a plain build is the fork's Iceberg 1.12 line; `-PicebergLinkedIn` selects the 1.5.2 reference line; the Iceberg 1.2 / Spark 3.1 projects are deleted (`54-v9e-flip-result.md`) | 440249d1acdd2930add70d5e3b792c6c15eb40b3 |
| 122 | C10: the Spark image builds on a CI runner in six to seven minutes; the Hadoop recipe starts and all 15 dataloader integration checks pass; the wait step now waits; the job runs on pull requests and after every merge (`55-c10-c14-result.md`) | 71c4c70be081b9d7c51e6538066e59c216586e5a |
| 123 | C14: the OpenAPI generator jar is a Gradle dependency in the cache that `clean` keeps, not a `curl` after every `clean` (`55-c10-c14-result.md`) | 75d6e1851f80b0688a0aa22ddac52388c9dfbc89 |
| 124 | X1, W6: format v3 tables behind `cluster.iceberg.format-v3.enabled`, default off; create or upgrade through the table property on both APIs; Spark's row-level writes work on them; four native answers change (`57-x1-format-v3-result.md`) | e2e02bfa0d1b674cb774f591355e19c911fee46b |
| 126 | W7, X4: Spark 3.5 and Spark 4.2 read old Parquet and ORC files through native default values on format v3 tables; widening the type of a column with an `initial-default` is now accepted on v3 (`61-w7-x4-result.md`) | 57db4015ff0085f1cce6ca9c5a54ee09150a876b |
| 121 | V15, V3b, V3c: a write whose House Tables row would not fit is refused with 400 before anything is written, the limit a cluster property (default 255); the IRC config route's FileIO follows the default storage; failed IRC requests are audited (`59-v15-v3b-v3c-result.md`) | 72947228dc77359a6c1b76fef667ba108ee7769d |
| 127 | V11a, V12a: on the Iceberg 1.12 line the plan route is served synchronously and the credentials route answers an empty list; the 1.5.2 line still answers 406 for both (`62-v11a-v12a-result.md`) | 91e175c4809915645473f7070bf64b93b8adda66 |
| 125 | X6: every maintenance job that opens a table runs on a format v3 table with deletion vectors and loses no live data; tests only, no job changed; the retention backup's handling of deletes is pinned and put to Mike (D46) (`63-x6-maintenance-v3-result.md`) | 262a1416b982ec99694713cfdc8ed8d26fa3e344 |
| 128 | N1-c, N7: Spark 3.5 on Iceberg's plain REST catalog gives the baseline outcome on 168 of 177 smoke statements, 9 declared differences; the Spark 3.5 and Spark 4.2 suites share one scenario list (`64-n1c-n7-result.md`) | ca94d82e0c833d513db0eadf6007627c7840ccd3 |
| 130 | X10, concurrency part: row-level writes racing compaction, expiry, retention and orphan removal on format v3 tables are refused by the service on the old base and re-checked by Iceberg, natively and over REST; tests only; two Iceberg behaviors pinned (`65-x10-concurrency-result.md`) | adc28470278fe2c762ab37fbbe57c16bc7872a99 |
| 131 | V11b, V11c: with server planning Spark 4.2 writes store the same tables and reads return the same rows; one plan holds at most 10,000 file scan tasks and a larger scan is answered `failed`; two differences pinned (`66-v11b-v11c-result.md`) | 1dd485d37140f91a0bcbe469b933762085f968a4 |
| 129 | X5: each column type format v3 adds is supported or refused with a reason; `unknown` supported, `variant` behind `cluster.iceberg.format-v3.variant.enabled` (default off, D48), `timestamp_ns`, `timestamptz_ns`, `geometry` and `geography` refused (`68-x5-v3-types-result.md`) | 70b35d7a5608ad10db53acbb99a1aa853d18ea45 |
| 132 | V11e: the plan route refuses format v3 tables with a visible failure (D49); a scan naming a column added after its snapshot is planned as the client plans it (`69-v11e-result.md`) | 9f9561e6987541460d537eb40d7c248b04c68955 |
| 133 | C16: a pull request's CI is split into parallel jobs, 52.2 minutes before and 21.6 after, with identical test lists; post-merge runs stay single jobs (`71-c16-ci-split-result.md`) | 7e57bd2162489d248da60022d7af434e7e056b01 |
| 134 | V14: a create transaction over the Iceberg REST routes keeps its schema, partition spec and sort order history; the compatibility kit passes 42 of 160 (`72-v14-create-history-result.md`) | d30cb9f628d34b8d1f6ca42968ad201d4a01d2fa |
| 135 | N11: the maintenance jobs build and pass on Spark 4.2 over the Iceberg REST routes, 395 tests, 0 failed, 5 skipped; the Spark 3.5 jobs unchanged; three changes in shared job code (`73-n11-jobs-spark42-result.md`) | e2823b698825d1c0cc02f5429826fd3e3e8c341d |
| 136 | C12: Java compile and Javadoc use UTF-8 in the root build, so a build under the `C` locale no longer fails on non-ASCII sources; C8 and T12 needed no change (`dec16c80`, `98328ea2`) | 4b696a5562c27faf268b15908e64d3cfd6b91ad4 |
| 138 | C16 fix-forward: the step that prints failed tests also reads the build's console log, so a job whose test process died no longer prints zero failures; 62 printed on the real failed log (`75-n8-v18-c15-and-ci-fix-result.md`) | c2c5214482a365ce3aaded99766266793a811724 |
| 139 | N8, V18, C15: policy tags in one stored order, the Iceberg 1.2 guards removed, byte-stable OpenAPI specs (`75-n8-v18-c15-and-ci-fix-result.md`) | e6c52c21dcb4df4dc037803e35d86450a71656c6 |
| 137 | X10 (rest), X15: every maintenance job loses no live data on format v3 tables with branches and tags, evolved partition specs and equality deletes, on both jobs lines; partition stats label values by the partition struct's own fields (`76-x10-shapes-result.md`) | 461ae4378a059621db6bf84a88469fa1d2c0eb95 |
| 140 | T6, N10: test services store grants, so SHOW GRANTS and the effect of a grant are observed on every path (scenario 17, 22 statements); the 19 write checks are one list run by Spark 4.2 and by Spark 3.5 on the plain REST path, which now runs on both Iceberg lines; smoke 210 statements (`77-t6-n10-result.md`) | dfcd15a0487f70ca893be907da9a20618a008630 |
| 142 | V19, V20, V22: the rename target limit is a cluster property; the FileIO class of a table's own storage type in its Iceberg REST responses; the last Iceberg 1.2 leftover removed (`78-v19-v20-v22-n16-x9-result.md`) | af210f1b107ed04ce5401843911e3b7cfa95bf47 |
| 141 | N16, X9: the jobs build one catalog per job run and not one per table load; retention signals delete files in backups (`78-v19-v20-v22-n16-x9-result.md`) | 3079859d591364df54fe29fadb82d32bf17c10c2 |

`openhouse-1.12.0` in mkuchenbecker/iceberg is Apache 1.12.0 (212aa894e) plus these pull requests.

| Pull request | What it holds | Commit |
| --- | --- | --- |
| 3 to 8 | The 13 reapplied LinkedIn patches, validated together | not listed here |
| 9 | Publishing group and version can be overridden | 984904f02 |
| 10 | I5: the patches applied to Spark 4.2 | 7cf3a1040 |
| 11 | W1 and W2: native-default tests and the ORC change | 28742df3e |
| 12 | I11: time-travel reads ignore the session write-audit-publish branch on Spark 3.5 | a88f556c |

Nothing is merged into either fork's main or into LinkedIn's repositories.
