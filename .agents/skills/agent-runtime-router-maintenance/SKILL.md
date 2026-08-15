---
name: agent-runtime-router-maintenance
description: >-
  Audit, refresh, or repair an Agent Runtime Router installation using its
  receipt, ignored source locator, and current local checkout. Use when the
  router needs updating, its source path changed, an installation is missing
  or drifted, or a target project's router setup should be verified.
---

# Agent Runtime Router Maintenance

Use this skill after bootstrap. It owns the installed router's source
resolution, receipt-aware refresh, route validation, and drift reporting. It
does not silently overwrite locally modified skills or runtime state.

## Resolve the source

Run the bundled resolver from the target repository:

```text
python .agents/skills/agent-runtime-router-maintenance/scripts/resolve_source.py \
  resolve --target .
```

Resolution order is:

1. an explicit `--router-root` for this invocation;
2. `AGENT_RUNTIME_ROUTER_ROOT` in the environment;
3. the ignored `.agents/.agent-runtime-router/source.json` locator;
4. a validated adjacent `agent-runtime-router` sibling.

If none resolves, stop and ask for the checkout path. Do not search unrelated
directories or fetch a replacement.

## Audit and refresh

1. Read target guidance and the current receipt at
   `.agents/.agent-runtime-router/receipt.json`.
2. Validate the current installation:

   ```text
   python .agents/skills/agent-runtime-router-maintenance/scripts/validate_installation.py \
     --target .
   ```

3. Generate a new read-only plan from the resolved source:

   ```text
   python <router-root>/.agents/skills/bootstrap-runtime-router/scripts/install_runtime.py \
     plan --router-root <router-root> --target . --output <temporary-plan.json>
   ```

Use the same Python interpreter for the plan and apply commands. For an
existing installation, prefer the target-local interpreter under
`.agents/.agent-runtime-router/venv/` when it is available; mixing a global
Python with a target runtime can produce an incompatible site-packages path.

1. Review source revision, package digest, skill statuses, route changes, and
   conflicts. Obtain explicit approval for that exact plan.
2. Apply only the unchanged approved plan:

   ```text
   python <router-root>/.agents/skills/bootstrap-runtime-router/scripts/install_runtime.py \
     apply --router-root <router-root> --target . \
     --plan <temporary-plan.json> --approve
   ```

If a receipt-owned skill is `CONFLICT`, do not use `cp`, `rsync`, an editor,
or a direct overwrite to bypass the installer. Show the local/source digest
and ask whether the user wants that specific skill replaced by the upstream
version. After explicit approval, regenerate the plan with the approved skill
named explicitly:

```text
python <router-root>/.agents/skills/bootstrap-runtime-router/scripts/install_runtime.py \
  plan --router-root <router-root> --target . \
  --accept-upstream-skill agent-runtime-router \
  --output <temporary-plan.json>
```

The resulting plan must show `UPDATE (ACCEPT_UPSTREAM)` for only the approved
skill. Review that exact plan and apply it with `--approve`; the replacement is
staged and rolled back atomically if any precondition or validation fails.
Never approve an upstream resolution for a runtime, route block, source
locator, policy, catalog, adapter, or credential file through this option.

## Bound the read-only investigation

When a harness is asked to plan a first adoption, keep the investigation
bounded: spend at most 30 minutes and 40 tool calls on inventory, profiling,
and plan generation. Inspect the repository guidance and explicitly named
router/configuration files only. Do not recursively inspect dependency,
generated, cache, or runtime trees. At minimum exclude `.git`,
`.agents/.agent-runtime-router`, `.agents/runtime-router`, `.kilo/node_modules`,
`node_modules`, `.venv`, `venv`, `build`, `dist`, `target`, `.gradle`, `.local`,
`coverage`, `.idea`, `.cursor`, and `.vscode`. Prefer `git ls-files` and bounded
`rg --glob` queries; never use unbounded recursive listings.

Do not run `kilo models`, a quota plugin, a provider probe, or a worker during
the read-only phase unless that exact probe is separately approved. If the
budget expires or evidence remains incomplete, stop and report `INCONCLUSIVE`
or `BLOCKED` with the missing evidence. Do not continue exploring, invent
configuration, or edit the target to make the plan appear complete.

## Harness audit and refresh

If the target has a target-owned harness profile and adapter configuration,
inspect them after the mechanical installation refresh:

```text
python .agents/.agent-runtime-router/run.py harness profile \
  --target . --pretty
python .agents/.agent-runtime-router/run.py harness discover \
  --target . --config .agents/runtime-router/adapters/<id>/discovery.json \
  --pretty
python .agents/.agent-runtime-router/run.py harness verify \
  --target . --adapter <id> --dry-run --pretty
python .agents/.agent-runtime-router/run.py harness audit \
  --target . --pretty
python .agents/.agent-runtime-router/run.py harness plan-adaptation \
  --target . --harness <new-id> \
  --output <temporary-adaptation-plan.json> --pretty
```

