---
name: continuous-improvement
description: >-
  Orchestrate a continuous-improvement cycle: discover improvements (code, UI,
  docs, deps), auto-apply small/medium fixes, pause for user approval on large
  or high-impact changes, run full quality gates, then commit and open a PR.
  Use when the user asks for the whole shebang, continuous improvement, CI
  enhancement loops, auto-improve, or “just run with it.”
---

# Continuous improvement (“the whole shebang”)

Orchestrator skill. It **does not replace** individual skills — it sequences
them. Prefer this when the user wants an end-to-end enhance → test → PR loop
with light supervision; run child skills alone when they want a narrow pass.

Related always-on norms: [OPERATING.md](../../OPERATING.md),
[parallel-multi-agent](../parallel-multi-agent/SKILL.md).

---

## Modes

| Mode | Trigger phrases (examples) | Behavior |
| :--- | :--- | :--- |
| **Cycle** (default) | “whole shebang”, “continuous improvement”, “auto-improve once” | One full loop → PR → stop |
| **Loop** | “keep improving”, “continuous improvement loop”, “run N cycles” | Repeat Cycle until stop condition |
| **Discover-only** | “what would you improve?”, “improvement backlog” | Produce backlog + sizes; **no** edits |

Default to **Cycle** unless the user asks for Loop or Discover-only.

### Loop stop conditions (any)

1. User says stop / pause / enough
2. A full Cycle finds **no** actionable items (or only deferred Large items)
3. User-requested cycle count reached
4. A Large item is waiting on approval and nothing else is shippable

---

## Size classes (mandatory classification)

Before implementing, classify every candidate:

| Size | Examples | Action |
| :--- | :--- | :--- |
| **S** — Small | Spotless/ktlint, dead imports, `:common` string moves, tiny CSS spacing, doc typos, single-test fix | Auto-apply in-cycle |
| **M** — Medium | Localized refactor (one module), polish within existing design system, checklist/skill sync, non-breaking API tidy | Auto-apply if gates stay green; mention in cycle brief |
| **L** — Large / high-impact | Multi-package redesign, trading-math / order-path changes, live-trading safety UX changes, dependency major bumps, new product surfaces, “restyle the whole dashboard” | **Stop and ask** — do not start until the user approves a short proposal |

**Impact override:** anything that can change live order behavior, `dryRun` /
`simulation` semantics, or credentials handling is **L** even if the diff looks
small. See [dry-run-and-simulation](../dry-run-and-simulation/SKILL.md).

When in doubt between M and L → treat as **L** and ask.

---

## One Cycle — workflow

```text
- [ ] Step 0: Branch & safety
- [ ] Step 1: Discover backlog (classify S/M/L)
- [ ] Step 2: Gate Large items (user feedback)
- [ ] Step 3: Implement approved S/M (+ approved L)
- [ ] Step 4: Verify (tests + targeted UI QA)
- [ ] Step 5: Docs / CHANGELOG
- [ ] Step 6: Commit, push, open PR
- [ ] Step 7: Cycle report → stop or Loop
```

### Step 0 — Branch & safety

1. Start from an up-to-date `main` (or user-named base).
2. Create a dedicated branch, e.g. `improve/cycle-YYYYMMDD-HHMM` or
   `improve/<theme>`.
3. Never use the user’s production `rebalancer-config.json` / DB for UI boots —
   isolated simulation only.
4. Do **not** flip live trading flags.

### Step 1 — Discover backlog

Fan out discovery with [parallel-multi-agent](../parallel-multi-agent/SKILL.md)
when tracks are disjoint. Suggested discovery tracks (pick what fits timebox):

| Track | Child skill / focus |
| :--- | :--- |
| Code quality | [kotlin-refactoring-and-cleanup](../kotlin-refactoring-and-cleanup/SKILL.md), light [autonomous-code-optimizer](../autonomous-code-optimizer/SKILL.md) Pass 1–3 **survey** (don’t full-converge unless user asked) |
| UI polish | [ui-visual-review](../ui-visual-review/SKILL.md) — recommend only; size each finding |
| Docs | [documentation-review](../documentation-review/SKILL.md) or [changelog-and-docs-sync](../changelog-and-docs-sync/SKILL.md) gap scan |
| Deps (optional) | [dependency-upgrade](../dependency-upgrade/SKILL.md) — list only unless user wants bumps this cycle |
| Known smells | [OPERATING.md](../../OPERATING.md) UI misses; open TODOs/FIXMEs; failing or flaky tests |

