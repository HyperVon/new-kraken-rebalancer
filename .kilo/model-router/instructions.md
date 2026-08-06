# Routed Workflow Orchestration

The project has a route-enforcing subagent launcher at
`.kilo/model-router/route-subagents`. Use it for broad workflows that define
parallel discovery or review tracks.

When the initial `route-kilo` prompt contains a known repository skill reference
such as `/open-pr` or `/documentation-review`, the launcher resolves the
reference to `.agents/skills/<name>/SKILL.md` and prepends an instruction for the
main session to read and follow that file. Unknown slash commands are passed
through unchanged.

## Skill Mapping

Use the matching workflow preset instead of asking the user to create or edit a
manifest:

| Skill | Preset |
| :--- | :--- |
| `documentation-review` | `documentation-review` |
| Documentation-review adversarial re-review | `documentation-adversarial-review` |
| `autonomous-code-optimizer` | `autonomous-code-optimizer` |
| `continuous-improvement` | `continuous-improvement` |
| `continuous-quality` | `continuous-quality` |
| `adversarial-pr-review` | `adversarial-pr-review` |
| `ai-slop-detector` | `ai-slop-detector` |
| `complex-code-comments` | `complex-code-comments` |
| `dependency-upgrade` | `dependency-upgrade` |
| `architecture-review` | `architecture-review` |
| `rules-and-skills-audit` | `rules-and-skills-audit` |
| `skill-reviewer` | `skill-reviewer` |

## Launch Contract

**MUST — no exceptions.** For every Kilo CLI session that needs to fan out
parallel read-only discovery or review tracks, you MUST invoke the
`.kilo/model-router/route-subagents` script with a `--workflow` preset and
`--run`. You MUST NOT use the native `Task` / `task` tool as a substitute for
the routed launcher, and you MUST NOT call `ctx_batch_execute`, `ctx_execute`,
or any in-process parallel scan instead of the script. A role label or Kilo Auto
tier does not select a provider/model route; only the script's exact-route
selection satisfies the model-selection gate.

When a named workflow reaches its parallel-discovery step:

1. Invoke the matching `route-subagents --workflow <preset> --task "<request>" --run`
   before doing the broad discovery in the parent.
2. Do NOT replace the script with a parent-only `ctx_batch_execute` scan or an
   unverified role-only worker. Context Mode remains useful inside a bounded
   track or for parent-owned follow-up, but it does NOT replace routed fan-out.
3. The script prints the complete route/quota plan to stdout BEFORE launching
   any workers. Inspect that plan. Only then do workers start (because `--run`
   is passed).
4. Independent tracks MUST launch concurrently. The script uses a single
   `ThreadPoolExecutor`; do NOT spawn one worker in the foreground and wait
   before starting the next.
5. The script copies each read-only worker into a temporary repository copy
   with a `.gitignore`'d ignore filter (`.git`, `.gradle`, `build/`,
   `rebalancer-config.json`, `*.db`, `.env*`, etc.), so a worker that ignores
   its prompt cannot modify the parent worktree.
6. Keep `--allow-edits` off for all standard read-only discovery. Only a custom
   manifest with explicitly owned writable paths may set it.
7. After the workers finish, the script writes a secret-free Markdown + JSON
   route report and prints a compact `Route summary` table. The parent session
   MUST relay that per-track route summary (track, status, route chain, profile,
   billing, duration) into the conversation.
8. For `adversarial-pr-review`, keep plan-only mode (omit `--run`) ONLY when a
   separate human route decision is explicitly required. The user's request to
   run the adversarial review authorizes the `--run` gate.
9. When the user explicitly asks for a different model or delegation, stop
   parent implementation and perform the exact-route handoff first via the
   script. Do NOT claim that a role-only Task call changed the model.

10. Decompose only the independent read-only discovery tracks required by the
    workflow. The preset supplies bounded scopes and specialized agent roles.
11. Plan routes and quota with the parent request as task context:

    ```bash
    ./.kilo/model-router/route-subagents \
       --workflow <preset> \
       --task "<the user's workflow request>" \
       --run
    ```

12. The command prints the complete route/quota plan before launching. The user's
    request to run the named read-only workflow authorizes this bounded discovery
    fan-out; do not ask the user to hand-edit a manifest. Omit `--run` only when
    the workflow explicitly requires a separate human route decision. Keep
    `adversarial-pr-review` in plan-only mode until its review-specific approval
    gate is satisfied.
13. Keep implementation, edits, backlog integration, Gradle, browser tests, and
    final verification in the parent unless the workflow explicitly says
    otherwise. Never use `--allow-edits` for standard discovery.

Independent tracks must launch concurrently. In a host with background process
support, run the single `route-subagents --run` command in the background and
poll its status/logs; do not run one worker in the foreground and wait before
starting the next. If using a host Task equivalent instead, submit all
independent calls in one parallel tool message. Never describe sequential
foreground launches as fan-out.

