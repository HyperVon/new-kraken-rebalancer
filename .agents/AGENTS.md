# Agent Rules & Technical Guidelines — Kraken Rebalancer

Welcome, Antigravity/AI Agent. This repository contains **Kraken Rebalancer**, an autonomous, production-grade cryptocurrency portfolio rebalancing engine.

Below are the architectural rules, coding constraints, financial math guidelines, UI/UX conventions, git/CLI directives, and quality gates you must strictly adhere to when modifying this codebase.

---

## 1. Technology Stack & Architecture

### Stack Specification

- **Language**: Kotlin 2.4.0 (100% Kotlin Multiplatform: JVM + JS)
- **Backend**: Ktor 3.5.0 (Netty engine, Jackson `ContentNegotiation`), Koin 4.2.1 (DI)
- **Database**: SQLite (`kraken-rebalancer.db`) via JetBrains Exposed ORM 0.61.0
- **Concurrency**: Kotlin Coroutines (`kotlinx.coroutines` 1.11.0) & Kotlin `Flow` / `SharedFlow`
- **Shared Core (`:common`)**: Kotlin Multiplatform shared module (`common/src/commonMain/`) housing `CssClass` sealed class hierarchies, `HtmlIds`, `HtmlAttrs`, `ViewText`, `TimeRange`, `OrderSide`, `OrderType`, and `PrecisionConstants` shared identically by backend and frontend.
- **Frontend**: Server-side HTML (`kotlinx.html` DSL) + `kotlinx-css` DSL (`CssStyles.kt` with shared `CssClass` sealed hierarchy) + HTMX + Ktor SSE + Client-side Kotlin/JS (`:frontend-js` subproject compiling to JS via Kotlin JS IR backend served as `/static/rebalancer.js`)
- **Testing**: Kotest 6.1 (`StringSpec`), MockK 1.14, Ktor MockEngine, Karma/Istanbul
- **Build**: Gradle (Kotlin DSL)

### Architecture Decomposition (SRP)

The engine adheres strictly to the **Single Responsibility Principle**:

1. **`KrakenRebalancerApplication.kt`**: Application entry point, Ktor setup, Koin DI initialization, coroutine lifecycle management, and JVM shutdown hooks.
2. **`PortfolioManagerImpl` (Orchestrator)**: Manages continuous coroutine rebalancing loops (`startRebalancingLoop`, `stopRebalancingLoop`, `runLoop`) and listens reactively to `ConfigService.watchConfigChanges()`.
3. **`PortfolioAnalyzerImpl` (Brain)**: Fetches prices and balances, calculates portfolio values, tracks All-Time High (ATH) and drawdown levels, computes signed relative allocation deviations, and generates target `BUY`/`SELL` orders.
4. **`OrderExecutorImpl` (Brawn)**: Safely executes order sequences with strict cash protection:
   - **Sell Overweight First**: Executes sell orders to build USD cash reserves.
   - **USD Balance Verification**: Polls Kraken API up to 3 times (250ms interval) to verify settled liquidity.
   - **Buy Underweight Second**: Caps buy allocations to **99% of available USD cash** to account for market slippage and exchange fees.
5. **Persistence Repositories** (`SqliteTradeRepositoryImpl`, `SqlitePortfolioStatsRepositoryImpl`): Handles database persistence, trades, metrics, and cascade operations.
6. **`TradeHistoryServiceImpl`**: Maintains live snapshot streaming over Ktor SSE (`/api/status/stream`).

---

## 2. Code Quality & Codebase Cleanliness

### No Fully Qualified Names (FQNs)

- **NEVER** use Fully Qualified Names (FQNs) in standard code, services, controllers, HTML DSL components, or test files unless resolving an unavoidable class name collision.
- Always use explicit `import` statements at the top of the file (e.g. `import com.gemini.krakenbot.util.Formatter` instead of `com.gemini.krakenbot.util.Formatter.formatUSD(...)`).

### Markdown Lint & Code Formatting

- **ALWAYS** address and resolve all markdown lint errors across `.md` files (`README.md`, `CHANGELOG.md`, `AGENTS.md`, `docs/*.md`).
  - Maintain clean heading hierarchies (`#`, `##`, `###`).
  - Quote mermaid diagram labels containing special characters.
  - Surround lists with blank lines and ensure standard indentations.
  - Ensure markdown table columns use consistent pipe alignments.
  - Avoid trailing whitespace at the ends of lines.
- Address all Kotlin compiler warnings (remove unused imports, redundant casts, unnecessary escape characters, and redundant `inline` modifiers).

---

## 3. Financial Math & Precision Guidelines (CRITICAL)

### BigDecimal Precision Rules

- **NEVER** use `Double` or `Float` for currency amounts, asset balances, trade volumes, or prices.
- Always use `BigDecimal` with:
  - **8 decimal places** (`setScale(8, RoundingMode.HALF_UP)`) for cryptocurrency quantities and balances.
  - **2 decimal places** (`setScale(2, RoundingMode.HALF_UP)`) for USD valuations and fiat totals.
