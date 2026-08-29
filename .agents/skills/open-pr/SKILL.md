---
name: open-pr
description: >-
  Open a GitHub PR with gh — complete every verification in the PR test plan
  before creating the PR (never defer checks to after merge), pre-PR quality
  gates, mandatory adaptive bounded adversarial review, conventional title, and
  structured body. Use when the user asks to open or create a pull request.
---

# Open Pull Request Skill

## Non-negotiable: verify before `gh pr create`

**Always complete all verifications for a PR prior to creating the PR.** Prefer
taking the time to be sure the change definitely works over shipping faster with
unchecked boxes.

- Every item in the PR **Test plan** (and **Verification Results**) must be
  **actually run** and marked `[x]` **before** `gh pr create`.
- Do **not** list a check and leave it `[ ]` for “after merge”, “CI will catch
  it”, or “user can spot-check later”.
- Do **not** open a PR with known incomplete manual/UI/sim verification just
  because automated gates passed.
- If a check is not applicable, **omit it** from the body (or note N/A with
  reason) — do not leave an unfinished checkbox.
- UI / viewport / sim spot-checks that the change needs belong **here**, not
  post-merge. Use throwaway `--out-dir` captures when canonical screenshots are
  unchanged; still verify before opening.

See [OPERATING.md](../../OPERATING.md) § Complete PR verifications before opening.

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

## Step 3: Change-specific verification

Run whatever the change requires **before** drafting checked boxes:

| Change kind | Verify with |
| :--- | :--- |
| UI / CSS / History density | Sim boot + viewport check (`~1280–1440px`); `ui-manual-qa` / temp screenshots as needed |
| Appearance in README | `docs-screenshot-refresh` (canonical) or document why canonicals are unaffected |
| Behavior / trading path | Relevant Kotest / evaluation already in Step 2; add targeted runs if gaps |

Do not invent a Test plan item you have not executed.

## Step 4: Adversarial review (mandatory)

Follow [adversarial-pr-review](../adversarial-pr-review/SKILL.md) on the full
branch diff vs base **before** creating the PR. The parent must partition the
diff into bounded concern tracks, fix legitimate findings, and re-review
affected tracks until that skill converges.

## Step 5: Title & body

Conventional title (`feat:`, `fix:`, `docs:`, …). Body template:
`examples/sample_pr_body.md` (overview, changes, verification including
coverage/lint). Use explicit issue closing keywords (`Closes #123`, `Fixes #456`)
when addressing tracked backlog items.

**Test plan rule:** only checked items. Every `[x]` must already be done.
Confirm candidate `BASE_SHA...HEAD_SHA` diff has converged and all gates passed.

## Step 6: Create via `gh`

```bash
BRANCH=$(git branch --show-current)
gh auth setup-git
env -u GITHUB_TOKEN gh pr create --base main --head "$BRANCH" --title "<title>" --body "<body>"
```

Or:

```bash
./.agents/skills/open-pr/scripts/create_pr.sh
```

## Step 7: Return URL

Give the user the clickable PR link.

## Checklist

- [ ] Not on `main`; current branch pushed
- [ ] `pre_commit_check.sh` green with accurate AGENTS lint path
- [ ] All change-specific verifications done (no deferred “after merge” checks)
- [ ] [adversarial-pr-review](../adversarial-pr-review/SKILL.md) converged
- [ ] PR body Test plan / Verification Results all `[x]` or omitted as N/A
- [ ] Conventional title + structured body; `gh pr create` succeeded
