# ARR-backed Kilo routing

Kraken uses Agent Runtime Router (ARR) as its only routing and worker
supervision implementation. Kilo is a harness adapter; it is not the routing
policy and it is not the source of provider eligibility decisions.

## Ownership

- `.agents/runtime-router/policy.json` owns ARR policy switches and the target
  blacklist boundary.
- `.agents/runtime-router/adapters/kilo/provider-policy.json` owns the enabled
  providers, include rules, billing/free rules, model overrides, the optional
  quota source settings, and legacy blacklist fixture. Free-TPS thresholds and
  probe/cache bounds remain in the ARR target policy at
  `.agents/runtime-router/policy.json`.
- `.agents/runtime-router/adapters/kilo/profiles.json` owns the eight Kraken
  task profiles and their primary/secondary quality thresholds, context and
  output estimates, reasoning requirements, and native variant preferences.
- `.agents/runtime-router/adapters/kilo/catalog.py` and `quota.py`, plus the
  TPS/readiness orchestration in `run_arr_task.py`, are target adapters. They
  return ARR contracts and never rank candidates or persist credentials/raw
  provider output.
- `.agents/runtime-router/adapters/kilo/benchmarks.py` is the target-owned
  quality bridge. It may use `ARTIFICIAL_ANALYSIS_API_KEY` when configured and
  otherwise falls back to the public OpenRouter model feed. It persists only
  bounded numeric quality evidence in the Kilo namespace; raw responses,
  authorization headers, and model descriptions are discarded.
- ARR owns eligibility, ranking, effort selection, bounded launch, reports,
  failover rules, and namespaced evidence semantics.

## First checkout / refresh

Read the ARR `bootstrap-runtime-router` skill and review its read-only plan.
After explicit approval, apply the plan. The receipt-managed runtime is
`.agents/.agent-runtime-router/`; target evidence is generated under
`.agents/runtime-router/harnesses/kilo/` and is ignored by Git.

After pulling a newer ARR or target revision, use the maintenance skill's
plan/apply flow. Do not copy an old virtual environment or catalog between
worktrees. Re-run `harness audit` and the adapter tests after refresh.

### Routine Kilo CLI upgrades

The Kilo adapter accepts patch releases in the reviewed `7.4` series beginning
with `7.4.21`. After a routine Kilo patch upgrade, regenerate only the ignored,
machine-local Kilo metadata:

```bash
python3 .agents/.agent-runtime-router/run.py --python \
  .agents/runtime-router/adapters/kilo/gen_discovery.py
python3 .agents/.agent-runtime-router/run.py --python \
  .agents/runtime-router/adapters/kilo/test_adapter.py
python3 .agents/.agent-runtime-router/run.py harness audit --target . --pretty
```

Generation performs bounded local `--version`, `run --help`, and `models
--help` checks only. It does not list models, read credentials, contact a
provider, or refresh a catalog. A fresh catalog remains usable until its normal
TTL expires; a stale catalog still follows the separate approved discovery
path. A new Kilo minor or major release, or a changed required help flag, fails
closed with a compatibility error: update and verify the target adapter before
routing rather than pinning or bypassing the check.

## Routing commands

```bash
# Plan only; no worker is started.
python3 .agents/.agent-runtime-router/run.py --python \
  .agents/runtime-router/adapters/kilo/run_arr_task.py \
  --profile routine "Inspect the requested source change"

# Explicitly approve discovery/TPS evidence and the worker launch.
python3 .agents/.agent-runtime-router/run.py --python \
  .agents/runtime-router/adapters/kilo/run_arr_task.py \
  --profile routine --approve "Run the approved low-cost smoke task"

# Bounded read-only workflow; add --approve only after reviewing its plan.
python3 .agents/.agent-runtime-router/run.py --python \
  .agents/runtime-router/adapters/kilo/route_subagents.py \
  --manifest .agents/runtime-router/adapters/kilo/manifest.local \
  --free-only \
  "Review the requested change"
```

`./route-kilo` is a convenience wrapper for the first command. It fails closed
when the receipt-managed runtime is absent. A direct native Kilo subagent call
does not prove ARR routing; the structured ARR plan/report must show the route,
normalized effort, billing class, evidence source, and worker status.

## Evidence behavior

- Kilo model listing is bounded and redacted. Failed or malformed discovery
  never creates a usable catalog cache.
