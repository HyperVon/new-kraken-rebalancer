---
name: coroutines-flows-sse
description: >-
  Kotlin Flow architecture — hot SharedFlow vs cold Flow, ConfigService config
  flow, TradeHistoryService snapshotFlow, SSE /api/status/stream, and
  collectLatest restart of the rebalance loop. Use when changing reactive
  pipelines, SSE, or PortfolioManager config watching. See docs/FLOWS.md.
---

# Coroutines, Flows & SSE

Canonical doc: [`docs/FLOWS.md`](../../../docs/FLOWS.md).

## Hot vs cold

| | Cold `Flow` | Hot `SharedFlow` |
| :--- | :--- | :--- |
| Starts | On collect | Independent of collectors |
| Use here | Paginated trade sync, USD poll | Config changes, snapshot broadcast |

## Key pipelines

### Config (hot)

`ConfigServiceImpl._configFlow` — `MutableSharedFlow<Settings>(replay=1, DROP_OLDEST)`.

`PortfolioManagerImpl` uses **`collectLatest`** on `watchConfigChanges()` so a
settings change cancels the sleeping `delay()` and **restarts** the rebalance
loop immediately.

### Snapshots → SSE (hot)

`TradeHistoryServiceImpl.snapshotFlow` — buffer 16, `DROP_OLDEST`.

Path: rebalance cycle → `addSnapshot` → DB + `tryEmit` →
`DashboardController` / routes → **`GET /api/status/stream`** → browser
`EventSource`.

On connect, send latest snapshot from DB, then collect the SharedFlow.

### Paginated sync / USD poll (cold)

- `getTradeHistoryPaginated()` — `emit` suspends for backpressure.
- `pollUsdBalanceAfterSells()` — cold poll until stable / attempts exhausted.

## Concurrency rules

- DB/network: `withContext(Dispatchers.IO)`.
- No `GlobalScope` — use component-bound scopes.
- Prefer `tryEmit` on DROP_OLDEST SharedFlows (non-suspending broadcast).

## Checklist

- [ ] Hot vs cold choice matches `docs/FLOWS.md`
- [ ] Config watch uses `collectLatest`
- [ ] SSE endpoint remains `/api/status/stream`
- [ ] Flow tests use `runTest` + `advanceUntilIdle`
