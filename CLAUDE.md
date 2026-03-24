# OpenHouse Claude Code Rules

## Task checklists

Multi-step tasks use `memory/tasks/<branch-name>.md` as the checklist file.
The branch name can be found with `git rev-parse --abbrev-ref HEAD`.

On every session start for an in-progress task:
1. Read `memory/tasks/<branch-name>.md`
2. Pick up at the first unchecked item
3. Do not re-do completed items

## Testing

Always run tests after writing or modifying them. Never leave a test change unverified.
Report results before considering a step done.

## Commands

Never compose shell commands using `&&`, `||`, `;`, or pipes. Each command is its own Bash call.
