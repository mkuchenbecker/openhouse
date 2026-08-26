# Appendix E: A TLA+ Model of the Commit Protocol

A TLA+ model of the commit protocol violates `NoSnapshotLoss` by exactly the #612 interleaving before fix #612, and checks clean with the fix over the same state space. The violation is a two-writer trace in which the loser's stale payload expires the winner's durably committed snapshot; the fix's catalog CAS turns that trace into a retriable abort. The model also shows why the bug survived single-instance testing: with all writers on one JVM, the retry-dedup cache accidentally serializes same-base writers and no violation is reachable.

Five configurations were checked. The first two rows are the result; the rest bound it.

| Configuration | What it checks | Result |
|---|---|---|
| `prefix.cfg` / `tlc-prefix.log` | Pre-fix, 2 writers, per-replica dedup caches | **`NoSnapshotLoss` violated** — the #612 trace (§3) |
| `postfix.cfg` / `tlc-postfix.log` | Post-fix, same constants | **All invariants hold**, complete state graph |
| `prefix_large.cfg`, `postfix_large.cfg` + logs | 3 writers / 6 commits | Violation and pass both reproduce |
| `prefix_sharedcache.cfg` / `tlc-prefix-sharedcache.log` | Pre-fix, but all writers on one JVM so the dedup cache is shared | Holds — an artifact of the shared cache, not a correctness result (§5) |
| `OpenHouseCommit.tla` | The spec itself, one module; the `DivergenceCheck` constant toggles pre/post-fix | — |

