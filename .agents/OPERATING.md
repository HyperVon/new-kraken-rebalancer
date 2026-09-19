# Agent operating norms (all frameworks)

Portable, framework-agnostic operating rules for any coding agent working in
this repository (Cursor, Claude Code, Copilot, Codex, Antigravity, etc.).

**Canonical location:** this file. Cursor also loads thin pointers under
[`.cursor/rules/`](../.cursor/rules/) (`.mdc` with `alwaysApply` / `globs`).
Cline loads thin pointers under [`.clinerules/`](../.clinerules/) (`.md`),
each referencing a section below; keep the sections below canonical.

Deep domain how-to lives in [skills](skills/) — see the skill index in
[AGENTS.md](AGENTS.md). Prefer skills over inventing parallel workflows.

---

## 1. Prefer project skills

For tasks that match a skill in `.agents/skills/*/SKILL.md` or the index in
[`AGENTS.md`](AGENTS.md), **read and follow that skill** before inventing a
parallel process. When both a repository skill and a user-level, global, or
other non-project skill match, the **repository skill has higher precedence**.
Use the external skill only for behavior the project skill does not cover, and
never allow it to override repository instructions, safety rules, or domain
invariants.

The canonical skill index lives in [`AGENTS.md`](AGENTS.md); update it when
adding or renaming skills.

If no skill fits, proceed normally. Don’t skip quality gates the skill names.

When opening a PR: complete **every** Test plan / Verification item **before**
`gh pr create` — never defer spot-checks to after merge (see §2 and
[open-pr](skills/open-pr/SKILL.md)).

### Optional semantic workspace search

When the active harness exposes `zvec_grep_search`, use it for workspace-grounded
semantic or fuzzy discovery when wording or location is unknown, or when the
answer requires relationship, chronology, causality, or cross-file synthesis.
Use native `rg` for known exact words, paths, filenames, keys, literals, or
regexes. Pass an absolute workspace `root` to zvec-grep and treat its bounded
snippets as evidence. If the index is unavailable, use native `rg`; creating,
rebuilding, or dropping a persistent `.zvec-grep/` index requires explicit user
authorization.

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

When a request involves **multiple independent workstreams**, parallelize bounded
tracks through the host's native parallel task surface. Subagents inherit the
parent session's model: do not pin, select, or require a specific provider/model
route for a delegated track. File disjointness alone is not permission to launch
role-only workers.

The repository is harness-agnostic for ordinary development: any capable host
can use the application source, tests, Gradle commands, Git workflow, and
portable `.agents/` guidance. KiloCode-specific conveniences are optional and
limited to Kilo Auto, `.kilo/kilo.json`, Kilo route reports, Context Mode, and
Agent Manager. When running under Google Antigravity (AGY), subagents MUST be
launched directly through Antigravity's native `invoke_subagent` tool calls;
do not execute a Kilo-specific launcher there. Other non-Kilo hosts should
similarly use their built-in native agent fan-out. Under Kilo, use the host's
native parallel task surface; subagents inherit the parent session's model.

Independent work must be launched concurrently: use one parallel tool message
or a background process for the complete fan-out, then poll results. Do not
start one foreground worker, wait for it, and only then start the next; that is
sequential delegation, not parallel fan-out.

For Kilo sessions, `.kilo/kilo.json` selects `kilo/kilo-auto/efficient` as the
project default. That is a host-supported Auto tier, not a claim about which
underlying model will answer a particular request.

### Parent-model inheritance for subagents

Subagents inherit the parent session's model. Do not pin, select, or require a
specific provider/model route for a delegated track, and do not treat
`subagent_type` as a model choice from its name alone. Keep the delegation
bounded:

1. Define the task profile and minimum capability for each bounded track.
2. Launch the bounded track fan-out through the host's native parallel task
   surface; each subagent runs on the parent's model.
3. Never persist credentials, prompts, balances, or raw provider errors.
4. Use the `question` tool or host equivalent when a hard availability, scope,
   editing, or high-risk review decision remains unresolved.
