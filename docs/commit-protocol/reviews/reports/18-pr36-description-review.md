# Writing review: mkuchenbecker/openhouse#36, title and body

Target: the `title` and `body` fields of PR #36 only. Diff (23 files, +1258/-39) and the
repository at `/home/user/openhouse` were read for factual grounding, not reviewed.

Criteria applied: the hard rules (authority 1), the writing-review skill procedure and findings
contract (authority 2) against the local copies of `STRUCTURE.md` and `SKILL.md`, and the PR-description
genre standard (authority 3). No disagreement between the hard rules and the local references arose
except one, noted under rule (a) below.

## (i) Hard-rule compliance

| Rule | Result | Violations |
|---|---|---|
| a. No em-dashes | Pass | 0 |
| b. Complete sentences in prose | Pass | 0 |
| c. Layered explanation, no conclusion first appearing in a lower layer | **Fail** | 3 |
| d. Every paragraph leads with its main point | **Fail** | 1 |
| e. Tables instead of context-heavy lists | Pass | 0 |

Rule (a) detail: zero em-dashes (U+2014), zero en-dashes (U+2013), zero spaced-hyphen or
double-hyphen substitutes, in both title and body. Note the disagreement to record: `SKILL.md` §14
and its false-positive list both soften the ban ("If the sample uses em dashes, keep them at about
the same rate", "Em dashes alone... are evidence only when paired with formulaic sales-y rhythm").
The hard rule bans the character outright and wins. It does not matter here, because the count is zero
either way.

Rule (b) detail: every prose sentence outside tables and headings is complete. `**TLDR.**` is a layer
label followed by a complete sentence, functioning as an inline heading; not counted. The
checkbox items (`- [x] Client-facing API Changes`) are verbatim structure from
`.github/pull_request_template.md`, supplied by the repository, and are not prose.

Rule (e) detail: both context-bearing lists in the body are already tables (the Sketch layer table
and the Testing Done test table), and each is introduced by a sentence stating what it shows, as
`STRUCTURE.md` rule 2 requires. The remaining lists are the template's single-attribute checkboxes.
No list in the body should be a table that is not one.

## (ii) Findings, most severe first

### 1. The Sketch declares three Java layers; 61% of the added lines are a directory it never mentions

| Field | Content |
|---|---|
| location | Section `### Sketch`, first sentence and table |
| principle | Hard rule (c), layered explanation; `STRUCTURE.md` rule 7, each layer expands the one above and introduces no new conclusions |
| claim | The Sketch describes the shape of the change as three Java layers, and the largest single component of the diff, the new `specs/tla/` directory, first appears three sections later inside Testing Done. |
| evidence | "Three layers change, and the HTTP contract stays backward compatible." The table lists `UserTableHtsJdbcRepository.renameTableId`, `UserTablesServiceImpl.renameUserTable`, and the `OpenHouseInternalTableOperations` rename branch. The diff adds eight files under `specs/tla/` (`OpenHouseCommitRename.tla` 382 lines, `README.md` 85, three `.cfg` files, three `tlc-*.out` files totalling 267), 769 of the PR's 1258 added lines. The only mention is "committed under `specs/tla/` with its configurations and captured checker output", in the last paragraph of Testing Done. |
| failure scenario | The senior engineer reads the Sketch, forms a mental model of a three-file Java change plus tests, opens the diff, and finds a third of the file list is a formal-methods directory the description did not prepare them for. The surprise is where trust in the rest of the description goes, because the one section whose job was to describe the shape of the diff got the shape wrong. |
| severity | blocker |
| confidence | confirmed |
| reviewer | writing-review |

The fix is structural, not additive: the Sketch table needs a fourth row naming `specs/tla/` and what
a reviewer is expected to do with it, and the Sketch's lead sentence needs to stop saying "three".

### 2. A Java signature change appears for the first time in the lowest layer, contradicting an unchecked box

| Field | Content |
|---|---|
| location | Section `# Additional Information`, second prose paragraph |
| principle | Hard rule (c); `STRUCTURE.md` rule 1, conclusion on top; `none (internal consistency)` for the checkbox contradiction |
| claim | "The `HouseTableRepository.rename` Java signature does change, and every implementation and test fixture in this repository is updated here" is a new conclusion introduced below every layer that should have carried it, and it sits directly under an unchecked `- [ ] Breaking Changes` box. |
| evidence | The TLDR, the Summary paragraphs, and the Sketch never mention the interface signature. The Sketch table's `HouseTableRepository` row does not exist; the diff modifies `HouseTableRepository.java` (+22/-1), `HouseTableRepositoryImpl.java`, `HouseTablesH2Repository.java` in two separate modules, and `tables-test-fixtures`. The `Changes` list checks "Internal API Changes" but `Additional Information` leaves "Breaking Changes" unchecked. |
| failure scenario | A reviewer who scans the checkboxes, as the template invites, concludes nothing breaks. A reviewer who reads to the bottom learns that an interface every fork implements changed shape. The two readings disagree, and the checkbox is the one most readers trust. |
| severity | blocker |
| confidence | confirmed |
| reviewer | writing-review |

### 3. The TLA+ paragraph is provenance, and it negotiates with the reader's skepticism instead of removing the need for it

| Field | Content |
|---|---|
| location | Section `## Testing Done`, final paragraph |
| principle | `STRUCTURE.md` rule 8, state the present and history goes to the ledger; `SKILL.md` §34, answering objections no one raised; genre standard, claims of rigor the reader cannot check from the PR itself |
| claim | The paragraph explains where the design came from rather than what is in the tree, and then pre-answers an objection the reviewer has not made, which draws attention to the weakness it is trying to cover. |
| evidence | "The design came from a TLA+ model of the commit protocol" is production history. "The unguarded model produces a lost-update counterexample and the guarded model checks clean" is checker output offered as a credential, and "checks clean" is the "all green" form the genre standard names. "Reviewers who want that argument can reproduce it from the README in that directory. Reviewers who would rather not can read the two tests above, which encode the same interleaving in Java" constructs a reader objection and answers it in the text. |
| failure scenario | The reader who is skeptical of formal methods reads a paragraph that anticipated exactly that skepticism and offered an opt-out. The offer confirms the suspicion that the model is decoration rather than evidence, and it converts a neutral reader into a skeptical one. The reader who is not skeptical still gets no answer to the question the diff actually raises: why 267 lines of raw TLC output are checked into the repository. |
| severity | suggestion |
| confidence | confirmed |
| reviewer | writing-review |

The information the reviewer needs about `specs/tla/` is one fact: it is in this diff and here is what
to do with it. That belongs in the Sketch table, as finding 1 says. The provenance, the counterexample,
and the escape hatch do not belong in the PR at all.

### 4. Local tool output is offered as evidence

| Field | Content |
|---|---|
| location | Section `## Testing Done`, second prose paragraph, final sentence |
| principle | Genre standard, tool output statistics quoted as credentials and process narration; `STRUCTURE.md` rule 8 |
| claim | "All three touched module suites pass locally, along with spotless and checkstyle" asks the reviewer to accept an unverifiable claim about the author's machine, duplicating what CI reports. |
| evidence | The quoted sentence. The PR reports `mergeable_state: clean`; the check runs are the authority on whether the suites pass, and the reviewer can see them. |
| failure scenario | The reader discounts the sentence, because there is no way to check it, and the discount carries to the claims around it that were checkable. |
| severity | suggestion |
| confidence | confirmed |
| reviewer | writing-review |

### 5. The test table enumerates what each test proves, which is what the test bodies do

| Field | Content |
|---|---|
| location | Section `## Testing Done`, first paragraph and table |
| principle | Genre standard, enumerations of tests with what each one proves; hard rule (d) for the lead sentence |
| claim | The paragraph leads with a coverage inventory and only reaches its point in the second sentence, and the table it introduces restates in prose what the two named tests assert in Java. |
| evidence | The paragraph opens "New tests cover the change at the repository, service, controller, and catalog layers, and the existing rename tests are updated." Its actual claim is the next sentence: "Two tests carry the correctness argument and are the ones worth reading". The table's right column ("A commit landing between the rename's read and its update raises a conflict, leaves the committed row intact, and creates no rename target") paraphrases assertions the reviewer will read verbatim, in the same PR, minutes later. |
| failure scenario | The time-limited reader pays for a section that tells them nothing they will not learn from the diff, and the one useful instruction, which two tests to open first, is buried behind an inventory sentence. |
| severity | suggestion |
| confidence | confirmed |
| reviewer | writing-review |

This is the rule (d) violation counted in the table above. It is reported here rather than separately
because its cause is the section's content, not its sentence order: the paragraph leads with an
inventory because an inventory is what the section was written to deliver.

### 6. The backward-compatibility claim is made three times

| Field | Content |
|---|---|
| location | `### Sketch` lead sentence; the paragraph after the Sketch table; `# Additional Information`, first prose paragraph |
| principle | `STRUCTURE.md` rule 1, support in descending order of importance, not repetition; `SKILL.md` §25 |
| claim | One claim about the HTTP contract is stated three times in three sections with no added information after the first. |
| evidence | "the HTTP contract stays backward compatible"; "so existing clients keep working and gain partial protection without any change"; "The HTTP contract stays compatible because the new query parameter is optional." |
| failure scenario | The reader on the third pass looks for what is new in the sentence, finds nothing, and starts skimming the section that also contains the signature change from finding 2. |
| severity | suggestion |
| confidence | confirmed |
| reviewer | writing-review |

### 7. The model-limitations caveat cannot change the conclusion

| Field | Content |
|---|---|
| location | `# Additional Information`, final prose paragraph |
| principle | `STRUCTURE.md` rule 5, a caveat must be able to change the conclusion |
| claim | The three limitations of the TLA+ model do not bear on whether the code change is correct or safe to merge, and the paragraph says itself that they are recorded elsewhere. |
| evidence | "The model's limitations are recorded in the spec header and in `specs/tla/README.md`. It uses a single renamer process, omits the commit-state-unknown window, and does not model the identifier change itself." If any of the three were addressed, the fix and its tests would be unchanged; the caveat fails the test in both directions. |
| failure scenario | The reader spends attention on the fidelity of a model they were not asked to trust, and learns that the description is willing to spend their attention on things that do not change the decision. |
| severity | suggestion |
| confidence | confirmed |
| reviewer | writing-review |

### 8. `#612` resolves to nothing in this repository

| Field | Content |
|---|---|
| location | `## Summary`, third paragraph |
| principle | `STRUCTURE.md` rule 4, the document stands on its own, undefined insider terms |
| claim | GitHub renders the bare `#612` as a link into `mkuchenbecker/openhouse`, where no such pull request exists. |
| evidence | "This is the same lost-update class as the stale-base snapshot loss fixed in #612". The repository has 36 pull requests. The number is established shorthand inside `docs/commit-protocol/protocol.md`, which uses it five times, so the intended reader probably recognizes it, but the rendered link is dead and the referent is upstream. |
| failure scenario | A reviewer who does not already carry the shorthand clicks a 404 and loses the one sentence that placed this fix in a known family of bugs. |
| severity | nit |
| confidence | probable |
| reviewer | writing-review |

### 9. Title scope under-declares, and names the mechanism twice

| Field | Content |
|---|---|
| location | `title` |
| principle | `STRUCTURE.md` rule 1, the conclusion is the subject line; `SKILL.md` §11, one name for one thing |
| claim | The conventional-commit scope `housetables` covers ten of the twenty-three changed files, and the mechanism is called "optimistic locking" in the title and "version CAS" in the body. |
| evidence | "fix(housetables): guard table rename against concurrent commits with optimistic locking". The diff also touches `iceberg/openhouse/internalcatalog`, `services/tables`, `tables-test-fixtures`, and adds `specs/tla/`. |
| failure scenario | A reader scanning the commit log for changes to the Iceberg catalog path does not see this one. |
| severity | nit |
| confidence | probable |
| reviewer | writing-review |

The title is otherwise the strongest line in the document: it states a disputable, actionable claim
(the rename is now guarded), names the defect class, and needs no context. Keep its shape.

### 10. Generation footer and session URL

| Field | Content |
|---|---|
| location | Body, last two lines |
| principle | `STRUCTURE.md` rule 8; `SKILL.md` §18, §20 |
| claim | The footer describes how the text was produced and links a session that no other reader can open. |
| evidence | "🤖 Generated with [Claude Code](https://claude.com/claude-code)" followed by a `claude.ai/code/session_...` URL. |
| failure scenario | Minimal. Recorded because the rules cover it, not because it costs the reviewer anything. |
| severity | nit |
| confidence | confirmed |
| reviewer | writing-review |

This is plausibly a required attribution convention rather than a writing choice, in which case it is
house style and not a defect. Flagged at nit severity for that reason.

### Sentence pass, folded

The sentence pass produced no finding that stands on its own. Recorded for completeness: no em-dashes,
en-dashes, curly quotes, title-case headings, bold mini-heading lists, filler phrases, hedge stacks,
inflated-importance vocabulary, or §7 stock words. Three triads appear ("raises a conflict, leaves the
committed row intact, and creates no rename target"; "uses a single renamer process, omits the
commit-state-unknown window, and does not model the identifier change itself"; "the repository,
service, controller, and catalog layers" is a four). Two are inside text this review proposes deleting
for other reasons, so the rhythm is not diagnosed separately. "The write was acknowledged and the
committed snapshot was gone" is a closing beat that restates the TLDR (§31, §25), but a single
emphatic short sentence after a mechanism paragraph is within the false-positive guardrails and the
prose earns it. Not a finding.

The prose is well above the bar this catalog was written for. Every finding above is structural.

## (iii) Deletion budget

Approximately **32% of the body**, 203 of 633 words excluding the generation footer.

| Cut | Opening words | Words | Disposition |
|---|---|---|---|
| Largest single cut: TLA+ provenance | "The design came from a TLA+ model of the commit protocol..." | 66 | Delete entirely. Replace with one row in the Sketch table naming `specs/tla/` as part of this diff and what the reviewer should do with it. |
| Test enumeration and table | "New tests cover the change at the repository, service, controller, and catalog layers..." through the two-row table | 84, keep ~25 | Reduce to one sentence naming `UserTablesServiceTest#testUserTableRenameConflictsWithConcurrentCommit` as the test to open first. Delete the "What it establishes" column; the assertions say it. |
| Coverage inventory and local run | "The remaining tests cover the happy paths with and without the token..." | 36 | Delete. The file list shows the coverage; CI shows the suites. |
| Model limitations | "The model's limitations are recorded in the spec header..." | 30 | Delete. The paragraph names the two places that already hold this, and the caveat cannot change the merge decision. |
| Duplicate compatibility sentence | "The HTTP contract stays compatible because the new query parameter is optional." | 12 | Delete. Stated twice already, better, in the Sketch. |

Counter-argument considered and rejected: every passage above is accurate, and the TLA+ material
describes files that really are in this diff, so a reviewer could argue all of it is relevant.
Relevance is not the standard the genre sets. A PR description is read once by someone about to read
the diff, and its job is to make the diff reviewable. Accurate content that the diff and CI already
show does not make the diff more reviewable; it delays the reader's arrival at it. The `specs/tla/`
directory is the one case where relevance is real, and the finding above is that the description
handles it in the wrong place and at the wrong length: it needs a table row, not a paragraph of
provenance and a paragraph of caveats.

What must not be cut: the TLDR, the mechanism paragraph naming `renameTableId` and the missing
version predicate, the `#612` family placement, the intentional-behavior-change paragraph, and the
entire Sketch table. That is the part of this description that does its job, and it is roughly half
the body.

## (iv) Verdict: would the described audience take this seriously?

**Yes**, with one reservation that is worth stating plainly.

Reasons the reader stays. The first two hundred words are the best case this PR can make: a specific
named method, a specific missing SQL predicate, a specific failure interleaving, a specific HTTP
status, and a specific statement that the behavior change is intended. Nothing in the opening is
padding, and nothing asks for trust that the reviewer cannot check against the diff. The Sketch table
is exactly the artifact a reviewer of a 23-file change wants: a map from layer to change, in the order
the call stack runs. A senior engineer reads that and concludes the author understands the bug. Nobody
closes this tab before reaching the diff.

Reasons the reader's confidence dips, in order of damage. First, the Sketch says the change is three
layers, and 61% of the added lines are a directory the Sketch does not name. That is the reservation:
the credibility the Sketch earned is spent when the file list contradicts it, and it is spent at the
exact moment the reviewer starts reading code. Second, "All three touched module suites pass locally"
is the sentence a skeptical reader stops on, because it is the one claim in the document that is
offered without a way to check it, and CI already answers the question. Third, the TLA+ paragraph
anticipates the reader's skepticism of formal methods and offers them a way out of it, which reads as
the author knowing the model will not persuade. Fourth, "Breaking Changes" is unchecked on a PR whose
body says a repository-wide Java interface changed shape.

None of the four is fatal. All four are removable without touching a claim the reviewer needs, and
three of them are removed by the deletion budget above.
