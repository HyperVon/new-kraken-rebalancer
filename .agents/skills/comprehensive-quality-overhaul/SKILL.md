---
name: comprehensive-quality-overhaul
description: >-
  Full-repository quality sweep using every project skill in parallel across
  multiple worktrees. Runs code review, AI slop detection, autonomous
  optimization, documentation review, skills/rules audit, security/dependency
  checks, test coverage analysis, and comment hygiene in a single coordinated
  cycle. Architecture and product reviews are captured as recommendations for
  later approval, not implemented automatically.
  Use for "improve everything", "total quality overhaul", "run all skills",
  "comprehensive quality sweep", or "kitchen sink quality pass".
---

# Comprehensive Quality Overhaul

Orchestrate a full repository quality sweep across isolated worktrees, then
integrate evidence into reviewable candidate PRs. This skill sequences child
skills; it does not replace their contracts, severity rubrics, or stop
conditions.

Read [ORCHESTRATION.md](ORCHESTRATION.md) before executing the fan-out. It
contains worktree setup, coordination protocol, free-route launcher mechanics,
retry handling, detailed track prompts, triage templates, and teardown.

## Trigger and non-goals

Use for “improve everything”, “total quality overhaul”, “run all skills”,
“comprehensive quality sweep”, or “kitchen sink quality pass”.

This skill:

- explores the repository and produces candidate PRs; it does not imply that
  every finding is automatically applied or merged;
- does not change live-trading behavior or credentials without explicit
  approval;
- does not replace adversarial-pr-review for high-risk PRs;
- does not run concurrent Gradle builds in one clone;
- does not boot the application inside parallel worktrees; app-boot work is
  parent-owned and serial;
- does not make architecture or product recommendations into implementation
  without explicit approval.

## Contracts

| Item | Contract |
| :--- | :--- |
| Trigger | Full-repository quality sweep requests listed above |
| Inputs | Repository state, current main/base, applicable project skills, and explicit approval for L-class changes |
| Outputs | Findings report, S/M candidate fixes, L proposals, PR triage, verification evidence, and separately authorized PR actions |
| Routing | Worker fan-out uses free routes only; follow the launcher/config rules in ORCHESTRATION.md |
| Isolation | Five worktrees: code, docs, skills/rules, tests/security/dependencies, and architecture/product |
| Stop | All tracks report, findings are triaged, approved changes are verified, and unresolved L items are presented as proposals |
| Parent owns | Integration, app boot, final gates, branch/commit/push/PR decisions, and teardown |

## Track ownership

| Track | Child skills | Default role |
| :--- | :--- | :--- |
| Code | code-review; autonomous-code-optimizer Pass 1+3 survey; kotlin-refactoring-and-cleanup; reduce-code-size; complex-code-comments; todo-resolution | Audit and report scoped S/M corrections; the parent applies approved changes |
| Documentation | documentation-review; changelog-and-docs-sync; user-guide | Audit and report scoped doc corrections; the parent applies approved changes |
| Skills/rules | rules-and-skills-audit; skill-reviewer; ai-slop-detector on skills/rules/docs | Audit and report guidance corrections; the parent applies approved changes |
| Tests/security/dependencies | continuous-quality; write-kotest; dependency-upgrade; ai-slop-detector on tests/build/security | Report defects and test/dependency corrections; the parent applies approved changes |
| Architecture/product | architecture-review; product-opportunity-review | Recommend only; all items are L-class until approved |

The parent may run ui-visual-review serially after integration if visual evidence
is required. Do not run the full four-pass autonomous optimizer here; use only
its Pass 1 and Pass 3 survey unless the user explicitly asks for exhaustive
convergence.

## Safety and verification invariants

- Preserve live-trading safeguards, credentials boundaries, dryRun versus
  simulation semantics, durable-order behavior, CORS/security controls, and
  project quality gates.
- Treat anything affecting live order behavior, dryRun/simulation, credentials,
  trading math/order paths, or major dependencies as L regardless of diff size.
  Stop and ask with evidence and a compensating-control proposal.
- Workers never inspect secrets, boot servers, run Gradle, commit, push, open
  PRs, or create issues. They write only within their assigned worktree and
  the parent-absolute coordination directory.
- Workers write heartbeats at least every 60 seconds and findings
  incrementally. The parent emits active-run status at least every 30 seconds.
- Never resolve overlapping findings inside the parallel wave; deduplicate and
  integrate in the parent.
- Do not claim convergence because exclusions or thresholds were widened.
- Do not use paid worker routes. Verify every selected route is free and not
  blacklisted; preserve the full blacklist when using a config override.
- Run app-boot and final verification serially. Keep build state isolated.
- Do not commit, push, open PRs, or delete worktrees beyond the authorized
  workflow and user-approved lifecycle.

## Execution outline

1. Establish a clean, current base; remove only this skill's leftover
   worktrees/coordination state; create five isolated worktrees; record model
   routes.
2. Under Kilo, run the receipt-managed ARR launcher in plan mode first, using
   the `comprehensive-quality-overhaul` workflow and `--free-only`. Never call
   Kilo's native `Task` tool as a fallback when the target ARR adapter exists.
   If the launcher reports `INCOMPLETE` for missing catalog/evidence, stop and
   report that state (or obtain the separate approval needed for refresh); do
   not reinterpret it as launcher unavailability. Launch all five tracks only
   after the exact plan is reviewed and approved. Other harnesses use their
   native bounded fan-out and must record the selected route themselves.
3. Poll heartbeats and findings; handle stalled tracks with one retry, or a
   finalize-only retry when a substantial partial diff exists.
4. Collect and deduplicate findings. Classify S/M/L; stop on L changes needing
   approval.
5. Build a candidate-PR triage table with dependencies, readiness,
   adversarial-review needs, merge order, and drop candidates.
6. After user approval, run the required serial gates and use the separately
   authorized commit/push/open-pr workflow.
7. Report complete/partial status, evidence, recommended merge sequence, and
   next decisions. Tear down skill-owned worktrees only after review.

The exact commands, coordination JSON, retry contract, report shape, and
teardown commands are in ORCHESTRATION.md; do not reconstruct them from memory.

## Candidate triage rules

- One finding or one cohesive theme per candidate PR.
- Flag security, bug-fix, and fail-closed items as high priority.
- Mark parallel-ready candidates only when file ownership and dependencies are
  disjoint.
- Status values are Ready to merge, Needs adversarial review, Needs approval,
  Blocked, or Drop candidate.
- Any PR touching trading math, Kraken I/O, CORS, the live-order journal, or
  credentials needs adversarial-pr-review before merge.
- Do not open a PR until the user approves the candidate plan.

## Compact completion checklist

- [ ] Base, branch, routes, worktrees, and coordination paths were recorded.
- [ ] Five tracks launched concurrently with free-only verified routes.
- [ ] Worker scope, heartbeat, incremental finding, and no-secret contracts held.
- [ ] App-boot work was requested to and executed by the parent serially.
- [ ] Findings were deduplicated and classified with evidence.
- [ ] S/M changes stayed scoped and L changes were presented for approval.
- [ ] Candidate PR triage includes status, dependencies, and merge order.
- [ ] Required quality gates and adversarial review ran before any approved PR.
- [ ] Partial runs, retries, and blockers are reported.
- [ ] Skill-owned worktrees and coordination state are torn down at the approved
      lifecycle point.
