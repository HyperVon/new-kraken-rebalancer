---
name: portfolio-rebalancing-math
description: >-
  Portfolio rebalancing engine math — BigDecimal scales, ATH/drawdown fiat
  deployment, deviation triggers, fiat correction, dust thresholds, sell-first
  execution, durable live-order intents, and 99% cash caps. Use when changing PortfolioCalculations,
  PortfolioAnalyzerImpl, RebalancerEngine, OrderExecutorImpl, or docs/ALGORITHM.md.
---

# Portfolio Rebalancing Engine Math

Canonical deep doc: [`docs/ALGORITHM.md`](../../../docs/ALGORITHM.md).

Primary code:

- `RebalancerEngine` — domain calculator (valuation, drawdown, targets, deviations,
  fiat correction); no network/DB
- `PortfolioCalculations` — shared % / target / deviation math
- `PortfolioAnalyzerImpl` — snapshot/ATH I/O; delegates math to `RebalancerEngine`
- `OrderExecutorImpl` — sell-first execution, USD settle, buy cap, and durable
  live AddOrder intents

## Financial precision (CRITICAL)

- **Never** use `Double`/`Float` for balances, volumes, prices, or USD amounts.
- Crypto quantities: scale **8**, `RoundingMode.HALF_UP`.
- USD valuations: scale **2**, `RoundingMode.HALF_UP`.
- Tests: `shouldBeEqualComparingTo` or `compareTo() == 0` — never `.equals()` /
  `shouldBeEqualByComparingTo`.
- Defaults: `BigDecimal.ZERO` for nullable stats (e.g. ATH).

Shared scales also live in `:common` `PrecisionConstants` (`SCALE_CRYPTO=8`,
`SCALE_USD=2`, `CASH_RESERVE_FACTOR_DOUBLE=0.99`).

### Analysis vs snapshot percent scales

- Percentage numerators are multiplied by 100 **before** division at
  `SCALE_PERCENT = 4`; dividing the ratio first would collapse precision to
  0.01 percentage points. USD values use `SCALE_USD = 2`.
- `createAssetSnapshot` rounds displayed percents to 2dp — do not reuse snapshot
  percents as trigger inputs.
- Accumulate raw per-asset USD and round the portfolio total once.

---

## Phase overview

1. **Snapshot** — balances × prices → total portfolio value.
2. **Analysis** — ATH/drawdown → effective targets → deviations → orders.
3. **Execution** — sell → settle cash → buy → persist snapshot.

---

## ATH → drawdown → fiat deployment

1. Track portfolio **ATH** in SQLite (`PortfolioStatsRepository`). Update on new highs.
   Missing/null stats are an initial zero state, but database read or legacy
   migration failures propagate and abort analysis before any order planning or
   lower ATH write. A non-cancellation ATH persistence failure is logged as a
   warning and analysis continues with the ATH selected in memory for the
   current cycle. Cancellation still propagates.
2. `Drawdown% = (ATH - Current) / ATH × 100`.
3. `Deploy% = (Drawdown% / fiatMaxDrawdown)^fiatDeploymentExponent`, capped at 100%.
   - Exponent `< 1` = aggressive early deployment; `> 1` = conservative; `1` = linear.
   - `fiatMaxDrawdown = 0` disables deployment.
4. `EffectiveUsdTarget = BaseUsdTarget × (1 - Deploy%)`.
5. Freed allocation redistributed **proportionally to crypto** so totals remain 100%.
   When there is no positive non-USD target, deployment is an applied no-op:
   the configured USD target remains 100% and the cycle reports 0% deployed.

---

## Deviations & triggers

```text
DeviationUSD = CurrentValue - TargetValue
Deviation%   = DeviationUSD / TargetValue × 100   (signed relative)
```

When `TargetValue` is `$0` but `CurrentValue > 0`, `Deviation%` is **100%**
so a zero-target holding can still clear the percent trigger (paired with the
dust gate).

