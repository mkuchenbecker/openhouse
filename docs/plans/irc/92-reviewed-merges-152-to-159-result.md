# Five more pull requests merged after an independent review: 152, 155, 156, 157, 159 (2026-11-28)

Bottom line: five pull requests merged into `irc-integration` on 2026-11-28, each after an independent sub-agent review
under the owner's standing order S58. `irc-integration` is at `8a5a9980`; 79 pull requests are merged, eight of them under
the standing review order (151 to 157 and 159). What is true now that was not at the last notes commit (`eeb3bf9b`):

1. The unauthorized reads on the Iceberg REST commit and view routes are closed (152). A commit that changes nothing is a
   read and gets every check a table load gets; a view load needs the read privilege; the decision is made before anything
   stored is checked or answered.
2. Native answers are LinkedIn main's again by default (156): no row-length check unless a width is configured, and with
   the format v3 switch off the native answers are main's, with the stated differences below.
3. The Iceberg REST namespace writes (create, update properties, drop) are served under `/iceberg` over the owner's
   namespace service (157). The compatibility kit went from 42 to 54 passed of 160 (54 passed, 90 failed, 16 skipped)
   [ran, by the author].
4. Build jobs no longer die on refused downloads (155, 159): read-only jobs take dynamic versions from the restored cache,
   every job retries a refused request for about two minutes, and the cache writer fetches every configuration's files and
   tries once more. The first pull request build after the writers completed downloaded nothing beyond the restored cache
   in any of 11 jobs on either line and saw no refused request [read, by the reviewer of 159].

Not true yet: pull request 158 (the follow-up to 152's review) is sent back and not merged; pull request 160 (one
existence rule for namespaces) is in progress; the baseline of LinkedIn main is in progress. Nothing is merged into either
fork's main or into LinkedIn's or Apache's repositories.

Sources: the five merge commit messages (`68e2557d`, `4d9362ee`, `0d54d2fd`, `518a2134`, `8a5a9980`), the authors' records
(`records/authorize-first-on-rest-commits.md`, `records/ci-dynamic-version-resolution.md`,
`records/restore-native-service-behavior.md`, `records/iceberg-rest-namespace-routes.md`) and the reviewers' reports
(`records/pr-review-152.md`, `-155`, `-156`, `-157`, `-159`, each with its re-check sections; `-158` for the pull request
that is not merged), under the common brief `records/pr-review-brief-common.md`. The earlier three are in
`91-first-reviewed-merges-result.md`.

Marks: [ran] executed and seen; [read] read in a file, log or the merge message; [inferred] concluded without running.
The reviewers ran nothing: every review claim below is [read] unless marked otherwise. Test and build results are the
authors', as the merge messages report them, except the post-merge reads in the next section.

## Post-merge CI, as read by the orchestrator on 2026-11-28 UTC

