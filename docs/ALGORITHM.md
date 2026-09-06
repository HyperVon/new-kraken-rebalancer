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

    SNAP --> ATH{"New ATH?"}
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
        A2 -- "None Triggered" --> E4
    end

    ANALYSIS --> EXEC

    subgraph EXEC["Phase 3: Execution"]
        E1["Execute SELL orders first\n(generate USD liquidity)"] --> E1R{"Order\nSucceeded?"}
        E1R -- Yes --> E1C["Update projected cash"]
        E1R -- No --> E1U{"Submission uncertain?"}
        E1U -- Yes --> E1A["Persist UNCERTAIN\nAbort remaining batch"]
        E1U -- No --> E1F["Log definite failure\nSkip cash update"]
        E1C --> E2
        E1F --> E2
        E1A --> E4
        E2["Settle USD if any sell succeeded\n(fill-confirm by txid, else balance poll;\n3x backoff; abort buys if none positive)"] --> E3["Execute BUY orders second\n(99% cash budget; stop batch if uncertain)"]
        E3 --> E4["Record Snapshot\n& Trade History\nto SQLite database"]
    end

    EXEC --> SLEEP["Sleep (configurable delay)"]
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
e.g., every 60 seconds). Each cycle consists of three phases: **Snapshot**, **Analysis**,
and **Execution**.

### 3. Architectural Separation of Concerns

To maintain the Single Responsibility Principle (SRP) and keep domain logic highly testable, the pure domain rebalancing math and typed planning models are encapsulated in the standalone `:engine` module, while the service and execution orchestrators live in the backend:

