# Kotlin Flow Architecture

This document explains how **Kotlin Coroutines Flows** are used throughout the application to handle asynchronous events without blocking threads. There are two kinds of flow in play: **hot** `SharedFlow` (a live broadcast) and **cold** `Flow` (an on-demand pipeline).

---

## The Two Types at a Glance

| | Cold `Flow` | Hot `SharedFlow` |
| --- | --- | --- |
| **Analogy** | On-demand video (starts when you press play) | Live radio (broadcasting regardless of listeners) |
| **Execution** | Starts only when collected | Runs independently of collectors |
| **Per-collector** | Each collector gets its own independent run | All collectors share the same broadcast |
| **Lifecycle** | Finite — completes when the producer finishes | Infinite — never completes on its own |
| **Backpressure** | Automatic: `emit()` suspends if collector is slow | Configurable: buffer + overflow strategy |
| **Use in this app** | Paginated API fetching, balance polling | Config changes, live dashboard streaming |

---

## System-Wide Flow Map

This diagram shows every place flows are used and how data moves between components.

```mermaid
flowchart TB
    subgraph UI["🖥️  Browser / Dashboard"]
        SSE["SSE Client\n(HTMX sse-connect)"]
        Settings["Settings UI\n(Save Config)"]
    end

    subgraph Ktor["⚡ Ktor HTTP Server"]
        DashCtrl["DashboardController\nhandleSseStream()"]
        SettingsPost["DashboardController\nhandlePostSettings()"]
    end

    subgraph Core["🔄 Core Application Logic"]
        PM["PortfolioManagerImpl\nrunLoop()"]
        OE["OrderExecutorImpl\nsettleUsdAfterSells()"]
    end

    subgraph Services["📦 Services"]
        CS["ConfigServiceImpl\n_configFlow\nMutableSharedFlow&lt;Settings&gt;\nreplay=1, no extraBufferCapacity, DROP_OLDEST"]
        THS["TradeHistoryServiceImpl\n(façade)"]
        Store["TradeHistorySnapshotStore\nsnapshotFlow\nMutableSharedFlow&lt;PortfolioSnapshot&gt;\nreplay=1, buffer=16, DROP_OLDEST"]
        Sync["TradeHistorySyncService\n(300s throttle + pagination)"]
    end

    subgraph External["🌐 External"]
        Kraken["Kraken API"]
    end

    subgraph DB["🗄️ SQLite"]
        Repo["Trade / Snapshot\nRepository"]
    end

    %% Config flow path
    Settings -->|"HTTP POST /settings"| SettingsPost
    SettingsPost -->|"updateConfig()"| CS
    CS -->|"HOT: tryEmit(settings)\nalways succeeds synchronously"| CS
    CS -->|"watchConfigChanges()\ncollectLatest { settings → }"| PM

    %% Rebalance loop
    PM -->|"loop delay\nsettings.loopDelaySeconds"| PM
    PM -->|"performRebalanceCycle()"| OE
    OE -->|"place buy/sell orders"| Kraken
    OE -->|"COLD poll after successful sell\n(not dry-run); best of 3 / early 95 pct"| Kraken

    %% Snapshot emission (façade delegates to SnapshotStore)
    PM -->|"addSnapshot(snapshot)"| THS
    THS -->|"delegate"| Store
    Store -->|"saveSnapshot()"| Repo
    Store -->|"HOT: tryEmit(snapshot)\nalways succeeds synchronously"| Store
    Store -->|"getHistoryFlow()\ncollect { snapshot → }"| DashCtrl
    DashCtrl -->|"send(ServerSentEvent)"| SSE

    %% Paginated sync (façade → SyncService; 300s throttle inside Sync)
    PM -->|"syncTradesFromKraken()\neach cycle"| THS
    THS -->|"delegate"| Sync
    Sync -->|"COLD getTradeHistoryPaginated()\n.collect { page → }\nemit() suspends until collector ready"| Kraken
    Kraken -->|"pages of TradeRecord"| Sync
    Sync -->|"reconcile & save"| Repo

    %% Dashboard initial load
    SSE -->|"SSE connect /api/status/stream"| DashCtrl
    DashCtrl -->|"getLatestSnapshot()"| Repo

    classDef hot fill:#1a3a5c,stroke:#4fa3e0,color:#e8f4fd
    classDef cold fill:#1a3c2a,stroke:#4caf7d,color:#e8f5e9
    classDef external fill:#3c2a1a,stroke:#e09a4f,color:#fdf5e8
    classDef infra fill:#2a1a3c,stroke:#9a4fe0,color:#f5e8fd

    class CS,Store hot
    class Sync cold
    class Kraken external
    class Repo,DB infra
```

