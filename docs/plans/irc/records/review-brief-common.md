# Common brief for the retroactive reviews (read all of it before starting)

You are an independent reviewer. You did not write this code. You are READ-ONLY: no edits, no commits, no builds, no
Gradle, no GitHub comments, no messages to other agents. You read and you write ONE report file. The orchestrator
decides what gets fixed.

## What you are reviewing and why

A fork of LinkedIn's OpenHouse (`mkuchenbecker/openhouse`) has a branch `irc-integration` that is LinkedIn main
(commit `bb8929e1`) plus 71 merged pull requests (numbers 80 to 150), about 86,000 added lines in 711 files. The goal
of the effort: OpenHouse becomes a conforming Iceberg REST Catalog server that also supports Iceberg format v3, with
Spark SQL as the surface that matters. The pull requests were written by coding agents and merged by an orchestrating
agent that, for 69 of the 71, read only the author's report and the list of changed files. The two that got an
independent review both had real gaps. The owner's words on 2026-11-27, which define your job:

- "all prs should have a sub agent review. this is a standing order. my expectation is you have built up a lot of
  cruft on the branch. we will fix forward. the biggest externality is we will need to re-implement once done because
  the linear progression will read like slop."
- On behavior changes: "This is a hard requirement in that this can't be contributed upstream given behavior changes
  without sufficient justification. it's not a two way door to change behavior. it is a two way door when it's
  documented for follow up and not a foundation decision that invalidates significant work. significant is a
  judgement call, but if a single agent can fix it with a single pr less than a couple thousand lines with <20 files
  it's easily fixable."
- Earlier: "consider behavior changes the most expensive thing"; "surface area is spark sql not catalog apis";
  "v3 is a label, functional changes are needed for it to work".

So you look for four things: defects, behavior changes against LinkedIn main, cruft, and claims no test pins. And you
say what a clean re-implementation of your area should keep.

## Where things are

- Code, read-only checkout at the branch head `81b4cd4d`: `/home/user/oh-review`. Never write there.
  - Everything the effort changed in your area: `git -C /home/user/oh-review diff bb8929e1..HEAD -- <paths>`.
  - One pull request's change: `git -C /home/user/oh-review diff <merge>^1..<merge>` (the merge commits are listed in
    your assignment); its message (the author's and orchestrator's claims): `git -C /home/user/oh-review log -1 --format=%B <merge>`.
  - What LinkedIn main did before: `git -C /home/user/oh-review show bb8929e1:<path>`.
  - All merges in order: `git -C /home/user/oh-review log --first-parent --oneline bb8929e1..HEAD`.
- Notes and records (claims to check, never facts): `/home/user/openhouse/docs/plans/irc/` — `00-map.md` is the map,
  `45-merged-pull-requests.md` the merged list, `NN-...-result.md` the per-work summaries, `records/` the raw reports
  of the agents that did the work, the decision files hold D1 to D52, `01-stated.md` holds what the owner said (S1...).
  Form your own view of the code FIRST, then read the claims.
- The Iceberg fork the default build uses, read only: `git -C /home/user/iceberg show origin/openhouse-1.12.0:<path>`.
- Two Iceberg lines exist in the build: a plain build is the fork's Iceberg 1.12; `-PicebergLinkedIn` is LinkedIn's
  Iceberg 1.5.2 "reference line". Code under `src/iceberg-1.12` compiles only on the first.

## What a behavior change is (be strict)

Anything that existed on LinkedIn main `bb8929e1` and now does or answers something different when a deployment
changes nothing in its configuration: a native REST route of the tables service or House Tables (status, body, error
type or text, ordering, side effects, what is written to storage, what is audited or metered); what Spark SQL sees
through the existing OpenHouse catalog; what a maintenance job does or writes; what the Java client sends; what the
default build produces (artifacts, their contents, dependency versions, deleted modules); what LinkedIn's own CI
workflows do. NEW routes, new modules and anything behind a switch that is off by default are not behavior changes,
but say so explicitly and check the "off" path really is unchanged (a refactor that moved native code through a new
shared path can change behavior with the switch off: look for exactly that).

For each behavior change record: what existed, before and after (with file and line on both sides), which pull request
introduced it, whether it is on by default, whether the code or notes give a justification (quote where), whether it
is documented for follow-up (where), whether upstream would accept it as is (your judgement, one sentence), and the
size of restoring the old behavior or gating it: SMALL (one agent, one pull request, under about 2,000 lines and 20
files) or FOUNDATION (undoing it invalidates significant other work: say what).

## What cruft is

Things a careful upstream reviewer would reject or that a clean re-implementation would not contain: dead code and
unused parameters; two implementations of one thing; leftovers of an abandoned approach; test scaffolding or
test-only branches in main sources; comments that cite this effort's private ids (work items like "V17", decisions
like "D47", pull request numbers) or narrate history instead of explaining code; classes that grew by accretion and
should be split or merged; naming that follows the order work happened in rather than what the thing is; copies of
Iceberg or OpenHouse code that could call the original; configuration properties nobody reads; switches that guard
nothing; tests that duplicate each other; `@ConditionalOnClass`/reflection tricks that exist only to straddle the two
Iceberg lines (note them: they are a cost of the two-line build, and say whether each is still needed).

## Your report

Write it to `/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/reports/review-<slug>.md`
(your slug is in your assignment). Write it as you go, section by section, so a restart loses little. No length
limit; be complete rather than short, but every line must carry a finding or a checked fact. Sections, in this order:

1. **Bottom line**: is this area sound; the three to five things that matter most.
2. **Defects**, most severe first. Each: what goes wrong for a user, an operator or an attacker; file and line; the
   lines quoted; how sure you are (certain from reading / likely / cannot tell without running); the smallest fix or
   the test that would settle it. Mark each BLOCKER (wrong results, data loss, security, or breaks a stated
   requirement), SHOULD FIX, or MINOR.
3. **Behavior changes against LinkedIn main**: one table row per change with the fields above. This list must be
   complete for your area: go through every file your area changed that already existed at `bb8929e1`
   (`git diff --diff-filter=MDR --stat bb8929e1..HEAD -- <paths>` lists them) and account for each, even if the
   answer is "refactor, no behavior change, checked by reading <what>". Deleted files and moved files count.
4. **Cruft**, grouped by kind, each with file and line and what to do about it.
5. **Tests**: claims in merge messages or result files that no test would catch if false; assertions that cannot
   fail; tests that pin a defect as expected behavior (is the pin still right?); declared differences whose stated
   reason is a guess; anything skipped or assumed away.
6. **Per pull request**: a table of every pull request in your assignment: do the merge message's "Leaves true"
   claims hold (yes / partly / no / cannot tell by reading), and one line of why.
7. **For the clean re-implementation**: the essential design of this area in at most 15 bullets (what must survive),
   what should NOT be carried over, and the commit sequence you would propose for upstream (each commit one idea,
   reviewable alone, behavior-preserving steps before behavior-adding ones).
8. **Not checked**: what you did not look at and why.

Be concrete and adversarial; do not praise; do not soften. If a line of attack found nothing, say so in one line.
Where the code is fine, say it is fine and move on. Prefer reading the code path end to end over sampling.

## Final hand-back to the orchestrator (one message, at most 60 lines)

Counts (defects by severity, behavior changes by default-on / gated and by SMALL / FOUNDATION, cruft items), the top
findings in order, the path of your report, and what you did not check. Nothing else.
