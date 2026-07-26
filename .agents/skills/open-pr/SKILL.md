---
name: open-pr
description: >-
  Open a GitHub PR with gh — pre-PR quality gates (Spotless, tests, coverage,
  markdown lint on .agents/AGENTS.md), mandatory dual-model adversarial review,
  conventional title, and structured body. Use when the user asks to open or
  create a pull request.
---

# Open Pull Request Skill

## Step 0: Branch & remote

```bash
git branch --show-current
git status
```

- Do **not** open a PR from `main`.
- Push the **current** feature branch before creating the PR.

## Step 1: Existing PRs

```bash
gh pr list --head "$(git branch --show-current)"
```

If one exists, return its URL instead of duplicating.

## Step 2: Quality gates

```bash
./.agents/skills/commit-and-push/scripts/pre_commit_check.sh
```

Must pass: markdown lint (`.agents/AGENTS.md` + skills), Spotless 120,
`./gradlew test`, `:frontend-js:jsBrowserTest`, and coverage expectations (JaCoCo
95%/90%, Karma 90%/75%).

## Step 3: Adversarial review (mandatory)

Follow [adversarial-pr-review](../adversarial-pr-review/SKILL.md) on the full
branch diff vs base **before** creating the PR. Fix legitimate findings and
re-review until that skill converges.

## Step 4: Title & body

Conventional title (`feat:`, `fix:`, `docs:`, …). Body template:
`examples/sample_pr_body.md` (overview, changes, verification including
coverage/lint).

## Step 5: Create via `gh`

```bash
BRANCH=$(git branch --show-current)
gh auth setup-git
env -u GITHUB_TOKEN gh pr create --base main --head "$BRANCH" --title "<title>" --body "<body>"
```

Or:

```bash
./.agents/skills/open-pr/scripts/create_pr.sh
```

## Step 6: Return URL

Give the user the clickable PR link.

## Checklist

- [ ] Not on `main`; current branch pushed
- [ ] `pre_commit_check.sh` green with accurate AGENTS lint path
- [ ] [adversarial-pr-review](../adversarial-pr-review/SKILL.md) converged
- [ ] Conventional title + structured body; `gh pr create` succeeded
