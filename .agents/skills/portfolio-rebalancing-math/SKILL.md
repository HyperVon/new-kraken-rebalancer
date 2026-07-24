---
name: portfolio-rebalancing-math
description: >-
  Portfolio rebalancing engine math — BigDecimal scales, ATH/drawdown fiat
  deployment, deviation triggers, fiat correction, dust thresholds, sell-first
  execution, and 99% cash caps. Use when changing PortfolioCalculations,
  PortfolioAnalyzerImpl, OrderExecutorImpl, or docs/ALGORITHM.md.
---

# Portfolio Rebalancing Engine Math

Canonical deep doc: [`docs/ALGORITHM.md`](../../../docs/ALGORITHM.md).

Primary code:

- `PortfolioCalculations` — shared % / target / deviation math
- `PortfolioAnalyzerImpl` — snapshot, ATH, drawdown, order generation
- `OrderExecutorImpl` — sell-first execution, USD poll, buy cap

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

- Negative → underweight → **BUY**.
- Positive → overweight → **SELL**.
- Rebalance only if `|Deviation%| ≥ deviationTriggerPercent`.

### Fiat correction (USD-only trigger)

When **only** USD exceeds the trigger (deposit/withdrawal):

- **Surplus**: buy underweight crypto proportional to USD deficits.
- **Shortage**: sell overweight crypto proportional to USD surpluses.

This concentrates trades on assets furthest from target and clears dust more
effectively than spreading across all pairs.

---

## Execution safety (`OrderExecutorImpl`)

1. **Sell first** — only successful sells update projected cash.
2. **USD poll** (non–dry-run): up to **3** attempts, **250ms** apart; accept when
   balance ≥ **95%** of projected, else best observed.
3. **Buy second** — verify cash; if short, scale buys to **99%** of available USD
   (`PrecisionConstants.CASH_RESERVE_FACTOR_DOUBLE`).
4. **Dust** — skip orders with USD notional `< dustThresholdUSD`.
5. Market orders; volumes at crypto scale 8.
6. **dryRun**: log `[DRY RUN]` intents; do not place (see dry-run-and-simulation skill).

---

## Key settings (`rebalancer-config.json`)

| Setting | Role |
| :--- | :--- |
| `deviationTriggerPercent` | Absolute relative deviation gate |
| `dustThresholdUSD` | Min order notional |
| `fiatMaxDrawdown` / `fiatDeploymentExponent` | Deployment curve |
| `dryRun` / `simulation` | Distinct safety / emulator flags |
| `loopDelaySeconds` | Cycle sleep |

---

## Checklist

- [ ] BigDecimal only; scales 8/2; matcher `shouldBeEqualComparingTo`
- [ ] ATH/drawdown deployment and crypto redistribution correct
- [ ] Signed deviations retained; trigger uses absolute value
- [ ] Fiat correction only when USD alone triggers
- [ ] Sell → 3×250ms poll → 95% settle → 99% buy cap → dust skip
- [ ] Changes reflected in `docs/ALGORITHM.md` when behavior changes
