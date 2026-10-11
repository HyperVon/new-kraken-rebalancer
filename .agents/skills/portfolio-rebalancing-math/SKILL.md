---
name: portfolio-rebalancing-math
description: >-
  Current portfolio rebalancing math — fixed configured USD target, deviation
  triggers, fiat correction, precision, safe sell/settle/buy execution, and live
  order intents. Use when changing PortfolioCalculations, PortfolioAnalyzerImpl,
  RebalancerEngine, OrderExecutorImpl, or docs/ALGORITHM.md.
---

# Portfolio Rebalancing Math

Canonical algorithm contract: [`docs/ALGORITHM.md`](../../../docs/ALGORITHM.md).

Primary code:

- `RebalancerEngine` — current valuation, fixed targets, deviations, and fiat
  correction; domain math has no network or database access.
- `PortfolioCalculations` — shared target, percentage, and deviation math.
- `PortfolioAnalyzerImpl` — current balance/price inputs and cycle snapshot.
- `OrderExecutorImpl` — sell-first execution, USD settlement, cash reserve,
  and durable live AddOrder intents.

## Financial precision

- Never use `Double` / `Float` for balances, volumes, prices, or USD amounts.
- Crypto quantities use scale 8; USD values use scale 2, both
  `RoundingMode.HALF_UP` unless an order volume or spend cap requires rounding
  down.
- Analysis deviations use 4 decimal places; display snapshot percentages use
  2 decimal places. Do not use display values as trigger inputs.
- Sum raw asset USD values and round the portfolio total once. Per-asset values
  used for order sizing are rounded to cents.
- Compare `BigDecimal` in tests with `shouldBeEqualComparingTo` or
  `compareTo() == 0`, never `.equals()` / `shouldBeEqualByComparingTo`.

Shared scales live in `:common` `PrecisionConstants` (`SCALE_CRYPTO=8`,
`SCALE_USD=2`, `SCALE_PERCENT=4`, and the 0.99 cash reserve).

## Current cycle math

For each cycle, use current account balances and direct USD ticker prices for
configured assets. USD is valued at one dollar. A missing or zero non-USD mark
fails valuation before order generation.

For configured weights totaling 100%:

```text
usdTarget       = sum(configured USD weights)
cryptoScale     = (100% - usdTarget) / sum(configured non-USD weights)
targetWeight[i] = configured weight[i] × cryptoScale       (non-USD assets)
targetWeight[USD] = usdTarget
rawValue[i]     = balance[i] × priceUSD[i]
currentValue[i] = round_to_cents(rawValue[i])
portfolioValue  = round_to_cents(sum(rawValue[i]))
targetValue[i]  = round_to_cents(portfolioValue × targetWeight[i] / 100)
deviationUSD[i] = currentValue[i] - targetValue[i]
deviationPct[i] = deviationUSD[i] / targetValue[i] × 100
```

The configured USD weight remains fixed each cycle. Remaining allocation is
split among configured non-USD assets in their existing relative proportions.
No ATH, drawdown, historical capital flow, OHLC high, retrospective report, or
Buy & Hold value changes the plan.

A zero target with a positive holding uses a 100% relative deviation so it can
still clear the percent gate and be liquidated; zero holding at zero target has
zero deviation.

An asset triggers only when both conditions hold:

- `abs(deviationPct) >= deviationTriggerPercent`
- `abs(deviationUSD) >= minimumOrderSizeUSD`

Positive deviation means overweight / sell; negative means underweight / buy.
Missing or zero non-USD ticker prices fail closed before any order is planned.
Keep exact symbol/pair matching rules in `RebalancerEngine`; never accept a
price by loose substring matching.

### USD-only correction

USD is not an order row. If USD is the only triggered asset:

- Cash surplus is distributed among underweight non-USD assets in proportion
  to their USD deficits.
- Cash shortfall is funded by selling overweight non-USD assets in proportion
  to their USD surpluses.
- Skip corrections when there are no counter-balancing positions.

If crypto assets already triggered, their plan is used without a second USD
correction. Recheck minimum order size and positive volume after allocation and
rounding. Sell volume cannot exceed the cycle-entry holding.

## Execution safety

1. **Sells first.** Only successful sells contribute projected proceeds; use
   submitted notional after volume/holding caps, not the original intent.
