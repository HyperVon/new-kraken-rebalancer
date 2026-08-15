---
name: agent-runtime-router
description: >-
  Use the installed Agent Runtime Router to inventory explicit provider/model
  evidence, make a fail-closed route decision, or build a digest-only dry-run
  plan. Use when routing a task, selecting a subagent candidate, inspecting
  denials or quota evidence, or checking why no route was eligible.
---

# Agent Runtime Router

Use the receipt-managed runner at
`.agents/.agent-runtime-router/run.py`. It executes the installed package from
the target-local isolated environment and does not depend on a personal source
checkout path.

## Harness preflight — always do this first

Before routing, recommending a model, or preparing a launch, establish which
harness is active and whether this target is ready to use ARR with it. Do not
infer the harness from the conversational model, credentials, a product name,
or a guessed command. Use only explicit session evidence, a target-owned
profile, or another bounded adapter-owned observation.

The skill-level preflight classifies the result as follows (these are guidance
states; the current CLI exposes the underlying profile/audit metadata rather
than a single five-state classifier):

- `READY`: the harness identity is evidenced, ARR has a matching contract, the
  target-owned profile and adapter are valid, and the matching catalog cache is
  fresh and usable. Continue with routing.
- `SUPPORTED_NOT_CONFIGURED`: ARR's bundled integration registry has a
  contract, but this target lacks a valid profile/adapter or active state.
  Explain that integration is needed, produce a read-only integration or
  adaptation plan, and ask the user for approval before writing target files.
- `NEEDS_REFRESH`: the target integration exists, but its matching catalog is
  absent, stale, or unusable. Ask for approval for the bounded discovery or
  refresh; do not route from another harness's cache.
- `UNSUPPORTED`: no ARR contract is registered for the evidenced harness.
  Tell the user that a target-owned adapter integration is required and ask
  whether they want that work planned. Do not claim that generic fallback
  makes the harness ready.
- `UNKNOWN_HARNESS`: the active harness cannot be proven. Ask the user to
  identify it or provide bounded evidence, and stop with `INCOMPLETE`.

Consult the secret-free registry before rediscovering a known harness:

```text
python .agents/.agent-runtime-router/run.py integration list --pretty
python .agents/.agent-runtime-router/run.py integration show \
  --id <known-integration-id> --pretty
python .agents/.agent-runtime-router/run.py harness profile \
  --target . --pretty
python .agents/.agent-runtime-router/run.py harness audit \
  --target . --pretty
```

The registry is only a command/evidence contract shortcut. It does not supply
the target's provider/model catalog, quota, credentials, blacklist, policy, or
execution authority. A known harness is therefore not automatically a ready
target integration. Target configuration changes require approval; live
discovery and provider calls require their own approval. Preserve the
structured `INCOMPLETE`, `NO_ROUTE`, and adapter-error distinction rather than
editing policy or inventing evidence to make the preflight pass.

### Plan, refresh, and execution are separate

Keep these three operations distinct:

1. A normal route plan reads the existing target-owned profile, policy, and
   catalog cache. It must not refresh the cache as a side effect. If the cache
   is missing, stale, or unusable, report `NEEDS_REFRESH`/`INCOMPLETE` and stop.
2. A discovery or cache refresh is a live metadata operation. It may invoke a
   harness listing command, quota source, or network-backed adapter and may
   write target-owned evidence. It requires a separate, explicit approval and
   must be run through the bounded discovery command with an explicit cache
   output path. Some harness/provider listings legitimately take several
   minutes during a cold start; use the adapter's configured bounded deadline,
   not an ad-hoc 10–15 second shell timeout. Do not add `--refresh` to an
   ordinary plan command.
3. Worker execution is a third approval boundary. Review the completed route
   plan, then approve only the exact command/plan that will run. A discovery
   approval is not worker approval, and a worker approval must not be used as a
   substitute for a missing or failed discovery result.

Never remove an approval gate or edit a consumer adapter merely to make a
plan appear `READY`. If the adapter cannot refresh evidence independently of
execution, report that boundary as a target-adapter design issue and produce a
separate repair plan; do not silently combine discovery and launch.

