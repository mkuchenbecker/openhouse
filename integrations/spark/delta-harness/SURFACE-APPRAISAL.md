# Surface appraisal — the honest cross-product, re-derived from substrates

Response to the review: "core DML seems small; MoR belongs in core; surface/interaction tests must
cross with DML; branching should be a couple hundred; drop/undrop doubles everything; expect
10k–30k without vacuous tests." Verdict up front: **the review is directionally right on every
point**; the current 1,297 is a behavior-catalog with only two multipliers wired, not the full
substrate cross. This document re-derives the surface as **substrates × operations × consumers**,
evaluates each claim, and prices the honest cross-product with named pruning rules.

## 1. The architecture the suite already half-has (and should commit to)

Every case is `PREFIX (starting table state + write routing) → OPERATION → CONSUMER battery`.
The prefix is a composition of orthogonal **substrate axes**, each a data value (this is what makes
the "drop in a new file format and it just runs" requirement work — a new axis entry, zero new test
code; same for a partition-evolved state if that pin ever flips, asserted via `_spec_id`
old-data-old-spec / new-data-new-spec, a mechanism `surface.meta.hiddenColumns` already exercises):

| Axis | Values | Today |
|---|---|---|
| **L** layout | {unpart, part} × {parquet, orc, avro} = 6 | ✅ wired |
| **M** row-op mode | {CoW, MoR} = 2 | ⚠️ MoR is a side-bucket, not an axis (the review is right) |
| **P** prep lineage | {plain, RTAS'd, drop+undrop'd} = 3 | ❌ only plain (RTAS runnable now; undrop gated on embedded-HTS work) |
| **S** schema state | {base, evolved, ordered} = 3 (+partition-evolved, dormant behind the pin) | ⚠️ wired at L only, not L×M |
| **T** write target | {main, `branch_` syntax, `wap.branch` conf} = 3 | ❌ branch tested as a feature, not as a routing axis |

**Operation catalog (~150 behaviors):** DML 55 (53 + 2 partitioned-only) · DDL ~35 · maintenance 7 ·
history (TT/restore) 8 · branch lifecycle 10 · streaming/CDC 6 · control-plane 4 · negatives/pins ~25.

## 2. Evaluation of each review claim

**"Core DML seems small."** Partially agree. 53 ops is a solid Spark-SQL DML catalog (the merge/
subquery/overwrite variants are the bulk of what the engine distinguishes), but it reads small
because it's only crossed with L(6). The catalog itself could grow ~10 ops (more MERGE source
shapes, INSERT REPLACE WHERE, DataFrame-API variants) — the real growth is the crossing, below.

**"MoR mutation ops should be core, not MoR-scoped."** Agree, and it exposes a real gap: M is a
substrate property (`write.delete/update/merge.mode`), so ALL 55 ops belong on L×M=12 substrates —
including **reads**: today no read op runs against a MoR table with live position-deletes, which is
a distinct scan path (deletes applied at read time). Current shape (44 mutation ops × 6 MoR layouts,
reads excluded) under-covers reads and double-books writes as a separate bucket.

**"Surface/interaction tests must be crossed with DML."** Agree for the reader/writer features,
with a named boundary: streaming reads, CDC/changelog, write-configs, and metadata columns are
CONSUMERS whose correctness depends on which writer produced the data — the changelog must
represent each mutation class (append/overwrite/CoW-delete/MoR-delete/update/merge) correctly, per
M. That's a genuine reader×writer-class×M cross (~120 cases), none vacuous. **Not** crossed: pins
(views/ANALYZE/imports), the message-readability guard, namespace negatives — one-shot contracts
that DML cannot alter; crossing those would be the vacuous tests to avoid.

