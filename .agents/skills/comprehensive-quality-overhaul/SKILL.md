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
multiple worktrees, then integrates findings into a set of candidate PRs for
the user to judge, not a single merged outcome.

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
  implementer coordination.

## Contracts

| Contract item | Value |
| :--- | :--- |
| **Trigger** | "improve everything", "total quality overhaul", "run all skills", "comprehensive quality sweep", "kitchen sink quality pass" |
| **Non-goals** | Live-trading changes without approval, credential changes without approval, booting app in parallel worktrees |
| **Inputs** | Fresh `main`, user approval for L-class items, host-supported model routes per track |
| **Outputs** | Findings report, integrated S/M fixes, L-item proposals, PR triage with merge order, quality-gate verification, PRs opened |
| **Token constraint** | All model routing for this skill must use **free models only** (no paid provider routes), except when an agent is performing adversarial PR review on a high-risk PR (trading math, Kraken I/O, CORS, live-order journal, credentials) — in that case use the strongest available free route, or fall back to a paid route only if no free route meets the acceptance requirement. In Kilo CLI sessions, fan-out **must** use the `.kilo/model-router/route-subagents` launcher with a **custom `--manifest` + free-only `--config` override** — it is the only mechanism that selects and records a per-track exact route. The launcher has **no `--free-only` flag**; free-only is enforced through the override (see Step 0 §4). Direct `Task` subagents with an explicit free-route instruction in every prompt are a fallback only when the launcher cannot run (non-Kilo host, no network, launcher failure) — not a parallel option. |
| **Side effects** | Worktrees created, branches created, files edited, quality gates run, PR opened, GitHub issues for L items |
| **Stop condition** | All tracks report, S/M fixes applied and verified, gates green, PR opened. L items deferred as proposals/issues. |

## Worktree topology

Five isolated worktrees. Each gets its own `build/`, lock, and disposable
runtime state. The parent owns integration, app-boot verification, and final
gates.

| Worktree | Track | Skills run | Owner role |
| :--- | :--- | :--- | :--- |
| `wt-code` | Code quality & style | `code-review`, `autonomous-code-optimizer` (Pass 1+3 survey), `kotlin-refactoring-and-cleanup`, `reduce-code-size`, `complex-code-comments`, `todo-resolution` | reviewer-a |
| `wt-docs` | Documentation | `documentation-review`, `changelog-and-docs-sync`, `user-guide` | reviewer-b |
| `wt-skills` | Skills, rules, agent guidance | `rules-and-skills-audit`, `skill-reviewer`, `ai-slop-detector` (skills/rules/docs scope) | reviewer-a |
| `wt-tests` | Tests, QA, security, deps | `continuous-quality`, `write-kotest`, `dependency-upgrade`, `ai-slop-detector` (test + build/security scope) | reviewer-b |
| `wt-arch` | Architecture & product | `architecture-review`, `product-opportunity-review` | reviewer-a |

Each worktree agent is an **implementer for all findings (S/M/L)** in its assigned domain. Workers:

- **Discover and implement** all findings (S/M/L) directly in their worktree
- Report findings and evidence to the coordination layer
- Do **not** run Gradle, start servers, create GitHub issues, or open PRs themselves
- Their only file writes outside the worktree source are coordination artifacts under `.worktrees/.coordination/` (heartbeats, findings, topics, questions, requests)
- Worker prompts must explicitly grant read/write filesystem access to the worktree and the parent `.worktrees/.coordination/` directory; the parent owns L-item implementation, branch management, commit, push, and PR creation after user approval

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
requests/
results/
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

Shared coordination files (`topics/*`, `questions/*`, `requests/*`, and any
other shared state) may be written by multiple agents. Use lockfiles to avoid
torn writes:

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

#### Request channel (worker → parent)

Workers never boot the application themselves. When a skill needs an app boot
(screenshot capture, viewport verification, UI interaction), the worker
writes a small request to `requests/<track>-<n>.json`:

