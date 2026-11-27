# Authorize first on the Iceberg REST commit routes: record

## Bottom line (kept current)
In progress. Worktree `/home/user/oh-authz`, branch `irc/authorize-first-on-rest-commits` from `origin/irc-integration` at `81b4cd4d`.
Pushed: `59b3c3c5` (tests, red: 11 of 27 failed on the old code, each for the expected reason), `a51eda90` (fix; the 34
auth tests of the four auth classes pass on the default line). Next: broader IRC/view suites on both lines, merge, pull request.

## Steps done
1. Read both review reports (commit-write D-1, D-2; views-capabilities D1, D3) and the code of every `/iceberg` route.
2. Orchestrator addition received mid-task: the IRC view load now requires `GET_TABLE_METADATA` on the view's
   authorization reference, before the metadata file is read; view HEAD follows the table HEAD; view list unchanged.

## Design (decided before tests)
- Table commit (non-create) and one-table transaction: after `loadBase` (the same stored-table load the native PUT makes
  before its own check, needed to build the same authorization resource), and before requirements, updates, validation:
  - no updates: the privilege decisions of a table load (LOCK_ADMIN when legacy-locked, then GET_TABLE_METADATA);
  - updates: the write-path privilege (UPDATE_TABLE_METADATA, SYSTEM_ADMIN on a replica), as the core asks it.
  A refusal is audited as a FAILED table event (READ for a read; UPDATE / COMMIT / REPLACE_COMMIT for a write).
  The core's own checks still run afterwards, unchanged, so an authorized caller sees the same answers.
- Staged-create completion on an existing table: the assert-create requirement is checked first, so the answer is the
  fixed "already exists" text instead of a requirement message carrying stored ids.
- View commit: gate, row lookup (404s), then the replace privilege (UPDATE_TABLE_METADATA on the view's reference),
  audited as a FAILED REPLACE on refusal, before the metadata read and the requirement check. Empty view commit also
  needs GET_TABLE_METADATA.
- View load: gate, row lookup (404), GET_TABLE_METADATA on the reference, then the metadata read.

3. Commit `59b3c3c5`: tests first. Ran on the default line against the old code: 27 tests, 11 failed (all for the
   reason intended: 200 with metadata, 409 with the stored UUID, 400 from an update, 200 view load).
4. Commit `a51eda90`: the fix. Ran the four auth classes on the default line: 34 tests, 0 failed.