---

## Flow 1 — Config Changes (Hot SharedFlow)

**Path:** Settings UI → `ConfigService._configFlow` → `PortfolioManager`

```mermaid
sequenceDiagram
    participant User as User (Browser)
    participant API as DashboardController.handlePostSettings
    participant CS as ConfigServiceImpl
    participant PM as PortfolioManagerImpl

    User->>API: POST /settings (new settings)
    API->>CS: updateConfig(newConfig)
    CS->>CS: validate & save to disk atomically
    alt no active execution session
        note over CS: tryEmit(settings)<br/>guaranteed to succeed<br/>(DROP_OLDEST strategy)
        CS-->>PM: SharedFlow emits new Settings
    else active execution session
        CS->>CS: stage runtime config until session exits
    end

    note over PM: collectLatest cancels the<br/>sleeping delay() in the active<br/>loop and immediately restarts<br/>with the new settings
    PM->>PM: restart loop with new settings
```

**Key design choices:**

- `replay = 1` means `PortfolioManager` immediately gets the current config the moment it subscribes on startup — no race condition on boot.
- Config’s `MutableSharedFlow` uses **no** `extraBufferCapacity` (default 0) with `DROP_OLDEST`; snapshot flow uses `extraBufferCapacity = 16` so slow SSE clients do not stall emitters.
- `collectLatest` (not `collect`) is used so that a settings change during a long loop `delay()`
  takes effect immediately. During an active rebalance session, config saves/reloads persist to disk
  but defer runtime publication until the session exits; unrelated coroutine cancellation still
  propagates normally.
- Real-live order placement writes `PENDING` plus the deterministic `cl_ord_id` before AddOrder.
  Definite exchange rejections resolve the row immediately; transport/response failures become
  `UNCERTAIN` and immediately abort the remaining batch. An unresolved row is excluded from
  heuristic fill reconciliation, duplicate cleanup, and retention pruning: an operator must verify
  Kraken open orders, closed orders, and fills before clearing its state in SQLite. Absence from
  trade history alone is never treated as proof of rejection.

---

## Flow 2 — Live Dashboard Updates (Hot SharedFlow)

**Path:** `PortfolioManager` → `TradeHistoryServiceImpl` façade →
`TradeHistorySnapshotStore.snapshotFlow` → Ktor SSE → Browser

```mermaid
sequenceDiagram
    participant PM as PortfolioManagerImpl
    participant THS as TradeHistoryServiceImpl
    participant Store as TradeHistorySnapshotStore
    participant DB as SQLite
    participant SSE as DashboardController.handleSseStream
    participant Browser as Browser Tab

    Browser->>SSE: GET /api/status/stream (HTMX SSE extension)
    SSE->>DB: getLatestSnapshot()
    DB-->>SSE: last snapshot
    SSE->>Browser: send initial snapshot

    note over SSE: collect() suspends here.<br/>Coroutine is parked, consuming<br/>no CPU, waiting for emissions.

    loop Every rebalance cycle
        PM->>THS: addSnapshot(snapshot)
        THS->>Store: addSnapshot(snapshot)
        Store->>DB: saveSnapshot()
        Store->>Store: tryEmit(snapshot)
        note over Store: Broadcast to all<br/>connected SSE sessions
        Store-->>SSE: snapshot emitted via getHistoryFlow()
        SSE->>Browser: send(ServerSentEvent)
    end

    Browser-->>SSE: disconnect
    note over SSE: Ktor cancels the coroutine.<br/>CancellationException breaks<br/>collect(), SSE handler exits.
```

**Key design choices:**

