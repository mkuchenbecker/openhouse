# Views Work: Orchestration Plan & Execution Checklist

**Status:** Live tracking document, updated by the orchestrator as lanes progress.
**Branch:** `claude/iceberg-rest-spec-compliance-l0s2ju` (orchestrator-owned; lane work happens on
child/port branches listed below — only the orchestrator edits this file).
**Companions:** [views-iceberg-rest-compliance.md](views-iceberg-rest-compliance.md) (server
plan) · [views-client-plugin-plan.md](views-client-plugin-plan.md) (client plugin plan) ·
[Iceberg REST Catalog spec](https://iceberg.apache.org/rest-catalog-spec)

## 1. Work model

Three implementation lanes fan out in parallel; the orchestrator tracks them, runs
multi-reviewer reviews on each completed lane (per the code-review-skills
`review-orchestrator` flow, adapted: findings are applied as direct edits by the lane's
agent, not posted as PR feedback), and owns integration.

| Lane | Goal | Branch(es) | PR target |
|---|---|---|---|
| **P1 — Port upstream backend stack** | Carbon-copy upstream linkedin/openhouse [#696](https://github.com/linkedin/openhouse/pull/696) → [#697](https://github.com/linkedin/openhouse/pull/697) → [#698](https://github.com/linkedin/openhouse/pull/698) into this fork as three stacked draft PRs, fidelity-verified like [#43](https://github.com/mkuchenbecker/openhouse/pull/43) was for #694 | `claude/port-696-entity-type-discriminator`, `claude/port-697-hts-view-lifecycle`, `claude/port-698-hts-e2e-mysql` (+ `claude/feature-view-support` mirror if upstream's feature branch differs from fork `main`) | stacked: 696→base, 697→696, 698→697 |
| **P2 — Address #697 review feedback** | Work the ~35 blocking findings from the upstream #697 review on the fork's port-697 branch, with a findings→commit/disposition map; REST-catalog spec is governing where relevant | `claude/port-697-hts-view-lifecycle` (additional commits after the carbon-copy commits) | same PR as P1's 697 port |
| **S — Server REST views implementation** | Execute the server plan (all phases) incl. the arch-review dispositions in §2 | `claude/views-rest-server-impl` off `claude/iceberg-rest-spec-compliance-l0s2ju` | `claude/iceberg-rest-spec-compliance-l0s2ju` |
| **C — Client plugin implementation** | Execute the client plan incl. dispositions in §2 | `claude/views-rest-client-impl` off `claude/iceberg-rest-spec-compliance-l0s2ju` | `claude/iceberg-rest-spec-compliance-l0s2ju` |

Sequencing: P1 → P2 (stack must exist first). S and C run parallel to each other and to P1/P2.
Integration (orchestrator): merge S and C into `claude/iceberg-rest-spec-compliance-l0s2ju`
after each passes review, then add the cross-lane integration itest (§2 F1/gate-on e2e) that
neither lane can run alone.

## 2. Authoritative dispositions from the blind architecture review

A blind arch review (code-review-skills `arch-review`, verified against decompiled
`1.5.2.17` fork artifacts and the spec) produced findings F1–F9. These dispositions are
**binding on lanes S and C** and amend the two plan documents; each lane updates its own
plan doc to match as part of implementation.

- **F1 (blocker, both lanes) — per-operation views-disabled contract.**
  Server: render `VIEWS_DISABLED` (and `DATABASE_NOT_FOUND`) as `type:
  NoSuchNamespaceException` on the **create** and **list** routes, `type:
  NoSuchViewException` on load/replace/drop/HEAD — matching the spec's own per-route 404
  types. Client: on the enabled path, catch `NoSuchNamespaceException` /
  `NoSuchViewException` from the embedded catalog's `listViews` and return an empty list
  (Spark's `SparkCatalog.listViews` catches nothing). With the server change,
  `CREATE VIEW` normalizes via `SparkCatalog.createView`'s existing
  `NoSuchNamespaceException` handling — client tests assert the normalized
  `AnalysisException`, not a raw error.
- **F2 (blocker, lane C) — 1.5.2.17 client capabilities.** `RESTSessionCatalog.listViews`
  does **no** `next-page-token` paging (single GET) and `viewExists` issues GET, not HEAD.
  Remove both claims and their §6 test assertions; document them. Lane S adds the spec's
  protective server obligation to its plan and future-service notes: **when `pageToken` is
  absent, return all results.**
- **F3 (lane S) — own the whole `/v1/**` error surface.** `throw-exception-if-no-handler-found`
  + the global handler currently turn unknown-path probes into OH-envelope **400s**. The
  views error rendering must own `/v1/**`: `NoHandlerFoundException` under `/v1/**` → 404
  Iceberg envelope; `AccessDeniedException` on these routes → 403 Iceberg envelope (no
  stacktrace leakage); 401 stays a bare status (documented). Advice ordering must beat the
  global handler for these routes.
- **F4 (lane C) — truly lazy embedded catalog.** Construct + initialize the embedded
  `RESTCatalog` on first view operation, not in `OpenHouseCatalog.initialize` (whose eager
  `fetchConfig` would let a config failure break **table** operations). Bootstrap failure
  surfaces as that view operation's failure; tables untouched.
- **F5 (lane S) — `/v1/config` declares `endpoints`.** Emit the explicit list of the seven
  implemented routes (spec-sanctioned capability advertisement; empty config implies the
  default endpoint set, which is wrong in both directions for ≥1.6 clients).
- **F6 (lane S) — unwrap wire envelopes at the handler.** `ViewsService` speaks
  `ViewMetadata`, `List<MetadataUpdate>`, `List<UpdateRequirement>`, identifiers, page
  token — not `CreateViewRequest`/`UpdateTableRequest`.
- **F7 (lane S) — `openhouse.source-dialect` summary key is optional.** Server defaults it
  to the sole representation's dialect (unique-dialect rule makes this well defined);
  required only when representations are plural. Stock-client create must pass.
- **F8/F9 (doc nits, both lanes).** "No 422" claims scoped to the views surface; version
  cited as **1.5.2.17**; step-0 class-presence checks are answered affirmative (all needed
  REST/view classes present in `iceberg-core-1.5.2.17` and
  `iceberg-spark-runtime-3.5_2.12-1.5.2.17`) — record it and drop the fallback branches.
- **Serialization mechanism (lane S, decided):** controllers consume/produce `String`
  bodies parsed/serialized with Iceberg's own parsers (`RESTCatalogAdapter` style), no
  custom Jackson converters — malformed-JSON errors then belong to the views error surface,
  not the global handler.

## 3. Execution checklist

### Lane P1 — port the upstream stack ✅ (2026-08-28)
- [x] Fetch upstream `feature/view-support` + PR heads 696/697/698 (anonymous git read)
- [x] Mirror base branch: `claude/feature-view-support` (upstream tree absent from fork `main`; no shared git ancestry — fork syncs are tree copies)
- [x] Port 696 → [#45](https://github.com/mkuchenbecker/openhouse/pull/45) (exact upstream head SHAs, no cherry-picks; 17 commits)
- [x] Port 697 → [#46](https://github.com/mkuchenbecker/openhouse/pull/46) (22 commits)
- [x] Port 698 → [#47](https://github.com/mkuchenbecker/openhouse/pull/47) (1 commit)
- [x] Validation: housetables 291/291, common 15/15 on the 697 port; #698's MySQL E2E run in docker via its own `oh-only-mysql` recipe — 23/23 incl. database-backed discriminator cases. Note: fork CI fires only for base `main`, so stacked PRs carry local validation only.

### Lane P2 — address #697 feedback ✅ (2026-08-28)
- [x] Findings cataloged from the public PR pages — ~21 of ~35 threads render server-side; findings 9, 10, 12, 13, 16, 18–25, 28, 29 are hidden mid-timeline items GitHub does not serve anonymously, recorded as not-retrievable in the PR table (owner can paste them for a follow-up round)
- [x] Five thematic commits on the 697 port: collation pin (`ddl/0002`, `utf8mb4_0900_as_ci`), ingress bounds + genuine-duplicate-key-only 409, corruption vocabulary/hygiene (stable 500 + correlation id), service-owned `UserViewQuery`, view metrics ownership; 698 branch merged forward with E2E assertions re-pointed
- [x] Findings→commit disposition table appended to [#46](https://github.com/mkuchenbecker/openhouse/pull/46)
- [x] housetables 308/308, common 13/13; MySQL E2E on merged 698 head 23/23 (collation pin exercised via initdb)

### Lane S — server implementation ✅ (2026-08-28)
- [x] All seven §3.1 routes with §2 dispositions folded in; `/v2` views surface removed; backend stubbed per plan; serialization via `IcebergRestWire` + Iceberg parsers
- [x] Plan doc amended (F1, F2-obligation, F3, F5, F6, F7, F8/F9); `docs/specs/catalog.md` regen deferred (needs bootable service + widdershins)
- [x] tables 641/641, common 25/25; spotless clean on touched files
- [x] Draft PR [#49](https://github.com/mkuchenbecker/openhouse/pull/49); noted deviations: two pre-existing e2e tests re-pinned to F3's unknown-`/v1`-path contract; services/common audit seam widened (`AuditedResponseRenderer`) so view failures keep producing audit events

### Lane C — client implementation
- [ ] Client plan phases with §2 dispositions (F1 catch, F2 corrections, F4 lazy init)
- [ ] Plan doc amended
- [ ] MockWebServer suite (serving `/v1/config` before view calls) + gate-off parity itests green
- [ ] Draft PR into `claude/iceberg-rest-spec-compliance-l0s2ju`
- [ ] Gate-on e2e itest deferred to integration (needs lane S's service) — tracked below

### Reviews & integration (orchestrator)
- [ ] Lane S review (arch-review + spec, testing-review, pedantic-linter) → fixes applied → pass
- [x] Lane C review (arch+spec vs 1.5.2.17 bytecode, testing, pedantic-linter) → 1 testing blocker (F4 fault-injection gap) + suggestions → all 20 fix items applied on [#48](https://github.com/mkuchenbecker/openhouse/pull/48) head `262edfbb`; java-itest 63/63 (21 wire tests), Spark 3.5 222/222. Decisions: TLS warn (https-gated); User-Agent unconditional; `WebClientFactory.SESSION_ID` made public; displaced-catalog graveyard fixes the token-refresh race
- [ ] Lane P1/P2 review (fidelity + findings coverage) → pass
- [ ] Merge S, then C, into `claude/iceberg-rest-spec-compliance-l0s2ju`
- [ ] Add gate-on integration itest (client enabled ↔ stubbed server: SELECT falls through, CREATE VIEW → AnalysisException, SHOW VIEWS → empty)
- [ ] Full-tree test pass on the integrated branch

## 4. Lane log

| When (UTC) | Event |
|---|---|
| 2026-08-28 ~05:5x | Checklist created; lanes P1, S, C launched |
| 2026-08-28 06:0x | P1 complete: fork PRs [#45](https://github.com/mkuchenbecker/openhouse/pull/45)/[#46](https://github.com/mkuchenbecker/openhouse/pull/46)/[#47](https://github.com/mkuchenbecker/openhouse/pull/47) up at exact upstream SHAs; housetables 291/291, common 15/15, MySQL E2E 23/23. P2 launched |
| 2026-08-28 06:4x | Lane C complete: [#48](https://github.com/mkuchenbecker/openhouse/pull/48) (client plugin), java-itest 56/56, Spark 3.5 itest 222/222. Review fan-in launched (arch+spec, testing, pedantic-linter). Open Qs for review: TLS warn-vs-fail-fast; embedded-catalog User-Agent |
