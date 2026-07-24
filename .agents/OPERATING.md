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

| User intent | Skill |
| :--- | :--- |
| Commit / push | `commit-and-push` |
| Open PR | `open-pr` |
| UI click-through QA | `ui-manual-qa` |
| UI visual critique / implement | `ui-visual-review` / `ui-visual-implement` |
| Docs screenshots | `docs-screenshot-refresh` |
| Docs audit | `documentation-review` |
| Fan-out parallel work | `parallel-multi-agent` |
| Post-deploy UI smoke | `post-deploy-ui-smoke` |
| Continuous improvement / “whole shebang” | `continuous-improvement` (+ `.agents/improvement-backlog.md`) |

If no skill fits, proceed normally. Don’t skip quality gates the skill names.

---

## 2. Parallel multi-agent work

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
4. After agents return: merge, resolve conflicts, run quality gates, continue.

### Anti-patterns

- Parallel edits to the **same file** without a single owner
- Spawning agents for tiny one-liners
- Parallelizing before a blocking design decision is settled

Details: [skills/parallel-multi-agent/SKILL.md](skills/parallel-multi-agent/SKILL.md).

---

## 3. No blocking long processes

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

## 4. UI change verification

When editing dashboard HTML/CSS/HTMX (`view/**`) or `:frontend-js`:

1. **Viewport** — Judge layout at **laptop ~1280–1440px**, not only mobile.
2. **Cache** — Stylesheet must stay cache-busted (`/static/style.css?v=…`).
   Native-looking white OS buttons usually mean stale CSS.
3. **Interactions** — Prefer `ui-manual-qa` (STYLE/REGRESSION cases) after
   meaningful UI work; unit tests alone miss click/zoom/view presets.
4. **Visuals** — Refresh README/User Guide screenshots when shipping appearance
   changes (`docs-screenshot-refresh`).
5. **Safety chrome** — Keep Simulation / Dry Run obvious.

After a deploy or LAN UI check, run
[post-deploy-ui-smoke](skills/post-deploy-ui-smoke/SKILL.md) (hard-refresh first).

### Common misses

- Squished LIVE + Data Age header cluster on laptop widths
- Concatenated deviation legend (“Over targetUnder target”)
- History Views/Zoom unstyled native buttons
- View presets that don’t hide series (Day · Total only)
- Chart drag that both zooms and pans without a separate pan control
- Scrubber stays **disabled** after drag/wheel zoom (only Zoom buttons synced it)
- Scrubber thumb moves but **chart does not pan** (wrote `options.scales`
  instead of `chart.zoomScale`)

---

## Cursor-specific projection

| Portable section above | Cursor rule file |
| :--- | :--- |
| Prefer project skills | `.cursor/rules/prefer-project-skills.mdc` (`alwaysApply`) |
| Parallel multi-agent | `.cursor/rules/parallel-multi-agent.mdc` (`alwaysApply`) |
| No blocking long processes | `.cursor/rules/no-blocking-long-processes.mdc` (`alwaysApply`) |
| UI change verification | `.cursor/rules/ui-change-verification.mdc` (path globs) |

Commit both this file and `.cursor/rules/` so Cursor clones pick up rules
automatically and other harnesses still have a single portable source.
