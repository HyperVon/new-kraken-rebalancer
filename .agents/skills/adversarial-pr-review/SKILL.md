---
name: adversarial-pr-review
description: >-
  Parent-orchestrated adaptive adversarial PR review — partitions a PR into N
  bounded read-only reviewer tracks based on file ownership and risk, validates
  findings in the parent, and re-reviews only affected tracks until convergence.
  Use when creating a PR, updating an open PR, or when the user requests an
  adversarial or multi-model PR review.
---

# Adversarial PR review (local)

**Local only** — runs inside the current agent session. It is not a Cursor
Automation or cloud-triggered workflow. A bare `git push` does not run it.

## When this skill is mandatory

Read and follow this skill **before finishing** any of:

1. **Open PR** — after quality gates, before `gh pr create` ([open-pr](../open-pr/SKILL.md)).
2. **Update open PR** — when [commit-and-push](../commit-and-push/SKILL.md) (or
   equivalent) will push to a branch that already has an open PR.
3. **Explicit ask** — when the user requests adversarial, multi-model, or
   multi-agent review of a PR or branch diff.

If the branch has no open PR and the user is only committing WIP without
opening one, skip this skill unless they asked for a review.

## Core operating model

This is a **parent-orchestrated review**, not a request for every subagent to
review the entire repository. The parent agent owns the review plan, merge
base, coverage matrix, triage, edits, quality gates, and final convergence
decision. Task agents are bounded read-only scouts or focused verifiers.

### Select N tracks from the change

`N` is deliberately not a fixed number. After inspecting the changed-file list
and high-risk hunks, the parent chooses the smallest useful set of independent
tracks. As a guide, a material PR normally uses **2–6 tracks**, with a hard
maximum of **8**; a tiny one-concern change may use one track when the parent
records why. Do not create one agent per file and do not duplicate a full-diff
review merely to satisfy a count.

Use only tracks represented by the diff. Typical tracks are:

| Track | Review question | Typical scope |
| :--- | :--- | :--- |
| CI / build / tooling | Can the workflow, toolchain, and gates run as claimed? | `.github/`, Gradle, scripts, configs |
| Runtime correctness | Did behavior, state transitions, or error handling regress? | Changed production modules plus named source dependencies |
| Trading / exchange safety | Are money, order, retry, idempotency, and Kraken claims safe? | Trading and Kraken hunks plus official docs |
| Persistence / security | Are credentials, schemas, migrations, permissions, and data-loss boundaries safe? | Persistence, config, security, Kilo permissions |
| UI / client behavior | Do SSR, CSS, HTMX, Kotlin/JS, and browser interactions remain coherent? | Changed view, CSS, frontend, and related tests |
| Tests / documentation | Do tests protect the change and do docs match source truth? | Changed tests, docs, skills, and projections |

Tracks must have disjoint primary ownership where possible. Add a second,
independent verifier only for a high-risk or disputed track; give that verifier
the finding and the smallest affected path set, not the original full prompt.

Before launching, write a compact parent-side matrix:

| Track | Files / hunks | Risk | Agent / model | Depends on | Stop condition |
| :--- | :--- | :--- | :--- | :--- | :--- |
| … | … | low / medium / high | … | none / track … | … |

## Context and delegation guardrails

These rules are mandatory because a stalled near-limit subagent is worse than
several small, independently useful reports:

- Give each agent an explicit file set and only the minimum source paths needed
  to verify those files. The parent may inspect the full diff; workers should
  not receive it by default.
- Target each delegated request well below the roughly **256K input-token**
  practical reliability boundary for GPT-5.6 Luna. Prefer prompts and source
  scopes that stay below about **128K**; split the track before it approaches
  **180K** when the host exposes context telemetry.
- Cap discovery agents at **8 tool iterations** unless the parent explicitly
  widens the cap for a named high-risk question.
- Require a final report of at most **12 lines** and at most **5 findings**.
  Reports contain findings only, not progress logs, raw file dumps, repeated
  prompts, or a complete transcript.
- Tell agents not to load unrelated skills, run builds, start servers, edit,
  inspect credentials, read runtime databases/logs, or perform the parent’s
  integration work.
- If a worker approaches its context or iteration limit, it must stop and return
  a compact partial report with uncovered paths. It must **not** attempt manual
  compaction and continue in the same task. The parent launches a new,
  narrower follow-up for only the uncovered paths.
- Never paste full prior reports into a follow-up. Pass only the relevant
  finding, path, source line, and one verification question.
- The parent owns all builds and final quality gates. Never run concurrent
  Gradle builds in the same clone.

## Agent selection and launch

Use the cheapest model that can answer each track reliably, escalating only for
high-risk financial, security, persistence, or disputed reasoning. Model
diversity is useful, but scope diversity is mandatory. The former OpenCode
fast/strong reviewer roles are optional model choices, not a requirement to
launch exactly two full-diff agents.

The routing rules below are harness-neutral. Named agent types are repository
or Kilo/OpenCode examples only; Cursor, Claude Code, Copilot, and other hosts
should map the same capabilities to their own read-only Task/equivalent agents.
Preserve the bounded scope, stop condition, report cap, and parent ownership
regardless of the host.

