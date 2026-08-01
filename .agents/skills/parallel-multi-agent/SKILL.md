---
name: parallel-multi-agent
description: >-
  Split multi-track work into concurrent Task subagents when file ownership is
  disjoint. Use when planning large fixes, mixed CSS/docs/JS changes, or when
  the user asks to parallelize / fan out / multi-agent.
---

# Parallel multi-agent playbook

Persistent always-on summary: `.cursor/rules/parallel-multi-agent.mdc`.
Use this skill for the full split/integrate workflow.

## Step 1 — Partition

List tracks as a table:

| Track | Owns (files/dirs) | Avoids | Depends on |
| :--- | :--- | :--- | :--- |
| A | … | … | none / track B output |

- **Independent** → parallel Task agents (same parent turn).
- **Coupled** → one agent or the parent.

## Step 2 — Brief each agent

Every Task prompt must include:

1. Absolute repo path + current branch
2. Goal and acceptance criteria
3. Files to edit / files forbidden
4. **Already done** context (so they do not redo or conflict)
5. Project constraints worth repeating (Spotless 120, `:common` purity, sim-only, etc.)

Prefer `run_in_background: true` only when the parent can usefully continue; otherwise wait for coupled tracks.

## Step 3 — Integrate

1. Read each agent’s summary; verify diffs with `git status` / `git diff`
2. Fix overlap conflicts yourself (do not re-fan the same files)
3. Run gates **serially, forcing re-execution** (`--rerun-tasks`): Spotless,
   relevant JVM/JS tests
4. Update CHANGELOG / skills if behavior or workflows changed

## One Gradle build per clone

Gradle serializes on the project directory, so **concurrent agents running
`./gradlew` in the same clone corrupt each other**: test workers die with
`java.io.EOFException`, and later invocations report `UP-TO-DATE` for work that
never ran.

Pick one:

1. **Parent owns the build** (simplest): agents edit files and report; only the
   parent runs tests/gates. Tell each agent explicitly not to run `./gradlew`.
2. **Worktree per agent**: give each a `git worktree add` directory so each gets
   its own `build/` and lock.

Either way, never trust a green result from a run that overlapped another agent's
build — re-verify serially (see below).

## Repo-specific ownership hints

| Concern | Prefer owner |
| :--- | :--- |
| SSR / CSS modules | `view/css/*`, `view/component/*` — one stream per CSS file when possible |
| Charts / History JS | `frontend-js/.../History*.kt` — **single** stream |
| Shared IDs/strings | `:common` — coordinate before parallel CSS+JS |
| Agent skills / AGENTS | `.agents/**` — safe parallel with app code |
| Screenshots / User Guide | docs skills — safe parallel with non-UI backend |

## Example split (production UI hotfix)

- Track CSS: header spacing + button appearance (`LayoutStyles`, `NavigationStyles`)
- Track skills: `ui-manual-qa` / `ui-visual-review` regression cases
- Track History JS: visibility presets + zoom scrubber (`HistoryChartState.kt`,
  `HistoryZoom.kt`)
- Parent: wire tests + CHANGELOG + commit
