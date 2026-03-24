# RFC Templates

## Fetch Status

All three docs returned HTTP 401 — LinkedIn SSO required, not accessible without auth.

| Doc ID | Status |
|---|---|
| `1jf7XeU0vvI94wf2I8dOHcBHNJwm0jY1eySGo-CDW-do` | 401 auth required |
| `1zpFPDjnh9RKAzFZZ5fzdqXBfujhzJMO5MdfHaNFRQdk` | 401 auth required |
| `1PtvPdpWc_2_toAjeUAdXxRpQtvJlpZfIxyE7Qg0x8Pc` | 401 auth required |

## Fallback: Inferred conventions

Based on general LinkedIn eng RFC norms and the structure of the design docs already in
`~/code/docs/optimizer-current/`:

### Standard sections (internal eng RFC)
1. **Overview / Summary** — 1–2 paragraph problem + solution
2. **Motivation** — why now, what breaks without this
3. **Non-Goals** — explicit scope boundary
4. **Design** — architecture diagrams + component descriptions
5. **Alternatives Considered** — rejected approaches with reasons
6. **Open Questions** — unresolved issues tagged with owner/date
7. **References** — links to related docs, PRs, code

### Conventions observed in optimizer-current/ docs
- Concise, technical tone; assume reader is an SWE familiar with Iceberg/Spark
- Diagrams expected inline (ASCII or Mermaid), not as appendices
- Failure modes documented per component, not in a single section
- Rationale for key decisions stated inline ("we chose X because Y, not Z because ...")
- Open questions are real questions, not rhetorical — each needs an answer

## Q&A answers (from Step 3.6)

**Audience / Structure**
Tiered document. Leadership reads first half of page 1; team + org read problem + sketch; only SMEs
reach detailed design paragraphs. Structure:
1. Mission / Problem (leadership, < half page)
2. Proposed Solution (leadership, 1 paragraph)
3. Design Sketch (team/org — high-level diagram + key components)
4. Detailed Design (org — APIs, DB model, data flow, error, observability)
5. Appendix: Design Decisions (trade-off tables with one-way / two-way door classification)

**Scope**
Architecture RFC. Include: APIs, DB model, architecture, data flow, error handling, observability.
Exclude: implementation details (those live in code review).

**Gaps framing**
Trade-offs / design decisions with one-way vs two-way door classification.
"Two-way door, fine to ship" → note it. "Hard to reverse, matters" → flag for follow-up.
Design Decisions appendix is explicitly requested.

**Diagrams**
Inline in the relevant sections.