- Passed on both lines for the merges of 151, 152, 153, 154, 155 and 156 [read].
- Pull request 157 (`518a2134`): running with nothing failed when last read at 04:20 UTC [read]. Not known to be finished.
- Pull request 159 (`8a5a9980`): not read yet.
- The first pull request build after the cache writers completed (pull request 159's own build) downloaded no file beyond
  the restored cache in any job on either line and saw no refused request [read by the reviewer of 159, in all 11 jobs].

### An error of the orchestrator's

After pull request 155 merged the orchestrator reported that the default line's cache warm-up had run with no failure. That
was wrong. The fetch had been refused by Maven Central and printed 52 failure lines; the orchestrator's check had truncated
the output before them. The author of pull request 159 found it from the logs: the first refused request was a fixed-version
jar (the root project's `otelAgent` download), Central was then disabled for the rest of that Gradle command, 52 lookups
failed and a cache without those files was saved. The next pull request build therefore still downloaded eleven files in four
default-line jobs. The failure lines named no request and the step is outside the job's verdict, so nothing signalled it.
Pull request 159 is the correction. The reviewer of 155 had said the default line's fetch was the one not yet seen and that
nothing would turn red if it failed; both were right, and the orchestrator's check did not look.

## 1. Pull request 152: authorize Iceberg REST commits and the view load before using what is stored (merge `68e2557d`)

Bottom line: closes FF-01, FF-02, FF-03 (decision D53). Two review rounds; the first found that the fix covered only a
literally empty update list.

### What it leaves true
- On the table commit, the one-table transaction and the view commit routes the native privilege decision is made, for the
  authenticated principal on the stored table's or view's own resource, before anything is checked against what is stored or
  answered. The native order is kept: a missing table is 404 for anyone, an existing one is 403 for a denied caller.
- A commit is a read when, after its updates are applied, Iceberg's builder records no change and there is no policy patch.
  Such a commit passes the table load's own check (lock administration on a legacy lock, the read privilege, the system-only
  decision, the client-capability gate) and is audited as a read.
- A view load, and a view commit that changes nothing, need the read privilege on the view. Refusals are audited as the
  native refusals are. Native routes are unchanged.
- Evidence [ran, by the author]: two rounds of tests, each red before its fix (11 of 27, then 5 of 53), each denying one
  privilege and allowing the rest so a test proves which check runs; seven shapes of commit that change nothing on both
  routes; legacy-locked, system-only-locked and replica tables; 961 tests on the 1.12 line and 901 on the 1.5.2 line in the
  Iceberg REST, view, audit and commit-path suites; Spark 3.5 view tests on both lines; pull request build green on
  `33797be0`.

### Behavior changes (all on the `/iceberg` routes, which do not exist on LinkedIn main)
Toward main: none (there is nothing on main to move toward). Away from main: none. On routes that do not exist on main, in
full, as the merge message lists them (ledger part F, BC-061 to BC-067):
- A commit or transaction that changes nothing, by a caller the load refuses: 200 with metadata, 204 or 409 before; now the
  load's refusal (403 without the read privilege or without lock administration on a legacy lock; 423 on a system-only lock;
  422 without a required client capability), audited as a failed read. Allowed callers get the same answer plus a read audit
  event.
- A commit or transaction that changes the table, by a caller without the write privilege: 409 or 400 before; 403 now.
- A view replace without the write privilege: 409 or 400, unaudited, before; 403 with the native refusal's audit event now.
  A view commit that changes nothing without the read privilege: 403.
- A view load without the read privilege: 200 with the definition before; 403 now.
- A principal who may replace a view but not read it can no longer replace it through an Iceberg client, because the client
  loads the view first.
- For every caller: a staged-create completion on an existing table that lists another failing requirement first now answers
  the table-exists failure, not the other requirement's text with the stored value.
- Allowed writes now make the write decision twice.

Decision D70 records the rule behind the first item: which requests are reads is decided by what the request does, not by
whether its update list is empty.

### What the independent review found and how each finding was closed
Round 1 on `478edea6`: merge after fixes (F1 first). Round 2 on `33797be0` (the fix commits `1dd4e003`, `51194711`,
`cd3c0dfd`): merge.

