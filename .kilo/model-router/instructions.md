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

When a named workflow reaches its parallel-discovery step:

For Kilo broad workflows, this handoff is mandatory: invoke the matching
`route-subagents --workflow ... --run` preset before doing the broad discovery
in the parent. Do not replace it with a parent-only `ctx_batch_execute` scan or
an unverified role-only worker. Context Mode remains useful inside a bounded
track or for parent-owned follow-up, but it does not replace routed fan-out.

1. Decompose only the independent read-only discovery tracks required by the
   workflow. The preset supplies bounded scopes and specialized agent roles.
2. Plan routes and quota with the parent request as task context:

   ```bash
   ./.kilo/model-router/route-subagents \
      --workflow <preset> \
      --task "<the user's workflow request>" \
      --refresh \
      --run
   ```

3. The command prints the complete route/quota plan before launching. The user's
   request to run the named read-only workflow authorizes this bounded discovery
   fan-out; do not ask the user to hand-edit a manifest. Omit `--run` only when
   the workflow explicitly requires a separate human route decision. Keep
   `adversarial-pr-review` in plan-only mode until its review-specific approval
   gate is satisfied.
4. Keep implementation, edits, backlog integration, Gradle, browser tests, and
    final verification in the parent unless the workflow explicitly says
    otherwise. Never use `--allow-edits` for standard discovery.

Independent tracks must launch concurrently. In a host with background process
support, run the single `route-subagents --run` command in the background and
poll its status/logs; do not run one worker in the foreground and wait before
starting the next. If using a host Task equivalent instead, submit all
independent calls in one parallel tool message. Never describe sequential
foreground launches as fan-out.

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
