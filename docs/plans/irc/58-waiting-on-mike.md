# Waiting on Mike

Bottom line: nothing blocks work, but the reviews of 2026-11-27 and the scout added questions that are his and that are foundations: seven rows of the behavior-change ledger, six questions about the owner's namespace line, and three more from the reviews. Until he answers, the interim in each entry holds. These are the decisions that are Mike's, most of them one-way doors that matter
before any release outside the forks. Moved out of `18-state.md` to keep it under 150 lines. Dated 2026-11-23, restructured 2026-11-27. Only
`01-stated.md` carries Mike's authority.

Order: what was added on 2026-11-28, later (the audit constant, the namespace layer's three differences, the view create, the list of records, his store as a dependency, scan planning, the re-run question being moot), then what was added earlier on 2026-11-28 (the re-run question, what the review of his namespace stack observed, and two interim choices that are stricter or narrower than his routes), then the foundation questions from the reviews, then the scout's questions, then the reviews' other questions, then the earlier
questions, unchanged except where marked MOOT or partly settled. Each entry is a question for him, what happens meanwhile,
and what each answer changes.

## 2026-11-28

Bottom line: three things for him. One is a question about CI (it may become moot), one is a list of what the independent
review of pull request 153 observed in his namespace stack after it was merged (decision D54; his history is untouched and
nothing in the list was changed by this effort), and one is the two places where the interim `/iceberg` namespace routes
(D67) are stricter or narrower than his routes. Nothing blocks work.

### (a) Re-running a CI job (D68)

- Question: is one re-run acceptable for a CI job that ran no test because a download failed (for example `Repository maven
  is disabled` or HTTP 429 from Maven Central)? Asked of him, unanswered. The orchestrator's rule is "never re-run a CI job
  to get green"; it stands (D68).
- Meanwhile: pull request 155 (FF-47, in review) removes the cause: read-only CI jobs take dynamic-version lookups from
  the restored cache, and the cache writer downloads every configuration's files. If it merges and holds, the question is
  moot. Evidence: `records/ci-dynamic-version-resolution.md`.

### (b) Observed in your namespace stack (pull request 153, description section "Observed in the merged namespace stack"; `records/pr-review-153.md`)

None of this was changed; the behaviors that look wrong are pinned in tests as known defects with their causes. Each is his
to decide upstream.
- Drop semantics disagree between 869, 870 and 871: the House Tables note says the public API rejects a drop while a table
  or view occupies the namespace, the tables service drops the record and nothing else, and the 1.5 client maps DROP with a
  400 to "namespace not empty"; nothing can produce that 400 (the DELETE has no body and the path variable is not validated).
- Nothing resolves effective properties for tables, though the title of 871 says it does (his `NAMESPACES.md` disclaims it):
  the only callers of the namespace repository are the four namespace methods of the tables service.
- `{prefix}` is never validated, so `POST /v1/<anything>/namespaces` stores a record, and 16 measured requests that had no
  handler (405 or 400 before) now answer from his controller, among them requests on a database named `namespaces`.
- On Spark 3.5 with the 1.5.2 line: `USE`, `DESCRIBE NAMESPACE`, `ALTER NAMESPACE` and `DROP NAMESPACE` on a database that
  holds tables but has no record answer `SCHEMA_NOT_FOUND`; `CREATE NAMESPACE` succeeds but `SHOW DATABASES` does not list it,
  and it stores Spark's `owner`, `comment` and `location`; a `DROP NAMESPACE` of a database with a record and tables removes
  the record and leaves the tables.
- House Tables start-up creates `catalog_namespaces` with a collation only MySQL 8 has; with initialization off his DDL file
  must be applied by hand. His deployment document omits the table, the DDL file, the MySQL 8 requirement and the second
  namespace API (the read-only `/iceberg/v1/namespaces` next to his records-only routes); `docs/specs/catalog.md` and
  `housetables.md` do not list his routes.
- His own service test application (`SpringH2Application`) lacks his error advice in its component scan, the same gap this
  effort fixed for the shared test fixtures.
- `listNamespaces()` omits created namespaces; over the REST-shaped shim `removeProperties` answers false although the key is
  removed.

Ledger: part D of `85-behavior-change-ledger.md` (BC-044 to BC-052).

### (c) Two interim choices of D67 that are stricter or narrower than his routes

- The `/iceberg` drop refuses a namespace that still holds a table or view, as the Iceberg specification requires; his drop
  removes the record and leaves the tables. Question: which should the `/iceberg` route follow, the specification (as built)
  or his route?
- Narrower than his routes: a list of records is not added to House Tables, and the native view and table create do not
  start accepting a record as proof that a database exists, so a record he stores does not by itself make a database for
  the native create. For `/iceberg` a namespace exists if it has a record or holds a table or view (wider than the
  table-derived existence the routes had; his routes count the record alone). Question: should `/iceberg` list his records
  (a House Tables list), and should his record count as a database for the native create?
- Meanwhile: the interim as stated in D67, built on branch `irc/iceberg-rest-namespace-routes`; moving it later changes the
  mapping, not the layer.

## 2026-11-28, later

Bottom line: seven things for him, from the merges of 152, 155, 156, 157 and 159. Two are decisions only he can make about
the Iceberg REST namespace layer and the native view create (b, c); one is a new API (d); one is a new audit constant (a).
Nothing blocks work; the interim in each entry holds.

### (a) The new audit operation type for an upgrade to format v3 (pull request 156, ledger BC-056)

- Question: with `cluster.iceberg.format-v3.enabled` on, an upgrade of a table to v3 emits an audit event of a new
  operation type, `UPGRADE_FORMAT_VERSION`, because an irreversible operation (it cuts off every Iceberg 1.5.2 reader) must
  be visible. Consumers that deserialize the audit operation type strictly must know the constant before any cluster turns
  the switch on. Is a new constant acceptable, or should the upgrade be a field on the existing update event?
- Meanwhile: the switch is off by default, so nothing emits it. A failure to send the event cannot change the write.

### (b) Three places the Iceberg REST namespace layer differs from his routes (pull request 157)

- His drop removes the record and leaves the tables; `/iceberg` drop refuses a namespace that still holds a table or view,
  as the specification requires.
- His create accepts a database that already holds tables; `/iceberg` create answers 409 (the specification's already
  exists).
- His list (there is none for records); `/iceberg` lists databases derived from tables only, so a record-only namespace is
  not listed.
- As a result, no existing database can get properties through `/iceberg`: create answers 409 and update answers his 404 for a
  database that holds tables (pinned as known defects). Question: which should the `/iceberg` routes follow, the
  specification or his routes, for each of the three?
- Meanwhile: as built (D67, `518a2134`). Moving it changes the mapping, not the layer. The three divergences are listed in the
  pull request description.

### (c) The native view create accepting a namespace record as proof that a database exists (ledger BC-074, FF-51)

- 24 compatibility-kit view tests wait on it: the view suite creates no namespace first, and the native view create needs a
  database that holds a table or a view. It is a one-line native behavior change in the native views service (a view create
  that answered 404 would succeed in a record-only namespace), which this effort will not make without his justification.
- Meanwhile: not done (D67).

### (d) A House Tables list of namespace records (ledger BC-074, FF-52)

- Two kit tests (a created namespace is stored but not listed) and the listing of record-only namespaces wait on it. It is
  his API (a list route in House Tables).
- Meanwhile: not added (D67).

### (e) His namespace store is now a dependency of the Iceberg REST routes (pull request 157, D73, ledger BC-071, BC-050)

- The `/iceberg` namespace writes need it, and a record-only namespace's reads (load, exists, listing, list tables, list
  views) fail while it fails. A namespace that holds a table or view answers as before, with a warning, when the store
  cannot be read (decided D73). It has not been run against his real store on MySQL; his deployment document still omits the
  table, the DDL and the MySQL 8 requirement.

