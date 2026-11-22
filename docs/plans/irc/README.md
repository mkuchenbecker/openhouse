# Iceberg REST Catalog and v3 for OpenHouse: planning notes

Bottom line: these notes plan the work. Nothing is implemented. Only `01-stated.md` carries Mike's
authority. Everything else is Claude's observation or judgement and is not agreed.

Each file is under 150 lines so it can be reviewed in one sitting.

| File | What it is | Whose voice |
| --- | --- | --- |
| `18-state.md` | Start here. Current state: what is merged where, what is in progress, and how to audit | Claude |
| `01-stated.md` | What Mike said | Mike |
| `02-facts.md` | What was checked in the repos, with what was not verified | Observation |
| `03-inferred.md` | Claude's judgements and three questions for Mike | Claude |
| `04-work.md` | The list of work and dependencies | Claude |
| `06-smoke-suite.md` | Proposed Spark SQL smoke scenarios | Claude |
| `08-findings.md` | What the first round of work found | Observation by delegated agents |
| `09-seam-result.md` | What the plugin seam does and where the two paths still differ | Observation by a delegated agent |
| `10-v2-defaults.md` | Whether column defaults on v2 tables survive the 1.12 line | Observation by a delegated agent |
| `11-harness-via-seam.md` | Delta-harness counts through the REST-shaped path, and the differences it showed | Observation by a delegated agent |
| `12-openhouse-on-1.12-scout.md` | Local scouting of OpenHouse on the fork's Iceberg 1.12 line: what compiles, what fails, and how the fork could be resolved | Observation by a delegated agent |
| `13-decisions.md` | Decisions Claude made on two-way doors, each with reason, references and how to reverse it | Claude, not agreed unless it cites a statement |
| `14-native-defaults-design.md` | Design for native column defaults on the new Iceberg line, with work items W1 to W11 | Observation by a delegated agent, adopted with changes in D11 |
| `15-optin-build-result.md` | Result of building OpenHouse's Iceberg 1.5 variant against the fork's 1.12 line behind an opt-in switch | Observation by a delegated agent |
| `16-decisions-continued.md` | Decisions D13 to D21, continuing `13-decisions.md` | Claude, not agreed unless it cites a statement |
| `19-v2-irc-read-result.md` | Result of the IRC read routes (V2): routes, test counts, ten differences from the OpenHouse client, and what write routes will need | Observation by a delegated agent |
| `20-iteration-friction.md` | Diagnosis of slow test loops: memory, sleeping tests and uncached CI, with ranked changes | Observation by a delegated agent |
| `23-v1-commit-core-result.md` | Result of V1: the commit core, the candidate entry point, what bypasses it, test counts and what the IRC write routes can rely on | Observation by a delegated agent |
| `24-commit-rule-inventory.md` | Every write that reaches stored table metadata and every rule on the way, with the ten rules that cannot be stated over a metadata pair | Observation by a delegated agent |
| `25-optin-green-result.md` | Result of making the opt-in Iceberg 1.12 build green: test table, the two remaining failures, line detection and Spark SQL differences | Observation by a delegated agent |
| `17-delegation.md` | Which kind of agent takes which work item | Claude |
| `05-iceberg-patch-ledger.md` | Each LinkedIn Iceberg patch against Apache 1.12.0 | Delegated agent, spot-checked |
| `07-seam-design.md` | Design note for the plugin seam | Delegated agent, not agreed |

Suggested reading order for review: `03-inferred.md`, then `04-work.md`. A judgement rejected in the
first changes the second.
