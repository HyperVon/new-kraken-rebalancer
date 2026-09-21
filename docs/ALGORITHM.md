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
     dividends, observed top-level promotion `reward` rows, transfer `airdrop` credits, and `earn/reward` are investment performance that improve
     portfolio value and reduce drawdown without scaling ATH; Earn allocation mechanics remain internal.
   - **Two-Layer Funding Provenance & Flow Classification**: Kraken reuses coarse ledger types for economically
     distinct activity, so classification follows a strict two-layer architecture:
     1. *Intrinsic classification (`LedgerFlowClassifier`)*: Evaluates intrinsic ledger metadata. Same-asset
        `refid`-paired zero-net legs and known internal-subtype rows (spot/futures/staking wallet moves, earn
        allocation, migration) classify as `INTERNAL_MOVE`. Documented `transfer` wallet markers are
        `INTERNAL_MOVE` only when a complete linked two-leg pair has one debit and one credit for the
        same asset scope; lone markers and arbitrary cross-asset pairs are unsupported. Trade rows defer to `TradesHistory` (`TRADE_IGNORED`),
        margin-family rows (`margin`, `rollover`, `settled`, `credit`, `sale`) replay in-kind as `EXTERNAL_BALANCE`
        without scaling ATH, and unrecognized ledger types fail closed. Modern `earn/reward` is
        `EXTERNAL_BALANCE`; `earn/allocation`, `deallocation`, `autoallocate`, and `migration` are
        `INTERNAL_MOVE`; another Earn subtype is ambiguous. For `transfer`, exact internal subtypes,
        authoritative internal evidence, or an asset-aware same-asset zero-net pairing may prove
        `INTERNAL_MOVE`; documented `reward` and observed/documented `airdrop` subtypes are
        `EXTERNAL_BALANCE`; observed top-level Kraken promotion rows with `type=reward` are also
        `EXTERNAL_BALANCE` and never owner capital; undocumented prose descriptions (`fork`,
        `distribution`) and bare transfers remain ambiguous without
        affirmative external provenance. Obvious credit/debit amount directions and parser amount
        validity are checked before replay, so malformed decimals do not become zero flows.
        `refid` is used only to correlate
        rows and never parsed for undocumented meaning. For deposits and withdrawals, the classifier
        delegates external validation to an affirmative `FundingProvenanceResolver`.
     2. *External provenance verification (`FundingProvenanceResolver`)*: In production,
        `KrakenFundingProvenanceResolver` batches authenticated Kraken Funding (Beta)
        `GET /funding/v1/deposits` and `GET /funding/v1/withdrawals` requests over the ledger range
        (with a bounded correlation margin), follows Kraken's `next_cursor` pagination with a bounded page
        budget, resolves `method_id` through `GET /funding/v1/methods/{deposit|withdraw}`, and caches the
        fetched families while they cover the batch. It does not make one funding request per ledger row.
        Deprecated `DepositStatus`/`WithdrawStatus` requests are used only to enrich records whose modern
        method metadata is unavailable; a legacy page at the request limit or with unparseable entries is
        discarded rather than partially trusted. A direct reference (`refid` equals the funding record id)
        proves identity: the ledger family, normalized asset, direction, amount (tolerating
        representation-level drift), fee when authoritative, and terminal status must still agree, while
        booking-time lag is tolerated because Kraken can post ledgers minutes after the funding record.
        Fuzzy correlation keeps the strict time window and absolute amount tolerance and accepts exactly
        one candidate only. Zero, duplicate, or contradictory candidates remain unresolved, and
        incomplete pagination is never treated as proof that no record exists. Spot REST does not provide
        a historical Futures-transfer query, so a Spot/Futures leg that is not explicitly marked or
        represented by an authoritative internal source remains unresolved. An indistinguishable status
        record cannot be separated from external funding by the Spot API alone. Confirmed external
        deposits and withdrawals classify as `OWNER_CAPITAL`.
     Flows for assets outside the configured allocation universe are ignored.
   - **Net Capital for Fee-Bearing Deposits**: Confirmed external deposits contribute their net capital
     (`event.netBalanceDelta() = amount - fee`) as `OWNER_CAPITAL`. ATH scales strictly on the net contributed
     funds, preventing fee drag from being misattributed as strategy loss or unproven plumbing.
   - **Prepared Card Funding Lifecycle**: ATH retains the full ledger batch for refid correlation, prepares
      one immutable `FundingProvenanceResolver` snapshot, and passes that exact prepared instance to classification,
      card normalization, and basis context. A confirmed card/consumer funding deposit is ambiguous while its
      plumbing shape is incomplete (external deposit + USD `spend` + purchased-asset `receive` for a card buy);
      partial rows defer ATH and remain unjournaled. A confirmed card deposit on a cash-like asset (USD, ZUSD,
      USDC, USDT) whose identity has no spend/receive plumbing anywhere in retained history is ordinary owner
      capital at its net balance delta; the lone-deposit identity check re-groups retained rows by `refid`, so a
      distant sibling still fails closed on span or shape. Confirmed ordinary Wire/ACH funding without plumbing stays
      `NotApplicable` to `CardFundingNormalizer` and is handled as ordinary owner capital. Every funding leg in a
      normalized owner event must be `EXTERNAL`; unresolved siblings or external/internal mixtures are ambiguous,
      all-internal groups are `NotApplicable`, and multiple external funding legs are unsupported unless a future
      explicit shape is added. Only normalization groups intersecting the current undecided identity set can block
      the current ATH; retained decided groups remain context, while a group split between decided and newly arrived
      identities fails closed rather than applying only the new sibling. Historical decided card groups contribute only
      raw per-leg balance deltas without calling fee pricing providers, preventing historical pricing gaps on already-accounted
      fees from blocking new ordinary bank deposits.
   - **Synthetic Capital vs Actual Effects**: `NormalizedFundingTransaction.OwnerContribution` and
       `OwnerWithdrawal` carry both `netOwnerCapitalUsd` (the synthetic amount used for ATH scaling and Buy & Hold
       recorded-anchor allocation) and exact per-leg `TimedAssetDelta` values derived from `LedgerEvent.netBalanceDelta()`.
       Each delta maintains its ledger ID and timestamp so that basis reconstruction at an arbitrary target time never
       replays future card legs prematurely. Buy & Hold consumes only the synthetic amount and never replays the conversion legs.
       ATH basis reconstruction replays completed card actual deltas, including fees, exactly once and excludes both the
       representative funding row and raw card plumbing rows from separate replay.
   - **Decision Journal vs Actual-Balance Context Separation**: The decision journal (`applied_ath_flows`) records
      which economic owner-capital events have had their ATH scaling applied, preventing double-application on future
      scans. Reconstructing pre-flow portfolio holdings for a target event time (`resolveEventTimeBasis`) operates on
      actual account balances (`ActualOwnerFlowContext`). When a late-arriving backfilled flow is evaluated, all
      intervening ordinary owner flows (`OWNER_CAPITAL`, e.g. ACH/wire deposits and withdrawals)—whether already
      decided in earlier cycles or pending in the current batch—are replayed into holdings if they occurred within the
      basis window `(predecessor actual-state boundary, target flow time]`. Multi-leg card transactions replay their
      actual balance deltas exclusively via `TimedAssetDelta` entries; card representative deposits and plumbing rows
      are strictly excluded from ordinary owner-flow replay to ensure exact-once balance attribution.
      New durable semantic rows retain the original ledger event time as `event_time_millis`, so replay cannot move an
      already-decided owner flow across a predecessor snapshot, balance-observation, or ordering boundary through
      second-level timestamp truncation. A new semantic row must match the retained ledger timestamp exactly;
      a mismatch defers with `PRE_FLOW_BASIS_UNCERTAIN`. Legacy identity-only rows keep a null semantic timestamp
      and the existing conservative fallback. A pre-v9 semantic row with otherwise-valid meaning uses the retained
      ledger row's exact time until it can be replaced by a newly journaled decision; no timestamp precision is invented.
   - **Undecided Card Overlap Ordering Safety**: If another undecided owner-capital event falls strictly inside the
      source-time span of an undecided multi-leg card transaction (`minCardTime < other.time < maxCardTime`),
      micro-ordering between the external flow and the intermediate card conversion legs cannot be proven without
      exchange execution sequence metadata. The system fails closed with `AthTrustFailureReason.EVENT_ORDERING_UNCERTAIN`
      and leaves both flows unjournaled so future cycles or operator review can resolve them cleanly.
   - **Unusable Decided Card Funding Isolation**: Already-decided card groups that cannot be structurally reconstructed
      (e.g., legacy ambiguous state) are preserved as explicit historical uncertainty (`UnusableDecidedFundingContext`).
      To prevent historical anomalies outside the active reconstruction window from permanently blocking ATH tracking,
      an unusable group triggers `PRE_FLOW_BASIS_UNCERTAIN` only if its source-time span intersects the active basis
      reconstruction interval `(predecessor actual-state boundary, target flow time]`. Groups entirely preceding the
      predecessor observation or following the target event are safely ignored.
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
     is retained for the account lifetime and is typically a few thousand rows in active accounts.
     This design choice prioritizes correctness and exact-once reconciliation over sliding-window heuristics,
     as bounded overlap cursors can silently miss backfilled rows older than their window. Future optimization
     paths include an indexed database status column or a hybrid bounded overlap cursor with periodic full sweeps.
     *Post-Horizon Lookahead Is Context Only*: within the bounded card-transaction lookahead window
     (`MAX_CARD_TRANSACTION_SPAN`, 120s), same-refid rows beyond the reconciliation horizon are read only to
     recognize that a known card group is incomplete. They never enter provenance preparation, classification,
     card normalization, owner-flow economics, initial-ATH absorption, or the applied-flow journal. A
     transaction is treated as economically complete only when every source leg is at or before the confirmed
     horizon; otherwise the cycle defers with `AMBIGUOUS_FUNDING` and journals nothing, so a pre-horizon card
     deposit can never be journaled while its spend/receive legs are still beyond the horizon. A
     lookahead-incomplete group blocks the cycle only while it still has an undecided pre-horizon leg; a fully
     decided group also uses confirmed rows only. Incomplete historical context defers when it intersects
     the active reconstruction interval. Missing-watermark bootstrap validates the same absorption rules;
     legacy journal migration waits if its watermark exceeds the confirmed horizon.
   - **Pre-Flow Basis Reconstruction with Intervening Balance Replay**: Flows apply sequentially oldest-first
     (simultaneous flows net into one step), each against its event-time pre-flow basis. The basis reconstructs
     exact portfolio holdings immediately before the flow:
     `holdings_at_flow = predecessor_holdings + replayed_trades + replayed_external_balances + replayed_actual_card_deltas + replayed_historical_owner_flows`.
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
     first from a successful non-dry-run trade in the preceding 180s, then from the nearest
     recorded snapshot in the preceding 180s, and finally from a completed 15-minute OHLC candle
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
     *Durable Owner-Capital Semantics (schema v8; exact event time in schema v9)*: alongside each journaled identity, the
     `ath_applied_flows` journal persists the decision category, asset, actual balance delta, optional
     normalized group (card refid), decision version, and exact `event_time_millis` for new semantic rows in the same
     checkpoint transaction. Already-decided
     ordinary owner flows (ACH/wire deposits and withdrawals) replay from these persisted semantics, so a
     late-arriving flow still reconstructs the correct pre-flow basis even when exchange provenance no longer
     proves the historical event; current authoritative provenance remains mandatory only for rows that have
     not been decided yet. New persisted semantic timestamps and raw ledger identity must agree at millisecond
     precision; disagreement fails closed with `PRE_FLOW_BASIS_UNCERTAIN`. Pre-v9 semantic rows use the retained
     ledger timestamp when their exact column is null. Legacy identity-only journal rows (pre-v8 or presumed by the migration below)
     keep their prior behavior when current provenance still proves owner capital and fail closed instead of
     silently omitting when it cannot, if the row intersects the active reconstruction interval.
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

