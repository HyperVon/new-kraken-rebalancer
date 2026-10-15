---
name: coroutines-flows-sse
description: >-
  Kotlin Flow architecture for saved Settings, the latest current-cycle
  Dashboard snapshot, SSE, bounded History capture and account sync, and
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
| Hot `StateFlow` + bounded deque | `CurrentPortfolioState` | Exposes the latest completed snapshot and keeps up to 24 in-memory snapshots for the hero trend; clears the trend when simulation mode or credential scope changes. |
| Bounded `Channel` | `HistoryObservationRecorder` | Passes selected cycle samples and activity to an off-cycle writer; a full queue can skip a sample. |

The History page uses a separate store for direct cycle samples and bounded,
account-scoped Kraken trade and ledger synchronization. It does not reconstruct
portfolio values from exchange events. Order-specific Kraken `TradesHistory`
reads used to confirm sells remain a separate execution-safety path.

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
`CurrentPortfolioState`, whose `StateFlow` holds the latest value and whose
bounded in-memory deque supplies up to 24 same-source snapshots to the hero
trend. A simulation-mode or credential-scope change clears that deque. The
Dashboard fragment reads the latest value and trend; `/api/status/stream`
collects the latest value and sends current-cycle snapshot JSON through Ktor
SSE. HTMX's SSE extension refreshes the Dashboard fragment. `:frontend-js`
updates age and freshness but does not open `EventSource` or parse the stream.

The server-side stream reports current cycle/snapshot state and unresolved
order-intent status through the relevant endpoints. Do not persist or rebuild a
historical cycle timeline for Dashboard, startup, health, or readiness; the
bounded hero trend remains process-local and source-scoped.
Per-client SSE failures stay local to that session, and cancellation must
propagate.

## History capture and account synchronization

At startup, the application starts `HistoryObservationRecorder` and the
portfolio manager. The loop queues its selected cycle sample and activity to a
bounded channel; a worker persists them asynchronously in `history.db`. Missing
or incomplete direct evidence has no total value, and History storage failure
must not block the rebalance cycle. Simulation values use a separate
process-session scope and are not authenticated account data.

The History page reads values only for the current account and configured
managed-asset scope. An operator-triggered sync fetches Kraken Trades and
Ledgers one bounded page at a time. Each page and cursor commit before the next
request. The synchronizer pins a ConfigService execution session, checks the
account identity around each exchange read, and verifies the data-directory
account binding before commit. Sync status reports failures rather than
combining a changed account or scope with prior data.

Preserve cancellation in the recorder worker and synchronizer. Keep database
writes and page calls off the order path.

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
- Keep the History queue bounded and non-blocking for the rebalance cycle.
- Catch ordinary client/session failures locally where appropriate, but always
  rethrow `CancellationException`.

## Checklist

- [ ] Saved Settings use the replaying hot flow and `collectLatest`
- [ ] Execution sessions defer Settings publication until the cycle completes
- [ ] Dashboard state exposes the latest snapshot plus a source-scoped bounded hero trend; SSE carries current-cycle snapshots
- [ ] History capture is bounded, direct-evidence based, and off the order path
- [ ] Trade and ledger pages verify account scope and commit cursors before continuing
- [ ] Simulation values remain distinct from account-scoped History
- [ ] Sell fill checks remain scoped to returned order IDs and separate from History sync
- [ ] Cold settlement polls remain bounded and stop buys when cash is uncertain
- [ ] SSE, worker, and poll cancellation continues to propagate
- [ ] `docs/FLOWS.md` remains the canonical runtime diagram and path description