- A real Kilo model listing can take several minutes during a cold start or
  while a provider backend responds slowly. Use the generated discovery
  contract's multi-minute deadline; do not wrap it in a shorter shell timeout,
  truncate it with `head`, or classify a provider as unavailable after a
  10–15 second spot check. The adapter still has one finite overall deadline
  and a finite per-provider deadline, so it will eventually return structured
  timeout evidence.
- **Kilo Code only:** if its shell tool reports the discovery as an active
  background job, it is still running. Start one bounded command, use Kilo's
  job-status result to obtain its terminal JSON, and do not start a duplicate
  discovery or read the cache before then. A short sleep is not a timeout;
  only the adapter's terminal report or its configured 900-second deadline can
  establish a discovery outcome.
- **Kilo Code only:** ARR read-only workers receive a temporary snapshot of
  regular target files and deliberately omit symbolic links rather than
  following them. Kilo's tracked `.kilo/shell-strategy.md` compatibility link
  is therefore not part of a worker snapshot; `.kilo/kilo.json` must reference
  the canonical regular `.opencode/shell-strategy.md` file instead. Do not
  manually copy a link target into a worker or weaken the generic ARR snapshot
  boundary to work around a missing instruction path.
- The Kilo refresh command must pass `--cache-ttl 7200`, matching the
  target-owned policy. ARR's generic five-minute default is intentionally
  conservative and is too short for this target's catalog/TPS/readiness
  preparation sequence.
- A successful catalog alone does not make a free route eligible: Kraken's
  target policy also requires fresh TPS and tool-readiness evidence. When an
  ordinary free-only plan reports `NO_ROUTE` for those missing observations,
  request a separate evidence-only approval and run
  `run_arr_task.py --prepare-evidence --approve <task>` (or the corresponding
  `route_subagents.py` workflow command). This runs only bounded evidence
  probes and returns `EVIDENCE_READY`; it never launches the requested worker.
  It deliberately rejects `--refresh`, keeping Kilo model discovery a separate
  approval. Re-run plan mode and review the result before separately approving
  a worker launch.
- OpenCode quota data is optional. A missing plugin leaves free routing usable;
  paid quota remains unknown until a fresh, identity-bound plugin result exists.
- Free candidates always require a fresh cached TPS measurement at or above the
  target minimum (20 tokens/sec by default). Paid, subscription,
  account-priced, and positive-balance PAYG candidates are not TPS-probed.
- Free routes rank first, then subscription/account-priced routes, then PAYG;
  a PAYG route is eligible only with an explicit positive balance.
- Artificial Analysis quality is primary when its target-owned key is present;
  OpenRouter benchmark values are an explicit, visibly lower-confidence
  fallback. Quality evidence is cached for the configured 24-hour window and
  never makes an unknown score look verified.
- Unknown, stale, contradictory, denied, or wrong-harness evidence is reported
  structurally and cannot silently become a route.

## Removal and acceptance gate

The pre-ARR router was preserved in Git history at
`new-kraken-rebalancer@7e94c39` for comparison only. It is no longer present in
the working tree. Before changing provider policy, update the target fixtures
and the deterministic parity tests first. For a live acceptance run, use cheap
models only and record the redacted report; never commit catalog, quota, TPS,
health, quality cache, credentials, prompts, or provider output.

The old router's eight profile names remain target-owned and are represented in
`profiles.json`; the workflow registry also carries the legacy review,
comprehensive-quality-overhaul, quality, optimization, dependency, and
skill-audit presets. The parity tests
must remain offline. A real acceptance run is separate and must use a
disposable worktree plus explicit `--approve`.

For a registered read-only workflow, use the receipt-managed runner in plan
mode first, then add `--approve` only after reviewing every route:

```bash
python3 .agents/.agent-runtime-router/run.py --python \
  .agents/runtime-router/adapters/kilo/route_subagents.py \
  --workflow comprehensive-quality-overhaul --free-only \
  --task "<parent request>"
```

Missing or stale catalog evidence is reported as `INCOMPLETE`; it is not a
reason to replace ARR with Kilo's native same-model subagents. Use the
maintenance/bootstrap skills when the receipt-managed runtime itself is absent.
The default registered workflow uses ARR's temporary read-only snapshots and
returns its audit through terminal worker results; it does not create five
worktrees, heartbeats, or coordination files merely to show intermediate
progress.