### Ledger history and external rewards

`LedgersSyncService` pulls Kraken's private `/0/private/Ledgers` endpoint at most
once every **300 seconds**. Coverage-grade synchronization (`CURRENT_LEDGER_COVERAGE_VERSION = "10"`) queries
unprojected Kraken ledgers (`types = null`) so that all raw ledger records—including top-level `trade`
checkpoint rows and unknown future ledger types—are captured and persisted. Ordinary non-coverage
sync passes fall back to the fifteen retained balance-affecting response types
(`staking`, `dividend`, `earn`, `reward`, `deposit`, `withdrawal`, `transfer`, `adjustment`,
`conversion`, `spend`, `receive`, `margin`, `rollover`, `settled`, and `credit`) in pages of **50**. Kraken's API query filter does not
support `type=earn` (passing `type=earn` returns `EGeneral:Invalid arguments`);
the service queries `type=all` when requesting `earn`, top-level `reward`, or `conversion` and
filters rows locally for the requested response type. Similarly, the API query filter uses `type=sale` for the
consumer `spend`/`receive` rows and filters locally. Pagination for filtered queries
checks Kraken's authoritative total count (`nextOffset < totalCount`) and the
raw response page size (`rawPageSize >= 50`) so intermediate pages containing
zero target rows continue paginating until completion. A seeded installation whose coverage
version predates version `10` backfills from the configured inception date when it predates the
default window, otherwise it performs the bounded **96-day** backfill with unprojected ledgers
with the same identity deduplication and records the covered lower bound; a later earlier
configured inception triggers another bounded migration backfill. Ledgers remain retained for the
lifetime of the account. The first and recovered initial syncs use the configured inception when
it predates the default window, otherwise **96 days**, and store durable progress metadata; later syncs use the latest stored
ledger time (or watermark) with a **300-second overlap**. SQLite enforces the
`(ledger id, timestamp, asset, type)` identity so overlapping pages and retries
are safe. See Kraken's [Ledgers API reference](https://docs.kraken.com/api-reference/account-data/get-ledgers-info)
and [ledger field guidance](https://support.kraken.com/articles/360001169383-how-to-interpret-ledger-history-fields).

The ordinary sync captures observed top-level `type=reward` rows by querying
`type=all` and filtering the response locally because Kraken's current public
query enum does not advertise `reward` as a filter value. Inception recovery
also requests unfiltered ledger pages so unsupported or newly introduced rows
cannot disappear behind an allow-list. The classifier treats the exact
`reward` row as an in-kind `EXTERNAL_BALANCE`, never `OWNER_CAPITAL`; unknown
top-level values remain unsupported and fail closed.

Before an approved-start baseline is replayed, `AuthoritativeLedgerBalanceValidator` checks the
retained ledger sequence against Kraken's post-entry balances. It includes authoritative `trade`
rows as continuity checkpoints for this validation, and replay matches each trade to those same
rows by execution identity so the leg's recorded net wallet movement supplies the balance effect,
while `TradesHistory` remains the source of trade economics. Rows for one normalized asset and timestamp are validated as a
bounded unordered group rather than by lexically sorting ledger IDs. Snapshot reconstruction orders
same-instant events onto those recorded checkpoint links (newest first) and leaves events without
checkpoint evidence in repository order, so a recorded balance effect is never emitted at an instant
before it exists. Documented Spot/staking,
Spot/Futures, and Spot/Spot transfer markers use their mapped wallet scopes; staking rows that do
not identify a scope are resolved against all compatible known scopes, or seed a new opaque scope
only when their own balance matches their net delta within the applicable precision envelope.
The observed Kraken `SOL03`/`SOL` staking-wallet pair is accepted as a same-asset compatibility
alias; arbitrary cross-asset internal-transfer pairs remain invalid.
Existing four-decimal ledger fees are accepted
only within the precision envelope implied by that stored fee, not by a global tolerance.
Parser amount validity is persisted through schema migration `12`; existing rows retain their
legacy interpretation because SQLite does not retain the original amount text, while newly parsed
malformed amounts remain explicitly invalid. Obvious credit/debit direction violations also fail
closed. Non-authoritative rows are never treated as balance checkpoints;
an ambiguous dust-sweep scope that changes aggregate balances, incomplete internal-transfer group,
duplicate identity, malformed fee, unknown internal-transfer scope, or unresolved authoritative
mismatch fails closed with a
sanitized log diagnostic and a compact metadata reason. The validator returns the resolved wallet
scope disposition per ledger ID and baseline replay consumes that same evidence: every
non-conversion row resolved to `SPOT` changes the reconstructed configured balance, and
`STAKING`, `FUTURES`, and `OPAQUE_STAKING` rows are skipped. A zero-net row may remain
intentionally unresolved because it cannot mutate the reconstructed balance, but an unresolved
nonzero row fails closed. Trade-type rows are consumed through one shared `TradeLedgerReplay`
contract: a trade is matched to its legs by `tradeId` first, then by one exact durable `orderTxid`
or `clientOrderId` binding when the fill identity was pruned, and inverted from each leg's
recorded net movement, so a fee charged in the base asset is applied to the base balance exactly
once instead of being replayed as its rounded quote equivalent, and leg rounding follows the
  recorded movement. Missing, duplicated, unexpected, or direction-contradictory leg shapes fail
  closed, and an ambiguous identity or missing leg is never resolved with amount/time similarity;
  a missing leg is accepted only when its reported movement is provably zero. Complete
conversions retain their explicit strategy-neutral two-leg replay for actual-history reconstruction;
the pure Buy & Hold path consumes them as plumbing without synthetic scaling. Baseline replay version `14` and snapshot reconstruction
version `17` invalidate only the derived baseline and snapshot results, so completed recovery
trade/ledger streams and their offsets remain reusable. The reconstruction
universe is derived per run: configured allocations plus every replayable trade base and quote plus
non-zero-delta Spot ledger assets. Historical-only balances are seeded from the latest authoritative
retained ledger balance at or before the anchor; a missing seed fails closed as
`no authoritative balance for historical asset <symbol>`.

Pre-inception retention can prune a fill from `TradesHistory` while both of its `type=trade`
ledger legs survive. Those authoritative orphan legs are replayed into reconstruction so a
recorded execution is never silently dropped from a reconstructed historical balance. A leg group
is replayable only when it is structurally proven: either a single leg whose net movement is
provably zero, or two legs with distinct assets, one debit and one credit, a spread within one
second, an authoritative post balance, a valid fee, and a valid amount shape, resolved to
`SPOT`. A group already matched to a retained trade identity is
not replayed twice. Incomplete or contradictory groups fail reconstruction closed, and non-Spot
groups are left out, because neither can be proven to have moved the strategy wallet.

The implementation was validated against a sanitized forensic copy containing
conversion, funding, reward, trade, and documented transfer activity. No
account-specific row counts, amounts, identifiers, or credentials are part of
this repository's algorithm contract; the inventory is intentionally described
by behavior rather than copied from an account export. The observed copy is
evidence for the supported classifications, not a closed-world assertion that
Kraken can never return another type or subtype.

Funding provenance uses authenticated Kraken Funding (Beta) deposit and withdrawal
history. Kraken documents `List Funding Deposits` and `List Funding Withdrawals`
with **Funds: Query**, so the configured **Query Funds** permission covers the
application's read-only use. Records whose modern `method_id` is no longer listed
fall back to the deprecated `DepositStatus`/`WithdrawStatus` endpoints
(**Funds: Query** for deposits; **Funds: Withdraw** or **Data: Query ledger entries**
for withdrawals) for method metadata only; enrichment failure degrades to unresolved
provenance and never fails the whole read. A permission denial is retained as
`FUNDING_PROVENANCE_UNAVAILABLE` and logged with the required permission.

The History `/api/history/rewards` endpoint charts `staking`, `dividend`, top-level
promotion `reward`, transfer `airdrop` credits, and `earn/reward` entries for tracked allocation assets. It aligns cumulative
per-asset amounts to
stored portfolio snapshot timestamps, values each asset using that snapshot's
price, and returns total and per-asset USD series for the selected range.
Earn allocation mechanics are persisted for account reconstruction but are not
performance rewards; unknown Earn subtypes remain fail-closed. Dividend entries
for untracked assets remain persisted but excluded as external inflows.

For ATH and actual-history accounting, all supported persisted ledger types—including
observed top-level promotion `reward` rows recovered from unfiltered pages—are
classified before application and use `amount - fee` where replayed, preserving
both legs of a consumer transaction. Top-level `reward` and `earn/reward` are
in-kind performance events; Earn allocation mechanics are internal and ignored
by ATH and Buy & Hold. A complete `conversion` group is an internal transformation:
the actual-history path replays each source and destination leg once with its own
balance delta and fee. The pure Buy & Hold path validates and consumes the group
as plumbing but emits no synthetic conversion or trade event. Incomplete or
contradictory conversion groups fail closed.
Historical snapshot reconstruction replays the corresponding account-balance
legs so reconstructed Spot balances remain faithful. For internal wallet moves, a Spot debit is
reversed into the earlier balance and a Spot credit is reversed out; non-Spot counterpart legs are
ignored. Same-scope Spot-to-Spot pairs are both applied once, so their net-zero balance effect
remains net zero. The reconstruction dynamically walks backward to the configured `inceptionDate`,
generating daily close snapshots and an inception anchor using historical
Kraken OHLC daily pricing (`interval = 1440`). This bounds consecutive snapshot intervals to `<= 86,400L`
seconds, eliminating historical coverage gaps and enabling continuous Rebalancer vs. Buy & Hold
comparison across the entire strategy lifecycle. Kraken
states that Buy Crypto Widget and Kraken app transactions appear in Ledger history
and not Trades history, so the comparison does not try to deduplicate these ledger
rows against `TradesHistory`. Reconstruction version `17` records the continuous history start and
is paired with the ledger and trade coverage versions it replayed, so a coverage migration cannot suppress
the required rebuild. Each reconstruction trigger captures a single time anchor that flows through
coverage check, event range, balance state, and `SNAPSHOT_RECONSTRUCTION_THROUGH` (which equals the
anchor itself): raw trade/ledger horizons must prove through at least the anchor second — no 300s
stale-evidence tolerance — because a lagging horizon can hide a trade between evidence end and the
balance observation. On either history stream, a newly inserted fill inside the inclusive
reconstruction interval `[SNAPSHOT_RECONSTRUCTION_START, SNAPSHOT_RECONSTRUCTION_THROUGH]`, or a
reconciled fill whose economics materially changed, invalidates the reconstruction.

When a seeded database migrates to ledger coverage version `10` or trade coverage version `2`, the migration may reuse completed
inception-recovery coverage only when both private-history streams are complete, their durable
offsets/version and total/oldest-row evidence reach the required lower bound, and the persisted
account-scope binding matches the scope validated for the current run. It then fetches only an
unproven tail after the recovery horizon. Insufficient, later-starting, account-mismatched,
failed, or partial evidence falls back to the required historical backfill; an old retained row
alone never promotes coverage.

Benchmark events are built from the original classified ledger rows before any
passthrough reduction. Safe same-source-timestamp USD funding plumbing (`OWNER_CAPITAL`
deposit/withdrawal plus `spend`/`receive`) carries the original typed category
and every source ledger ID; it is never represented by a synthetic row that is
classified a second time. USD-only plumbing may collapse to its net economics,
while mixed-asset card plumbing collapses confirmed card transactions into net owner capital via
centralized normalization, as described below. Complete conversions and complete
refid-linked consumer groups are validated and consumed as plumbing; they do not
become synthetic Buy & Hold events. Unlinked consumer passthrough rows are also
excluded from the passive event stream because their missing counterpart cannot
prove an independent credit or charge. Internal moves remain neutral and unresolved
funding remains unavailable. A mixed-sign or overdrawn funding/plumbing group is
left separate rather than being reclassified as the opposite owner-flow
direction. Where owner flows or non-plumbing balance movements share a timestamp
and the economic order cannot be proven, the comparison returns unavailable rather
than imposing a lexical order.

For card-funded Buy Crypto transactions, a centralized normalizer (`CardFundingNormalizer`)
governs both ATH neutralization and Buy & Hold accounting, guaranteeing identical economic
interpretation across the engine. Card funding legs must share a non-blank `refid` within a
120-second proximity window (`MAX_CARD_TRANSACTION_SPAN_SECONDS = 120L`). Buy & Hold comparison
normalizes card transactions once across the full queried lifetime ledger set before building benchmark
events, ensuring user-selected display windows (e.g. 24h, 7d, 30d, 90d, All) never split multi-leg card
transactions across window partitions or cause false unavailable states. The normalizer
validates complete transaction shapes:

1. *3-leg USD card buy crypto*: external funding (USD deposit), USD `spend`, and purchased-asset
   `receive` with opposing USD directions. Non-USD direct-asset two-leg shapes are not currently
   a supported normalization shape and remain unavailable rather than being guessed.

Incomplete shapes (such as deposit plus spend without receive leg) or USD-only plumbing netting to zero
fail closed as ambiguous (`AMBIGUOUS_FUNDING` for ATH, `AMBIGUOUS_LEDGER_TYPE` for B&H). A lone cash-like
card deposit with authoritative external provenance and no retained plumbing is instead an ordinary owner
contribution at its net balance delta; the same rule does not apply to crypto assets, withdrawals, internal
subtypes, or any group with a sibling leg.
Non-USD leg fees (such as BTC receive fees) are converted to USD at event-time historical prices
before deducting from gross capital; unpriceable fees fail closed (`HISTORICAL_PRICE_UNAVAILABLE`
for ATH, `MISSING_PRICE` for B&H). The confirmed transaction collapses into a single owner contribution
net of all fees ($5,000 gross deposit - $20 spend fee = $4,980 net) and allocates it strictly by the fixed
recorded-anchor value weights; spend and receive legs are consumed as plumbing evidence and are not replayed
into B&H. This preserves counterfactual neutrality between the rebalancer and B&H without double-counting
assets, inventing conversion alpha, or treating transaction fees as performance drawdown.
A provenance preparation failure is reported separately as `FUNDING_PROVENANCE_UNAVAILABLE`.

Before rendering benchmark points, each interval validates and reconciles every
successful authoritative trade, supported external ledger event, and fee against
the previous tracked balances. If any tracked asset still differs from the next
snapshot after rounding USD to scale 2 and crypto to scale 8, the comparison is
unavailable with `UNEXPLAINED_BALANCE_CHANGE` at that next snapshot's timestamp.
Trades and internal conversions are reconciliation evidence for the actual series,
not synthetic Buy & Hold events; the passive path applies only eligible external
movements and normalized owner flows. It never emits estimated numeric alpha for
an unexplained tracked mutation; untracked assets remain outside this validation
boundary. Baseline holdings outside the derived configured-universe series scope
are never recorded by the legacy snapshot writer and reconcile only at the anchor;
when no series scope is derivable, a non-zero implied balance absent from the
series still fails closed as `UNSUPPORTED_TRADE`. The same scope rule governs the
universe-split conversion rescue: a complete-wallet anchor may carry zero-balance
keys for assets the writer never records, so a counterpart is treated as untracked
only when it is neither economically present at the anchor (non-zero balance) nor
ever observed by the post-baseline series — a conversion into an asset the series
does record stays closed. The Buy & Hold benchmark keeps
owning those out-of-scope holdings from the anchor while the recorded rebalancer
series only ever sees rows it contains, so reported differences include their
price movement; a holding the price provider cannot price at a point is omitted
from the benchmark composition at that point.

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
interval must reconcile, and the selected representation is retained only for the
actual balance replay. Pure Buy & Hold does not replay the successful fill or internal
conversion as a synthetic trade. An error identifies the first interval that remains
unexplained after the retry.

Kraken ledger fees are denominated in the ledger asset and are persisted at crypto
precision; they must not use the four-decimal fiat trade-fee scale. Existing rows
may already contain a truncated fee. When an interval has no tracked trade and at
most one authoritative ledger event per tracked asset, reconciliation uses that
event's persisted post-ledger balance to derive the exact tracked delta. This
compatibility path is intentionally not used for mixed or repeated same-asset
events, where absolute post-event balances could be order-dependent. A genuine
zero post-event balance is intentionally treated as non-authoritative because
legacy rows used zero as the missing-balance sentinel. The accepted delta is reused
for actual balance replay and for any eligible external ledger movement in the
passive benchmark; trade and conversion deltas are never turned into synthetic Buy
& Hold events. The comparison remains fail-closed when the event sequence cannot be
reconciled.

### Strategy inception & Buy & Hold benchmark semantics

The Buy & Hold benchmark answers whether the user would have more money today by
running the rebalancer versus holding the recorded anchor basket with the same
external capital over time:

- **Inception recovery is separate from ordinary sync.** On startup and during the normal loop,
  the shared account-scope guard must validate the active Kraken scope before any private history,
  balance observation, or recovery write. Empty databases may bind a hashed scope; non-empty legacy
  databases without a binding, unavailable scopes, and mismatches fail closed without claiming the
  existing history. `InceptionRecoveryService` then runs at most four private-history pages per
  invocation, continuing healthy incomplete batches after ~30 seconds while retaining a conservative
  five-minute retry delay for failures and transient conditions. It uses the authenticated Kraken
  TradesHistory and Ledgers endpoints, a fixed inclusive recovery horizon captured on the first run,
  and separate durable offsets, totals, oldest-record markers, status, version, and
  configuration/account fingerprint. A resumed incomplete stream re-reads one page of overlap
  before advancing its offset. When Kraken omits a current total, only a short raw page proves
  completion; stale persisted totals cannot terminate the stream. The existing ordinary
  bounded seed/incremental cursor and `history_seeded` marker are not changed by recovery.
- **The evidence contract is intentionally conservative.** Kraken exchange trade IDs and order IDs
  prove fill provenance and pagination coverage, but not which local strategy created a fill. A
  non-dry-run fill is positively bot-owned only when it retains local cycle/client-order metadata or
  matches a durable rebalancer order-intent identity. An authoritative API fill without that local
  evidence remains `UNKNOWN` for recovery; it is never inferred to be manual/external merely because
  no bot candidate exists. The earliest positively bot-owned non-USD fill is a candidate only after
  both recovered streams are complete. An unknown successful trade at or before that candidate
  makes the result ambiguous; a misleading manual multi-symbol burst cannot establish inception,
  and no bot evidence produces `COMPLETE_NO_BOT_EVIDENCE`.
- **Automatic confirmation requires a baseline as well as a candidate.** The service selects the
  latest retained balance anchor whose timestamp and `balancesObservedAt` are not after the fixed
  recovery horizon, verifies the configured allocation universe at the anchor, derives the
  reconstruction-only universe (replayable trade bases and quotes plus non-zero-delta Spot ledger
  assets) with authoritative balance seeds, then reverse-replays complete supported events from the
  candidate through that anchor. Each trade replays with its real base and quote asset from the stored
  pair (`Asset.splitTradingPair`); a non-USD quote is never reinterpreted as USD cost, unknown quotes
  fail closed, and the reconstructed baseline carries historical-only assets at their true balances
  with no target allocation. Trades are safely de-duplicated for this
  read-only accounting pass without changing stored identities. Ledger fees remain at ledger-asset
  precision; authenticated funding provenance and complete card/consumer refid groups are required,
  while unknown, unsupported, inconsistent, or ambiguous events fail closed. The synthetic baseline
  is persisted at exactly `candidate timestamp - 1 ms` with `balancesObservedAt = null`; its snapshot
  and evidence metadata are written atomically. Existing valid baseline identities are reused rather
  than overwritten.
- **Historical prices only.** Baseline valuation uses a retained successful non-dry trade or balance
  snapshot no more than 180 seconds before the baseline, then completed 15-minute, 60-minute,
  240-minute, or daily OHLC candles whose close is at or before the baseline and no more than one
  bucket old. Retained pair identities allow historical or delisted markets; non-USD quotes require
  the same bounded historical USD conversion ladder. The current ticker is never used for an old
  price, and a future trade or candle cannot outrank past evidence. The candidate asset may use only
  its own execution price at the candidate-minus-one-millisecond baseline. Missing historical prices
  remain `MISSING_PRICE`; an operational historical-source outage remains a distinct retryable
  source error. Missing retained anchors, negative reconstructed balances, a non-positive total
  baseline, a snapshot that drops a configured baseline asset, or incomplete funding groups leave
  the comparison unavailable.
- **Resolution and retention states are durable.** `IN_PROGRESS` and `FAILED` retain resumable
  coverage; `AMBIGUOUS`, `COMPLETE_NO_BOT_EVIDENCE`, and `BASELINE_UNAVAILABLE` explain why no
  lifetime baseline was confirmed; `CONFIRMED` records the candidate, source, baseline identity,
  configuration fingerprint, and reason; `MANUAL_OVERRIDE` honors an explicit `inceptionDate`.
  Changing the inception override, allocation shape, or account scope invalidates automatic
  evidence. A durable `CONFIRMED` record is not currently trusted until the active account scope is
  validated again. A valid, non-future configured inception date immediately becomes a durable retention
  floor, even while approved-start recovery is pending; invalid or future dates do not widen retention.
  Snapshot/trade pruning keeps the five-second pre-inception boundary required by replay and retains the
  full comparison evidence from that floor onward.
- **Baseline readiness is not comparison availability.** After a confirmed or manually approved
  inception baseline, the current comparison still runs the full ownership, ledger, balance, and
  historical-price reconciliation policy. If later evidence blocks that calculation, a serialized
  bounded search advances through retained snapshots and persists `VERIFIED`, `INCOMPLETE`, or
  `EXHAUSTED` progress. A verified later timestamp is an optional comparison anchor only; accepting
  it preserves the original strategy inception and makes the same anchor explicit in configuration.
- **The successful automatic baseline proof is durable.** When the Settings evaluation proves the
  strategy inception is the effective automatic Buy & Hold baseline (`AVAILABLE` with the baseline
  exactly at inception), the proof is persisted as its own sync-metadata record — separate from the
  later-start proposal state — under a dedicated contract version: verified baseline timestamp and
  snapshot identity (position cursor plus database id), the inception bound it was anchored on, the
  config fingerprint and account scope digest at proof time, and an evidence digest of every
  snapshot, trade, and ledger row at or before a verified horizon. The next Settings evaluation
  re-validates that record — re-hashing the local snapshot, trade, and ledger evidence up to the
  stored horizon — and, when it holds, returns the proven baseline identity without
  reconciliation replay, historical-price resolution, or funding preparation; a Settings reload
  or app restart no longer replays the full historical comparison to rediscover the same
  baseline. The record fails closed: a contract version change, a different
  inception, a moved or rewritten baseline snapshot, a changed config universe or account scope, a
  reconstruction contract that is no longer current, any evidence row at or before the verified
  horizon that is later edited, backfilled, or deleted, or malformed/partial metadata each
  invalidate the record with a bounded reason and force one full re-verification that re-persists
  the proof on success. Append-only tail rows after the verified horizon — a new live snapshot,
  deposit, or trade — do not invalidate the proof, because the baseline question is anchored in the
  verified interval, not in the tail. The record answers only "is strategy inception a proven
  automatic baseline": History still calculates current comparison economics, current NAV is never
  served from it, and an explicit `comparisonStartDate` continues to govern operator-facing
  proposals exactly as before. Settings status reporting separates the two questions: the fast
  path reports the proven baseline without claiming the current comparison was evaluated, an
  unavailable message appears only when a full evaluation runs and fails, and the later-start
  proposal search never consumes the persisted proof.
- **Verification runs against the stable, coverage-confirmed historical state.** The Settings
  automatic-baseline evaluation uses the longest contiguous prefix of snapshots whose balance
  observation (`balancesObservedAt`, falling back to the snapshot timestamp for reconstructed
  rows per the reconstruction contract) lies at or before `stableThrough = min(certified ledger
  coverage horizon, certified trade coverage horizon)` — the same monotonic, evidence-certified
  metadata the reconstruction invariant requires. An uncovered snapshot starts the unstable
  tail; later snapshots may not re-enter the verification window until coverage catches up
  monotonically, and a covered observation reappearing after an uncovered one (non-monotonic
  order) defers verification outright instead of erasing an interior checkpoint. Newest live
  snapshots observed past that horizon are
  unstable-tail evidence: the full evaluation verifies through the latest stable snapshot, and
  the persisted proof's evidence horizon is that stable snapshot rather than the newest row, so
  a snapshot written while its cycle's fills and ledgers are still syncing cannot fail the
  baseline evaluation with `UNEXPLAINED_BALANCE_CHANGE`. When certified coverage is unknown or
  fewer than two stable snapshots exist, the evaluation defers with a `HISTORY_COVERAGE_STALE`
  log instead of reporting an owner-capital failure. This is evidence gating, not tolerance: no
  snapshot is dropped, skipped, or accepted without confirmed coverage, and once trade/ledger
  history catches up the previously unstable snapshot is eligible normally. History comparison
  uses the same stable-horizon gate to evaluate confirmed historical prefixes without premature
  unexplained-balance failures on uncertified live tails, and persists verified automatic baseline
  proofs directly upon successful evaluation.
- **A passive anchor is separate from strategy-inception approval.** If lifetime recovery is
  ambiguous, truncated, or has no trustworthy historical baseline, the comparison anchors at the
  earliest trustworthy retained snapshot at or after the bounded passive evidence floor that expresses
  an invested thesis: a recorded or authoritatively reconstructed row whose non-cash holdings
  reach a material exposure floor (`$5.00`, mirroring the smallest position the configured
  order-size guards can express). Sub-material dust can never fix the anchor thesis, while a
  mostly-cash first real position still anchors coherently; only a zero-investment state
  degenerates (later contributions would sit in cash rather than being invested by the anchor
  weights). There is no literal date floor. The exact retained timestamp, observation
  marker, balances, prices, and provenance are the anchor; this does not confirm the old
  strategy start, and a pending recovery state remains unavailable.
- **Coverage gaps fail closed.** Later-start proposal search is allowed only when retained snapshots cover
  the relevant strategy period continuously without missing historical eras. In upgraded installations with legacy
  pruning, continuous history start is tracked monotonically in metadata; if older candidate coverage was destroyed
  by pruning or contains a gap exceeding 24 hours, comparison availability reports `HISTORICAL_COVERAGE_GAP`
  and no retained snapshot is presented as the earliest trustworthy lifetime strategy start. A
  passive invested anchor can still be available when the retained post-anchor snapshots themselves
  are complete and reconcile.
- **Buy & Hold preserves the recorded anchor thesis.** The basket starts with every positive holding
  in the selected recorded anchor, using its actual balance, historical price, and value proportion
  normalized to project precision. It is not an equal-capital recreation of current targets: the
  target percentages, later configuration changes, trade ownership labels, internal conversions,
  and consumer-transaction plumbing never rewrite the anchor lots. Consequently the first actual and
  B&H values normally match within rounding tolerance. A passive re-anchor uses the same rule; it is
  a bounded recorded-state comparison, not an approval of the historical strategy inception.
- **Comparison reconciles actual holdings through recorded base/quote semantics.** Every successful
  trade in the interval is replayed through `Asset.splitTradingPair`, so a delisted or no longer
  configured USD market (for example `STRCZUSD`) adjusts the tracked quote balance and its base
  holding instead of making the whole comparison unavailable; a pair with unknown quote semantics
  still fails closed. Snapshots may carry historical-only assets beyond the configured targets, but
  every non-zero balance must be produced by the replayed baseline, trades, or ledger events — an
  unexplained appearance fails closed. Asset-universe validation compares the current configured
  allocation membership (including configured zero-weight assets), not every historical-only row in
  the full-wallet baseline. A historical-only row may therefore be absent from a later
  configured-only snapshot once reconciliation explains its balance change; a same-instant
  configured-only legacy row cannot replace the approved full-wallet anchor; dropping a configured
  baseline asset still reports `ASSET_UNIVERSE_CHANGED`. Legacy/unknown-observation boundaries
  follow the same rule: a boundary ledger for an omitted historical-only asset counts as already
  embodied only when its authoritative post-balance is zero, so absence is never read as wallet
  truth and a nonzero post-balance stays a candidate the recorded series must explain.
- **Recorded history exposes one final state per instant and only spot-wallet effects.**
  Reconstruction persists a row per replayed event, so several cumulative rows can share a
  millisecond; the chart keeps the first row written for an instant (the state
  after every event of that instant) before down-sampling. The comparison instead reconciles
  the full retained series — intermediate same-instant states are reconciliation evidence
  that collapsing would destroy — and only down-samples the resulting comparison points
  for display. Ledger rows resolved to Kraken's
  staking or futures wallet scopes never move comparison balances, mirroring the recorded
  series, while linked internal-transfer pairs are still classified over the full ledger set.
  A trade whose quote asset never enters the recorded universe settles only its tracked leg;
  a tracked quote without a recorded balance fails closed. A one-unit crypto quantity offset
  left by backward replay from live balances is tolerated, while quote cash stays cent-exact;
  the comparison remains fail-closed (`UNEXPLAINED_BALANCE_CHANGE`) when the recorded series
  is inconsistent with retained trade and ledger evidence. Owner-flow ordering is also
  fail-closed when a withdrawal overlaps a tracked balance reduction, or when contribution and
  withdrawal plumbing share source evidence whose sequence cannot be proven. Trades and internal
  conversions do not create synthetic benchmark events and therefore do not introduce a passive
  ordering conflict.
- **Display windows are applied after accounting.** The History range (`24h`, `7d`, `30d`,
  `90d`, or `all`) selects the points returned to the chart only. The query loads the effective
  baseline through the requested end, trims the unstable tail, reconciles the complete stable
  prefix, then filters and down-samples the reconciled points to the selected window. This
  prevents a finite range from omitting an earlier checkpoint that explains a later balance
  change, and keeps overlapping range economics identical to the corresponding points in `all`.
  `latestDifferenceUSD` and `latestDifferencePercent` are taken from the last displayed point;
  fewer than two displayed points returns `INSUFFICIENT_SNAPSHOTS` without bypassing accounting.
- **Stable-history replay requires current coverage certificates.** The trade and ledger horizons
  are trusted only when both current coverage-version markers and nonnegative coverage starts are
  present, each start does not exceed its horizon, and the two account-scope digests agree with
  the shared account binding when one exists. Valid epoch seconds must also fit the downstream
  epoch-millisecond metadata representation. Event retrieval is capped at the end of the earlier
  certified second, so an uncertified live-tail event cannot be used to produce a verified
  comparison or proposal.
- **Trade ownership is not passive allocation input.** Every successful non-dry-run fill that can
  affect the tracked Spot balances is still validated against authoritative ledger legs and the
  recorded snapshots. `REBALANCER`, `MANUAL`, and `UNKNOWN` labels do not change pure Buy & Hold:
  the benchmark never mirrors a trade. Exchange trade or order IDs prove settlement, not who
  initiated it; ownership ambiguity alone does not block a re-anchored passive report. A fill that
  cannot reconcile to the actual recorded balance series still fails closed as an unexplained
  balance change.
- **Owner contributions after the selected anchor are invested by the fixed recorded-anchor value
  weights** (existing synthetic holdings untouched); only the new money moves. This is the same
  weighting policy used to capitalize the exact recorded anchor value.
  Confirmed card Buy Crypto transactions collapse into a single net owner contribution
  allocated by those fixed anchor weights; any USD funding plumbing netting to zero fails closed
  as ambiguous. Contribution prices come only from recorded history near the event —
  never a live ticker for an old contribution — and missing prices fail closed. The
  evidence ladder is the same bounded historical ladder used elsewhere: a retained USD-quoted
  execution in the wide past window (with only a small future skew when no past execution exists),
  an at-or-before recorded snapshot, a completed Kraken OHLC candle, or a trustworthy cross-quote
  conversion through the quote asset's own historical USD rate. A contribution in a historical-only asset is
  valued in USD and allocated across the recorded anchor holdings only; it never receives a
  benchmark weight and never becomes a live rebalance target.
- **Owner withdrawals scale the whole synthetic portfolio proportionally by
  market value**, so the cash event itself creates no artificial alpha either way.
- **Replayed movements are attributed to what the synthetic basket actually holds.**
  Owner contributions are valued in USD at the event time and invested by fixed anchor value
  weights rather than held in the contributed asset. Withdrawals scale the whole synthetic NAV
  proportionally. Holding-dependent rewards are mirrored in-kind only while the synthetic basket
  holds that asset; a positive reward in an otherwise unheld asset remains actual-only. Explicitly
  classified account-level credits may introduce their credited asset even when it was absent at
  the anchor. A generic USD/equity cash dividend is excluded because the crypto/cash thesis has no
  underlying equity position. Other supported independent charges and external balance movements
  retain their attributable treatment.
- **Conversions and consumer plumbing stay neutral in pure Buy & Hold.** Complete conversions with
  at least one tracked leg, and complete refid-linked consumer `spend`/`receive` groups, are
  validated and consumed once for actual-history continuity but emit no synthetic transformation,
  trade, or owner flow. Unlinked or singleton consumer passthrough rows are excluded from the
  passive event stream because their missing counterpart cannot prove an independent movement;
  incomplete linked multi-row groups fail closed. Other internal moves are ignored; unrecognized or
  ambiguous ledger rows fail closed (`UNSUPPORTED_LEDGER_TYPE`, `AMBIGUOUS_LEDGER_TYPE`).

### Buy & Hold comparison cache contract

A successful (`AVAILABLE`) Buy & Hold comparison is durably cached so repeat requests, long-running
processes, and restarts reuse the reconciled result instead of replaying history. The cache is
correctness-preserving: a cached entry is served only while the evidence the authoritative
calculation actually consumed is unchanged.

- **Entry validity.** A cache entry is keyed by the exact evaluation window (first/last stable
  evaluation snapshot timestamps) and carries an input fingerprint: SHA-256 over the cache format
  version, the certified stable horizon (`stableThrough`), the consumed-evidence digest, the OHLC
  candle content revision, the funding provenance token, the configured allocation universe, the
  reconstruction revision markers, the inception resolution, and the evaluation snapshot boundary
  (count, first, last). A read matches only when the stored fingerprint equals the fingerprint
  computed for the current request. Unavailable results are never persisted, and cache read/write
  failures fall back to the authoritative calculation.
- **Consumed evidence.** The fingerprint binds the recorded snapshots at or before the certified
  horizon, all trades and ledgers up to that horizon, the predecessor snapshot before the effective
  inception baseline, the OHLC candles consumed for historical valuations, the funding provenance
  evidence identity, and the configuration affecting benchmark semantics. The digest is row-level
  and content-derived: reordering, fee corrections, or balance edits in consumed rows change it.
- **Invalidation.** The cache misses and one authoritative replay occurs when any consumed evidence
  changes: a snapshot, trade, or ledger row is added, edited, or deleted at or before the certified
  horizon; the certified trade/ledger horizon advances; order reconciliation rewrites consumed fill
  economics; reconstruction continuity metadata changes; allocations change; funding evidence
  content changes (deposits, withdrawals, internal transfers); or a consumed OHLC candle appears or
  is corrected. An `AVAILABLE` replay then repopulates the cache with the new fingerprint.
- **Non-invalidating writes.** New live-tail snapshots beyond `stableThrough` bump the global
  evidence revision but leave the consumed-evidence digest unchanged, so the cached comparison is
  still a hit (one bounded rehash, no replay). Empty or content-identical OHLC refetches do not
  advance the OHLC content revision. Passage of time alone never invalidates.
- **Funding freshness vs durable identity.** Prepared funding provenance has a short in-memory
  freshness window (60s). When prepared evidence is absent or expired, the fingerprint falls back
  to a durable, persisted funding-evidence identity (scope, funding families, bounded request
  range, and a content fingerprint of the normalized deposit/withdrawal/internal-transfer records)
  so requests across TTL expiry and process restarts reuse the cache without funding API calls.
  Provenance decisions always use freshly prepared evidence during authoritative calculation; the
  durable identity certifies cache identity only, never provenance answers. A later authoritative
  preparation observing different funding evidence changes the durable fingerprint and invalidates
  the cache. Degraded provenance results are never cached.
- **OHLC freshness and revalidation.** Historical OHLC candles are cached in memory and persisted
  with covering-fetch proofs. A covering fetch stays authoritative for a bounded freshness window:
  successful empty responses revalidate after a short interval (so later backfills can cure
  `MISSING_PRICE` / `HISTORICAL_PRICE_SOURCE_ERROR` frontiers), candles fetched while recent
  (data ends within a day of the fetch) revalidate hourly, and clearly historical candles revalidate
  weekly. An expired covering fetch triggers exactly one single-flighted refetch per window —
  concurrent requests join the same flight. A successful refetch upserts candle corrections and
  backfills; a transient provider failure never persists a successful empty result, serves the
  stale cached series, and retries after the next window.
- **Retention.** The comparison cache retains only the newest few successful ranges (keyed by
  calculation time, then window end). Pruning runs inside the same transaction that persists a
  newer successful range, so a replacement is durably committed before superseded rows are removed
  and the table cannot grow without bound.

### Trade economics & slippage lifecycle

Each executed order creates a **local estimate** row at rebalance time:

- **`TradeSource.LOCAL_ESTIMATE`** — `expectedPrice` from the ticker snapshot used for planning; fee from the fixed local planning estimate (`PrecisionConstants.FEE_RATE_ESTIMATE` = **0.006**); slippage computed vs that expected price.
- **`TradeSource.API_FILL`** — Kraken `/0/private/TradesHistory` fills (or reconciled rows after sync).
- **`TradeSource.MANUAL`** — explicit user/external trade evidence. It is not inferred from a
  settled exchange fill merely because local bot evidence is absent.
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
| `inceptionDate` | Optional manual strategy start (ISO-8601 string or `YYYY-MM-DD`). When blank, bounded recovery seeks complete Kraken coverage plus positive local bot-ownership evidence and a reconstructable baseline; if lifetime recovery remains ambiguous or truncated, pure Buy & Hold may use an exact recorded snapshot on or after its separate passive evidence floor. Future-dated values are ignored. An explicit date remains a manual override and still needs a retained baseline anchor for comparison. |

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
