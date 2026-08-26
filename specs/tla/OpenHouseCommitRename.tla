------------------------ MODULE OpenHouseCommitRename ------------------------
(*****************************************************************************)
(* TLA+ model of the OpenHouse table commit protocol, extended with the      *)
(* RENAME path.                                                              *)
(*                                                                           *)
(* This module is the OpenHouseCommit spec (which reproduced the stale-base  *)
(* snapshot-loss bug fixed by "abort doCommit on stale-base divergence" and  *)
(* verified the fix) extended with a Renamer process that models the HTS     *)
(* rename flow:                                                              *)
(*                                                                           *)
(*   OpenHouseInternalCatalog.renameTable                                    *)
(*     -> OpenHouseInternalTableOperations.doCommit (rename branch, which    *)
(*        writes a fresh metadata.json carrying the renamer's loaded         *)
(*        snapshot state)                                                    *)
(*     -> HouseTableRepositoryImpl.rename -> PATCH /hts/tables/rename        *)
(*     -> UserTableHtsJdbcRepository.renameTableId: a JPQL                   *)
(*        UPDATE UserTableRow SET tableId=..., metadataLocation=...          *)
(*        WHERE databaseId=... AND tableId=...                               *)
(*                                                                           *)
(* Pre-fix, that UPDATE is UNCONDITIONAL: it has no @Version predicate and   *)
(* does not bump @Version.  A rename racing a normal snapshot commit can     *)
(* therefore overwrite the winning commit's metadataLocation with a          *)
(* metadata.json built from the renamer's (stale) loaded state -- the same   *)
(* lost-update class as the stale-base bug, through a channel none of the    *)
(* commit-path CAS layers guard.                                             *)
(*                                                                           *)
(* The constant RenameGuard toggles the fix: the rename UPDATE becomes       *)
(* conditional on the row still being at the renamer's loaded state          *)
(* (expected metadataLocation + @Version) and atomically bumps @Version;     *)
(* a mismatched row updates 0 rows -> 409 conflict -> the renamer reloads    *)
(* and retries.                                                              *)
(*                                                                           *)
(* All file:line references are to the OpenHouse repo at commit 2a9dac8.     *)
(* ITO  = iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/      *)
(*        openhouse/internal/catalog/OpenHouseInternalTableOperations.java   *)
(* REPO = services/tables/src/main/java/com/linkedin/openhouse/tables/       *)
(*        repository/impl/OpenHouseInternalRepositoryImpl.java               *)
(* HTS  = services/housetables/.../services/UserTablesServiceImpl.java,      *)
(*        .../repository/impl/jdbc/UserTableHtsJdbcRepository.java and       *)
(*        .../dto/mapper/UserTableVersionMapper.java                         *)
(*                                                                           *)
(* ABSTRACTIONS (unchanged from the base spec)                               *)
(*  - Metadata.json locations are natural numbers 0..MaxCommits allocated    *)
(*    by a counter; snapshots are identified by the writer that produced     *)
(*    them; the HTS row is (catVer, catLoc, catSnaps); the single-row        *)
(*    UPDATE is the atomic commit point.                                     *)
(*  - The rename's identifier change (databaseId/tableId) is NOT modeled:    *)
(*    only the metadataLocation overwrite matters for snapshot loss, so the  *)
(*    rename is modeled as "replace the row's metadata pointer with a        *)
(*    freshly written file whose content is the renamer's loaded snapshot    *)
(*    set".  Uniqueness conflicts on the target name (409 AlreadyExists)     *)
(*    fold into the renamer aborting back to idle (RenameCrash).             *)
(*****************************************************************************)
EXTENDS Naturals, Sequences, FiniteSets

CONSTANTS
  Writers,          \* e.g. {w1, w2}; also used as the snapshot-id space
  MaxCommits,       \* global budget of metadata.json files ever written
  DivergenceCheck,  \* TRUE = post-fix commit path
                    \* (abortIfWriterBaseDivergedFromCatalog, ITO:269,604-635)
  SharedDedupCache, \* TRUE = all writers hit one Tables-service JVM
  RenameGuard       \* TRUE  = guarded rename: the HTS rename UPDATE is
                    \*         conditional on the renamer's expected
                    \*         metadataLocation/@Version and bumps @Version
                    \* FALSE = current code: unconditional JPQL UPDATE with
                    \*         no version predicate and no version bump
                    \*         (UserTableHtsJdbcRepository.renameTableId)

ASSUME MaxCommits \in Nat /\ MaxCommits >= 2

Locs == 0..MaxCommits            \* metadata.json location ids; 0 = initial
NoPend == [base |-> 0, snaps |-> {}, loc |-> 0]   \* loc 0 never pending

VARIABLES
  \* ---- the HTS row (source of truth) + storage allocator ----
  catVer,        \* JPA @Version column (UserTableRow.java)
  catLoc,        \* metadataLocation column: current committed metadata.json
  catSnaps,      \* snapshot set inside the metadata.json at catLoc
  nextLoc,       \* allocator for new metadata.json locations
  \* ---- per-replica failIfRetryUpdate dedup cache (ITO:642-664) ----
  seenKeys,      \* [Writers -> SUBSET Locs]: commitKeys seen by w's replica
  \* ---- per-writer client + in-flight server transaction state ----
  wstate,        \* "idle" | "loaded" | "pending" | "done"
  wBase,         \* COMMIT_KEY: base metadata location declared at load
  wPayload,      \* full snapshot list staged as SNAPSHOTS_JSON
  wPend,         \* doCommit output awaiting the HTS save
  \* ---- the renamer process (one logical rename request) ----
  rstate,        \* "idle" | "loaded" | "pending" | "done"
  rBase,         \* metadata location the renamer loaded from
  rSnaps,        \* snapshot set of the renamer's loaded metadata: the
                 \* rename branch's new metadata.json is built from the
                 \* renamer's loaded TableMetadata, so it carries exactly
                 \* these snapshots
  rPendLoc,      \* freshly written metadata.json awaiting the HTS rename
  \* ---- history, for the invariants and readable traces ----
  everCommitted, \* all snapshot ids that some committed metadata contained
  history        \* Seq of [ver, loc, snaps, by]: the committed versions

vars == <<catVer, catLoc, catSnaps, nextLoc, seenKeys,
          wstate, wBase, wPayload, wPend,
          rstate, rBase, rSnaps, rPendLoc, everCommitted, history>>

Actors == Writers \cup {"renamer"}

TypeOK ==
  /\ catVer \in Nat
  /\ catLoc \in Locs
  /\ catSnaps \subseteq Writers
  /\ nextLoc \in 1..(MaxCommits + 1)
  /\ seenKeys \in [Writers -> SUBSET Locs]
  /\ wstate \in [Writers -> {"idle", "loaded", "pending", "done"}]
  /\ wBase \in [Writers -> Locs]
  /\ wPayload \in [Writers -> SUBSET Writers]
  /\ wPend \in [Writers -> [base: Locs, snaps: SUBSET Writers, loc: Locs]]
  /\ rstate \in {"idle", "loaded", "pending", "done"}
  /\ rBase \in Locs
  /\ rSnaps \subseteq Writers
  /\ rPendLoc \in Locs
  /\ everCommitted \subseteq Writers
  /\ history \in Seq([ver: Nat, loc: Locs, snaps: SUBSET Writers,
                      by: Actors])

Init ==
  /\ catVer = 0 /\ catLoc = 0 /\ catSnaps = {} /\ nextLoc = 1
  /\ seenKeys = [w \in Writers |-> {}]
  /\ wstate = [w \in Writers |-> "idle"]
  /\ wBase = [w \in Writers |-> 0]
  /\ wPayload = [w \in Writers |-> {}]
  /\ wPend = [w \in Writers |-> NoPend]
  /\ rstate = "idle" /\ rBase = 0 /\ rSnaps = {} /\ rPendLoc = 0
  /\ everCommitted = {} /\ history = <<>>

\* Has w's replica already seen this commitKey?
KeySeen(w) ==
  IF SharedDedupCache
  THEN wBase[w] \in UNION {seenKeys[x] : x \in Writers}
  ELSE wBase[w] \in seenKeys[w]

-----------------------------------------------------------------------------
(* ======================= NORMAL COMMIT PATH (base spec) ================== *)

(* Load(w): client + server refresh, advisory versionCheck, staging of the   *)
(* full snapshot list + COMMIT_KEY on the transaction.                       *)
Load(w) ==
  /\ wstate[w] = "idle"
  /\ wstate' = [wstate EXCEPT ![w] = "loaded"]
  /\ wBase' = [wBase EXCEPT ![w] = catLoc]
  /\ wPayload' = [wPayload EXCEPT ![w] = catSnaps \cup {w}]
  /\ UNCHANGED <<catVer, catLoc, catSnaps, nextLoc, seenKeys, wPend,
                 rstate, rBase, rSnaps, rPendLoc, everCommitted, history>>

(* AbortDiverged(w): POST-FIX ONLY.  abortIfWriterBaseDivergedFromCatalog    *)
(* detects the silent rebase and throws CommitFailedException (409).         *)
AbortDiverged(w) ==
  /\ DivergenceCheck
  /\ wstate[w] = "loaded"
  /\ catLoc # wBase[w]
  /\ wstate' = [wstate EXCEPT ![w] = "idle"]
  /\ UNCHANGED <<catVer, catLoc, catSnaps, nextLoc, seenKeys, wBase,
                 wPayload, wPend, rstate, rBase, rSnaps, rPendLoc,
                 everCommitted, history>>

(* AbortDedup(w): failIfRetryUpdate 409s a commitKey its JVM-local cache     *)
(* has already seen.                                                         *)
AbortDedup(w) ==
  /\ wstate[w] = "loaded"
  /\ (DivergenceCheck => catLoc = wBase[w])   \* fix check runs first
  /\ KeySeen(w)
  /\ wstate' = [wstate EXCEPT ![w] = "idle"]
  /\ UNCHANGED <<catVer, catLoc, catSnaps, nextLoc, seenKeys, wBase,
                 wPayload, wPend, rstate, rBase, rSnaps, rPendLoc,
                 everCommitted, history>>

(* WriteMetadata(w): the rest of doCommit up to and including writing the    *)
(* new metadata.json.  Captures the silent rebase: wPend.base := catLoc      *)
(* (current!) while the snapshot content stays wPayload[w] (stale).          *)
WriteMetadata(w) ==
  /\ wstate[w] = "loaded"
  /\ nextLoc <= MaxCommits                     \* global commit budget
  /\ (DivergenceCheck => catLoc = wBase[w])    \* post-fix CAS gate
  /\ ~KeySeen(w)                               \* failIfRetryUpdate passes
  /\ seenKeys' = [seenKeys EXCEPT ![w] = @ \cup {wBase[w]}]
  /\ wPend' = [wPend EXCEPT ![w] =
                 [base |-> catLoc,             \* <-- silent rebase
                  snaps |-> wPayload[w],       \* <-- subtractive-merge result
                  loc |-> nextLoc]]
  /\ nextLoc' = nextLoc + 1
  /\ wstate' = [wstate EXCEPT ![w] = "pending"]
  /\ UNCHANGED <<catVer, catLoc, catSnaps, wBase, wPayload,
                 rstate, rBase, rSnaps, rPendLoc, everCommitted, history>>

(* HtsCommit(w): THE atomic commit point -- the optimistic-locked            *)
(* single-row UPDATE behind houseTableRepository.save.                       *)
HtsCommit(w) ==
  /\ wstate[w] = "pending"
  /\ catLoc = wPend[w].base
  /\ catVer' = catVer + 1
  /\ catLoc' = wPend[w].loc
  /\ catSnaps' = wPend[w].snaps
  /\ everCommitted' = everCommitted \cup wPend[w].snaps
  /\ history' = Append(history, [ver |-> catVer + 1, loc |-> wPend[w].loc,
                                 snaps |-> wPend[w].snaps, by |-> w])
  /\ wstate' = [wstate EXCEPT ![w] = "done"]
  /\ UNCHANGED <<nextLoc, seenKeys, wBase, wPayload, wPend,
                 rstate, rBase, rSnaps, rPendLoc>>

(* HtsConflict(w): optimistic-lock failure -> 409 -> engine retry.           *)
HtsConflict(w) ==
  /\ wstate[w] = "pending"
  /\ catLoc # wPend[w].base
  /\ wstate' = [wstate EXCEPT ![w] = "idle"]
  /\ UNCHANGED <<catVer, catLoc, catSnaps, nextLoc, seenKeys, wBase,
                 wPayload, wPend, rstate, rBase, rSnaps, rPendLoc,
                 everCommitted, history>>

(* Crash(w): writer or its tables-service request dies mid-flight.           *)
Crash(w) ==
  /\ wstate[w] \in {"loaded", "pending"}
  /\ wstate' = [wstate EXCEPT ![w] = "idle"]
  /\ UNCHANGED <<catVer, catLoc, catSnaps, nextLoc, seenKeys, wBase,
                 wPayload, wPend, rstate, rBase, rSnaps, rPendLoc,
                 everCommitted, history>>

-----------------------------------------------------------------------------
(* ============================ RENAME PATH ================================ *)

(* RenameLoad: OpenHouseInternalCatalog.renameTable loads the table          *)
(* (loadTable -> doRefresh) and opens a transaction that only updates the    *)
(* openhouse.tableId/databaseId properties.  The renamer's view of the       *)
(* table -- including its snapshot set -- is frozen here.                    *)
RenameLoad ==
  /\ rstate = "idle"
  /\ rstate' = "loaded"
  /\ rBase' = catLoc
  /\ rSnaps' = catSnaps
  /\ UNCHANGED <<catVer, catLoc, catSnaps, nextLoc, seenKeys,
                 wstate, wBase, wPayload, wPend, rPendLoc,
                 everCommitted, history>>

(* RenameWriteMetadata: the rename lands in doCommit (ITO:386-400 routes it  *)
(* to houseTableRepository.rename).  Note that                               *)
(* abortIfWriterBaseDivergedFromCatalog returns early for the rename branch  *)
(* -- a rename commit carries no SNAPSHOTS_JSON payload (ITO:610-616) --     *)
(* and failIfRetryUpdate only counts commits carrying COMMIT_KEY, so         *)
(* NEITHER commit-path guard applies here even post-fix.  A new              *)
(* metadata.json is written whose content is the renamer's loaded state.     *)
RenameWriteMetadata ==
  /\ rstate = "loaded"
  /\ nextLoc <= MaxCommits                     \* same global commit budget
  /\ rPendLoc' = nextLoc
  /\ nextLoc' = nextLoc + 1
  /\ rstate' = "pending"
  /\ UNCHANGED <<catVer, catLoc, catSnaps, seenKeys,
                 wstate, wBase, wPayload, wPend, rBase, rSnaps,
                 everCommitted, history>>

(* RenameHtsUnguarded: CURRENT CODE (RenameGuard = FALSE).                   *)
(* UserTableHtsJdbcRepository.renameTableId is an unconditional JPQL         *)
(* UPDATE: it matches the row by identifier only, overwrites                 *)
(* metadataLocation with the renamer's new file, has no @Version (or        *)
(* expected-metadataLocation) predicate, and does not bump @Version.  It     *)
(* therefore lands REGARDLESS of how far the row has advanced since          *)
(* RenameLoad, replacing the current metadata with the renamer's stale       *)
(* view: any snapshot committed after RenameLoad is silently dropped.       *)
(* catVer is unchanged -- the version column is not bumped -- so even the    *)
(* history record carries the old version number.                            *)
RenameHtsUnguarded ==
  /\ ~RenameGuard
  /\ rstate = "pending"
  /\ catLoc' = rPendLoc                        \* unconditional overwrite
  /\ catSnaps' = rSnaps                        \* stale loaded content
  /\ history' = Append(history, [ver |-> catVer, loc |-> rPendLoc,
                                 snaps |-> rSnaps, by |-> "renamer"])
  /\ rstate' = "done"
  /\ UNCHANGED <<catVer, nextLoc, seenKeys, wstate, wBase, wPayload,
                 wPend, rBase, rSnaps, rPendLoc, everCommitted>>

(* RenameHtsGuarded: THE FIX (RenameGuard = TRUE).                           *)
(* The rename request carries the renamer's expected base (the metadata      *)
(* location it loaded, exactly the CAS token the normal save path uses),     *)
(* and the UPDATE is conditional on the row still matching it (expected      *)
(* metadataLocation + @Version) while atomically bumping @Version.  It can   *)
(* only land if no commit has advanced the row since RenameLoad -- in which  *)
(* case rSnaps = catSnaps and nothing is lost.                               *)
RenameHtsGuarded ==
  /\ RenameGuard
  /\ rstate = "pending"
  /\ catLoc = rBase                            \* conditional UPDATE matched
  /\ catVer' = catVer + 1                      \* @Version bumped atomically
  /\ catLoc' = rPendLoc
  /\ catSnaps' = rSnaps                        \* = catSnaps, by the guard
  /\ history' = Append(history, [ver |-> catVer + 1, loc |-> rPendLoc,
                                 snaps |-> rSnaps, by |-> "renamer"])
  /\ rstate' = "done"
  /\ UNCHANGED <<nextLoc, seenKeys, wstate, wBase, wPayload, wPend,
                 rBase, rSnaps, rPendLoc, everCommitted>>

(* RenameHtsConflict: with the guard, a row that advanced since RenameLoad   *)
(* makes the conditional UPDATE match 0 rows -> 409                          *)
(* (EntityConcurrentModificationException) -> HouseTableConcurrentUpdate-    *)
(* Exception -> CommitFailedException; the renamer reloads and retries.      *)
(* The written metadata.json at rPendLoc is orphaned garbage.                *)
RenameHtsConflict ==
  /\ RenameGuard
  /\ rstate = "pending"
  /\ catLoc # rBase
  /\ rstate' = "idle"
  /\ UNCHANGED <<catVer, catLoc, catSnaps, nextLoc, seenKeys,
                 wstate, wBase, wPayload, wPend, rBase, rSnaps, rPendLoc,
                 everCommitted, history>>

(* RenameCrash: the rename request dies mid-flight (or aborts on a target-   *)
(* name AlreadyExists conflict) before the HTS row update; nothing           *)
(* committed changes.                                                        *)
RenameCrash ==
  /\ rstate \in {"loaded", "pending"}
  /\ rstate' = "idle"
  /\ UNCHANGED <<catVer, catLoc, catSnaps, nextLoc, seenKeys,
                 wstate, wBase, wPayload, wPend, rBase, rSnaps, rPendLoc,
                 everCommitted, history>>

-----------------------------------------------------------------------------
Terminating ==
  /\ \A w \in Writers : wstate[w] = "done"
  /\ rstate = "done"
  /\ UNCHANGED vars

Next ==
  \/ \E w \in Writers :
       Load(w) \/ AbortDiverged(w) \/ AbortDedup(w) \/ WriteMetadata(w)
       \/ HtsCommit(w) \/ HtsConflict(w) \/ Crash(w)
  \/ RenameLoad \/ RenameWriteMetadata
  \/ RenameHtsUnguarded \/ RenameHtsGuarded \/ RenameHtsConflict
  \/ RenameCrash
  \/ Terminating

Spec == Init /\ [][Next]_vars

-----------------------------------------------------------------------------
(* INVARIANTS *)

(* NoSnapshotLoss: no writer or renamer ever expires a snapshot in this      *)
(* model (a rename must preserve table content), so every snapshot that      *)
(* appeared in any committed metadata version must still be in the           *)
(* currently committed metadata.  The unguarded rename violates this: a      *)
(* commit landing between RenameLoad and the unconditional rename UPDATE is  *)
(* clobbered by the renamer's stale metadata.                                *)
NoSnapshotLoss == everCommitted \subseteq catSnaps

(* MonotonicVersion: every committed row state carries a strictly            *)
(* increasing @Version and a brand-new metadata location.  The unguarded     *)
(* rename also violates the version clause (catVer = Len(history)): it       *)
(* rewrites the row without bumping @Version, which is exactly why           *)
(* concurrent writers' optimistic locking cannot see it.                     *)
MonotonicVersion ==
  /\ catVer = Len(history)
  /\ \A i \in 1..(Len(history) - 1) :
       /\ history[i].ver < history[i + 1].ver
       /\ history[i].loc # history[i + 1].loc
  /\ \A i, j \in 1..Len(history) : (i # j) => history[i].loc # history[j].loc

=============================================================================