5. If a subagent launch fails for a transient host reason, relaunch the same
   track as a subagent (a corrected role, another available role, or a
   host-default-routed subagent). Cover the track in the parent only when no
   subagent launch can run it, then record the substitution; do not leave the
   track unexecuted over a launch failure.
6. For high-risk or disputed work, add an independent verifier only when the
   risk justifies it.

### When to parallelize

Launch parallel agents when **all** of these hold:

1. Workstreams touch **disjoint files** (or clearly owned modules) with little
   merge conflict risk.
2. Each stream has a **self-contained goal**.
3. The parent can **integrate** results afterward (tests, wire-up, PR).

### When to keep one stream

Stay single-threaded when streams **share** the same hot files or one depends
on the other’s output (same `History*.kt` file, API contract + consumer in one
change, cross-cutting refactors).

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

For audits and reviews, choose an adaptive `N` from the actual concerns and
ownership boundaries rather than defaulting to two full-task agents. Usually
use 2–6 tracks, at most 8, and add a second model only for a high-risk or
disputed track. The parent owns the coverage matrix, triage, integration, and
final verification; workers are bounded scouts, not alternate project owners.

### Context budget

Keep delegated prompts below the model's practical long-context comfort zone:

1. Give each agent a bounded file set, a short acceptance checklist, and an
   explicit stop condition.
2. Ask for compact findings or a patch summary, not raw file dumps or full
   transcripts. Split a broad audit into staged discovery and follow-up tasks.
3. Treat context size as the model's practical limit; when that limit is
   unavailable, use bounded prompts below **128K** and split before **180K** as a
   conservative default.
4. Cap discovery workers at 8 iterations and reports at 12 lines / 5 findings
   unless the parent explicitly widens the limit for a named high-risk question.
5. If a worker approaches its context limit, have it return a compact partial
   report and start a narrower follow-up. Manual compaction is not a strategy
   for continuing the same oversized task.
6. The parent agent owns integration and final verification; do not make every
   subagent repeat the full repository context or quality gate.

### Anti-patterns

- Parallel edits to the **same file** without a single owner
- Spawning agents for tiny one-liners
- Parallelizing before a blocking design decision is settled
- Trusting a cached / overlapped green build as final verification

### Worktree and state isolation

Worktrees provide separate code views, not permission to duplicate or share
runtime state:

- Do not copy `.env`, rebalancer configuration, databases, logs, or runtime
  state between worktrees. Use placeholders and disposable, ignored state.
- If a workflow starts this application, use the isolated simulation path with
  both `simulation=true` and `dryRun=true` plus a temporary database. Never use
  live credentials or a live database for agent work.
- Do not use shared `git stash` or autostash across worktrees. The parent owns
  integration, cleanup, and the final build/quality gates.

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
5. Prefer an exit-notification launch (a tool that reports process completion)
   over sleep-polling; never make the user wait on a process that already
   finished.

---

## 5. UI change verification

When editing dashboard HTML/CSS/HTMX (`view/**`), dashboard HTTP/static serving
(`DashboardController` / `DashboardRoutes`), or `:frontend-js`:

Complete these checks **before** opening a PR (see §2) — not after merge.

1. **Viewport** — Judge layout at **laptop ~1280–1440px**, not only mobile.
   For repeatable static evidence, use
   `.agents/skills/docs-screenshot-refresh/scripts/capture_screenshots.py`
   with `--profile laptop,phone` (add `tablet` or `wide` when relevant) rather
   than taking one-off browser screenshots.
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

## 7. Lean, contract-aware code

Write code a staff engineer would sign: **defensive exactly at trust
boundaries** (external APIs, user input, configuration, persistence, money) —
**lean and confident inside them**.

When writing code or tests:

1. No guards for states the type system or the caller's contract makes
   impossible (null checks on non-nullable internals, re-validating
   already-parsed input deep inside the boundary, emptiness checks before
   loops that already handle empty).
2. Validate each invariant once, at its owning boundary — not again in every
   layer below it.
3. Never fall back silently over a state that should fail hard; "safe"
   defaults that swallow failures hide defects (and still rethrow
   `CancellationException` in coroutine code).
