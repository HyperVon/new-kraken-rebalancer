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

| Track | Files / hunks | Risk | Role / host route / effort | Depends on | Stop condition |
| :--- | :--- | :--- | :--- | :--- | :--- |
| … | … | low / medium / high | … | none / track … | … |

## Context and delegation guardrails

These rules are mandatory because a stalled near-limit subagent is worse than
several small, independently useful reports:

- Give each agent an explicit file set and only the minimum source paths needed
  to verify those files. The parent may inspect the full diff; workers should
  not receive it by default.
- Target each delegated request well below the selected route's documented or
  observed practical context limit. Prefer prompts and source scopes below
  about **128K**; split the track before it approaches **180K** when the host
  exposes context telemetry, or use those limits conservatively when it does not.
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

**MUST — no exceptions.** For every Kilo CLI session, you MUST invoke the
receipt-managed ARR launcher (`.agents/.agent-runtime-router/run.py --python`
wrapping `.agents/runtime-router/adapters/kilo/route_subagents.py`) with
`--workflow adversarial-pr-review --free-only --approve` to launch independent
review tracks. You MUST NOT use the native `Task` / `task` tool,
`ctx_batch_execute`, `ctx_execute`, or any in-process parallel scan as a
substitute for the routed launcher. A role label (e.g. `adversarial-reviewer-a`)
or a Kilo Auto tier does not select a provider/model route; only the launcher’s
exact route plan satisfies the model-selection gate. If it reports
`INCOMPLETE`, `NO_ROUTE`, or an execution error, stop and preserve that status;
do not silently fall back to same-model native subagents.

For `adversarial-pr-review`, the approval gate is the user's explicit request
to run an adversarial/multi-agent PR review. That request authorizes `--approve`
immediately; you do NOT need to ask for additional permission. Omit `--approve`
only when the workflow explicitly requires a separate human route decision
that the user has not yet provided.

For Kilo CLI sessions this handoff is **mandatory**: launch the independent
tracks through the `adversarial-pr-review` preset using the receipt-managed
`.agents/.agent-runtime-router/run.py --python` wrapper and the target ARR
adapter (with `--free-only --approve` after the exact-route gate passes) per the
target ARR adapter docs. Do not substitute raw
role-only `Task` calls for the preset — a role label does not select a
provider/model route. Include the track matrix in the parent task so agents do
not redo one another's work.

While the launcher runs, the parent MUST keep checking observability instead of
sitting idle: poll the per-run status file printed at launch (or the
the target ARR harness-state status pointer) every
60–90 seconds and read the background-process `logs` between polls (see "While
workers run" in the target ARR adapter docs). Status transitions
decide when to stop polling; a stalled run is stopped and retried once, not
waited on blindly.

`subagent_type` and other agent-role labels identify a capability or harness
role, not a model or effort level from their names alone. A profile is route
evidence only when host metadata exposes the selected route; do not claim that a
role changed the model. Record the selected route, effort when exposed, cost
class/entitlement, availability evidence, fallback, user approval, and any
substitution before launch. Native Auto handles its server-side model mapping
and fallback; repository scripts must not recreate that logic.

The routing rules below are harness-neutral. Named agent types are repository
or Kilo/OpenCode examples only; Cursor, Claude Code, Copilot, and other hosts
should map the same capabilities to their own read-only Task/equivalent agents.
Preserve the bounded scope, stop condition, report cap, and parent ownership
regardless of the host. **For Kilo CLI sessions the preset in
the target ARR adapter workflow is the required launch path (see below);
the role table is only a mapping aid for hosts that launch natively.**

Prefer a repository-specialized read-only role when its contract matches the
track. A generic role is only a last-resort role mapping after the native
model-selection gate has passed; it is not a model/provider fallback and cannot
authorize a material or parallel launch when route selection is unavailable.

| Agent type / capability | Use when | Example role |
| :--- | :--- | :--- |
| Agent-guidance auditor | Rules, skills, CI, Kilo config, permissions, and harness guidance | Kilo `agent-guidance-auditor` |
| Documentation-contract auditor | Product docs checked against source/build/test truth | Kilo `documentation-contract-auditor` |
| Explorer | Narrow source discovery or evidence lookup outside specialized scopes | `explore` / host equivalent |
| Fast capable | High-volume bounded discovery or a specialized-track substitute | `adversarial-reviewer-a` when available |
| Strong reasoning | High-risk safety, persistence, exchange semantics, or disputed finding | `adversarial-reviewer-b` when available |
| Generic capable | Only when no closer specialized type is available | `general` / host equivalent |

When running under Google Antigravity (AGY), launch reviewer tracks natively
using built-in `invoke_subagent` tool calls; do NOT execute
the Kilo-specific ARR workflow launcher. For other
non-Kilo, non-AGY hosts, use the host's native parallel task delegation. Include
the track matrix in the parent task so agents do not redo one another's work.
A prompt must contain:

1. Absolute repository path, branch, and base.
2. The single track question and exact allowed paths or hunks.
3. The PR intent and already-completed context.
4. Forbidden files/actions, especially secrets and runtime data.
5. Acceptance criteria, iteration cap, and the compact output format.
6. The selected host route and effort when exposed, cost class, fallback,
   availability evidence, and recorded user approval; do not launch if the host
   cannot expose a usable route.

Before reviewing the full diff, the parent may capture the bounded surface with:

```bash
./.agents/skills/adversarial-pr-review/scripts/review_surface.sh main
```

This reports merge-base, diff statistics, and changed paths without launching
agents or reading runtime data.

### Automatic fallback on launch failure

Recover autonomously when an intended role fails, is cancelled, or is
unavailable without pretending that a role replacement selected a model:

1. Retry once only when the failure appears transient.
2. Otherwise use only a preselected fallback route that the host can enforce and
   expose for the **same narrow track**. A different role label alone is not a
   valid fallback. Do not send the replacement the full PR diff.
3. If no host-enforceable fallback exists, stop fan-out and have the parent
   cover **every uncovered acceptance criterion** with sequential checks or
   keep the track explicitly deferred. The track cannot be marked complete
   while its coverage matrix has unchecked paths or questions.
4. Record requested route, selected route, effort, role, scope, availability,
   and the reason for any substitution in the verification notes. Never infer
   the selected route from a role label.

Do not bypass the native model-selection gate because a provider-specific
selection is inconvenient. Do not claim a model ran when the routed worker
returned an error or an empty report. The native Task wrapper remains a fallback
only when it exposes the selected route itself.

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
   conventions. Worker findings are not in the route report: read them from
   the worker session database per "Reading worker findings" in
   the target ARR adapter documentation.
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