```json
{
  "track": "wt-docs",
  "request": "capture screenshot",
  "details": "Settings page at ~1280 for docs/images/settings.png",
  "ret_id": "wt-docs-1"
}
```

The parent polls `requests/`, performs each task **serially** (never inside a
parallel worktree, never concurrently), and writes the outcome to
`results/<track>-<n>.json` with a status (`done`/`failed`), a short summary,
and an artifact path. Workers must not block on the result; they reference
the request id in their findings and the parent folds the outcome into the
Step 2 triage.

### Architecture & product tracks (implemented in overhaul mode)

`architecture-review` and `product-opportunity-review` are **recommend-only**
skills in their traditional form. However, for this overhaul skill, workers
should still **implement their recommendations as code changes** in the worktree
so they become candidate PRs for user review. The difference is:

- Traditional: recommendations only (no code changes)
- Overhaul mode: implement the architectural changes as code, produce PRs
- The user still decides whether to merge the resulting PRs

This applies to all tracks including `wt-arch` — all findings (S/M/L) become
candidate PRs. The user decides which to merge.

### App-boot skills (serial parent only)

`ui-visual-review`, `ui-visual-implement`, `ui-manual-qa`, `post-deploy-ui-smoke`,
and `docs-screenshot-refresh` require booting the application. Run these
**serially by the parent** after all read-only tracks and S/M fixes are
integrated. Never run them inside a parallel worktree.

## Workflow

```text
- [ ] Step 0: Clean leftover state, branch, model routes, and worktree setup
- [ ] Step 1: Fan-out 5 read-only tracks
- [ ] Step 2: Collect and triage findings (S/M/L classification)
- [ ] Step 3: PR triage — candidate PRs with merge recommendation
- [ ] Step 4: Commit, push, open PRs (iterative per triage)
- [ ] Step 5: Overhaul report
- [ ] Step 6: Teardown worktrees
```

### Step 0 — Clean leftover state, branch, model routes, and worktree setup

1. **Clean up leftover worktrees and coordination state from any previous
   run before doing anything else.** This skill creates `.worktrees/wt-*`
   plus `.worktrees/.coordination/`; if an earlier run was interrupted or
   skipped teardown, remove its remains so runs do not accumulate:

```bash
shopt -s nullglob
for wt in .worktrees/wt-*; do git worktree remove --force "$wt"; done
shopt -u nullglob
git worktree prune
rm -rf .worktrees/.coordination
rmdir .worktrees 2>/dev/null || true
```

   Only `.worktrees/wt-*` worktrees and the skill-owned
   `.worktrees/.coordination/` are touched — other repository worktrees and
   any other `.worktrees/` content are never removed. If a leftover worktree
   holds uncommitted work you need, commit it to its branch first: removal
   discards uncommitted changes. Branches survive worktree removal; clean up
   `improve/overhaul-*` branches only when their PRs are merged or abandoned.
2. **Start from `main`.** Run `git checkout main && git pull origin main` before doing anything else. Never start this skill from any other branch.
3. Create a dedicated branch: `improve/overhaul-YYYYMMDD`. On a same-day
   rerun (branch name already exists), append a numeric suffix
   (`improve/overhaul-YYYYMMDD-2`) instead of reusing the branch.