### Launching from a Kilo session (background process)

Kilo background-process and shell wrappers re-evaluate commands through zsh,
so inline quoting is fragile: commands containing `$(...)`, single quotes, or
long `--task` strings can fail with `parse error near ()` before the launcher
runs. Do not fight the wrapper — put the invocation in a small shell script and
run that script instead.

1. Write the full task text to a temp file with the Write tool (for example
   `$TMPDIR/routed-task.txt`). A file keeps the `--task` string out of every
   shell parse.
2. Write a launcher script with the Write tool, then start it with the
   background-process tool as `bash /path/to/launcher.sh` — no inline special
   characters:

   ```bash
   #!/usr/bin/env bash
   set -euo pipefail
   TASK_TEXT="$(cat "$TMPDIR/routed-task.txt")"
   exec ./.kilo/model-router/route-subagents \
     --workflow adversarial-pr-review \
     --task "$TASK_TEXT" \
     --run
   ```

3. The script prints the route/quota plan before workers start and the Route
   summary table plus report path when they finish.

Polling caveats:

- The launcher now emits live progress: phase lines (`[subagents HH:MM:SS]`,
  `[router HH:MM:SS]`, `[quota HH:MM:SS]`) stream to stderr, which stays
  line-buffered even when piped, and both wrappers run Python unbuffered
  (`python3 -u`), so the route plan table on stdout also appears in real time.
  `logs` therefore shows progress instead of nothing until exit.
- A secret-free live status snapshot is written to
  `~/.cache/kilo/model-router/status.json` while workers run: pid, phase, and
  per-track route / status (`queued` → `running` → `done`/`failed`) with
  exit code and elapsed seconds. `cat ~/.cache/kilo/model-router/status.json`
  tells you exactly where a run is and whether each worker is still alive.
- Network phases (provider catalogs, Artificial Analysis pages, quota-plugin
  queries, TPS probes) are each logged before they start and after they
  resolve, so a silent multi-minute stall is attributable to a specific
  endpoint. A TPS probe can legitimately take up to
  `min(tpsProbe.timeoutSeconds, probeCharacters / minTps)` seconds per free
  route (default 50s); several free routes are probed serially, so plan-only
  runs may sit in the probe phase for minutes on first use of a route.
- A track can fail fast (exit 1 in under a second, `failure_kind: null`) on
  concurrent cold starts of `kilo run`. Retry the same command; the second run
  passes with identical routes.

### While workers run: check observability, do not wait blindly

An agent that launched a fan-out MUST keep polling the observability surface
instead of sitting idle on the assumption that the run will finish:

- Poll `cat ~/.cache/kilo/model-router/status.json` roughly every 60–90 seconds
  while workers are queued/running. The file is secret-free and shows pid,
  phase, and per-track status (`queued` → `running` → `done`/`failed`) with
  route, exit code, and elapsed seconds. A stale `updated_at_utc` with no
  progress across several polls is a real stall, not a slow worker.
- Read the background-process `logs` output between polls. The `[subagents]`,
  `[router]`, and `[quota]` phase lines identify the current network phase, so
  a multi-minute silence is attributable to a specific endpoint instead of
  being a mystery. TPS probes legitimately take up to ~50s per free route and
  run serially — do not kill a run that is merely probing.
- If a run is stuck for more than ~2–3 minutes with no new phase line, no
  status.json transition, and no worker `kilo run` process on the host, stop
  the launcher and retry the same command once before investigating further —
  cold-start races and quota-plugin timeouts resolve on retry.
- Use the status.json transitions to decide when to stop polling: the run is
  finished when every track is `done` or `failed` and the Route summary table
  prints; only then collect the per-track route summary and the report path.

For adversarial or second-pass review, inspect the generated route report before
calling the result an independent-model review. A role name or Kilo Auto tier is
not evidence of model diversity. If the actual provider/model routes are the
same, report that no independent route was obtained and rerun the disputed track
through a different host-enforceable route when the risk justifies it.
The adversarial presets request distinct exact routes for their tracks; if
availability prevents that, the launcher records the reuse warning instead of
claiming diversity.

The launcher selects an exact provider/model independently for every track,
uses the installed quota plugin, and applies bounded runtime failover. A raw
Kilo `Task` call remains unrouteable and must not be used as a substitute.
When the user explicitly asks for a different model or delegation, stop parent
implementation and perform the exact-route handoff first. Do not claim that a
role-only Task call changed the model; if no host-enforceable route is available,
state that limitation and keep the work parent-owned.

Kilo's native `grep` tool accepts regular-expression patterns, and its native
`glob` and `read` tools are available for repository searches. Use those tools
directly when they are present; Context Mode is an output-management aid, not a
replacement for native regex search. If a tool call actually fails, report the
specific error rather than claiming that regex search is unavailable.

