# The OpenHouse Commit Protocol: Analysis, Verification, and a Path Forward

OpenHouse's table commit protocol is sound at its core and sharp at its edges. The core
— one atomic commit point, a single optimistic-locked row update in the House Tables
service, fed by write-ahead metadata files that are garbage until referenced — gives
per-table linearizable commits, and model checking found no violation of its
invariants in any checked configuration. The edges are where
the risk lives, and this document set establishes four conclusions about them:

1. **The protocol's structural weakness is client authority over absolute state.** The
   client ships the table's entire snapshot list; the server persists it subtractively.
   Correctness therefore rests entirely on a trio of base checks, and when one gap in
   them existed, production silently lost a durably committed snapshot (fix #612).
   [protocol.md](protocol.md) explains the mechanism; [Appendix A](appendix-a-snapshot-drop-bug.md)
   traces the incident and fix.
2. **The fix is verified, and its blind spots are mapped.** A TLA+ model of the
   protocol reproduces the incident as a `NoSnapshotLoss` invariant violation without
   the fix, and with it TLC completes the checked state spaces with no violation —
   and [Appendix E](appendix-e-tla.md) names the CAS-exempt rename path as the likely
   next counterexample of the same class, a prediction an extended model has since
   confirmed (the fix ships separately as its own draft pull request). The model also explains why the bug survived
   single-instance testing: the per-JVM retry-dedup cache accidentally serializes
   same-base writers on one replica.
3. **Nine blocking defects remain beside the commit point.** A three-expert review
   (architecture, testing, and a blind protocol-correctness expert judging against
   Apache Iceberg's own contract) produced 29 verified findings, 9 blocking. The three
   worst all mutate or misreport *committed* state: a post-commit-point `IOException`
   handler that signals "safe to clean up" for a commit that succeeded, an in-place
   rewrite of already-committed metadata.json, and the unguarded rename
   ([Appendix B](appendix-b-code-review.md)). All three were found independently by
   briefed and blind reviewers.
4. **The structural fix is to move metadata authorship to the server.** Two options
   reach it ([Appendix D](appendix-d-rest-native-migration.md), grounded in the Iceberg
   protocol reference of [Appendix C](appendix-c-iceberg-commit-protocol.md)): adopt the
   Iceberg REST catalog commit contract — typed requirements plus updates, the catalog
   service as sole author of metadata content, the HTS row CAS unchanged as the atomic
   arbiter — or derive the same typed updates server-side over today's wire contract,
   which needs no client change at all. Both eliminate the client-authority weakness by
   construction; only REST adoption also removes the whole-table conflict granularity and
   retires the bespoke client, which is why it is recommended, conditional on OpenHouse
   committing to that retirement. **13–22 engineering-weeks remain** of the 15–25
   originally scoped for it, the prototype phase being delivered here.
   A working prototype of that commit path is included in the same change set as
   this document (`services/tables/src/main/java/com/linkedin/openhouse/tables/resthandler/`): a
   REST commit endpoint that validates requirements against fresh state, rebuilds
   metadata server-side, and commits through the unchanged internal catalog and HTS
   CAS — passing a 35-test matrix that includes a no-snapshot-loss regression
   mirroring the #612 interleaving and an injected-race server-side re-apply, with
   the legacy commit path untouched.

## Reading guide

[protocol.md](protocol.md) is the place to start; everything else supports it.

| Document | What it answers |
|---|---|
| [protocol.md](protocol.md) | How a commit actually works, end to end, with sequence diagrams — the primary document |
| [Appendix A](appendix-a-snapshot-drop-bug.md) | What the snapshot-loss bug was, the exact interleaving, and why fix #612 works |
| [Appendix B](appendix-b-code-review.md) | What is still wrong with the commit path: 29 adjudicated findings with evidence and tiers |
| [Appendix C](appendix-c-iceberg-commit-protocol.md) | How Apache Iceberg commits natively and over REST, as the reference model |
| [Appendix D](appendix-d-rest-native-migration.md) | What moving to REST-catalog-native commits takes: design, phases, estimate, risks |
| [Appendix E](appendix-e-tla.md) | The TLA+ model: invariants, the reproduced violation, the verified fix |
| [sequence-diagram.puml](sequence-diagram.puml) | PlantUML sources for the happy-path and conflict-path diagrams |
| [tla/](tla/) | The spec, TLC configurations, and run logs |
| `services/tables/.../resthandler/` | The REST-native commit prototype; its test matrix spans the tables-service tests (`IcebergRestCommitControllerTest`, `RestUpdateValidatorTest`) and the internal-catalog tests (`RestNativeCommitOperationsTest`) |

## How to verify the claims

Every code claim is pinned to repo commit `2a9dac8` as `path:line`. The TLA+ results
re-run in seconds: the commands are in [Appendix E](appendix-e-tla.md) §8, using the
spec and configurations in [tla/](tla/). The code-review findings each carry their
evidence inline, and [Appendix B](appendix-b-code-review.md)'s adjudication log records
every merge, disagreement, and verification correction, including how much of the
briefed experts' output the blind expert reproduced independently (~55%, including
every top-tier defect).