Everything is under [`tla/`](tla/); §8 has the repro commands, which need [tla2tools](https://github.com/tlaplus/tlaplus/releases) (`tla2tools.jar` is not committed; runs above used the latest release under OpenJDK 21.0.10).

File:line references use the conventions of [protocol.md](protocol.md) and [Appendix A](appendix-a-snapshot-drop-bug.md) (`ITO` = `OpenHouseInternalTableOperations.java`, `REPO` = `OpenHouseInternalRepositoryImpl.java`, all at repo commit `2a9dac8`).

---

## 1. Why the protocol fits in a small model

The protocol reduces to a textbook optimistic-concurrency model once you notice three things:

1. **There is exactly one atomic commit point** — the HTS single-row `UPDATE ... WHERE version = v` (`UserTablesServiceImpl.java:111`). Everything before it is either read-only or writes to fresh, unreferenced storage paths. So the model needs only one "commit lands" action; file writes need no atomicity modeling at all.
2. **The snapshot payload is declarative absolute state** (full list + refs, [protocol.md](protocol.md) §5), and the server merge is subtractive (`ITO:314-354`): merged result = payload set, exactly. The whole merge collapses to one set assignment `catSnaps' = wPayload[w]` — no need to model add/remove separately.
3. **The bug is a pure interleaving property.** The silent rebase (`BaseTransaction.applyUpdates`) is modeled by having the doCommit action capture `base := catalog-current-location` while the payload/COMMIT_KEY keep their load-time values. No storage, JSON, refs, schemas, or HTTP layers required.

What the model abstracts away: metadata.json contents beyond the snapshot-ID set; refs and branches; schema and property evolution; snapshot expiration (writers only append — see §5); CommitStateUnknown and ambiguous HTS responses (the ambiguous half of [protocol.md](protocol.md) §4 row S4); dedup-cache TTL and size eviction; the rename, replace, and stage-create paths; multiple tables; and the client/engine retry counter (retries are bounded only by a global commit budget).

The state spaces are tiny and every run finishes in under a second. At 2 writers with 1 snapshot each, TLC explores a complete state graph of 103 distinct states pre-fix (223 generated) and 99 post-fix (221 generated), both at depth 11. At 3 writers and 6 commits the post-fix run is still complete at 4,197 distinct states (11,536 generated, depth 20); the pre-fix run at those constants is *not* a complete graph, because the invariant violation halts the search at 218 distinct states with 119 still on the queue.

## 2. The model

Per `OpenHouseCommit.tla`. Metadata.json locations are naturals `0..MaxCommits` (counter replaces the `%05d-UUID` naming; uniqueness is what matters). Snapshot IDs are writer names (each writer appends exactly one snapshot).

**State variables**

| Variable | Real-world counterpart |
|---|---|
| `catVer` | HTS `UserTableRow.@Version` (JPA optimistic-lock column) |
| `catLoc` | HTS `metadataLocation` column (current committed metadata.json) |
| `catSnaps` | snapshot set inside the metadata.json at `catLoc` |
| `nextLoc` | storage allocator for new metadata.json paths |
| `seenKeys[w]` | `failIfRetryUpdate`'s per-JVM Guava cache (`ITO:93-94`) on writer w's replica |
| `wstate/wBase/wPayload` | writer phase; staged `COMMIT_KEY` (`REPO:196`); staged `SNAPSHOTS_JSON` (`REPO:187`) |
| `wPend[w]` | doCommit output (rebased base, merged snapshots, new file path) awaiting the HTS save |
| `everCommitted`, `history` | ghost variables for the invariants and readable traces |

**Actions** (each commented in the spec with its code path):

- `Load(w)` — client + server refresh, advisory `versionCheck` (passes trivially against the writer's own view, `REPO:451-475`), staging of full snapshot list + COMMIT_KEY.
- `AbortDiverged(w)` — **post-fix only**: `abortIfWriterBaseDivergedFromCatalog` (`ITO:269, 604-635`), guarded by `DivergenceCheck`.
- `AbortDedup(w)` — `failIfRetryUpdate` (`ITO:642-664`) 409s a repeated commitKey.
- `WriteMetadata(w)` — the heart: `transaction.commitTransaction()` → **silent rebase** (`wPend.base := catLoc`, current catalog) with **stale payload** (`wPend.snaps := wPayload[w]` — the exact result of the subtractive merge), file written to a fresh path, commitKey burned pre-commit (`ITO:648-654`).
- `HtsCommit(w)` — the atomic commit point: guard `catLoc = wPend[w].base` models `UserTableVersionMapper` + the `@Version` CAS. Crucially the guard compares the **rebased** base — which is why the HTS CAS cannot catch the bug: after the rebase, the declared base matches the row again.
- `HtsConflict(w)` — optimistic-lock failure → 409 → engine retry from fresh load (pending file orphaned).
- `Crash(w)` — writer/service dies pre-commit-point; nothing committed changes (validates [protocol.md](protocol.md) §4's S1–S4 claim that pre-S5 failures leave only orphan files).

**Invariants**

- `NoSnapshotLoss == everCommitted ⊆ catSnaps` — since no expiration is modeled, any committed snapshot must stay reachable in the current committed metadata forever.
- `MonotonicVersion` — `catVer = Len(history)`, strictly increasing versions, and no metadata-location reuse across committed versions (assumption 2 of the fix's residual-gap analysis in [Appendix A](appendix-a-snapshot-drop-bug.md) §3).
- `TypeOK`.

## 3. TLC result — pre-fix: violation found (bug #612 reproduced)

`prefix.cfg` (2 writers, MaxCommits=4, `DivergenceCheck=FALSE`, per-replica dedup caches): **`Error: Invariant NoSnapshotLoss is violated`** after 223 generated / 103 distinct states. Abridged counterexample (full trace in `tlc-prefix.log`) — this is exactly incident-12185's interleaving with W=w2, R=w1:

```
1. Init                catalog: ver 0, loc 0, snaps {}
2. Load(w1)            w1: base 0, payload {w1}
3. Load(w2)            w2: base 0, payload {w2}          <- both loaded at T_X
4. WriteMetadata(w1)   w1 pending: base 0, snaps {w1}, file loc 1
5. HtsCommit(w1)       catalog: ver 1, loc 1, snaps {w1}  <- racing commit R lands (T_Y)
6. WriteMetadata(w2)   SILENT REBASE: w2 pending base = 1 (current!),
                       snaps {w2} (stale payload; subtractive merge
                       expires w1's snapshot); dedup cache on w2's
                       replica hasn't seen key 0 -> passes
7. HtsCommit(w2)       guard catLoc(1) = pend.base(1) PASSES -> catalog:
                       ver 2, loc 2, snaps {w2}
                       everCommitted {w1,w2} ⊄ catSnaps {w2}  *** VIOLATION ***
```

Step 6–7 shows why all three pre-fix CAS layers miss it: `versionCheck` ran before the race (step 3), `failIfRetryUpdate` sees key 0 for the first time on w2's replica, and the HTS `@Version`/location CAS compares against the *rebased* base, which matches. The violation also reproduces at 3 writers / 6 commits (`tlc-prefix-large.log`, 218 distinct states explored before the search halts).

## 4. TLC result — post-fix: invariants hold

`postfix.cfg` (identical constants, `DivergenceCheck=TRUE`): **"Model checking completed. No error has been found."** 221 generated / 99 distinct states, complete state graph, depth 11. At 3 writers / 6 commits: 11,536 generated / 4,197 distinct, depth 20, no error. The guard `catLoc = wBase[w]` at `WriteMetadata` (i.e., `abortIfWriterBaseDivergedFromCatalog` running before any file write or HTS save) converts every stale-base doCommit into a retriable abort; the writer reloads and recommits a payload that includes the racing snapshot. `MonotonicVersion` and `TypeOK` hold in every configuration, pre- and post-fix.

## 5. The dedup cache masks the bug on a single JVM

`prefix_sharedcache.cfg` (pre-fix, but all writers hitting **one** Tables-service JVM, so `failIfRetryUpdate`'s cache is shared): **no violation** (105 generated / 45 distinct states). Because the commitKey is just the base metadata path, two writers committing from the same base get serialized by the shared cache ([Appendix B](appendix-b-code-review.md) finding 5) — the second one is 409'd before the stale doCommit can run. This is consistent with the incident requiring multi-replica routing (or the unmodeled 5-minute TTL / 1000-entry eviction) to manifest, and it explains why the bug could survive single-instance testing.

It is not a correctness argument. The model omits eviction, so the shared-cache pass is an artifact of these constants, and production runs multiple replicas behind a load balancer — the per-replica configuration, the one that violates.

## 6. Limitations / abstractions (what a "pass" here does and does not mean)

1. **No expiration modeled.** Real `putSnapshots` legitimately removes snapshots; the invariant would need a ghost "explicitly expired by a non-diverged writer" set to state the full property. As modeled (append-only writers), any removal is a bug — which is precisely the #612 class, so the simplification is load-bearing in the right direction but means legitimate-expiration-vs-append races are unexplored.
2. **CommitStateUnknown not modeled.** `HtsCommit`/`HtsConflict` are a clean atomic either/or; the ambiguous window of [protocol.md](protocol.md) §4 row S4 (HTS 5xx/timeout → `checkCommitStatus` → 503) and client-side duplicate-commit hazards after an ambiguous success are out of scope.
3. **Undefended paths not modeled**: replaceTable/RTAS, stage-create/stage-replace (no `COMMIT_KEY` — intentionally authoritative), rename (which bypasses the `@Version` CAS entirely — [Appendix B](appendix-b-code-review.md) blocking finding 3, and now modeled separately, §7), property-only commits.
4. **Dedup cache TTL/eviction not modeled** (see §5); paths are compared as atoms, so the scheme-normalization concerns ([Appendix B](appendix-b-code-review.md) finding 13) and the location-reuse assumption are assumed away (though `MonotonicVersion` checks that no committed location is ever reused *within* the model).
5. Single table, one snapshot per writer, small bounded constants — TLC verifies these bounds only, not the unbounded protocol (no TLAPS proof attempted; for this bug class, small-scope counterexamples are clearly sufficient — the real one needed 2 writers and 2 commits).

## 7. Deeper modeling

**The rename race is modeled, and it is a real defect.** The same skeleton extended with a rename process produces a `NoSnapshotLoss` counterexample against the unguarded JPQL rename ([Appendix B](appendix-b-code-review.md) blocking finding 3): a 7-step trace at 183 distinct states in which a rename issued from base L0 overwrites the pointer a concurrent commit has already advanced, discarding an acknowledged snapshot. The guarded model — rename carries the caller's expected base, the UPDATE is conditional and bumps `@Version` atomically, a mismatch yields 409 — holds `NoSnapshotLoss`, `MonotonicVersion`, and `TypeOK` across 6,130 distinct states. That extended model was a tool for locating the defect rather than an artifact the repository carries, so it is not committed; the fix that implements the guarded design ships on its own, carrying the tests that encode the same interleaving.

Remaining extensions, in the order they are worth funding:

| Extension | Value | Effort | Verdict |
|---|---|---|---|
| `Expire(w)` action (writer stages a payload omitting a known snapshot) plus a refined `NoSnapshotLoss` with an explicit-expiry ghost set | Verifies the fix does not break legitimate expiration, and sharpens the invariant to explicit-expiry-only removal | Small | Worth doing |
| CommitStateUnknown: split `HtsCommit` into request / ambiguous-response / `checkCommitStatus` steps | Verifies that "writes never retry" plus a status re-check never double-applies or misreports | Roughly doubles the action count, still well within TLC range | Worth doing if HTS or client behavior changes are planned |
| Crash recovery beyond `Crash(w)` | None: the protocol has no recovery log, and pre-commit-point crashes provably leave only orphans, which the current model already checks | — | Not worth doing |
| Multi-table, or refs and schema modeling | None: no invariant of interest depends on them | — | Not worth doing |

`OpenHouseCommit.tla` stays in this appendix as the regression spec for any future change to `doCommit` ordering: the fix's before-`failIfRetryUpdate` ordering is load-bearing, and the model's guard structure captures it.

## 8. Repro commands

```
cd docs/commit-protocol/tla
java -cp tla2tools.jar tlc2.TLC -config prefix.cfg  -metadir states_prefix  OpenHouseCommit.tla   # violation
java -cp tla2tools.jar tlc2.TLC -config postfix.cfg -metadir states_postfix OpenHouseCommit.tla   # pass
```
