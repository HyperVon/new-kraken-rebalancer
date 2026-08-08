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

**Orchestrator skill.** Runs every applicable project skill in parallel across
multiple worktrees, then integrates findings and ships a single quality PR.

This skill does **not** replace individual skills — it sequences them. Each
child skill owns its own contract, severity rubric, and stop conditions; this
skill provides the fan-out, worktree isolation, integration, and gate
orchestration.

Sibling: [continuous-improvement](../continuous-improvement/SKILL.md) for
lighter ongoing cycles. [continuous-quality](../continuous-quality/SKILL.md)
for test/QA-only hardening.

## Non-goals

- Does not automatically implement every finding. The goal is to **explore**
  and produce **candidate PRs**; the user judges which to merge.
- Does not ship live-trading or credential changes without explicit user approval.
- Does not replace the mandatory [adversarial-pr-review](../adversarial-pr-review/SKILL.md) before merge for high-risk PRs.
- Does not run concurrent Gradle builds in a single clone.
- Does not boot the application inside parallel worktrees (port conflicts,
  orphan processes). App-boot skills run serially by the parent after
  read-only discovery and coordination.

## Contracts

| Contract item | Value |
| :--- | :--- |
| **Trigger** | "improve everything", "total quality overhaul", "run all skills", "comprehensive quality sweep", "kitchen sink quality pass" |
| **Non-goals** | Architecture redesign, live-trading changes, credential changes without approval, booting app in parallel worktrees |
| **Inputs** | Fresh `main`, user approval for L-class items, host-supported model routes per track |
| **Outputs** | Findings report, integrated S/M fixes, L-item proposals, PR triage with merge order, quality-gate verification, PRs opened |
| **Token constraint** | All `route-subagents` calls for this skill must use **free models only** (no paid provider routes) |
| **Side effects** | Worktrees created, branches created, files edited, quality gates run, PR opened, GitHub issues for L items |
| **Stop condition** | All tracks report, S/M fixes applied and verified, gates green, PR opened. L items deferred as proposals/issues. |

## Worktree topology

Four isolated worktrees. Each gets its own `build/`, lock, and disposable
runtime state. The parent owns integration, app-boot verification, and final
gates.

| Worktree | Track | Skills run | Owner role |
| :--- | :--- | :--- | :--- |
| `wt-code` | Code quality & style | `code-review`, `autonomous-code-optimizer` (Pass 1+3 survey), `kotlin-refactoring-and-cleanup`, `reduce-code-size`, `complex-code-comments`, `todo-resolution` | reviewer-a |
| `wt-docs` | Documentation | `documentation-review`, `changelog-and-docs-sync`, `user-guide` | reviewer-b |
| `wt-skills` | Skills, rules, agent guidance | `rules-and-skills-audit`, `skill-reviewer`, `ai-slop-detector` (skills/rules/docs scope) | reviewer-a |
| `wt-tests` | Tests, QA, security, deps | `continuous-quality`, `write-kotest`, `dependency-upgrade`, `ai-slop-detector` (test + build/security scope) | reviewer-b |
| `wt-arch` | Architecture & product | `architecture-review`, `product-opportunity-review`, `ui-visual-review` | reviewer-a |

Each worktree agent is **autonomous within its worktree**. Agents may edit
files, run skill workflows, commit to uniquely named branches, push/PR those
branches, and write to the shared coordination layer. The parent owns
integration, cross-track coordination, and merge-order enforcement; workers
own their own branch and PR lifecycle.

### Coordination layer

All tracks share a lightweight coordination surface under the parent worktree:

```text.worktrees/.coordination/
agent-status/
  wt-code.json
  wt-docs.json
  wt-skills.json
  wt-tests.json
  wt-arch.json
findings/
  wt-code/
  wt-docs/
  wt-skills/
  wt-tests/
  wt-arch/
topics/
questions/
```

Each worker writes a heartbeat JSON to `agent-status/<worktree>.json` at least
every 60 seconds:

```json
{
  "track": "wt-code",
  "status": "running|blocked|done|error",
  "current_skill": "code-review",
  "progress": "auditing PortfolioAnalyzerImpl",
  "findings_count": 3,
  "blockers": [],
  "warnings": ["Found circular dep between X and Y"],
  "questions": ["Should FQN cleanup include test files?"]
}
```

Workers also append incremental findings to `findings/<worktree>/<finding-id>.json`
as soon as they have evidence, instead of waiting until the end. This lets the
parent and other tracks see partial results in near-real time.

The parent polls this directory and can:

