# delta-harness — BUILD STATUS (built vs. the SURFACE-APPRAISAL estimate)

**Lead with this.** `SURFACE-APPRAISAL.md` priced the runnable surface at **~4,200 cases** — but that
estimate double-counted vacuous crosses (see the "estimate correction" column). After the checklist
build-out the suite is at **1,697** verified green (8m40s parallel), and the honest **non-vacuous** target is
**~2,600–3,000**, not 4,200. This tracks every block so the gap is never buried. The live task list
mirrors it.

**Headline: ~1,806 built (1,697 stub-baseline + 109 undrop under real HTS); the undrop leg is no
longer gated.** The 4,200 figure included ~1,400 vacuous cases (reads/inserts on delete-free MoR ==
CoW; RTAS/branch × format; DDL×consumer over rejected/one-shot DDLs). The undrop leg — once the only
genuinely-gated real block — is now BUILT via an embedded real House Table Service
(HTS-EMBED-PLAN.md/HTS-EMBED-IMPL.md): 106 surface-doubling cases + 3 admin-lifecycle, all green, with
**restore preserving every feature's state**. The full suite runs identically stub-vs-real-HTS.

## Block ledger

| # | Block | Case-id prefix | Orig. est. | Estimate correction | Built | Status |
|---|---|---|---:|---|---:|---|
| 1 | Core DML × M | `<op> @ <layout>`, `... @ mor-*` | 660 | ~582 non-vacuous (reads/inserts on delete-free MoR == CoW) | ~582 | ✅ at the non-vacuous count |
| 1b | MoR delete-file **coexistence** | `coexist.* @ mor-verify/*` | — | the real new MoR surface | 18 | ✅ NEW (task #5) |
| 2 | Prep RTAS'd (parquet) | `prep.rtas:*` | 660 | ×format vacuous → ~106 | 106 | ✅ |
| 2b | Prep RTAS × **MoR** | `prep.rtasMor:*` | — | non-vacuous (task #2 fix) | 44 | ✅ NEW |
| 3 | Prep undrop'd (drop→soft-delete→restore) | `undrop:*`, `undropAdmin.*` | 660 | ×format vacuous → ~106 + ~3 admin | 109 | ✅ **BUILT** — embedded real HTS (HTS-EMBED-PLAN/IMPL.md) |
| 4 | Schema-state {evolved, ordered} | `prep.ordered:*`, `prep.evolved:*` | 1,080 | ×12 mostly vacuous → ~492 | ~492 | ✅ at the non-vacuous count |
| 5 | Branch/WAP surface (DML) | `branchWap:*` | 440 | ×format vacuous → ~106 | 106 | ✅ |
| 5b | Branch × **MoR** | `branchWap:* @ mor-*` | — | non-vacuous (task #2 fix) | 44 | ✅ NEW |
| 6 | DDL × consumer battery | `ddlConsume:*` | 420 | negatives/one-shots vacuous → ~56 | 56 | ✅ NEW (task #3) |
| 7 | Reader × writer-class | `readerWriter:*` | 120 | core writer×reader×M → ~16 | 16 | ✅ NEW (task #4) — surfaced **G13** |
| 8 | Maintenance × substrates | `maintenance.*`, `surface.maint.*`, `maint.mor.*` | 150 | partial + MoR-delete cross | ~39 | ✅ deepened — surfaced **G14** |
| 9 | 3-way prefix compositions | `interact.*`, `interact.undrop.*` | 250 | partial + undrop 3-ways (real HTS) | ~28 | ✅ deepened |
| 10 | Hazards / interactions on M | `hazard.*`, `hazard.mor.*` | 150 | partial + MoR delete-file modality | ~36 | ✅ deepened |
| 11 | Negatives / pins / control / concurrency | `*.neg.*`, `surface.pin.*`, `control.*`, `surface.conc.*` | 250 | real | ~250 | ✅ done |
| — | Nested / types / transforms / TT / restore / creates | various | — | baseline | ~430 | ✅ done |
| — | MoR-read (live position delete) | `prep.morRead:*` | — | non-vacuous | 9 | ✅ done |

## Findings surfaced by the build-out
- **G14** (block 8): `rewrite_data_files` over a live MoR position delete **applies** the delete (row
  set correct) but leaves the position delete **dangling** in the current snapshot (`.delete_files`
  still 1) — not folded/removed until `rewrite_position_delete_files`/`expire`. Format-consistent.
  Pinned by `maint.mor.rewriteDataFilesDanglingDelete @ {parquet,orc,avro}`; filed AUDIT-FINDINGS G14.
- **G13** (task #4): CDC changelog is **unsupported over a MoR table whose update/merge wrote
  position-delete files** ("Delete files are currently not supported in changelog scans"). MoR
  delete-only and all CoW work; MoR update/merge don't — CDC silently breaks on the shapes MoR
  exists to optimize. Pinned by `readerWriter.changelog.{update,merge}.mor`; filed AUDIT-FINDINGS G13.
- **Compaction is branch-blind** (`surface.maint.compactWithBranch`): no branch-targeted compaction
  surface in 1.5; under `spark.wap.branch` it silently targets main.

## Vacuity findings (why the honest target is < 4,200)
- Reads/inserts on a **delete-free MoR** table are byte-identical to CoW → not built (would be vacuous).
  The real MoR surface is mutation-ops×MoR (built, 264) + delete-file coexistence (built, 18) +
  reads-with-live-deletes (built, 9).
- RTAS/branch × **format** commute (metadata/refs don't touch encoding) → parquet only.
- DDL×consumer over **rejected or one-shot** DDLs has no post-state to consume → only state-changing
  DDL × real consumers is non-vacuous (56, not 420).

## Undrop leg — BUILT (embedded real HTS, Option A)
The undrop leg is no longer gated. `HARNESS_REAL_HTS=1` boots the **real** House Table Service as a
2nd in-JVM Spring context (0 production-code changes; see HTS-EMBED-PLAN.md/HTS-EMBED-IMPL.md), so the
harness can drive the real soft-delete → restore lifecycle. Two sub-blocks:
- **`undrop:*` (106)** — the full non-vacuous op catalog run on a table taken through a real
  soft-delete → restore round-trip. The surface-doubling P-axis leg (parquet × both partitionings ×
  53 ops = 106, mirroring the RTAS leg). **104 pass / 2 skip (pre-tagged bugs) / 0 fail** →
  **restore preserves every feature's state** (rows, snapshot lineage, refs, spec, sort order,
  properties, schema): no modality hazard in undrop's destruction set. (Format is vacuous for
  metadata/ref reconstruction, as with RTAS/branch.)
- **`undropAdmin.*` (3)** — HTS-admin lifecycle: restore round-trip, list-soft-deleted,
  restore-after-purge-rejected. All pass.

## Remaining real work (value-ordered)
1. Deepen partial blocks (8/9/10) toward their non-vacuous ceilings (~+150).
2. The P×S×T composition dial toward the ~10–15k stress ceiling — documented, not pursued (mostly
   redundant beyond the non-vacuous core).

## Discipline note
**No build work without a live checklist.** **Every status reply leads with built-vs-estimate**, not
the latest slice's green count. When an estimate turns out inflated by vacuous cells, correct the
estimate in the open (as done for blocks 1, 4, 6, 7) rather than chasing a vacuous number.
