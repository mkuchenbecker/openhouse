# N1-c and N7: Spark 3.5 on the plain REST path, one shared scenario list, result

Bottom line: Spark 3.5 with Iceberg's own `SparkCatalog type=rest` over HTTP to the service's Iceberg REST routes, with
the OpenHouse SQL extensions loaded, gives the baseline outcome on 168 of 177 smoke statements. The other 9 are
declared differences. None differs in rows read or in what a write stores, and no capability is unreachable. They are
Spark 4.2's differences less its two Spark 4 rows. The Spark 3.5 and Spark 4.2 suites now compile one scenario list.
Pull request 128, merged 2026-11-24 into `irc-integration` as merge commit
`ca94d82e0c833d513db0eadf6007627c7840ccd3` (pull request head `87547373`). No product code changed.
Marks: [ran] run in a worktree, with the counts the record and merge message give; [read] read in code, not run;
[inferred] not observed. Post-merge CI for pull request 128 has not been read yet.

## The 9 declared differences on rest-http (Iceberg 1.12 line) [ran]

| Statement | Baseline | rest-http | Category |
| --- | --- | --- | --- |
| s19 #3 CREATE NAMESPACE | Unsupported-operation error, "Describing database is not supported" | Same type, "Server does not support endpoint: POST /v1/{prefix}/namespaces" | Exception type or wording (D20) |
| s24 #3 ALTER NAMESPACE SET DBPROPERTIES | Unsupported-operation error | Same type, endpoint not supported (properties route) | Exception type or wording (D20) |
| s24 #4 DROP NAMESPACE | Unsupported-operation error | Same type, endpoint not supported (DELETE namespace) | Exception type or wording (D20) |
| s27 #3 UNLOCK of a missing table | Java client `WebClientResponseWithMessageException` | Iceberg `NoSuchTableException`, same message | Exception type or wording |
| s29 #2 RENAME to the same name | Java client exception | `BadRequestException`, "Malformed request: " plus the same message | Exception type or wording |
| s24 #2 DESCRIBE NAMESPACE | Refused | Rows (catalog, namespace name) | Namespace and SHOW behaviour (D37) |
| s24 #5 SHOW NAMESPACES IN db | Refused | No rows | Namespace and SHOW behaviour (D37) |
| s25 #1 SHOW TABLES IN openhouse | `ValidationException`, database id not provided | `NoSuchNamespaceException`, "Invalid namespace: " | Namespace and SHOW behaviour (D37) |
| s25 #3 SHOW TABLES after USE openhouse | As #1 | As #1 | Namespace and SHOW behaviour (D37) |

Policies (s16 and every s31 form), grants (s17) and unlock of an unlocked table (s27 #2) give the baseline.

## Against Spark 4.2 [ran]

Spark 4.2 over the service's Iceberg REST routes: 166 of 177 identical, 11 declared (`50-n3b-spark42-smoke-result.md`,
D37). Spark 3.5 rest-http has the same 9 differences; the two extra on Spark 4.2 belong to Spark 4. With the new
scenario s31 (a test per remaining SET POLICY form, 16 statements, all identical): rest-http 184 of 193, Spark 4.2
182 of 193 with the same 11 declared.

## Other Spark 3.5 paths [ran]

- Legacy and rest-shim on 1.5.2: 193 of 193.
- Legacy and rest-shim on 1.12: 191 of 193. The 2 are the namespace messages "Checking if database exists is not
  supported" (s19 #3, s24 #4; D14b), which the old suite already asserted per line and which are now stated against
  the one baseline.

## Pinned, not changed

The same on every path, so a path swap does not change them; the root cause was not traced [read].

- The stored order of policy tags varies between service processes. `PolicyTag.tags` is a `HashSet` of an enum, so
  the order of (PII, HC) followed identity hash codes and changed between runs. s31 accepts either order (`rowsMatching`).
- A replication destination is stored with its quotes and upper-cased (`"'A'"`).
- A column-retention pattern keeps its quotes without `WITH TIMEZONE` and loses them with it.

## Decisions (two-way)

1. The shared list lives in the Spark 3.5 itest (`src/shared/smoke/java`): that itest's legacy path is the baseline, and
   the project is in both builds (Spark 4.2 is not in `-PicebergLinkedIn`). The copy and `SmokeScenariosCopyTest` are gone.
2. The baseline stays Spark 3.5 with the OpenHouse catalog on 1.5.2; the 1.12 namespace messages are declared
   differences of the legacy and rest-shim paths.
3. rest-http categories use the brief's names: exception type or wording, namespace and SHOW behaviour.
4. s31 is added to the shared list, so Spark 4.2 also runs it; `rowsMatching` handles the random replication schedule
   and the tag order.
5. rest-http is skipped on the 1.5.2 line by an assumption, which `RestHttpPathLineTest` pins to the build's declared
   line (`smoke.iceberg.build`), so it cannot be skipped by accident on 1.12. Reversing costs 3 line-specific declarations.
6. Each path has its own outcomes file (`outcomesFile()` hook; the default is unchanged for Spark 4.2).

## Evidence, per build line (JDK 17, 0 failed everywhere) [ran]

| Suite | 1.12 line | `-PicebergLinkedIn` (1.5.2) |
| --- | --- | --- |
| 3.5 catalogTest | 329, 1 skipped | 329, 54 skipped (32 rest-http, 22 pre-existing) |
| 3.5 statementTest, test | 137 each | 137 each |
| 4.2 test, extensionsTest, smokeTest | 67, 24, 65 | not in this build |

spotlessCheck passed on the 1.12 line. The 1.5.2 run preceded the last commit, which changed only s31's tag-order
expectation and a guide. CI on the pull request head: both jobs passed, run 45319527801.

## Known gaps

- rest-http is not run on 1.5.2. A temporary probe (not committed) forced the assumption true: every statement ran, and
  only the three namespace refusals differ ("This server does not support POST /v1/namespaces yet"), so the 1.5.2
  client reaches every route the suite uses [ran].
- The 18 Spark 4.2 write-statement checks are not ported to rest-http; writes there are checked by the smoke
  statements' read-backs.
- SHOW GRANTS is empty in the fixture on every path, so grants are verified only as far as the baseline verifies them.
- Equivalence of the old Spark 3.5 suite and the shared list was read, not proven by a diff of outcomes [read].
