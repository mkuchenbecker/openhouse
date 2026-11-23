# Decision log, continued a third time

Continues `22-decisions-continued-2.md`.

Bottom line: three more decisions (D30 to D32), all dated 2026-11-23. D30 reverses part of D20 for creates after
Apache's compatibility kit showed the cost. D31 accepts two differences of Iceberg 1.12 and names one that is not
accepted. D32 is a question put to Mike, because it defines the goal; work proceeds on the first reading until he
answers. Each has its reason, the evidence it rests on, and how to reverse it. None is agreed by Mike unless the
entry cites a statement. Claude wrote every entry.

## D30. A location named by an IRC client is ignored on create

- Date: 2026-11-23.
- Decision: on IRC create, staged create and replace, a location named by the client is ignored, and the server's
  location is used and returned. A commit that changes the location of an existing table or view is still refused.
  This replaces the refusal chosen in D20 for creates. In progress on branch `irc/v13b-spec-deviations`.
- Why: Apache's compatibility kit has a switch for servers that own locations and expects them to override, which
  moves 13 kit tests. The response tells the client the real location, so no client is misled.
- References: `39-v13-compat-kit-result.md` (location group, work item 3); D20 in `16-decisions-continued.md`.
- Reverse by: restoring the 400.

## D31. Differences of Iceberg 1.12 accepted, and one not accepted

- Date: 2026-11-23.
- Decision: accepted as the new line's behavior on Iceberg 1.12:
  - `ALTER TABLE ... ADD COLUMN ... DEFAULT` is refused, where LinkedIn's line accepted the statement and silently
    dropped the default. This is consistent with D5 and D11: a refusal is better than a silent no-op.
  - After repeated DELETEs on one data file there is one live position delete file, where LinkedIn's line has
    three, because 1.12 replaces earlier delete files. The rows returned are the same.
- NOT accepted: an explicit time-travel read fails while a write-audit-publish branch is set in the session. That
  is being fixed in the Iceberg fork, on branch `irc/wap-time-travel` (item I11).
- Why: the first two are upstream behavior with no loss of data or of rows, and they keep the line free of fork
  patches. The third makes a working read fail for a user who stages writes.
- References: `40-i6f-full-regression-result.md`, "The delta-harness on 1.12" and "The three failures that were
  fixed"; D5, D11.
- Reverse by: patching the fork to accept the default statement, or to keep one delete file per commit.

## D32. What "conforming Iceberg REST Catalog server" means is put to Mike

- Date: 2026-11-23. Question asked 2026-11-23. Not decided.
- Question: does conforming mean meeting the specification while keeping OpenHouse's own rules, or passing
  Apache's compatibility kit by relaxing those rules over IRC?
- Working reading until he answers: the first. Spec-level deviations are fixed, routes not yet served are added,
  and OpenHouse's rules are not changed. Those rules are replace gating, no partition evolution, no column drop or
  rename, the Spark-only view dialect, databases that exist only while they hold tables or views, and the
  cluster-fixed format version.
- Why Claude did not decide it: it defines the goal, and the second reading changes rules that LinkedIn's
  deployment relies on.
- References: `39-v13-compat-kit-result.md` (31 of 160 kit tests pass; 84 of the rest are OpenHouse rules that the
  specification permits).
- Reverse by: Mike's answer. If it is the second reading, the class b work items in that file become the plan.
