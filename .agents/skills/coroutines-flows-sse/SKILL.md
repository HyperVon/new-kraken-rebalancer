---
name: coroutines-flows-sse
description: >-
  Kotlin Flow architecture — hot SharedFlow vs cold Flow, ConfigService config
  flow, TradeHistorySnapshotStore snapshotFlow (via TradeHistoryService façade),
  SSE /api/status/stream, and collectLatest restart of the rebalance loop. Use
  when changing reactive pipelines, SSE, or PortfolioManager config watching.
  See docs/FLOWS.md.
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

`TradeHistorySnapshotStore.snapshotFlow` — `replay=1`, buffer 16, `DROP_OLDEST`.
`TradeHistoryServiceImpl` is a thin façade: `addSnapshot` / `getHistoryFlow()`
delegate to the store.

Path: rebalance cycle → façade `addSnapshot` → DB + store `tryEmit` →
`DashboardController` collects `getHistoryFlow()` → **`GET /api/status/stream`**
→ browser `EventSource`.

On connect, send latest snapshot from DB, then collect the SharedFlow (replay
covers a snapshot emitted between the DB read and subscribe).

### Paginated sync / USD settle (cold)

- `TradeHistorySyncService.getTradeHistoryPaginated()` — `emit` suspends for
  backpressure (invoked from the façade `syncTradesFromKraken()`).
- `settleUsdAfterSells()` — only when **≥1 sell succeeded** and **not** dry-run:
  - **Primary:** `pollFillConfirmedUsd()` → `sumMatchedSellProceeds()` (history
    matched by sell `ordertxid`, **net of fee**, up to 5×50 pages) → balance peek
    `min(fill, balance)` when spendable USD is visible, else
    `min(fill, projectedCash)`.
  - **Fallback:** `refreshUsdBalanceAfterSells()` →
    `pollUsdBalanceAfterSells().last()` when no txids or fill confirm is empty.
  Both cold polls: **3** attempts from **250ms** (doubling; defensive
  `coerceAtMost(32s)`); emit best positive observation (or `0`); executor aborts
  buys when none. Skipped poll → buys use projected cash.

## Concurrency rules

- DB/network: `withContext(Dispatchers.IO)`.
- No `GlobalScope` — use component-bound scopes.
- Prefer `tryEmit` on DROP_OLDEST SharedFlows (non-suspending broadcast).

## Checklist

- [ ] Hot vs cold choice matches `docs/FLOWS.md`
- [ ] If FLOWS Mermaid changed → run
      [validate_mermaid.py](../documentation-review/scripts/validate_mermaid.py)
- [ ] Config watch uses `collectLatest`
- [ ] SSE endpoint remains `/api/status/stream`
- [ ] Flow tests use `runTest` + `advanceUntilIdle`
