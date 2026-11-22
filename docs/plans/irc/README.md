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
| `05-iceberg-patch-ledger.md` | Each LinkedIn Iceberg patch against Apache 1.12.0 | Delegated agent, spot-checked |

Suggested reading order for review: `03-inferred.md`, then `04-work.md`. A judgement rejected in the
first changes the second.
