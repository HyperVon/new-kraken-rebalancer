---
name: model-routing
description: >-
  Inventory provider/model routes and recommend subagent models using capability,
  context, comparative cost-per-task, and observed availability. Use when choosing
  models, providers, effort levels, fallbacks, or quota-aware parallel tracks,
  including before material or parallel subagent work. Includes bounded route
  inventory helpers; do not use it as a replacement for parallel-multi-agent,
  review, or domain skills.
---

# Model Routing

Choose the smallest capable model and effort level for each task while making the
evidence and uncertainty visible. This skill owns model/provider selection; it does
not own task decomposition, code review, trading decisions, or provider
configuration.

**Local-first by default.** When the host exposes a genuinely local model
(for example, Ollama), and it is sufficiently capable for the task, prefer it
over a paid or cloud route: local inference is free, avoids sending the model
prompt/context to an external model provider, works offline, and has no
provider quota dependency. Tools, MCPs, and shell commands still require their
normal network and data-egress checks. Local models are comparatively weak, so
this default only applies when their capability is adequate for the task.
Escalate to a stronger cloud model on capability evidence, not by habit.

## Contract And Boundaries

| Item | Contract |
| :--- | :--- |
| Trigger | A request to inventory models/providers, choose a subagent model, compare cost or capability, avoid exhausted routes, or select fallbacks. |
| Inputs | The task or track, risk, required tools/modalities, estimated context, latency or budget constraints, host route data, and any observed session failures. |
| Outputs | One primary route, one or two fallbacks when useful, effort guidance, evidence and confidence, cost classification, and any substitution record. A local route is the preferred default when capable; note when a cloud escalation is required. |
| Side effects | Read-only host/catalog queries and optional external research. Do not change provider configuration, persist session quota state, or run availability probes by default. |
| Stop condition | Stop after a defensible recommendation or selection. Stop and escalate when no candidate satisfies hard requirements, a required route cannot be enforced, or the only evidence is too uncertain for the task risk. |

Use [parallel-multi-agent](../parallel-multi-agent/SKILL.md) for decomposition,
ownership, iteration caps, and integration. Use the applicable domain, review, or
quality skill for the work itself. This skill must not turn a model recommendation
into an unbounded extra agent or a second full review.

## Delegation Gate

Before launching any material or parallel Task/subagent work:

1. Define the minimum capability, tool needs, risk, and context profile for each
   bounded track.
2. Select and record the primary route, effort, fallback, availability evidence,
   and any substitution before launch.
3. State the exact provider/model and effort plan to the user and obtain explicit
   approval before the first material or parallel Task call. No approval means
   no launch.
4. Treat `subagent_type` as a role by default; it does not prove the underlying
   model or route from its name alone.
5. A host-pinned agent profile satisfies the exact-route gate only when host
   metadata explicitly maps that profile to a provider/model and a fixed or
   host-defined effort. Record the mapping source and label the effort as
   host-defined; do not claim an independently selected effort parameter.
   Verify the selected launcher honors that mapping: some Task wrappers accept
   a role but inherit the parent route. A profile declaration is not launch
   evidence unless the host reports the selected route or the launcher accepts
   the route explicitly.
6. If neither direct selection nor an explicit host-pinned mapping is available,
   stop fan-out and keep the work in the parent or obtain route-selection support.
   A recorded limitation is a stop condition, not permission to launch a role-only
   worker.
7. For a broad request with multiple disjoint tracks, make this an explicit
   decision point: after route inventory, present the exact track/route/effort
   matrix with the `question` tool or host equivalent and obtain approval before
   launching. If no exact route is available, ask whether to continue serially
   or stop for route support; do not silently choose the parent-owned fallback.
   If the user already approved the exact plan, proceed without repeating the
   question.
8. Escalate or add an independent verifier only when task risk and available
   capability evidence justify it.

For a genuinely low-risk, non-material single scout, the record can be brief,
but it must identify the capability threshold and any host selection limitation.
This exception does not authorize material or parallel delegation, and
`subagent_type` remains a role rather than a model route.

## Non-Negotiable Rules

1. Capability sufficiency is a hard constraint; price is not a substitute for
   required reasoning, tool use, context, or domain reliability.
2. Start with low effort for bounded work and escalate only for task risk,
   evidence of failure, or a capability gap. Do not blindly reuse the parent
   model for every track.
3. Treat availability as evidence with a state and confidence. A catalog entry
   marked `active`, a configured credential, or a successful metadata refresh does
   not prove current quota or request health.
