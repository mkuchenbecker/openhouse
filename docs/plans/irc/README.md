# Iceberg REST Catalog and v3 for OpenHouse: planning notes

Bottom line: these notes plan the work. Nothing is implemented. Only `01-stated.md` carries Mike's
authority. Everything else is Claude's observation or judgement and is not agreed.

Each file is under 150 lines so it can be reviewed in one sitting.

| File | What it is | Whose voice |
| --- | --- | --- |
| `01-stated.md` | What Mike said | Mike |
| `02-facts.md` | What was checked in the repos, with what was not verified | Observation |
| `03-inferred.md` | Claude's judgements and three questions for Mike | Claude |
| `04-work.md` | The list of work and dependencies | Claude |
| `06-smoke-suite.md` | Proposed Spark SQL smoke scenarios | Claude |
| `08-findings.md` | What the first round of work found | Observation by delegated agents |
| `09-seam-result.md` | What the plugin seam does and where the two paths still differ | Observation by a delegated agent |
| `10-v2-defaults.md` | Whether column defaults on v2 tables survive the 1.12 line | Observation by a delegated agent |
| `11-harness-via-seam.md` | Delta-harness counts through the REST-shaped path, and the differences it showed | Observation by a delegated agent |
| `05-iceberg-patch-ledger.md` | Each LinkedIn Iceberg patch against Apache 1.12.0 | Delegated agent, spot-checked |
| `07-seam-design.md` | Design note for the plugin seam | Delegated agent, not agreed |

Suggested reading order for review: `03-inferred.md`, then `04-work.md`. A judgement rejected in the
first changes the second.
