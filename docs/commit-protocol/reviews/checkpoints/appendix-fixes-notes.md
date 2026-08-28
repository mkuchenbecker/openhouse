# Checkpoint: applying review 16 to commit-protocol appendices

## Ground truth gathered
- TLC logs (docs/commit-protocol/tla/): prefix 223/103 depth 11 complete; postfix 221/99 depth 11 complete;
  prefix-large 389 generated / 218 distinct / **119 left on queue**, depth 7 (halted by violation);
  postfix-large 11,536/4,197 depth 20 complete; prefix-sharedcache 105/45. All "Finished in 00s".
- Prototype (delivered on this branch): services/tables/.../resthandler/{IcebergRestCommitController,
  IcebergRestCommitService, RestUpdateValidator, IcebergRestExceptionHandler, IcebergRestSerde}.java
  Route: POST /v1/rest/namespaces/{namespace}/tables/{tableId}/commit
  Tests: IcebergRestCommitControllerTest (15, services/tables .../e2e/h2/), RestUpdateValidatorTest (14,
  .../resthandler/), RestNativeCommitOperationsTest (6, internalcatalog test tree) = 35.
  NOT wired: operation-level authz (no OPA privilege call in the service), per-table feature toggle,
  spec-exact route/prefix, read plane, MySQL HTS certification.
  Token interceptor does cover /** (TablesMvcConfigurer:44).
- PR #36 (branch claude/tla-driven-commit-fixes): rename model in specs/tla/OpenHouseCommitRename.tla,
  rename_unguarded.cfg -> NoSnapshotLoss counterexample, 7-step trace, 183 distinct states;
  rename_guarded.cfg -> 6130 distinct states, invariants hold. Fix implemented (conditional JPQL + version bump).

## Estimate restatement
Original table P0 2-3, P1 2-4, P2 5-8, P3 4-7, P4 2-3 = 15-25. P0 delivered => 13-22 remaining.

## Requirement set settled for appendix D (each must discriminates)
M1 deletion requires naming (kills b, e)
M2 every pointer-moving path covered by the same conflict check (a fails: rename fixed separately)
M3 preserve OpenHouse policy gates at the commit point (kills d)
M4 HTS @Version CAS stays sole arbiter, no schema/layout/migration (kills d)
Constraint (not a column): legacy clients unmodified through the window, per-table rollback.
Shoulds: server retry for store races; spec-conformant unknown-state rendering; requirement-scoped
conflict granularity; idempotent answer to ambiguous retries (no option delivers).
Recommendation conditional: (a) vs (c) is not decided by a must; (c) reaches M1 with no client rollout.