- Read any track's status, warnings, blockers, or questions
- Publish guidance to `topics/<track>.txt` for a specific worker
- Start a cross-track topic in `topics/` when multiple workers should weigh in

#### Orchestrator heartbeat to user

While tracks are running, the parent **must** emit a status update to the user
at least every 30 seconds. Use a compact one-line-per-track format:

```text
[overhaul] wt-code: code-review @ PortfolioAnalyzerImpl — 3 findings, 1 warning
[overhaul] wt-docs: documentation-review @ README — 1 finding
[overhaul] wt-skills: rules-and-skills-audit — 0 findings, blocked on X
[overhaul] wt-tests: continuous-quality — 2 findings
[overhaul] wt-arch: architecture-review — running
[overhaul] cross-track: wt-code → wt-skills "symbol X removed?" (open)
```

Rules:

- Never go silent for more than 30s during active discovery.
- If a track is blocked or errored, surface it immediately, not on the next
  scheduled tick.
- When a cross-track topic is opened or answered, include it in the next
  heartbeat: `cross-track: <from> → <to> "<topic>" (<status>)`.
- Keep each line under 120 chars. Do not paste raw findings or file contents
  into the heartbeat; reference them by count and path.

#### Cross-track discovery

If a worker notices another track is working on a related area, it may open a
topic for discussion. Examples:

- `wt-code` finds a symbol used in docs → opens `topics/symbol-usage.md` for
  `wt-docs` to confirm
- `wt-skills` discovers a skill teaches a removed API → opens `topics/skill-drift.md`
  for `wt-code` to verify in source

Topics are **optional** and **advisory only**. The parent owns integration and
decides whether to act on cross-track discussion. Workers must not block on
another track’s response.

#### What to share / what not to share

| Share | Do NOT share |
| :--- | :--- |
| Finding title, severity, path, evidence anchor | Full file contents or raw diffs |
| Questions and warnings | Secrets, credentials, API keys |
| Current skill name and progress | Resolved credentials or live account data |
| Topic proposals for cross-track discussion | Full repository context or unrelated skill text |

Keep every coordination artifact under a few hundred bytes. If a worker needs to
share more context, it should write a compact summary, not a full report.

#### File locking for shared coordination files

Shared coordination files (`topics/*`, `questions/*`, and any future shared
state) may be written by multiple agents. Use lockfiles to avoid torn writes:

- Lock file name: `<target-file>.lockfile`
- Lock content: agent identifier, timestamp, pid — e.g.
  `track=wt-code ts=2026-08-08T00:30:00Z pid=12345`
- Acquisition:
  1. Check if `<target-file>.lockfile` exists.
  2. If it exists, wait 1–2s and retry.
  3. If it still exists after ~10s, treat the holder as stalled.
- Release: delete the lockfile immediately after the write completes.
- The orchestrator **must** monitor lockfiles during discovery. If a lockfile
  is held longer than 60s, the orchestrator removes it and emits a warning
  identifying the stale holder:
  `[overhaul] WARNING: removed stale lockfile from wt-code (held 90s)`

Per-track files (`agent-status/wt-*.json`, `findings/wt-<track>/*`) do not need
locking because each track owns its own directory.

### Recommend-only tracks (no parallel worktree)

`architecture-review` and `product-opportunity-review` are **recommend-only**
skills. Run them **serially by the parent** after read-only tracks complete,
or include them in a follow-up session. Their findings are always classified
as **L** and require explicit user approval before any implementation.

### App-boot skills (serial parent only)

`ui-visual-review`, `ui-visual-implement`, `ui-manual-qa`, `post-deploy-ui-smoke`,
and `docs-screenshot-refresh` require booting the application. Run these
**serially by the parent** after all read-only tracks and S/M fixes are
integrated. Never run them inside a parallel worktree.

## Workflow

```text
- [ ] Step 0: Branch, model routes, and worktree setup
- [ ] Step 1: Fan-out 5 read-only tracks
- [ ] Step 2: Collect and triage findings (S/M/L classification)
- [ ] Step 3: PR triage — candidate PRs with merge recommendation
- [ ] Step 4: Commit, push, open PRs (iterative per triage)
- [ ] Step 5: Overhaul report
```

### Step 0 — Branch, model routes, and worktree setup