4. **Route selection — free models only.** Record primary route, effort,
    fallback, and availability evidence for each track. See
    [parallel-multi-agent](../parallel-multi-agent/SKILL.md) § Native model-selection gate.
    **This skill routes only through free models** (no paid provider routes),
    except the single adversarial-review carve-out in the Contracts table.
    In Kilo CLI sessions, launch the fan-out through
    `.kilo/model-router/route-subagents` with a **custom manifest** and a
    **free-only config override** — this is the mandatory path; it selects
    and records a per-track exact route. Fall back to direct `Task` subagents
    with an explicit free-route instruction in every prompt **only** when the
    launcher cannot run (non-Kilo host, no network, launcher failure) — never
    as the default; if the host exposes only pinned roles with no route
    choice, record that limitation and the actual model instead of claiming
    a route selection.
      - **Launcher free-only mechanics (verified against the installed
        launcher):** the installed `route-subagents` has **no `--free-only`
        flag** and no `--workflow` preset for this skill. Free-only is
        expressed via a custom `--manifest` plus a `--config` override.
      - **`--config` REPLACES the tracked `.kilo/model-router/config`** — it is
        deep-merged only against the launcher's built-in defaults, never against
        the tracked file. The override **must reproduce the full `blacklist`
        section verbatim** from `.kilo/model-router/config`; an override that
        omits it silently drops the user-maintained blacklist and blacklisted
        models become eligible again (observed live: the first run selected
        `nvidia/z-ai/glm-5.2` and `nvidia/minimaxai/minimax-m3`, both on
        `blacklist.models`).
      - `policy.allowPaid=false` alone is insufficient: subscription/account-
        priced providers (`kilo`, `opencode-go`, `openai`) are not gated by it.
        They must be disabled explicitly with `providers.<name>.enabled: false`
        in the override. Keep free providers (`nvidia` and openrouter `:free`
        variants) enabled with `policy.allowFree: true`.
      - Override shape used successfully:
        `{"providers":{"kilo":{"enabled":false},"opencode-go":{"enabled":false},"openai":{"enabled":false}},"policy":{"allowPaid":false,"allowFree":true,"denyFreeForSensitive":false},"blacklist":{...copied verbatim from tracked config...}}`
      - **Verify the route plan printed before workers launch**: every route
        must be a free route and none may appear on `blacklist.models`. Abort
        and fix the override if a blacklisted or paid route is selected.
      - Keep the manifest and override in the gitignored
        `.worktrees/.coordination/` directory so they never get committed.
5. Create worktrees. Each worktree gets its own isolated directory:

```bash
git worktree add .worktrees/wt-code -b improve/overhaul-YYYYMMDD-wt-code main
git worktree add .worktrees/wt-docs -b improve/overhaul-YYYYMMDD-wt-docs main
git worktree add .worktrees/wt-skills -b improve/overhaul-YYYYMMDD-wt-skills main
git worktree add .worktrees/wt-tests -b improve/overhaul-YYYYMMDD-wt-tests main
git worktree add .worktrees/wt-arch -b improve/overhaul-YYYYMMDD-wt-arch main
```

1. Copy `.kilo/` and `.agents/` into each worktree so skills resolve. Both
   directories are already git-tracked, so this copy is a harmless safety net
   for hosts that resolve skills from the worktree root. Do not copy `.env`,
   `rebalancer-config.json`, `*.db`, or `.gradle`.
2. Create the shared coordination directory in the **parent** worktree
   (including the request channel):

```bash
mkdir -p .worktrees/.coordination/{agent-status,findings,topics,questions,requests,results}
```

   Every worker runs inside `.worktrees/wt-*`, so **always give workers the
   parent worktree's absolute path** to `.worktrees/.coordination/`; a
   relative path would resolve to a nested, nonexistent directory inside the
   worker worktree.

### Step 1 — Fan-out 5 read-only tracks

Launch all five tracks concurrently via a single
`.kilo/model-router/route-subagents` invocation with a **custom manifest**
and the **free-only `--config` override** from Step 0 —
mandatory in Kilo CLI sessions because it selects and records a per-track
exact route. Fall back to a one-message parallel `Task` fan-out with an
explicit free-route instruction in every prompt only when the launcher
cannot run (non-Kilo host, no network, launcher failure). Do not start workers one at a time sequentially; the whole
fan-out launches at once. Each track runs its assigned skills in discovery
mode and returns at most 12 report lines and 5 findings per skill. Workers do
not edit repository files, run Gradle, start servers, inspect secrets,
create GitHub issues, commit, push, or open PRs.

