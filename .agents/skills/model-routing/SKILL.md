---
name: model-routing
description: >-
  Inventory provider/model routes and recommend subagent models using capability,
  context, comparative cost-per-task, and observed availability. Use when choosing
  models, providers, effort levels, fallbacks, or quota-aware parallel tracks;
  do not use it as a replacement for parallel-multi-agent, review, or domain skills.
---

# Model Routing

Choose the smallest capable model and effort level for each task while making the
evidence and uncertainty visible. This skill owns model/provider selection; it does
not own task decomposition, code review, trading decisions, or provider
configuration.

## Contract And Boundaries

| Item | Contract |
| :--- | :--- |
| Trigger | A request to inventory models/providers, choose a subagent model, compare cost or capability, avoid exhausted routes, or select fallbacks. |
| Inputs | The task or track, risk, required tools/modalities, estimated context, latency or budget constraints, host route data, and any observed session failures. |
| Outputs | One primary route, one or two fallbacks when useful, effort guidance, evidence and confidence, cost classification, and any substitution record. |
| Side effects | Read-only host/catalog queries and optional external research. Do not change provider configuration, persist session quota state, or run availability probes by default. |
| Stop condition | Stop after a defensible recommendation or selection. Stop and escalate when no candidate satisfies hard requirements, a required route cannot be enforced, or the only evidence is too uncertain for the task risk. |

Use [parallel-multi-agent](../parallel-multi-agent/SKILL.md) for decomposition,
ownership, iteration caps, and integration. Use the applicable domain, review, or
quality skill for the work itself. This skill must not turn a model recommendation
into an unbounded extra agent or a second full review.

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
route has remaining quota. `kilo stats` is historical usage evidence only.

Do not run `kilo roll-call` or equivalent probe calls as routine inventory. They
consume quota or money and can perturb the very availability being measured. Use a
probe only with explicit approval and a bounded reason that cannot be answered by
metadata or an actual task call.

If the host exposes only named agent types or a fixed parent model, report that
limitation. A skill can recommend an exact route and fallback, but it cannot force
an arbitrary model through a host interface that does not expose model selection.
Do not claim that a subagent type changed models unless the host configuration
verifies it.

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
2. Expected total cost: route price plus likely retry, correction, review, and
   latency costs. Include actual context and estimated input/output/reasoning
   tokens when the host exposes them.
3. Availability confidence and route health evidence.
4. Provider and model-family diversity for fallbacks or independent review.

Use a Pareto-style choice: a candidate is useful when no other eligible candidate
is at least as capable, no more expensive in expected total cost, and no less
usable for the task. Select the least-cost candidate on that frontier that clears
the capability threshold. Preserve uncertainty instead of inventing a precise
score.

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
return the recommendation and the enforcement limitation rather than silently
using the parent model. For a parallel split, make this record per track and
follow [parallel-multi-agent](../parallel-multi-agent/SKILL.md)'s iteration and
report limits.

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
- Maintaining a permanent ledger of current model names instead of recording the
  evidence and substitutions for the session.

## Verification Checklist

- [ ] The task profile and hard requirements are explicit.
- [ ] Primary and fallback identifiers are exact host routes.
- [ ] Capability evidence matches the task and records its source/version.
- [ ] Intelligence level and sufficiency are stated with matched evidence,
      confidence, and date, or explicitly labeled qualitative evidence.
- [ ] Cost class does not confuse zero metadata price with free usage.
- [ ] Availability state is based on observed evidence, not catalog status alone.
- [ ] Artificial Analysis mappings and benchmark cost are labeled as comparative;
      attribution is present when data is displayed.
- [ ] Substitutions and host enforcement limits are visible.
- [ ] No credentials, raw private output, probes, or transient quota state were
      written to the repository or project memory.