1. Start from an up-to-date `main`.
2. Create a dedicated branch: `improve/overhaul-YYYYMMDD`.
3. Select a host-supported route for each track. Record primary route, effort
   when exposed, fallback, and availability evidence. See
   [parallel-multi-agent](../parallel-multi-agent/SKILL.md) § Native model-selection gate.
   **This skill routes only through free models.** When invoking
   `.kilo/model-router/route-subagents`, pass `--free-only` or set the router
   config to exclude paid providers so all parallel tracks use free-tier routes.
4. Create worktrees. Each worktree gets its own isolated directory:

```bash
git worktree add .worktrees/wt-code -b improve/overhaul-YYYYMMDD-wt-code main
git worktree add .worktrees/wt-docs -b improve/overhaul-YYYYMMDD-wt-docs main
git worktree add .worktrees/wt-skills -b improve/overhaul-YYYYMMDD-wt-skills main
git worktree add .worktrees/wt-tests -b improve/overhaul-YYYYMMDD-wt-tests main
git worktree add .worktrees/wt-arch -b improve/overhaul-YYYYMMDD-wt-arch main
```

1. Copy `.kilo/` and `.agents/` into each worktree so skills resolve. Do not
   copy `.env`, `rebalancer-config.json`, `*.db`, or `.gradle`.
2. Create the shared coordination directory in the **parent** worktree:

```bash
mkdir -p .worktrees/.coordination/{agent-status,findings,topics,questions}
```

### Step 1 — Fan-out 5 read-only tracks

Launch all five tracks concurrently. Each track runs its assigned skills in
discovery mode and returns at most 12 report lines and 5 findings per skill.
Workers do not edit files, run Gradle, start servers, inspect secrets, or load
unrelated skills.

Workers write a heartbeat JSON to `.worktrees/.coordination/agent-status/<track>.json`
at least every 60 seconds, and append incremental findings to
`.worktrees/.coordination/findings/<track>/` as soon as they have evidence.
The parent polls this directory for live status, warnings, blockers, and
cross-track questions.

**Track A — Code quality** (`wt-code`)

Run these skills in sequence within the worktree; collect findings:

1. `code-review` — full codebase audit for SRP, BigDecimal safety, dryRun/simulation, Kraken rate limits, `:common` purity, coverage gates
2. `autonomous-code-optimizer` — **Pass 1 (static quality & security) and Pass 3 (architecture & design) survey only.** Do not run the full 4-pass convergence loop; report findings and stop.
3. `kotlin-refactoring-and-cleanup` — FQN elimination, magic string moves, warning debt
4. `reduce-code-size` — behavior-preserving deletion, reuse, file decomposition
5. `complex-code-comments` — missing / wrong / stale / noisy comment audit
6. `todo-resolution` — actionable TODO comment audit

Report format: `[P0-P3] title` — `path:Lx-Ly` with category, evidence, impact, and smallest safe correction.

**Track B — Documentation** (`wt-docs`)