Workers write a heartbeat JSON to the **parent-absolute**
`<parent>/.worktrees/.coordination/agent-status/<track>.json` at least every
60 seconds, append incremental findings to
`<parent>/.worktrees/.coordination/findings/<track>/` as soon as they have
evidence, and check `<parent>/.worktrees/.coordination/topics/<track>.txt`
at each heartbeat for parent guidance. The parent polls this directory for live status, warnings,
blockers, and cross-track questions.

**Retry policy:** if a track produces no heartbeat for ~3 minutes, mark it
stalled and retry it once from scratch. If the retry also fails, surface the
failure in the next heartbeat, proceed with the remaining tracks, and mark
the run as a partial run in the final report.

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

All findings from these two skills are classified as **L** and require
explicit user approval before any implementation. They run in parallel with
tracks A–D as exploratory discovery, not as implementation tracks.

`ui-visual-review` is excluded from parallel worktrees because it requires
booting the application. Run it serially by the parent after discovery if
desired.

#### Worker contract

Every worker prompt must state this contract:

- **Effect of "implementer"**: workers audit, form findings, **implement fixes** (S/M/L), and report. They do not run Gradle, start servers, open GitHub issues, commit, push, or open PRs. The parent owns every mutation step after the worker implements the fix in the worktree.
- **Write scope**: workers write source code changes in their worktree AND coordination artifacts (heartbeats, findings, topics/questions responses, `requests/` entries) under the parent's absolute `.worktrees/.coordination/` path. Grant each worker read/write filesystem access to that directory; everything else stays read-only.
- **Models**: workers may fan out only on **free routes** (free-only `--config` override + custom manifest; see Step 0 — there is no `--free-only` flag). The paid adversarial-review carve-out applies only to the parent's own review of a high-risk PR, never to worker fan-out.
- **Topics**: check `<parent>/.worktrees/.coordination/topics/<track>.txt` at every heartbeat and answer or acknowledge what the parent asks at the next heartbeat.
- **Overlap**: if another track's work overlaps a finding, record it with its own path and evidence; do not attempt to resolve or deduplicate in parallel — Step 2 consolidates.
- **Requests**: never boot the application; if skill instructions require an app boot or screenshot, write a `requests/<track>-<n>.json` and keep going.

### Step 2 — Collect and triage findings

After all tracks complete, the parent reads each compact report and builds a
unified findings table. Classify every item:

| Size | Criteria | Action |
| :--- | :--- | :--- |
| **S** — Small | Spotless/ktlint, dead imports, `:common` string moves, doc typos, single-test fix, broken link | Apply after triage approval (Step 3/4) |
| **M** — Medium | Localized refactor (one module), polish within existing design system, checklist/skill sync, non-breaking API tidy | Apply after triage approval (Step 3/4) if gates stay green |
| **L** — Large / high-impact | Multi-package changes, trading-math / order-path changes, live-trading safety, dependency major bumps, new product surfaces, "restyle whole dashboard", architecture redesign, product feature additions | **Stop and ask** — present proposal, wait for approval |

**Impact override:** anything that can change live order behavior, `dryRun` /
`simulation` semantics, or credentials handling is **L** even if the diff looks
small. Architecture redesign and product feature recommendations are always
**L** (implemented in overhaul mode; user decides merge/abandon).

### Step 3 — PR triage — candidate PRs with merge recommendation

After all tracks report, group every finding into a **candidate PR** and produce
a judgment plan for the user. The goal is a broad set of reviewable PRs, not a
single merged outcome. Workers have already implemented the fixes in their
worktrees; the user judges which PRs to merge before any code changes are
applied to `main`.

#### Candidate PR rules

1. **One finding or one cohesive theme per PR** — prefer many small PRs over
   large mixed-scope PRs. A PR may bundle tightly coupled S/M fixes that share
   a single concern (e.g. “rename `dustThresholdUSD` → `minimumOrderSizeUSD`”
   across 10 files); do not bundle unrelated concerns.
2. **L items become standalone proposal PRs** — each architecture, product, or
   high-impact finding that needs approval is its own PR proposal with a clear
   problem statement and risk assessment. Workers implement the fix in the worktree;
   the PR is left open for the user to judge.
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