- Negative → underweight → **BUY**.
- Positive → overweight → **SELL**.
- Order generation requires **both**:
  - `|Deviation%| ≥ deviationTriggerPercent`, and
  - `|DeviationUSD| ≥ minimumOrderSizeUSD` (`AssetMetrics.isSignificant`).
- Missing or zero non-USD ticker price aborts the cycle before orders
  (`calculatePortfolioValues` → `Result.Failure`).

### Price lookup (`RebalancerEngine.resolvePriceFromTicker`)

- Try the exact ticker key `Asset.tradingPair(symbol)` first.
- Fallback: iterate `rawPrices` using `Asset.matchesUsdQuotedPair(key, symbol)`
  only — **never** `key.contains(symbol)`.
- Zero/missing non-USD price → `Result.Failure` with
  `ViewText.PRICE_NOT_FOUND_PREFIX` before any orders.
- Preserve this invariant when extracting helpers.

### Fiat correction (USD-only trigger)

When **only** USD passes both gates (`|Deviation%| ≥ deviationTriggerPercent`
and `|DeviationUSD| ≥ minimumOrderSizeUSD`; deposit/withdrawal):

- **Surplus**: buy underweight crypto proportional to USD deficits.
- **Shortage**: sell overweight crypto proportional to USD surpluses.

This concentrates trades on assets furthest from target and clears dust more
effectively than spreading across all pairs.

- In `distributeFiatCorrection`:
  `remaining = deviationAbs.setScale(SCALE_USD, RoundingMode.DOWN)`; each
  `share = min(remaining, computedShare)`.
- Skip symbols whose proportional share rounds to `$0.00`.
- Fiat correction runs only when USD alone triggered **and** crypto order maps
  are empty.

---

## Execution safety (`OrderExecutorImpl`)

1. **Sell first** — only successful sells update projected cash.
   - Each sell volume is capped to the cycle-entry asset balance rounded down
     to crypto scale, so cent-rounded zero-target liquidation intent cannot
     request more units than the account holds.
   - After each successful sell, add the effective submitted notional
     (`result.volume × pinned price`) to `projectedCash` (pre-fee). Never add
     the original intent after holdings capping or volume rounding.
   - Live settle replaces this with fill-confirmed proceeds: sum
     `(usdAmount − fee)` for matching sell `orderTxid`s, capped by balance peek /
     projected cash.
   - Dry-run or no sells: skip settle; buys budget off projected cash only.
2. **USD settle** (`OrderSettleHelper.settleUsdAfterSells`) — only when **≥1 sell succeeded** and **not** dry-run:
   prefer **fill-confirmed** sell proceeds (trade history matched by order
   txid, net of fee); fall back to USD **balance poll** when no txids or fill
   confirm is empty. Both cold polls: up to **3** attempts from **250ms**
   doubling backoff; track best positive; accept early at **≥95%** of
   projected; **abort buys** (fail-closed) if none. Skip settle (use projected
   cash) when dry-run or no sell succeeded. Poll/Flow mechanics:
   [coroutines-flows-sse](../coroutines-flows-sse/SKILL.md).
   Repeated nonblank Kraken trade IDs across shifting pages count once; id-less
   rows remain distinct because equal-economics partial fills can be legitimate.
3. **Buy second** — wrap the sell→buy sequence in `withStableBackend` using `RebalanceSessionContext`; apply a
   **cycle-level 99%** budget of settled USD
   (`PrecisionConstants.CASH_RESERVE_FACTOR` / `CASH_RESERVE_FACTOR_DOUBLE`), then
   cap each buy by remaining budget.