### (f) The scan planning route (D75, FF-05; unchanged question)

- Scan planning is off by default behind `cluster.tables.irc.scan-planning.enabled` (pull request 158, in review). Which
  privilege the route should need when it is on is still his (the same access question as credential vending, D45).

### (g) The re-run question of D68 is moot for now

- The newest builds downloaded nothing beyond the restored cache and saw no refused request (pull request 159's own build,
  all 11 jobs). The question in (a) of the first 2026-11-28 section stays open and unanswered but is not asked of him
  again until a build fails for a refused download.

## Foundation questions from the reviews (2026-11-27)

The owner's rule S60 (`01-stated.md`): a behavior change against LinkedIn main cannot be contributed upstream without
sufficient justification. These seven ledger rows (`85-behavior-change-ledger.md`) follow from deleting the Iceberg 1.2
and Spark 3.1 line, or from moving to Iceberg 1.12 and Java 17, and he was never told the consequences. D63
(`90-decisions-continued-7.md`) is the orchestrator's reading of what goes upstream and is itself to be confirmed.

- BC-024 (shipped artifact `com.linkedin.openhouse:datalayout`). Question: is `libs:datalayout` to stay on Spark 3.1.1 and
  Iceberg 1.2.0.25 on the reference line, or does it now build against Spark 3.5.2 and the line's Iceberg, so that a Spark
  3.1 consumer gets Spark 3.5 and Iceberg 1.5.2 transitively? Meanwhile: nothing changes; it is decided together with
  BC-025. If Spark 3.1 is retired: keep the change and declare it as a POM change. If not: restore it on the reference
  line (Spark 3.1 against Maven Central's runtime).
- BC-025 (the Iceberg 1.2 and Spark 3.1 line). Question: does his S44 ("i don't care about spark 1.2.") mean that
  LinkedIn's Spark 3.1 artifacts (the runtime, the apps and their uber jars, the test fixtures) are dropped from LinkedIn's
  build, including from the reference line that was to be main's build unchanged, with the scheduler image moving to the
  Spark 3.5 apps jar? Meanwhile: deleted on both lines as built (D3 read S44 so, "This is Claude's reading of S44"). If
  yes: keep, and list the dropped artifacts in one upstream commit. If no: the reference line must build them again from
  pinned copies of the shared modules (D3's "Reverse by").
- BC-028 (bytecode target of every module). Question: does the default build drop every Java 8 and 11 consumer of the Java
  client and the Spark runtime jars (the default line now builds Java 17; the reference line keeps Java 8)? Meanwhile: as
  built. If yes: documented. If no: those consumers must be served from the reference line, which then has to be a release
  line (BC-034, FF-26).
- BC-029 (tables that require `column-defaults`). Question: may the default build stop reading and writing every existing
  table that requires `column-defaults` (answer 422 `CLIENT_CAPABILITY_MISSING`), with no migration, on the strength of S8
  ("Migration is not a concern. Valid state is.") and S47 (Parquet over ORC, native Iceberg default values)? Or must W8 (the
  migration) land before the default build stops declaring the capability? Meanwhile: as built; the reference line is
  unaffected. If yes: documented as a consequence of the move to 1.12. If no: W8 first, or the default build keeps declaring
  the capability.
- BC-030 (Spark SQL on the default build). Question: does he accept four Spark SQL behaviors of the default line (`CREATE
  NAMESPACE` and `DROP NAMESPACE` messages; an RTAS dropping a column an old partition spec uses succeeds; `ALTER TABLE ...
  ADD COLUMN ... DEFAULT` refused; one delete file after repeated DELETEs on a file), or must the fork be patched (D14 and
  D31, "Reverse by: patching the fork")? Meanwhile: as built; declared in the smoke tests. If accepted: documented. If not:
  fork patches.
- BC-033 (the S3 stack of the tables service and its published POMs: the fork's `iceberg-aws`, AWS SDK 2.54.17, apache5 as
  the HTTP client). Question: does he accept the new S3 stack on the default build without a run against real S3, or require
  one before it is the default? Meanwhile: as built; "Not run: real S3". If accepted: documented. If a run is required: the
  default flip waits for it.
- BC-035 (any Gradle command on a fresh clone of the default line fails until `publish-local.sh` has built the fork). This is
  the question already below, "where the fork's artifacts are hosted"; it is the same one. Meanwhile: the interim ready
  without him is to fail only the tasks that need Iceberg and check `java -version` in the script. If he hosts them:
  the failure goes away. If he does not: the default line stays a local-build line.

## From the scout (2026-11-27)

The scout's report is `records/scout-namespace-and-prior-work.md`; the summary is `89-scout-namespace-and-prior-work-result.md`.

- (a) Where the Iceberg REST routes live. His 870 serves the namespace routes at the service root on the specification's
  paths (`/v1/{prefix}/namespaces`); this effort's routes are under `/iceberg/v1`. Asked on 2026-11-27, unanswered. Meanwhile:
  D64, the namespace routes are built under `/iceberg` as a thin layer over his service, cheap to move. Root only: the
  `/iceberg` layer moves or goes. `/iceberg` only: his 871 client targets the root, so it would need to follow. Both: as built.
- (b) May the `/iceberg` drop of a namespace refuse while the namespace holds tables, when his route allows it? His three
  pull requests disagree with each other. The scout: "869 body and `services/housetables/NAMESPACES.md` say 'The public API
  rejects drop while a table or view occupies the namespace'; 870 removed that check ('The review update removes the
  namespace occupancy query') and its `services/tables/NAMESPACES.md` says drop does not inspect tables; 871 still maps DROP
  400 to `NamespaceNotEmptyException`"; and his earlier ruling W5 (fork 56) said "the error, never a cascade". The
  specification and the kit expect 409 `NamespaceNotEmptyException`. Meanwhile: the `/iceberg` drop is not built until he
  answers. Refuse: two kit tests pass, and the two drop routes differ. Allow: they match, and the two kit tests stay known
  failures.
- (c) About 20 view tests of the kit need the native view create to accept a namespace record as proof that the database
  exists (`ViewsServiceImpl.requireDatabaseExists`). That is a native behavior change that needs his justification (S60).
  Meanwhile: not done; those tests stay in the known-failure list. If he agrees: about 20 more kit tests pass.
- (d) His rename guard (upstream 700) is 97 commits behind main with conflicts, and this branch serves rename over the
  Iceberg REST routes on the unguarded native rename. Question: will he refresh it, or may it be ported here? Meanwhile: the
  rename race is recorded as a known inherited defect. Refreshed: merged like 869 to 871. Ported: this effort carries the
  change and a note for him.
- (e) His temporary-tables stack (905, 923, 906 to 909, 924) conflicts with this branch's commit core in three files
  (923: `OpenHouseInternalRepositoryImpl.java`, `TablesServiceImpl.java`, `ExpiredTemporaryTableTest.java`; 907 adds
  `OpenHouseInternalTableOperations.java`). Question: when it settles, may his rules that sit in the
  native validator and repository (not in the commit path) be applied over the Iceberg REST routes? Meanwhile: nothing
  merged; his rules in `OpenHouseInternalTableOperations` apply over the Iceberg REST routes by themselves, the others
  only in part.
- (f) The scout says a rule that rollback must never need a restart is in the earlier drafts 55 and 56 ("Rollback must never
  depend on a restart; every intermediate state must be indefinitely sustainable", fork 56 rulings 1 and 1b, fork 55
  "Owner constraint applied throughout"). Is that his rule? This effort's cluster switches (the format v3 switch, the
  variant property) are read at startup, so rolling one back needs a restart. Meanwhile: switches stay off by default.
  If it is his rule: the switches need a way to roll back without a restart. If not: nothing changes.

## From the reviews, needing him (2026-11-27)

- The privilege the scan planning route needs when it is on (FF-05, `86-review-findings-and-fix-forward.md`): it exposes
  per-file column bounds, so a metadata privilege alone is too little; options include `SELECT` or a storage-level read
  check; it is the same access question as credential vending (D45). Meanwhile: D56, the route is off by default.
- The release workflow, and which line releases (FF-26): LinkedIn's release workflow now pushes a tag and then publishes
  nothing (BC-034, RF-051), because the default line cannot publish before the fork is hosted. The orchestrator proposes that
  the reference line is the release line until the fork is hosted; hosting stays his. Meanwhile: FF-26 waits for the orchestrator's decision that the reference line is the release line; hosting the fork stays his.
- The inherited native defects (D62): (1) a replace commit has no version check, so an append between load and replace can be
  lost, as natively for RTAS (RF-004); (2) after a commit fails past `failIfRetryUpdate`, a per-instance cache refuses every
  later commit on that base for five minutes (RF-005). Fixing either changes native behavior and needs his justification
  upstream. Meanwhile: documented, not fixed. If he asks for the fixes: new items with their own justification.

## Earlier questions

- D32: what "conforming Iceberg REST Catalog server" means. Part settled on 2026-11-27 by D55 (`90-decisions-continued-7.md`): the 33 namespace-model failures (a namespace cannot exist on its own; create, alter and drop are not served) follow from a rule the owner's own namespace design contradicts, so stored, possibly empty namespaces are part of OpenHouse and the routes are built over his service. What remains for him is the rest of the question: the 44 failures from OpenHouse rules the kit does not expect (replace needs `replace.enabled` 16, view dialect policy 15, schema and partition evolution rules 13), the 18 small differences, and what conformance is then claimed. The compatibility kit's 95 failures, by cause
  (`60-v11-v12-investigation-result.md`): 33 are the namespace model (a namespace cannot exist on its own, and
  create, alter and drop are not served); 44 are OpenHouse rules the kit does not expect (replace needs
  `replace.enabled` 16, view dialect policy 15, schema and partition evolution rules 13); the other 18 are small model
  differences (6), kit message-text assertions (9), view rename (2) and one kit contradiction (1). None is scan
  planning or credentials.
  Update 2026-11-28 (pull request 157, merge `518a2134`): the kit is at 160 tests, 54 passed, 90 failed, 16 skipped [ran, by
  the author]; the namespace writes are served and the 33 namespace-model failures are mostly gone. What the remaining 90
  failures are, by the cause each line of the kit's reviewed list carries (`expected-outcomes.txt` on `irc-integration`,
  `8a5a9980`; counted by class and by the reason group of each of its 106 non-passing lines with `awk` on the `|`-separated
  fields: 90 FAILED plus 16 SKIPPED, which matches 160 - 54):
  - 76 are OpenHouse model differences the kit has no flag for (class b): 24 the view suite creates no namespace first and
    the native view create needs a database that holds a table or view (ledger BC-074, FF-51: waits on him, (c) above); 16
    replace needs `replace.enabled=true` (policy); 15 views accept the Spark dialect only (13) and refuse reserved view
    properties (2); 9 partition evolution and bucket specs are refused; 4 column drop or rename is refused; 3 a namespace id
    with a hyphen is not an OpenHouse id; 2 the server owns the format version (a create asking for version 1 is refused);
    2 listing a namespace the view suite never created answers 404; 1 the server's drop removes the table's files so the
    kit's register finds no file.
  - 10 are kit or client assumptions that hold only for Iceberg's reference implementation (class e): 8 the kit asserts the
    reference implementation's message text (type and status are right); 1 the kit asserts a never-created namespace both
    absent and present; 1 a missing metadata file answers 500 where the kit expects 404 (the one of these that is the
    server's).
  - 2 are a route the server does not serve (class c): view rename, which OpenHouse does not have.
  - 2 are a defect-class difference (class d): a created namespace (a record, no table) exists but is not listed, because
    House Tables has no list of records (ledger BC-074, FF-52: waits on him, (d) above).
  - The 16 skipped: 15 are tests the kit skips by its own capability flag, which the kit's service sets (class a: names with
    a dot 2, the empty namespace 5, nested namespaces 2, table creation without a namespace 1, names with a slash 2, the
    variant type 3) and 1 is disabled by the kit itself (class e).
  So what he decides ((c), (d), the 44 policy rules of the earlier count, and what conformance is then claimed) is 24 + 2 on
  the namespace model, and the rest are OpenHouse rules or the kit's own assumptions. Classes are each test's first failing
  step; a later step may fail for another reason once that one is gone (the list's own header).
- Whether the server should turn server-side scan planning on for clients (V11d, D44). The route is served either way;
  turning it on changes every client's read path. Nothing waits on this.
- Credential vending needs an identity service and an access contract (V12b, D45): which storages, which service
  (an AWS STS role, an Azure identity), which privilege grants read or write, and the lifetimes. HDFS cannot be vended
  to Iceberg 1.12 clients at all. Nothing waits on this.
- The one-way doors in D12 and D24, before any release outside the forks. D34 adds one: the register and metrics
  route semantics become a public contract at release.
- D35: whether to fix the native directory-reuse defect (N5), because the fix changes native behavior. Four native
  requests can still delete a renamed table's files.
- A veto, if wanted, of the accepted Spark 4.2 differences in D37 (list in `50-n3b-spark42-smoke-result.md`). They
  become user-visible behaviour at release.
- Publishing the fork's Iceberg jars anywhere is a one-way door, and the 1.12 default makes it needed before any
  release. C13, the release workflow's order of tag and publish, belongs with it (D38 addendum).
- D40: whether to build real atomic multi-table commit. It needs a new House Tables endpoint, a regenerated client
  and a split of the commit core, and is a one-way door. No engine sends the route, so leaving it as built is a
  reasonable choice.
- Worker sessions on separate machines, because a bigger container is not available (D21;
  `20-iteration-friction.md`).
- Format v3 (D12, D42, D43): turning the v3 switch on for a real cluster, and upgrading a real table. An upgrade is
  irreversible, and LinkedIn 1.5.2 clients cannot load the table afterwards. The service now supports both behind the
  switch, which is off by default (`57-x1-format-v3-result.md`).
- D46: how a retention backup should treat deletes on a merge-on-read table. Today the backup holds rows deleted before
  retention and nothing records the deletes; on v3 no deletion vector is backed up. The author recommends rewriting the
  expiring partitions with deletes applied first, at the cost of an extra rewrite and a behavior change on v2. It touches
  an external uploader that reads the manifests. Nothing waits on this; behavior is pinned (X8, X9).
- MOOT (2026-11-27): FF-18 restores the 400 that LinkedIn main answered (D57), so this question no longer needs him. Format v3 column types (X5, pull request 129, merged 2026-11-24): on v2 tables a column of a type only format v3 has
  answers 500 "Invalid schema for v2". A 400 would be right but changes a v2 answer. Nothing waits on this.
- MOOT (2026-11-27): FF-18 also makes this a 400 (D57). Format v3 column types (X5): a default value on a `variant` or `unknown` column answers 500 on every table version, from
  Iceberg's parser. Turning it into a 400 changes v2 answers too. Nothing waits on this.
- Format v3 column types (X5, narrowed by D48): `variant` is now off by default behind
  `cluster.iceberg.format-v3.variant.enabled`, so nothing opens by turning on the v3 switch. What is left for Mike is
  whether and when to turn that property on for a real cluster, his call like the v3 switch itself. A table that takes a
  `variant` column can never drop it, cannot be read from Spark 3.5, is not compacted and cannot carry a retention
  policy until the jobs run on Spark 4 (N11). Nothing waits on this.
- D47: whether to add a view-only rename to the House Tables API so the Iceberg REST view rename route can be served.
  Without it a rename is two calls and can leave two names for one view or lose a name. The author recommends a new
  atomic House Tables endpoint, and against a non-atomic version. A new House Tables API is a one-way door. Nothing
  waits on this; the route keeps answering 406 (V21).
- Spark 4.2 jobs (N11, D50, `70-n11-jobs-spark42-scout-result.md`): four things are his: a Spark 4.2 uber jar and image
  with `jobs.yaml` entries and the jobs service's token key (N12, N13); routing `variant` tables or a cluster to those
  jobs and then the `variant` default (N14); job state and optimizer reporting without House Tables access (N15).
  The jobs project and its tests have landed (pull request 135, merge commit `e2823b69`, `73-n11-jobs-spark42-result.md`),
  and what remains is exactly these four deployment items. Nothing waits on them. Until they are decided a Spark 4.2
  platform is not maintainable by Spark 4.2 jobs in a deployment.
- A principal holding only CREATE TABLE on a database: over the Iceberg REST routes its create succeeds; through the
  OpenHouse catalog the table is created and the statement then fails with 403, because the catalog reads the new table
  back and the creator holds nothing on its own table in the test authorization model. Does a deployed policy grant a
  creator access to its own table? Only he can say. Nothing waits on it; a test pins it (`77-t6-n10-result.md`).
- The OpenHouse Java client's connection pool (N17, N16, `78-v19-v20-v22-n16-x9-result.md`) can only be fixed by changing the
  client for every user of it. The jobs now create one pool per job run instead of one per table load, so nothing
  waits on it.
- The jobs' deployment configuration (X17, `79-c20-x11-x12-x13-result.md`): `jobs.yaml` in the docker-compose recipe defines no
  `spark_catalog`, and sort stats collection copies its sample from it. Nothing waits on it; the job's tests point that
  catalog at OpenHouse, as a deployment that runs the job must.
- V23 and V24 (`81-v17-x16-result.md`): two House Tables lookups would remove the listings that remain over the Iceberg
  REST routes: "does any row have exactly this database id" and "rows of a database, live and soft-deleted, with this
  table UUID or a location under this prefix". Nothing waits on them; today's cost is pinned by a test.
- Register-view (V10, D52, `82-v10-x18-result.md`): anyone with CREATE_TABLE on a database can bring back a view another
  principal dropped, from any of its metadata files, older ones too (a view drop removes only the row; the files stay).
  It is audited under the registrant and the view keeps its original creator. If he wants it tighter: the latest file only,
  the original creator only, a stronger privilege, or a cluster switch for the route. Recommendation: leave as is. Nothing waits.
- Register-view content checks (V10): they assume nobody but the service can write into a view directory, which rests on
  storage permissions outside the repository. Question: can a client principal create or write a directory next to a
  table's under the database directory on his storage? Nothing waits on this.
- The House Tables service's default database URL in main source is an embedded H2 (`JdbcProviderConfiguration`), and H2
  2.1 with its default settings can serve a session a row older than a commit that already returned (X16,
  `81-v17-x16-result.md`; X18, `82-v10-x18-result.md`). The test stand-ins are fixed; the default is not touched. Question:
  does any real deployment run House Tables on that default, and should the default URL get the same setting? Recommendation:
  add the setting to the default (one line); his say, because it is main-source configuration. Nothing waits on this.
- Item order: D29.
