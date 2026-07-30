# Agent operating norms (all frameworks)

Portable, framework-agnostic operating rules for any coding agent working in
this repository (Cursor, Claude Code, Copilot, Codex, Antigravity, etc.).

**Canonical location:** this file. Cursor also loads projections under
[`.cursor/rules/`](../.cursor/rules/) (`.mdc` with `alwaysApply` / `globs`).
Keep those projections in sync when changing norms here.

Deep domain how-to lives in [skills](skills/) — see the skill index in
[AGENTS.md](AGENTS.md). Prefer skills over inventing parallel workflows.

---

## 1. Prefer project skills

For tasks that match a skill in `.agents/skills/*/SKILL.md` or the index in
`AGENTS.md`, **read and follow that skill** before inventing a parallel process.
When both a repository skill and a user-level, global, or other non-project
skill match, the **repository skill has higher precedence**. Use the external
skill only for behavior the project skill does not cover, and never allow it to
override repository instructions, safety rules, or domain invariants.

| User intent | Skill |
| :--- | :--- |
| Commit / push | `commit-and-push` |
| Open PR | `open-pr` (+ mandatory `adversarial-pr-review`) |
| Push updating an open PR | `commit-and-push` → `adversarial-pr-review` |
| Adversarial / multi-model PR review | `adversarial-pr-review` |
| Pre-PR / diff code review (conventions) | `code-review` |
| Changelog / README / docs sync after a change | `changelog-and-docs-sync` |
| Quality gates (Spotless, JaCoCo, Karma) | `gradle-quality-gates` |
| Dependency upgrades | `dependency-upgrade` |
| Kotlin refactor / cleanup | `kotlin-refactoring-and-cleanup` |
| Code-size reduction / large-file splits | `reduce-code-size` |
| UI click-through QA | `ui-manual-qa` |
| UI visual critique / implement | `ui-visual-review` / `ui-visual-implement` |
| Docs screenshots | `docs-screenshot-refresh` |
| End-user User Guide | `user-guide` |
| Docs audit | `documentation-review` |
| Architecture review / redesign brainstorm | `architecture-review` |
| Product opportunity review / feature roadmap | `product-opportunity-review` |
| Skill / agent-files review (skills, rules, AGENTS) | `skill-reviewer` |
| Complex-code comments (audit / hygiene) | `complex-code-comments` |
| Fan-out parallel work | `parallel-multi-agent` |
| Post-deploy UI smoke | `post-deploy-ui-smoke` |
| Continuous improvement / “whole shebang” | `continuous-improvement` (+ `.agents/improvement-backlog.md`) |
| Continuous quality / QA loop / test hardening | `continuous-quality` (+ `.agents/quality-backlog.md`) |

If no skill fits, proceed normally. Don’t skip quality gates the skill names.

When opening a PR: complete **every** Test plan / Verification item **before**
`gh pr create` — never defer spot-checks to after merge (see §2 and
[open-pr](skills/open-pr/SKILL.md)).

---

## 2. Complete PR verifications before opening

**Always do all verifications for a PR prior to creating the PR.** Prefer certainty
that the change works over shipping faster with incomplete checks.

- Every item in the PR **Test plan** / **Verification Results** must be
  **executed and checked `[x]` before** `gh pr create` (see [open-pr](skills/open-pr/SKILL.md)).
- Do **not** defer spot-checks, UI/viewport verification, sim boots, or other
  manual steps to “after merge” or “the user can confirm later”.
- Do **not** open a PR with unchecked boxes that you intend to finish later.
- If a check does not apply, omit it (or mark N/A with reason) — never leave a
  fake unfinished TODO in the PR body.
- Automated gates alone are not enough when the change needs UI/sim verification;
  run those first, then open.

Anti-patterns: listing “Spot-check at ~1280 after merge”; opening red/incomplete
PRs to move faster; checking a box without having run the step.

---

## 3. Parallel multi-agent work

When a request involves **multiple independent workstreams**, do **not**
serialize them by default. Split and run concurrently with whatever subagent /
Task mechanism the host provides.

### When to parallelize

Launch parallel agents when **all** of these hold:

1. Workstreams touch **disjoint files** (or clearly owned modules) with little
   merge conflict risk.
2. Each stream has a **self-contained goal**.
3. The parent can **integrate** results afterward (tests, wire-up, PR).

### When to keep one stream

Stay single-threaded when streams **share** the same hot files or one depends
on the other’s output (same `History.kt`, API contract + consumer in one change,
cross-cutting refactors).

### How to split

1. Name the tracks briefly for the user (parallel vs coupled).
2. Give each agent: repo path, branch, already-done context, files to
   touch/avoid, acceptance criteria.
3. Reserve one coupled track for interdependent code; fan out the rest together.
4. Keep Gradle to **one build per clone** — either the parent runs all builds, or
   each agent gets its own `git worktree`. Concurrent `./gradlew` in one directory
   kills test workers (`EOFException`) and fakes `UP-TO-DATE`.
5. After agents return: merge, resolve conflicts, run quality gates with
   `--rerun-tasks`, continue.

### Anti-patterns

- Parallel edits to the **same file** without a single owner
- Spawning agents for tiny one-liners
- Parallelizing before a blocking design decision is settled
- Trusting a cached / overlapped green build as final verification

Details: [skills/parallel-multi-agent/SKILL.md](skills/parallel-multi-agent/SKILL.md).

---

## 4. No blocking long processes

Do **not** leave the user waiting on a foreground command that never exits
(app servers, `./gradlew run`, watchers, long sleeps).

1. Start long-lived processes in the **background** (non-blocking spawn).
2. Wait for readiness with short polls / log patterns (`/api/health`,
   “Application started”), not by awaiting the process itself.