| Round | Finding | Severity | Closed by |
|---|---|---|---|
| 1 | F1: a commit whose updates change nothing (for example `set-properties {}`) still answered the table's full metadata on the write decision alone, past the read privilege, the legacy lock and the system-only lock | MUST FIX | `51194711`: a commit that changed nothing always ends in the load's check; seven shapes tested on both routes. Confirmed in round 2 |
| 1 | F2: the empty commit lacked the load's system-only decision and capability gate | SHOULD FIX | `51194711`: the empty commit gets the full load check |
| 1 | F3: an authorization-backend failure answers 403 on the Iceberg REST table routes where native and the view routes answer 503 (inherited, on more paths now) | SHOULD FIX | Not fixed; pinned as a known defect in a test. FF-48, in pull request 158 |
| 1 | F4: a principal who may replace a view but not read it can no longer replace it through a client | SHOULD FIX (declare) | Declared as a behavior change; Spark tests `cd3c0dfd` pin what a write-only and a read-only principal get |
| 1 | F6: allowed no-change commits were not audited while their refusal was | MINOR | `51194711`: READ audit event, same shape as the load's |
| 1 | F9: the changed Spark pin dropped the client-level denied replace | MINOR | `cd3c0dfd` |
| 1 | F11: duplicated authorization code | MINOR | `51194711`: single read decision in the views service |
| 1 | F12: no tests for the replica, legacy-lock and backend-failure branches | MINOR | `1dd4e003`. The refused transaction's audit event stays untested (declared) |
| 1 | F5, F7, F8, F10: view commit row decided on is not the row used; a real commit answers a writer without the read privilege the full reloaded metadata; a staged-create completion reads the metadata file only to pass a base; the audit kind of a refused commit is approximated | MINOR | Left and declared. F7 judged "not parity but not merge-blocking": the native update answers a summary. Becomes decision D69 and pull request 158 |
| 2 | R1: on a legacy-locked or system-only-locked table a writer without the right who sends a failing requirement still gets a 409 naming the stored UUID or ids, because the lock checks run after the requirement checks; no metadata leaks | SHOULD FIX (ids only) | Not closed in this pull request. Known gap; pull request 158 |
| 2 | A third House Tables read on the view no-change path, with the same narrow window as F5 | MINOR | Not closed |

### Known gaps (merge message, with where each goes)
- Lock checks run after requirement checks on a locked table (R1): 158.
- A real commit answers the writer the full reloaded Iceberg metadata (snapshots, history, file paths) where the native
  update answers a table summary: older than this change, not parity; ledger BC-068; decision D69 (a commit requires the
  read privilege and answers the version it stored); 158.
- When the authorization service fails the table routes answer 403 where the view routes answer 503: FF-48; 158.
- Read, not run: how a deployed authorization handler copes with the second decision.

## 2. Pull request 155: CI, build jobs stop dying on refused requests to Maven Central (merge `4d9362ee`)

Bottom line: closes FF-47 (with 159). Two review rounds; the first corrected the author's diagnosis and found the writer's
new step unexercised and unbounded.

### What it leaves true
- Read-only CI jobs take dynamic-version lookups from the restored cache for up to 20 days and resolve the Spring Boot
  plugin without its marker lookup. Every CI job retries a refused request for about two minutes before giving up (seven
  tries with waits of 2, 4, 8, 16, 32 and 64 seconds, per repository per Gradle command [read, reviewer]).
- The cache-writing job additionally downloads the files of every resolvable configuration of every project, with a
  ten-minute limit and outside the job's verdict. Every job prints the age of the cache it restored and lists what it
  downloaded beyond it, with a warning when the cache is close to the 20-day horizon.
- No build resolves anything different: the dependency reports of every resolvable configuration of every project are
  identical with and without the scripts on both Iceberg lines, and plugin classpaths hold the same modules [ran, author;
  the reviewer compared six before and after pairs and found them identical except the marker node].
- The local build and LinkedIn's release workflows (`build-run-tests.yml`, `build-tag-publish.yml`) are untouched.
- Why: build jobs died before any test ran several times a day across unrelated changes, and this project never re-runs a
  job to get green (D68). A request that Maven Central refuses (429) is retried three times in three seconds and then Gradle
  disables that repository for the rest of the command, so everything after it fails without asking.

### Behavior changes
CI only (`split-build.yml`, `dataloader-tests.yml`, four new scripts under `scripts/ci`): a read-only job resolves a dynamic
version as the writer last listed it, at most 20 days ago, where it asked Central each time; today that cannot change any
selection, and the trade is written in the script and the developer documentation. Jobs can wait about two minutes per
repository per Gradle command on a refusal that persists. Ledger BC-059. Toward main: none (these workflows' jobs do not exist
on main). Away from main: the CI behavior above. Routes not on main: none.

### What the independent review found and how each finding was closed
Round 1 on `7cbfdd61`: merge after fixes (F1 and F2 first). Round 2 on `14adc5c1`: merge.

