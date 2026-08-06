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

## How this differs from nearby skills

| Skill | Role |
| :--- | :--- |
| **coroutines-flows-sse** (this) | Hot vs cold Flow, SSE path, config `collectLatest`, paginated sync and USD-settle **poll mechanics** |
| [portfolio-rebalancing-math](../portfolio-rebalancing-math/SKILL.md) | Rebalance math + execution **safety invariants** (sell-first, settle policy, 99% budget) |
| [trade-history-sync](../trade-history-sync/SKILL.md) | Trade/ledger sync, dedupe/seeding, and rewards queries |
| [frontend-js-development](../frontend-js-development/SKILL.md) | Stream-chip age / chart rebind after HTMX SSE fragment swaps — **not** `EventSource` |
| [koin-di-and-config](../koin-di-and-config/SKILL.md) | ConfigService / Settings persistence (not Flow restart semantics) |

## Hot vs cold

| | Cold `Flow` | Hot `SharedFlow` |
| :--- | :--- | :--- |
| Starts | On collect | Independent of collectors |
| Use here | Paginated trade/ledger sync, USD poll | Config changes, snapshot broadcast |

## Key pipelines

### Config (hot)

`ConfigServiceImpl._configFlow` — `MutableSharedFlow<Settings>(replay=1, DROP_OLDEST)`.

`PortfolioManagerImpl` uses **`collectLatest`** on `watchConfigChanges()` so a
settings change cancels the sleeping `delay()` and **restarts** the rebalance
loop immediately. During `beginExecutionSession()` … `endExecutionSession()`,
`ConfigServiceImpl` stages saved/reloaded config and emits only when the
outermost session exits, so an active cycle or paginated account sync is never
cancelled into a mixed settings/credential version.

### Snapshots → SSE (hot)

`TradeHistorySnapshotStore.snapshotFlow` — `replay=1`, buffer 16, `DROP_OLDEST`.
`TradeHistoryServiceImpl` is a thin façade: `addSnapshot` / `getHistoryFlow()`
delegate to the store.

Path: rebalance cycle → façade `addSnapshot` → DB + store `tryEmit` →
`DashboardController` collects `getHistoryFlow()` → **`GET /api/status/stream`**
→ browser via **HTMX SSE** (`sse-connect` / `sse:message` fragment refresh).
`:frontend-js` does **not** open `EventSource`.

`handleSseStream` sends the DB latest snapshot, then collects the hot flow
(`replay = 1` covers the subscribe race; a duplicate first event is acceptable).

- `snapshotFlow`: `extraBufferCapacity = 16`, `DROP_OLDEST`, `tryEmit` — slow
  clients must not block rebalance producers.
- Per-session SSE errors are swallowed (non-cancellation); other subscribers continue.

### Paginated sync / USD settle (cold)

Owner of **settle policy** (when to settle, fail-closed buys, 99% budget):
[portfolio-rebalancing-math](../portfolio-rebalancing-math/SKILL.md). This
section owns the **cold Flow poll implementation**.

- `TradeHistorySyncService` paginated Kraken history fetch (private cold
  `getTradeHistoryPaginated()`; invoked from the façade
  `syncTradesFromKraken()`). One coroutine `Mutex` spans the throttle check and
  pagination so concurrent top-level sync callers cannot overlap.
- `LedgersSyncService` paginated Kraken ledger fetch (private cold
  `getLedgersPaginated()`; invoked from `syncLedgersFromKraken()`). It uses the
  same 300s throttle/session boundary, but inserts pages under a unique ledger
  identity and persists separate seed progress and watermark metadata.
- `settleUsdAfterSells()` — only when **≥1 sell succeeded** and **not** dry-run:
  - **Primary:** `pollFillConfirmedUsd()` → `sumMatchedSellProceeds()` (history
    matched by sell `orderTxid`, **net of fee**, up to 5×50 pages) → balance peek
    `min(fill, balance)` when spendable USD is visible, else
    `min(fill, projectedCash)`.
  - **Fallback:** `pollUsdBalanceAfterSells()` →
    `pollUsdBalanceAfterSells().last()` when no txids or fill confirm is empty.
  Both cold polls: **3** attempts from **250ms** (doubling; defensive
  `coerceAtMost(32s)`); emit best positive observation (or `0`); executor aborts
  buys when none. Skipped poll → buys use projected cash.

Fill-confirm poll constants (`pollFillConfirmedUsd` / `sumMatchedSellProceeds`):

- `pollFillConfirmedUsd`: 3 attempts, 250ms doubling backoff (cap 32s), 95%
  early accept vs `projectedCash`, `startSec = now − 600s`.
- `sumMatchedSellProceeds`: up to 5 pages × 50 rows; match sell `orderTxid`;
  net `usdAmount − fee`; keep scanning after the first sighting (multi-leg fills).
  Deduplicate repeated nonblank Kraken trade IDs across shifted pages, but keep
  id-less rows distinct.
- Cap fill-confirmed USD with `min(fill, balancePeek)` when spendable balance is
  visible; otherwise `min(fill, projectedCash)` (same as the primary bullets
  above — never both caps at once).

## Concurrency rules

- DB/network: `withContext(Dispatchers.IO)`.
- No `GlobalScope` — use component-bound scopes.
- Prefer `tryEmit` on DROP_OLDEST SharedFlows (non-suspending broadcast).

### Cancellation is control flow (not a cycle error)

- In `PortfolioManagerImpl.runLoop`, rethrow `CancellationException` from the
  outer `collectLatest`, the inner cycle, and `delay()` — never log-and-continue.
- Catching cancellation inside `while (isRunning)` prevents `collectLatest` from
  cancelling the sleeping delay on a settings change.
- Same rule for SSE handlers and USD-settle polls: catch `Exception`, but always
  rethrow cancellation.

## Checklist

- [ ] Hot vs cold choice matches `docs/FLOWS.md`
- [ ] If FLOWS Mermaid changed → run
      [validate_mermaid.py](../documentation-review/scripts/validate_mermaid.py)
- [ ] Config watch uses `collectLatest`
- [ ] Active execution sessions defer config-flow publication until cycle/sync exit
- [ ] SSE endpoint remains `/api/status/stream`
- [ ] Flow tests use `runTest` + `advanceUntilIdle`
- [ ] Ledger pagination remains cold, mutex-protected, overlap-safe, and
      cancellation-aware