4. Distinguish `free`, `subscription/account-priced`, `paid`, and `unknown` cost
   classes. A numeric price of zero may describe OAuth or account billing; it is
   not automatically free.
5. Keep direct and routed provider variants, reasoning/effort variants, and model
   aliases as separate routes until their identity, price, limits, and health are
   verified.
6. Separate every route's access provider, billing/entitlement owner, model
   creator/family, gateway or transport, and credential scope. Never infer a
   direct provider route from an upstream model name, display name, or alias.
   For example, a `kilo/~openai/...` route is a Kilo Gateway route that happens
   to target an OpenAI model family; it is not equivalent to an `openai/...`
   route or proof that an OpenAI subscription pays for it.
7. Treat a configured credential or subscription as evidence only for its exact
   provider and plan scope. It does not transfer across gateways, API products,
   model families, OAuth account types, or billing owners unless the host
   explicitly verifies that mapping.
8. Never put credentials, API keys, account data, raw private runtime output, or
   transient quota state in the repository or project memory.
9. Do not infer intelligence from a model name, parameter count, price, catalog
   status, or one benchmark. State the evidence source and its limitations.
10. Prefer a local model when it clears the capability threshold for the task.
    Treat local as the default primary over paid/cloud routes, and escalate to
    a stronger model only on capability evidence or task risk. Do not assume a
    local model is adequate purely because it is free; verify capability.

## Workflow

### 1. Define The Task Profile

Write down the minimum requirements before looking at model names:

| Signal | Questions |
| :--- | :--- |
| Work type | Is this bounded discovery, mechanical editing, routine code/tests, cross-cutting design, or high-risk review/trading/security work? |
| Tool use | Must the model call repository tools, execute tests, use a browser, or work without tools? |
| Context | How much source, history, or output must fit, including room for tool results and reasoning? |
| Modalities | Are text, image, attachment, or structured-output capabilities required? |
| Reasoning | Is low effort sufficient, or is planning, debugging, adversarial review, or domain reasoning required? |
| Intelligence | What level of general, coding, tool-use, or domain reasoning is sufficient, and what dated model-specific evidence supports it? |
| Constraints | Are latency, spend, provider diversity, a user-required model, or a no-new-provider policy material? |

For common project tracks, use these as starting points rather than defaults:

- Bounded read-only discovery or mechanical work usually needs tool calls, a
  compatible context window, and low effort.
- Routine implementation and tests need reliable tool calls, enough context for
  the owned files, and medium effort only when the change is not mechanical.
- Cross-cutting design, trading, credentials, security, or disputed review needs
  stronger reasoning evidence and may justify an independent verifier.

For parallel work, build a separate profile and selection for each bounded track.
Do not spend a frontier-model call on a track whose contract only needs discovery.

### 2. Inventory Actual Routes

Start with the host's model/provider inventory and configuration. Prefer exact
`provider/model` identifiers, route-specific limits, tool support, context limits,
reasoning variants, and price metadata. Treat the host as authoritative for what
it can actually select, not for unverified quota.

When the Kilo CLI is available, these are optional Kilo-only checks:

```text
kilo models --verbose --refresh [provider]
kilo auth list
kilo config check
```

Use a provider-scoped refresh when the catalog is large. `kilo models` supplies
catalog metadata and pricing, `kilo auth list` shows configured credential types,
and `kilo config check` validates configuration. None of these proves that a
route has remaining quota or can serve a request now. `kilo stats` is historical
usage evidence only.

Do not probe the entire catalog. For a candidate route that will be presented as
available, use one bounded exact-route `kilo roll-call` probe when the route is
exposed by that CLI and the user has approved the probe or the task explicitly
requires availability verification. Probes may consume quota or money. The
repository helper keeps this bounded:

```bash
./.agents/skills/model-routing/scripts/inventory_routes.sh \
  --match 'provider/model' --tool-call --reasoning \
  --probe --verified-only --limit 1
```

`--probe` marks a route `verified` only after a successful request;
`--verified-only` prevents unavailable candidates from being presented. Probe
success proves request health at that moment, not remaining quota or future
reliability. The helper applies `--limit` before probing, so a broad first-N
sample can report zero verified rows even while other catalog routes are
available. Always narrow `--match` to the exact candidate before interpreting
`--verified-only`; a zero sampled result is not a zero-route result. If a
host-pinned provider is not exposed by the CLI, do not
substitute a similarly named Kilo or OpenRouter route. Use host-specific
health/entitlement evidence or mark the pinned route `unknown`.

