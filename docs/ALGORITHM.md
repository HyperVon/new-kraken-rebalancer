# Rebalancing Algorithm

This document details the operational logic of the Kraken Rebalancer. The system
is designed to autonomously maintain a specific portfolio allocation across a
set of assets (cryptocurrencies & fiat).

## Overview

```mermaid
flowchart TD
    START([Cycle Start]) --> SNAP
    
    subgraph SNAP["Phase 1: Snapshot"]
        S1[Fetch Balances from Kraken] --> S2[Fetch Market Prices]
        S2 --> S3["Calculate USD Value per Asset"]
        S3 --> S4["Sum → Total Portfolio Value"]
    end

    SNAP --> ATH{New ATH?}
    ATH -- Yes --> SAVE_ATH["Update ATH in SQLite database"]
    ATH -- No --> DD
    SAVE_ATH --> DD

    subgraph DD["Drawdown Assessment"]
        DD1["Drawdown % = (ATH - Current) / ATH × 100"]
        DD1 --> DD2["Deploy % = (Drawdown / MaxDrawdown) ^ Exponent"]
        DD2 --> DD3["Reduce USD Target by Deploy %\nRedistribute to Crypto"]
    end

    DD --> ANALYSIS

    subgraph ANALYSIS["Phase 2: Analysis"]
        A1["Calculate Deviation per Asset\n(Current Value vs Target Value)"]
        A1 --> A2{"Triggered (Dev% + dust)?"}
        A2 -- "Crypto Triggered" --> A3["Generate BUY/SELL orders"]
        A2 -- "Only USD Triggered" --> A4["Fiat Correction:\nDistribute among counter-balanced assets"]
        A2 -- "None Triggered" --> SKIP[No trades needed]
    end

    ANALYSIS --> EXEC

    subgraph EXEC["Phase 3: Execution"]
        E1["Execute SELL orders first\n(generate USD liquidity)"] --> E1R{"Order\nSucceeded?"}
        E1R -- Yes --> E1C["Update projected cash"]
        E1R -- No --> E1F["Log failure, skip cash update"]
        E1C --> E2
        E1F --> E2
        E2["Refresh USD if sell succeeded\nand not dry-run\n(best of 3x backoff from 250ms;\nabort buys if none positive)"] --> E3["Execute BUY orders second\n(99% cycle cash budget)"]
        E3 --> E4["Record Snapshot\n& Trade History\nto SQLite database"]
    end

    EXEC --> SLEEP["Sleep (configurable delay)"]
    SKIP --> SLEEP
    SLEEP --> START
```

## Core Concepts

### 1. Portfolio Definition

The portfolio is defined by a set of target allocations summing to 100%.
**Example:**

- **BTC**: 50%
- **ETH**: 45%
- **USD**: 5%

### 2. Operational Loop

The application runs a continuous "Rebalance Cycle" with a configurable delay (
e.g., every 60 seconds). Each cycle consists of three phases: **Snapshot**, *
*Analysis**, and **Execution**.

### 3. Architectural Separation of Concerns

To maintain the Single Responsibility Principle (SRP) and keep domain logic highly testable, the core engine is decoupled into specific implementation (`impl`) classes:

- **`PortfolioManagerImpl` (The Orchestrator)**: Manages the continuous coroutine loop. It acts as a lightweight facade that delegates domain logic to the analyzer and executor, and coordinates snapshot persistence. It reactively restarts the loop upon configuration changes via `watchConfigChanges()`.
- **`PortfolioAnalyzer` (The Brain)**: Responsible for Phase 1 and 2. It resolves prices, tracks the All-Time High (ATH), calculates dynamic fiat deployment ratios, computes deviations, and determines the exact `BUY`/`SELL` amounts required. Portfolio value calculation returns a `Result<PortfolioValues>` for graceful error handling.
- **`PortfolioCalculations` (Shared Math)**: Consolidated percentage, target, and deviation calculations shared between the analyzer and snapshot builder — eliminates duplicate math across the codebase.
- **`OrderExecutor` (The Brawn)**: Responsible for Phase 3. It takes the calculated orders and safely executes them against the Kraken API. It manages the strict sell-before-buy sequence, projected vs. actual cash tracking, dust-threshold filtering, action-log formatting, and persisting each order via `TradeHistoryService.saveTrade`.
- **`KrakenServiceImpl` + `RateLimiter` (The Gateway)**: Handles HMAC-SHA512
  authenticated API calls with a Kraken call-counter rate limiter (linear
  elapsed-time decay of `elapsedSeconds × 0.33`, plus per-endpoint costs) and
  `retryWithFlow` for transient failures, rate limits, and temporary lockouts.
- **Persistence Impls (`SqliteTradeRepositoryImpl`, `SqlitePortfolioStatsRepositoryImpl`, `ConfigServiceImpl`)**: Config uses atomic write-then-rename file operations and exposes `watchConfigChanges()` as a reactive `Flow<Settings>`. Trade logs and portfolio statistics are persisted to SQLite (using JetBrains Exposed ORM).
- **`TradeHistoryServiceImpl`**: Maintains a reactive `MutableSharedFlow<PortfolioSnapshot>` that broadcasts snapshots to the Ktor Server-Sent Events (SSE) stream in real-time. Trade history sync uses a flow-based paginated fetch from the Kraken API.

