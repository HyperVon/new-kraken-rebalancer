---
name: parallel-multi-agent
description: >-
  Split multi-track work into adaptive, bounded concurrent Task subagents when
  file ownership is disjoint. Use when planning large fixes, mixed CSS/docs/JS
  changes, review fan-out, or when the user asks to parallelize / fan out /
  multi-agent.
---

# Parallel multi-agent playbook

Canonical always-on norms: [OPERATING.md § 3](../../OPERATING.md#3-parallel-multi-agent-work).
Use this skill for the full split/integrate workflow.

## Delegation modes

Set the mode explicitly in every worker brief:

- **Read-only discovery/review:** inspect assigned evidence and return findings;
  no edits, builds, servers, Git mutations, or secret/runtime-data inspection.
- **User-authorized implementation:** edit only the assigned, disjoint files
  within the user's approved scope. Name allowed and forbidden paths and the
  acceptance checks. Stop for approval before expanding ownership or making a
  high-impact change not already authorized. Read-only mode never implicitly
  grants implementation permission.

The parent owns integration and final verification in both modes. Workers do
not start servers or inspect secrets/runtime data. Builds remain parent-owned
unless explicitly assigned to a separate worktree under the serial-build rules
below; implementation permission alone is not permission for Git mutations.

## Step 1 — Partition

List the smallest useful set of independent tracks as a table. The number of
tracks is task-dependent, not a fixed two-agent recipe; normally use one track
per independent concern, not one agent per file. For a material audit, keep the
fan-out bounded (usually 2–6, maximum 8) and reserve a coupled track for files
that must be reasoned about together.

| Track | Owns (files/dirs) | Risk | Mode / role / minimum capability | Depends on |
| :--- | :--- | :--- | :--- | :--- |
| A | … | … | … | none / track B output |

For review work, include an iteration cap and stop condition.
The parent owns the full diff and final coverage matrix; each worker receives
only its assigned paths and minimum dependencies.

- **Independent** → parallel workers launched in the same parent turn through
  the host's native parallel task surface (`invoke_subagent` under Antigravity).
- **Coupled** → one agent or the parent.

### Parent-model inheritance

Subagents inherit the parent session's model; do not pin, select, or require a
specific provider/model route for a track:

- Record the minimum capability for each bounded track and any substitution
  needed after a launch failure.
- Treat `subagent_type` as the worker role, not as a model choice from its name.
- In Google Antigravity (AGY) sessions, launch subagents natively using built-in `invoke_subagent` tool calls. Do NOT execute a Kilo-specific launcher.
- Under Kilo CLI, launch bounded fan-out through the host's
  native Task surface. Subagents run on the parent session's model; do not
  substitute `ctx_batch_execute`, `ctx_execute`, or an in-process scan for a
  real subagent launch.
- Native Auto owns its model mappings and fallbacks; it does not need a
  repository-side inventory or probe.
- For a broad read-only named workflow, perform discovery fan-out natively via the host's parallel task surface.
- Add an independent verifier only when the track risk justifies it; it also
  inherits the parent's model. Independence comes from a separate bounded review,
  not a claimed distinct provider/model route.

## Step 2 — Brief each agent

Every worker prompt must include:

1. Absolute repo path + current branch
2. Goal and acceptance criteria
3. Explicit mode, assigned evidence/allowed edit paths, and forbidden files
4. **Already done** context (so they do not redo or conflict)
5. Project constraints worth repeating (Spotless 120, `:common` purity, sim-only, etc.)
6. Substitution record: role, scope, and the reason for any relaunch after a
   subagent launch failure

Keep prompts and reports bounded. Use the model's practical context limit. When that limit is unavailable, prefer each
delegated request below **128K** and split it before it approaches **180K**.
Give each agent an explicit file scope, stop condition, and iteration cap;
request at most 12 report lines and 5 findings, not raw file dumps or progress
logs. Scope the evidence set so the worker can reserve its final step for the
required compact report; a worker that consumes its entire iteration budget on
reads has not completed its track. Split broad work into staged discovery and
focused follow-ups; the parent retains integration and final verification.

Workers must not perform the whole parent task, receive the full repository
context, or load unrelated skills. Apply the permissions for the explicit mode
above; do not tell an implementation worker both to edit and never to edit.
If a worker approaches its context
or iteration limit, it returns a compact partial report and the parent starts a
new narrower follow-up. Do not use manual compaction as a way to continue the
same oversized worker task.

Independent tracks must launch concurrently, not one foreground task at a time.
Use the host's native concurrent/background task mechanism and completion
notifications when available. When the host exposes a parallel tool, submit all independent Task
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
and tests/documentation. Use only tracks represented by the diff. An independent
verifier is a bounded track for high-risk or disputed findings, not a reason
to send two agents the entire PR or select a different model for a worker.

Prefer the repository's specialized types when available: use
`agent-guidance-auditor` for rules/CI/Kilo guidance,
`documentation-contract-auditor` for product-doc/source contracts, and
`explore` for narrow source discovery. These names are Kilo/OpenCode examples;
other harnesses should map the same roles to their own read-only agents.
Use `general` only as a last-resort bounded role for a genuinely low-risk,
non-material single scout. A role label is not a model choice.

Launch independent tracks in one message when possible. Record the track, mode,
role, scope, user approval, iteration cap, coverage, stop reason, and any launch
failure/relaunch or parent substitution. Do not record per-track model routes.
Never paste full prior reports into follow-ups; pass only the finding and the
smallest affected path set.

For an adversarial re-review of a completed documentation audit, assign a
separate bounded read-only track through the host's native parallel surface.
Subagents still inherit the parent's model; role labels do not establish model
diversity. Native model selection for the parent remains governed by
[OPERATING.md § 8](../../OPERATING.md#8-native-model-selection).

If a subagent launch fails for a transient host reason, relaunch the same
track as a subagent (a corrected role or a host-default-routed subagent); cover
it in the parent only when no subagent launch can run it. Never claim that a
role or profile enforced a model. The parent still owns the review surface, integration,
and final verification:

```bash
./.agents/skills/adversarial-pr-review/scripts/review_surface.sh main
```

For named broad workflows, launch the bounded track fan-out through the host's
native parallel task surface; subagents inherit the parent session's model. The
parent still owns integration and final gates; workers edit only in explicitly
authorized implementation mode.

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
| Dashboard JS behavior | `frontend-js/src/jsMain/kotlin/Dashboard.kt` — **single** stream |
| Shared IDs/strings | `:common` — coordinate before parallel CSS+JS |
| Agent skills / AGENTS | `.agents/**` — safe parallel with app code |
| Screenshots / User Guide | docs skills — safe parallel with non-UI backend |

## Example split (production UI hotfix)

- Track CSS: header spacing + button appearance (`LayoutStyles`, `NavigationStyles`)
- Track skills: `ui-manual-qa` / `ui-visual-review` regression cases
- Track Dashboard JS: freshness and sorting behavior in `Dashboard.kt`
- Parent: wire tests + CHANGELOG + commit
