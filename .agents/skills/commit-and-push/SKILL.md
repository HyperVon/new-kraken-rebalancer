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
git status
git branch --show-current
```

Ensure you are on the intended branch (not detached HEAD).

## Step 1: Documentation

- Update `CHANGELOG.md` (Keep a Changelog).
- Update `README.md` when features, stack, or package trees change.
- Sync `docs/ALGORITHM.md` / `FLOWS.md` / `EVALUATION.md` when behavior changes.
- Update JaCoCo exclusions in `build.gradle.kts` when packages move.
- Agent rules: `.agents/AGENTS.md` (not root `AGENTS.md`).

## Step 2: Pre-commit checks

```bash
./.agents/skills/commit-and-push/scripts/pre_commit_check.sh
```

Or manually:

```bash
! grep -nE '^##[[:space:]]*\[[Uu]nreleased\]' CHANGELOG.md   # must not match
npx markdownlint-cli .agents/AGENTS.md .agents/OPERATING.md CLAUDE.md .github/copilot-instructions.md CHANGELOG.md CONTRIBUTING.md README.md SECURITY.md docs/*.md .agents/skills/**/SKILL.md .agents/skills/**/*.md
./gradlew spotlessCheck
./gradlew build jacocoTestCoverageVerification
./gradlew :frontend-js:jsBrowserTest
```

The script fails fast if `CHANGELOG.md` still has an `## [Unreleased]` heading —
convert it to a dated SemVer heading (`## [X.Y.Z] - YYYY-MM-DD`) first (see
[changelog-and-docs-sync](../changelog-and-docs-sync/SKILL.md)). Canonical lint
paths live in [gradle-quality-gates](../gradle-quality-gates/SKILL.md).

Include `CONTRIBUTING.md` and `SECURITY.md` in markdownlint when present.
Fix Spotless with `./gradlew spotlessApply`. Do not proceed on failures.

Coverage expectations: JVM JaCoCo 95% line/method/instruction, 90% branch;
JS Karma 90% statements/functions/lines, 75% branches.

## Step 3: Commit

```bash
git add -A
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

`git status` should show the branch up to date with `origin/<branch>`.

## Checklist

- [ ] Docs/CHANGELOG/JaCoCo synced as needed
- [ ] Lint paths include `.agents/AGENTS.md`, skills, and present top-level docs
- [ ] If an open PR exists: [adversarial-pr-review](../adversarial-pr-review/SKILL.md) converged
- [ ] If opening a PR next: all Test plan verifications done first ([open-pr](../open-pr/SKILL.md))
- [ ] Tests green; pushed **current** branch via `gh`
