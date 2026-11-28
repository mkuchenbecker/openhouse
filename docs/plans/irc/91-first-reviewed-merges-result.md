# The first three pull requests merged after an independent review (2026-11-28)

Bottom line: three pull requests merged into `irc-integration` on 2026-11-28, each after an independent sub-agent review
under the owner's standing order S58: 154 (jobs and Spark client back to LinkedIn main's behavior, merge `7cf347d7`),
151 (X2 and X3, row-level statements on format v3, tests only, merge `afe8d3c8`) and 153 (the owner's namespace stack,
upstream 869, 870, 871, merge `bc0ff1e8`, decision D54). `irc-integration` is at `bc0ff1e8`; 74 pull requests are merged.
Every review so far found something its author had missed (last section). Sources: the three merge commit messages, the
authors' records (`records/restore-jobs-and-client-behavior.md`, `records/x2-x3-v3-row-level.md`,
`records/build-on-owner-namespace-stack.md`) and the reviewers' reports (`records/pr-review-154.md`,
`records/pr-review-151.md`, `records/pr-review-153.md`, `records/pr-review-152.md`), under the common brief
`records/pr-review-brief-common.md`.

Marks: [ran] executed and seen; [read] read in a file, log or the merge message; [inferred] concluded without running.
The reviewers ran nothing: every review claim below is [read] unless marked otherwise. CI results are the authors' and
the merge messages' as reported, except the post-merge reads in the next section.

## Post-merge CI, as read by the orchestrator on 2026-11-28

- Pull request 154 (`7cf347d7`): post-merge CI passed on both lines [read].
- Pull request 151 (`afe8d3c8`): post-merge CI was running with nothing failed when last read at 01:21 UTC [read]. Not
  known to be finished.
- Pull request 153 (`bc0ff1e8`): post-merge CI has not been read. Its pull request build (green on `94528ec9`) ran before
  154 and 151 landed, so the post-merge build is the first check of the three changes together. Until it is read, the
  combination is unchecked.

## 1. Pull request 154: jobs and the Spark client back to LinkedIn main's behavior (merge `7cf347d7`)

Bottom line: merged as the reviewer found it (verdict: merge as is, no finding that had to be fixed first). Closes FF-10,
FF-14, FF-15; FF-16 is only partly closed.