**"Branching should be a couple hundred — test the whole surface on the branch."** Agree. T is a
routing axis over the operation catalog: DML(55) + DDL(35, as G8-leak characterizations until the
guard exists) + maintenance(7) + TT/restore(8) + stream/CDC(6) ≈ 111 behaviors × {branch-syntax,
wap-conf} = **~220–440** depending on M. Pruning rule that survives scrutiny: branch × *format* is
vacuous (refs never touch encoding — the earlier decision stands), but branch × **M is NOT** —
cherry-pick rejects row-delete snapshots, so a MoR branch has a *different merge story* (G11 gets
worse: the silent-loss fallback isn't even available). So T runs at parquet × {unpart} × M(2).
**Empirical answer to the direct question (probed now, `surface.maint.compactWithBranch`):** main
compaction leaves branches intact and readable (safe); compaction under `spark.wap.branch` silently
targets MAIN (branch-blind — rewrote 0 branch files); there is **no branch-targeted compaction
surface in 1.5 at all**, so a long-lived branch's files are uncompactable until merged — an
operational finding in itself. SE on a branch = SE on the table (refs are table-global): that's G11.
OFD with branches: reachability-safe (H5), with the G11-composed residual edge.

**"Drop/undrop doubles the entire surface."** Directionally right, two qualifiers. (1) It's a
prep-prefix axis (P), and the multiplier applies to the batteries where lineage/metadata identity
matters — DML, TT/restore, branch, maintenance, streaming — not to negatives/pins (a rejection
doesn't care how the table was born; crossing those is vacuous). (2) The undrop leg is **gated on
engineering, not design**: the embedded soft-delete repo is a stub and public DROP hard-codes
purge (REST-FIDELITY-EVAL) — the embedded-HTS restructure is a prerequisite. The RTAS leg is
runnable TODAY and has already paid (G10: RTAS'd tables demonstrably misbehave), which is the
strongest argument that P is a high-yield axis, not a formality.

## 3. The priced cross-product (pruning rules named, nothing hand-waved)

| Block | Cross | Cases |
|---|---|---|
| Core DML | 55 × L×M(12) | **660** |
| Prep: RTAS'd | 55 × 12 (full; prune to 4 substrates only with evidence) | **660** |
| Prep: undrop'd | same, gated on embedded-HTS | *(660)* |
| Schema state | {evolved, ordered} × ~45 applicable ops × 12 (evolved-INSERT ops become explicit-column rewrites — themselves the H8 regression tests) | **1,080** |
| Branch/WAP surface | ~111 behaviors × T(2) × {parquet × M(2)} | **440** |
| DDL × consumer battery | 35 DDL × 6 consumers (DML-slice, TT, restore, expire, branch-merge, stream-resume) × 2 substrates | **420** |
| Reader × writer-class | {stream, CDC×4 bound-shapes, incremental} × 10 writer classes × M | **120** |
| Maintenance × substrates | 7 procs × 12 + compaction/SE/OFD × P × T slices | **~150** |
| 3-way prefix compositions | P×S, P×T, S×T at slice level (RTAS'd+evolved+branch → DML slice, etc.) | **~250** |
| Hazards/interactions on M | G11 × MoR (cherry-pick dead), expire-merge × prep, H-suite × substrates where state-relevant | **~150** |
| Negatives, pins, control, concurrency, msg-guard (deliberately NOT crossed) | — | **~250** |
| **Runnable-now total** | | **~4,200** |
| + undrop leg (post-HTS) | | ~5,500 |
| + full P×S×T composition on DML (the dial: 55×12×3×3×3 = 53k raw; composed only where planes interact) | | **~10k–15k** |

The 10k–30k expectation is **reachable honestly at ~10–15k**: the path there is not more behaviors
but turning the composition dial on P×S×T (every prefix pair/triple whose state planes interact —
and G10/G11/H-series prove these planes DO interact). 30k would require crossing the
negative/pin/message tier with substrates, which is where vacuity starts — recommend against.

## 4. The two engineering consequences (surface them now, not at 10k)

1. **Runtime**: ~0.6s/case → 4.2k ≈ 45 min (fits today's budget); 10–15k ≈ 2–3h → needs **sharding**
   (N harness JVMs, each with its own embedded server + namespace — embarrassingly parallel; 4-way
   ≈ 40 min at 10k). One-time harness change: shard-by-case-hash + merged report.
2. **Undrop prerequisite**: the embedded-HTS boot (SpringH2HtsApplication + de-@Primary the stub)
   unlocks the P axis third leg — worth doing before the axis, not after.

## 5. Immediate order of work (post-appraisal, on approval)

1. Promote **M to a substrate axis** (12 base layouts; reads-on-MoR-with-deletes gap closes) — restructures existing buckets, +~350 net cases.
2. Wire the **RTAS prep prefix** across DML/TT/branch/maintenance (+~700).
3. Build the **T axis** (branch/wap-conf routing) over the operation catalog (+~440, incl. DDL-on-branch characterizations and the MoR-branch merge story).
4. Extend **S to L×M** and add the H8-explicit-column insert battery (+~700).
5. Reader×writer-class cross (+120), DDL×consumer battery (+420).
6. Sharding, then the P×S×T composition dial toward 10k.
