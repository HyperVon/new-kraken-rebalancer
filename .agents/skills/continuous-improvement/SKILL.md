---
name: continuous-improvement
description: >-
  Orchestrate a continuous-improvement cycle: discover improvements (code, UI,
  docs, deps), track backlog in .agents/improvement-backlog.md (GitHub issues
  for L/deferred), auto-apply small/medium fixes, pause for user approval on
  large or high-impact changes, run full quality gates, then commit and open a
  PR. Use when the user asks for the whole shebang, continuous improvement, CI
  enhancement loops, auto-improve, or “just run with it.”
---

# Continuous improvement (“the whole shebang”)

## How this differs from nearby skills

| Skill | Role |
| :--- | :--- |
| **continuous-improvement** (this) | Recurring end-to-end enhance → test → PR cycle across code / UI / docs / deps with a persistent backlog |
| [continuous-quality](../continuous-quality/SKILL.md) | QA-only hardening cycle: tests, regressions, evaluation gaps, defect fixes |
| [comprehensive-quality-overhaul](../comprehensive-quality-overhaul/SKILL.md) | One-shot full-repo audit sweep producing triaged candidate PRs for approval |
| [autonomous-code-optimizer](../autonomous-code-optimizer/SKILL.md) | Converging multi-pass refactor loop until zero issues; no backlog or PR orchestration |
| [code-review](../code-review/SKILL.md) | Single review pass over a diff / audit |

Orchestrator skill. It **does not replace** individual skills — it sequences
them. Prefer this when the user wants an end-to-end enhance → test → PR loop
with light supervision; run child skills alone when they want a narrow pass.

Sibling: [continuous-quality](../continuous-quality/SKILL.md) hardens
correctness (tests, regressions, evaluation gaps, defect fixes). Hand QA-only
work there; keep product polish / redesigns on this skill.

Related always-on norms: [OPERATING.md](../../OPERATING.md),
[parallel-multi-agent](../parallel-multi-agent/SKILL.md).

**Persistent backlog:** [improvement-backlog.md](../../improvement-backlog.md)
is the source of truth for open / done / deferred items across cycles.

## Bounded discovery and implementation delegation

When a cycle fans out, the parent chooses an adaptive number of tracks from the
actual backlog and file ownership. Do not send every child agent the whole
repository or run a fixed two-agent review. Use one track per independent area
(for example code, UI, docs, comments, or dependencies), normally 2–6 and at
most 8, with one owner for shared or coupled files.

Each Task prompt names the absolute repo/branch, already-done context, exact
allowed paths, acceptance criteria, iteration cap, and stop condition. Workers
return compact findings or patch summaries (at most 12 lines and 5 findings),
do not edit outside their assigned files, run builds, start servers, inspect
secrets/runtime data, or load unrelated skills. Keep delegated requests well
below the roughly 256K practical context boundary; target below 128K and split
before 180K. If a worker approaches its limit, it returns a partial report and
the parent starts a narrower follow-up. Manual compaction is not a continuation
strategy. The parent owns integration, backlog updates, serial quality gates,
and final verification.

When running under Google Antigravity (AGY), launch discovery subagents natively using built-in `invoke_subagent` tool calls; do NOT execute a Kilo-specific workflow launcher. Discovery workers are read-only; implementation, backlog integration, Gradle, browser tests, and final verification remain parent-owned and serial.

For optional Kilo CLI sessions, launch the bounded discovery fan-out through the host's native Task surface with a selected route.

---

## Modes

| Mode | Trigger phrases (examples) | Behavior |
| :--- | :--- | :--- |
| **Cycle** (default) | “whole shebang”, “continuous improvement”, “auto-improve once” | One full loop → PR → stop |
| **Loop** | “keep improving”, “continuous improvement loop”, “run N cycles” | Repeat Cycle until stop condition |
| **Discover-only** | “what would you improve?”, “improvement backlog” | Produce backlog + sizes; **no** code edits (still update backlog file + issues) |

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

## Backlog tracking (mandatory)

Track work in **two places** with different roles:

| Store | What goes there | Why |
| :--- | :--- | :--- |
| [`.agents/improvement-backlog.md`](../../improvement-backlog.md) | **All** findings (S/M/L) with status `open` / `in_progress` / `done` / `deferred` / `dropped` | Readable remaining-vs-done history in-repo; survives chat compaction |
| GitHub issues | Every **L** item + any item **deferred** past the current cycle | Discussion, approval, cross-session visibility; link from the backlog `Issue` column |

### File rules

1. **Read** the backlog at Step 0 (avoid rediscovering `done` / already-`deferred` rows).
2. After Step 1 discovery, **upsert** new rows (`Status=open`, IDs like `CI-8-1`
   = cycle 8 item 1). Update existing rows instead of duplicating summaries.
3. When implementing: set `in_progress` + branch name in Notes.
4. When shipping: move rows to the **Done** section with PR number; set
   `Status=done`.