3. If blocked ~15–20s with no useful progress, say what you’re waiting on —
   don’t silently hang.
4. When done, **kill** the process and free the port; don’t leave orphan
   Java/Gradle/Node runs.

---

## 5. UI change verification

When editing dashboard HTML/CSS/HTMX (`view/**`), dashboard HTTP/static serving
(`DashboardController` / `DashboardRoutes`), or `:frontend-js`:

Complete these checks **before** opening a PR (see §2) — not after merge.

1. **Viewport** — Judge layout at **laptop ~1280–1440px**, not only mobile.
2. **Cache** — Stylesheet must stay cache-busted (`/static/style.css?v=…`).
   Native-looking white OS buttons usually mean stale CSS.
3. **Interactions** — Prefer `ui-manual-qa` (STYLE/REGRESSION cases) after
   meaningful UI work; unit tests alone miss click/zoom/view presets.
4. **Visuals** — Refresh README/User Guide screenshots when shipping appearance
   changes (`docs-screenshot-refresh`). If canonical shots are unaffected,
   still verify with a temp capture when the PR claims a visual fix.
5. **Safety chrome** — Keep the settings-backed trading-mode plate visible on
   every page (`SIMULATION` > `DRY RUN` > `LIVE TRADING`). Keep the separate
   stream-health chip labeled `STREAM` / `STALE`; it must not imply live trading.

After a deploy or LAN UI check, run
[post-deploy-ui-smoke](skills/post-deploy-ui-smoke/SKILL.md) (hard-refresh first).

### Common misses

- Missing/misleading mode plate, or a stream-health chip labeled as live trading
- Squished STREAM/STALE + relative age/time header cluster on laptop widths
- Dashboard hero delta/sparkline or Cash/Crypto progress tiles clipped/empty
- Activity cycles flattened into an unreadable list or missing the History link
- Safety cards whose ON/OFF state is unclear
- Concatenated deviation legend (“Over targetUnder target”)
- History Views/Zoom unstyled native buttons
- History chart title/legend/zoom header wrapping or caption/table semantics lost
- View presets that don’t hide series (Day · Total only)
- Chart drag that both zooms and pans without a separate pan control
- Scrubber stays **disabled** after drag/wheel zoom (only Zoom buttons synced it)
- Scrubber thumb moves but **chart does not pan** (wrote `options.scales`
  instead of `chart.zoomScale`)

### No unsolicited accessibility metadata

Do not add new ARIA attributes, ARIA roles, accessibility-only labels/copy, or
accessibility-specific acceptance criteria unless the user explicitly requests
accessibility work. Do not expand ordinary UI requests into accessibility
remediation. Preserve existing accessibility metadata when it is outside the
requested change; remove or alter it only when the user asks or the scoped
feature cannot work correctly without doing so.

---

## 6. Complex-code comments

Prefer **readable code without comments**. Add comments only where the logic is
non-obvious or complex (intent, invariants, traps, non-local consequences) —
not to narrate what the next line does.

When editing code:

1. Prefer rename/extract/simplify over a comment when that makes it clear.
2. If you change behavior, **update or delete** nearby comments so they stay
   true — stale comments are worse than none.
3. Do not add wallpaper KDoc (“Calculate X”) on trivial helpers.
4. For a repo-wide or targeted **comment audit** (missing / wrong / stale /
   noisy), use
    [skills/complex-code-comments/SKILL.md](skills/complex-code-comments/SKILL.md).

---

## 7. Cost-aware model selection

When the host allows choosing a model or reasoning effort, use the **least
expensive model and lowest effort reasonably likely to complete the task
correctly**.

1. Start low for bounded, routine work such as searches, mechanical edits,
   formatting, straightforward tests, and status checks.
2. Escalate only when the task's complexity or risk justifies it: ambiguous or
   cross-cutting design, financial/safety-sensitive reasoning, repeated failure,
   or evidence that the current tier cannot complete the work reliably.
3. Honor a model or effort explicitly required by the user, host, or an
   applicable skill. If that model is unavailable in the current host, use the
   closest available model by capability and cost, preserve the intended role,
   and document the substitution.
4. Optimize for total cost, including retries and review time. A cheap attempt
   that is unlikely to succeed is not cost-effective.
5. For parallel work, give each track the cheapest capable tier independently;
   do not promote every subagent because one track is difficult.

Correctness and safety remain the hard constraint. Cost decides between options
that are all likely to succeed; it never justifies weakening verification or
using an underpowered model for high-impact work.

---

## Cursor-specific projection

| Portable section above | Cursor rule file |
| :--- | :--- |
| Prefer project skills | `.cursor/rules/prefer-project-skills.mdc` (`alwaysApply`) |
| Complete PR verifications before opening | `.cursor/rules/pr-verifications-before-open.mdc` (`alwaysApply`) |
| Parallel multi-agent | `.cursor/rules/parallel-multi-agent.mdc` (`alwaysApply`) |
| No blocking long processes | `.cursor/rules/no-blocking-long-processes.mdc` (`alwaysApply`) |
| Complex-code comments | `.cursor/rules/complex-code-comments.mdc` (`alwaysApply`) |
| Cost-aware model selection | `.cursor/rules/cost-aware-model-selection.mdc` (`alwaysApply`) |
| UI change verification | `.cursor/rules/ui-change-verification.mdc` (path globs) |

Cursor projections may add harness-only details (e.g. `block_until_ms: 0`,
`AwaitShell`) that are absent from the portable bullets above — keep the
portable meaning aligned when editing either side.

Commit both this file and `.cursor/rules/` so Cursor clones pick up rules
automatically and other harnesses still have a single portable source.
