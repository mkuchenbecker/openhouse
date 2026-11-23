# Decision log, continued a fifth time

Continues `51-decisions-continued-4.md`, which is full (its D41 carries a dated amendment).

Bottom line: one new decision, D43, dated 2026-11-23, the orchestrator's. It records the format v3 design choices
the orchestrator accepted while work item X1 (with W6, pull request 124) was being built, and one data-safety finding
that the same work closes. D42 (v3 off by default, chosen per table, never implied) still stands and D43 refines it.
Nothing here is agreed by Mike. Claude wrote the entry.

## D43. Format v3: how requests are accepted and refused

- Date: 2026-11-23. The orchestrator's decision, a two-way door, except that upgrading a real table stays Mike's
  (D12). Work items X1 and W6, branch `irc/x1-format-v3`, pull request 124 (not merged when this was written).
- Decision, in the order a request meets them:
  - With the cluster switch off, any request asking for format version 3 is refused with 400, on create and on
    upgrade, natively and over IRC.
  - A replace keeps the table's version. It honours an explicit 3 when the switch is on, and refuses a downgrade.
  - Registering v3 metadata, replicated tables at v3, v3-only types and encryption keys are refused. The message
    names the later work item that will handle them (X5 for types, X7 for replication).
  - A `write-default` below v3 is refused only when a commit introduces or changes it. A table that already carries
    one is not broken by unrelated commits.
  - The OpenHouse Java client sends `format-version` only when it commits v3 metadata over an absent or lower base.
- One native answer changes. A raw native create asking for format version 3 used to answer 201 and create a v2
  table. It now answers 400. Chosen because on LinkedIn's line the same Spark SQL statement fails, and silently
  creating v2 is neither the baseline nor honest.
- Finding that goes with it (data safety). On the Iceberg 1.12 line, before this work, a native table update with
  `format-version=3` upgraded a v2 table to v3 with no switch and no check. The update reaches Iceberg's property
  replacement, which accepts v3. It was reachable on the integration branch since the flip (pull request 118) and
  under the opt-in build before it. Nothing is deployed from the branch, so no table is known to be affected.
  Closing it is part of pull request 124, with a test that sweeps every write path. Recorded in
  `21-findings-continued.md`, thirteenth round.
- Why: a v3 table cannot go back and older readers cannot read it (D42). A refusal that names its reason is better
  than a quiet v2 table, and a v3 request that is accepted by accident is the worst case.
- Reverse by: each refusal is one check; removing it restores the earlier answer. A table that became v3 stays v3.
- References: D12, D42, `45-merged-pull-requests.md` for what is merged; the record of the work is
  `x1-format-v3.md` in the session's reports directory (outside the repository), with pushed head `5f7c4a44`.
