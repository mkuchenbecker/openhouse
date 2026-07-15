# delta-harness — BUILD STATUS (built vs. the SURFACE-APPRAISAL estimate)

**Lead with this.** `SURFACE-APPRAISAL.md` priced the runnable surface at **~4,200 cases**. The suite
is at **1,519** (verified green). This tracks every block: estimated / built / remaining — so the gap
is never buried. Kept in sync every session; the live task list mirrors it.

**Headline: ~1,519 of ~4,200 built (~36%).** Not blocked — the rest is unbuilt or built at a minimal
slice, not turned to full cross. Undrop leg (~660) is the only genuinely-gated block (embedded-HTS).

## Block ledger

| # | Block | Case-id prefix | Est. | Built | Status |
|---|---|---|---:|---:|---|
| 1 | Core DML (target L×M=12) | `<op> @ <layout>`, `... @ mor-*` | 660 | ~582 | ⚠️ built at L=6 + separate MoR-mutation bucket; NOT restructured to L×M=12 (reads/inserts not on MoR) |
| 2 | Prep RTAS'd | `prep.rtas:*` | 660 | 106 | ⚠️ 53×2 parquet only. **Over-pruned: RTAS×MoR dropped (NOT vacuous).** ×orc/avro legitimately vacuous |
| 3 | Prep undrop'd | — | 660 | 0 | ⛔ GATED on embedded-HTS restructure (REST-FIDELITY-EVAL.md) |
| 4 | Schema-state {evolved, ordered} | `prep.ordered:*`, `prep.evolved:*` | 1,080 | ~492 | ⚠️ pre-existing at ×6, not extended to ×12; evolved only on delete/update/read |
| 5 | Branch/WAP surface | `branchWap:*` | 440 | 106 | ⚠️ 53 DML × 2 layouts × 1 mechanism × CoW. **Over-pruned: branch×MoR dropped (NOT vacuous).** DDL/maint/TT not yet routed onto branch |
| 6 | DDL × consumer battery | — | 420 | 0 | ❌ UNBUILT this session |
| 7 | Reader × writer-class | — | 120 | 0 | ❌ UNBUILT |
| 8 | Maintenance × substrates | `maintenance.*`, `surface.maint.*` | 150 | ~30 | ◐ partial |
| 9 | 3-way prefix compositions | `interact.*` (subset) | 250 | ~25 | ◐ partial |
| 10 | Hazards / interactions on M | `hazard.*`, `interact.*` | 150 | ~33 | ◐ partial |
| 11 | Negatives / pins / control / concurrency | `*.neg.*`, `surface.pin.*`, `control.*`, `surface.conc.*` | 250 | ~250 | ✅ done |
| — | Nested / types / transforms / TT / restore / creates (pre-appraisal) | various | — | ~430 | ✅ done (baseline) |
| — | MoR-read gap slice | `prep.morRead:*` | (token) | 9 | ✅ done (minimal, not the structural L×M promotion) |

## Remaining work queue (value-ordered; = live tasks #2–#5)

1. **branch×MoR + RTAS×MoR** (~90) — the two over-prune misses; my own appraisal says non-vacuous. *(task #2)*
2. **DDL × consumer battery** (~420) — unbuilt, high-yield. *(task #3)*
3. **reader × writer-class** (~120) — unbuilt; changelog/stream fidelity per writer class. *(task #4)*
4. **core DML → L×M, schema-state → ×12** (~+700) — largest single expansion. *(task #5)*
5. Undrop leg (~660) — deferred, gated on embedded-HTS (not a task until that lands).

Turning 1–4 lands ≈ +1,330 → ≈ 2,850; plus the L×M/×12 promotions closes most of the rest to the
~4,200 estimate. The ~10–15k ceiling is the further P×S×T composition dial (documented, not pursued).

## Discipline note
Going forward: **no build work without a live checklist**, and **every status reply leads with
built-vs-estimate**, not with the green count of the latest slice.