If the host exposes only named agent types or a fixed parent model, report that
limitation. A skill can recommend an exact route and fallback, but it cannot force
an arbitrary model through a host interface that does not expose model selection.
Do not claim that a subagent type changed models unless the host configuration
verifies it.

For repeatable bounded Kilo inventory, use the repository helper when `kilo` is
available. First inspect catalog candidates without probing:

```bash
./.agents/skills/model-routing/scripts/inventory_routes.sh \
  --tool-call --reasoning --limit 20
```

After selecting a specific candidate, probe only that exact route:

```bash
./.agents/skills/model-routing/scripts/inventory_routes.sh \
  --match 'provider/model' --tool-call --reasoning \
  --probe --verified-only --limit 1
```

The wrapper keeps the verbose catalog in a disposable temporary file and emits
only bounded metadata rows. CLI probes cover only CLI-visible routes; they do
not prove availability for a separate host-pinned provider namespace. Do not
persist raw catalog/probe output, credentials, or private account data.

### 3. Check Quota And Plan Entitlements

Catalog pricing, an `active` model, configured authentication, and a plan name are
not evidence that a request can currently run. When the host exposes a read-only
quota, balance, or entitlement diagnostic, use it before selecting a paid or
account-priced primary route. Prefer a provider- and plan-scoped result with a
recent timestamp; do not use a request probe merely to discover quota.

Before checking quota, resolve route identity from host metadata rather than the
model label. Record these independently: exact selectable route, access/API
provider, gateway or transport, upstream model creator/family, billing or
entitlement owner, credential type/scope, and price source. A model creator name
inside a route identifier is not an access-provider claim. A provider credential
listed by `kilo auth list` is not proof that a similarly named Kilo Gateway route
uses that credential or that its plan covers the route.

Record only a categorical result, never balances, account identifiers, tokens, or
raw billing output:

| Quota state | Meaning | Routing action |
| :--- | :--- | :--- |
| `sufficient` | Recent diagnostic covers the exact provider/plan route and indicates enough allowance for the estimated task | Eligible primary or fallback |
| `insufficient` | Diagnostic reports exhausted, blocked, or inadequate allowance | Hard-filter the route for this task |
| `unknown` | Only catalog/auth/config evidence exists, the scope is ambiguous, or the diagnostic is stale | Do not use as a paid/account-priced primary when quota matters |
| `unavailable` | Diagnostic failed, credentials are invalid, or the provider is disabled | Hard-filter until new evidence appears |

For subscription or account-priced plans, `sufficient` means the entitlement or
remaining allowance is confirmed for the selected route, not merely that the plan
exists. If no trustworthy quota source is exposed, say that quota is unknown and
return no verified paid primary rather than guessing from catalog price. A route
with unknown quota can remain a clearly labeled conditional fallback only when the
task risk and user budget permit it.

Do not print or persist the diagnostic's private values. If a host/plugin reports a
transport error or a command-handled sentinel, classify quota as `unknown`, not
`insufficient`, unless the diagnostic explicitly reports exhaustion.

### 4. Add Independent Capability And Cost Evidence

Assess intelligence separately from interface capabilities. Record the relevant
level as `low`, `medium`, `high`, or `frontier` only with a confidence label and
evidence source. Prefer a dated, model-specific benchmark or evaluation that
matches the task, route, and reasoning variant. A model name, release date,
parameter count, price, context window, or tool support is not intelligence
evidence.

When no trustworthy benchmark or evaluation is available, provide a cautious
qualitative assessment instead of inventing a score: state the likely level, why
it is sufficient or insufficient for this task, and mark confidence as low or
medium. Say explicitly when the assessment is based on model-family or general
evidence rather than the exact route. Intelligence evidence is comparative and
does not guarantee correctness.

