# N1-c and N7: Spark 3.5 smoke suite on the plain REST path; one shared scenario list (record)

## Bottom line

- N1-c: Spark 3.5 with Iceberg's own `SparkCatalog type=rest` over HTTP to the tables service's IRC routes,
  OpenHouse SQL extensions loaded, gives the baseline outcome on 168 of 177 smoke statements (184 of 193 with
  the new s31). The 9 that differ are declared: 5 exception type or wording, 4 namespace and SHOW behaviour.
  No capability is unreachable, and no statement differs in rows read or in what a write stores. They are
  exactly Spark 4.2's differences less its two Spark 4 rows. [ran]
- N7: one scenario list (`SparkSqlSmokeScenarios`) in the Spark 3.5 itest's `src/shared/smoke/java`, compiled
  by both itests; the copy and `SmokeScenariosCopyTest` are gone. Spark 4.2: 166 of 177 identical, the same
  11 declared, before s31; 182 of 193 after (the 16 s31 statements are identical there). [ran]
- Branch `irc/n1c-n7-spark35-rest-smoke`, draft pull request 128, head 87547373730d0e89c523ab566c27dfdfb68846ec.
  CI on that head: both jobs green (Build and Run Tests; Reference build on LinkedIn 1.5.2), run 45319527801.

## Declared differences on rest-http (Iceberg 1.12 line) [ran]

| Statement | Baseline | rest-http | Category |
| --- | --- | --- | --- |
| s19 #3 CREATE NAMESPACE | UOE "Describing database is not supported" | UOE "Server does not support endpoint: POST /v1/{prefix}/namespaces" | type or wording (D20) |
| s24 #3 ALTER NAMESPACE SET DBPROPERTIES | UOE "Set properties on a database..." | UOE "Server does not support endpoint: POST .../properties" | type or wording (D20) |
| s24 #4 DROP NAMESPACE | UOE "Describing database..." | UOE "Server does not support endpoint: DELETE .../{namespace}" | type or wording (D20) |
| s27 #3 UNLOCK missing table | Java client `WebClientResponseWithMessageException` | Iceberg `NoSuchTableException`, same message | type or wording |
| s29 #2 RENAME to same name | Java client exception | `BadRequestException` "Malformed request: " + same message | type or wording |
| s24 #2 DESCRIBE NAMESPACE | refused | rows (catalog, namespace name) | namespace and SHOW (D37) |
| s24 #5 SHOW NAMESPACES IN db | refused | no rows | namespace and SHOW (D37) |
| s25 #1 SHOW TABLES IN openhouse | `ValidationException` DatabaseId not provided | `NoSuchNamespaceException` "Invalid namespace: " | namespace and SHOW (D37) |
| s25 #3 SHOW TABLES (USE openhouse) | as #1 | as #1 | namespace and SHOW (D37) |

Policies (s16, all s31 forms), grants (s17) and unlock of an unlocked table (s27 #2) give the baseline.

## Other paths

- legacy, rest-shim on 1.12: 191 of 193, 2 declared (s19 #3, s24 #4: "Checking if database exists is not
  supported", D14b). These were already asserted per line before; now stated against the one baseline. [ran]
- legacy, rest-shim on -PicebergLinkedIn: 193 of 193. [ran]

## Iceberg 1.5.2 line [ran, temporary probe not committed]

With the assumption forced true on `-PicebergLinkedIn`, rest-http runs every statement. It differs from the
1.12 declarations only on s19 #3, s24 #3, s24 #4: the 1.5.2 `RESTCatalog` has no endpoint list, sends the
request, and gets `RESTException: Unable to process: This server does not support POST /v1/namespaces yet`
(and the matching properties and DELETE routes). So the 1.5.2 client is not too old for any route the suite
uses. As briefed, the path is skipped on that line by an assumption; `RestHttpPathLineTest` pins that the
assumption holds exactly when the build is the 1.12 line (`smoke.iceberg.build`, from `-PicebergLinkedIn`).

## Findings

- `PolicyTag.tags` is a `HashSet` of an enum: the stored order of `(PII, HC)` follows identity hash codes and
  changed between runs (PII,HC on one run, HC,PII on another, same on every path of a run). s31 accepts either.
- Baseline quirks pinned as observed, on every path: replication destination stored as `"'A'"` (quotes kept,
  upper-cased); a column retention pattern keeps its quotes without `WITH TIMEZONE` and loses them with it.
  These come from the SQL extensions' literals, shared by all paths. Not fixed (would change existing paths).

## Decisions (two-way)

1. Shared list lives in the Spark 3.5 itest (`src/shared/smoke/java`): the baseline is that itest's legacy
   path; the project is in both builds (Spark 4.2 is not in `-PicebergLinkedIn`). Shown by: 3.5 catalogTest on
   both lines and 4.2 smokeTest compile and run the one file.
2. The baseline stays Spark 3.5 + OpenHouse catalog on 1.5.2; the 1.12 namespace messages are declared
   differences of the legacy and rest-shim paths (category "(c) Iceberg 1.12 line"). Shown by: outcomes files.
3. rest-http categories are the brief's names ("exception type or wording", "namespace and SHOW behaviour").
4. s31 added to the shared list (so Spark 4.2 also runs it) to have a test per policy form on all paths;
   `rowsMatching` added for the random replication schedule and the tag set order.
5. Skip on 1.5.2 kept although the probe shows it mostly works (brief); reversing costs 3 line-specific
   declarations.
6. Per-path outcomes files (`outcomesFile()` hook; default unchanged for Spark 4.2).

## Test counts as run (local, JDK 17)

| Suite | 1.12 line | -PicebergLinkedIn |
| --- | --- | --- |
| 3.5 catalogTest | 329, 1 skipped (pre-existing), 0 failed | 329, 54 skipped (32 rest-http + 22 pre-existing), 0 failed |
| 3.5 statementTest | 137, 0 failed | 137, 0 failed |
| 3.5 test | 137, 0 failed | 137, 0 failed |
| 4.2 test / extensionsTest / smokeTest | 67 / 24 / 65, 0 failed | not in this build |
| spotlessCheck 3.5 itest, 4.2 itest | green | - |

Spark 4.2 smokeTest at the N7 commit: 64 (the base's 66 less the 2 copy tests); now 65 (+ s31).

## Commits

- c5a083ef N7 shared list, copy and copy test removed.
- ea4f0e87 N1-c rest-http path, declarations, line assumption and its test.
- 43edcbf2 N1-c s31 every policy form.
- 87547373 N1-c s31 tag order; development guide.

## Not verified

- The 18 Spark 4.2 write-statement checks were not ported to rest-http.
- Grants cannot be observed in the default fixture (SHOW GRANTS empty on every path, as baseline).