### What it leaves true
- The scheduler's client factory and its test are main's files byte for byte: it sends the `column-defaults` header on
  every request as on main and logs no warning [read, and confirmed by the reviewer against main's code].
- Sort stats on a v1 or v2 table issues main's SQL text in main's order, with no extra catalog build and no extra table
  load; on the 1.5.2 line under the repository's job configuration it fails exactly as main did (pinned, with the cause).
- The apps uber jar has no `log4j2.properties`, as on main; the scheduler image carries it.
- Every OpenHouse SQL statement has main's outcome on every catalog shape, with one exception that is now a ledger row
  (a policy statement through Iceberg's catalog over a REST server that does not advertise policies).
- The thin Spark 3.5 runtime jar's POM names the capabilities module, which is now published.
- Evidence [ran, by the author]: tests that fail before and pass after on both Iceberg lines; the legacy-path smoke suite
  gives the same 300 outcomes per line before and after (298 plus two declared 1.12 differences on the default line, per
  the record); jar entry lists and generated POMs before and after on both lines; pull request build green on `95cdb514`
  (15 passed, 1 skipped by design).

### Behavior changes against the base, in full
As the merge message and the final author report state them, with the reviewer's additions marked.

1. The resolver (`OpenHouseCapabilities`, Spark 3.5 and 4.2) offers `policies` on a catalog that is not Iceberg's
   `SparkCatalog` or `SparkSessionCatalog`, and no longer refuses with a message that blames a server. Main had no
   resolver. Unreachable from SQL: the planner gives no plan for these statements on a wrapper catalog, on main and today.
   It is pinned only by its unit test.
2. The scheduler no longer detects the Iceberg line and logs no warning; it sends main's constant header. In the image the
   wire is unchanged (detection always failed there). On the 1.12 test classpath the header is now sent where it was absent
   (found by the reviewer, not listed by the author).
3. Sort stats on the 1.5.2 line with OpenHouse's parser outermost (the `jobs.yaml` order) and `spark_catalog` pointed at
   OpenHouse: success becomes "Cannot parse order: parser is not an Iceberg ExtendedParser", where an earlier change on
   this branch had made it succeed. Toward main.
4. Sort stats: no parse probe on 1.12, no Java catalog build, no extra table GET. Toward main.
5. Sort stats on a v3 table: version and write format come from the metadata query's plan instead of a table load; if the
   plan names no table the copy is written as v2. No counterpart on main. Neutral.
6. `log4j2.properties` out of the Spark 3.5 apps uber jar (both lines) and the Spark 4.2 apps jar; the scheduler image gets
   `-Dlog4j2.configurationFile`. Toward main.
7. Found by the reviewer, not listed by the author: the docker-compose recipe's Spark jobs and the apps tests on both Spark
   lines lose the INFO logging configuration they got from the jar (main's recipe ran jobs from the Spark 3.1 apps jar,
   which carried it). The shipped 3.5 apps jar is now exactly main's.
8. The thin Spark 3.5 runtime POM gains a runtime dependency on `openhouse-catalog-capabilities`, a new published artifact
   main never had, because the runtime's classes already needed it. Publishing makes that module's API public
   (`CapabilityNames`, `GrantsCapability`, `UnlockCapability`, the value types, `RestCapabilityClient`). The old comment's
   "one-way door" warning was deleted without a replacement.
9. CI: the deployables action fails if the apps jar carries `log4j2.properties`.
10. Three job changes kept as fixes of defects that exist on main, each confirmed by the reviewer against main's code: one
    catalog per job run, the retention signal about delete files in backups, partition stats labelled by each file's own
    spec.

Also true against the base, said in the merge message: on the default line the scheduler declares `column-defaults` while
the job catalogs do not, so tables that require it are scheduled and then refused to their jobs (the owner's open question,
BC-029).

### What the independent review found (one round) and how each finding was closed

| Finding | Severity | Closed by |
|---|---|---|
| F1: FF-16 reported closed, but its plan row also asks to relocate the module in the uber jar (overruled by a decision: nothing is relocated), delete `CONFIG_OVERRIDES` and the Java runtime's compile dependency (RF-021), and add a classpath acceptance test; no test pins the new POM dependency; the one-way-door sentence was dropped | SHOULD FIX | Not closed in the pull request. FF-16 is recorded as partly done in `86-...`; the three remaining parts stay open; the new POM dependency is ledger row BC-055 (D61) |
| F2: a policy statement through Iceberg's `SparkCatalog` over a REST server that does not advertise policies is refused, where main's code would have committed it as a property; contradicts the literal text of the FF-10 decision | SHOULD FIX (decision) | Decision D65: kept as a fix; ledger row BC-053 |
| F3: the author's inference that the uber jars may not be published at all is likely wrong (the classifier artifact of `mavenJava` probably publishes it); same on main | MINOR | Not closed; no change needed; settle with a `publishToMavenLocal` listing |
| F4: behavior changes the author did not list (recipe logging, apps tests, Spark 4.2 apps jar, scheduler on the 1.12 test classpath) | MINOR | Ledger row BC-054 and the list above |
| F5: the new CI jar check passes silently if `unzip` fails | MINOR | Not closed; FF-49 |
| F6: the wrapper-catalog statement test leaves out REVOKE, SET TAG, SET and UNSET REPLICATION, and GRANT or SHOW GRANTS on a database | MINOR | Not closed; not tracked as its own item |
| F7: `sourceTableOf` reads Spark's analyzed plan (developer API) | MINOR | No action; style note |
| F8: private ids remain in two build files (`(decision D23)`, `work item X5`) and in the four commit messages | MINOR | Not closed; FF-49 |
| F9: the capabilities artifact's content depends on the Iceberg line (the release publishes the 1.12 transport) | MINOR | Not closed; to be said in the module's comment |

### What is left open
- FF-16: the adapter's capability constant (still read by the opt-in seam), the Java runtime's compile dependency (RF-021)
  and a classpath acceptance test (a consumer of the thin jar plus the published Java runtime runs a GRANT) [read].
- Private ids in `integrations/java/openhouse-catalog-capabilities/build.gradle` and `apps/spark-3.5/build.gradle`, and the
  silent pass of the new jar check (FF-49).
- The default line's scheduler-versus-job-catalog mismatch on `column-defaults` (BC-029, the owner's question).
- Unreachable-from-SQL resolver change: pinned by its unit test only.

## 2. Pull request 151: X2 and X3, row-level statements on format v3 (merge `afe8d3c8`)

Bottom line: tests only; no production code changed and no defect found. Merged after two review rounds; the first found
that the key assertions could not fail.

### What it leaves true
- On every Spark 3.5 catalog path (the OpenHouse catalog, the REST-shaped shim, Iceberg's plain REST catalog) and on Spark
  4.2 over the Iceberg REST routes, across 14 table shapes (Parquet, ORC, Avro; v2 and v3; merge-on-read and
  copy-on-write; partitioned and not) and seven statements each: a merge-on-read statement writes deletes for exactly the
  rows it removes; a v3 data file has one live deletion vector, replaced and never duplicated; row ids and last-updated
  sequence numbers follow the specification and the fork's contract in both write modes, also across rollback and a
  v2-to-v3 upgrade; the snapshot's added-rows is the specification's upper bound [ran by the author].
- Evidence [ran]: 51 Spark 3.5 tests and 17 Spark 4.2 tests, plus a lineage test on Spark 4.2 without Iceberg's SQL
  extensions; pull request build green on both lines at `b27e5133`; the author's uncommitted mutation turned 11 checks red
  on a merge-on-read table silently written copy-on-write.

### Behavior changes against the base
None. Test sources only. One test task of the Spark 4.2 itest (`smokeTestServerPlanning`) no longer reruns this list under
server planning, because the plan route refuses v3 tables. The reviewer agreed: no file under any `src/main` is touched.

### What the independent review found and how each finding was closed

Round 1 on `47d20539`: merge after fixes. Round 2 on `b27e5133` (diff `47d20539..b27e5133`): merge.

| Finding | Severity | Closed by |
|---|---|---|
| F1: every deletion-vector expectation was computed from the table under test, so a merge-on-read table silently written copy-on-write passed everything on the 1.12 line | MUST FIX | `8f096093`: the expectation now comes from the statement's own model (rows deleted plus rows updated for merge-on-read, zero for copy-on-write); write modes checked after every create; a live deletion vector required on v3 merge-on-read statements. Confirmed by the reviewer in round 2 [read] |
| F2: lineage of upgraded rows accepted a null row id and copied the sequence number from the actual value | SHOULD FIX | `8f096093`: the sequence number expected is the v2 insert's, read before the upgrade; the id must lie in the range the commit allocated; a live row with null lineage fails |
| F3: next-row-id growth was metadata against metadata; added-rows never compared with the rows actually added | SHOULD FIX | `b27e5133`. The first version of the new check (added-rows equals the rows of the new data files) failed on every path; the author changed the check to the specification's definition (an upper bound), and the reviewer confirmed against the specification and the fork's snapshot producer that the check was wrong and the product right (D66) |
| F4: `smokeTestServerPlanning` reran the whole list without server planning | MINOR | Fix commits: the class is excluded from that task |
| F5: private ids and history narration in comments and messages | MINOR | Fix commits: none left in added lines |
| F6: class Javadoc misstated the v2 baseline | MINOR | Fix commits |
| F7: the v3 assumption was not cross-checked against the build's line (a wrong skip could pass silently) | MINOR | `8f096093`: the probe is asserted equal to the build line, then assumed |

### What is left open
- No harness reads one engine's writes with the other.
- Avro is not written on Spark 3.5 in the test JVM (a class clash between the embedded service's Iceberg core and the
  shaded runtime, declared on all three paths; the shipped runtime jar is not affected, checked by the reviewer).
- On the 1.5.2 line v2 position delete files accumulate one per statement (declared).
- Equality of added-rows with the manifest-based sum compares two values the same writer produced, so only the lower bound
  can fail; manifests with a null first row id are silently excluded (reviewer's residual note).
- Local file system only. The baseline is the test's own model and the specification, not a recording of LinkedIn main.

## 3. Pull request 153: the owner's namespace stack, upstream 869, 870, 871 (merge `bc0ff1e8`, decision D54)

Bottom line: the owner's three pull requests are merged into `irc-integration` as one merge commit each, his history
untouched, so his later pushes merge cheaply; everything the stack brings is on by default and moves away from LinkedIn
main; all of it is his to decide upstream, and no `/iceberg` namespace routes exist yet (decision D67 describes them).
Merged after two review rounds; the first sent it back for an incomplete behavior-change list and for defects pinned as
expected behavior.

### What it leaves true
- No file of upstream 869, 870, 871 is edited (heads `39f23249`, `e09a9625`, `464a220d`); the only conflict was an import
  block in `TablesServiceImpl`. The reviewer reproduced two merge trees with git's own merge and found the third to differ
  only in that block [read]. LinkedIn main is still `bb8929e1` underneath.
- The `/iceberg` routes and their answers are unchanged: the REST compatibility kit is identical (160 tests, 42 passed, 95
  failed, 23 skipped; the reviewed list untouched) and the config route lists only routes served under `/iceberg`.
- Native routes that map a method keep their paths.
- The test fixtures have a stand-in for his namespace repository, held to one contract together with his real repository
  over a real House Tables service (8 cases).
- Evidence [ran, by the author]: both Iceberg lines compile and pass his tests, the fixtures, the namespace integration
  tests, the service route classes, the Java itest, the Spark 3.5 and Spark 4.2 namespace scenarios and the kit; pull
  request build green on `94528ec9` on both lines (12 test jobs and 2 test-group jobs; the Docker job skipped as on the
  base). His Docker-backed tests ran in CI and passed.

### Behavior changes against the base, in full
All come with his stack, are on by default, have no switch and move away from LinkedIn main. They are now rows BC-044 to
BC-052 of the ledger (part D).

1. New routes at the service root: `POST /v1/{prefix}/namespaces`, `GET` and `DELETE /v1/{prefix}/namespaces/{ns}`,
   `POST .../properties` (writes need SYSTEM_ADMIN, reads GET_TABLE_METADATA). A stored record is not a namespace for the
   `/iceberg` routes or the native database list.
2. `{prefix}` is never validated, so 16 measured requests that had no handler (405 or 400 before) now answer from his
   controller, including `POST /v1/<anything>/namespaces`, which stores a record. Measured against a detached worktree of
   the base [ran]; the reviewer did not re-measure.
3. Spark 3.5 through the OpenHouse catalog on the 1.5.2 line: `USE`, `DESCRIBE NAMESPACE`, `ALTER NAMESPACE` and
   `DROP NAMESPACE` on a database that holds tables but has no record answer `SCHEMA_NOT_FOUND` where main answered "not
   supported"; `CREATE NAMESPACE` succeeds, stores Spark's `owner`, `comment` and `location` as properties, and `SHOW
   DATABASES` does not list the result; `DROP NAMESPACE` of a database with a record and tables removes the record and
   leaves the tables. `DESCRIBE` and `ALTER` change on the 1.12 line too; `CREATE` and `DROP` do not (1.12's catalog still
   refuses the existence check).
4. The Java client's namespace operations call the service; `listNamespaces()` omits created namespaces; over the
   REST-shaped shim `removeProperties` answers false although the key is removed.
5. House Tables runs a schema script at start that creates `catalog_namespaces` with a collation only MySQL 8 has
   (`utf8mb4_0900_ai_ci`); with script initialization off, his DDL file `ddl/0002__add_namespace_metadata.sql` must be
   applied by hand. House Tables also gains `/hts/namespaces` routes [read, not tested].
6. Smaller, in the review's table [read]: one more web client per Java catalog (neutral); the OpenAPI documents and
   generated clients gain a Namespace tag and API (additive); new beans in the tables service.
7. The delta-harness was not re-run; its baseline note records three cases that now fail on the 1.5.2 line (derived, not
   run).

### What the independent review found and how each finding was closed

Round 1 on `cd6f90d8`: merge after fixes (F1 and F2 first). Round 2 on `94528ec9`: merge.

| Finding | Severity | Closed by |
|---|---|---|
| F1: the behavior-change account was incomplete: `USE` on 1.5.2; House Tables start-up; route takeover wider than the three paths listed; populated `DROP NAMESPACE`; Spark's own properties stored | MUST FIX | `0a485be3` and `94528ec9`: every item stated in the description's "Behavior changes against the base" and pinned (`USE` in two scenarios on every path and line; 16 takeover requests with measured before and after; populated drop; the key set `{comment, location, owner}`). Confirmed in round 2 [read]. Measurement corrected the reviewer: the base answered 400 for unmatched paths, not 404, and any prefix creates a record |
| F2: defects pinned as expected behavior (`removeProperties` false on the shim, a created namespace not listed) | MUST FIX | `0a485be3`: each pin carries "KNOWN DEFECT" and its cause; the Javadocs no longer present them as design |
| F3: the stand-in's fidelity was asserted, not checked | SHOULD FIX | `94528ec9`: one contract run against the stand-in and his real store over House Tables on H2; the run found one divergence (an invalid document must raise a bad-request web client exception), fixed |
| F4: the Spark pins describe the embedded store, not a real deployment | SHOULD FIX | Description and Javadoc state the answers hold only with a JDBC House Tables store that has the table |
| F5: delta-harness expectations on the 1.5.2 line | SHOULD FIX | `94528ec9`: the baseline note records the three cases (derived, not re-run); the harness itself is not edited |
| F6: deployment documentation omits the table, the DDL, MySQL 8 and the second namespace API | SHOULD FIX | Not closed. It is his document; listed "for the owner" (`58-waiting-on-mike.md`). `docs/specs/catalog.md` and `housetables.md` also lack the routes |
| F7: `theConfigDoesNotAdvertiseTheOwnersRoutes` could not fail for the reason named | MINOR | `94528ec9`: rewritten so each advertised endpoint is sent under `/iceberg` and must not reach the catch-all |
| F8: private ids and pull request numbers in test comments and output | MINOR | `94528ec9`: replaced by descriptive names |
| F9: feedback for the owner on his stack (drop semantics disagree; nothing resolves effective properties; prefix unvalidated; his service test application lacks his error advice) | MINOR | Not this pull request's to fix; put to him in `58-waiting-on-mike.md` |

### What is left open
- The behavior changes above are pinned, not fixed: existence is the record alone; drop semantics disagree between his
  three pull requests; nothing resolves effective properties; the prefix is not validated; his deployment document is
  incomplete. They are his to decide upstream.
- `/iceberg` namespace routes on his service (D67, in flight on branch `irc/iceberg-rest-namespace-routes`).
- The delta-harness on the 1.5.2 line (derived, not re-run); `use()` in the Spark scenarios restores state with
  `spark.catalog().setCurrentDatabase`, safe only while scenarios start from `spark_catalog` (reviewer's fragility note).
- A JUnit-annotated contract class sits in the fixtures' main source set (MINOR, reviewer accepted).
- The post-merge build of the combination (CI facts above).
- Re-merging his next push re-conflicts on the same import block of `TablesServiceImpl` (the merge message gives the
  procedure).

## What the reviews are finding

Bottom line: every reviewed pull request so far has had a finding its author missed. Reviewers read; they ran nothing, so
what they found is what reading code against the specification and against main reveals.

- 151: expectations derived from the table under test (a merge-on-read table written copy-on-write passed everything).
- 153: an incomplete behavior-change list, and defects pinned as expected behavior.
- 152, not yet merged: a bypass through commits whose updates change nothing (a principal with the write privilege reads a
  legacy-locked or system-only-locked table with `set-properties {}`); the pull request is being reworked.
- 154: none that had to be fixed, and the reviewer showed the original retroactive review was wrong on one point: policy
  statements on a non-Iceberg wrapper catalog did not work on main (they fail at planning on main too), so the resolver
  restore changes nothing visible from SQL.