When the active harness runs discovery asynchronously, a still-running job is
neither a timeout nor an unavailable provider. Start one bounded discovery
only, then observe that job's actual terminal result using the harness's normal
job-status mechanism. Do not infer completion from a short sleep, start a
duplicate refresh, or consume its cache while it is still running. If the
harness cannot show a terminal result, report the refresh as `INCOMPLETE`;
only the adapter's structured terminal report may establish usable evidence.

## Commands

All inputs are explicit JSON files. Keep catalogs, tasks, policies, and
packets free of credentials and full prompts.

Validate a candidate catalog:

```text
python .agents/.agent-runtime-router/run.py inventory \
  --catalog <catalog.json> --pretty
```

Select one eligible candidate:

```text
python .agents/.agent-runtime-router/run.py route \
  --task <task.json> --catalog <catalog.json> --policy <policy.json> --pretty
```

Create a provider-neutral dry-run plan:

```text
python .agents/.agent-runtime-router/run.py plan \
  --task <task.json> --catalog <catalog.json> --policy <policy.json> \
  --packet <packet.json> --pretty
```

## Advisory model recommendation; ARR-owned effort selection

When a user asks which model to use for a task, make a read-only
recommendation. The user's currently selected primary harness model remains
authoritative; this workflow does not switch it or launch a worker.

1. Build a task request from the stated capabilities, context, sensitivity,
   quality minimum, and other explicit constraints. Include `effort` only when
   the user explicitly requires a particular normalized level; otherwise leave
   it unset so ARR can choose.
2. Run the target-local `route` command against the complete target-owned
   catalog and policy, then report the selected candidate, billing/quota
   evidence, `selected_effort`, `selected_variant`, `selected_quality`, and
   the rejection reasons for plausible alternatives.
3. Let ARR choose effort as part of the `(model, effort)` decision. Effort is
   not a universal quality scale: a stronger model at low effort may beat a
   weaker model at maximum effort. ARR must compare effort-specific
   benchmark/AA evidence, cost, quota, and policy together. Never reuse
   `Candidate.quality` for a different effort when `effort_profiles` are
   present.
4. If the task explicitly requires an effort but no matching effort-specific
   evidence exists, report `NO_ROUTE` with the rejection reason. If no effort
   was requested and the catalog uses the legacy scalar `Candidate.quality`
   contract, a normal route may still be valid: report `selected_effort: null`
   and do not claim effort-specific evidence or a native effort mapping. Say
   `INCOMPLETE` only when the requested recommendation or launch requires
   effort-specific evidence that the target does not provide. Do not invent a
   Kilo/OpenCode/native variant mapping. A target adapter must map normalized
   effort (`minimal`, `low`, `medium`, `high`, `xhigh`, `max`) to the observed
   native option.

For a cost-effective advisory, include alternatives (for example cheaper,
stronger, free, or lower latency) and show cost ranges, quota/billing/TPS
evidence, freshness, unknowns, and rejection reasons. Do not turn raw scores
from different benchmarks into one number or hide uncertainty behind a
“best value” label. This is currently a skill-level read-only workflow over
`route` output. A dedicated provider-neutral `recommend` contract/CLI is
planned as roadmap Milestone 6.3C; do not claim that it exists, switch the
primary model, or launch a worker as part of a recommendation.

For subagents, use the same route-and-effort decision only when the target has
a workspace-aware ARR launcher. Otherwise explain that native harness
delegation may reuse the parent model and is not proof that ARR selected the
subagent route.

### Quality evidence and models outside AA coverage

Do not treat benchmark numbers as a universal scale. A score of `45` from one
benchmark is not comparable with `45` from another unless ARR has a separately
versioned, validated calibration. Preserve the source, benchmark/version,
metric/scale, exact model identity, effort, agent/harness context, freshness,
applicability, and mapping confidence with every quality observation.

Milestone 6.3A is still planned: the current v1 router may carry legacy
scalar quality fields, so this source precedence is the accepted target policy
and evidence contract, not a claim that every installed catalog enforces all
of these fields today. When an exact Artificial Analysis record exists, treat it as authoritative.
Alternate benchmark sources may fill coverage gaps for models AA does not
cover, but they never override or get averaged into an AA result. Use an
alternate score only for the task dimensions its source actually measures; a
coding-only result does not prove architecture or long-horizon agentic ability.
Unknown, stale, unmatched, or proxy evidence remains unknown for strict
profiles. Fuzzy model-name matching may create a diagnostic suggestion or a
target-local evaluation candidate, but it must not make another model's score
routeable.

