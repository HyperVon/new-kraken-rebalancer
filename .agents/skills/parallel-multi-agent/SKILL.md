---
name: parallel-multi-agent
description: >-
  Split multi-track work into adaptive, bounded concurrent Task subagents when
  file ownership is disjoint. Use when planning large fixes, mixed CSS/docs/JS
  changes, review fan-out, or when the user asks to parallelize / fan out /
  multi-agent.
---

# Parallel multi-agent playbook

Persistent always-on summary: `.cursor/rules/parallel-multi-agent.mdc`.
Use this skill for the full split/integrate workflow.

## Step 1 — Partition

List the smallest useful set of independent tracks as a table. The number of
tracks is task-dependent, not a fixed two-agent recipe; normally use one track
per independent concern, not one agent per file. For a material audit, keep the
fan-out bounded (usually 2–6, maximum 8) and reserve a coupled track for files
that must be reasoned about together.

| Track | Owns (files/dirs) | Avoids | Depends on |
| :--- | :--- | :--- | :--- |
| A | … | … | none / track B output |

For review work, add `risk`, `model`, `iteration cap`, and `stop condition`.
The parent owns the full diff and final coverage matrix; each worker receives
only its assigned paths and minimum dependencies.

- **Independent** → parallel Task agents (same parent turn).
- **Coupled** → one agent or the parent.

## Step 2 — Brief each agent

Every Task prompt must include:

1. Absolute repo path + current branch
2. Goal and acceptance criteria
3. Files to edit / files forbidden
4. **Already done** context (so they do not redo or conflict)
5. Project constraints worth repeating (Spotless 120, `:common` purity, sim-only, etc.)

Keep prompts and reports bounded. For GPT-5.6 Luna, treat roughly **256K input
tokens** as a practical reliability and cost boundary despite the larger
documented context window. Prefer each delegated request below **128K** and
split it before it approaches **180K**. Give each agent an explicit file scope,
stop condition, and iteration cap; request at most 12 report lines and 5
findings, not raw file dumps or progress logs. Split broad work into staged
discovery and focused follow-ups; the parent retains integration and final
verification.

Workers must not perform the whole parent task. They should not receive the
full repository context, run builds, start servers, edit files, inspect secrets
or runtime data, or load unrelated skills. If a worker approaches its context
or iteration limit, it returns a compact partial report and the parent starts a
new narrower follow-up. Do not use manual compaction as a way to continue the
same oversized worker task.

Prefer `run_in_background: true` only when the parent can usefully continue; otherwise wait for coupled tracks.

## Step 3 — Integrate

1. Read each agent’s compact summary; verify diffs with `git status` / `git diff`
2. Fix overlap conflicts yourself (do not re-fan the same files)
3. Run gates **serially, forcing re-execution** (`--rerun-tasks`): Spotless,
   relevant JVM/JS tests
4. Re-run only tracks affected by an edit; add a cross-track verifier only when
   a fix crosses ownership boundaries
5. Update CHANGELOG / skills if behavior or workflows changed

## Review-specific fan-out

For adversarial PR review, the parent should first inventory changed paths and
high-risk hunks, then assign focused tracks such as CI/build, runtime
correctness, trading/exchange safety, persistence/security, UI/client behavior,
and tests/documentation. Use only tracks represented by the diff. A second
model is a targeted verifier for high-risk or disputed findings, not a reason
to send two agents the entire PR.

Launch independent tracks in one message when possible. Record the track,
allowed paths, model substitution, iteration cap, coverage, and stop reason.
Never paste full prior reports into follow-ups; pass only the finding and the
smallest affected path set.

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