1. `documentation-review` — full audit of README, CHANGELOG, SECURITY, CONTRIBUTING, docs/*, .agents/, .cursor/rules/, copilot-instructions.md against current source/build truth
2. `changelog-and-docs-sync` — verify README package tree, JaCoCo exclusions, stack versions, AGENTS.md pins
3. `user-guide` — end-user walkthrough accuracy and embedded screenshot freshness

Report format: `[WRONG|STALE|MISSING|ORPHAN] title` — `path` (section) with evidence and fix.

**Track C — Skills, rules, agent guidance** (`wt-skills`)

1. `rules-and-skills-audit` — redundancy, conflicts, unclear triggering, stale assumptions, consolidation opportunities across `.agents/`, `.cursor/rules/`, `.clinerules/`, `CLAUDE.md`, `.github/copilot-instructions.md`
2. `skill-reviewer` — content depth, coding standards, architecture guidance, domain patterns, anti-patterns, checklists for each SKILL.md
3. `ai-slop-detector` — scope: agent skills, agent rules, documentation. Look for hallucinated tools/flags, contradictions with code, invalid frontmatter, broken links, rule drift, AI fluff prose

Report format: finding type, affected paths, evidence, recommended action.

**Track D — Tests, QA, security, dependencies** (`wt-tests`)

1. `continuous-quality` — full tests and coverage gates review, uncovered edge cases, defect classes not covered
2. `write-kotest` — test necessity audit (impossible-case tests, cosmetic duplicates, coverage padding, mirror tests)
3. `dependency-upgrade` — check every dependency, plugin, tool, Gradle/Kotlin toolchain for newer stable releases; flag security alerts
4. `ai-slop-detector` — scope: tests, build scripts, configuration templates, CI. Look for tests that do not protect required behavior, weakened assertions, invented config keys, schema drift, unsafe runtime behavior, secret exposure risk

Report format: defect class or dependency, current vs latest, breaking changes, security alert, risk class.

**Track E — Architecture & product** (`wt-arch`)

1. `architecture-review` — fresh-eyes critique; discover meaningful alternative architectures, stacks, module boundaries. **Recommend only; do not implement.**
2. `product-opportunity-review` — feature ideation, underserved user needs, workflow gaps, differentiation opportunities. **Recommend only; do not implement.**
3. `ui-visual-review` — live Dashboard / Settings / History visual critique in simulation mode. **Recommend only; do not implement.**

All findings from these three skills are classified as **L** and require
explicit user approval before any implementation. They run in parallel with
tracks A–D as exploratory discovery, not as implementation tracks.

### Step 2 — Collect and triage findings

After all tracks complete, the parent reads each compact report and builds a
unified findings table. Classify every item:

| Size | Criteria | Action |
| :--- | :--- | :--- |
| **S** — Small | Spotless/ktlint, dead imports, `:common` string moves, doc typos, single-test fix, broken link | Auto-apply |
| **M** — Medium | Localized refactor (one module), polish within existing design system, checklist/skill sync, non-breaking API tidy | Auto-apply if gates stay green |
| **L** — Large / high-impact | Multi-package changes, trading-math / order-path changes, live-trading safety, dependency major bumps, new product surfaces, "restyle whole dashboard", architecture redesign, product feature additions | **Stop and ask** — present proposal, wait for approval |

**Impact override:** anything that can change live order behavior, `dryRun` /
`simulation` semantics, or credentials handling is **L** even if the diff looks
small. Architecture redesign and product feature recommendations are always
**L** (recommend-only unless user approves implementation).

### Step 4 — PR triage — candidate PRs with merge recommendation

After all tracks report, group every finding into a **candidate PR** and produce
a judgment plan for the user. The goal is a broad set of reviewable PRs, not a
single merged outcome. Do not implement fixes yet; the user judges which PRs
to merge before any code changes are applied.

#### Candidate PR rules

1. **One finding or one cohesive theme per PR** — prefer many small PRs over
   large mixed-scope PRs. A PR may bundle tightly coupled S/M fixes that share
   a single concern (e.g. “rename `dustThresholdUSD` → `minimumOrderSizeUSD`”
   across 10 files); do not bundle unrelated concerns.
2. **L items become standalone proposal PRs** — each architecture, product, or
   high-impact finding that needs approval is its own PR proposal with a clear
   problem statement and risk assessment. Do not implement; leave it open for
   the user to judge.
3. **Hotfix-style changes are flagged** — bug fixes, security patches, and
   fail-closed corrections are labeled `priority: high` and recommended for
   early merge regardless of size.
4. **Parallel-ready PRs are identified** — PRs touching disjoint files and
   having no dependency can be reviewed/merged concurrently.

#### Readiness rubric

| Status | Meaning |
| :--- | :--- |
| **Ready to merge** | Gates green, diff is self-contained, no dependency on another PR, adversarial review passed or not required |
| **Needs adversarial review** | Gates green but touches trading math, Kraken I/O, CORS, live-order journal, or credentials — must pass adversarial PR review before merge |
| **Needs your approval** | L-class item; PR is a proposal, not an implementation. User decides merge or drop. |
| **Blocked** | Depends on another PR not yet merged, or depends on an L item that is not yet approved |
| **Drop candidate** | Low-value, duplicate, or superseded by another PR. Recommend closing without merge. |

#### Output: PR Triage Report

Produce one table per domain (code, docs, skills/rules, tests/security,
dependencies, architecture/product). For each candidate PR:

```markdown
| # | Title | Domain | Files | Size | Status | Recommendation | Depends on |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| 1 | fix: ... | code | 3 | S | Ready to merge | Merge first — fail-closed settle fix | — |
| 2 | docs: ... | docs | 5 | S | Ready to merge | Merge in any order | — |
| 3 | refactor: ... | code | 12 | M | Needs adversarial review | Merge after PR 1 | PR 1 |
| 4 | feat: ... | arch | proposal | L | Needs your approval | Awaiting your call | — |
| 5 | chore: ... | deps | 1 | S | Drop candidate | Low value, superseded by #3 | #3 |
```

Then a **judgment summary**:

```markdown
## Judgment summary

- Ready to merge: N PRs
  - Recommended merge order: PR 1 → PR 3 → PR 2
  - Parallel-safe group: PR 2 + PR 4 (no overlap)
- Needs adversarial review: N PRs
- Needs your approval (L proposals): N items
- Blocked: N PRs
- Drop candidates: N PRs
- Overall recommendation: merge the N ready PRs first; review L proposals separately
```

Present the full triage report to the user. Do not open any PRs until the user
approves the plan or explicitly asks you to proceed.

### Step 9 — Commit, push, open PRs (iterative per triage)

After the user approves the triage plan, iterate through the PRs in the
recommended order. For each PR:

1. Ensure the branch is up to date with `main` (rebase if needed).
2. Commit changes for this PR only with a conventional commit message.
3. Push the branch.
4. Open the PR using [open-pr](../open-pr/SKILL.md) — mandatory
   [adversarial-pr-review](../adversarial-pr-review/SKILL.md) for any PR
   touching trading math, Kraken I/O, CORS, live-order journal, or
   credentials.
5. For parallel-ready PRs (no dependencies, disjoint files), open them
   concurrently.

The parent owns merge-order enforcement and integration. Stop after each
opened PR and wait for the user to review/merge before proceeding to dependent
PRs if they prefer a controlled rollout.

### Step 5 — Overhaul report

Deliver a concise summary:

```markdown
# Comprehensive Quality Overhaul — report
- Branch: improve/overhaul-YYYYMMDD
- Tracks run: 5 parallel (code, docs, skills, tests+security, architecture/product)
- Worktrees: .worktrees/wt-*
- Candidate PRs generated: N
- Ready to merge: N PRs
- Needs adversarial review: N PRs
- Needs your approval (L proposals): N items
- Drop candidates: N PRs
- Recommended merge sequence: PR 1 → PR 3 → PR 2 (parallel: PR 4 + PR 5)
- Next: your review — approve PRs to implement/merge, defer or drop L proposals
```

## Size class reference

| Size | Examples | Action |
| :--- | :--- | :--- |
| **S** | Spotless/ktlint, dead imports, `:common` string moves, doc typos, single-test fix, broken link | Auto-apply |
| **M** | Localized refactor (one module), polish within existing design system, checklist/skill sync, non-breaking API tidy | Auto-apply if gates stay green |
| **L** | Multi-package redesign, trading-math / order-path changes, live-trading safety UX changes, dependency major bumps, new product surfaces, "restyle whole dashboard", architecture redesign, product feature additions | **Stop and ask** |

**Impact override:** anything that can change live order behavior, `dryRun` /
`simulation` semantics, or credentials handling is **L** even if the diff looks
small. Architecture redesign and product feature recommendations are always
**L** — recommend-only unless the user explicitly approves implementation.

## Anti-patterns

- Running concurrent Gradle builds in one clone (causes `EOFException` and
  fake `UP-TO-DATE` results)
- Sending every worker the full repository context
- Claiming convergence when JaCoCo/Karma exclusions were widened instead of
  new tests added
- Editing outside assigned worktree boundaries
- Copying `.env`, `rebalancer-config.json`, databases, or logs between worktrees
- Using shared `git stash` or autostash across worktrees
- Opening a PR with unchecked verification items
- Silent live-trading or production-config use during any track
- Running full `autonomous-code-optimizer` 4-pass convergence during overhaul
  (too long); survey only (Pass 1 + Pass 3) unless user explicitly asks for exhaustive
- Booting the application inside a parallel worktree (port conflicts, orphans)
- Merging candidate PRs without user review — this skill produces recommendations,
  not automatic merges
- Bundling unrelated concerns into one PR just to reduce PR count
- Adding unsolicited ARIA attributes during UI work
- Using paid provider routes for this skill’s subagents (must stay on free routes)

## Checklist

- [ ] Fresh branch created from up-to-date main
- [ ] Model routes selected and recorded per track
- [ ] Five worktrees created with isolated state
- [ ] All 5 read-only tracks completed discovery (compact reports returned)
- [ ] Findings triaged S/M/L with evidence anchors
- [ ] PR triage report delivered with candidate PRs, readiness status, and merge recommendation
- [ ] User reviewed triage and approved which PRs to proceed with
- [ ] Approved PRs implemented with serial quality gates
- [ ] `pre_commit_check.sh` green
- [ ] JaCoCo / Karma / Spotless / markdownlint clean
- [ ] GitHub issues created for deferred L items
- [ ] PRs opened with adversarial PR review where required
- [ ] Overhaul report delivered

Base directory for this skill: file:///Users/charlesv/Projects/new-kraken-rebalancer/.agents/skills/comprehensive-quality-overhaul
Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.
