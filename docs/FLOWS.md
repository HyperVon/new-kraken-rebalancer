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
        SSE["SSE Client\n(EventSource)"]
        Settings["Settings UI\n(Save Config)"]
    end

    subgraph Ktor["⚡ Ktor HTTP Server"]
        DashRoute["DashboardRoutes\nhandleSseStream()"]
        ConfigRoute["Config Endpoint\nupdateConfig()"]
    end

    subgraph Core["🔄 Core Application Logic"]
        PM["PortfolioManagerImpl\nrunLoop()"]
        OE["OrderExecutorImpl\nrefreshUsdBalanceAfterSells()"]
    end

    subgraph Services["📦 Services"]
        CS["ConfigServiceImpl\n_configFlow\nMutableSharedFlow&lt;Settings&gt;\nreplay=1, DROP_OLDEST"]
        THS["TradeHistoryServiceImpl\nsnapshotFlow\nMutableSharedFlow&lt;PortfolioSnapshot&gt;\nbuffer=16, DROP_OLDEST"]
    end

    subgraph External["🌐 External"]
        Kraken["Kraken API"]
    end

    subgraph DB["🗄️ SQLite"]
        Repo["Trade / Snapshot\nRepository"]
    end

    %% Config flow path
    Settings -->|"HTTP POST /settings"| ConfigRoute
    ConfigRoute -->|"updateConfig()"| CS
    CS -->|"HOT: tryEmit(settings)\nalways succeeds synchronously"| CS
    CS -->|"watchConfigChanges()\ncollectLatest { settings → }"| PM

    %% Rebalance loop
    PM -->|"loop delay\nsettings.loopDelaySeconds"| PM
    PM -->|"performRebalanceCycle()"| OE
    OE -->|"place buy/sell orders"| Kraken
    OE -->|"COLD pollUsdBalanceAfterSells()\n.last() — polls until stable"| Kraken

    %% Snapshot emission
    PM -->|"addSnapshot(snapshot)"| THS
    THS -->|"saveSnapshot()"| Repo
    THS -->|"HOT: tryEmit(snapshot)\nalways succeeds synchronously"| THS
    THS -->|"getHistoryFlow()\ncollect { snapshot → }"| DashRoute
    DashRoute -->|"send(ServerSentEvent)"| SSE

    %% Paginated sync
    PM -->|"syncTradesFromKraken()"| THS
    THS -->|"COLD getTradeHistoryPaginated()\n.collect { page → }\nemit() suspends until collector ready"| Kraken
    Kraken -->|"pages of TradeRecord"| THS
    THS -->|"reconcile & save"| Repo

    %% Dashboard initial load
    SSE -->|"SSE connect /api/status/stream"| DashRoute
    DashRoute -->|"getLatestSnapshot()"| Repo

    classDef hot fill:#1a3a5c,stroke:#4fa3e0,color:#e8f4fd
    classDef cold fill:#1a3c2a,stroke:#4caf7d,color:#e8f5e9
    classDef external fill:#3c2a1a,stroke:#e09a4f,color:#fdf5e8
    classDef infra fill:#2a1a3c,stroke:#9a4fe0,color:#f5e8fd

    class CS,THS hot
    class Kraken external
    class Repo,DB infra
```

---

## Flow 1 — Config Changes (Hot SharedFlow)

**Path:** Settings UI → `ConfigService._configFlow` → `PortfolioManager`

```mermaid
sequenceDiagram
    participant User as User (Browser)
    participant API as Config Endpoint
    participant CS as ConfigServiceImpl
    participant PM as PortfolioManagerImpl

    User->>API: POST /settings (new settings)
    API->>CS: updateConfig(newConfig)
    CS->>CS: validate & save to disk atomically
    note over CS: tryEmit(settings)<br/>guaranteed to succeed<br/>(DROP_OLDEST strategy)
    CS-->>PM: SharedFlow emits new Settings

    note over PM: collectLatest cancels the<br/>sleeping delay() in the active<br/>loop and immediately restarts<br/>with the new settings
    PM->>PM: restart loop with new settings
```

**Key design choices:**

- `replay = 1` means `PortfolioManager` immediately gets the current config the moment it subscribes on startup — no race condition on boot.
- `collectLatest` (not `collect`) is used so that a settings change during a long loop `delay()` takes effect immediately, without waiting for the delay to expire.

---

## Flow 2 — Live Dashboard Updates (Hot SharedFlow)

**Path:** `PortfolioManager` → `TradeHistoryService.snapshotFlow` → Ktor SSE → Browser

```mermaid
sequenceDiagram
    participant PM as PortfolioManagerImpl
    participant THS as TradeHistoryServiceImpl
    participant DB as SQLite
    participant SSE as DashboardRoutes (SSE)
    participant Browser as Browser Tab

    Browser->>SSE: GET /api/status/stream (SSE connect)
    SSE->>DB: getLatestSnapshot()
    DB-->>SSE: last snapshot
    SSE->>Browser: send initial snapshot

    note over SSE: collect() suspends here.<br/>Coroutine is parked, consuming<br/>no CPU, waiting for emissions.

    loop Every rebalance cycle
        PM->>THS: addSnapshot(snapshot)
        THS->>DB: saveSnapshot()
        THS->>THS: tryEmit(snapshot)
        note over THS: Broadcast to all<br/>connected SSE sessions
        THS-->>SSE: snapshot emitted
        SSE->>Browser: send(ServerSentEvent)
    end

    Browser-->>SSE: disconnect
    note over SSE: Ktor cancels the coroutine.<br/>CancellationException breaks<br/>collect(), SSE handler exits.
