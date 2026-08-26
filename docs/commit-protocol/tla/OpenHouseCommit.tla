--------------------------- MODULE OpenHouseCommit ---------------------------
(*****************************************************************************)
(* TLA+ model of the OpenHouse table commit protocol, focused on the         *)
(* snapshot-loss bug fixed by commit 9407819 (#612,                          *)
(* "fix(catalog): abort doCommit on stale-base divergence").                 *)
(*                                                                           *)
(* All file:line references are to /home/user/openhouse at HEAD 2a9dac8.     *)
(* ITO  = iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/      *)
(*        openhouse/internal/catalog/OpenHouseInternalTableOperations.java   *)
(* REPO = services/tables/src/main/java/com/linkedin/openhouse/tables/       *)
(*        repository/impl/OpenHouseInternalRepositoryImpl.java               *)
(* HTS  = services/housetables/.../services/UserTablesServiceImpl.java and   *)
(*        .../dto/mapper/UserTableVersionMapper.java                         *)
(*                                                                           *)
(* ABSTRACTIONS                                                              *)
(*  - Metadata.json locations are modeled as natural numbers 0..MaxCommits   *)
(*    allocated by a counter (the real names are {tableLoc}/%05d-UUID; the   *)
(*    UUID only guarantees path uniqueness, which the counter gives us).     *)
(*  - A snapshot is identified by the writer that produced it: each writer   *)
(*    tries to append exactly one snapshot (its own name). No expiration is  *)
(*    modeled, so NoSnapshotLoss reduces to "every snapshot ever committed   *)
(*    stays in the committed snapshot set forever".                          *)
(*  - The HTS row is (catVer, catLoc, catSnaps): the JPA @Version column,    *)
(*    the metadataLocation column, and the snapshot set reachable from the   *)
(*    metadata.json that catLoc points to. The single-row optimistic-locked  *)
(*    UPDATE (UserTablesServiceImpl.java:111) is the atomic commit point and *)
(*    is modeled as one action, HtsCommit.                                   *)
(*  - Storage writes of metadata.json files are modeled only as the          *)
(*    allocation of a new location (WriteMetadata); an unreferenced file is  *)
(*    invisible garbage, so its content lives in wPend until (unless) the    *)
(*    HTS CAS lands it.                                                      *)
(*  - The tables-service advisory versionCheck (REPO:451-475) compares the   *)
(*    request base against the writer's OWN freshly loaded view, so it       *)
(*    passes trivially; it is folded into Load and not modeled separately.   *)
(*  - Client/engine retry (Iceberg commit.retry) is modeled as the writer    *)
(*    returning to "idle" and re-running Load; retries are bounded only by   *)
(*    the global commit budget MaxCommits (which bounds the state space).    *)
(*  - Crash of a writer/tables-service instance mid-commit is Crash(w):     *)
(*    the pending metadata file becomes an orphan and nothing else changes,  *)
(*    matching failure windows S1-S4 of report 01 section 4.                 *)
(*  - CommitStateUnknown (ambiguous HTS response, window S4b) is NOT        *)
(*    modeled: the HTS save either lands atomically or conflicts.            *)
(*****************************************************************************)
EXTENDS Naturals, Sequences, FiniteSets

CONSTANTS
  Writers,          \* e.g. {w1, w2}; also used as the snapshot-id space
  MaxCommits,       \* global budget of metadata.json files ever written
  DivergenceCheck,  \* TRUE = post-fix (abortIfWriterBaseDivergedFromCatalog)
  SharedDedupCache  \* TRUE = all writers hit one Tables-service JVM
                    \* (failIfRetryUpdate's Guava cache, ITO:93-94, is
                    \*  per-JVM; FALSE models each writer routed to its own
                    \*  replica behind the load balancer)

ASSUME MaxCommits \in Nat /\ MaxCommits >= 2

Locs == 0..MaxCommits            \* metadata.json location ids; 0 = initial
NoPend == [base |-> 0, snaps |-> {}, loc |-> 0]   \* loc 0 never pending

VARIABLES
  \* ---- the HTS row (source of truth) + storage allocator ----
  catVer,        \* JPA @Version column (UserTableRow.java:28)
  catLoc,        \* metadataLocation column: current committed metadata.json
  catSnaps,      \* snapshot set inside the metadata.json at catLoc
  nextLoc,       \* allocator for new metadata.json locations
  \* ---- per-replica failIfRetryUpdate dedup cache (ITO:93-94,642-664) ----
  seenKeys,      \* [Writers -> SUBSET Locs]: commitKeys seen by w's replica
  \* ---- per-writer client + in-flight server transaction state ----
  wstate,        \* "idle" | "loaded" | "pending" | "done"
  wBase,         \* COMMIT_KEY: base metadata location declared at load
                 \* (REPO:196 updateProperties.set(COMMIT_KEY, tableVersion))
  wPayload,      \* full snapshot list staged as SNAPSHOTS_JSON (REPO:187)
  wPend,         \* doCommit output awaiting the HTS save (ITO:361-411)
  \* ---- history, for the invariants and readable traces ----
  everCommitted, \* all snapshot ids that some committed metadata contained
  history        \* Seq of [ver, loc, snaps, by]: the committed versions

vars == <<catVer, catLoc, catSnaps, nextLoc, seenKeys,
          wstate, wBase, wPayload, wPend, everCommitted, history>>

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
  /\ everCommitted \subseteq Writers
  /\ history \in Seq([ver: Nat, loc: Locs, snaps: SUBSET Writers, by: Writers])

Init ==
  /\ catVer = 0 /\ catLoc = 0 /\ catSnaps = {} /\ nextLoc = 1
  /\ seenKeys = [w \in Writers |-> {}]
  /\ wstate = [w \in Writers |-> "idle"]
  /\ wBase = [w \in Writers |-> 0]
  /\ wPayload = [w \in Writers |-> {}]
  /\ wPend = [w \in Writers |-> NoPend]
  /\ everCommitted = {} /\ history = <<>>

\* Has w's replica already seen this commitKey?  (ITO:648 CACHE.getIfPresent)
KeySeen(w) ==
  IF SharedDedupCache
  THEN wBase[w] \in UNION {seenKeys[x] : x \in Writers}
  ELSE wBase[w] \in seenKeys[w]

-----------------------------------------------------------------------------
(* Load(w): the writer (engine + tables service up to transaction staging). *)
(* Abstracts: client doRefresh (OpenHouseTableOperations.java:97-128),      *)
(* server loadTable/doRefresh (ITO:106-132), the advisory versionCheck      *)
(* (REPO:451-475, passes trivially against the writer's own view), and      *)
(* staging SNAPSHOTS_JSON + COMMIT_KEY on the transaction (REPO:187,196).   *)
(* The payload is the FULL snapshot list as of the loaded base, plus the    *)
(* writer's one new snapshot -- declarative absolute state (report 01 s.5). *)
Load(w) ==
  /\ wstate[w] = "idle"
  /\ wstate' = [wstate EXCEPT ![w] = "loaded"]
  /\ wBase' = [wBase EXCEPT ![w] = catLoc]
  /\ wPayload' = [wPayload EXCEPT ![w] = catSnaps \cup {w}]
  /\ UNCHANGED <<catVer, catLoc, catSnaps, nextLoc, seenKeys, wPend,
                 everCommitted, history>>

(* AbortDiverged(w): POST-FIX ONLY.                                         *)
(* abortIfWriterBaseDivergedFromCatalog (ITO:269 call site, :604-635):      *)
(* at doCommit time the base argument is the CATALOG-CURRENT metadata       *)
(* (BaseTransaction.applyUpdates silently refreshed it), so comparing the   *)
(* writer's COMMIT_KEY against it detects the silent rebase and throws      *)
(* CommitFailedException (409) -> engine retries from a fresh load.         *)
AbortDiverged(w) ==
  /\ DivergenceCheck
  /\ wstate[w] = "loaded"
  /\ catLoc # wBase[w]
  /\ wstate' = [wstate EXCEPT ![w] = "idle"]
  /\ UNCHANGED <<catVer, catLoc, catSnaps, nextLoc, seenKeys, wBase,
                 wPayload, wPend, everCommitted, history>>

(* AbortDedup(w): failIfRetryUpdate (ITO:642-664) 409s a commitKey its      *)
(* JVM-local cache has already seen (meant to poison server-side Iceberg    *)
(* internal retries).  Note it fires on ANY repeat of the key, including a  *)
(* DIFFERENT writer committing from the same base on the same replica      *)
(* (report 01, smell #2).                                                   *)
AbortDedup(w) ==
  /\ wstate[w] = "loaded"
  /\ (DivergenceCheck => catLoc = wBase[w])   \* fix check runs first (ITO:269-271)
  /\ KeySeen(w)
  /\ wstate' = [wstate EXCEPT ![w] = "idle"]
  /\ UNCHANGED <<catVer, catLoc, catSnaps, nextLoc, seenKeys, wBase,
                 wPayload, wPend, everCommitted, history>>

(* WriteMetadata(w): the rest of doCommit up to and including writing the   *)
(* new metadata.json (ITO:250-383).  KEY MODELING POINT -- the silent       *)
(* rebase: transaction.commitTransaction (REPO:216) runs Iceberg            *)
(* BaseTransaction.applyUpdates, which refreshes the in-flight base to the  *)
(* CURRENT catalog state and re-stamps the STALE staged properties          *)
(* (COMMIT_KEY = wBase, SNAPSHOTS_JSON = wPayload) on top of it.  Hence     *)
(* wPend.base := catLoc (current!), while the snapshot content is           *)
(* wPayload[w] unchanged.  The subtractive merge (ITO:314-354) adds payload *)
(* snapshots missing from the rebased base and REMOVES base snapshots       *)
(* absent from the payload, so the merged snapshot set is exactly           *)
(* wPayload[w] -- a racing snapshot in catSnaps \ wPayload[w] is silently   *)
(* expired.  The file write itself (ITO:361-366) is a fresh unique path:    *)
(* harmless until referenced.                                               *)
WriteMetadata(w) ==
  /\ wstate[w] = "loaded"
  /\ nextLoc <= MaxCommits                     \* global commit budget
  /\ (DivergenceCheck => catLoc = wBase[w])    \* post-fix CAS gate (ITO:269)
  /\ ~KeySeen(w)                               \* failIfRetryUpdate passes
  /\ seenKeys' = [seenKeys EXCEPT ![w] = @ \cup {wBase[w]}]  \* key burned
                                               \* PRE-commit (ITO:648-654)
  /\ wPend' = [wPend EXCEPT ![w] =
                 [base |-> catLoc,             \* <-- silent rebase
                  snaps |-> wPayload[w],       \* <-- subtractive-merge result
                  loc |-> nextLoc]]
  /\ nextLoc' = nextLoc + 1
  /\ wstate' = [wstate EXCEPT ![w] = "pending"]
  /\ UNCHANGED <<catVer, catLoc, catSnaps, wBase, wPayload,
                 everCommitted, history>>

(* HtsCommit(w): THE atomic commit point.  houseTableRepository.save        *)
(* (ITO:401-411) -> PUT /hts/tables -> UserTableVersionMapper.toVersion     *)
(* (row.metadataLocation must equal the request's tableVersion, which       *)
(* doCommit set from base.metadataFileLocation(), i.e. wPend.base) ->       *)
(* Hibernate UPDATE ... WHERE version = v (UserTablesServiceImpl.java:111). *)
(* Note the guard compares against wPend.base (the REBASED base), which is  *)
(* why the HTS CAS does NOT catch the silent-rebase bug: after the rebase   *)
(* the declared base matches the row again.                                 *)
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
  /\ UNCHANGED <<nextLoc, seenKeys, wBase, wPayload, wPend>>

(* HtsConflict(w): the row moved between doCommit's refreshed base and the  *)
(* save -> 0 rows updated -> ObjectOptimisticLockingFailureException -> 409 *)
(* -> HouseTableConcurrentUpdateException -> CommitFailedException          *)
(* (ITO:448-451) -> engine retries from a fresh load.  The already-written  *)
(* metadata.json at wPend.loc is orphaned garbage (window S3/S4a).          *)
HtsConflict(w) ==
  /\ wstate[w] = "pending"
  /\ catLoc # wPend[w].base
  /\ wstate' = [wstate EXCEPT ![w] = "idle"]
  /\ UNCHANGED <<catVer, catLoc, catSnaps, nextLoc, seenKeys, wBase,
                 wPayload, wPend, everCommitted, history>>

(* Crash(w): writer or its tables-service request dies mid-flight (before   *)
(* the HTS row update).  Nothing committed changes; at worst an orphan      *)
(* metadata file exists (report 01 s.4, S1-S4).  The application may retry  *)
(* later, which is indistinguishable from returning to "idle".              *)
Crash(w) ==
  /\ wstate[w] \in {"loaded", "pending"}
  /\ wstate' = [wstate EXCEPT ![w] = "idle"]
  /\ UNCHANGED <<catVer, catLoc, catSnaps, nextLoc, seenKeys, wBase,
                 wPayload, wPend, everCommitted, history>>

Terminating ==
  /\ \A w \in Writers : wstate[w] = "done"
  /\ UNCHANGED vars

Next ==
  \/ \E w \in Writers :
       Load(w) \/ AbortDiverged(w) \/ AbortDedup(w) \/ WriteMetadata(w)
       \/ HtsCommit(w) \/ HtsConflict(w) \/ Crash(w)
  \/ Terminating

Spec == Init /\ [][Next]_vars

-----------------------------------------------------------------------------
(* INVARIANTS *)

(* NoSnapshotLoss: since no writer ever expires a snapshot in this model,   *)
(* every snapshot that appeared in any committed metadata version must      *)
(* still be in the currently committed metadata.  The pre-fix protocol      *)
(* violates this via the stale-base rebase + subtractive merge (bug #612 /  *)
(* incident-12185).                                                         *)
NoSnapshotLoss == everCommitted \subseteq catSnaps

(* MonotonicVersion: the HTS @Version CAS yields a strictly increasing      *)
(* version, and each committed version references a brand-new metadata      *)
(* location (no location reuse -- assumption 2 of the fix's residual-gap    *)
(* analysis in report 02).                                                  *)
MonotonicVersion ==
  /\ catVer = Len(history)
  /\ \A i \in 1..(Len(history) - 1) :
       /\ history[i].ver < history[i + 1].ver
       /\ history[i].loc # history[i + 1].loc
  /\ \A i, j \in 1..Len(history) : (i # j) => history[i].loc # history[j].loc

=============================================================================