Benchmark/source adapters are reusable and harness-neutral. A new harness
integration should provide route discovery, capabilities, effort/variant
mapping, and native launch semantics; it should not implement benchmark
parsing or cross-source score conversion. Before enabling alternate evidence,
produce a read-only coverage report and shadow-routing comparison. Read
`docs/quality-evidence.md` from the ARR source checkout for the complete policy
and implementation order; do not assume that source document exists in a
consumer target merely because this installed skill is present.

### Human decision gate for unknown evidence

Fail-closed automatic routing is still the default. The bounded human-selection
mode described here is planned (roadmap Milestone 6.3B); until a target exposes
and explicitly enables that contract, report `INCOMPLETE`/`NO_ROUTE` rather
than inventing a local override. Once implemented, it may request a bounded
provisional options report when candidates pass all known hard gates but are
blocked only by unknown billing, cost, quota, availability, or capability
evidence. Do not include known denials, pin failures, failed mandatory free-TPS,
health blocks, known capability/context failures, or other known hard
rejections.

Show each exact candidate/effort/variant, evidence source/tier, known facts,
unknown fields, and provisional ranking reasons. Do not launch after displaying
the list. Require the user to select one exact option, acknowledge each
unresolved risk, and set a spend cap when billing/cost is unknown. Record a
user claim as `USER_ATTESTED`, not verified provider evidence. The final
selection must be bound to the task, policy, candidate, effort, variant,
command/plan, workspace, limits, and attestations. Never use a blanket
“allow all unknowns” switch, silently retry another unknown route, or reuse a
selection after task/policy/evidence drift. The harness may render the options
and collect the choice; ARR owns validation and approval binding.

### Language-neutral task analysis

Do not classify tasks with English-only keywords or translate the user's prompt
just to route it. The active primary model may interpret the request in its
original language and emit language-neutral requirements (capabilities,
context, sensitivity, latency/cost constraints, and confidence) for ARR to
validate. It must not choose an effort merely from its own impression of task
difficulty, recommend an effort to the user, or ask the user to accept one:
omit `effort` unless the user explicitly requested it. ARR owns the
model-and-effort decision because AA/benchmark quality is specific to each
pair. ARR's `selected_effort` is a routing result, not an instruction or
recommendation for the user. ARR never treats the interpretation as authority to bypass policy,
invent provider evidence, or select a native command. If the model cannot
produce a valid structured proposal, preserve `INCOMPLETE`/`NO_ROUTE` or ask
the user for an explicit profile rather than guessing from language-specific
text. User-facing explanations should remain in the user's language when the
harness supports that behavior.

Exit status `0` means a usable result, `2` means no candidate was eligible,
and `64` means invalid input. Always inspect the JSON evidence, including every
candidate's rejection reasons and the ranking rule.

## Harness integration checks

When the target has an approved local harness integration, inspect its
secret-free profile and run an explicit bounded discovery source:

```text
python .agents/.agent-runtime-router/run.py harness profile \
  --target . --pretty
python .agents/.agent-runtime-router/run.py harness discover \
  --target . --config .agents/runtime-router/adapters/<id>/discovery.json \
  --pretty
python .agents/.agent-runtime-router/run.py harness verify \
  --target . --adapter <id> --dry-run --pretty
python .agents/.agent-runtime-router/run.py harness plan-adaptation \
  --target . --harness <new-id> \
  --output <temporary-adaptation-plan.json> --pretty
```

Discovery is no-mutation by default. Add `--cache-output <path>` only when the
approved workflow explicitly authorizes refreshing the target-local cache.
The command executes only the fixed argv or HTTPS allowlist declared by the
target adapter. Exit `3` means an adapter integration failure; it is distinct
from exit `2` for incomplete/no-route evidence.

For Kilo specifically, do not confuse Kilo's host-native aliases with ARR
catalog evidence. `kilo-auto/small` (or another `kilo-auto/*` label) can be an
internal Kilo title/helper session and is not proof that ARR selected that
model for a worker. ARR catalog candidates are the exact IDs emitted by the
target discovery cache, commonly `kilo/kilo-auto/<tier>`; inspect the
candidate's provider, billing, quota, freshness, capability, and rejection
evidence before describing it as a route. Never infer a catalog candidate
from the conversational model, a Kilo UI label, or a provider-policy include
pattern.

