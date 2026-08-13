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

| Track | Owns (files/dirs) | Risk | Role / host route / effort | Depends on |
| :--- | :--- | :--- | :--- | :--- |
| A | … | … | … | none / track B output |

For review work, add `risk`, host route/effort evidence, iteration cap, and
stop condition.
The parent owns the full diff and final coverage matrix; each worker receives
only its assigned paths and minimum dependencies.

- **Independent** → parallel workers launched in the same parent turn (routed
  launcher under Kilo CLI, native `invoke_subagent` under Antigravity).
- **Coupled** → one agent or the parent.

### Native model-selection gate

Before the first material or parallel worker launch, select a host-supported
model route for each track:

- Record the minimum capability, primary route, effort when exposed, fallback,
  availability evidence, cost class/entitlement, and any substitution.
- State the route and effort plan to the user and obtain explicit approval before
  the first material or parallel worker launch.
- Treat `subagent_type` as the worker role, not as route evidence from its name.
- In Google Antigravity (AGY) sessions, launch subagents natively using built-in `invoke_subagent` tool calls. Do NOT execute the Kilo-specific ARR workflow launcher.
- Under Kilo CLI, every read-only discovery or review fan-out MUST go through
  `.agents/runtime-router/adapters/kilo/route_subagents.py`;
  a raw role-only `Task` call is not a substitute because it selects no
  provider/model route. Direct `Task` subagents are the fallback only when the
  launcher cannot run (non-Kilo host, no network, launcher failure).
- Native Auto owns its model mappings and fallbacks; it does not need a
  repository-side inventory or probe.
- For a broad read-only named workflow under Antigravity, perform discovery fan-out natively via `invoke_subagent`. Under Kilo CLI, use the routed preset.
- Escalate or add an independent verifier only when the track risk and available
  capability evidence justify it.

## Step 2 — Brief each agent

Every worker prompt must include:

1. Absolute repo path + current branch
2. Goal and acceptance criteria
3. Files to edit / files forbidden
4. **Already done** context (so they do not redo or conflict)
5. Project constraints worth repeating (Spotless 120, `:common` purity, sim-only, etc.)
6. Selected host route, effort when exposed, cost class, availability evidence,
   and user approval; if the host cannot expose the route, do not launch

Keep prompts and reports bounded. Use the selected route's documented or
observed practical context limit. When that limit is unavailable, prefer each
delegated request below **128K** and split it before it approaches **180K**.
Give each agent an explicit file scope, stop condition, and iteration cap;
request at most 12 report lines and 5 findings, not raw file dumps or progress
logs. Scope the evidence set so the worker can reserve its final step for the
required compact report; a worker that consumes its entire iteration budget on
reads has not completed its track. Split broad work into staged discovery and
focused follow-ups; the parent retains integration and final verification.

Workers must not perform the whole parent task. They should not receive the
full repository context, run builds, start servers, edit files, inspect secrets
or runtime data, or load unrelated skills. If a worker approaches its context
or iteration limit, it returns a compact partial report and the parent starts a
new narrower follow-up. Do not use manual compaction as a way to continue the
same oversized worker task.

Independent tracks must launch concurrently, not one foreground task at a time.
When the host exposes background process support, launch the single routed
workflow or each independent process with `run_in_background: true` and poll its
status/logs. When the host exposes a parallel tool, submit all independent Task
calls in one message. Foreground waiting is reserved for coupled work whose next
step depends on the result.

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

Prefer the repository's specialized types when available: use
`agent-guidance-auditor` for rules/CI/Kilo guidance,
`documentation-contract-auditor` for product-doc/source contracts, and
`explore` for narrow source discovery. These names are Kilo/OpenCode examples;
other harnesses should map the same roles to their own read-only agents.
Use `general` only as a last-resort bounded role for a genuinely low-risk,
non-material single scout or as a role label for a host-selected route. It is
never a model/provider substitute and cannot bypass the material/parallel route
gate.

Launch independent tracks in one message when possible. Record the track, role,
host route/effort mapping, model substitution, user approval, iteration cap,
coverage, and stop reason.
Never paste full prior reports into follow-ups; pass only the finding and the
smallest affected path set.

For an adversarial re-review of a completed documentation audit, use the
`documentation-adversarial-review` preset rather than raw role-only Task calls.
After it completes, inspect the Markdown/JSON route report. Same-role, same-tier,
or same-provider labels do not prove independent model reasoning; record the
actual routes and state explicitly when no independent route was obtained.

### Native Kilo model selection

Kilo sessions inherit the project default `kilo/kilo-auto/efficient` from
`.kilo/kilo.json`. Select `kilo/kilo-auto/frontier` through the host for a
high-risk or disputed review, or `kilo/kilo-auto/small` for bounded routine
work. Auto tiers choose their underlying models and server-side fallbacks; do
not add a launcher, catalog parser, connectivity probe, or hardcoded
underlying-model pool to reproduce that behavior. The separate
ARR's target workflow launcher exists only to enforce direct cross-provider routes
when the host Task surface cannot do so.

If a host Task surface cannot expose the selected route, keep the track
parent-owned or use the routed manifest workflow; never claim that a role or
profile enforced a model. The parent still owns the review surface, integration,
and final verification:

```bash
./.agents/skills/adversarial-pr-review/scripts/review_surface.sh main
```

For named broad workflows, prefer the automatic preset instead of creating a
manifest manually:

```bash
./.agents/runtime-router/adapters/kilo/route_subagents.py \
  --workflow documentation-review \
  --task "<the user's workflow request>" \
  --refresh \
  --approve
```

Use the matching workflow definition listed in the target ARR adapter docs. The
launcher supplies bounded scopes and specialized roles, prints the route/quota
plan, and launches each read-only track. A named read-only workflow request
authorizes this bounded fan-out; the parent still owns edits, integration, and
final gates.

## Worktree and state isolation

Treat a worktree as an isolated code workspace, not a place to duplicate
credentials or runtime state:

- For this repository’s Agent Manager workflow, use `.kilo/run-script` (tracked)
  and, when present, an optional local `.kilo/setup-script` (untracked, so it
  does not exist in a fresh clone or worktree). The run path forces
  `simulation=true` and `dryRun=true` and uses a private temporary database.
- Never copy `.env`, `rebalancer-config.json`, databases, logs, or runtime state
  into another worktree. Keep configuration placeholder-only and use disposable
  ignored state for tests or local runs.
- Do not use shared `git stash` or autostash across worktrees. The parent owns
  integration, cleanup, and the final build/quality gates.

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