Each `--run` writes a secret-free Markdown and JSON route report under
`~/.cache/kilo/model-router/reports/` and prints the Markdown path after the
workers finish. Reports include track scope, profile, planned and used
provider/model, billing, benchmark/capability/quota metadata, timing, and
failovers. They intentionally omit parent prompts, worker report text,
credentials, and raw provider errors. Set `KILO_MODEL_ROUTER_REPORT_DIR` or pass
`--report-dir` to choose another local destination.

After the workers finish, the launcher also prints a compact `Route summary`
table to stdout: track, status, the planned-to-used provider/model route chain
(including failovers), profile, billing, and duration. The parent session must
relay that per-track route summary into the conversation so the operator sees
which providers/models ran which tasks without opening the report directory.
Keep the relay one table or a few lines; do not paste the full report.

When a catalog exposes model variants, the selected profile chooses one instead
of silently accepting the provider default: trivial/routine prefer low/medium,
coding prefers medium/high, complex-coding/agentic prefer high/thinking, and
quick-review/detailed-review/critical prefer xhigh/max, with model-specific
fallbacks. Headless workers use
`--variant`; the full TUI receives a temporary agent configuration overlay and
does not modify the project config. High-risk profiles (detailed-review,
critical) prioritize capability evidence; free routes remain eligible when they
satisfy the profile and policy. Only explicit blacklist patterns exclude models
or providers.

Ranking only considers candidates that satisfy every eligibility gate: sufficiently
qualified (quality must be assessable and meet the profile minimum — routes whose
capability is unknown or cannot be assessed are never considered), accessible and
useable (active, tool-capable, quota not exhausted, policy-permitted and
available). Profile inference classifies deliberation tasks (review, audit,
documentation, analysis, workflow, delegation) as a `review` profile rather
than `coding`, so a code review is held to a higher intelligence minimum.
Among the eligible set, routes are ordered by lowest effective cost, then by
smallest capability headroom above the profile minimum (so a just-sufficient
small/fast model wins a trivial task, yet a genuinely strong model wins where
the minimum is high), then by an already-paid subscription over PAYG, then by
higher available quota (to prefer headroom and spread load), then by unknown
quota deprioritized. High-risk profiles (`detailed-review`, `critical`) add a
margin above their minimum, so security/money work never routes to a barely
adequate model. A free or cheaper route (quality still above the minimum)
therefore outranks a more expensive higher-quality route; quota headroom breaks
cost ties. Free-billing models whose quota state is `unknown` (the quota plugin
does not meter free models) are treated as usable and compete on cost like
confirmed-sufficient routes rather than being pushed behind them. Subscription /
account-priced routes keep their real per-task cost (they still burn a token
budget), so a smaller model wins over a large one at a similar effective price;
on an effective-cost tie a subscription route is preferred over a PAYG one.

Before a selected free route is used, the router sanity-checks its sustained
throughput with a short probe (an OpenAI-compatible chat completion that asks
for roughly a thousand characters of output, capped by `tpsProbe.maxTokens`),
and re-selects the next best route when the measured tokens/sec stays below
`tpsProbe.minTps` (default 20). Probe results are cached under
`~/.cache/kilo/model-router/tps.json` for `tpsProbe.cacheMinutes` (default 60)
so warm startups stay fast; unmeasurable routes (no endpoint, no key, probe
error) never block selection. The probe times out after
`min(tpsProbe.timeoutSeconds, tpsProbe.probeCharacters / tpsProbe.minTps)`
seconds (50s by default) and a timed-out route is cached at 0 tokens/sec, so it
stays excluded for the cache window instead of being re-probed. If every free
route is too slow, selection falls back to the next cheapest qualifying route,
paid if necessary, and warns.
Tune or disable via the `tpsProbe` section of `.kilo/model-router/config`.

Standard read-only workers run from temporary repository copies, so an agent
that ignores its prompt cannot modify the parent worktree. `--allow-edits` is
the explicit exception for a custom manifest with owned writable paths.

Persistent route exclusions live in `.kilo/model-router/config` under
`blacklist`. `blacklist.models` accepts glob patterns matching either a full
`provider/model` route or a model ID; `blacklist.providers` excludes every model
from a provider. Keep both arrays empty unless an operator asks to exclude a
route. A future model-selection update should edit those arrays and verify the
next route plan; the blacklist is applied before capability, cost, and quota
ranking for both the primary launcher and routed workers.

End-of-life models are added to `blacklist.models` automatically: when a launch
answers HTTP 410 / "end of life" (`model_eol`), both `route-kilo` runs and
routed subagents append the exact dead route to the tracked
`.kilo/model-router/config` and immediately retry the next best candidate
without excluding the provider. That write is intentional and will appear as a
config diff to review and commit — an EOL is a universal fact worth sharing,
and the explicit `--run` gate already authorized the run that discovered it.