### Step 4 — Commit, push, open PRs (iterative per triage)

For each approved PR, create a dedicated branch from the newest `main` named
`improve/overhaul-YYYYMMDD-<slug>` (or rebase the existing overhaul branch
onto the newest `main` first). Do not reuse the discovery worktree branches
(`improve/overhaul-YYYYMMDD-wt-*`) for PRs — they only carry the read-only
scout state. Then for each PR:

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

### Step 6 — Teardown worktrees

Once the user has reviewed all candidate PRs and decided which to merge/abandon, remove the worktrees and coordination state so the next run starts clean. The worktrees should remain available until the user has reviewed all PRs and made merge/abandon decisions, because the worktrees contain the implemented changes for each PR.

```bash
for wt in .worktrees/wt-*; do git worktree remove --force "$wt"; done
git worktree prune
rm -rf .worktrees
```

Do not delete the `improve/overhaul-*` branches here — the PRs still reference
them; clean up branches later once the PRs are merged or abandoned. If the run
stops early (user interruption or a failed track), run this teardown anyway
before ending the session, and note in the report that cleanup was performed.

## Size class reference

| Size | Examples | Action |
| :--- | :--- | :--- |
| **S** — Small | Spotless/ktlint, dead imports, `:common` string moves, doc typos, single-test fix, broken link | Apply after triage approval (Step 3/4) |
| **M** — Medium | Localized refactor (one module), polish within existing design system, checklist/skill sync, non-breaking API tidy | Apply after triage approval (Step 3/4) if gates stay green |
| **L** | Multi-package redesign, trading-math / order-path changes, live-trading safety UX changes, dependency major bumps, new product surfaces, "restyle whole dashboard", architecture redesign, product feature additions | **Stop and ask** |

**Impact override:** anything that can change live order behavior, `dryRun` /
`simulation` semantics, or credentials handling is **L** even if the diff looks
small. Architecture redesign and product feature recommendations are always
**L** (implemented in overhaul mode; user decides merge/abandon).

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
- Using paid provider routes for this skill's workers (free-only; paid is
  allowed only for the single adversarial high-risk carve-out in the
  Contracts table)
- Leaving `.worktrees/` or `.coordination/` behind at the end of a run — always
  run Step 6 teardown so the next launch starts clean
- Workers writing or committing outside their coordination artifacts (no
  source edits, no commits, no GitHub issues, no PRs from inside workers)
- Workers resolving overlapping findings against each other in parallel
  (record them; Step 2 dedupes)
- Calling `route-subagents` without the free-only manifest + `--config`
  override — every worker route must
  stay free unless it is the parent's adversarial high-risk carve-out
- Omitting the tracked `blacklist` section from the `--config` override —
  `--config` replaces (not merges with) `.kilo/model-router/config`, so an
  override without the blacklist re-enables blacklisted models
- Falling back to direct `Task` fan-out when `route-subagents` is available —
  in Kilo CLI sessions the launcher with a free-only override is mandatory, not optional

## Checklist

- [ ] Leftover `.worktrees/` from previous runs cleaned up (Step 0 cleanup)
- [ ] `.worktrees/` added to `.gitignore` so parent `git add -A` never stages worktrees
- [ ] Fresh branch created from up-to-date main
- [ ] Model routes selected and recorded per track
- [ ] Fan-out launched via `.kilo/model-router/route-subagents` with custom manifest + free-only `--config` override carrying the full `blacklist` verbatim (direct `Task` only as documented fallback)
- [ ] Five worktrees created with isolated state
- [ ] Worker prompts gave the parent-absolute coordination path and granted .coordination read/write
- [ ] Worker contract enforced: implementer role, free-only routes, no issues/commits/PRs
- [ ] Request channel (worker app/q screenshot requests → parent results) worked
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
- [ ] Worktrees torn down (Step 6), `.worktrees/` removed
