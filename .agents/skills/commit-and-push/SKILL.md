---
name: commit-and-push
description: >-
  Finalize changes — update CHANGELOG/README/docs, run quality gates, run
  adversarial PR review when updating an open PR, commit, and push the current
  branch with gh auth. Use when the user asks to commit and/or push (not for
  casual WIP).
---

# Commit and Push Workflow

When the user asks to "commit and push", "commit / push", or similar, follow
this workflow in order. Do NOT skip steps.

## Step 0: Status & branch

```bash
git status --short --untracked-files=all
git diff --cached --name-status
git diff --cached
git branch --show-current
git config user.name
git config user.email
```

Ensure you are on the intended branch (not detached HEAD) and author identity matches the user's intended git author. Never use destructive commands (`git reset --hard`, `git clean -fd`) without explicit user direction. If forced updates are authorized on a branch, create a backup branch (`git branch backup/<branch>-$(date +%Y%m%d)`) first.

Inspect pre-staged changes and all untracked paths as well as unstaged work.
Do not assume an existing index belongs to this task. If it includes unrelated
work, stop for a staging decision; do not unstage, overwrite, or commit it
without authorization.

## Step 1: Documentation

- Update `CHANGELOG.md` (Keep a Changelog).
- Update `README.md` when features, stack, or package trees change.
- Sync `docs/ALGORITHM.md` / `docs/FLOWS.md` / `docs/EVALUATION.md` when behavior changes.
- Update JaCoCo exclusions in the owning module build (`backend/build.gradle.kts`
  or `engine/build.gradle.kts`) when packages move.
- Agent rules: `.agents/AGENTS.md` (not root `AGENTS.md`).

## Step 2: Pre-commit checks

```bash
./.agents/skills/commit-and-push/scripts/pre_commit_check.sh
```

Or manually:

```bash
! grep -nE '^##[[:space:]]*\[[Uu]nreleased\]' CHANGELOG.md   # must not match
git ls-files -z -- '*.md' '*.mdc' | xargs -0 npx markdownlint-cli
python3 .agents/scripts/validate_skills.py
./gradlew spotlessCheck
./gradlew build jacocoTestCoverageVerification
./gradlew :frontend-js:jsBrowserTest
```

Prefer `pre_commit_check.sh` — it lints every tracked `*.md` / `*.mdc` via
`git ls-files` and runs `validate_skills.py` (frontmatter + link validation).
CI `npm run lint:md` covers `**/*.md` including dot-dirs (`--dot`); only `.mdc`
files such as `.cursor/rules/*.mdc` need the explicit tracked-file form above.

The script fails fast if `CHANGELOG.md` still has an `## [Unreleased]` heading —
convert it to a dated SemVer heading (`## [X.Y.Z] - YYYY-MM-DD`) first (see
[changelog-and-docs-sync](../changelog-and-docs-sync/SKILL.md)). Canonical lint
paths live in [gradle-quality-gates](../gradle-quality-gates/SKILL.md).

Include `CONTRIBUTING.md` and `SECURITY.md` in markdownlint when present.
Fix Spotless with `./gradlew spotlessApply`. Do not proceed on failures.

Before staging, inspect the change boundary:

```bash
git diff --check
git diff --stat
git diff --name-status
git status --short --untracked-files=all
git diff --cached --name-status
git diff --cached
```

Confirm that every changed path is intentional and that no generated file,
credential, runtime state, or unrelated user change is included. Do not use a
destructive cleanup command to make the worktree appear clean.

Read the complete unstaged diff and the contents of each candidate untracked
file before choosing explicit paths to stage. Do not stage a directory or glob
that can sweep in unreviewed work.

Coverage expectations are canonical in
[gradle-quality-gates](../gradle-quality-gates/SKILL.md): backend JaCoCo
95% line/method/instruction and temporarily 85% branch; engine 95%
line/method/instruction and 90% branch; JS Karma 90% statements/lines,
80% functions, 75% branches.

## Step 3: Commit

```bash
git add -- <reviewed-path-1> <reviewed-path-2>
git diff --cached --name-status
git diff --cached
git diff --cached --check
```

Stage only reviewed, explicitly named paths. Before every commit, inspect the
**full staged diff** and staged name/status list, and require
`git diff --cached --check` to pass. Confirm that the entire index contains only
authorized work, including anything staged before this workflow. Stop on
unexpected paths or hunks; never use blanket staging.

```bash
git commit -m "$(cat <<'EOF'
<type>: <concise description>

EOF
)"
```

Types: `feat`, `fix`, `refactor`, `docs`, `style`, `test`, `build`, `chore`.

## Step 4: Adversarial review when updating an open PR

```bash
gh pr list --head "$(git branch --show-current)" --state open
```

If an open PR exists for this branch, follow
[adversarial-pr-review](../adversarial-pr-review/SKILL.md) on the full PR diff
vs base **before** pushing. Partition it into bounded concern tracks, fix
legitimate findings (new commits as needed), re-run Step 2 quality gates, and
re-review affected tracks until that skill converges. Skip this step when there
is no open PR (WIP commit/push only).

When this push will **create** a PR (or you will open one next), also finish
every change-specific verification **before** `gh pr create` — see
[open-pr](../open-pr/SKILL.md) and OPERATING.md § Complete PR verifications
before opening. Do not push-then-open with unchecked “after merge” test-plan
items.

## Step 5: Push current branch

Push **the current branch**, not always `main`:

```bash
BRANCH=$(git branch --show-current)
gh auth setup-git
env -u GITHUB_TOKEN git push -u origin "$BRANCH"
```

If auth fails, `gh auth status` / `gh auth login`. Do not ask the user to
authenticate manually.

## Step 6: Verify

Verify both status and the exact pushed commit:

```bash
BRANCH=$(git branch --show-current)
git fetch --no-tags origin "$BRANCH"
test "$(git rev-parse HEAD)" = "$(git rev-parse "origin/$BRANCH")"
git status
```

`git status` should show the branch up to date with `origin/<branch>`. If the
SHA check fails, stop and investigate instead of claiming the push succeeded.

## Checklist

- [ ] Docs/CHANGELOG/JaCoCo synced as needed
- [ ] Lint paths include `.agents/AGENTS.md`, skills, and present top-level docs
- [ ] Untracked and pre-staged state inspected; only reviewed explicit paths staged
- [ ] Full staged diff and name/status reviewed; `git diff --cached --check` passed
- [ ] If an open PR exists: [adversarial-pr-review](../adversarial-pr-review/SKILL.md) converged
- [ ] If opening a PR next: all Test plan verifications done first ([open-pr](../open-pr/SKILL.md))
- [ ] Tests green; pushed **current** branch via `gh`
