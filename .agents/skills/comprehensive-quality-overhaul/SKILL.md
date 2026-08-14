---
name: comprehensive-quality-overhaul
description: >-
  Full-repository quality sweep using every project skill in parallel across
  multiple bounded tracks. Runs code review, AI slop detection, autonomous
  optimization, documentation review, skills/rules audit, security/dependency
  checks, test coverage analysis, and comment hygiene in a single coordinated
  cycle. Architecture and product reviews are captured as recommendations for
  later approval, not implemented automatically.
  Use for "improve everything", "total quality overhaul", "run all skills",
  "comprehensive quality sweep", or "kitchen sink quality pass".
---

# Comprehensive Quality Overhaul

Orchestrate a full repository quality sweep across isolated tracks, then
integrate evidence into reviewable candidate PRs. The default ARR/Kilo preset
uses read-only snapshots rather than writable worktrees. This skill sequences
child skills; it does not replace their contracts, severity rubrics, or stop
conditions.

Read [ORCHESTRATION.md](ORCHESTRATION.md) before executing the fan-out. It
contains the read-only delivery contract, free-route launcher mechanics, retry
handling, detailed track prompts, triage templates, and teardown boundaries.

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
| Isolation | Five read-only audit tracks. The default ARR/Kilo launcher gives each a temporary snapshot; a separate approved workflow is required for writable worktrees. |
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
  PRs, create issues, or write coordination artifacts. Read-only ARR/Kilo
  workers return their audit in the supervised terminal result; the parent owns
  any durable summary.
- Do not treat missing progress text, heartbeats, or coordination files as a
  stalled ARR/Kilo worker. Wait for the launcher's terminal structured result
  and configured deadline; only a terminal failure justifies one retry.
- Never resolve overlapping findings inside the parallel wave; deduplicate and
  integrate in the parent.
- Do not claim convergence because exclusions or thresholds were widened.
- Do not use paid worker routes. Verify every selected route is free and not
  blacklisted; preserve the full blacklist when using a config override.
- Do not mistake a Kilo `kilo-auto/*` UI/helper label for an ARR route. Route
  identity comes from the target-owned catalog candidate and its evidence.
- On `NO_ROUTE`, inspect rejection reasons once and report the blocker. Never
  broaden policy, waive `--free-only`, or switch to native delegation without
  an explicit user decision.
- Run app-boot and final verification serially. Keep build state isolated.
- Do not commit, push, open PRs, or delete worktrees beyond the authorized
  workflow and user-approved lifecycle.

## Execution outline

1. Establish a clean, current target; record the target revision and model
   routes. The registered read-only ARR/Kilo workflow does not create
   worktrees or a coordination directory.
2. Under Kilo, run the receipt-managed ARR launcher in plan mode first, using
   the `comprehensive-quality-overhaul` workflow and `--free-only`. Never call
   Kilo's native `Task` tool as a fallback when the target ARR adapter exists.
   If the launcher reports `INCOMPLETE` for missing catalog/evidence, stop and
   report that state (or obtain the separate approval needed for discovery or
   evidence preparation); do not reinterpret it as launcher unavailability.
   Do not add `--refresh` to plan mode. A cache refresh is a separate approved
   discovery operation and must be completed, validated, and followed by a new
   plan without refresh. Under Kilo, a successful catalog can still leave free
   routes blocked by TPS/tool-readiness evidence; request the explicit
   `--prepare-evidence --approve` step, which cannot launch workers, then plan
   again before asking for worker-launch approval.
   Kilo model enumeration is allowed to take several minutes; never replace
   the adapter's configured bounded deadline with a short manual timeout or
   exclude a provider from one short diagnostic alone.
   Launch all five tracks only after the exact plan is reviewed and approved.
   Other harnesses use their native bounded fan-out and must record the
   selected route themselves.
3. Wait for the terminal worker results; do not diagnose a stall from silence.
   Handle one terminal failure or timeout with at most one identical retry.
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

- [ ] Target revision and free-only routes were recorded; no unapproved
      worktree or coordination state was created.
- [ ] Five tracks launched concurrently with free-only verified routes.
- [ ] Read-only worker scope, terminal-report, and no-secret contracts held.
- [ ] App-boot work was requested to and executed by the parent serially.
- [ ] Findings were deduplicated and classified with evidence.
- [ ] S/M changes stayed scoped and L changes were presented for approval.
- [ ] Candidate PR triage includes status, dependencies, and merge order.
- [ ] Required quality gates and adversarial review ran before any approved PR.
- [ ] Partial runs, terminal failures, retries, and blockers are reported.