| Round | Finding | Severity | Closed by |
|---|---|---|---|
| 1 | F1: the author's diagnosis was wrong about the first refused request: it was the Spring Boot plugin marker lookup, not the version list; it is observed in the pull request's own run (three refused HEAD requests, then Central disabled and json-smart and mysql failing without a request) | MUST FIX | `16a21187`: the plugin is mapped to its module without the marker (only in readers, at the requested version); retry properties for every job; the account corrected in the description and record. Confirmed by comparing six before and after dependency reports |
| 1 | F2: the writer's fetch had never run, had no time limit and sat on the post-merge critical path | MUST FIX | `16a21187`: ten-minute limit, runs only when the build ran. The reference line's fetch was exercised in a hand-dispatched run (4 seconds, 29,570 files, 0 failures) [ran, author]. The default line's fetch was NOT seen before merge; the reviewer accepted that because it cannot change a verdict. It then failed on the first post-merge run: pull request 159 |
| 1 | F3: the resolution lag was not written down | SHOULD FIX | Written in the script and the docs. The reviewer withdrew one premise of their own: the open-ended log4j and logback ranges are decided by fixed versions elsewhere in the graph, so no selection can lag today |
| 1 | F4: past 20 days readers silently return to the old failure mode | SHOULD FIX | The age of the restored cache is printed, with a warning from 19 days |
| 1 | F5: `ciResolveDependencies` hid what it could not resolve | SHOULD FIX | Each failure is printed, plus a `<files> files, <failures> failures` line per project |
| 1 | F6: the post-merge check could not fail | SHOULD FIX | The checks read the fetch step's times and its own output and grep for `429` and `Failed to get resource` |
| 1 | F7, F8: the writer condition written twice; documentation incomplete | MINOR | One job-level `GRADLE_CACHE_WRITER`; the script header names the Dataloader job |
| 2 | The docs' "about two minutes" is per repository per command, not per job | MINOR | Not closed |

### Known gaps (merge message)
The default line's fetch step had not run (it did, in 159's account, and failed); read-only jobs downloading nothing once
the writer has warmed the cache (now seen: 159); the marker lookup staying absent on a stale cache. A read-only job that
still must download something can fail on a refusal that outlasts the retries. A workflow run started by hand on a branch
starts from a cold cache. The fetch step is outside the verdict, so its failure shows only in its log.

## 3. Pull request 156: native answers are LinkedIn main's again by default (merge `0d54d2fd`)

Bottom line: closes FF-09, FF-12, FF-13, FF-18, FF-19, FF-40. Three review rounds; the first found a regression with the
switch on, the second a read of the stored table before authorization introduced by the fix for round one, the third
confirmed. Decisions D71 and D72 record the two costs.

### What it leaves true
- With the row-length limit properties unset, no row-length check runs and the store decides, as on main (BC-001 to BC-003).
- With `cluster.iceberg.format-v3.enabled` off, native answers are main's: a create asking for a format version above the
  cluster's is stored at the cluster's version; an update asking for more than 2 gets main's refusal with main's text and
  status; a v2 write-default is stored; no format-version check or metric runs for commits below v3; a v3-only column type on
  a table below v3 gets main's 400; a replace reads its format version from its own base. A table that is already at v3 keeps
  v3's rules when the switch is off.
- With the switch on, v3 behavior is unchanged, including a replace that upgrades a v2 table to v3 with v3-only content.
- A cluster whose configured format version is above 2 with the switch off makes no table of that version: creates and
  replaces are refused with a message saying what to change, and everything else works (the service starts).
- The Iceberg REST commit routes answer 400 for a format-version property they cannot store.
- Nothing is read before authorization for any of these decisions.
- Evidence [ran, by the author]: each test failed before its fix and passes after, on both lines (608 tests on the default
  line and 536 on the reference line in the touched and related classes); pull request build green on `25a64a26`. The
  premise "the reference line is main" was checked by the reviewer against main's own code for every answer pinned; LinkedIn
  main itself was never run.

