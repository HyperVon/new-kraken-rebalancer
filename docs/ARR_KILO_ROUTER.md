# ARR-backed Kilo routing

Kraken uses Agent Runtime Router (ARR) as its only routing and worker
supervision implementation. Kilo is a harness adapter; it is not the routing
policy and it is not the source of provider eligibility decisions.

## Ownership

- `.agents/runtime-router/policy.json` owns ARR policy switches and the target
  blacklist boundary.
- `.agents/runtime-router/adapters/kilo/provider-policy.json` owns the enabled
  providers, include rules, billing/free rules, model overrides, quota/TPS
  source settings, and legacy blacklist fixture.
- `.agents/runtime-router/adapters/kilo/profiles.json` owns the eight Kraken
  task profiles and their primary/secondary quality thresholds, context and
  output estimates, reasoning requirements, and native variant preferences.
- `.agents/runtime-router/adapters/kilo/catalog.py`, `quota.py`, and `tps.py`
  are target adapters. They return ARR contracts and never rank candidates or
  persist credentials/raw provider output.
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
python3 .agents/runtime-router/adapters/kilo/route_subagents.py \
  --manifest .agents/runtime-router/adapters/kilo/manifest.local \
  "Review the requested change"
```

`./route-kilo` is a convenience wrapper for the first command. It fails closed
when the receipt-managed runtime is absent. A direct native Kilo subagent call
does not prove ARR routing; the structured ARR plan/report must show the route,
normalized effort, billing class, evidence source, and worker status.

## Evidence behavior

- Kilo model listing is bounded and redacted. Failed or malformed discovery
  never creates a usable catalog cache.
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
quality, optimization, dependency, and skill-audit presets. The parity tests
must remain offline. A real acceptance run is separate and must use a
disposable worktree plus explicit `--approve`.