---

## Phase 1: Snapshot

In this phase, the system builds a complete view of the current portfolio state.

1. **Fetch Balances**: Retrieves the current balance of all configured assets
   from the Kraken API.
2. **Fetch Prices**: Retrieves the current market price (in USD) for all non-USD
   assets.
3. **Calculate Valuation**:
    - Calculates the USD value of every asset (`Balance * Price`).
    - Sums these values to determine the **Total Portfolio Value**.
4. **Price safety**: If any non-USD configured asset is missing a ticker price or
   the resolved price is zero, the cycle **aborts** before orders are generated
   (`Result.Failure`) to avoid erroneous trades.

---

## Phase 2: Analysis

The system determines what trades are necessary to restore the portfolio to its
target state.

### 1. Target Calculation & Dynamic Adjustment

Normally, the target value is `Total Portfolio Value * Target %`. However, the system implements a **Dynamic Fiat Deployment Strategy**:

1. **ATH Tracking**: The bot tracks the portfolio's All-Time High (ATH) value in the SQLite database. ATH is set on first run or updated whenever a new high is reached.
2. **Drawdown Calculation**:
   `Drawdown % = (ATH - Current Value) / ATH * 100`
3. **Fiat Deployment Percentage**:
   Based on the configured `fiatMaxDrawdown` (e.g., 30%) and `fiatDeploymentExponent` (e.g., 1.0):
   `Deployment % = (Drawdown % / Max Drawdown %) ^ Exponent` (Capped at 100%)

   Fractional exponents use `Double.pow`, then the result is re-entered as
   `BigDecimal` at percent scale (`SCALE_PERCENT = 4`). When `fiatMaxDrawdown ≤ 0`,
   deployment is **disabled** (`Deploy% = 0`).

   **Examples (Max Drawdown = 30%)**:

   | Drawdown | Linear (1.0) | Aggressive (0.5) | Conservative (2.0) |
   | :--- | :--- | :--- | :--- |
   | **1.5%** (5% of Max) | 5% | 22% | 0.25% |
   | **7.5%** (25% of Max) | 25% | 50% | 6.25% |
   | **15%** (50% of Max) | 50% | 71% | 25% |
   | **22.5%** (75% of Max) | 75% | 87% | 56% |
   | **30%** (100% of Max) | 100% | 100% | 100% |

4. **Target Adjustment**:
   The target percentage for USD is reduced by the Deployment %:
   `Effective USD Target = Base USD Target * (1 - Deployment %)`
   The removed allocation is redistributed proportionally to the crypto assets, ensuring the total remains 100%.

Using these effective targets, the **Ideal Value** for each asset is calculated.

### 2. Deviation Calculation

The difference between current and target value is calculated:
`Deviation (USD) = Current Value - Target Value`
`Deviation (%) = Deviation (USD) / Target Value * 100` (signed relative
deviation). When the target value is `$0` but the holding still has a
positive value, `Deviation (%)` is treated as **100%** so a zero-target
position can still clear the percent trigger (paired with the dust gate).

### 3. Trigger Logic

An asset generates an order only when **both** gates pass:

1. Absolute relative deviation
   `|Deviation (%)| ≥ deviationTriggerPercent` (e.g., 5%).
2. Absolute USD deviation is significant:
   `|Deviation (USD)| ≥ dustThresholdUSD` (`AssetMetrics.isSignificant`).

Dust therefore filters **order generation**, not only execution.

- **Scenario A: Standard Rebalance**
  If a crypto asset (e.g., BTC) passes both gates:
  - **Overweight (> 0)**: A **SELL** order is generated for the excess USD
      amount.
  - **Underweight (< 0)**: A **BUY** order is generated for the deficit
      USD amount.

- **Scenario B: Fiat Correction (Deposit/Withdrawal)**
  If *only* the USD asset passes both gates (e.g., due to a fresh
  deposit of cash), the system recognizes this as a "Fiat Correction" event.
  - The surplus (or deficit) of USD is distributed intelligently among
      assets that counter-balance the deviation.
  - **Surplus (Deposit)**: Buys are distributed among **Underweight**
      assets only, proportional to their current USD deficit.
  - **Shortage (Withdrawal)**: Sells are distributed among **Overweight**
      assets only, proportional to their current USD surplus.
  - Each share is rounded to USD scale (2 decimals) and drawn from a budget
      truncated to the same scale, so the shares can never sum above the fiat
      deviation being corrected. A share that rounds to `$0.00` is dropped
      instead of becoming a zero-value order.
  - *Note: This concentrates the rebalancing power into the assets that
      are furthest from their targets, effectively clearing dust
      thresholds.*

---

## Phase 3: Execution

The system executes the calculated orders in a specific sequence to ensure
liquidity. Each order returns a structured `OrderResult` indicating success or
failure.

