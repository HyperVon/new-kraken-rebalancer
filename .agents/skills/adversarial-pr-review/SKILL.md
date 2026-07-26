---
name: adversarial-pr-review
description: >-
  Local dual-model adversarial review loop for pull requests — Composer 2.5 Fast
  and Grok 4.5 High review in parallel, parent validates and fixes legitimate
  findings, then re-reviews until both report no further legitimate findings.
  Use when creating a PR, updating an open PR (commit/push to its head), or when
  the user asks for an adversarial / multi-model PR review.
---

# Adversarial PR review (local)

**Local only** — runs inside the current agent session. Not a Cursor Automation
and not cloud-triggered. A bare `git push` with no agent does **not** run this.

## When this skill is mandatory

Read and follow this skill **before finishing** any of:

1. **Open PR** — after quality gates, before `gh pr create` ([open-pr](../open-pr/SKILL.md)).
2. **Update open PR** — when [commit-and-push](../commit-and-push/SKILL.md) (or
   equivalent) will push to a branch that already has an open PR.
3. **Explicit ask** — user requests adversarial / multi-model review of a PR or
   branch diff.

If the branch has no open PR and the user is only committing WIP without opening
one, skip this skill unless they asked for a review.

## Models (required)

Launch **two** Task subagents in **one** message (parallel):

| Role | Model slug |
| :--- | :--- |
| Reviewer A | `composer-2.5-fast` |
| Reviewer B | `cursor-grok-4.5-high` |

Do **not** substitute other models unless the user explicitly overrides.

## Scope

- Diff under review = full PR change vs merge base (`main` or the PR’s base),
  not only the latest commit.
- Include intentional behavior changes; do not wave them through as “hygiene.”
- Cross-check claims against source (constants, layering, tests that would still
  pass if a fix were reverted).

## Loop

Repeat until **convergence**:

1. **Gather** — `gh pr diff` / `git diff <base>...HEAD`, changed file list, and
   any high-risk hunks (trading math, settings POST, persistence keys, side /
   fee / reconstruction paths).
2. **Review** — launch both reviewers with the same adversarial prompt (below).
   Read-only for reviewers: no edits, no `./gradlew`.
3. **Triage** — parent merges both reports. Keep findings that are
   **legitimate** (verified in source). Drop false positives, duplicates, and
   pure style nits that contradict project conventions.
4. **Fix** — apply legitimate `critical` and `warning` fixes on the PR branch.
   Optionally fix clear `nit`s in the same pass if small. Re-run project quality
   gates as needed ([gradle-quality-gates](../gradle-quality-gates/SKILL.md) /
   [commit-and-push](../commit-and-push/SKILL.md) pre-commit script).
5. **Re-review** — launch both models again on the **updated** full diff.
6. **Stop** when both reviewers report **no further legitimate** `critical` or
   `warning` findings (or only deferred L / explicit user-approved deferrals).
   Nits alone do not block shipping unless the user asked for polish.

### Convergence rules

- Cap at **5** full review rounds unless the user asks to continue.
- If the same disputed finding survives two rounds with no safe fix, document it
  in the PR body / comment and stop — do not thrash.
- Do **not** force-push, skip hooks, weaken assertions to greenwash, or expand
  scope into unrelated refactors.

## Reviewer prompt (use for both)

Give each Task agent:

- Repo absolute path and PR number(s) / branch → base.
- Paths to diffs if written under `/tmp` (or instruct them to use `gh pr diff` /
  `git diff`).
- Stated intent of the PR (behavior-preserving vs intentional behavior change).
- Highest-risk hunks quoted when known.
- Hunt list: correctness regressions in “refactors,” persistence key migration,
  trading-safety / silent defaults, reconstruction / side casing, layering
  inversions, dead-code removal that isn’t dead, tautological tests, docs that
  contradict code, **exchange semantic overclaims** (idempotency / uniqueness /
  retries / AddOrder fields — verify against official Kraken docs linked from
  [kraken-api-integration](../kraken-api-integration/SKILL.md), not PR prose;
  `userref` ≠ uniqueness, `cl_ord_id` is open-order uniqueness only).
- Output: markdown findings grouped `critical` → `warning` → `nit`, each with
  location, evidence quote, why it matters, optional one-line suggestion.
  **No edits. No builds.**

## After convergence

- Commit and push review fixes on the **current** PR branch when the user’s
  workflow already includes commit/push (or they asked to update the PR).
- Summarize for the user: rounds run, findings fixed, any deferred items.
- Then continue [open-pr](../open-pr/SKILL.md) or finish [commit-and-push](../commit-and-push/SKILL.md).

## Checklist

- [ ] Trigger matched (new PR, push to open PR, or explicit review ask)
- [ ] Both models used in parallel (`composer-2.5-fast`, `cursor-grok-4.5-high`)
- [ ] Full PR diff vs base reviewed each round
- [ ] Exchange / AddOrder semantic claims checked against official docs when
      present in the diff (code or docs/skills) — use links in
      kraken-api-integration
- [ ] Legitimate critical/warning findings fixed and re-reviewed
- [ ] Converged or deferred with an explicit note — no infinite loop