### Behavior changes, as the merge message splits them
Toward LinkedIn main: all of the above defaults. Also: on the 1.12 line with the switch off a replace of a v1 table on a v2
cluster makes it v2, and a replace of a v2 table on a v1 cluster is refused as a downgrade (both read from main's replace
path; the second turns a replace that worked on this branch into a failure); a replace makes two fewer House Tables reads
(12 to 10 keyed reads on a staged replace [ran, author]); implementations of the extension interfaces written against main
compile again.

Away from LinkedIn main (ledger BC-056 to BC-058):
1. With the switch on, an upgrade to v3 adds an audit event of a new operation type, `UPGRADE_FORMAT_VERSION`, and a warning
   in the log; a failure to send it cannot change the write. Consumers that deserialize the audit operation type strictly
   must know the new constant before any cluster turns the switch on: a question for the owner.
2. For an update, replace or PUT that creates, carrying a v3-only type with the switch off: a caller without the privilege
   gets 403 where main gave 400, and an allowed caller whose request has another validation failure gets that one first and
   alone where main gave all in one 400; creates keep main's order. This is the cost of not reading the table before
   authorization (D71).
3. On the 1.12 line the service, not Iceberg, throws the refusals that equal main's, and type names are spelled as Iceberg
   1.12 spells them. With the switch off and the cluster's format version above 2, creates and replaces are refused with a
   message where main failed them with Iceberg's own text (D72).

On routes that do not exist on main: the Iceberg REST commit routes answer 400, not 500, for a format-version property they
cannot store.

### What the independent review found and how each finding was closed
Round 1: merge after fixes (F1 first). Round 2 on `a0bb671d` (fix commits `be254bea`, `04fa5681`, `b2df562a`, `3312162f`):
NOT YET. Round 3 on `25a64a26` (fix commit `ca0c8e94`): merge.

| Round | Finding | Severity | Closed by |
|---|---|---|---|
| 1 | F1: with the switch on, a replace that upgrades a v2 table to v3 failed with "Invalid schema for v2" (answered 500) when the schema had a v3-only type or an initial default; it succeeded on the base | MUST FIX | `be254bea`: the version-3 request is put into the table properties before the replace transaction is built; the reviewer corrected their own example (`timestamp_ns` was refused on the base too; the real regressions were `unknown` and `initial-default`) |
| 1 | F2: with the switch off, writes to an existing v3 table that has an `unknown` column were refused at request validation | SHOULD FIX | `be254bea`; the fix then introduced R1 below |
| 1 | F3: an Iceberg REST commit with a format-version property above 2, switch off, answered 500 where 400 was right | SHOULD FIX | `04fa5681`: a 400 inside the commit core after authorization; native keeps main's 500 |
| 1 | F4: the upgrade audit event could fail a committed upgrade and adds an enum constant consumers may not accept | SHOULD FIX | `b2df562a`: send wrapped in try and catch and logged; the owner question is in the description |
| 1 | F5: replace format-version changes on the 1.12 line were missing from the author's list | SHOULD FIX (document) | `be254bea`: pinned in `FormatVersionV1ClusterTest`, listed as toward main |
| 1 | F6, F7, F9, F11: refusal earlier in the update on 1.12; hard-coded 1.5.2 text; stale comments; start-up check overstated | MINOR | `3312162f`: documented; named constant; comments fixed; the start-up check became "refuse creates and replaces" (D72) |
| 1 | F8, F10, F12, F13, F14: non-canonical type spellings; guards keyed on the switch alone; partial closures; FF-12 defaults throw on Iceberg REST routes only; placement of the upgrade hand-off | MINOR | Unchanged and accepted as listed |
| 2 | R1: the fix for F2 read the stored table (`tableExists`, then `loadTable`) in the request validator, before authorization, on native create, PUT and snapshots PUT; the answer depended on what was read, so an unauthorized caller could learn whether a table exists at format v3; also not atomic | MUST FIX | `ca0c8e94`: the validator reads nothing; the refusal comes after the privilege check, on the table the check already uses. Costs main's order for updates (D71). Confirmed in round 3 with no path left on which a caller without the privilege gets an answer that depends on the stored format version |
| 2 | The pre-authorization read and the answer that depends on it were not in the behavior-change list | part of R1 | The list now has item 2 above |
| 3 | A snapshots PUT whose table is missing but whose base version is not the initial one skips both checks and reaches Iceberg 1.12's own refusal: 500 where main gave 400 | MINOR | Not closed; known gap |
| 3 | The new test compares status only, not bodies; the allowed-caller case covers only the native PUT | MINOR | Not closed |
| 3 | An Iceberg REST create at a cluster version above 2 with the switch off answers a wrapped 500 (misconfiguration only; no main baseline) | MINOR | Not closed; known gap |