1. **Sell Orders First**: All SELL orders are executed immediately to generate
   USD.
    - Only successful sells update the projected cash balance. Failed sells are
      logged but do not inflate the available cash. If every sell fails (or none
      run), buys continue against the **pre-sell** projected cash and the 99%
      cycle budget — no invented sell liquidity.
2. **USD Balance Refresh**: After **≥1 successful sell** and when **not** in
   dry-run mode, the system polls the exchange up to **3** times with exponential
   backoff starting at **250ms** (doubling each attempt: 250ms → 500ms → 1000ms)
   to fetch the settled USD balance. It tracks the **best (maximum) positive**
   observation and accepts early once the balance reaches **≥95%** of the
   projected amount. If no positive USD balance is observed after all attempts,
   **buys are aborted** (fail-closed — projected proceeds are never treated as
   confirmed cash). The poll is skipped when no sell succeeded or when
   `dryRun` is true; in those cases buys use the **projected** cash balance
   (pre-sell cash when sells did not succeed).
3. **Buy Orders Second**:
    - The whole sell→buy sequence runs inside `KrakenService.withStableBackend`
      so a mid-cycle `simulation` flip cannot split sells and buys across backends.
    - A **cycle-level budget** of **99%** of post-sell settled USD caps aggregate
      multi-buy spend (`PrecisionConstants.CASH_RESERVE_FACTOR`).
    - Each buy is further capped by the remaining cycle budget; dust buys below
      `dustThresholdUSD` are skipped.
    - Only successful buys deduct from available cash and the remaining budget.
4. **Order Placement**:
    - Orders are placed as **Market Orders** for immediate execution.
    - "Dust" orders (below the configured `dustThresholdUSD`) are skipped to
      avoid API errors.
    - Order volumes use `BigDecimal` with 8 decimal places of precision.
    - `dryRun` suppresses placement on the **active** backend: live logs
      `[DRY RUN]`; simulation logs `[EMULATOR DRY RUN]`. It is independent of
      `simulation` (which only selects live Kraken vs the offline emulator).
5. **Persistence**: The cycle snapshot (including all trade actions and their outcomes) is saved directly to the SQLite database (under the trade and snapshot tables).

### Trade economics & slippage lifecycle

Each executed order creates a **local estimate** row at rebalance time:

- **`TradeSource.LOCAL_ESTIMATE`** — `expectedPrice` from the ticker snapshot used for planning; fee from the configured estimate rate (`PrecisionConstants.FEE_RATE_ESTIMATE` = **0.0026**); slippage computed vs that expected price.
- **`TradeSource.API_FILL`** — Kraken `/0/private/TradesHistory` fills (or reconciled rows after sync).

During **Kraken sync**, a matching local row is updated in place: API fill price/volume/fee replace the estimate, **`expectedPrice` is preserved**, slippage is **recomputed** against the API execution price, and `source` becomes `API_FILL`. Rows without the new `source` column infer provenance from legacy data (API fills had null slippage; local estimates had slippage set).

Dedupe prefers settled API fills over local estimates when pair alias or estimate-vs-fill rules match (see trade-history sync skill).

## Configuration

The behavior is controlled by `rebalancer-config.json`:

| Parameter | Description |
| :--- | :--- |
| `loopDelaySeconds` | Time to wait between cycles. |
| `deviationTriggerPercent` | Sensitivity of the rebalancer. Lower values track targets closer but trade more frequently (higher fees). |
| `dustThresholdUSD` | Minimum significant USD deviation **and** minimum order notional. Assets below this USD deviation do not trigger; smaller orders are also skipped at execution. |
| `dryRun` | Suppresses order placement on the **active** backend (`[DRY RUN]` live / `[EMULATOR DRY RUN]` simulation). Orthogonal to `simulation`. |
| `simulation` | If set to `true`, `DynamicKrakenService` routes to `SimulatedKrakenService` (offline emulator). Empty DB pre-seeds ~**15 days** of snapshots at 6-hour steps. Snapshots/trades older than **90 days** are pruned on each `addSnapshot`. |
| `fiatMaxDrawdown` | The portfolio drawdown percentage at which 100% of the USD allocation should be deployed into assets. Set to `0` to disable. |
| `fiatDeploymentExponent` | Controls the aggressiveness of deployment. `1.0` is linear. Values `< 1.0` deploy more cash earlier (aggressive). Values `> 1.0` save cash for deeper dips (conservative). |

## Precision

Monetary and ratio math uses `BigDecimal` with these scales (`PrecisionConstants`):

| Constant | Scale | Use |
| :--- | ---: | :--- |
| `SCALE_CRYPTO` | **8** | Balances, prices, order volumes |
| `SCALE_USD` | **2** | USD notionals and **persisted snapshot** percent/USD display fields |
| `SCALE_PERCENT` | **4** | Internal analysis percents (drawdown, deploy, deviation triggers) |
| `SCALE_FEE` | **4** | Fee amounts |

Snapshot/UI asset percents are rounded to `SCALE_USD` (2 dp) when persisted;
trigger math keeps `SCALE_PERCENT` (4 dp).
