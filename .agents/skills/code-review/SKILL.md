---
name: code-review
description: Perform a thorough, structured code review, PR review, or code audit covering Code Quality, Bug Detection, Security Analysis, Performance, Financial Math Safety, and Project Conventions.
---

# Code Review Skill

Use this skill when analyzing source code, pull requests, or code snippets to provide detailed, actionable, prioritized, and structured feedback aligned with project architecture.

## Review Categories

Analyze the target code across the following 6 core dimensions:

### 1. Code Quality & Cleanliness

- **Single Responsibility Principle (SRP)**: Check for monolithic methods, bloated classes, or mixed orchestrator/brain/brawn responsibilities.
- **Refactoring Opportunities**: Suggest structural improvements, cleaner abstractions, DRY extractions, and improved readability.
- **No Fully Qualified Names (FQNs)**: Ensure explicit `import` statements are used at the top of the file rather than inline FQNs (e.g. `import com.gemini.krakenbot.util.Formatter` instead of `com.gemini.krakenbot.util.Formatter.formatUSD(...)`).
- **Eliminate Magic Strings & Constants**: Verify that UI labels, HTML IDs/attributes, CSS classes, and domain types leverage the shared Kotlin Multiplatform `:common` module (`CssClass`, `HtmlIds`, `HtmlAttrs`, `TimeRange`, `OrderSide`, `OrderType`, `ViewText`).
- **Shared Core (`:common`) Integrity**: Verify that shared code in `common/src/commonMain/` remains 100% pure Kotlin Multiplatform without JVM-only (e.g. `java.math.BigDecimal`, SLF4J) or JS-only DOM imports.
- **Environment Agnosticism & Public Repository Safety**: Ensure no developer-specific absolute filesystem paths (`/Users/...`, `C:\Users\...`), local machine hostnames (`my-macbook`, `charles-pc`), or developer-specific local network hosts exist in source code, configs, or test assertions. All test mocks must use generic, environment-agnostic hostnames (`app-server.local`, `localhost`).

### 2. Bug Detection & Financial Math Safety

- **BigDecimal Financial Precision (CRITICAL)**:
  - **NEVER** allow `Double` or `Float` for currency, balance, volume, or price math.
  - Scale: 8 decimals for crypto balances (`RoundingMode.HALF_UP`), 2 decimals for USD totals.
  - Assertions: Compare `BigDecimal` values using `compareTo() == 0` or Kotest `shouldBeEqualByComparingTo` (NEVER `.equals()`).
  - Defaults: Use non-null `BigDecimal.ZERO` defaults to prevent `NullPointerException`.
- **Order Execution Safety**:
  - Verify sequence: Sell overweight assets *first* to build USD cash reserves, then buy underweight assets *second*.
  - Verify buy allocation cap: Buy orders MUST be capped to **99% of available USD cash**.
  - Liquidity Polling: Verify settled liquidity is checked via Kraken API before executing buy legs.
- **Logic & Control Flow**: Identify edge cases, off-by-one errors, infinite loops, or improper state mutations.
- **Concurrency & Async**: Check for non-atomic state updates, shared mutable state without `Mutex` protection, or missing `runTest` scope in unit tests.

### 3. Kraken API & Ticker Normalization

- **Symbol Mapping**: Validate standard symbol conversions (`BTC` -> `XBTUSD`/`XXBT`, `DOGE` -> `XDGUSD`/`XXDG`).
- **Ticker Normalization**: Standardize display symbols into clean `BASE/USD` format across UI, API, and logs.
- **Rate Limiting & Backoff**: Ensure private endpoints use coroutine `Mutex` rate limiting and handle `EGeneral:Temporary lockout` with exponential backoff (10s to 15min).

### 4. Security Analysis

- **Vulnerabilities**: Inspect for injection risks (SQL, OS command, unsafe HTML rendering/XSS), secret leaks, or insecure deserialization.
- **Input Validation & Sanitize**: Ensure API payloads, query params, and user configs are strictly validated.
- **Auth & Key Handling**: Verify safe credential handling without logging secret API keys or private tokens.

### 5. Performance & Frontend Kotlin/JS Hygiene

- **Bottlenecks & Complexity**: Identify inefficient algorithms, redundant database queries, or expensive loops.
- **Kotlin/JS (`:frontend-js`) Safety**:
  - **Chart.js Integrity**: Deep-clone configuration option objects in Kotlin/JS before passing to Chart.js to prevent global option mutations across re-renders.
  - **DOM Cleanup**: Clear interval timers and event listeners on DOM element detachment to avoid memory leaks.
- **Async & Non-blocking I/O**: Ensure blocking operations are offloaded from main loops or event dispatchers.

### 6. Database Integrity & Testing Best Practices

- **Exposed ORM**: Schema operations executed inside `transaction` blocks, targeting records by primary key ID, with cascade cleanups for child records.
- **Test Isolation**: Backend unit/integration tests **MUST** execute against in-memory SQLite (`jdbc:sqlite::memory:`).
- **Kotest Conventions**: Specs structured with `StringSpec` init blocks, `@Suppress("unused")`, and `FakeKrakenService.kt` doubles.
- **Documentation & Build Exclusions Sync**: Ensure package additions/deletions under `src/main/kotlin` update `README.md` directory trees and `build.gradle.kts` JaCoCo exclusions.

---

## Response Output Format

Structure all code reviews cleanly in GitHub-flavored markdown using the following template:

````markdown
# Code Review Summary

Provide a high-level 2-3 sentence overview of the code quality and primary findings.

## Highlights & Strengths

- Bullet points noting well-implemented patterns or solid design choices.

## Issues & Recommendations

### [CRITICAL / MAJOR / MINOR / SUGGESTION] Issue Title

- **Category**: `[Code Quality / Bug & Financial Math / Kraken API / Security / Performance / Database & Testing]`
- **Location**: `[filename.ext:L12-L34](file:///path/to/filename.ext#L12-L34)`
- **Issue**: Detailed explanation of the issue or anti-pattern.
- **Impact**: Potential consequences if unaddressed.
- **Suggested Fix**:

```kotlin
// Corrected / refactored snippet
```
````

---

## Review Completion Checklist

Before completing a code review, verify:

- [ ] Financial calculations checked for strict `BigDecimal` usage (scale 8/2, `compareTo()`)
- [ ] Order execution sequence and 99% USD liquidity cap verified
- [ ] No Fully Qualified Names (FQNs) or hardcoded absolute user paths (`/Users/...`) found
- [ ] Kraken symbol mapping (`BTC` -> `XBTUSD`) and rate-limiting backoffs checked
- [ ] Kotlin/JS DOM listener cleanup and Chart.js deep-cloning verified
- [ ] JaCoCo build exclusions and `README.md` structure tree verified for package updates
- [ ] Markdown files formatted and linted (`npx markdownlint-cli`)
- [ ] Tests and builds executed (`./gradlew test :frontend-js:jsTest`) to verify zero regressions
