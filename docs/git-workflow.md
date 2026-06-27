# Git Workflow

## Branches

- `main` only carries stable baselines and reviewed merges.
- Active MVP work happens on `feat/neoforge-mvp-bootstrap`.
- Future docs-only changes use `docs/*`, chores use `chore/*`, fixes use `fix/*`.

## Commits

- Use Conventional Commits.
- Each commit must map to exactly one implementation step or one documentation step.
- Any commit that changes code, config, tests, or build logic must also update documentation in the same commit.
- `docs/implementation-log.md` is the minimum required documentation update for every non-trivial commit.

## Push Policy

- Push immediately after every commit: `git push origin <current-branch>`.
- Do not batch unrelated steps into a single commit.
- Keep the remote branch continuously reviewable.

## Worktrees

- Worktrees are optional and only for later parallel work after the remote baseline exists.
- Project-local worktrees must live under `.worktrees/` or `worktrees/`.
- Worktree directories must stay ignored by git.

## Verification

- Run the smallest relevant verification command before each commit.
- Record the command and outcome in `docs/implementation-log.md`.
- If verification is blocked by environment or network state, log the blocker explicitly before pushing.