```

**Key design choices:**

- `extraBufferCapacity = 16` + `DROP_OLDEST` means a slow browser connection **never** stalls the rebalancing loop. The portfolio manager can always `tryEmit()` and move on immediately.
- Multiple browser tabs can all connect simultaneously — each gets its own `collect()` call which independently consumes from the same shared broadcast.

---

## Flow 3 — Paginated Trade Sync (Cold Flow)

**Path:** `syncTradesFromKraken()` → `getTradeHistoryPaginated()` → Kraken API (page by page) → SQLite

```mermaid
sequenceDiagram
    participant Caller as syncTradesFromKraken()
    participant Flow as getTradeHistoryPaginated() [cold]
    participant API as Kraken API
    participant DB as SQLite

    Caller->>Flow: .collect { apiTrades → }
    note over Flow: Cold! Execution starts<br/>NOW because collect() was called.

    loop While there are more pages
        Flow->>API: getTradeHistory(offset=0, 50, 100 ...)
        API-->>Flow: List<TradeRecord> (50 records)
        Flow->>Caller: emit(page)
        note over Flow: emit() suspends here.<br/>Waits for collector to finish<br/>processing before fetching next page.<br/>This is automatic backpressure.
        Caller->>DB: reconcile & save trades
        note over Flow: Collector done → Flow resumes<br/>and fetches the next page.
    end

    Flow->>Caller: (completes — no more pages)
    note over Caller: collect() returns normally.<br/>Execution continues to next line.
```

**Key design choices:**

- Processing happens page-by-page, keeping memory constant regardless of how many total trades exist.
- `emit()` naturally suspends until the collector finishes, meaning Kraken's API is never hit faster than the database can process the last batch — automatic rate-limiting.
- Being cold means this is only ever triggered by `syncTradesFromKraken()` — nothing happens in the background.

---

## Flow 4 — USD Balance Polling (Cold Flow)

**Path:** After sell orders → `pollUsdBalanceAfterSells()` → Kraken API (with backoff) → caller

```mermaid
sequenceDiagram
    participant OE as OrderExecutorImpl
    participant Flow as pollUsdBalanceAfterSells() [cold]
    participant API as Kraken API

    OE->>Flow: .last()
    note over Flow: .last() is a terminal operator.<br/>It collects the entire flow and<br/>returns only the final emitted value.

    loop Up to MAX_REFRESH_ATTEMPTS
        Flow->>Flow: delay(backoffMs)
        Flow->>API: getBalances()
        API-->>Flow: USD balance
        alt Balance >= 95% of expected
            Flow->>OE: emit(balance)
            Flow->>OE: (flow completes early)
        else Balance too low
            Flow->>OE: emit(balance)
            Flow->>Flow: backoffMs = min(backoffMs * 2, 32s)
        end
    end

    note over OE: .last() returns the last positive<br/>observed balance, or projected cash<br/>if no positive balance was observed.
```

**Key design choices:**

- Using `.last()` instead of `.collect()` is intentional — the caller uses the
  **last positive observed balance**, not the maximum observation. If no
  positive balance is observed, the initial projected amount is emitted as the
  fallback. That fail-open fallback is tracked separately in
  [#54](https://github.com/HyperVon/new-kraken-rebalancer/issues/54).
- Exponential backoff is managed entirely inside the cold flow, keeping `OrderExecutorImpl`'s orchestration logic clean.
- Being cold means this only runs when explicitly triggered after sells — it never polls in the background.

---

## Hot vs. Cold: Why Does It Matter?

The choice between hot and cold flows in this application is deliberate and maps directly to the nature of each problem:

| Use Case | Type | Why? |
| --- | --- | --- |
| Config changes | **Hot** | Config exists before anyone listens. New subscribers must get the current value immediately (`replay=1`). Multiple components could theoretically watch it. |
| Dashboard streaming | **Hot** | Snapshots are produced by the rebalancer loop independently of how many browsers are connected. Each connected browser should see the same live broadcast. |
| Paginated API sync | **Cold** | Fetching is always triggered on-demand for a specific reason. The caller owns the full lifecycle. Backpressure is critical for memory safety with large histories. |
| Balance polling | **Cold** | A one-shot operation triggered after sells. The polling only makes sense in that specific context, and the caller only needs the final result. |