### Known gaps (merge message)
LinkedIn main itself was never run; its answers are read from its code and, where the request path is shared, from the
reference line. A default on a variant or unknown column still answers 500 on v3 tables. With the switch on, a v3-only type
on a v2 table still answers 500. An Iceberg REST commit asking for a lower format version, and an Iceberg REST create on a
cluster configured above 2 with the switch off, answer 500. A snapshots commit on a table dropped since the client loaded it
answers 500 where main answered 400 for a v3-only type. The copied Iceberg 1.5.2 message text lives in shared code, not in
the 1.12-only source set. FF-12 checked BC-010 (clustering-column limit before mapping) and found it not a change: main runs
the same validation in the same order, so no code changed for it.

## 4. Pull request 157: the Iceberg REST namespace writes under `/iceberg` (merge `518a2134`)

Bottom line: `/iceberg` was answering 406 for namespace create, update properties and drop and counted only tables and views
as existence; that was the largest block of kit failures (33 of 95). It is now a thin second front for the owner's
namespace service (decisions D54, D64, D67). Compatibility kit: 160 tests, 42 passed, 95 failed, 23 skipped before; 54
passed, 90 failed, 16 skipped after (five failures and seven skipped namespace-properties tests now pass; four reason texts
corrected) [ran, author]. Spark SQL smoke 34 of 34 on every path that goes through `/iceberg` [ran, author]. Two review
rounds.

### What it leaves true
- Create, update properties and drop call his service with his request classes and his authorization. The SYSTEM_ADMIN
  decision is made for the authenticated principal on the lower-cased database name before anything else is checked or
  answered; a denied caller gets the same 403 for a new name, a name with a record, a name that holds tables and an invalid
  name.
- A namespace exists on `/iceberg` if it has a record or holds a table or view. Load answers the record's spelling and
  properties to a caller with the read privilege; a caller without it learns nothing about a record.
- Drop refuses a namespace that still holds a table or view, as the specification requires, in one method.
- For a namespace that exists because it holds a table or view, every answer is as before, also while the namespace store or
  the authorization service cannot be reached (a warning is logged) (D73).
- His routes, the native routes and the Spark 3.5 OpenHouse-catalog paths are unchanged; no file of his stack is edited.
- Evidence [ran, by the author]: route tests; authorization tests that deny one privilege, and separately one resource, and
  allow the rest; failure tests with the store and the authorization service failing; a lost race; a real Iceberg
  `RESTCatalog` client; the pinned House Tables reads per route; all on both Iceberg lines; pull request build green on
  `63c65e76` on both lines.

### Behavior changes (all on `/iceberg` and its clients, which do not exist on LinkedIn main; no switch)
Ledger part F, BC-069 to BC-072. As the merge message lists them:
- Create, update properties and drop are served where they answered 406: create 200 or 409; update 200, 404 or 422; drop
  204, 404, or 409 when not empty; 403 for a refused caller; a lost race 409, which Iceberg's clients read as already-exists
  or not-empty.
- A namespace that has a record exists for load, exists, the parent listing, list tables and list views; a rename into a
  namespace with only a record answers the cross-database refusal where it answered not-found.
