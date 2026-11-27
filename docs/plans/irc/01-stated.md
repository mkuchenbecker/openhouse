# What Mike said

Bottom line: this file holds only statements Mike made, paraphrased tightly and grouped. Nothing here is
Claude's judgement. Claude's inferences live in `02-inferred.md`. If a line here is wrong, it is a
transcription error and should be fixed here.

## Goal

- S1. Add Iceberg REST Catalog (IRC) spec support and Iceberg v3 support to OpenHouse.
- S2. The work happens on the `mkuchenbecker` forks.
- S3. The outcome is a series of changes across multiple repos that culminate in a valid state where IRC
  is supported.
- S4. Views, multi-table transactions, scan planning, credential vending, register, and replication of v3
  tables are in scope. The work is massive.
- S5. "v3" is a label. Functional changes are needed for it to work.

## What is and is not a concern

- S6. Consumers are not the concern. There is a catalog plugin in the Iceberg fork.
- S7. The fate of the native API is not the concern.
- S8. Migration is not a concern. Valid state is.
- S9. Behavior changes are the most expensive thing. "Do not change behavior" is not to be taken to an
  extreme.
- S10. The surface area that matters for behavior is Spark SQL, not the catalog APIs.

## Prerequisites

- S11. Prerequisites are built first.
- S12. A prerequisite for both repos is to sync with the LinkedIn upstream.
- S13. The forks should be 1:1 equal to LinkedIn at the start.
- S14. The fork's existing `1.11.x` branch needs validation. Preference: start from scratch and reapply.

## Architecture

- S15. Today's architecture is four layers: catalog plugin, OpenHouse client, OpenHouse service,
  OpenHouse implementation.
- S16. Client features should be modeled as capabilities.
- S17. The direction is a native REST client.
- S18. The OpenHouse client should probably be hidden behind a REST-shaped API, so the catalog path can be
  swapped with a config.
- S19. Confirmed: that seam is in-process, inside the plugin, with the legacy client implementing a
  REST-shaped client interface.
- S20. In that world the client is reusable outside LinkedIn through the plugin architecture.
- S21. Ideally catalog capabilities are modeled so they compose with REST. Not strictly required.

## Platforms

- S22. Spark 3.5 is fine for now. The target to be looking at is 4.2+.
- S23. A Spark 4.2 target platform with IRC is valid.

## Tests

- S24. The delta-harness does some of the work. Claude should use independent judgement and land it.
- S25. Fewer tests that act as smoke tests may be the better way to get off the ground.
- S26. When changing a module, use TDD and test it black box before changing it.
- S27. Each piece of work has tests and is validated along the way.

## Execution model

- S28. This is a list of work and dependencies. Phases and sequencing are the wrong model.
- S29. Claude is the orchestrator. It delegates to preserve its own context and uses less capable models
  where the task allows.

## How to work with Mike

- S30. Keep what Mike said separate from what Claude inferred.
- S31. "Continue" is not agreement with Claude's work. Inferred requirements stay Claude's judgement
  until Mike says otherwise.
- S32. About 200 lines of prose is the most Mike can review well at one time.
- S33. Bottom line up front. Know the audience.

## Decisions on Claude's questions (2026-11-22)

- S34. Reset the openhouse fork's main to LinkedIn main, and rewrite history.
- S35. The openhouse build depends on the mkuchenbecker Iceberg fork for the new Iceberg line.
- S36. The clean Iceberg base is Apache 1.12.
- S37. After the reset of main was refused by a repository rule: Claude is mistaking form for function
  and getting blocked far too early. It is not blocked.
- S38. Use a branch. Main was never a requirement.
- S39. The mkuchenbecker forks are a requirement.
- S40. Claude must preserve its own context. Claude orchestrates. If Claude writes code itself, it has failed. Delegate.

## Statements of 2026-11-22, later

- S41. Claude is not to wait for Mike for anything that Claude could decide and that is a two-way door.
- S42. When Mike asks for status, assume he has zero context from his last message.
- S43. Making a design and expecting a review was a failure: it blocked needlessly. The decisions made
  should be recorded with references that can be audited later.
- S44. "i don't care about spark 1.2."
- S45. Replication is a leaking implementation detail. Make a task but leave it unaddressed, because Mike
  does not care. Long term, imagine performance tiering with managed performance. He cannot state strongly
  enough how much replication is an implementation detail.
- S46. Merges are fully controlled by Claude on branches. Each commit must have its qualitative "why"
  captured for auditability.
- S47. Column defaults are tricky. Mike thinks Parquet should be the target over ORC, with native Iceberg
  default values rather than OpenHouse default values.
- S48. Asked for a smart subagent to diagnose and speed up the testing iteration loops, because there seems to be iteration friction seen from the outside; this was not a directive to fix anything, and a fast answer on whether it is needed is useful.
- S49. Mike is fine with a bigger container if that is available.
- S50. Selective testing is important if the build cycle is long. The full suite is only needed on merge, and that can be asynchronous with fix-forward.
- S51. Asked for status with respect to the global task, avoiding implementation details.

## Statements of 2026-11-25 and 2026-11-27

- S52. [2026-11-25] "lets pause execution for now"
- S53. [2026-11-25] "Are all the artifacts that you've produced in a state that they're reviewable by another agent? I really want to make sure that we are keeping durable records such that another agent could pick this up. But I actually want to have a high bandwidth conversation with an agent to talk about what's been produced. So if you could produce a map of all of the artifacts, that would be incredibly useful to me."
- S54. [2026-11-25] "please add these docs to a place i can link to, such as a draft pull request"
- S55. [2026-11-27] "Before continuing, make a checklist and plan."
- S56. [2026-11-27] "before continuing, do a self review"

### Answer to the self review in `84-self-review.md`, 2026-11-27 (his numbers are its fault numbers)

- S57. [fault 1] "(1) there are in flight namespace prs authored by me. make sure you are using that and building on top of it."
- S58. [fault 2] "(2) so do the review. all prs should have a sub agent review. this is a standing order. my expectation is you have built up a lot of cruft on the branch. we will fix forward. the biggest externality is we will need to re-implemnt once done because the linear progression will read like slop."
- S59. [fault 3] "(3) use the duplicate work for reference."
- S60. [fault 4] "(4) is your big miss. This is a hard requirement in that this can't be contributed upstream given behavior changes without sufficient justification. it's not a two say door to change behavior. it is a two way door when it's documented for follow up and not a foundation decision that invalidates significant work. significant is a judgement call, but if a single agent can fix it with a single pull request less than a couple thousand lines with <20 files it's easily fixable."
- S61. [fault 6] "6 i have no line limit. i want results as effectively as possible."