[Artificial Analysis](https://artificialanalysis.ai/) is an optional external prior,
not live route health. Prefer its documented
[Data API](https://artificialanalysis.ai/data-api/docs) when an authorized key is
already configured outside the repository. The documented API base is
`https://artificialanalysis.ai/api/v2`; keep `x-api-key` server-side, respect the
documented rate limits, cache within the permitted window, and record the source
timestamp and index version. Never commit a key or embed one in a command,
example, report, or log.

The documented free language endpoint is `/language/models/free`. Lower-tier
responses may omit provider-specific context, modalities, or route details; leave
those fields unknown rather than filling them from a model name. Prefer stable
model and creator IDs over mutable slugs when the API provides them.

If no authorized API access exists, public leaderboard or methodology pages can
provide qualitative, dated evidence. Do not make website scraping a default
integration. If Artificial Analysis values are shown to a human, include visible
attribution and the source URL. The API's current data conventions use `null` for
not measured or not applicable; never convert that to zero.

Use the evidence that matches the task:

| Task need | Relevant Artificial Analysis evidence |
| :--- | :--- |
| Tool-using subagent | Agentic Index, GDPval-AA, or tau3 evidence where available |
| Coding or terminal work | Coding or Terminal-Bench evidence |
| General research or review | Overall Intelligence and relevant general-reasoning evidence |
| Scientific or domain work | The relevant capability/domain index and individual benchmark, when measured |

The [Intelligence Index methodology](https://artificialanalysis.ai/methodology/intelligence-benchmarking)
is a comparative benchmark, not a guarantee for this repository. Individual
evaluations can be more useful for a specific task, and benchmark results can
share blind spots. Record the index version and do not present a public score as
a guarantee of correctness.

Join external model records to host routes only through stable IDs, an explicit
mapping, or a high-confidence verified alias. If the provider route or reasoning
variant does not match, leave the mapping unknown. Artificial Analysis benchmark
cost-per-task is comparative benchmark cost based on benchmark usage; it is not
the exact cost of the current task or proof of the selected provider's price.

### Local-First Preference

Before ranking cloud routes, check whether the host exposes a local model that
satisfies the task profile. When Kilo and Ollama are available, enumerate the
local routes with:

```text
kilo models ollama
ollama list
```

Treat local as the default primary when it meets the hard requirements:

- **Capability fit**: the task's reasoning, tool-use, context, and modality needs
  fit what the local model demonstrably supports. Basic, mechanical, bounded, or
  low-risk work (simple edits, summary, formatting, small refactors, isolated
  lookups) is a good local fit. High-risk trading, credential, security, or
  adversarial-review work should not use a local model as the sole primary
  without validated capability, an independent verifier, and required human
  approval.
- **Empirical check over name**: do not accept a local model on `ollama list`
  alone. Confirm tool support (`kilo models --verbose`) and reuse recent,
  task-relevant quality and latency evidence where available. Run a quick,
  bounded real probe only when the user requests validation or the task
  explicitly requires route testing; do not probe merely for routine inventory.
- **No model-provider egress requirement**: prefer local when the task involves
  sensitive files or you want the model prompt/context to remain on the machine;
  apply normal tool, MCP, and network policies separately.

Escalate from local to a cloud/paid route only on capability evidence or
task risk, and say so explicitly in the decision record:

| Local default | Escalate to stronger model when |
| :--- | :--- |
| Routine, mechanical, bounded, low-risk | Complex multi-file design, debugging, or refactors |
| Tool-compatible simple edits | Demanding agentic tool orchestration |
| Offline / private / no-quota work | Frontier reasoning or review quality required |
| Sensitive-data tasks | Proven local failure or quality gap |

Do not let "free" override a real capability gap, and do not overthink trivial
router decisions — for genuinely simple tasks, choosing a capable local model is
the expected default rather than a special case.

### Subscription And Account-Priced Preference

After local routes, prefer a **verified subscription/account-priced route** over
a pay-as-you-go per-token route when both clear the task's capability, context,
tool, provider, and request-health requirements. This is a preference, not a
capability override: choose the stronger or more reliable PAYG route when the
subscription route is inadequate, quota-limited, slower enough to increase total
cost, or not covered by the verified entitlement.

Require all of the following before treating a subscription route as eligible:

- The exact provider/model route is selectable or explicitly host-pinned.
- The subscription or account entitlement is verified for that access provider,
  gateway, model family, and plan scope.
- A recent route-specific health probe or equivalent host diagnostic succeeded.
- The expected task fits the plan's known limits; unknown quota is not
  `sufficient`.

Never infer subscription coverage from a provider name, configured credential,
active catalog row, or zero token price. Keep the access provider, gateway, model
creator, billing owner, and entitlement scope separate. A PAYG route with
verified access is preferable to an unverified subscription route.

### 5. Hard-Filter Candidates

Remove a candidate before ranking it when any of these fail:

- The route cannot satisfy required tool calls, context, output, reasoning, or
  attachment/image capabilities.
- The provider is not configured or the host cannot select the exact route when
  exact selection is required.
- The requested provider or billing/entitlement owner is not the route's verified
  access or billing owner. Do not silently substitute a gateway route that targets
  the same model family.
- A user-required model or provider constraint is not met.
- The route has failed for quota, authentication, or provider health in the
  current session and no new evidence makes it usable.
- The route's quota state is `insufficient` or `unavailable`.
- The task or user budget requires quota confirmation and the route's quota state
  is `unknown`.
- The route's expected cost violates an explicit budget.

For high-risk work, an unknown route may be a fallback candidate, but it is not
"verified available". If no candidate clears the hard filter, stop rather than
pretending that a cheaper or more familiar model is sufficient.

### 6. Rank By Expected Total Cost

Do not rank by `intelligence / cost` alone. Use a capability threshold and then
compare the non-dominated candidates on:

1. Capability evidence relevant to the task.
2. Cost class and expected total cost: prefer a verified subscription/account-
   priced route over PAYG among otherwise eligible candidates, then compare route
   price plus likely retry, correction, review, and latency costs. Include actual
   context and estimated input/output/reasoning tokens when the host exposes them.
3. Availability confidence and route health evidence.
4. Provider and model-family diversity for fallbacks or independent review.

Use a Pareto-style choice: a candidate is useful when no other eligible candidate
is at least as capable, no more expensive in expected total cost, and no less
usable for the task. Select the least-cost candidate on that frontier that clears
the capability threshold. Preserve uncertainty instead of inventing a precise
score.

Apply the local-first preference as a tiebreaker: among candidates that clear the
capability threshold with comparable expected total cost and reliability, prefer
the local route. Favor local explicitly for low-risk mechanical work even when a
paid route is marginally faster to reason about, because local has zero marginal
cost, no model-provider data egress, and no quota risk. Only escalate on capability evidence,
latency that matters, or task risk.

Classify cost before comparing it:

| Class | Treatment |
| :--- | :--- |
| `free` | The route metadata explicitly says no charge; still check capability and health. |
| `subscription/account-priced` | Billing is through an account or plan; numeric catalog cost may be zero without proving zero marginal cost. |
| `paid` | The route exposes a usable price; estimate the task cost from its actual context and output. |
| `unknown` | Do not sort it as free or cheap; surface the uncertainty and use it only when the task risk permits. |

Keep Artificial Analysis cost-per-task, host token pricing, and historical usage
as separate evidence fields. Historical `kilo stats` can show observed spend or
prior use, but it cannot establish current quality, quota, or availability.

### 7. Select And Record The Route

Select one primary and, when the task is material or a failure would be costly,
one or two fallbacks. Prefer a different provider route for the first fallback;
two aliases of the same unhealthy service are not independent fallbacks. Record
the decision before launching subagents:

| Field | Required content |
| :--- | :--- |
| Track | The bounded task or role being delegated |
| Primary | Exact provider/model route and effort level |
| Cost | Cost class, route price evidence, and any task estimate |
| Capability | Required capability plus the evidence source and mapping confidence |
| Intelligence | Required reasoning level, model-specific score/evaluation or qualitative assessment, confidence, source, and date |
| Availability | Route health plus quota state (`sufficient`, `insufficient`, `unknown`, or `unavailable`), timestamp, and evidence scope |
| Fallback | Exact alternate route and why it is suitable |
| Substitution | Requested route, selected route, and reason when they differ |

Use `verified` only for a recent successful call or meaningful route-specific
health evidence. Use `configured/unknown` when the host can select the route or
has credentials but current request health is untested. Use `unavailable` for a
current-session quota, authentication, disabled-provider, or provider-health
failure. Catalog metadata alone never upgrades `configured/unknown` to
`verified`.

Use the host's exact model-selection mechanism when it exists. If it does not,
do not launch material or parallel work; return the recommendation and the
enforcement limitation, or continue the work in the parent. If a host-pinned
profile supplies the mapping, record the profile, explicit provider/model,
host-defined effort, and the metadata source. For a parallel split, make this
record per track and follow
[parallel-multi-agent](../parallel-multi-agent/SKILL.md)'s iteration and report
limits.

For Kilo CLI launches, a `mode: all` profile plus explicit `--model` and
`--variant` arguments is route-enforceable. The project helper
`parallel-multi-agent/scripts/run_routed_agent.sh` validates that the profile
is not subagent-only before invoking that path. Keep the route dynamic from the
current inventory; do not hardcode it into a profile merely to satisfy a Task
wrapper that cannot expose model selection.

When a user names a provider, report both the requested provider and the route's
verified access/billing provider. If they differ, label the route as a
substitution, do not describe it as the requested provider, and prefer a direct
route or stop when the provider constraint is hard. Apply this rule to every
provider, not only OpenAI.

### 8. Respond To Runtime Evidence

When an actual task call fails, classify the failure and change route instead of
blindly retrying:

| Failure | Session action |
| :--- | :--- |
| Quota or rate limit | Mark that exact route unavailable for this session and use a different fallback. |
| Authentication or disabled provider | Mark the route unavailable until configuration changes; do not repeat it. |
| Provider or transport failure | Treat the route as unavailable for the current decision unless new health evidence appears. |
| Context, tool, or modality mismatch | Remove the route from this task profile. |
| Quality failure | Escalate capability or switch model family; preserve the failed evidence in the report. |

Do not persist these transient states to project memory, a committed file, or a
long-lived denylist. A later task must re-check current host evidence. Do not
retry the same failed route merely because it was the cheapest or the parent
model.

## Report And Stop Conditions

Keep the final routing report compact. A useful shape is:

```markdown
| Track | Primary | Effort | Cost | Capability evidence | Intelligence | Availability | Fallback | Substitution |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| ... | provider/model | low/medium/high | class + estimate | source + confidence | level + score/evaluation or qualitative rationale + confidence/date | state + evidence time | provider/model | requested -> selected, reason |
```

Stop after the report and selection are complete. Stop for an explicit decision
when a paid availability probe is the only way to distinguish routes, an external
data API would incur an unapproved cost, an exact user-required model cannot be
enforced, or no trustworthy candidate meets the hard requirements. High-risk
application changes still require the repository's existing human-approval and
domain workflows; model routing does not waive them.

## Anti-Patterns

- Sorting the entire catalog by raw intelligence divided by token price.
- Treating `status: active`, an auth entry, or a metadata refresh as live quota.
- Treating every zero price as free, or transferring a routed provider's price to
  a direct provider route.
- Treat an upstream model family, route prefix, display name, or similarly named
  credential as the access provider, billing owner, or entitlement source.
- Treat a subscription for one provider or product as covering a gateway, API,
  or account product without explicit host evidence.
- Matching Artificial Analysis and host models with an unverified fuzzy name.
- Using a public benchmark score as a correctness guarantee or permanent policy.
- Repeating the same quota-failed route, or probing every catalog model before a
  bounded task.
- Selecting the same parent model for every parallel track without checking each
  track's minimum capability and cost.
- Defaulting to a paid/cloud model for a task a capable local model handles,
  or assuming a local model is adequate merely because it is free.
- Escalating to a bigger model without a capability or risk reason, or never
  re-checking local capability as the task profile changes.
- Launching material or parallel work from only `subagent_type` and claiming the
  underlying model or route is known without host selection evidence.
- Launching before the user approves the exact provider/model and effort plan.
- Treating a host-pinned profile as route evidence without recording the host
  metadata that maps it to a provider/model and fixed or host-defined effort.
- Presenting an active catalog route as available without a bounded health probe,
  or using a CLI probe for a host-pinned provider namespace it cannot address.
- Treating a subscription/account-priced label or zero token price as proof of
  entitlement, quota, or free usage.
- Treating a generic role such as `general` as a selected model or provider
  route.
- Maintaining a permanent ledger of current model names instead of recording the
  evidence and substitutions for the session.

## Verification Checklist

- [ ] The task profile and hard requirements are explicit.
- [ ] A capable local model was considered first, and a cloud escalation (if any)
      is defended by capability evidence or task risk.
- [ ] Primary and fallback identifiers are exact host routes.
- [ ] The user approved the exact provider/model and effort plan before launch.
- [ ] Any pinned profile has an explicit host metadata mapping recorded; bare
      role names were not used as route evidence.
- [ ] Capability evidence matches the task and records its source/version.
- [ ] Intelligence level and sufficiency are stated with matched evidence,
      confidence, and date, or explicitly labeled qualitative evidence.
- [ ] Cost class does not confuse zero metadata price with free usage.
- [ ] CLI-visible selections have a recent bounded health probe, or host-pinned
      selections have equivalent host-specific health evidence.
- [ ] Subscription/account-priced entitlement is verified for the exact route;
      unknown quota is not treated as sufficient.
- [ ] Artificial Analysis mappings and benchmark cost are labeled as comparative;
      attribution is present when data is displayed.
- [ ] Substitutions and host enforcement limits are visible.
- [ ] No credentials, raw private output, probes, or transient quota state were
      written to the repository or project memory.
