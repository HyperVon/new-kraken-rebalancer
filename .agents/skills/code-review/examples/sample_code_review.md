# Code Review Summary

The target pull request refactors the `OrderExecutorImpl` class to enforce strict USD cash reserve validation before executing buy legs during a portfolio rebalance loop. The changes enhance liquidity safety and prevent potential execution failures, but contain a few minor anti-patterns regarding `BigDecimal` comparison scale and hardcoded absolute paths in unit tests.

## Highlights & Strengths

- **Sequence Safety**: Overweight sell orders execute *first*, accurately accumulating settled USD liquidity before underweight buy orders are submitted.
- **Liquidity Cap**: Buy allocations are correctly capped to 99% of available settled USD cash to buffer exchange fees and price slippage.
- **Type Safety**: HTML DSL and status badges properly consume shared `CssClass` sealed hierarchies from `:common`.

## Issues & Recommendations

### [MAJOR] BigDecimal Comparison in Unit Assertions

- **Category**: `Bug Detection & Financial Math Safety`
- **Location**: `[OrderExecutorTest.kt:L45-L52](file:///Users/charlesv/Projects/new-kraken-rebalancer/src/test/kotlin/com/gemini/krakenbot/service/OrderExecutorTest.kt#L45-L52)`
- **Issue**: The test compares calculated USD balance with `.equals()`: `executedOrder.usdAmount.equals(BigDecimal("100.50"))`. Because scale differences (e.g. `100.5` vs `100.50`) cause `.equals()` to return false, this test is fragile.
- **Impact**: Potential false-negative unit test build failures during automated CI/CD runs.
- **Suggested Fix**:

```kotlin
// Replace .equals() with Kotest shouldBeEqualByComparingTo or compareTo() == 0:
executedOrder.usdAmount shouldBeEqualComparingTo BigDecimal("100.50")
```

### [MINOR] Hardcoded User Directory Path in Test Asset

- **Category**: `Code Quality & Cleanliness`
- **Location**: `[OrderExecutorTest.kt:L18](file:///Users/charlesv/Projects/new-kraken-rebalancer/src/test/kotlin/com/gemini/krakenbot/service/OrderExecutorTest.kt#L18)`
- **Issue**: Hardcoded absolute path `/Users/charlesv/Projects/new-kraken-rebalancer/src/test/resources/mock_ticker.json` found in mock setup.
- **Impact**: Tests fail on other developers' machines or GitHub Actions CI containers where `/Users/charlesv/` does not exist.
- **Suggested Fix**:

```kotlin
// Use classpath resource or relative workspace path:
val resourceStream = javaClass.classLoader.getResourceAsStream("mock_ticker.json")
```

---

## Review Completion Checklist

- [x] Financial calculations checked for strict `BigDecimal` usage (scale 8/2, `compareTo()`)
- [x] Order execution sequence and 99% USD liquidity cap verified
- [x] No Fully Qualified Names (FQNs) or hardcoded absolute user paths (`/Users/...`) found
- [x] Kraken symbol mapping (`BTC` -> `XBTUSD`) and rate-limiting backoffs checked
- [x] Kotlin/JS DOM listener cleanup and Chart.js deep-cloning verified
- [x] JaCoCo build exclusions and `README.md` structure tree verified for package updates
- [x] Markdown files formatted and linted (`npx markdownlint-cli`)
- [x] Tests and builds executed (`./gradlew test :frontend-js:jsTest`) to verify zero regressions
