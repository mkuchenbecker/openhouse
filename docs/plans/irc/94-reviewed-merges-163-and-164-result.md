# Two more pull requests merged after an independent review: 163 and 164 (2026-11-28)

Bottom line: two pull requests merged into `irc-integration` on 2026-11-28, each after an independent sub-agent review under
the owner's standing order S58: 163 (merge `a0239ad7`, about 08:42 UTC) and 164 (merge `35bd941c`, about 09:30 UTC).
`irc-integration` is at `35bd941c`; 83 pull requests are merged, twelve of them under the standing review order (every
number from 151 to 160, and 163 and 164; 161 and 162 are open). What is true now that was not at the last notes commit
(`b0e3c3bf`, which recorded 81 merged through 158):

1. There is one House Tables stand-in for the tests, and it is held to the real store by one contract (163). Almost every test
   of the tables service runs against that stand-in, so a green test used to prove the stand-in, not the product. The contract
   (30 cases) passes on the production repository over HTTP to a real House Tables service and on the stand-in in both suites;
   before, the two old stand-ins passed 7 and 4 of the 30. No production file changed.
2. Messages, comments, build files and docs no longer cite private planning ids, and a CI check keeps them out (164): no
   tracked file outside the delta harness cites one unless the line is reviewed in `scripts/ci/private-ids-allowed.txt`.
3. The faithful stand-in exposed three defects of LinkedIn main's code that nothing had seen, and one of this branch's own
   (RF-069 to RF-071, RF-012). The first three are pinned by tests and reported, not fixed (D62 extended); RF-012 is fixed in
   the follow-up (FF-54), which is in flight.

Not true yet, and not claimed: the recording of LinkedIn main (draft pull request 161), the minors of the `/iceberg` handlers
(draft pull request 162) and the follow-up to 163 are in flight with no merge; the real-store contract runs on H2, not MySQL
with the production DDL; the release note for the fixtures module's changed surface is still owed; whether the deployed policy
reads the table UUID that authorization receives as null (RF-069) is not verified by anyone. The compatibility kit is unchanged
at 160 tests: 54 passed, 90 failed, 16 skipped (CORRECTION 2026-11-28, from pull request 167: the kit then ran with format v3 hard-coded on, so this is the format v3 run; the default configuration is 52 passed, 92 failed, 16 skipped) [ran, by the author of 163 and by its reviewer from the CI logs]. Nothing is
merged into either fork's main or into LinkedIn's or Apache's repositories.

Sources: the two merge commit messages (`a0239ad7`, `35bd941c`), the authors' records (`records/house-tables-stand-in-fidelity.md`,
`records/private-ids-out-of-code.md`) and the reviewers' reports (`records/pr-review-163.md`, `records/pr-review-164.md`,
`records/pr-review-164-recheck.md`), under the common brief `records/pr-review-brief-common.md`. Marks: [ran] executed and
seen; [read] read in a file, log or merge message; [inferred] concluded without either. The reviewers ran nothing here, except
that the reviewer of 164 ran the pull request's own check and his own scanners: every other review claim below is [read].
Test and build results are the authors', as the merge messages report them.

## Post-merge CI, as read by the orchestrator on 2026-11-28 UTC

- 160 (`96acfca3`): green [read].
- 158 (`4b9d152d`): in progress with nothing failed at 07:10 UTC [read]. Not known to be finished.
- 163 (`a0239ad7`) and 164 (`35bd941c`): not read.
- Pull request builds before merge: 163 on `88bec497`, 13 jobs green and 1 skipped by design; 164 on `f4ae7353`, 13 jobs green
  (the re-check counts 14 completed successfully, with the reference line's Docker job and the wrapper jobs skipped as is normal)
  [read, merge messages and re-check].

## 1. Pull request 163: one House Tables stand-in, held to the real store by one contract (merge `a0239ad7`)

Bottom line: closes FF-32 and FF-33 (RF-062; the test side of RF-012; BC-039). One review report, on head `88bec497`: MERGE AFTER
FIXES, whose only must-fix, F1, was a condition on merge order with pull request 161, not a defect of the diff; 163 merged first.
Findings F2 to F8 were not closed before the merge: the merge message lists them as known gaps, and they go to the follow-up
(FF-54). The head reviewed is the head whose CI the merge message reports [read]; no second review report exists.

### What it leaves true
- One contract of the table-row repository (30 cases: find, save with its version check, delete, soft delete, restore, rename,
  listings with paging, sorting and filters, the database list; success, not-found, conflict, letter case, lengths), written from
  the real implementation. It passes on the production `HouseTableRepositoryImpl` over HTTP to the production House Tables
  service (in the test JVM, random port, H2 in MySQL mode with the bootstrap schema) and on the stand-in in both suites.
- There is one stand-in (`HouseTablesH2Repository` in the fixtures module, with its fragment `HouseTablesH2StoreImpl`). The services
  copy, its toggle repository, its properties and its duplicate read-after-commit test are deleted. Its lock, its soft-deleted
  store and its database belong to one application context; its reads never come from a caller's persistence context (they are
  projections, and what it inserts is detached); its H2 settings come from a `DataSource` bean and cannot be overridden by a property.
