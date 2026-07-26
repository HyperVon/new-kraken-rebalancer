---
name: portfolio-rebalancing-math
description: >-
  Portfolio rebalancing engine math — BigDecimal scales, ATH/drawdown fiat
  deployment, deviation triggers, fiat correction, dust thresholds, sell-first
  execution, and 99% cash caps. Use when changing PortfolioCalculations,
  PortfolioAnalyzerImpl, RebalancerEngine, OrderExecutorImpl, or docs/ALGORITHM.md.
---

# Portfolio Rebalancing Engine Math

Canonical deep doc: [`docs/ALGORITHM.md`](../../../docs/ALGORITHM.md).

Primary code:

- `RebalancerEngine` — domain calculator (valuation, drawdown, targets, deviations,
  fiat correction); no network/DB
- `PortfolioCalculations` — shared % / target / deviation math
- `PortfolioAnalyzerImpl` — snapshot/ATH I/O; delegates math to `RebalancerEngine`
- `OrderExecutorImpl` — sell-first execution, USD settle, buy cap, `cl_ord_id` on AddOrder

## Financial precision (CRITICAL)

- **Never** use `Double`/`Float` for balances, volumes, prices, or USD amounts.
- Crypto quantities: scale **8**, `RoundingMode.HALF_UP`.
- USD valuations: scale **2**, `RoundingMode.HALF_UP`.
- Tests: `shouldBeEqualComparingTo` or `compareTo() == 0` — never `.equals()` /
  `shouldBeEqualByComparingTo`.
- Defaults: `BigDecimal.ZERO` for nullable stats (e.g. ATH).

Shared scales also live in `:common` `PrecisionConstants` (`SCALE_CRYPTO=8`,
`SCALE_USD=2`, `CASH_RESERVE_FACTOR_DOUBLE=0.99`).

---

## Phase overview

1. **Snapshot** — balances × prices → total portfolio value.
2. **Analysis** — ATH/drawdown → effective targets → deviations → orders.
3. **Execution** — sell → settle cash → buy → persist snapshot.

---

## ATH → drawdown → fiat deployment

1. Track portfolio **ATH** in SQLite (`PortfolioStatsRepository`). Update on new highs.
2. `Drawdown% = (ATH - Current) / ATH × 100`.
3. `Deploy% = (Drawdown% / fiatMaxDrawdown)^fiatDeploymentExponent`, capped at 100%.
   - Exponent `< 1` = aggressive early deployment; `> 1` = conservative; `1` = linear.
   - `fiatMaxDrawdown = 0` disables deployment.
4. `EffectiveUsdTarget = BaseUsdTarget × (1 - Deploy%)`.
5. Freed allocation redistributed **proportionally to crypto** so totals remain 100%.

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
  - `|DeviationUSD| ≥ dustThresholdUSD` (`AssetMetrics.isSignificant`).
- Missing or zero non-USD ticker price aborts the cycle before orders
  (`calculatePortfolioValues` → `Result.Failure`).

### Fiat correction (USD-only trigger)

When **only** USD passes both gates (`|Deviation%| ≥ deviationTriggerPercent`
and `|DeviationUSD| ≥ dustThresholdUSD`; deposit/withdrawal):

- **Surplus**: buy underweight crypto proportional to USD deficits.
- **Shortage**: sell overweight crypto proportional to USD surpluses.

This concentrates trades on assets furthest from target and clears dust more
effectively than spreading across all pairs.

---

## Execution safety (`OrderExecutorImpl`)

1. **Sell first** — only successful sells update projected cash.
2. **USD poll** — only when **≥1 sell succeeded** and **not** dry-run: up to
   **3** attempts with exponential backoff starting at **250ms** (doubling:
   250ms → 500ms → 1000ms); track the **best (maximum) positive** observation and
   accept early when balance ≥ **95%** of projected. If no positive balance is
   observed, **abort buys** (fail-closed). Skip the poll (use projected cash)
   when dry-run or no sell succeeded.
3. **Buy second** — wrap the sell→buy sequence in `withStableBackend`; apply a
   **cycle-level 99%** budget of settled USD
   (`PrecisionConstants.CASH_RESERVE_FACTOR` / `CASH_RESERVE_FACTOR_DOUBLE`), then
   cap each buy by remaining budget.
4. **Dust** — skip orders with USD notional `< dustThresholdUSD`.
5. Market orders; volumes at crypto scale 8. Live AddOrder includes deterministic
   `cl_ord_id` (`cycleId|symbol|side` → UUID) so `retryWithFlow` reuses the same
   client id while the order is still open (Kraken does not treat `userref` as
   idempotent).
6. **dryRun**: suppress placement on the active backend — SLF4J uses
   `[DRY RUN]` (live) / `[EMULATOR DRY RUN]` (simulation); dashboard activity
   always uses `[DRY RUN]` (see dry-run-and-simulation skill).

---

## Key settings (`rebalancer-config.json`)

| Setting | Role |
| :--- | :--- |
| `deviationTriggerPercent` | Absolute relative deviation gate |
| `dustThresholdUSD` | Significance gate (`isSignificant`) **and** min order notional |
| `fiatMaxDrawdown` / `fiatDeploymentExponent` | Deployment curve |
| `dryRun` / `simulation` | Distinct safety / emulator flags |
| `loopDelaySeconds` | Cycle sleep |

---

## Checklist

- [ ] BigDecimal only; scales 8/2; matcher `shouldBeEqualComparingTo`
- [ ] ATH/drawdown deployment and crypto redistribution correct
- [ ] Signed deviations retained; trigger uses absolute value **and** dust USD significance
- [ ] Missing/zero non-USD price aborts cycle before orders
- [ ] Fiat correction only when USD alone passes both gates (≥ trigger and ≥ dust)
- [ ] Sell → (if sell succeeded and not dry-run) 3× poll (250ms exponential backoff) → best observed / 95% settle → fail-closed abort → cycle 99% buy budget → dust skip
- [ ] Changes reflected in `docs/ALGORITHM.md` when behavior changes
- [ ] If ALGORITHM Mermaid changed → run
      [validate_mermaid.py](../documentation-review/scripts/validate_mermaid.py)