Produce a **Cycle backlog** table:

| ID | Area | Size | Summary | Child skill | Ship this cycle? |
| :--- | :--- | :--- | :--- | :--- | :--- |
| CI-1 | … | S/M/L | … | … | yes / defer / ask |

Timebox discovery (~15–30 min of agent work unless user expands). Prefer a
**shippable** slice over boiling the ocean.

### Step 2 — Gate Large items (user feedback)

If any **L** items exist (or M items that risk trading/safety):

1. Present a short proposal: problem, approach, files touched, risk, rollback.
2. Ask which IDs to **approve / defer / drop**.
3. **Wait** for the user before implementing those IDs.
4. Continue with S/M items in parallel only if file ownership does not overlap
   the pending L work.

If the user said “run with it” / “whole shebang” **and** there are **no** L
items → proceed without pausing.

If the only items are L → do **not** invent busywork; stop at the proposal.

### Step 3 — Implement

Follow the **child skill** for each approved item (do not reinvent). Typical
apply order:

1. Code cleanup / refactors (non-UI)
2. UI implement via [ui-visual-implement](../ui-visual-implement/SKILL.md) for
   approved visual findings only
3. Docs / skills sync as needed
4. Approved dependency upgrades last (highest blast radius)

Use parallel agents when ownership is disjoint; keep `History.kt` and shared
CSS modules single-owner.

### Step 4 — Verify

Always:

```bash
./.agents/skills/commit-and-push/scripts/pre_commit_check.sh
```

Also when UI changed:

- Prefer [ui-manual-qa](../ui-manual-qa/SKILL.md) scoped or full (include
  `STYLE-*`, `REGRESSION-*`, `HIST-ZOOM-*` if History touched)
- Or at least [post-deploy-ui-smoke](../post-deploy-ui-smoke/SKILL.md) against
  local sim after hard-refresh / `?v=`

Trading / algorithm touches → evaluation awareness via
[write-kotest](../write-kotest/SKILL.md) / `docs/EVALUATION.md`.

Fix failures before commit. Do not open a red PR.

### Step 5 — Docs

- `CHANGELOG.md` Unreleased for user-visible or workflow changes
- README / User Guide / screenshots only if visuals or public behavior changed
  ([docs-screenshot-refresh](../docs-screenshot-refresh/SKILL.md),
  [user-guide](../user-guide/SKILL.md))

### Step 6 — Ship

1. [commit-and-push](../commit-and-push/SKILL.md) on the cycle branch
2. [open-pr](../open-pr/SKILL.md) against `main`
3. Return the PR URL

Conventional title examples: `improve: …`, `refactor: …`, `fix: …`, `docs: …`.
PR body: summary of S/M shipped, L deferred, verification results, test plan.

### Step 7 — Cycle report

```markdown
# Continuous improvement — cycle report
- Branch / PR: …
- Shipped: CI-… (S/M)
- Deferred / awaiting approval: CI-… (L)
- Gates: pre_commit_check …
- Next: merge / start Loop cycle N+1 / stop
```

---

## What “run with it” means here

| Auto without asking | Always ask first |
| :--- | :--- |
| S/M cleanups, polish inside current design system | L refactors, redesigns, trading-path changes |
| Skill/checklist/docs sync tied to shipped fixes | Major dependency upgrades |
| Opening an improve/* PR after green gates | Merging the PR; deploying; live config edits |
| Another Loop cycle after a clean ship (if Loop mode) | Continuing after an L proposal with no reply |

---

## Anti-patterns

- Full [autonomous-code-optimizer](../autonomous-code-optimizer/SKILL.md)
  convergence on every Cycle (too long) — survey + targeted fixes unless user
  asked for exhaustive optimize
- Implementing ui-visual-review findings without size class + L gate
- Mixing unrelated mega-themes in one PR (split cycles / PRs)
- Skipping `pre_commit_check.sh` because “it’s just docs”
- Silent live-trading or production-config use
- Parallel edits to the same hot file (`History.kt`, one CSS module)

---

## Checklist

- [ ] Mode chosen (Cycle / Loop / Discover-only)
- [ ] Improve branch from fresh base
- [ ] Backlog classified S/M/L; L gated with user when present
- [ ] Child skills followed for each shipped item
- [ ] `pre_commit_check.sh` green; UI QA if UI changed
- [ ] CHANGELOG/docs updated as needed
- [ ] Commit + PR; cycle report delivered
- [ ] Individual skills still usable alone (this skill only orchestrates)