- **Test Comparisons**: In unit tests, ALWAYS compare `BigDecimal` values using `compareTo() == 0` or Kotest `shouldBeEqualByComparingTo`. NEVER use `.equals()`, as scale differences (e.g. `1.0` vs `1.00`) cause false test failures.
- **Null Safety Defaults**: Always use `BigDecimal.ZERO` as non-null defaults for stats (e.g., `allTimeHigh`) to prevent NullPointerExceptions.
- **Signed Relative Deviations**: Keep signed relative deviations (`-` for underweight, `+` for overweight) rather than absolute values so dashboard indicators accurately convey portfolio state.

---

## 4. Database Integrity & Persistence

- **JetBrains Exposed ORM**: Execute schema operations within `transaction` blocks.
- **Primary Key Targeting**: Always target SQLite records by primary key ID for updates and deletions.
- **Cascading Cleanups**: Maintain referential integrity by explicitly removing associated child snapshot and action log records when trades or stats are deleted.
- **Atomic Operations**: Prefer Exposed `upsert` and atomic updates over non-atomic fetch-then-write patterns.
- **Test Isolation**: All backend tests **MUST** execute against an in-memory SQLite database (`jdbc:sqlite::memory:`) to ensure production databases (`kraken-rebalancer.db`) and local JSON files are never modified during test runs.

---

## 5. Kraken API & Ticker Normalization

- **Symbol Mapping Conventions**:
  - `BTC` $\rightarrow$ `XBTUSD` (ticker) / `XXBT` or `XBT` (balance)
  - `DOGE` $\rightarrow$ `XDGUSD` (ticker) / `XXDG` or `DOGE` (balance)
- **Ticker Normalization**: Standardize all display symbols into clean `BASE/USD` format across UI components, logs, and API payloads.
- **Rate Limiting**: Protect private Kraken API endpoints using `RateLimiter` backed by coroutine `Mutex` locks. Handle `EGeneral:Temporary lockout` with exponential backoff (starting at 10 seconds, scaling up to 15 minutes on repeated lockouts).

---

## 6. Frontend UI/UX & Kotlin/JS Conventions

- **Server-Side HTML & CSS**: Render markup with `kotlinx.html` DSL and style with `kotlinx-css` DSL (`CssStyles.kt`). Use modular layout helpers in `Layouts.kt` (`statusCard`, `glassPanel`).
- **Visual Design**: Maintain sleek, modern dark mode aesthetics with curated HSL accent colors, clean typography (Inter font), dynamic badges, and responsive tables.
- **Kotlin/JS Subproject (`:frontend-js`)**: All client-side JavaScript is written in type-safe Kotlin in `:frontend-js`.
  - **Chart.js Integrity**: Deep-clone configuration option objects in Kotlin/JS before passing them to Chart.js to prevent global option mutations across re-renders.
  - **DOM Cleanup**: Clear interval timers and event listeners on DOM element detachment to avoid memory leaks.
- **Time Frame Selector Functionality**: When a user selects a time range (24h, 7d, 30d, 90d, All) on the History page, **all 4 top summary metric cards** (**High Value**, **Total Trades**, **Total Volume Traded**, **Total Fees Paid**) MUST update dynamically alongside the charts and trade log table.

---

## 7. Git Workflow & GitHub CLI (`gh`) Directives

- **Token & Push Resolution via GitHub CLI (`gh`)**:
  - If you encounter git credential prompts, personal access token (PAT) expiration, or push authentication errors, use the GitHub CLI (`gh auth status`, `gh auth login`, `gh git-credential`) or `gh pr create` / `gh release` commands to resolve authentication seamlessly.
  - **Do NOT ask the user to authenticate manually.** The `gh` CLI is installed and available; use it proactively.
- **No Hardcoded Absolute Paths**: Never hardcode absolute filesystem paths (`/tmp/...`, `/Users/...`). Use relative paths, workspace-relative temp paths, or environment variables. This is a **public GitHub repository** — committed code must never contain user-specific paths like `/Users/charlesv/`.

---

## 8. Testing & Quality Gates

- **Coverage Standard**: Maintain 95%+ (aiming for 100%) line, method, and branch coverage on backend JVM code and 75%+ coverage on Kotlin/JS.
- **Kotest Spec Structure**: Define Kotest specs (`StringSpec`) using standard class body `init { ... }` blocks with the `@Suppress("unused")` annotation:

  ```kotlin
  @Suppress("unused")
  class PortfolioServiceTest : StringSpec() {
      init {
          "should calculate target allocations correctly" {
              // Test implementation
          }
      }
  }
  ```

- **Test Doubles**: Use `FakeKrakenService.kt` for integration tests rather than complex `coEvery` mocks.
- **Async Coroutines**: Wrap suspend calls in `runTest` to execute in virtual time without wall-clock delays.
- **Mandatory Runtime Verification**: NEVER declare a task, bug fix, or feature complete without running automated build and test commands:
  - Backend: `./gradlew test`
  - Frontend: `./gradlew :frontend-js:jsTest`
- **Documentation Maintenance**: Update `README.md`, `CHANGELOG.md`, and relevant markdown documentation whenever adding features, modifying public APIs, or refactoring architecture.
