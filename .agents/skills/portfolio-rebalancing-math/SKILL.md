---
name: portfolio-rebalancing-math
description: Portfolio rebalancing engine math — BigDecimal scale rules (8/2), signed relative allocation deviations, cash reserve caps (99% USD liquidity cap), ATH/drawdown tracking, and order sequence safety.
---

# Portfolio Rebalancing Engine Math

Use this skill when modifying portfolio valuation algorithms, target allocation math, rebalance order generation, or liquidity execution sequences (`PortfolioAnalyzerImpl`, `OrderExecutorImpl`).

## Financial Math Precision Rules (CRITICAL)

- **Strict `BigDecimal`**: **NEVER** use `Double` or `Float` for balances, currency amounts, trade volumes, or prices.
- **Scale Requirements**:
  - Cryptocurrency quantities: **8 decimal places** (`setScale(8, RoundingMode.HALF_UP)`).
  - USD valuations & fiat totals: **2 decimal places** (`setScale(2, RoundingMode.HALF_UP)`).
- **Assertions**: Compare `BigDecimal` values using `compareTo() == 0` or Kotest `shouldBeEqualByComparingTo`. NEVER use `.equals()`.
- **Null Safety**: Always use `BigDecimal.ZERO` as non-null default values.

---

## Signed Relative Allocation Deviations

Compute signed relative allocation deviations to convey portfolio drift accurately on dashboard indicators:

$$\text{Relative Deviation} = \frac{\text{Current Allocation \%} - \text{Target Allocation \%}}{\text{Target Allocation \%}}$$

- **Negative ($-$)**: Asset is **underweight** (requires `BUY` order).
- **Positive ($+$)**: Asset is **overweight** (requires `SELL` order).

---

## Order Execution Safety Sequence

When executing a portfolio rebalance loop, `OrderExecutorImpl` must strictly enforce cash safety:

1. **Sell Overweight Assets First**: Execute all sell orders first to accumulate settled USD cash reserves.
2. **Poll USD Liquidity**: Poll Kraken API up to 3 times (250ms interval) to verify settled cash liquidity.
3. **Buy Underweight Assets Second**: Cap buy allocations to **99% of available USD cash** to account for market slippage and exchange fee deductions:

$$\text{Max Buy Cash} = \text{Available USD Cash} \times 0.99$$

---

## Checklist

Before completing portfolio math or order execution code:

- [ ] All math uses `BigDecimal` (scale 8 for crypto, scale 2 for USD)
- [ ] Relative allocation deviations retain sign ($-$/$+$)
- [ ] Rebalance sequence executes sell legs *before* buy legs
- [ ] Buy allocations capped at 99% of settled USD cash
- [ ] Unit tests compare `BigDecimal` with `shouldBeEqualComparingTo` or `compareTo() == 0`