These commands do not switch harnesses or mutate policy. A cache refresh must
be separately approved and explicitly use `--cache-output`; adapter failures
remain distinct from a no-route result. Review a `READY_FOR_REVIEW` adaptation
plan, then apply only that unchanged plan with a separate approval:

```text
python .agents/.agent-runtime-router/run.py harness apply-adaptation \
  --target . --plan <temporary-adaptation-plan.json> --approve --pretty
```

The apply step promotes only the requested verified profile/cache and active
pointer, preserves the previous adapter and target denials, records a
secret-free receipt, and fails closed on any drift. Semantic adapter changes
still require target-local edits and a new discovery/verification plan.

Do not use a route-plan command with `--refresh` to “make the plan work.” A
normal plan is read-only and may consume an existing cache only. If the cache
is absent, stale, or unusable, stop with `NEEDS_REFRESH`/`INCOMPLETE`, obtain a
separate discovery approval, and run the bounded `harness discover` command
with an explicit target-local `--cache-output`. Discovery may take several
minutes for a cold-starting or network-backed harness; do not wrap it in a
shorter ad-hoc timeout, truncate its output with a pipeline, or classify a
provider as unavailable before the configured adapter deadline. Re-run the
route plan without refresh after discovery. Discovery approval never
authorizes a worker, and worker approval never authorizes an unplanned
discovery.

If the harness executes discovery in the background, treat its live job status
as the authority: one job that is still running has not failed merely because a
short observation window elapsed. Do not launch another refresh, read a
partially written cache, or synthesize a timeout from a sleep/poll interval.
Wait for the configured bounded deadline or the harness's terminal result. If
the harness cannot supply that result, retain `INCOMPLETE` rather than guessing
whether the provider is unavailable.

If a target adapter needs a semantic repair (for example, Kilo table parsing,
quota/TPS evidence, or native launch binding), do not patch that adapter from
this generic maintenance workflow. Report the exact adapter failure and the
smallest target-owned repair plan, then verify the repaired adapter and refresh
evidence under their own approvals.

`harness verify --dry-run` checks the active profile and target-owned
`adapters/<id>/adapter.json` plus `discovery.json` metadata. It does not import
or execute target adapter code.

Use `harness audit` as the evidence snapshot for active harness, policy digest,
blacklist/task-profile counts, cache freshness, source digest, and fallback
state. It is read-only and prints no candidate contents.
Use `plan-adaptation` before a harness switch; it preserves the current adapter
and policy, and reports `INCOMPLETE` when the requested adapter lacks a
verified profile or fresh cache. Do not switch the active pointer as part of a
mechanical maintenance refresh.

To inventory a semantic harness adaptation without changing the target, resolve
the source and run the bootstrap helper's separate plan phase:

```text
python <router-root>/.agents/skills/bootstrap-runtime-router/scripts/install_runtime.py \
  integration-plan --router-root <router-root> --target . \
  --output <temporary-integration-plan.json>
```

Treat `INCOMPLETE` as an explicit missing-profile/adapter decision, not as
permission to infer providers or copy a catalog.

The helper refreshes only receipt-owned content whose current digest still
matches the prior receipt. A locally changed skill, route block, source
locator, runtime package, or runtime runner is a conflict and must be resolved
explicitly. A plan may also report `RECONCILE` for receipt metadata when the
managed files are already current but the receipt is stale; this is metadata
repair, not permission to overwrite local content.

## Boundaries

- Never read or copy credentials, provider configuration secrets, prompts, or
  runtime data.
- Never install globally, modify the target's application dependency files, or
  contact package indexes or providers.
- Do not delete or silently repair a divergent installation. Report the exact
  conflict and stop.
- After refresh, verify the receipt, route, skill manifests, source locator,
  and `python .agents/.agent-runtime-router/run.py --version`. Also report
  `git status --short` (names only), `git diff --check`, which tracked files
  changed, and that the ignored runtime files are intentionally absent from
  the change list. Do not commit automatically; tell the user whether a
  normal source-control commit is appropriate.

## Reload the coding harness after a refresh

The installed ARR runner and package are replaced by the approved refresh, so
commands invoked through `.agents/.agent-runtime-router/run.py` use the new
runtime immediately. A long-lived coding-harness session may nevertheless
retain the old project instructions or skill text in its current context.
After a successful refresh, close and reopen the harness TUI from the target
repository, or start a new session, before relying on changed guidance. This
is the safest portable behavior; a harness-specific reload command may be
used only when its contract explicitly proves that project instructions and
skills are reloaded. Do not treat an existing conversation's cached context
as evidence that the new guidance is active.