5. Keep long-lived deferred **L** rows in the open table until the user drops
   them (`dropped`) or they ship.
6. Commit backlog updates on the cycle branch with the rest of the cycle (or in
   Discover-only as the only change if no code shipped).

### GitHub issue rules

1. Ensure labels exist (create once if missing):

   ```bash
   gh label create "continuous-improvement" --color "0E8A16" --description "From continuous-improvement cycles" 2>/dev/null || true
   gh label create "size/S" --color "C2E0C6" --description "Small continuous-improvement item" 2>/dev/null || true
   gh label create "size/M" --color "FEF2C0" --description "Medium continuous-improvement item" 2>/dev/null || true
   gh label create "size/L" --color "F9D0C4" --description "Large / needs approval" 2>/dev/null || true
   ```

2. Before creating, **dedupe**:

   ```bash
   gh issue list --state open --label continuous-improvement --limit 50
   # also: gh issue list --state open --search "keyword from summary"
   ```

3. Create an issue for each **L** or cross-cycle **deferred** item that has no
   Issue link yet:

   ```bash
   gh issue create \
     --title "[CI-8-3] Short summary" \
     --label "continuous-improvement,size/L" \
     --body "$(cat <<'EOF'
   ## Summary
   …

   ## Size / risk
   L — …

   ## Evidence
   `path` / symbol …

   ## Proposed approach
   …

   ## Cycle
   Discovered in cycle 8 on branch `improve/…`
   EOF
   )"
   ```

4. Write the issue number into the backlog `Issue` column (`#NN`).
5. **Do not** open issues for S/M items that will ship in the same cycle PR
   unless the user asks — the backlog file is enough.
6. When a PR ships an item: comment on linked issues and `gh issue close N`
   (or leave open if only partially addressed). Reference issues in the PR body
   (`Closes #NN` when fully done).

### Discover-only mode

Still update `improvement-backlog.md` and create/link GitHub issues for L /
deferred items. Do not implement code or open an improve PR unless asked.

---

## One Cycle — workflow

```text
- [ ] Step 0: Branch & safety (+ read improvement-backlog.md)
- [ ] Step 1: Discover backlog (classify S/M/L; upsert file; issues for L/deferred)
- [ ] Step 2: Gate Large items (user feedback)
- [ ] Step 3: Implement approved S/M (+ approved L)
- [ ] Step 4: Verify (tests + targeted UI QA)
- [ ] Step 5: Docs / CHANGELOG / backlog Done rows
- [ ] Step 6: Commit, push, open PR (Closes #… where applicable)
- [ ] Step 7: Cycle report → stop or Loop
```

### Step 0 — Branch & safety

1. Start from an up-to-date `main` (or user-named base).
2. Create a dedicated branch, e.g. `improve/cycle-YYYYMMDD-HHMM` or
   `improve/<theme>`.
3. Read [improvement-backlog.md](../../improvement-backlog.md); skip rediscovery
   of `done` / already-tracked `deferred` unless verifying they still apply.
4. Never use the user’s production `rebalancer-config.json` / DB for UI boots —
   isolated simulation only.
5. Do **not** flip live trading flags.

### Step 1 — Discover backlog

After the native model-selection gate, fan out discovery with
[parallel-multi-agent](../parallel-multi-agent/SKILL.md) when tracks are
disjoint. Suggested discovery tracks (pick what fits timebox):

| Track | Child skill / focus |
| :--- | :--- |
| Code quality | [kotlin-refactoring-and-cleanup](../kotlin-refactoring-and-cleanup/SKILL.md), light [autonomous-code-optimizer](../autonomous-code-optimizer/SKILL.md) Pass 1–3 **survey** (don’t full-converge unless user asked) |
| UI polish | [ui-visual-review](../ui-visual-review/SKILL.md) — recommend only; size each finding |
| Docs | [documentation-review](../documentation-review/SKILL.md) or [changelog-and-docs-sync](../changelog-and-docs-sync/SKILL.md) gap scan |
| Comments | [complex-code-comments](../complex-code-comments/SKILL.md) — missing / wrong / stale / noisy |
| Deps (optional) | [dependency-upgrade](../dependency-upgrade/SKILL.md) — list only unless user wants bumps this cycle |
| Security alerts (always) | Check `gh api repos/{owner}/{repo}/dependabot/alerts` (see [dependency-upgrade](../dependency-upgrade/SKILL.md) § Security alerts). Every cycle — surface open alerts even when skipping routine bumps |
| Known smells | [OPERATING.md](../../OPERATING.md) UI misses; open TODOs/FIXMEs; failing or flaky tests |

**Dependabot alerts are not optional.** Even in a cycle where you are not doing
routine version bumps, run the Dependabot alert query. Fix clean critical/high
pin bumps this cycle (S/M); otherwise add a backlog row + GitHub issue and
gate anything requiring a major migration as **L**.

Produce a **Cycle backlog** table (chat + file):