- **`PortfolioManagerImpl` (The Orchestrator)**: Manages the continuous coroutine loop. It acts as a lightweight facade that delegates domain logic to the analyzer and executor, and coordinates snapshot persistence. It reactively restarts the loop upon configuration changes via `watchConfigChanges()`.
- **`PortfolioAnalyzer` (The Brain)**: Responsible for Phase 1 and 2. It resolves prices, tracks the All-Time High (ATH), assembles end-of-cycle `PortfolioSnapshot`s, and delegates valuation / drawdown / deviation / fiat-correction math to **`RebalancerEngine`** in `:engine`. Portfolio value calculation returns a `Result<PortfolioValues>` for graceful error handling.
- **`RebalancerEngine` (Domain calculator — `:engine`)**: Side-effect-light math (no network/DB) for portfolio values, drawdown, fiat deployment, targets, deviation analysis, and fiat correction. It emits a typed `RebalancePlan` with `RebalanceEvent` values; a presentation adapter keeps the existing snapshot action-log strings stable. Logging is retained for diagnostics.
- **`PortfolioCalculations` (Shared Math — `:engine`)**: Consolidated percentage, target, and deviation calculations shared by the analyzer (including end-of-cycle snapshot assembly) — eliminates duplicate math across the codebase.
- **`OrderExecutor` (The Brawn)**: Responsible for Phase 3. It takes the calculated orders and safely executes them against the Kraken API. It manages the strict sell-before-buy sequence, projected vs. actual cash tracking, dust-threshold filtering, action-log formatting, and persisting each order via `TradeHistoryService.saveTrade`. Before a real live placement, it persists a `PENDING` intent with a deterministic Kraken **`cl_ord_id`** (from `cycleId|symbol|side`). AddOrder is attempted only once; an ambiguous transport/response failure becomes `UNCERTAIN`, aborts the remaining batch, and blocks later live orders until operator reconciliation (`userref` is not a uniqueness key among open orders).
- **`KrakenServiceImpl` + transport limiters (The Gateway)**: Handles
  HMAC-SHA512 authenticated API calls with Kraken's separate public and private
  controls. The private account counter defaults to the standard account
  `safeLimit = 20` and `0.5` points/second decay; `Ledgers`, `TradesHistory`,
  and `ClosedOrders` cost 4, other private calls cost 1, and `AddOrder` and
  `CancelOrder` do not charge that counter because trading has separate limits.
  Public ticker/OHLC calls use a separate conservative limiter of at most about
  one call per second. Private nonce acquisition, signing, POST, and response
  handling are serialized. `retryWithFlow` retries only network I/O, 429,
  temporary lockout, and relevant 5xx responses with capped backoff; AddOrder
  remains one-shot because an ambiguous response may follow an accepted order.
  See [Kraken's current rate-limit guidance](https://support.kraken.com/articles/206548367-what-are-the-api-rate-limits-?mobile_site=false).
- **Persistence Impls (`SqliteTradeRepositoryImpl`, `SqliteOrderIntentRepositoryImpl`, `SqlitePortfolioStatsRepositoryImpl`, `ConfigServiceImpl`)**: Config uses atomic write-then-rename file operations and exposes `watchConfigChanges()` as a reactive `Flow<Settings>`. Trade logs, live-order intents, and portfolio statistics are persisted to SQLite (using JetBrains Exposed ORM); schema versions are recorded and file-backed migrations receive a pre-migration backup.
- **`TradeHistoryServiceImpl`**: Thin façade over Sync / SnapshotStore / Query /
  Reconstruction. The hot `MutableSharedFlow<PortfolioSnapshot>` lives on
  `TradeHistorySnapshotStore` and is exposed via `getHistoryFlow()` for the Ktor
  SSE stream. Trade history sync uses a flow-based paginated fetch from the
  Kraken API (`TradeHistorySyncService`). Ledger synchronization is a separate
  paginated flow (`LedgersSyncService`) so ledger persistence does not alter
  trade reconciliation semantics.

---

## Phase 1: Snapshot

In this phase, the system builds a complete view of the current portfolio state.

1. **Fetch Balances**: Retrieves the current balance of all configured assets
   from the Kraken API.
2. **Fetch Prices**: Retrieves the current market price (in USD) for all non-USD
   assets.
3. **Calculate Valuation**:
    - Calculates the USD value of every asset (`Balance * Price`).
    - Rounds each per-asset USD value used by analysis and order sizing to USD
      scale, but sums the raw values and rounds the **Total Portfolio Value** only
      once.
4. **Price safety**: If any non-USD configured asset is missing a ticker price or
   the resolved price is zero, the cycle **aborts** before orders are generated
   (`Result.Failure`) to avoid erroneous trades.

The reconstruction path follows the same consistency boundary: it captures one
execution-session configuration and pins one exchange backend for balances,
ticker prices, OHLC history, and snapshot calculations. A settings change or
simulation flip cannot make one reconstruction pass mix configurations or
backends.

---

## Phase 2: Analysis

The system determines what trades are necessary to restore the portfolio to its
target state.

### 1. Target Calculation & Dynamic Adjustment

Normally, the target value is `Total Portfolio Value * Target %`. However, the system implements a **Dynamic Fiat Deployment Strategy**:

1. **ATH Tracking & Cash-Flow Adjustment**: The bot tracks the portfolio's All-Time High (ATH) value in
   the SQLite database. ATH is set on first run or updated whenever a new high
   is reached.
   - **Cash-Flow Neutrality**: Monotonic ATH tracking without flow adjustment would cause external deposits
     to artificially raise ATH and external withdrawals to plunge the bot into false drawdowns. To preserve true
     strategy performance, owner capital flows adjust ATH proportionally:
     `Adjusted ATH = Current ATH * (Pre-Flow Value + Net External Flow) / Pre-Flow Value`
     This ensures an external deposit scales ATH without wiping out an existing drawdown percentage, and an external
     withdrawal scales ATH down without triggering artificial drawdown or forced fiat deployment. Staking rewards,
     dividends, and `earn/reward` are investment performance that improve portfolio value and reduce drawdown
     without scaling ATH; Earn allocation mechanics remain internal.
   - **Two-Layer Funding Provenance & Flow Classification**: Kraken reuses coarse ledger types for economically
     distinct activity, so classification follows a strict two-layer architecture:
     1. *Intrinsic classification (`LedgerFlowClassifier`)*: Evaluates intrinsic ledger metadata. Same-asset
        `refid`-paired zero-net legs and known internal-subtype rows (spot/futures/staking wallet moves, earn
        allocation, migration) classify as `INTERNAL_MOVE`. Trade rows defer to `TradesHistory` (`TRADE_IGNORED`),
        margin-family rows (`margin`, `rollover`, `settled`, `credit`, `sale`) replay in-kind as `EXTERNAL_BALANCE`
        without scaling ATH, and unrecognized ledger types fail closed. Modern `earn/reward` is
        `EXTERNAL_BALANCE`; `earn/allocation`, `deallocation`, `autoallocate`, and `migration` are
        `INTERNAL_MOVE`; another Earn subtype is ambiguous. For `transfer`, exact internal subtypes,
        authoritative internal evidence, or an asset-aware same-asset zero-net pairing may prove
        `INTERNAL_MOVE`; documented `reward` subtype is `EXTERNAL_BALANCE`; undocumented prose
        descriptions (`airdrop`, `fork`, `distribution`) and bare transfers remain ambiguous without
        affirmative external provenance. `refid` is used only to correlate
        rows and never parsed for undocumented meaning. For deposits and withdrawals, the classifier
        delegates external validation to an affirmative `FundingProvenanceResolver`.
     2. *External provenance verification (`FundingProvenanceResolver`)*: In production,
        `KrakenFundingProvenanceResolver` batches authenticated `/0/private/DepositStatus` and
        `/0/private/WithdrawStatus` requests over the ledger range (with a bounded correlation margin),
        paginates with Kraken's `cursor`/`limit` parameters, and caches the fetched families while they
        cover the batch. It does not make one funding request per ledger row. A direct reference or fuzzy
        candidate must agree on the ledger family, normalized asset, direction, gross/net amount, fee when
        authoritative, timestamp, and terminal status; for withdrawals the account-debit alternative is
        `amount + fee`, while deposit credit uses `amount - fee`. Fuzzy correlation accepts exactly one candidate only.
        Zero, duplicate, or contradictory candidates remain unresolved. The legacy status endpoints are
        active but deprecated in Kraken's API documentation; Spot REST does not provide a historical
        Futures-transfer query, so a Spot/Futures leg that is not explicitly marked or represented by an
        authoritative internal source remains unresolved. An indistinguishable status record cannot be
        separated from external funding by the Spot API alone. Confirmed external deposits and withdrawals
        classify as `OWNER_CAPITAL`.
     Flows for assets outside the configured allocation universe are ignored.
   - **Net Capital for Fee-Bearing Deposits**: Confirmed external deposits contribute their net capital
     (`event.netBalanceDelta() = amount - fee`) as `OWNER_CAPITAL`. ATH scales strictly on the net contributed
     funds, preventing fee drag from being misattributed as strategy loss or unproven plumbing.
   - **Prepared Card Funding Lifecycle**: ATH retains the full ledger batch for refid correlation, prepares
     one immutable `FundingProvenanceResolver` snapshot, and passes that exact prepared instance to classification,
     card normalization, and basis context. A confirmed card/consumer funding deposit is ambiguous until its
     complete plumbing shape arrives (external deposit + USD `spend` + purchased-asset `receive` for a card buy);
     incomplete rows defer ATH and remain unjournaled. Confirmed ordinary Wire/ACH funding without plumbing stays
     `NotApplicable` to `CardFundingNormalizer` and is handled as ordinary owner capital. Every funding leg in a
     normalized owner event must be `EXTERNAL`; unresolved siblings or external/internal mixtures are ambiguous,
     all-internal groups are `NotApplicable`, and multiple external funding legs are unsupported unless a future
     explicit shape is added. Only normalization groups intersecting the current undecided identity set can block
     the current ATH; retained decided groups remain context, while a group split between decided and newly arrived
     identities fails closed rather than applying only the new sibling.
   - **Synthetic Capital vs Actual Effects**: `NormalizedFundingTransaction.OwnerContribution` and
     `OwnerWithdrawal` carry both `netOwnerCapitalUsd` (the synthetic amount used for ATH scaling and Buy & Hold
     inception-weight allocation) and exact per-leg `AssetDelta` values derived from `LedgerEvent.netBalanceDelta()`.
     Buy & Hold consumes only the synthetic amount and never replays the conversion legs. ATH basis reconstruction
     replays completed card actual deltas, including fees, exactly once and excludes both the representative funding
     row and raw card plumbing rows from separate replay.
   - **Ambiguous Funding Deferral & Fail-Closed Safety**: Unlike terminal neutral events (`INTERNAL_MOVE`,
     `TRADE_IGNORED`) or performance events (`EXTERNAL_BALANCE`) which are acknowledged in the decision journal,
     flows classified as `AMBIGUOUS` or `UNSUPPORTED` MUST NOT be journaled as decided or skipped. Instead, they
     fail closed by deferring the entire ATH update (`AthUpdateResult.Deferred`), preserving the last trusted
     drawdown and forcing fiat deployment to zero. Every deferred result carries a structured
     `AthTrustFailureReason`: `LEDGER_COVERAGE_STALE`, `LEDGER_COVERAGE_UNKNOWN`,
     `FUNDING_PROVENANCE_UNAVAILABLE`, `AMBIGUOUS_FUNDING`, `UNSUPPORTED_LEDGER_EVENT`,
     `HISTORICAL_PRICE_UNAVAILABLE`, `PRE_FLOW_BASIS_UNCERTAIN`, `BALANCE_OBSERVATION_UNCERTAIN`,
     `EVENT_ORDERING_UNCERTAIN`, or `PERSISTENCE_FAILURE`. The reason is logged and exposed as
     `lastAthDeferredReason` in backend health status; it is diagnostic only and every deferral still
     forces fiat deployment to zero. They remain unjournaled so future sync cycles or operator
     reconciliations can re-evaluate them with fresh metadata, and once resolved with affirmative evidence, they
     apply exactly once.
   - **Ledger Coverage Ceiling & Identity-Driven Reconciliation**: ATH flow processing is upper-bounded by
     confirmed ledger synchronization coverage (`SyncMetadataKeys.LEDGER_WATERMARK_EPOCH_SEC`), ensuring events
     cannot be skipped if a rebalance cycle runs before ledger polling catches up. The cycle takes its balance
     observation before the ledger sync that stamps the coverage watermark, so coverage normally confirms the
     whole observation; the reconciliation horizon is the earlier of the two, and rows between the observation
     and a wider coverage wait for the next cycle because they are not in the observed total yet.
     When balances were observed
     after ledger coverage, the whole ATH update defers: the balance must neither establish a new ATH nor produce
     a drawdown that drives fiat deployment, so the cycle preserves the last trusted drawdown and forces
     deployment to zero. Unknown or missing ledger coverage with a dated observation defers the same way
     (a total that may contain unseen owner capital must never ratchet ATH); a malformed flow watermark
     also defers with no state advanced, leaving the key for the operator to repair. Which rows still need a
     decision is determined by identity, not timestamp: every retained ledger row up to the reconciliation
     horizon is rescanned and the applied-flow journal filters what was already decided, so late-arriving backfill
     below an old watermark is reconciled exactly once.
     *Performance & Storage Tradeoff*: Rescanning every retained row is linear in the retained ledger set, which
     is naturally bounded by the 90-day retention horizon (typically a few thousand rows in active accounts).
     This design choice prioritizes correctness and exact-once reconciliation over sliding-window heuristics,
     as bounded overlap cursors can silently miss backfilled rows older than their window. Future optimization
     paths include an indexed database status column or a hybrid bounded overlap cursor with periodic full sweeps.
   - **Pre-Flow Basis Reconstruction with Intervening Balance Replay**: Flows apply sequentially oldest-first
     (simultaneous flows net into one step), each against its event-time pre-flow basis. The basis reconstructs
     exact portfolio holdings immediately before the flow:
     `holdings_at_flow = predecessor_holdings + replayed_trades + replayed_external_balances + replayed_actual_card_deltas + replayed_crypto_flows`.
     Successful non-dry-run trades adjust tracked crypto quantities and fiat outlays/proceeds (including fees),
     off-universe trades adjust only the fiat leg, and intervening `EXTERNAL_BALANCE` events (such as staking
     rewards, ledger adjustments, or dividends occurring between predecessor snapshot and the flow event time)
     are replayed in-kind into holdings before valuation. Holdings are then revalued at event-time prices:
     `Pre-flow basis = sum(holding_i * price_at_flow_i)`.
     The predecessor's `balancesObservedAt` is the lower request-start boundary; legacy snapshots without it
     fall back to their save timestamp. Ledger rows in the uncertain interval
     `(balancesObservedAt, predecessor.timestamp]` are accepted only when authoritative post-event balances
     prove one unique embedded prefix; ambiguous, missing-balance, or same-timestamp rows defer the update
     instead of receiving a lexical order. If a modern snapshot is observed before the flow but saved after
     it and no snapshot saved before the flow establishes the pre-flow state, the update also defers. Flow-time
     prices are resolved strictly from event-time evidence:
     first from a successful non-dry-run trade at or before the event within ±180s, then from the nearest
     recorded snapshot at or before the event within ±180s, and finally from a completed 15-minute OHLC candle
     whose `candleStart + 900s <= eventTime` (an exact candle end is valid). Future trades/snapshots and active
     candles are excluded. Live exchange ticker prices are strictly decoupled from historical lookups: live
     tickers are only permitted for near-real-time events within a tight 300-second window
     (`MAX_NEAR_REALTIME_TICKER_WINDOW_SECONDS = 300L`). Historical flows older than 300s without verified
     historical trade, snapshot, or completed OHLC pricing fail closed by deferring the update
     (`AthUpdateResult.Deferred`).
     If no predecessor snapshot exists at all, the legacy/initial-baseline case assumes the flow predates ATH
     establishment and journals it as absorbed; a modern observation boundary that proves a later-saved snapshot
     could have observed the flow instead fails closed as described above.
   - **Crash-Idempotent Checkpoint & Migration Limitations**: The ATH value, applied per-ledger flow identities,
     and the flow watermark persist in a single SQLite transaction. A crash before commit retries safely; after commit
     nothing is double-applied — restarts skip recorded ledger IDs even inside a held watermark window.
     The journal is a lifetime decision log: it is never pruned by the watermark. When the initial ATH is
     established, undecided decision-bearing rows below the observation are journaled as absorbed.
     *Migration Limitation*: Databases upgraded from older timestamp-window releases perform a one-time migration
     (`SyncMetadataKeys.ATH_FLOW_JOURNAL_MIGRATED`): rows below the legacy watermark whose journal entries were
     pruned under earlier versions are presumed decided, so historical flows are not double-counted. Forcing a
     genuine re-scan requires restoring a pre-scaling database backup.
   - **Safety & Persistence**: Missing or explicitly null stats represent an empty initial
     state. A database read or legacy-file migration failure aborts the analysis
     before ATH persistence or order planning, rather than treating the ATH as
     zero. Any non-cancellation ATH persistence failure logs an error and aborts
     the cycle (fail-closed) so the bot never plans orders against an unpersisted
     All-Time High. Cancellation still propagates so a cancelled cycle cannot
     continue.
2. **Drawdown Calculation**:
   `Drawdown % = (ATH - Current Value) / ATH * 100`
   The numerator is multiplied by 100 before division so the result retains all
   four internal percentage decimal places.
3. **Fiat Deployment Percentage**:
   Based on the configured `fiatMaxDrawdown` (e.g., 30%), `fiatDeploymentExponent` (e.g., 1.0), and optional
   `fiatDeploymentThresholdPercent` (e.g., 2.0% deadband):
   - If `Drawdown % < fiatDeploymentThresholdPercent`, `Deployment % = 0` (suppresses micro-drawdown deployment).
   - If `Drawdown % >= fiatDeploymentThresholdPercent`:
     `Effective Drawdown % = Drawdown % - fiatDeploymentThresholdPercent`
     `Effective Max Drawdown % = max(fiatMaxDrawdown - fiatDeploymentThresholdPercent, 0.0001)`
     `Deployment % = (Effective Drawdown % / Effective Max Drawdown %) ^ Exponent` (Capped at 100%)

   Fractional exponents use `Double.pow`, then the result is re-entered as
   `BigDecimal` at percent scale (`SCALE_PERCENT = 4`). When `fiatMaxDrawdown ≤ 0`
   or `fiatDeploymentExponent ≤ 0`, deployment is **disabled** (`Deploy% = 0`).

   **Examples (Max Drawdown = 30%, Threshold = 0%)**:

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
   The removed allocation is redistributed proportionally to crypto assets,
   ensuring the total remains 100%. If there is no positive non-usd target to
   receive that allocation, fiat deployment is a no-op and the configured USD
   target remains unchanged.

Using these effective targets, the **Ideal Value** for each asset is calculated.

### 2. Deviation Calculation

The difference between current and target value is calculated:
`Deviation (USD) = Current Value - Target Value`
`Deviation (%) = Deviation (USD) / Target Value * 100` (signed relative
deviation). The deviation numerator is multiplied by 100 before division so
trigger math retains `SCALE_PERCENT` precision. When the target value is `$0`
but the holding still has a
positive value, `Deviation (%)` is treated as **100%** so a zero-target
position can still clear the percent trigger (paired with the dust gate).

### 3. Trigger Logic

An asset generates an order only when **both** gates pass:

1. Absolute relative deviation
   `|Deviation (%)| ≥ deviationTriggerPercent` (e.g., 5%).
2. Absolute USD deviation is significant:
   `|Deviation (USD)| ≥ minimumOrderSizeUSD` (`AssetMetrics.isSignificant`).

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
    - Sell volume is capped to the cycle-entry asset balance rounded down to
      eight decimals. Cent-rounded full-liquidation intent therefore cannot
      request more units than were held at analysis time.
2. **USD Settle (fill-confirmed, balance fallback)**: After **≥1 successful
   sell** and when **not** in dry-run mode, the system prefers **fill-confirmed**
   sell proceeds: poll trade history (same 3× backoff from **250ms**, paginating
   up to 5×50 rows) for API fills whose `ordertxid` matches the sell AddOrder
   txids, sum **net** proceeds (`cost − fee`), and set cash = opening USD +
   confirmed proceeds. When spendable USD is already visible on a balance peek,
   cash is capped to `min(fill-confirmed, balance)`. When the peek is empty or
   fails, cash is capped to **projected cash** so history cannot invent liquidity
   beyond this cycle's sell intents. Early-accept at **≥95%** of
   projected. If txids exist but no positive fills appear, or the capped
   fill-confirmed amount is below the 95% threshold, **fall back** to the legacy
   USD **balance poll** (same attempt/backoff/≥95% rules); a short result can mean
   Kraken's history index or pagination is lagging. **Abort buys** if neither
   path confirms positive USD (fail-closed). When no sell txids are available
   (e.g. some test doubles), go straight to the balance poll. Skipped
   entirely when no sell succeeded or `dryRun` is true (buys use projected cash).
   Successful sells record `cycleId` and `orderTxid` on persisted trade rows.
   Repeated nonblank Kraken trade IDs caused by shifting offset pages count
   once; id-less rows remain distinct because identical partial-fill economics
   can be legitimate.
3. **Buy Orders Second**:
    - The whole sell→buy sequence runs inside `KrakenService.withStableBackend`
      so a mid-cycle `simulation` flip cannot split sells and buys across backends.
    - A **cycle-level budget** of **99%** of post-sell settled USD caps aggregate
      multi-buy spend (`PrecisionConstants.CASH_RESERVE_FACTOR`).
    - Each buy is further capped by the remaining cycle budget; dust buys below
      `minimumOrderSizeUSD` are skipped.
    - Only successful buys deduct from available cash and the remaining budget.
4. **Order Placement**:
    - Orders are placed as **Market Orders** for immediate execution.
    - Before a real live AddOrder call, a durable `PENDING` row is written to
      `order_intents` with `clientOrderId`. A definite exchange response
      resolves that row. A transport failure, response failure, or response
      without a txid is ambiguous and marks it `UNCERTAIN`; the executor stops
      the batch.
      Cancellation persists that uncertain state in a `NonCancellable`
      durability block before propagating. Dry-run and simulation exceptions
      update the local estimate with the actual known failure instead. If that
      journal update also fails, the placement exception remains primary and
      the persistence failure is attached as suppressed diagnostic context.
    - AddOrder is **not retried** after an ambiguous response. Any unresolved
      live intent blocks subsequent live order batches and is excluded from
      sync reconciliation, duplicate cleanup, and retention pruning. An
      operator must verify Kraken open orders, closed orders, and fills before
      clearing the SQLite state; missing trade history alone is not proof that
      Kraken rejected the order.
    - Operators review unresolved rows with `GET /api/order-intents` and clear
      only `UNCERTAIN` rows through `POST /api/order-intents/{id}/resolve` using
      an explicit `CONFIRMED` or `REJECTED` outcome, evidence, and the optional
      Kraken `orderTxid` when known. `PENDING`
      rows cannot be terminalized while AddOrder may still be in flight;
      restart recovery converts abandoned PENDING rows to UNCERTAIN. `GET
      /api/readiness` remains `503` while any unresolved row exists.
    - "Dust" orders (below the configured `minimumOrderSizeUSD`) are skipped to
      avoid API errors.
    - USD intents are converted to crypto volumes at 8 decimal places with
      `RoundingMode.DOWN`, so submitted notional never exceeds the intent.
      Sell volumes are also floored and capped to the cycle-entry holdings.
    - `dryRun` suppresses placement on the **active** backend. Server logs use
      `[DRY RUN]` (live) or `[EMULATOR DRY RUN]` (simulation); the dashboard
      activity log always uses `[DRY RUN]`. Orthogonal to `simulation` (which
      only selects live Kraken vs the offline emulator).
    - With `simulation = true` and `dryRun = false`, the offline emulator charges
      a `0.26%` fee on each order and updates balances net of that fee. Emulator
      dry-run returns before changing balances.
5. **Persistence**: The cycle snapshot (including all trade actions and their outcomes) is saved directly to the SQLite database (under the trade and snapshot tables).

### Ledger history and staking/Earn rewards

`LedgersSyncService` pulls Kraken's private `/0/private/Ledgers` endpoint at most
once every **300 seconds**, requesting the thirteen strategy-neutral response types
(`staking`, `dividend`, `earn`, `deposit`, `withdrawal`, `transfer`, `adjustment`,
`spend`, `receive`, `margin`, `rollover`, `settled`, and `credit`) in pages of **50**. Kraken's API query filter does not
support `type=earn` (passing `type=earn` returns `EGeneral:Invalid arguments`);
the service queries `type=all` when requesting `earn` and filters rows locally
for `type == "earn"`. Similarly, the API query filter uses `type=sale` for the
consumer `spend`/`receive` rows and filters locally. Pagination for filtered queries
checks Kraken's authoritative total count (`nextOffset < totalCount`) and the
raw response page size (`rawPageSize >= 50`) so intermediate pages containing
zero target rows continue paginating until completion. A seeded installation
whose coverage version predates version `5` performs a bounded **96-day** backfill
with the same identity deduplication; ledgers remain retained for the lifetime
of the account. The first and recovered initial syncs also use a bounded **96-day**
seed window and store durable progress metadata; later syncs use the latest stored
ledger time (or watermark) with a **300-second overlap**. SQLite enforces the
`(ledger id, timestamp, asset, type)` identity so overlapping pages and retries
are safe. See Kraken's [Ledgers API reference](https://docs.kraken.com/api-reference/account-data/get-ledgers-info)
and [ledger field guidance](https://support.kraken.com/articles/360001169383-how-to-interpret-ledger-history-fields).

Funding provenance uses authenticated `DepositStatus` and `WithdrawStatus`
lookups. Kraken documents `DepositStatus` with **Funds: Query** and
`WithdrawStatus` with **Funds: Withdraw** or **Data: Query ledger entries**;
the configured **Query Funds** and **Query Ledgers** permissions therefore
cover the application's read-only use. Note: Kraken's REST documentation marks
`DepositStatus` and `WithdrawStatus` as deprecated in favor of `List Funding Deposits`
and `List Funding Withdrawals`. The endpoints will be migrated in a follow-up
pass without changing the contract or fail-closed permission semantics. A permission
denial is retained as `FUNDING_PROVENANCE_UNAVAILABLE` and logged with the required
permission.

The History `/api/history/rewards` endpoint charts `staking`, `dividend`, and
`earn/reward` entries for tracked allocation assets. It aligns cumulative
per-asset amounts to
stored portfolio snapshot timestamps, values each asset using that snapshot's
price, and returns total and per-asset USD series for the selected range.
Earn allocation mechanics are persisted for account reconstruction but are not
performance rewards; unknown Earn subtypes remain fail-closed. Dividend entries
for untracked assets remain persisted but excluded as external inflows.

For ATH and benchmark accounting, all thirteen synchronized ledger types are
classified before application and use `amount - fee` where replayed, preserving
both legs of a consumer transaction. `earn/reward` is an in-kind performance
event; Earn allocation mechanics are internal and ignored by ATH and Buy & Hold.
Historical snapshot reconstruction replays the corresponding account-balance
legs so reconstructed Spot balances remain faithful. Kraken
states that Buy Crypto Widget and Kraken app transactions appear in Ledger history
and not Trades history, so the comparison does not try to deduplicate these ledger
rows against `TradesHistory`. The reconstruction marker is paired with the ledger
coverage version it replayed, so a coverage migration cannot suppress the required
rebuild.

Benchmark events are built from the original classified ledger rows before any
passthrough reduction. Safe same-source-timestamp USD funding plumbing (`OWNER_CAPITAL`
deposit/withdrawal plus `spend`/`receive`) carries the original typed category
and every source ledger ID; it is never represented by a synthetic row that is
classified a second time. USD-only plumbing may collapse to its net economics,
while mixed-asset card plumbing collapses confirmed card transactions into net owner capital via
centralized normalization, as described below. Internal moves remain neutral and unresolved
funding remains unavailable. A mixed-sign or overdrawn funding/plumbing group is
left separate rather than being reclassified as the opposite owner-flow
direction. Where a trade, owner flow, or non-plumbing balance movement shares a
timestamp and the economic order cannot be proven, the comparison returns
unavailable rather than imposing a lexical order.

For card-funded Buy Crypto transactions, a centralized normalizer (`CardFundingNormalizer`)
governs both ATH neutralization and Buy & Hold accounting, guaranteeing identical economic
interpretation across the engine. Card funding legs must share a non-blank `refid` within a
120-second proximity window (`MAX_CARD_TRANSACTION_SPAN_SECONDS = 120L`). The normalizer
validates complete transaction shapes:

1. *3-leg USD card buy crypto*: external funding (USD deposit), USD `spend`, and purchased-asset
   `receive` with opposing USD directions. Non-USD direct-asset two-leg shapes are not currently
   a supported normalization shape and remain unavailable rather than being guessed.

Incomplete shapes (such as deposit plus spend without receive leg) or USD-only plumbing netting to zero
fail closed as ambiguous (`AMBIGUOUS_FUNDING` for ATH, `AMBIGUOUS_LEDGER_TYPE` for B&H).
Non-USD leg fees (such as BTC receive fees) are converted to USD at event-time historical prices
before deducting from gross capital; unpriceable fees fail closed (`HISTORICAL_PRICE_UNAVAILABLE`
for ATH, `MISSING_PRICE` for B&H). The confirmed transaction collapses into a single owner contribution
net of all fees ($5,000 gross deposit - $20 spend fee = $4,980 net) and allocates it strictly by original
inception weights; spend and receive legs are consumed as plumbing evidence and are not replayed
into B&H. This preserves counterfactual neutrality between the rebalancer and B&H without double-counting
assets, inventing conversion alpha, or treating transaction fees as performance drawdown.
A provenance preparation failure is reported separately as `FUNDING_PROVENANCE_UNAVAILABLE`.

Before rendering benchmark points, each interval replays every successful
authoritative trade, supported external ledger event, and fee into the previous
tracked balances. If any tracked asset still differs from the next snapshot after
rounding USD to scale 2 and crypto to scale 8, the comparison is unavailable with
`UNEXPLAINED_BALANCE_CHANGE` at that next snapshot's timestamp. It never emits
estimated numeric alpha for an unexplained tracked mutation; untracked assets remain
outside this validation boundary.

Snapshots track an explicit `balancesObservedAt` timestamp representing the local
balance-request start boundary, distinct from the snapshot creation/display
timestamp. Events after this instant are not assumed to be reflected in the returned
balances unless reconciliation proves they were. Rows written before this field existed
retain a null observation boundary; their display timestamp is used only as a bounded
search boundary, never treated as an exact request-start time. For pre-flow replay,
the predecessor snapshot's interval `(balancesObservedAt, snapshot.timestamp]` is
uncertain: an authoritative post-event balance must identify one unique embedded
prefix before those rows are excluded from replay. The comparison engine reasons about
exchange events (trades and external ledgers) relative to these conservative balance
observation boundaries. The request start is a lower bound, while snapshot creation
is the conservative upper bound of the balance-request window. Candidate events
extend through that window plus up to 1,000ms of clock skew; they are never assumed
to be included merely because they fall inside it. Reconciliation matches unique
candidate subsets across both trades and ledger events. A shared limit of 12
initial plus late candidates bounds each accounting attempt's search to at most
4,096 assignments. For legacy sub-second snapshot bursts, candidate events near an unknown boundary are assigned only when the
complete sequence has one unique reconciliation; ambiguous or unexplained changes remain
unavailable. For user-selected subranges,
an optional pre-baseline anchor snapshot ($S_0$) attributes boundary events without
modifying the displayed baseline or points. For `TradeSource.API_FILL`, replay uses the
precise `price × volume` notional when a positive fill price is available. If precise
accounting fails with an unexplained balance change, that interval retries with
the persisted USD-scale cost only when that cost is the rounded representation of the same
fill. Observation-marker presence does not select cost precision: reconstructed and live
rows can use different accounting despite both lacking the marker. Each attempt starts
from the preceding reconciled event assignments; failed attempts are discarded. Every
interval must reconcile, and the selected representation is reused during Buy & Hold
replay. An error identifies the first interval that remains unexplained after the retry.

Kraken ledger fees are denominated in the ledger asset and are persisted at crypto
precision; they must not use the four-decimal fiat trade-fee scale. Existing rows
may already contain a truncated fee. When an interval has no tracked trade and at
most one authoritative ledger event per tracked asset, reconciliation uses that
event's persisted post-ledger balance to derive the exact tracked delta. This
compatibility path is intentionally not used for mixed or repeated same-asset
events, where absolute post-event balances could be order-dependent. A genuine
zero post-event balance is intentionally treated as non-authoritative because
legacy rows used zero as the missing-balance sentinel. The accepted
delta is reused for Buy & Hold replay, and the comparison remains fail-closed when
the event sequence cannot be reconciled.

### Strategy inception & Buy & Hold benchmark semantics

The Buy & Hold benchmark answers whether the user would have more money today by
running the rebalancer versus holding the original inception investment thesis with
the same external capital over time:

- **Inception resolution** (configured date → cached detection → trade-burst
  detection → earliest snapshot) carries a confidence flag. Rather than relying on
  fragile snapshot age checks against the 90-day retention horizon, inception confidence
  relies on a durable install state marker (`SyncMetadataKeys.INCEPTION_INSTALL_TYPE`),
  falling back to checking whether history was already seeded (`tradeRepository.isHistorySeeded()`).
  On upgraded installs (where early history may have been pruned under a previous retention era),
  the earliest snapshot is NOT adopted as inception: the comparison reports
  `INCEPTION_HISTORY_TRUNCATED`, leaves the inception snapshot null without caching,
  and the user must set `inceptionDate` manually. Fresh installs record `fresh` install state
  and auto-detect inception with `InceptionConfidence.CONFIDENT`. A known inception whose anchor
  snapshot is no longer retained likewise fails closed (`INCEPTION_SNAPSHOT_PRUNED`).
- **Owner contributions after inception are invested by original inception value
  weights** (existing synthetic holdings untouched); only the new money moves.
  Confirmed card Buy Crypto transactions collapse into a single net owner contribution
  allocated by inception weights; any USD funding plumbing netting to zero fails closed
  as ambiguous. Contribution prices come only from recorded snapshots near the event —
  never a live ticker for an old contribution — and missing prices fail closed.
- **Owner withdrawals scale the whole synthetic portfolio proportionally by
  market value**, so the cash event itself creates no artificial alpha either way.
- Investment returns (staking, dividends, adjustments) replay in-kind;
  internal moves are ignored; unrecognized or ambiguous ledger rows fail closed
  (`UNSUPPORTED_LEDGER_TYPE`, `AMBIGUOUS_LEDGER_TYPE`).

### Trade economics & slippage lifecycle

Each executed order creates a **local estimate** row at rebalance time:

- **`TradeSource.LOCAL_ESTIMATE`** — `expectedPrice` from the ticker snapshot used for planning; fee from the fixed local planning estimate (`PrecisionConstants.FEE_RATE_ESTIMATE` = **0.006**); slippage computed vs that expected price.
- **`TradeSource.API_FILL`** — Kraken `/0/private/TradesHistory` fills (or reconciled rows after sync).
- **`TradeSource.LEGACY_UNKNOWN`** — a successful historical row written before
  explicit provenance, where the stored shape cannot safely distinguish a
  local estimate from an exchange fill.

During **Kraken sync**, a matching local row is updated in place: API fill price/volume/fee replace the estimate, **`expectedPrice` is preserved**, slippage is **recomputed** against the API execution price, and `source` becomes `API_FILL`. A legacy row with stored slippage remains an inferred local estimate; the null-slippage shape is not assumed to be an API fill.

Each Kraken fill also retains its exchange trade ID. Sync uses that ID as the
authoritative per-fill identity, so distinct legs of one order cannot collapse
when their rounded economics match. For older source-less rows with no
slippage, provenance is genuinely ambiguous: startup migration marks them
`LEGACY_UNKNOWN`; sync preserves them and treats only an exact conservative
fingerprint match as already imported rather than rewriting them as a fill.

Dedupe prefers settled API fills over local estimates when pair alias or estimate-vs-fill rules match (see trade-history sync skill).

## Configuration

The behavior is controlled by `rebalancer-config.json`:

| Parameter | Description |
| :--- | :--- |
| `loopDelaySeconds` | Time to wait between cycles. |
| `deviationTriggerPercent` | Sensitivity of the rebalancer. Lower values track targets closer but trade more frequently (higher fees). |
| `minimumOrderSizeUSD` | Minimum significant USD deviation **and** minimum order notional. Assets below this USD deviation do not trigger; smaller orders are also skipped at execution. **Minimum `2` (enforced in `ConfigService` + UI `min="2"`).** |
| `dryRun` | Suppresses order placement on the **active** backend. Server logs: `[DRY RUN]` live / `[EMULATOR DRY RUN]` simulation; activity log always `[DRY RUN]`. Orthogonal to `simulation`. |
| `simulation` | If set to `true`, `DynamicKrakenService` routes to `SimulatedKrakenService` (offline emulator). Empty DB pre-seeds ~**15 days** of snapshots at 6-hour steps. Ledger entries are retained indefinitely; snapshots/trades prune only before `min(90-day cutoff, inception − 5s)` and never while inception is unresolved. |
| `fiatMaxDrawdown` | The portfolio drawdown percentage at which 100% of the USD allocation should be deployed into assets. Set to `0` to disable. |
| `fiatDeploymentExponent` | Controls the aggressiveness of deployment. `1.0` is linear. Values `< 1.0` deploy more cash earlier (aggressive). Values `> 1.0` save cash for deeper dips (conservative). |
| `fiatDeploymentThresholdPercent` | Deadband threshold below which no fiat is deployed (0.0 to 100.0). Prevents micro-deployments during small drawdowns. |
| `inceptionDate` | Strategy start date (ISO-8601 string or `YYYY-MM-DD`). When omitted or blank, auto-detected from an initial successful trade burst. Future-dated values are ignored. Required on upgraded installs whose early history was pruned (`INCEPTION_HISTORY_TRUNCATED`). |

## Precision

Monetary and ratio math uses `BigDecimal` with these scales (`PrecisionConstants`):

| Constant | Scale | Use |
| :--- | ---: | :--- |
| `SCALE_CRYPTO` | **8** | Balances, prices, order volumes |
| `SCALE_USD` | **2** | USD notionals and **persisted snapshot** percent/USD display fields |
| `SCALE_PERCENT` | **4** | Internal analysis percents (drawdown, deploy, deviation triggers) |
| `SCALE_FEE` | **4** | Fiat trade fee amounts |
| `SCALE_LEDGER_FEE` | **8** | Ledger-asset fee amounts |

Snapshot/UI asset percents are rounded to `SCALE_USD` (2 dp) when persisted;
trigger math keeps `SCALE_PERCENT` (4 dp).