- Load, and the misses of the existence routes, make one keyed record read and the namespace service's read decision; a
  denial changes no answer but an authorization ledger records it. A record-only namespace's reads, and all three writes,
  fail while the store fails.
- Spark SQL over `/iceberg` (Spark 3.5 over plain REST on both lines; Spark 4.2): `CREATE NAMESPACE` succeeds and stores
  Spark's owner property; `USE` of it succeeds; `ALTER NAMESPACE SET DBPROPERTIES` on a database that holds tables answers
  schema-not-found; `DROP NAMESPACE` of one answers not-empty (raised as already-exists by the Iceberg 1.5.2 client).

Three divergences from his routes, listed for him in the pull request description: the drop refusal for a non-empty
namespace; the conflict on creating over a database that holds tables; the list derived from tables only
(`58-waiting-on-mike.md`).

### What the independent review found and how each finding was closed
Round 1 on `362de10d`: merge after fixes (F1 first). Round 2 on `63c65e76` (fix commits `c3c461e3`, `09095433`,
`63c65e76`): merge.

| Round | Finding | Severity | Closed by |
|---|---|---|---|
| 1 | F1: existing read routes had come to depend on two new services (the authorization service and the namespace store: a 30-second read with no retry) with no test and a false statement in the description that these routes make no authorization decision | MUST FIX | The orchestrator decided that a namespace that holds a table must answer as before (D73). `09095433` and tests `c3c461e3`; the reviewer also corrected their own text: an authorization-service failure answers 403 on these routes, not 503 (pre-existing; FF-48) |
| 1 | F2: create answers 409 for a table-holding namespace, and update answers 404, so no existing database can get properties through `/iceberg`; a third divergence from his routes not put to him | SHOULD FIX | Named in the description and in "For the owner"; `58-waiting-on-mike.md` |
| 1 | F3: update of a table-only namespace says "does not exist" while load says it exists | SHOULD FIX | Under "For the owner", with the 406 alternative |
| 1 | F4: a lost race reaches clients as the wrong exception | MINOR | Pinned (409 `CommitFailedException`), clients' readings asserted through Iceberg's own error handlers |
| 1 | F5: the authorization tests could not tell the resource decided on | MINOR | Deny-on-resource tests; the lower-casing is pinned |
| 1 | F6: the drop's view probe ignored the views gate | MINOR | Changed (gate added to the holding check), not as the reviewer advised: this produced F12 |
| 1 | F7, F9, F10, F11: path names not pattern-checked; kit reason text; Spark's own properties stored; a private id | MINOR | Listed for the owner; text corrected; pinned on Spark 3.5 and 4.2; id removed |
| 1 | F8: duplication and reach into internals | MINOR | Unchanged; accepted |
| 2 | F12 (new, from the F6 change): with the views gate in the drop and create probe, a database that holds only views that are hidden by the gate exists for HEAD, load and list views but not for the writes: drop answers 404, and create stores a record over an existing database | SHOULD FIX | Not closed in this pull request ("to be fixed next"). Decision D74 and pull request 160: one existence rule |
| 2 | A load of a name with no record and no table reads the record twice (pre-existing at round 1, missed there) | MINOR | Not closed |