| ID | Area | Size | Summary | Child skill | Ship this cycle? |
| :--- | :--- | :--- | :--- | :--- | :--- |
| CI-8-1 | … | S/M/L | … | … | yes / defer / ask |

Then:

1. Upsert rows into [improvement-backlog.md](../../improvement-backlog.md).
2. Create/link GitHub issues for **L** and cross-cycle **deferred** items.
3. Show the user the table **with issue links** for anything needing approval.

Timebox discovery (~15–30 min of agent work unless user expands). Prefer a
**shippable** slice over boiling the ocean.

### Step 2 — Gate Large items (user feedback)

If any **L** items exist (or M items that risk trading/safety):

1. Present a short proposal: problem, approach, files touched, risk, rollback,
   and GitHub issue URL/number.
2. Ask which IDs to **approve / defer / drop**.
3. **Wait** for the user before implementing those IDs.
4. Continue with S/M items in parallel only if file ownership does not overlap
   the pending L work.
5. Reflect the decision in the backlog (`deferred` / `dropped` / `in_progress`)
   and comment on the GitHub issue.

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

Use the bounded delegation rules above when ownership is disjoint; keep the
History JS module (`History*.kt`) and shared CSS modules single-owner.

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

Complete **all** PR Test plan / Verification items **before** `gh pr create`
(including UI/sim spot-checks). Never defer checks to after merge — see
[open-pr](../open-pr/SKILL.md).

### Step 5 — Docs

- `CHANGELOG.md` dated SemVer heading (never `[Unreleased]`) for user-visible or
  workflow changes — see [changelog-and-docs-sync](../changelog-and-docs-sync/SKILL.md)
- Update [improvement-backlog.md](../../improvement-backlog.md) Done / deferred
  rows to match what this cycle actually shipped
- README / User Guide / screenshots only if visuals or public behavior changed
  ([docs-screenshot-refresh](../docs-screenshot-refresh/SKILL.md),
  [user-guide](../user-guide/SKILL.md))

### Step 6 — Ship

1. [commit-and-push](../commit-and-push/SKILL.md) on the cycle branch
2. [open-pr](../open-pr/SKILL.md) against `main` — body lists backlog IDs and
   `Closes #NN` for finished issues
3. Return the PR URL

Conventional title examples: `improve: …`, `refactor: …`, `fix: …`, `docs: …`.
PR body: summary of S/M shipped, L deferred (with issue links), verification
results, test plan.

### Step 7 — Cycle report

```markdown
# Continuous improvement — cycle report
- Branch / PR: …
- Backlog file: .agents/improvement-backlog.md (updated)
- Shipped: CI-… (S/M) — Done in backlog; issues closed if any
- Deferred / awaiting approval: CI-… (L) — issue #…
- Gates: pre_commit_check …
- Next: merge / start Loop cycle N+1 / stop
```

---

## What “run with it” means here

| Auto without asking | Always ask first |
| :--- | :--- |
| S/M cleanups, polish inside current design system | L refactors, redesigns, trading-path changes |
| Skill/checklist/docs sync tied to shipped fixes | Major dependency upgrades |
| Backlog file upserts; GitHub issues for L/deferred | Merging the PR; deploying; live config edits |
| Opening an improve/* PR after green gates | Continuing after an L proposal with no reply |
| Another Loop cycle after a clean ship (if Loop mode) | Closing backlog rows as `dropped` without user say-so |

---

## Anti-patterns

- Full [autonomous-code-optimizer](../autonomous-code-optimizer/SKILL.md)
  convergence on every Cycle (too long) — survey + targeted fixes unless user
  asked for exhaustive optimize
- Implementing ui-visual-review findings without size class + L gate
- Adding unsolicited ARIA attributes (`aria-*`), ARIA roles, or accessibility metadata during discovery or refactoring — strictly prohibited by OPERATING.md §5
- Mixing unrelated mega-themes in one PR (split cycles / PRs)
- Skipping `pre_commit_check.sh` because “it’s just docs”
- Silent live-trading or production-config use
- Parallel edits to the same hot file (one `History*.kt` file, one CSS module)
- Filing a GitHub issue for every trivial S polish that ships same-day (noise)
- Leaving discoveries only in chat — always persist to
  `improvement-backlog.md`
- Rediscovering and re-adding items already marked `done` or `deferred`

---

## Checklist

- [ ] Mode chosen (Cycle / Loop / Discover-only)
- [ ] Improve branch from fresh base; backlog file read
- [ ] Backlog classified S/M/L; file upserted; issues for L/deferred
- [ ] L gated with user when present
- [ ] Child skills followed for each shipped item
- [ ] `pre_commit_check.sh` green; UI QA if UI changed
- [ ] CHANGELOG/docs + backlog Done rows updated
- [ ] Commit + PR (with `Closes #…`); cycle report delivered
- [ ] Individual skills still usable alone (this skill only orchestrates)