- The real-store run (`:services:tables:namespaceIntegrationTest`, 30 of 30 in CI) is part of `check`, runs in the `rest` job of
  both lines, and cannot be skipped by a tag or an assumption [read, reviewer, CI logs].
- No file under `services/*/src/main`, `iceberg/`, `cluster/` or `client/` is in the diff [read, reviewer from the stat].

### How the stand-ins differed from the real store, before and after

| Operation | Real store | Before (the old stand-ins) | After (pull request 163) |
|---|---|---|---|
| Restore over a live table or a view | 409 | Overwrote the live row | 409, nothing changed |
| Restore of a missing soft-deleted row | 404 | Did nothing or the wrong exception type (`NoSuchUserTableException` where the catalog maps the 404 to `NoSuchSoftDeletedUserTableException`) | 404 |
| Rename of a missing row or of a view | 404 | Silent no-op | 404 |
| Delete of a missing row | 404 | Silent no-op | 404 |
| Rename to another database, or onto its own name | 400 (`HouseTableCallerException`) | Occupancy checked at the wrong key; could overwrite an occupant (the services copy moved the row) | 400 |
| Rename onto a name differing only in letter case | 409 (case-insensitive key) | 204, two tables | 409 |
| Save of a table over a view row, or of a view over a table | Conflict (the row's entity type is checked) | Overwrote the row (the save did not stamp the type, and merged) | Conflict; reads and writes are JPQL or projections, no merge |
| Column widths (location, table id) | Production DDL 255; bootstrap schema 128 and 512; soft-deleted table id 128 | Whatever H2 gave | 255 in the stand-in (the production DDL), per-store widths in the contract; a wider value is a 409; the soft-deleted 128 is not reproduced (declared) |
| Soft-deleted listing: case, sort, page size | Ignores case; sorted as asked (default `databaseId`); the page has the requested size | Exact case; the page-count was used as the page size (`pageSize` 1, `size` 1 in the recording); unsorted | Closed |
| Descending page request | Comes back ascending (the repository sends only property names, RF-071) | Honored descending, and two tests relied on it | Ascending; those tests corrected |
| Row fields: UUID, creator, URI | None (`UserTable` has no UUID field) | Kept | Null |
| Read of a missing row (`findById`) | 404, an exception | Empty | Throws |
| Lock and soft-deleted store | Per-row versions, per service | One JVM-wide lock and a static map shared by every application context | Instance fields of the fragment bean, one per context |
| H2 result reuse | n/a | Turned back on by a lowest-precedence property source a consumer could override | `DataSource` bean with a fixed URL, `OPTIMIZE_REUSE_RESULTS=FALSE`; no property can undo it |
| Two lookups (views by database; find by id in the services copy) | Ignore case | Exact | `upper()` in the key and in-database lookups |
| Two copies | n/a | Disagreed with each other (7 and 4 of the 30 cases passed) | One copy, 30 of 30 |

Sources: the review's section 5 table and section 4, the merge message, `records/house-tables-stand-in-fidelity.md` [read].

### Behavior changes
- None in the product. For tests and for outside consumers of the published fixtures module (ledger BC-039, updated): the
  answers above, only the fields House Tables keeps, and a changed public surface (the static soft-deleted map, the lock, the
  derived finders and the properties resource removed; a `DataSource` bean and four new types added; a consumer's own
  `DataSource` bean or `spring.datasource.*` settings no longer take effect). A release note is owed.
- Existing tests whose expectation changed, each on the real store's evidence (the merge message): case-only rename answers
  (404 to the same table; 204 to 409), a second restore (404 to 409), the exception type of a restore with nothing to restore, page
  order and view lookups in the stand-in's own tests, row fields in golden files, and the two tests below that depended on row UUIDs.

### What the faithful stand-in exposed (pinned or reported, not fixed here)
- RF-069: the Iceberg REST view load, and by the reviewer's reading the native DROP TABLE, authorize a resource built from the
  House Tables row, which has no UUID in production. The same code is on LinkedIn main. Pinned for the view load
  (`IrcViewRoutesAuthTest`); the drop is not pinned in 163. Whether the deployed policy keys on that UUID is not verified.
- RF-012 (extended): the register-view check that refuses a directory named for a live table's UUID compares against row UUIDs
  and so cannot fire in production. It is this branch's own code. `IrcRegisterViewRouteTest.aDirectoryNamedForALiveTablesUuidIsRegistered`
  now pins 200 where the test expected 400. Fix: FF-54.
- RF-070: the clean-up after an I/O failure in the table commit path calls a delete that the real repository refuses with an
  exception the catch does not cover. The same code is on LinkedIn main. Reported, not pinned in 163.
- RF-071: on the real store a descending page request comes back ascending; a cross-database or same-name rename is 400.

### What the independent review found
One report (`records/pr-review-163.md`), head `88bec497`, base `4b9d152d`, read only; CI read from the check runs and the logs of
the two `rest` jobs.

| Finding | Severity | State at merge |
|---|---|---|
| F1: 163 changes about a dozen answers that 161's `commit-path.tsv` records as "LinkedIn main's" (they are main's old stand-in's: every `GET .../softDeletedTables` page size, the case-only rename pair, the second restore); whichever merges second turns `CommitPath*Test` red unless it declares them | MUST FIX (merge-order condition) | Satisfied by merge order: 163 merged first, and 161 declares them (D79) |
| F2: two tests (`IrcWriteRoutesAuthTest`, `IrcViewRoutesAuthTest`) still call `entityManager.clear()` under a comment that is no longer true | SHOULD FIX | Open; in the follow-up |
| F3: the DROP-path null UUID and the clean-up's unsupported delete are found but not pinned | SHOULD FIX | Open; pinned in the follow-up; RF-069, RF-070 |
| F4: undeclared stand-in differences: name validation on reads, deletes and listings (the real store answers 400); `saveAll`, `count`, `findAll(Sort)` and others that Spring Data still serves and the real repository refuses; sort order of names with digits and underscores (Java against MySQL's collation); soft-deleted rows not transactional | MINOR | Open; in the follow-up |
| F5: no contract case for the paged database list (`findAll(Pageable)`, used by the catalog) or for the database spelling a rename writes | MINOR | Open; in the follow-up |
| F6: the view tests now assert "some UUID" where they asserted the view's own, so a root named for the wrong UUID would pass | MINOR | Open; in the follow-up |
| F7: the fixtures' public surface changed with no release note yet | MINOR | Open; the merge message says the note is owed |
| F8: the author's record says the location columns were widened to 512; the code does not | MINOR | Open; in the follow-up (the record carries a correction line) |

Checked and found sound (the reviewer): the contract's expectations against the production code end to end (error mapping and
retries, the service, the repositories, the mappers, the validator, the controller defaults); no existing test disabled or weakened
without evidence from the real store, apart from F6; no `@Disabled` or assumption added; no private ids in added lines; no overlap
with 162's files; the compatibility kit's 106 non-passing tests exactly its listed outcomes.

### Known gaps (merge message)
- The real-store run is H2, not MySQL, and uses the bootstrap schema, not the production DDL (collation order, strict mode and the
  soft-deleted table's 128-character id are not covered): FF-56.
- No client-side error, 5xx or retry path is exercised. The stand-in serializes all writes where the store locks per row.
- No contract case for the paged database list. Two `entityManager.clear()` calls remain under a comment that is no longer true.
- The stored recording in open pull request 161 was taken with main's old stand-in: about a dozen commit-path answers there differ
  and must be declared as stand-in differences when it merges this base (D79).

## 2. Pull request 164: private planning ids out of messages, comments, build files and docs; a CI check (merge `35bd941c`)

Bottom line: closes FF-34 and FF-49 and the comment part of FF-35 outside the delta harness (RF-045). Two review rounds: the first
(MERGE AFTER FIXES) found an id wrapped across a comment line and 14 lines of the fork's developer document that the guard
skipped; the second (MERGE) found nothing open that blocks.

### What it leaves true
- No tracked file outside the delta harness cites a private id (a work item, a decision, a review id, this fork's pull request
  number) unless the line is reviewed in `scripts/ci/private-ids-allowed.txt`.
- `scripts/ci/check-private-ids.py` (a self-test, then a scan of the whole tree with wrapped comment lines joined) enforces that
  in the default line's `plan` job. It scans the whole tree, not a diff against a base, so it keeps working after the branch is
  re-implemented on a fresh base.
- The four refusals that cited work items say the same thing in plain words; the one that told the caller nothing actionable now
  says what to do.
- The CI step that checks that the apps jar carries no `log4j2.properties` fails when the jar is missing or cannot be listed (it
  passed before).
- Evidence [ran, by the author]: tests pinning the old texts written first and seen failing (6 of 37), passing after, on both
  lines; the jar step run against four jars (missing, not a zip, with the file, without); the guard passes on the head, refuses
  481 lines on the base before this merge and gives no false failure on LinkedIn main's tree (25 matches, all reviewed).
  Re-check [ran, by the reviewer]: the guard on the head (2.8 s), its `--self-test` (0 failures, 11 positive and 9 negative
  examples), the base (481 refused), LinkedIn main (25 matches, all in the allow-list).

### Behavior changes (ledger BC-089 to BC-091)
- Message text only, of four refusals that are new on this branch and do not exist on LinkedIn main: a format v3 type not
  supported; the column-defaults opt-in on a format v3 table; format v3 replication not supported; the Gradle publish refusal.
  Exception type, code, HTTP status and trigger are unchanged (the reviewer: `git diff -w` shows only the string literals).
- The opt-in refusal now reads "... for format v2 tables only, and OpenHouse does not translate a table's stored defaults to
  native ones. Keep a table that sets it at format version 2, or create the format v3 table without the property."
- Fork CI: the `plan` job fails on a private id; the apps-jar step stops on an unreadable jar. Test log labels and the reason
  texts of declared smoke differences changed (test output only; the compatibility kit's reason column is free text and its
  ratchet compares only test name and outcome).

### What the independent review found and how each finding was closed
Round 1 on `d57bb536` (9 commits, 130 files): MERGE AFTER FIXES. Round 2 on `f4ae7353` (re-check): MERGE.

| Round | Finding | Severity | Closed by |
|---|---|---|---|
| 1 | F1: "work item V1" wrapped over two comment lines in `CommitPathUpdateRulesTest` passed the line-based guard | MUST FIX | `0145cfcb` (the sentence says it in words) and the guard now joins wrapped comment lines (`bc95534a`). Confirmed in round 2: the reviewer's own multi-line search over the merged tree found 0 hits |
| 1 | F2: `docs/development/iceberg-fork.md` kept decisions and work items on 14 lines, the guard skipped all of `docs/` and every `*.md` | MUST FIX | `d7e59c7c` (the 14 lines rewritten in words) and `bc95534a` (docs scanned). Confirmed in round 2: five lines checked against the planning notes, faithful |
| 1 | F3: the stated reason for skipping the delta harness (its ids are defined in its guide) is true only in part: about 26 lines cite documents that are not in the repository | SHOULD FIX | The guard's docstring was reworded in `bc95534a` (per the author's log); the 26 lines themselves are left on purpose (D82); FF-58 |
| 1 | F4: the guard is easy to get around (letter set, V0 to V3 only as "(V1)", pull request numbers bounded at 162, `*` allow-list entries that pass a token on any line) | SHOULD FIX | `bc95534a`: docs scanned, wrap join, letters P and J, unbounded pull request numbers, bare `#N` from 80 to 999, exact entries, `--self-test`. Blind spots left: identifiers, the letters B, E, G, H, O and R, "(V2;" |
| 1 | F5: names that encode ids were only partly listed as left (five method names; test data named from work items in many files) | SHOULD FIX | Left on purpose and held by allow-list entries "test data name, rename pending" (D82); FF-57 |
| 1 | F6: some rewritten comments lost the reason the id carried (`IcebergRestViewRegistration`) | MINOR | `0145cfcb` (per the author's log) |
| 1 | F7: the allow-list's reason for two section labels was inaccurate | MINOR | `bc95534a` (per the author's log) |
| 1 | F8: the new opt-in refusal could say what to do more directly | MINOR | `9b1d5daa`; the re-check read both pieces of advice as working (a v3 create without the property is not refused; a v2 table that sets it stays v2) |
| 2 | One dead allow-list entry (the deleted Spark 3.1 `ColumnDefaultSparkITest`, `#267`); the guard's remaining blind spots | can follow | FF-53 (list for 164) |

Also checked in the re-check [read]: the merge of the base (`f4ae7353`) altered no file of pull request 163 (132 files changed
on both sides equal; six files changed by both sides differ from 163 in comment lines only); CI on `f4ae7353` all green, the plan
job's step "Check for private planning ids" succeeded.

### Known gaps (merge message)
- Identifier names that encode ids (five test and method names) and test-data names derived from ids (tables and jobs named
  `x5...`, `x6...`, `x10...` in sixteen files) are not renamed, because other open branches touch those tests; each is held by an
  allow-list entry (FF-57).
- The delta harness keeps about 26 lines citing ids and documents that are not in the repository; it is not built by CI and is
  the guard's one declared skip (FF-58).
- The guard does not look inside identifiers and does not cover the letters B, E, G, H, O and R. One allow-list entry matches a
  file that no longer exists.

## In flight, not results

Recorded as in flight, with what is known and no predicted results.

- **Pull request 161** (`irc/baseline-of-linkedin-main`; FF-20, FF-21 and FF-22 in part): a stored recording of what LinkedIn
  main `bb8929e1` answers, compared in CI on both Iceberg lines. First review: MERGE AFTER FIXES (findings M1 to M3 and S1 to S6,
  `records/pr-review-161.md`); the author is closing them and declaring the test-double differences that pull request 163
  created (D79). Its findings so far, which are results, are in the ledger section "What the recording of LinkedIn main
  measured" (from pull request 161, not yet merged): on the reference line 9 differences from main, all from the owner's namespace
  stack; on the default line 17, including two causes that were in no ledger row (BC-092, BC-093) and a third that the reviewer
  found (BC-094); no difference on write statements on either line.
- **Pull request 162** (`irc/iceberg-rest-handler-minors`): items of FF-53 and findings C1 and C3 of the re-check of 158. First
  review: MERGE AFTER FIXES (one missing test, F1, a namespace that holds only a view during an authorization outage); the author
  is closing the findings and merging the new base. D83 records one decision about it.
- **The follow-up to 163** (branch `irc/stand-in-follow-up-and-register-view-uuid`, no pull request yet; FF-54): makes the
  register-view directory check work on what production has (RF-012), pins the inherited gaps with tests, lists every place
  authorization receives a null UUID, and closes findings F2 to F8 of `records/pr-review-163.md`. Its record lists commits pushed;
  what is next is merging the base, running the suites and opening the pull request.

## Pointers

- Ledger: `85-behavior-change-ledger.md`, BC-039 (updated), part B (BC-089, BC-092 to BC-094), part C (BC-090, BC-091), and the new
  section on what the recording of main measured.
- Findings: `86-review-findings-and-fix-forward.md` (status at the top; RF-069 to RF-071 and the extension of RF-012; FF-54 to
  FF-58; FF-53's list has the re-check of 164).
- Decisions: `90-decisions-continued-7.md` D79 to D83 (new) and D62 (extended). Waiting on the owner: `58-waiting-on-mike.md`.
- State: `18-state.md`. Merged list: `45-merged-pull-requests.md`.
- Records: `records/pr-review-163.md`, `records/pr-review-164.md`, `records/pr-review-164-recheck.md`,
  `records/house-tables-stand-in-fidelity.md`, `records/private-ids-out-of-code.md`; in flight `records/pr-review-161.md`,
  `records/baseline-of-linkedin-main.md`, `records/pr-review-162.md`, `records/iceberg-rest-handler-minors.md`,
  `records/stand-in-follow-up.md`.
- Where the brief and the records differ: the merge message of 163 says about a dozen commit-path answers differ in 161; the
  author's record of 161 says 16 stand-in differences are declared. The merge message of 164 says the identifier names are "listed
  with proposed names"; that list is not in the message text or in the author's record (the method names are in the review F5).

## Later the same day (added after pull requests 162, 165 and 161 merged)

Superseded in part: the three pull requests listed above as in flight merged afterwards (162 as `67989d16`, 165 as `35cc7b1e`, 161 as
`81cb4b74`); see `95-reviewed-merges-162-165-161-result.md`. Correction to this file: the stored recording of 161 was not taken with
"main's old stand-in" for the commit path; it was taken with this branch's earlier stand-in of `services/tables`' tests copied into
main (`e2e/h2/HouseTablesH2Repository` at `0fe4678a`), and 161 declares 16 test-double differences on each line, not "about a dozen".
The question this file leaves open on RF-069, whether anything verified the claim about the policy engine, is answered in `86-...`
(RF-069): the sample policy and this repository's handler are read and established; only the deployed policy is unknown.