### Known gaps (merge message)
A namespace with a record and no tables is not listed (House Tables has no list of records: the owner's API). A database
that already holds tables cannot get properties through `/iceberg` (create answers 409 where his own create accepts it;
update answers his 404): pinned as known defects. The native view create does not accept a record as proof that a database
exists, which is what 24 of the remaining kit view tests need: a native behavior change, the owner's to justify. With views
disabled, a database that holds only views can be given a record by create although the exists route says it exists (F12).
An authorization-service failure answers 403, not 503, on these routes as on the other `/iceberg` table routes. Not run
against the real namespace store on MySQL.

## 5. Pull request 159: the cache writer's fetch says why it was incomplete and tries once more (merge `8a5a9980`)

Bottom line: closes the remainder of FF-47. One review: merge as is, no finding that had to be fixed.

### What it leaves true
- Each failure of the writer's fetch prints its innermost cause (the request and its status). When failures are left the
  fetch runs once more after a minute in a new Gradle command; anything still failing raises a warning annotation on the
  run. The step stays outside the verdict with its ten-minute limit over both attempts and cannot turn a red result green.
  The product build is untouched.
- Evidence: the two writer logs (52 failures in the first, none in the second, which fetched every file the pull request
  jobs had downloaded) [read]; this pull request's own build, which restored the completed cache, downloaded nothing in any
  job on either line and saw no refusal [read, by the reviewer, in all 11 jobs]; the step's logic exercised locally with
  failing and recovering counts and with Central unreachable [ran, author, against a mock].

### Behavior changes
CI writer jobs only: a second fetch after a minute when the first left failures, and a warning. Ledger BC-060.

### What the independent review found
One round, on `b5ec038d`: merge as is. The diagnosis was confirmed against the logs (the first writer's fetch stalled from
02:50:34 to 02:52:11 and printed 52 failure lines; the cache was saved after it; the second writer fetched in 5 seconds with
no failure and 29 files downloaded in its own job). The retry was confirmed to re-run no test and no job and to be unable to
turn a red result green.

| Round | Finding | Severity | Closed by |
|---|---|---|---|
| 1 | F1: the documented check command also matches the step's own echoed script line | MINOR | Not closed |
| 1 | F2: a timeout during the second attempt leaves no "Fetch failures left" line and no warning | MINOR | Not closed |
| 1 | F3: the warning's wording when the count is not a number ("left build failed failures") | MINOR | Not closed |
| 1 | F4: the fetch uses `gradle.buildFinished`, a Gradle API that goes away in Gradle 9 | MINOR | Not closed |

### Known gaps (merge message)
The second-attempt path and the warning have never run in CI (writers run only after a merge). A second refusal still leaves
the cache incomplete until a later writer. If the first attempt stalls for most of the ten minutes the step is killed before
it can warn.

## What the reviews found, in total

Bottom line: every one of the eight pull requests reviewed under the standing order had a finding its author missed, except
two (154 and 159) where the reviewer found nothing that had to be fixed. Reviewers read; they ran nothing, so what they
found is what reading code against the specification, the logs and main reveals. Rounds in parentheses.

- 151 (2): expectations derived from the table under test, so a table silently written copy-on-write passed everything.
- 152 (2): a commit whose updates change nothing still returned metadata on the write decision alone.
- 153 (2): an incomplete behavior-change list, and defects pinned as expected behavior.
- 154 (1): none that had to be fixed, and a correction of the retroactive review: policy statements on a wrapper catalog fail
  at planning on main too.
- 155 (2): the author's diagnosis was wrong about the first refused request (the plugin marker, not the version list), and the
  writer's new step was unexercised and unbounded.
- 156 (3): a regression with the switch on (a replace upgrading to v3 failed), then a read of the stored table before
  authorization introduced by the fix for round one.
- 157 (2): existing read routes had come to depend on the namespace store, with a false statement that they did not.
- 159 (1): none (four minor notes).

Totals: 15 rounds over eight pull requests; 9 MUST FIX findings: eight in first rounds (151 F1, 152 F1, 153 F1 and F2,
155 F1 and F2, 156 F1, 157 F1) and one that only a re-check found (156 R1).

### The pattern worth stating

Twice a fix for a review finding introduced a new defect that only the re-check caught:
- 156 round two: the fix for F2 (existing v3 tables refused with the switch off) read the stored table before authorization
  on native routes, the very pattern pull request 152 had removed from the Iceberg REST routes.
- 157: the change for F6 (the drop's view probe honors the views gate) made a view-only database exist for reads but not for
  writes (F12); the second round found it and pull request 160 corrects it.

Both were found only because the same reviewer read the fix commits (D66). A third case is the orchestrator's own: a check
of a CI log that cut the output short (above), found by the author of the next pull request. The rule that follows: a fix
for a finding gets the same reading as the change it fixes, and a check of a log reads the whole of the step it is about.