- Each SSE `data` payload is Jackson-serialized **`PortfolioSnapshot` JSON** (`timestamp`, `totalValueUSD`, `assets`, `actions`, drawdown/deploy fields).
- `replay = 1` + `extraBufferCapacity = 16` + `DROP_OLDEST` means late SSE subscribers still get the latest snapshot, and a slow browser connection **never** stalls the rebalancing loop. The portfolio manager can always `tryEmit()` and move on immediately.
- Multiple browser tabs can all connect simultaneously — each gets its own `collect()` call which independently consumes from the same shared broadcast.

---

## Flow 3 — Paginated Trade Sync (Cold Flow)

**Path:** `TradeHistoryServiceImpl.syncTradesFromKraken()` →
`TradeHistorySyncService` → `getTradeHistoryPaginated()` → Kraken API
(page by page) → SQLite

```mermaid
sequenceDiagram
    participant Caller as syncTradesFromKraken()
    participant Sync as TradeHistorySyncService
    participant Flow as getTradeHistoryPaginated() [cold]
    participant API as Kraken API
    participant DB as SQLite

    Caller->>Sync: syncTradesFromKraken()
    Sync->>Flow: .collect { apiTrades → }
    note over Flow: Cold! Execution starts<br/>NOW because collect() was called.

    loop While there are more pages
        Flow->>API: getTradeHistory(offset=0, 50, 100 ...)
        API-->>Flow: List<TradeRecord> (50 records)
        Flow->>Sync: emit(page)
        note over Flow: emit() suspends here.<br/>Waits for collector to finish<br/>processing before fetching next page.<br/>This is automatic backpressure.
        Sync->>DB: reconcile & save trades
        note over Flow: Collector done → Flow resumes<br/>and fetches the next page.
    end

    Flow->>Sync: (completes — no more pages)
    note over Caller: collect() returns normally.<br/>Execution continues to next line.
```

**Key design choices:**

- `PortfolioManagerImpl` calls `syncTradesFromKraken()` **every** rebalance cycle,
  but `TradeHistorySyncService` (via the façade) **no-ops** unless ≥ **300 seconds**
  have elapsed since `lastSyncTime` (5-minute throttle).
- Live sync is skipped when credentials are invalid and `simulation` is false;
  simulation mode never hits Kraken for history.
- Incremental sync uses a **300-second overlap** window from the **effective**
  watermark (`latestTradeTime`, falling back to `sync_watermark_epoch_sec` when
  there are no non-dry-run fills) so fills near the cutoff are not missed and
  dry-run-only accounts do not re-pull from EPOCH.
- Within one sync pass, API fills are fingerprinted so a pagination window shift
  cannot double-insert the same fill; dry-run locals never match API fills
  (`isMatchingApiTrade` returns false when `local.dryRun`).
- Processing happens page-by-page (page size **50**), keeping memory constant
  regardless of how many total trades exist.
- `emit()` naturally suspends until the collector finishes, meaning Kraken's API
  is never hit faster than the database can process the last batch — automatic
  backpressure.
- Being cold means pagination only runs when `syncTradesFromKraken()` collects —
  nothing polls Kraken for trades in the background.

---

## Flow 4 — USD Settle after sells (Cold Flow)

**Path:** After **≥1 successful sell** and when **not** dry-run →
`settleUsdAfterSells()`:

1. **Primary (when sell AddOrder txids exist):** `pollFillConfirmedUsd()` →
   `sumMatchedSellProceeds()` (trade history matched by `orderTxid`, net of fee,
   paginate up to 5×50) → optional balance peek
   `min(fillConfirmed, balance)` when spendable USD is visible → else cap to
   `projectedCash`.
2. **Fallback:** when no txids, or fill confirmation returns no positive USD →
   `refreshUsdBalanceAfterSells()` → `pollUsdBalanceAfterSells().last()` (below).

Skipped when dry-run or no sell succeeded (buys use projected cash). Fail-closed:
abort buys if neither path confirms positive USD.