Prefer a repository-specialized read-only agent when its contract matches the
track. `general` is a last-resort fallback or parent-level cross-track verifier,
not the default for every track.

| Agent type / capability | Use when | Example role |
| :--- | :--- | :--- |
| Agent-guidance auditor | Rules, skills, CI, Kilo config, permissions, and harness guidance | Kilo `agent-guidance-auditor` |
| Documentation-contract auditor | Product docs checked against source/build/test truth | Kilo `documentation-contract-auditor` |
| Explorer | Narrow source discovery or evidence lookup outside specialized scopes | `explore` / host equivalent |
| Fast capable | High-volume bounded discovery or a specialized-track substitute | `adversarial-reviewer-a` when available |
| Strong reasoning | High-risk safety, persistence, exchange semantics, or disputed finding | `adversarial-reviewer-b` when available |
| Generic capable | Only when no closer specialized type is available | `general` / host equivalent |

Launch independent tracks in one Task message when the host supports parallel
calls. Include the track matrix in the prompts so agents do not redo one
another's work. A prompt must contain:

1. Absolute repository path, branch, and base.
2. The single track question and exact allowed paths or hunks.
3. The PR intent and already-completed context.
4. Forbidden files/actions, especially secrets and runtime data.
5. Acceptance criteria, iteration cap, and the compact output format.

### Automatic fallback on launch failure

Recover autonomously when an intended agent fails, is cancelled, or is
unavailable:

1. Retry once only when the failure appears transient.
2. Otherwise replace it with the closest available specialized agent for the
   **same narrow track**. Use a generic agent only when no closer type exists.
   Do not send the replacement the full PR diff.
3. If the replacement also fails, the parent covers **every uncovered
   acceptance criterion** with as many smaller sequential questions or
   parent-owned checks as needed. The track cannot be marked complete while its
   coverage matrix has unchecked paths or questions; if coverage cannot be
   completed, document the explicit deferral instead of claiming convergence.
4. Record role, intended agent/model, actual agent/model, scope, and reason for
   substitution in the verification notes.

Do not block the review on a provider-specific model. Do not claim a model ran
when the Task call returned an error or an empty report.

## Scope and evidence

The parent review surface is the complete PR change against its merge base
(`main` or the PR base), including intentional behavior changes. Each worker
sees only its assigned slice plus minimum dependencies. Agents must:

- cross-check claims against current source, tests, configuration, or official
  documentation;
- distinguish a concrete regression from a pre-existing defect or preference;
- flag correctness, safety, persistence, layering, dead-code, tautological-test,
  and docs/source contradictions in the assigned slice;
- verify exchange claims against official Kraken documentation when relevant;
- remain read-only and avoid `./gradlew`, servers, application data, and secrets.

Use this output format and nothing more:

```text
track: <name>
critical: No legitimate findings | <path:line> - <evidence, impact, smallest fix>
warning: No legitimate findings | <path:line> - <evidence, impact, smallest fix>
nit: No legitimate findings | <path:line> - <evidence, impact, smallest fix>
coverage: <checked paths>; <uncovered paths or none>
```

## Adaptive convergence loop

Repeat only for affected tracks:

1. **Inventory** — the parent captures the merge base, changed paths, risk
   areas, and the track matrix.
2. **Fan out** — launch N bounded, read-only tracks in parallel when independent.
3. **Triage** — the parent verifies each finding against source and removes
   duplicates, false positives, and style preferences that contradict project
   conventions.
4. **Targeted verification** — disputed or high-impact findings get a focused
   second verifier. It receives only the finding and affected paths.
5. **Fix** — the parent applies legitimate critical/warning fixes and small
   clear nits, then runs the required quality gates serially.
6. **Re-review** — re-run only tracks whose paths or dependent contracts changed.
   Add a cross-track verifier only when the fix crosses ownership boundaries.
7. **Converge** — every track has a final compact report, all changed high-risk
   paths are covered, and no legitimate critical/warning finding remains.

Cap the overall loop at **5 rounds** and each track at **3 review passes**. If
the same disputed finding survives two safe-fix attempts, document the evidence
and explicit deferral instead of thrashing. Nits do not block shipping unless
the user requested polish.

## After convergence

- Commit and push review fixes on the current PR branch when the workflow
  includes commit/push or the user asked for it.
- Summarize the number of tracks, scopes, model substitutions, findings fixed,
  deferred items, and quality gates.
- Continue [open-pr](../open-pr/SKILL.md) or finish
  [commit-and-push](../commit-and-push/SKILL.md).

## Checklist

- [ ] Trigger matched: new PR, push to open PR, or explicit review request
- [ ] Parent created an N-track coverage matrix; N was justified by the diff
- [ ] Each agent had a bounded scope, stop condition, and compact output cap
- [ ] Independent tracks ran in parallel where safe; no overlapping Gradle builds
- [ ] Failed agents were replaced with narrower scoped fallbacks and recorded
- [ ] Full PR surface is covered across tracks, without every agent rereading it
- [ ] Exchange / AddOrder claims used official docs when relevant
- [ ] Legitimate critical/warning findings were fixed and affected tracks re-reviewed
- [ ] Converged or explicitly deferred; no infinite loop or manual-compaction continuation