When a route is unavailable, perform one bounded diagnostic: record the
catalog status/count, the selected harness, and the most frequent rejection
reasons for the requested track. Distinguish missing/unusable evidence,
adapter failure, and a genuine `NO_ROUTE`. Do not loop on a model label, waive
`--free-only`, enable unknown quota/cost, or fall back to the harness's native
subagent tool without asking the user for one explicit decision. A short
manual probe is not evidence that a provider is unavailable: do not exclude a
provider merely because a diagnostic command exceeded an arbitrary timeout
that is shorter than the target adapter's configured deadline. Let the
bounded adapter report `timeout` at its own deadline, and preserve that
structured result.

To switch harnesses, review the emitted adaptation plan and apply only the
unchanged plan after separate approval:

```text
python .agents/.agent-runtime-router/run.py harness apply-adaptation \
  --target . --plan <temporary-adaptation-plan.json> --approve --pretty
```

The apply command promotes only the target-local verified profile/cache and
active pointer, preserves the previous adapter and routing denials, and writes
a digest-only receipt. Any drift requires a new plan.

For native launching, require the target adapter's `LaunchSpec` to pass
`bind_launch_spec()` before constructing an `ExecutionApproval`. This rejects
identity drift, environment overrides, and limit mismatches; the returned
`WorkerCommand.sha256` is the digest that approval must bind.

If a route plan reaches a `render_launch_input_rejected` adapter error, stop
routing retries. It means the target adapter constructed an invalid bounded
native command or prompt shape (for example, a control character in an argv
argument), not that a provider is unavailable. Keep the rejected value out of
reports, repair or reject the value in the target-owned adapter, add a
regression, then re-plan. This is a harness-neutral adapter-contract failure;
do not refresh evidence, relax routing policy, or switch to native delegation
as a workaround.

## Routing rules

- Denials and blacklist entries are hard constraints and override preferences,
  paid allowances, and availability claims.
- Unknown capability, context, availability, quota, freshness, and cost
  evidence remains rejected unless the corresponding policy switch explicitly
  permits that dimension.
- Never replace a pinned provider or model silently, infer mutable provider
  facts, or turn a dry-run plan into execution authority.
- If the result is `no route`, use the per-candidate reasons to identify the
  missing or denied evidence. Do not broaden the policy merely to make a route
  appear.
- A route decision is not a provider call or a worker launch. Keep target
  semantic adapter changes (catalog parsing, quota/TPS probes, native command
  construction) in the consumer project; the generic ARR skill may diagnose
  them but must not edit them while performing a read-only route or maintenance
  workflow.

## Cross-project acceptance evidence

When another repository is used to validate this router:

1. Record the repository, user-designated ref or commit, and exact source
   files for the catalog and policy.
2. Reconstruct the complete resolved policy, including provider and candidate
   denials, then check both a permitted route and every relevant denied route.
3. Label reduced or hand-built inputs `SYNTHETIC`; they are useful for core
   regression tests but are not proof of consumer integration.
4. Keep consumer-specific configuration, prompts, fixtures, inventories, and
   migration plans outside this router checkout.

If any required source or denial evidence is incomplete, report the result as
`INCONCLUSIVE` and stop the compatibility claim rather than filling gaps from
memory or a partial fixture.

## Worker boundary

The installed package also exposes an explicit local-worker API. A worker start
requires a one-shot task-, candidate-, and absolute-command-bound approval and
remains local, argv-only, bounded, cancellable, and observable. Do not invoke it from a
routing request unless the user separately authorizes the specific local
command. Read-only multi-track workers receive a temporary snapshot containing
only regular, permitted target files. Symbolic links are omitted rather than
followed, so a worker can never receive bytes from outside the target through a
link. A target harness's instruction/configuration list must therefore point to
regular in-target files needed by workers, not a compatibility symlink. If an
expected path is absent from a read-only worker snapshot, inspect and repair
that target-owned configuration; never copy the link target manually or weaken
the snapshot boundary. Provider authentication, network dispatch, retries,
worktrees, and recovery remain outside this installed skill.
