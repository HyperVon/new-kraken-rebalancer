---
name: coroutines-flows-sse
description: >-
  Kotlin Flow architecture for saved Settings, the latest current-cycle
  Dashboard snapshot, SSE, bounded Actual observation capture, and
  order-specific USD sell settlement. Use when changing reactive pipelines,
  startup, SSE, or PortfolioManager config watching. See docs/FLOWS.md.
---

# Coroutines, Flows & SSE

Canonical runtime map: [`docs/FLOWS.md`](../../../docs/FLOWS.md).

## Active flow types

| Type | Active use | Behavior |
| :--- | :--- | :--- |
| Cold `Flow` | Exchange retries and sell-settlement polling | Starts its request/poll work when collected. |
| Hot `SharedFlow` | Saved Settings | Replays current Settings and publishes later updates. |
| Hot `StateFlow` | `CurrentPortfolioState` | Retains only the latest completed cycle snapshot, or `null` before the first cycle. |
| Bounded `Channel` | `ActualObservationDispatcher` | Passes eligible direct observation candidates to an off-cycle writer; a full queue can skip a sample. |

Retrospective trade/ledger synchronization, report reconstruction, and
historical reporting flows are retired. The legacy database remains available
only for safe migration and account-history continuity checks during binding;
it is not an active reporting destination. Order-specific Kraken
`TradesHistory` reads used to confirm sells are still part of execution safety.

## Settings changes and the rebalance loop

`ConfigServiceImpl.watchConfigChanges()` exposes a replaying hot
`SharedFlow<Settings>`. `PortfolioManagerImpl` collects it with `collectLatest`:
a change replaces the idle delay and starts the next cycle with the new
Settings.

Settings updates saved during an execution session are staged and emitted
after its outermost `endExecutionSession()`. The active cycle keeps its pinned
configuration and exchange backend, then the loop uses the latest published
Settings. Preserve cancellation as control flow: `collectLatest`, the cycle,
and the idle delay must rethrow `CancellationException` so shutdown and loop
restart work correctly.

## Current-cycle snapshots and SSE

`PortfolioManagerImpl` publishes each completed snapshot to
`CurrentPortfolioState`, an in-memory `StateFlow` holding one latest value.
The Dashboard fragment reads that value; `/api/status/stream` collects it and
sends current-cycle snapshot JSON through Ktor SSE. HTMX's SSE extension
refreshes the Dashboard fragment. `:frontend-js` updates age and freshness but
does not open `EventSource` or parse the stream.

The server-side stream reports current cycle/snapshot state and unresolved
order-intent status through the relevant endpoints. Do not persist or rebuild a
historical cycle timeline for Dashboard, startup, health, or readiness.
Per-client SSE failures stay local to that session, and cancellation must
propagate.

## Actual observations

At startup, the application starts `ActualObservationDispatcher` and the
portfolio manager. Eligible direct balance and price evidence from a completed
live cycle is placed on the dispatcher's bounded channel. A worker verifies
account binding and credential scope, then appends the raw observation to the
Actual store outside the order path. Simulation does not create authenticated
Actual observations. Queue, binding, or storage problems can skip a sample
without blocking rebalancing.

`/actual` reads raw evidence and gaps for the current account/configured asset
scope. An optional prospective Buy & Hold segment can start from eligible
complete evidence after continuity verification and proceeds forward. It does
not require or trigger retrospective reconstruction. Preserve cancellation in
the dispatcher worker and benchmark evaluation.

## Cold USD sell settlement

Settlement policy and safety invariants belong to
[portfolio-rebalancing-math](../portfolio-rebalancing-math/SKILL.md). The cold
poll implementation lives in `OrderSettleHelper` and runs after successful
sells, outside dry-run:

1. When Kraken returned sell order transaction IDs, poll `TradesHistory` for
   successful matching sell fills and sum net proceeds after fees. Scan up to
   five pages and deduplicate repeated nonblank trade IDs.
2. Cap confirmed proceeds to spendable USD when visible; if that read fails,
   cap to projected cash. Accept the result only at or above 95% of projected
   cash.
3. If fills are missing or materially short, or IDs are unavailable, poll the
   spendable USD balance as a fallback.
4. Each cold poll is bounded to three attempts with exponential delay starting
   at 250 ms. If safe USD cannot be confirmed, the executor stops buys for the
   cycle.

These reads only confirm settlement for the current cycle's sell orders. They
must not be turned into an account-history sync or reporting path.

## Concurrency rules

- Use `withContext(Dispatchers.IO)` for blocking database or network work.
- Do not use `GlobalScope`; use component-bound application or request scopes.
- Keep the Actual queue bounded and non-blocking for the rebalance cycle.
- Catch ordinary client/session failures locally where appropriate, but always
  rethrow `CancellationException`.

## Checklist

- [ ] Saved Settings use the replaying hot flow and `collectLatest`
- [ ] Execution sessions defer Settings publication until the cycle completes
- [ ] Dashboard state retains only the latest snapshot and SSE carries that state
- [ ] Actual capture is bounded, direct-evidence based, and off the order path
- [ ] Prospective Buy & Hold requires verified continuity and moves forward only
- [ ] Sell fill checks remain scoped to returned order IDs; no history sync returns
- [ ] Cold settlement polls remain bounded and stop buys when cash is uncertain
- [ ] SSE, worker, and poll cancellation continues to propagate
- [ ] `docs/FLOWS.md` remains the canonical runtime diagram and path description