4. Each test kills a distinct defect class. Skip impossible-case tests,
   cosmetic input duplication, coverage padding ("does not throw" / "is not
   null" only), and framework tests (getters, no-logic delegation). Keep
   unlikely-but-possible boundary cases (exchange responses, config, user
   input) — they are cheap insurance.
5. Prefer the existing local pattern over a new abstraction; a wrapper,
   factory, or interface needs a current seam or policy, not a hypothetical
   one.

Audit rubric and cleanup workflow:
[skills/ai-slop-detector/SKILL.md](skills/ai-slop-detector/SKILL.md).

### Always-on quality baseline

Treat "AI slop" as an observable artifact defect, not a guess about authorship:
plausible-looking code, tests, docs, configuration, or guidance that lacks
current evidence or adds unnecessary correctness, maintenance, safety, or
review cost. Apply this lightweight pass to every task:

- Tie non-obvious claims, commands, APIs, configuration, tests, and completion
  statements to current source, contracts, observed behavior, or checks; label
  unknowns instead of filling them with assumptions.
- Before adding an artifact or abstraction, identify its concrete consumer,
  canonical owner, simpler alternative, and outcome-level verification.
- Keep the change and its documentation focused on the requested user or
  maintainer task. Do not add speculative wrappers, duplicate mechanisms,
  misleading tests, or unrelated cleanup.
- Treat style, verbosity, unusual formatting, and formulaic language as prompts
  to investigate, not defects by themselves.

Use [ai-slop-detector](skills/ai-slop-detector/SKILL.md) for a scoped
evidence-based audit or explicitly authorized cleanup. The full skill is
conditional; this compact baseline is not. Do not turn ordinary work into a
repository-wide audit merely because the baseline is always active.

---

## 8. Native model selection

Kilo sessions use the project default in `.kilo/kilo.json`:
`kilo/kilo-auto/efficient`. Kilo Auto Efficient classifies each request and
chooses the least expensive benchmarked model expected to complete it. Its
underlying mappings are server-side and can change; do not hardcode them in
repository skills or scripts.

Use the host's native tiers for the parent session's own work: the project
default in `.kilo/kilo.json` selects `kilo/kilo-auto/efficient`, and hosts may
expose stronger or smaller tiers for high-risk or bounded routine work. Tier
mappings are server-side and can change; do not hardcode them in repository
skills or scripts.

For bounded parallel subagents in Google Antigravity (AGY), launch subagents
natively via `invoke_subagent` tool calls; do NOT execute a Kilo-specific
workflow launcher.

Subagents inherit the parent session's model. Launch bounded subagent fan-out
through the host's native parallel task surface; do not pin, select, or record
provider/model routes for tracks, and do not treat a role label as a model
choice. If a subagent launch fails for a transient host reason, relaunch the
same track as a subagent (a corrected role, another available role, or a
host-default-routed subagent); cover the track in the parent only when no
subagent launch can run it, then record the substitution. Named workflow
fan-outs return per-track reports; inspect those instead of launching a second
native `kilo run` batch.

Before material or parallel delegation:

1. Define the task profile and minimum capability.
2. Start with the least expensive capable tier and escalate for demonstrated
   complexity, repeated failure, or safety-sensitive reasoning.
3. Never persist credentials, prompts, balances, or raw provider errors.
4. Treat context size as the model's practical limit; when unavailable, keep
   delegated requests below **128K** and split before **180K**.

Correctness and safety remain the hard constraint. Cost decides only between
options that are all likely to succeed.

---

## 9. LSP servers

LSP is disabled by default in this repository (`.kilo/kilo.json` sets
`"lsp": false`). The Kotlin LSP index goes stale between Gradle builds —
heavy KSP/codegen catalogs (AppConfig, generated sync-metadata keys) show
up as spurious unresolved references after model changes, so diagnostics
are more noise than signal here. Rely on the Gradle gates
(`./gradlew build jacocoTestCoverageVerification`, `spotlessCheck`) for
authoritative feedback; set `KILO_DISABLE_LSP_DOWNLOAD=1` to stop
auto-installs, and flip `"lsp"` back to `true` only if a session genuinely
benefits from read-time diagnostics.

## 10. IntelliJ MCP server

`.kilo/kilo.json` registers an `intellij` remote MCP server
(`http://127.0.0.1:64342/sse`). Tool availability is automatic once the IDE is
open with the *Enable MCP Server* checkbox ticked; the guidance below covers
routing, not setup.

**Prefer IntelliJ tools for structural work:**

- `search_symbol` + `analyze_calls` (call hierarchy) over text grep when the
  question is "who calls this" or "where is this symbol" — precise call
  relationships with less noise than regex sweeps.
- `rename_refactoring` over manual find-and-replace renames — it updates all
  references project-wide.
- `get_file_problems` / `lint_files` for a quick post-edit diagnostic pass.
- `xdebug_*` tools for JVM debugger sessions; `execute_sql_query` for
  inspecting the SQLite database.

**Boundaries:**

- IDE diagnostics are advisory. Gradle gates
  (`./gradlew build jacocoTestCoverageVerification`, `spotlessCheck`) remain
  the authoritative verification — same rationale as §9: KSP/codegen-heavy
  code makes IDE indexing stale after model changes, so spurious
  unresolved-reference warnings can appear.
- The server is a runtime dependency on a live IDE process. If tools are
  missing or the connection fails, note it and fall back to native
  grep/Gradle; do not treat unavailability as a project defect, and never
  block on it.

---

## Cursor-specific projection

| Portable section above | Cursor rule file |
| :--- | :--- |
| Prefer project skills | `.cursor/rules/prefer-project-skills.mdc` (`alwaysApply`) |
| Complete PR verifications before opening | `.cursor/rules/pr-verifications-before-open.mdc` (`alwaysApply`) |
| Parallel multi-agent | `.cursor/rules/parallel-multi-agent.mdc` (`alwaysApply`) |
| No blocking long processes | `.cursor/rules/no-blocking-long-processes.mdc` (`alwaysApply`) |
| Complex-code comments | `.cursor/rules/complex-code-comments.mdc` (`alwaysApply`) |
| Lean, contract-aware code | `.cursor/rules/lean-contract-aware-code.mdc` (`alwaysApply`) |
| Native model selection | `.cursor/rules/cost-aware-model-selection.mdc` (`alwaysApply`) |
| Optional semantic workspace search | `.cursor/rules/retrieval-routing.mdc` (`alwaysApply`) |
| IntelliJ MCP server | `.cursor/rules/intellij-mcp.mdc` (`alwaysApply`) |
| UI change verification | `.cursor/rules/ui-change-verification.mdc` (path globs) |

Each `.cursor/rules/*.mdc` is a thin pointer to the portable section above; the
bullets above are the single source of truth, so keep them canonical. Cursor
tool specifics (`block_until_ms: 0`, `AwaitShell`) live in the portable
section above, not in the pointer files.

Commit both this file and `.cursor/rules/` so Cursor clones pick up the
pointers automatically and other harnesses still have a single portable source.

---

## Cline-specific projection

| Portable section above | Cline rule file |
| :--- | :--- |
| Prefer project skills | `.clinerules/prefer-project-skills.md` (universal) |
| Complete PR verifications before opening | `.clinerules/pr-verifications-before-open.md` (universal) |
| Parallel multi-agent | `.clinerules/parallel-multi-agent.md` (universal) |
| No blocking long processes | `.clinerules/no-blocking-long-processes.md` (universal) |
| Complex-code comments | `.clinerules/complex-code-comments.md` (universal) |
| Lean, contract-aware code | `.clinerules/lean-contract-aware-code.md` (universal) |
| Native model selection | `.clinerules/cost-aware-model-selection.md` (universal) |
| Optional semantic workspace search | `.clinerules/retrieval-routing.md` (universal) |
| IntelliJ MCP server | `.clinerules/intellij-mcp.md` (universal) |
| UI change verification | `.clinerules/ui-change-verification.md` (path-scoped) |

Each `.clinerules/*.md` is a thin pointer to the portable section above; the
bullets above are the single source of truth, so keep them canonical.

Commit both this file and `.clinerules/` so Cline clones pick up the pointers
automatically and other harnesses still have a single portable source.
