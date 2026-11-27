# Checklist and plan for resuming

Bottom line: nothing is running and nothing is lost. Two pieces of work are half done and can finish without the owner.
After them, about ten more items can be done without him. Everything else that moves OpenHouse toward a conforming
Iceberg REST catalog waits on his answers, and the one answer that unlocks the most is what "conforming" means (D32).
Written 2026-11-27 by the orchestrator at the owner's request ("Before continuing, make a checklist and plan").
Work stays paused until he says to continue.

## 1. Checklist before any work restarts (checked 2026-11-27 21:28 UTC)

- [x] The container restarted at 21:27 UTC on 2026-11-27 (the third restart). Checkouts, worktrees, the locally
      published Iceberg fork jars and the scratch records all survived. No build or agent is running.
- [x] `irc-integration` is unchanged at `81b4cd4d`; post-merge builds are green for all 71 merges.
- [x] The only open pull request against `irc-integration` is the notes pull request 79.
- [x] The raw records are in git (`records/`), and `00-map.md` maps every artifact.
- [x] The paused branch `irc/x2-x3-v3-row-level` is on GitHub at `1892643c`.
- [ ] Model limit: until 2026-11-30 09:00 UTC code agents run on the larger fallback model and notes agents on the
      small one; check on resume whether that still holds.
- [ ] Disk is at 79 percent with 16 GB free: delete build output of finished worktrees before the first build.
- [ ] The owner has said to continue.

## 2. Checklist for every piece of work (what "done" means; unchanged, except the last line is new)

- [ ] The row is confirmed still open against today's code before anything is written.
- [ ] Tests come first and fail for the right reason, then the change.
- [ ] Both Iceberg lines are run or the difference is declared with its reason; nothing is skipped to get green.
- [ ] A draft pull request against `irc-integration`; its build is green on both lines; a red job is read, not re-run.
- [ ] A new write route, or anything with authorization consequences, gets an independent read-only review first.
- [ ] The orchestrator reads the changed files, then merges with a message that says why, what it leaves true, the
      evidence, the known gaps and the reason for each commit.
- [ ] The post-merge build is read; anything red is fixed forward.
- [ ] Notes: work row, result file, merged list, state file, and any decision with its reason.
- [ ] New: the agent's record is copied into `records/` in the same notes commit, so no record lives only in the
      container.

## 3. The work, as a list with dependencies

Each line says what it needs. "Nothing" means it can start at once.

### 3a. Finish what is half done (needs: nothing)

1. X2 with X3, row-level statements on format v3. Tests are written, pushed, and pass on the default build with no
   defect found. Left: one run on the reference line, the test-groups check, the pull request, its build, the merge.
2. W13, the column-defaults capability the jobs scheduler declares. Found by reading: the scheduler cannot know its
   Iceberg line and declares the capability on both. Left: confirm with the built jars; then the orchestrator decides
   whether what the scheduler sends on the 1.12 line is wrong and gets fixed (a change on the wire, two-way door).
3. W14, the developer documentation for the Spark 3.5 write-default limitation (needs: nothing).
4. N9, the cause of the quoting difference between catalog paths (needs: nothing).

### 3b. Can be done without the owner (needs: nothing unless stated)

5. V25: the table list route ignores the page size. The orchestrator decides (two-way door); the specification allows
   a server to page, so the likely decision is to honor it. Needs: a look at what the 1.12 client does with a token.
6. N18: a refused replace leaves one data file on the REST paths. The fix is in the Iceberg fork's Spark staged-table
   abort; a fork change with its own test, then a new pin. Needs: nothing; lowest value of this group.
7. X10 leftovers: a maintenance job run against a branch, a table built by replace, and a partial-progress compaction
   raced by a writer. Needs: nothing.
8. C17 and C18: why default-line test tasks miss the build cache, and path-based selection of test groups. Needs:
   nothing; value is faster builds only.
9. C11 and C7: build layout leftovers and the publish script's lock on macOS (cannot be run here; read only).
10. N4: the native rename onto a view's name answers 500. Pinned today. A fix changes a native answer: goes to the
    owner as a question, not done.
11. A second independent review, read-only, of the two largest write surfaces that never had one: the table commit
    path over the Iceberg REST routes and the capability routes (policies, grants, unlock). Needs: nothing. Proposed by
    the orchestrator because both reviews so far found real gaps.

### 3c. Waits on the owner (each line: the question, and what it unlocks)

- D32, what "conforming" means -> the namespace model (33 of the 95 compatibility kit failures), the replace gate (16),
  view dialect rules (15), evolution rules (13); this is the largest block left.
- D47, a view-only rename in House Tables -> view rename, the last view route not served.
- V23 and V24, two House Tables lookups -> the remaining full listings on a missing namespace and on staged create.
- D45, credential vending and request signing -> V12b to V12e.
- D40, real atomic multi-table commit -> more than one table per transaction.
- D44, the server asking clients to use server-side planning (V11d) -> one-way for every reader.
- D12 and D48, turning format v3 and the variant type on for a real cluster; W8, migrating stored defaults.
- Publishing the Iceberg fork's jars -> I6d, building without the local publish step; C13.
- N12 to N15, the Spark 4.2 jobs deployables (jar, image, routing, job state).
- D46, how a retention backup treats deletes on merge-on-read tables (X8); X14, the orphan-file age floor.
- D35, the native directory-reuse fix (N5).
- Register-view: whether anyone with create rights may bring back a view someone else dropped (D52); whether a client
  can write into a view directory on his storage.
- The House Tables service's embedded H2 default; the job configuration without `spark_catalog` (X17).
- Real S3 for T11 (needs credentials he would have to supply).
- Unaddressed on his instruction: replication (I9, X7), storage tiering (T7).

## 4. Order and pace

Items 1 to 4 first, two agents at a time (memory allows no more). Then 11 and 5, then 7, then 6, 8, 9 as capacity
allows. The orchestrator writes no code and no notes; code agents on the larger model, notes agents on the small one.
A status message assumes the owner remembers nothing.

## 5. What this plan does not do

It does not get the compatibility kit past 42 of 160. No item in 3a or 3b changes that number; only the answers in 3c
do. If the owner wants progress on conformance before deciding D32 in full, the smallest useful answer is whether a
namespace may exist without a table in it, because that alone decides 26 of the kit's failures.
