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

For subagents, use the same route-and-effort decision only when the target has
a workspace-aware ARR launcher. Otherwise explain that native harness
delegation may reuse the parent model and is not proof that ARR selected the
subagent route.

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
command. Provider authentication, network dispatch, retries, worktrees, and
recovery remain outside this installed skill.