2. **Settle available USD.** After at least one successful sell in a non-dry-run
   cycle, prefer Kraken fills matched to the sell order transaction IDs. Cap
   proceeds to spendable USD (or projected cash if the peek fails). If fill
   evidence is missing or materially short, use bounded USD balance polling.
   Stop buys when no positive spendable USD is confirmed. Fill history here is
   order-specific settlement evidence, not retrospective reporting.
3. **Buy second.** Limit the cycle's buys to 99% of post-sell USD, rounded down
   to cents, and reduce the remaining budget after each successful buy.
4. **Recheck dust and volume.** Enforce the minimum notional after cash-budget
   adjustment. Do not submit a zero or negative amount or asset volume.
5. **Live order journal.** Only when simulation and dry run are both off, persist
   a `PENDING` intent with deterministic Kraken `cl_ord_id` before AddOrder.
   Attempt AddOrder once. Ambiguous outcomes become `UNCERTAIN`, stop the batch,
   and block later live submissions until an operator verifies Kraken and
   resolves the intent with evidence. Do not retry or auto-resolve ambiguity.
6. **Keep modes distinct.** Simulation selects the offline emulator; dry run
   suppresses placement on the selected backend. A live order is possible only
   when both are off.

## Current portfolio surfaces and persistence

`CurrentPortfolioState` is an in-memory `StateFlow` containing only the latest
completed cycle snapshot. Dashboard and SSE expose that current snapshot and its
cycle actions; they do not retain a retrospective timeline. `/history`
permanently redirects to `/actual`, and former `/api/history/*` endpoints
return `410 Gone`.

Actual observation capture is separate from order execution. It stores direct
balance/ticker evidence asynchronously, preserves incomplete samples as gaps,
and does not substitute historical prices. Simulation does not create
authenticated Actual data. An optional prospective Buy & Hold comparison starts
only from an eligible complete observation after continuity verification and
uses forward observations from that baseline.

The legacy `kraken-rebalancer.db` file remains available for safe execution
journal migration and account-history continuity during binding; it is not an
active reporting destination. Retired sync, reconstruction, and report writes
remain stopped, and existing files/tables are left untouched. Execution intents
and Actual observations use their separate active stores.

## Active settings and config compatibility

| Setting | Role |
| :--- | :--- |
| `allocations` | Configured asset symbols, weights, and colors; weights include USD and sum to 100%. |
| `deviationTriggerPercent` | Relative deviation gate. |
| `minimumOrderSizeUSD` | Dollar significance gate and execution minimum. |
| `loopDelaySeconds` | Delay between cycles. |
| `dryRun` | Suppresses order placement on the selected backend. |
| `simulation` | Selects the offline emulator. |
| `qualityScores` | Optional allocation preview inputs; do not affect orders until suggested targets are applied and saved. |

Older JSON may contain retired keys such as `fiatMaxDrawdown`,
`fiatDeploymentExponent`, `fiatDeploymentThresholdPercent`, `inceptionDate`, and
`comparisonStartDate`. The parser discards them on load; the next Settings save
writes active settings without those keys. Schema migration and account-history
continuity compatibility must remain intact.

## Test ownership

Use focused current-contract tests listed in
[`docs/EVALUATION.md`](../../../docs/EVALUATION.md), including the engine math,
portfolio manager, order-settlement, execution-journal, Dashboard cutover,
Actual observation, prospective benchmark continuity, and Settings/config tests.
Do not add retrospective ATH/reconstruction/comparison scenarios.

## Checklist

- [ ] Fixed configured USD target and relative crypto scaling preserved
- [ ] Both deviation-percent and minimum-USD gates apply
- [ ] USD correction runs only when USD alone triggers
- [ ] Sells precede settled-cash-limited buys; 99% reserve remains
- [ ] No zero/negative amount or volume reaches an exchange backend
- [ ] Ambiguous live AddOrder stays unresolved and blocks later live orders
- [ ] Dashboard state stays latest-cycle-only; Actual evidence stays raw and forward-only
- [ ] Prospective Buy & Hold requires eligible baseline and verified continuity
- [ ] Retired config fields are dropped while schema/migration compatibility remains
- [ ] Update `docs/ALGORITHM.md` and focused tests when contracts change