4. **Dust** — skip orders with USD notional `< minimumOrderSizeUSD`.
   - **Pre-flight order guards** (`OrderExecutorImpl.executeSingleOrder` via `RebalanceSessionContext`): after
     the dust check, abort when `usdAmount.signum() <= 0` or the computed
     `volume.signum() <= 0` — return `null`; do not call `executeOrder`.
   - Applies when `minimumOrderSizeUSD = 0`, or when a buy is trimmed to $0 by the
     99% cycle budget.
   - Anti-pattern: relying on Kraken to reject zero volume — the app would still
     persist a `TradeRecord`.
5. **Live submission journal** — when `!dryRun && !simulation`, persist a
   `PENDING` row in `order_intents` with deterministic `cl_ord_id`
   (`cycleId|symbol|side` → UUID) before AddOrder. AddOrder has one attempt. A
   transport/response ambiguity or missing txid becomes `UNCERTAIN`, aborts the
   batch, and blocks future live orders while any unresolved row remains. Such
   rows are excluded from sync reconciliation, duplicate cleanup, and pruning;
   only an operator may resolve them through the operator API after checking
   Kraken open orders, closed orders, and fills. Legacy trade `PENDING`/
   `UNCERTAIN` guards are imported into this journal during schema migration.
   `cl_ord_id` is open-order uniqueness, not full idempotency; `userref` is not
   uniqueness.
   Backend exceptions and cancellation persist `UNCERTAIN` before propagating;
   cancellation uses `NonCancellable` only for that durability update. A
   persistence failure never masks the original placement exception; attach it
   as suppressed diagnostic context. Non-live backend exceptions instead update
   the estimate with the actual failure.
6. Market orders; volumes at crypto scale 8. Convert USD intents with
   `RoundingMode.DOWN` so `volume × price` never exceeds the intended notional;
   sell volumes are additionally capped to cycle-entry holdings.
7. **dryRun**: suppress placement on the active backend — SLF4J uses
   `[DRY RUN]` (live) / `[EMULATOR DRY RUN]` (simulation); dashboard activity
   always uses `[DRY RUN]` (see dry-run-and-simulation skill).

---

## Key settings (`rebalancer-config.json`)

| Setting | Role |
| :--- | :--- |
| `deviationTriggerPercent` | Absolute relative deviation gate |
| `minimumOrderSizeUSD` | Significance gate (`isSignificant`) **and** min order notional |
| `fiatMaxDrawdown` / `fiatDeploymentExponent` | Deployment curve |
| `dryRun` / `simulation` | Distinct safety / emulator flags |
| `loopDelaySeconds` | Cycle sleep |

---

## Domain Engine Refactoring Invariants

- **Comment Preservation**: Retain deep inline why-comments (pair-alias matching, `fiatDeploymentExponent` Double.pow scaling, USD reserve truncation) when extracting or moving calculation methods into helper objects like `RebalancerEngine`.
- **Error Constants**: Missing ticker price abort paths must return `Result.Failure` matching `ViewText.PRICE_NOT_FOUND_PREFIX`.

---

## Checklist

- [ ] BigDecimal only; scales 8/2; matcher `shouldBeEqualComparingTo`
- [ ] ATH/drawdown deployment and crypto redistribution correct
- [ ] Signed deviations retained; trigger uses absolute value **and** dust USD significance
- [ ] Missing/zero non-USD price aborts cycle before orders
- [ ] Fiat correction only when USD alone passes both gates (≥ trigger and ≥ dust)
- [ ] Sell → (if sell succeeded and not dry-run) fill-confirm then poll fallback
      (3× / 250ms doubling backoff, keep best positive, early-accept at **≥95%**
      of projected) → abort buys only when confirmed USD **≤ 0** → cycle 99% buy
      budget → dust skip
- [ ] Real live AddOrder persists `PENDING` first, runs once, and ambiguous
      outcomes become blocking `UNCERTAIN` without automatic reconciliation
- [ ] Changes reflected in `docs/ALGORITHM.md` when behavior changes
- [ ] If ALGORITHM Mermaid changed → run
      [validate_mermaid.py](../documentation-review/scripts/validate_mermaid.py)
