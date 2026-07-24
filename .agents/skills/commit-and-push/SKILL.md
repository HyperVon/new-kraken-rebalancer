---
name: commit-and-push
description: >-
  Finalize changes — update CHANGELOG/README/docs, run quality gates, commit,
  and push the current branch with gh auth. Use when the user asks to commit
  and/or push (not for casual WIP).
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
npx markdownlint-cli .agents/AGENTS.md CHANGELOG.md README.md docs/*.md .agents/skills/**/SKILL.md
./gradlew spotlessCheck
./gradlew test :frontend-js:jsTest
```

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

## Step 4: Push current branch

Push **the current branch**, not always `main`:

```bash
BRANCH=$(git branch --show-current)
gh auth setup-git
env -u GITHUB_TOKEN git push -u origin "$BRANCH"
```

If auth fails, `gh auth status` / `gh auth login`. Do not ask the user to
authenticate manually.

## Step 5: Verify

`git status` should show the branch up to date with `origin/<branch>`.

## Checklist

- [ ] Docs/CHANGELOG/JaCoCo synced as needed
- [ ] Lint paths include `.agents/AGENTS.md` and skills
- [ ] Tests green; pushed **current** branch via `gh`