```mermaid
sequenceDiagram
    participant OE as OrderExecutorImpl
    participant Fill as pollFillConfirmedUsd() [cold]
    participant Hist as getTradeHistory
    participant Bal as getBalances

    note over OE: Only when executedSells and not dryRun
    alt "sell orderTxids present"
        OE->>Fill: settleUsdAfterSells → pollFillConfirmedUsd → .last()
        loop "Up to 3 attempts (250ms doubling)"
            Fill->>Fill: delay(backoffMs)
            Fill->>Hist: pages until short or max 5
            Hist-->>Fill: matched fills (cost - fee)
            alt "cash >= 95% of projected"
                Fill->>OE: emit(bestCash)
                Fill->>OE: (flow completes early)
            else "positive but below 95%"
                Fill->>OE: emit(bestCash)
            end
        end
        OE->>Bal: peekUsdBalance (once)
        note over OE: min(fillConfirmed, balance) when balance > 0<br/>else min(fillConfirmed, projectedCash)
    else "no txids or fillConfirmed = 0"
        OE->>OE: refreshUsdBalanceAfterSells (Flow 4b)
    end
```

### Flow 4b — USD Balance Polling fallback (Cold Flow)

**Path:** `refreshUsdBalanceAfterSells()` → `pollUsdBalanceAfterSells().last()` →
Kraken balances API (with backoff). Used when sell txids are missing (e.g. some
test doubles) or fill confirmation found no matching positive proceeds.

```mermaid
sequenceDiagram
    participant OE as OrderExecutorImpl
    participant Flow as pollUsdBalanceAfterSells() [cold]
    participant API as Kraken API

    note over OE: Fallback when no txids or empty fill confirm
    OE->>Flow: refreshUsdBalanceAfterSells → .last()
    note over Flow: .last() is a terminal operator.<br/>It collects the entire flow and<br/>returns only the final emitted value.

    loop "Up to 3 attempts (250ms → 500ms → 1000ms)"
        Flow->>Flow: delay(backoffMs)
        Flow->>API: getBalances()
        API-->>Flow: USD balance
        alt "Balance >= 95% of expected"
            Flow->>OE: emit(bestObserved)
            Flow->>OE: (flow completes early)
        else "Positive but below 95%"
            Flow->>OE: emit(bestObserved)
            Flow->>Flow: "backoffMs = min(backoffMs * 2, 32s)"
        else "Zero / empty / error"
            Flow->>Flow: "backoffMs = min(backoffMs * 2, 32s)"
        end
    end

    note over OE: .last() returns the best (max)<br/>positive observed balance, or 0<br/>if none — then OE aborts buys.
```

**Key design choices:**

- Fill confirmation is preferred when AddOrder returns txids — buy budget is
  sized from **opening USD + net fill proceeds**, capped by a balance peek when
  spendable cash is already visible, otherwise by **projected cash** so history
  cannot invent liquidity beyond the cycle's sell intents.
- Balance polling remains the fail-safe path (and the only path for backends
  that omit txids).
- Using `.last()` instead of `.collect()` is intentional — both cold polls track
  the **best (maximum) positive** observation across attempts. If nothing
  positive is observed, `.last()` yields `0` and `OrderExecutorImpl` **aborts
  buys** (fail-closed).
- Backoff starts at **250ms** and doubles per attempt
  (**250ms → 500ms → 1000ms** with `MAX_REFRESH_ATTEMPTS = 3`). Code also
  `coerceAtMost(32000)` as a defensive ceiling; that cap is unreachable under
  current constants.
- Being cold means this only runs when explicitly triggered after a successful
  sell outside dry-run — it never polls in the background.

---

## Hot vs. Cold: Why Does It Matter?

The choice between hot and cold flows in this application is deliberate and maps directly to the nature of each problem:

| Use Case | Type | Why? |
| --- | --- | --- |
| Config changes | **Hot** | Config exists before anyone listens. New subscribers must get the current value immediately (`replay=1`). Multiple components could theoretically watch it. |
| Dashboard streaming | **Hot** | Snapshots are produced by the rebalancer loop independently of how many browsers are connected. Each connected browser should see the same live broadcast. |
| Paginated API sync | **Cold** | Fetching is always triggered on-demand for a specific reason. The caller owns the full lifecycle. Backpressure is critical for memory safety with large histories. |
| USD settle (fill / balance) | **Cold** | One-shot after sells. Fill-confirm or balance poll only makes sense in that context; the caller only needs the final settled cash. |
